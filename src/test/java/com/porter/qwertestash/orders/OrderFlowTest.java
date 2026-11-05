package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The order state machine on the fake world: claim, gather and pack, deliver, done; cancel; pause; resume; failures. */
class OrderFlowTest {
    private static final String D = "minecraft:diamond", S = "minecraft:stone", P = "minecraft:ender_pearl", RED = "minecraft:red_shulker_box";

    private static Order order(String id, Order.Line... lines) {
        return new Order(id, List.of(lines));
    }

    private static Order.Line item(String key, int count) {
        return new Order.Line("item", key, count);
    }

    private static OrderSim world() {
        return new OrderSim().stock(D, "v", 100_000, 64).stock(S, "w", 5000, 64).stock(P, "p", 64, 16);
    }

    @Test
    void claimGatherPackDeliverDone() {
        OrderSim w = world();
        w.empties.put(RED, 3);
        assertNull(w.flow.offer(order("o-1", item(D, 150), item(P, 20))));
        assertTrue(w.flow.planned());
        assertEquals("o-1", w.saved.id);
        assertEquals(1, w.flow.units().size());

        w.run(50);

        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(1, r.shulkers());
        assertEquals(150, r.delivered().get(D));
        assertEquals(20, r.delivered().get(P));
        assertEquals(1, w.delivery.size());
        assertEquals(99_850, w.loose.get(D).get("v"));
        assertTrue(r.terminal());

        // The saved plan stays until the server has the end
        assertNotNull(w.saved);
        w.flow.ack("o-1");
        assertNull(w.saved);
        assertFalse(w.flow.holdsOrder());
    }

    @Test
    void anOrderThatOnlyExistsInsideShulkersIsPlannedAndDelivered() {
        OrderSim w = new OrderSim().inShulker(D, "v", 3000, 64).inShulker(P, "p", 48, 16);
        w.empties.put(RED, 2);
        assertNull(w.flow.offer(order("o-sh", item(D, 640), item(P, 20))));
        assertEquals(1, w.flow.units().size());
        w.run(20);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(640, r.delivered().get(D));
        assertEquals(20, r.delivered().get(P));
        assertEquals(2360, w.shulkerStock.get(D).get("v"));
        assertEquals(28, w.shulkerStock.get(P).get("p"));
        assertTrue(w.said.stream().noneMatch(s -> s.contains("can't take from shulkers")));
        assertFalse(r.message().contains("inside shulkers"));
    }

    @Test
    void looseStacksAreUsedBeforeShulkerContents() {
        OrderSim w = new OrderSim().stock(D, "v", 200, 64).inShulker(D, "v", 5000, 64);
        w.empties.put(RED, 2);
        assertNull(w.flow.offer(order("o-mix", item(D, 150))));
        w.run(20);
        assertEquals("delivered", w.flow.report().status());
        // 150 came from the 200 loose ones: no shulker was opened
        assertEquals(50, w.loose.get(D).get("v"));
        assertEquals(5000, w.shulkerStock.get(D).get("v"));
        assertEquals(0, w.sourcesOpened);
    }

    @Test
    void whatLooseStacksCannotGiveComesOutOfShulkers() {
        OrderSim w = new OrderSim().stock(D, "v", 100, 64).inShulker(D, "v", 5000, 64);
        w.empties.put(RED, 2);
        assertNull(w.flow.offer(order("o-mix2", item(D, 150))));
        // 100 loose (64 + 36) and 50 from a shulker: the two partials of one variant are one load of 86
        long total = w.flow.units().get(0).lots.stream().mapToLong(Order.Lot::count).sum();
        assertEquals(150, total);
        assertEquals(3, w.flow.units().get(0).lots.size());
        w.run(20);
        assertEquals("delivered", w.flow.report().status());
        assertEquals(0, w.loose.get(D).get("v"));
        assertEquals(4950, w.shulkerStock.get(D).get("v"));
        assertEquals(1, w.sourcesOpened);
    }

    @Test
    void phasesWalkThroughBuildingAndDelivering() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        w.flow.offer(order("o-2", item(D, 64 * 30)));
        w.run(50);
        assertTrue(w.phases.contains("building 1/2"), w.phases.toString());
        assertTrue(w.phases.contains("delivering 1/2"), w.phases.toString());
        assertTrue(w.phases.contains("building 2/2"), w.phases.toString());
        assertTrue(w.phases.contains("delivering 2/2"), w.phases.toString());
        assertEquals("delivered", w.flow.report().status());
        assertEquals(2, w.delivery.size());
        // Never more than one finished shulker carried
        assertTrue(w.carried.isEmpty());
    }

    @Test
    void shulkerLinesAndEmptyLinesAreFetchedThenDelivered() {
        OrderSim w = world();
        w.packed.put("s:abc", 2);
        w.empties.put(RED, 3);
        assertNull(w.flow.offer(order("o-3", new Order.Line("shulker", "s:abc", 2), item(RED, 2), item(S, 10))));
        w.run(100);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(5, r.shulkers());
        assertEquals(2, r.delivered().get("s:abc"));
        assertEquals(2, r.delivered().get(RED));
        assertEquals(10, r.delivered().get(S));
    }

    @Test
    void offerIsRefusedWithAReasonWhenItCannotBePlanned() {
        OrderSim w = world();
        assertEquals("nothing can be done: minecraft:emerald: none usable in the stash", w.flow.offer(order("o-4", item("minecraft:emerald", 5))));
        assertFalse(w.flow.holdsOrder());

        // Not enough empty shulkers for the bins
        OrderSim none = world();
        String why = none.flow.offer(order("o-5", item(D, 10)));
        assertNotNull(why);
        assertTrue(why.startsWith("need 1 empty shulkers"), why);
        assertFalse(none.flow.holdsOrder());

        OrderSim noDelivery = world();
        noDelivery.deliverySet = false;
        assertEquals("no delivery container is set", noDelivery.flow.offer(order("o-6", item(D, 10))));

        OrderSim tooBig = world();
        tooBig.stock(S, "w", 2_000_000, 64);
        tooBig.empties.put(RED, 100);
        String big = tooBig.flow.offer(order("o-7", item(S, 64 * 27 * 55)));
        assertNotNull(big);
        assertTrue(big.contains("54"));
    }

    @Test
    void aSecondOrderIsRefusedWhileOneIsInHand() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        assertNull(w.flow.offer(order("o-8", item(D, 10))));
        assertEquals("already working on another order", w.flow.offer(order("o-9", item(D, 10))));
        // The same order again is the same order
        assertNull(w.flow.offer(order("o-8", item(D, 10))));
    }

    @Test
    void cancelAtTheNextSafePoint() {
        OrderSim w = world();
        w.empties.put(RED, 3);
        w.flow.offer(order("o-10", item(D, 64 * 30)));
        // One shulker is built and delivered, then the server cancels
        w.step();
        w.step();
        assertEquals(1, w.delivery.size());
        assertTrue(w.flow.cancel("o-10"));
        assertTrue(w.flow.cancelRequested());
        assertFalse(w.flow.cancel("o-other"));
        w.run(10);

        OrderFlow.Report r = w.flow.report();
        assertEquals("cancelled", r.status());
        assertEquals(1, w.delivery.size());
        assertEquals(1, r.shulkers());
        assertTrue(w.flow.report().terminal());
        w.flow.ack("o-10");
        assertNull(w.saved);
    }

    @Test
    void cancelBeforeAnythingIsDone() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-11", item(D, 10)));
        w.flow.cancel("o-11");
        w.run(5);
        assertEquals("cancelled", w.flow.report().status());
        assertEquals(0, w.delivery.size());
        assertEquals(100_000, w.loose.get(D).get("v"));
    }

    @Test
    void pausesWhileTheBotCannotWorkAndResumes() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-12", item(D, 10)));
        w.gate = false;
        assertTrue(w.flow.next() instanceof OrderFlow.Idle);
        assertEquals("paused: test pause", w.flow.phase());
        assertEquals("in_progress", w.flow.report().status());
        w.now += 60_000;
        assertTrue(w.flow.next() instanceof OrderFlow.Idle);
        assertEquals("in_progress", w.flow.report().status());

        w.gate = true;
        w.run(10);
        assertEquals("delivered", w.flow.report().status());
    }

    @Test
    void theReportSaysWhyItIsPausedAndKeepsSayingIt() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-p", item(D, 10)));
        w.gate = false;
        w.pauseText = "in the queue";
        w.now += 1000;
        assertEquals("in_progress", w.flow.report().status());
        assertEquals("paused: in the queue", w.flow.report().phase());
        w.gate = true;
        w.run(10);
        assertEquals("delivered", w.flow.report().status());
    }

    @Test
    void parkingWithNothingRunningTakesEffectAtOnce() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-park", item(D, 10)));
        w.active = false;
        w.flow.park();
        assertFalse(w.flow.holdsOrder());
        assertNotNull(w.saved);
        // With the sorter running it waits for the next safe point instead
        OrderSim running = world();
        running.empties.put(RED, 1);
        running.flow.offer(order("o-park2", item(D, 10)));
        running.flow.park();
        assertTrue(running.flow.holdsOrder());
        running.flow.next();
        assertFalse(running.flow.holdsOrder());
    }

    @Test
    void theDeliveryContainerInAnotherDimensionEndsTheOrderWithThatReason() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        w.flow.offer(order("o-dim", item(D, 10)));
        w.otherDimension = true;
        w.run(5);
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertTrue(r.message().contains("another dimension"), r.message());
    }

    @Test
    void aShulkerPutInJustBeforeAnOfflineIsLookedForNotBuiltAgain() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        w.deferConfirm = true;
        w.flow.offer(order("o-twice", item(D, 10)));
        w.step();
        w.step();
        assertEquals(1, w.delivery.size());
        assertEquals(0, w.saved.done);
        assertEquals(0, w.saved.deliveringIndex);
        int stock = w.loose.get(D).get("v");

        // Offline right there; back, with the same saved plan: the container is looked at first
        w.flow.reset();
        w.deferConfirm = false;
        assertNull(w.flow.offer(order("o-twice", item(D, 10))));
        w.run(10);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(1, r.shulkers());
        assertEquals(1, w.delivery.size(), "delivered twice");
        assertEquals(stock, w.loose.get(D).get("v"), "built again");
        assertTrue(w.phases.contains("checking delivery 1/1"), w.phases.toString());
    }

    @Test
    void aShulkerThatNeverWentInIsStillDeliveredAfterTheRestart() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        w.flow.offer(order("o-notyet", item(D, 10)));
        w.step();
        // Built, about to be put in, offline before the click: it is still carried
        Unit u = w.flow.units().get(0);
        w.flow.deliveryStarted(u);
        w.flow.reset();
        assertNull(w.flow.offer(order("o-notyet", item(D, 10))));
        w.run(10);
        assertEquals("delivered", w.flow.report().status());
        assertEquals(1, w.delivery.size());
    }

    @Test
    void failsWhenHeldUpForTooLong() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-13", item(D, 10)));
        w.gate = false;
        w.flow.next();
        w.now += OrderFlow.PAUSE_TIMEOUT_MS + 1000;
        w.flow.next();
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertTrue(r.message().contains("held up"));
    }

    @Test
    void waitsForTheContainersToBeReadAgainThenPlans() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.rereading = true;
        w.staleCount = 5;
        assertNull(w.flow.offer(order("o-14", item(D, 10))));
        assertFalse(w.flow.planned());
        assertNull(w.saved);
        assertTrue(w.flow.next() instanceof OrderFlow.Idle);
        assertEquals("re-reading containers", w.flow.report().phase());

        w.rereading = false;
        w.staleCount = 0;
        w.run(10);
        assertEquals("delivered", w.flow.report().status());
    }

    @Test
    void theRereadWaitOnlyRunsOutWhenItMakesNoProgress() {
        OrderSim w = world();
        w.rereading = true;
        w.staleCount = 100;
        w.flow.offer(order("o-15", item(D, 10)));
        for (int i = 0; i < 20; i++) {
            w.staleCount -= 5;
            w.now += 120_000;
            assertEquals("in_progress", w.flow.report().status());
        }
        // Stuck: the count stops going down
        w.now += OrderFlow.REREAD_TIMEOUT_MS + 1000;
        assertEquals("failed", w.flow.report().status());
    }

    @Test
    void aPlanThatCannotBeMadeAfterTheRereadFailsTheOrder() {
        OrderSim w = world();
        w.rereading = true;
        w.flow.offer(order("o-16", item(D, 10)));
        w.rereading = false;
        // No empty shulkers turned up
        w.flow.next();
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertTrue(r.message().startsWith("need 1 empty"));
    }

    @Test
    void aRestartResumesTheSamePlanWhereItStopped() {
        OrderSim w = world();
        w.empties.put(RED, 3);
        w.flow.offer(order("o-17", item(D, 64 * 30)));
        w.step();
        w.step();
        assertEquals(1, w.delivery.size());
        OrderFlow.Saved saved = w.saved;
        List<Order.Lot> firstPlan = new ArrayList<>(w.flow.units().get(1).lots);

        // The bot goes offline: forgotten here, kept in the stash file (the shulker just put in counts)
        w.flow.reset();
        assertEquals(1, w.saved.done);
        assertFalse(w.flow.holdsOrder());
        assertEquals("o-17", w.flow.resumeId());
        assertSame(saved, w.saved);

        // The stash changed meanwhile, but the plan is the saved one
        w.loose.get(D).put("v", 5000);
        assertNull(w.flow.offer(order("o-17", item(D, 64 * 30))));
        assertEquals(1, w.flow.index());
        assertEquals(firstPlan, w.flow.units().get(1).lots);
        w.run(10);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(2, r.shulkers());
        assertEquals(64 * 30, r.delivered().get(D));
    }

    private static void assertSame(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertSame(expected, actual);
    }

    @Test
    void aFinishedPlanResumedJustReportsTheEnd() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-18", item(D, 10)));
        w.run(10);
        assertEquals("delivered", w.flow.report().status());
        // The report never got through and the bot restarted
        w.flow.reset();
        assertNull(w.flow.offer(order("o-18", item(D, 10))));
        w.run(5);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(1, r.shulkers());
        assertEquals(10, r.delivered().get(D));
    }

    @Test
    void parkedOrdersAreKeptButNotWorkedOn() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-19", item(D, 10)));
        w.flow.park();
        assertTrue(w.flow.next() instanceof OrderFlow.Idle);
        assertFalse(w.flow.holdsOrder());
        assertNotNull(w.saved);
        assertEquals(0, w.delivery.size());
    }

    @Test
    void aFullDeliveryContainerFailsAfterTwoTriesKeepingWhatWasDelivered() {
        OrderSim w = world();
        w.empties.put(RED, 5);
        w.deliveryCapacity = 1;
        w.flow.offer(order("o-20", item(D, 64 * 30 * 3)));
        w.run(60);
        OrderFlow.Report r = w.flow.report();
        // One shulker went in; the second would not: partly delivered counts as delivered, with the reason
        assertEquals("delivered", r.status());
        assertEquals(1, r.shulkers());
        assertTrue(r.message().contains("delivery container full"), r.message());
    }

    @Test
    void aFullContainerWithNothingDeliveredIsAFailure() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        w.deliveryCapacity = 0;
        w.flow.offer(order("o-21", item(D, 10)));
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertTrue(r.message().contains("delivery container full"));
    }

    @Test
    void theDeliveryContainerGoingAwayEndsTheOrder() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        w.flow.offer(order("o-22", item(D, 10)));
        w.deliveryUnreachable = true;
        w.run(5);
        assertEquals("failed", w.flow.report().status());
        assertTrue(w.flow.report().message().contains("couldn't reach the delivery container"));

        OrderSim gone = world();
        gone.empties.put(RED, 2);
        gone.flow.offer(order("o-23", item(D, 10)));
        gone.deliverySet = false;
        gone.run(5);
        assertTrue(gone.flow.report().message().contains("delivery container is gone"));
    }

    @Test
    void aShulkerThatNeverShowsUpInTheContainerIsNotCounted() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        w.loseDeliveries = true;
        w.flow.offer(order("o-24", item(D, 10)));
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertEquals(0, r.shulkers());
        assertTrue(r.message().contains("wasn't seen in the delivery container"), r.message());
        assertTrue(w.phases.stream().anyMatch(p -> p.startsWith("checking delivery")));
    }

    @Test
    void whatCannotBeBuiltIsReplannedOnceThenSkipped() {
        OrderSim w = world();
        w.empties.put(RED, 3);
        w.failBuilds = true;
        w.flow.offer(order("o-25", item(D, 10)));
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertTrue(r.message().contains("nothing could be delivered"), r.message());
        assertTrue(r.message().contains("replanned a shulker"), r.message());
    }

    @Test
    void aMissingPackedShulkerIsSkippedAndTheRestDelivered() {
        OrderSim w = world();
        w.packed.put("s:abc", 1);
        w.empties.put(RED, 2);
        w.flow.offer(order("o-26", new Order.Line("shulker", "s:abc", 1), item(D, 10)));
        // Someone took the packed shulker before the bot got to it
        w.packed.put("s:abc", 0);
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(1, r.shulkers());
        assertTrue(r.message().contains("no longer in the stash"), r.message());
        assertTrue(r.message().contains("delivered less than asked"));
    }

    @Test
    void shortDeliveriesAreReported() {
        OrderSim w = new OrderSim().stock(D, "v", 40, 64);
        w.empties.put(RED, 1);
        assertNull(w.flow.offer(order("o-27", item(D, 100))));
        w.run(10);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(40, r.delivered().get(D));
        assertTrue(r.message().contains(D + " 40/100"), r.message());
        assertTrue(r.message().contains("only 40 of 100"), r.message());
    }

    @Test
    void progressCountsDoneUnits() {
        OrderSim w = world();
        w.empties.put(RED, 2);
        w.flow.offer(order("o-28", item(D, 64 * 30)));
        OrderFlow.Report r = w.flow.report();
        assertEquals(0, r.done());
        assertEquals(2, r.total());
        w.step();
        w.step();
        assertEquals(1, w.flow.report().done());
        w.run(10);
        assertEquals(2, w.flow.report().done());
    }

    @Test
    void dropSavedKeepsOnlyTheNamedOrder() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-29", item(D, 10)));
        w.flow.reset();
        w.flow.dropSaved("o-29");
        assertNotNull(w.saved);
        w.flow.dropSaved("o-other");
        assertNull(w.saved);
        assertNull(w.flow.resumeId());
    }

    @Test
    void messagesToTheLogNameTheOrder() {
        OrderSim w = world();
        w.empties.put(RED, 1);
        w.flow.offer(order("o-30", item(D, 10)));
        w.run(10);
        assertTrue(w.said.stream().anyMatch(s -> s.startsWith("Taking order o-30")));
        assertTrue(w.said.stream().anyMatch(s -> s.startsWith("Order o-30 delivered")));
        Map<String, Integer> totals = w.deliveredTotals();
        assertEquals(10, totals.get(D));
    }
}
