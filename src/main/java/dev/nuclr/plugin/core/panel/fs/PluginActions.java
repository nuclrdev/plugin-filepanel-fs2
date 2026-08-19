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
package dev.nuclr.plugin.core.panel.fs;

/** Action identifiers understood by the local-filesystem panel plugin. */
final class PluginActions {

	/** Host-dispatched Ctrl+C action. Must match Commander's action protocol value. */
	static final String CLIPBOARD_COPY = "clipboard.copy";

	/** Host-dispatched paste action. Must match Commander's action protocol value. */
	static final String CLIPBOARD_PASTE = "clipboard.paste";

	/** Host-dispatched Delete-key action. Must match Commander's action protocol value. */
	static final String DELETE = "delete";

	/** Context-menu action that copies local files using the platform file-list flavour. */
	static final String CLIPBOARD_COPY_FILES = "clipboard.copy.files";

	/** Context-menu action that copies local filesystem paths as text. */
	static final String CLIPBOARD_COPY_FULL_PATHS = "clipboard.copy.fullPaths";

	private PluginActions() {
	}
}
