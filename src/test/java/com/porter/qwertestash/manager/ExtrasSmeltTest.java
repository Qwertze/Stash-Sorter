package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.FurnaceTripTest;
import com.porter.qwertestash.orders.Smelting;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The B2 extras (cook, smelt and fuel lines) against the hash the server computes, and what the heartbeat says about smelting. */
class ExtrasSmeltTest {
    /** The hash the real server (HandRecipes, B2, 1.21.4 and 1.21.11 alike) gives its extras: the fixture is that server's own body. */
    private static final String SERVER_HASH = "1178d68b41c4a2e3dc4b103f33aab2de84f70022";
    private static final String CONTRACT_VECTOR = "1c59d6974fee7188e308981d792cdd0df4e7e31b";

    @Test
    void theServersExtrasAreAcceptedWithTheServersHash() throws Exception {
        Extras e = FurnaceTripTest.extras();
        assertEquals(SERVER_HASH, e.hash());
        assertNotNull(Smelting.of(e));
        assertEquals(200, e.json().get("cookTicks").getAsInt());
        assertTrue(e.json().getAsJsonArray("smelt").size() >= 15);
        // The normalised copy hashes to the same value again
        assertEquals(SERVER_HASH, Extras.compute(e.json()));
    }

    @Test
    void theContractsSecondHashVectorIsReproduced() throws Exception {
        JsonObject o = JsonParser.parseString("""
            { "v": 1, "hash": "%s", "cookTicks": 200,
              "smelt": [ { "id": "qwerte:smelt/glass", "in": ["minecraft:red_sand", "minecraft:sand"], "out": "minecraft:glass", "count": 1 } ],
              "fuel": [ { "id": "minecraft:coal", "ticks": 1600 } ] }
            """.formatted(CONTRACT_VECTOR)).getAsJsonObject();
        assertEquals(CONTRACT_VECTOR, Extras.compute(o));
        assertEquals(CONTRACT_VECTOR, Extras.parse(o).hash());
        // A changed cook time is another hash: the block that states the old one is refused
        o.addProperty("cookTicks", 100);
        assertThrows(RecipeStore.Invalid.class, () -> Extras.parse(o));
    }

    @Test
    void smeltIsSaidOnlyWithTheSmeltAndFuelTablesAndTheSettingOn() throws Exception {
        Extras e = FurnaceTripTest.extras();
        assertEquals(List.of("smelt"), ManagerIndex.craftCan(e, true));
        assertTrue(ManagerIndex.craftCan(e, false).isEmpty());
        assertTrue(ManagerIndex.craftCan(null, true).isEmpty());
        JsonObject empty = JsonParser.parseString("{\"v\":1,\"hash\":\"" + Extras.EMPTY_HASH + "\"}").getAsJsonObject();
        assertTrue(ManagerIndex.craftCan(Extras.parse(empty), true).isEmpty(), "empty extras: no smelting");
        // Smelt recipes without fuel are no smelting either
        JsonObject noFuel = JsonParser.parseString("""
            { "v": 1, "hash": "x", "cookTicks": 200,
              "smelt": [ { "id": "qwerte:smelt/glass", "in": ["minecraft:sand"], "out": "minecraft:glass", "count": 1 } ] }
            """).getAsJsonObject();
        noFuel.addProperty("hash", Extras.compute(noFuel));
        assertNull(Smelting.of(Extras.parse(noFuel)));
        assertEquals(1, ManagerIndex.clampMaxSmelt(0), "maxSmelt is at least 1 or the server sees a bot that can't smelt");
        assertEquals(256, ManagerIndex.clampMaxSmelt(1000));
        assertEquals(256, ManagerIndex.clampMaxSmelt(256));
    }

    private static JsonObject craftOrders(ManagerIndex.CraftOrders c) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] bytes = ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(c, null));
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("craftOrders");
    }

    @Test
    void theHeartbeatSaysSmeltMaxSmeltAndTheFurnace() {
        ManagerIndex.CraftOrders base = new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "ok", 2048, List.of());
        ManagerIndex.CraftOrders on = base.withV2(SERVER_HASH, List.of("smelt")).withSmelt(256, new ManagerIndex.BotTable("1,64,2", "placed"));
        JsonObject o = craftOrders(on);
        assertEquals(2, o.get("v").getAsInt());
        assertEquals("smelt", o.getAsJsonArray("can").get(0).getAsString());
        assertEquals(256, o.get("maxSmelt").getAsInt());
        assertEquals("1,64,2", o.getAsJsonObject("botFurnace").get("pos").getAsString());
        assertEquals("placed", o.getAsJsonObject("botFurnace").get("source").getAsString());
        // No furnace yet: an explicit null (clears what the server kept); a maxSmelt of 0 is said as 1
        JsonObject none = craftOrders(base.withV2(SERVER_HASH, List.of("smelt")).withSmelt(0, null));
        assertTrue(none.get("botFurnace").isJsonNull());
        assertEquals(1, none.get("maxSmelt").getAsInt());
        // Without smelt in can: no maxSmelt
        JsonObject off = craftOrders(base.withV2(SERVER_HASH, List.of()).withSmelt(256, null));
        assertFalse(off.has("maxSmelt"));
        assertEquals(0, off.getAsJsonArray("can").size());
        // An older server that refused the newer fields gets the 1.15.0 shape: none of it
        JsonObject legacy = craftOrders(on.asLegacy());
        assertEquals(1, legacy.get("v").getAsInt());
        assertFalse(legacy.has("maxSmelt"));
        assertFalse(legacy.has("botFurnace"));
        assertFalse(legacy.has("can"));
    }
}
