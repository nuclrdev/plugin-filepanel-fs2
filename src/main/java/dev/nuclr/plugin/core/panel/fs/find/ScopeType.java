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

import org.apache.commons.lang3.SystemUtils;

/**
 * Where a Find File search looks. Populates the "Where" combo box; the
 * {@link #VOLUMES} label is resolved per-platform at runtime.
 */
public enum ScopeType {

	/** The focused panel's current folder (the default). */
	CURRENT_FOLDER("Current folder"),

	/** The current folders of both panels. */
	BOTH_PANELS("Both panels"),

	/** Only the items currently marked/selected in the focused panel. */
	MARKED_ITEMS("Marked items"),

	/** All mounted volumes (drives / volumes / mount points, per platform). */
	VOLUMES("Volumes"),

	/** A single user-entered path or plugin URI. */
	CUSTOM_PATH("Custom path…");

	private final String label;

	ScopeType(String label) {
		this.label = label;
	}

	/**
	 * The label to show in the combo box. For {@link #VOLUMES} this is resolved to
	 * the platform-appropriate term; all other entries use their fixed label.
	 */
	public String label() {
		if (this == VOLUMES) {
			if (SystemUtils.IS_OS_WINDOWS) {
				return "Drives";
			}
			if (SystemUtils.IS_OS_MAC) {
				return "Volumes";
			}
			return "Mount points";
		}
		return label;
	}
}
