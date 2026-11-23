package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.FireworkFixtures;
import com.porter.qwertestash.orders.FireworkSpecs;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone B3 on the bot's manager side (CRAFT-CONTRACT-v1.2.md 2.1, 4 and 13.2): the extras of the real B3 server are accepted with the server's own hash (the
 * {@code special}, {@code dye} and {@code star} lines), the heartbeat says {@code firework} behind its setting, and the index carries {@code items[].spec}
 * only for a bot that says so.
 */
class FireworkIndexTest {
    // The extras and their hash

    @Test
    void theRealB3ServersExtrasAreAcceptedWithTheServersHash() throws Exception {
        Extras e = FireworkFixtures.EXTRAS;
        assertEquals(FireworkFixtures.SERVER_HASH, e.hash());
        assertEquals(FireworkFixtures.SERVER_HASH, Extras.compute(FireworkFixtures.extrasJson()));
        // The normalised copy (validated fields only) hashes to the same value, and parses again
        assertEquals(FireworkFixtures.SERVER_HASH, Extras.compute(e.json()));
        assertEquals(FireworkFixtures.SERVER_HASH, Extras.parse(e.json()).hash());
        // It holds the three tables of B3 next to B2's
        assertEquals(3, e.json().getAsJsonArray("special").size());
        assertEquals(16, e.json().getAsJsonObject("dyes").size());
        assertEquals("b3312c", e.json().getAsJsonObject("dyes").getAsJsonObject("red").get("firework").getAsString());
        assertFalse(e.json().getAsJsonObject("dyes").getAsJsonObject("red").has("armor"), "the armour colours are B5's: left out, hashed as empty");
        assertEquals(6, e.json().getAsJsonObject("starItems").size());
        assertEquals(7, e.json().getAsJsonObject("starItems").getAsJsonArray("creeper").size());
        assertEquals(200, e.json().get("cookTicks").getAsInt());
    }

    /** The contract's 13.2 written out again from the raw body, without {@link Extras}: the lines of every section, sorted, hashed. */
    private static String independentHash(JsonObject o) throws Exception {
        List<String> lines = new ArrayList<>();
        if (o.has("cookTicks")) lines.add("cook\t" + o.get("cookTicks").getAsLong());
        for (JsonElement e : o.getAsJsonArray("smelt")) {
            JsonObject s = e.getAsJsonObject();
            List<String> in = new ArrayList<>();
            for (JsonElement i : s.getAsJsonArray("in")) in.add(i.getAsString());
            Collections.sort(in);
            lines.add("smelt\t" + s.get("id").getAsString() + "\t" + s.get("out").getAsString() + "\t" + s.get("count").getAsInt() + "\t" + String.join(",", in));
        }
        for (JsonElement e : o.getAsJsonArray("fuel")) lines.add("fuel\t" + e.getAsJsonObject().get("id").getAsString() + "\t" + e.getAsJsonObject().get("ticks").getAsLong());
        for (JsonElement e : o.getAsJsonArray("special")) lines.add("special\t" + e.getAsString());
        for (var d : o.getAsJsonObject("dyes").entrySet()) {
            JsonObject v = d.getValue().getAsJsonObject();
            lines.add("dye\t" + d.getKey() + "\t" + (v.has("armor") ? v.get("armor").getAsString() : "") + "\t" + (v.has("firework") ? v.get("firework").getAsString() : ""));
        }
        for (var r : o.getAsJsonObject("starItems").entrySet()) {
            List<String> ids = new ArrayList<>();
            for (JsonElement i : r.getValue().getAsJsonArray()) ids.add(i.getAsString());
            Collections.sort(ids);
            lines.add("star\t" + r.getKey() + "\t" + String.join(",", ids));
        }
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder("extras-v1\n");
        for (String l : lines) sb.append(l).append('\n');
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theSpecialDyeAndStarLinesAreHashedAsSection13_2Says() throws Exception {
        assertEquals(FireworkFixtures.SERVER_HASH, independentHash(FireworkFixtures.extrasJson()));
        // A small body of the three sections, the hash made outside this code (sha1sum of the text, lines sorted: dye, special, star)
        JsonObject small = JsonParser.parseString("""
            { "v": 1, "hash": "x",
              "special": [ "rocket" ],
              "dyes": { "red": { "firework": "b3312c" } },
              "starItems": { "trail": [ "minecraft:diamond" ] } }
            """).getAsJsonObject();
        assertEquals("2d2e7faa5eda824646e8e2706eea8849f75d20a1", Extras.compute(small));
        small.addProperty("hash", "2d2e7faa5eda824646e8e2706eea8849f75d20a1");
        assertEquals("2d2e7faa5eda824646e8e2706eea8849f75d20a1", Extras.parse(small).hash());
        // Each section matters: another colour, a special kind, an item or a role is another hash and the body that states the old one is refused
        for (String change : new String[] {"colour", "special", "item", "role"}) {
            JsonObject o = JsonParser.parseString(small.toString()).getAsJsonObject();
            switch (change) {
                case "colour" -> o.getAsJsonObject("dyes").getAsJsonObject("red").addProperty("firework", "b3312d");
                case "special" -> o.getAsJsonArray("special").add("star");
                case "item" -> o.getAsJsonObject("starItems").getAsJsonArray("trail").add("minecraft:feather");
                default -> o.getAsJsonObject("starItems").add("twinkle", new JsonArray());
            }
            assertNotEquals("2d2e7faa5eda824646e8e2706eea8849f75d20a1", Extras.compute(o), change);
            assertThrows(RecipeStore.Invalid.class, () -> Extras.parse(o), change);
        }
        // A dye with an armour colour is another line: dye TAB name TAB armor TAB firework
        JsonObject withArmor = JsonParser.parseString(small.toString()).getAsJsonObject();
        withArmor.getAsJsonObject("dyes").getAsJsonObject("red").addProperty("armor", "b02e26");
        assertEquals(independentHash(withB2Parts(withArmor)), Extras.compute(withArmor));
    }

    private static JsonObject withB2Parts(JsonObject o) {
        o.add("smelt", new JsonArray());
        o.add("fuel", new JsonArray());
        return o;
    }

    // The heartbeat

    @Test
    void fireworkIsInTheCanOnlyWithTheSettingAndTheTables() {
        Extras b3 = FireworkFixtures.EXTRAS;
        assertEquals(List.of("smelt", "firework"), ManagerIndex.craftCan(b3, true, true));
        assertEquals(List.of("firework"), ManagerIndex.craftCan(b3, false, true));
        assertEquals(List.of("smelt"), ManagerIndex.craftCan(b3, true, false));
        assertTrue(ManagerIndex.craftCan(b3, false, false).isEmpty());
        // The B2 behaviour of the two-argument form is unchanged
        assertEquals(List.of("smelt"), ManagerIndex.craftCan(b3, true));
        assertTrue(ManagerIndex.craftCan(null, true, true).isEmpty());
        // Extras with the special, dye and star tables missing say none
        JsonObject noStars = JsonParser.parseString(FireworkFixtures.extrasJson().toString()).getAsJsonObject();
        noStars.remove("starItems");
        assertNull(FireworkSpecs.Tables.of(noStars));
        JsonObject noDyes = JsonParser.parseString(FireworkFixtures.extrasJson().toString()).getAsJsonObject();
        noDyes.remove("dyes");
        assertNull(FireworkSpecs.Tables.of(noDyes));
        JsonObject empty = JsonParser.parseString("{\"v\":1,\"hash\":\"" + Extras.EMPTY_HASH + "\"}").getAsJsonObject();
        try {
            assertTrue(ManagerIndex.craftCan(Extras.parse(empty), true, true).isEmpty());
        } catch (RecipeStore.Invalid e) {
            throw new AssertionError(e);
        }
        assertNull(ManagerIndex.fireworkTables(b3, false));
        assertNotNull(ManagerIndex.fireworkTables(b3, true));
        assertNull(ManagerIndex.fireworkTables(null, true));
    }

    @Test
    void theHeartbeatSaysFireworkAndTheB3ExtrasHash() {
        ManagerIndex.CraftOrders base = new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "ok", 2048, List.of());
        ManagerIndex.CraftOrders on = base.withV2(FireworkFixtures.SERVER_HASH, ManagerIndex.craftCan(FireworkFixtures.EXTRAS, true, true)).withSmelt(256, null);
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] bytes = ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(on, null));
        JsonObject o = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("craftOrders");
        assertEquals(2, o.get("v").getAsInt());
        assertEquals(FireworkFixtures.SERVER_HASH, o.get("extras").getAsString());
        assertEquals("smelt", o.getAsJsonArray("can").get(0).getAsString());
        assertEquals("firework", o.getAsJsonArray("can").get(1).getAsString());
        assertEquals(256, o.get("maxSmelt").getAsInt());
        // Firework alone does not smelt: no maxSmelt
        ManagerIndex.CraftOrders only = base.withV2(FireworkFixtures.SERVER_HASH, List.of("firework")).withSmelt(256, null);
        JsonObject o2 = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(only, null)), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("craftOrders");
        assertEquals(1, o2.getAsJsonArray("can").size());
        assertFalse(o2.has("maxSmelt"));
        // An older server that refused the newer fields gets the 1.15.0 shape: no can
        JsonObject legacy = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(on.asLegacy(), null)), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("craftOrders");
        assertFalse(legacy.has("can"));
    }

    // The index

    private static Item stack(String id, String keyHash, String fw, int count) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = 64;
        it.key = "minecraft:" + id + (keyHash == null ? "" : "#" + keyHash);
        it.variant = it.key;
        it.fw = fw;
        return it;
    }

    private static Stash stash(Item... items) {
        Stash stash = new Stash("s", "2b2t.org");
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        for (int i = 0; i < items.length; i++) box.contents[i] = items[i];
        stash.boxes.add(box);
        return stash;
    }

    private static JsonObject index(Stash stash, String player, FireworkSpecs.Tables tables) {
        ManagerIndex.Built b = ManagerIndex.build(stash, false, player, "1.21.4", "overworld", id -> null, null, tables);
        return JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Map<String, JsonObject> byKey(JsonObject idx) {
        Map<String, JsonObject> out = new java.util.LinkedHashMap<>();
        for (JsonElement e : idx.getAsJsonArray("items")) out.put(e.getAsJsonObject().get("key").getAsString(), e.getAsJsonObject());
        return out;
    }

    private static final int RED = Integer.parseInt("b3312c", 16), BLUE = Integer.parseInt("253192", 16);

    private Stash firework() {
        return stash(
            stack("firework_rocket", null, FireworkSpecs.encodeRocket(1, List.of()), 64),
            stack("firework_rocket", "ab12cd34", FireworkSpecs.encodeRocket(2, List.of(FireworkSpecs.Explosion.of(0, new int[] {RED}, new int[0], false, false))), 16),
            stack("firework_star", "11112222", FireworkSpecs.encodeStar(FireworkSpecs.Explosion.of(1, new int[] {RED, BLUE}, new int[0], true, true)), 8),
            stack("firework_star", "33334444", FireworkSpecs.encodeStar(FireworkSpecs.Explosion.of(1, new int[] {0x123456}, new int[0], false, false)), 3),
            stack("firework_rocket", "55556666", null, 2),
            stack("gunpowder", null, null, 30));
    }

    @Test
    void theIndexCarriesTheSpecOfEachRocketAndStarForABotThatSaysFirework() {
        Map<String, JsonObject> items = byKey(index(firework(), "Bot", FireworkFixtures.TABLES));
        assertEquals("rocket:1", items.get("minecraft:firework_rocket").get("spec").getAsString(), "a plain rocket is rocket:1 (S1)");
        assertEquals("rocket:2|small_ball;red;;", items.get("minecraft:firework_rocket#ab12cd34").get("spec").getAsString());
        assertEquals("star:large_ball;red,blue;;tw", items.get("minecraft:firework_star#11112222").get("spec").getAsString());
        assertFalse(items.get("minecraft:firework_star#33334444").has("spec"), "a colour not in the table: no spec (S11)");
        assertFalse(items.get("minecraft:firework_rocket#55556666").has("spec"), "a rocket with another component: no spec (S13)");
        assertFalse(items.get("minecraft:gunpowder").has("spec"));
        // The player field is there (the server accepts a spec only from the stash's owner)
        assertEquals("Bot", index(firework(), "Bot", FireworkFixtures.TABLES).get("player").getAsString());
    }

    @Test
    void withoutFireworkInTheCanOrWithoutAPlayerTheIndexIsWhatItWasBeforeB3() {
        ManagerIndex.Built before = ManagerIndex.build(firework(), false, "Bot", "1.21.4", "overworld", id -> null);
        ManagerIndex.Built noTables = ManagerIndex.build(firework(), false, "Bot", "1.21.4", "overworld", id -> null, null, null);
        assertEquals(before.hash(), noTables.hash());
        assertEquals(new String(before.json(), StandardCharsets.UTF_8).replaceAll("\"generatedAt\":\\d+", ""), new String(noTables.json(), StandardCharsets.UTF_8).replaceAll("\"generatedAt\":\\d+", ""));
        for (JsonObject o : byKey(index(firework(), "Bot", null)).values()) assertFalse(o.has("spec"));
        // No player: the server would refuse the whole index for a spec, so none is sent
        for (JsonObject o : byKey(index(firework(), "", FireworkFixtures.TABLES)).values()) assertFalse(o.has("spec"));
        // The spec is part of the index hash (the contract: it changes once)
        ManagerIndex.Built with = ManagerIndex.build(firework(), false, "Bot", "1.21.4", "overworld", id -> null, null, FireworkFixtures.TABLES);
        assertNotEquals(before.hash(), with.hash());
        // ... and stable: the same stash gives the same hash
        assertEquals(with.hash(), ManagerIndex.build(firework(), false, "Bot", "1.21.4", "overworld", id -> null, null, FireworkFixtures.TABLES).hash());
    }

    @Test
    void theSpecFollowsTheExtrasColourTable() {
        // A table where red is another colour: the stack that was red has no spec now (it is derived again from what the stash saved)
        JsonObject j = JsonParser.parseString(FireworkFixtures.extrasJson().toString()).getAsJsonObject();
        j.getAsJsonObject("dyes").getAsJsonObject("red").addProperty("firework", "000001");
        FireworkSpecs.Tables other = FireworkSpecs.Tables.of(j);
        Map<String, JsonObject> items = byKey(index(firework(), "Bot", other));
        assertFalse(items.get("minecraft:firework_rocket#ab12cd34").has("spec"));
        assertEquals("rocket:1", items.get("minecraft:firework_rocket").get("spec").getAsString());
    }
}
