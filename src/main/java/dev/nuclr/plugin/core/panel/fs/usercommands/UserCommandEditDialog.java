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
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.extras.components.FlatButton;
import com.formdev.flatlaf.extras.components.FlatLabel;
import com.formdev.flatlaf.extras.components.FlatTextField;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/**
 * The Add (Insert) and Edit (F4) dialog for one user command.
 *
 * <p>Modal, and its whole result is the {@link #getResult()} command — the caller owns the
 * list and decides what to do with it, so this dialog neither saves nor knows about
 * persistence.
 *
 * <p>Two fields are more than text boxes. The working directory pairs an editable field with
 * a {@code …} button opening a {@link JFileChooser} in
 * {@link JFileChooser#DIRECTORIES_ONLY} mode; picking a folder fills the field in, and typing
 * a path by hand stays possible. The shortcut is a {@link ShortcutField}, written by pressing
 * the key rather than by naming it, and a key already taken by another command is refused
 * here — as the user presses it — rather than at save time, so the field never shows a value
 * that will not survive OK.
 */
@Slf4j
public class UserCommandEditDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private final FlatTextField nameField = new FlatTextField();
	private final FlatTextField commandField = new FlatTextField();
	private final FlatTextField workingDirectoryField = new FlatTextField();
	private final FlatButton browseButton = new FlatButton();
	private final ShortcutField shortcutField;
	private final FlatButton clearShortcutButton = new FlatButton();
	private final JCheckBox confirmationCheckBox = new JCheckBox("Ask for confirmation before execution");

	private final transient List<UserCommand> existing;
	private final transient int editingIndex;
	private final transient NuclrPluginContext context;

	/** The folder the chooser opens in when the field is empty — where the panel is. */
	private final transient Path panelDirectory;

	private transient UserCommand result;

	/**
	 * Build the dialog (must be called on the EDT).
	 *
	 * @param owner          window to anchor to
	 * @param initial        the command to edit, or {@link UserCommand#empty()} to add one
	 * @param existing       the current list, for duplicate-shortcut checks
	 * @param editingIndex   index of {@code initial} within {@code existing}, or {@code -1} when adding
	 * @param panelDirectory the active panel's folder, used as the chooser's starting point
	 * @param context        plugin context for sounds; may be {@code null}
	 */
	public UserCommandEditDialog(Window owner, UserCommand initial, List<UserCommand> existing, int editingIndex,
			Path panelDirectory, NuclrPluginContext context) {

		super(owner, editingIndex < 0 ? "Add User Command" : "Edit User Command", ModalityType.APPLICATION_MODAL);

		this.existing = existing == null ? List.of() : existing;
		this.editingIndex = editingIndex;
		this.panelDirectory = panelDirectory;
		this.context = context;
		this.shortcutField = new ShortcutField(this::acceptShortcut, captured -> updateClearEnabled());

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		nameField.setColumns(28);
		nameField.setPlaceholderText("Git Status");
		commandField.setColumns(28);
		commandField.setPlaceholderText("git status");
		commandField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, commandField.getFont().getSize()));
		workingDirectoryField.setColumns(28);
		workingDirectoryField.setPlaceholderText("Current panel folder");

		browseButton.setText("…");
		browseButton.setToolTipText("Choose a folder");
		browseButton.addActionListener(e -> chooseWorkingDirectory());

		clearShortcutButton.setText("Clear");
		clearShortcutButton.setToolTipText("Remove the shortcut key");
		clearShortcutButton.addActionListener(e -> {
			shortcutField.setShortcut(null);
			updateClearEnabled();
			shortcutField.requestFocusInWindow();
		});

		setContentPane(buildContent());
		applyInitialValues(initial);
		installKeyBindings();

		pack();
		setMinimumSize(new Dimension(520, getHeight()));
		setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(nameField::requestFocusInWindow);
	}

	/**
	 * The command as accepted by the user.
	 *
	 * @return the edited command, or empty when the dialog was cancelled
	 */
	public Optional<UserCommand> getResult() {
		return Optional.ofNullable(result);
	}

	private JPanel buildContent() {

		JPanel form = new JPanel(new GridBagLayout());
		form.setBorder(BorderFactory.createEmptyBorder(14, 16, 8, 16));

		int row = 0;
		addRow(form, row++, "Name:", nameField, null);
		addRow(form, row++, "Command:", commandField, null);
		addRow(form, row++, "Working directory:", workingDirectoryField, browseButton);
		addRow(form, row++, "Shortcut:", shortcutField, clearShortcutButton);

		FlatLabel hint = new FlatLabel();
		hint.setLabelType(FlatLabel.LabelType.small);
		hint.setText("Shortcut keys work only while the User Commands list is open.");
		var hintConstraints = new GridBagConstraints();
		hintConstraints.gridx = 1;
		hintConstraints.gridy = row++;
		hintConstraints.gridwidth = 2;
		hintConstraints.anchor = GridBagConstraints.WEST;
		hintConstraints.insets = new Insets(0, 0, 8, 0);
		form.add(hint, hintConstraints);

		var checkConstraints = new GridBagConstraints();
		checkConstraints.gridx = 1;
		checkConstraints.gridy = row;
		checkConstraints.gridwidth = 2;
		checkConstraints.anchor = GridBagConstraints.WEST;
		checkConstraints.insets = new Insets(2, 0, 0, 0);
		form.add(confirmationCheckBox, checkConstraints);

		FlatButton okButton = new FlatButton();
		okButton.setText("OK");
		okButton.addActionListener(e -> commit());

		FlatButton cancelButton = new FlatButton();
		cancelButton.setText("Cancel");
		cancelButton.addActionListener(e -> cancel());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.setBorder(BorderFactory.createEmptyBorder(4, 16, 12, 16));
		buttons.add(okButton);
		buttons.add(cancelButton);

		JPanel content = new JPanel(new BorderLayout());
		content.add(form, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(okButton);
		return content;
	}

	/** One label + field row, optionally with a trailing button in a third column. */
	private static void addRow(JPanel form, int row, String label, JComponent field, JComponent trailing) {

		var labelConstraints = new GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.gridy = row;
		labelConstraints.anchor = GridBagConstraints.WEST;
		labelConstraints.insets = new Insets(0, 0, 8, 10);
		form.add(new JLabel(label), labelConstraints);

		var fieldConstraints = new GridBagConstraints();
		fieldConstraints.gridx = 1;
		fieldConstraints.gridy = row;
		fieldConstraints.weightx = 1;
		fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
		fieldConstraints.insets = new Insets(0, 0, 8, trailing != null ? 6 : 0);
		form.add(field, fieldConstraints);

		if (trailing != null) {
			var trailingConstraints = new GridBagConstraints();
			trailingConstraints.gridx = 2;
			trailingConstraints.gridy = row;
			trailingConstraints.anchor = GridBagConstraints.WEST;
			trailingConstraints.insets = new Insets(0, 0, 8, 0);
			form.add(trailing, trailingConstraints);
		}
	}

	private void applyInitialValues(UserCommand initial) {
		UserCommand seed = initial == null ? UserCommand.empty() : initial;
		nameField.setText(seed.name());
		commandField.setText(seed.command());
		workingDirectoryField.setText(seed.workingDirectory());
		shortcutField.setShortcut(seed.shortcut());
		confirmationCheckBox.setSelected(seed.confirmationRequired());
		updateClearEnabled();
	}

	private void installKeyBindings() {
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "usercommand.edit.cancel");
		getRootPane().getActionMap().put("usercommand.edit.cancel", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				cancel();
			}
		});
	}

	private void updateClearEnabled() {
		clearShortcutButton.setEnabled(shortcutField.getShortcut() != null);
	}

	/**
	 * Vet a key the user just pressed in the shortcut field. Refusing here — rather than at OK
	 * — means the message names the key they actually pressed, and the field keeps whatever it
	 * held before.
	 */
	private boolean acceptShortcut(KeyStroke captured) {
		Optional<String> problem = UserCommandValidation.shortcutError(captured, existing, editingIndex);
		if (problem.isEmpty()) {
			return true;
		}
		showValidationError(problem.get());
		return false;
	}

	/** Open a directory-only chooser and put the chosen folder in the field. */
	private void chooseWorkingDirectory() {

		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Choose the working directory");
		startingFolder().ifPresent(folder -> chooser.setCurrentDirectory(folder.toFile()));

		SoundEvents.popup(context);
		if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
			SoundEvents.cancel(context);
			return;
		}
		File selected = chooser.getSelectedFile();
		if (selected == null) {
			SoundEvents.cancel(context);
			return;
		}
		workingDirectoryField.setText(selected.toPath().toAbsolutePath().normalize().toString());
		SoundEvents.confirmation(context);
	}

	/** Where the chooser should open: the typed path if it is usable, else the panel's folder. */
	private Optional<Path> startingFolder() {
		String typed = workingDirectoryField.getText();
		if (typed != null && !typed.isBlank()) {
			try {
				Path path = Path.of(typed.trim()).toAbsolutePath().normalize();
				Path existingAncestor = path;
				while (existingAncestor != null && !Files.isDirectory(existingAncestor)) {
					existingAncestor = existingAncestor.getParent();
				}
				if (existingAncestor != null) {
					return Optional.of(existingAncestor);
				}
			} catch (InvalidPathException e) {
				log.debug("Working directory field holds an unusable path [{}]: {}", typed, e.getMessage());
			}
		}
		return Optional.ofNullable(panelDirectory);
	}

	private void commit() {

		UserCommand candidate = new UserCommand(
				nameField.getText(),
				commandField.getText(),
				workingDirectoryField.getText(),
				shortcutField.getShortcut(),
				confirmationCheckBox.isSelected());

		Optional<String> problem = UserCommandValidation.validate(candidate, existing, editingIndex);
		if (problem.isPresent()) {
			showValidationError(problem.get());
			return;
		}

		result = candidate;
		SoundEvents.confirmation(context);
		dispose();
	}

	private void cancel() {
		result = null;
		SoundEvents.cancel(context);
		dispose();
	}

	private void showValidationError(String message) {
		SoundEvents.error(context);
		JOptionPane.showMessageDialog(this, message, "User Commands", JOptionPane.WARNING_MESSAGE);
	}
}
