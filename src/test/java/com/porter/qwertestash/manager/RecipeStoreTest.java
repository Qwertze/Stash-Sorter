package com.porter.qwertestash.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.Recipes;
import com.porter.qwertestash.orders.TestRecipes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fetched recipe table: checked, hashed, cached atomically, read back with its hash computed again (CRAFT-CONTRACT.md "Recipe tables for the bots"). */
class RecipeStoreTest {
    private static final String BODY = TestRecipes.json();

    @Test
    void aTableIsCheckedHashedAndCachedAndReadBack(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        assertNull(store.load("1.21.4"));
        Recipes t = store.accept("1.21.4", BODY, RecipeStore.quote(TestRecipes.table().hash()));
        assertEquals(TestRecipes.table().hash(), t.hash());
        assertTrue(Files.exists(store.file("1.21.4")));
        assertFalse(Files.exists(dir.resolve("recipes-1.21.4.json.tmp")), "no temp file is left");
        Recipes back = store.load("1.21.4");
        assertNotNull(back);
        assertEquals(t.hash(), back.hash());
        assertEquals(t.size(), back.size());
    }

    @Test
    void anEtagThatIsNotTheTablesHashRefusesIt(@TempDir Path dir) {
        RecipeStore store = new RecipeStore(dir);
        assertThrows(RecipeStore.Invalid.class, () -> store.accept("1.21.4", BODY, RecipeStore.quote("0".repeat(40))));
        assertFalse(Files.exists(store.file("1.21.4")), "nothing is cached from a refused table");
        // An ETag that is not a hash is only a hint and is ignored
        assertDoesNotThrowAccept(store, "W/\"abc\"");
    }

    private static void assertDoesNotThrowAccept(RecipeStore store, String etag) {
        try {
            store.accept("1.21.4", BODY, etag);
        } catch (RecipeStore.Invalid e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aCachedTableWhoseHashIsNotItsFilesIsDiscarded(@TempDir Path dir) throws Exception {
        RecipeStore store = new RecipeStore(dir);
        store.accept("1.21.4", BODY, null);
        // Changed by hand: a result differs, the stored hash is the old one
        String text = Files.readString(store.file("1.21.4"));
        int at = text.indexOf("\"count\":4");
        Files.writeString(store.file("1.21.4"), text.substring(0, at) + "\"count\":5" + text.substring(at + 9));
        assertNull(store.load("1.21.4"));
        assertFalse(Files.exists(store.file("1.21.4")), "the damaged file is removed");
        // Not JSON at all
        Files.writeString(store.file("1.21.4"), "garbage");
        assertNull(store.load("1.21.4"));
    }

    @Test
    void aTableForAnotherVersionOrOfTheWrongShapeIsRefused(@TempDir Path dir) {
        RecipeStore store = new RecipeStore(dir);
        assertThrows(RecipeStore.Invalid.class, () -> store.accept("1.21.11", BODY, null));
        String[] bad = {
            "{\"version\":\"1.21.4\",\"recipes\":[]}",
            "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"minecraft:a\",\"type\":\"smelting\",\"result\":\"minecraft:b\"}]}",
            "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"minecraft:a\",\"type\":\"shaped\",\"w\":2,\"h\":2,\"cells\":[[\"minecraft:b\"]],\"result\":\"minecraft:a\",\"count\":1}]}",
            "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"minecraft:a\",\"type\":\"shaped\",\"w\":4,\"h\":1,\"cells\":[[\"minecraft:b\"],null,null,null],\"result\":\"minecraft:a\",\"count\":1}]}",
            "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"../x\",\"type\":\"shapeless\",\"ingredients\":[[\"minecraft:b\"]],\"result\":\"minecraft:a\",\"count\":1}]}",
            "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"minecraft:a\",\"type\":\"shapeless\",\"ingredients\":[[\"minecraft:b\"]],\"result\":\"minecraft:a\",\"count\":0}]}",
            "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"minecraft:a\",\"type\":\"shapeless\",\"ingredients\":[[\"minecraft:b\"]],\"result\":\"minecraft:a\"},{\"id\":\"minecraft:a\",\"type\":\"shapeless\",\"ingredients\":[[\"minecraft:b\"]],\"result\":\"minecraft:a\"}]}",
            "{\"version\":\"1.21.4\",\"recipes\":[{\"id\":\"minecraft:a\",\"type\":\"shapeless\",\"ingredients\":[[]],\"result\":\"minecraft:a\"}]}",
            "[]",
            "not json",
        };
        for (String b : bad) assertThrows(RecipeStore.Invalid.class, () -> store.accept("1.21.4", b, null), b);
        assertFalse(Files.exists(store.file("1.21.4")));
    }

    @Test
    void theEtagIsTheHashInQuotes() {
        String h = "ab".repeat(20);
        assertEquals("\"" + h + "\"", RecipeStore.quote(h));
        assertEquals(h, RecipeStore.hashOfEtag("\"" + h + "\""));
        assertEquals(h, RecipeStore.hashOfEtag("W/\"" + h + "\""));
        assertNull(RecipeStore.hashOfEtag("\"short\""));
        assertNull(RecipeStore.hashOfEtag(null));
    }

    @Test
    void theVersionInTheFileNameIsSafe() {
        assertTrue(RecipeStore.validMc("1.21.4"));
        assertTrue(RecipeStore.validMc("1.21.11"));
        assertFalse(RecipeStore.validMc("../../x"));
        assertFalse(RecipeStore.validMc("a/b"));
        assertFalse(RecipeStore.validMc(""));
        assertFalse(RecipeStore.validMc(null));
    }

    @Test
    void theHoldOfTheCurrentTableIsReplacedWhole() {
        Recipes before = Recipes.current();
        try {
            Recipes.setCurrent(null);
            assertNull(Recipes.current());
            Recipes.setCurrent(TestRecipes.table());
            assertEquals(TestRecipes.table().hash(), Recipes.current().hash());
        } finally {
            Recipes.setCurrent(before);
        }
        JsonObject root = JsonParser.parseString(BODY).getAsJsonObject();
        assertEquals(Recipes.tableHash(root), TestRecipes.table().hash());
    }

    // When it is fetched

    @Test
    void aBotWithNoTableAsksAtOnceAndOneWithTheServersTableDoesNot() {
        RecipeSync s = new RecipeSync();
        assertTrue(s.due(0, null));
        s.started();
        assertFalse(s.due(1, null), "one at a time");
        s.succeeded(10);
        assertFalse(s.due(20, "a".repeat(40)), "nothing said by the server: nothing to fetch");
        s.serverHash("a".repeat(40));
        assertFalse(s.due(20, "a".repeat(40)), "the same hash");
        s.serverHash("b".repeat(40));
        assertFalse(s.due(20, "a".repeat(40)), "not before the gap has passed");
        assertTrue(s.due(10 + RecipeSync.MIN_GAP_MS, "a".repeat(40)), "the server's table differs from the bot's");
    }

    @Test
    void failuresBackOffAndAMissingTableIsAskedForRarely() {
        RecipeSync s = new RecipeSync();
        s.started();
        s.failed(0, -1);
        assertFalse(s.due(RecipeSync.FAIL_MIN_MS - 1, null));
        assertTrue(s.due(RecipeSync.FAIL_MIN_MS, null));
        s.started();
        s.failed(RecipeSync.FAIL_MIN_MS, -2);
        assertFalse(s.due(RecipeSync.FAIL_MIN_MS + 2 * RecipeSync.FAIL_MIN_MS - 1, null), "doubled");
        s.started();
        s.failed(0, 404);
        assertFalse(s.due(RecipeSync.MISSING_MS - 1, null));
        assertTrue(s.due(RecipeSync.MISSING_MS, null));
        s.started();
        s.failed(0, 503);
        assertTrue(s.due(RecipeSync.BUILDING_MS, null));
        s.reset();
        assertTrue(s.due(0, null));
    }
}
