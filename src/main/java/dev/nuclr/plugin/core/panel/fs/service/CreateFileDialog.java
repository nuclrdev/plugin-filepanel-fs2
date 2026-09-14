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

import java.awt.KeyboardFocusManager;
import java.awt.event.HierarchyEvent;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** The Shift+F4 file-name prompt. */
final class CreateFileDialog {

	private CreateFileDialog() {
	}

	static String showDialog() {
		JTextField fileName = new JTextField(36);
		fileName.setName("createFile.name");

		JPanel content = new JPanel(new java.awt.BorderLayout(0, 6));
		content.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		content.add(new JLabel("File name:"), java.awt.BorderLayout.NORTH);
		content.add(fileName, java.awt.BorderLayout.CENTER);

		fileName.addHierarchyListener(event -> {
			if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && fileName.isShowing()) {
				javax.swing.SwingUtilities.invokeLater(() -> {
					fileName.requestFocusInWindow();
					fileName.selectAll();
				});
			}
		});

		int choice = JOptionPane.showConfirmDialog(
				KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(),
				content, "Create file", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
		if (choice != JOptionPane.OK_OPTION) {
			return null;
		}
		return fileName.getText();
	}
}
