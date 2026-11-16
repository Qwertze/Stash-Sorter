package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.TestRecipes;
import com.porter.qwertestash.stash.Stash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The extras block (CRAFT-CONTRACT-v1.2.md 13): the hash vectors, the cache beside the table, the fetch rules, the heartbeat fields and the refetch rule. */
class ExtrasTest {
    private static final String EMPTY = "c927f92c1e5bc11c5a34a36b24103c44cf61d038", FULL = "1c59d6974fee7188e308981d792cdd0df4e7e31b";
    private static final String TABLE = TestRecipes.json();

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String emptyExtras() {
        return "{\"v\":1,\"hash\":\"" + EMPTY + "\"}";
    }

    private static String fullExtras() {
        return "{\"v\":1,\"hash\":\"" + FULL + "\",\"cookTicks\":200,\"smelt\":[{\"id\":\"qwerte:smelt/glass\",\"in\":[\"minecraft:sand\",\"minecraft:red_sand\"],"
            + "\"out\":\"minecraft:glass\",\"count\":1}],\"fuel\":[{\"id\":\"minecraft:coal\",\"ticks\":1600}],\"special\":[],\"dyes\":{},\"starItems\":{},\"stew\":{},\"dyeable\":[]}";
    }

    /** The test table with an extras object added at the top, as {@code ?extras=1} answers. */
    private static String body(String extras) {
        JsonObject root = obj(TABLE);
        if (extras != null) root.add("extras", obj(extras));
        return root.toString();
    }

    // The hash vectors

    @Test
    void theEmptyExtrasHashVector() throws Exception {
        assertEquals(EMPTY, Extras.compute(obj("{\"v\":1}")));
        assertEquals(EMPTY, Extras.EMPTY_HASH);
        assertEquals(EMPTY, Extras.parse(obj(emptyExtras())).hash());
        // Empty sections are no lines
        assertEquals(EMPTY, Extras.compute(obj("{\"v\":1,\"smelt\":[],\"fuel\":[],\"special\":[],\"dyes\":{},\"starItems\":{},\"stew\":{},\"dyeable\":[]}")));
    }

    @Test
    void theCookFuelAndSmeltHashVector() throws Exception {
        // The inputs are given unsorted: the hash sorts them
        assertEquals(FULL, Extras.compute(obj(fullExtras())));
        assertEquals(FULL, Extras.parse(obj(fullExtras())).hash());
    }

    @Test
    void theLinesAreSortedAndEveryKindIsHashed() throws Exception {
        // Every line kind; the expected value is computed by hand from the contract's line formats
        String json = "{\"v\":1,\"special\":[\"star\",\"rocket\"],\"dyes\":{\"red\":{\"armor\":\"b02e26\",\"firework\":\"b3312c\"}},\"starItems\":{\"creeper\":[\"b:2\",\"a:1\"]},"
            + "\"stew\":{\"minecraft:poppy\":{\"effect\":\"minecraft:night_vision\",\"ticks\":100}},\"dyeable\":[\"minecraft:leather_boots\"]}";
        String text = "extras-v1\n" + "dye\tred\tb02e26\tb3312c\n" + "dyeable\tminecraft:leather_boots\n" + "special\trocket\n" + "special\tstar\n"
            + "star\tcreeper\ta:1,b:2\n" + "stew\tminecraft:poppy\tminecraft:night_vision\t100\n";
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, Extras.compute(obj(json)));
    }

    @Test
    void aBlockWhoseHashIsNotItsContentsIsRefused() {
        String wrong = fullExtras().replace(FULL, EMPTY);
        assertThrows(RecipeStore.Invalid.class, () -> Extras.parse(obj(wrong)));
        assertThrows(RecipeStore.Invalid.class, () -> Extras.parse(obj("{\"v\":2,\"hash\":\"" + EMPTY + "\"}")));
        assertThrows(RecipeStore.Invalid.class, () -> Extras.parse(obj("{\"v\":1}")));
        assertThrows(RecipeStore.Invalid.class, () -> Extras.parse(obj("{\"v\":1,\"hash\":\"" + EMPTY + "\",\"fuel\":[{\"id\":\"nope\",\"ticks\":1}]}")));
        assertThrows(RecipeStore.Invalid.class, () -> Extras.parse(obj("{\"v\":1,\"hash\":\"" + EMPTY + "\",\"fuel\":\"x\"}")));
    }

    @Test
    void onlyTheValidatedFieldsAreKept() throws Exception {
        String json = "{\"v\":1,\"hash\":\"" + FULL + "\",\"cookTicks\":200,\"surprise\":{\"a\":1},\"smelt\":[{\"id\":\"qwerte:smelt/glass\",\"in\":[\"minecraft:sand\","
            + "\"minecraft:red_sand\"],\"out\":\"minecraft:glass\",\"count\":1,\"extra\":true}],\"fuel\":[{\"id\":\"minecraft:coal\",\"ticks\":1600,\"note\":\"x\"}]}";
        Extras e = Extras.parse(obj(json));
        assertEquals(FULL, e.hash());
        assertFalse(e.json().has("surprise"));
        assertFalse(e.json().getAsJsonArray("smelt").get(0).getAsJsonObject().has("extra"));
        assertFalse(e.json().getAsJsonArray("fuel").get(0).getAsJsonObject().has("note"));
        // What is kept still hashes to the same value
        assertEquals(FULL, Extras.compute(e.json()));
        assertEquals(EMPTY, Extras.parse(obj(emptyExtras().replace("}", ",\"junk\":[1]}"))).hash());
    }

    @Test
    void aBodyJustUnderTheCapIsLoadableAfterItIsCached(@TempDir Path dir) throws Exception {
        // Padded to just under MAX_BYTES with whitespace; the cache file adds a wrapper and must still load
        RecipeStore store = new RecipeStore(dir);
        String base = body(emptyExtras());
        String padded = base + " ".repeat((int) RecipeStore.MAX_BYTES - base.length());
        assertTrue(padded.length() <= RecipeStore.MAX_BYTES);
        store.acceptAll("1.21.4", padded, null);
        assertTrue(java.nio.file.Files.size(store.file("1.21.4")) <= RecipeStore.MAX_FILE_BYTES);
        assertNotNull(store.loadAll("1.21.4"));
    }

    // The fetch (2.4)

    @Test
    void anOldServersAnswerHasNoExtras(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        RecipeStore.Loaded l = store.acceptAll("1.21.4", body(null), "\"" + com.porter.qwertestash.orders.Recipes.parse(new java.io.StringReader(TABLE)).hash() + "\"");
        assertNull(l.extras());
        assertNull(l.extrasRefused());
        assertNull(l.extrasHash());
        assertNull(store.loadAll("1.21.4").extras());
    }

    @Test
    void anAnswerWithExtrasIsCachedAndLoadedAgain(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        String table = com.porter.qwertestash.orders.Recipes.parse(new java.io.StringReader(TABLE)).hash();
        RecipeStore.Loaded l = store.acceptAll("1.21.4", body(emptyExtras()), "\"" + table + "+" + EMPTY + "\"");
        assertNotNull(l.extras());
        assertEquals(EMPTY, l.extras().hash());
        assertEquals(table, l.table().hash());
        RecipeStore.Loaded again = store.loadAll("1.21.4");
        assertEquals(EMPTY, again.extras().hash());
        assertEquals(table, again.table().hash());
        // An older plugin's reader (load) still takes the file
        assertEquals(table, store.load("1.21.4").hash());
    }

    @Test
    void theEtagOfTheExtrasForm() {
        String t = "a".repeat(40), e = "b".repeat(40);
        assertEquals(t, RecipeStore.hashOfEtag("\"" + t + "+" + e + "\""));
        assertEquals(t, RecipeStore.hashOfEtag("\"" + t + "\""));
        assertEquals(e, Extras.hashOfEtag("W/\"" + t + "+" + e + "\""));
        assertNull(Extras.hashOfEtag("\"" + t + "\""));
        assertNull(Extras.hashOfEtag(null));
        assertEquals("\"" + t + "+" + e + "\"", RecipeStore.quote(t, e));
        assertEquals("\"" + t + "\"", RecipeStore.quote(t, null));
    }

    @Test
    void extrasWhoseHashIsWrongAreDroppedButTheTableIsKept(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        String table = com.porter.qwertestash.orders.Recipes.parse(new java.io.StringReader(TABLE)).hash();
        RecipeStore.Loaded l = store.acceptAll("1.21.4", body(fullExtras().replace(FULL, EMPTY)), "\"" + table + "+" + EMPTY + "\"");
        assertNull(l.extras());
        assertNotNull(l.extrasRefused());
        assertEquals(EMPTY, l.extrasHash(), "what the ETag said, so the bot doesn't ask again for the same hash");
        assertEquals(table, l.table().hash());
        assertNull(store.loadAll("1.21.4").extras());
    }

    @Test
    void anEtagThatNamesExtrasTheBodyDoesNotCarryIsRefused(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        String table = com.porter.qwertestash.orders.Recipes.parse(new java.io.StringReader(TABLE)).hash();
        RecipeStore.Loaded l = store.acceptAll("1.21.4", body(null), "\"" + table + "+" + EMPTY + "\"");
        assertNull(l.extras());
        assertNotNull(l.extrasRefused());
        // Extras that don't match the ETag's second half
        RecipeStore.Loaded other = store.acceptAll("1.21.4", body(fullExtras()), "\"" + table + "+" + EMPTY + "\"");
        assertNull(other.extras());
        assertNotNull(other.extrasRefused());
    }

    @Test
    void aTableThatDoesNotMatchTheEtagIsStillRefused(@TempDir Path dir) {
        RecipeStore store = new RecipeStore(dir);
        assertThrows(RecipeStore.Invalid.class, () -> store.acceptAll("1.21.4", body(emptyExtras()), "\"" + "0".repeat(40) + "+" + EMPTY + "\""));
    }

    @Test
    void aCachedBlockWhoseHashChangedIsIgnoredNotTheTable(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        String table = com.porter.qwertestash.orders.Recipes.parse(new java.io.StringReader(TABLE)).hash();
        store.acceptAll("1.21.4", body(fullExtras()), "\"" + table + "+" + FULL + "\"");
        Path file = store.file("1.21.4");
        String edited = java.nio.file.Files.readString(file).replace("\"ticks\":1600", "\"ticks\":1700");
        java.nio.file.Files.writeString(file, edited);
        RecipeStore.Loaded l = store.loadAll("1.21.4");
        assertNotNull(l);
        assertNull(l.extras());
        assertEquals(table, l.table().hash());
    }

    // The refetch rule (2.2)

    @Test
    void theBotAsksAgainWhenTheServersExtrasHashDiffers() {
        RecipeSync sync = new RecipeSync();
        String table = "a".repeat(40);
        long now = 1_000_000;
        sync.serverHash(table);
        assertFalse(sync.due(now, table, null), "an older server says no extrasHash: nothing to ask for");
        sync.serverExtras(EMPTY);
        assertTrue(sync.due(now, table, null));
        assertFalse(sync.due(now, table, EMPTY));
        sync.serverExtras(FULL);
        assertTrue(sync.due(now, table, EMPTY));
        // Refused ones are not asked for again while the hash stays
        sync.extrasRefused(FULL);
        assertFalse(sync.due(now, table, EMPTY));
        sync.serverExtras("c".repeat(40));
        assertTrue(sync.due(now, table, EMPTY));
        sync.reset();
        sync.serverHash(table);
        assertFalse(sync.due(now, table, null));
    }

    @Test
    void theHeartbeatReplyCarriesTheExtrasHash() {
        assertEquals(EMPTY, ManagerIndex.craftReply("{\"recipesHash\":\"" + "a".repeat(40) + "\",\"extrasHash\":\"" + EMPTY + "\"}").extrasHash());
        assertNull(ManagerIndex.craftReply("{\"recipesHash\":\"" + "a".repeat(40) + "\"}").extrasHash());
        assertNull(ManagerIndex.craftReply("{\"extrasHash\":\"nope\"}").extrasHash());
    }

    // The heartbeat (2.1)

    private static JsonObject craftOrders(ManagerIndex.CraftOrders c) {
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        byte[] bytes = ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(c, null));
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("craftOrders");
    }

    @Test
    void theHeartbeatSaysV2WithExtrasAndCan() {
        ManagerIndex.CraftOrders base = new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "ok", 2048, List.of());
        JsonObject none = craftOrders(base.withV2(null, ManagerIndex.craftCan()));
        assertEquals(2, none.get("v").getAsInt());
        assertTrue(none.has("extras") && none.get("extras").isJsonNull(), "the key is required when v >= 2: null for none");
        assertEquals(0, none.getAsJsonArray("can").size());
        assertFalse(none.has("maxSmelt"));
        JsonObject held = craftOrders(base.withV2(EMPTY, List.of()));
        assertEquals(EMPTY, held.get("extras").getAsString());
        // Unknown words and bad ones are left out (the closed set is the server's to extend)
        JsonObject can = craftOrders(base.withV2(null, List.of("smelt", "Bad Word", "firework")));
        assertEquals(2, can.getAsJsonArray("can").size());
    }

    @Test
    void anOlderServerThatRefusedTheNewFieldsGetsTheOldShape() {
        ManagerIndex.CraftOrders legacy = new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "auto", 2048, List.of()).withV2(EMPTY, List.of()).asLegacy();
        JsonObject o = craftOrders(legacy);
        assertEquals(1, o.get("v").getAsInt());
        assertFalse(o.has("extras"));
        assertFalse(o.has("can"));
        assertEquals("unset", o.get("table").getAsString());
    }

    @Test
    void thisBuildSaysNoFeaturesYet() {
        assertTrue(ManagerIndex.craftCan().isEmpty());
    }
}
