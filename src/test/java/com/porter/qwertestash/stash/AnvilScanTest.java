package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.8.1 final review: the anvil search is bounded per tick and in height; the anvil goes into a hotbar slot that loses nothing. */
class AnvilScanTest {
    /** Everything air but the anvils given; a count of lookups. */
    static final class Counting implements AnvilFinder.Blocks {
        final Set<BlockPos> anvils = new HashSet<>();
        final Set<Long> unloadedChunks = new HashSet<>();
        long names, loads;

        @Override
        public boolean loaded(int x, int z) {
            loads++;
            return !unloadedChunks.contains(((long) Math.floorDiv(x, 16) << 32) | (Math.floorDiv(z, 16) & 0xffffffffL));
        }

        @Override
        public @Nullable String name(int x, int y, int z) {
            names++;
            return anvils.contains(new BlockPos(x, y, z)) ? "anvil" : "air";
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return true;
        }
    }

    @Test
    void theHeightIsOnlyTheStashsAndTheSorterHeight() {
        int[] r = AnvilFinder.region(new int[] {-100, -64, -100, 100, 320, 100}, List.of(new BlockPos(0, 60, 0), new BlockPos(5, 70, 5)), new BlockPos(0, 64, 0), 8, 3);
        assertEquals(57, r[1]);
        assertEquals(73, r[4]);
    }

    @Test
    void aBigSearchIsSpreadOverTicksNeverMoreThanTheBudgetEach() {
        Counting w = new Counting();
        w.anvils.add(new BlockPos(100, 64, -90));
        int[] region = {-128, 40, -128, 127, 103, 127};
        AnvilFinder.Scan scan = new AnvilFinder.Scan(region);
        int steps = 0;
        while (!scan.done() && steps < 10_000) {
            int used = scan.step(w, AnvilFinder.LOOKUPS_PER_TICK);
            assertTrue(used <= AnvilFinder.LOOKUPS_PER_TICK, "step used " + used);
            steps++;
        }
        assertTrue(scan.done());
        assertTrue(steps > 1, "spread over " + steps + " ticks");
        assertEquals(List.of(new BlockPos(100, 64, -90)), scan.found());
        assertEquals(256L * 256 * 64, w.names, "every block once");
    }

    @Test
    void unloadedChunksCostOneLookEach() {
        Counting w = new Counting();
        for (int cx = -2; cx < 2; cx++) for (int cz = -2; cz < 2; cz++) w.unloadedChunks.add(((long) cx << 32) | (cz & 0xffffffffL));
        AnvilFinder.Scan scan = new AnvilFinder.Scan(new int[] {-32, 60, -32, 31, 70, 31});
        scan.step(w, AnvilFinder.LOOKUPS_PER_TICK);
        assertTrue(scan.done());
        assertEquals(0, w.names);
        assertEquals(16, w.loads);
    }

    @Test
    void theAnvilGoesIntoAHotbarSlotThatLosesNothing() {
        Set<Integer> keep = Set.of(0), carry = Set.of(3, 4, 20, 21);
        // Already in an allowed hotbar slot: it stays
        assertEquals(4, AnvilFinder.hotbarFor(4, keep, carry, i -> true));
        // An empty hotbar slot first
        assertEquals(2, AnvilFinder.hotbarFor(20, keep, carry, i -> i != 2));
        // Else a carry slot (what is there is the stash's: it just swaps places)
        assertEquals(3, AnvilFinder.hotbarFor(20, keep, carry, i -> true));
        // Only last one holding the bot's own things (swapped back afterwards)
        assertEquals(1, AnvilFinder.hotbarFor(20, keep, Set.of(20), i -> true));
        // Every hotbar slot kept: none
        assertEquals(-1, AnvilFinder.hotbarFor(20, Set.of(0, 1, 2, 3, 4, 5, 6, 7, 8), carry, i -> false));
        assertFalse(AnvilFinder.hotbarFor(20, keep, carry, i -> false) == 0, "never a kept slot");
    }
}
