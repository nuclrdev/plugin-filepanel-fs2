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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * One-shot helper that answers, for a given starting location, whether it sits
 * inside a Git working tree and — if so — where the work-tree root and the real
 * {@code .git} directory live.
 *
 * <p>The walk-up itself is pure filesystem logic (no JGit, no git binary), so it is
 * cheap and fully unit-testable headless. It correctly handles the case where
 * {@code .git} is a <em>file</em> rather than a directory: submodules and linked
 * worktrees store a {@code gitdir: <path>} pointer in a plain {@code .git} file, and
 * the probe resolves that pointer to the actual git directory.
 *
 * <p>The JGit-backed ignore semantics (nested {@code .gitignore}, {@code info/exclude}
 * and {@code core.excludesFile}) are exposed lazily through {@link #ignoreMatcher()}
 * so the detection layer stays free of heavyweight dependencies.
 *
 * <p>Instances are immutable; obtain one via {@link #probe(Path)} or
 * {@link #probe(NuclrResource)}.
 */
@Slf4j
public final class GitIgnoreProbe {

	private static final GitIgnoreProbe NOT_IN_REPO = new GitIgnoreProbe(false, null, null);

	private final boolean insideWorkTree;
	private final Path workTreeRoot;
	private final Path gitDir;

	private GitIgnoreProbe(boolean insideWorkTree, Path workTreeRoot, Path gitDir) {
		this.insideWorkTree = insideWorkTree;
		this.workTreeRoot = workTreeRoot;
		this.gitDir = gitDir;
	}

	/**
	 * Probe upwards from the given resource. Resources with no local path (remote
	 * panels, archives) are never inside a Git working tree.
	 */
	public static GitIgnoreProbe probe(NuclrResource resource) {
		if (resource == null || resource.getPath() == null) {
			return NOT_IN_REPO;
		}
		return probe(resource.getPath());
	}

	/**
	 * Walk up from {@code start} looking for a {@code .git} entry. The search begins
	 * at {@code start} itself when it is a directory, otherwise at its parent.
	 *
	 * @param start the starting location, or {@code null}
	 * @return a probe result; {@link #isInsideWorkTree()} is {@code false} when no
	 *         working tree encloses {@code start}
	 */
	public static GitIgnoreProbe probe(Path start) {
		if (start == null) {
			return NOT_IN_REPO;
		}

		Path dir = startDirectoryFor(start);

		while (dir != null) {
			Path gitEntry = dir.resolve(".git");

			if (Files.isDirectory(gitEntry)) {
				return new GitIgnoreProbe(true, dir, gitEntry);
			}

			if (Files.isRegularFile(gitEntry)) {
				// Submodule / linked-worktree pointer: ".git" is a file holding
				// "gitdir: <path>". Resolve it to the real git directory; fall back to
				// the pointer file's own location if the pointer cannot be read.
				Path resolved = resolveGitDirPointer(dir, gitEntry);
				return new GitIgnoreProbe(true, dir, resolved);
			}

			dir = dir.getParent();
		}

		return NOT_IN_REPO;
	}

	private static Path startDirectoryFor(Path start) {
		Path normalized = start.toAbsolutePath().normalize();
		if (Files.isDirectory(normalized)) {
			return normalized;
		}
		Path parent = normalized.getParent();
		return parent != null ? parent : normalized;
	}

	private static Path resolveGitDirPointer(Path worktreeDir, Path gitFile) {
		try {
			for (String line : Files.readAllLines(gitFile, StandardCharsets.UTF_8)) {
				String trimmed = line.trim();
				if (trimmed.startsWith("gitdir:")) {
					String target = trimmed.substring("gitdir:".length()).trim();
					if (target.isEmpty()) {
						break;
					}
					Path pointer = Path.of(target);
					if (!pointer.isAbsolute()) {
						pointer = worktreeDir.resolve(pointer);
					}
					return pointer.normalize();
				}
			}
		} catch (IOException e) {
			log.debug("Unable to read .git pointer file {}: {}", gitFile, e.getMessage());
		}
		// Pointer unreadable / malformed — keep the pointer file location as a best effort.
		return gitFile;
	}

	/** {@code true} if the probed location is inside a Git working tree. */
	public boolean isInsideWorkTree() {
		return insideWorkTree;
	}

	/** The work-tree root (directory containing {@code .git}), or {@code null}. */
	public Path getWorkTreeRoot() {
		return workTreeRoot;
	}

	/**
	 * The resolved git directory — for a normal checkout this is {@code <root>/.git},
	 * for a submodule / linked worktree it is the directory the {@code .git} file
	 * pointer references. {@code null} when not inside a working tree.
	 */
	public Path getGitDir() {
		return gitDir;
	}

	/**
	 * Build the JGit-backed ignore matcher for this working tree, exposing the
	 * resolved {@code IgnoreNode} chain (root {@code .gitignore}, {@code info/exclude}
	 * and {@code core.excludesFile}) for use during traversal. Returns {@code null}
	 * when the location is not inside a working tree, or when the matcher cannot be
	 * built (in which case traversal simply proceeds without ignore filtering).
	 */
	public GitIgnoreMatcher ignoreMatcher() {
		if (!insideWorkTree || workTreeRoot == null) {
			return null;
		}
		try {
			return GitIgnoreMatcher.forWorkTree(workTreeRoot, gitDir);
		} catch (RuntimeException e) {
			log.warn("Failed to build gitignore matcher for {}: {}", workTreeRoot, e.getMessage());
			return null;
		}
	}

	@Override
	public String toString() {
		return "GitIgnoreProbe{insideWorkTree=" + insideWorkTree
				+ ", workTreeRoot=" + workTreeRoot
				+ ", gitDir=" + gitDir + "}";
	}

	/** The {@code .git} directory names treated as VCS metadata and always skipped during traversal. */
	public static List<String> alwaysExcludedDirectoryNames() {
		return List.of(".git");
	}
}
