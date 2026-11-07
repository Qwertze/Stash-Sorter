package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The index's durability fields: damage and maxDamage, only for a damaged, damageable item. */
class IndexDurabilityTest {
    private static Item item(String id, int damage, int maxDamage) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = 1;
        it.maxStack = 1;
        it.key = id + damage;
        it.variant = it.key;
        it.damage = damage;
        it.maxDamage = maxDamage;
        it.damaged = damage > 0;
        return it;
    }

    private static JsonObject entry(Item... items) {
        Stash stash = new Stash("s", "2b2t.org");
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        for (int i = 0; i < items.length; i++) box.contents[i] = items[i];
        stash.boxes.add(box);
        ManagerIndex.Built b = ManagerIndex.build(stash, false, "Bot", "1.21.4", "overworld", id -> null);
        JsonArray arr = JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items");
        for (JsonElement e : arr) return e.getAsJsonObject();
        throw new AssertionError("no items");
    }

    @Test
    void aDamagedDamageableItemHasBothFields() {
        JsonObject o = entry(item("diamond_pickaxe", 120, 1561));
        assertEquals(120, o.get("damage").getAsInt());
        assertEquals(1561, o.get("maxDamage").getAsInt());
    }

    @Test
    void anUndamagedOrUndamageableItemHasNeither() {
        for (JsonObject o : List.of(entry(item("diamond_pickaxe", 0, 1561)), entry(item("stone", 0, 0)), entry(item("stone", 5, 0)))) {
            assertFalse(o.has("damage"));
            assertFalse(o.has("maxDamage"));
        }
        assertTrue(ManagerIndex.durability(1, 0)[0] == 0 && ManagerIndex.durability(0, 9)[1] == 0);
    }
}
