package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which furnace an order smelts in (CRAFT-CONTRACT-v1.2.md 14): remembered, then the nearest empty one standing, then one from the stock, then one crafted, else no_furnace. */
class FurnaceFinderTest {
    private static final BlockPos A = new BlockPos(1, 64, 1), B = new BlockPos(10, 64, 10);

    static final class Fake implements FurnaceFinder.World {
        final Set<BlockPos> furnaces = new HashSet<>();
        @Nullable BlockPos found;
        boolean stock, craft;
        int searches;

        @Override
        public boolean isFurnace(BlockPos p) {
            return furnaces.contains(p);
        }

        @Override
        public @Nullable BlockPos search() {
            searches++;
            return found;
        }

        @Override
        public boolean stockHasFurnace() {
            return stock;
        }

        @Override
        public boolean canCraft() {
            return craft;
        }
    }

    @Test
    void theRememberedFurnaceComesFirstAndIsNotSearchedFor() {
        Fake w = new Fake();
        w.furnaces.add(A);
        w.found = B;
        w.stock = true;
        FurnaceFinder.Decision d = FurnaceFinder.decide(new FurnaceFinder.Memory(A, "placed"), w);
        assertEquals(FurnaceFinder.Step.USE, d.step());
        assertEquals(A, d.pos());
        assertEquals("placed", d.source());
        assertEquals(0, w.searches);
        assertFalse(d.forget());
    }

    @Test
    void aRememberedFurnaceThatIsGoneIsForgottenAndTheSearchGoesOn() {
        Fake w = new Fake();
        w.found = B;
        FurnaceFinder.Decision d = FurnaceFinder.decide(new FurnaceFinder.Memory(A, "found"), w);
        assertEquals(FurnaceFinder.Step.FOUND, d.step());
        assertEquals(B, d.pos());
        assertEquals("found", d.source());
        assertTrue(d.forget());
    }

    @Test
    void withNothingRememberedTheNearestStandingFurnaceIsNextThenTheStockThenACraft() {
        Fake w = new Fake();
        w.found = B;
        w.stock = true;
        w.craft = true;
        assertEquals(FurnaceFinder.Step.FOUND, FurnaceFinder.decide(null, w).step());
        w.found = null;
        FurnaceFinder.Decision stock = FurnaceFinder.decide(null, w);
        assertEquals(FurnaceFinder.Step.PLACE_FROM_STOCK, stock.step());
        assertEquals("placed", stock.source());
        w.stock = false;
        FurnaceFinder.Decision craft = FurnaceFinder.decide(null, w);
        assertEquals(FurnaceFinder.Step.CRAFT, craft.step());
        assertEquals("crafted", craft.source());
    }

    @Test
    void withNoneOfThatTheReasonIsNoFurnace() {
        FurnaceFinder.Decision d = FurnaceFinder.decide(null, new Fake());
        assertEquals(FurnaceFinder.Step.FAIL, d.step());
        assertEquals(FurnaceFinder.NONE, d.failure());
        assertNull(d.pos());
    }

    @Test
    void onlyAFurnaceTheCraftLimitsRuleOutIsOutOfLimits() {
        assertTrue(FurnaceFinder.outOfLimits(CraftBounds.TOO_FAR));
        assertTrue(FurnaceFinder.outOfLimits(CraftBounds.WRONG_DIMENSION));
        assertFalse(FurnaceFinder.outOfLimits(null), "inside the limits");
        assertFalse(FurnaceFinder.outOfLimits(CraftBounds.NO_AREA), "no area known: no verdict");
        assertFalse(FurnaceFinder.outOfLimits(CraftBounds.NO_DIMENSION));
        // The same verdicts CraftBounds gives for a furnace spot: inside the area widened by 64, and far outside it
        CraftBounds.StashArea area = new CraftBounds.StashArea(new int[] {0, 60, 0, 20, 70, 20}, List.of(), 0, 0);
        assertFalse(FurnaceFinder.outOfLimits(CraftBounds.areaWhy("overworld", area, new CraftSettings.Table(10, 64, 10, "overworld"), 64)));
        assertTrue(FurnaceFinder.outOfLimits(CraftBounds.areaWhy("overworld", area, new CraftSettings.Table(5000, 64, 10, "overworld"), 64)));
        assertTrue(FurnaceFinder.outOfLimits(CraftBounds.areaWhy("overworld", area, new CraftSettings.Table(10, 64, 10, "the_nether"), 64)));
    }

    @Test
    void aFurnaceKnownToBeEmptyWinsOverANearerOneNobodyLookedInto() {
        BlockPos near = new BlockPos(2, 64, 2), empty = new BlockPos(9, 64, 9);
        BlockPos from = new BlockPos(0, 64, 0);
        assertEquals(empty, FurnaceFinder.nearestPreferring(List.of(near, empty), from, p -> true, p -> p.equals(empty)));
        assertEquals(near, FurnaceFinder.nearestPreferring(List.of(near, empty), from, p -> true, p -> false), "none known: the nearest");
        assertEquals(near, FurnaceFinder.nearestPreferring(List.of(near, empty), from, p -> !p.equals(empty), p -> p.equals(empty)), "a preferred one that is not usable is not");
    }

    @Test
    void theNearestFurnaceWinsTiesByCoordinatesAndOnlyAPlainFurnaceCounts() {
        BlockPos near = new BlockPos(2, 64, 2), far = new BlockPos(9, 64, 9), tie = new BlockPos(2, 64, -2);
        assertEquals(near, FurnaceFinder.nearest(List.of(far, near), new BlockPos(0, 64, 0), p -> true));
        assertEquals(far, FurnaceFinder.nearest(List.of(far, near), new BlockPos(0, 64, 0), p -> !p.equals(near)));
        assertEquals(tie, FurnaceFinder.nearest(List.of(near, tie), new BlockPos(0, 64, 0), p -> true), "equal distance: by x, y, z");
        assertTrue(FurnaceFinder.isFurnaceName("furnace"));
        assertFalse(FurnaceFinder.isFurnaceName("blast_furnace"));
        assertFalse(FurnaceFinder.isFurnaceName("smoker"));
        assertTrue(FurnaceFinder.isOwn("placed") && FurnaceFinder.isOwn("crafted"));
        assertFalse(FurnaceFinder.isOwn("found"));
    }
}
