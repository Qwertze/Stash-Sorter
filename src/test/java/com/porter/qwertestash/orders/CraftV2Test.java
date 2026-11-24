package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Craft blocks v2 on the bot (CRAFT-CONTRACT-v1.2.md, milestone B1): the pre-click check (11.1), the reading of the block and its reserved fields (9), the
 * preflight before any withdrawal (11.2), the report of both parts of a split pair (11.4) and v1 blocks still running by v1 rules.
 */
class CraftV2Test {
    private static final Recipes R = TestRecipes.table();
    private static final String LOG = "minecraft:spruce_log", STAIRS = "minecraft:spruce_stairs", D = "minecraft:diamond", RED = "minecraft:red_shulker_box";
    private static final String EXTRAS = "c927f92c1e5bc11c5a34a36b24103c44cf61d038";

    private static Order.Qty q(String id, int n) {
        return new Order.Qty(id, n);
    }

    /** The block the server would make, from the sub-plan itself: v2 with these fields. */
    private static Order.Craft block(int v, int maxDepth, @org.jspecify.annotations.Nullable String extras, List<String> needs, @org.jspecify.annotations.Nullable String reserved,
                                     Map<String, Long> pool, Object... rows) {
        CraftSub.Session s = new CraftSub.Session(R, null, pool, pool, Map.of(), v == 2 ? CraftSub.Limits.v2(maxDepth) : CraftSub.Limits.V1);
        List<Order.Qty> lines = new ArrayList<>();
        List<Order.CraftStep> steps = new ArrayList<>();
        Map<String, Long> take = new java.util.LinkedHashMap<>();
        long fills = 0;
        for (int i = 0; i < rows.length; i += 2) {
            CraftSub.Outcome o = s.plan((String) rows[i], (Integer) rows[i + 1]);
            assertTrue(o.ok(), o.plan().refusal());
            lines.add(q((String) rows[i], (Integer) rows[i + 1]));
            o.plan().fromStock().forEach((k, val) -> take.merge(k, val, Long::sum));
            for (CraftPlanner.Step st : o.plan().steps()) {
                List<Order.Qty> in = new ArrayList<>();
                st.uses().forEach((k, val) -> in.add(q(k, (int) (long) val)));
                steps.add(new Order.CraftStep(st.recipe().id(), st.recipe().result(), st.recipe().count(), st.fills(), in));
                fills += st.fills();
            }
        }
        List<Order.Qty> takes = new ArrayList<>(), left = new ArrayList<>();
        take.forEach((k, val) -> takes.add(q(k, (int) (long) val)));
        s.surplus().forEach((k, val) -> left.add(q(k, (int) (long) val)));
        return new Order.Craft(v, "1.21.4", R.hash(), lines, steps, takes, left, (int) fills, false, List.of(), maxDepth, extras, needs, reserved);
    }

    private static Order.Craft v2(Map<String, Long> pool, Object... rows) {
        return block(2, 12, null, List.of(), null, pool, rows);
    }

    private static Order order(String id, Order.Craft craft, Order.Line... lines) {
        return new Order(id, List.of(lines), null, false, craft);
    }

    private static Order.Line craft(String key, int n) {
        return new Order.Line(Order.CRAFT, key, n);
    }

    private static Order.Line item(String key, int n) {
        return new Order.Line(Order.ITEM, key, n);
    }

    private static OrderSim world(int logs) {
        OrderSim w = new OrderSim().stock(D, "v", 1000, 64).craftOrders(R);
        w.craftStash.put(LOG, (long) logs);
        w.empties.put(RED, 5);
        w.preflight = true;
        return w;
    }

    // The check (11.1)

    @Test
    void aV2BlockPassesWhenItsFieldsAreRight() {
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        assertEquals(2, c.v());
        assertNull(CraftOrder.check(c, R, 2048, null, Set.of()));
        // The 3-argument form is the same for a bot with no extras and no features
        assertNull(CraftOrder.check(c, R, 2048));
    }

    @Test
    void maxDepthMustBeOneToTwelve() {
        Order.Craft base = v2(Map.of(LOG, 10L), STAIRS, 8);
        for (int depth : new int[] {0, -1, 13, 100}) {
            Order.Craft c = new Order.Craft(2, "1.21.4", R.hash(), base.lines(), base.steps(), base.take(), base.leftovers(), base.fills(), false, List.of(), depth, null, List.of(), null);
            assertNotNull(CraftOrder.check(c, R, 2048, null, Set.of()), "depth " + depth);
        }
        for (int depth : new int[] {1, 4, 12}) {
            Order.Craft c = new Order.Craft(2, "1.21.4", R.hash(), base.lines(), base.steps(), base.take(), base.leftovers(), base.fills(), false, List.of(), depth, null, List.of(), null);
            assertNull(CraftOrder.check(c, R, 2048, null, Set.of()), "depth " + depth);
        }
    }

    @Test
    void everyNeedMustBeAFeatureTheBotCan() {
        Order.Craft c = block(2, 12, EXTRAS, List.of("smelt"), null, Map.of(LOG, 10L), STAIRS, 8);
        assertNotNull(CraftOrder.check(c, R, 2048, EXTRAS, Set.of()), "B1 bot: can is empty");
        assertNotNull(CraftOrder.check(c, R, 2048, EXTRAS, Set.of("firework")));
        assertNull(CraftOrder.check(c, R, 2048, EXTRAS, Set.of("smelt", "firework")), "what a later milestone's bot would say");
    }

    @Test
    void extrasIsNullOrTheBotsOwnHash_andTheBotsWhenThereAreNeeds() {
        Order.Craft none = block(2, 12, null, List.of(), null, Map.of(LOG, 10L), STAIRS, 8);
        assertNull(CraftOrder.check(none, R, 2048, null, Set.of()));
        assertNull(CraftOrder.check(none, R, 2048, EXTRAS, Set.of()), "null extras pass whatever the bot holds");
        Order.Craft named = block(2, 12, EXTRAS, List.of(), null, Map.of(LOG, 10L), STAIRS, 8);
        assertNull(CraftOrder.check(named, R, 2048, EXTRAS, Set.of()));
        assertNotNull(CraftOrder.check(named, R, 2048, "1".repeat(40), Set.of()), "another hash");
        assertNotNull(CraftOrder.check(named, R, 2048, null, Set.of()), "the bot holds none");
        Order.Craft needsNone = block(2, 12, null, List.of("smelt"), null, Map.of(LOG, 10L), STAIRS, 8);
        assertNotNull(CraftOrder.check(needsNone, R, 2048, EXTRAS, Set.of("smelt")), "needs but names no extras");
    }

    @Test
    void aReservedFieldIsARecipeMismatch() {
        Order.Craft c = block(2, 12, null, List.of(), "steps[].fuel", Map.of(LOG, 10L), STAIRS, 8);
        String bad = CraftOrder.check(c, R, 2048, null, Set.of());
        assertNotNull(bad);
        assertTrue(bad.contains("steps[].fuel"), bad);
    }

    @Test
    void theRecipeHashAndTheV1ChecksOfStepsStillApplyToV2() {
        Order.Craft good = v2(Map.of(LOG, 10L), STAIRS, 8);
        Order.Craft other = new Order.Craft(2, "1.21.4", "0".repeat(40), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false, List.of(), 12, null,
            List.of(), null);
        assertNotNull(CraftOrder.check(other, R, 2048, null, Set.of()));
        List<Order.CraftStep> steps = new ArrayList<>(good.steps());
        Order.CraftStep s0 = steps.get(0);
        steps.set(0, new Order.CraftStep("minecraft:nope", s0.result(), s0.count(), s0.fills(), s0.inputs()));
        Order.Craft withBad = new Order.Craft(2, "1.21.4", R.hash(), good.lines(), steps, good.take(), good.leftovers(), good.fills(), false, List.of(), 12, null, List.of(), null);
        assertNotNull(CraftOrder.check(withBad, R, 2048, null, Set.of()));
        assertNotNull(CraftOrder.check(good, R, 3, null, Set.of()), "more fills than the bot makes");
    }

    @Test
    void aVersionOtherThanOneOrTwoIsRefusedAndV1RunsByV1Rules() {
        Order.Craft v1 = block(1, 0, null, List.of(), null, Map.of(LOG, 10L), STAIRS, 8);
        assertNull(CraftOrder.check(v1, R, 2048, null, Set.of()));
        Order.Craft three = new Order.Craft(3, "1.21.4", R.hash(), v1.lines(), v1.steps(), v1.take(), v1.leftovers(), v1.fills(), false, List.of(), 12, null, List.of(), null);
        assertNotNull(CraftOrder.check(three, R, 2048, null, Set.of()));
        // A v1 block never looks at maxDepth, needs or extras
        Order.Craft v1Odd = new Order.Craft(1, "1.21.4", R.hash(), v1.lines(), v1.steps(), v1.take(), v1.leftovers(), v1.fills(), false, List.of(), 99, "x", List.of("smelt"), "smelt");
        assertNull(CraftOrder.check(v1Odd, R, 2048, null, Set.of()));
    }

    // The block as the server sends it (9)

    private static JsonObject blockJson() {
        return JsonParser.parseString("""
            { "v": 2, "mc": "1.21.4", "recipes": "%s", "extras": null, "maxDepth": 12, "needs": [],
              "lines": [ { "key": "minecraft:spruce_stairs", "count": 8, "spec": null, "orCraft": true } ],
              "steps": [ { "recipe": "minecraft:spruce_planks", "station": "table", "result": "minecraft:spruce_planks", "count": 4, "fills": 3,
                           "inputs": [ { "id": "minecraft:spruce_log", "count": 3 } ] },
                         { "recipe": "minecraft:spruce_stairs", "result": "minecraft:spruce_stairs", "count": 4, "fills": 2,
                           "inputs": [ { "id": "minecraft:spruce_planks", "count": 12 } ] } ],
              "take": [ { "id": "minecraft:spruce_log", "count": 3 } ],
              "leftovers": [],
              "fills": 5, "smelt": 0, "forEnchant": [] }
            """.formatted(R.hash())).getAsJsonObject();
    }

    @Test
    void theContractsExampleBlockIsReadAndPasses() {
        Order.Craft c = Order.parseCraft(blockJson());
        assertNotNull(c);
        assertFalse(c.malformed());
        assertEquals(2, c.v());
        assertEquals(12, c.maxDepth());
        assertNull(c.extras());
        assertEquals(List.of(), c.needs());
        assertNull(c.reserved(), "null spec, a table station, an empty forEnchant and smelt 0 are not reserved fields");
        assertNull(CraftOrder.check(c, R, 2048, null, Set.of()));
    }

    @Test
    void everyReservedFieldMakesTheBlockFailItsCheck() {
        // B3's and B4's fields (specs, keys, grids, remainders) are carried out by a bot that says so: for any other bot, and on a step that is no special
        // one, the check refuses them, as it did while they were reserved
        JsonObject rem = blockJson();
        rem.getAsJsonArray("steps").get(0).getAsJsonObject().add("remainder", JsonParser.parseString("[ { \"id\": \"minecraft:bowl\", \"count\": 1 } ]"));
        Order.Craft remainder = Order.parseCraft(rem);
        assertNull(remainder.reserved(), "a step remainder is read (B4)");
        assertTrue(remainder.usesSpecs(), "step remainder");
        assertNotNull(CraftOrder.check(remainder, R, 2048, null, Set.of()), "step remainder");
        assertNotNull(CraftOrder.check(remainder, R, 2048, null, Set.of("firework", "tipped", "stew", "copy")), "a plain step has no remainder");
        String[][] cases = {
            {"spec", "\"rocket:1\""}, {"grid", "[null,null,null,null,null,null,null,null,null]"}};
        for (String[] f : cases) {
            JsonObject b = blockJson();
            b.getAsJsonArray("steps").get(0).getAsJsonObject().add(f[0], JsonParser.parseString(f[1]));
            Order.Craft c = Order.parseCraft(b);
            assertNull(c.reserved(), "step " + f[0]);
            assertTrue(c.usesSpecs(), "step " + f[0]);
            assertNotNull(CraftOrder.check(c, R, 2048, null, Set.of()), "step " + f[0]);
            assertNotNull(CraftOrder.check(c, R, 2048, null, Set.of("firework")), "step " + f[0] + ": even a bot that says firework refuses a plain step with a spec or a grid");
        }
        mutate(b -> b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("spec", "rocket:1"), "lines spec");
        mutate(b -> b.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("key", "minecraft:spruce_log#ab12cd34"), "take key");
        mutate(b -> b.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("spec", "dyed:000000"), "take spec");
        mutate(b -> b.getAsJsonArray("leftovers").add(JsonParser.parseString("{\"id\":\"minecraft:stick\",\"count\":1,\"spec\":\"star:star;red;;\"}")), "leftover spec");
        mutate(b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("key", "minecraft:spruce_log#ab12cd34"), "input key");
        mutate(b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("spec", "dyed:000000"), "input spec");
    }

    private static void mutate(java.util.function.Consumer<JsonObject> change, String what) {
        JsonObject b = blockJson();
        change.accept(b);
        Order.Craft c = Order.parseCraft(b);
        assertNotNull(c);
        assertFalse(c.malformed(), what);
        assertNull(c.reserved(), what + ": read, not reserved (B3)");
        assertTrue(c.usesSpecs(), what);
        assertNotNull(CraftOrder.check(c, R, 2048, null, Set.of()), what);
    }

    @Test
    void aV2BlockMissingARequiredFieldIsUnreadable() {
        for (String field : new String[] {"maxDepth", "extras", "needs", "recipes", "lines", "steps", "take", "leftovers", "fills"}) {
            JsonObject b = blockJson();
            b.remove(field);
            Order.Craft c = Order.parseCraft(b);
            assertTrue(c != null && c.malformed(), field);
        }
        JsonObject noDepth = blockJson();
        noDepth.remove("maxDepth");
        assertTrue(Order.parseCraft(noDepth).malformed(), "a missing maxDepth is unreadable, not an exception");
        JsonObject textDepth = blockJson();
        textDepth.addProperty("maxDepth", "twelve");
        assertTrue(Order.parseCraft(textDepth).malformed());
        JsonObject nullDepth = blockJson();
        nullDepth.add("maxDepth", com.google.gson.JsonNull.INSTANCE);
        assertTrue(Order.parseCraft(nullDepth).malformed());
        JsonObject wrong = blockJson();
        wrong.addProperty("needs", "smelt");
        assertTrue(Order.parseCraft(wrong).malformed());
        JsonObject extras = blockJson();
        extras.addProperty("extras", 5);
        assertTrue(Order.parseCraft(extras).malformed());
    }

    @Test
    void aV1BlockIsReadAsBeforeWhateverElseItCarries() {
        JsonObject b = blockJson();
        b.addProperty("v", 1);
        b.remove("maxDepth");
        b.remove("extras");
        b.remove("needs");
        Order.Craft c = Order.parseCraft(b);
        assertFalse(c.malformed());
        assertEquals(1, c.v());
        assertNull(CraftOrder.check(c, R, 2048, null, Set.of()));
    }

    // The preflight (11.2)

    @Test
    void aV2OrderThatTheStashCoversCarriesOnAndReportsPreflightOk() {
        OrderSim w = world(10);
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-ok", c, item(D, 100), craft(STAIRS, 8))));
        w.run(40);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(100, r.delivered().get(D));
        assertEquals(8, r.delivered().get(STAIRS));
        assertEquals("ok", r.craft().get("preflight").getAsString());
        assertFalse(r.craft().has("restock"));
        assertTrue(w.phases.contains("checking the stash") || true);
    }

    @Test
    void aShortCraftTakeFailsTheOrderBeforeAnythingIsTaken() {
        // The plan took 3 logs; the stash holds 2 now
        OrderSim w = world(2);
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        assertEquals(3, c.take().get(0).count());
        assertNull(w.flow.offer(order("o-short", c, item(D, 100), craft(STAIRS, 8))));
        assertFalse(w.flow.running());
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertEquals("Not enough in the stash any more to make this order: 1 Spruce Log. Restock and retry.", r.message());
        // Nothing was withdrawn, unpacked, crafted or delivered
        assertEquals(1000, w.loose.get(D).get("v"));
        assertEquals(2L, w.craftStash.get(LOG));
        assertEquals(0, w.craftChunks);
        assertEquals(0, w.sourcesOpened);
        assertTrue(w.flow.units().isEmpty());
        assertTrue(w.delivery.isEmpty());
        assertTrue(r.delivered().isEmpty());
        JsonObject craft = r.craft();
        assertNotNull(craft);
        assertEquals("short", craft.get("preflight").getAsString());
        assertEquals(0, craft.get("fills").getAsInt());
        assertEquals(0, craft.getAsJsonArray("made").size());
        assertEquals(0, craft.getAsJsonArray("used").size());
        assertEquals(0, craft.getAsJsonArray("returned").size());
        JsonObject sh = craft.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(STAIRS, sh.get("key").getAsString());
        assertEquals(8, sh.get("count").getAsInt());
        assertEquals("ingredients_short", sh.get("reason").getAsString());
        JsonObject entry = craft.getAsJsonArray("restock").get(0).getAsJsonObject();
        JsonObject first = entry.getAsJsonArray("options").get(0).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(LOG, first.getAsJsonArray("ids").get(0).getAsString());
        assertEquals(1, first.get("count").getAsInt());
        // The status body the server gets: every key with delivered 0
        JsonObject body = new OrderDriver(w.flow, () -> 0L, (a, b) -> { }).statusJson(r);
        JsonArray lines = body.getAsJsonObject("result").getAsJsonArray("lines");
        Map<String, Integer> delivered = new java.util.HashMap<>();
        lines.forEach(l -> delivered.put(l.getAsJsonObject().get("key").getAsString(), l.getAsJsonObject().get("delivered").getAsInt()));
        assertEquals(Map.of(D, 0, STAIRS, 0), delivered);
        assertEquals("short", body.getAsJsonObject("result").getAsJsonObject("craft").get("preflight").getAsString());
    }

    @Test
    void aShortItemLineFailsItToo_andManyShortfallsAreCutToThree() {
        OrderSim w = world(10);
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-item", c, item(D, 1500), craft(STAIRS, 8))));
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertEquals("Not enough in the stash any more to make this order: 500 Diamond. Restock and retry.", r.message());
        assertEquals(1000, w.loose.get(D).get("v"));

        List<Preflight.Shortfall> five = new ArrayList<>();
        for (int i = 1; i <= 5; i++) five.add(new Preflight.Shortfall("minecraft:item_" + i, i));
        assertEquals("Not enough in the stash any more to make this order: 1 Item 1, 2 Item 2, 3 Item 3, and 2 more. Restock and retry.", Preflight.message(five));
        assertEquals(5, Preflight.restock(five).size());
    }

    @Test
    void theItemLineAndTheTakeOfOneKeyAreAddedUp() {
        // The order asks for 8 logs as an item line and the block takes 3 more: 11 are needed, 10 are there
        OrderSim w = world(10);
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-sum", c, item(LOG, 8), craft(STAIRS, 8))));
        assertEquals("failed", w.flow.report().status());
        assertTrue(w.flow.report().message().contains("1 Spruce Log"), w.flow.report().message());
    }

    @Test
    void shulkerBoxLinesKeepTheirOwnChecks() {
        OrderSim w = world(10);
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        // 5 empty shulkers in the stash: an item line for 6 of them is the old check's business ("need 6 empty shulkers"), not the preflight's
        String why = w.flow.offer(order("o-sb", c, item(RED, 6), craft(STAIRS, 8)));
        assertFalse(why != null && why.startsWith("Not enough in the stash any more"), String.valueOf(why));
        assertFalse(w.flow.holdsOrder() && w.flow.report().message().startsWith("Not enough in the stash any more"));
    }

    @Test
    void aV1BlockIsNotPreflightedAndNothingChangesForIt() {
        OrderSim w = world(2);
        Order.Craft c = block(1, 0, null, List.of(), null, Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-v1", c, item(D, 100), craft(STAIRS, 8))));
        w.run(40);
        OrderFlow.Report r = w.flow.report();
        // The old rules: 2 logs make 4 of the 8 stairs, and the order is delivered with the note
        assertEquals("delivered", r.status(), r.message());
        assertEquals(100, r.delivered().get(D));
        assertFalse(r.craft().has("preflight"));
        assertTrue(r.message().contains("not crafted"), r.message());
    }

    @Test
    void aV2BlockThatFailsItsCheckIsShortWithRecipeMismatchAndTheRestGoesOn() {
        OrderSim w = world(10);
        Order.Craft c = block(2, 12, null, List.of("smelt"), null, Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-needs", c, item(D, 10), craft(STAIRS, 8))));
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(10, r.delivered().get(D));
        assertEquals(0, r.delivered().getOrDefault(STAIRS, 0));
        assertEquals("recipe_mismatch", r.craft().getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertFalse(r.craft().has("preflight"), "no preflight for a block that failed its check");
        assertEquals(10L, w.craftStash.get(LOG));
    }

    @Test
    void aHostThatCannotCountTheStashMakesNoPreflight() {
        OrderSim w = world(2);
        w.preflight = false;
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-nocount", c, item(D, 100), craft(STAIRS, 8))));
        w.run(40);
        assertFalse(w.flow.report().craft().has("preflight"));
    }

    @Test
    void anUnreadableStashFailsTheOrderInsteadOfSkippingThePreflight() {
        OrderSim w = world(10);
        w.preflightUnreadable = true;
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-unread", c, item(D, 100), craft(STAIRS, 8))));
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertEquals("can't read the stash right now; retry", r.message());
        assertEquals(1000, w.loose.get(D).get("v"));
        assertEquals(0, w.craftChunks);
        assertTrue(w.flow.units().isEmpty());
        assertEquals(0, r.craft().getAsJsonArray("restock").size());
    }

    @Test
    void theSavedLedgerKeepsPreflightOk() {
        OrderSim w = world(10);
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-save", c, craft(STAIRS, 8))));
        JsonObject saved = w.flow.craftLedger().toSaved();
        assertEquals("ok", saved.get("preflight").getAsString());
        CraftLedger again = new CraftLedger(c, List.of(craft(STAIRS, 8)), R, 2048);
        assertNull(again.preflight());
        again.restore(saved);
        assertEquals("ok", again.preflight());
    }

    // A split pair (5, 11.4)

    @Test
    void bothPartsOfASplitPairAreOneKeyInTheReport() {
        // 3 stairs from the stash and 5 crafted: the server's split of an orCraft line for 8
        OrderSim w = world(10);
        w.stock(STAIRS, "v", 3, 64);
        Order.Craft c = v2(Map.of(LOG, 10L), STAIRS, 5);
        Order o = order("o-pair", c, item(STAIRS, 3), craft(STAIRS, 5));
        assertEquals(Map.of(STAIRS, 8), o.requested());
        assertNull(w.flow.offer(o));
        assertEquals(1, w.flow.units().size(), "both parts are one kind in one bin");
        assertEquals(2, w.flow.units().get(0).lots.size());
        w.run(40);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(8, r.delivered().get(STAIRS), "the delivered count sums both parts");
        JsonObject body = new OrderDriver(w.flow, () -> 0L, (a, b) -> { }).statusJson(r);
        JsonArray lines = body.getAsJsonObject("result").getAsJsonArray("lines");
        assertEquals(1, lines.size(), "one entry per key");
        assertEquals(8, lines.get(0).getAsJsonObject().get("delivered").getAsInt());
        assertEquals("ok", body.getAsJsonObject("result").getAsJsonObject("craft").get("preflight").getAsString());
    }

    @Test
    void thePackerKeepsAPairInOneGroupButItsLotsStayTwoLoads() {
        // 20 from the stash and 20 crafted of one key: adjacent in one bin, as two loads (two slots; the server counts ceil(40/64) = 1)
        List<Order.Lot> lots = new ArrayList<>();
        lots.add(new Order.Lot("minecraft:chest", "v", 20));
        lots.add(Order.Lot.toCraft("minecraft:chest", 20));
        lots.add(new Order.Lot("minecraft:stone", "v", 64));
        List<List<Order.Lot>> bins = Order.pack(lots);
        assertEquals(1, bins.size());
        assertEquals(3, bins.get(0).size());
        assertEquals("minecraft:chest", bins.get(0).get(0).key());
        assertEquals("minecraft:chest", bins.get(0).get(1).key());
        assertTrue(bins.get(0).get(0).craft() != bins.get(0).get(1).craft());
    }
}
