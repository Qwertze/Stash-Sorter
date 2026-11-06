package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.7.2: the next step of an order shulker. In 1.7.1 fewer than two free carry slots with only a source shulker left ended
 * the shulker as "not enough left to pack".
 */
class BinStepTest {
    private static final String A = "minecraft:arrow", D = "minecraft:diamond";

    @Test
    void lowCarryRoomPutsThingsAwayAndCarriesOnInsteadOfAborting() {
        // Only a shulker holds the next load, one free carry slot: 1.7.1 gave the shulker up here
        assertEquals(BinStep.Next.PUT_AWAY, BinStep.next(5, 0, 1, false, true, true));
        // Room again after the trip: the shulker is fetched
        assertEquals(BinStep.Next.UNPACK, BinStep.next(5, 0, 3, false, true, true));
        // A loose source, but every carry slot but one is taken by other things
        assertEquals(BinStep.Next.PUT_AWAY, BinStep.next(5, 0, 1, true, false, true));
        assertEquals(BinStep.Next.GATHER, BinStep.next(5, 0, 4, true, false, true));
    }

    @Test
    void noRoomIsAnAbortOfItsOwnOnlyWhenNothingCanBePutAway() {
        assertEquals(BinStep.Next.NO_ROOM, BinStep.next(5, 0, 1, false, true, false));
        assertEquals(BinStep.Next.NO_ROOM, BinStep.next(5, 0, 0, true, false, false));
    }

    @Test
    void aRealLackOfSourcesIsStillNotEnoughLeftToPack() {
        assertEquals(BinStep.Next.NO_SOURCE, BinStep.next(5, 0, 10, false, false, true));
        assertEquals(BinStep.Next.NO_SOURCE, BinStep.next(5, 0, 1, false, false, true), "no source at all: putting away would not help");
    }

    @Test
    void carriedStacksArePackedFirst() {
        assertEquals(BinStep.Next.PACK, BinStep.next(3, 3, 5, true, false, true));
        assertEquals(BinStep.Next.PACK, BinStep.next(5, 1, 1, true, false, true), "full hands: pack what is carried");
        assertEquals(BinStep.Next.PACK, BinStep.next(5, 1, 5, false, true, true), "no loose source: pack what is carried before unpacking");
        assertEquals(2, BinStep.batch(5, 1, 3));
    }

    @Test
    void aPartFilledShulkerIsTheBinsOwnAndItsLotsAreWhatItHolds() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v", 64), new Order.Lot(D, "v", 64), new Order.Lot(A, "a", 64));
        List<BinTracker.Held> inside = List.of(new BinTracker.Held(0, D, "v", 64), new BinTracker.Held(1, D, "v", 30));
        assertTrue(BinStep.partOf(lots, inside));
        List<Order.Lot> shortLots = BinStep.lotsOf(inside);
        assertEquals(List.of(new Order.Lot(D, "v", 64), new Order.Lot(D, "v", 30)), shortLots);
        // The shulker is the short unit's by exactly these
        assertTrue(BinTracker.finished(shortLots, inside));

        assertFalse(BinStep.partOf(lots, List.of()), "an empty shulker holds nothing to deliver");
        assertFalse(BinStep.partOf(lots, List.of(new BinTracker.Held(0, "minecraft:stone", null, 64))), "something that isn't the order's");
        assertFalse(BinStep.partOf(lots, List.of(new BinTracker.Held(0, A, "a", 64), new BinTracker.Held(1, A, "a", 64))), "more than the loads ask for");
    }

    // Review M3: only the bin's own shulker is delivered short

    private static final String RED = "red_shulker_box", BLUE = "blue_shulker_box";
    private static final List<Order.Lot> KIT = List.of(new Order.Lot(D, "v", 64), new Order.Lot(A, "a", 64), new Order.Lot(A, "a", 64));
    private static final List<BinTracker.Held> PACKED = List.of(new BinTracker.Held(0, D, "v", 64), new BinTracker.Held(1, A, "a", 64));

    @Test
    void theBinsOwnShulkerIsFoundByColourAndWhatWasLastPackedInIt() {
        // A partly emptied source shulker (after a restart nothing marks it as one) also fits the loads, but not the totals
        var source = new BinStep.Carried(RED, false, false, List.of(new BinTracker.Held(0, A, "a", 30)));
        var own = new BinStep.Carried(RED, false, false, PACKED);
        assertEquals(PACKED, BinStep.ownShulker(KIT, PACKED, true, RED, List.of(source, own)));
        assertEquals(null, BinStep.ownShulker(KIT, PACKED, true, RED, List.of(source)), "only the source shulker: not delivered short");
    }

    @Test
    void anythingUncertainFallsBackToTheReplan() {
        var own = new BinStep.Carried(RED, false, false, PACKED);
        assertEquals(null, BinStep.ownShulker(KIT, PACKED, false, RED, List.of(own)), "it held something when first put down");
        assertEquals(null, BinStep.ownShulker(KIT, PACKED, true, null, List.of(own)), "its colour is not known");
        assertEquals(null, BinStep.ownShulker(KIT, List.of(), true, RED, List.of(own)), "nothing was seen packed");
        assertEquals(null, BinStep.ownShulker(KIT, PACKED, true, BLUE, List.of(own)), "another colour");
        assertEquals(null, BinStep.ownShulker(KIT, PACKED, true, RED, List.of(new BinStep.Carried(RED, true, false, PACKED))), "named");
        assertEquals(null, BinStep.ownShulker(KIT, PACKED, true, RED, List.of(new BinStep.Carried(RED, false, true, PACKED))), "a known source shulker");
        // The same totals in other stacks are the same shulker's (a stack split when it was put in)
        var split = List.of(new BinTracker.Held(0, D, "v", 64), new BinTracker.Held(1, A, "a", 32), new BinTracker.Held(2, A, "a", 32));
        assertEquals(split, BinStep.ownShulker(KIT, PACKED, true, RED, List.of(new BinStep.Carried(RED, false, false, split))));
    }
}
