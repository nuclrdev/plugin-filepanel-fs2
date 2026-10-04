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
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.event.HierarchyEvent;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The file-name prompt shown when plain text is pasted into a folder. Shows a
 * short preview of the text; Enter saves, ESC cancels (standard
 * {@link JOptionPane} behaviour).
 */
final class PasteTextDialog {

	private PasteTextDialog() {
	}

	static String showDialog(String suggestedName, String preview) {
		JTextField fileName = new JTextField(suggestedName, 36);
		fileName.setName("pasteText.name");

		JTextArea previewArea = new JTextArea(preview);
		previewArea.setEditable(false);
		previewArea.setFocusable(false);
		previewArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fileName.getFont().getSize()));
		previewArea.setBackground(UIManager.getColor("Panel.background"));
		previewArea.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

		JPanel name = new JPanel(new BorderLayout(0, 6));
		name.add(new JLabel("Save clipboard text as:"), BorderLayout.NORTH);
		name.add(fileName, BorderLayout.CENTER);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		content.add(name, BorderLayout.NORTH);
		content.add(previewArea, BorderLayout.CENTER);

		fileName.addHierarchyListener(event -> {
			if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && fileName.isShowing()) {
				SwingUtilities.invokeLater(() -> {
					fileName.requestFocusInWindow();
					// Select the name but not the extension, so typing replaces just the name.
					int dot = suggestedName.lastIndexOf('.');
					fileName.select(0, dot > 0 ? dot : suggestedName.length());
				});
			}
		});

		int choice = JOptionPane.showConfirmDialog(
				KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),
				content, "Paste text", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return null;
		}
		return fileName.getText();
	}
}
