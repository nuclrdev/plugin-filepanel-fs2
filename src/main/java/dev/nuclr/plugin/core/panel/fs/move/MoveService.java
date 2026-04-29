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
package dev.nuclr.plugin.core.panel.fs.move;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import dev.nuclr.plugin.core.panel.fs.copy.CancellationController;
import dev.nuclr.plugin.core.panel.fs.copy.ConflictHandler;
import dev.nuclr.plugin.core.panel.fs.copy.ConflictMode;
import lombok.extern.slf4j.Slf4j;

/**
 * Execution-only move engine — no UI or dialog logic of any kind.
 *
 * <p>Lifecycle: one call to {@link #execute} per confirmed {@link MoveRequest}.
 * The same {@code MoveService} instance may be reused across multiple runs.
 *
 * <p>Move strategy per file:
 * <ol>
 *   <li>If source and target are on the <b>same filesystem</b>, delegate to
 *       {@code Files.move(REPLACE_EXISTING)} — an atomic rename with no byte
 *       copying; progress advances by file size.</li>
 *   <li>Otherwise (<b>cross-filesystem</b>), fall back to buffered copy then
 *       delete-source, reporting byte-level progress identical to the copy engine.</li>
 * </ol>
 *
 * <p>Directories are always created individually at the target (never moved as a
 * whole tree), so the per-entry progress loop covers all content.  Empty source
 * directories are deleted post-order after all files are moved.
 *
 * <p>Cancellation checkpoints occur:
 * <ol>
 *   <li>Before processing each entry.</li>
 *   <li>Inside the byte-copy loop (cross-filesystem only), after every buffer flush.</li>
 *   <li>After each file is fully written.</li>
 * </ol>
 */
@Slf4j
public class MoveService {

    /** Read/write buffer size for cross-filesystem copies. */
    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * Execute a confirmed move request synchronously on the calling thread.
     *
     * <p>This method blocks until the move completes, is cancelled, or fails.
     * Run it on a virtual/background thread — never on the Swing EDT.
     *
     * @param request         confirmed parameters (never null)
     * @param listener        progress sink; {@code null} disables all callbacks
     * @param ctrl            shared cancellation controller (never null)
     * @param conflictHandler called for every destination-exists conflict
     * @return final outcome of the run
     */
    public MoveOutcome execute(
            MoveRequest request,
            MoveProgressListener listener,
            CancellationController ctrl,
            ConflictHandler conflictHandler) {

        // ------------------------------------------------------------------
        // 1. Pre-scan: discover all entries and compute totals
        // ------------------------------------------------------------------
        ScanResult scan;
        try {
            scan = scan(request.sources());
        } catch (IOException ex) {
            log.error("Pre-scan failed", ex);
            notifyError(listener, null, null, ex);
            notifyComplete(listener, MoveOutcome.FAILED,
                    MoveProgressSnapshot.terminal(MovePhase.FAILED, 0, 0, 0, 0));
            return MoveOutcome.FAILED;
        }

        notify(listener, MoveProgressSnapshot.postScan(scan.totalFiles(), scan.totalBytes()));

        if (ctrl.isCancelled()) {
            return finishCancelled(listener, 0, scan, 0L);
        }

        // ------------------------------------------------------------------
        // 2. Move loop
        // ------------------------------------------------------------------
        AtomicInteger filesMoved  = new AtomicInteger(0);
        AtomicLong    bytesMoved  = new AtomicLong(0L);
        // Source directories to delete after all files are moved (in scan order
        // so reversed iteration yields deepest-first).
        List<Path> sourceDirs = new ArrayList<>();

        for (SourceEntry entry : scan.entries()) {

            // Checkpoint: before each entry
            if (ctrl.isCancelled()) {
                return finishCancelled(listener, filesMoved.get(), scan, bytesMoved.get());
            }
            try {
                ctrl.checkPause();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return finishCancelled(listener, filesMoved.get(), scan, bytesMoved.get());
            }

            Path target = resolveTarget(entry.path(), entry.root(), request.targetDirectory());

            try {
                if (entry.isDirectory()) {
                    // Always create target directory individually — the files inside
                    // are handled as separate entries so we get per-file progress.
                    Files.createDirectories(target);
                    sourceDirs.add(entry.path());
                } else {
                    MoveOutcome fileResult = moveFile(
                            entry, target,
                            filesMoved.get(), scan.totalFiles(),
                            bytesMoved.get(), scan.totalBytes(),
                            listener, ctrl, conflictHandler);

                    if (fileResult == MoveOutcome.CANCELLED) {
                        return finishCancelled(listener, filesMoved.get(), scan, bytesMoved.get());
                    }
                    if (fileResult == MoveOutcome.SUCCESS) {
                        filesMoved.incrementAndGet();
                        bytesMoved.addAndGet(entry.sizeBytes());
                    }
                    // SKIP → continue with next entry, no counters updated
                }
            } catch (IOException ex) {
                log.warn("Failed to move {} → {}", entry.path(), target, ex);
                notifyError(listener, entry.path(), target, ex);
                // Best-effort: continue with remaining files
            }
        }

        // ------------------------------------------------------------------
        // 3. Remove now-empty source directories (deepest first)
        // ------------------------------------------------------------------
        for (int i = sourceDirs.size() - 1; i >= 0; i--) {
            try {
                Files.deleteIfExists(sourceDirs.get(i));
            } catch (IOException ex) {
                log.warn("Could not remove source directory {}: {}", sourceDirs.get(i), ex.getMessage());
            }
        }

        // ------------------------------------------------------------------
        // 4. Completion
        // ------------------------------------------------------------------
        MoveProgressSnapshot finalSnap = MoveProgressSnapshot.terminal(
                MovePhase.COMPLETED,
                filesMoved.get(), scan.totalFiles(),
                bytesMoved.get(), scan.totalBytes());
        notifyComplete(listener, MoveOutcome.SUCCESS, finalSnap);
        return MoveOutcome.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Single-file move
    // -----------------------------------------------------------------------

    /**
     * Move one regular file from {@code entry.path()} to {@code target}.
     *
     * <p>Same-filesystem: {@code Files.move(REPLACE_EXISTING)} — instant rename.
     * Cross-filesystem: buffered copy then delete-source with byte-level progress.
     *
     * @return {@link MoveOutcome#SUCCESS} (including skip), or
     *         {@link MoveOutcome#CANCELLED} if ctrl fired during the move
     */
    private MoveOutcome moveFile(
            SourceEntry entry,
            Path target,
            int filesMovedSoFar,
            int totalFiles,
            long bytesMovedSoFar,
            long totalBytes,
            MoveProgressListener listener,
            CancellationController ctrl,
            ConflictHandler conflictHandler) throws IOException {

        Path source = entry.path();

        // ---- Conflict resolution -------------------------------------------
        boolean targetExisted = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        ConflictMode resolution = ConflictMode.OVERWRITE;

        if (targetExisted) {
            resolution = conflictHandler.resolve(source, target);
            switch (resolution) {
                case SKIP   -> { return MoveOutcome.SUCCESS; }
                case CANCEL -> { ctrl.cancel(); return MoveOutcome.CANCELLED; }
                case RENAME -> target = findFreeName(target);
                case OVERWRITE, APPEND -> Files.deleteIfExists(target);
                case ASK -> throw new IllegalStateException(
                        "ConflictHandler returned ASK — it must resolve to a concrete mode");
            }
        }

        // ---- Ensure parent directory exists --------------------------------
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // ---- Detect same-filesystem ----------------------------------------
        // Use the target's parent (which now exists) as the reference point.
        boolean sameFs = false;
        try {
            Path storeRef = (parent != null) ? parent : target;
            FileStore srcStore = Files.getFileStore(source);
            FileStore tgtStore = Files.getFileStore(storeRef);
            sameFs = srcStore.equals(tgtStore);
        } catch (IOException ignored) {
            // Cannot determine — assume cross-filesystem (safe fallback)
        }

        if (sameFs) {
            // ---- Same-filesystem: atomic rename ----------------------------
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            long newTotal = bytesMovedSoFar + entry.sizeBytes();
            int  totalPct = totalBytes > 0
                    ? (int) Math.min(100L, newTotal * 100L / totalBytes)
                    : (totalFiles > 0 ? (filesMovedSoFar + 1) * 100 / totalFiles : 100);
            notify(listener, new MoveProgressSnapshot(
                    MovePhase.MOVING, source, target,
                    entry.sizeBytes(), entry.sizeBytes(), 100,
                    filesMovedSoFar + 1, totalFiles,
                    newTotal, totalBytes, totalPct));
            return MoveOutcome.SUCCESS;
        }

        // ---- Cross-filesystem: buffered copy + delete source ---------------
        long fileSize = entry.sizeBytes();

        try (InputStream in  = Files.newInputStream(source);
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buf = new byte[BUFFER_SIZE];
            long fileBytesWritten = 0L;
            int  read;

            while ((read = in.read(buf)) != -1) {

                // Checkpoint: inside copy loop
                if (ctrl.isCancelled()) {
                    return MoveOutcome.CANCELLED;
                }
                try {
                    ctrl.checkPause();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return MoveOutcome.CANCELLED;
                }

                out.write(buf, 0, read);
                fileBytesWritten += read;

                int filePct    = fileSize > 0
                        ? (int) Math.min(100L, fileBytesWritten * 100L / fileSize)
                        : 100;
                long totalMoved = bytesMovedSoFar + fileBytesWritten;
                int  totalPct   = totalBytes > 0
                        ? (int) Math.min(100L, totalMoved * 100L / totalBytes)
                        : 100;

                notify(listener, new MoveProgressSnapshot(
                        MovePhase.MOVING, source, target,
                        fileBytesWritten, fileSize, filePct,
                        filesMovedSoFar, totalFiles,
                        totalMoved, totalBytes, totalPct));
            }
        }

        // Checkpoint: after file written
        if (ctrl.isCancelled()) {
            return MoveOutcome.CANCELLED;
        }

        // Delete source after successful copy
        Files.delete(source);
        return MoveOutcome.SUCCESS;
    }

    // -----------------------------------------------------------------------
    // Pre-scan
    // -----------------------------------------------------------------------

    private ScanResult scan(List<Path> sources) throws IOException {
        List<SourceEntry> entries      = new ArrayList<>();
        long[]            totalBytesAcc = {0L};

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
                        long size = attrs.size();
                        entries.add(new SourceEntry(file, root.getParent(), false, size));
                        totalBytesAcc[0] += size;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        log.warn("Cannot access {} during scan: {}", file, exc.getMessage());
                        return FileVisitResult.CONTINUE;
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

    private static Path findFreeName(Path path) {
        String name    = path.getFileName().toString();
        int    dot     = name.lastIndexOf('.');
        String stem    = dot >= 0 ? name.substring(0, dot) : name;
        String ext     = dot >= 0 ? name.substring(dot)    : "";
        int    counter = 1;
        Path   candidate;
        do {
            candidate = path.resolveSibling(stem + "_" + counter + ext);
            counter++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private static MoveOutcome finishCancelled(
            MoveProgressListener listener, int filesMoved,
            ScanResult scan, long bytesMoved) {
        notifyComplete(listener, MoveOutcome.CANCELLED, MoveProgressSnapshot.terminal(
                MovePhase.CANCELLED, filesMoved, scan.totalFiles(), bytesMoved, scan.totalBytes()));
        return MoveOutcome.CANCELLED;
    }

    private static void notify(MoveProgressListener l, MoveProgressSnapshot snap) {
        if (l != null) l.onProgress(snap);
    }

    private static void notifyComplete(MoveProgressListener l, MoveOutcome outcome, MoveProgressSnapshot snap) {
        if (l != null) l.onComplete(outcome, snap);
    }

    private static void notifyError(MoveProgressListener l, Path src, Path tgt, Exception ex) {
        if (l != null) l.onError(src, tgt, ex);
    }

    // -----------------------------------------------------------------------
    // Internal value types
    // -----------------------------------------------------------------------

    /** A single item discovered during the pre-scan. */
    record SourceEntry(
            Path path,
            /** Parent of the top-level source root; used for relative target resolution. */
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
