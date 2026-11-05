package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinTrackerTest {
    private static final String D = "minecraft:diamond", S = "minecraft:stone";

    private static BinTracker.Held held(int slot, String key, String variant, int count) {
        return new BinTracker.Held(slot, key, variant, count);
    }

    @Test
    void nextNeedIsTheFirstLoadNotInHand() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v1", 64), new Order.Lot(D, "v1", 64), new Order.Lot(S, "s1", 10));
        BinTracker.Need need = BinTracker.nextNeed(lots, List.of(), List.of());
        assertNotNull(need);
        assertEquals(0, need.index());
        assertEquals(-1, need.topUpSlot());

        // One diamond load is already carried whole: the next is the second
        need = BinTracker.nextNeed(lots, List.of(), List.of(held(3, D, "v1", 64)));
        assertEquals(1, need.index());

        // Both are carried, one load is inside the shulker: only the stone is missing
        need = BinTracker.nextNeed(lots, List.of(held(0, D, "v1", 64)), List.of(held(4, D, "v1", 64)));
        assertEquals(2, need.index());
        assertEquals(S, need.lot().key());

        // Everything is in hand
        assertNull(BinTracker.nextNeed(lots, List.of(held(0, D, "v1", 64)), List.of(held(4, D, "v1", 64), held(5, S, "s1", 10))));
    }

    @Test
    void aPartialStackIsToppedUpNotRestarted() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v1", 64));
        BinTracker.Need need = BinTracker.nextNeed(lots, List.of(), List.of(held(7, D, "v1", 30)));
        assertEquals(7, need.topUpSlot());
        assertEquals(30, need.have());
    }

    @Test
    void aStackOfAnotherVariantIsNotThePartOfTheLoad() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v1", 64));
        BinTracker.Need need = BinTracker.nextNeed(lots, List.of(), List.of(held(7, D, "v2", 30)));
        assertEquals(-1, need.topUpSlot());
        assertFalse(BinTracker.sameKind(lots.get(0), D, "v2"));
        assertTrue(BinTracker.sameKind(new Order.Lot(D, null, 5), D, "anything"));
    }

    @Test
    void readyAreCarriedStacksOfExactlyALoad() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v1", 64), new Order.Lot(S, "s1", 10));
        List<BinTracker.Held> carried = List.of(held(1, D, "v1", 64), held(2, S, "s1", 9), held(3, "minecraft:dirt", null, 64));
        List<BinTracker.Held> ready = BinTracker.ready(lots, List.of(), carried);
        assertEquals(1, ready.size());
        assertEquals(1, ready.get(0).slot());
        // A load already inside the shulker is not ready again
        assertEquals(0, BinTracker.ready(lots, List.of(held(0, D, "v1", 64)), List.of(held(1, D, "v1", 64))).size());
    }

    @Test
    void ownsMeansTheBinCouldHoldIt() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v1", 64), new Order.Lot(S, null, 10));
        assertTrue(BinTracker.owns(lots, D, "v1", 64));
        assertTrue(BinTracker.owns(lots, D, "v1", 12));
        assertFalse(BinTracker.owns(lots, D, "v2", 12));
        assertTrue(BinTracker.owns(lots, S, "whatever", 10));
        assertFalse(BinTracker.owns(lots, S, "whatever", 11));
        assertFalse(BinTracker.owns(lots, "minecraft:dirt", null, 1));
    }

    @Test
    void aFinishedShulkerHoldsExactlyTheBinsTotalsByKey() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v1", 64), new Order.Lot(D, "v1", 22), new Order.Lot(S, "s1", 10));
        assertTrue(BinTracker.finished(lots, List.of(held(0, D, "v9", 64), held(1, D, "v9", 22), held(2, S, "s1", 10))));
        assertFalse(BinTracker.finished(lots, List.of(held(0, D, "v1", 64), held(2, S, "s1", 10))));
        assertFalse(BinTracker.finished(lots, List.of(held(0, D, "v1", 64), held(1, D, "v1", 22), held(2, S, "s1", 10), held(3, S, "s1", 1))));
    }

    @Test
    void remainingCountsWhatIsNeitherPackedNorCarried() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v1", 64), new Order.Lot(D, "v1", 64), new Order.Lot(S, "s1", 10));
        assertEquals(1, BinTracker.remaining(lots, List.of(held(0, D, "v1", 64)), List.of(held(4, D, "v1", 64))).size());
        assertEquals(3, BinTracker.remaining(lots, List.of(), List.of()).size());
        assertEquals(2, BinTracker.notPacked(lots, List.of(held(0, S, "s1", 10))).size());
    }
}
