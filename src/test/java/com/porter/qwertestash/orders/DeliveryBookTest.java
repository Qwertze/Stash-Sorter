package com.porter.qwertestash.orders;

import com.porter.qwertestash.orders.DeliveryBook.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The delivery storage's bookkeeping: lowest usable first, full and usable again, lids seen, and what to do next. */
class DeliveryBookTest {
    private static final Pos A = new Pos(10, 64, 20), B = new Pos(11, 64, 20);

    private static DeliveryBook stack(int extra) {
        DeliveryBook b = new DeliveryBook();
        b.syncPrimary(List.of(A, B));
        for (int i = 1; i <= extra; i++) b.add(List.of(new Pos(10, 64 + i, 20), new Pos(11, 64 + i, 20)));
        return b;
    }

    @Test
    void chestsAreFilledLowestFirstAndFullOnesPassedOver() {
        DeliveryBook b = stack(2);
        assertEquals(3, b.chests());
        assertSame(b.primary(), b.firstUsable());
        b.markFull(b.primary(), 1);
        assertEquals(65, b.firstUsable().y());
        assertEquals(1, b.fullCount());
        b.markFull(b.firstUsable(), 2);
        assertEquals(66, b.firstUsable().y());
        b.markRoom(b.primary(), 3);
        assertSame(b.primary(), b.firstUsable());
    }

    @Test
    void theTopRowIsTheHighestChests() {
        DeliveryBook b = stack(2);
        assertEquals(1, b.topRow().size());
        assertEquals(66, b.topRow().get(0).y());
        assertEquals(List.of(new Pos(10, 66, 20), new Pos(11, 66, 20)), b.topRow().get(0).parts());
    }

    @Test
    void aPlayerOpeningAndClosingAFullChestFlagsItForALook() {
        DeliveryBook b = stack(1);
        b.markFull(b.primary(), 1);
        // The bot's own lid does not count
        assertTrue(b.onViewers(A, 1, true));
        assertTrue(b.onViewers(A, 0, false));
        assertNull(b.flagged());
        // A player's does: seen open, then shut
        b.onViewers(B, 1, false);
        assertNull(b.flagged());
        b.onViewers(B, 0, false);
        assertSame(b.primary(), b.flagged());
        assertFalse(b.onViewers(new Pos(0, 0, 0), 1, false));
    }

    @Test
    void aPlayerWithTheBotInsideToo() {
        DeliveryBook b = stack(0);
        b.markFull(b.primary(), 1);
        // Two viewers while the bot has it open: one is a player
        b.onViewers(A, 2, true);
        b.onViewers(A, 1, true);
        b.onViewers(A, 0, false);
        assertSame(b.primary(), b.flagged());
    }

    @Test
    void aChestThatIsNotFullIsNotFlagged() {
        DeliveryBook b = stack(0);
        b.onViewers(A, 1, false);
        b.onViewers(A, 0, false);
        assertNull(b.flagged());
    }

    @Test
    void roomDecision() {
        DeliveryBook b = stack(1);
        long now = 10_000_000;
        assertEquals(DeliveryRoom.Kind.ROOM, b.room(false, null, now, DeliveryBook.RECHECK_MS).kind());

        b.markFull(b.primary(), now);
        assertEquals(DeliveryRoom.Kind.ROOM, b.room(false, null, now, DeliveryBook.RECHECK_MS).kind());
        b.markFull(b.sorted().get(1), now);

        // All full: expand when on and nothing blocks; else wait with the reason
        assertEquals(DeliveryRoom.Kind.EXPAND, b.room(true, null, now, DeliveryBook.RECHECK_MS).kind());
        DeliveryRoom off = b.room(false, null, now, DeliveryBook.RECHECK_MS);
        assertEquals(DeliveryRoom.Kind.WAIT, off.kind());
        assertTrue(off.why().contains("auto expand is off"), off.why());
        DeliveryRoom blocked = b.room(true, "a stone is in the way", now, DeliveryBook.RECHECK_MS);
        assertEquals(DeliveryRoom.Kind.WAIT, blocked.kind());
        assertEquals("delivery storage full: can't place higher: a stone is in the way", blocked.why());
    }

    @Test
    void aFlaggedLowChestIsLookedAtBeforeTheOneInUse() {
        DeliveryBook b = stack(1);
        long now = 5_000;
        b.markFull(b.primary(), now);
        b.onViewers(A, 1, false);
        b.onViewers(A, 0, false);
        DeliveryRoom r = b.room(true, null, now, DeliveryBook.RECHECK_MS);
        assertEquals(DeliveryRoom.Kind.RECHECK, r.kind());
        assertEquals(A, r.recheck());
        b.markRoom(b.primary(), now);
        assertEquals(DeliveryRoom.Kind.ROOM, b.room(true, null, now, DeliveryBook.RECHECK_MS).kind());
        assertSame(b.primary(), b.firstUsable());
    }

    @Test
    void waitingLooksAtFullChestsNowAndThen() {
        DeliveryBook b = stack(0);
        long t0 = 1_000_000;
        b.markFull(b.primary(), t0);
        assertEquals(DeliveryRoom.Kind.WAIT, b.room(false, null, t0 + 1000, DeliveryBook.RECHECK_MS).kind());
        DeliveryRoom due = b.room(false, null, t0 + DeliveryBook.RECHECK_MS, DeliveryBook.RECHECK_MS);
        assertEquals(DeliveryRoom.Kind.RECHECK, due.kind());
        // Looked at and still full: quiet again for a while
        b.markFull(b.primary(), t0 + DeliveryBook.RECHECK_MS);
        assertEquals(DeliveryRoom.Kind.WAIT, b.room(false, null, t0 + DeliveryBook.RECHECK_MS + 1, DeliveryBook.RECHECK_MS).kind());
    }

    @Test
    void aDoubleChestPlacedHalfByHalfIsOneEntry() {
        DeliveryBook b = stack(0);
        Pos a1 = new Pos(10, 65, 20), b1 = new Pos(11, 65, 20);
        // The first half stands alone, then the second joins it: the game now says the double chest is both blocks
        b.addOrMerge(List.of(a1));
        assertEquals(2, b.chests());
        b.addOrMerge(List.of(a1, b1));
        assertEquals(2, b.chests());
        assertEquals(List.of(a1, b1), b.topRow().get(0).parts());
        // Marking it full marks the one chest, so the next is used
        b.markFull(b.primary(), 1);
        b.markFull(b.topRow().get(0), 1);
        assertEquals(2, b.fullCount());
        assertEquals(null, b.firstUsable());
    }

    @Test
    void overlappingEntriesAreMergedKeepingFull() {
        DeliveryBook b = stack(0);
        Pos a1 = new Pos(10, 65, 20), b1 = new Pos(11, 65, 20);
        b.add(List.of(a1));
        DeliveryBook.Entry twin = b.add(List.of(a1, b1));
        b.markFull(twin, 9);
        assertTrue(b.dedupe());
        assertEquals(2, b.chests());
        assertTrue(b.topRow().get(0).full);
        assertEquals(2, b.topRow().get(0).parts().size());
        assertFalse(b.dedupe());
    }

    @Test
    void onlyOneLookAtAFullChestPerIntervalWhateverTheChestCount() {
        DeliveryBook b = stack(4);
        long t0 = 1_000_000;
        for (DeliveryBook.Entry e : b.sorted()) b.markFull(e, t0 - DeliveryBook.RECHECK_MS * 3);
        long now = t0;
        DeliveryRoom first = b.room(false, null, now, DeliveryBook.RECHECK_MS);
        assertEquals(DeliveryRoom.Kind.RECHECK, first.kind());
        assertEquals(A, first.recheck(), "the lowest first");
        b.markFull(b.primary(), now);
        // The others are not due until the interval has passed since that look
        assertEquals(DeliveryRoom.Kind.WAIT, b.room(false, null, now + 1000, DeliveryBook.RECHECK_MS).kind());
        assertEquals(DeliveryRoom.Kind.RECHECK, b.room(false, null, now + DeliveryBook.RECHECK_MS, DeliveryBook.RECHECK_MS).kind());
    }

    @Test
    void aNewDeliveryContainerForgetsTheOldStorageButTheSameOneKeepsIt() {
        DeliveryBook b = stack(2);
        b.markFull(b.primary(), 1);
        // Read again as the same container: nothing changes
        b.syncPrimary(List.of(A, B));
        assertEquals(3, b.chests());
        assertTrue(b.primary().full);
        // A single chest that grew into a double (or the other way): same container, entries kept
        b.syncPrimary(List.of(A));
        assertEquals(3, b.chests());
        assertEquals(1, b.primary().parts().size());
        // Another container
        b.syncPrimary(List.of(new Pos(500, 70, 500)));
        assertEquals(1, b.chests());
        assertFalse(b.primary().full);
        b.syncPrimary(null);
        assertEquals(0, b.chests());
    }
}
