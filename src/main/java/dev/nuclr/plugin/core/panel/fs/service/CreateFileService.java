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

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/** Creates a zero-byte local file after prompting for its name. */
@Slf4j
public final class CreateFileService {

	private static final String DIALOG_TITLE = "Create file";

	private CreateFileService() {
	}

	public static Path createFile(NuclrResource currentFolder, NuclrPluginCallback callback,
			NuclrPluginContext context) {
		Path parent = currentFolder == null ? null : currentFolder.getPath();
		if (parent == null || !Files.isDirectory(parent)) {
			Alerts.showError(context, DIALOG_TITLE, "No folder is open.");
			return null;
		}
		if (!Files.isWritable(parent)) {
			Alerts.showError(context, DIALOG_TITLE, "The current folder is not writable.");
			return null;
		}

		final String[] entered = new String[1];
		SoundEvents.popup(context);
		Alerts.runOnEdtAndWait(() -> entered[0] = CreateFileDialog.showDialog());
		if (entered[0] == null) {
			SoundEvents.cancel(context);
			return null;
		}

		try {
			return create(parent, entered[0], callback);
		} catch (IllegalArgumentException e) {
			Alerts.showError(context, DIALOG_TITLE, e.getMessage());
			return null;
		} catch (IOException | UnsupportedOperationException e) {
			String name = entered[0].trim();
			log.warn("Failed to create file [{}] in [{}]: {}", name, parent, e.getMessage(), e);
			if (callback != null) {
				callback.onError(name, e instanceof Exception ex ? ex : new IOException(e));
			}
			Alerts.showError(context, DIALOG_TITLE,
					e.getMessage() != null ? e.getMessage() : "Could not create file.");
			return null;
		}
	}

	static Path create(Path parent, String enteredName, NuclrPluginCallback callback) throws IOException {
		String name = enteredName == null ? "" : enteredName.trim();
		if (name.isBlank()) {
			throw new IllegalArgumentException("File name is required.");
		}
		if (isInvalidSingleFileName(name)) {
			throw new IllegalArgumentException("File name cannot contain path separators.");
		}

		Path target;
		try {
			target = parent.resolve(name);
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException("The file name is not valid.", e);
		}
		if (Files.exists(target)) {
			throw new IllegalArgumentException("A file or folder with that name already exists.");
		}

		if (callback != null) {
			callback.onStart("Creating file " + name);
		}
		Files.createFile(target);
		if (callback != null) {
			callback.onComplete();
		}
		return target;
	}

	static boolean isInvalidSingleFileName(String name) {
		return name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
				|| name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0;
	}
}
