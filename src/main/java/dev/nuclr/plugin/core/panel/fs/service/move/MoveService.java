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
package dev.nuclr.plugin.core.panel.fs.service.move;

import static dev.nuclr.plugin.core.panel.fs.FilePanelPayloadKeys.RESULT_REFRESH;
import static dev.nuclr.plugin.core.panel.fs.FilePanelPayloadKeys.RESULT_REFRESH_PATHS;
import static dev.nuclr.plugin.core.panel.fs.FilePanelPayloadKeys.RESULT_REFRESH_SELECTED_RESOURCE;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.FileNuclrResource;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import dev.nuclr.plugin.core.panel.fs.service.Alerts;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin-level entry point for the F6 rename/move action. Gathers the source selection and the
 * destination (the receiving panel's current folder), drives the setup / conflict / progress
 * dialogs, and delegates the actual filesystem work to {@link MoveEngine}. Mirrors
 * {@link dev.nuclr.plugin.core.panel.fs.service.CopyService}.
 */
@Slf4j
public class MoveService {

	private static final String DialogTitle = "Rename/Move";

	/**
	 * Move the selected (or focused) resources to {@code currentFolder} (or, for a single source
	 * renamed in the dialog, to the explicit path the user typed).
	 *
	 * @param currentFolder     the destination directory (the receiving panel's folder)
	 * @param selectedResources marked resources to move; used when non-empty
	 * @param focusedResource   the cursor item, used when nothing is marked
	 * @param data              event payload; {@code result.refresh} is set on success so the
	 *                          initiating (source) panel reloads after items move out of it
	 * @param callback          the commander progress bridge (unused — the plugin owns its UI)
	 * @param context           plugin context, used to build the resource the panel should focus
	 *                          after a single-item rename
	 * @return {@code true} when the move completed, otherwise {@code false}
	 */
	public boolean move(NuclrResource currentFolder, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback, NuclrPluginContext context) {

		Path destination = currentFolder != null ? currentFolder.getPath() : null;
		if (destination == null) {
			Alerts.showError(context, DialogTitle, "The destination is not a folder.");
			return false;
		}

		List<Path> sources = collectSources(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			Alerts.showError(context, DialogTitle, "There is nothing to move.");
			return false;
		}

		// For a single item, pre-fill the full target path (folder + name) so the user can rename
		// it in place; for several, just the folder (each keeps its own name).
		Path prefillTarget = sources.size() == 1 ? destination.resolve(sources.get(0).getFileName()) : null;
		String prefill = prefillTarget != null
				? prefillTarget.toString()
				: destination.toString() + File.separator;

		MoveOptions options = MoveDialog.show(header(sources), prefill, context);
		if (options == null) {
			SoundEvents.cancel(context);
			return false; // cancelled
		}

		MoveTarget target = resolveTarget(options.getDestination() != null ? options.getDestination() : destination,
				options.isMoveIntoFolder(), sources, prefillTarget);
		options.setDestination(target.destination());
		boolean explicitTarget = target.explicit();

		if (sources.stream().allMatch(source -> MoveEngine.isSelfMove(source,
				explicitTarget ? target.destination() : target.destination().resolve(source.getFileName())))) {
			return false; // every item would stay exactly where it is: nothing to move
		}

		MoveConflictDialog conflictDialog = new MoveConflictDialog(context);
		AtomicBoolean completed = new AtomicBoolean(false);

		MoveProgressDialog.run(progress -> {
			MoveEngine engine = new MoveEngine(options, progress, conflictDialog, (src, e) -> {
				SoundEvents.error(context);
				return true;
			}, explicitTarget);
			completed.set(engine.move(sources));
		}, context);

		if (!completed.get()) {
			return false;
		}
		SoundEvents.processComplete(context);

		// Publish every source parent and the effective destination through result.refresh.paths.
		// Unlike copy, a move also asks the initiating source pane to refresh directly.
		if (data != null) {
			Path effectiveDestination = explicitTarget ? options.getDestination().getParent() : options.getDestination();
			putRefreshResults(data, sources, effectiveDestination);

			// For a single-item rename in place, put the cursor back on the renamed entry
			// after the refresh. Only when it lands in the same folder being reloaded (an
			// actual rename, not a move into another directory) and the file now exists.
			if (explicitTarget && sources.size() == 1 && context != null) {
				Path renamed = options.getDestination();
				Path sourceParent = sources.get(0).getParent();
				if (renamed != null && Files.exists(renamed)
						&& renamed.getParent() != null && renamed.getParent().equals(sourceParent)) {
					try {
						data.put(RESULT_REFRESH_SELECTED_RESOURCE, new FileNuclrResource(context, renamed));
					} catch (RuntimeException e) {
						log.debug("Could not build renamed resource for focus: {}", e.getMessage());
					}
				}
			}
		}
		return true;
	}

	/**
	 * Where a move goes: {@code destination} is the exact new path of the single source when
	 * {@code explicit}, otherwise a folder each source is moved into under its own name.
	 */
	record MoveTarget(Path destination, boolean explicit) {
	}

	/**
	 * Interpret the destination typed in the dialog.
	 *
	 * <ul>
	 * <li>A relative path is resolved against the sources' own folder, so a bare name renames in
	 * place and {@code sub\name} or {@code ..\name} are relative to the file, not the app's working
	 * directory.</li>
	 * <li>A trailing separator, or several sources, always names a folder to move into.</li>
	 * <li>For a single source, the unchanged pre-filled path, the source's own path, or a case-only
	 * change of its name is used verbatim (merging into a same-named folder rather than nesting
	 * inside it). Any other existing folder is moved into; anything else is the new path.</li>
	 * </ul>
	 */
	static MoveTarget resolveTarget(Path typed, boolean intoFolder, List<Path> sources, Path prefillTarget) {

		Path sourceFolder = sources.isEmpty() ? null : sources.get(0).getParent();
		Path resolved = typed.isAbsolute() || sourceFolder == null
				? typed.normalize()
				: sourceFolder.resolve(typed).normalize();

		if (sources.size() != 1 || intoFolder) {
			return new MoveTarget(resolved, false);
		}
		Path source = sources.get(0);
		if (MoveEngine.sameLocation(resolved, source) || MoveEngine.isCaseOnlyRename(source, resolved)
				|| (prefillTarget != null && MoveEngine.sameLocation(resolved, prefillTarget))) {
			return new MoveTarget(resolved, true);
		}
		return new MoveTarget(resolved, !Files.isDirectory(resolved));
	}

	static void putRefreshResults(Map<String, Object> data, List<Path> sources, Path destination) {
		if (data == null) {
			return;
		}
		data.put(RESULT_REFRESH, true);
		var refreshPaths = new java.util.LinkedHashSet<Path>();
		if (sources != null) {
			sources.stream().map(Path::getParent).filter(java.util.Objects::nonNull).forEach(refreshPaths::add);
		}
		if (destination != null) {
			refreshPaths.add(destination);
		}
		data.put(RESULT_REFRESH_PATHS, List.copyOf(refreshPaths));
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
				continue; // never move the parent navigation entry
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
