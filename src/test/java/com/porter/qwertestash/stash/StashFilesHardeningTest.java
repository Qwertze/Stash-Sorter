package com.porter.qwertestash.stash;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stash and index file writes, the corrupt-file load and forget (ideas from PR 1, 1.22.0). */
class StashFilesHardeningTest {
    @TempDir
    Path tmp;
    private Path saved;

    @BeforeEach
    void folder() {
        saved = Stash.stashFolder;
        Stash.stashFolder = tmp;
    }

    @AfterEach
    void restore() {
        Stash.stashFolder = saved;
    }

    /** Lets the index write that a save queues finish and removes it, so the temporary folder can be deleted. */
    private static void settle(Stash s) throws Exception {
        long end = System.currentTimeMillis() + 3000;
        while (!Files.exists(s.indexPath()) && System.currentTimeMillis() < end) Thread.sleep(10);
        Thread.sleep(50);
        StashIndex.forget(s.indexPath());
    }

    private long count(String contains) throws IOException {
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().contains(contains)).count();
        }
    }

    @Test
    void aWriteLeavesOnlyTheTargetAndNoTempFile() throws IOException {
        Path target = tmp.resolve("a.json");
        SnapshotFiles.write(target, "one");
        SnapshotFiles.write(target, "two");
        assertEquals("two", Files.readString(target));
        assertEquals(1, count(""));
    }

    @Test
    void aFailedMoveRemovesTheStagingFile() throws IOException {
        Path dir = tmp.resolve("isDir");
        Files.createDirectories(dir.resolve("child"));
        // the target is a non-empty directory: the move fails
        try {
            SnapshotFiles.write(dir, "x");
        } catch (IOException expected) {
            // expected
        }
        assertEquals(1, count(""));
    }

    @Test
    void backupsGetUniqueNamesAndThreeAreKept() throws IOException {
        Path src = tmp.resolve("s.json");
        Files.writeString(src, "data");
        Path first = SnapshotFiles.backup(src, ".bak-");
        for (int i = 0; i < 5; i++) SnapshotFiles.backup(src, ".bak-");
        assertNotNull(first);
        assertEquals(3, count(".bak-"));
        assertEquals("data", Files.readString(src));
    }

    @Test
    void aCorruptFileIsBackedUpAndStaysUntilReplaced() throws IOException {
        Path file = tmp.resolve("x.json");
        Files.writeString(file, "{ not json", StandardCharsets.UTF_8);
        Stash.Loaded loaded = Stash.loadFrom(file, "s", "srv");
        assertNull(loaded.stash());
        assertFalse(loaded.protect());
        assertEquals(1, count(".bak-"));
        assertEquals("{ not json", Files.readString(file));
    }

    @Test
    void aMissingFileIsNoStashAndNoBackup() throws IOException {
        Stash.Loaded loaded = Stash.loadFrom(tmp.resolve("none.json"), "s", "srv");
        assertNull(loaded.stash());
        assertFalse(loaded.protect());
        assertEquals(0, count(""));
    }

    @Test
    void anUnreadableFileProtectsItAndTheStashStaysDirty() throws IOException {
        // A directory where the file should be: reading it is an I/O error, copying it fails too
        Path file = tmp.resolve("srv_s.json");
        Files.createDirectories(file);
        Stash.Loaded loaded = Stash.loadFrom(file, "s", "srv");
        assertNull(loaded.stash());
        assertTrue(loaded.protect());

        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        fresh.save(); // must not throw, must not replace
        fresh.save(); // within the wait: nothing happens
        assertTrue(fresh.isDirty());
        assertTrue(fresh.protectsFile());
        assertTrue(Files.isDirectory(file));
    }

    @Test
    void aSavedStashLoadsBackAndAGoodFileNeedsNoBackup() throws Exception {
        Stash s = new Stash("s", "srv");
        s.markDirty();
        s.save();
        assertFalse(s.isDirty());
        Path file = tmp.resolve("srv_s.json");
        assertTrue(Files.exists(file));
        Stash.Loaded loaded = Stash.loadFrom(file, "s", "srv");
        assertNotNull(loaded.stash());
        assertEquals("s", loaded.stash().name);
        assertEquals(0, count(".bak-"));
        settle(s);
    }

    @Test
    void protectionEndsOnceACopyOfTheFileExists() throws Exception {
        Path file = tmp.resolve("srv_s.json");
        Files.writeString(file, "old");
        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        fresh.save();
        assertEquals(1, count(".bak-"));
        assertFalse(fresh.protectsFile());
        assertFalse(fresh.isDirty());
        assertTrue(Files.readString(file).contains("\"name\""));
        settle(fresh);
    }

    @Test
    void forgetDeletesTheStashAndItsIndexAndDropsQueuedWrites() throws Exception {
        Stash s = new Stash("s", "srv");
        s.markDirty();
        s.save(); // queues an index write
        settle(s);
        Path data = tmp.resolve("srv_s.json");
        Path index = s.indexPath();
        Files.writeString(index, "index");
        Stash.delete("s", "srv");
        assertFalse(Files.exists(data));
        assertFalse(Files.exists(index));
        // the queued write (30 s gap or not) must not bring it back
        StashIndex.writeNow(s);
        StashIndex.forget(index);
        Thread.sleep(300);
        assertFalse(Files.exists(index));
    }

    @Test
    void aDeleteFailureIsReported() throws IOException {
        Path data = tmp.resolve("srv_s.json");
        Files.createDirectories(data.resolve("child"));
        boolean thrown = false;
        try {
            Stash.delete("s", "srv");
        } catch (IOException e) {
            thrown = true;
        }
        assertTrue(thrown);
    }

    @Test
    void aFileThatBecomesReadableIsTakenOverNotOverwritten() throws Exception {
        Path file = tmp.resolve("srv_s.json");
        Files.createDirectories(file); // unreadable now
        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        fresh.save(true);
        assertTrue(fresh.protectsFile());
        // the problem goes away: a real stash is there
        Files.delete(file);
        Stash saved = new Stash("s", "srv");
        saved.boxes.add(box(1, 2, 3));
        saved.markDirty();
        saved.save();
        settle(saved);
        String before = Files.readString(file);

        fresh.save(true);
        assertFalse(fresh.protectsFile());
        assertFalse(fresh.isDirty());
        assertEquals(1, fresh.boxes.size());
        assertEquals(before, Files.readString(file), "the file was not rewritten");
        assertEquals(0, count(".bak-"));
    }

    @Test
    void containersScannedWhileProtectedAreMergedIntoTheTakenOverFile() throws Exception {
        Path file = tmp.resolve("srv_s.json");
        Files.createDirectories(file);
        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        fresh.boxes.add(box(9, 9, 9));
        Files.delete(file);
        Stash saved = new Stash("s", "srv");
        saved.boxes.add(box(1, 2, 3));
        saved.markDirty();
        saved.save();
        settle(saved);
        fresh.save(true);
        assertFalse(fresh.protectsFile());
        assertEquals(2, fresh.boxes.size());
        Stash.Loaded again = Stash.loadFrom(file, "s", "srv");
        assertEquals(2, again.stash().boxes.size());
        settle(fresh);
    }

    @Test
    void aForcedSaveIgnoresTheBackoffButAnOrdinaryOneDoesNot() throws Exception {
        Path file = tmp.resolve("srv_s.json");
        Files.createDirectories(file);
        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        fresh.save(); // fails, starts the wait
        Files.delete(file);
        Files.writeString(file, "{ broken");
        fresh.save(); // inside the wait: nothing
        assertTrue(fresh.protectsFile());
        assertEquals(0, count(".bak-"));
        fresh.save(true); // the last save: invalid content is copied aside and replaced
        assertFalse(fresh.protectsFile());
        assertEquals(1, count(".bak-"));
        assertFalse(fresh.isDirty());
        settle(fresh);
    }

    @Test
    void resetMakesTheLastSaveOfAProtectedStash() throws Exception {
        Path file = tmp.resolve("srv_s.json");
        Files.createDirectories(file);
        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        fresh.save(); // starts the wait
        Files.delete(file);
        Files.writeString(file, "not json");
        java.lang.reflect.Field field = Stashes.class.getDeclaredField("stash");
        field.setAccessible(true);
        field.set(null, fresh);
        Stashes.reset();
        assertNull(field.get(null));
        assertEquals(1, count(".bak-"));
        assertTrue(Files.readString(file).contains("\"name\""));
        settle(fresh);
    }

    private static Stash.Box box(int x, int y, int z) {
        com.aquarius.mc.block.BlockPos pos = new com.aquarius.mc.block.BlockPos(x, y, z);
        return new Stash.Box(pos, new java.util.ArrayList<>(java.util.List.of(pos)));
    }

    @Test
    void oldStagingFilesAreSweptAndYoungOnesAndOthersStay() throws IOException {
        Path old = tmp.resolve(".stash-old1.tmp");
        Path young = tmp.resolve(".stash-young.tmp");
        Path other = tmp.resolve("srv_s.json");
        Path alsoOther = tmp.resolve(".stash-notmp");
        for (Path p : new Path[] {old, young, other, alsoOther}) Files.writeString(p, "x");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 2 * 3600_000L));
        Files.setLastModifiedTime(other, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 2 * 3600_000L));
        assertEquals(1, SnapshotFiles.sweepStale(tmp, 3600_000L));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(young));
        assertTrue(Files.exists(other));
        assertTrue(Files.exists(alsoOther));
    }

    @Test
    void thingsSetWhileProtectedSurviveTheTakeOver() throws Exception {
        Path file = tmp.resolve("srv_s.json");
        Files.createDirectories(file);
        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        fresh.dimension = "the_nether";
        fresh.cornerA = new int[] {1, 2, 3};
        fresh.cornerB = new int[] {4, 5, 6};
        fresh.dyedSpecGen = 2;
        fresh.repairGear = new java.util.ArrayList<>(java.util.List.of("mine"));
        fresh.activeOrder = new com.porter.qwertestash.orders.OrderFlow.Saved();
        fresh.activeOrder.id = "in-memory-order";
        fresh.orphans.add(new Stash.Orphan(new com.aquarius.mc.block.BlockPos(7, 7, 7), "shulker", 1, "now"));

        Files.delete(file);
        Stash saved = new Stash("s", "srv");
        saved.dimension = "overworld";
        saved.cornerA = new int[] {9, 9, 9};
        saved.dyedSpecGen = 1;
        saved.repairGear = new java.util.ArrayList<>(java.util.List.of("old"));
        saved.activeOrder = new com.porter.qwertestash.orders.OrderFlow.Saved();
        saved.activeOrder.id = "old-order";
        saved.orphans.add(new Stash.Orphan(new com.aquarius.mc.block.BlockPos(8, 8, 8), "shulker", 2, "then"));
        saved.inFlight = null;
        saved.markDirty();
        saved.save();
        settle(saved);

        java.util.List<Stash.Orphan> sameList = fresh.orphans;
        fresh.save(true);
        assertFalse(fresh.protectsFile());
        assertEquals("the_nether", fresh.dimension);
        assertEquals(1, fresh.cornerA[0]);
        assertEquals(4, fresh.cornerB[0]);
        assertEquals(2, fresh.dyedSpecGen);
        assertEquals(java.util.List.of("mine"), fresh.repairGear);
        assertEquals("in-memory-order", fresh.activeOrder.id);
        assertTrue(sameList == fresh.orphans, "merged into the same list object");
        assertEquals(2, fresh.orphans.size());
        settle(fresh);
    }

    @Test
    void fieldsLeftAtTheirDefaultAreTakenFromTheFile() throws Exception {
        Path file = tmp.resolve("srv_s.json");
        Files.createDirectories(file);
        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        Files.delete(file);
        Stash saved = new Stash("s", "srv");
        saved.dimension = "overworld";
        saved.cornerA = new int[] {9, 9, 9};
        saved.dyedSpecGen = 1;
        saved.markDirty();
        saved.save();
        settle(saved);
        fresh.save(true);
        assertEquals("overworld", fresh.dimension);
        assertEquals(9, fresh.cornerA[0]);
        assertEquals(1, fresh.dyedSpecGen);
        assertFalse(fresh.isDirty());
    }

    @Test
    void aBoxAtTheSamePositionKeepsNewContentsAndTheFilesMarks() throws Exception {
        Path file = tmp.resolve("srv_s.json");
        Files.createDirectories(file);
        Stash fresh = new Stash("s", "srv");
        fresh.protectExistingFile();
        Stash.Box mine = box(1, 2, 3);
        mine.readAt = 5000L;
        fresh.boxes.add(mine);
        Files.delete(file);
        Stash saved = new Stash("s", "srv");
        Stash.Box old = box(1, 2, 3);
        old.readAt = 100L;
        old.excluded = true;
        old.dropOff = true;
        old.labels = new java.util.ArrayList<>(java.util.List.of("sign"));
        saved.boxes.add(old);
        saved.markDirty();
        saved.save();
        settle(saved);
        fresh.save(true);
        assertEquals(1, fresh.boxes.size());
        Stash.Box merged = fresh.boxes.get(0);
        assertTrue(merged == mine, "the in-memory box stays");
        assertEquals(5000L, merged.readAt);
        assertTrue(merged.excluded);
        assertTrue(merged.dropOff);
        assertEquals(java.util.List.of("sign"), merged.labels);
        settle(fresh);
    }

    @Test
    void aWriteFinishingAfterAForgetDeletesItsFileButNotANewStashs() throws Exception {
        Stash s = new Stash("s", "srv");
        s.boxes.add(box(1, 1, 1));
        s.markDirty();
        s.save();
        settle(s);
        Path index = s.indexPath();
        // forgotten: nothing registered, a late write is taken away again
        StashIndex.forget(index);
        assertFalse(Files.exists(index));
        // a stash made again under the same name registers a new state and its index stays
        s.save();
        long end = System.currentTimeMillis() + 3000;
        while (!Files.exists(index) && System.currentTimeMillis() < end) Thread.sleep(10);
        Thread.sleep(100);
        assertTrue(Files.exists(index));
        settle(s);
    }
}
