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

import static dev.nuclr.plugin.core.panel.fs.FilePanelPayloadKeys.RESULT_REFRESH_PATHS;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import dev.nuclr.plugin.core.panel.fs.service.CopyEngine.Action;
import dev.nuclr.plugin.core.panel.fs.service.CopyEngine.Resolution;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin-level entry point for the F5 copy action. Gathers the source selection and the
 * destination (the receiving panel's current folder), drives the setup / conflict / progress
 * dialogs, and delegates the actual filesystem work to {@link CopyEngine}.
 */
@Slf4j
public class CopyService {

	private static final String DialogTitle = "Copy";

	/**
	 * Copy the selected (or focused) resources into {@code currentFolder}.
	 *
	 * @param currentFolder     the destination directory (the receiving panel's folder)
	 * @param selectedResources marked resources to copy; used when non-empty
	 * @param focusedResource   the cursor item, used when nothing is marked
	 * @param data              event payload; receives {@code result.refresh.paths} on success
	 * @param callback          the commander progress bridge (unused — the plugin owns its UI)
	 * @return {@code true} when the copy completed, otherwise {@code false}
	 */
	public boolean copy(NuclrResource currentFolder, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback) {
		return copy(currentFolder, selectedResources, focusedResource, data, callback, null);
	}

	public boolean copy(NuclrResource currentFolder, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback, NuclrPluginContext context) {

		Path destination = currentFolder != null ? currentFolder.getPath() : null;
		if (destination == null || !Files.isDirectory(destination)) {
			Alerts.showError(context, DialogTitle, "The destination is not a folder.");
			return false;
		}

		List<Path> sources = collectSources(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			Alerts.showError(context, DialogTitle, "There is nothing to copy.");
			return false;
		}

		CopyOptions options = CopyDialog.show(header(sources), destination, context);
		if (options == null) {
			SoundEvents.cancel(context);
			return false; // cancelled
		}
		if (options.getDestination() == null) {
			options.setDestination(destination);
		}
		boolean destinationExisted = Files.exists(options.getDestination());
		boolean destinationIsTarget = sources.size() == 1 && !Files.isDirectory(options.getDestination());

		CopyConflictDialog conflictDialog = new CopyConflictDialog(context);
		AtomicBoolean completed = new AtomicBoolean(false);

		CopyProgressDialog.run(progress -> {
			CopyEngine engine = new CopyEngine(options, progress, conflictDialog, (src, e) -> {
				SoundEvents.error(context);
				return true;
			});
			completed.set(engine.copy(sources));
		}, context);

		if (completed.get()) {
			SoundEvents.processComplete(context);
			Path refreshDirectory = destinationIsTarget ? options.getDestination().getParent() : options.getDestination();
			var refreshDirectories = new java.util.LinkedHashSet<Path>();
			if (refreshDirectory != null) {
				refreshDirectories.add(refreshDirectory);
			}
			// A multi-source copy may create a destination directory. Its parent must
			// refresh as well so the newly-created folder appears immediately.
			if (!destinationExisted && !destinationIsTarget && options.getDestination().getParent() != null) {
				refreshDirectories.add(options.getDestination().getParent());
			}
			putRefreshPaths(data, refreshDirectories);
		}

		// The handling plugin publishes result.refresh.paths to every panel showing the actual
		// destination. It does not refresh the initiating source panel because copying leaves it unchanged.
		return completed.get();
	}

	static void putRefreshPaths(Map<String, Object> data, Iterable<Path> destinations) {
		if (data == null || destinations == null) {
			return;
		}
		var paths = new java.util.LinkedHashSet<Path>();
		for (Path destination : destinations) {
			if (destination != null) {
				paths.add(destination);
			}
		}
		if (paths.isEmpty()) {
			return;
		}
		try {
			data.put(RESULT_REFRESH_PATHS, List.copyOf(paths));
		} catch (UnsupportedOperationException ignored) {
			// Optional host coordination; the directory watcher still observes the change.
		}
	}

	/**
	 * Copy regular files received from the system clipboard directly into the
	 * panel's current directory. Unlike F5 copy, paste does not show the
	 * destination setup dialog; existing-target conflicts still use the normal
	 * conflict prompt and the transfer remains cancellable.
	 *
	 * @return {@code true} when a copy run completed, {@code false} when there
	 *         was nothing valid to copy or the operation was cancelled
	 */
	public boolean pasteFiles(NuclrResource currentFolder, List<Path> clipboardPaths,
			NuclrPluginContext context) {

		Path destination = currentFolder != null ? currentFolder.getPath() : null;
		if (destination == null || !Files.isDirectory(destination)) {
			Alerts.showError(context, DialogTitle, "The destination is not a folder.");
			return false;
		}

		List<Path> sources = regularFiles(clipboardPaths);
		if (sources.isEmpty()) {
			return false;
		}

		CopyOptions options = new CopyOptions();
		options.setDestination(destination);
		options.setConflictMode(CopyOptions.ConflictMode.ASK);

		CopyConflictDialog conflictDialog = new CopyConflictDialog(context);
		AtomicBoolean completed = new AtomicBoolean(false);

		CopyProgressDialog.run(progress -> {
			CopyEngine.ConflictResolver resolver = (source, target) -> isSameFile(source, target)
					? Resolution.of(Action.RENAME)
					: conflictDialog.resolve(source, target);
			CopyEngine engine = new CopyEngine(options, progress, resolver, (src, e) -> {
				SoundEvents.error(context);
				return true;
			});
			completed.set(engine.copy(sources));
		}, context);

		if (completed.get()) {
			SoundEvents.processComplete(context);
		}
		return completed.get();
	}

	/** Return normalized, distinct regular files from an untrusted clipboard path list. */
	public static List<Path> regularFiles(List<Path> paths) {
		if (paths == null || paths.isEmpty()) {
			return List.of();
		}
		return paths.stream()
				.filter(path -> path != null && Files.isRegularFile(path))
				.map(path -> path.toAbsolutePath().normalize())
				.distinct()
				.toList();
	}

	private static boolean isSameFile(Path source, Path target) {
		try {
			return Files.isSameFile(source, target);
		} catch (java.io.IOException e) {
			return source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize());
		}
	}

	/** Resolve the resources to act on: marked selection if present, otherwise the cursor item. */
	private static List<Path> collectSources(List<NuclrResource> selectedResources, NuclrResource focusedResource) {

		List<NuclrResource> chosen = new ArrayList<>();
		if (selectedResources != null && !selectedResources.isEmpty()) {
			chosen.addAll(selectedResources);
		} else if (focusedResource != null) {
			chosen.add(focusedResource);
		}

		List<Path> paths = new ArrayList<>();
		for (NuclrResource resource : chosen) {
			if (resource == null || resource.getPath() == null) {
				continue;
			}
			if ("..".equals(resource.getName())) {
				continue; // never copy the parent navigation entry
			}
			paths.add(resource.getPath());
		}
		return paths;
	}

	private static String header(List<Path> sources) {
		if (sources.size() == 1) {
			Path name = sources.get(0).getFileName();
			return name != null ? name.toString() : sources.get(0).toString();
		}
		return sources.size() + " items";
	}
}
