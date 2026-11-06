package com.porter.qwertestash.orders;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 fixes: an order that meets a pause in shulker handling waits instead of failing at once, and a shulker planned
 * again counts what lies inside shulkers, not only loose stacks.
 */
class OrderShulkerPauseTest {
    private static final String D = "minecraft:diamond", P = "minecraft:ender_pearl", RED = "minecraft:red_shulker_box";

    // ---- 1: the shulker pause is a reason to wait

    @Test
    void shulkerPauseReasons() {
        assertNull(OrderBlocker.shulkerPause(false, false, 0, ""));
        assertNull(OrderBlocker.shulkerPause(false, false, -50, "old problem"), "a pause that is over is no reason");

        String lost = OrderBlocker.shulkerPause(true, false, 0, "");
        assertTrue(lost.startsWith(OrderBlocker.SHULKER_PAUSED), lost);
        assertTrue(lost.contains("could not be picked up"), lost);

        String resume = OrderBlocker.shulkerPause(false, true, 0, "");
        assertTrue(resume.startsWith(OrderBlocker.SHULKER_PAUSED), resume);

        String room = OrderBlocker.shulkerPause(false, false, 20 * 60 * 3, "no room to put a shulker down beside the bot");
        assertEquals("shulker handling is paused: no room to put a shulker down beside the bot, trying again in 3 min", room);
        assertTrue(OrderBlocker.shulkerPause(false, false, 30, "").contains("after a shulker problem, trying again in 1 min"));
    }

    @Test
    void aBinGivenUpForTheShulkerPauseIsRetriedNotReplanned() {
        assertTrue(OrderBlocker.retryableBuildFailure(null));
        assertTrue(OrderBlocker.retryableBuildFailure(OrderBlocker.SHULKER_PAUSED));
        assertFalse(OrderBlocker.retryableBuildFailure("the items were not where they were saved"));

        OrderSim w = new OrderSim().stock(D, "v", 1000, 64);
        w.empties.put(RED, 2);
        assertNull(w.flow.offer(new Order("o-p1", List.of(new Order.Line("item", D, 100)))));
        Unit u = w.flow.units().get(0);
        w.flow.noteBuildStart(u);
        String why = OrderBlocker.SHULKER_PAUSED;
        assertTrue(w.flow.buildFailed(u, why, OrderBlocker.retryableBuildFailure(why)));
        assertFalse(u.replanned, "a pause is not the plan's fault: no replan, nothing skipped");
        w.run(50);
        assertEquals("delivered", w.flow.report().status());
        assertFalse(w.flow.report().message().contains("replanned"), w.flow.report().message());
    }

    @Test
    void anOrderStartedDuringAShulkerPauseWaitsThenRuns() {
        OrderSim w = new OrderSim().stock(D, "v", 1000, 64);
        w.empties.put(RED, 2);
        w.buildPause = OrderBlocker.shulkerPause(false, false, 20 * 60 * 3, "no room to put a shulker down beside the bot");
        assertNull(w.flow.offer(new Order("o-p2", List.of(new Order.Line("item", D, 100)))));

        w.run(20);
        OrderFlow.Report paused = w.flow.report();
        assertEquals("in_progress", paused.status(), "it waits instead of failing");
        assertTrue(paused.phase().startsWith("paused: shulker handling is paused: no room"), paused.phase());
        assertEquals(0, w.delivery.size());

        // The pause ends
        w.buildPause = null;
        w.run(50);
        OrderFlow.Report done = w.flow.report();
        assertEquals("delivered", done.status(), done.message());
        assertEquals(100, done.delivered().get(D));
    }

    @Test
    void aFinishedShulkerIsDeliveredDuringAShulkerPauseAndOnlyTheNextBinWaits() {
        OrderSim w = new OrderSim().stock(D, "v", 100_000, 64);
        w.empties.put(RED, 3);
        w.packed.put("s:abcdef0123", 1);
        assertNull(w.flow.offer(new Order("o-p3", List.of(new Order.Line("item", D, 64 * 30), new Order.Line("shulker", "s:abcdef0123", 1)),
            ShulkerNames.all("Kit"))));
        // Plan: the packed shulker, then two bins. The first bin is built, then a shulker can't be picked up: handling pauses
        for (String expected : new String[] {"fetch", "name", "deliver", "build"}) {
            assertTrue(w.step());
            assertEquals(expected, w.last);
        }
        w.buildPause = OrderBlocker.shulkerPause(true, false, 0, "");
        // The carried, finished bin is still named and delivered
        w.run(10);
        assertEquals(2, w.delivery.size(), "finished shulkers go out during the pause");
        assertEquals(List.of("Kit", "Kit"), w.named);
        OrderFlow.Report paused = w.flow.report();
        assertEquals("in_progress", paused.status());
        assertTrue(paused.phase().startsWith("paused: shulker handling is paused"), paused.phase());

        w.buildPause = null;
        w.run(30);
        assertEquals("delivered", w.flow.report().status());
        assertEquals(3, w.delivery.size());
    }

    @Test
    void aBuildHeldByTheShulkerPauseFailsOnlyAfterThePauseTimeout() {
        OrderSim w = new OrderSim().stock(D, "v", 1000, 64);
        w.empties.put(RED, 2);
        w.buildPause = OrderBlocker.shulkerPause(true, false, 0, "");
        assertNull(w.flow.offer(new Order("o-p4", List.of(new Order.Line("item", D, 100)))));
        w.step();
        w.now += OrderFlow.PAUSE_TIMEOUT_MS - 1000;
        w.step();
        assertEquals("in_progress", w.flow.report().status());
        w.now += 2000;
        w.step();
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertTrue(r.message().startsWith("the bot was held up for too long (shulker handling is paused"), r.message());
    }

    // ---- 2: a replanned shulker counts what lies inside shulkers

    private static StockPlanner.Survey survey(Map<String, Long> loose, Map<String, List<StockPlanner.Var>> looseVars,
                                              Map<String, Long> inShulkers, Map<String, List<StockPlanner.Var>> shulkerVars) {
        Map<String, Integer> max = new HashMap<>();
        max.put(D, 64);
        max.put(P, 16);
        return new StockPlanner.Survey(loose, max, Set.of(), Set.of(), looseVars, Map.of(), inShulkers, shulkerVars);
    }

    private static long total(List<Order.Lot> lots, String key) {
        return lots.stream().filter(l -> l.key().equals(key)).mapToLong(Order.Lot::count).sum();
    }

    @Test
    void trimKeepsWhatOnlyExistsInsideShulkers() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v", 64), new Order.Lot(D, "v", 64), new Order.Lot(P, "p", 16));
        StockPlanner.Survey s = survey(Map.of(), Map.of(),
            Map.of(D, 3000L, P, 48L), Map.of(D, List.of(new StockPlanner.Var("v", 3000)), P, List.of(new StockPlanner.Var("p", 48))));

        List<Order.Lot> out = StockPlanner.trim(lots, s);
        assertEquals(128, total(out, D), "diamonds only inside shulkers are kept");
        assertEquals(16, total(out, P));
    }

    @Test
    void trimTakesLooseFirstThenShulkersAndDropsWhatIsGone() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v", 64), new Order.Lot(D, "v", 64), new Order.Lot(D, "v", 64), new Order.Lot(P, "p", 16));
        StockPlanner.Var looseD = new StockPlanner.Var("v", 10), shulkerD = new StockPlanner.Var("v", 500);
        StockPlanner.Survey s = survey(Map.of(D, 10L), Map.of(D, List.of(looseD)), Map.of(D, 500L), Map.of(D, List.of(shulkerD)));

        List<Order.Lot> out = StockPlanner.trim(lots, s);
        assertEquals(192, total(out, D), "10 loose plus 182 out of shulkers");
        assertEquals(0, total(out, P), "pearls are gone everywhere: dropped");
        assertTrue(out.size() <= Order.SLOTS);
    }

    @Test
    void trimStillCapsAtWhatIsThere() {
        List<Order.Lot> lots = List.of(new Order.Lot(D, "v", 64), new Order.Lot(D, "v", 64));
        StockPlanner.Survey s = survey(Map.of(D, 20L), Map.of(D, List.of(new StockPlanner.Var("v", 20))),
            Map.of(D, 30L), Map.of(D, List.of(new StockPlanner.Var("v", 30))));
        assertEquals(50, total(StockPlanner.trim(lots, s), D));
    }

    // ---- 3: the failure message leads with the reason

    @Test
    void theEndNoteLeadsWithTheReasonAndFitsTheServer() {
        List<String> shortLines = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) shortLines.add("minecraft:some_long_item_name_number_" + i + " 0/640");
        String note = OrderFlow.endNote(false, List.of("replanned a shulker: shulker handling is paused", "couldn't start building a shulker"), shortLines);
        assertTrue(note.startsWith("nothing could be delivered: couldn't start building a shulker; replanned a shulker"), note);
        assertTrue(note.length() <= OrderFlow.MESSAGE_MAX, note.length() + ": " + note);
    }
}
