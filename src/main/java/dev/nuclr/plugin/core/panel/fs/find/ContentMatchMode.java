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

/**
 * How the "Containing" text is interpreted when matching file content. Drives the
 * {@code Text | Regex | Hex} segmented control in {@link FindFileDialog}.
 */
public enum ContentMatchMode {

	/** Literal substring search. Honours case-sensitive / whole-word toggles. */
	TEXT("find.mode.text"),

	/** Java regular expression search. Honours the case-sensitive toggle. */
	REGEX("find.mode.regex"),

	/** Byte-pattern search: the query is a sequence of hex octets (e.g. {@code 89 50 4E 47}). */
	HEX("find.mode.hex");

	private final String labelKey;

	ContentMatchMode(String labelKey) {
		this.labelKey = labelKey;
	}

	/** Human-readable label shown on the segmented toggle button. */
	public String label() {
		return tr(labelKey);
	}
}
