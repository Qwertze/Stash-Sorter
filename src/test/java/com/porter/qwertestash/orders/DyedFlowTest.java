package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.porter.qwertestash.orders.DyedFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole orders for dyed armour through the order flow on the fake world (CRAFT-CONTRACT-v1.2.md 9, 11, 12 and the server's B5 notes): the block is checked, the
 * preflight counts the take by exact key (the dyed stock) and by id (the undyed base and the dyes), a load is one piece (armour has a stack of 1) made one chunk
 * at a time, a two-coat colour makes its first coat and uses it up in the same chunk, the made stack is known by its own key in the bin, the first coat is never
 * delivered or handed back, the report carries the spec, and a bot that doesn't say {@code dyed} gets nothing crafted.
 */
class DyedFlowTest {
    private static final String RED_REF = CHEST + "@" + RED;

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock("minecraft:diamond", "v", 1000, 64).craftOrders(R);
        w.extrasHash = SERVER_HASH;
        w.can = CAN;
        w.fireworks = FIREWORKS;
        w.specials = TABLES;
        w.dyes = DYED;
        w.preflight = true;
        w.empties.put("minecraft:red_shulker_box", 5);
        // Armour has a stack of 1: every kind of it, the plain pieces and each colour
        for (String id : new String[] {CHEST, BOOTS, HORSE, WOLF}) w.craftStack.put(id, 1);
        return w;
    }

    private static Order order(String id, JsonObject block, String item, String spec, int count) {
        return new Order(id, List.of(new Order.Line(Order.CRAFT, item, count, false, spec)), null, false, Order.parseCraft(block));
    }

    private static long count(JsonArray list, String id, String spec) {
        for (var e : list) {
            JsonObject o = e.getAsJsonObject();
            String key = o.has("id") ? "id" : "key";
            if (o.get(key).getAsString().equals(id) && (spec == null ? !o.has("spec") : o.has("spec") && o.get("spec").getAsString().equals(spec))) return o.get("count").getAsLong();
        }
        return 0;
    }

    private static long delivered(OrderFlow.Report r, String itemPrefix) {
        long n = 0;
        for (var e : r.delivered().entrySet()) if (e.getKey().startsWith(itemPrefix)) n += e.getValue();
        return n;
    }

    @Test
    void anOrderForDyedChestplatesTakesTheStockByKeyAndDyesTheRestOnePieceAtATime() {
        OrderSim w = world();
        // 5 red chestplates: 2 in stock (by key for the preflight, by pseudo id for the chunk), 3 plain pieces and 3 dyes
        w.craftStash.put(K_RED1, 2L);
        w.craftStash.put(RED_REF, 2L);
        w.craftStash.put(CHEST, 3L);
        w.craftStash.put("minecraft:red_dye", 3L);
        assertNull(w.flow.offer(order("o-red", red(2, 5), CHEST, RED, 5)), w.said.toString());
        w.run(200);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message() + " " + w.said);
        JsonObject craft = r.craft();
        assertNotNull(craft);
        assertEquals("ok", craft.get("preflight").getAsString());
        JsonObject made = craft.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(CHEST, made.get("key").getAsString());
        assertEquals(RED, made.get("spec").getAsString());
        assertEquals(5, made.get("made").getAsInt());
        assertEquals(0, craft.getAsJsonArray("short").size());
        assertEquals(3, craft.get("fills").getAsInt());
        assertEquals(3, count(craft.getAsJsonArray("used"), CHEST, null), "the undyed bases");
        assertEquals(3, count(craft.getAsJsonArray("used"), "minecraft:red_dye", null));
        assertEquals(2, count(craft.getAsJsonArray("used"), CHEST, RED), "the dyed stock is delivered, not used up");
        assertEquals(5, delivered(r, CHEST + "#"), r.delivered().toString());
        assertEquals(0L, w.craftStash.get(CHEST));
        assertTrue(w.craftChunks >= 3, "one piece to a chunk: " + w.craftChunks);
    }

    @Test
    void aTwoCoatColourIsMadeAndItsFirstCoatIsNeverDeliveredOrHandedBack() {
        OrderSim w = world();
        Two two = twoCoats().get(0);
        String result = DyedSpecs.specOf(two.result()), mid = DyedSpecs.specOf(two.mid());
        w.craftStash.put(CHEST, 3L);
        for (int i = 0; i < 16; i++) if (two.first()[i] + two.second()[i] > 0) w.craftStash.put(dyeId(i), (long) 3 * (two.first()[i] + two.second()[i]));
        assertNull(w.flow.offer(order("o-two", twoCoat(CHEST, two, 3), CHEST, result, 3)), w.said.toString());
        w.run(300);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message() + " " + w.said);
        JsonObject craft = r.craft();
        JsonObject made = craft.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(result, made.get("spec").getAsString());
        assertEquals(3, made.get("made").getAsInt());
        assertEquals(6, craft.get("fills").getAsInt(), "two crafts a piece");
        assertEquals(0, craft.getAsJsonArray("short").size());
        // The first coat is carried and used up; it is none of the order's stock, delivery or returns
        assertEquals(0, count(craft.getAsJsonArray("returned"), CHEST, mid), craft.toString());
        assertEquals(0, count(craft.getAsJsonArray("used"), CHEST, mid), craft.toString());
        assertEquals(0, count(craft.getAsJsonArray("returned"), CHEST, result));
        assertEquals(3, delivered(r, CHEST + "#"), r.delivered().toString());
        for (var e : r.delivered().entrySet()) assertFalse(e.getKey().contains(String.format("%08x", mid.hashCode())), "no first coat in a bin: " + e.getKey());
        assertEquals(3, w.craftChunks, "three pieces, one chunk each");
    }

    @Test
    void aBotThatDoesNotSayDyedOrHasNoTablesCraftsNothing() {
        OrderSim off = world();
        off.can = Set.of("firework", "smelt", "tipped", "stew", "copy");
        String refused = off.flow.offer(order("o-off", red(2, 5), CHEST, RED, 5));
        assertNotNull(refused);
        assertTrue(refused.contains("recipe_mismatch"), refused);
        assertEquals(0, off.craftChunks);
        OrderSim noTables = world();
        noTables.dyes = null;
        String again = noTables.flow.offer(order("o-notables", red(2, 5), CHEST, RED, 5));
        assertNotNull(again);
        assertTrue(again.contains("recipe_mismatch"), again);
        assertEquals(0, noTables.craftChunks);
    }

    @Test
    void aStashShortOfTheDyedStockOrTheDyeFailsTheOrderBeforeAnythingIsTaken() {
        OrderSim w = world();
        w.craftStash.put(K_RED1, 1L);
        w.craftStash.put(CHEST, 3L);
        w.craftStash.put("minecraft:red_dye", 3L);
        assertNull(w.flow.offer(order("o-short", red(2, 5), CHEST, RED, 5)), w.said.toString());
        assertFalse(w.flow.running());
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertEquals("short", r.craft().get("preflight").getAsString());
        JsonObject sh = r.craft().getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(RED, sh.get("spec").getAsString());
        assertEquals("ingredients_short", sh.get("reason").getAsString());
        assertEquals(0, w.craftChunks);
    }

    @Test
    void aResultOfAnotherColourShortsTheWholeLineWithRecipeMismatch() {
        OrderSim w = world();
        w.craftStash.put(CHEST, 2L);
        w.craftStash.put("minecraft:red_dye", 2L);
        w.chunkTrip = CraftOrder.RECIPE_MISMATCH;
        assertNull(w.flow.offer(order("o-mismatch", red(0, 2), CHEST, RED, 2)), w.said.toString());
        w.run(100);
        JsonObject craft = w.flow.report().craft();
        JsonObject sh = craft.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(CHEST, sh.get("key").getAsString());
        assertEquals(RED, sh.get("spec").getAsString());
        assertEquals(2, sh.get("count").getAsInt());
        assertEquals("recipe_mismatch", sh.get("reason").getAsString());
        assertEquals(1, w.craftChunks, "one chunk was tried: the rest of the line was short at once");
        assertEquals(2L, w.craftStash.get(CHEST), "nothing was taken from the stash");
    }

    @Test
    void anOrderWithoutADyedLineIsNotAffectedByTheBotSayingDyed() {
        // A B4 order on a bot that also says dyed: the same result as before
        OrderSim w = world();
        w.craftStash.put(SpecialFixtures.K_POTION, 1L);
        w.craftStash.put(SpecialFixtures.K_ARROW, 5L);
        w.craftStash.put("minecraft:arrow", 8L);
        w.craftStash.put("minecraft:lingering_potion@" + SpecialFixtures.POISON, 1L);
        w.craftStash.put("minecraft:tipped_arrow@" + SpecialFixtures.POISON, 5L);
        w.extrasHash = SpecialFixtures.SERVER_HASH;
        JsonObject block = SpecialFixtures.tipped();
        assertNull(w.flow.offer(new Order("o-tipped", List.of(new Order.Line(Order.CRAFT, "minecraft:tipped_arrow", 10, false, SpecialFixtures.POISON)), null, false, Order.parseCraft(block))),
            w.said.toString());
        w.run(80);
        assertEquals("delivered", w.flow.report().status(), w.flow.report().message());
        assertEquals(10, w.flow.report().craft().getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
    }

    @Test
    void aStockPieceOfTheFirstCoatsColourBesideATwoCoatOrderForItIsDeliveredWhateverTheOrderOfTheLines() {
        Two two = twoCoats().get(0);
        String result = DyedSpecs.specOf(two.result()), mid = DyedSpecs.specOf(two.mid());
        String kMid = "minecraft:leather_chestplate#d0d0d0d0", midRef = CHEST + "@" + mid;
        for (boolean midFirst : new boolean[] {true, false}) {
            OrderSim w = world();
            JsonObject b = twoCoat(CHEST, two, 1);
            b.getAsJsonArray("lines").add(com.google.gson.JsonParser.parseString(line(CHEST, mid, 1)));
            b.getAsJsonArray("take").add(com.google.gson.JsonParser.parseString(q(CHEST, kMid, mid, 1)));
            assertNull(check(b), "the block passes the bot's check");
            w.craftStash.put(kMid, 1L);
            w.craftStash.put(midRef, 1L);
            w.craftStash.put(CHEST, 1L);
            for (int i = 0; i < 16; i++) if (two.first()[i] + two.second()[i] > 0) w.craftStash.put(dyeId(i), (long) two.first()[i] + two.second()[i]);
            Order.Line a = new Order.Line(Order.CRAFT, CHEST, 1, false, mid), f = new Order.Line(Order.CRAFT, CHEST, 1, false, result);
            assertNull(w.flow.offer(new Order("o-mixed", midFirst ? List.of(a, f) : List.of(f, a), null, false, Order.parseCraft(b))), w.said.toString());
            w.run(300);
            OrderFlow.Report r = w.flow.report();
            String how = (midFirst ? "mid line first: " : "two-coat line first: ") + r.message() + " " + w.said;
            assertEquals("delivered", r.status(), how);
            JsonObject craft = r.craft();
            assertEquals(0, craft.getAsJsonArray("short").size(), how);
            int madeOf = 0;
            for (var e : craft.getAsJsonArray("made")) if (e.getAsJsonObject().get("spec").getAsString().equals(mid) || e.getAsJsonObject().get("spec").getAsString().equals(result)) madeOf += e.getAsJsonObject().get("made").getAsInt();
            assertEquals(2, madeOf, "both lines are made, one of each colour: " + craft);
            assertEquals(2, delivered(r, CHEST + "#"), "one piece of each colour: " + r.delivered());
            // One plain piece and the two coats' dyes are used; the stock piece of the colour is used once, delivered or as the first coat that the crafted one replaces
            assertEquals(1, count(craft.getAsJsonArray("used"), CHEST, null), how + craft);
            assertEquals(1, count(craft.getAsJsonArray("used"), CHEST, mid), how + craft);
            assertEquals(0, count(craft.getAsJsonArray("returned"), CHEST, mid), "nothing is left over: " + craft);
            assertEquals(0, count(craft.getAsJsonArray("returned"), CHEST, result), craft.toString());
        }
    }
}
