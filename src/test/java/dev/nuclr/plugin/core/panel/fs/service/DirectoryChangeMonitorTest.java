/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryChangeMonitorTest {

	@Test
	void pollingFallbackDetectsAChangedOpenDirectory(@TempDir Path directory) throws Exception {
		CountDownLatch changed = new CountDownLatch(1);
		var notifications = new CopyOnWriteArrayList<Path>();
		try (var monitor = monitor(path -> {
			notifications.add(path);
			changed.countDown();
		})) {
			monitor.watch(directory, java.util.List.of());
			Files.writeString(directory.resolve("new.txt"), "new");

			assertTrue(changed.await(2, TimeUnit.SECONDS));
			assertEquals(directory.toAbsolutePath().normalize(), notifications.get(0));
		}
	}

	@Test
	void changingTheWatchedDirectoryStopsNotificationsForTheOldOne(@TempDir Path root) throws Exception {
		Path first = Files.createDirectory(root.resolve("first"));
		Path second = Files.createDirectory(root.resolve("second"));
		CountDownLatch changed = new CountDownLatch(1);
		var notifications = new CopyOnWriteArrayList<Path>();
		try (var monitor = monitor(path -> {
			notifications.add(path);
			changed.countDown();
		})) {
			monitor.watch(first, java.util.List.of());
			monitor.watch(second, java.util.List.of());
			Files.writeString(first.resolve("ignored.txt"), "old");
			Thread.sleep(120);
			assertFalse(changed.await(100, TimeUnit.MILLISECONDS));

			Files.writeString(second.resolve("noticed.txt"), "current");
			assertTrue(changed.await(2, TimeUnit.SECONDS));
			assertEquals(java.util.List.of(second.toAbsolutePath().normalize()), notifications);
		}
	}

	@Test
	void sustainedChangesCannotStarveTheDebouncedRefresh(@TempDir Path directory) throws Exception {
		CountDownLatch changed = new CountDownLatch(1);
		try (var monitor = new DirectoryChangeMonitor(ignored -> changed.countDown(),
				Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(20),
				Duration.ofSeconds(1), false)) {
			monitor.watch(directory);
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
			int sequence = 0;
			while (System.nanoTime() < deadline && changed.getCount() != 0) {
				Files.writeString(directory.resolve("changing.txt"), Integer.toString(sequence++));
				Thread.sleep(30);
			}

			assertTrue(changed.await(100, TimeUnit.MILLISECONDS),
					"continuous changes must be published at the maximum dirty age");
		}
	}

	@Test
	void nativeWatchServiceDetectsCreateBeforeThePollingDeadline(@TempDir Path directory) throws Exception {
		CountDownLatch changed = new CountDownLatch(1);
		try (var monitor = new DirectoryChangeMonitor(ignored -> changed.countDown(),
				Duration.ofMillis(20), Duration.ofMillis(250), Duration.ofSeconds(10),
				Duration.ofSeconds(10), true)) {
			monitor.watch(directory, java.util.List.of());
			Thread.sleep(100); // allow the virtual worker to register the native key
			Files.writeString(directory.resolve("native.txt"), "created");

			assertTrue(changed.await(2, TimeUnit.SECONDS));
		}
	}

	@Test
	void invalidNativeWatchKeyStillPublishesAChange(@TempDir Path root) throws Exception {
		Path directory = Files.createDirectory(root.resolve("watched"));
		CountDownLatch changed = new CountDownLatch(1);
		try (var monitor = new DirectoryChangeMonitor(ignored -> changed.countDown(),
				Duration.ofMillis(20), Duration.ofMillis(250), Duration.ofSeconds(10),
				Duration.ofSeconds(10), true)) {
			monitor.watch(directory, java.util.List.of());
			Thread.sleep(100);
			Files.delete(directory);

			assertTrue(changed.await(2, TimeUnit.SECONDS));
		}
	}

	private static DirectoryChangeMonitor monitor(java.util.function.Consumer<Path> listener) {
		return new DirectoryChangeMonitor(listener, Duration.ofMillis(20), Duration.ofMillis(40), false);
	}
}
