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

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Performs the actual deletion of local files/folders for the FS panel plugin.
 *
 * <p>Soft delete uses the platform recycle bin via {@link Desktop#moveToTrash} when available,
 * otherwise falls back to a regular (physical) delete. Physical delete is recursive and
 * <strong>junction/symlink safe</strong>: a reparse point is unlinked itself and never descended
 * into, so deleting e.g. {@code C:\Documents and Settings} can never destroy {@code C:\Users}.
 *
 * <p>Runs synchronously on the caller's (background) thread; reports progress and honours
 * cancellation through {@link NuclrPluginCallback}, and prompts via {@link ErrorPrompt} on failure.
 */
@Slf4j
public final class DeleteService {

	/** Per-item failure prompt. Return true to skip and continue, false to abort the operation. */
	@FunctionalInterface
	public interface ErrorPrompt {
		boolean onError(NuclrResource item, Exception e);
	}

	private DeleteService() {
	}

	public static boolean delete(List<NuclrResource> sources, boolean permanent, NuclrPluginCallback cb,
			ErrorPrompt errorPrompt) {

		boolean useTrash = !permanent && trashSupported();

		for (NuclrResource src : sources) {

			if (cb != null && cb.isCancelled()) {
				return false;
			}

			Path path = src.getPath();
			if (path == null) {
				continue;
			}

			String name = displayName(src, path);
			if (cb != null) {
				cb.onStart(name);
			}

			try {
				if (useTrash) {
					if (!Desktop.getDesktop().moveToTrash(path.toFile())) {
						throw new IOException("Could not move to the Recycle Bin");
					}
				} else {
					deletePhysical(path, cb);
				}
				if (cb != null && cb.isCancelled()) {
					return false; // cancelled mid-item: it may be only partially deleted, do not report success
				}
				if (cb != null) {
					cb.onComplete();
				}
			} catch (Exception e) {
				log.warn("Failed to delete [{}]: {}", path, e.getMessage(), e);
				if (cb != null) {
					cb.onError(name, e);
				}
				boolean skip = errorPrompt == null || errorPrompt.onError(src, e);
				if (!skip) {
					return false; // Abort
				}
			}
		}
		return true;
	}

	private static void deletePhysical(Path path, NuclrPluginCallback cb) throws IOException {

		if (cb != null && cb.isCancelled()) {
			return;
		}

		BasicFileAttributes attrs;
		try {
			attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (NoSuchFileException alreadyGone) {
			return;
		}

		// Recurse only into real directories — never into a junction/symlink (delete the link itself).
		if (attrs.isDirectory() && !isReparsePoint(path)) {
			try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
				for (Path child : children) {
					if (cb != null && cb.isCancelled()) {
						return;
					}
					deletePhysical(child, cb);
				}
			}
		}

		if (cb != null && cb.isCancelled()) {
			return; // cancelled before removing this entry (a directory may be left partially emptied)
		}

		Files.delete(path);
		if (cb != null) {
			cb.onProgress(1, -1);
		}
	}

	/** True if the path is a symbolic link or a Windows junction (mount-point reparse). */
	private static boolean isReparsePoint(Path path) {
		try {
			if (Files.isSymbolicLink(path)) {
				return true;
			}
			return !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS));
		} catch (IOException e) {
			// Fail safe: if the path cannot be resolved, treat it as a reparse point so we never
			// risk recursing into (and emptying) a junction/symlink target. A genuine directory we
			// could not resolve will then fail the non-recursive Files.delete and surface as an error.
			log.warn("Could not determine reparse status of [{}]; not recursing: {}", path, e.getMessage());
			return true;
		}
	}

	private static boolean trashSupported() {
		return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
	}

	private static String displayName(NuclrResource src, Path path) {
		if (src.getName() != null && !src.getName().isBlank()) {
			return src.getName();
		}
		Path fileName = path.getFileName();
		return fileName != null ? fileName.toString() : path.toString();
	}
}
