package com.porter.qwertestash.farm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.porter.qwerte.core.farm.FarmException;
import com.porter.qwerte.core.farm.FarmRecording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Random;

/** The encrypted stores (FARM A9, C6.4, C6.10): seal, rollback, tamper, wrong key, quota, paths, key handling. */
class FarmStoreTest {
    private static String key(long seed) {
        byte[] k = new byte[32];
        new Random(seed).nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private static final FarmRecording REC = Sim.walkLoop("custom", Sim.loops(0), null);
    private static final byte[] PLAIN = REC.encode();

    private FarmStore store(Path dir, String env) {
        return new FarmStore(dir.resolve("farm"), env, System::currentTimeMillis);
    }

    @Test
    void aProgramRoundTripsAndTheOriginLivesOnlyInTheSealedLocationStore(@TempDir Path dir) throws IOException {
        FarmStore s = store(dir, key(1));
        s.put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 12345, 64, -6789);
        assertArrayEquals(PLAIN, s.open("f-1a2b3c4d"));
        FarmStore.Entry e = s.entry("f-1a2b3c4d");
        assertEquals(12345, e.ox());
        assertEquals("custom", e.mode());
        assertEquals(80, e.ticks());
        // nothing on disk holds the origin or a recognisable program
        for (Path p : (Iterable<Path>) Files.list(dir.resolve("farm"))::iterator) {
            byte[] b = Files.readAllBytes(p);
            String text = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(text.contains("12345") || text.contains("-6789") || text.contains("QFP1") || text.contains("minecraft"), p.getFileName().toString());
        }
        // a new store over the same dir with the same key reads it again
        assertArrayEquals(PLAIN, store(dir, key(1)).open("f-1a2b3c4d"));
    }

    @Test
    void attachReSealsTheDraftUnderTheFarmIdWithTheSameHash(@TempDir Path dir) {
        FarmStore s = store(dir, key(1));
        s.put("draft", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        s.attach("f-1a2b3c4d", FarmRecording.sha256Hex(PLAIN));
        assertNull(s.entry("draft"));
        assertFalse(s.hasFile("draft"));
        assertArrayEquals(PLAIN, s.open("f-1a2b3c4d"));
        assertEquals(FarmRecording.sha256Hex(PLAIN), s.entry("f-1a2b3c4d").programHash());
        // a draft whose hash is not the one asked for is not attached
        s.put("draft", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        assertEquals("bad_hash", assertThrows(FarmException.class, () -> s.attach("f-aaaaaaaa", "0".repeat(64))).code());
    }

    @Test
    void anOlderFileForTheSameFarmIsRefusedAndLeftInPlace(@TempDir Path dir) throws IOException {
        FarmStore s = store(dir, key(1));
        s.put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        Path file = dir.resolve("farm").resolve("f-1a2b3c4d.qse");
        byte[] older = Files.readAllBytes(file);
        FarmRecording rec2 = Sim.walkLoop("custom", Sim.loops(5), null);
        s.put("f-1a2b3c4d", rec2.encode(), rec2, "minecraft:overworld", 1, 2, 3);
        Files.write(file, older); // a rollback
        assertEquals("seal_failed", assertThrows(FarmException.class, () -> s.open("f-1a2b3c4d")).code());
        assertTrue(Files.exists(file), "a file that cannot be opened is never deleted");
    }

    @Test
    void aFlippedByteIsSealFailedAndTheFileStays(@TempDir Path dir) throws IOException {
        FarmStore s = store(dir, key(1));
        s.put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        Path file = dir.resolve("farm").resolve("f-1a2b3c4d.qse");
        byte[] b = Files.readAllBytes(file);
        b[b.length - 5] ^= 1;
        Files.write(file, b);
        assertEquals("seal_failed", assertThrows(FarmException.class, () -> s.open("f-1a2b3c4d")).code());
        assertTrue(Files.exists(file));
    }

    @Test
    void aWrongKeyIsKeyMismatchAndNothingIsDeleted(@TempDir Path dir) {
        store(dir, key(1)).put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        FarmStore other = store(dir, key(2));
        assertEquals("key_mismatch", assertThrows(FarmException.class, () -> other.open("f-1a2b3c4d")).code());
        assertEquals("key_mismatch", assertThrows(FarmException.class, other::entries).code());
        assertTrue(Files.exists(dir.resolve("farm").resolve("f-1a2b3c4d.qse")));
        assertTrue(Files.exists(dir.resolve("farm").resolve("locations.qse")));
    }

    @Test
    void anUnusableEnvironmentKeyFailsClosedWithoutFallingBackToTheFile(@TempDir Path dir) {
        assertEquals("key_invalid", store(dir, Base64.getEncoder().encodeToString(new byte[31])).keyCode());
        assertEquals("key_invalid", store(dir, "not base64 !!").keyCode());
        assertNull(store(dir, "  " + key(3) + "  ").keyCode(), "surrounding whitespace is trimmed");
        assertFalse(Files.exists(dir.resolve("farm").resolve("farm.key")), "an environment key never writes a key file");
    }

    @Test
    void noKeyIsEverMadeOverSealedFilesAndWindowsFailsClosed(@TempDir Path dir) throws IOException {
        store(dir, key(1)).put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        FarmStore noEnv = store(dir, null);
        String code = noEnv.keyCode();
        assertTrue(code.equals("key_missing") || code.equals("key_perms"), code);
        assertFalse(Files.exists(dir.resolve("farm").resolve("farm.key")), "never a new key over existing data");
    }

    @Test
    void thePosixKeyFileIs0600AndTheDirectory0700(@TempDir Path dir) throws IOException {
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        FarmStore s = store(dir, null);
        assertNull(s.keyCode());
        assertEquals("file", s.keySource());
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(dir.resolve("farm").resolve("farm.key")));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir.resolve("farm")));
    }

    @Test
    void theKeyNeverAppearsInAnythingTheStoreOrTheSelfTestPrints(@TempDir Path dir) {
        String env = key(9);
        FarmStore s = store(dir, env);
        String all = s.keyIdHex() + s.keySource() + s.entries() + s.entry("draft");
        s.put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 7, 8, 9);
        all += s.entry("f-1a2b3c4d");
        assertFalse(all.contains(env));
        assertEquals(8, s.keyIdHex().length());
        FarmException e = assertThrows(FarmException.class, () -> store(dir, key(10)).open("f-1a2b3c4d"));
        assertFalse(String.valueOf(e.getMessage()).contains(env));
        assertEquals(0, e.getStackTrace().length, "no stack trace, nothing to leak through it");
    }

    @Test
    void pathsComeOnlyFromValidatedIds(@TempDir Path dir) {
        FarmStore s = store(dir, key(1));
        for (String bad : new String[] {"../x", "f-1a2b3c4d/../../x", "F-1A2B3C4D", "f-1a2b3c4", "locations", "draft.qse", ""}) {
            assertEquals("bad_program", assertThrows(FarmException.class, () -> s.put(bad, PLAIN, REC, "minecraft:overworld", 1, 2, 3)).code(), bad);
        }
    }

    @Test
    void theQuotaIsThirtyTwoPrograms(@TempDir Path dir) {
        FarmStore s = store(dir, key(1));
        for (int i = 0; i < 32; i++) s.put(String.format("f-%08x", i), PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        assertEquals("store_full", assertThrows(FarmException.class, () -> s.put("f-000000ff", PLAIN, REC, "minecraft:overworld", 1, 2, 3)).code());
        // replacing one that is there is fine
        s.put("f-00000001", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
    }

    @Test
    void staleTemporaryFilesAreRemovedAndFreshOnesKept(@TempDir Path dir) throws IOException {
        FarmStore s = store(dir, key(1));
        s.put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        Path old = dir.resolve("farm").resolve("f-aaaaaaaa.qse.tmp");
        Path fresh = dir.resolve("farm").resolve("f-bbbbbbbb.qse.tmp");
        Files.writeString(old, "x");
        Files.writeString(fresh, "x");
        Files.setLastModifiedTime(old, FileTime.fromMillis(System.currentTimeMillis() - 11 * 60_000L));
        assertEquals(1, s.cleanTmp());
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    @Test
    void forgetDeletesTheFileAndTheLocation(@TempDir Path dir) {
        FarmStore s = store(dir, key(1));
        s.put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        s.forget("f-1a2b3c4d");
        assertNull(s.entry("f-1a2b3c4d"));
        assertFalse(s.hasFile("f-1a2b3c4d"));
        assertEquals("program_missing", assertThrows(FarmException.class, () -> s.open("f-1a2b3c4d")).code());
        s.forget("f-1a2b3c4d"); // deleting what is not there is fine
        assertNotNull(s.entries());
    }

    @Test
    void aFailureWhileReplacingLeavesTheOldProgramAndNoTemporaryProgramFile(@TempDir Path dir) throws IOException {
        FarmStore s = store(dir, key(1));
        s.put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 1, 2, 3);
        byte[] oldFile = Files.readAllBytes(dir.resolve("farm").resolve("f-1a2b3c4d.qse"));
        byte[] oldLoc = Files.readAllBytes(dir.resolve("farm").resolve("locations.qse"));
        // a non-empty directory where the temporary locations file must go makes the staging fail: nothing is replaced
        Files.createDirectory(dir.resolve("farm").resolve("locations.qse.tmp"));
        Files.writeString(dir.resolve("farm").resolve("locations.qse.tmp").resolve("keep"), "x");
        FarmRecording rec2 = Sim.walkLoop("custom", Sim.loops(5), null);
        assertThrows(FarmException.class, () -> s.put("f-1a2b3c4d", rec2.encode(), rec2, "minecraft:overworld", 1, 2, 3));
        assertArrayEquals(oldFile, Files.readAllBytes(dir.resolve("farm").resolve("f-1a2b3c4d.qse")), "the old program is untouched");
        assertArrayEquals(oldLoc, Files.readAllBytes(dir.resolve("farm").resolve("locations.qse")));
        assertFalse(Files.exists(dir.resolve("farm").resolve("f-1a2b3c4d.qse.tmp")), "the staged program file is removed again");
        assertArrayEquals(PLAIN, s.open("f-1a2b3c4d"), "and it still opens");
        // a new farm id that fails to stage leaves no program file behind
        assertThrows(FarmException.class, () -> s.put("f-2b3c4d5e", rec2.encode(), rec2, "minecraft:overworld", 1, 2, 3));
        assertFalse(Files.exists(dir.resolve("farm").resolve("f-2b3c4d5e.qse")));
    }

    @Test
    void openWithEntryGivesTheOriginAndTheProgramOfTheSameCommit(@TempDir Path dir) {
        FarmStore s = store(dir, key(1));
        s.put("f-1a2b3c4d", PLAIN, REC, "minecraft:overworld", 12345, 64, -6789);
        FarmStore.Opened o = s.openWithEntry("f-1a2b3c4d");
        assertArrayEquals(PLAIN, o.plain());
        assertEquals(12345, o.entry().ox());
        assertEquals(FarmRecording.sha256Hex(PLAIN), o.entry().programHash());
        assertEquals("program_missing", assertThrows(FarmException.class, () -> s.openWithEntry("f-0000000f")).code());
    }
}
