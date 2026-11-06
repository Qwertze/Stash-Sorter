package com.porter.qwertestash.module;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2: an order shulker broken after a shulker of another colour was unpacked was taken as picked up, then looked for
 * on the ground as the other colour, and the bot never moved; AutoDrop then gave it to a player.
 */
class ShulkerPickupTest {
    private static final String PLAIN = "shulker_box", PINK = "pink_shulker_box";

    // ---- what is looked for

    @Test
    void recoveryLooksForThePlanShulkerNotTheLastOnePutDown() {
        // The order shulker (plain) went down, then a pink one was put down, unpacked and picked up: the last placed is pink
        String lastPlaced = PINK;
        String id = ShulkerPickup.lookFor(PLAIN, null);
        assertEquals(PLAIN, id, "the plan's own shulker");
        assertFalse(lastPlaced.equals(id));
        // The plan's id wins over whatever stands at the spot
        assertEquals(PLAIN, ShulkerPickup.lookFor(PLAIN, PINK));
    }

    @Test
    void anUnknownPlanColourFallsBackToTheBlockThereThenToAnyShulker() {
        assertEquals(PLAIN, ShulkerPickup.lookFor(null, PLAIN));
        assertEquals(PLAIN, ShulkerPickup.lookFor("", PLAIN));
        assertNull(ShulkerPickup.lookFor(null, "stone"), "not a shulker block: any shulker");
        assertNull(ShulkerPickup.lookFor(null, null), "no filter at all: any shulker");
    }

    // ---- the shulker count

    @Test
    void noFalsePickupAfterReturningToAPlacedShulker() {
        ShulkerPickup.Watch w = new ShulkerPickup.Watch();
        // Held: the order shulker in hand, a pink source shulker, another one in slots 0, 1, 2
        w.placing(3);
        assertTrue(w.putDown(2), "the order shulker is down");

        // The pink one is put down, unpacked, broken and picked up again (the order shulker still stands)
        w.placing(2);
        assertTrue(w.putDown(1));
        w.slots(List.of(2));
        w.sawStanding(1);
        assertTrue(w.grew(2), "the pink one is back");

        // What 1.6.1 did: back to the order shulker with the count still measured against the pink one
        assertTrue(w.grew(2), "stale: the order shulker looks picked up before it is even broken");

        // 1.6.2: coming back to the standing order shulker measures from what is held now
        w.standing(2, List.of(1, 2));
        assertFalse(w.grew(2), "nothing picked up yet");
        w.sawStanding(2);
        assertFalse(w.grew(2), "broken, still on the ground");
        assertTrue(w.grew(3), "picked up");
        assertTrue(w.heldBefore(1));
        assertTrue(w.heldBefore(2));
        assertFalse(w.heldBefore(0), "slot 0 is the new one");
    }

    @Test
    void aCountThatGrewWithNoSlotIsOnlyAPickupOnceConfirmed() {
        assertEquals(ShulkerPickup.Unknown.NotPickedUp, ShulkerPickup.unknownSlot(0, true), "one like ours still lies there");
        assertEquals(ShulkerPickup.Unknown.Wait, ShulkerPickup.unknownSlot(0, false));
        assertEquals(ShulkerPickup.Unknown.Wait, ShulkerPickup.unknownSlot(ShulkerPickup.CONFIRM_TICKS - 1, false));
        assertEquals(ShulkerPickup.Unknown.PickedUp, ShulkerPickup.unknownSlot(ShulkerPickup.CONFIRM_TICKS, false));
    }

    @Test
    void aShulkerLikeOursInSightDefersAPickupForAMinuteAtMost() {
        // Someone else's shulker of the same colour lying near the spot must not keep ours "on the ground" until it is given up
        assertEquals(ShulkerPickup.Unknown.NotPickedUp, ShulkerPickup.unknownSlot(ShulkerPickup.MAX_DEFER_TICKS - 1, true));
        assertEquals(ShulkerPickup.Unknown.PickedUp, ShulkerPickup.unknownSlot(ShulkerPickup.MAX_DEFER_TICKS, true));
        assertEquals(ShulkerPickup.Unknown.PickedUp, ShulkerPickup.unknownSlot(ShulkerPickup.MAX_DEFER_TICKS, false));
        assertTrue(ShulkerPickup.MAX_DEFER_TICKS < 20 * 120, "before collecting gives up (2 minutes)");
    }

    // ---- AutoDrop

    private static final long T = 1_000_000;

    @Test
    void autoDropKeepsTheShulkerBeingCollected() {
        Set<String> collecting = Set.of(PLAIN);
        assertTrue(ShulkerPickup.keepFromReturn(PLAIN, true, collecting, null, false, 0, 0, 0, 0), "the order shulker being collected");
        assertFalse(ShulkerPickup.keepFromReturn(PINK, true, collecting, null, false, 0, 0, 0, 0), "another colour is the player's");
        assertFalse(ShulkerPickup.keepFromReturn("diamond", false, collecting, null, false, 0, 0, 0, 0));
        assertFalse(ShulkerPickup.keepFromReturn(null, true, collecting, null, false, 0, 0, 0, 0));
        // Collecting one of unknown colour: any shulker
        assertTrue(ShulkerPickup.keepFromReturn(PINK, true, Set.of(ShulkerPickup.ANY), null, false, 0, 0, 0, 0));
        assertFalse(ShulkerPickup.keepFromReturn("diamond", false, Set.of(ShulkerPickup.ANY), null, false, 0, 0, 0, 0));
    }

    @Test
    void autoDropKeepsTheDropOfAShulkerJustBrokenNextToItsSpotOnly() {
        ShulkerPickup.Break broke = new ShulkerPickup.Break(PLAIN, 10, 64, 10, T);
        Set<String> none = Set.of();
        assertTrue(ShulkerPickup.keepFromReturn(PLAIN, true, none, broke, true, 10, 64, 10, T + 200), "its own drop");
        assertTrue(ShulkerPickup.keepFromReturn(PLAIN, true, none, broke, true, 12, 64, 11, T - 500), "seen a moment before the spot");
        assertFalse(ShulkerPickup.keepFromReturn(PLAIN, true, none, broke, true, 20, 64, 10, T + 200), "too far from the spot");
        assertFalse(ShulkerPickup.keepFromReturn(PLAIN, true, none, broke, true, 10, 64, 10, T + ShulkerPickup.BREAK_WINDOW_MS + 1), "too late");
        assertFalse(ShulkerPickup.keepFromReturn(PLAIN, true, none, broke, true, 10, 64, 10, T - ShulkerPickup.BREAK_EARLY_MS - 1), "before the break");
        assertFalse(ShulkerPickup.keepFromReturn(PINK, true, none, broke, true, 10, 64, 10, T + 200), "another colour");
        assertFalse(ShulkerPickup.keepFromReturn(PLAIN, true, none, broke, false, 10, 64, 10, T + 200), "where it appeared is not known");
        // A break of unknown colour keeps nothing on its own: unknown colours count only while collecting
        assertFalse(ShulkerPickup.keepFromReturn(PLAIN, true, none, new ShulkerPickup.Break(null, 10, 64, 10, T), true, 10, 64, 10, T + 200));
    }

    @Test
    void autoDropGivesBackWhenNothingOfOursIsBeingCollectedOrJustBroken() {
        // A shulker of ours standing between batches, one to be resumed, one given up on: none of them keeps a player's
        assertFalse(ShulkerPickup.keepFromReturn(PLAIN, true, Set.of(), null, true, 10, 64, 10, T));
        assertFalse(ShulkerPickup.keepFromReturn(PLAIN, true, Set.of(), new ShulkerPickup.Break(PLAIN, 10, 64, 10, T - 60_000), true, 10, 64, 10, T));
    }
}
