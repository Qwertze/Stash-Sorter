package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.EnchantState;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The enchant fields of the index (ench, rc, named), the heartbeat's enchant block and the reply's bottlesReserved (ENCHANT-CONTRACT v1.0). */
class EnchantIndexTest {
    private static Item item(String id, int maxDamage, String... levels) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = 1;
        it.maxStack = 1;
        it.key = "minecraft:" + id + (levels.length > 0 ? "#" + String.join("", levels).hashCode() : "");
        it.variant = it.key;
        it.maxDamage = maxDamage;
        it.repairCost = 0;
        for (String l : levels) {
            it.enchantLevels.add(l);
            it.enchants.add(l.substring(0, l.lastIndexOf(':')));
        }
        return it;
    }

    private static ManagerIndex.Built build(Item... items) {
        Stash stash = new Stash("s", "2b2t.org");
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        for (int i = 0; i < items.length; i++) box.contents[i] = items[i];
        stash.boxes.add(box);
        return ManagerIndex.build(stash, false, "Bot", "1.21.4", "overworld", id -> null);
    }

    private static JsonObject entry(Item item) {
        JsonArray arr = JsonParser.parseString(new String(build(item).json(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items");
        for (JsonElement e : arr) return e.getAsJsonObject();
        throw new AssertionError("no items");
    }

    @Test
    void anEnchantedBookListsItsStoredEnchantmentsAndRepairCost() {
        Item book = item("enchanted_book", 0, "sharpness:4", "unbreaking:3");
        book.repairCost = 3;
        JsonObject o = entry(book);
        JsonArray ench = o.getAsJsonArray("ench");
        assertEquals(2, ench.size());
        assertEquals("minecraft:sharpness", ench.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(4, ench.get(0).getAsJsonObject().get("lvl").getAsInt());
        assertEquals(3, o.get("rc").getAsInt());
        assertFalse(o.has("named"));
    }

    @Test
    void aPlainBaseHasAnEmptyEnchListAndItsRepairCost() {
        JsonObject o = entry(item("diamond_sword", 1561));
        assertTrue(o.has("ench"));
        assertEquals(0, o.getAsJsonArray("ench").size());
        assertEquals(0, o.get("rc").getAsInt());
        assertFalse(o.has("named"));
    }

    @Test
    void aWorkedItemHasItsRepairCostAndEnchantments() {
        Item sword = item("netherite_sword", 2031, "sharpness:5", "mending:1");
        sword.repairCost = 7;
        JsonObject o = entry(sword);
        assertEquals(7, o.get("rc").getAsInt());
        assertEquals(2, o.getAsJsonArray("ench").size());
    }

    @Test
    void aCustomNamedItemIsFlaggedNamed() {
        Item sword = item("diamond_sword", 1561);
        sword.customName = true;
        sword.customText = "Excalibur";
        JsonObject o = entry(sword);
        assertTrue(o.get("named").getAsBoolean());
        // the name's text is never in the enchant fields
        assertFalse(o.getAsJsonArray("ench").toString().contains("Excalibur"));
    }

    @Test
    void anUnknownRepairCostIsLeftOutSoTheServerPlansAnEstimate() {
        Item old = item("diamond_sword", 1561);
        old.repairCost = -1;
        JsonObject o = entry(old);
        assertTrue(o.has("ench"));
        assertFalse(o.has("rc"));
    }

    @Test
    void itemsThatAreNoAnvilMaterialHaveNoEnchantFields() {
        for (Item it : List.of(item("cobblestone", 0), item("diamond", 0), item("white_shulker_box", 0))) {
            JsonObject o = entry(it);
            assertFalse(o.has("ench"), it.id);
            assertFalse(o.has("rc"), it.id);
            assertFalse(o.has("named"), it.id);
        }
    }

    @Test
    void enchantmentIdsAreMadeNamespacedAndBadEntriesAreSkipped() {
        EnchantFields.Fields f = EnchantFields.of(item("diamond_pickaxe", 1561, "minecraft:efficiency:5", "unbreaking:x", "fortune:3", ":2", "silk_touch:0"));
        assertNotNull(f);
        assertEquals(java.util.Map.of("efficiency", 5, "fortune", 3), f.ench());
        JsonObject o = new JsonObject();
        EnchantFields.write(o, f);
        assertEquals("minecraft:efficiency", o.getAsJsonArray("ench").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void theIndexStillCarriesThePlayerTheServerChecksTheseFieldsAgainst() {
        JsonObject root = JsonParser.parseString(new String(build(item("diamond_sword", 1561)).json(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("Bot", root.get("player").getAsString());
    }

    // The heartbeat

    private static JsonObject heartbeat(ManagerIndex.EnchantHb hb, String player) {
        Stash stash = new Stash("s", "2b2t.org");
        ManagerIndex.Built built = ManagerIndex.build(stash, false, player, "1.21.4", "overworld", id -> null);
        byte[] body = ManagerIndex.heartbeat(built, -1, null, null, null, null, null, null, null, null, null, null, null, null, hb);
        return JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void theHeartbeatBlockHasTheContractsShape() {
        JsonObject e = heartbeat(new ManagerIndex.EnchantHb(true, "1.21.4", 18, false), "Bot").getAsJsonObject("enchant");
        assertEquals(1, e.get("v").getAsInt());
        assertTrue(e.get("enabled").getAsBoolean());
        assertEquals("1.21.4", e.get("mc").getAsString());
        assertEquals(18, e.get("carry").getAsInt());
        assertTrue(e.get("enabled").getAsJsonPrimitive().isBoolean());
        assertEquals(4, e.size());
    }

    @Test
    void theCarryIsOptionalAndCapped() {
        JsonObject none = heartbeat(new ManagerIndex.EnchantHb(false, "1.21.11", -1, false), "Bot").getAsJsonObject("enchant");
        assertFalse(none.has("carry"));
        assertFalse(none.get("enabled").getAsBoolean());
        assertEquals(36, heartbeat(new ManagerIndex.EnchantHb(true, "1.21.4", 400, false), "Bot").getAsJsonObject("enchant").get("carry").getAsInt());
        assertEquals(0, heartbeat(new ManagerIndex.EnchantHb(true, "1.21.4", 0, false), "Bot").getAsJsonObject("enchant").get("carry").getAsInt());
    }

    @Test
    void turningTheOptionOffSendsOneExplicitNull() {
        JsonObject o = heartbeat(new ManagerIndex.EnchantHb(false, null, -1, true), "Bot");
        assertTrue(o.has("enchant"));
        assertTrue(o.get("enchant").isJsonNull());
    }

    @Test
    void withoutAPlayerOrAHeartbeatBlockTheKeyIsLeftOut() {
        assertFalse(heartbeat(new ManagerIndex.EnchantHb(true, "1.21.4", 5, false), "").has("enchant"));
        assertFalse(heartbeat(null, "Bot").has("enchant"));
    }

    @Test
    void theReplysBottlesReservedIsReadAndBadOnesAreIgnored() {
        assertEquals(14, EnchantState.parse("{\"ok\":true,\"enchant\":{\"bottlesReserved\":14}}"));
        assertEquals(0, EnchantState.parse("{\"enchant\":{\"bottlesReserved\":0}}"));
        assertEquals(-1, EnchantState.parse("{\"ok\":true}"));
        assertEquals(-1, EnchantState.parse("{\"enchant\":{\"bottlesReserved\":-5}}"));
        assertEquals(-1, EnchantState.parse("{\"enchant\":{\"bottlesReserved\":\"lots\"}}"));
        assertEquals(-1, EnchantState.parse("{\"enchant\":{\"bottlesReserved\":99999999999}}"));
        assertEquals(-1, EnchantState.parse("not json"));
        assertEquals(-1, EnchantState.parse("{\"enchant\":7}"));
    }

    @Test
    void theHeldOrderBottlesGoToRepairAndGoStaleWithoutNewReplies() {
        long[] now = {1_000};
        EnchantState s = new EnchantState(() -> now[0]);
        assertEquals(0, s.bottlesReserved());
        s.onHeartbeat("{\"enchant\":{\"bottlesReserved\":300}}");
        assertEquals(300, s.bottlesReserved());
        // repair's usable formula takes it off the stash count
        assertEquals(1728 + 500 - 300 - 1728, com.porter.qwertestash.orders.RepairRules.usable(1728 + 500, s.bottlesReserved(), 1728));
        now[0] += EnchantState.MAX_AGE_MS + 1;
        assertEquals(0, s.bottlesReserved());
        // a reply with none (an older server, or nothing reserved) clears the figure
        s.onHeartbeat("{\"enchant\":{\"bottlesReserved\":5}}");
        s.onHeartbeat("{\"ok\":true}");
        assertEquals(0, s.bottlesReserved());
    }
}
