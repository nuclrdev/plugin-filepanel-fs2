/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service.move;

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

import dev.nuclr.plugin.core.panel.fs.service.move.MoveEngine.Action;
import dev.nuclr.plugin.core.panel.fs.service.move.MoveEngine.Resolution;
import dev.nuclr.plugin.core.panel.fs.support.RecordingCallback;

/**
 * Tests for the UI-agnostic {@link MoveEngine}. Mirrors {@code CopyEngineTest} but also covers the
 * move-specific behaviour the copy engine lacks: the same-store atomic rename fast path (which also
 * removes the source), the directory-merge fallback when the target folder already exists, and the
 * "move onto itself" guards added in commit {@code 2fd992a}.
 *
 * <p>Conflict prompts are stubbed with a plain {@link MoveEngine.ConflictResolver} lambda so no
 * Swing dialog is reached (surefire runs headless and would throw on any modal window). The error
 * prompt always returns {@code true} (skip-and-continue) so a stray error never blocks a run.
 *
 * <p>All cases run within a single {@link TempDir}, so source and target share one file store and
 * take the atomic-rename path; the cross-volume copy+delete fallback cannot be provoked portably and
 * shares its byte-streaming code with the append path, which <em>is</em> exercised here.
 */
class MoveEngineTest {

	private static MoveOptions options(Path destination, MoveOptions.ConflictMode mode) {
		MoveOptions opts = new MoveOptions();
		opts.setDestination(destination);
		opts.setConflictMode(mode);
		opts.setAskOnReadOnly(false); // keep fixed-mode tests deterministic
		return opts;
	}

	/** Move a single file into a destination folder: it lands there and the source is gone. */
	@Test
	void movesSingleFileIntoFolderAndRemovesSource(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Path file = Files.writeString(srcDir.resolve("a.txt"), "hello");

		RecordingCallback cb = new RecordingCallback();
		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.ASK), cb, null, (s, e) -> true, false)
				.move(List.of(file));

		assertTrue(ok);
		assertEquals("hello", Files.readString(dstDir.resolve("a.txt")));
		assertFalse(Files.exists(file), "the source must be removed after a move");
		assertEquals(List.of("a.txt"), cb.started);
		assertEquals(1, cb.completeCount);
		assertTrue(cb.progressCount >= 1);
	}

	/** With an explicit target the destination names the file itself (an in-place rename). */
	@Test
	void renamesSingleFileToExplicitTarget(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path file = Files.writeString(srcDir.resolve("a.txt"), "hello");
		Path renamed = srcDir.resolve("b.txt"); // same folder, new name — does not exist yet

		boolean ok = new MoveEngine(options(renamed, MoveOptions.ConflictMode.ASK), new RecordingCallback(), null,
				(s, e) -> true, true).move(List.of(file));

		assertTrue(ok);
		assertTrue(Files.isRegularFile(renamed), "destination should be the renamed file, not a folder");
		assertEquals("hello", Files.readString(renamed));
		assertFalse(Files.exists(file), "the original name must be gone after a rename");
	}

	/** A whole directory tree relocates (same-store atomic move) and the source disappears. */
	@Test
	void movesDirectoryTreeAndRemovesSource(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path tree = Files.createDirectory(srcDir.resolve("tree"));
		Files.writeString(tree.resolve("top.txt"), "1");
		Path sub = Files.createDirectory(tree.resolve("sub"));
		Files.writeString(sub.resolve("deep.txt"), "2");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.ASK), new RecordingCallback(), null,
				(s, e) -> true, false).move(List.of(tree));

		assertTrue(ok);
		assertEquals("1", Files.readString(dstDir.resolve("tree/top.txt")));
		assertEquals("2", Files.readString(dstDir.resolve("tree/sub/deep.txt")));
		assertFalse(Files.exists(tree), "the source tree must be gone after the move");
	}

	/** When the target folder already exists the move merges into it, child by child. */
	@Test
	void mergesIntoExistingTargetDirectory(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path tree = Files.createDirectory(srcDir.resolve("tree"));
		Files.writeString(tree.resolve("new.txt"), "new");

		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Path existingTree = Files.createDirectory(dstDir.resolve("tree")); // target dir already present
		Files.writeString(existingTree.resolve("old.txt"), "old");

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.ASK), new RecordingCallback(), null,
				(s, e) -> true, false).move(List.of(tree));

		assertTrue(ok);
		assertEquals("old", Files.readString(existingTree.resolve("old.txt")), "pre-existing child must survive");
		assertEquals("new", Files.readString(existingTree.resolve("new.txt")), "moved child must be merged in");
		assertFalse(Files.exists(tree), "emptied source directory must be removed after a full merge");
	}

	/** A directory moved onto itself is a no-op (regression for commit 2fd992a). */
	@Test
	void directoryMovedOntoItselfIsNoOp(@TempDir Path dir) throws IOException {
		Path tree = Files.createDirectory(dir.resolve("tree"));
		Files.writeString(tree.resolve("keep.txt"), "keep");

		RecordingCallback cb = new RecordingCallback();
		// Explicit target == the source path: the user "moved" the folder to where it already is.
		boolean ok = new MoveEngine(options(tree, MoveOptions.ConflictMode.OVERWRITE), cb, null, (s, e) -> true, true)
				.move(List.of(tree));

		assertTrue(ok);
		assertTrue(Files.exists(tree), "the directory must be left untouched");
		assertEquals("keep", Files.readString(tree.resolve("keep.txt")));
		assertTrue(cb.started.isEmpty(), "a self-move must not even start work on the entry");
		assertEquals(0, cb.completeCount);
	}

	/** A file "moved" to its own path is left intact rather than truncated onto itself. */
	@Test
	void fileMovedOntoItselfIsLeftIntact(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "content");

		RecordingCallback cb = new RecordingCallback();
		boolean ok = new MoveEngine(options(file, MoveOptions.ConflictMode.OVERWRITE), cb, null, (s, e) -> true, true)
				.move(List.of(file));

		assertTrue(ok);
		assertTrue(Files.exists(file));
		assertEquals("content", Files.readString(file), "the file must not be emptied by a move onto itself");
		assertEquals(1, cb.completeCount);
	}

	@Test
	void overwriteConflictReplacesExisting(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.OVERWRITE), new RecordingCallback(), null,
				(s, e) -> true, false).move(List.of(file));

		assertTrue(ok);
		assertEquals("NEW", Files.readString(dstDir.resolve("a.txt")));
		assertFalse(Files.exists(file));
	}

	@Test
	void skipConflictKeepsExistingAndLeavesSource(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.SKIP), new RecordingCallback(), null,
				(s, e) -> true, false).move(List.of(file));

		assertTrue(ok);
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
		assertTrue(Files.exists(file), "a skipped source is not removed");
	}

	@Test
	void renameConflictWritesNumberedSibling(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.RENAME), new RecordingCallback(), null,
				(s, e) -> true, false).move(List.of(file));

		assertTrue(ok);
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
		assertEquals("NEW", Files.readString(dstDir.resolve("a (2).txt")));
		assertFalse(Files.exists(file));
	}

	@Test
	void appendConflictAppendsBytesAndRemovesSource(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "BBB");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "AAA");

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.APPEND), new RecordingCallback(), null,
				(s, e) -> true, false).move(List.of(file));

		assertTrue(ok);
		assertEquals("AAABBB", Files.readString(dstDir.resolve("a.txt")));
		assertFalse(Files.exists(file), "an appended source is deleted afterwards");
	}

	@Test
	void onlyNewerSkipsOlderSource(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Path existing = Files.writeString(dstDir.resolve("a.txt"), "OLD");

		Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(2, ChronoUnit.HOURS)));
		Files.setLastModifiedTime(existing, FileTime.from(Instant.now()));

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.ONLY_NEWER), new RecordingCallback(), null,
				(s, e) -> true, false).move(List.of(file));

		assertTrue(ok);
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
		assertTrue(Files.exists(file), "the older source is skipped, hence not removed");
	}

	@Test
	void askModeConsultsResolver(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		boolean[] asked = { false };
		MoveEngine.ConflictResolver resolver = (s, t) -> {
			asked[0] = true;
			return Resolution.of(Action.OVERWRITE);
		};

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.ASK), new RecordingCallback(), resolver,
				(s, e) -> true, false).move(List.of(file));

		assertTrue(ok);
		assertTrue(asked[0]);
		assertEquals("NEW", Files.readString(dstDir.resolve("a.txt")));
	}

	@Test
	void cancelResolutionAbortsRunAndKeepsSource(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "NEW");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.writeString(dstDir.resolve("a.txt"), "OLD");

		MoveEngine.ConflictResolver resolver = (s, t) -> Resolution.of(Action.CANCEL);

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.ASK), new RecordingCallback(), resolver,
				(s, e) -> true, false).move(List.of(file));

		assertFalse(ok);
		assertEquals("OLD", Files.readString(dstDir.resolve("a.txt")));
		assertTrue(Files.exists(file), "an aborted move must leave the source in place");
	}

	@Test
	void cancellationStopsBeforeFirstFile(@TempDir Path dir) throws IOException {
		Path srcDir = Files.createDirectory(dir.resolve("src"));
		Path a = Files.writeString(srcDir.resolve("a.txt"), "a");
		Path b = Files.writeString(srcDir.resolve("b.txt"), "b");
		Path dstDir = Files.createDirectory(dir.resolve("dst"));

		RecordingCallback cb = new RecordingCallback();
		cb.cancelled = true; // cancelled before any work

		boolean ok = new MoveEngine(options(dstDir, MoveOptions.ConflictMode.ASK), cb, null, (s, e) -> true, false)
				.move(List.of(a, b));

		assertFalse(ok);
		assertFalse(Files.exists(dstDir.resolve("a.txt")));
		assertFalse(Files.exists(dstDir.resolve("b.txt")));
		assertTrue(Files.exists(a) && Files.exists(b), "nothing was moved, so both sources remain");
	}

	@Test
	void nullDestinationReturnsFalse(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("a.txt"), "x");
		MoveOptions opts = new MoveOptions();
		opts.setDestination(null);

		boolean ok = new MoveEngine(opts, new RecordingCallback(), null, (s, e) -> true, false).move(List.of(file));

		assertFalse(ok);
		assertTrue(Files.exists(file));
	}

	@Test
	void appendReportsMultipleByteProgressUpdates(@TempDir Path dir) throws IOException {
		Path file = Files.write(dir.resolve("big.bin"), new byte[200_000]);
		Path dstDir = Files.createDirectory(dir.resolve("dst"));
		Files.write(dstDir.resolve("big.bin"), new byte[] { 1 }); // existing target -> APPEND streams the source

		RecordingCallback cb = new RecordingCallback();
		new MoveEngine(options(dstDir, MoveOptions.ConflictMode.APPEND), cb, null, (s, e) -> true, false)
				.move(List.of(file));

		// 200 KB streamed at a 64 KB buffer -> several progress callbacks.
		assertTrue(cb.progressCount >= 3, "expected multiple progress updates, got " + cb.progressCount);
		assertEquals(200_001, Files.size(dstDir.resolve("big.bin")));
		assertFalse(Files.exists(file));
	}

	@Test
	void autoRenameFindsFirstFreeSibling(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("nuclr.jar"), "x");
		Files.writeString(dir.resolve("nuclr (2).jar"), "x");

		Path renamed = MoveEngine.autoRename(dir.resolve("nuclr.jar"));

		assertEquals("nuclr (3).jar", renamed.getFileName().toString());
	}
}
