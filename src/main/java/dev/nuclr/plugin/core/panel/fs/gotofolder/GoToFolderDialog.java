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
package dev.nuclr.plugin.core.panel.fs.gotofolder;

import static dev.nuclr.plugin.core.panel.fs.Messages.tr;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
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
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.extras.components.FlatButton;

import lombok.extern.slf4j.Slf4j;

/**
 * The Shift+Alt+G "Go to folder" prompt: type a path, press Enter, and the panel is there —
 * Finder's Cmd+Shift+G, with completion.
 *
 * <p>The field keeps the focus throughout. Down steps into the list of folders that could
 * finish what has been typed and Up steps back out of its top, so a path can be walked with the
 * arrows without ever taking the hands off the keyboard; Tab fills in as far as the matches
 * agree, which is the whole name once only one folder is left. Enter goes wherever the field
 * reads, or to the highlighted suggestion while the list has the cursor.
 *
 * <p>Every filesystem question — does this exist, what is inside it — is asked on a background
 * thread and answered back on the EDT, keyed by a generation counter so a slow listing of a
 * disconnected share cannot overwrite the answer for what the user has typed since. Nothing
 * here blocks on a drive that has stopped responding.
 */
@Slf4j
public class GoToFolderDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private static final String GoAction = "gotofolder.go";
	private static final String CloseAction = "gotofolder.close";
	private static final String CompleteAction = "gotofolder.complete";
	private static final String DownAction = "gotofolder.down";
	private static final String UpAction = "gotofolder.up";

	/** One thread, so the listings queue rather than storm a struggling drive. */
	private final transient ExecutorService lookups = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "goto-folder-completion");
		thread.setDaemon(true);
		return thread;
	});

	/** The folder the panel is showing; relative input is taken from here. */
	private final transient Path currentFolder;

	/** Called with the chosen path once the dialog has closed. */
	private final transient Consumer<Path> onGo;

	private final JTextField field = new JTextField();
	private final DefaultListModel<Path> model = new DefaultListModel<>();
	private final JList<Path> suggestions = new JList<>(model);
	private final JLabel status = new JLabel(" ");
	private final FlatButton goButton = new FlatButton();

	/** Bumped for every edit; a lookup whose generation is stale is thrown away on arrival. */
	private final transient AtomicLong generation = new AtomicLong();

	/**
	 * Build the dialog (must be called on the EDT).
	 *
	 * @param owner         window to anchor to
	 * @param currentFolder the folder the panel is on, for relative paths; may be {@code null}
	 * @param onGo          called with the chosen path once the dialog has closed
	 */
	public GoToFolderDialog(Window owner, Path currentFolder, Consumer<Path> onGo) {

		super(owner, tr("goto.dialog.title"), ModalityType.APPLICATION_MODAL);

		this.currentFolder = currentFolder;
		this.onGo = onGo;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setContentPane(buildContent());

		installActions();
		installKeyBindings();

		// Open on the current folder, spelled out and selected: Enter alone is then a no-op
		// rather than an error, and typing replaces it the way a location bar does.
		if (currentFolder != null) {
			field.setText(currentFolder.toString());
			field.selectAll();
		}
		refreshSuggestions();

		setPreferredSize(new Dimension(720, 420));
		pack();
		setMinimumSize(new Dimension(420, 260));
		setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(field::requestFocusInWindow);
	}

	private JPanel buildContent() {

		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refreshSuggestions();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				refreshSuggestions();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				refreshSuggestions();
			}
		});

		suggestions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		suggestions.setCellRenderer(new SuggestionRenderer());
		suggestions.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
					goToSelectedSuggestion();
				}
			}
		});

		status.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));

		goButton.setText(tr("goto.button.go"));
		goButton.addActionListener(e -> go());
		FlatButton closeButton = new FlatButton();
		closeButton.setText(tr("goto.button.cancel"));
		closeButton.addActionListener(e -> close());

		// Keyboard-first, as in the other panel lists: buttons stay out of the focus cycle so
		// Enter always means the field, never whichever button happened to be focused.
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		for (FlatButton button : List.of(goButton, closeButton)) {
			button.setFocusable(false);
			buttons.add(button);
		}

		JPanel footer = new JPanel(new BorderLayout(12, 0));
		footer.add(status, BorderLayout.WEST);
		footer.add(buttons, BorderLayout.EAST);

		JLabel prompt = new JLabel(tr("goto.prompt"));
		prompt.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		JPanel header = new JPanel(new BorderLayout());
		header.add(prompt, BorderLayout.NORTH);
		header.add(field, BorderLayout.CENTER);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(header, BorderLayout.NORTH);
		content.add(new JScrollPane(suggestions), BorderLayout.CENTER);
		content.add(footer, BorderLayout.SOUTH);
		return content;
	}

	private void installActions() {
		for (JComponent component : List.of(field, suggestions)) {
			component.getActionMap().put(GoAction, action(this::go));
			component.getActionMap().put(CloseAction, action(this::close));
			component.getActionMap().put(CompleteAction, action(this::complete));
			component.getActionMap().put(DownAction, action(this::moveIntoSuggestions));
			component.getActionMap().put(UpAction, action(this::moveOutOfSuggestions));
		}
		getRootPane().getActionMap().put(CloseAction, action(this::close));
	}

	private void installKeyBindings() {

		// Tab is a focus-traversal key, and traversal is decided by the focus manager before any
		// input map is consulted: without emptying these sets the binding below would never run.
		for (JComponent component : List.of(field, suggestions)) {
			component.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Set.of());
			component.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Set.of());
		}

		InputMap fieldMap = field.getInputMap(JComponent.WHEN_FOCUSED);
		fieldMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), GoAction);
		fieldMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CloseAction);
		// Tab completes instead of traversing: there is nowhere else in this dialog the focus
		// would usefully go, and completion is what the key means in a path field.
		fieldMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), CompleteAction);
		fieldMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), DownAction);

		InputMap listMap = suggestions.getInputMap(JComponent.WHEN_FOCUSED);
		listMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), GoAction);
		listMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CloseAction);
		listMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), CompleteAction);
		// Up off the first row returns to the field; every other Up is the list's own.
		listMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), UpAction);

		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CloseAction);
	}

	/** Look up what could finish the text, off the EDT, and show it if it is still current. */
	private void refreshSuggestions() {

		String text = field.getText();
		long mine = generation.incrementAndGet();

		lookups.execute(() -> {
			List<Path> found = PathCompletion.suggestions(text, currentFolder);
			Path resolved = PathCompletion.resolve(text, currentFolder);
			boolean exists = resolved != null && Files.isDirectory(resolved);
			SwingUtilities.invokeLater(() -> {
				if (generation.get() == mine) {
					showSuggestions(found, text, exists);
				}
			});
		});
	}

	private void showSuggestions(List<Path> found, String text, boolean exists) {

		model.clear();
		for (Path path : found) {
			model.addElement(path);
		}

		goButton.setEnabled(!text.isBlank());

		if (text.isBlank()) {
			status.setText(" ");
			return;
		}

		if (exists) {
			status.setText(found.isEmpty() ? tr("goto.status.exists") : tr("goto.status.inside", String.valueOf(found.size())));
			return;
		}

		status.setText(found.isEmpty() ? tr("goto.status.no_such_folder") : tr("goto.status.matching", String.valueOf(found.size())));
	}

	/** Fill the field in as far as every suggestion agrees, and re-offer what is left. */
	private void complete() {

		List<Path> found = currentSuggestions();
		String prefix = PathCompletion.commonPrefix(found);

		if (prefix.isEmpty()) {
			return;
		}

		Path first = found.get(0);
		Path parent = first.getParent();
		String completed = parent == null ? prefix : parent.resolve(prefix).toString();

		// One match left: complete it whole and add the separator, so the next keystroke
		// carries on into it rather than editing the name just completed.
		if (found.size() == 1) {
			completed = first.toString();
			if (!completed.endsWith(java.io.File.separator)) {
				completed = completed + java.io.File.separator;
			}
		}

		field.setText(completed);
		field.setCaretPosition(field.getDocument().getLength());
		field.requestFocusInWindow();
	}

	private List<Path> currentSuggestions() {
		var found = new java.util.ArrayList<Path>(model.size());
		for (int index = 0; index < model.size(); index++) {
			found.add(model.get(index));
		}
		return found;
	}

	/** Down from the field: put the cursor on the first suggestion. */
	private void moveIntoSuggestions() {

		if (model.isEmpty()) {
			return;
		}

		if (!suggestions.isFocusOwner()) {
			suggestions.setSelectedIndex(0);
			suggestions.ensureIndexIsVisible(0);
			suggestions.requestFocusInWindow();
			return;
		}

		int next = Math.min(suggestions.getSelectedIndex() + 1, model.size() - 1);
		suggestions.setSelectedIndex(next);
		suggestions.ensureIndexIsVisible(next);
	}

	/** Up from the top of the list: back to the field, where typing carries on. */
	private void moveOutOfSuggestions() {

		int selected = suggestions.getSelectedIndex();

		if (selected <= 0) {
			suggestions.clearSelection();
			field.requestFocusInWindow();
			field.setCaretPosition(field.getDocument().getLength());
			return;
		}

		suggestions.setSelectedIndex(selected - 1);
		suggestions.ensureIndexIsVisible(selected - 1);
	}

	/** Enter: the highlighted suggestion while the list has the cursor, else what was typed. */
	private void go() {

		if (suggestions.isFocusOwner() && suggestions.getSelectedValue() != null) {
			goToSelectedSuggestion();
			return;
		}

		Path path = PathCompletion.resolve(field.getText(), currentFolder);

		if (path == null) {
			status.setText(tr("goto.status.type_folder"));
			return;
		}

		// A path that is not there is not a reason to close: the user is mid-way through typing
		// one, and losing the dialog would lose the text with it.
		if (!Files.exists(path)) {
			status.setText(tr("goto.status.no_such_folder_path", path));
			return;
		}

		close();
		if (onGo != null) {
			onGo.accept(path);
		}
	}

	private void goToSelectedSuggestion() {

		Path selected = suggestions.getSelectedValue();

		if (selected == null) {
			return;
		}

		close();
		if (onGo != null) {
			onGo.accept(selected);
		}
	}

	private void close() {
		lookups.shutdownNow();
		setVisible(false);
		dispose();
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

	/** Shows the folder's own name, with the path it sits in behind it. */
	private final class SuggestionRenderer extends javax.swing.DefaultListCellRenderer {

		private static final long serialVersionUID = 1L;

		@Override
		public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
				boolean isSelected, boolean cellHasFocus) {

			super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

			if (value instanceof Path path) {
				Path parent = path.getParent();
				setText(PathCompletion.nameOf(path));
				setToolTipText(path.toString());
				if (parent != null && !isSelected) {
					setForeground(UIManager.getColor("Label.foreground"));
				}
			}

			setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
			return this;
		}
	}
}
