package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.VariantItems;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.porter.qwertestash.orders.SpecialFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The smaller findings of the review of B4: originality of a book by all its stacks, what is charged with no fill, the stew table, the take's bound and the words of refusals. */
class SpecialReviewTest {
    private static Item book(String hash, String fw) {
        Item it = new Item();
        it.id = "written_book";
        it.key = "minecraft:written_book#" + hash;
        it.fw = fw;
        it.count = 1;
        return it;
    }

    @Test
    void aBookKeyIsAnOriginalWhenAnyStackOfItIsAndACopyOnlyWhenNoneIsAndNoneIsUnknown() {
        String k = "minecraft:written_book#aa";
        assertEquals(0, VariantItems.generationOfKey(List.of(book("aa", "G1"), book("aa", "G0")), k), "an original among copies of the same key");
        assertEquals(0, VariantItems.generationOfKey(List.of(book("aa", "G0"), book("aa", null)), k));
        assertEquals(1, VariantItems.generationOfKey(List.of(book("aa", "G1"), book("aa", "G1")), k), "copies only: refused");
        assertEquals(-1, VariantItems.generationOfKey(List.of(book("aa", "G1"), book("aa", null)), k), "an unread stack of the key: not a refusal, read when in hand");
        assertEquals(-1, VariantItems.generationOfKey(List.of(book("bb", "G0")), k), "another key");
        assertEquals(-1, VariantItems.generationOfKey(List.of(), k));
        Item shulker = new Item();
        shulker.id = "white_shulker_box";
        shulker.key = "minecraft:white_shulker_box";
        shulker.contents.add(book("aa", "G1"));
        assertEquals(0, VariantItems.generationOfKey(List.of(shulker, book("aa", "G0")), k), "loose and inside a shulker are all looked at");
        assertEquals(1, VariantItems.generationOfKey(List.of(shulker), k));
    }

    @Test
    void withNoFillOnlyWhatWasGatheredOfTheLinesOwnItemIsChargedAndOnlyWhenTheLoadWasMade() {
        Map<String, Long> gathered = Map.of("minecraft:firework_rocket@rocket:1", 2L, "minecraft:paper", 1L, "minecraft:gunpowder", 3L);
        String own = "minecraft:firework_rocket@rocket:1";
        // An interrupted chunk with own stock and ingredients and no fill: the load was not made, nothing is charged (everything goes back)
        assertEquals(Map.of(), CraftCharge.charge(gathered, 0, Map.of(), own, 0));
        // The load was made from its own stock alone: that stock is charged, the paper and the gunpowder are put back
        assertEquals(Map.of(own, 2L), CraftCharge.charge(gathered, 0, Map.of(), own, 2));
        // With fills it is the charge it always was: everything gathered
        assertEquals(Map.of(own, 2L, "minecraft:paper", 1L, "minecraft:gunpowder", 3L), CraftCharge.charge(gathered, 1, Map.of(), own, 2));
        assertEquals(CraftCharge.charge(gathered, 1, Map.of("minecraft:paper", 1L)), CraftCharge.charge(gathered, 1, Map.of("minecraft:paper", 1L), own, 2));
        // The older entry point (a resume of B3 and before) is as before
        assertEquals(Map.of(), CraftCharge.charge(gathered, 0, Map.of()));
    }

    @Test
    void aStewTableWithAKeyThatIsNoSmallFlowerOrAnEffectThatIsNoneIsNotTrusted() throws Exception {
        assertTrue(TABLES.canStew());
        JsonObject j = JsonParser.parseString(EXTRAS.json().toString()).getAsJsonObject();
        j.getAsJsonObject("stew").add("minecraft:diamond", JsonParser.parseString("{\"effect\":\"minecraft:night_vision\",\"ticks\":100}"));
        assertFalse(VariantSpecs.Tables.of(j).canStew(), "a flower that is none");
        JsonObject k = JsonParser.parseString(EXTRAS.json().toString()).getAsJsonObject();
        k.getAsJsonObject("stew").add("minecraft:poppy", JsonParser.parseString("{\"effect\":\"minecraft:made_up\",\"ticks\":100}"));
        assertFalse(VariantSpecs.Tables.of(k).canStew(), "an effect that is none");
        assertEquals(16, VariantSpecs.SMALL_FLOWERS.size());
        assertEquals(39, VariantSpecs.EFFECTS.size());
        for (var e : TABLES.stew().entrySet()) assertTrue(VariantSpecs.EFFECTS.contains(e.getValue().effect()), e.getKey());
        // The gate follows: a bot with such a table says no stew
        assertFalse(com.porter.qwertestash.manager.ManagerIndex.craftCan(relist(j), false, false, false, true, false).contains("stew"));
    }

    private static com.porter.qwertestash.manager.Extras relist(JsonObject j) throws Exception {
        j.addProperty("hash", com.porter.qwertestash.manager.Extras.compute(j));
        return com.porter.qwertestash.manager.Extras.parse(j);
    }

    @Test
    void aB4BlockThatTakesMoreThanItsStepsLinesAndLeftoversCanUseIsRefused() {
        for (var block : List.of(tipped(), stew(), bannerCopy(), mapCopy(), bookCopy(), shield())) assertNull(check(block));
        JsonObject extra = tipped();
        extra.getAsJsonArray("take").add(JsonParser.parseString(q("minecraft:diamond", 64)));
        assertNotNull(check(extra), "a diamond nothing uses");
        JsonObject more = tipped();
        more.getAsJsonArray("take").get(2).getAsJsonObject().addProperty("count", 9);
        assertNotNull(check(more), "9 arrows for a craft that uses 8");
        JsonObject key = bannerCopy();
        key.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("count", 5);
        assertNotNull(check(key), "more of the key than its crafts and line use");
        // A block with no step of B4 keeps the rules it had: a surplus take is not judged
        JsonObject rocket = CraftFireworkTest.rocketFromStock();
        rocket.addProperty("extras", SERVER_HASH);
        rocket.getAsJsonArray("take").add(JsonParser.parseString(q("minecraft:diamond", 5)));
        assertNull(CraftOrder.check(parse(rocket), R, 2048, SERVER_HASH, Set.of("firework"), null, 0, FIREWORKS, TABLES));
    }

    @Test
    void theWordsOfARefusalAreAsciiAndShortWhateverTheBlockSaid() {
        String nasty = "minecraft:x\u001b[31m\n" + "é".repeat(500);
        assertTrue(CraftOrder.safe(nasty).length() <= CraftOrder.MAX_SAID + 3);
        assertFalse(CraftOrder.safe(nasty).matches(".*[^\\x20-\\x7e].*"));
        assertEquals("", CraftOrder.safe(null));
        assertEquals("fine", CraftOrder.safe("fine"));
        assertEquals(CraftOrder.safe(nasty), CraftOrder.describe(nasty));
        // A refusal that names the server's strings is clamped
        JsonObject b = tipped();
        b.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("recipe", "qwerte:special/" + "z".repeat(100));
        String bad = check(b);
        assertNotNull(bad);
        assertTrue(bad.length() <= CraftOrder.MAX_SAID + 3, bad);
        // A recipe or result id of a few hundred characters makes the block unreadable at parse time
        JsonObject huge = tipped();
        huge.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("recipe", "q".repeat(300));
        assertTrue(Order.parseCraft(huge).malformed());
        JsonArray steps = tipped().getAsJsonArray("steps");
        assertEquals(1, steps.size());
    }
}
