/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

/**
 * Watches one displayed local directory. Native notifications provide low latency; periodic
 * snapshots cover unsupported providers, dropped events, network shares, and watch overflows.
 */
@Slf4j
public final class DirectoryChangeMonitor implements AutoCloseable {

	private static final Duration DEFAULT_DEBOUNCE = Duration.ofMillis(200);
	private static final Duration DEFAULT_MAX_DIRTY_DURATION = Duration.ofSeconds(1);
	private static final Duration DEFAULT_SNAPSHOT_INTERVAL = Duration.ofSeconds(5);
	private static final Duration VERIFIED_NATIVE_SNAPSHOT_INTERVAL = Duration.ofSeconds(30);
	private static final long NO_REFRESH_PENDING = Long.MAX_VALUE;

	private final Consumer<Path> listener;
	private final long debounceNanos;
	private final long maxDirtyNanos;
	private final long snapshotIntervalNanos;
	private final long verifiedNativeSnapshotIntervalNanos;
	private final boolean nativeWatchEnabled;

	private long generation;
	private boolean closed;
	private volatile boolean nativeWatchProven;
	private Thread worker;
	private WatchService activeWatchService;

	public DirectoryChangeMonitor(Consumer<Path> listener) {
		this(listener, DEFAULT_DEBOUNCE, DEFAULT_MAX_DIRTY_DURATION,
				DEFAULT_SNAPSHOT_INTERVAL, VERIFIED_NATIVE_SNAPSHOT_INTERVAL, true);
	}

	DirectoryChangeMonitor(Consumer<Path> listener, Duration debounce, Duration snapshotInterval,
			boolean nativeWatchEnabled) {
		this(listener, debounce, DEFAULT_MAX_DIRTY_DURATION, snapshotInterval,
				VERIFIED_NATIVE_SNAPSHOT_INTERVAL, nativeWatchEnabled);
	}

	DirectoryChangeMonitor(Consumer<Path> listener, Duration debounce, Duration maxDirtyDuration,
			Duration snapshotInterval, Duration verifiedNativeSnapshotInterval,
			boolean nativeWatchEnabled) {
		this.listener = java.util.Objects.requireNonNull(listener);
		this.debounceNanos = positiveNanos(debounce, "debounce");
		this.maxDirtyNanos = positiveNanos(maxDirtyDuration, "maxDirtyDuration");
		this.snapshotIntervalNanos = positiveNanos(snapshotInterval, "snapshotInterval");
		this.verifiedNativeSnapshotIntervalNanos = positiveNanos(
				verifiedNativeSnapshotInterval, "verifiedNativeSnapshotInterval");
		this.nativeWatchEnabled = nativeWatchEnabled;
	}

	/**
	 * Replace the watched directory. The initial snapshot is captured by the worker,
	 * never by the caller (which may be the EDT).
	 */
	public void watch(Path directory) {
		watch(directory, null);
	}

	/**
	 * Replace the watched directory using the entries already collected by the panel
	 * as the polling baseline. This avoids a second directory enumeration after every
	 * navigation.
	 */
	public void watch(Path directory, Iterable<EntryState> listedEntries) {
		if (directory == null || !directory.getFileSystem().equals(FileSystems.getDefault())) {
			clear();
			return;
		}

		Path normalized = directory.toAbsolutePath().normalize();
		synchronized (this) {
			if (closed) {
				return;
			}
			stopWorkerLocked();
			long token = ++generation;
			worker = Thread.ofVirtual().name("filepanel-fs-watch-" + token)
					.start(() -> monitor(token, normalized, listedEntries));
		}
	}

	/** Stop watching without permanently closing this monitor. */
	public synchronized void clear() {
		stopWorkerLocked();
	}

	@Override
	public synchronized void close() {
		closed = true;
		stopWorkerLocked();
	}

	private void monitor(long token, Path directory, Iterable<EntryState> listedEntries) {
		WatchService watchService = openWatchService(token, directory);
		DirectorySnapshot previous = listedEntries != null ? snapshot(listedEntries) : snapshot(directory);
		long nextSnapshot = System.nanoTime() + (nativeWatchProven
				? verifiedNativeSnapshotIntervalNanos : snapshotIntervalNanos);
		long refreshDue = NO_REFRESH_PENDING;
		long firstDirtyAt = NO_REFRESH_PENDING;

		try {
			while (isCurrent(token)) {
				long now = System.nanoTime();
				long forcedRefreshDue = firstDirtyAt == NO_REFRESH_PENDING
						? NO_REFRESH_PENDING : saturatedAdd(firstDirtyAt, maxDirtyNanos);
				long wakeAt = Math.min(nextSnapshot, Math.min(refreshDue, forcedRefreshDue));
				long waitNanos = Math.max(1L, Math.min(TimeUnit.MILLISECONDS.toNanos(500), wakeAt - now));

				WatchKey key = poll(watchService, waitNanos);
				if (key != null) {
					var events = key.pollEvents();
					boolean overflow = events.stream().anyMatch(event -> event.kind() == OVERFLOW);
					boolean changed = overflow || events.stream().anyMatch(event -> event.kind() == ENTRY_CREATE
							|| event.kind() == ENTRY_DELETE || event.kind() == ENTRY_MODIFY);
					if (!key.reset()) {
						changed = true;
						nativeWatchProven = false;
						nextSnapshot = Math.min(nextSnapshot,
								saturatedAdd(System.nanoTime(), snapshotIntervalNanos));
						closeQuietly(watchService);
						watchService = null;
					} else if (changed && !overflow) {
						nativeWatchProven = true;
					} else if (overflow) {
						nativeWatchProven = false;
						nextSnapshot = Math.min(nextSnapshot,
								saturatedAdd(System.nanoTime(), snapshotIntervalNanos));
					}
					if (changed) {
						now = System.nanoTime();
						firstDirtyAt = firstDirtyAt == NO_REFRESH_PENDING ? now : firstDirtyAt;
						refreshDue = saturatedAdd(now, debounceNanos);
					}
				}

				now = System.nanoTime();
				if (now >= nextSnapshot) {
					DirectorySnapshot current = snapshot(directory);
					if (!current.equals(previous)) {
						previous = current;
						firstDirtyAt = firstDirtyAt == NO_REFRESH_PENDING ? now : firstDirtyAt;
						refreshDue = saturatedAdd(now, debounceNanos);
					}
					long interval = nativeWatchProven
							? verifiedNativeSnapshotIntervalNanos : snapshotIntervalNanos;
					nextSnapshot = saturatedAdd(now, interval);
				}

				if (now >= refreshDue || now >= forcedRefreshDue) {
					refreshDue = NO_REFRESH_PENDING;
					firstDirtyAt = NO_REFRESH_PENDING;
					notifyChanged(directory);
				}
			}
		} finally {
			closeQuietly(watchService);
			clearActiveWatchService(token);
		}
	}

	private WatchService openWatchService(long token, Path directory) {
		if (!nativeWatchEnabled) {
			return null;
		}
		try {
			WatchService watchService = directory.getFileSystem().newWatchService();
			directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
			synchronized (this) {
				if (!isCurrent(token)) {
					closeQuietly(watchService);
					return null;
				}
				activeWatchService = watchService;
			}
			return watchService;
		} catch (IOException | RuntimeException e) {
			log.debug("Native directory watching is unavailable for [{}]; using snapshots: {}",
					directory, e.getMessage());
			return null;
		}
	}

	private static WatchKey poll(WatchService watchService, long waitNanos) {
		try {
			if (watchService != null) {
				return watchService.poll(waitNanos, TimeUnit.NANOSECONDS);
			}
			TimeUnit.NANOSECONDS.sleep(waitNanos);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (ClosedWatchServiceException e) {
			return null;
		}
	}

	private void notifyChanged(Path directory) {
		try {
			listener.accept(directory);
		} catch (RuntimeException e) {
			log.warn("Directory change listener failed for [{}]", directory, e);
		}
	}

	private synchronized boolean isCurrent(long token) {
		return !closed && generation == token && !Thread.currentThread().isInterrupted();
	}

	private void stopWorkerLocked() {
		generation++;
		closeQuietly(activeWatchService);
		activeWatchService = null;
		if (worker != null) {
			worker.interrupt();
			worker = null;
		}
	}

	private static DirectorySnapshot snapshot(Iterable<EntryState> entries) {
		var copy = new HashSet<EntryState>();
		for (EntryState entry : entries) {
			if (entry != null) {
				copy.add(entry);
			}
		}
		return new DirectorySnapshot(true, Set.copyOf(copy));
	}

	private synchronized void clearActiveWatchService(long token) {
		if (generation == token) {
			activeWatchService = null;
		}
	}

	private static DirectorySnapshot snapshot(Path directory) {
		if (!Files.isDirectory(directory)) {
			return new DirectorySnapshot(false, Set.of());
		}
		var entries = new HashSet<EntryState>();
		try (var paths = Files.list(directory)) {
			paths.forEach(path -> entries.add(entryState(path)));
			return new DirectorySnapshot(true, Set.copyOf(entries));
		} catch (IOException | RuntimeException e) {
			return new DirectorySnapshot(false, Set.of());
		}
	}

	private static EntryState entryState(Path path) {
		String name = path.getFileName() != null ? path.getFileName().toString() : path.toString();
		try {
			BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
					LinkOption.NOFOLLOW_LINKS);
			BasicFileAttributes effective = attrs;
			if (attrs.isSymbolicLink()) {
				try {
					effective = Files.readAttributes(path, BasicFileAttributes.class);
				} catch (IOException ignored) {
					effective = null;
				}
			}
			boolean directory = effective != null && effective.isDirectory();
			long size = effective == null || directory ? 0L : effective.size();
			return new EntryState(name, directory, size,
					attrs.lastModifiedTime().toMillis());
		} catch (IOException | RuntimeException e) {
			return new EntryState(name, false, -1L, -1L);
		}
	}

	private static long saturatedAdd(long value, long increment) {
		return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
	}

	private static long positiveNanos(Duration duration, String name) {
		long nanos = java.util.Objects.requireNonNull(duration, name).toNanos();
		if (nanos <= 0) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return nanos;
	}

	private static void closeQuietly(WatchService watchService) {
		if (watchService == null) {
			return;
		}
		try {
			watchService.close();
		} catch (IOException ignored) {
			// Best-effort shutdown.
		}
	}

	private record DirectorySnapshot(boolean available, Set<EntryState> entries) {
	}

	/** Immutable directory-entry state supplied by a completed panel listing. */
	public record EntryState(String name, boolean directory, long size, long modifiedMillis) {
	}
}
