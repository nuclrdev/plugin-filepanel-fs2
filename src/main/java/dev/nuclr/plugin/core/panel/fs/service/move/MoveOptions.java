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

import java.nio.file.Path;

import lombok.Data;

/**
 * User-chosen settings for an F6 rename/move operation, produced by {@link MoveDialog}
 * and consumed by {@link MoveEngine}. Mirrors the copy subsystem's options model.
 */
@Data
public class MoveOptions {

	/** How permissions/ownership are applied when a move falls back to a cross-volume copy. */
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

	/**
	 * Destination the selection is moved to. Either a target directory (the receiving panel's
	 * folder) or, for a single source, an explicit new path (rename). {@link MoveService}
	 * decides which interpretation applies.
	 */
	private Path destination;

	private AccessRights accessRights = AccessRights.DEFAULT;

	private ConflictMode conflictMode = ConflictMode.ASK;

	/** When set, a read-only existing target always prompts even under a fixed conflict mode. */
	private boolean askOnReadOnly = true;

	/** Carry over the source timestamps when a move falls back to a cross-volume copy. */
	private boolean preserveTimestamps;

	/** Move the link target's contents rather than relocating the symbolic link itself. */
	private boolean copySymbolicLinkContents;

	/** Reserved: move into several destinations in one pass (UI flag only for now). */
	private boolean multipleDestinations;

	/** Reserved: apply the name filter (UI flag only for now). */
	private boolean useFilter;
}
