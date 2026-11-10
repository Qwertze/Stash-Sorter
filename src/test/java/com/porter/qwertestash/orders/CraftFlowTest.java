package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Craft lines on the order machine (1.15.0): the pre-click check, craft loads joining the packer, chunks, shorts and their reasons, cancel, resume. */
class CraftFlowTest {
    private static final Recipes R = TestRecipes.table();
    private static final String LOG = "minecraft:spruce_log", STAIRS = "minecraft:spruce_stairs", SLAB = "minecraft:spruce_slab", FENCE = "minecraft:spruce_fence",
        PLANKS = "minecraft:spruce_planks", STICK = "minecraft:stick", D = "minecraft:diamond", RED = "minecraft:red_shulker_box";

    private static Order.Qty q(String id, int n) {
        return new Order.Qty(id, n);
    }

    /** The craft block the server would make for these lines, from the sub-plan itself (the pool is the stash). */
    private static Order.Craft block(Map<String, Long> pool, Object... rows) {
        CraftSub.Session s = new CraftSub.Session(R, null, pool, pool, Map.of());
        List<Order.Qty> lines = new ArrayList<>();
        List<Order.CraftStep> steps = new ArrayList<>();
        Map<String, Long> take = new java.util.LinkedHashMap<>();
        long fills = 0;
        for (int i = 0; i < rows.length; i += 2) {
            String id = (String) rows[i];
            int n = (Integer) rows[i + 1];
            CraftSub.Outcome o = s.plan(id, n);
            assertTrue(o.ok(), id + ": " + o.plan().refusal());
            lines.add(q(id, n));
            o.plan().fromStock().forEach((k, v) -> take.merge(k, v, Long::sum));
            for (CraftPlanner.Step st : o.plan().steps()) {
                List<Order.Qty> in = new ArrayList<>();
                st.uses().forEach((k, v) -> in.add(q(k, (int) (long) v)));
                steps.add(new Order.CraftStep(st.recipe().id(), st.recipe().result(), st.recipe().count(), st.fills(), in));
                fills += st.fills();
            }
        }
        List<Order.Qty> takes = new ArrayList<>();
        take.forEach((k, v) -> takes.add(q(k, (int) (long) v)));
        List<Order.Qty> left = new ArrayList<>();
        s.surplus().forEach((k, v) -> left.add(q(k, (int) (long) v)));
        return new Order.Craft(1, "1.21.4", R.hash(), lines, steps, takes, left, (int) fills, false);
    }

    private static Order order(String id, Order.Craft craft, Order.Line... lines) {
        return new Order(id, List.of(lines), null, false, craft);
    }

    private static Order.Line craft(String key, int n) {
        return new Order.Line(Order.CRAFT, key, n);
    }

    private static OrderSim world(int logs) {
        OrderSim w = new OrderSim().stock(D, "v", 1000, 64).craftOrders(R);
        w.craftStash.put(LOG, (long) logs);
        w.empties.put(RED, 5);
        return w;
    }

    @Test
    void aCraftLineIsCraftedBeforeItsBinAndDelivered() {
        OrderSim w = world(10);
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-1", c, craft(STAIRS, 8))));
        // The load joined the packer as an item load of the result id, to be crafted
        assertEquals(1, w.flow.units().size());
        assertEquals(1, w.flow.units().get(0).lots.size());
        assertTrue(w.flow.units().get(0).lots.get(0).craft());
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(8, r.delivered().get(STAIRS));
        // 8 stairs: 2 fills of stairs from 12 planks (3 fills of planks) from 3 logs
        assertEquals(5, w.flow.craftLedger().fills());
        assertEquals(7L, w.craftStash.get(LOG));
        JsonObject craft = r.craft();
        assertNotNull(craft);
        assertEquals(5, craft.get("fills").getAsInt());
        assertEquals(STAIRS, craft.getAsJsonArray("made").get(0).getAsJsonObject().get("key").getAsString());
        assertEquals(8, craft.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(0, craft.getAsJsonArray("short").size());
        assertEquals(LOG, craft.getAsJsonArray("used").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(3, craft.getAsJsonArray("used").get(0).getAsJsonObject().get("count").getAsInt());
        assertFalse(r.message().contains("not crafted"), r.message());
    }

    @Test
    void theThreeRowsOfVectorOneShareTheirSurplusAndTheRestIsGivenBack() {
        OrderSim w = world(10);
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8, SLAB, 6, FENCE, 3);
        assertEquals(6, c.take().get(0).count());
        assertEquals(11, c.fills());
        assertNull(w.flow.offer(order("o-v1", c, craft(STAIRS, 8), craft(SLAB, 6), craft(FENCE, 3))));
        w.run(60);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(8, r.delivered().get(STAIRS));
        assertEquals(6, r.delivered().get(SLAB));
        assertEquals(3, r.delivered().get(FENCE));
        assertEquals(11, r.craft().get("fills").getAsInt());
        assertEquals(6L, r.craft().getAsJsonArray("used").get(0).getAsJsonObject().get("count").getAsLong());
        // Planks 3 and sticks 2 are left over and go back
        Map<String, Integer> back = new java.util.HashMap<>();
        r.craft().getAsJsonArray("returned").forEach(e -> back.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject().get("count").getAsInt()));
        assertEquals(Map.of(PLANKS, 3, STICK, 2), back);
        // Never more than the order's take
        assertEquals(4L, w.craftStash.get(LOG));
    }

    @Test
    void aCraftLineAndAnItemLineShareAShulker() {
        OrderSim w = world(10);
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-mix", c, new Order.Line(Order.ITEM, D, 100), craft(STAIRS, 8))));
        assertEquals(1, w.flow.units().size(), "one bin: 2 stacks of diamonds and the stairs");
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(100, r.delivered().get(D));
        assertEquals(8, r.delivered().get(STAIRS));
        assertEquals(900, w.loose.get(D).get("v"));
    }

    @Test
    void whatCannotBeCraftedIsShortWithAReasonAndTheRestIsDelivered() {
        // The server planned from 10 logs, but the stash has 4 now (gone since): stairs and slabs work, the fence does not
        OrderSim w = world(4);
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8, SLAB, 6, FENCE, 3);
        assertNull(w.flow.offer(order("o-short", c, new Order.Line(Order.ITEM, D, 10), craft(STAIRS, 8), craft(SLAB, 6), craft(FENCE, 3))));
        w.run(60);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(10, r.delivered().get(D));
        assertEquals(8, r.delivered().get(STAIRS));
        assertEquals(6, r.delivered().get(SLAB));
        assertEquals(0, r.delivered().getOrDefault(FENCE, 0));
        assertEquals(1, r.craft().getAsJsonArray("short").size());
        JsonObject sh = r.craft().getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(FENCE, sh.get("key").getAsString());
        assertEquals(3, sh.get("count").getAsInt());
        assertEquals("ingredients_short", sh.get("reason").getAsString());
        assertTrue(r.message().contains("3 item(s) not crafted: ingredients_short"), r.message());
    }

    @Test
    void aWrongRecipeHashMakesEveryCraftLineShortAndNothingIsCrafted() {
        OrderSim w = world(10);
        Order.Craft good = block(Map.of(LOG, 10L), STAIRS, 8);
        Order.Craft bad = new Order.Craft(1, "1.21.4", "0".repeat(40), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false);
        assertNull(w.flow.offer(order("o-bad", bad, new Order.Line(Order.ITEM, D, 10), craft(STAIRS, 8))));
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(10, r.delivered().get(D));
        assertEquals(0, r.delivered().getOrDefault(STAIRS, 0));
        assertEquals(10L, w.craftStash.get(LOG));
        assertEquals("recipe_mismatch", r.craft().getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertTrue(r.message().contains("8 item(s) not crafted: recipe_mismatch"), r.message());
        assertEquals(0, w.craftChunks);
    }

    @Test
    void anOrderOfCraftLinesAloneThatCannotBeCraftedFailsWithTheNote() {
        OrderSim w = world(10);
        w.craftTable = null;
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8);
        String why = w.flow.offer(order("o-none", c, craft(STAIRS, 8)));
        assertNotNull(why);
        assertTrue(why.contains("8 item(s) not crafted: recipe_mismatch"), why);
        assertFalse(w.flow.holdsOrder());
    }

    @Test
    void everyCheckOfTheOrdersCraftBlockIsMadeBeforeAnyClick() {
        Order.Craft good = block(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(CraftOrder.check(good, R, 2048));
        // v
        assertNotNull(CraftOrder.check(new Order.Craft(2, "1.21.4", R.hash(), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false), R, 2048));
        // the hash
        assertNotNull(CraftOrder.check(new Order.Craft(1, "1.21.4", "1".repeat(40), good.lines(), good.steps(), good.take(), good.leftovers(), good.fills(), false), R, 2048));
        // a recipe that is not in the table, or makes something else, or another count
        List<Order.CraftStep> steps = new ArrayList<>(good.steps());
        Order.CraftStep s0 = steps.get(0);
        steps.set(0, new Order.CraftStep("minecraft:nope", s0.result(), s0.count(), s0.fills(), s0.inputs()));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        steps.set(0, new Order.CraftStep(s0.recipe(), "minecraft:stick", s0.count(), s0.fills(), s0.inputs()));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        steps.set(0, new Order.CraftStep(s0.recipe(), s0.result(), s0.count() + 1, s0.fills(), s0.inputs()));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        // an input no cell of the recipe accepts
        steps.set(0, new Order.CraftStep(s0.recipe(), s0.result(), s0.count(), s0.fills(), List.of(q("minecraft:diamond", 3))));
        assertNotNull(CraftOrder.check(withSteps(good, steps), R, 2048));
        // the cap
        assertNotNull(CraftOrder.check(good, R, 4));
        // a take id that is not a plain id
        List<Order.Qty> take = new ArrayList<>(good.take());
        take.set(0, q("minecraft:spruce_log#ab12", 3));
        assertNotNull(CraftOrder.check(new Order.Craft(1, "1.21.4", R.hash(), good.lines(), good.steps(), take, good.leftovers(), good.fills(), false), R, 2048));
        // unreadable, or missing
        assertNotNull(CraftOrder.check(Order.Craft.unreadable(), R, 2048));
        assertNotNull(CraftOrder.check(null, R, 2048));
    }

    private static Order.Craft withSteps(Order.Craft c, List<Order.CraftStep> steps) {
        return new Order.Craft(1, c.mc(), c.recipes(), c.lines(), steps, c.take(), c.leftovers(), c.fills(), false);
    }

    @Test
    void theTableGivingOutEndsCraftingForTheRestAndTheOrderGoesOn() {
        OrderSim w = world(10);
        w.chunkTrip = "table";
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8, SLAB, 6);
        assertNull(w.flow.offer(order("o-table", c, new Order.Line(Order.ITEM, D, 10), craft(STAIRS, 8), craft(SLAB, 6))));
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(10, r.delivered().get(D));
        assertEquals(2, r.craft().getAsJsonArray("short").size());
        assertEquals("table_blocked", r.craft().getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
        assertTrue(r.message().contains("14 item(s) not crafted: table_blocked"), r.message());
    }

    @Test
    void aCancelStopsCraftingAndReportsWhatWasMade() {
        OrderSim w = world(10);
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8, SLAB, 6);
        assertNull(w.flow.offer(order("o-cancel", c, craft(STAIRS, 8), craft(SLAB, 6))));
        // One chunk is made, then the cancel comes
        w.step();
        assertTrue(w.flow.running());
        assertTrue(w.flow.cancel("o-cancel"));
        w.run(10);
        OrderFlow.Report r = w.flow.report();
        assertEquals("cancelled", r.status());
        assertNotNull(r.craft());
        JsonObject sh = r.craft().getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals("cancelled", sh.get("reason").getAsString());
        // The status carries a result for a cancelled craft order
        OrderDriver d = new OrderDriver(w.flow, () -> w.now, (a, b) -> { });
        JsonObject json = d.statusJson(r);
        assertTrue(json.has("result"));
        assertTrue(json.getAsJsonObject("result").has("craft"));
    }

    @Test
    void aChunkIsAsBigAsTheFreeSlotsAllow() {
        OrderSim w = world(100);
        w.freeSlots = 8;
        // 64 chests: 512 planks (8 stacks) from 128 logs: more than eight slots; chunks are smaller
        Order.Craft c = block(Map.of("minecraft:oak_log", 200L), "minecraft:chest", 64);
        w.craftStash.put("minecraft:oak_log", 200L);
        assertNull(w.flow.offer(order("o-room", c, craft("minecraft:chest", 64))));
        w.run(100);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals(64, r.delivered().get("minecraft:chest"));
        assertTrue(w.craftChunks > 1, "chunks: " + w.craftChunks);
    }

    @Test
    void nothingFitsIsInventoryFull() {
        OrderSim w = world(10);
        w.freeSlots = 1;
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-full", c, new Order.Line(Order.ITEM, D, 10), craft(STAIRS, 8))));
        w.run(30);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message());
        assertEquals("inventory_full", r.craft().getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void theCapOnFillsIsACapReason() {
        OrderSim w = world(10);
        w.craftMaxFills = 5;
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8);
        // The block itself is within the cap of 5 (5 fills) ...
        assertNull(w.flow.offer(order("o-cap", c, new Order.Line(Order.ITEM, D, 10), craft(STAIRS, 8))));
        w.craftMaxFills = 5;
        w.run(30);
        assertEquals("delivered", w.flow.report().status());
        assertEquals(8, w.flow.report().delivered().get(STAIRS));
        // ... and 6 fills are over it: the server's block is refused outright
        OrderSim x = world(10);
        x.craftMaxFills = 4;
        assertNull(x.flow.offer(order("o-cap2", c, new Order.Line(Order.ITEM, D, 10), craft(STAIRS, 8))));
        x.run(30);
        assertEquals("recipe_mismatch", x.flow.report().craft().getAsJsonArray("short").get(0).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void aRestartCarriesOnWithTheCraftLedgerAndTheLotsAsTheyWere() {
        OrderSim w = world(10);
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8, SLAB, 6);
        Order o = order("o-resume", c, new Order.Line(Order.ITEM, D, 10), craft(STAIRS, 8), craft(SLAB, 6));
        assertNull(w.flow.offer(o));
        // Fence-slab-stairs share a bin: one chunk is made
        w.step();
        w.step();
        assertNotNull(w.saved.craft);
        assertTrue(w.saved.units.get(0).lotCraft.contains(Order.Lot.CRAFTED));
        long fills = w.flow.craftLedger().fills();
        assertTrue(fills > 0);
        // The bot restarts: a new flow takes the same order and the saved plan
        w.flow.reset();
        assertNull(w.flow.offer(o));
        assertEquals(fills, w.flow.craftLedger().fills());
        assertTrue(w.flow.units().get(0).lots.stream().anyMatch(Order.Lot::crafted));
        w.run(40);
        assertEquals("delivered", w.flow.report().status(), w.flow.report().message());
        assertEquals(8, w.flow.report().delivered().get(STAIRS));
        assertEquals(6, w.flow.report().delivered().get(SLAB));
    }

    @Test
    void theSavedPlanWithCraftLoadsSurvivesJson() {
        OrderSim w = world(10);
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8, SLAB, 6);
        assertNull(w.flow.offer(order("o-json", c, craft(STAIRS, 8), craft(SLAB, 6))));
        w.step();
        com.google.gson.Gson gson = new com.google.gson.Gson();
        OrderFlow.Saved back = gson.fromJson(gson.toJson(w.saved), OrderFlow.Saved.class);
        assertEquals(w.saved.units.get(0).lotCraft, back.units.get(0).lotCraft);
        assertEquals(w.saved.craft, back.craft);
        assertTrue(back.units.get(0).lotCraft.contains(Order.Lot.PENDING));
        assertTrue(back.units.get(0).lotCraft.contains(Order.Lot.CRAFTED));
        // A plan saved by 1.14.0 has neither field
        OrderFlow.Saved old = gson.fromJson("{\"id\":\"o-1\",\"units\":[{\"kind\":2,\"key\":\"\",\"lotKeys\":[\"minecraft:diamond\"],\"lotCounts\":[5]}]}", OrderFlow.Saved.class);
        assertNull(old.craft);
        assertTrue(old.units.get(0).lotCraft.isEmpty());
    }

    @Test
    void anOldOrderWithoutACraftBlockIsParsedAsBefore() {
        JsonObject o = JsonParser.parseString("{\"id\":\"o-1\",\"lines\":[{\"type\":\"item\",\"key\":\"minecraft:diamond\",\"count\":5}]}").getAsJsonObject();
        Order parsed = Order.parse(o);
        assertNotNull(parsed);
        assertNull(parsed.craft);
        assertFalse(parsed.hasCraft());
        // A craft line from a newer server is kept (an older bot would have left it out)
        JsonObject n = JsonParser.parseString("{\"id\":\"o-2\",\"lines\":[{\"type\":\"craft\",\"key\":\"minecraft:spruce_stairs\",\"count\":8}],"
            + "\"craft\":{\"v\":1,\"mc\":\"1.21.4\",\"recipes\":\"" + "a".repeat(40) + "\",\"lines\":[{\"key\":\"minecraft:spruce_stairs\",\"count\":8}],"
            + "\"steps\":[{\"recipe\":\"minecraft:spruce_planks\",\"result\":\"minecraft:spruce_planks\",\"count\":4,\"fills\":6,\"inputs\":[{\"id\":\"minecraft:spruce_log\",\"count\":6}]}],"
            + "\"take\":[{\"id\":\"minecraft:spruce_log\",\"count\":6}],\"leftovers\":[{\"id\":\"minecraft:spruce_planks\",\"count\":3}],\"fills\":11}}").getAsJsonObject();
        Order p = Order.parse(n);
        assertNotNull(p);
        assertTrue(p.hasCraft());
        assertEquals(1, p.craft.v());
        assertEquals(6, p.craft.steps().get(0).fills());
        assertEquals("minecraft:spruce_log", p.craft.take().get(0).id());
        assertFalse(p.craft.malformed());
        // A craft block that is there but broken makes every craft line short, the order still parses
        JsonObject broken = n.deepCopy();
        broken.getAsJsonObject("craft").addProperty("fills", "many");
        Order b = Order.parse(broken);
        assertNotNull(b);
        assertTrue(b.craft.malformed());
    }

    @Test
    void theClaimSaysWhetherTheBotCraftsAndTheResultCarriesTheCraftObject() {
        OrderSim w = world(10);
        OrderDriver d = new OrderDriver(w.flow, () -> w.now, (a, b) -> { });
        w.now += 70_000;
        OrderDriver.Request without = d.next(true, null, "s", "t", "Bot", true);
        assertFalse(JsonParser.parseString(new String(without.body())).getAsJsonObject().has("craftOrders"));
        w.now += 70_000;
        d.craftOrders(true);
        OrderDriver.Request with = d.next(true, null, "s", "t", "Bot", true);
        assertEquals(1, JsonParser.parseString(new String(with.body())).getAsJsonObject().get("craftOrders").getAsInt());
        assertEquals("/api/v1/orders/claim", with.path());
    }

    @Test
    void aHeldCraftOrdersSavedPlanSurvivesTwoNoContentClaimsButAPlainOneIsDroppedAtOnce() {
        OrderSim w = world(10);
        w.saved = new OrderFlow.Saved();
        w.saved.id = "o-held";
        w.saved.craft = "{}";
        OrderDriver d = new OrderDriver(w.flow, () -> w.now, (a, b) -> { });
        d.craftOrdersWhen(() -> true);
        for (int i = 0; i < 2; i++) {
            w.now += 70_000;
            OrderDriver.Request claim = d.next(true, null, "s", "t", "Bot", true);
            assertEquals(1, JsonParser.parseString(new String(claim.body())).getAsJsonObject().get("craftOrders").getAsInt());
            assertTrue(claim.reply().done(204, ""));
            assertNotNull(w.saved, "claim " + i);
        }
        w.now += 70_000;
        d.next(true, null, "s", "t", "Bot", true).reply().done(204, "");
        assertNull(w.saved, "dropped on the third");
        // Without a craft ledger a 204 means nothing is held: dropped at once
        w.saved = new OrderFlow.Saved();
        w.saved.id = "o-plain";
        w.now += 70_000;
        d.next(true, null, "s", "t", "Bot", true).reply().done(204, "");
        assertNull(w.saved);
    }

    @Test
    void theReportOfAnOrderWithoutCraftLinesHasNoCraftObject() {
        OrderSim w = world(0);
        w.empties.put(RED, 3);
        assertNull(w.flow.offer(new Order("o-plain", List.of(new Order.Line(Order.ITEM, D, 10)))));
        w.run(20);
        assertNull(w.flow.report().craft());
        OrderDriver d = new OrderDriver(w.flow, () -> w.now, (a, b) -> { });
        assertFalse(d.statusJson(w.flow.report()).getAsJsonObject("result").has("craft"));
    }

    @Test
    void thePhaseSaysHowFarTheFillsAre() {
        OrderSim w = world(10);
        Order.Craft c = block(Map.of(LOG, 10L), STAIRS, 8);
        assertNull(w.flow.offer(order("o-phase", c, craft(STAIRS, 8))));
        assertEquals("crafting 2/5 fills: Spruce Stairs", w.flow.craftPhase(STAIRS, 2));
        w.run(30);
        assertTrue(w.phases.stream().anyMatch(p -> p.equals("crafting 0/5 fills: Spruce Stairs")), w.phases.toString());
    }
}
