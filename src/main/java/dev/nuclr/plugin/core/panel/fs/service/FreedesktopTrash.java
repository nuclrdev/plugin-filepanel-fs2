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
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The freedesktop.org Trash specification (v1.0), used on Linux when {@link java.awt.Desktop}
 * cannot move to trash - which is any session without a GTK/GIO desktop behind the JVM.
 *
 * <p>An item on the same file system as {@code $XDG_DATA_HOME} goes to the home trash,
 * {@code $XDG_DATA_HOME/Trash}; anything else goes to {@code $topdir/.Trash-$uid} on its own file
 * system, because moving a folder across file systems is a copy and the spec forbids that. Each
 * entry gets a {@code .trashinfo} record (original path and deletion time) so desktop file
 * managers list it and can restore it. The shared {@code $topdir/.Trash/$uid} variant is not
 * used; the spec makes {@code .Trash-$uid} the fallback whenever that check does not succeed.
 *
 * <p>The record is created first, with {@code CREATE_NEW}, which reserves the name against a
 * concurrent trasher; the item is then renamed into place, and the record is removed again if
 * the rename fails, so a failure never leaves a half-trashed item.
 */
final class FreedesktopTrash implements DeleteService.TrashOperation {

	private static final DateTimeFormatter DELETION_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

	private final Path homeTrash;

	FreedesktopTrash(Path homeTrash) {
		this.homeTrash = Objects.requireNonNull(homeTrash);
	}

	/** The home trash for the current user, or {@code null} when no home directory is known. */
	static FreedesktopTrash forCurrentUser() {
		String dataHome = System.getenv("XDG_DATA_HOME");
		Path base;
		if (dataHome != null && !dataHome.isBlank() && Path.of(dataHome).isAbsolute()) {
			base = Path.of(dataHome);
		} else {
			String home = System.getProperty("user.home");
			if (home == null || home.isBlank()) {
				return null;
			}
			base = Path.of(home, ".local", "share");
		}
		return new FreedesktopTrash(base.resolve("Trash"));
	}

	@Override
	public void moveToTrash(Path path) throws IOException {
		Path item = path.toAbsolutePath().normalize();
		if (!Files.exists(item, LinkOption.NOFOLLOW_LINKS)) {
			throw new NoSuchFileException(item.toString());
		}

		Path trash;
		String recordedPath;
		if (sameFileStore(item, existingAncestor(homeTrash))) {
			trash = homeTrash;
			recordedPath = item.toString();
		} else {
			Path topdir = topdir(item);
			trash = topdir.resolve(".Trash-" + uid(item));
			// Inside a $topdir trash the path is recorded relative to $topdir.
			recordedPath = topdir.relativize(item).toString();
		}
		if (trash.startsWith(item)) {
			throw new IOException("Cannot move a folder that contains the Trash into the Trash");
		}

		Path files = trash.resolve("files");
		Path info = trash.resolve("info");
		createPrivateDirectories(files);
		createPrivateDirectories(info);

		String baseName = item.getFileName() == null ? "item" : item.getFileName().toString();
		for (int attempt = 1; attempt < 10_000; attempt++) {
			String name = attempt == 1 ? baseName : baseName + "." + attempt;
			Path record = info.resolve(name + ".trashinfo");
			Path target = files.resolve(name);
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			try {
				Files.writeString(record, trashInfo(recordedPath), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			} catch (FileAlreadyExistsException taken) {
				continue;
			}
			try {
				Files.move(item, target);
				return;
			} catch (FileAlreadyExistsException raced) {
				Files.deleteIfExists(record);
			} catch (IOException | RuntimeException e) {
				Files.deleteIfExists(record);
				throw e;
			}
		}
		throw new IOException("No free name left in " + files + " for " + baseName);
	}

	static String trashInfo(String recordedPath) {
		return "[Trash Info]\n"
				+ "Path=" + encodePath(recordedPath) + "\n"
				+ "DeletionDate=" + LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).format(DELETION_DATE) + "\n";
	}

	/** Percent-encodes everything but the unreserved URI characters and the path separator. */
	static String encodePath(String path) {
		StringBuilder out = new StringBuilder();
		for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
					|| c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
				out.append((char) c);
			} else {
				out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
						.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
			}
		}
		return out.toString();
	}

	/** The highest directory above {@code item} still on its file system: its mount point. */
	private static Path topdir(Path item) throws IOException {
		FileStore store = Files.getFileStore(item);
		Path top = item.getParent();
		while (top != null && top.getParent() != null && store.equals(Files.getFileStore(top.getParent()))) {
			top = top.getParent();
		}
		if (top == null) {
			throw new IOException("Cannot find the file system root of " + item);
		}
		return top;
	}

	private static boolean sameFileStore(Path a, Path b) throws IOException {
		return Files.getFileStore(a).equals(Files.getFileStore(b));
	}

	/** {@code path} itself if it exists, else its nearest existing ancestor (the trash may not yet). */
	private static Path existingAncestor(Path path) throws IOException {
		Path p = path.toAbsolutePath();
		while (p != null && !Files.exists(p)) {
			p = p.getParent();
		}
		if (p == null) {
			throw new IOException("No existing directory above " + path);
		}
		return p;
	}

	private static Object uid(Path item) throws IOException {
		try {
			return Files.getAttribute(item, "unix:uid", LinkOption.NOFOLLOW_LINKS);
		} catch (UnsupportedOperationException | IllegalArgumentException e) {
			throw new IOException("This file system has no Trash for files outside the home folder", e);
		}
	}

	private static void createPrivateDirectories(Path dir) throws IOException {
		if (Files.isDirectory(dir)) {
			return;
		}
		Files.createDirectories(dir);
		try {
			Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
		} catch (UnsupportedOperationException notPosix) {
			// Nothing to tighten on a file system without POSIX permissions.
		}
	}
}
