/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.find;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.fs.support.TestResource;

/** Walk-up detection tests for {@link GitIgnoreProbe}, including the {@code .git}-file edge case. */
class GitIgnoreProbeTest {

	@Test
	void notInsideWorkTreeWhenNoGitDir(@TempDir Path tmp) throws IOException {
		Path sub = Files.createDirectories(tmp.resolve("a/b/c"));
		GitIgnoreProbe probe = GitIgnoreProbe.probe(sub);

		assertFalse(probe.isInsideWorkTree());
		assertNull(probe.getWorkTreeRoot());
		assertNull(probe.getGitDir());
		assertNull(probe.ignoreMatcher());
	}

	@Test
	void detectsGitDirectoryFromNestedFile(@TempDir Path tmp) throws IOException {
		Path root = tmp.resolve("repo");
		Files.createDirectories(root.resolve(".git"));
		Path nestedFile = root.resolve("src/main/App.java");
		Files.createDirectories(nestedFile.getParent());
		Files.writeString(nestedFile, "class App {}");

		GitIgnoreProbe probe = GitIgnoreProbe.probe(nestedFile);

		assertTrue(probe.isInsideWorkTree());
		assertEquals(root.toRealPath(), probe.getWorkTreeRoot().toRealPath());
		assertEquals(root.resolve(".git").toRealPath(), probe.getGitDir().toRealPath());
	}

	@Test
	void detectsWorkTreeRootFromTheRootItself(@TempDir Path tmp) throws IOException {
		Path root = tmp.resolve("repo");
		Files.createDirectories(root.resolve(".git"));

		GitIgnoreProbe probe = GitIgnoreProbe.probe(root);

		assertTrue(probe.isInsideWorkTree());
		assertEquals(root.toRealPath(), probe.getWorkTreeRoot().toRealPath());
	}

	@Test
	void resolvesGitFilePointerWithAbsolutePath(@TempDir Path tmp) throws IOException {
		// A submodule / linked worktree stores ".git" as a *file* holding a gitdir pointer.
		Path root = Files.createDirectories(tmp.resolve("submodule"));
		Path realGitDir = Files.createDirectories(tmp.resolve("realgitdir"));
		Files.writeString(root.resolve(".git"), "gitdir: " + realGitDir.toAbsolutePath() + "\n",
				StandardCharsets.UTF_8);

		GitIgnoreProbe probe = GitIgnoreProbe.probe(root.resolve("file.txt"));

		assertTrue(probe.isInsideWorkTree());
		assertEquals(root.toRealPath(), probe.getWorkTreeRoot().toRealPath());
		assertEquals(realGitDir.toRealPath(), probe.getGitDir().toRealPath());
	}

	@Test
	void resolvesGitFilePointerWithRelativePath(@TempDir Path tmp) throws IOException {
		Path root = Files.createDirectories(tmp.resolve("worktree"));
		Path realGitDir = Files.createDirectories(tmp.resolve("gitstore"));
		Files.writeString(root.resolve(".git"), "gitdir: ../gitstore\n", StandardCharsets.UTF_8);

		GitIgnoreProbe probe = GitIgnoreProbe.probe(root);

		assertTrue(probe.isInsideWorkTree());
		assertEquals(realGitDir.toRealPath(), probe.getGitDir().toRealPath());
	}

	@Test
	void malformedGitFileStillReportsInsideWorkTree(@TempDir Path tmp) throws IOException {
		Path root = Files.createDirectories(tmp.resolve("weird"));
		Files.writeString(root.resolve(".git"), "not a gitdir pointer\n", StandardCharsets.UTF_8);

		GitIgnoreProbe probe = GitIgnoreProbe.probe(root);

		assertTrue(probe.isInsideWorkTree());
		assertEquals(root.toRealPath(), probe.getWorkTreeRoot().toRealPath());
		// Falls back to the pointer file's own location when the pointer is unreadable.
		assertEquals(root.resolve(".git").toRealPath(), probe.getGitDir().toRealPath());
	}

	@Test
	void nullInputsAreNotInsideWorkTree() {
		assertFalse(GitIgnoreProbe.probe((Path) null).isInsideWorkTree());
		assertFalse(GitIgnoreProbe.probe((dev.nuclr.platform.plugin.NuclrResource) null).isInsideWorkTree());
		assertFalse(GitIgnoreProbe.probe(new TestResource(null)).isInsideWorkTree());
	}

	@Test
	void stopsAtNearestWorkTreeRoot(@TempDir Path tmp) throws IOException {
		// Outer repo contains an inner repo; probing inside the inner one resolves to it.
		Path outer = tmp.resolve("outer");
		Files.createDirectories(outer.resolve(".git"));
		Path inner = outer.resolve("vendor/inner");
		Files.createDirectories(inner.resolve(".git"));
		Path innerFile = Files.createDirectories(inner.resolve("lib")).resolve("x.txt");
		Files.writeString(innerFile, "x");

		GitIgnoreProbe probe = GitIgnoreProbe.probe(innerFile);

		assertEquals(inner.toRealPath(), probe.getWorkTreeRoot().toRealPath());
	}
}
