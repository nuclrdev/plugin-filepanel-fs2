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
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Path relationships the copy and move engines must check before writing, so that an
 * operation can never read from and write to the same bytes.
 */
public final class TransferPaths {

	private TransferPaths() {
	}

	/**
	 * True when {@code target} is {@code source} itself or lies anywhere beneath it. Links and
	 * letter case are resolved for the part of {@code target} that already exists, so a folder
	 * reached through a link, or typed in a different case, is still recognised.
	 */
	public static boolean isSameOrInside(Path source, Path target) {
		return resolved(target).startsWith(resolved(source));
	}

	/**
	 * The path a conflict-dialog rename refers to: a relative entry (typically a bare new name) is
	 * taken beside the conflicting {@code target}, never against the JVM working directory.
	 */
	public static Path renameTarget(Path target, Path typed) {
		if (typed.isAbsolute()) {
			return typed.normalize();
		}
		Path parent = target.toAbsolutePath().getParent();
		return (parent != null ? parent.resolve(typed) : typed.toAbsolutePath()).normalize();
	}

	/** True when both paths name the same location, without following links (a link itself). */
	public static boolean sameLocation(Path a, Path b) {
		return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
	}

	/**
	 * True when both paths name one existing file — directly, through a link, through a hard
	 * link or under a different letter case. Writing one while reading the other would destroy it.
	 */
	public static boolean isSameFile(Path a, Path b) {
		try {
			return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
		} catch (IOException e) {
			return false;
		}
	}

	/** The real path of the longest existing prefix of {@code path}, with the rest appended. */
	private static Path resolved(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		Path existing = absolute;
		while (existing != null && !Files.exists(existing)) {
			existing = existing.getParent();
		}
		if (existing == null) {
			return absolute;
		}
		try {
			return existing.toRealPath().resolve(existing.relativize(absolute));
		} catch (IOException e) {
			return absolute;
		}
	}
}
