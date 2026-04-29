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
package dev.nuclr.plugin.core.panel.fs.copy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

/**
 * Execution-only copy engine — no UI or dialog logic of any kind.
 *
 * <p>Lifecycle: one call to {@link #execute} per confirmed {@link CopyRequest}.
 * The same {@code CopyService} instance may be reused across multiple runs.
 *
 * <p>Extension points (all injected per-execution):
 * <ul>
 *   <li>{@link ConflictHandler} — swap in a dialog, a fixed policy, or a
 *       test stub without touching this class.</li>
 *   <li>{@link CopyProgressListener} — wire any progress display; may be
 *       {@code null} to disable progress reporting.</li>
 *   <li>{@link CancellationController} — shared with the UI cancel button.</li>
 * </ul>
 *
 * <p>Cancellation checkpoints occur:
 * <ol>
 *   <li>Before processing each entry (file or directory).</li>
 *   <li>Inside the byte-copy loop, after every buffer flush.</li>
 *   <li>After each file is fully written.</li>
 * </ol>
 */
@Slf4j
public class CopyService {

    /** Read/write buffer size. 64 KB is a good trade-off for most storage. */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Execute a confirmed copy request synchronously on the calling thread.
     *
     * <p>This method blocks until the copy completes, is cancelled, or fails.
     * Run it on a virtual/background thread — never on the Swing EDT.
     *
     * @param request        confirmed parameters (never null)
     * @param listener       progress sink; {@code null} disables all callbacks
     * @param ctrl           shared cancellation controller (never null)
     * @param conflictHandler called for every destination-exists conflict
     * @return final outcome of the run
     */
    public CopyOutcome execute(
            CopyRequest request,
            CopyProgressListener listener,
            CancellationController ctrl,
            ConflictHandler conflictHandler) {

        // ------------------------------------------------------------------
        // 1. Pre-scan: discover all entries and compute totals
        // ------------------------------------------------------------------
        ScanResult scan;
        try {
            scan = scan(request.sources(), request.options().copySymlinkContents());
        } catch (IOException ex) {
            log.error("Pre-scan failed", ex);
            notifyError(listener, null, null, ex);
            CopyProgressSnapshot failed = CopyProgressSnapshot.terminal(CopyPhase.FAILED, 0, 0, 0, 0);
            notifyComplete(listener, CopyOutcome.FAILED, failed);
            return CopyOutcome.FAILED;
        }

        notify(listener, CopyProgressSnapshot.postScan(scan.totalFiles(), scan.totalBytes()));

        if (ctrl.isCancelled()) {
            CopyProgressSnapshot snap = CopyProgressSnapshot.terminal(
                    CopyPhase.CANCELLED, 0, scan.totalFiles(), 0, scan.totalBytes());
            notifyComplete(listener, CopyOutcome.CANCELLED, snap);
            return CopyOutcome.CANCELLED;
        }

        // ------------------------------------------------------------------
        // 2. Copy loop
        // ------------------------------------------------------------------
        AtomicInteger filesCopied = new AtomicInteger(0);
        AtomicLong bytesCopied   = new AtomicLong(0L);

        for (SourceEntry entry : scan.entries()) {

            // Checkpoint: before each entry
            if (ctrl.isCancelled()) {
                return finishCancelled(listener, filesCopied.get(), scan, bytesCopied.get());
            }
            try {
                ctrl.checkPause();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return finishCancelled(listener, filesCopied.get(), scan, bytesCopied.get());
            }

            Path target = resolveTarget(entry.path(), entry.root(), request.targetDirectory());

            try {
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    CopyOutcome fileResult = copyFile(
                            entry, target, request.options(),
                            filesCopied.get(), scan.totalFiles(),
                            bytesCopied.get(), scan.totalBytes(),
                            listener, ctrl, conflictHandler);

                    if (fileResult == CopyOutcome.CANCELLED) {
                        return finishCancelled(listener, filesCopied.get(), scan, bytesCopied.get());
                    }
                    if (fileResult == CopyOutcome.SUCCESS) {
                        filesCopied.incrementAndGet();
                        bytesCopied.addAndGet(entry.sizeBytes());
                    }
                    // SKIP → continue with next entry, no counters updated
                }
            } catch (IOException ex) {
                log.warn("Failed to copy {} → {}", entry.path(), target, ex);
                notifyError(listener, entry.path(), target, ex);
                // Best-effort: continue with remaining files
            }
        }

        // ------------------------------------------------------------------
        // 3. Completion
        // ------------------------------------------------------------------
        CopyProgressSnapshot finalSnap = CopyProgressSnapshot.terminal(
                CopyPhase.COMPLETED,
                filesCopied.get(), scan.totalFiles(),
                bytesCopied.get(), scan.totalBytes());
        notifyComplete(listener, CopyOutcome.SUCCESS, finalSnap);
        return CopyOutcome.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Single-file copy
    // -----------------------------------------------------------------------

    /**
     * Copy one regular file from {@code entry.path()} to {@code target}.
     *
     * <p>Conflict resolution, POSIX permission transfer, and timestamp
     * preservation are all handled here.
     *
     * @return {@link CopyOutcome#SUCCESS} (including skip), or
     *         {@link CopyOutcome#CANCELLED} if ctrl fired during the copy
     */
    private CopyOutcome copyFile(
            SourceEntry entry,
            Path target,
            CopyOptions opts,
            int filesCopiedSoFar,
            int totalFiles,
            long bytesCopiedSoFar,
            long totalBytes,
            CopyProgressListener listener,
            CancellationController ctrl,
            ConflictHandler conflictHandler) throws IOException {

        Path source   = entry.path();
        long fileSize = entry.sizeBytes();

        // ---- Conflict resolution -------------------------------------------
        boolean targetExisted = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        ConflictMode resolution = ConflictMode.OVERWRITE; // default when target absent

        if (targetExisted) {
            resolution = conflictHandler.resolve(source, target);
            switch (resolution) {
                case SKIP   -> { return CopyOutcome.SUCCESS; }
                case CANCEL -> { ctrl.cancel(); return CopyOutcome.CANCELLED; }
                case RENAME -> target = findFreeName(target);
                case OVERWRITE -> Files.deleteIfExists(target);
                case APPEND -> { /* handled below via APPEND open option */ }
                case ASK -> throw new IllegalStateException(
                        "ConflictHandler returned ASK — it must resolve to a concrete mode");
            }
        }

        // ---- Ensure parent directory exists --------------------------------
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // ---- Buffered stream copy (checkpoint inside loop) -----------------
        boolean append = (resolution == ConflictMode.APPEND);

        try (InputStream in = Files.newInputStream(source);
             OutputStream out = openOutput(target, append)) {

            byte[] buf = new byte[BUFFER_SIZE];
            long fileBytesWritten = 0L;
            int read;

            while ((read = in.read(buf)) != -1) {

                // Checkpoint: inside copy loop
                if (ctrl.isCancelled()) {
                    return CopyOutcome.CANCELLED;
                }
                try {
                    ctrl.checkPause();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return CopyOutcome.CANCELLED;
                }

                out.write(buf, 0, read);
                fileBytesWritten += read;

                // Emit progress snapshot after every chunk
                int filePct = fileSize > 0
                        ? (int) Math.min(100L, fileBytesWritten * 100L / fileSize)
                        : 100;
                long totalCopied = bytesCopiedSoFar + fileBytesWritten;
                int totalPct = totalBytes > 0
                        ? (int) Math.min(100L, totalCopied * 100L / totalBytes)
                        : 100;

                notify(listener, new CopyProgressSnapshot(
                        CopyPhase.COPYING,
                        source, target,
                        fileBytesWritten, fileSize, filePct,
                        filesCopiedSoFar, totalFiles,
                        totalCopied, totalBytes, totalPct));
            }
        }

        // Checkpoint: after file written
        if (ctrl.isCancelled()) {
            return CopyOutcome.CANCELLED;
        }

        // ---- Post-copy metadata --------------------------------------------
        applyTimestamps(source, target, opts);
        applyPermissions(source, target, opts);

        return CopyOutcome.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Pre-scan
    // -----------------------------------------------------------------------

    /**
     * Walk all {@code sources} and collect every regular file and directory
     * in traversal order.  Computes {@code totalFiles} and {@code totalBytes}.
     *
     * @param followSymlinks when {@code true} symbolic links are dereferenced
     *                       and their targets are copied; otherwise they are skipped
     */
    private ScanResult scan(List<Path> sources, boolean followSymlinks) throws IOException {
        List<SourceEntry> entries   = new ArrayList<>();
        long[] totalBytesAcc = {0L};

        for (Path root : sources) {
            if (Files.isRegularFile(root, LinkOption.NOFOLLOW_LINKS)) {
                long size = Files.size(root);
                entries.add(new SourceEntry(root, root.getParent(), false, size));
                totalBytesAcc[0] += size;

            } else if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        entries.add(new SourceEntry(dir, root.getParent(), true, 0));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (Files.isSymbolicLink(file) && !followSymlinks) {
                            return FileVisitResult.CONTINUE; // skip symlinks
                        }
                        long size = attrs.size();
                        entries.add(new SourceEntry(file, root.getParent(), false, size));
                        totalBytesAcc[0] += size;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        log.warn("Cannot access {} during scan: {}", file, exc.getMessage());
                        return FileVisitResult.CONTINUE; // skip unreadable entries
                    }
                });
            } else {
                log.warn("Source is neither a regular file nor a directory, skipping: {}", root);
            }
        }

        int totalFiles = (int) entries.stream().filter(e -> !e.isDirectory()).count();
        return new ScanResult(entries, totalFiles, totalBytesAcc[0]);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Resolve the destination path for a single source entry.
     *
     * <p>For a top-level file the target is {@code targetDir / fileName}.
     * For items inside a directory tree the target mirrors the relative path
     * from {@code sourceRoot} so the directory structure is preserved.
     *
     * <p>Extension point: override this method to implement flat copies,
     * de-duplication by name, or any other path-mapping strategy.
     */
    private static Path resolveTarget(Path source, Path sourceRoot, Path targetDir) {
        if (sourceRoot != null) {
            return resolveRelativePath(targetDir, sourceRoot.relativize(source));
        }
        return targetDir.resolve(source.getFileName());
    }

    private static Path resolveRelativePath(Path targetDir, Path relativePath) {
        Path resolved = targetDir;
        for (Path part : relativePath) {
            resolved = resolved.resolve(part.toString());
        }
        return resolved;
    }

    /**
     * Find an unused filename near {@code path} by appending {@code _1}, {@code _2}, …
     * to the stem (before the extension).
     */
    private static Path findFreeName(Path path) {
        String name = path.getFileName().toString();
        int dot     = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot) : name;
        String ext  = dot >= 0 ? name.substring(dot)    : "";  // includes the leading '.'
        int counter = 1;
        Path candidate;
        do {
            candidate = path.resolveSibling(stem + "_" + counter + ext);
            counter++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private static OutputStream openOutput(Path target, boolean append) throws IOException {
        if (append) {
            return Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return Files.newOutputStream(target,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void applyTimestamps(Path source, Path target, CopyOptions opts) {
        if (!opts.preserveTimestamps()) return;
        try {
            BasicFileAttributes srcAttrs = Files.readAttributes(source, BasicFileAttributes.class);
            Files.setLastModifiedTime(target, srcAttrs.lastModifiedTime());
        } catch (IOException ex) {
            log.warn("Could not preserve timestamps for {}: {}", target, ex.getMessage());
        }
    }

    /**
     * Apply POSIX permissions to {@code target} according to {@link AccessRightsMode}.
     * Silently skips on platforms that do not support POSIX attributes.
     */
    private static void applyPermissions(Path source, Path target, CopyOptions opts) {
        if (opts.accessRights() != AccessRightsMode.COPY) return;
        try {
            var perms = Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(target, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows — POSIX not available
        } catch (IOException ex) {
            log.warn("Could not copy permissions to {}: {}", target, ex.getMessage());
        }
    }

    private static CopyOutcome finishCancelled(
            CopyProgressListener listener, int filesCopied,
            ScanResult scan, long bytesCopied) {
        CopyProgressSnapshot snap = CopyProgressSnapshot.terminal(
                CopyPhase.CANCELLED,
                filesCopied, scan.totalFiles(),
                bytesCopied, scan.totalBytes());
        notifyComplete(listener, CopyOutcome.CANCELLED, snap);
        return CopyOutcome.CANCELLED;
    }

    private static void notify(CopyProgressListener l, CopyProgressSnapshot snap) {
        if (l != null) l.onProgress(snap);
    }

    private static void notifyComplete(CopyProgressListener l, CopyOutcome outcome, CopyProgressSnapshot snap) {
        if (l != null) l.onComplete(outcome, snap);
    }

    private static void notifyError(CopyProgressListener l, Path src, Path tgt, Exception ex) {
        if (l != null) l.onError(src, tgt, ex);
    }

    // -----------------------------------------------------------------------
    // Internal value types
    // -----------------------------------------------------------------------

    /** A single item discovered during the pre-scan. */
    record SourceEntry(
            /** The absolute path to this entry. */
            Path path,
            /**
             * The parent of the top-level source root.  Used by
             * {@link #resolveTarget} to compute relative destination paths.
             */
            Path root,
            boolean isDirectory,
            long sizeBytes
    ) {}

    /** Result of the pre-scan phase. */
    record ScanResult(
            List<SourceEntry> entries,
            int totalFiles,
            long totalBytes
    ) {}
}
