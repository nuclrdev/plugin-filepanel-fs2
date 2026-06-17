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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.FileNuclrResource;
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
	 */
	public void move(NuclrResource currentFolder, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback, NuclrPluginContext context) {

		Path destination = currentFolder != null ? currentFolder.getPath() : null;
		if (destination == null) {
			Alerts.showError(DialogTitle, "The destination is not a folder.");
			return;
		}

		List<Path> sources = collectSources(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			Alerts.showError(DialogTitle, "There is nothing to move.");
			return;
		}

		// For a single item, pre-fill the full target path (folder + name) so the user can rename
		// it in place; for several, just the folder (each keeps its own name).
		String prefill = sources.size() == 1
				? destination.resolve(sources.get(0).getFileName()).toString()
				: destination.toString() + File.separator;

		MoveOptions options = MoveDialog.show(header(sources), prefill);
		if (options == null) {
			return; // cancelled
		}
		if (options.getDestination() == null) {
			options.setDestination(destination);
		}

		// A single source whose typed destination is not an existing directory is a rename: the
		// destination path is used verbatim. Otherwise the destination is a folder to move into.
		boolean explicitTarget = sources.size() == 1 && !Files.isDirectory(options.getDestination());

		MoveConflictDialog conflictDialog = new MoveConflictDialog();

		MoveProgressDialog.run(progress -> {
			MoveEngine engine = new MoveEngine(options, progress, conflictDialog, (src, e) -> true, explicitTarget);
			engine.move(sources);
		});

		// The destination pane is reloaded by the "refresh.plugin.file.panel" event the handling
		// plugin emits for its own uuid. Unlike copy, a move also empties the source pane, so we
		// ask the *initiating* pane to refresh via result.refresh.
		if (data != null) {
			data.put("result.refresh", true);

			// For a single-item rename in place, put the cursor back on the renamed entry
			// after the refresh. Only when it lands in the same folder being reloaded (an
			// actual rename, not a move into another directory) and the file now exists.
			if (explicitTarget && sources.size() == 1 && context != null) {
				Path renamed = options.getDestination();
				Path sourceParent = sources.get(0).getParent();
				if (renamed != null && Files.exists(renamed)
						&& renamed.getParent() != null && renamed.getParent().equals(sourceParent)) {
					try {
						data.put("result.refresh.selected.resource", new FileNuclrResource(context, renamed));
					} catch (RuntimeException e) {
						log.debug("Could not build renamed resource for focus: {}", e.getMessage());
					}
				}
			}
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
