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
package dev.nuclr.plugin.core.panel.fs;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.Path;
import java.text.DecimalFormat;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.panel.fs.DriveInfoService.DriveInfo;
import lombok.extern.slf4j.Slf4j;

/**
 * Modal "Information" popup showing the current drive's space, label and serial number.
 * Values are rendered into a read-only but selectable/copyable text area. Dismissable via
 * the window's close (X), the OK button, or ESC.
 */
@Slf4j
final class DriveInfoDialog {

	private static final String TITLE = "Information";
	private static final long GB = 1024L * 1024L * 1024L;
	private static final long MB = 1024L * 1024L;
	private static final DecimalFormat GROUPED = new DecimalFormat("#,##0");
	private static final String DASH = "—"; // em dash for unknown values

	private DriveInfoDialog() {
	}

	/** Inspect {@code path} and show the info dialog on the EDT. Safe to call from any thread. */
	static void show(Path path) {
		if (path == null) {
			return;
		}
		DriveInfo info = DriveInfoService.inspect(path);
		SwingUtilities.invokeLater(() -> render(info));
	}

	private static void render(DriveInfo info) {

		Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		JDialog dialog = new JDialog(owner, TITLE, JDialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JTextArea area = new JTextArea(format(info));
		area.setEditable(false);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
		area.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		area.setCaretPosition(0);

		JScrollPane scroll = new JScrollPane(area);
		scroll.setBorder(BorderFactory.createEmptyBorder());

		JButton ok = new JButton("OK");
		ok.addActionListener(e -> dialog.dispose());

		JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
		buttons.add(ok);

		JPanel content = new JPanel(new BorderLayout(0, 12));
		content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
		content.add(scroll, BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);

		// ESC dismisses.
		dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
				KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

		dialog.setContentPane(content);
		dialog.getRootPane().setDefaultButton(ok);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		SwingUtilities.invokeLater(ok::requestFocusInWindow);
		dialog.setVisible(true);
	}

	/** Aligned "label   value" block for the drive. */
	private static String format(DriveInfo info) {

		String total = info.totalBytes() > 0
				? sizeGb(info.totalBytes()) + "  (" + GROUPED.format(info.totalBytes()) + " bytes)"
				: DASH;

		String available;
		if (info.totalBytes() > 0) {
			int pct = info.availablePercent();
			available = pct + "%, " + sizeGb(info.usableBytes())
					+ "  (" + GROUPED.format(info.usableBytes()) + " bytes)";
		} else {
			available = DASH;
		}

		StringBuilder sb = new StringBuilder();
		row(sb, "Drive", info.path() != null ? info.path().toString() : DASH);
		row(sb, "Space, total", total);
		row(sb, "Space, available", available);
		row(sb, "Volume label", nvl(info.label()));
		row(sb, "Serial number", nvl(info.serialNumber()));
		return sb.toString().stripTrailing();
	}

	private static void row(StringBuilder sb, String label, String value) {
		// Left-pad the label column to a fixed width so values line up.
		sb.append(String.format("%-20s %s%n", label, value));
	}

	/** Format bytes as whole GB (falling back to MB for small volumes), matching Windows' 1024-based "GB". */
	private static String sizeGb(long bytes) {
		if (bytes >= GB) {
			return GROUPED.format(Math.round(bytes / (double) GB)) + " GB";
		}
		return GROUPED.format(Math.round(bytes / (double) MB)) + " MB";
	}

	private static String nvl(String s) {
		return s == null ? DASH : s;
	}
}
