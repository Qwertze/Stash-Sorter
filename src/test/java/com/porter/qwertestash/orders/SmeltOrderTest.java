package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smelting in craft blocks (CRAFT-CONTRACT-v1.2.md 9, 11, 12 and 14, B2): the check, the reading, the sub-plan with fuel, the ledger and the preflight. */
class SmeltOrderTest {
    private static final Recipes R = TestRecipes.table();
    private static final Smelting SM = FurnaceTripTest.SM;
    private static final String SAND = "minecraft:sand", GLASS = "minecraft:glass", COAL = "minecraft:coal", LOG = "minecraft:oak_log", CHARCOAL = "minecraft:charcoal";
    private static final String EXTRAS = FurnaceTripTest.extras().hash();
    private static final Set<String> CAN = Set.of("smelt");

    private static Order.Qty q(String id, int n) {
        return new Order.Qty(id, n);
    }

    private static Order.Craft glassBlock(int items, int coal, int smelt) {
        Order.CraftStep step = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, items, List.of(q(SAND, items)), true, List.of(q(COAL, coal)));
        return new Order.Craft(2, "1.21.4", R.hash(), List.of(q(GLASS, items)), List.of(step), List.of(q(SAND, items), q(COAL, coal)), List.of(), 0, false, List.of(), 12, EXTRAS,
            List.of("smelt"), null, smelt);
    }

    // The check (11.1)

    @Test
    void aFurnaceStepPassesWhenTheBotCanSmeltAndTheNumbersAddUp() {
        Order.Craft c = glassBlock(10, 2, 10);
        assertNull(CraftOrder.check(c, R, 2048, EXTRAS, CAN, SM, 256));
        // Several steps of one recipe (a batch each) are fine too
        Order.CraftStep a = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 64, List.of(q(SAND, 64)), true, List.of(q(COAL, 8)));
        Order.CraftStep b = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 6, List.of(q(SAND, 6)), true, List.of(q(COAL, 1)));
        Order.Craft two = new Order.Craft(2, "1.21.4", R.hash(), List.of(q(GLASS, 70)), List.of(a, b), List.of(q(SAND, 70), q(COAL, 9)), List.of(), 0, false, List.of(), 12, EXTRAS,
            List.of("smelt"), null, 70);
        assertNull(CraftOrder.check(two, R, 2048, EXTRAS, CAN, SM, 256));
    }

    @Test
    void aFurnaceStepFailsTheCheckForEveryWrongThing() {
        Order.Craft c = glassBlock(10, 2, 10);
        assertNotNull(CraftOrder.check(c, R, 2048, EXTRAS, Set.of(), SM, 256), "the bot can't smelt");
        assertNotNull(CraftOrder.check(c, R, 2048, EXTRAS, CAN, null, 256), "no smelting tables");
        assertNotNull(CraftOrder.check(c, R, 2048, EXTRAS, CAN, SM, 8), "more than maxSmelt");
        assertNotNull(CraftOrder.check(glassBlock(10, 1, 10), R, 2048, EXTRAS, CAN, SM, 256), "1 coal burns 8 items, not 10");
        assertNotNull(CraftOrder.check(glassBlock(10, 2, 3), R, 2048, EXTRAS, CAN, SM, 256), "smelt says fewer than the steps smelt");
        assertNull(CraftOrder.check(glassBlock(10, 2, 12), R, 2048, EXTRAS, CAN, SM, 256), "smelt may count more (charcoal made as fuel is counted by the server)");
        assertNotNull(CraftOrder.check(glassBlock(257, 33, 257), R, 2048, EXTRAS, CAN, SM, 256), "the cap is 256");
        Order.CraftStep other = new Order.CraftStep("qwerte:smelt/glass", "minecraft:brick", 1, 10, List.of(q(SAND, 10)), true, List.of(q(COAL, 2)));
        assertNotNull(CraftOrder.check(withStep(c, other), R, 2048, EXTRAS, CAN, SM, 256), "makes something else");
        Order.CraftStep wrongIn = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 10, List.of(q("minecraft:clay_ball", 10)), true, List.of(q(COAL, 2)));
        assertNotNull(CraftOrder.check(withStep(c, wrongIn), R, 2048, EXTRAS, CAN, SM, 256), "an input the recipe doesn't take");
        Order.CraftStep fewIn = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 10, List.of(q(SAND, 9)), true, List.of(q(COAL, 2)));
        assertNotNull(CraftOrder.check(withStep(c, fewIn), R, 2048, EXTRAS, CAN, SM, 256), "inputs and items disagree");
        Order.CraftStep noFuel = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 10, List.of(q(SAND, 10)), true, List.of());
        assertNotNull(CraftOrder.check(withStep(c, noFuel), R, 2048, EXTRAS, CAN, SM, 256), "no fuel");
        Order.CraftStep notFuel = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 10, List.of(q(SAND, 10)), true, List.of(q("minecraft:stone", 40)));
        assertNotNull(CraftOrder.check(withStep(c, notFuel), R, 2048, EXTRAS, CAN, SM, 256), "not a fuel");
        Order.CraftStep unknown = new Order.CraftStep("qwerte:smelt/nope", GLASS, 1, 10, List.of(q(SAND, 10)), true, List.of(q(COAL, 2)));
        assertNotNull(CraftOrder.check(withStep(c, unknown), R, 2048, EXTRAS, CAN, SM, 256), "a recipe the extras don't have");
    }

    private static Order.Craft withStep(Order.Craft c, Order.CraftStep s) {
        return new Order.Craft(c.v(), c.mc(), c.recipes(), c.lines(), List.of(s), c.take(), c.leftovers(), c.fills(), false, c.forEnchant(), c.maxDepth(), c.extras(), c.needs(), null, c.smelt());
    }

    @Test
    void theOldCheckOfAnEmptyBotStillRefusesAFurnaceStep() {
        // The 5-argument form is what a bot with no smelting says
        assertNotNull(CraftOrder.check(glassBlock(10, 2, 10), R, 2048, EXTRAS, CAN));
        assertNotNull(CraftOrder.check(glassBlock(10, 2, 10), R, 2048, EXTRAS, Set.of()));
    }

    @Test
    void gridFillsCountTableStepsOnly() {
        Order.Craft c = glassBlock(10, 2, 10);
        assertEquals(0, c.stepFills());
        assertEquals(10, c.smeltItems());
        assertTrue(c.hasFurnace());
        // A bot that makes no fills at all can still smelt (the furnace steps are items, not grid fills)
        assertNull(CraftOrder.check(c, R, 1, EXTRAS, CAN, SM, 256));
    }

    // The block as the server sends it (9)

    private static JsonObject blockJson() {
        return JsonParser.parseString("""
            { "v": 2, "mc": "1.21.4", "recipes": "%s", "extras": "%s", "maxDepth": 12, "needs": ["smelt"],
              "lines": [ { "key": "minecraft:glass", "count": 10, "spec": null, "orCraft": true } ],
              "steps": [ { "recipe": "qwerte:smelt/glass", "station": "furnace", "result": "minecraft:glass", "count": 1, "fills": 10,
                           "inputs": [ { "id": "minecraft:sand", "count": 10 } ], "fuel": [ { "id": "minecraft:coal", "count": 2 } ] } ],
              "take": [ { "id": "minecraft:sand", "count": 10 }, { "id": "minecraft:coal", "count": 2 } ],
              "leftovers": [], "fills": 0, "smelt": 10, "forEnchant": [] }
            """.formatted(R.hash(), EXTRAS)).getAsJsonObject();
    }

    @Test
    void aFurnaceBlockIsReadAndNothingOfItIsReservedAnyMore() {
        Order.Craft c = Order.parseCraft(blockJson());
        assertNotNull(c);
        assertFalse(c.malformed());
        assertNull(c.reserved(), "B2 carries out furnace stations, fuel and smelt");
        assertEquals(10, c.smelt());
        Order.CraftStep s = c.steps().get(0);
        assertTrue(s.furnace());
        assertEquals(List.of(q(COAL, 2)), s.fuel());
        assertNull(CraftOrder.check(c, R, 2048, EXTRAS, CAN, SM, 256));
        // A table step without a station stays a table step
        JsonObject b = blockJson();
        b.getAsJsonArray("steps").get(0).getAsJsonObject().remove("station");
        assertFalse(Order.parseCraft(b).steps().get(0).furnace());
    }

    @Test
    void aStationThatIsNeitherIsUnreadableAndLaterFieldsAreReadNotReserved() {
        JsonObject b = blockJson();
        b.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("station", "stonecutter");
        assertTrue(Order.parseCraft(b).malformed());
        JsonObject spec = blockJson();
        spec.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("spec", "rocket:1");
        // B3 reads a spec on a step; the check refuses it for a bot that doesn't say firework. B4 reads a remainder too (judged by the check)
        assertNull(Order.parseCraft(spec).reserved());
        assertTrue(Order.parseCraft(spec).usesSpecs());
        JsonObject remainder = blockJson();
        remainder.getAsJsonArray("steps").get(0).getAsJsonObject().add("remainder", com.google.gson.JsonParser.parseString("[ { \"id\": \"minecraft:bowl\", \"count\": 1 } ]"));
        assertNull(Order.parseCraft(remainder).reserved());
        assertEquals(1, Order.parseCraft(remainder).steps().get(0).remainder().size());
        JsonObject neg = blockJson();
        neg.addProperty("smelt", -1);
        assertTrue(Order.parseCraft(neg).malformed());
        JsonObject text = blockJson();
        text.addProperty("smelt", "ten");
        assertTrue(Order.parseCraft(text).malformed());
    }

    @Test
    void aV1BlockIgnoresFurnaceFieldsAndIsCheckedAsBefore() {
        JsonObject b = blockJson();
        b.addProperty("v", 1);
        for (String f : new String[] {"maxDepth", "extras", "needs"}) b.remove(f);
        Order.Craft c = Order.parseCraft(b);
        assertFalse(c.malformed());
        assertFalse(c.steps().get(0).furnace(), "a v1 block has no stations");
        assertEquals(0, c.smelt());
        // Its recipe is not in the table: a v1 block can't smelt, as before
        assertNotNull(CraftOrder.check(c, R, 2048, null, CAN, SM, 256));
    }

    @Test
    void oneInputKindAtMost64ItemsAndAFuelWithinItsStackPerStep() {
        Order.Craft c = glassBlock(10, 2, 10);
        Order.CraftStep mixed = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 10, List.of(q(SAND, 6), q("minecraft:red_sand", 4)), true, List.of(q(COAL, 2)));
        assertNotNull(CraftOrder.check(withStep(c, mixed), R, 2048, EXTRAS, CAN, SM, 256), "mixed input kinds are separate steps");
        Order.CraftStep big = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 65, List.of(q(SAND, 65)), true, List.of(q(COAL, 9)));
        assertNotNull(CraftOrder.check(withStep(glassBlock(65, 9, 65), big), R, 2048, EXTRAS, CAN, SM, 256), "more than 64 items in one step");
        Order.CraftStep stack = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 64, List.of(q(SAND, 64)), true, List.of(q("minecraft:stick", 128)));
        assertNotNull(CraftOrder.check(withStep(glassBlock(64, 9, 64), stack), R, 2048, EXTRAS, CAN, SM, 256), "128 sticks are more than a stack");
        Order.CraftStep two = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 10, List.of(q(SAND, 10)), true, List.of(q(COAL, 1), q("minecraft:charcoal", 1)));
        assertNotNull(CraftOrder.check(withStep(c, two), R, 2048, EXTRAS, CAN, SM, 256), "one fuel kind per step");
        Order.CraftStep ok64 = new Order.CraftStep("qwerte:smelt/glass", GLASS, 1, 64, List.of(q(SAND, 64)), true, List.of(q(COAL, 8)));
        assertNull(CraftOrder.check(withStep(glassBlock(64, 8, 64), ok64), R, 2048, EXTRAS, CAN, SM, 256));
    }

    @Test
    void aPlanMakesOneStepPerBatchAndNeverMergesFurnaceSteps() {
        // 100 glass from sand and red sand: batches of at most 64 per input kind, each its own step with its own fuel; the same recipe appears more than once
        CraftSub.Outcome o = session(Map.of(SAND, 70L, "minecraft:red_sand", 30L, COAL, 20L), "qwerte:smelt/glass").plan(GLASS, 100);
        assertTrue(o.ok(), o.plan().refusal());
        List<CraftPlanner.Step> steps = o.plan().steps();
        assertTrue(steps.size() >= 3, "64 + 6 sand and 30 red sand at least: " + steps.size());
        for (CraftPlanner.Step s : steps) {
            assertTrue(s.furnace());
            assertEquals(1, s.uses().size());
            assertTrue(s.fills() <= 64);
            assertEquals(1, s.fuel().size());
        }
        assertEquals(100, steps.stream().mapToInt(CraftPlanner.Step::fills).sum());
    }

    @Test
    void coalClassFuelComesBeforeWoodAndWhatCoversTheBatchBeforeWhatMustBeMade() {
        // Plenty of logs and a little coal that covers 8 items (1 coal): the coal is used though the logs are more plentiful
        CraftSub.Outcome o = session(Map.of(SAND, 8L, COAL, 1L, LOG, 50L), "qwerte:smelt/glass", "qwerte:smelt/charcoal").plan(GLASS, 8);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of(COAL, 1L), o.plan().steps().get(0).fuel());
        // No coal, logs that cover the batch: the logs, not charcoal made from them
        CraftSub.Outcome w = session(Map.of(SAND, 8L, LOG, 50L), "qwerte:smelt/glass", "qwerte:smelt/charcoal").plan(GLASS, 8);
        assertTrue(w.ok(), w.plan().refusal());
        assertEquals(1, w.plan().steps().size());
        assertEquals(Map.of(LOG, 6L), w.plan().steps().get(0).fuel());
    }

    @Test
    void aFuelWhoseBatchNeedIsMoreThanAStackIsSkipped() {
        // 64 items with sticks (100 ticks each) would need 128 sticks: more than a stack, so not used; the planks (300 ticks, 43 needed) are
        CraftSub.Outcome o = session(Map.of(SAND, 64L, "minecraft:stick", 500L, "minecraft:oak_planks", 100L), "qwerte:smelt/glass").plan(GLASS, 64);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(Map.of("minecraft:oak_planks", 43L), o.plan().steps().get(0).fuel());
        // With only sticks it can not be made at all
        assertFalse(session(Map.of(SAND, 64L, "minecraft:stick", 500L), "qwerte:smelt/glass").plan(GLASS, 64).ok());
    }

    // The sub-plan with fuel (7, 14)

    private static CraftSub.Session session(Map<String, Long> pool, String... allowed) {
        return new CraftSub.Session(R, Set.of(allowed), pool, pool, Map.of(), CraftSub.Limits.v2(12), SM);
    }

    @Test
    void aSmeltStepBurnsFuelOfOneKindAndCountsItsItemsApartFromGridFills() {
        CraftSub.Outcome o = session(Map.of(SAND, 10L, COAL, 5L), "qwerte:smelt/glass").plan(GLASS, 10);
        assertTrue(o.ok(), o.plan().refusal());
        CraftPlanner.Plan p = o.plan();
        assertEquals(1, p.steps().size());
        CraftPlanner.Step s = p.steps().get(0);
        assertTrue(s.furnace());
        assertEquals(10, s.fills());
        assertEquals(Map.of(SAND, 10L), s.uses());
        assertEquals(Map.of(COAL, 2L), s.fuel());
        assertEquals(Map.of(SAND, 10L, COAL, 2L), p.fromStock());
        assertEquals(0, p.fills());
        assertEquals(10, CraftPlanner.smeltItems(p.steps()));
    }

    @Test
    void withoutFuelTheSmeltIsRefused() {
        CraftSub.Outcome o = session(Map.of(SAND, 10L), "qwerte:smelt/glass").plan(GLASS, 10);
        assertFalse(o.ok());
        // And without the smelt recipe among the allowed ones the item still "needs a furnace" in the table's words
        assertFalse(session(Map.of(SAND, 10L, COAL, 5L)).plan(GLASS, 10).ok());
    }

    @Test
    void charcoalMadeEarlierInThePlanFuelsTheStepAfterIt() {
        // 8 glass need 1600 ticks: 4 logs burn 1200 only, so the fuel is charcoal smelted from a log (itself fuelled by a log)
        CraftSub.Outcome o = session(Map.of(SAND, 8L, LOG, 4L), "qwerte:smelt/glass", "qwerte:smelt/charcoal").plan(GLASS, 8);
        assertTrue(o.ok(), o.plan().refusal());
        List<CraftPlanner.Step> steps = o.plan().steps();
        assertEquals(2, steps.size());
        assertEquals("qwerte:smelt/charcoal", steps.get(0).recipe().id(), "the fuel is made first");
        assertEquals("qwerte:smelt/glass", steps.get(1).recipe().id());
        assertEquals(Map.of(CHARCOAL, 1L), steps.get(1).fuel());
        assertEquals(Map.of(LOG, 2L, SAND, 8L), o.plan().fromStock());
        assertEquals(10, CraftPlanner.smeltItems(steps) + 1, "9 items smelted in all");
        // The runs of the plan: the furnace only, one run
        assertEquals(1, CraftSegments.split(o.plan()).size());
    }

    @Test
    void aPlanIsCutIntoRunsByStation() {
        Recipes.Recipe table = R.byId("minecraft:spruce_planks");
        assertNotNull(table);
        CraftPlanner.Step smelt = new CraftPlanner.Step(SM.asRecipe("qwerte:smelt/glass"), 4, 0, Map.of(SAND, 4L), Map.of(COAL, 1L));
        CraftPlanner.Step craft = new CraftPlanner.Step(table, 1, 1, Map.of("minecraft:spruce_log", 1L));
        CraftPlanner.Step smelt2 = new CraftPlanner.Step(SM.asRecipe("qwerte:smelt/glass"), 2, 2, Map.of(SAND, 2L), Map.of(COAL, 1L));
        CraftPlanner.Plan plan = new CraftPlanner.Plan(GLASS, 6, List.of(smelt, craft, smelt2), Map.of(), 1, null);
        List<CraftSegments.Segment> segs = CraftSegments.split(plan);
        assertEquals(3, segs.size());
        assertTrue(segs.get(0).furnace());
        assertFalse(segs.get(1).furnace());
        assertEquals("", segs.get(0).plan().item(), "only the last run names the item");
        assertEquals(GLASS, segs.get(2).plan().item());
        assertEquals(1, segs.get(1).plan().fills());
        // A table-only plan is one run, unchanged
        CraftPlanner.Plan only = new CraftPlanner.Plan("minecraft:spruce_planks", 4, List.of(craft), Map.of(), 1, null);
        assertEquals(1, CraftSegments.split(only).size());
        assertEquals("minecraft:spruce_planks", CraftSegments.split(only).get(0).plan().item());
    }

    // The ledger (12) and the journal in it

    @Test
    void theResultSaysWhatWasSmeltedAndHowTheFurnaceWasHad_andNothingOfItForABlockWithoutAFurnace() {
        Order.Craft c = glassBlock(10, 2, 10);
        CraftLedger l = new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, GLASS, 10)), R, 2048);
        l.useSmelting(SM, 256);
        JsonObject before = l.toResult();
        JsonObject s0 = before.getAsJsonArray("smelted").get(0).getAsJsonObject();
        assertEquals(GLASS, s0.get("id").getAsString());
        assertEquals(10, s0.get("wanted").getAsInt());
        assertEquals(0, s0.get("made").getAsInt());
        assertTrue(before.get("furnace").isJsonNull(), "no furnace used yet");
        l.setFurnaceSource("placed");
        l.setFurnaceSource("found");
        l.addSmelted(Map.of(GLASS, 7L));
        JsonObject r = l.toResult();
        assertEquals(7, r.getAsJsonArray("smelted").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals("placed", r.getAsJsonObject("furnace").get("source").getAsString());
        assertEquals(1, r.getAsJsonObject("furnace").size(), "no position");
        assertEquals(249, l.smeltLeft());
        // A block with no furnace step: neither field
        Order.Craft plain = new Order.Craft(2, "1.21.4", R.hash(), List.of(), List.of(), List.of(), List.of(), 0, false, List.of(), 12, null, List.of(), null);
        CraftLedger none = new CraftLedger(plain, List.of(), R, 2048);
        assertFalse(none.toResult().has("smelted"));
        assertFalse(none.toResult().has("furnace"));
        assertEquals(0, none.smeltLeft());
    }

    @Test
    void theJournalIsSavedWithTheLedgerAndComesBackAfterARestartMidBatch() {
        Order.Craft c = glassBlock(20, 3, 20);
        List<Order.Line> lines = List.of(new Order.Line(Order.CRAFT, GLASS, 20));
        CraftLedger l = new CraftLedger(c, lines, R, 2048);
        l.useSmelting(SM, 256);
        l.journal().open();
        l.journal().placed(COAL, 3);
        l.journal().placed(SAND, 20);
        l.journal().loaded(new SmeltJournal.Active("qwerte:smelt/glass", SAND, 20, GLASS, 20, 0, COAL));
        l.journal().collected(GLASS, 6);
        l.addSmelted(Map.of(GLASS, 2L));
        l.setFurnaceSource("crafted");
        JsonObject saved = l.toSaved();
        assertTrue(saved.has("smelt"));

        CraftLedger again = new CraftLedger(c, lines, R, 2048);
        again.useSmelting(SM, 256);
        assertFalse(again.journal().isOpen());
        again.restore(JsonParser.parseString(saved.toString()).getAsJsonObject());
        assertTrue(again.journal().isOpen());
        assertEquals(Map.of(COAL, 3L, SAND, 20L), again.journal().placed());
        assertEquals(Map.of(GLASS, 6L), again.journal().produced());
        assertEquals(6, again.journal().active().collected());
        assertEquals(2, again.smeltMade(GLASS));
        assertEquals("crafted", again.furnaceSource());
        // A journal that was closed is not saved
        again.journal().close();
        assertFalse(again.toSaved().has("smelt"));
        // A ledger saved before B2 restores as it did
        JsonObject old = again.toSaved();
        old.remove("smeltMade");
        old.remove("furnace");
        CraftLedger oldRestore = new CraftLedger(c, lines, R, 2048);
        oldRestore.restore(old);
        assertFalse(oldRestore.journal().isOpen());
    }

    @Test
    void whatTheFurnaceTookIsChargedToTheBudgetOnceWhateverRoute() {
        Order.Craft c = glassBlock(20, 3, 20);
        CraftLedger l = new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, GLASS, 20)), R, 2048);
        assertEquals(20L, l.budget().get(SAND));
        l.charge(Map.of(SAND, 12L, COAL, 2L));
        assertEquals(8L, l.budget().get(SAND));
        assertEquals(1L, l.budget().get(COAL));
        l.charge(Map.of(SAND, 100L));
        assertNull(l.budget().get(SAND), "never below zero");
    }

    // The preflight with fuel (11.2)

    @Test
    void thePreflightCoversTheFuelOfTheTake() {
        Order.Craft c = glassBlock(10, 2, 10);
        Order o = new Order("o", List.of(new Order.Line(Order.ITEM, "minecraft:stone", 5), new Order.Line(Order.CRAFT, GLASS, 10)), null, false, c);
        Map<String, Long> need = Preflight.needs(o, c);
        assertEquals(10L, need.get(SAND));
        assertEquals(2L, need.get(COAL), "the fuel is in the take, so in the preflight");
        Map<String, Long> stock = new LinkedHashMap<>(Map.of("minecraft:stone", 5L, SAND, 10L, COAL, 1L));
        List<Preflight.Shortfall> shorts = Preflight.check(need, stock);
        assertEquals(1, shorts.size());
        assertEquals(COAL, shorts.get(0).key());
        assertEquals(1, shorts.get(0).missing());
        assertEquals("Not enough in the stash any more to make this order: 1 Coal. Restock and retry.", Preflight.message(shorts));
        JsonArray restock = Preflight.restock(shorts);
        assertEquals(COAL, restock.get(0).getAsJsonObject().getAsJsonArray("options").get(0).getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("ids").get(0).getAsString());
        stock.put(COAL, 2L);
        assertTrue(Preflight.check(need, stock).isEmpty());
    }

    @Test
    void theLimitsOfASmeltingPlanAreTheV2Ones() {
        CraftLedger l = new CraftLedger(glassBlock(10, 2, 10), List.of(), R, 2048);
        assertEquals(12, l.limits().maxDepth());
        List<String> reasons = new ArrayList<>(CraftOrder.REASONS);
        for (String r : new String[] {"no_furnace", "furnace_blocked", "furnace_busy", "smelt_timeout"}) assertTrue(reasons.contains(r), r);
    }
}
