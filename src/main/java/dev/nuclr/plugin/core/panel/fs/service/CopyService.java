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

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

		try (TemporarySources materialized = collectSources(selectedResources, focusedResource)) {
			List<Path> sources = materialized.paths();
			if (sources.isEmpty()) {
				Alerts.showError(context, DialogTitle, "There is nothing to copy.");
				return false;
			}

			CopyOptions options = CopyDialog.show(header(sources), destination, context);
			if (options == null) {
				SoundEvents.cancel(context);
				return false; // cancelled
			}
			if (options.getDestination() == null) options.setDestination(destination);
			boolean destinationExisted = Files.exists(options.getDestination());
			boolean destinationIsTarget = sources.size() == 1 && !Files.isDirectory(options.getDestination());
			CopyConflictDialog conflictDialog = new CopyConflictDialog(context);
			AtomicBoolean completed = new AtomicBoolean(false);

			CopyProgressDialog.run(progress -> {
				CopyEngine engine = new CopyEngine(options, progress, conflictDialog, (src, e) -> { SoundEvents.error(context); return true; });
				completed.set(engine.copy(sources));
			}, context);

			if (completed.get()) {
				SoundEvents.processComplete(context);
				Path refreshDirectory = destinationIsTarget ? options.getDestination().getParent() : options.getDestination();
				var refreshDirectories = new java.util.LinkedHashSet<Path>();
				if (refreshDirectory != null) refreshDirectories.add(refreshDirectory);
				if (!destinationExisted && !destinationIsTarget && options.getDestination().getParent() != null) refreshDirectories.add(options.getDestination().getParent());
				putRefreshPaths(data, refreshDirectories);
			}
			return completed.get();
		}
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
		return copyInto(currentFolder, regularFiles(clipboardPaths), context);
	}

	/**
	 * Copy files and folders dropped from another application into {@code targetFolder}.
	 * Like paste there is no setup dialog; conflicts still prompt, the transfer remains
	 * cancellable, and a source dropped into its own folder is copied beside itself.
	 *
	 * @return {@code true} when a copy run completed, {@code false} when there
	 *         was nothing valid to copy or the operation was cancelled
	 */
	public boolean dropFiles(NuclrResource targetFolder, List<Path> droppedPaths, NuclrPluginContext context) {
		return copyInto(targetFolder, existingPaths(droppedPaths), context);
	}

	private boolean copyInto(NuclrResource folder, List<Path> sources, NuclrPluginContext context) {

		Path destination = folder != null ? folder.getPath() : null;
		if (destination == null || !Files.isDirectory(destination)) {
			Alerts.showError(context, DialogTitle, "The destination is not a folder.");
			return false;
		}

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

	/**
	 * Return normalized, distinct paths that exist (files, folders or links) from an untrusted
	 * dropped path list. A link is kept even when its target is gone; the engine copies links.
	 */
	public static List<Path> existingPaths(List<Path> paths) {
		if (paths == null || paths.isEmpty()) {
			return List.of();
		}
		return paths.stream()
				.filter(path -> path != null && Files.exists(path, LinkOption.NOFOLLOW_LINKS))
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
	private static TemporarySources collectSources(List<NuclrResource> selectedResources, NuclrResource focusedResource) {

		List<NuclrResource> chosen = new ArrayList<>();
		if (selectedResources != null && !selectedResources.isEmpty()) {
			chosen.addAll(selectedResources);
		} else if (focusedResource != null) {
			chosen.add(focusedResource);
		}

		TemporarySources sources = new TemporarySources();
		for (NuclrResource resource : chosen) {
			if (resource == null) continue;
			if ("..".equals(resource.getName())) {
				continue; // never copy the parent navigation entry
			}
			if (resource.getPath() != null) {
				sources.paths.add(resource.getPath());
				continue;
			}
			try (InputStream input = resource.openInputStream()) {
				if (input == null) continue;
				Path directory = sources.directory();
				Path target = directory.resolve(exportFileName(resource.getName()));
				Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
				sources.paths.add(target);
			} catch (UnsupportedOperationException ignored) {
				// A synthetic navigation/resource entry with no stream remains non-copyable.
			} catch (Exception e) {
				log.warn("Unable to materialize virtual copy source {}: {}", resource.getName(), e.getMessage());
			}
		}
		return sources;
	}

	private static String exportFileName(String name) {
		String candidate = name == null || name.isBlank() ? "export" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
		return candidate.lastIndexOf('.') > 0 ? candidate : candidate + ".csv";
	}

	/** Paths spooled from path-less resources, cleaned on success, cancellation, or failure. */
	private static final class TemporarySources implements AutoCloseable {
		private final List<Path> paths = new ArrayList<>();
		private Path directory;
		List<Path> paths() { return paths; }
		Path directory() throws IOException { if (directory == null) directory = Files.createTempDirectory("nuclr-virtual-copy-"); return directory; }
		@Override public void close() {
			if (directory == null) return;
			try (var files = Files.list(directory)) { files.forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException e) { log.debug("Could not remove temporary virtual copy source {}", path, e); } }); }
			catch (IOException e) { log.debug("Could not enumerate temporary virtual copy sources", e); }
			try { Files.deleteIfExists(directory); } catch (IOException e) { log.debug("Could not remove temporary virtual copy directory {}", directory, e); }
		}
	}

	private static String header(List<Path> sources) {
		if (sources.size() == 1) {
			Path name = sources.get(0).getFileName();
			return name != null ? name.toString() : sources.get(0).toString();
		}
		return sources.size() + " items";
	}
}
