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

import org.junit.jupiter.api.Assumptions;
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
	void singleFileToNonDirectoryDestinationRenamesInsteadOfCreatingFolder(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path file = Files.writeString(srcDir.resolve("a.txt"), "hello");
		Path renamed = srcDir.resolve("b.txt"); // same folder, new name — does not exist yet

		boolean ok = new CopyEngine(options(renamed, CopyOptions.ConflictMode.ASK), new RecordingCallback(), null, null)
				.copy(List.of(file));

		assertTrue(ok);
		assertTrue(Files.isRegularFile(renamed), "destination should be a file, not a folder");
		assertEquals("hello", Files.readString(renamed));
		assertFalse(Files.isDirectory(renamed));
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

	/** F5 within one panel targets the source itself; Overwrite must not truncate it. */
	@Test
	void overwritingFileWithItselfLeavesItIntact(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "precious");

		RecordingCallback cb = new RecordingCallback();
		boolean ok = new CopyEngine(options(dir, CopyOptions.ConflictMode.OVERWRITE), cb, null, (s, e) -> true)
				.copy(List.of(file));

		assertTrue(ok);
		assertEquals("precious", Files.readString(file));
		assertEquals(1, cb.errorCount);
	}

	@Test
	void appendingFileToItselfLeavesItIntact(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "precious");

		new CopyEngine(options(dir, CopyOptions.ConflictMode.APPEND), new RecordingCallback(), null, (s, e) -> true)
				.copy(List.of(file));

		assertEquals("precious", Files.readString(file));
	}

	@Test
	void renamingFileCopiedOntoItselfMakesADuplicate(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "precious");

		boolean ok = new CopyEngine(options(dir, CopyOptions.ConflictMode.RENAME), new RecordingCallback(), null,
				null).copy(List.of(file));

		assertTrue(ok);
		assertEquals("precious", Files.readString(file));
		assertEquals("precious", Files.readString(dir.resolve("a (2).txt")));
	}

	@Test
	void refusesToCopyFolderIntoItsOwnSubfolder(@TempDir Path dir) throws IOException {
		Path tree = Files.createDirectory(dir.resolve("tree"));
		Files.writeString(tree.resolve("top.txt"), "1");
		Path sub = Files.createDirectory(tree.resolve("sub"));

		RecordingCallback cb = new RecordingCallback();
		boolean ok = new CopyEngine(options(sub, CopyOptions.ConflictMode.ASK), cb, null, (s, e) -> true)
				.copy(List.of(tree));

		assertTrue(ok);
		assertEquals(1, cb.errorCount);
		assertFalse(Files.exists(sub.resolve("tree")), "nothing may be copied into the source's own subtree");
	}

	@Test
	void refusesToCopyFolderOntoItself(@TempDir Path dir) throws IOException {
		Path tree = Files.createDirectory(dir.resolve("tree"));
		Files.writeString(tree.resolve("top.txt"), "1");

		RecordingCallback cb = new RecordingCallback();
		new CopyEngine(options(dir, CopyOptions.ConflictMode.OVERWRITE), cb, null, (s, e) -> true)
				.copy(List.of(tree));

		assertEquals(1, cb.errorCount);
		assertEquals("1", Files.readString(tree.resolve("top.txt")));
	}

	@Test
	void skipModeKeepsExistingTargetOfCopiedLink(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Path link = createLinkOrSkip(srcDir.resolve("a.txt"), Path.of("elsewhere.txt"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.SKIP), new RecordingCallback(), null,
				null).copy(List.of(link));

		assertTrue(ok);
		assertFalse(Files.isSymbolicLink(dstDir.resolve("a.txt")));
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void renameModeCopiesLinkBesideExistingTarget(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Path link = createLinkOrSkip(srcDir.resolve("a.txt"), Path.of("elsewhere.txt"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		new CopyEngine(options(dstDir, CopyOptions.ConflictMode.RENAME), new RecordingCallback(), null, null)
				.copy(List.of(link));

		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
		assertTrue(Files.isSymbolicLink(dstDir.resolve("a (2).txt")));
	}

	@Test
	void renamingFolderOverExistingFileKeepsTheFile(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path tree = Files.createDirectory(srcDir.resolve("tree"));
		Files.writeString(tree.resolve("top.txt"), "1");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("tree"), "a file, not a folder");

		CopyEngine.ConflictResolver resolver = (s, t) -> Resolution.of(Action.RENAME);
		boolean ok = new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), new RecordingCallback(), resolver,
				null).copy(List.of(tree));

		assertTrue(ok);
		assertEquals("a file, not a folder", Files.readString(dstDir.resolve("tree")));
		assertEquals("1", Files.readString(dstDir.resolve("tree (2)/top.txt")));
	}

	@Test
	void skipModeKeepsFileBlockingACopiedFolder(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path tree = Files.createDirectory(srcDir.resolve("tree"));
		Files.writeString(tree.resolve("top.txt"), "1");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("tree"), "a file, not a folder");

		new CopyEngine(options(dstDir, CopyOptions.ConflictMode.SKIP), new RecordingCallback(), null, null)
				.copy(List.of(tree));

		assertEquals("a file, not a folder", Files.readString(dstDir.resolve("tree")));
	}

	@Test
	void refusesRenameThatPointsBackIntoTheCopiedFolder(@TempDir Path dir) throws IOException {
		Path tree = Files.createDirectory(dir.resolve("tree"));
		Files.writeString(tree.resolve("top.txt"), "1");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("tree"), "a file, not a folder");

		CopyEngine.ConflictResolver resolver = (s, t) -> new Resolution(Action.RENAME, tree.resolve("inside"));
		RecordingCallback cb = new RecordingCallback();
		new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), cb, resolver, (s, e) -> true)
				.copy(List.of(tree));

		assertEquals(1, cb.errorCount);
		assertFalse(Files.exists(tree.resolve("inside")), "nothing may be copied into the source itself");
	}

	@Test
	void bareRenameNameLandsBesideTheConflictingTarget(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		CopyEngine.ConflictResolver resolver = (s, t) -> new Resolution(Action.RENAME, Path.of("renamed.txt"));
		new CopyEngine(options(dstDir, CopyOptions.ConflictMode.ASK), new RecordingCallback(), resolver, null)
				.copy(List.of(file));

		assertEquals("NEW", Files.readString(dstDir.resolve("renamed.txt")));
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void copiesLinkIntoFolderThatDoesNotExistYet(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path link = createLinkOrSkip(srcDir.resolve("a.lnk"), Path.of("elsewhere.txt"));
		Path target = dir.resolve("new-folder").resolve("a.lnk");

		boolean ok = new CopyEngine(options(target, CopyOptions.ConflictMode.ASK), new RecordingCallback(), null,
				null).copy(List.of(link));

		assertTrue(ok);
		assertTrue(Files.isSymbolicLink(target));
	}

	/** Symbolic links need a privilege on Windows that a test run may not have. */
	private static Path createLinkOrSkip(Path link, Path target) {
		try {
			return Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException e) {
			Assumptions.abort("symbolic links unavailable: " + e.getMessage());
			return null;
		}
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
