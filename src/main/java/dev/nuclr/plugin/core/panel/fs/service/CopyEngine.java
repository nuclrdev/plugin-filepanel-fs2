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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
 * UI-agnostic engine that performs the actual filesystem copy for the F5 action.
 *
 * <p>Mirrors {@link DeleteService}'s design: it runs synchronously on the caller's
 * (background) thread, reports progress / honours cancellation through a
 * {@link NuclrPluginCallback}, and delegates every user decision to functional
 * interfaces so it carries no Swing dependency and can be unit-tested directly.
 *
 * <p>Directory copies <strong>merge</strong> into an existing target directory; per-file
 * clashes inside are resolved individually. Symbolic links are re-created as links unless
 * {@link CopyOptions#isCopySymbolicLinkContents()} is set, in which case their target's
 * contents are copied. Cancellation is checked before each entry and inside the byte loop.
 */
@Slf4j
public final class CopyEngine {

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

	/** Asked to resolve a clash when the source target already exists and the mode is {@code ASK}. */
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

	private final CopyOptions options;
	private final NuclrPluginCallback cb;
	private final ConflictResolver resolver;
	private final ErrorPrompt errorPrompt;

	private long totalBytes;
	private long copiedBytes;
	private boolean aborted;

	public CopyEngine(CopyOptions options, NuclrPluginCallback cb, ConflictResolver resolver, ErrorPrompt errorPrompt) {
		this.options = options;
		this.cb = cb;
		this.resolver = resolver;
		this.errorPrompt = errorPrompt;
	}

	/**
	 * Copy each source path into {@link CopyOptions#getDestination()}.
	 *
	 * @return {@code true} if the run completed (possibly with skipped items), {@code false} if it
	 *         was cancelled or aborted part-way.
	 */
	public boolean copy(Iterable<Path> sources) {

		Path destination = options.getDestination();
		if (destination == null) {
			return false;
		}

		this.totalBytes = scanTotalBytes(sources);
		this.copiedBytes = 0;
		this.aborted = false;

		for (Path source : sources) {
			if (isCancelled()) {
				return false;
			}
			Path target = destination.resolve(fileName(source));
			copyEntry(source, target);
			if (aborted || isCancelled()) {
				return false;
			}
		}
		return true;
	}

	/** Recursively copy a single entry (file, directory or link) to {@code target}. */
	private void copyEntry(Path source, Path target) {

		if (isCancelled() || aborted) {
			return;
		}

		BasicFileAttributes attrs;
		try {
			attrs = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		} catch (NoSuchFileException gone) {
			return; // vanished between selection and copy
		} catch (IOException e) {
			reportError(source, e);
			return;
		}

		boolean link = attrs.isSymbolicLink();

		if (link && !options.isCopySymbolicLinkContents()) {
			copyLink(source, target);
			return;
		}

		boolean directory = link ? Files.isDirectory(source) : attrs.isDirectory();

		if (directory) {
			copyDirectory(source, target);
		} else {
			copyFile(source, target);
		}
	}

	private void copyDirectory(Path source, Path target) {

		try {
			Files.createDirectories(target); // merge if it already exists
		} catch (FileAlreadyExistsException existsAsFile) {
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
		} catch (IOException e) {
			reportError(source, e);
			return;
		}

		try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
			for (Path child : children) {
				if (isCancelled() || aborted) {
					return;
				}
				copyEntry(child, target.resolve(fileName(child)));
			}
		} catch (IOException e) {
			reportError(source, e);
		}

		applyAttributes(source, target);
	}

	private void copyFile(Path source, Path target) {

		if (isCancelled()) {
			return;
		}

		cb.onStart(fileName(source));

		boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
		boolean append = false;
		Path effectiveTarget = target;

		if (exists) {
			CopyOptions.ConflictMode mode = options.getConflictMode();
			boolean readOnlyPrompt = options.isAskOnReadOnly() && !Files.isWritable(target);

			if (mode == CopyOptions.ConflictMode.ASK || readOnlyPrompt) {
				Resolution r = ask(source, target);
				if (r == null || r.action() == Action.CANCEL) {
					aborted = true;
					return;
				}
				switch (r.action()) {
					case SKIP -> { return; }
					case APPEND -> append = true;
					case RENAME -> effectiveTarget = r.renameTarget() != null ? r.renameTarget() : autoRename(target);
					case OVERWRITE -> { /* fall through to write */ }
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
					case OVERWRITE -> { /* fall through to write */ }
					default -> { /* ASK handled above */ }
				}
			}
		}

		try {
			writeBytes(source, effectiveTarget, append);
			if (isCancelled()) {
				return;
			}
			applyAttributes(source, effectiveTarget);
			cb.onComplete();
		} catch (IOException e) {
			reportError(source, e);
		}
	}

	/** Stream the source into the target, honouring cancellation and reporting byte progress. */
	private void writeBytes(Path source, Path target, boolean append) throws IOException {

		Path parent = target.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		StandardOpenOption[] openOptions = append
				? new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.APPEND }
				: new StandardOpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING };

		try (InputStream in = Files.newInputStream(source);
				OutputStream out = Files.newOutputStream(target, openOptions)) {

			byte[] buffer = new byte[BUFFER];
			int read;
			while ((read = in.read(buffer)) >= 0) {
				if (isCancelled()) {
					return; // leaves a partial file; acceptable on user cancel
				}
				out.write(buffer, 0, read);
				copiedBytes += read;
				cb.onProgress(copiedBytes, totalBytes);
			}
		}
	}

	/** Re-create a symbolic link at the target, pointing at the same place as the source link. */
	private void copyLink(Path source, Path target) {
		cb.onStart(fileName(source));
		try {
			Path linkTarget = Files.readSymbolicLink(source);
			Files.deleteIfExists(target);
			Files.createSymbolicLink(target, linkTarget);
			cb.onComplete();
		} catch (IOException | UnsupportedOperationException e) {
			reportError(source, e instanceof Exception ex ? ex : new IOException(e));
		}
	}

	private Resolution ask(Path source, Path target) {
		if (resolver == null) {
			return Resolution.of(Action.OVERWRITE);
		}
		return resolver.resolve(source, target);
	}

	private void reportError(Path source, Exception e) {
		log.warn("Failed to copy [{}]: {}", source, e.getMessage(), e);
		cb.onError(fileName(source), e);
		boolean skip = errorPrompt == null || errorPrompt.onError(source, e);
		if (!skip) {
			aborted = true;
		}
	}

	/** Apply permissions and timestamps to a freshly written target per the chosen options. */
	private void applyAttributes(Path source, Path target) {

		if (options.getAccessRights() == CopyOptions.AccessRights.COPY) {
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

	private static boolean isSourceNewer(Path source, Path target) {
		try {
			FileTime s = Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS);
			FileTime t = Files.getLastModifiedTime(target, LinkOption.NOFOLLOW_LINKS);
			return s.compareTo(t) > 0;
		} catch (IOException e) {
			return true; // when in doubt, copy
		}
	}

	private long scanTotalBytes(Iterable<Path> sources) {
		long total = 0;
		for (Path source : sources) {
			total += sizeOf(source);
		}
		return total;
	}

	private long sizeOf(Path path) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (attrs.isSymbolicLink() && !options.isCopySymbolicLinkContents()) {
				return 0;
			}
			boolean directory = attrs.isSymbolicLink() ? Files.isDirectory(path) : attrs.isDirectory();
			if (!directory) {
				return attrs.size();
			}
			long total = 0;
			try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
				for (Path child : children) {
					total += sizeOf(child);
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
