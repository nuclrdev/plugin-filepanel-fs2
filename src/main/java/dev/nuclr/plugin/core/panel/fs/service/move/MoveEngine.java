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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import lombok.extern.slf4j.Slf4j;

/**
 * UI-agnostic engine that performs the actual filesystem rename/move for the F6 action.
 *
 * <p>Mirrors {@link dev.nuclr.plugin.core.panel.fs.service.CopyEngine}: it runs synchronously on
 * the caller's (background) thread, reports progress / honours cancellation through a
 * {@link NuclrPluginCallback}, and delegates every user decision to functional interfaces so it
 * carries no Swing dependency and can be unit-tested directly.
 *
 * <p>When the source and target live on the same {@link FileStore} a move is a fast, atomic rename
 * (entire directory trees included). Across volumes, where a rename is impossible, it falls back to
 * a streamed copy followed by deletion of the source — this is the only path that reports byte
 * progress and can be interrupted mid-file. Directory clashes <strong>merge</strong> into an
 * existing target directory; per-file clashes inside are resolved individually.
 */
@Slf4j
public final class MoveEngine {

	/** How a single existing-target clash should be resolved. */
	public enum Action {
		OVERWRITE, SKIP, RENAME, APPEND, CANCEL
	}

	/** Outcome of a conflict prompt: the chosen action plus, for {@link Action#RENAME}, the new target. */
	public record Resolution(Action action, Path renameTarget) {
		public static Resolution of(Action action) {
			return new Resolution(action, null);
		}
	}

	/** Asked to resolve a clash when the target already exists and the mode is {@code ASK}. */
	@FunctionalInterface
	public interface ConflictResolver {
		/** @return the resolution, or {@code null}/{@link Action#CANCEL} to abort the whole operation. */
		Resolution resolve(Path source, Path target);
	}

	/** Per-item failure prompt. Return true to skip and continue, false to abort the operation. */
	@FunctionalInterface
	public interface ErrorPrompt {
		boolean onError(Path source, Exception e);
	}

	private static final int BUFFER = 64 * 1024;

	private final MoveOptions options;
	private final NuclrPluginCallback cb;
	private final ConflictResolver resolver;
	private final ErrorPrompt errorPrompt;

	/**
	 * When {@code true} the (single) source is moved to {@link MoveOptions#getDestination()}
	 * verbatim (a rename); otherwise the destination is a directory and each source keeps its name.
	 */
	private final boolean destinationIsExplicitTarget;

	private long totalBytes;
	private long movedBytes;
	private boolean aborted;

	public MoveEngine(MoveOptions options, NuclrPluginCallback cb, ConflictResolver resolver, ErrorPrompt errorPrompt,
			boolean destinationIsExplicitTarget) {
		this.options = options;
		this.cb = cb;
		this.resolver = resolver;
		this.errorPrompt = errorPrompt;
		this.destinationIsExplicitTarget = destinationIsExplicitTarget;
	}

	/**
	 * Move each source path to {@link MoveOptions#getDestination()}.
	 *
	 * @return {@code true} if the run completed (possibly with skipped items), {@code false} if it
	 *         was cancelled or aborted part-way.
	 */
	public boolean move(Iterable<Path> sources) {

		Path destination = options.getDestination();
		if (destination == null) {
			return false;
		}

		if (!destinationIsExplicitTarget) {
			try {
				Files.createDirectories(destination); // ensure the receiving folder exists
			} catch (IOException e) {
				reportError(destination, e);
				return false;
			}
		}

		this.totalBytes = scanTotalBytes(sources);
		this.movedBytes = 0;
		this.aborted = false;

		for (Path source : sources) {
			if (isCancelled()) {
				return false;
			}
			Path target = destinationIsExplicitTarget ? destination : destination.resolve(fileName(source));
			moveEntry(source, target);
			if (aborted || isCancelled()) {
				return false;
			}
		}
		return true;
	}

	/** Recursively move a single entry (file, directory or link) to {@code target}. */
	private void moveEntry(Path source, Path target) {

		if (isCancelled() || aborted) {
			return;
		}

		BasicFileAttributes attrs;
		try {
			attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (NoSuchFileException gone) {
			return; // vanished between selection and move
		} catch (IOException e) {
			reportError(source, e);
			return;
		}

		boolean link = attrs.isSymbolicLink();
		boolean directory = (link ? Files.isDirectory(source) : attrs.isDirectory())
				&& !(link && !options.isCopySymbolicLinkContents());

		if (directory) {
			if (sameLocation(source, target)) {
				return; // a directory moved onto itself: nothing to do (don't prompt per child)
			}
			moveDirectory(source, target);
		} else {
			moveFile(source, target);
		}
	}

	private void moveFile(Path source, Path target) {

		if (isCancelled()) {
			return;
		}

		cb.onStart(fileName(source));

		boolean append = false;
		Path effectiveTarget = target;

		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			MoveOptions.ConflictMode mode = options.getConflictMode();
			boolean readOnlyPrompt = options.isAskOnReadOnly() && !Files.isWritable(target);

			if (mode == MoveOptions.ConflictMode.ASK || readOnlyPrompt) {
				Resolution r = ask(source, target);
				if (r == null || r.action() == Action.CANCEL) {
					aborted = true;
					return;
				}
				switch (r.action()) {
					case SKIP -> { return; }
					case APPEND -> append = true;
					case RENAME -> effectiveTarget = r.renameTarget() != null ? r.renameTarget() : autoRename(target);
					case OVERWRITE -> { /* fall through */ }
					default -> { return; }
				}
			} else {
				switch (mode) {
					case SKIP -> { return; }
					case APPEND -> append = true;
					case RENAME -> effectiveTarget = autoRename(target);
					case ONLY_NEWER -> {
						if (!isSourceNewer(source, target)) {
							return;
						}
					}
					case OVERWRITE -> { /* fall through */ }
					default -> { /* ASK handled above */ }
				}
			}
		}

		if (sameLocation(source, effectiveTarget)) {
			// The conflict prompt was shown, but Overwrite/Append/keep would target the source
			// itself — leave the file untouched rather than move it onto (or append it to) itself.
			cb.onComplete();
			return;
		}

		try {
			Path parent = effectiveTarget.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}

			if (append) {
				appendBytes(source, effectiveTarget);
				if (isCancelled()) {
					return;
				}
				Files.deleteIfExists(source);
			} else if (sameStore(source, effectiveTarget)) {
				// Fast path: a same-volume rename moves the bytes instantly. Capture the size up
				// front — after the move the source is gone and would measure as zero.
				long size = sizeOf(source, false);
				Files.move(source, effectiveTarget, StandardCopyOption.REPLACE_EXISTING);
				movedBytes += size;
				cb.onProgress(movedBytes, totalBytes);
			} else {
				// Cross-volume: stream the contents (cancellable) then drop the source.
				writeBytes(source, effectiveTarget);
				if (isCancelled()) {
					return;
				}
				applyAttributes(source, effectiveTarget);
				Files.deleteIfExists(source);
			}
			cb.onComplete();
		} catch (IOException e) {
			reportError(source, e);
		}
	}

	private void moveDirectory(Path source, Path target) {

		boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);

		if (!targetExists) {
			// Try to relocate the whole tree in one atomic rename (only possible on the same store).
			if (sameStore(source, target)) {
				try {
					cb.onStart(fileName(source));
					// Measure before the move; afterwards the whole tree is gone from the source.
					long size = sizeOf(source, true);
					Files.move(source, target);
					movedBytes += size;
					cb.onProgress(movedBytes, totalBytes);
					cb.onComplete();
					return;
				} catch (AtomicMoveNotSupportedException | DirectoryNotEmptyException retryRecursively) {
					// Fall through to the per-child merge below.
				} catch (IOException e) {
					reportError(source, e);
					return;
				}
			}
			try {
				Files.createDirectories(target);
			} catch (IOException e) {
				reportError(source, e);
				return;
			}
		} else if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
			// A non-directory occupies the target name: route through the conflict prompt.
			Resolution r = ask(source, target);
			if (r == null || r.action() == Action.CANCEL) {
				aborted = true;
				return;
			}
			if (r.action() == Action.SKIP) {
				return;
			}
			try {
				Files.deleteIfExists(target);
				Files.createDirectories(target);
			} catch (IOException e) {
				reportError(source, e);
				return;
			}
		}

		// Merge: move each child individually, then drop the source directory if it is now empty.
		try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
			for (Path child : children) {
				if (isCancelled() || aborted) {
					return;
				}
				moveEntry(child, target.resolve(fileName(child)));
			}
		} catch (IOException e) {
			reportError(source, e);
			return;
		}

		if (isCancelled() || aborted) {
			return;
		}

		applyAttributes(source, target);
		try {
			Files.deleteIfExists(source); // succeeds only when every child was moved out
		} catch (DirectoryNotEmptyException leftovers) {
			// Some children were skipped; leaving the source directory in place is correct.
		} catch (IOException e) {
			reportError(source, e);
		}
	}

	/** Stream the source into a fresh target, honouring cancellation and reporting byte progress. */
	private void writeBytes(Path source, Path target) throws IOException {
		try (InputStream in = Files.newInputStream(source);
				OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING)) {
			pump(in, out);
		}
	}

	/** Append the source bytes to the end of an existing target, then leave the source for deletion. */
	private void appendBytes(Path source, Path target) throws IOException {
		try (InputStream in = Files.newInputStream(source);
				OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.APPEND)) {
			pump(in, out);
		}
	}

	private void pump(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[BUFFER];
		int read;
		while ((read = in.read(buffer)) >= 0) {
			if (isCancelled()) {
				return; // leaves a partial target and the intact source; acceptable on user cancel
			}
			out.write(buffer, 0, read);
			movedBytes += read;
			cb.onProgress(movedBytes, totalBytes);
		}
	}

	private Resolution ask(Path source, Path target) {
		if (resolver == null) {
			return Resolution.of(Action.OVERWRITE);
		}
		return resolver.resolve(source, target);
	}

	private void reportError(Path source, Exception e) {
		log.warn("Failed to move [{}]: {}", source, e.getMessage(), e);
		cb.onError(fileName(source), e);
		boolean skip = errorPrompt == null || errorPrompt.onError(source, e);
		if (!skip) {
			aborted = true;
		}
	}

	/** Apply permissions and timestamps after a cross-volume copy, per the chosen options. */
	private void applyAttributes(Path source, Path target) {

		if (options.getAccessRights() == MoveOptions.AccessRights.COPY) {
			try {
				PosixFileAttributeView srcView = Files.getFileAttributeView(source, PosixFileAttributeView.class);
				PosixFileAttributeView dstView = Files.getFileAttributeView(target, PosixFileAttributeView.class);
				if (srcView != null && dstView != null) {
					Set<PosixFilePermission> perms = srcView.readAttributes().permissions();
					dstView.setPermissions(perms);
				}
			} catch (IOException e) {
				log.debug("Could not copy permissions for [{}]: {}", target, e.getMessage());
			}
		}

		if (options.isPreserveTimestamps()) {
			try {
				BasicFileAttributes a = Files.readAttributes(source, BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
				BasicFileAttributeView view = Files.getFileAttributeView(target, BasicFileAttributeView.class,
						LinkOption.NOFOLLOW_LINKS);
				if (view != null) {
					view.setTimes(a.lastModifiedTime(), a.lastAccessTime(), a.creationTime());
				}
			} catch (IOException e) {
				log.debug("Could not preserve timestamps for [{}]: {}", target, e.getMessage());
			}
		}
	}

	/**
	 * Generate the first non-clashing "{@code stem (n).ext}" sibling of {@code target}
	 * (matching the dialog's suggestion), starting at {@code n = 2}.
	 */
	static Path autoRename(Path target) {
		Path parent = target.getParent();
		String name = fileName(target);
		int dot = name.lastIndexOf('.');
		String stem = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";

		for (int n = 2; n < Integer.MAX_VALUE; n++) {
			String candidate = stem + " (" + n + ")" + ext;
			Path resolved = parent != null ? parent.resolve(candidate) : Path.of(candidate);
			if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
				return resolved;
			}
		}
		return target;
	}

	/** True when both paths resolve to the same filesystem location (a move onto itself). */
	private static boolean sameLocation(Path a, Path b) {
		return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
	}

	private static boolean isSourceNewer(Path source, Path target) {
		try {
			FileTime s = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS);
			FileTime t = Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS);
			return s.compareTo(t) > 0;
		} catch (IOException e) {
			return true; // when in doubt, move
		}
	}

	/** True when {@code source} and {@code target} live on the same file store (a rename is possible). */
	private static boolean sameStore(Path source, Path target) {
		try {
			Path probe = target;
			while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
				probe = probe.getParent();
			}
			if (probe == null) {
				return false;
			}
			FileStore sourceStore = Files.getFileStore(source);
			FileStore targetStore = Files.getFileStore(probe);
			return sourceStore.equals(targetStore);
		} catch (IOException e) {
			return false; // when unsure, take the safe copy+delete path
		}
	}

	private long scanTotalBytes(Iterable<Path> sources) {
		long total = 0;
		for (Path source : sources) {
			total += sizeOf(source, true);
		}
		return total;
	}

	private long sizeOf(Path path, boolean recurse) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (attrs.isSymbolicLink() && !options.isCopySymbolicLinkContents()) {
				return 0;
			}
			boolean directory = attrs.isSymbolicLink() ? Files.isDirectory(path) : attrs.isDirectory();
			if (!directory) {
				return attrs.size();
			}
			if (!recurse) {
				return 0;
			}
			long total = 0;
			try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
				for (Path child : children) {
					total += sizeOf(child, true);
				}
			}
			return total;
		} catch (IOException e) {
			return 0;
		}
	}

	private boolean isCancelled() {
		return cb != null && cb.isCancelled();
	}

	private static String fileName(Path path) {
		Path name = path.getFileName();
		return name != null ? name.toString() : path.toString();
	}
}
