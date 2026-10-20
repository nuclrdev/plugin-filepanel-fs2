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

import static dev.nuclr.plugin.core.panel.fs.Messages.tr;

import org.apache.commons.lang3.SystemUtils;

/**
 * Where a Find File search looks. Populates the "Where" combo box; the
 * {@link #VOLUMES} label is resolved per-platform at runtime.
 */
public enum ScopeType {

	/** The focused panel's current folder (the default). */
	CURRENT_FOLDER("find.scope.current_folder"),

	/** The current folders of both panels. */
	BOTH_PANELS("find.scope.both_panels"),

	/** Only the items currently marked/selected in the focused panel. */
	MARKED_ITEMS("find.scope.marked_items"),

	/** All mounted volumes (drives / volumes / mount points, per platform). */
	VOLUMES("find.scope.volumes"),

	/** A single user-entered path or plugin URI. */
	CUSTOM_PATH("find.scope.custom_path");

	private final String labelKey;

	ScopeType(String labelKey) {
		this.labelKey = labelKey;
	}

	/**
	 * The label to show in the combo box. For {@link #VOLUMES} this is resolved to
	 * the platform-appropriate term; all other entries use their fixed label.
	 */
	public String label() {
		if (this == VOLUMES) {
			if (SystemUtils.IS_OS_WINDOWS) {
				return tr("find.scope.drives");
			}
			if (SystemUtils.IS_OS_MAC) {
				return tr("find.scope.volumes");
			}
			return tr("find.scope.mount_points");
		}
		return tr(labelKey);
	}
}
