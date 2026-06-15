/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.support.RecordingCallback;
import dev.nuclr.plugin.core.panel.fs.support.TestResource;

/**
 * Tests for {@link DeleteService}. Every case forces {@code permanent = true} so the
 * physical (recursive) delete path is exercised rather than the platform recycle bin,
 * which is environment dependent and unavailable headless.
 */
class DeleteServiceTest {

	private static NuclrResource resourceFor(Path p) {
		return new TestResource(p);
	}

	@Test
	void deletesSingleFilePhysically(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("victim.txt");
		Files.writeString(file, "x");
		RecordingCallback cb = new RecordingCallback();

		DeleteService.delete(List.of(resourceFor(file)), true, cb, null);

		assertFalse(Files.exists(file));
		assertEquals(List.of("victim.txt"), cb.started);
		assertEquals(1, cb.completeCount);
		assertTrue(cb.progressCount >= 1);
		assertEquals(0, cb.errorCount);
	}

	@Test
	void deletesDirectoryTreeRecursively(@TempDir Path dir) throws IOException {
		Path root = dir.resolve("tree");
		Path sub = root.resolve("sub");
		Files.createDirectories(sub);
		Files.writeString(root.resolve("a.txt"), "a");
		Files.writeString(sub.resolve("b.txt"), "b");
		RecordingCallback cb = new RecordingCallback();

		DeleteService.delete(List.of(resourceFor(root)), true, cb, null);

		assertFalse(Files.exists(root));
		assertEquals(1, cb.completeCount);
	}

	@Test
	void cancelledBeforeStart_deletesNothing(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("keep.txt");
		Files.writeString(file, "x");
		RecordingCallback cb = new RecordingCallback();
		cb.cancelled = true;

		DeleteService.delete(List.of(resourceFor(file)), true, cb, null);

		assertTrue(Files.exists(file), "cancellation before start must not delete");
		assertTrue(cb.started.isEmpty());
		assertEquals(0, cb.completeCount);
	}

	@Test
	void cancellationMidDirectory_leavesItPartiallyDeletedAndUnreported(@TempDir Path dir) throws IOException {
		Path root = dir.resolve("partial");
		Files.createDirectories(root);
		for (int i = 0; i < 5; i++) {
			Files.writeString(root.resolve("f" + i + ".txt"), "x");
		}
		RecordingCallback cb = new RecordingCallback();
		cb.cancelAfterProgress = 1; // cancel after the first child is removed

		DeleteService.delete(List.of(resourceFor(root)), true, cb, null);

		assertTrue(Files.exists(root), "directory must survive a mid-delete cancel");
		try (var s = Files.list(root)) {
			long remaining = s.count();
			assertTrue(remaining > 0 && remaining < 5, "some but not all children removed, was " + remaining);
		}
		assertEquals(0, cb.completeCount, "a cancelled item is never reported complete");
	}

	@Test
	void nullCallbackAndErrorPrompt_areTolerated(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("n.txt");
		Files.writeString(file, "x");

		DeleteService.delete(List.of(resourceFor(file)), true, null, null);

		assertFalse(Files.exists(file));
	}

	@Test
	void resourceWithNullPath_isSkippedAndOthersStillDeleted(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("real.txt");
		Files.writeString(file, "x");
		List<NuclrResource> sources = new ArrayList<>();
		sources.add(new TestResource(null)); // null path -> skipped
		sources.add(resourceFor(file));
		RecordingCallback cb = new RecordingCallback();

		DeleteService.delete(sources, true, cb, null);

		assertFalse(Files.exists(file));
		assertEquals(List.of("real.txt"), cb.started);
	}

	@Test
	void errorPrompt_skipContinuesWithRemainingItems(@TempDir Path dir) throws IOException {
		assumeTrue(SystemUtils.IS_OS_WINDOWS, "file-lock based delete failure is Windows-specific");

		Path locked = dir.resolve("locked.bin");
		Path normal = dir.resolve("normal.txt");
		Files.write(locked, new byte[] { 1 });
		Files.writeString(normal, "x");
		RecordingCallback cb = new RecordingCallback();
		int[] prompts = { 0 };

		// java.io.FileInputStream opens the file WITHOUT share-delete on Windows, so the
		// file cannot be deleted while the stream is held — a deterministic delete failure.
		try (InputStream held = new FileInputStream(locked.toFile())) {
			DeleteService.delete(List.of(resourceFor(locked), resourceFor(normal)), true, cb, (item, e) -> {
				prompts[0]++;
				return true; // skip and continue
			});
		}

		assertEquals(1, prompts[0], "the locked file should have triggered exactly one prompt");
		assertTrue(Files.exists(locked), "locked file could not be deleted");
		assertFalse(Files.exists(normal), "the subsequent file must still be deleted after a skip");
		assertEquals(1, cb.errorCount);
	}

	@Test
	void errorPrompt_abortStopsRemainingItems(@TempDir Path dir) throws IOException {
		assumeTrue(SystemUtils.IS_OS_WINDOWS, "file-lock based delete failure is Windows-specific");

		Path locked = dir.resolve("locked.bin");
		Path normal = dir.resolve("normal.txt");
		Files.write(locked, new byte[] { 1 });
		Files.writeString(normal, "x");
		RecordingCallback cb = new RecordingCallback();

		try (InputStream held = new FileInputStream(locked.toFile())) {
			DeleteService.delete(List.of(resourceFor(locked), resourceFor(normal)), true, cb, (item, e) -> false);
		}

		assertTrue(Files.exists(normal), "abort must stop before the next item");
	}

	@Test
	void symbolicLinkToDirectory_isUnlinkedNotDescendedInto(@TempDir Path dir) throws IOException {
		Path target = dir.resolve("target");
		Files.createDirectories(target);
		Path payload = target.resolve("precious.txt");
		Files.writeString(payload, "do not delete me");

		Path link = dir.resolve("link");
		try {
			Files.createSymbolicLink(link, target);
		} catch (IOException | UnsupportedOperationException noPrivilege) {
			assumeTrue(false, "cannot create symlinks in this environment: " + noPrivilege.getMessage());
		}

		DeleteService.delete(List.of(resourceFor(link)), true, new RecordingCallback(), null);

		assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the link itself must be removed");
		assertTrue(Files.exists(target), "the link target directory must survive");
		assertTrue(Files.exists(payload), "files under the link target must survive");
	}
}
