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

	private CreateFileService() {
	}

	public static Path createFile(NuclrResource currentFolder, NuclrPluginCallback callback,
			NuclrPluginContext context) {
		Path parent = currentFolder == null ? null : currentFolder.getPath();
		if (parent == null || !Files.isDirectory(parent)) {
			Alerts.showError(context, tr("create.file.title"), tr("create.no.folder"));
			return null;
		}
		if (!Files.isWritable(parent)) {
			Alerts.showError(context, tr("create.file.title"), tr("create.folder.not.writable"));
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
			Alerts.showError(context, tr("create.file.title"), e.getMessage());
			return null;
		} catch (IOException | UnsupportedOperationException e) {
			String name = entered[0].trim();
			log.warn("Failed to create file [{}] in [{}]: {}", name, parent, e.getMessage(), e);
			if (callback != null) {
				callback.onError(name, e instanceof Exception ex ? ex : new IOException(e));
			}
			Alerts.showError(context, tr("create.file.title"),
					e.getMessage() != null ? e.getMessage() : tr("create.file.failed"));
			return null;
		}
	}

	static Path create(Path parent, String enteredName, NuclrPluginCallback callback) throws IOException {
		String name = enteredName == null ? "" : enteredName.trim();
		if (name.isBlank()) {
			throw new IllegalArgumentException(tr("create.name.required"));
		}
		if (isInvalidSingleFileName(name)) {
			throw new IllegalArgumentException(tr("create.name.separators"));
		}

		Path target;
		try {
			target = parent.resolve(name);
		} catch (InvalidPathException e) {
			throw new IllegalArgumentException(tr("create.name.invalid"), e);
		}
		if (Files.exists(target)) {
			throw new IllegalArgumentException(tr("create.name.exists"));
		}

		if (callback != null) {
			callback.onStart(tr("create.file.progress", name));
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
