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

import java.awt.Component;
import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles the Ctrl+C ({@code clipboard.copy}) action for the local filesystem
 * panel by popping up a small, keyboard-navigable menu in the middle of the
 * screen that lets the user choose what goes onto the system clipboard:
 *
 * <ul>
 *   <li><b>Copy full path to clipboard</b> &mdash; the absolute path(s) as text.
 *       A {@code ".."} entry contributes the currently opened folder's path
 *       (the {@code ".."} pseudo-entry has no path of its own).</li>
 *   <li><b>Copy selected files/folders to clipboard</b> &mdash; the actual files
 *       as {@link DataFlavor#javaFileListFlavor}, so they can be pasted into the
 *       OS file manager. Disabled when the selection includes a {@code ".."}
 *       entry, which has no real file to copy.</li>
 * </ul>
 *
 * <p>The popup is dismissed without doing anything when the user presses Escape;
 * the arrow keys move between items and Enter activates the highlighted one
 * (standard {@link JPopupMenu} behaviour).
 */
@Slf4j
public final class ClipboardService {

	private ClipboardService() {
	}

	/** {@code true} if the resource is the synthetic ".." parent entry. */
	private static boolean isParent(NuclrResource resource) {
		return resource != null && "..".equals(resource.getName());
	}

	/**
	 * Show the clipboard-copy popup for {@code resources}. Does nothing when the
	 * list is empty. Always runs on the EDT.
	 *
	 * @param resources    the files/folders the action applies to (the marked
	 *                     entries, or the cursor entry when nothing is marked)
	 * @param currentFolder the folder currently open in the panel; used as the
	 *                     full path for any {@code ".."} entry in {@code resources}
	 */
	public static void showClipboardMenu(List<NuclrResource> resources, NuclrResource currentFolder) {

		if (resources == null || resources.isEmpty()) {
			return;
		}

		Alerts.runOnEdtAndWait(() -> showMenu(resources, currentFolder));
	}

	private static void showMenu(List<NuclrResource> resources, NuclrResource currentFolder) {

		// "Copy selected files/folders" needs real files, so disable it when the
		// selection contains the ".." entry (which has no file of its own).
		boolean containsParent = resources.stream().anyMatch(ClipboardService::isParent);

		var popup = new JPopupMenu();

		var copyPath = new javax.swing.JMenuItem("Copy full path to clipboard");
		copyPath.addActionListener(e -> copyFullPaths(resources, currentFolder));
		popup.add(copyPath);

		var copyFiles = new javax.swing.JMenuItem("Copy selected files/folders to clipboard");
		copyFiles.setEnabled(!containsParent);
		copyFiles.addActionListener(e -> copyFiles(resources));
		popup.add(copyFiles);

		Component invoker = resolveInvoker();
		if (invoker == null) {
			log.warn("No invoker component available to anchor the clipboard popup menu");
			return;
		}

		// Center the popup on the screen. show() takes invoker-relative coordinates,
		// so convert the screen-centered point back into the invoker's space.
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		Dimension menu = popup.getPreferredSize();
		Point location = new Point(
				Math.max(0, (screen.width - menu.width) / 2),
				Math.max(0, (screen.height - menu.height) / 2));
		SwingUtilities.convertPointFromScreen(location, invoker);
		popup.show(invoker, location.x, location.y);
	}

	/**
	 * Find a component to anchor the popup to: the currently focused component
	 * (the panel's table while the user is in it), falling back to the active
	 * window.
	 */
	private static Component resolveInvoker() {
		var kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
		Component focusOwner = kfm.getFocusOwner();
		if (focusOwner != null && focusOwner.isShowing()) {
			return focusOwner;
		}
		Window active = kfm.getActiveWindow();
		return active != null && active.isShowing() ? active : null;
	}

	/**
	 * Copy the absolute path of each resource, one per line. A ".." entry
	 * contributes the currently opened folder's path.
	 */
	public static void copyFullPaths(List<NuclrResource> resources, NuclrResource currentFolder) {
		if (resources == null || resources.isEmpty()) {
			return;
		}
		String text = resources.stream()
				.map(resource -> pathText(resource, currentFolder))
				.collect(Collectors.joining(System.lineSeparator()));
		setClipboardContents(new StringSelection(text));
	}

	private static String pathText(NuclrResource resource, NuclrResource currentFolder) {
		NuclrResource effective = isParent(resource) ? currentFolder : resource;
		if (effective == null) {
			return "";
		}
		Path path = effective.getPath();
		if (path != null) {
			return path.toAbsolutePath().toString();
		}
		return effective.getFullPath() != null ? effective.getFullPath() : effective.getName();
	}

	/** Copy the actual files/folders onto the clipboard as a Java file list. */
	public static void copyFiles(List<NuclrResource> resources) {
		if (resources == null || resources.isEmpty()) {
			return;
		}
		var files = new ArrayList<File>();
		for (var resource : resources) {
			if (isParent(resource) || resource.getPath() == null) {
				continue;
			}
			files.add(resource.getPath().toFile());
		}
		if (files.isEmpty()) {
			return;
		}
		setClipboardContents(new FileListTransferable(files));
	}

	private static void setClipboardContents(Transferable contents) {
		try {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(contents, null);
		} catch (RuntimeException e) {
			log.warn("Failed to set clipboard contents: {}", e.getMessage(), e);
		}
	}

	/** Minimal {@link Transferable} exposing a list of files via {@link DataFlavor#javaFileListFlavor}. */
	private record FileListTransferable(List<File> files) implements Transferable {

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] { DataFlavor.javaFileListFlavor };
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.javaFileListFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}
			return files;
		}
	}

}
