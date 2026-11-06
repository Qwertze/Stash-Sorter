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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.7.2: containers a player broke are forgotten without a perimeter. On 1.7.1 five broken boxes of "Farm" (no perimeter)
 * were read again every minute for two hours.
 */
class GoneBoxesTest {
    /** The world as the proxy has it loaded: which chunks (by chunk x) and which blocks are chests. */
    private final Set<Integer> loadedChunks = new HashSet<>();
    private final Set<BlockPos> chests = new HashSet<>();
    private final Predicate<BlockPos> loaded = p -> loadedChunks.contains(p.x() >> 4);
    private final Predicate<BlockPos> container = p -> loaded.test(p) && chests.contains(p);

    private Box single(int x) {
        BlockPos p = new BlockPos(x, 64, 0);
        chests.add(p);
        return new Box(p, new ArrayList<>(List.of(p)));
    }

    private Box dbl(int x) {
        BlockPos a = new BlockPos(x, 64, 0), b = new BlockPos(x + 1, 64, 0);
        chests.add(a);
        chests.add(b);
        return new Box(a, new ArrayList<>(List.of(a, b)));
    }

    private void breakBox(Box box) {
        chests.removeAll(box.parts);
        chests.remove(box.pos);
    }

    @Test
    void aBoxWhoseBlocksAreAirInLoadedChunksIsDroppedWithoutAPerimeter() {
        loadedChunks.add(0);
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < 6; i++) boxes.add(single(i * 2));
        Box dbl = dbl(13);
        boxes.add(dbl);
        breakBox(boxes.get(2));
        breakBox(dbl);

        List<Box> gone = GoneBoxes.toDrop(boxes, loaded, container);
        assertEquals(List.of(boxes.get(2), dbl), gone);
        assertTrue(GoneBoxes.gone(dbl, loaded, container));
        assertFalse(GoneBoxes.gone(boxes.get(0), loaded, container));
    }

    @Test
    void aBoxInAnUnloadedChunkIsKept() {
        loadedChunks.add(0);
        Box here = single(1), far = single(40), kept = single(3);
        breakBox(here);
        // Chunk 2 is not loaded: what is there is not known, whatever the cache says
        chests.remove(far.pos);
        assertTrue(GoneBoxes.gone(here, loaded, container));
        assertFalse(GoneBoxes.gone(far, loaded, container));
        assertEquals(List.of(here), GoneBoxes.toDrop(List.of(here, far, kept), loaded, container));
    }

    @Test
    void aDoubleChestHalfInAnUnloadedChunkIsKept() {
        loadedChunks.add(0);
        Box edge = dbl(15);
        breakBox(edge);
        assertFalse(GoneBoxes.gone(edge, loaded, container), "the half in chunk 1 is not loaded");
        loadedChunks.add(1);
        assertTrue(GoneBoxes.gone(edge, loaded, container));
    }

    @Test
    void halfOfADoubleChestLeftStandingKeepsTheBox() {
        loadedChunks.add(0);
        Box d = dbl(4);
        chests.remove(d.parts.get(1));
        assertFalse(GoneBoxes.gone(d, loaded, container));
    }

    @Test
    void theDeliveryContainerIsNeverDropped() {
        loadedChunks.add(0);
        Box delivery = single(1), other = single(3);
        delivery.delivery = true;
        breakBox(delivery);
        assertEquals(List.of(), GoneBoxes.toDrop(List.of(delivery, other), loaded, container));
    }

    @Test
    void everyBoxInViewLookingGoneIsNotBelieved() {
        // The bot in another dimension at the stash's coordinates: nothing there is a chest
        loadedChunks.add(0);
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < 5; i++) boxes.add(single(i * 2));
        chests.clear();
        assertEquals(List.of(), GoneBoxes.toDrop(boxes, loaded, container));
        // One or two broken in a stash that small are believed
        assertEquals(boxes.subList(0, 2), GoneBoxes.toDrop(boxes.subList(0, 2), loaded, container));
        assertTrue(GoneBoxes.suspicious(3, 0));
        assertFalse(GoneBoxes.suspicious(3, 1));
    }
}
