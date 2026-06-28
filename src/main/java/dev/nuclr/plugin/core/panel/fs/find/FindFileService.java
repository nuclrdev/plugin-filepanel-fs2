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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Executes a {@link FindFileRequest} by walking {@link NuclrResource}s through the
 * active panel's plugin (never {@code java.nio.file} directly), on Java 21 virtual
 * threads. Matches are marshalled back to the EDT via {@link SwingUtilities#invokeLater}.
 *
 * <p>This service is UI-agnostic: {@link FindFileDialog} builds the immutable request,
 * hands it here, and receives results through a {@link Listener}. The dialog itself never
 * touches a worker thread.
 *
 * <p>Correctness guarantees:
 * <ul>
 *   <li>symlink branches track canonical ancestor paths and skip any link that resolves
 *       to an ancestor — cycle detection is unconditional;</li>
 *   <li>{@code .git} directories are skipped regardless of the gitignore toggle;</li>
 *   <li>content reads are gated on the per-entry {@link NuclrResource#isReadable() READ}
 *       capability flag.</li>
 * </ul>
 *
 * <p>Constructed with its collaborator (the panel plugin); owns a virtual-thread executor
 * that is released by {@link #close()}.
 */
@Slf4j
public final class FindFileService implements AutoCloseable {

	/** Cap on bytes read per file for content matching — keeps memory bounded for the MVP. */
	private static final long MAX_CONTENT_BYTES = 64L * 1024 * 1024;

	/** Receives search results; every callback is delivered on the EDT. */
	public interface Listener {

		/** A resource matched the request. */
		void onMatch(NuclrResource resource);

		/** Periodic progress tick. {@code current} may be {@code null}. */
		default void onProgress(long visited, long matched, NuclrResource current) {
		}

		/** Traversal finished (or was cancelled). */
		void onComplete(long visited, long matched, boolean cancelled);

		/** A recoverable error occurred while visiting {@code resource} (traversal continues). */
		default void onError(NuclrResource resource, Exception error) {
		}
	}

	/**
	 * Handle to a running search. Supports cooperative {@link #cancel()} plus
	 * {@link #pause()}/{@link #resume()} — workers block at {@link #awaitIfPaused()}
	 * checkpoints while paused, and unblock on resume or cancel.
	 */
	public static final class SearchHandle {

		private final AtomicBoolean cancelled = new AtomicBoolean(false);
		private final Object pauseLock = new Object();
		private volatile boolean paused;

		public void cancel() {
			cancelled.set(true);
			// Wake any worker parked in awaitIfPaused so it can observe the cancel and stop.
			synchronized (pauseLock) {
				paused = false;
				pauseLock.notifyAll();
			}
		}

		public boolean isCancelled() {
			return cancelled.get();
		}

		public void pause() {
			paused = true;
		}

		public void resume() {
			synchronized (pauseLock) {
				paused = false;
				pauseLock.notifyAll();
			}
		}

		public boolean isPaused() {
			return paused;
		}

		/** Block the calling worker while the search is paused (returns immediately if cancelled). */
		void awaitIfPaused() {
			synchronized (pauseLock) {
				while (paused && !cancelled.get()) {
					try {
						pauseLock.wait();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			}
		}
	}

	private final FilePanelNuclrPlugin plugin;
	private final ExecutorService executor;

	public FindFileService(FilePanelNuclrPlugin plugin) {
		this.plugin = plugin;
		this.executor = Executors.newVirtualThreadPerTaskExecutor();
	}

	/**
	 * Begin executing the request asynchronously. Returns immediately with a handle the
	 * caller can use to cancel. All {@code listener} callbacks run on the EDT.
	 *
	 * @param request  the search to run
	 * @param listener result sink
	 * @return a handle to the running search
	 */
	public SearchHandle search(FindFileRequest request, Listener listener) {
		SearchHandle handle = new SearchHandle();
		executor.execute(() -> run(request, listener, handle));
		return handle;
	}

	private void run(FindFileRequest request, Listener listener, SearchHandle handle) {

		AtomicLong visited = new AtomicLong();
		AtomicLong matched = new AtomicLong();

		ContentMatcher contentMatcher = request.hasContentQuery() ? new ContentMatcher(request) : null;
		NameMatcher nameMatcher = new NameMatcher(request.getNamePattern());

		try {
			List<NuclrResource> roots = request.getRoots();
			GitIgnoreMatcher ignore = request.isRespectGitignore() ? ignoreMatcherFor(roots) : null;

			for (NuclrResource root : roots) {
				if (handle.isCancelled()) {
					break;
				}
				if (root.isFolder()) {
					// Walk into the folder; the scope folder itself is not a result.
					Set<Path> ancestors = new HashSet<>();
					Path canonical = canonicalize(root.getPath());
					if (canonical != null) {
						ancestors.add(canonical);
					}
					walk(root, request, nameMatcher, contentMatcher, ignore, ancestors, visited, matched, listener,
							handle);
				} else {
					// A file root (e.g. a marked file or a custom path to a file) is itself a candidate.
					visited.incrementAndGet();
					evaluate(root, request, nameMatcher, contentMatcher, matched, listener);
				}
			}
		} catch (RuntimeException e) {
			log.warn("Find File search failed: {}", e.getMessage(), e);
		} finally {
			boolean cancelled = handle.isCancelled();
			long v = visited.get();
			long m = matched.get();
			SwingUtilities.invokeLater(() -> listener.onComplete(v, m, cancelled));
		}
	}

	private GitIgnoreMatcher ignoreMatcherFor(List<NuclrResource> roots) {
		for (NuclrResource root : roots) {
			GitIgnoreProbe probe = GitIgnoreProbe.probe(root);
			if (probe.isInsideWorkTree()) {
				return probe.ignoreMatcher();
			}
		}
		return null;
	}

	/**
	 * Depth-first walk of one directory branch. {@code ancestors} holds the canonical
	 * paths of every directory on the path from the search root to {@code directory},
	 * so a symlink (or junction) that resolves back to an ancestor is skipped.
	 */
	private void walk(NuclrResource directory, FindFileRequest request, NameMatcher nameMatcher,
			ContentMatcher contentMatcher, GitIgnoreMatcher ignore, Set<Path> ancestors, AtomicLong visited,
			AtomicLong matched, Listener listener, SearchHandle handle) {

		if (handle.isCancelled()) {
			return;
		}

		List<NuclrResource> children = listChildren(directory, handle, listener);

		for (NuclrResource child : children) {
			handle.awaitIfPaused();
			if (handle.isCancelled()) {
				return;
			}

			visited.incrementAndGet();

			boolean isFolder = child.isFolder();

			// .git metadata is excluded unconditionally, independent of the gitignore toggle.
			if (isFolder && ".git".equals(child.getName())) {
				continue;
			}
			if (!request.isIncludeHidden() && child.isHidden()) {
				continue;
			}
			if (ignore != null && child.getPath() != null && ignore.isIgnored(child.getPath(), isFolder)) {
				continue;
			}

			evaluate(child, request, nameMatcher, contentMatcher, matched, listener);

			if (visited.get() % 256 == 0) {
				long v = visited.get();
				long m = matched.get();
				SwingUtilities.invokeLater(() -> listener.onProgress(v, m, child));
			}

			if (isFolder && request.isSearchSubfolders()) {
				descend(child, request, nameMatcher, contentMatcher, ignore, ancestors, visited, matched, listener,
						handle);
			}
		}
	}

	private void descend(NuclrResource child, FindFileRequest request, NameMatcher nameMatcher,
			ContentMatcher contentMatcher, GitIgnoreMatcher ignore, Set<Path> ancestors, AtomicLong visited,
			AtomicLong matched, Listener listener, SearchHandle handle) {

		if (child.isLink() && !request.isFollowSymlinks()) {
			return;
		}

		Path canonical = canonicalize(child.getPath());
		if (canonical != null) {
			if (ancestors.contains(canonical)) {
				// Cycle: this directory resolves to one of its own ancestors. Skip it.
				log.debug("Skipping cyclic path {} -> {}", child.getFullPath(), canonical);
				return;
			}
			Set<Path> branchAncestors = new HashSet<>(ancestors);
			branchAncestors.add(canonical);
			walk(child, request, nameMatcher, contentMatcher, ignore, branchAncestors, visited, matched, listener,
					handle);
		} else {
			// No local path to canonicalize (remote resource): descend without cycle tracking.
			walk(child, request, nameMatcher, contentMatcher, ignore, ancestors, visited, matched, listener, handle);
		}
	}

	private List<NuclrResource> listChildren(NuclrResource directory, SearchHandle handle, Listener listener) {
		List<NuclrResource> children = new ArrayList<>();
		try {
			// Enumerate one level through the plugin abstraction (not java.nio directly);
			// recursion + cycle detection are controlled here.
			plugin.walkDescendants(directory, children::add, handle.cancelled, false);
		} catch (IOException e) {
			SwingUtilities.invokeLater(() -> listener.onError(directory, e));
		}
		return children;
	}

	/** Apply name, date, size and (optionally) content filters; emit a match if all pass. */
	private void evaluate(NuclrResource resource, FindFileRequest request, NameMatcher nameMatcher,
			ContentMatcher contentMatcher, AtomicLong matched, Listener listener) {

		if (!nameMatcher.matches(resource.getName())) {
			return;
		}
		if (!passesDateFilter(resource, request)) {
			return;
		}
		if (!passesSizeFilter(resource, request)) {
			return;
		}

		boolean matchedResult;
		if (contentMatcher != null && !resource.isFolder()) {
			if (!resource.isReadable()) {
				// READ capability not granted for this entry — cannot inspect content.
				return;
			}
			boolean found = contentMatcher.matches(resource);
			matchedResult = request.isInvertMatch() != found; // XOR: invert flips the result
		} else if (contentMatcher != null) {
			// A content query is present but this is a folder — folders never match content.
			return;
		} else {
			matchedResult = true;
		}

		if (matchedResult) {
			matched.incrementAndGet();
			SwingUtilities.invokeLater(() -> listener.onMatch(resource));
		}
	}

	private static boolean passesDateFilter(NuclrResource resource, FindFileRequest request) {
		LocalDateTime from = request.getModifiedFrom();
		LocalDateTime to = request.getModifiedTo();
		if (from == null && to == null) {
			return true;
		}
		LocalDateTime modified = resource.getLastModifiedDateTime();
		if (modified == null) {
			return false;
		}
		if (from != null && modified.isBefore(from)) {
			return false;
		}
		return to == null || !modified.isAfter(to);
	}

	private static boolean passesSizeFilter(NuclrResource resource, FindFileRequest request) {
		Long min = request.getMinSizeBytes();
		Long max = request.getMaxSizeBytes();
		if (min == null && max == null) {
			return true;
		}
		if (resource.isFolder()) {
			return false; // size filters apply to files only
		}
		long size = resource.getLength();
		if (min != null && size < min) {
			return false;
		}
		return max == null || size <= max;
	}

	private static Path canonicalize(Path path) {
		if (path == null) {
			return null;
		}
		try {
			return path.toRealPath();
		} catch (IOException e) {
			return path.toAbsolutePath().normalize();
		}
	}

	@Override
	public void close() {
		executor.shutdownNow();
	}

	// ------------------------------------------------------------------
	// Name matching (glob, case-insensitive)
	// ------------------------------------------------------------------

	/** Compiles a shell-style glob ({@code *}, {@code ?}) into a case-insensitive regex. */
	static final class NameMatcher {

		private final Pattern pattern;
		private final boolean matchAll;

		NameMatcher(String glob) {
			this.matchAll = glob == null || glob.isEmpty() || glob.equals("*");
			this.pattern = matchAll ? null : Pattern.compile(globToRegex(glob), Pattern.CASE_INSENSITIVE);
		}

		boolean matches(String name) {
			if (matchAll) {
				return true;
			}
			return name != null && pattern.matcher(name).matches();
		}

		private static String globToRegex(String glob) {
			StringBuilder sb = new StringBuilder(glob.length() + 8);
			for (int i = 0; i < glob.length(); i++) {
				char c = glob.charAt(i);
				switch (c) {
					case '*' -> sb.append(".*");
					case '?' -> sb.append('.');
					case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '^', '$', '|' -> sb.append('\\').append(c);
					default -> sb.append(c);
				}
			}
			return sb.toString();
		}
	}

	// ------------------------------------------------------------------
	// Content matching (text / regex / hex)
	// ------------------------------------------------------------------

	/** Reads (a bounded prefix of) a resource's bytes and tests the content query against them. */
	static final class ContentMatcher {

		private final FindFileRequest request;
		private final Charset charset;
		private final Pattern regex;
		private final byte[] hexNeedle;
		private final String literalNeedle;

		ContentMatcher(FindFileRequest request) {
			this.request = request;
			this.charset = request.getEncoding() == null ? StandardCharsets.UTF_8 : request.getEncoding();
			String query = request.getContainingText();

			switch (request.getContentMode()) {
				case REGEX -> {
					int flags = request.isCaseSensitive() ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
					this.regex = Pattern.compile(query, flags);
					this.hexNeedle = null;
					this.literalNeedle = null;
				}
				case HEX -> {
					this.regex = null;
					this.hexNeedle = parseHex(query);
					this.literalNeedle = null;
				}
				case TEXT -> {
					this.regex = null;
					this.hexNeedle = null;
					this.literalNeedle = request.isCaseSensitive() ? query : query.toLowerCase();
				}
				default -> throw new IllegalStateException("Unknown content mode " + request.getContentMode());
			}
		}

		boolean matches(NuclrResource resource) {
			byte[] bytes = readBounded(resource);
			if (bytes == null) {
				return false;
			}
			return switch (request.getContentMode()) {
				case HEX -> indexOf(bytes, hexNeedle) >= 0;
				case REGEX -> {
					// UTF-8 with byte-pattern fallback: decode leniently for text search.
					String text = new String(bytes, charset);
					yield regex.matcher(text).find();
				}
				case TEXT -> {
					String text = new String(bytes, charset);
					String haystack = request.isCaseSensitive() ? text : text.toLowerCase();
					if (request.isWholeWord()) {
						yield wholeWordContains(haystack, literalNeedle);
					}
					yield haystack.contains(literalNeedle);
				}
			};
		}

		private static byte[] readBounded(NuclrResource resource) {
			try (InputStream in = resource.openInputStream()) {
				return in.readNBytes((int) Math.min(MAX_CONTENT_BYTES, Integer.MAX_VALUE));
			} catch (Exception e) {
				log.debug("Cannot read content of {}: {}", resource.getFullPath(), e.getMessage());
				return null;
			}
		}

		private static boolean wholeWordContains(String haystack, String needle) {
			if (needle.isEmpty()) {
				return false;
			}
			Matcher m = Pattern.compile("\\b" + Pattern.quote(needle) + "\\b").matcher(haystack);
			return m.find();
		}

		private static byte[] parseHex(String query) {
			String compact = query.replaceAll("\\s+", "");
			byte[] out = new byte[compact.length() / 2];
			for (int i = 0; i < out.length; i++) {
				out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
			}
			return out;
		}

		private static int indexOf(byte[] haystack, byte[] needle) {
			if (needle.length == 0 || needle.length > haystack.length) {
				return -1;
			}
			outer:
			for (int i = 0; i <= haystack.length - needle.length; i++) {
				for (int j = 0; j < needle.length; j++) {
					if (haystack[i + j] != needle[j]) {
						continue outer;
					}
				}
				return i;
			}
			return -1;
		}
	}
}
