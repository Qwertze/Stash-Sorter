package com.porter.qwertestash.stash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * How the stash file, its readable index and the recovery copies reach the disk (1.22.0, from the PR #1 hardening ideas). Every write is staged in a
 * file of a unique name in the same folder (private to its owner where the system has POSIX permissions) and moved over the target in one step, and
 * the staging file is removed when anything goes wrong, so two writers never share a temporary name and nothing half-written is ever the real file.
 */
final class SnapshotFiles {
    /** How many recovery copies of one kind are kept per file. */
    static final int KEEP_COPIES = 3;

    private SnapshotFiles() {
    }

    private static Path create(Path parent, String prefix, String suffix) throws IOException {
        Files.createDirectories(parent);
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
            return Files.createTempFile(parent, prefix, suffix, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(parent, prefix, suffix);
    }

    /** Writes the text to a new file of a unique name beside the target and returns it. */
    static Path stage(Path target, String text) throws IOException {
        Path temp = create(parent(target), ".stash-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            return temp;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** Moves a staged file over the target, atomically where the file system can. */
    static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Stage, then replace; the staging file never outlives a failure. */
    static void write(Path target, String text) throws IOException {
        Path temp = stage(target, text);
        try {
            replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Copies the file beside itself as {@code <file><suffix><timestamp to the nanosecond>-<random>}, private to its owner, so two copies in the same
     * second never overwrite each other. The newest {@link #KEEP_COPIES} copies of that suffix are kept.
     * @return the copy
     */
    static synchronized Path backup(Path source, String suffix) throws IOException {
        String prefix = source.getFileName() + suffix;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSSSSS"));
        Path copy = create(parent(source), prefix + stamp + "-", "");
        // Written into the private file made above (a copy with REPLACE_EXISTING would make a new file with the default permissions)
        try (var out = Files.newOutputStream(copy)) {
            Files.copy(source, out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(copy);
            throw e;
        }
        try (var files = Files.list(parent(source))) {
            List<Path> older = files.filter(p -> !p.equals(copy) && p.getFileName().toString().startsWith(prefix))
                .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).skip(KEEP_COPIES - 1).toList();
            for (Path stale : older) Files.deleteIfExists(stale);
        } catch (IOException | RuntimeException e) {
            // The copy itself was made; only the tidying up failed
        }
        return copy;
    }

    /**
     * Removes staging files ({@code .stash-*.tmp}) left behind in the folder by a crash and older than {@code maxAgeMs}. A file being written right now
     * is young and stays. Failures are ignored.
     * @return how many were removed
     */
    static int sweepStale(Path folder, long maxAgeMs) {
        int removed = 0;
        try (var files = Files.list(folder)) {
            long limit = System.currentTimeMillis() - maxAgeMs;
            for (Path p : (Iterable<Path>) files::iterator) {
                String n = p.getFileName().toString();
                if (!n.startsWith(".stash-") || !n.endsWith(".tmp")) continue;
                try {
                    if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).toMillis() < limit && Files.deleteIfExists(p)) removed++;
                } catch (IOException | RuntimeException ignored) {
                    // Left for the next time
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // No folder yet, or not listable
        }
        return removed;
    }

    private static Path parent(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        return parent == null ? Path.of(".").toAbsolutePath() : parent;
    }
}
