package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the heartbeat says about orders, and that an order's shulker line finds the shulker the index lists. */
class ManagerIndexOrdersTest {
    private static Item item(String id, int count, int max, String key) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = max;
        it.key = key;
        it.variant = key;
        return it;
    }

    private static Stash.Box box(Item... items) {
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        for (int i = 0; i < items.length; i++) box.contents[i] = items[i];
        return box;
    }

    private static ManagerIndex.Built built(Stash stash) {
        return ManagerIndex.build(stash, false, "Bot", "1.21.4", "overworld", id -> null);
    }

    @Test
    void heartbeatCarriesTheDeliveryContainerAndTheBlocker() {
        ManagerIndex.Built b = built(new Stash("s", "2b2t.org"));
        JsonObject o = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, "no delivery container set", new ManagerIndex.Delivery(10, -64, -2000, "minecraft:the_nether")), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject d = o.getAsJsonObject("delivery");
        assertEquals(10, d.get("x").getAsInt());
        assertEquals(-64, d.get("y").getAsInt());
        assertEquals(-2000, d.get("z").getAsInt());
        assertEquals("minecraft:the_nether", d.get("dimension").getAsString());
        assertEquals("no delivery container set", o.get("orderBlocker").getAsString());
    }

    @Test
    void noDeliveryAndNoBlockerAreExplicitNulls() {
        ManagerIndex.Built b = built(new Stash("s", "2b2t.org"));
        JsonObject o = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(o.has("delivery") && o.get("delivery").isJsonNull());
        assertTrue(o.has("orderBlocker") && o.get("orderBlocker").isJsonNull());
        // An empty blocker counts as none; a long one is cut to the protocol's 100 characters
        JsonObject empty = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, "", null), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(empty.get("orderBlocker").isJsonNull());
        JsonObject longer = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, "x".repeat(300), null), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(100, longer.get("orderBlocker").getAsString().length());
    }

    @Test
    void theDeliveryContainerIsNotPartOfTheIndex() {
        Stash stash = new Stash("s", "2b2t.org");
        Stash.Box stock = box(item("diamond", 10, 64, "minecraft:diamond"));
        Stash.Box delivery = box(item("diamond", 999, 64, "minecraft:diamond"));
        delivery.delivery = true;
        delivery.excluded = true;
        stash.boxes.add(stock);
        stash.boxes.add(delivery);
        // Even when excluded containers are listed, the delivery container is not
        JsonObject o = JsonParser.parseString(new String(ManagerIndex.build(stash, true, "Bot", "1.21.4", "overworld", id -> null).json(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(10, o.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(1, o.getAsJsonObject("containers").get("total").getAsInt());
    }

    @Test
    void searchAndTheIndexNeverListTheDeliveryContainer() {
        Stash stash = new Stash("s", "2b2t.org");
        Stash.Box stock = box(item("diamond", 10, 64, "minecraft:diamond"));
        Stash.Box delivery = box(item("diamond", 999, 64, "minecraft:diamond"));
        delivery.delivery = true;
        stash.boxes.add(stock);
        stash.boxes.add(delivery);
        var results = stash.search("diamond");
        assertEquals(1, results.size());
        assertEquals(10, results.get(0).total);
        assertEquals(1, results.get(0).spots.size());
        assertTrue(results.get(0).spots.get(0).box() == stock);
    }

    @Test
    void anOrdersShulkerKeyIsTheOneTheIndexListsItUnder() {
        Item shulker = item("red_shulker_box", 1, 1, "minecraft:red_shulker_box");
        Item a = item("diamond", 64, 64, "minecraft:diamond");
        a.slot = 0;
        Item b = item("ender_pearl", 16, 16, "minecraft:ender_pearl");
        b.slot = 5;
        shulker.contents = new ArrayList<>(List.of(a, b));

        Stash stash = new Stash("s", "2b2t.org");
        stash.boxes.add(box(shulker));
        JsonObject o = JsonParser.parseString(new String(built(stash).json(), StandardCharsets.UTF_8)).getAsJsonObject();
        String listed = o.getAsJsonArray("shulkers").get(0).getAsJsonObject().get("key").getAsString();
        assertEquals(listed, ManagerIndex.shulkerKey(shulker));
        assertTrue(listed.startsWith("s:") && listed.length() == 12);

        // Not a shulker with something in it: no key
        assertNull(ManagerIndex.shulkerKey(item("red_shulker_box", 1, 1, "minecraft:red_shulker_box")));
        assertNull(ManagerIndex.shulkerKey(a));
        assertNotNull(ManagerIndex.shulkerKey(shulker));
    }
}
