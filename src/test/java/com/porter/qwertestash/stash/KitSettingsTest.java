package com.porter.qwertestash.stash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Kit settings: the hash vectors, everything the server sends is checked, and the cache file. */
class KitSettingsTest {
    private static final String ZERO = "0".repeat(40);

    /** Parsed with the right hash put in, so the content is what is tested. */
    private static KitSettings parse(String json) throws KitSettings.Invalid {
        return KitSettings.parse(JsonParser.parseString(KitTestSupport.withHash(json)));
    }

    /** Parsed as given, the stated hash included. */
    private static KitSettings raw(String json) throws KitSettings.Invalid {
        return KitSettings.parse(JsonParser.parseString(json));
    }

    private static String settings(String rev, String hash, String chest, String rules) {
        return "{\"rev\":" + rev + ",\"hash\":\"" + hash + "\",\"kitsChest\":" + chest + ",\"rules\":" + rules + "}";
    }

    @Test
    void hashVectors() {
        KitScore.Scoring d = KitScore.Scoring.DEFAULT;
        assertEquals("cf9dcdb21f6fc7551bde30826e49358bd74d06ef", KitSettings.hashOf(null, Map.of(), d), "empty settings: v1, the chest line, the score line with opMin");
        assertEquals(KitSettings.EMPTY.hash(), KitSettings.hashOf(null, Map.of(), d));
        assertEquals("51f1510ce6282be5d046b67bf54619d4bdec7dd3", KitSettings.hashOf("120,64,-33", Map.of("pvp kit", "118,64,-33"), d));
        Map<String, String> unsorted = new LinkedHashMap<>();
        unsorted.put("b kit", "4,5,6");
        unsorted.put("a kit", "1,2,3");
        assertEquals("f4376cd4a1b7e71ad1fe3736e9f2516fcefbcdfd", KitSettings.hashOf(null, unsorted, new KitScore.Scoring(6, 0, 10, 5, 1)),
            "rules are hashed in key order, after the score line");
        assertEquals("af535f0af55cd71ab540c2f02066bd34720d367b", KitSettings.hashOf(null, unsorted, new KitScore.Scoring(6, 0, 10, 5, 1, 7)), "opMin is the last number of the score line");
    }

    @Test
    void theStatedHashMustBeTheHashOfTheContent() throws Exception {
        String right = KitSettings.hashOf("1,2,3", Map.of("pvp kit", "4,5,6"), KitScore.Scoring.DEFAULT);
        String body = "\"kitsChest\":\"1,2,3\",\"rules\":{\"pvp kit\":\"4,5,6\"}";
        assertEquals(right, raw("{\"rev\":1,\"hash\":\"" + right + "\"," + body + "}").hash());
        assertThrows(KitSettings.Invalid.class, () -> raw("{\"rev\":1,\"hash\":\"" + ZERO + "\"," + body + "}"), "a hash of something else");
        // The content changed after the hash was made: the chest, a rule, the scoring
        assertThrows(KitSettings.Invalid.class, () -> raw("{\"rev\":1,\"hash\":\"" + right + "\",\"kitsChest\":\"1,2,4\",\"rules\":{\"pvp kit\":\"4,5,6\"}}"));
        assertThrows(KitSettings.Invalid.class, () -> raw("{\"rev\":1,\"hash\":\"" + right + "\",\"kitsChest\":\"1,2,3\",\"rules\":{\"pvp kit\":\"4,5,7\"}}"));
        assertThrows(KitSettings.Invalid.class, () -> raw("{\"rev\":1,\"hash\":\"" + right + "\"," + body
            + ",\"scoring\":{\"threshold\":5,\"name\":2,\"mix\":3,\"full\":1,\"op\":4,\"opMin\":3}}"));
        assertThrows(KitSettings.Invalid.class, () -> raw("{\"rev\":1,\"hash\":\"" + right + "\"," + body
            + ",\"scoring\":{\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1,\"op\":4,\"opMin\":4}}"), "opMin is in the hash");
        // A scoring without opMin is opMin 3, which is what the right hash was made with
        assertEquals(right, raw("{\"rev\":1,\"hash\":\"" + right + "\"," + body + ",\"scoring\":{\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}}").hash());
        // The cache file goes through the same check
        JsonObject tampered = parse(settings("1", ZERO, "null", "{\"a kit\":\"1,2,3\"}")).toJson();
        tampered.getAsJsonObject("rules").addProperty("a kit", "9,9,9");
        assertThrows(KitSettings.Invalid.class, () -> KitSettings.parse(tampered));
    }

    @Test
    void opMinIsReadCheckedAndDefaultsToThree() throws Exception {
        assertEquals(3, parse(settings("1", ZERO, "null", "{}")).scoring().opMin());
        String base = "{\"rev\":1,\"hash\":\"" + ZERO + "\",\"kitsChest\":null,\"rules\":{},\"scoring\":{\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1,\"op\":4";
        assertEquals(new KitScore.Scoring(4, 2, 3, 1, 4, 1), parse(base + ",\"opMin\":1}}").scoring());
        assertEquals(1728, parse(base + ",\"opMin\":1728}}").scoring().opMin());
        assertThrows(KitSettings.Invalid.class, () -> parse(base + ",\"opMin\":0}}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + ",\"opMin\":1729}}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + ",\"opMin\":2.5}}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + ",\"opMin\":\"3\"}}"));
        KitSettings s = parse(base + ",\"opMin\":9}}");
        assertEquals(s, KitSettings.parse(s.toJson()), "opMin survives the cache round trip");
    }

    @Test
    void scoringIsPartOfTheHash() {
        assertTrue(!KitSettings.hashOf(null, Map.of(), KitScore.Scoring.DEFAULT).equals(KitSettings.hashOf(null, Map.of(), new KitScore.Scoring(5, 2, 3, 1, 4))));
    }

    @Test
    void scoringIsReadAndCheckedAndDefaultsWhenAbsent() throws Exception {
        assertEquals(KitScore.Scoring.DEFAULT, parse(settings("1", ZERO, "null", "{}")).scoring());
        KitSettings s = parse("{\"rev\":1,\"hash\":\"" + ZERO + "\",\"kitsChest\":null,\"rules\":{},\"scoring\":{\"threshold\":6,\"name\":0,\"mix\":10,\"full\":5,\"op\":1}}");
        assertEquals(new KitScore.Scoring(6, 0, 10, 5, 1), s.scoring());
        assertEquals(s, KitSettings.parse(s.toJson()), "scoring survives the cache round trip");
        String base = "{\"rev\":1,\"hash\":\"" + ZERO + "\",\"kitsChest\":null,\"rules\":{},\"scoring\":";
        assertThrows(KitSettings.Invalid.class, () -> parse(base + "{\"threshold\":0,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + "{\"threshold\":41,\"name\":2,\"mix\":3,\"full\":1,\"op\":4}}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + "{\"threshold\":4,\"name\":11,\"mix\":3,\"full\":1,\"op\":4}}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + "{\"threshold\":4,\"name\":2,\"mix\":-1,\"full\":1,\"op\":4}}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + "{\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1.5,\"op\":4}}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + "{\"threshold\":4,\"name\":2,\"mix\":3,\"full\":1}}"), "a part is missing");
        assertThrows(KitSettings.Invalid.class, () -> parse(base + "5}"));
        assertThrows(KitSettings.Invalid.class, () -> parse(base + "{\"threshold\":\"4\",\"name\":2,\"mix\":3,\"full\":1,\"op\":4}}"));
    }

    @Test
    void aValidObjectIsReadAndUnknownFieldsAreIgnored() throws Exception {
        KitSettings s = parse("{\"rev\":7,\"hash\":\"" + ZERO + "\",\"kitsChest\":\"120,64,-33\",\"rules\":{\"pvp kit\":\"118,64,-33\"},\"future\":[1]}");
        assertEquals(7, s.rev());
        assertEquals("120,64,-33", s.kitsChest());
        assertEquals(Map.of("pvp kit", "118,64,-33"), s.rules());
        assertEquals("51f1510ce6282be5d046b67bf54619d4bdec7dd3", s.hash(), "the hash the server said is kept, so the heartbeat compares like for like");
        assertFalse(s.isEmpty());
    }

    @Test
    void aNullKitsChestAndNoRulesIsEmpty() throws Exception {
        KitSettings s = parse(settings("0", ZERO, "null", "{}"));
        assertNull(s.kitsChest());
        assertTrue(s.isEmpty());
    }

    @Test
    void theWholeObjectIsRefusedForAnyBadPart() throws Exception {
        String ok = "{\"pvp kit\":\"1,2,3\"}";
        assertThrows(KitSettings.Invalid.class, () -> parse("[]"));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("\"7\"", ZERO, "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("-1", ZERO, "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1.5", ZERO, "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> raw(settings("1", "ABC", "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> raw(settings("1", ZERO.toUpperCase().replace('0', 'A'), "null", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "\"nope\"", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "5", ok)));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "[]")));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "{\"Pvp Kit\":\"1,2,3\"}")), "a key that is not normal");
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "{\"\":\"1,2,3\"}")), "an empty key");
        assertEquals(Map.of("toolkit", "1,2,3"), parse(settings("1", ZERO, "null", "{\"toolkit\":\"1,2,3\"}")).rules(), "a rule key does not need the word kit");
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "{\"pvp kit\":\"1, 2, 3\"}")));
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", "{\"pvp kit\":7}")));
        assertThrows(KitSettings.Invalid.class, () -> parse("{\"rev\":1,\"hash\":\"" + ZERO + "\"}"));
    }

    @Test
    void containerIdsFollowTheContractPatternAndRanges() {
        assertTrue(KitSettings.validId("0,0,0"));
        assertTrue(KitSettings.validId("-30000000,-2048,30000000"));
        assertTrue(KitSettings.validId("30000000,2048,-30000000"));
        assertFalse(KitSettings.validId("30000001,0,0"));
        assertFalse(KitSettings.validId("0,2049,0"));
        assertFalse(KitSettings.validId("0,-2049,0"));
        assertFalse(KitSettings.validId("0,0,-30000001"));
        assertFalse(KitSettings.validId("123456789,0,0"), "more than eight digits");
        assertFalse(KitSettings.validId("0,12345,0"), "more than four digits for y");
        assertFalse(KitSettings.validId("1,2"));
        assertFalse(KitSettings.validId("1,2,3,4"));
        assertFalse(KitSettings.validId("+1,2,3"));
        assertFalse(KitSettings.validId("1, 2,3"));
        assertFalse(KitSettings.validId("1.0,2,3"));
        assertFalse(KitSettings.validId(""));
        assertFalse(KitSettings.validId(null));
        assertNull(KitSettings.position("nonsense"));
        assertEquals(-33, KitSettings.position("120,64,-33").z());
    }

    @Test
    void atMostTwoHundredRules() throws Exception {
        assertEquals(200, parse(settings("1", ZERO, "null", rules(200))).rules().size());
        assertThrows(KitSettings.Invalid.class, () -> parse(settings("1", ZERO, "null", rules(201))));
    }

    private static String rules(int n) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < n; i++) sb.append(i > 0 ? "," : "").append("\"kit ").append(i).append("\":\"").append(i).append(",64,0\"");
        return sb.append('}').toString();
    }

    @Test
    void jsonRoundTrip() throws Exception {
        KitSettings s = parse(settings("3", ZERO, "\"1,2,3\"", "{\"a kit\":\"4,5,6\"}"));
        assertEquals(s, KitSettings.parse(s.toJson()));
    }

    // The cache file

    @Test
    void theCacheRoundTripsThroughTheFileAndIsWrittenAtomically(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub").resolve("kit-settings.json");
        KitStore store = new KitStore(file);
        assertNull(store.get("2b2t.org", "stash"), "nothing cached yet");
        int v0 = store.version();

        KitSettings s = parse(settings("3", ZERO, "\"1,2,3\"", "{\"a kit\":\"4,5,6\"}"));
        assertTrue(store.put("2b2t.org", "stash", s));
        assertTrue(store.version() > v0);
        assertFalse(store.put("2b2t.org", "stash", s), "the same settings change nothing");
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(file.resolveSibling("kit-settings.json.tmp")), "no temp file is left behind");

        KitStore again = new KitStore(file);
        assertEquals(s, again.get("2b2t.org", "stash"));
        assertNull(again.get("2b2t.org", "other"), "per server and stash");
        assertNull(again.get("other.org", "stash"));
    }

    @Test
    void anUnreadableFileMeansNoCacheAndIsReplacedByTheNextSave(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kit-settings.json");
        Files.writeString(file, "{ this is not json");
        KitStore store = new KitStore(file);
        assertNull(store.get("s", "t"));
        assertTrue(store.put("s", "t", KitSettings.EMPTY));
        assertEquals(KitSettings.EMPTY, new KitStore(file).get("s", "t"));
    }

    @Test
    void aBadEntryInTheFileIsDroppedAndTheGoodOnesKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("kit-settings.json");
        JsonObject good = parse(settings("1", ZERO, "null", "{\"a kit\":\"1,2,3\"}")).toJson();
        good.addProperty("server", "s");
        good.addProperty("stash", "good");
        JsonObject bad = parse(settings("1", ZERO, "null", "{\"a kit\":\"1,2,3\"}")).toJson();
        bad.getAsJsonObject("rules").addProperty("Not Normal", "1,2,3");
        bad.addProperty("server", "s");
        bad.addProperty("stash", "bad");
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        array.add(bad);
        array.add(good);
        root.add("stashes", array);
        Files.writeString(file, root.toString());

        KitStore store = new KitStore(file);
        assertNull(store.get("s", "bad"));
        assertNotNull(store.get("s", "good"));
    }

    @Test
    void atMostSixtyFourStashesAreKept(@TempDir Path dir) throws IOException {
        KitStore store = new KitStore(dir.resolve("kit-settings.json"));
        for (int i = 0; i < 70; i++) store.put("s", "stash" + i, KitSettings.EMPTY);
        assertNull(store.get("s", "stash0"), "the oldest goes first");
        assertNotNull(store.get("s", "stash69"));
        assertEquals(KitStore.MAX_STASHES, new KitStore(dir.resolve("kit-settings.json")).size());
    }
}
