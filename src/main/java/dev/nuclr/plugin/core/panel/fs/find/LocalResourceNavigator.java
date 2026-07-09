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
package dev.nuclr.plugin.core.panel.fs.find;

import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiFunction;

import javax.swing.JFileChooser;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/**
 * Local-filesystem implementation of the Find File dialog's navigator collaborators
 * ({@link ResourceBrowser} + {@link ResourcePathParser}). Supplied by
 * {@code LocalFileSystemPlugin}, so the dialog stays plugin-agnostic; an SFTP or archive
 * panel would supply its own transport-aware navigator instead.
 *
 * <p>{@code Browse…} presents a local directory picker; the custom-path field is parsed
 * as a plain filesystem path. Plugin URIs ({@code sftp://…}) are not local and resolve to
 * empty here — the panel that owns those URIs is responsible for parsing them.
 */
@Slf4j
public final class LocalResourceNavigator implements ResourceBrowser, ResourcePathParser {

	private final NuclrPluginContext context;
	private final BiFunction<NuclrPluginContext, Path, NuclrResource> resourceFactory;

	public LocalResourceNavigator(NuclrPluginContext context,
			BiFunction<NuclrPluginContext, Path, NuclrResource> resourceFactory) {
		this.context = context;
		this.resourceFactory = resourceFactory;
	}

	@Override
	public Optional<NuclrResource> browse(Window owner, NuclrResource start) {
		JFileChooser chooser = new JFileChooser();
		chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		chooser.setDialogTitle("Choose a folder to search");
		if (start != null && start.getPath() != null) {
			File dir = start.getPath().toFile();
			chooser.setCurrentDirectory(dir.isDirectory() ? dir : dir.getParentFile());
		}
		SoundEvents.popup(context);
		int result = chooser.showOpenDialog(owner);
		if (result != JFileChooser.APPROVE_OPTION) {
			SoundEvents.cancel(context);
			return Optional.empty();
		}
		File selected = chooser.getSelectedFile();
		if (selected == null) {
			SoundEvents.cancel(context);
			return Optional.empty();
		}
		SoundEvents.confirmation(context);
		return Optional.of(resourceFactory.apply(context, selected.toPath()));
	}

	@Override
	public Optional<NuclrResource> parse(String pathOrUri) {
		if (pathOrUri == null || pathOrUri.isBlank()) {
			return Optional.empty();
		}
		String text = pathOrUri.trim();
		// A scheme-qualified URI (sftp://, zip:/…) is not a local path; defer to the owning plugin.
		if (text.matches("(?i)^[a-z][a-z0-9+.\\-]*://.*")) {
			log.debug("Custom path {} is a plugin URI, not handled by the local navigator", text);
			return Optional.empty();
		}
		try {
			Path path = Path.of(text);
			if (!Files.exists(path)) {
				return Optional.empty();
			}
			return Optional.of(resourceFactory.apply(context, path));
		} catch (InvalidPathException e) {
			log.debug("Invalid custom path {}: {}", text, e.getMessage());
			return Optional.empty();
		}
	}
}
