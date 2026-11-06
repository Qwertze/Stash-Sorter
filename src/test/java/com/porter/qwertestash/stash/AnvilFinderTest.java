package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.8.1: an anvil for naming without one being set: found standing in the stash area, or put down from the stash. */
class AnvilFinderTest {
    /** A flat world at y 64 (floor at 63) with blocks set by the test. */
    static final class World implements AnvilFinder.Blocks {
        final Map<BlockPos, String> blocks = new HashMap<>();
        boolean loaded = true;

        World put(int x, int y, int z, String name) {
            blocks.put(new BlockPos(x, y, z), name);
            return this;
        }

        @Override
        public boolean loaded(int x, int z) {
            return loaded;
        }

        @Override
        public @Nullable String name(int x, int y, int z) {
            if (y <= 63) return "stone";
            return blocks.getOrDefault(new BlockPos(x, y, z), "air");
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return y == 64 && "air".equals(name(x, y, z)) && "air".equals(name(x, y + 1, z));
        }
    }

    private static final BlockPos FEET = new BlockPos(0, 64, 0);

    @Test
    void findsAnAnvilStandingWithinTheRadius() {
        World w = new World().put(5, 64, 3, "chipped_anvil").put(-20, 64, 0, "anvil");
        // No perimeter: the stash's containers widened by the radius the sorter looks for containers in (8)
        int[] region = AnvilFinder.region(null, List.of(new BlockPos(2, 64, 2)), FEET, 8, 3);
        assertArrayEquals(new int[] {-6, 61, -6, 10, 67, 10}, region);
        List<BlockPos> found = AnvilFinder.scan(region, w);
        assertEquals(List.of(new BlockPos(5, 64, 3)), found, "the one 20 blocks off is outside the radius");
        assertEquals(new BlockPos(5, 64, 3), AnvilFinder.nearest(FEET, found, w, p -> true, Set.of()));
        // Unloaded chunks are not looked at
        w.loaded = false;
        assertTrue(AnvilFinder.scan(region, w).isEmpty());
    }

    @Test
    void oneOutsideThePerimeterIsIgnored() {
        World w = new World().put(5, 64, 0, "anvil").put(-3, 64, 0, "damaged_anvil");
        int[] perimeter = {0, 60, -5, 10, 70, 5};
        assertArrayEquals(perimeter, AnvilFinder.region(perimeter, List.of(), FEET, 8, 3));
        List<BlockPos> found = AnvilFinder.scan(new int[] {-10, 60, -10, 10, 70, 10}, w);
        BlockPos pick = AnvilFinder.nearest(FEET, found, w, p -> p.x() >= perimeter[0] && p.x() <= perimeter[3], Set.of());
        assertEquals(new BlockPos(5, 64, 0), pick, "the nearer one is outside the perimeter");
    }

    @Test
    void oneTheBotCantStandNextToOrGivenUpOnIsSkipped() {
        World w = new World().put(2, 64, 0, "anvil").put(6, 64, 0, "anvil");
        // Walled in on every side
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            w.put(2 + d[0], 64, d[1], "stone");
            w.put(2 + d[0], 65, d[1], "stone");
        }
        List<BlockPos> found = AnvilFinder.scan(new int[] {-10, 60, -10, 10, 70, 10}, w);
        assertEquals(new BlockPos(6, 64, 0), AnvilFinder.nearest(FEET, found, w, p -> true, Set.of()));
        assertNull(AnvilFinder.nearest(FEET, found, w, p -> true, Set.of(new BlockPos(6, 64, 0))));
    }

    @Test
    void theOrderOfChoice() {
        assertEquals(AnvilFinder.Way.USE, AnvilFinder.decide(true, true, true), "the one set (or remembered) first");
        assertEquals(AnvilFinder.Way.FOUND, AnvilFinder.decide(false, true, true), "then one standing");
        assertEquals(AnvilFinder.Way.PLACE, AnvilFinder.decide(false, false, true), "then one put down from the stash");
        assertEquals(AnvilFinder.Way.NONE, AnvilFinder.decide(false, false, false));
        assertEquals("no anvils available to rename with", AnvilFinder.NO_ANVILS);
        assertEquals("minecraft:anvil", AnvilFinder.ITEMS.get(0), "an undamaged anvil is taken first");
    }

    @Test
    void theRememberedOneGoneIsLookedForAgain() {
        World w = new World().put(4, 64, 0, "anvil");
        BlockPos remembered = new BlockPos(4, 64, 0);
        assertTrue(AnvilFinder.isAnvil(w.name(4, 64, 0)));
        // It broke (an anvil breaks after enough uses): the block there is air now, it is given up on and another searched for
        w.put(4, 64, 0, "air").put(-6, 64, 2, "damaged_anvil");
        assertEquals(false, AnvilFinder.isAnvil(w.name(4, 64, 0)));
        List<BlockPos> found = AnvilFinder.scan(new int[] {-10, 60, -10, 10, 70, 10}, w);
        assertEquals(new BlockPos(-6, 64, 2), AnvilFinder.nearest(FEET, found, w, p -> true, Set.of(remembered)));
        assertEquals(AnvilFinder.Way.FOUND, AnvilFinder.decide(false, true, false));
        // None standing any more, one in the stash: it is put down
        assertEquals(AnvilFinder.Way.PLACE, AnvilFinder.decide(false, false, true));
    }

    @Test
    void theSearchIsBounded() {
        int[] huge = AnvilFinder.region(new int[] {-1000, 0, -1000, 1000, 300, 1000}, List.of(), FEET, 8, 3);
        assertEquals(AnvilFinder.MAX_WIDTH, huge[3] - huge[0] + 1);
        assertEquals(AnvilFinder.MAX_HEIGHT, huge[4] - huge[1] + 1);
        assertTrue(huge[0] <= 0 && huge[3] >= 0, "around the bot");
    }
}
