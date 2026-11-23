package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole order for rockets through the order flow on the fake world (CRAFT-CONTRACT-v1.2.md 9, 11 and 12): the block the server writes is checked, the
 * preflight counts the take by key, the chunk of a spec'd line is planned and made, the made stack is known by its own key in the bin, the report carries the
 * spec, and a result of another spec shorts the whole line with {@code recipe_mismatch}. The grid and the table are {@link FireworkTripTest}'s.
 */
class FireworkFlowTest {
    private static final Recipes R = TestRecipes.table();
    private static final String ROCKET = "minecraft:firework_rocket", SPEC = "rocket:2|small_ball;red;;", STAR = "minecraft:firework_star", STAR_SPEC = "star:small_ball;red;;";
    private static final String STAR_KEY = "minecraft:firework_star#aaaabbbb", REF = ROCKET + "@" + SPEC;

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock("minecraft:diamond", "v", 1000, 64).craftOrders(R);
        w.extrasHash = FireworkFixtures.SERVER_HASH;
        w.can = Set.of("firework");
        w.fireworks = FireworkFixtures.TABLES;
        w.preflight = true;
        w.empties.put("minecraft:red_shulker_box", 5);
        // What the stash holds by key (the preflight), and what the chunk takes by pseudo id (the sim has no keys of its own)
        w.craftStash.put("minecraft:paper", 2L);
        w.craftStash.put("minecraft:gunpowder", 4L);
        w.craftStash.put(STAR_KEY, 2L);
        w.craftStash.put(STAR + "@" + STAR_SPEC, 2L);
        return w;
    }

    private static Order order(String id, int count) {
        Order.Craft c = Order.parseCraft(CraftFireworkTest.rocketFromStock());
        return new Order(id, List.of(new Order.Line(Order.CRAFT, ROCKET, count, false, SPEC)), null, false, c);
    }

    @Test
    void anOrderForRocketsIsCheckedPreflightedMadeAndDeliveredUnderTheKeyOfTheMadeStack() {
        OrderSim w = world();
        assertNull(w.flow.offer(order("o-fw", 6)), w.said.toString());
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message() + " " + w.said);
        JsonObject craft = r.craft();
        assertNotNull(craft);
        assertEquals("ok", craft.get("preflight").getAsString());
        JsonObject made = craft.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(ROCKET, made.get("key").getAsString());
        assertEquals(SPEC, made.get("spec").getAsString());
        assertEquals(6, made.get("made").getAsInt());
        assertEquals(0, craft.getAsJsonArray("short").size());
        assertEquals(2, craft.get("fills").getAsInt());
        // The two stars are used (by their spec), paper and gunpowder too
        assertEquals(0L, w.craftStash.get(STAR + "@" + STAR_SPEC));
        assertEquals(0L, w.craftStash.get("minecraft:paper"));
        // The shulker holds a stack of 6 under the key the made stack has, never the pseudo id
        assertEquals(1, r.delivered().size(), r.delivered().toString());
        String madeKey = r.delivered().keySet().iterator().next();
        assertTrue(madeKey.startsWith(ROCKET + "#"), madeKey);
        assertEquals(6, r.delivered().get(madeKey));
        assertFalse(madeKey.contains("@"));
        // The status body the server gets: the result carries the spec, no line says the made rockets are lost
        JsonObject body = new OrderDriver(w.flow, () -> 0L, (a, b) -> {
        }).statusJson(r);
        assertEquals(SPEC, body.getAsJsonObject("result").getAsJsonObject("craft").getAsJsonArray("made").get(0).getAsJsonObject().get("spec").getAsString());
        assertFalse(r.message().contains("not delivered"), r.message());
    }

    @Test
    void aSurplusRocketOfTheLastFillIsReturnedWithItsSpec() {
        // 4 asked, 6 made (two fills of 3): the 2 over are the order's surplus and are given back
        OrderSim w = world();
        assertNull(w.flow.offer(order("o-fw4", 4)), w.said.toString());
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message() + " " + w.said);
        assertEquals(4, r.craft().getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        assertEquals(4, r.delivered().values().iterator().next());
        boolean back = false;
        for (var e : r.craft().getAsJsonArray("returned")) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("id").getAsString().equals(ROCKET)) {
                back = true;
                assertEquals(SPEC, o.get("spec").getAsString());
                assertEquals(2, o.get("count").getAsInt());
            }
        }
        assertTrue(back, r.craft().toString());
    }

    @Test
    void aResultOfAnotherSpecShortsTheWholeLineWithRecipeMismatch() {
        OrderSim w = world();
        // Lots of 3 rockets: two loads for the 6, and the second must not be tried after the first came out wrong
        w.craftStack.put(REF, 3);
        w.chunkTrip = CraftOrder.RECIPE_MISMATCH;
        assertNull(w.flow.offer(order("o-fw-mismatch", 6)), w.said.toString());
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        JsonObject craft = r.craft();
        assertNotNull(craft);
        assertEquals(0, craft.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        JsonObject sh = craft.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(ROCKET, sh.get("key").getAsString());
        assertEquals(SPEC, sh.get("spec").getAsString());
        assertEquals(6, sh.get("count").getAsInt());
        assertEquals("recipe_mismatch", sh.get("reason").getAsString());
        assertEquals(1, w.craftChunks, "one chunk was tried: the rest of the line was short at once");
        // Nothing was taken from the stash
        assertEquals(2L, w.craftStash.get(STAR + "@" + STAR_SPEC));
    }

    @Test
    void aBlockThatDoesNotPassTheBotsCheckShortsEveryCraftLineWithRecipeMismatch() {
        // A bot that doesn't say firework (the setting off): the block is refused as v1 refused what it didn't know
        OrderSim off = world();
        off.can = Set.of();
        off.fireworks = null;
        // The order is only that line: with nothing to deliver it is refused at once, with the reason of every short craft line
        String refused = off.flow.offer(order("o-off", 6));
        assertNotNull(refused);
        assertTrue(refused.contains("6 item(s) not crafted: recipe_mismatch"), refused);
        assertTrue(off.said.toString().contains("the order needs firework, which this bot can't do"), off.said.toString());
        assertEquals(0, off.craftChunks);
        // Another extras hash than the bot's
        OrderSim stale = world();
        stale.extrasHash = "0".repeat(40);
        String again = stale.flow.offer(order("o-stale", 6));
        assertNotNull(again);
        assertTrue(again.contains("recipe_mismatch"), again);
        assertTrue(stale.said.toString().contains("other extras"), stale.said.toString());
        assertEquals(0, stale.craftChunks);
        // With a plain item line next to it the order still goes on and delivers it (the craft lines are short)
        OrderSim mixed = world();
        mixed.can = Set.of();
        mixed.fireworks = null;
        Order.Craft c = Order.parseCraft(CraftFireworkTest.rocketFromStock());
        assertNull(mixed.flow.offer(new Order("o-mixed", List.of(new Order.Line(Order.ITEM, "minecraft:diamond", 10), new Order.Line(Order.CRAFT, ROCKET, 6, false, SPEC)), null, false, c)));
        mixed.run(80);
        JsonObject craft = mixed.flow.report().craft();
        assertNotNull(craft);
        JsonObject sh = craft.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(SPEC, sh.get("spec").getAsString());
        assertEquals("recipe_mismatch", sh.get("reason").getAsString());
        assertEquals(10, mixed.flow.report().delivered().get("minecraft:diamond"));
    }

    @Test
    void aStashShortOfTheExactStarsFailsTheOrderBeforeAnythingIsTaken() {
        OrderSim w = world();
        w.craftStash.put(STAR_KEY, 1L);
        assertNull(w.flow.offer(order("o-fw-short", 6)), w.said.toString());
        assertFalse(w.flow.running());
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertEquals("Not enough in the stash any more to make this order: 1 Firework Star. Restock and retry.", r.message());
        assertEquals("short", r.craft().get("preflight").getAsString());
        JsonObject sh = r.craft().getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(SPEC, sh.get("spec").getAsString(), "the preflight's short lines name the variant too");
        assertEquals(0, w.craftChunks);
    }
}
