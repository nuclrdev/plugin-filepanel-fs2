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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MakeNewFolderService {

	private static final String DialogTitle = "Make Folder";

	public static Path makeNewFolder(NuclrResource currentFolder, NuclrPluginCallback callback) {

		if (currentFolder == null || currentFolder.getPath() == null) {
			Alerts.showError(DialogTitle, "No folder is open.");
			return null;
		}

		Path parent = currentFolder.getPath();
		if (!Files.isDirectory(parent)) {
			Alerts.showError(DialogTitle, "The current item is not a folder.");
			return null;
		}
		if (!Files.isWritable(parent)) {
			Alerts.showError(DialogTitle, "The current folder is not writable.");
			return null;
		}

		String folderName = promptFolderName();
		if (folderName == null) {
			return null;
		}
		folderName = folderName.trim();
		if (folderName.isBlank()) {
			return null;
		}
		if (isInvalidSingleFolderName(folderName)) {
			Alerts.showError(DialogTitle, "Folder name cannot contain path separators.");
			return null;
		}

		try {
			Path target = parent.resolve(folderName);
			if (Files.exists(target)) {
				Alerts.showError(DialogTitle, "A file or folder with that name already exists.");
				return null;
			}
			if (callback != null) {
				callback.onStart("Creating folder " + folderName);
			}
			Files.createDirectory(target);
			if (callback != null) {
				callback.onComplete();
			}
			return target;
		} catch (InvalidPathException | IOException | UnsupportedOperationException e) {
			log.warn("Failed to create folder [{}] in [{}]: {}", folderName, parent, e.getMessage(), e);
			if (callback != null) {
				callback.onError(folderName, e instanceof Exception ex ? ex : new IOException(e));
			}
			Alerts.showError(DialogTitle, (e.getMessage() != null ? e.getMessage() : "Could not create folder."));
			return null;
		}
	}

	private static boolean isInvalidSingleFolderName(String folderName) {
		return folderName.equals(".")
				|| folderName.equals("..")
				|| folderName.indexOf('/') >= 0
				|| folderName.indexOf('\\') >= 0
				|| folderName.indexOf('\0') >= 0;
	}

	private static String promptFolderName() {
		final String[] result = new String[1];
		Alerts.runOnEdtAndWait(() -> result[0] = JOptionPane.showInputDialog(null, "Folder name:", DialogTitle,
				JOptionPane.PLAIN_MESSAGE));
		return result[0];
	}

}
