package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An order with enchant lines through OrderFlow: the intent, the pause exemption, queued and forced cancels, the journal and the result. */
class EnchantFlowTest {
    private static final String BLOCK = """
        { "v": 1, "mc": "1.21.4",
          "lines": [ { "key": "minecraft:diamond_sword", "count": 2, "enchants": [ { "id": "minecraft:sharpness", "lvl": 5 } ],
                       "plans": [ { "materials": [ { "kind": "base", "key": "minecraft:diamond_sword", "ench": [], "rc": 0 },
                                                   { "kind": "book", "key": "minecraft:enchanted_book#a1111111", "ench": [ { "id": "minecraft:sharpness", "lvl": 5 } ], "rc": 0 } ],
                                    "steps": [ { "left": 0, "right": 1, "cost": 5, "rc": 1 } ], "levels": [ { "id": "minecraft:sharpness", "lvl": 5 } ],
                                    "extras": [], "curse": [], "totalLevels": 5, "points": 55, "bottles": 14, "finalRc": 1 },
                                  { "materials": [ { "kind": "base", "key": "minecraft:diamond_sword", "ench": [], "rc": 0 },
                                                   { "kind": "book", "key": "minecraft:enchanted_book#a1111111", "ench": [ { "id": "minecraft:sharpness", "lvl": 5 } ], "rc": 0 } ],
                                    "steps": [ { "left": 0, "right": 1, "cost": 5, "rc": 1 } ], "levels": [ { "id": "minecraft:sharpness", "lvl": 5 } ],
                                    "extras": [], "curse": [], "totalLevels": 5, "points": 55, "bottles": 14, "finalRc": 1 } ] } ],
          "take": [ { "key": "minecraft:diamond_sword", "count": 2 }, { "key": "minecraft:enchanted_book#a1111111", "count": 2 },
                    { "key": "minecraft:experience_bottle", "count": 28 }, { "key": "minecraft:anvil", "count": 2 } ],
          "curse": [] }
        """;

    private static final String ORDER = "{\"id\":\"ord-1\",\"lines\":[{\"type\":\"enchant\",\"key\":\"minecraft:diamond_sword\",\"count\":2,"
        + "\"enchants\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}]},{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":3}],\"enchant\":" + BLOCK + "}";

    private static final String ENCHANT_ONLY = "{\"id\":\"ord-2\",\"lines\":[{\"type\":\"enchant\",\"key\":\"minecraft:diamond_sword\",\"count\":2,"
        + "\"enchants\":[{\"id\":\"minecraft:sharpness\",\"lvl\":5}]}],\"enchant\":" + BLOCK + "}";

    /** A host that does the bare minimum: a plan of one bin, a store for the saved plan, a clock and the enchant hooks. */
    static final class Host implements OrderFlow.Host {
        final Map<String, OrderFlow.Saved> store = new HashMap<>();
        final List<String> said = new ArrayList<>();
        @Nullable String problem;
        boolean gate = true;
        List<Unit> resumedSeen;
        Map<String, Long> carried = Map.of();
        boolean returning;
        Map<String, Long> still = Map.of();
        int plans;

        @Override
        public boolean gate() {
            return gate;
        }

        @Override
        public boolean rereading() {
            return false;
        }

        @Override
        public int staleCount() {
            return 0;
        }

        @Override
        public boolean deliverySet() {
            return true;
        }

        @Override
        public @Nullable String deliveryProblem() {
            return null;
        }

        @Override
        public int finishedSlot(Unit unit) {
            return -1;
        }

        @Override
        public Map<String, Integer> contentOf(Unit unit, int slot) {
            return Map.of();
        }

        @Override
        public boolean canFetch(Unit unit) {
            return false;
        }

        @Override
        public StockPlanner.Plan plan(Order order) {
            return plan(order, List.of());
        }

        @Override
        public StockPlanner.Plan plan(Order order, List<Order.Lot> lots) {
            plans++;
            List<Order.Lot> all = new ArrayList<>();
            for (Order.Line l : order.lines) if (l.type().equals(Order.ITEM)) all.add(new Order.Lot(l.key(), l.count()));
            all.addAll(lots);
            if (all.isEmpty()) return new StockPlanner.Plan(List.of(), List.of(), "nothing to deliver");
            List<Unit> units = new ArrayList<>();
            for (List<Order.Lot> bin : Order.pack(all)) units.add(new Unit(Unit.Kind.Bin, "", new ArrayList<>(bin)));
            return new StockPlanner.Plan(units, List.of(), null);
        }

        @Override
        public @Nullable String checkEmpties(List<Unit> remaining) {
            return null;
        }

        @Override
        public void save(OrderFlow.Saved saved) {
            store.put(saved.id, saved);
        }

        @Override
        public void clearSaved(String id) {
            store.remove(id);
        }

        @Override
        public OrderFlow.@Nullable Saved saved(String id) {
            return store.get(id);
        }

        @Override
        public @Nullable String savedId() {
            return store.isEmpty() ? null : store.keySet().iterator().next();
        }

        @Override
        public void say(String text) {
            said.add(text);
        }

        @Override
        public @Nullable String enchantProblem(Order order) {
            return problem;
        }

        @Override
        public void enchantResumed(List<Unit> units) {
            resumedSeen = units;
        }

        @Override
        public Map<String, Long> enchantCarried() {
            return carried;
        }

        @Override
        public boolean enchantReturning() {
            return returning;
        }

        @Override
        public Map<String, Long> enchantStillCarried() {
            return still;
        }
    }

    private final AtomicLong clock = new AtomicLong(5_000_000);

    private OrderFlow flow(Host h) {
        return new OrderFlow(h, clock::get);
    }

    private static Order order(String json) {
        Order o = Order.parse(JsonParser.parseString(json).getAsJsonObject());
        assertNotNull(o);
        return o;
    }

    private static EnchantLedger.Item begin(OrderFlow f, int n) {
        EnchantLedger l = f.enchantLedger();
        l.begin(n, l.block().items().get(n).plan());
        return l.item(n);
    }

    @Test
    void everyEnchantItemIsALoadOfItsBinAndTheIntentComesUntilItIsDone() {
        Host h = new Host();
        OrderFlow f = flow(h);
        assertNull(f.offer(order(ORDER)));
        // one bin: 3 diamonds, two swords to enchant (a slot each)
        assertEquals(1, f.units().size());
        Unit u = f.units().get(0);
        assertEquals(3, u.lots.size());
        assertEquals(2, u.lots.stream().filter(Order.Lot::enchant).count());
        OrderFlow.Intent i = f.next();
        assertInstanceOf(OrderFlow.EnchantLoads.class, i);
        assertEquals(u, ((OrderFlow.EnchantLoads) i).unit());
        assertTrue(f.phase().startsWith("enchanting 1/2"), f.phase());
        // asked again while nothing happened
        assertInstanceOf(OrderFlow.EnchantLoads.class, f.next());
        // item 1 is finished: its lot is the finished stack (in hand), rekeyed so the bin and the delivery count find it
        f.enchantItemDone(u, 0, "minecraft:diamond_sword#c0ffee00", "minecraft:diamond_sword#c0ffee0011223344", Map.of("sharpness", 5));
        assertEquals(1, u.lots.stream().filter(Order.Lot::crafted).count());
        assertEquals(1, u.lots.stream().filter(Order.Lot::enchant).count());
        assertEquals(1, u.rekeys.size());
        assertEquals("minecraft:diamond_sword", u.rekeys.get(0)[1]);
        assertInstanceOf(OrderFlow.EnchantLoads.class, f.next());
        // item 2 can't be made: short, its lot is gone, the bin goes on to be built
        f.enchantItemShort(u, 1, Codes.COST_MISMATCH);
        assertEquals(-1, u.nextEnchantLot());
        assertInstanceOf(OrderFlow.Build.class, f.next());
        EnchantLedger l = f.enchantLedger();
        assertEquals(1, l.doneCount());
        assertEquals(1, l.shortCount());
        // the saved plan carries the journal and the lots
        OrderFlow.Saved s = h.store.get("ord-1");
        assertNotNull(s.enchant);
        assertTrue(s.units.get(0).lotCraft.contains(Order.Lot.CRAFTED));
    }

    @Test
    void anOrderThisBotCantMakeHasEveryEnchantItemShortWithTheReasonAndTheRestGoesOn() {
        Host h = new Host();
        h.problem = Codes.MODULE_CONFLICT;
        OrderFlow f = flow(h);
        assertNull(f.offer(order(ORDER)));
        assertEquals(1, f.units().size());
        // only the item line is a lot now
        assertEquals(0, f.units().get(0).lots.stream().filter(Order.Lot::enchant).count());
        assertInstanceOf(OrderFlow.Build.class, f.next());
        EnchantLedger l = f.enchantLedger();
        assertTrue(l.refusedAtStart());
        assertEquals(2, l.shortCount());
        JsonObject r = l.toResult();
        assertEquals(Codes.MODULE_CONFLICT, r.getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void anOrderOfEnchantLinesAloneThatIsRefusedFailsWithTheReason() {
        Host h = new Host();
        h.problem = Codes.MC_MISMATCH;
        OrderFlow f = flow(h);
        String why = f.offer(order(ENCHANT_ONLY));
        assertNotNull(why);
        assertTrue(why.contains("mc_mismatch"), why);
    }

    @Test
    void beforeStepOneThePauseTimeoutStillFailsTheOrder() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        begin(f, 0);
        h.gate = false;
        f.next();
        clock.addAndGet(OrderFlow.PAUSE_TIMEOUT_MS + 1);
        f.next();
        assertEquals(OrderFlow.State.Failed, f.state());
        assertTrue(f.report().message().contains("held up for too long"));
    }

    @Test
    void pastStepOneAPauseHasNoTimeLimit() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        EnchantLedger.Item it = begin(f, 0);
        f.enchantLedger().stepDone(0, 0);
        assertTrue(f.enchantLedger().pastStepOne());
        h.gate = false;
        f.next();
        for (int i = 0; i < 5; i++) {
            clock.addAndGet(OrderFlow.PAUSE_TIMEOUT_MS * 3);
            assertInstanceOf(OrderFlow.Idle.class, f.next());
            OrderFlow.Report r = f.report();
            assertEquals("in_progress", r.status());
            assertTrue(r.phase().startsWith("paused"), r.phase());
        }
        assertEquals(OrderFlow.State.Running, f.state());
        // the gate opens: it carries on at the step boundary
        h.gate = true;
        assertInstanceOf(OrderFlow.EnchantLoads.class, f.next());
        assertEquals(1, it.stepsDone);
    }

    @Test
    void aCancelBeforeStepOneIsImmediateAndEverythingIsReturned() {
        Host h = new Host();
        h.carried = Map.of("minecraft:diamond_sword", 1L);
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        begin(f, 0);
        assertTrue(f.cancel("ord-1"));
        assertTrue(f.cancelRequested());
        assertFalse(f.cancelQueued());
        assertInstanceOf(OrderFlow.Idle.class, f.next());
        assertEquals(OrderFlow.State.Cancelled, f.state());
        OrderFlow.Report r = f.report();
        assertEquals("cancelled", r.status());
        assertNotNull(r.enchant());
        // every item is short with "cancelled"
        for (var e : r.enchant().getAsJsonArray("short")) assertEquals(Codes.CANCELLED, e.getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void aCancelPastStepOneWaitsForTheItemAndSkipsTheRest() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        begin(f, 0);
        f.enchantLedger().stepDone(0, 0);
        assertTrue(f.cancel("ord-1"));
        // queued: nothing in the host stops the anvil work
        assertFalse(f.cancelRequested());
        assertTrue(f.cancelQueued());
        assertInstanceOf(OrderFlow.EnchantLoads.class, f.next());
        assertEquals(OrderFlow.State.Running, f.state());
        // the item finishes: the order is cancelled at the next safe point, the second item is skipped
        Unit u = f.units().get(0);
        f.enchantItemDone(u, 0, "minecraft:diamond_sword#c0ffee00", "minecraft:diamond_sword#c0ffee0011223344", Map.of("sharpness", 5));
        assertTrue(f.cancelRequested());
        assertFalse(f.cancelQueued());
        assertInstanceOf(OrderFlow.Idle.class, f.next());
        assertEquals(OrderFlow.State.Cancelled, f.state());
        EnchantLedger l = f.enchantLedger();
        assertEquals(1, l.doneCount());
        assertEquals(Codes.CANCELLED, l.item(1).reason);
        OrderFlow.Report r = f.report();
        assertEquals(1, r.enchant().getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
    }

    @Test
    void theReportWaitsBetweenStepsWhileACancelIsQueued() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        begin(f, 0);
        f.enchantLedger().stepDone(0, 0);
        assertFalse(f.holdStatus());
        f.cancel("ord-1");
        // in the middle of a step the bot does not report (the report would acknowledge a force-cancel); at a boundary it does
        f.enchantAtBoundary(false);
        assertTrue(f.holdStatus());
        f.enchantAtBoundary(true);
        assertFalse(f.holdStatus());
    }

    @Test
    void aForceCancelStopsAtTheBoundaryNotMidStepAndTheItemsAreReturned() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        begin(f, 0);
        f.enchantLedger().stepDone(0, 0);
        // the server answered a report with 409: finished or force-cancelled there
        assertTrue(f.cancelHard("ord-1"));
        assertTrue(f.forceCancelled());
        assertTrue(f.cancelRequested());
        // the trip ends at the next boundary and the item is short: the order ends cancelled, the items go back
        Unit u = f.units().get(0);
        f.enchantItemShort(u, 0, Codes.CANCELLED);
        assertInstanceOf(OrderFlow.Idle.class, f.next());
        assertEquals(OrderFlow.State.Cancelled, f.state());
        assertEquals(0, f.enchantLedger().doneCount());
    }

    @Test
    void aCancelHardOnAPlainOrderIsAnOrdinaryCancel() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order("{\"id\":\"p\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":3}]}"));
        assertTrue(f.cancelHard("p"));
        assertFalse(f.forceCancelled());
        assertTrue(f.cancelRequested());
    }

    @Test
    void theJournalCarriesOnAfterARestart() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        EnchantLedger l = f.enchantLedger();
        begin(f, 0);
        l.stepDone(0, 0);
        l.addBottles(9);
        f.enchantChanged();
        // the bot restarts: a new flow takes the order up again from the saved plan
        OrderFlow again = flow(h);
        assertNull(again.offer(order(ORDER)));
        EnchantLedger back = again.enchantLedger();
        assertNotNull(back);
        assertEquals(EnchantLedger.Status.RUNNING, back.item(0).status);
        assertEquals(1, back.item(0).stepsDone);
        assertEquals(9, back.bottlesUsed());
        assertTrue(back.pastStepOne());
        assertEquals(1, again.units().size());
        assertEquals(2, again.units().get(0).lots.stream().filter(Order.Lot::enchant).count());
        assertNotNull(h.resumedSeen);
        assertInstanceOf(OrderFlow.EnchantLoads.class, again.next());
    }

    @Test
    void aResumedOrderTheBotCantMakeNowHasWhatIsLeftShort() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        begin(f, 0);
        f.enchantChanged();
        h.problem = Codes.MODULE_CONFLICT;
        OrderFlow again = flow(h);
        assertNull(again.offer(order(ORDER)));
        assertEquals(0, again.units().get(0).lots.stream().filter(Order.Lot::enchant).count());
        assertEquals(2, again.enchantLedger().shortCount());
        assertInstanceOf(OrderFlow.Build.class, again.next());
    }

    @Test
    void aResumedItemPastStepOneIsNeverFailedByARefusalAndTheOthersAre() {
        for (String problem : List.of(Codes.MODULE_CONFLICT, Codes.MC_MISMATCH, Codes.BUSY)) {
            Host h = new Host();
            OrderFlow f = flow(h);
            f.offer(order(ORDER));
            begin(f, 0);
            f.enchantLedger().stepDone(0, 0);
            f.enchantChanged();
            h.problem = problem;
            OrderFlow again = flow(h);
            assertNull(again.offer(order(ORDER)));
            EnchantLedger l = again.enchantLedger();
            assertEquals(EnchantLedger.Status.RUNNING, l.item(0).status, problem);
            assertEquals(1, l.item(0).stepsDone);
            assertEquals(EnchantLedger.Status.SHORT, l.item(1).status, problem);
            assertEquals(problem, l.item(1).reason);
            assertEquals(1, again.units().get(0).lots.stream().filter(Order.Lot::enchant).count(), problem);
            assertInstanceOf(OrderFlow.EnchantLoads.class, again.next(), problem);
            assertTrue(l.pastStepOne());
        }
    }

    @Test
    void theBoundaryIsResetWhenTheOrderIsTakenUpOrForgotten() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        begin(f, 0);
        f.enchantLedger().stepDone(0, 0);
        f.cancel("ord-1");
        f.enchantAtBoundary(false);
        assertTrue(f.holdStatus());
        f.reset();
        f.offer(order(ORDER));
        begin(f, 0);
        f.enchantLedger().stepDone(0, 0);
        f.cancel("ord-1");
        assertFalse(f.holdStatus());
    }

    @Test
    void theResultOfAnEndedOrderHasTheEnchantObject() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        Unit u = f.units().get(0);
        f.enchantItemDone(u, 0, "minecraft:diamond_sword#c0ffee00", "minecraft:diamond_sword#c0ffee0011223344", Map.of("sharpness", 5));
        f.enchantItemShort(u, 1, Codes.TOO_EXPENSIVE);
        f.finish(OrderFlow.State.Delivered, "");
        OrderFlow.Report r = f.report();
        assertTrue(r.terminal());
        assertTrue(r.message().contains("1 item not enchanted: too_expensive"), r.message());
        OrderDriver d = new OrderDriver(f, clock::get, (level, text) -> {
        });
        JsonObject status = d.statusJson(r);
        JsonObject en = status.getAsJsonObject("result").getAsJsonObject("enchant");
        assertNotNull(en);
        assertEquals(1, en.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(2, en.getAsJsonArray("made").get(0).getAsJsonObject().get("wanted").getAsInt());
        assertEquals("too_expensive", en.getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        // the line key is requested under its own key, so delivered is counted there
        boolean line = false;
        for (var e : status.getAsJsonObject("result").getAsJsonArray("lines")) line |= e.getAsJsonObject().get("key").getAsString().equals("minecraft:diamond_sword");
        assertTrue(line);
        // a cancelled order still reports it
        OrderFlow g = flow(new Host());
        g.offer(order(ORDER));
        g.cancel("ord-1");
        g.next();
        assertTrue(new OrderDriver(g, clock::get, (a, b) -> {
        }).statusJson(g.report()).getAsJsonObject("result").has("enchant"));
    }

    @Test
    void anOrderWithoutEnchantLinesHasNoEnchantResult() {
        Host h = new Host();
        OrderFlow f = flow(h);
        f.offer(order("{\"id\":\"p\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":3}]}"));
        assertNull(f.enchantLedger());
        f.finish(OrderFlow.State.Delivered, "");
        assertNull(f.report().enchant());
        assertFalse(new OrderDriver(f, clock::get, (a, b) -> {
        }).statusJson(f.report()).getAsJsonObject("result").has("enchant"));
    }

    @Test
    void theEndOfTheOrderWatchesWhatIsCarriedAndReportsWhatWentBack() {
        Host h = new Host();
        h.still = Map.of("minecraft:diamond_sword", 1L);
        h.carried = Map.of("minecraft:diamond_sword", 1L, "minecraft:enchanted_book#a1111111", 2L);
        h.returning = false;
        OrderFlow f = flow(h);
        f.offer(order(ORDER));
        f.cancel("ord-1");
        f.next();
        OrderFlow.Report r = f.report();
        // one sword is still carried, the book went back
        JsonObject en = r.enchant();
        assertEquals(1, en.getAsJsonArray("returned").size());
        assertEquals("minecraft:enchanted_book#a1111111", en.getAsJsonArray("returned").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(2, en.getAsJsonArray("returned").get(0).getAsJsonObject().get("count").getAsInt());
    }

    // The protocol driver

    private static JsonObject json(OrderDriver.Request r) {
        return JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theClaimCarriesEnchantOrdersAndThePlayerOnlyWhenAsked() {
        Host h = new Host();
        OrderFlow f = flow(h);
        OrderDriver d = new OrderDriver(f, clock::get, (a, b) -> {
        });
        boolean[] on = {false};
        d.enchantOrdersWhen(() -> on[0]);
        clock.addAndGet(60_000);
        OrderDriver.Request off = d.next(true, null, "2b2t.org", "stash", "Bot", true);
        assertNotNull(off);
        assertEquals("/api/v1/orders/claim", off.path());
        assertFalse(json(off).has("enchantOrders"));
        assertEquals("Bot", json(off).get("player").getAsString());
        on[0] = true;
        d.reset();
        clock.addAndGet(60_000);
        OrderDriver.Request claim = d.next(true, null, "2b2t.org", "stash", "Bot", true);
        assertNotNull(claim);
        JsonObject body = json(claim);
        assertEquals(1, body.get("enchantOrders").getAsInt());
        assertEquals("Bot", body.get("player").getAsString());
        assertEquals("2b2t.org", body.get("server").getAsString());
    }

    @Test
    void aReportAnsweredWith409ForceCancelsAnEnchantOrderPastStepOne() {
        Host h = new Host();
        OrderFlow f = flow(h);
        OrderDriver d = new OrderDriver(f, clock::get, (a, b) -> {
        });
        d.next(true, null, "2b2t.org", "stash", "Bot", true);
        f.offer(order(ORDER));
        begin(f, 0);
        f.enchantLedger().stepDone(0, 0);
        clock.addAndGet(60_000);
        OrderDriver.Request status = d.next(true, null, "2b2t.org", "stash", "Bot", true);
        assertNotNull(status);
        assertTrue(status.path().endsWith("/status"));
        // the server answers 409: the order was force-cancelled (or finished) there
        assertTrue(status.reply().done(409, "{\"ok\":false}"));
        assertTrue(f.forceCancelled());
        assertTrue(f.cancelRequested());
    }

    @Test
    void whileACancelIsQueuedMidStepNoStatusIsSent() {
        Host h = new Host();
        OrderFlow f = flow(h);
        OrderDriver d = new OrderDriver(f, clock::get, (a, b) -> {
        });
        f.offer(order(ORDER));
        begin(f, 0);
        f.enchantLedger().stepDone(0, 0);
        f.cancel("ord-1");
        f.enchantAtBoundary(false);
        clock.addAndGet(60_000);
        assertNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
        f.enchantAtBoundary(true);
        assertNotNull(d.next(true, null, "2b2t.org", "stash", "Bot", true));
    }
}
