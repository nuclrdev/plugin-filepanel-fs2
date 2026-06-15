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

import java.nio.file.Path;

import lombok.Data;

/**
 * User-chosen settings for an F5 copy operation, produced by the copy setup
 * dialog and consumed by {@link CopyEngine}.
 */
@Data
public class CopyOptions {

	/** How permissions/ownership are applied to the freshly written target. */
	public enum AccessRights {
		/** Let the filesystem assign default permissions (do not copy the source ACL). */
		DEFAULT,
		/** Replicate the source file's permissions onto the target. */
		COPY,
		/** Inherit permissions from the destination directory (filesystem default). */
		INHERIT
	}

	/** What to do when a target file already exists. */
	public enum ConflictMode {
		/** Prompt the user for each clash. */
		ASK,
		/** Replace the existing file unconditionally. */
		OVERWRITE,
		/** Keep the existing file, skip the source. */
		SKIP,
		/** Write the source under a non-clashing name. */
		RENAME,
		/** Append the source bytes to the end of the existing file. */
		APPEND,
		/** Overwrite only when the source is newer than the existing file. */
		ONLY_NEWER
	}

	/** Destination directory the selection is copied into. */
	private Path destination;

	private AccessRights accessRights = AccessRights.DEFAULT;

	private ConflictMode conflictMode = ConflictMode.ASK;

	/** When set, a read-only existing target always prompts even under a fixed conflict mode. */
	private boolean askOnReadOnly = true;

	/** Carry over the source modification (and where possible creation/access) timestamps. */
	private boolean preserveTimestamps;

	/** Copy the link target's contents rather than re-creating the symbolic link. */
	private boolean copySymbolicLinkContents;

	/** Reserved: copy into several destinations in one pass (UI flag only for now). */
	private boolean multipleDestinations;

	/** Reserved: apply the name filter (UI flag only for now). */
	private boolean useFilter;
}
