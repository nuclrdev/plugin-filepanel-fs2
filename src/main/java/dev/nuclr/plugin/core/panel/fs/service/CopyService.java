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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
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
	 * @param data              event payload (unused — the destination pane is refreshed via the
	 *                          plugin's {@code refresh.plugin.file.panel} event, not this map)
	 * @param callback          the commander progress bridge (unused — the plugin owns its UI)
	 */
	public void copy(NuclrResource currentFolder, List<NuclrResource> selectedResources, NuclrResource focusedResource,
			Map<String, Object> data, NuclrPluginCallback callback) {

		Path destination = currentFolder != null ? currentFolder.getPath() : null;
		if (destination == null || !Files.isDirectory(destination)) {
			Alerts.showError(DialogTitle, "The destination is not a folder.");
			return;
		}

		List<Path> sources = collectSources(selectedResources, focusedResource);
		if (sources.isEmpty()) {
			Alerts.showError(DialogTitle, "There is nothing to copy.");
			return;
		}

		CopyOptions options = CopyDialog.show(header(sources), destination);
		if (options == null) {
			return; // cancelled
		}
		if (options.getDestination() == null) {
			options.setDestination(destination);
		}

		CopyConflictDialog conflictDialog = new CopyConflictDialog();

		CopyProgressDialog.run(progress -> {
			CopyEngine engine = new CopyEngine(options, progress, conflictDialog, (src, e) -> true);
			engine.copy(sources);
		});

		// Note: the destination pane is reloaded by the "refresh.plugin.file.panel" event the
		// handling plugin emits for its own uuid. We deliberately do NOT set "result.refresh"
		// here: that flag is consumed by the pane that *initiated* the copy (the source), which
		// would needlessly reload it and reset its cursor to "..".
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
