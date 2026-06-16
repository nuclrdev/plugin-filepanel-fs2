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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.panel.fs.service.CopyEngine.Action;
import dev.nuclr.plugin.core.panel.fs.service.CopyEngine.Resolution;
import lombok.extern.slf4j.Slf4j;

/**
 * Swing {@link CopyEngine.ConflictResolver} that shows the "File already exists" warning and,
 * when the user picks Rename, the rename prompt. Honours the "Remember choice" checkbox by
 * caching the answer and applying it to subsequent clashes without re-prompting.
 *
 * <p>One instance is created per copy operation so the remembered choice is scoped to that run.
 * Methods marshal to the EDT and block for the user's answer, so the resolver is safe to call
 * from the background copy thread.
 */
@Slf4j
final class CopyConflictDialog implements CopyEngine.ConflictResolver {

	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

	/** Sticky answer once "Remember choice" was ticked; {@code null} until then. */
	private Resolution remembered;

	@Override
	public Resolution resolve(Path source, Path target) {

		if (remembered != null) {
			return remembered;
		}

		final Resolution[] result = new Resolution[1];
		runOnEdtAndWait(() -> result[0] = ask(source, target));
		return result[0];
	}

	private Resolution ask(Path source, Path target) {

		Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		JDialog dialog = new JDialog(owner, "Warning", JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JLabel title = new JLabel("File already exists", JLabel.CENTER);
		title.setFont(title.getFont().deriveFont(Font.BOLD));

		JPanel header = new JPanel(new BorderLayout(0, 4));
		header.add(title, BorderLayout.NORTH);
		header.add(new JLabel(target.toString()), BorderLayout.CENTER);

		JPanel info = new JPanel(new GridLayout(2, 1, 0, 2));
		info.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		info.add(detailRow("New", source));
		info.add(detailRow("Existing", target));

		JCheckBox remember = new JCheckBox("Remember choice");

		final Resolution[] picked = new Resolution[1];

		JButton overwrite = new JButton("Overwrite");
		JButton skip = new JButton("Skip");
		JButton rename = new JButton("Rename");
		JButton append = new JButton("Append");
		JButton cancel = new JButton("Cancel");

		overwrite.addActionListener(e -> {
			picked[0] = Resolution.of(Action.OVERWRITE);
			dialog.dispose();
		});
		skip.addActionListener(e -> {
			picked[0] = Resolution.of(Action.SKIP);
			dialog.dispose();
		});
		append.addActionListener(e -> {
			picked[0] = Resolution.of(Action.APPEND);
			dialog.dispose();
		});
		rename.addActionListener(e -> {
			// When remembering, store the auto-rename policy (null target → engine auto-names each).
			if (remember.isSelected()) {
				picked[0] = Resolution.of(Action.RENAME);
				dialog.dispose();
				return;
			}
			Path newTarget = promptRename(owner, target);
			if (newTarget == null) {
				return; // back to the warning dialog
			}
			picked[0] = new Resolution(Action.RENAME, newTarget);
			dialog.dispose();
		});
		cancel.addActionListener(e -> {
			picked[0] = Resolution.of(Action.CANCEL);
			dialog.dispose();
		});

		dialog.getRootPane().registerKeyboardAction(e -> {
			picked[0] = Resolution.of(Action.CANCEL);
			dialog.dispose();
		}, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		// Left/Right arrows move between the buttons (wrapping around), in addition to Tab.
		installArrowTraversal(overwrite, skip, rename, append, cancel);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttons.add(overwrite);
		buttons.add(skip);
		buttons.add(rename);
		buttons.add(append);
		buttons.add(cancel);

		JPanel rememberRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		rememberRow.add(remember);

		JPanel center = new JPanel(new BorderLayout(0, 6));
		center.add(header, BorderLayout.NORTH);
		center.add(info, BorderLayout.CENTER);
		center.add(rememberRow, BorderLayout.SOUTH);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
		content.add(center, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(overwrite);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(overwrite::requestFocusInWindow);
		dialog.setVisible(true);

		Resolution resolution = picked[0] != null ? picked[0] : Resolution.of(Action.CANCEL);
		if (remember.isSelected() && resolution.action() != Action.CANCEL) {
			remembered = resolution;
		}
		return resolution;
	}

	/** @return the chosen new target path, or {@code null} if the rename was cancelled. */
	private Path promptRename(Window owner, Path target) {

		JDialog dialog = new JDialog(owner, "Rename", JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JTextField field = new JTextField(CopyEngine.autoRename(target).toString(), 40);

		JPanel top = new JPanel(new BorderLayout(0, 4));
		top.add(new JLabel("New name:"), BorderLayout.NORTH);
		top.add(field, BorderLayout.CENTER);

		final Path[] result = new Path[1];

		JButton ok = new JButton("OK");
		JButton cancel = new JButton("Cancel");
		ok.addActionListener(e -> {
			String text = field.getText() == null ? "" : field.getText().trim();
			if (text.isEmpty()) {
				return;
			}
			try {
				result[0] = Path.of(text);
				dialog.dispose();
			} catch (RuntimeException ex) {
				log.debug("Invalid rename target [{}]: {}", text, ex.getMessage());
			}
		});
		cancel.addActionListener(e -> dialog.dispose());

		dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttons.add(ok);
		buttons.add(cancel);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
		content.add(top, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.pack();
		dialog.setMinimumSize(new Dimension(480, dialog.getHeight()));
		dialog.setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(field::requestFocusInWindow);
		dialog.setVisible(true);

		return result[0];
	}

	private static JComponent detailRow(String label, Path path) {
		String size = "?";
		String when = "";
		try {
			BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			size = Long.toString(a.size());
			when = STAMP.format(a.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()));
		} catch (IOException e) {
			// Source/target may be unreadable; show what we can.
		}
		JPanel row = new JPanel(new BorderLayout(12, 0));
		row.add(new JLabel(label), BorderLayout.WEST);
		row.add(new JLabel(size + "   " + when, JLabel.RIGHT), BorderLayout.EAST);
		return row;
	}

	/**
	 * Wire Left/Right arrow keys to move focus across a row of buttons (wrapping at the ends),
	 * so the warning dialog is fully keyboard-navigable without reaching for Tab.
	 */
	private static void installArrowTraversal(JButton... buttons) {
		for (int i = 0; i < buttons.length; i++) {
			JButton self = buttons[i];
			JButton left = buttons[(i - 1 + buttons.length) % buttons.length];
			JButton right = buttons[(i + 1) % buttons.length];

			self.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("LEFT"), "focusLeft");
			self.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("RIGHT"), "focusRight");
			self.getActionMap().put("focusLeft", new AbstractAction() {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(ActionEvent e) {
					left.requestFocusInWindow();
				}
			});
			self.getActionMap().put("focusRight", new AbstractAction() {
				private static final long serialVersionUID = 1L;

				@Override
				public void actionPerformed(ActionEvent e) {
					right.requestFocusInWindow();
				}
			});
		}
	}

	private static void runOnEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (Exception e) {
			log.warn("Failed to run copy conflict dialog on EDT: {}", e.getMessage(), e);
		}
	}
}
