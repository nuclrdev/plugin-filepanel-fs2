/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.fs.history;

import static dev.nuclr.plugin.core.panel.fs.Messages.tr;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.formdev.flatlaf.extras.components.FlatButton;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/**
 * The Alt+F12 Folders history list: every folder the panels have opened in the last year,
 * oldest first, under a heading for each day.
 *
 * <p>Keyboard-first, like the F2 list: the cursor starts on the most recent folder at the
 * bottom, Enter goes there, Delete forgets one folder, and typing narrows the list to the
 * folders whose path contains what was typed — the same inline-search habit the panel itself
 * teaches, so no field has to be focused first.
 *
 * <p>Day headings are lines of the list rather than decoration around it, which keeps the
 * whole thing one scrollable component; they are never selectable, so the arrows step from
 * folder to folder and jump the heading between two days.
 *
 * <p>Folders that no longer exist are still listed — a history that hid them would be lying
 * about where the user has been — but are drawn greyed, from a check that runs off the EDT so
 * a disconnected network drive cannot stall the list opening.
 */
@Slf4j
public class FolderHistoryDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	/** Matches the day headings in the panel's own date column. */
	private static final DateTimeFormatter DayFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private static final String GoAction = "folderhistory.go";
	private static final String RemoveAction = "folderhistory.remove";
	private static final String CloseAction = "folderhistory.close";
	private static final String UpAction = "folderhistory.up";
	private static final String DownAction = "folderhistory.down";
	private static final String PageUpAction = "folderhistory.pageUp";
	private static final String PageDownAction = "folderhistory.pageDown";
	private static final String HomeAction = "folderhistory.home";
	private static final String EndAction = "folderhistory.end";
	private static final String BackspaceAction = "folderhistory.backspace";

	private final transient FolderHistory history;
	private final transient NuclrPluginContext context;

	/** Called with the chosen folder once the dialog has closed. */
	private final transient Consumer<String> onGo;

	private final DefaultListModel<FolderHistoryRows.Row> model = new DefaultListModel<>();
	private final JList<FolderHistoryRows.Row> list = new JList<>(model);
	private final JLabel status = new JLabel();

	private final FlatButton goButton = new FlatButton();
	private final FlatButton removeButton = new FlatButton();

	/** What has been typed into the inline filter, or empty when everything is shown. */
	private String filter = "";

	/** Folders found not to exist any more, filled in off the EDT and read by the renderer. */
	private final Set<String> missing = Collections.synchronizedSet(new HashSet<>());

	/**
	 * Build the dialog (must be called on the EDT).
	 *
	 * @param owner   window to anchor to
	 * @param history the shared history; read now and updated in place as entries are removed
	 * @param onGo    called with the chosen folder once the dialog has closed
	 * @param context plugin context for sounds; may be {@code null}
	 */
	public FolderHistoryDialog(Window owner, FolderHistory history, Consumer<String> onGo,
			NuclrPluginContext context) {

		super(owner, tr("history.dialog.title"), ModalityType.APPLICATION_MODAL);

		this.history = history;
		this.onGo = onGo;
		this.context = context;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setContentPane(buildContent());

		installActions();
		installKeyBindings();
		reload(null);
		checkExistenceInBackground();

		setPreferredSize(new Dimension(900, 560));
		pack();
		setMinimumSize(new Dimension(520, 300));
		setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(list::requestFocusInWindow);
	}

	private JPanel buildContent() {

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new RowRenderer());
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
					goToSelected();
				}
			}
		});
		// A click (or anything else that is not one of the bindings below) can still land on a
		// heading; step off it rather than leaving the cursor on a line Enter cannot open.
		list.addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				snapOffHeading();
				updateButtonState();
			}
		});
		list.addKeyListener(new KeyAdapter() {
			@Override
			public void keyTyped(KeyEvent e) {
				typeIntoFilter(e);
			}
		});

		status.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

		goButton.setText(tr("history.button.go"));
		goButton.addActionListener(e -> goToSelected());
		removeButton.setText(tr("history.button.remove"));
		removeButton.addActionListener(e -> removeSelected());
		FlatButton clearButton = new FlatButton();
		clearButton.setText(tr("history.button.clear_all"));
		clearButton.addActionListener(e -> clearAll());
		FlatButton closeButton = new FlatButton();
		closeButton.setText(tr("history.button.close"));
		closeButton.addActionListener(e -> close());

		// Keyboard-first, as in the F2 list: keeping the buttons out of the focus cycle means
		// Enter and Delete always act on the list rather than on a focused button.
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		for (FlatButton button : List.of(goButton, removeButton, clearButton, closeButton)) {
			button.setFocusable(false);
			buttons.add(button);
		}

		JPanel footer = new JPanel(new BorderLayout(12, 0));
		footer.add(status, BorderLayout.WEST);
		footer.add(buttons, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(new JScrollPane(list), BorderLayout.CENTER);
		content.add(footer, BorderLayout.SOUTH);
		return content;
	}

	private void installActions() {
		list.getActionMap().put(GoAction, action(this::goToSelected));
		list.getActionMap().put(RemoveAction, action(this::removeSelected));
		list.getActionMap().put(CloseAction, action(this::close));
		list.getActionMap().put(UpAction, action(() -> move(-1)));
		list.getActionMap().put(DownAction, action(() -> move(1)));
		list.getActionMap().put(PageUpAction, action(() -> move(-pageStep())));
		list.getActionMap().put(PageDownAction, action(() -> move(pageStep())));
		list.getActionMap().put(HomeAction, action(() -> selectEdge(true)));
		list.getActionMap().put(EndAction, action(() -> selectEdge(false)));
		list.getActionMap().put(BackspaceAction, action(this::backspaceFilter));
		getRootPane().getActionMap().put(CloseAction, action(this::close));
	}

	/**
	 * Bind the list's keys on the list itself, replacing the arrow, paging and Home/End
	 * defaults: those step onto day headings, and a heading is not somewhere the cursor may
	 * rest.
	 */
	private void installKeyBindings() {

		InputMap focused = list.getInputMap(JComponent.WHEN_FOCUSED);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), GoAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), RemoveAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CloseAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), UpAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), DownAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), PageUpAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), PageDownAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), HomeAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), EndAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), BackspaceAction);

		// Escape also at window level, so it still closes if focus ever sits elsewhere.
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CloseAction);
	}

	/**
	 * Rebuild the lines from the history and the current filter.
	 *
	 * @param folderToSelect the folder to put the cursor back on; {@code null}, or a folder the
	 *                       filter now hides, falls back to the most recent folder shown
	 */
	private void reload(String folderToSelect) {

		List<FolderHistoryRows.Row> rows =
				FolderHistoryRows.build(history.entries(), ZoneId.systemDefault(), filter);

		model.clear();
		for (FolderHistoryRows.Row row : rows) {
			model.addElement(row);
		}

		int index = folderToSelect == null ? -1 : indexOfFolder(folderToSelect);
		if (index < 0) {
			index = FolderHistoryRows.lastVisitIndex(rows);
		}
		if (index >= 0) {
			list.setSelectedIndex(index);
			list.ensureIndexIsVisible(index);
		}

		updateStatus(rows);
		updateButtonState();
	}

	private void updateStatus(List<FolderHistoryRows.Row> rows) {
		int folders = 0;
		for (FolderHistoryRows.Row row : rows) {
			if (row instanceof FolderHistoryRows.Visit) {
				folders++;
			}
		}
		String counted = tr(folders == 1 ? "history.status.folder" : "history.status.folders", String.valueOf(folders));
		status.setText(filter.isEmpty() ? counted : tr("history.status.matching", counted, filter));
	}

	private void updateButtonState() {
		boolean hasSelection = selectedEntry() != null;
		goButton.setEnabled(hasSelection);
		removeButton.setEnabled(hasSelection);
	}

	private int indexOfFolder(String path) {
		for (int index = 0; index < model.size(); index++) {
			if (model.get(index) instanceof FolderHistoryRows.Visit visit
					&& visit.entry().path().equals(path)) {
				return index;
			}
		}
		return -1;
	}

	private FolderHistoryEntry selectedEntry() {
		int index = list.getSelectedIndex();
		if (index < 0 || index >= model.size()) {
			return null;
		}
		return model.get(index) instanceof FolderHistoryRows.Visit visit ? visit.entry() : null;
	}

	/** Move the cursor by {@code delta} lines, landing on the nearest folder in that direction. */
	private void move(int delta) {

		if (model.isEmpty()) {
			return;
		}
		int from = list.getSelectedIndex();
		int target = from < 0 ? (delta > 0 ? 0 : model.size() - 1) : from + delta;
		target = Math.max(0, Math.min(model.size() - 1, target));

		int found = nearestVisit(target, delta >= 0 ? 1 : -1);
		if (found < 0) {
			// Nothing that way (the last line is a folder, so this only happens at the ends):
			// look back the other way rather than losing the cursor.
			found = nearestVisit(target, delta >= 0 ? -1 : 1);
		}
		if (found >= 0) {
			list.setSelectedIndex(found);
			list.ensureIndexIsVisible(found);
		}
	}

	private void selectEdge(boolean first) {
		if (model.isEmpty()) {
			return;
		}
		int index = first ? nearestVisit(0, 1) : nearestVisit(model.size() - 1, -1);
		if (index >= 0) {
			list.setSelectedIndex(index);
			list.ensureIndexIsVisible(index);
		}
	}

	/** The first folder line at or after {@code from}, stepping by {@code step} (+1 or -1). */
	private int nearestVisit(int from, int step) {
		for (int index = from; index >= 0 && index < model.size(); index += step) {
			if (model.get(index) instanceof FolderHistoryRows.Visit) {
				return index;
			}
		}
		return -1;
	}

	/** Step off a day heading the mouse (or the list itself) may have landed the cursor on. */
	private void snapOffHeading() {
		int index = list.getSelectedIndex();
		if (index < 0 || index >= model.size() || model.get(index) instanceof FolderHistoryRows.Visit) {
			return;
		}
		int target = nearestVisit(index, 1);
		if (target < 0) {
			target = nearestVisit(index, -1);
		}
		if (target >= 0) {
			list.setSelectedIndex(target);
		}
	}

	private int pageStep() {
		int visible = list.getVisibleRowCount();
		int lastVisible = list.getLastVisibleIndex();
		int firstVisible = list.getFirstVisibleIndex();
		if (firstVisible >= 0 && lastVisible > firstVisible) {
			visible = lastVisible - firstVisible;
		}
		return Math.max(1, visible);
	}

	/** Add a typed character to the inline filter, ignoring control keys and shortcuts. */
	private void typeIntoFilter(KeyEvent e) {
		char typed = e.getKeyChar();
		if (e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
			return;
		}
		if (typed == KeyEvent.CHAR_UNDEFINED || Character.isISOControl(typed)) {
			return;
		}
		String folder = keepSelectedFolder();
		filter = filter + typed;
		reload(folder);
	}

	private void backspaceFilter() {
		if (filter.isEmpty()) {
			return;
		}
		String folder = keepSelectedFolder();
		filter = filter.substring(0, filter.length() - 1);
		reload(folder);
	}

	private String keepSelectedFolder() {
		FolderHistoryEntry entry = selectedEntry();
		return entry == null ? null : entry.path();
	}

	/** Close first, then navigate: the panel must not reload behind a modal window. */
	private void goToSelected() {

		FolderHistoryEntry entry = selectedEntry();
		if (entry == null) {
			return;
		}
		dispose();
		if (onGo != null) {
			onGo.accept(entry.path());
		}
	}

	private void removeSelected() {

		FolderHistoryEntry entry = selectedEntry();
		if (entry == null) {
			return;
		}

		int index = list.getSelectedIndex();
		history.remove(entry.path());
		reload(null);

		// Put the cursor back where the removed folder was, so a run of Deletes clears a
		// stretch of the list without the cursor jumping to the bottom each time.
		int target = nearestVisit(Math.min(index, model.size() - 1), 1);
		if (target < 0) {
			target = nearestVisit(Math.min(index, model.size() - 1), -1);
		}
		if (target >= 0) {
			list.setSelectedIndex(target);
			list.ensureIndexIsVisible(target);
		}
		updateButtonState();
	}

	private void clearAll() {

		if (model.isEmpty()) {
			return;
		}
		SoundEvents.warning(context);
		int answer = JOptionPane.showConfirmDialog(this,
				tr("history.confirm.clear"), tr("history.dialog.title"),
				JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
		if (answer != JOptionPane.YES_OPTION) {
			return;
		}
		history.clear();
		filter = "";
		reload(null);
	}

	private void close() {
		SoundEvents.cancel(context);
		dispose();
	}

	/**
	 * Work out which of the listed folders no longer exist, off the EDT, and grey them out as
	 * the answers come in. A missing drive can take seconds to answer, which is exactly why
	 * this does not run while the list is being built.
	 */
	private void checkExistenceInBackground() {

		List<FolderHistoryEntry> entries = history.entries();
		Thread.ofVirtual().name("folder-history-existence").start(() -> {
			var gone = new ArrayList<String>();
			for (FolderHistoryEntry entry : entries) {
				if (!exists(entry.path())) {
					gone.add(entry.path());
				}
			}
			if (gone.isEmpty()) {
				return;
			}
			SwingUtilities.invokeLater(() -> {
				missing.addAll(gone);
				list.repaint();
			});
		});
	}

	/**
	 * Whether a remembered folder is still there. Anything that is not a local path this JVM
	 * can even parse — an archive or a remote location recorded by another panel — counts as
	 * present, since only the panel that owns it can say otherwise.
	 */
	private static boolean exists(String path) {
		try {
			return Files.isDirectory(Path.of(path));
		} catch (RuntimeException e) {
			log.debug("Not checking {}: {}", path, e.getMessage());
			return true;
		}
	}

	private static Action action(Runnable body) {
		return new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				body.run();
			}
		};
	}

	/** Paints the two kinds of line: a folder, and the rule that separates one day from the next. */
	private final class RowRenderer implements ListCellRenderer<FolderHistoryRows.Row> {

		private final JLabel folderLabel = new JLabel();
		private final DaySeparator daySeparator = new DaySeparator();

		private RowRenderer() {
			folderLabel.setOpaque(true);
			folderLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends FolderHistoryRows.Row> list,
				FolderHistoryRows.Row value, int index, boolean selected, boolean focused) {

			if (value instanceof FolderHistoryRows.Day day) {
				daySeparator.setText(DayFormat.format(day.date()));
				return daySeparator;
			}

			var entry = ((FolderHistoryRows.Visit) value).entry();
			folderLabel.setText(entry.path());
			folderLabel.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
			if (selected) {
				folderLabel.setForeground(list.getSelectionForeground());
			} else {
				folderLabel.setForeground(missing.contains(entry.path())
						? disabledForeground(list)
						: list.getForeground());
			}
			folderLabel.setToolTipText(missing.contains(entry.path())
					? tr("history.tooltip.not_found", entry.path())
					: entry.path());
			return folderLabel;
		}

		private Color disabledForeground(JList<?> list) {
			Color color = UIManager.getColor("Label.disabledForeground");
			return color != null ? color : list.getForeground().darker();
		}
	}

	/** A day heading: the date, centred, with a rule running out to both edges of the list. */
	private static final class DaySeparator extends JComponent {

		private static final long serialVersionUID = 1L;

		private String text = "";

		private DaySeparator() {
			setOpaque(true);
			setFont(UIManager.getFont("List.font"));
		}

		private void setText(String text) {
			this.text = text == null ? "" : text;
		}

		@Override
		public Dimension getPreferredSize() {
			FontMetrics metrics = getFontMetrics(getFont());
			return new Dimension(10, metrics.getHeight() + 10);
		}

		@Override
		protected void paintComponent(Graphics graphics) {

			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				Color background = UIManager.getColor("List.background");
				g.setColor(background != null ? background : getBackground());
				g.fillRect(0, 0, getWidth(), getHeight());

				Color rule = UIManager.getColor("Component.borderColor");
				if (rule == null) {
					rule = UIManager.getColor("Separator.foreground");
				}
				Color label = UIManager.getColor("Label.disabledForeground");
				if (label == null) {
					label = rule;
				}

				FontMetrics metrics = g.getFontMetrics();
				int middle = getHeight() / 2;
				int textWidth = metrics.stringWidth(text);
				int textLeft = Math.max(8, (getWidth() - textWidth) / 2);

				if (rule != null) {
					g.setColor(rule);
					g.drawLine(8, middle, textLeft - 8, middle);
					g.drawLine(textLeft + textWidth + 8, middle, getWidth() - 8, middle);
				}
				if (label != null) {
					g.setColor(label);
				}
				g.drawString(text, textLeft, middle + metrics.getAscent() / 2 - 1);
			} finally {
				g.dispose();
			}
		}
	}
}
