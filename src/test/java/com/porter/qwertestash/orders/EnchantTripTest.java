package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.EnchantRules;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EnchantTrip run through a fake anvil that follows the real rules: the step flow, the cost and result checks, an anvil that breaks, pauses, cancels and the journal. */
class EnchantTripTest {
    private static final int LIMIT = 200_000;

    private static EnchantSim world(EnchantKit.Case c, int bottles, double points) {
        EnchantSim sim = new EnchantSim(c.ref().key()).give(c.held()).xp(points);
        sim.bottles = bottles;
        return sim;
    }

    private static EnchantTrip trip(EnchantSim sim, EnchantKit.Case c, int stepsDone) {
        return new EnchantTrip(sim, EnchantKit.MC, c.ref().key(), c.ref().plan(), stepsDone);
    }

    @Test
    void aWholeItemIsMadeStepByStep() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, c.ref().plan().bottles() + 40, 0);
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertTrue(t.done());
        EnchantTrip.Outcome o = t.outcome();
        assertNotNull(o);
        assertEquals(EnchantTrip.Kind.FINISHED, o.kind(), sim.log.toString());
        assertEquals(c.ref().plan().steps().size(), o.stepsDone());
        // every step was journalled in order
        for (int i = 0; i < c.ref().plan().steps().size(); i++) assertEquals(i, sim.stepsDone.get(i));
        // the finished item is carried, with the ordered level, and nothing else of the plan is left
        List<EnchantItems.Held> left = sim.stacks();
        assertEquals(1, left.size(), left.toString());
        assertFalse(left.get(0).book());
        assertEquals(Map.of("sharpness", 5), left.get(0).ench());
        assertTrue(o.spec().matches(left.get(0)));
        assertEquals(1, sim.gearMoves);
        assertEquals(1, sim.restores);
        assertFalse(sim.gearAway);
        assertFalse(sim.open);
        // the bottles used are the ones thrown, and the costs shown were the planned ones
        assertTrue(t.bottlesUsed() > 0);
        for (int i = 0; i < sim.shownCosts.size(); i++) assertEquals(c.ref().plan().steps().get(i).cost(), sim.shownCosts.get(i));
    }

    @Test
    void aLongerRunWithTwoEnchantments() {
        EnchantKit.Case c = EnchantKit.sharpnessAndUnbreaking();
        EnchantSim sim = world(c, c.ref().plan().bottles() + 60, 0);
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind(), sim.log.toString());
        EnchantItems.Held item = sim.stacks().get(0);
        assertEquals(Map.of("sharpness", 5, "unbreaking", 3), item.ench());
        // the levels were paid per step: the bot never holds more than a few levels beyond the last step's need
        assertTrue(sim.levelNow() < 40);
    }

    @Test
    void itLevelsUpOnlyToWhatTheStepNeeds() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 400, 0);
        trip(sim, c, 0);
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        // the bottles used are close to the plan's estimate, nowhere near the 400 in the inventory
        assertTrue(t.bottlesUsed() <= c.ref().plan().bottles(), "used " + t.bottlesUsed() + ", estimate " + c.ref().plan().bottles());
    }

    @Test
    void ranOutOfBottlesNeedsMoreAndGoesOnFromTheJournal() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 4, 0);
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        EnchantTrip.Outcome o = t.outcome();
        assertEquals(EnchantTrip.Kind.NEED_BOTTLES, o.kind());
        assertTrue(o.levels() > 0);
        assertEquals(o.stepsDone(), sim.stepsDone.size());
        // the gear is back on while the host fetches bottles
        assertFalse(sim.gearAway);
        // more bottles are brought and the trip is made again from where the journal is
        sim.bottles += 300;
        EnchantTrip again = trip(sim, c, o.stepsDone());
        sim.run(again, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, again.outcome().kind(), sim.log.toString());
        assertEquals(Map.of("sharpness", 5), sim.stacks().get(0).ench());
    }

    @Test
    void aHigherCostBeforeStepOneIsACostMismatch() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 400, 0);
        sim.costBump = 1;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.SHORT, t.outcome().kind());
        assertEquals(Codes.COST_MISMATCH, t.outcome().reason());
        // nothing was paid and every item is back in the inventory
        assertEquals(c.held().size(), sim.stacks().size());
        assertNull(sim.left);
        assertFalse(sim.open);
        assertEquals(0, sim.stepsDone.size());
    }

    @Test
    void aCostOfFortyOrMoreIsTooExpensive() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 400, 0);
        sim.costBump = 40;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.SHORT, t.outcome().kind());
        assertEquals(Codes.TOO_EXPENSIVE, t.outcome().reason());
        assertEquals(c.held().size(), sim.stacks().size());
    }

    @Test
    void aLowerCostThanPlannedIsAccepted() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 400, 0);
        sim.costBump = -1;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind(), sim.log.toString());
    }

    @Test
    void aHigherCostPastStepOneIsPaidByLevellingUpToIt() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        EnchantTrip first = trip(sim, c, 0);
        // step 1 is done at the planned cost; then the world gets dearer
        while (!first.done() && sim.stepsDone.isEmpty()) first.tick();
        sim.costBump = 1;
        sim.run(first, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, first.outcome().kind(), sim.log.toString());
        assertTrue(sim.shownCosts.get(1) > c.ref().plan().steps().get(1).cost());
    }

    @Test
    void aWrongResultEndsTheItemAndTheInputsComeBack() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 400, 0);
        sim.wrongResult = Map.of("sharpness", 3);
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.SHORT, t.outcome().kind());
        assertEquals(Codes.WRONG_RESULT, t.outcome().reason());
        assertEquals(c.held().size(), sim.stacks().size());
        assertNull(sim.left);
        assertNull(sim.right);
    }

    @Test
    void moreThanOneItemOnTheLeftIsRefused() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 400, 0);
        sim.leftStack = 2;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.SHORT, t.outcome().kind());
        assertEquals(0, sim.stepsDone.size());
        assertEquals(c.held().size(), sim.stacks().size());
    }

    @Test
    void anAnvilThatBreaksIsReplacedAndTheItemCarriesOn() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.breakOnTake = true;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        EnchantTrip.Outcome o = t.outcome();
        assertEquals(EnchantTrip.Kind.NEED_ANVIL, o.kind());
        assertEquals(1, o.stepsDone());
        assertEquals(1, o.anvilsBroken());
        assertEquals(1, sim.stepsDone.size());
        // the spare is put down (the host's job) and a trip from the journal does the rest
        sim.anvilBlock = true;
        sim.arrived = false;
        sim.walkTicks = 0;
        EnchantTrip again = trip(sim, c, o.stepsDone());
        sim.run(again, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, again.outcome().kind(), sim.log.toString());
        assertEquals(Map.of("sharpness", 5), sim.stacks().get(0).ench());
    }

    @Test
    void aBrokenAnvilAfterTheLastStepStillFinishesTheItem() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        int steps = c.ref().plan().steps().size();
        EnchantTrip t = trip(sim, c, 0);
        while (!t.done() && sim.stepsDone.size() < steps - 1) t.tick();
        sim.breakOnTake = true;
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind(), sim.log.toString());
        assertEquals(1, t.outcome().anvilsBroken());
    }

    @Test
    void anAnvilThatIsNotThereAtTheStartNeedsOne() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 400, 0);
        sim.anvilBlock = false;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.NEED_ANVIL, t.outcome().kind());
        assertEquals(0, t.outcome().stepsDone());
    }

    @Test
    void aPlayerNearPausesWithNoTimeLimitAndTheItemCarriesOn() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        EnchantTrip t = trip(sim, c, 0);
        // step 1 done
        while (!t.done() && sim.stepsDone.isEmpty()) t.tick();
        sim.playerNear = true;
        for (int i = 0; i < 40_000; i++) t.tick();
        assertFalse(t.done());
        assertNotNull(t.waiting());
        int steps = sim.stepsDone.size();
        // still waiting, nothing thrown or placed
        for (int i = 0; i < 2000; i++) t.tick();
        assertEquals(steps, sim.stepsDone.size());
        sim.playerNear = false;
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind(), sim.log.toString());
        assertNull(t.waiting());
    }

    @Test
    void theBotBeingControlledAlsoOnlyPauses() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.controlled = true;
        EnchantTrip t = trip(sim, c, 0);
        for (int i = 0; i < 5000; i++) t.tick();
        assertFalse(t.done());
        assertEquals(0, sim.stepsDone.size());
        sim.controlled = false;
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind());
    }

    @Test
    void aPlayerNearNeverInterruptsBetweenPlacingAndTaking() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        EnchantTrip t = trip(sim, c, 0);
        // run until the items are in the anvil, then a player shows up
        while (!t.done() && sim.left == null) t.tick();
        assertNotNull(sim.left);
        sim.playerNear = true;
        for (int i = 0; i < 200 && sim.stepsDone.isEmpty(); i++) t.tick();
        assertEquals(1, sim.stepsDone.size(), "the step was taken although a player was near");
        // and then it waits
        for (int i = 0; i < 500; i++) t.tick();
        assertEquals(1, sim.stepsDone.size());
        assertFalse(t.done());
    }

    @Test
    void aForceCancelStopsAtTheNextStepBoundary() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        EnchantTrip t = trip(sim, c, 0);
        // heard while the first step is in the anvil
        while (!t.done() && sim.left == null) t.tick();
        sim.forceCancelled = true;
        sim.run(t, LIMIT);
        EnchantTrip.Outcome o = t.outcome();
        assertEquals(EnchantTrip.Kind.FORCE_CANCELLED, o.kind());
        // the step in the anvil was finished (never stopped between placing and taking), the next one was not begun
        assertEquals(1, o.stepsDone());
        assertNull(sim.left);
        assertFalse(sim.gearAway);
    }

    @Test
    void aForceCancelBeforeAnyStepStopsAtOnce() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.forceCancelled = true;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FORCE_CANCELLED, t.outcome().kind());
        assertEquals(0, t.outcome().stepsDone());
    }

    @Test
    void anAbortInTheMiddleOfAStepPutsTheItemsBackAndTheJournalCarriesOn() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        EnchantTrip t = trip(sim, c, 0);
        while (!t.done() && sim.left == null) t.tick();
        // a step is half way and a module takes over
        sim.abort = true;
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.ABORTED, t.outcome().kind());
        assertEquals(0, t.outcome().stepsDone());
        assertNull(sim.left);
        assertEquals(c.held().size(), sim.stacks().size());
        assertFalse(sim.open);
        assertFalse(sim.gearAway);
        // later the same item carries on
        sim.abort = false;
        EnchantTrip again = trip(sim, c, t.outcome().stepsDone());
        sim.run(again, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, again.outcome().kind(), sim.log.toString());
    }

    @Test
    void aStepWhoseResultIsCarriedIsDoneEvenIfTheJournalSaysNot() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        // the first step is made by hand: its result is carried, its inputs are gone
        EnchantTrip first = trip(sim, c, 0);
        while (!first.done() && sim.stepsDone.isEmpty()) first.tick();
        // the bot was cut off before the journal was written: a new trip starts from step 0
        sim.stepsDone.clear();
        EnchantTrip again = trip(sim, c, 0);
        sim.run(again, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, again.outcome().kind(), sim.log.toString());
        // only the remaining steps were made now
        assertEquals(c.ref().plan().steps().size() - 1, sim.stepsDone.size());
        assertEquals(0, sim.stepsDone.get(0) - 1);
    }

    @Test
    void anItemMissingFromTheInventoryIsShortWithAWrongResult() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.inv.remove(c.held().get(1).slot());
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.SHORT, t.outcome().kind());
        assertEquals(Codes.WRONG_RESULT, t.outcome().reason());
    }

    @Test
    void noRoomForTheGearIsInventoryFull() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.noRoomForGear = true;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.SHORT, t.outcome().kind());
        assertEquals(Codes.INVENTORY_FULL, t.outcome().reason());
    }

    @Test
    void theAnvilIsWalkedToAndOpened() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.arrived = false;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind(), sim.log.toString());
        assertTrue(sim.walkTicks >= 3);
        assertTrue(sim.openTicks >= 1);
    }

    @Test
    void aBusyPortHoldsTheTripBack() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.busyTicks = 100;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind(), sim.log.toString());
    }

    @Test
    void theLevelsAreLeftAsTheSpentStepsLeaveThem() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, EnchantRules.points(30));
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind());
        // levels already held were used first: with a high level the first steps need no bottles at all
        assertTrue(t.bottlesUsed() < c.ref().plan().bottles());
    }

    @Test
    void aWindowThatClosesAfterPlacingIsOpenedAgainNotAShort() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.dropWindows = 2;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind(), sim.log.toString());
        assertTrue(sim.openTicks >= 3, "opened " + sim.openTicks);
    }

    @Test
    void aWindowThatKeepsClosingGivesUpTheAnvilAfterABoundedNumberOfTries() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.dropWindows = 100;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.NEED_ANVIL, t.outcome().kind());
        assertTrue(sim.openTicks <= EnchantTrip.MAX_REOPENS + 1, "opened " + sim.openTicks);
        assertEquals(c.held().size(), sim.stacks().size());
    }

    @Test
    void aResultThatIsStillInTheSlotIsTakenAgain() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.skipTakes = 2;
        EnchantTrip t = trip(sim, c, 0);
        sim.run(t, LIMIT);
        assertEquals(EnchantTrip.Kind.FINISHED, t.outcome().kind(), sim.log.toString());
    }

    @Test
    void aForceCancelInALevelPauseEndsTheTripAtOnce() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        sim.playerNear = true;
        EnchantTrip t = trip(sim, c, 0);
        for (int i = 0; i < 300; i++) t.tick();
        assertFalse(t.done());
        sim.forceCancelled = true;
        sim.run(t, 100);
        assertEquals(EnchantTrip.Kind.FORCE_CANCELLED, t.outcome().kind());
        assertFalse(sim.gearAway);
    }

    @Test
    void aForceCancelWhileThrowingEndsTheTripToo() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        EnchantTrip t = trip(sim, c, 0);
        for (int i = 0; i < 30 && !t.done(); i++) t.tick();
        sim.forceCancelled = true;
        sim.abort = true;
        sim.run(t, 500);
        assertEquals(EnchantTrip.Kind.FORCE_CANCELLED, t.outcome().kind());
    }

    @Test
    void anAbortDuringStowingStillPutsTheGearBack() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantSim sim = world(c, 600, 0);
        EnchantTrip t = trip(sim, c, 0);
        t.tick();
        t.tick();
        sim.abort = true;
        sim.run(t, 500);
        assertEquals(EnchantTrip.Kind.ABORTED, t.outcome().kind());
        assertFalse(sim.gearAway);
    }

    @Test
    void aJournalPlanThatPointsAtItemsThatDoNotExistYetIsNotFollowed() {
        EnchantKit.Case c = EnchantKit.sharpnessV();
        EnchantOrder.ItemPlan p = c.ref().plan();
        EnchantOrder.ItemPlan bad = new EnchantOrder.ItemPlan(p.materials(), List.of(new EnchantOrder.Step(0, 99, 5, 1), new EnchantOrder.Step(3, 3, 5, 1)), p.levels(), p.extras(), p.curse(), p.points(), p.bottles());
        EnchantTrip t = new EnchantTrip(world(c, 600, 0), EnchantKit.MC, c.ref().key(), bad, 0);
        assertEquals(-1, t.specOf(3).rc());
        assertEquals(-1, t.specOf(4).rc());
        assertEquals(-1, t.specOf(500).rc());
    }
}
