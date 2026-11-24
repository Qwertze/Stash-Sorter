package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** B4: a written book is copied from an original; the gather prefers generation 0 and leaves a copy only when nothing else is there. */
class GenerationPrefTest {
    @Test
    void aCopyIsPenalisedOnlyForACopiedBook() {
        assertEquals(0, GenerationPref.penalty(true, 0));
        assertEquals(0, GenerationPref.penalty(true, -1));
        assertEquals(GenerationPref.COPY_PENALTY, GenerationPref.penalty(true, 1));
        assertEquals(GenerationPref.COPY_PENALTY, GenerationPref.penalty(true, 2));
        assertEquals(0, GenerationPref.penalty(false, 1));
        assertTrue(GenerationPref.isCopy(1));
        assertFalse(GenerationPref.isCopy(0));
        assertFalse(GenerationPref.isCopy(-1));
    }

    @Test
    void theOriginalWinsOverACopyWhateverElseTheScoreSays() {
        // takeLot's score: a copy with exactly enough (0) against an original of another variant and far too many (1_000_000 + 63)
        long copy = GenerationPref.penalty(true, 1);
        long original = 1_000_000L + 63;
        assertTrue(original < copy + 0);
        // and with equal generations nothing changes
        assertEquals(GenerationPref.penalty(true, 1), GenerationPref.penalty(true, 3));
    }

    @Test
    void aKeyWithOnlyCopiesStillGivesOne() {
        // two copies, no original: scores differ only by count; the lowest is picked, none is refused
        long a = GenerationPref.score(true, 1, true, 1, 1, 1);
        long b = GenerationPref.score(true, 2, true, 1, 1, 1);
        assertTrue(Math.min(a, b) < Long.MAX_VALUE);
        assertEquals(a, b);
    }

    @Test
    void generationsMinusOneZeroAndOneMixed() {
        // want 1: an exact copy (1), an exact original (0), a far-too-big original (-1 unknown, 64 of 64) and an exact unknown
        long copy = GenerationPref.score(true, 1, true, 1, 1, 64);
        long original = GenerationPref.score(true, 0, true, 1, 1, 64);
        long unknown = GenerationPref.score(true, -1, true, 1, 1, 64);
        long bigOriginal = GenerationPref.score(true, 0, true, 64, 1, 64);
        assertEquals(original, unknown, "an unknown generation is neither preferred nor avoided");
        assertTrue(original < copy);
        assertTrue(bigOriginal < copy, "a big original beats an exact copy");
        // a copy of a book that is not copied (a plain order of copies) gets no penalty
        assertEquals(original, GenerationPref.score(false, 1, true, 1, 1, 64));
        // among the four the lowest is an original or the unknown one
        java.util.List<Long> all = java.util.List.of(copy, original, unknown, bigOriginal);
        assertEquals(original, java.util.Collections.min(all));
    }

    @Test
    void boxesWithAnOriginalComeFirst() {
        List<String> boxes = List.of("copies", "mixed", "copies2");
        assertEquals(List.of("mixed"), GenerationPref.prefer(boxes, b -> b.equals("mixed")));
        // none with an original: all stay (the line then comes up short as before; the stash check refuses a key with copies only before anything is taken)
        assertEquals(boxes, GenerationPref.prefer(boxes, b -> false));
    }
}
