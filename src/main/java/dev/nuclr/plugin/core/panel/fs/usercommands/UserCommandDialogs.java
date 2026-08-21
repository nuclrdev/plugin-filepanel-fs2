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
import java.awt.Component;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.nio.file.Path;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import dev.nuclr.plugin.core.panel.fs.service.Alerts;
import lombok.extern.slf4j.Slf4j;

/**
 * The two small confirmations user commands need: "run this?" before an execution the user
 * marked as needing asking, and "delete this?" before an entry leaves the list.
 *
 * <p>Both follow the plugin's existing confirmation shape (see the delete flow): modal, two
 * buttons, the safe one focused and bound to Enter and Escape alike, and callable from any
 * thread — they marshal to the EDT and block for the answer.
 */
@Slf4j
final class UserCommandDialogs {

	private UserCommandDialogs() {
	}

	/**
	 * Ask before running a command that is configured to require it. Shows the command line
	 * itself, which is the point of the confirmation, plus the folder it will run in.
	 *
	 * @param command          the command about to run
	 * @param workingDirectory the folder it resolved to
	 * @param context          plugin context for the warning sound; may be {@code null}
	 * @return {@code true} to run it, {@code false} on Cancel or Escape
	 */
	static boolean confirmRun(UserCommand command, Path workingDirectory, NuclrPluginContext context) {

		JPanel message = new JPanel();
		message.setLayout(new BoxLayout(message, BoxLayout.Y_AXIS));

		JLabel question = new JLabel("Run this command?");
		question.setAlignmentX(Component.LEFT_ALIGNMENT);
		message.add(question);
		message.add(Box.createVerticalStrut(10));

		JTextArea commandText = plainText(command.command());
		commandText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, commandText.getFont().getSize()));
		commandText.setAlignmentX(Component.LEFT_ALIGNMENT);
		message.add(commandText);

		if (workingDirectory != null) {
			message.add(Box.createVerticalStrut(10));
			JTextArea folder = plainText("in  " + workingDirectory);
			folder.setAlignmentX(Component.LEFT_ALIGNMENT);
			message.add(folder);
		}

		SoundEvents.warning(context);
		boolean proceed = choose(command.name().isEmpty() ? "Run command" : command.name(), message, "Run", "Cancel");
		if (!proceed) {
			SoundEvents.cancel(context);
		}
		return proceed;
	}

	/**
	 * Ask before removing an entry from the list.
	 *
	 * @param command the entry about to be deleted
	 * @param context plugin context for the warning sound; may be {@code null}
	 * @return {@code true} to delete it, {@code false} on Cancel or Escape
	 */
	static boolean confirmDelete(UserCommand command, NuclrPluginContext context) {

		SoundEvents.warning(context);
		boolean proceed = choose("Delete user command",
				plainText("Delete user command \"" + command.name() + "\"?"), "Delete", "Cancel");
		if (!proceed) {
			SoundEvents.cancel(context);
		}
		return proceed;
	}

	private static JTextArea plainText(String text) {
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setBorder(null);
		return area;
	}

	/**
	 * Show a modal two-button dialog and return true if the {@code proceed} button was chosen.
	 * The {@code safe} button is the default and initially focused, and Escape or closing the
	 * window is equivalent to pressing it.
	 */
	private static boolean choose(String title, Component message, String proceedText, String safeText) {

		final boolean[] proceed = { false };

		Runnable show = () -> {
			Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
			JDialog dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

			JButton proceedButton = new JButton(proceedText);
			JButton safeButton = new JButton(safeText);

			proceedButton.addActionListener(e -> {
				proceed[0] = true;
				dialog.dispose();
			});
			safeButton.addActionListener(e -> {
				proceed[0] = false;
				dialog.dispose();
			});

			installArrowTraversal(proceedButton, proceedButton, safeButton);
			installArrowTraversal(safeButton, proceedButton, safeButton);

			dialog.getRootPane().registerKeyboardAction(e -> {
				proceed[0] = false;
				dialog.dispose();
			}, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

			JPanel content = new JPanel(new BorderLayout(0, 12));
			content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
			content.add(message, BorderLayout.CENTER);

			JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
			buttons.add(proceedButton);
			buttons.add(safeButton);
			content.add(buttons, BorderLayout.SOUTH);

			dialog.setContentPane(content);
			dialog.getRootPane().setDefaultButton(safeButton);
			dialog.pack();
			dialog.setLocationRelativeTo(owner);
			SwingUtilities.invokeLater(safeButton::requestFocusInWindow);
			dialog.setVisible(true); // blocks (modal) until disposed
		};

		Alerts.runOnEdtAndWait(show);
		return proceed[0];
	}

	/** Left arrow focuses the left button, Right arrow the right button (in addition to Tab). */
	private static void installArrowTraversal(JButton button, JButton left, JButton right) {
		button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("LEFT"), "focusLeft");
		button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("RIGHT"), "focusRight");
		button.getActionMap().put("focusLeft", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				left.requestFocusInWindow();
			}
		});
		button.getActionMap().put("focusRight", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				right.requestFocusInWindow();
			}
		});
	}
}
