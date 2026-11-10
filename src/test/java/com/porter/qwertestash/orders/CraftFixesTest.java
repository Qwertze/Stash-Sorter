package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The review fixes: charging from what was gathered, setting aside again after a restart, limits on what an order may ask, the ledger's accuracy. */
class CraftFixesTest {
    private static final Recipes R = TestRecipes.table();
    private static final String LOG = "minecraft:spruce_log", STAIRS = "minecraft:spruce_stairs", PLANKS = "minecraft:spruce_planks";

    // M1

    @Test
    void theChargeIsWhatWasGatheredEvenAfterAnInterruptedTripWasPlannedAgain() {
        List<Order.Lot> loads = List.of(new Order.Lot(LOG, 3), new Order.Lot(LOG + "#ab12", 2));
        Map<String, Long> gathered = CraftCharge.gathered(loads);
        assertEquals(Map.of(LOG, 5L), gathered);
        // The first trip made two fills and was cut short; the plan made again from what is carried takes nothing from the stash
        CraftPlanner.Plan again = CraftPlanner.plan(R, STAIRS, 4, Map.of(PLANKS, 6L), CraftPlanner.Limits.open());
        assertTrue(again.ok());
        assertTrue(again.fromStock().keySet().stream().noneMatch(k -> k.equals(LOG)), "the replan takes no logs");
        assertEquals(Map.of(LOG, 5L), CraftCharge.charge(gathered, 2, Map.of()));
        // Nothing made: nothing charged, everything goes back
        assertTrue(CraftCharge.charge(gathered, 0, Map.of()).isEmpty());
        // What was put back is not charged
        assertEquals(Map.of(LOG, 3L), CraftCharge.charge(gathered, 2, Map.of(LOG, 2L)));
        assertTrue(CraftCharge.charge(gathered, 2, Map.of(LOG, 9L)).isEmpty());
    }

    // M2

    @Test
    void equalSizedLoadsGetDifferentStacksAndTheSurplusIsMatchedUpToItsCounts() {
        List<CraftResume.Stack> carried = List.of(new CraftResume.Stack(3, STAIRS, 8), new CraftResume.Stack(5, STAIRS, 8), new CraftResume.Stack(7, PLANKS, 3),
            new CraftResume.Stack(9, PLANKS, 2), new CraftResume.Stack(11, "minecraft:stick", 2));
        CraftResume.Result r = CraftResume.remark(List.of(new CraftResume.Load(STAIRS, 8), new CraftResume.Load(STAIRS, 8)), Map.of(PLANKS, 5L, "minecraft:stick", 2L), carried);
        assertEquals(List.of(3, 5), r.lotSlots());
        assertEquals(Map.of(7, PLANKS, 9, PLANKS, 11, "minecraft:stick"), r.surplusSlots());
        assertTrue(r.refund().isEmpty());
    }

    @Test
    void surplusThatIsNotCarriedAnyMoreIsRefunded() {
        CraftResume.Result r = CraftResume.remark(List.of(new CraftResume.Load(STAIRS, 8)), Map.of(PLANKS, 6L), List.of(new CraftResume.Stack(2, PLANKS, 4)));
        assertEquals(List.of(-1), r.lotSlots());
        assertEquals(Map.of(2, PLANKS), r.surplusSlots());
        assertEquals(Map.of(PLANKS, 2L), r.refund());
        // A stack used for a load is not also surplus
        CraftResume.Result both = CraftResume.remark(List.of(new CraftResume.Load(PLANKS, 4)), Map.of(PLANKS, 4L), List.of(new CraftResume.Stack(2, PLANKS, 4)));
        assertEquals(List.of(2), both.lotSlots());
        assertEquals(Map.of(PLANKS, 4L), both.refund());
    }

    private static CraftLedger ledger(Map<String, Long> take, String key, int n) {
        List<Order.Qty> t = new ArrayList<>();
        take.forEach((k, v) -> t.add(new Order.Qty(k, (int) (long) v)));
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(key, n)), List.of(), t, List.of(), 0, false);
        return new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, key, n)), R, 2048);
    }

    @Test
    void theSavedLedgerKeepsTheSurplusAndAResumeRefundsAndClampsTheBudget() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.madeChunk(STAIRS, 6, 5, Map.of(LOG, 3L));
        l.setSurplus(Map.of(PLANKS, 3L));
        JsonObject saved = l.toSaved();
        CraftLedger back = ledger(Map.of(LOG, 10L), STAIRS, 8);
        back.restore(saved);
        assertEquals(Map.of(PLANKS, 3L), back.savedSurplus());
        assertEquals(7L, back.budget().get(LOG));
        back.refund(Map.of(LOG, 2L));
        assertEquals(9L, back.budget().get(LOG));
        back.refund(Map.of(LOG, 50L));
        assertEquals(10L, back.budget().get(LOG), "never more than the take");
        // A record with a budget over the take, or for an id outside it, is clamped
        JsonObject tampered = saved.deepCopy();
        tampered.getAsJsonObject("budget").addProperty(LOG, 99);
        tampered.getAsJsonObject("budget").addProperty("minecraft:diamond", 5);
        CraftLedger clamped = ledger(Map.of(LOG, 10L), STAIRS, 8);
        clamped.restore(tampered);
        assertEquals(10L, clamped.budget().get(LOG));
        assertFalse(clamped.budget().containsKey("minecraft:diamond"));
    }

    @Test
    void aRestartBetweenConsumingSurplusAndRecordingTheChunkRefundsNothingItUsed() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.setSurplus(Map.of(PLANKS, 3L));
        // The chunk begins (it will consume the planks) and the ledger is saved; the bot then restarts with the planks gone
        l.chunkStarted();
        CraftLedger back = ledger(Map.of(LOG, 10L), STAIRS, 8);
        back.restore(l.toSaved());
        assertTrue(back.savedSurplus().isEmpty());
        CraftResume.Result r = CraftResume.remark(List.of(), back.savedSurplus(), List.of());
        assertTrue(r.refund().isEmpty());
        // Once the chunk is settled the surplus is what is really carried
        l.setSurplus(Map.of(PLANKS, 1L));
        CraftLedger after = ledger(Map.of(LOG, 10L), STAIRS, 8);
        after.restore(l.toSaved());
        assertEquals(Map.of(PLANKS, 1L), after.savedSurplus());
    }

    @Test
    void aStackBiggerThanTheSurplusLeftIsNotMarkedAndNothingIsRefundedForIt() {
        CraftResume.Result r = CraftResume.remark(List.of(), Map.of(PLANKS, 3L), List.of(new CraftResume.Stack(4, PLANKS, 4)));
        assertTrue(r.surplusSlots().isEmpty());
        assertTrue(r.refund().isEmpty());
        // Exact, or several smaller ones, are marked
        assertEquals(Map.of(4, PLANKS), CraftResume.remark(List.of(), Map.of(PLANKS, 4L), List.of(new CraftResume.Stack(4, PLANKS, 4))).surplusSlots());
        assertEquals(2, CraftResume.remark(List.of(), Map.of(PLANKS, 4L), List.of(new CraftResume.Stack(1, PLANKS, 3), new CraftResume.Stack(2, PLANKS, 1))).surplusSlots().size());
        // Marking stops at the count: the third stack is not taken
        CraftResume.Result stop = CraftResume.remark(List.of(), Map.of(PLANKS, 4L), List.of(new CraftResume.Stack(1, PLANKS, 3), new CraftResume.Stack(2, PLANKS, 1), new CraftResume.Stack(3, PLANKS, 2)));
        assertEquals(2, stop.surplusSlots().size());
        assertFalse(stop.surplusSlots().containsKey(3));
    }

    @Test
    void aTableIsOutOfBoundsWithoutAnAreaOrADimensionOrOutsideThem() {
        com.porter.qwertestash.stash.CraftSettings.Table t = new com.porter.qwertestash.stash.CraftSettings.Table(10, 64, 10, "minecraft:overworld");
        int[] area = {0, 60, 0, 20, 70, 20};
        assertFalse(com.porter.qwertestash.stash.CraftBounds.outOfBounds("minecraft:overworld", area, t, 64));
        assertTrue(com.porter.qwertestash.stash.CraftBounds.outOfBounds("minecraft:overworld", null, t, 64), "no area: no limit, so refused");
        assertTrue(com.porter.qwertestash.stash.CraftBounds.outOfBounds(null, area, t, 64), "no recorded dimension");
        assertTrue(com.porter.qwertestash.stash.CraftBounds.outOfBounds("minecraft:the_nether", area, t, 64));
        assertTrue(com.porter.qwertestash.stash.CraftBounds.outOfBounds("minecraft:overworld", area, new com.porter.qwertestash.stash.CraftSettings.Table(200, 64, 10, "minecraft:overworld"), 64));
        assertFalse(com.porter.qwertestash.stash.CraftBounds.outOfBounds("minecraft:overworld", area, new com.porter.qwertestash.stash.CraftSettings.Table(84, 64, 10, "minecraft:overworld"), 64));
    }

    // L3, L4

    @Test
    void shortIsNeverMoreThanWantedMinusMade() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.madeChunk(STAIRS, 6, 5, Map.of(LOG, 3L));
        l.addShort(STAIRS, 5, CraftOrder.TIMEOUT);
        l.addShort(STAIRS, 5, CraftOrder.CAP);
        assertEquals(2, l.shortTotal());
        assertEquals("2 item(s) not crafted: timeout", l.note());
    }

    @Test
    void craftedItemsThatDidNotReachAShulkerAreNoted() {
        CraftLedger l = ledger(Map.of(LOG, 10L), STAIRS, 8);
        l.madeChunk(STAIRS, 8, 5, Map.of(LOG, 3L));
        l.setDelivered(Map.of(STAIRS, 3), Map.of(STAIRS, 8));
        assertEquals("5 crafted item(s) not delivered", l.note());
        l.setDelivered(Map.of(STAIRS, 8), Map.of(STAIRS, 8));
        assertNull(l.note());
        // Item lines of the same key are delivered first: 4 asked from the stash, 8 crafted, 12 delivered is everything
        CraftLedger mixed = ledger(Map.of(LOG, 10L), STAIRS, 8);
        mixed.madeChunk(STAIRS, 8, 5, Map.of(LOG, 3L));
        mixed.setDelivered(Map.of(STAIRS, 12), Map.of(STAIRS, 12));
        assertNull(mixed.note());
    }

    // C1

    private static Order.Craft block(int lineCount, int stepFills) {
        Order.CraftStep step = new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, stepFills, List.of(new Order.Qty(PLANKS, 6)));
        return new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(STAIRS, lineCount)), List.of(step), List.of(new Order.Qty(PLANKS, 6)), List.of(), stepFills, false);
    }

    @Test
    void aCraftLineMustBeCoveredByItsStepsAndWithinTheLimits() {
        assertNull(CraftOrder.check(block(8, 2), R, 2048));
        assertNotNull(CraftOrder.check(block(9, 2), R, 2048), "2 fills of 4 make 8, not 9");
        assertNotNull(CraftOrder.check(block(100_001, 30_000), R, 100_000), "a line over 100,000");
        // Made by the steps or taken from the stash as itself
        Order.Craft taken = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(PLANKS, 4)), List.of(), List.of(new Order.Qty(PLANKS, 4)), List.of(), 0, false);
        assertNull(CraftOrder.check(taken, R, 10));
        // The lines together
        List<Order.Qty> lines = new ArrayList<>();
        for (int i = 0; i < 11; i++) lines.add(new Order.Qty("minecraft:item_" + i, 100_000));
        List<Order.Qty> take = new ArrayList<>();
        for (int i = 0; i < 11; i++) take.add(new Order.Qty("minecraft:item_" + i, 100_000));
        Order.Craft many = new Order.Craft(1, "1.21.4", R.hash(), lines, List.of(), take, List.of(), 0, false);
        assertTrue(CraftOrder.check(many, R, 10).contains("in all"));
    }

    @Test
    void anOrderLineOverTheCapIsNotRead() {
        JsonObject o = com.google.gson.JsonParser.parseString("{\"id\":\"o-1\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:dirt\",\"count\":2000000000},"
            + "{\"type\":\"item\",\"key\":\"minecraft:stone\",\"count\":64}]}").getAsJsonObject();
        Order parsed = Order.parse(o);
        assertNotNull(parsed);
        assertEquals(1, parsed.lines.size());
        assertEquals("minecraft:stone", parsed.lines.get(0).key());
    }

    @Test
    void moreThan4096LoadsAreRefusedBeforeTheyAreMade() {
        OrderSim w = new OrderSim().craftOrders(R);
        w.craftStack.put(STAIRS, 1);
        w.empties.put("minecraft:red_shulker_box", 5);
        // 5000 stairs at one a stack: 5000 loads
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty(STAIRS, 5000)),
            List.of(new Order.CraftStep("minecraft:spruce_stairs", STAIRS, 4, 1250, List.of(new Order.Qty(PLANKS, 6)))), List.of(new Order.Qty(PLANKS, 7500)), List.of(), 1250, false);
        String why = w.flow.offer(new Order("o-lots", List.of(new Order.Line(Order.CRAFT, STAIRS, 5000)), null, false, c));
        assertNotNull(why);
        assertTrue(why.contains("recipe_mismatch"), why);
    }

    @Test
    void afterTheWaitTheReportCountsOnlyWhatWentBack() {
        OrderSim w = new OrderSim().stock("minecraft:diamond", "v", 1000, 64).craftOrders(R);
        w.craftStash.put(LOG, 10L);
        w.empties.put("minecraft:red_shulker_box", 5);
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty("minecraft:spruce_slab", 6)),
            List.of(new Order.CraftStep("minecraft:spruce_planks", PLANKS, 4, 1, List.of(new Order.Qty(LOG, 1))),
                new Order.CraftStep("minecraft:spruce_slab", "minecraft:spruce_slab", 6, 1, List.of(new Order.Qty(PLANKS, 3)))),
            List.of(new Order.Qty(LOG, 1)), List.of(new Order.Qty(PLANKS, 1)), 2, false);
        assertNull(w.flow.offer(new Order("o-late", List.of(new Order.Line(Order.CRAFT, "minecraft:spruce_slab", 6)), null, false, c)));
        w.stillCarried.put(PLANKS, 1L);
        w.run(30);
        w.now += OrderFlow.RETURN_WAIT_MS + 1;
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(0, r.craft().getAsJsonArray("returned").size(), "the planks never went back");
    }

    // The final report waits for the put-back

    @Test
    void theFinalReportWaitsForTheCraftedLeftoversToGoBackAndCountsWhatDid() {
        OrderSim w = new OrderSim().stock("minecraft:diamond", "v", 1000, 64).craftOrders(R);
        w.craftStash.put(LOG, 10L);
        w.empties.put("minecraft:red_shulker_box", 5);
        Order.Craft c = new Order.Craft(1, "1.21.4", R.hash(), List.of(new Order.Qty("minecraft:spruce_slab", 6)),
            List.of(new Order.CraftStep("minecraft:spruce_planks", PLANKS, 4, 1, List.of(new Order.Qty(LOG, 1))),
                new Order.CraftStep("minecraft:spruce_slab", "minecraft:spruce_slab", 6, 1, List.of(new Order.Qty(PLANKS, 3)))),
            List.of(new Order.Qty(LOG, 1)), List.of(new Order.Qty(PLANKS, 1)), 2, false);
        assertNull(w.flow.offer(new Order("o-wait", List.of(new Order.Line(Order.CRAFT, "minecraft:spruce_slab", 6)), null, false, c)));
        // The planks left over are still carried when the order ends
        w.stillCarried.put(PLANKS, 1L);
        w.run(30);
        assertFalse(w.flow.running());
        OrderFlow.Report waiting = w.flow.report();
        assertEquals("in_progress", waiting.status());
        assertEquals("putting crafted items back", waiting.phase());
        assertFalse(waiting.terminal());
        // Put back: reported at once, with what went back
        w.stillCarried.clear();
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status());
        assertEquals(PLANKS, r.craft().getAsJsonArray("returned").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1, r.craft().getAsJsonArray("returned").get(0).getAsJsonObject().get("count").getAsInt());
    }
}
