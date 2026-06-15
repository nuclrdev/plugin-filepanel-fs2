/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.fs.service.CopyEngine.Action;
import dev.nuclr.plugin.core.panel.fs.service.CopyEngine.Resolution;
import dev.nuclr.plugin.core.panel.fs.support.RecordingCallback;

/**
 * Tests for the UI-agnostic {@link CopyEngine}. Conflict prompts are stubbed with a plain
 * {@link CopyEngine.ConflictResolver} lambda so no Swing dialog is reached (surefire runs
 * headless and would throw on any modal window).
 */
class CopyEngineTest {

	private static CopyOptions options(Path destination, CopyOptions.ConflictMode mode) {
		CopyOptions opts = new CopyOptions();
		opts.setDestination(destination);
		opts.setConflictMode(mode);
		opts.setAskOnReadOnly(false); // keep fixed-mode tests deterministic
		return opts;
	}

	@Test
	void copiesSingleFile(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Path file = Files.writeString(srcDir.resolve("a.txt"), "hello");

		RecordingCallback cb = new RecordingCallback();
		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), cb, null, null)
				.copy(List.of(file));

		assertTrue(ok);
		assertEquals("hello", Files.readString(dstDir.resolve("a.txt")));
		assertEquals(List.of("a.txt"), cb.started);
		assertEquals(1, cb.completeCount);
		assertTrue(cb.progressCount >= 1);
	}

	@Test
	void copiesDirectoryTreeRecursively(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path tree = Files.createDirectory(srcDir.resolve("tree"));
		Files.writeString(tree.resolve("top.txt"), "1");
		Path sub = Files.createDirectory(tree.resolve("sub"));
		Files.writeString(sub.resolve("deep.txt"), "2");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), new RecordingCallback(), null, null)
				.copy(List.of(tree));

		assertTrue(ok);
		assertEquals("1", Files.readString(dstDir.resolve("tree/top.txt")));
		assertEquals("2", Files.readString(dstDir.resolve("tree/sub/deep.txt")));
	}

	@Test
	void overwriteConflictReplacesExisting(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.OVERWRITE), new RecordingCallback(),
				null, null).copy(List.of(file));

		assertTrue(ok);
		assertEquals("NEW", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void skipConflictKeepsExisting(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.SKIP), new RecordingCallback(), null, null)
				.copy(List.of(file));

		assertTrue(ok);
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void renameConflictWritesNumberedSibling(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.RENAME), new RecordingCallback(),
				null, null).copy(List.of(file));

		assertTrue(ok);
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
		assertEquals("NEW", Files.readString(dstDir.resolve("a (2).txt")));
	}

	@Test
	void appendConflictAppendsBytes(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "BBB");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "AAA");

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.APPEND), new RecordingCallback(),
				null, null).copy(List.of(file));

		assertTrue(ok);
		assertEquals("AAABBB", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void onlyNewerSkipsOlderSource(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Path existing = Files.writeString(dstDir.resolve("a.txt"), "OLD");

		// Make the source older than the existing target.
		Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));
		Files.setLastModifiedTime(existing, FileTime.from(Instant.now()));

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ONLY_NEWER), new RecordingCallback(),
				null, null).copy(List.of(file));

		assertTrue(ok);
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void askModeConsultsResolver(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean[] asked = { false };
		CopyEngine.ConflictResolver resolver = (s, t) -> {
			asked[0] = true;
			return Resolution.of(Action.OVERWRITE);
		};

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), new RecordingCallback(),
				resolver, null).copy(List.of(file));

		assertTrue(ok);
		assertTrue(asked[0]);
		assertEquals("NEW", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void cancelResolutionAbortsRun(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		CopyEngine.ConflictResolver resolver = (s, t) -> Resolution.of(Action.CANCEL);

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), new RecordingCallback(),
				resolver, null).copy(List.of(file));

		assertFalse(ok);
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void cancellationStopsBeforeNextFile(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path a = Files.writeString(srcDir.resolve("a.txt"), "a");
		Path b = Files.writeString(srcDir.resolve("b.txt"), "b");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));

		RecordingCallback cb = new RecordingCallback();
		cb.cancelled = true; // cancelled before any work

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), cb, null, null)
				.copy(List.of(a, b));

		assertFalse(ok);
		assertFalse(Files.exists(dstDir.resolve("a.txt")));
		assertFalse(Files.exists(dstDir.resolve("b.txt")));
	}

	@Test
	void autoRenameFindsFirstFreeSibling(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("nuclr.jar"), "x");
		Files.writeString(dir.resolve("nuclr (2).jar"), "x");

		Path renamed = CopyEngine.autoRename(dir.resolve("nuclr.jar"));

		assertEquals("nuclr (3).jar", renamed.getFileName().toString());
	}

	@Test
	void reportsByteProgressTotals(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Files.write(srcDir.resolve("big.bin"), new byte[200_000]);
		Path dstDir = Files.createDirectory(dir.resolve("dst"));

		RecordingCallback cb = new RecordingCallback();
		new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), cb, null, null)
				.copy(List.of(srcDir.resolve("big.bin")));

		// 200 KB at a 64 KB buffer → several progress callbacks.
		assertTrue(cb.progressCount >= 3, "expected multiple progress updates, got " + cb.progressCount);
		assertEquals(200_000, Files.size(dstDir.resolve("big.bin")));
	}
}
