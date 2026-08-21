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
package dev.nuclr.plugin.core.panel.fs.usercommands;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.formdev.flatlaf.extras.components.FlatButton;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/**
 * The F2 User Commands list: the commands the user has defined, and the five things they can
 * do to them — run (Enter), add (Insert), edit (F4), delete (Delete), close (Escape).
 *
 * <p>Each command's own key is bound here and nowhere else. That is the whole reason the
 * feature needs no application-wide shortcuts: a key such as {@code G} means "run Git Status"
 * only while this dialog has the keyboard, so it can never collide with the commander's
 * bindings, and {@link UserCommandShortcuts} keeps it clear of the five commands and the
 * navigation keys above so it cannot collide with the list either.
 *
 * <p>The dialog owns the working copy of the list and reports every change through
 * {@code onSave}; running a command closes the dialog first, so the console the command opens
 * is not left behind a modal window.
 */
@Slf4j
public class UserCommandsDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private static final String[] Columns = { "Key", "Name", "Command", "Working Directory" };

	/** Action-map keys for the dialog's own five commands. */
	private static final String RunAction = "usercommands.run";
	private static final String AddAction = "usercommands.add";
	private static final String EditAction = "usercommands.edit";
	private static final String DeleteAction = "usercommands.delete";
	private static final String CloseAction = "usercommands.close";

	/** Prefix for the per-command bindings, so they can be found and dropped on a rebuild. */
	private static final String ShortcutActionPrefix = "usercommands.shortcut.";

	private final transient List<UserCommand> commands;
	private final transient CommandsTableModel model = new CommandsTableModel();
	private final JTable table = new JTable(model);

	private final transient NuclrPluginContext context;
	private final transient Path panelDirectory;

	/** Persists the list after every change the dialog makes to it. */
	private final transient Consumer<List<UserCommand>> onSave;

	/** Runs a command; called after the dialog has closed. */
	private final transient Consumer<UserCommand> onRun;

	private final FlatButton runButton = new FlatButton();
	private final FlatButton editButton = new FlatButton();
	private final FlatButton deleteButton = new FlatButton();

	/**
	 * Build the dialog (must be called on the EDT).
	 *
	 * @param owner          window to anchor to
	 * @param commands       the stored commands; copied, never mutated in place
	 * @param panelDirectory the active panel's folder, shown as the default and used by the
	 *                       folder chooser as a starting point; may be {@code null}
	 * @param onSave         called with the new list after every add, edit or delete
	 * @param onRun          called with the chosen command once the dialog has closed
	 * @param context        plugin context for sounds; may be {@code null}
	 */
	public UserCommandsDialog(Window owner, List<UserCommand> commands, Path panelDirectory,
			Consumer<List<UserCommand>> onSave, Consumer<UserCommand> onRun, NuclrPluginContext context) {

		super(owner, "User Commands", ModalityType.APPLICATION_MODAL);

		this.commands = new ArrayList<>(commands == null ? List.of() : commands);
		this.panelDirectory = panelDirectory;
		this.onSave = onSave;
		this.onRun = onRun;
		this.context = context;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		setContentPane(buildContent());

		installActions();
		installFixedKeyBindings();
		rebindShortcuts();
		selectFirstRow();
		updateButtonState();

		setPreferredSize(new Dimension(780, 420));
		pack();
		setMinimumSize(new Dimension(560, 300));
		setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(table::requestFocusInWindow);
	}

	private JPanel buildContent() {

		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setRowSelectionAllowed(true);
		table.setFillsViewportHeight(true);
		table.setShowVerticalLines(false);
		table.getSelectionModel().addListSelectionListener(e -> updateButtonState());
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
					runSelected();
				}
			}
		});

		var keyColumn = table.getColumnModel().getColumn(0);
		keyColumn.setPreferredWidth(56);
		keyColumn.setMaxWidth(90);
		keyColumn.setCellRenderer(centered());

		table.getColumnModel().getColumn(1).setPreferredWidth(150);
		table.getColumnModel().getColumn(2).setPreferredWidth(280);
		table.getColumnModel().getColumn(3).setPreferredWidth(260);

		var monospaced = new DefaultTableCellRenderer();
		monospaced.setFont(new Font(Font.MONOSPACED, Font.PLAIN, table.getFont().getSize()));
		table.getColumnModel().getColumn(2).setCellRenderer(monospaced);

		runButton.setText("Run (Enter)");
		runButton.addActionListener(e -> runSelected());
		FlatButton addButton = new FlatButton();
		addButton.setText("Add (Insert)");
		addButton.addActionListener(e -> addCommand());
		editButton.setText("Edit (F4)");
		editButton.addActionListener(e -> editSelected());
		deleteButton.setText("Delete (Del)");
		deleteButton.addActionListener(e -> deleteSelected());
		FlatButton closeButton = new FlatButton();
		closeButton.setText("Close (Esc)");
		closeButton.addActionListener(e -> close());

		// The list is keyboard-first: leaving the buttons out of the focus cycle keeps the table
		// the focus owner, so Enter and Delete always mean "run" and "delete this command" rather
		// than "press whichever button happens to hold focus".
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		for (FlatButton button : List.of(runButton, addButton, editButton, deleteButton, closeButton)) {
			button.setFocusable(false);
			buttons.add(button);
		}

		JPanel footer = new JPanel(new BorderLayout(12, 0));
		footer.add(buttons, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(new JScrollPane(table), BorderLayout.CENTER);
		content.add(footer, BorderLayout.SOUTH);
		return content;
	}

	private static DefaultTableCellRenderer centered() {
		var renderer = new DefaultTableCellRenderer();
		renderer.setHorizontalAlignment(SwingConstants.CENTER);
		return renderer;
	}

	private void installActions() {
		table.getActionMap().put(RunAction, action(this::runSelected));
		table.getActionMap().put(AddAction, action(this::addCommand));
		table.getActionMap().put(EditAction, action(this::editSelected));
		table.getActionMap().put(DeleteAction, action(this::deleteSelected));
		table.getActionMap().put(CloseAction, action(this::close));
		getRootPane().getActionMap().put(CloseAction, action(this::close));
	}

	/**
	 * Bind the five list commands on the table itself. They go in the focused-component map so
	 * they win over the table's own defaults — Enter would otherwise just move the selection
	 * down a row — while the arrows, paging and Home/End are deliberately left alone.
	 */
	private void installFixedKeyBindings() {
		InputMap focused = table.getInputMap(JComponent.WHEN_FOCUSED);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), RunAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0), AddAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), EditAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), DeleteAction);
		focused.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CloseAction);

		// Escape also at window level, so it still closes if focus ever sits elsewhere.
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CloseAction);
	}

	/**
	 * Install one binding per command that has a shortcut, replacing whatever the previous list
	 * left behind. Called after every change, since a key can move between commands.
	 */
	private void rebindShortcuts() {

		InputMap focused = table.getInputMap(JComponent.WHEN_FOCUSED);
		for (KeyStroke bound : focused.keys() == null ? new KeyStroke[0] : focused.keys()) {
			Object name = focused.get(bound);
			if (name instanceof String text && text.startsWith(ShortcutActionPrefix)) {
				focused.remove(bound);
				table.getActionMap().remove(text);
			}
		}

		for (int index = 0; index < commands.size(); index++) {
			UserCommand command = commands.get(index);
			KeyStroke shortcut = command.shortcut();
			if (shortcut == null || UserCommandShortcuts.isReserved(shortcut)) {
				continue;
			}
			String name = ShortcutActionPrefix + index;
			focused.put(shortcut, name);
			final UserCommand target = command;
			table.getActionMap().put(name, action(() -> run(target)));
		}
	}

	private void selectFirstRow() {
		if (!commands.isEmpty()) {
			table.setRowSelectionInterval(0, 0);
		}
	}

	private void updateButtonState() {
		boolean hasSelection = table.getSelectedRow() >= 0;
		runButton.setEnabled(hasSelection);
		editButton.setEnabled(hasSelection);
		deleteButton.setEnabled(hasSelection);
	}

	private UserCommand selected() {
		int row = table.getSelectedRow();
		return row >= 0 && row < commands.size() ? commands.get(row) : null;
	}

	private void runSelected() {
		run(selected());
	}

	/** Close first, then run: the console must not open behind a modal window. */
	private void run(UserCommand command) {
		if (command == null) {
			return;
		}
		dispose();
		if (onRun != null) {
			onRun.accept(command);
		}
	}

	private void addCommand() {

		var dialog = new UserCommandEditDialog(this, UserCommand.empty(), commands, -1, panelDirectory, context);
		SoundEvents.popup(context);
		dialog.setVisible(true);

		dialog.getResult().ifPresent(added -> {
			commands.add(added);
			commit(commands.size() - 1);
		});
	}

	private void editSelected() {

		int row = table.getSelectedRow();
		UserCommand current = selected();
		if (current == null) {
			return;
		}

		var dialog = new UserCommandEditDialog(this, current, commands, row, panelDirectory, context);
		SoundEvents.popup(context);
		dialog.setVisible(true);

		dialog.getResult().ifPresent(edited -> {
			commands.set(row, edited);
			commit(row);
		});
	}

	private void deleteSelected() {

		int row = table.getSelectedRow();
		UserCommand current = selected();
		if (current == null) {
			return;
		}
		if (!UserCommandDialogs.confirmDelete(current, context)) {
			return;
		}

		commands.remove(row);
		commit(Math.min(row, commands.size() - 1));
	}

	/** Persist the list, refresh the table and the per-command keys, and restore the cursor. */
	private void commit(int rowToSelect) {

		if (onSave != null) {
			onSave.accept(List.copyOf(commands));
		}
		model.fireTableDataChanged();
		rebindShortcuts();

		if (rowToSelect >= 0 && rowToSelect < commands.size()) {
			table.setRowSelectionInterval(rowToSelect, rowToSelect);
			table.scrollRectToVisible(table.getCellRect(rowToSelect, 0, true));
		}
		updateButtonState();
		table.requestFocusInWindow();
	}

	private void close() {
		SoundEvents.cancel(context);
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

	/** Read-only view of the command list: Key, Name, Command, Working Directory. */
	private final class CommandsTableModel extends AbstractTableModel {

		private static final long serialVersionUID = 1L;

		@Override
		public int getRowCount() {
			return commands.size();
		}

		@Override
		public int getColumnCount() {
			return Columns.length;
		}

		@Override
		public String getColumnName(int column) {
			return Columns[column];
		}

		@Override
		public boolean isCellEditable(int rowIndex, int columnIndex) {
			return false;
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex) {
			UserCommand command = commands.get(rowIndex);
			return switch (columnIndex) {
				case 0 -> command.shortcutLabel();
				case 1 -> command.name();
				case 2 -> command.command();
				// An empty working directory is not missing information — it is the instruction
				// to use whatever folder the panel is showing, so say so rather than leaving a gap.
				default -> command.hasWorkingDirectory() ? command.workingDirectory() : "(current panel folder)";
			};
		}
	}
}
