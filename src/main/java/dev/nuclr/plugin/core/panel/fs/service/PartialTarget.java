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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Undoes a byte copy cancelled half way, so a cancelled copy or move never leaves a truncated
 * file under the real name for someone to mistake for the whole thing.
 *
 * <p>A fresh or overwritten target is deleted: its old content was already gone the moment it
 * was opened for writing. An appended target is cut back to the length it had before, because
 * the bytes up to there are the user's own file.
 */
public final class PartialTarget {

	/** Length of a target that will be written from scratch: discard means delete. */
	public static final long REPLACED = -1;

	private PartialTarget() {
	}

	/** What to record before writing: the current length when appending, else {@link #REPLACED}. */
	public static long before(Path target, boolean append) throws IOException {
		return append && Files.exists(target, LinkOption.NOFOLLOW_LINKS) ? Files.size(target) : REPLACED;
	}

	/** Restores {@code target} after a cancelled write, given what {@link #before} returned. */
	public static void discard(Path target, long lengthBefore) throws IOException {
		if (lengthBefore == REPLACED) {
			Files.deleteIfExists(target);
			return;
		}
		try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
			channel.truncate(lengthBefore);
		}
	}
}
