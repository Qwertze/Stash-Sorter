package com.porter.qwertestash.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.ItemKeys;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared vectors of KIT-CONTRACT v2 (revisions 2 to 4), read from the contract's own files (test resources {@code kit2/}, byte for byte): the table's
 * fingerprint, the 39 classification vectors, the match vectors M1 to M18b, and the template and settings hash vectors.
 */
class Kit2VectorsTest {
    private static final String OW = "minecraft:overworld";

    private static KitTypes table() {
        KitTypes t = KitTypes.shared();
        assertNotNull(t, "the table in the plugin is readable");
        return t;
    }

    // The table

    @Test
    void theTableHasTheContractsFingerprintAndIsTheFileByteForByte() throws Exception {
        assertEquals(KitTypes.FINGERPRINT_V1, table().fingerprint());
        assertEquals("c3ffa89424c6998aebcc40339e9d750df410952beb31e5e2dc05d7a59d663e3e", table().fingerprint());
        assertEquals(Kit2Vectors.read("kit-type-vectors.json").get("table").getAsString(), table().fingerprint());
        try (InputStream in = KitTypes.class.getResourceAsStream(KitTypes.RESOURCE)) {
            // The sha-256 of the contract's kit-types.json
            assertEquals("9b6be09e22e19c5cc721a90c39f49011d946686310a7489f5bb6a3e9040e2fb6", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
        }
        assertEquals(KitTypes.TYPE_IDS, table().types().stream().map(KitTypes.Type::id).toList());
    }

    @Test
    void aTableWhoseTypesAreNotTheSevenInOrderIsRefused() {
        String swapped = "{\"types\":[{\"id\":\"travel\",\"name\":\"T\",\"minKinds\":2,\"words\":[]},{\"id\":\"pvp\",\"name\":\"P\",\"minKinds\":3,\"words\":[]}],\"items\":{},\"suffixes\":{},\"prefixes\":{}}";
        assertThrows(KitTypes.Invalid.class, () -> KitTypes.parse(new java.io.StringReader(swapped)));
        assertThrows(KitTypes.Invalid.class, () -> KitTypes.parse(new java.io.StringReader("not json")));
    }

    @Test
    void typesOfFollowsItemThenLongestSuffixThenPrefix() {
        KitTypes t = table();
        assertEquals(List.of("pvp"), t.typesOf("minecraft:totem_of_undying"));
        assertEquals(List.of("travel", "pvp"), t.typesOf("minecraft:ender_pearl"));
        assertEquals(List.of("building"), t.typesOf("minecraft:cyan_concrete"));
        assertEquals(List.of("other"), t.typesOf("minecraft:music_disc_cat"));
        assertEquals(List.of(), t.typesOf("minecraft:cobblestone"));
        assertEquals(List.of(), t.typesOf("other:cyan_concrete"), "suffixes only for the minecraft namespace");
        assertEquals(List.of("pvp"), t.typesOf("totem_of_undying"), "without the namespace");
    }

    // The classification vectors

    private static List<String> strings(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    @Test
    void theThirtyNineClassificationVectorsGiveTheSameResults() {
        JsonObject file = Kit2Vectors.read("kit-type-vectors.json");
        assertEquals(39, file.getAsJsonArray("vectors").size());
        for (JsonElement ve : file.getAsJsonArray("vectors")) {
            JsonObject v = ve.getAsJsonObject();
            String label = v.get("label").getAsString();
            String name = v.get("name").isJsonNull() ? null : v.get("name").getAsString();
            Map<String, String> treatAs = new LinkedHashMap<>();
            for (var e : v.getAsJsonObject("treatAs").entrySet()) treatAs.put(e.getKey(), e.getValue().getAsString());
            List<KitTemplates.BoxStack> slots = new ArrayList<>();
            for (String id : strings(v.getAsJsonArray("slots"))) slots.add(new KitTemplates.BoxStack(id, id, 1));
            KitSettings.Types types = new KitSettings.Types("0".repeat(40), List.of(), Map.of(), treatAs, List.of());
            KitClassifier.Result r = KitClassifier.classify(table(), slots, name, types);
            JsonObject want = v.getAsJsonObject("expect");
            assertEquals(want.get("kit").getAsBoolean(), r.kit(), label + ": kit");
            assertEquals(want.get("code").getAsString(), r.code(), label + ": code");
            if (want.has("type")) assertEquals(want.get("type").isJsonNull() ? null : want.get("type").getAsString(), r.type(), label + ": type");
            if (want.has("slots")) assertEquals(want.get("slots").getAsInt(), r.slots(), label + ": slots");
            if (want.has("covered")) assertEquals(want.get("covered").getAsInt(), r.covered(), label + ": covered");
            if (want.has("kinds")) assertEquals(want.get("kinds").getAsInt(), r.kinds(), label + ": kinds");
            if (want.has("need")) assertEquals(want.get("need").getAsInt(), r.need(), label + ": need");
            if (want.has("minKinds")) assertEquals(want.get("minKinds").getAsInt(), r.minKinds(), label + ": minKinds");
            if (want.has("items")) assertEquals(strings(want.getAsJsonArray("items")), r.items(), label + ": items");
        }
    }

    @Test
    void aNamedAssortmentSaysWhichWordLoweredIt() {
        JsonObject file = Kit2Vectors.read("kit-type-vectors.json");
        for (JsonElement ve : file.getAsJsonArray("vectors")) {
            JsonObject v = ve.getAsJsonObject();
            if (!v.getAsJsonObject("expect").get("code").getAsString().equals("named_assortment")) continue;
            List<KitTemplates.BoxStack> slots = new ArrayList<>();
            for (String id : strings(v.getAsJsonArray("slots"))) slots.add(new KitTemplates.BoxStack(id, id, 1));
            KitClassifier.Result r = KitClassifier.classify(table(), slots, v.get("name").getAsString(), null);
            assertEquals(List.of("kit"), r.words(), v.get("label").getAsString());
        }
        // A word of the type, then the kit word
        List<KitTemplates.BoxStack> slots = new ArrayList<>();
        for (String id : List.of("minecraft:totem_of_undying", "minecraft:end_crystal", "minecraft:obsidian", "minecraft:ender_pearl", "minecraft:cobblestone", "minecraft:dirt", "minecraft:stone", "minecraft:gravel"))
            slots.add(new KitTemplates.BoxStack(id, id, 1));
        KitClassifier.Result r = KitClassifier.classify(table(), slots, "pvp kit", null);
        assertEquals(KitClassifier.NAMED_ASSORTMENT, r.code());
        assertTrue(r.words().contains("pvp"), r.words().toString());
    }

    // Match keys and the match vectors

    @Test
    void theMatchKeyLeavesOutTheNameDamageRepairCostAndContentsButKeepsTheRest() {
        JsonObject plain = com.google.gson.JsonParser.parseString("{\"id\":\"minecraft:netherite_sword\",\"count\":1,\"components\":{\"minecraft:enchantments\":{\"minecraft:sharpness\":5}}}").getAsJsonObject();
        JsonObject dressed = com.google.gson.JsonParser.parseString("{\"id\":\"minecraft:netherite_sword\",\"count\":1,\"components\":{\"minecraft:enchantments\":{\"minecraft:sharpness\":5},"
            + "\"minecraft:custom_name\":\"potato\",\"minecraft:damage\":300,\"minecraft:repair_cost\":7}}").getAsJsonObject();
        JsonObject weaker = com.google.gson.JsonParser.parseString("{\"id\":\"minecraft:netherite_sword\",\"count\":1,\"components\":{\"minecraft:enchantments\":{\"minecraft:sharpness\":4}}}").getAsJsonObject();
        assertEquals(Kit2Vectors.matchKey(plain), Kit2Vectors.matchKey(dressed));
        assertFalse(Kit2Vectors.matchKey(plain).equals(Kit2Vectors.matchKey(weaker)));
        assertTrue(Kit2Vectors.matchKey(plain).startsWith("minecraft:netherite_sword#"));
        // Nothing left is the plain id
        JsonObject onlyDamage = com.google.gson.JsonParser.parseString("{\"id\":\"minecraft:diamond_sword\",\"count\":1,\"components\":{\"minecraft:damage\":9}}").getAsJsonObject();
        assertEquals("minecraft:diamond_sword", Kit2Vectors.matchKey(onlyDamage));
        // The index key does see the name and the damage: the two keys are different things
        assertFalse(ItemKeys.of("netherite_sword", Kit2Vectors.components(dressed), false).key().equals(ItemKeys.of("netherite_sword", Kit2Vectors.components(plain), false).key()));
        // A plain stack, and no components at all
        assertEquals("minecraft:stick", ItemKeys.matchKey("stick", null, false));
        assertEquals("minecraft:stick", ItemKeys.matchKey("stick", Kit2Vectors.components(new JsonObject()), false));
    }

    @Test
    void theTwentyMatchVectorsGiveTheSameStates() {
        JsonObject file = Kit2Vectors.read("kit-template-vectors.json");
        assertEquals(2, file.get("version").getAsInt());
        assertEquals(20, file.getAsJsonArray("match").size());
        for (JsonElement me : file.getAsJsonArray("match")) {
            JsonObject m = me.getAsJsonObject();
            String label = m.get("label").getAsString();
            KitTemplate t = Kit2Vectors.template(m.get("templateKey").getAsString(), m.getAsJsonArray("template"));
            String name = m.get("boxName").isJsonNull() ? null : m.get("boxName").getAsString();
            KitTemplates.Match got = KitTemplates.match(t, Kit2Vectors.box(m.getAsJsonArray("box")), name);
            assertEquals(m.get("expect").getAsString().toUpperCase(), got.state().name(), label);
        }
    }

    @Test
    void anIncompleteCopyIsClassifiedWithWhatIsMissing() {
        JsonObject file = Kit2Vectors.read("kit-template-vectors.json");
        JsonObject m14 = null;
        for (JsonElement me : file.getAsJsonArray("match")) if (me.getAsJsonObject().get("label").getAsString().startsWith("M14")) m14 = me.getAsJsonObject();
        KitTemplate t = Kit2Vectors.template("mini crystal", m14.getAsJsonArray("template"));
        KitSettings.Types types = new KitSettings.Types("0".repeat(40), List.of(), Map.of(), Map.of(), List.of(t));
        KitClassifier.Result r = KitClassifier.classify(table(), Kit2Vectors.box(m14.getAsJsonArray("box")), "mini crystal", types);
        assertTrue(r.kit());
        assertEquals(KitClassifier.TEMPLATE_INCOMPLETE, r.code());
        assertEquals("pvp", r.type());
        assertEquals("t-00000001", r.template());
        assertEquals(1, r.missing().size());
        assertEquals("minecraft:end_crystal", r.missing().get(0).key());
        assertEquals(128, r.missing().get(0).count());
        // Complete: the plain template code; a single kind of item is stock first (M16)
        JsonObject m1 = file.getAsJsonArray("match").get(0).getAsJsonObject();
        KitTemplate food = Kit2Vectors.template("food", m1.getAsJsonArray("template"));
        KitClassifier.Result done = KitClassifier.classify(table(), Kit2Vectors.box(m1.getAsJsonArray("box")), null, new KitSettings.Types("0".repeat(40), List.of(), Map.of(), Map.of(), List.of(food)));
        assertEquals(KitClassifier.TEMPLATE, done.code());
        assertTrue(done.missing().isEmpty());
    }

    // The hashes

    @Test
    void theTemplateHashVectorsAndTheCanonicalStringV2() throws Exception {
        JsonObject file = Kit2Vectors.read("kit-template-vectors.json");
        for (JsonElement he : file.getAsJsonArray("hash")) {
            JsonObject h = he.getAsJsonObject();
            KitTemplate t = KitTemplate.parse(h.getAsJsonObject("template"));
            assertEquals(h.get("canonical").getAsString(), t.canonical(), t.id());
            assertEquals(h.get("sha1").getAsString(), t.hash(), t.id());
        }
        assertEquals("d9cf8cbc680b6b9dccdc308801a082f2b77277c2", KitTemplate.parse(file.getAsJsonArray("hash").get(0).getAsJsonObject().getAsJsonObject("template")).hash());
        assertEquals("a46f739d7d37be27edbc8ac9527a15d67b9cb4e2", KitTemplate.parse(file.getAsJsonArray("hash").get(1).getAsJsonObject().getAsJsonObject("template")).hash());
    }

    @Test
    void theSettingsHashVectorsV1IsUntouchedAndV2CoversChestsCategoriesOverridesAndTemplates() throws Exception {
        // Empty settings: the v1 hash stays, and its v2 hash
        assertEquals("cf9dcdb21f6fc7551bde30826e49358bd74d06ef", KitSettings.EMPTY.hash());
        assertEquals("ae46bc461e485b4ad61f4cf81983e22c21d070d0", KitSettings.hashV2("cf9dcdb21f6fc7551bde30826e49358bd74d06ef", List.of(), Map.of(), Map.of(), List.of()));
        // The full example of the vector file
        JsonObject file = Kit2Vectors.read("kit-template-vectors.json");
        JsonObject s = file.getAsJsonObject("settingsHash");
        List<KitTemplate> templates = new ArrayList<>();
        for (JsonElement he : file.getAsJsonArray("hash")) templates.add(KitTemplate.parse(he.getAsJsonObject().getAsJsonObject("template")));
        Map<String, String> chests = new LinkedHashMap<>();
        chests.put("pvp", "118,64,-30");
        chests.put("building", "121,64,-33");
        chests.put("c:shop", "125,64,-33");
        Map<String, String> as = new LinkedHashMap<>();
        as.put("crystal kit", "none");
        as.put("etc", "building");
        // The v1 hash of: kits chest 120,64,-33, rule pvp kit -> 118,64,-33, default scoring
        String v1 = KitSettings.hashOf("120,64,-33", Map.of("pvp kit", "118,64,-33"), KitScore.Scoring.DEFAULT);
        assertEquals("51f1510ce6282be5d046b67bf54619d4bdec7dd3", v1);
        assertEquals(s.get("v1").getAsString(), v1);
        assertEquals("a111900f6ee3ecdbca49f5acf4fd9debda0210f8", s.get("v2").getAsString());
        assertEquals("a111900f6ee3ecdbca49f5acf4fd9debda0210f8", KitSettings.hashV2(v1, List.of(new KitSettings.Category("c:shop", "Shop kits")), chests, as, templates));
        // The order of the inputs does not matter
        List<KitTemplate> reversed = new ArrayList<>(templates);
        java.util.Collections.reverse(reversed);
        assertEquals(s.get("v2").getAsString(), KitSettings.hashV2(v1, List.of(new KitSettings.Category("c:shop", "Shop kits")), new java.util.TreeMap<>(chests), new java.util.TreeMap<>(as), reversed));
    }

    @Test
    void settingsWithAV2PartAreParsedCheckedAndWrittenBack() throws Exception {
        JsonObject file = Kit2Vectors.read("kit-template-vectors.json");
        String v1 = KitSettings.hashOf("120,64,-33", Map.of("pvp kit", "118,64,-33"), KitScore.Scoring.DEFAULT);
        JsonObject types = new JsonObject();
        types.addProperty("hash", file.getAsJsonObject("settingsHash").get("v2").getAsString());
        JsonArray cats = new JsonArray();
        JsonObject shop = new JsonObject();
        shop.addProperty("id", "c:shop");
        shop.addProperty("name", "Shop kits");
        cats.add(shop);
        types.add("categories", cats);
        JsonObject chests = new JsonObject();
        chests.addProperty("pvp", "118,64,-30");
        chests.addProperty("building", "121,64,-33");
        chests.addProperty("c:shop", "125,64,-33");
        types.add("chests", chests);
        JsonObject as = new JsonObject();
        as.addProperty("crystal kit", "none");
        as.addProperty("etc", "building");
        types.add("treatAs", as);
        JsonArray tpl = new JsonArray();
        for (JsonElement he : file.getAsJsonArray("hash")) tpl.add(he.getAsJsonObject().getAsJsonObject("template"));
        types.add("templates", tpl);
        String json = "{\"rev\":9,\"hash\":\"" + v1 + "\",\"kitsChest\":\"120,64,-33\",\"rules\":{\"pvp kit\":\"118,64,-33\"},\"types\":" + types + "}";
        KitSettings parsed = KitSettings.parse(com.google.gson.JsonParser.parseString(json));
        assertNotNull(parsed.types());
        assertEquals(2, parsed.types().templates().size());
        assertEquals("125,64,-33", parsed.types().chests().get("c:shop"));
        assertEquals("building", parsed.types().treatAs().get("etc"));
        assertFalse(parsed.isEmpty());
        // Written and read again: the same object
        KitSettings again = KitSettings.parse(parsed.toJson());
        assertEquals(parsed, again);
        // Anything wrong in the v2 part refuses the whole object: a wrong hash, a chest for an unknown category, a template with one kind
        types.addProperty("hash", "0".repeat(40));
        assertThrows(KitSettings.Invalid.class, () -> KitSettings.parse(com.google.gson.JsonParser.parseString("{\"rev\":9,\"hash\":\"" + v1 + "\",\"kitsChest\":\"120,64,-33\",\"rules\":{\"pvp kit\":\"118,64,-33\"},\"types\":" + types + "}")));
        types.addProperty("hash", file.getAsJsonObject("settingsHash").get("v2").getAsString());
        chests.addProperty("c:nope", "1,2,3");
        assertThrows(KitSettings.Invalid.class, () -> KitSettings.parse(com.google.gson.JsonParser.parseString("{\"rev\":9,\"hash\":\"" + v1 + "\",\"kitsChest\":\"120,64,-33\",\"rules\":{\"pvp kit\":\"118,64,-33\"},\"types\":" + types + "}")));
        // Settings without types (an older server) have none
        assertNull(KitSettings.parse(com.google.gson.JsonParser.parseString("{\"rev\":9,\"hash\":\"" + v1 + "\",\"kitsChest\":\"120,64,-33\",\"rules\":{\"pvp kit\":\"118,64,-33\"}}")).types());
    }

    @Test
    void everyPresetClassifiesAsItsOwnCategory() {
        JsonObject presets = Kit2Vectors.read("kit-presets.json");
        assertEquals(9, presets.getAsJsonArray("presets").size());
        for (JsonElement pe : presets.getAsJsonArray("presets")) {
            JsonObject p = pe.getAsJsonObject();
            List<KitTemplates.BoxStack> slots = new ArrayList<>();
            for (JsonElement se : p.getAsJsonArray("slots")) {
                if (se.isJsonNull()) continue;
                JsonObject s = se.getAsJsonObject();
                String id = s.get("key").getAsString().replace("#*", "");
                for (int i = 0; i < 1; i++) slots.add(new KitTemplates.BoxStack(id, id, s.get("count").getAsInt()));
            }
            KitClassifier.Result r = KitClassifier.classify(table(), slots, null, null);
            assertTrue(r.kit(), p.get("name").getAsString() + ": " + r.code());
            assertEquals(p.get("category").getAsString(), r.type(), p.get("name").getAsString());
        }
    }
}
