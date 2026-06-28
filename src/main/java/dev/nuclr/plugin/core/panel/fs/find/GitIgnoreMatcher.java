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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.jgit.ignore.IgnoreNode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import lombok.extern.slf4j.Slf4j;

/**
 * Evaluates Git ignore rules for paths inside a single working tree using JGit's
 * {@link IgnoreNode}. Implements proper nested-ignore semantics: the {@code .gitignore}
 * closest to a path wins, and the {@code .git/info/exclude} plus {@code core.excludesFile}
 * nodes are consulted last, exactly as Git layers them.
 *
 * <p>Per-directory {@link IgnoreNode}s are parsed lazily and cached, so the matcher is
 * safe to share across the virtual-thread search workers (the cache is concurrent and
 * each node is immutable once parsed).
 *
 * <p>Obtain instances via {@link GitIgnoreProbe#ignoreMatcher()}.
 */
@Slf4j
final class GitIgnoreMatcher {

	/** Sentinel meaning "this directory has no .gitignore" — avoids re-probing the filesystem. */
	private static final IgnoreNode EMPTY = new IgnoreNode();

	private final Path workTreeRoot;
	private final IgnoreNode infoExcludeNode;
	private final IgnoreNode globalExcludeNode;
	private final ConcurrentMap<Path, IgnoreNode> perDirectory = new ConcurrentHashMap<>();

	private GitIgnoreMatcher(Path workTreeRoot, IgnoreNode infoExcludeNode, IgnoreNode globalExcludeNode) {
		this.workTreeRoot = workTreeRoot;
		this.infoExcludeNode = infoExcludeNode;
		this.globalExcludeNode = globalExcludeNode;
	}

	/**
	 * Build a matcher for the given working tree. Reads {@code <gitDir>/info/exclude}
	 * and the {@code core.excludesFile} pointed at by the repository config (falling
	 * back to the conventional {@code ~/.config/git/ignore}). Never throws for missing
	 * optional files; a malformed repository degrades to no extra excludes.
	 */
	static GitIgnoreMatcher forWorkTree(Path workTreeRoot, Path gitDir) {
		Path root = workTreeRoot.toAbsolutePath().normalize();
		IgnoreNode infoExclude = gitDir != null ? loadNode(gitDir.resolve("info").resolve("exclude")) : null;
		IgnoreNode globalExclude = loadNode(resolveCoreExcludesFile(gitDir));
		return new GitIgnoreMatcher(root, infoExclude, globalExclude);
	}

	/**
	 * Whether the given path is ignored by the working tree's rules.
	 *
	 * @param target      an absolute path inside (or outside) the working tree
	 * @param isDirectory whether {@code target} denotes a directory
	 * @return {@code true} if Git would ignore the path; {@code false} otherwise, or
	 *         when the path lies outside this working tree
	 */
	boolean isIgnored(Path target, boolean isDirectory) {
		Path abs = target.toAbsolutePath().normalize();
		if (!abs.startsWith(workTreeRoot) || abs.equals(workTreeRoot)) {
			return false;
		}

		// Deepest .gitignore wins: walk from the path's own directory up to the root,
		// taking the first node that gives a definitive ignored / not-ignored answer.
		for (Path dir = abs.getParent(); dir != null && dir.startsWith(workTreeRoot); dir = dir.getParent()) {
			IgnoreNode node = nodeFor(dir);
			if (node != EMPTY) {
				Boolean result = node.checkIgnored(toGitPath(dir, abs), isDirectory);
				if (result != null) {
					return result;
				}
			}
			if (dir.equals(workTreeRoot)) {
				break;
			}
		}

		String rootRelative = toGitPath(workTreeRoot, abs);
		Boolean info = infoExcludeNode == null ? null : infoExcludeNode.checkIgnored(rootRelative, isDirectory);
		if (info != null) {
			return info;
		}
		Boolean global = globalExcludeNode == null ? null : globalExcludeNode.checkIgnored(rootRelative, isDirectory);
		if (global != null) {
			return global;
		}
		return false;
	}

	private IgnoreNode nodeFor(Path directory) {
		return perDirectory.computeIfAbsent(directory, dir -> {
			IgnoreNode node = loadNode(dir.resolve(".gitignore"));
			return node == null ? EMPTY : node;
		});
	}

	/** Path of {@code target} relative to {@code base}, using Git's forward-slash separators. */
	private static String toGitPath(Path base, Path target) {
		String relative = base.relativize(target).toString();
		return relative.replace('\\', '/');
	}

	private static IgnoreNode loadNode(Path ignoreFile) {
		if (ignoreFile == null || !Files.isRegularFile(ignoreFile)) {
			return null;
		}
		IgnoreNode node = new IgnoreNode();
		try (InputStream in = Files.newInputStream(ignoreFile)) {
			node.parse(ignoreFile.toString(), in);
			return node.getRules().isEmpty() ? null : node;
		} catch (IOException e) {
			log.debug("Failed to parse ignore file {}: {}", ignoreFile, e.getMessage());
			return null;
		}
	}

	/**
	 * Resolve {@code core.excludesFile} from the repository configuration, falling
	 * back to the conventional XDG location used by Git when the setting is absent.
	 */
	private static Path resolveCoreExcludesFile(Path gitDir) {
		if (gitDir != null) {
			try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).readEnvironment().build()) {
				String configured = repo.getConfig().getString("core", null, "excludesfile");
				Path expanded = expandUserHome(configured);
				if (expanded != null && Files.isRegularFile(expanded)) {
					return expanded;
				}
			} catch (IOException | RuntimeException e) {
				log.debug("Unable to read core.excludesFile from {}: {}", gitDir, e.getMessage());
			}
		}

		String xdg = System.getenv("XDG_CONFIG_HOME");
		Path xdgIgnore = (xdg != null && !xdg.isBlank())
				? Path.of(xdg, "git", "ignore")
				: Path.of(System.getProperty("user.home", ""), ".config", "git", "ignore");
		return Files.isRegularFile(xdgIgnore) ? xdgIgnore : null;
	}

	private static Path expandUserHome(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.equals("~")) {
			return Path.of(System.getProperty("user.home", ""));
		}
		if (trimmed.startsWith("~/") || trimmed.startsWith("~\\")) {
			return Path.of(System.getProperty("user.home", ""), trimmed.substring(2));
		}
		return Path.of(trimmed);
	}

	/** Exposes the resolved root-level ignore nodes (info/exclude, global) for inspection. */
	List<IgnoreNode> rootIgnoreChain() {
		List<IgnoreNode> chain = new ArrayList<>();
		IgnoreNode rootGitignore = nodeFor(workTreeRoot);
		if (rootGitignore != EMPTY) {
			chain.add(rootGitignore);
		}
		if (infoExcludeNode != null) {
			chain.add(infoExcludeNode);
		}
		if (globalExcludeNode != null) {
			chain.add(globalExcludeNode);
		}
		return chain;
	}
}
