package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The repair state across trips: what is reported for each item, and the one retry. */
class RepairRunTest {
    private static final String PICK = "minecraft:diamond_pickaxe#a", AXE = "minecraft:diamond_axe#b";

    private static FakeRepairPort inventory() {
        FakeRepairPort p = new FakeRepairPort();
        p.slots[20] = FakeRepairPort.bottles(64);
        p.slots[10] = FakeRepairPort.tool("diamond_pickaxe", 140, 1561);
        p.slots[11] = FakeRepairPort.tool("diamond_axe", 70, 1561);
        return p;
    }

    private static RepairRun run(Repair.Report report, int before) {
        report.addTicks(before);
        return new RepairRun(report, List.of(new RepairRun.Item(PICK, 10, 140, 1561), new RepairRun.Item(AXE, 11, 70, 1561)));
    }

    @Test
    void everythingRepairedReportsNothingToNote() {
        FakeRepairPort p = inventory();
        Repair.Report r = new Repair.Report();
        RepairRun run = run(r, 0);
        RepairTrip t = p.run(new RepairTrip(p, run.targets(), run.budget()), 4000);
        assertFalse(run.tripDone(t));
        assertEquals(2, r.repaired());
        assertEquals(15, r.bottlesUsed());
        assertEquals(0, r.notRepaired());
        assertNull(r.note());
        assertTrue(run.pending().isEmpty());
    }

    @Test
    void aPlayerInTheWayIsRetriedOnceAndThenFinished() {
        FakeRepairPort p = inventory();
        Repair.Report r = new Repair.Report();
        RepairRun run = run(r, 0);
        p.playerAfterThrows = 2;
        RepairTrip first = p.run(new RepairTrip(p, run.targets(), run.budget()), 2000);
        assertEquals(RepairTrip.Cause.Player, first.cause());
        assertTrue(run.tripDone(first), "tried once more");
        assertEquals(2, run.pending().size());
        assertTrue(run.budget() < Repair.MAX_THROW_TICKS);

        // The player is gone: the second go repairs both
        p.playerNear = false;
        p.playerAfterThrows = -1;
        RepairTrip second = p.run(new RepairTrip(p, run.targets(), run.budget()), 4000);
        assertFalse(run.tripDone(second));
        assertEquals(2, r.repaired());
        assertNull(r.note());
        // The bottles thrown just before the player came are lost to the items that were already put back
        assertTrue(r.bottlesUsed() >= 15 && r.bottlesUsed() <= 15 + RepairTrip.BATCH, "bottles " + r.bottlesUsed());
    }

    @Test
    void interruptedTwiceIsDeliveredAsItIsWithTheReasonInterrupted() {
        FakeRepairPort p = inventory();
        Repair.Report r = new Repair.Report();
        RepairRun run = run(r, 0);
        p.orbsEaten = true;
        RepairTrip first = p.run(new RepairTrip(p, run.targets(), run.budget()), 2000);
        assertTrue(run.tripDone(first));
        RepairTrip second = p.run(new RepairTrip(p, run.targets(), run.budget()), 2000);
        assertFalse(run.tripDone(second), "only one retry");

        assertEquals(0, r.repaired());
        assertEquals(2, r.skipped().size());
        assertEquals(Repair.INTERRUPTED, r.skipped().get(0).reason());
        assertEquals(PICK, r.skipped().get(0).key());
        assertTrue(r.partial().isEmpty());
        assertEquals("2 item(s) not fully repaired: interrupted", r.note());
        assertTrue(r.bottlesUsed() > 0);
    }

    @Test
    void progressBeforeAnInterruptionIsReportedAsPartial() {
        FakeRepairPort p = inventory();
        Repair.Report r = new Repair.Report();
        RepairRun run = new RepairRun(r, List.of(new RepairRun.Item(PICK, 10, 140, 1561)));
        p.playerAfterThrows = 4;
        RepairTrip first = p.run(new RepairTrip(p, run.targets(), run.budget()), 2000);
        assertTrue(run.tripDone(first));
        // The player stays: the second go stops at once
        RepairTrip second = new RepairTrip(p, run.targets(), run.budget());
        p.run(second, 2000);
        assertFalse(run.tripDone(second));

        assertEquals(1, r.partial().size());
        Repair.Report.Partial part = r.partial().get(0);
        assertEquals(PICK, part.key());
        assertTrue(part.damage() < 140 && part.damage() > 0, "damage left: " + part.damage());
        assertEquals(1561, part.maxDamage());
        assertTrue(r.skipped().isEmpty());
        assertEquals("1 item(s) not fully repaired: interrupted", r.note());
    }

    @Test
    void noBottlesIsPartialForTheOneInHandAndSkippedForTheRest() {
        FakeRepairPort p = inventory();
        p.slots[20] = FakeRepairPort.bottles(3);
        Repair.Report r = new Repair.Report();
        RepairRun run = run(r, 0);
        RepairTrip t = p.run(new RepairTrip(p, run.targets(), run.budget()), 3000);
        assertFalse(run.tripDone(t), "out of bottles is final");
        assertEquals(1, r.partial().size());
        assertEquals(98, r.partial().get(0).damage());
        assertEquals(1, r.skipped().size());
        assertEquals(AXE, r.skipped().get(0).key());
        assertEquals(Repair.NO_BOTTLES, r.skipped().get(0).reason());
        assertEquals("2 item(s) not fully repaired: no bottles", r.note());
        assertEquals(3, r.bottlesUsed());
    }

    @Test
    void theTimeBudgetIsSharedByTheOrder() {
        Repair.Report r = new Repair.Report();
        assertEquals(Repair.MAX_THROW_TICKS - 500, run(r, 500).budget());
        assertEquals(0, run(r, Repair.MAX_THROW_TICKS + 99).budget());
    }

    @Test
    void givingUpReportsEverythingLeftWithTheReason() {
        Repair.Report r = new Repair.Report();
        RepairRun run = run(r, 0);
        run.giveUp(Repair.NO_BOTTLES);
        assertEquals(2, r.skipped().size());
        assertEquals("2 item(s) not fully repaired: no bottles", r.note());
        assertTrue(run.pending().isEmpty());
    }

    @Test
    void theBottleEstimateTakesTheSparesAlong() {
        Repair.Report r = new Repair.Report();
        RepairRun run = run(r, 0);
        // ceil(140 / 14) + ceil(70 / 14) = 15, and a few over
        assertEquals(15 + Repair.spare(2), run.bottlesNeeded());
        assertTrue(run.bottlesNeeded() > 15);
    }
}
