package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.7.2 review H1: a box is only forgotten as broken in the stash's own world. The proxy's chunk cache is cleared on a
 * login or a dimension change and refilled from the new world, and with no world it says "overworld", so a queue world,
 * the Nether at the same x/z or the first moments after a change all look like air where the stash stands.
 */
class GoneWatchTest {
    private static final String OVER = "overworld", NETHER = "the_nether";
    private final Set<BlockPos> chests = new HashSet<>();
    private final Predicate<BlockPos> loaded = p -> true;
    private final Predicate<BlockPos> container = chests::contains;

    private Box single(int x) {
        BlockPos p = new BlockPos(x, 64, 0);
        chests.add(p);
        return new Box(p, new ArrayList<>(List.of(p)));
    }

    /** What the sorter does on a check: the gate, the guard, then the two-check rule. */
    private List<Box> check(GoneWatch w, List<Box> boxes, String stashDim, String here, long now) {
        int standing = GoneBoxes.stillThere(boxes, loaded, container);
        if (w.blocker(stashDim, here, standing, now) != null) return List.of();
        return w.confirmed(GoneBoxes.toDrop(boxes, loaded, container), now);
    }

    @Test
    void anotherDimensionDropsNothing() {
        GoneWatch w = new GoneWatch();
        w.reset(0);
        w.observe(OVER, 0);
        w.read(OVER, 1_000);
        Box b = single(1);
        single(3);
        // Through a portal: the same x/z in the Nether is air
        chests.clear();
        for (long t = 40_000; t < 200_000; t += 5_000) assertEquals(List.of(), check(w, List.of(b), OVER, NETHER, t));
        assertNotNull(w.blocker(OVER, NETHER, 0, 200_000));
    }

    @Test
    void rightAfterLoginNothingIsDropped() {
        GoneWatch w = new GoneWatch();
        w.reset(0);
        w.observe(OVER, 0);
        w.read(OVER, 0);
        Box a = single(1), b = single(3);
        chests.remove(a.pos);
        // Within the first 30 seconds of the world, even with proof it is the stash's
        assertEquals("this world was only just joined", w.blocker(OVER, OVER, 1, 10_000));
        assertEquals(List.of(), check(w, List.of(a, b), OVER, OVER, 10_000));
        assertEquals(List.of(), check(w, List.of(a, b), OVER, OVER, 20_000));
        // A login later: the read before it no longer counts, and the settle time starts again
        w.reset(100_000);
        w.observe(OVER, 100_000);
        assertNotNull(w.blocker(OVER, OVER, 1, 110_000));
    }

    @Test
    void aOneBoxStashInAForeignWorldIsKept() {
        // No perimeter, no delivery container, one box; the queue world or limbo reports "overworld" (the proxy's fallback)
        GoneWatch w = new GoneWatch();
        w.reset(0);
        w.observe(OVER, 0);
        Box only = single(1);
        chests.clear();
        for (long t = 0; t < 600_000; t += 5_000) assertEquals(List.of(), check(w, List.of(only), OVER, OVER, t));
        assertEquals("nothing shows this is the stash's world yet", w.blocker(OVER, OVER, 0, 600_000));
        // A stash file from before 1.7.2: no dimension recorded, nothing is ever dropped until a read records it
        assertEquals("the stash's dimension isn't known yet", w.blocker(null, OVER, 5, 600_000));
    }

    @Test
    void aGenuinelyBrokenBoxIsDroppedAfterTheSecondCheck() {
        GoneWatch w = new GoneWatch();
        w.reset(0);
        w.observe(OVER, 0);
        Box broken = single(1), kept = single(3);
        chests.remove(broken.pos);
        // Past the settle time, with another container of the stash standing there
        assertEquals(List.of(), check(w, List.of(broken, kept), OVER, OVER, 40_000), "first check: only remembered");
        assertEquals(List.of(), check(w, List.of(broken, kept), OVER, OVER, 43_000), "less than 5 s later");
        assertEquals(List.of(broken), check(w, List.of(broken, kept), OVER, OVER, 45_000), "second check 5 s on");
    }

    @Test
    void aReadInThisWorldIsProofForAOneBoxStash() {
        GoneWatch w = new GoneWatch();
        w.reset(0);
        w.observe(OVER, 0);
        Box only = single(1);
        w.read(OVER, 2_000);
        chests.clear();
        assertNull(w.blocker(OVER, OVER, 0, 40_000));
        assertEquals(List.of(), check(w, List.of(only), OVER, OVER, 40_000));
        assertEquals(List.of(only), check(w, List.of(only), OVER, OVER, 46_000));
    }

    @Test
    void aDimensionChangeForgetsTheFirstChecksAndTheProof() {
        GoneWatch w = new GoneWatch();
        w.reset(0);
        w.observe(OVER, 0);
        w.read(OVER, 0);
        Box broken = single(1), kept = single(3);
        chests.remove(broken.pos);
        assertEquals(List.of(), check(w, List.of(broken, kept), OVER, OVER, 40_000));
        w.observe(NETHER, 41_000);
        w.observe(OVER, 50_000);
        // Back again: settle time and both checks from the start
        assertEquals(List.of(), check(w, List.of(broken, kept), OVER, OVER, 60_000));
        assertEquals(List.of(), check(w, List.of(broken, kept), OVER, OVER, 81_000));
        assertEquals(List.of(broken), check(w, List.of(broken, kept), OVER, OVER, 86_000));
    }

    @Test
    void theEverythingInViewGuardStillHolds() {
        GoneWatch w = new GoneWatch();
        w.reset(0);
        w.observe(OVER, 0);
        w.read(OVER, 0);
        List<Box> boxes = List.of(single(1), single(3), single(5));
        chests.clear();
        assertEquals(List.of(), check(w, boxes, OVER, OVER, 40_000));
        assertEquals(List.of(), check(w, boxes, OVER, OVER, 50_000));
    }

    @Test
    void aDoubleChestThatLostItsSavedHalfIsBuiltFromTheOther() {
        BlockPos a = new BlockPos(1, 64, 0), b = new BlockPos(2, 64, 0);
        chests.add(b);
        Box d = new Box(a, new ArrayList<>(List.of(a, b)));
        assertEquals(b, GoneBoxes.remainingHalf(d, loaded, container));
        // Not gone as a whole: the half left stands
        assertTrue(!GoneBoxes.gone(d, loaded, container));
        chests.add(a);
        assertNull(GoneBoxes.remainingHalf(d, loaded, container), "both halves stand");
        chests.remove(a);
        assertNull(GoneBoxes.remainingHalf(d, p -> !p.equals(b), container), "the other half's chunk isn't loaded");
    }
}
