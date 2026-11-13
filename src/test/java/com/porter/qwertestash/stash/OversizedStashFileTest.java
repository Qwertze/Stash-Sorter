package com.porter.qwertestash.stash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A saved stash file above the load bound is renamed aside, never overwritten by a later save. */
class OversizedStashFileTest {
    @TempDir
    Path tmp;

    @Test
    void anOversizedFileIsRenamedAsideWithItsDataKept() throws IOException {
        Path file = tmp.resolve("s.json");
        String data = "x".repeat(100);
        Files.writeString(file, data, StandardCharsets.UTF_8);
        Path aside = Stash.setAsideIfOversized(file, 50);
        assertNotNull(aside);
        assertFalse(Files.exists(file));
        assertTrue(aside.getFileName().toString().startsWith("s.json.oversized-"));
        assertEquals(data, Files.readString(aside));
        // a fresh save at the old path can't touch it
        Files.writeString(file, "{}", StandardCharsets.UTF_8);
        assertEquals(data, Files.readString(aside));
    }

    @Test
    void aFileWithinTheBoundIsLeftAlone() throws IOException {
        Path file = tmp.resolve("s.json");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        assertNull(Stash.setAsideIfOversized(file, 3));
        assertTrue(Files.exists(file));
        assertEquals(256L << 20, Stash.MAX_FILE_BYTES);
    }
}
