package com.porter.qwertestash.stash;

import com.porter.qwertestash.QwerteStashPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class StashStorageIntegrationTest {
    @TempDir Path dir;

    private static final class FixtureStash extends Stash {
        private final transient Path data;
        private final transient Path index;

        FixtureStash(Path data, Path index) {
            super("alpha", "local");
            this.data = data;
            this.index = index;
        }

        @Override Path dataPath() { return data; }
        @Override public Path indexPath() { return index; }
    }

    @Test
    void legacyFileLoadsWithDefaultsAndKeepsItsSelectedName() throws IOException {
        Path file = dir.resolve("fixture.json");
        Files.writeString(file, "{\"name\":\"ALPHA\",\"server\":\"local\",\"boxes\":[]}");
        Stash stash = Stash.load(file, "alpha", "local");
        assertEquals("alpha", stash.name);
        assertTrue(stash.deliveryStorage.isEmpty());
        assertTrue(stash.held.isEmpty());
        assertNull(Stash.load(dir.resolve("missing"), "alpha", "local"));
        assertThrows(IOException.class, () -> Stash.load(file, "beta", "local"));
        Files.writeString(file, "{\"name\":\"a:b\",\"server\":\"local\"}");
        assertThrows(IOException.class, () -> Stash.load(file, "a_b", "local"));
        Files.writeString(file, "{\"held\":{\"4\":\"stone\"}}");
        Stash legacy = Stash.load(file, "alpha", "local");
        assertEquals("stone", legacy.held.get(4));
        assertEquals("local", legacy.server);
    }

    @Test
    void readErrorsPreserveTheOriginalAndCorruptInputHasARecoveryCopy() throws IOException {
        assertThrows(IOException.class, () -> Stash.load(dir, "alpha", "local"));
        var originalLogger = QwerteStashPlugin.LOG;
        QwerteStashPlugin.LOG = ComponentLogger.logger("storage-test");
        try {
            Path file = dir.resolve("broken.json");
            byte[] broken = {(byte) 0xc3, 0x28};
            Files.write(file, broken);
            assertNull(Stash.load(file, "alpha", "local"));
            assertArrayEquals(broken, Files.readAllBytes(file));
            try (var files = Files.list(dir)) {
                Path copy = files.filter(p -> p.getFileName().toString().startsWith("broken.json.bak-")).findFirst().orElseThrow();
                assertArrayEquals(broken, Files.readAllBytes(copy));
                assertPrivate(copy);
            }
        } finally {
            QwerteStashPlugin.LOG = originalLogger;
        }
    }

    @Test
    void aFailedSaveStaysDirtyAndCanBeRetriedWithoutLosingTheBackup() throws Exception {
        Path data = dir.resolve("local_alpha.json");
        Path index = dir.resolve("local_alpha.index.txt");
        Stash active = new FixtureStash(data, index);
        var previousLogger = QwerteStashPlugin.LOG;
        QwerteStashPlugin.LOG = ComponentLogger.logger("storage-test");
        try {
            assertTrue(active.backUp());
            active.heldSource = () -> java.util.Map.of(4, "stone");
            active.markDirty();
            active.save();
            assertFalse(active.isDirty());
            assertPrivate(data);
            assertTrue(active.backUp());
            Files.delete(data);
            Files.createDirectory(data);
            Files.writeString(data.resolve("occupied"), "keep");

            var currentSession = new java.util.concurrent.atomic.AtomicReference<>("iron");
            active.heldSource = () -> java.util.Map.of(4, currentSession.get());
            active.markDirty();
            assertFalse(active.flushForRelease());
            assertTrue(active.isDirty());
            currentSession.set("new session");
            assertEquals("keep", Files.readString(data.resolve("occupied")));
            Files.delete(data.resolve("occupied"));
            Files.delete(data);
            assertTrue(active.flushForRelease());
            assertFalse(active.isDirty());
            assertEquals("iron", Stash.load(data, "alpha", "local").held.get(4));
            try (var files = Files.list(dir)) {
                Path backup = files.filter(p -> p.toString().contains(".prune-bak-")).findFirst().orElseThrow();
                assertEquals("stone", Stash.load(backup, "alpha", "local").held.get(4));
            }
        } finally {
            StashIndex.forget(index);
            QwerteStashPlugin.LOG = previousLogger;
        }
    }

    @Test
    void aFailedBackupCleansUpItsPartialCopy() throws IOException {
        assertThrows(IOException.class, () -> SnapshotFiles.backup(dir.resolve("missing"), ".bak-"));
        try (var files = Files.list(dir)) { assertEquals(0, files.count()); }
    }

    @Test
    void replacementsAndRecoveryCopiesHavePrivatePermissions() throws IOException {
        Path file = dir.resolve("known.json");
        SnapshotFiles.write(file, "first");
        Path staged = SnapshotFiles.stage(file, "second");
        assertPrivate(staged);
        assertEquals("first", Files.readString(file));
        SnapshotFiles.replace(staged, file);
        assertEquals("second", Files.readString(file));
        assertPrivate(file);
        for (int i = 0; i < 5; i++) {
            SnapshotFiles.write(file, Integer.toString(i));
            Path copy = SnapshotFiles.backup(file, ".bak-");
            assertEquals(Integer.toString(i), Files.readString(copy));
            assertPrivate(copy);
        }
        try (var files = Files.list(dir)) {
            assertEquals(3, files.filter(p -> p.getFileName().toString().contains(".bak-")).count());
        }
    }

    @Test
    void concurrentWritersPublishOneCompleteSnapshotAndCleanUp() throws Exception {
        Path file = dir.resolve("shared.json");
        String a = "a".repeat(16384), b = "b".repeat(16384);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); SnapshotFiles.write(file, a); return 0; });
            var second = pool.submit(() -> { start.await(); SnapshotFiles.write(file, b); return 0; });
            start.countDown();
            first.get();
            second.get();
        }
        assertTrue(List.of(a, b).contains(Files.readString(file)));
        Path occupied = Files.createDirectory(dir.resolve("occupied"));
        Files.writeString(occupied.resolve("keep"), "untouched");
        assertThrows(IOException.class, () -> SnapshotFiles.write(occupied, "new"));
        assertEquals("untouched", Files.readString(occupied.resolve("keep")));
        try (var files = Files.list(dir)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    void forgettingAnIndexInvalidatesQueuedSnapshotsAndAllowsRecreation() throws Exception {
        Path path = dir.resolve("home.index.txt");
        Stash stash = new Stash("home", "local") {
            @Override public Path indexPath() { return path; }
        };
        StashIndex.writeNow(stash);
        awaitFile(path);
        assertTrue(Files.readString(path).contains("home"));
        assertPrivate(path);
        StashIndex.touch(stash);
        StashIndex.writeNow(stash);
        StashIndex.forget(path);

        Path marker = dir.resolve("marker.index.txt");
        Stash barrier = new Stash("marker", "local") {
            @Override public Path indexPath() { return marker; }
        };
        StashIndex.writeNow(barrier);
        awaitFile(marker); // Earlier immediate writes have completed on the same single worker.
        assertFalse(Files.exists(path));
        StashIndex.writeNow(stash);
        awaitFile(path);
        assertTrue(Files.readString(path).contains("home"));
        StashIndex.forget(path);
        StashIndex.forget(marker);
    }

    private static void awaitFile(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(path), "index writer did not publish");
    }

    private static void assertPrivate(Path file) throws IOException {
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
    }
}
