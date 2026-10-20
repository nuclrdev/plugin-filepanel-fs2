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

import static dev.nuclr.plugin.core.panel.fs.Messages.tr;

import java.awt.KeyboardFocusManager;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
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
		content.add(new JLabel(tr("create.file.name")), java.awt.BorderLayout.NORTH);
		content.add(fileName, java.awt.BorderLayout.CENTER);

		// JOptionPane hands the initial focus to its default button through selectInitialValue(),
		// called once the dialog is shown - after any focus request made earlier, which is why a
		// HierarchyListener lost the race and keystrokes went nowhere. Take that hook instead.
		JOptionPane pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION) {
			private static final long serialVersionUID = 1L;

			@Override
			public void selectInitialValue() {
				fileName.requestFocusInWindow();
				fileName.selectAll();
			}
		};
		JDialog dialog = pane.createDialog(
				KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow(), tr("create.file.title"));
		dialog.setVisible(true);
		dialog.dispose();
		if (!Integer.valueOf(JOptionPane.OK_OPTION).equals(pane.getValue())) {
			return null;
		}
		return fileName.getText();
	}
}
