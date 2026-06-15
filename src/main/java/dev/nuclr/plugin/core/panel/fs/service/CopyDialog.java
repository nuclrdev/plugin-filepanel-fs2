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
package dev.nuclr.plugin.core.panel.fs.service;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

/**
 * Modal F5 "Copy" setup dialog. Collects the destination and copy options, returning a
 * populated {@link CopyOptions} on confirmation or {@code null} on Cancel/ESC. May be called
 * from any thread — it marshals to the EDT and blocks for the user's answer.
 */
@Slf4j
final class CopyDialog {

	private static final String TITLE = "Copy";

	private CopyDialog() {
	}

	/**
	 * @param header        description of what is being copied (e.g. {@code "nuclr.exe"} or {@code "3 items"})
	 * @param defaultTarget pre-filled destination directory
	 * @return the chosen options, or {@code null} if the user cancelled
	 */
	static CopyOptions show(String header, Path defaultTarget) {

		final CopyOptions[] result = new CopyOptions[1];

		runOnEdtAndWait(() -> result[0] = build(header, defaultTarget));
		return result[0];
	}

	private static CopyOptions build(String header, Path defaultTarget) {

		Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		JDialog dialog = new JDialog(owner, TITLE, JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		// --- destination ---
		JTextField destField = new JTextField(
				defaultTarget != null ? defaultTarget.toString() : "", 40);

		JPanel destPanel = new JPanel(new BorderLayout(0, 4));
		destPanel.add(new JLabel("Copy " + header + " to:"), BorderLayout.NORTH);
		destPanel.add(destField, BorderLayout.CENTER);

		// --- access rights ---
		JRadioButton rDefault = new JRadioButton("Default", true);
		JRadioButton rCopy = new JRadioButton("Copy");
		JRadioButton rInherit = new JRadioButton("Inherit");
		ButtonGroup accessGroup = new ButtonGroup();
		accessGroup.add(rDefault);
		accessGroup.add(rCopy);
		accessGroup.add(rInherit);

		JPanel accessPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		accessPanel.setBorder(BorderFactory.createTitledBorder("Access rights"));
		accessPanel.add(rDefault);
		accessPanel.add(rCopy);
		accessPanel.add(rInherit);

		// --- conflict handling + flags ---
		JComboBox<String> existing = new JComboBox<>(new String[] {
				"Ask", "Overwrite", "Skip", "Rename", "Append", "Only newer file(s)" });
		JCheckBox askReadOnly = new JCheckBox("Also ask on R/O files", true);

		JCheckBox preserve = new JCheckBox("Preserve all timestamps");
		JCheckBox symlink = new JCheckBox("Copy contents of symbolic links");
		JCheckBox multiDest = new JCheckBox("Process multiple destinations");
		JCheckBox useFilter = new JCheckBox("Use filter");

		JPanel existingRow = new JPanel(new BorderLayout(8, 0));
		existingRow.add(new JLabel("Already existing files:"), BorderLayout.WEST);
		existingRow.add(existing, BorderLayout.CENTER);

		JPanel optionsPanel = new JPanel();
		optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
		optionsPanel.add(existingRow);
		optionsPanel.add(Box.createVerticalStrut(4));
		optionsPanel.add(askReadOnly);
		optionsPanel.add(preserve);
		optionsPanel.add(symlink);
		optionsPanel.add(multiDest);

		// --- buttons ---
		JButton copyButton = new JButton("Copy");
		JButton filterButton = new JButton("Filter");
		JButton cancelButton = new JButton("Cancel");

		final CopyOptions[] chosen = new CopyOptions[1];

		copyButton.addActionListener(e -> {
			CopyOptions opts = collect(destField, rCopy, rInherit, existing, askReadOnly, preserve, symlink, multiDest,
					useFilter);
			if (opts == null) {
				return; // invalid destination; keep the dialog open
			}
			chosen[0] = opts;
			dialog.dispose();
		});
		filterButton.addActionListener(e -> useFilter.setSelected(true));
		cancelButton.addActionListener(e -> dialog.dispose());

		dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttons.add(copyButton);
		buttons.add(filterButton);
		buttons.add(cancelButton);

		JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		filterPanel.add(useFilter);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
		addStacked(body, destPanel, accessPanel, optionsPanel, filterPanel);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.add(body, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(copyButton);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(520, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(destField::requestFocusInWindow);
		dialog.setVisible(true); // blocks (modal) until disposed

		return chosen[0];
	}

	private static void addStacked(JPanel body, JComponent... parts) {
		for (int i = 0; i < parts.length; i++) {
			parts[i].setAlignmentX(Component.LEFT_ALIGNMENT);
			body.add(parts[i]);
			if (i < parts.length - 1) {
				body.add(Box.createVerticalStrut(10));
			}
		}
	}

	private static CopyOptions collect(JTextField destField, JRadioButton rCopy, JRadioButton rInherit,
			JComboBox<String> existing, JCheckBox askReadOnly, JCheckBox preserve, JCheckBox symlink,
			JCheckBox multiDest, JCheckBox useFilter) {

		String text = destField.getText() == null ? "" : destField.getText().trim();
		if (text.isEmpty()) {
			return null;
		}
		Path destination;
		try {
			destination = Path.of(text);
		} catch (InvalidPathException e) {
			log.debug("Invalid copy destination [{}]: {}", text, e.getMessage());
			return null;
		}

		CopyOptions opts = new CopyOptions();
		opts.setDestination(destination);
		opts.setAccessRights(rCopy.isSelected() ? CopyOptions.AccessRights.COPY
				: rInherit.isSelected() ? CopyOptions.AccessRights.INHERIT : CopyOptions.AccessRights.DEFAULT);
		opts.setConflictMode(conflictModeFor(existing.getSelectedIndex()));
		opts.setAskOnReadOnly(askReadOnly.isSelected());
		opts.setPreserveTimestamps(preserve.isSelected());
		opts.setCopySymbolicLinkContents(symlink.isSelected());
		opts.setMultipleDestinations(multiDest.isSelected());
		opts.setUseFilter(useFilter.isSelected());
		return opts;
	}

	private static CopyOptions.ConflictMode conflictModeFor(int index) {
		List<CopyOptions.ConflictMode> modes = List.of(
				CopyOptions.ConflictMode.ASK,
				CopyOptions.ConflictMode.OVERWRITE,
				CopyOptions.ConflictMode.SKIP,
				CopyOptions.ConflictMode.RENAME,
				CopyOptions.ConflictMode.APPEND,
				CopyOptions.ConflictMode.ONLY_NEWER);
		return index >= 0 && index < modes.size() ? modes.get(index) : CopyOptions.ConflictMode.ASK;
	}

	private static void runOnEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (Exception e) {
			log.warn("Failed to run copy dialog on EDT: {}", e.getMessage(), e);
		}
	}
}
