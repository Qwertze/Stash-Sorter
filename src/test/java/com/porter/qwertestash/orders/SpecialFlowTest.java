package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.porter.qwertestash.orders.SpecialFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whole orders for tipped arrows, stew, copies and shields through the order flow on the fake world (CRAFT-CONTRACT-v1.2.md 9, 11, 12 and the B4
 * clarifications): the block is checked, the preflight counts the take by exact key (the source of a copy included), the chunk of a spec'd line is planned and
 * made, the made stack is known by its own key in the bin, the source banner or book is never delivered and comes back (counted in {@code used} and
 * {@code returned}), the report carries the spec, a result of another spec or a book that is not an original shorts the whole line with
 * {@code recipe_mismatch}, and a bot that doesn't say the feature gets nothing crafted.
 */
class SpecialFlowTest {
    private static final String B = BANNER + "@copy:" + K_BANNER, TIPPED = "minecraft:tipped_arrow@" + POISON;

    private static OrderSim world() {
        OrderSim w = new OrderSim().stock("minecraft:diamond", "v", 1000, 64).craftOrders(R);
        w.extrasHash = SERVER_HASH;
        w.can = CAN;
        w.fireworks = FIREWORKS;
        w.specials = TABLES;
        w.preflight = true;
        w.empties.put("minecraft:red_shulker_box", 5);
        return w;
    }

    private static Order order(String id, JsonObject block, String item, String spec, int count) {
        return new Order(id, List.of(new Order.Line(Order.CRAFT, item, count, false, spec)), null, false, Order.parseCraft(block));
    }

    private static long count(com.google.gson.JsonArray list, String id, String spec) {
        for (var e : list) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("id").getAsString().equals(id) && (spec == null ? !o.has("spec") : o.has("spec") && o.get("spec").getAsString().equals(spec))) return o.get("count").getAsLong();
        }
        return 0;
    }

    @Test
    void anOrderForBannerCopiesIsMadeWithTheSourceKeptApartAndGivenBack() {
        OrderSim w = world();
        // By key (the preflight): 2 of the banner, 2 blanks; by pseudo id (the chunk of the sim)
        w.craftStash.put(K_BANNER, 2L);
        w.craftStash.put(BANNER, 2L);
        w.craftStash.put(B, 2L);
        assertNull(w.flow.offer(order("o-banner", SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3)), w.said.toString());
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message() + " " + w.said);
        JsonObject craft = r.craft();
        assertNotNull(craft);
        assertEquals("ok", craft.get("preflight").getAsString());
        JsonObject made = craft.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(BANNER, made.get("key").getAsString());
        assertEquals("copy:" + K_BANNER, made.get("spec").getAsString());
        assertEquals(3, made.get("made").getAsInt());
        assertEquals(0, craft.getAsJsonArray("short").size());
        assertEquals(2, craft.get("fills").getAsInt());
        // The banners of the key taken from the stash are used (2) and the source comes back (1): never delivered, never packed
        assertEquals(2, count(craft.getAsJsonArray("used"), BANNER, "copy:" + K_BANNER));
        assertEquals(2, count(craft.getAsJsonArray("used"), BANNER, null), "the blanks");
        assertEquals(1, count(craft.getAsJsonArray("returned"), BANNER, "copy:" + K_BANNER), craft.toString());
        // The shulker holds 3 under the key the made stack has
        long delivered = 0;
        for (var e : r.delivered().entrySet()) if (e.getKey().startsWith(BANNER + "#")) delivered += e.getValue();
        assertEquals(3, delivered, r.delivered().toString());
    }

    @Test
    void anOrderForTippedArrowsAndStewIsMadeAndReportedWithItsSpec() {
        OrderSim w = world();
        w.craftStash.put(K_POTION, 1L);
        w.craftStash.put(K_ARROW, 5L);
        w.craftStash.put("minecraft:arrow", 8L);
        w.craftStash.put("minecraft:lingering_potion@" + POISON, 1L);
        w.craftStash.put(TIPPED, 5L);
        assertNull(w.flow.offer(order("o-tipped", SpecialFixtures.tipped(), "minecraft:tipped_arrow", POISON, 10)), w.said.toString());
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message() + " " + w.said);
        JsonObject made = r.craft().getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals("minecraft:tipped_arrow", made.get("key").getAsString());
        assertEquals(POISON, made.get("spec").getAsString());
        assertEquals(10, made.get("made").getAsInt());
        // 8 made, 5 from stock: 13 for 10 asked: the 3 over come back with their spec
        assertEquals(3, count(r.craft().getAsJsonArray("returned"), "minecraft:tipped_arrow", POISON), r.craft().toString());
        assertEquals(1, count(r.craft().getAsJsonArray("used"), "minecraft:lingering_potion", POISON));

        OrderSim s = world();
        for (String id : new String[] {"minecraft:bowl", "minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:poppy"}) s.craftStash.put(id, 2L);
        s.craftStash.put(K_STEW, 3L);
        s.craftStash.put("minecraft:suspicious_stew@" + NV, 3L);
        assertNull(s.flow.offer(order("o-stew", SpecialFixtures.stew(), "minecraft:suspicious_stew", NV, 5)), s.said.toString());
        s.run(80);
        OrderFlow.Report sr = s.flow.report();
        assertEquals("delivered", sr.status(), sr.message() + " " + s.said);
        assertEquals(NV, sr.craft().getAsJsonArray("made").get(0).getAsJsonObject().get("spec").getAsString());
        assertEquals(5, sr.craft().getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
    }

    @Test
    void aStashShortOfTheSourceByKeyFailsTheOrderBeforeAnythingIsTaken() {
        OrderSim w = world();
        w.craftStash.put(K_BANNER, 1L);
        w.craftStash.put(BANNER, 2L);
        assertNull(w.flow.offer(order("o-short", SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3)), w.said.toString());
        assertFalse(w.flow.running());
        OrderFlow.Report r = w.flow.report();
        assertEquals("failed", r.status());
        assertTrue(r.message().contains("1 White Banner"), r.message());
        assertEquals("short", r.craft().get("preflight").getAsString());
        JsonObject sh = r.craft().getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals("copy:" + K_BANNER, sh.get("spec").getAsString());
        assertEquals("ingredients_short", sh.get("reason").getAsString());
        assertEquals(0, w.craftChunks);
    }

    @Test
    void aResultOfAnotherSpecShortsTheWholeLineWithRecipeMismatch() {
        OrderSim w = world();
        w.craftStash.put(K_BANNER, 2L);
        w.craftStash.put(BANNER, 2L);
        w.craftStash.put(B, 2L);
        w.craftStack.put(B, 1);
        w.chunkTrip = CraftOrder.RECIPE_MISMATCH;
        assertNull(w.flow.offer(order("o-mismatch", SpecialFixtures.bannerCopy(), BANNER, "copy:" + K_BANNER, 3)), w.said.toString());
        w.run(80);
        JsonObject craft = w.flow.report().craft();
        assertNotNull(craft);
        assertEquals(0, craft.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt());
        JsonObject sh = craft.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(BANNER, sh.get("key").getAsString());
        assertEquals("copy:" + K_BANNER, sh.get("spec").getAsString());
        assertEquals(3, sh.get("count").getAsInt());
        assertEquals("recipe_mismatch", sh.get("reason").getAsString());
        assertEquals(1, w.craftChunks, "one chunk was tried: the rest of the line was short at once");
        // Nothing was taken from the stash
        assertEquals(2L, w.craftStash.get(B));
    }

    @Test
    void aBookThatIsNotAnOriginalShortsTheLineWithRecipeMismatchAndTakesNothing() {
        // The host looks at the stash before it takes anything (the generation is kept with the stack): it shorts the load and the rest of the line
        OrderSim w = world();
        w.craftStash.put(K_BOOK, 1L);
        w.craftStash.put("minecraft:writable_book", 6L);
        w.craftStash.put("minecraft:written_book@src:" + K_BOOK, 1L);
        w.chunkTrip = CraftOrder.RECIPE_MISMATCH;
        assertNull(w.flow.offer(order("o-book", SpecialFixtures.bookCopy(), BOOK, "copy:" + K_BOOK, 6)), w.said.toString());
        w.run(80);
        JsonObject craft = w.flow.report().craft();
        JsonObject sh = craft.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(BOOK, sh.get("key").getAsString());
        assertEquals("copy:" + K_BOOK, sh.get("spec").getAsString());
        assertEquals(6, sh.get("count").getAsInt());
        assertEquals("recipe_mismatch", sh.get("reason").getAsString());
        assertEquals(1L, w.craftStash.get("minecraft:written_book@src:" + K_BOOK), "the book stays where it was");
        assertEquals(6L, w.craftStash.get("minecraft:writable_book"));
    }

    @Test
    void aBotThatDoesNotSayTheFeatureOrHasNoTablesCraftsNothing() {
        for (String feature : new String[] {"tipped", "stew", "copy"}) {
            JsonObject block = feature.equals("tipped") ? SpecialFixtures.tipped() : feature.equals("stew") ? SpecialFixtures.stew() : SpecialFixtures.bannerCopy();
            String item = feature.equals("tipped") ? "minecraft:tipped_arrow" : feature.equals("stew") ? "minecraft:suspicious_stew" : BANNER;
            String spec = feature.equals("tipped") ? POISON : feature.equals("stew") ? NV : "copy:" + K_BANNER;
            OrderSim off = world();
            off.can = Set.of("firework", "smelt", feature.equals("tipped") ? "stew" : "tipped");
            String refused = off.flow.offer(order("o-off-" + feature, block, item, spec, 3));
            assertNotNull(refused, feature);
            assertTrue(refused.contains("recipe_mismatch"), refused);
            assertEquals(0, off.craftChunks);
            OrderSim noTables = world();
            noTables.specials = null;
            String again = noTables.flow.offer(order("o-notables-" + feature, block, item, spec, 3));
            assertNotNull(again, feature);
            assertTrue(again.contains("recipe_mismatch"), again);
        }
    }

    @Test
    void anOrderForShieldsConsumesTheBannerAndDeliversNoBanner() {
        OrderSim w = world();
        w.craftStash.put(K_BANNER, 2L);
        w.craftStash.put("minecraft:shield", 2L);
        w.craftStash.put(B, 2L);
        assertNull(w.flow.offer(order("o-shield", SpecialFixtures.shield(), "minecraft:shield", "shield:" + K_BANNER, 2)), w.said.toString());
        w.run(80);
        OrderFlow.Report r = w.flow.report();
        assertEquals("delivered", r.status(), r.message() + " " + w.said);
        JsonObject made = r.craft().getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals("minecraft:shield", made.get("key").getAsString());
        assertEquals("shield:" + K_BANNER, made.get("spec").getAsString());
        assertEquals(2, made.get("made").getAsInt());
        assertEquals(2, count(r.craft().getAsJsonArray("used"), BANNER, "copy:" + K_BANNER), "the banners are used up");
        assertEquals(0, count(r.craft().getAsJsonArray("returned"), BANNER, "copy:" + K_BANNER));
        for (var e : r.delivered().keySet()) assertFalse(e.startsWith(BANNER), "no banner is delivered: " + r.delivered());
    }
}
