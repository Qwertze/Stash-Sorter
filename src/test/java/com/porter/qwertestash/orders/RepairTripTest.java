package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The repair trip on a fake inventory: gear stored and always put back, partial results, and every way it is cut short. */
class RepairTripTest {
    private static final String PICK = "minecraft:diamond_pickaxe#a";

    private static FakeRepairPort inventory() {
        FakeRepairPort p = new FakeRepairPort();
        p.slots[5] = FakeRepairPort.tool("diamond_helmet", 100, 363);
        p.slots[45] = new RepairTrip.Stack("shield", 1, 0, 336, false);
        p.slots[20] = FakeRepairPort.bottles(64);
        p.slots[10] = FakeRepairPort.tool("diamond_pickaxe", 140, 1561);
        return p;
    }

    private static RepairTrip trip(FakeRepairPort p, int budget, RepairTrip.Target... targets) {
        return new RepairTrip(p, List.of(targets), budget);
    }

    @Test
    void repairsTheItemAndPutsEverythingBack() {
        FakeRepairPort p = inventory();
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10)), 2000);

        assertTrue(t.done());
        assertEquals(RepairTrip.Cause.None, t.cause());
        assertEquals(1, t.outcomes().size());
        RepairTrip.Outcome o = t.outcomes().get(0);
        assertEquals(RepairTrip.Status.Repaired, o.status());
        assertEquals(0, o.damage());
        assertEquals(10, o.slot());
        // 140 durability at 14 a bottle
        assertEquals(10, t.bottlesUsed());
        // The helmet took no XP: it was not on the bot while the bottles were thrown
        assertEquals(0, p.mendedElsewhere);
        assertEquals(100, p.slots[5].damage());
        assertEquals("shield", p.slots[45].id());
        assertEquals(0, p.slots[10].damage());
        // The bottles that are left went back to where they came from, the hand is as it was
        assertEquals(54, p.slots[20].count());
        assertNull(p.slots[36]);
        assertEquals(0, p.held());
        assertTrue(p.lastLedger().isEmpty(), "nothing is left to undo");
        assertTrue(p.ledgers.size() > 1, "the moves were recorded while they lasted");
    }

    @Test
    void armourWithMendingWouldHaveTakenTheXpIfItHadStayedOn() {
        // The control: the same world without the storing, the first bottle mends the helmet
        FakeRepairPort p = inventory();
        p.slots[36] = FakeRepairPort.tool("diamond_pickaxe", 140, 1561);
        p.slots[45] = FakeRepairPort.bottles(64);
        p.throwBottle();
        for (int i = 0; i < 5; i++) p.world();
        assertEquals(86, p.slots[5].damage());
        assertEquals(140, p.slots[36].damage());
    }

    @Test
    void runningOutOfBottlesLeavesTheItemPartlyRepaired() {
        FakeRepairPort p = inventory();
        p.slots[20] = FakeRepairPort.bottles(4);
        p.slots[11] = FakeRepairPort.tool("diamond_axe", 70, 1561);
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10), new RepairTrip.Target("minecraft:diamond_axe#b", 11)), 2000);

        assertTrue(t.done());
        assertEquals(RepairTrip.Cause.NoBottles, t.cause());
        RepairTrip.Outcome first = t.outcomes().get(0);
        assertEquals(RepairTrip.Status.Partial, first.status());
        assertEquals(Repair.NO_BOTTLES, first.reason());
        assertEquals(140 - 4 * 14, first.damage());
        assertEquals(RepairTrip.Status.Untouched, t.outcomes().get(1).status());
        assertEquals(Repair.NO_BOTTLES, t.outcomes().get(1).reason());
        assertEquals(70, p.slots[11].damage());
        assertEquals(4, t.bottlesUsed());
        // Back where they were
        assertEquals(84, p.slots[10].damage());
        assertEquals(100, p.slots[5].damage());
        assertEquals("shield", p.slots[45].id());
    }

    @Test
    void severalItemsAreRepairedOneAfterTheOther() {
        FakeRepairPort p = inventory();
        p.slots[11] = FakeRepairPort.tool("diamond_axe", 28, 1561);
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10), new RepairTrip.Target("minecraft:diamond_axe#b", 11)), 3000);
        assertEquals(RepairTrip.Status.Repaired, t.outcomes().get(0).status());
        assertEquals(RepairTrip.Status.Repaired, t.outcomes().get(1).status());
        assertEquals(12, t.bottlesUsed());
        assertEquals(0, p.slots[11].damage());
        assertEquals("shield", p.slots[45].id());
    }

    @Test
    void aPlayerComingNearStopsTheThrowingAndEverythingGoesBack() {
        FakeRepairPort p = inventory();
        p.playerAfterThrows = 3;
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10)), 2000);

        assertTrue(t.done());
        assertEquals(RepairTrip.Cause.Player, t.cause());
        assertTrue(t.cause().retryable());
        assertTrue(p.throwsDone <= 4, "stopped within a throw or two: " + p.throwsDone);
        assertEquals("shield", p.slots[45].id());
        assertEquals(100, p.slots[5].damage());
        assertNotNull(p.slots[10]);
        assertTrue(p.lastLedger().isEmpty());
        RepairTrip.Outcome o = t.outcomes().get(0);
        assertEquals(Repair.INTERRUPTED, o.reason());
    }

    @Test
    void orbsThatVanishWithoutMendingEndTheTripAsInterrupted() {
        FakeRepairPort p = inventory();
        p.orbsEaten = true;
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10)), 2000);

        assertTrue(t.done());
        assertEquals(RepairTrip.Cause.Orbs, t.cause());
        assertTrue(t.cause().retryable());
        RepairTrip.Outcome o = t.outcomes().get(0);
        assertEquals(RepairTrip.Status.Untouched, o.status());
        assertEquals(Repair.INTERRUPTED, o.reason());
        assertEquals(140, p.slots[10].damage());
        assertEquals("shield", p.slots[45].id());
        // Only one batch was wasted
        assertTrue(p.throwsDone <= RepairTrip.BATCH);
    }

    @Test
    void theTimeCapStopsTheThrowing() {
        FakeRepairPort p = inventory();
        p.durabilityPerBottle = 1;
        RepairTrip t = p.run(trip(p, 30, new RepairTrip.Target(PICK, 10)), 2000);

        assertTrue(t.done());
        assertEquals(RepairTrip.Cause.Timeout, t.cause());
        assertTrue(!t.cause().retryable());
        assertEquals(RepairTrip.Status.Partial, t.outcomes().get(0).status());
        assertEquals("shield", p.slots[45].id());
        assertTrue(p.lastLedger().isEmpty());
    }

    @Test
    void aCancelMidwayPutsEverythingBack() {
        FakeRepairPort p = inventory();
        RepairTrip t = trip(p, 2400, new RepairTrip.Target(PICK, 10));
        for (int i = 0; i < 40; i++) {
            p.world();
            t.tick();
        }
        assertTrue(p.throwsDone > 0);
        p.abort = true;
        p.run(t, 500);

        assertTrue(t.done());
        assertEquals(RepairTrip.Cause.Aborted, t.cause());
        assertEquals("shield", p.slots[45].id());
        assertEquals(100, p.slots[5].damage());
        assertNotNull(p.slots[10]);
        assertNull(p.slots[36]);
        assertTrue(p.lastLedger().isEmpty());
    }

    @Test
    void anAbortBeforeAnythingMovedMovesNothing() {
        FakeRepairPort p = inventory();
        p.abort = true;
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10)), 100);
        assertTrue(t.done());
        assertEquals(0, p.moves);
        assertEquals(Repair.INTERRUPTED, t.outcomes().get(0).reason());
    }

    @Test
    void noRoomToStoreTheGearMeansNothingIsMoved() {
        FakeRepairPort p = inventory();
        for (int w = 9; w <= 44; w++) if (p.slots[w] == null) p.slots[w] = new RepairTrip.Stack("cobblestone", 64, 0, 0, false);
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10)), 100);
        assertTrue(t.done());
        assertEquals(RepairTrip.Cause.NoRoom, t.cause());
        assertEquals(0, p.moves);
        assertEquals(Repair.INTERRUPTED, t.outcomes().get(0).reason());
        assertEquals("shield", p.slots[45].id());
    }

    @Test
    void noBottlesAtAllLeavesTheItemsAlone() {
        FakeRepairPort p = inventory();
        p.slots[20] = null;
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10)), 100);
        assertTrue(t.done());
        assertEquals(RepairTrip.Cause.NoBottles, t.cause());
        assertEquals(Repair.NO_BOTTLES, t.outcomes().get(0).reason());
        assertEquals(0, p.moves);
    }

    @Test
    void theNeverTouchedHotbarSlotsAreLeftAlone() {
        FakeRepairPort p = inventory();
        p.keep.add(36);
        p.slots[36] = new RepairTrip.Stack("ender_pearl", 16, 0, 0, false);
        p.slots[37] = new RepairTrip.Stack("golden_apple", 8, 0, 0, false);
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10)), 2000);
        assertEquals(RepairTrip.Status.Repaired, t.outcomes().get(0).status());
        assertEquals("ender_pearl", p.slots[36].id());
        assertEquals("golden_apple", p.slots[37].id());
        assertEquals(0, p.held(), "the held slot is as it was");
    }

    @Test
    void aSavedLedgerIsUndoneByALaterStart() {
        FakeRepairPort p = new FakeRepairPort();
        // The helmet had been stored in 12 and the shield in 13 when the bot stopped
        p.slots[12] = FakeRepairPort.tool("diamond_helmet", 100, 363);
        p.slots[13] = new RepairTrip.Stack("shield", 1, 0, 336, false);
        List<int[]> saved = Repair.parseLedger(List.of("5>12", "45>13", "junk", "50>1"));
        assertEquals(2, saved.size());
        RepairTrip t = p.run(RepairTrip.undo(p, saved), 100);
        assertTrue(t.done());
        assertEquals("diamond_helmet", p.slots[5].id());
        assertEquals("shield", p.slots[45].id());
        assertNull(p.slots[12]);
        assertNull(p.slots[13]);
        assertTrue(p.lastLedger().isEmpty());
    }

    @Test
    void anUndoOfMovesThatNeverHappenedChangesNothing() {
        FakeRepairPort p = new FakeRepairPort();
        p.slots[5] = FakeRepairPort.tool("diamond_helmet", 100, 363);
        RepairTrip t = p.run(RepairTrip.undo(p, List.of(new int[] {5, 12})), 100);
        assertTrue(t.done());
        assertEquals("diamond_helmet", p.slots[5].id());
        assertEquals(0, p.moves);
    }

    @Test
    void aRefusedClickIsGivenUpOnAfterAWhileAndLeftInTheLedger() {
        FakeRepairPort p = inventory();
        RepairTrip t = trip(p, 2400, new RepairTrip.Target(PICK, 10));
        // The setup goes through, then the inventory queue refuses everything
        for (int i = 0; i < 12; i++) {
            p.world();
            t.tick();
        }
        p.refuseMoves = true;
        p.abort = true;
        p.run(t, RepairTrip.STUCK_TICKS * 3);
        assertTrue(t.done());
        assertTrue(!t.pending().isEmpty(), "what could not be put back stays in the ledger for the next start");
        assertEquals(t.pending().size(), p.lastLedger().size());
    }

    @Test
    void theItemsOwnSlotIsNeverTheMainHandSlot() {
        // The item sits in the first hotbar slot: another one is cleared for the hand instead
        FakeRepairPort p = inventory();
        p.slots[10] = null;
        p.slots[36] = FakeRepairPort.tool("diamond_pickaxe", 140, 1561);
        for (int w = 37; w <= 44; w++) p.slots[w] = new RepairTrip.Stack("cobblestone", 64, 0, 0, false);
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 36)), 3000);
        assertEquals(RepairTrip.Status.Repaired, t.outcomes().get(0).status());
        assertEquals(0, p.slots[36].damage());
        assertEquals("diamond_pickaxe", p.slots[36].id());
        for (int w = 37; w <= 44; w++) assertEquals("cobblestone", p.slots[w].id());
    }

    @Test
    void aPendingLedgerIsKeptAndPutBackWithTheNewTrip() {
        FakeRepairPort p = inventory();
        // An earlier trip left the shield in 12 (ledger 45>12)
        p.slots[45] = null;
        p.slots[12] = new RepairTrip.Stack("shield", 1, 0, 336, false);
        RepairTrip t = trip(p, 2400, new RepairTrip.Target(PICK, 10)).seed(List.of(new int[] {45, 12}));
        p.run(t, 3000);
        assertTrue(t.done());
        assertTrue(p.lastLedger().isEmpty());
        assertTrue(p.slots[45] != null && p.slots[45].id().equals("shield"), "the shield is back in the off hand");
    }

    @Test
    void aStaleEntryForTheWrongArmourIsDroppedNotReplayed() {
        FakeRepairPort p = new FakeRepairPort();
        p.slots[12] = FakeRepairPort.tool("diamond_pickaxe", 10, 100);
        RepairTrip t = p.run(RepairTrip.undo(p, List.of(new int[] {5, 12})), 100);
        assertTrue(t.done());
        assertEquals("diamond_pickaxe", p.slots[12].id());
        assertNull(p.slots[5]);
        assertEquals(0, p.moves);
        assertEquals(1, p.dropped);
    }

    @Test
    void anItemThatCannotGoBackStaysInHandAndSaysSo() {
        FakeRepairPort p = inventory();
        RepairTrip t = trip(p, 2400, new RepairTrip.Target(PICK, 10));
        for (int i = 0; i < 12; i++) {
            p.world();
            t.tick();
        }
        p.abort = true;
        p.refuseMoves = true;
        p.run(t, RepairTrip.STUCK_TICKS * 4);
        assertTrue(t.done());
        assertTrue(t.outcomes().get(0).slot() >= RepairTrip.HOTBAR_FIRST, "it is in the hand, not in its old slot: " + t.outcomes().get(0).slot());
    }

    @Test
    void offHandBottlesAreCounted() {
        FakeRepairPort p = inventory();
        p.slots[45] = FakeRepairPort.bottles(5);
        assertEquals(69, p.bottles());
    }

    @Test
    void aSelectThatNeverWorksIsGivenUp() {
        FakeRepairPort p = inventory();
        p.refuseSelect = true;
        p.held = 3;
        RepairTrip t = p.run(trip(p, 2400, new RepairTrip.Target(PICK, 10)), RepairTrip.STUCK_TICKS * 6);
        assertTrue(t.done());
        assertTrue(p.slots[45] != null && p.slots[45].id().equals("shield"), "the shield is back");
    }
}
