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

/** Same-directory staging for stash data, indexes and recovery copies. */
final class SnapshotFiles {
    private SnapshotFiles() {}

    private static Path create(Path parent, String prefix, String suffix) throws IOException {
        Files.createDirectories(parent);
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
            return Files.createTempFile(parent, prefix, suffix,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        return Files.createTempFile(parent, prefix, suffix);
    }

    static Path stage(Path target, String text) throws IOException {
        Path temp = create(target.getParent(), ".stash-", ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            return temp;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void write(Path target, String text) throws IOException {
        Path temp = stage(target, text);
        try {
            replace(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static synchronized Path backup(Path source, String suffix) throws IOException {
        String prefix = source.getFileName() + suffix;
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSSSSS"));
        Path copy = create(source.getParent(), prefix + stamp + "-", "");
        try (var out = Files.newOutputStream(copy)) {
            Files.copy(source, out);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(copy);
            throw e;
        }
        try (var files = Files.list(source.getParent())) {
            var older = files.filter(p -> !p.equals(copy) && p.getFileName().toString().startsWith(prefix))
                .sorted(Comparator.reverseOrder()).skip(2).toList();
            for (Path stale : older) Files.deleteIfExists(stale);
        }
        return copy;
    }
}
