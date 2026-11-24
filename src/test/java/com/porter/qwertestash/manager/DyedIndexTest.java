package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.aquarius.mc.item.ItemRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.DyedFixtures;
import com.porter.qwertestash.orders.DyedSpecs;
import com.porter.qwertestash.orders.SpecialFixtures;
import com.porter.qwertestash.orders.Variants;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.VariantItems;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DyedItemColor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone B5 on the bot's manager side (CRAFT-CONTRACT-v1.2.md 2.1, 3, 4 and 13.2): S14 to S16 derived from real component objects, the dyed spec in the
 * index only for a bot that says {@code dyed}, only on the extras' dyeable items and only for the stash's owner, the heartbeat's {@code can} behind its setting
 * and the extras, and B1 to B4 unchanged.
 */
class DyedIndexTest {
    private static final Set<String> DYEABLE = Set.copyOf(DyedFixtures.DYED.dyeable());
    private static final Variants.Kinds DYED = new Variants.Kinds(null, false, false).withDyeable(DYEABLE);

    private static DataComponents color(int rgb, boolean tooltip) {
        DataComponents c = new DataComponents(new HashMap<>());
        c.put(DataComponentTypes.DYED_COLOR, DyedItemColor.builder().rgb(rgb).showInTooltip(tooltip).build());
        return c;
    }

    /** The spec the plugin derives for a stack with this patch: through the stack's saved data, as the index does. */
    private static String specOf(String id, DataComponents patch) {
        var defaults = ItemRegistry.REGISTRY.get(id).components();
        return Variants.indexSpec(id, VariantItems.dataOf(id, patch, defaults), DYED);
    }

    // S14 to S16, from real components

    @Test
    void s14_aChestplateOfARgbIsItsColour() {
        assertEquals("dyed:b02e26", specOf("leather_chestplate", color(0xB02E26, true)));
        assertEquals("dyed:3c44aa", specOf("leather_helmet", color(0x3C44AA, true)));
        assertEquals("dyed:ffffff", specOf("leather_leggings", color(0xFFFFFF, true)));
        assertEquals("dyed:00000a", specOf("leather_boots", color(10, true)));
        assertEquals("dyed:123456", specOf("leather_horse_armor", color(0x123456, true)));
        assertEquals("dyed:123456", specOf("wolf_armor", color(0x123456, true)));
    }

    @Test
    void s15_aColourOfRgbZeroIsABlackSpecNotNoSpec() {
        assertEquals("dyed:000000", specOf("leather_boots", color(0, true)));
    }

    @Test
    void s16_aWoolWithAColourHasNoSpecAndNeitherDoesAnUndyedItem() {
        assertNull(specOf("white_wool", color(0xB02E26, true)), "not on the dyeable list");
        assertNull(specOf("leather_chestplate", new DataComponents(new HashMap<>())), "an undyed item has no spec (the contract's default 1)");
        assertNull(Variants.indexSpec("leather_chestplate", VariantItems.dataOf("leather_chestplate", new DataComponents(new HashMap<>()), null), DYED));
        assertNull(Variants.indexSpec("leather_chestplate", VariantItems.dataOf("leather_chestplate", null, null), DYED));
    }

    @Test
    void aStackWithAnyOtherComponentOrAHiddenTooltipHasNoSpecAndCustomDataIsLeftOut() {
        DataComponents named = color(0xB02E26, true);
        named.put(DataComponentTypes.CUSTOM_NAME, Component.text("Mine"));
        assertNull(specOf("leather_chestplate", named));
        DataComponents lore = color(0xB02E26, true);
        lore.put(DataComponentTypes.LORE, List.of(Component.text("x")));
        assertNull(specOf("leather_chestplate", lore));
        DataComponents damaged = color(0xB02E26, true);
        damaged.put(DataComponentTypes.DAMAGE, 5);
        assertNull(specOf("leather_chestplate", damaged));
        DataComponents repair = color(0xB02E26, true);
        repair.put(DataComponentTypes.REPAIR_COST, 3);
        assertNull(specOf("leather_chestplate", repair));
        // The default tooltip flag only: a colour whose tooltip is hidden is outside the grammar
        assertNull(specOf("leather_chestplate", color(0xB02E26, false)));
        // Custom data (some servers' ViaVersion adds it) is left out of the judgement, as the key leaves it out
        DataComponents withData = color(0xB02E26, true);
        withData.put(DataComponentTypes.CUSTOM_DATA, new com.viaversion.nbt.mini.MNBT());
        assertEquals("dyed:b02e26", specOf("leather_chestplate", withData));
        // The alpha channel is not part of the rgb: it is dropped (and said once), the spec is the colour the item shows
        assertEquals("dyed:b02e26", specOf("leather_chestplate", color(0x01B02E26, true)));
        assertEquals("dyed:b02e26", specOf("leather_chestplate", color(0xFFB02E26, true)));
        assertEquals("dyed:b02e26", specOf("leather_chestplate", color(0x00B02E26, true)));
        assertEquals("dyed:000000", specOf("leather_chestplate", color(0xFF000000, true)));
    }

    @Test
    void theSavedDataIsTheColourAndNothingElseOfTheItemIsKept() {
        assertEquals("D|b02e26", VariantItems.dataOf("leather_chestplate", color(0xB02E26, true), null));
        assertNull(VariantItems.dataOf("leather_chestplate", color(0xB02E26, false), null));
        // A banner, a book, a map and a tipped arrow keep what they kept
        assertNull(VariantItems.dataOf("white_banner", new DataComponents(new HashMap<>()), null));
        assertEquals("b02e26", Variants.specOf("leather_chestplate", "D|b02e26", "minecraft:leather_chestplate#a0a0a0a1", null).substring(5));
        assertEquals("dyed:b02e26", Variants.specOf("leather_chestplate", "D|b02e26", "minecraft:leather_chestplate#a0a0a0a1", null));
        assertEquals("minecraft:leather_chestplate@dyed:b02e26", Variants.refOf("leather_chestplate", "D|b02e26", "minecraft:leather_chestplate#a0a0a0a1", null));
        assertNull(Variants.specOf("leather_chestplate", "D|B02E26", "minecraft:leather_chestplate#a0a0a0a1", null), "uppercase is no spec");
        assertNull(Variants.specOf("leather_chestplate", "D|b02e2", "minecraft:leather_chestplate#a0a0a0a1", null));
        assertNull(Variants.specOf("leather_chestplate", "D|b02e2600", "minecraft:leather_chestplate#a0a0a0a1", null));
    }

    // The index

    private static Item stack(String id, String keyHash, String fw, int count) {
        Item it = new Item();
        it.id = id;
        it.name = Item.prettyName(id);
        it.count = count;
        it.maxStack = 1;
        it.key = "minecraft:" + id + (keyHash == null ? "" : "#" + keyHash);
        it.variant = it.key;
        it.fw = fw;
        return it;
    }

    private static Stash stash() {
        Stash stash = new Stash("s", "2b2t.org");
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        box.contents[0] = stack("leather_chestplate", "a0a0a0a1", "D|b02e26", 3);
        box.contents[1] = stack("leather_boots", "c0c0c0c1", "D|000000", 1);
        box.contents[2] = stack("white_wool", "e0e0e0e0", "D|b02e26", 4);
        box.contents[3] = stack("leather_chestplate", null, null, 6);
        box.contents[4] = stack("leather_chestplate", "a0a0a0a9", null, 1);
        box.contents[5] = stack("wolf_armor", "f0f0f0f0", "D|3c44aa", 1);
        box.contents[6] = stack("tipped_arrow", "a0a0a0a0", "T|long_poison", 5);
        stash.boxes.add(box);
        return stash;
    }

    private static Map<String, JsonObject> index(String player, Variants.Kinds kinds) {
        ManagerIndex.Built b = ManagerIndex.buildKinds(stash(), false, player, "1.21.4", "overworld", id -> null, null, kinds);
        Map<String, JsonObject> out = new java.util.LinkedHashMap<>();
        for (JsonElement e : JsonParser.parseString(new String(b.json(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("items")) {
            out.put(e.getAsJsonObject().get("key").getAsString(), e.getAsJsonObject());
        }
        return out;
    }

    @Test
    void theIndexCarriesADyedSpecOnlyOnDyeableItemsAndNothingForAnUndyedOne() {
        Map<String, JsonObject> items = index("Bot", DYED);
        assertEquals("dyed:b02e26", items.get("minecraft:leather_chestplate#a0a0a0a1").get("spec").getAsString());
        assertEquals("dyed:000000", items.get("minecraft:leather_boots#c0c0c0c1").get("spec").getAsString());
        assertEquals("dyed:3c44aa", items.get("minecraft:wolf_armor#f0f0f0f0").get("spec").getAsString());
        assertFalse(items.get("minecraft:white_wool#e0e0e0e0").has("spec"), "S16: a wool is not dyeable");
        assertFalse(items.get("minecraft:leather_chestplate").has("spec"), "an undyed item counts as the plain id");
        assertFalse(items.get("minecraft:leather_chestplate#a0a0a0a9").has("spec"), "a chestplate with another component");
        assertFalse(items.get("minecraft:tipped_arrow#a0a0a0a0").has("spec"), "the dyed kind alone writes no tipped spec");
    }

    @Test
    void theDyedKindIsSentOnlyWhenTheBotSaysItAndOnlyWithAPlayer() {
        for (JsonObject o : index("Bot", Variants.Kinds.NONE).values()) assertFalse(o.has("spec"));
        for (JsonObject o : index("Bot", new Variants.Kinds(null, true, true)).values()) assertFalse(o.has("spec") && o.get("spec").getAsString().startsWith("dyed:"));
        // No player: the server would refuse the whole index for a spec, so none is sent
        for (JsonObject o : index("", DYED).values()) assertFalse(o.has("spec"));
        for (JsonObject o : index(null, DYED).values()) assertFalse(o.has("spec"));
        // Dyed with the tipped kind: both are written
        Map<String, JsonObject> both = index("Bot", new Variants.Kinds(null, true, true).withDyeable(DYEABLE));
        assertTrue(both.get("minecraft:tipped_arrow#a0a0a0a0").has("spec"));
        assertTrue(both.get("minecraft:leather_chestplate#a0a0a0a1").has("spec"));
        // An empty dyeable list is no kind
        assertFalse(new Variants.Kinds(null, false, false).withDyeable(Set.of()).any());
        assertTrue(DYED.any());
        // A bot that sends none has the index it had: the same hash; with the spec it changes once
        ManagerIndex.Built before = ManagerIndex.build(stash(), false, "Bot", "1.21.4", "overworld", id -> null);
        assertEquals(before.hash(), ManagerIndex.buildKinds(stash(), false, "Bot", "1.21.4", "overworld", id -> null, null, Variants.Kinds.NONE).hash());
        ManagerIndex.Built with = ManagerIndex.buildKinds(stash(), false, "Bot", "1.21.4", "overworld", id -> null, null, DYED);
        assertNotEquals(before.hash(), with.hash(), "the spec is part of the hash");
        assertEquals(with.hash(), ManagerIndex.buildKinds(stash(), false, "Bot", "1.21.4", "overworld", id -> null, null, DYED).hash());
    }

    @Test
    void theOldTwoArgumentIndexSpecNeverWritesADyedSpec() {
        assertNull(Variants.indexSpec("D|b02e26", DYED), "without the item id the dyeable list can't be asked");
    }

    // The heartbeat

    @Test
    void theCanSaysDyedBehindItsSettingAndTheExtrasTables() {
        Extras b5 = DyedFixtures.EXTRAS;
        assertEquals(List.of("smelt", "firework", "tipped", "stew", "copy", "dyed"), ManagerIndex.craftCan(b5, true, true, true, true, true, true));
        assertEquals(List.of("dyed"), ManagerIndex.craftCan(b5, false, false, false, false, false, true));
        assertTrue(ManagerIndex.craftCan(b5, false, false, false, false, false, false).isEmpty(), "the toggle off says none");
        // The older entry points say what they said (no dyed)
        assertEquals(List.of("smelt", "firework", "tipped", "stew", "copy"), ManagerIndex.craftCan(b5, true, true, true, true, true));
        assertEquals(List.of("smelt", "firework"), ManagerIndex.craftCan(b5, true, true));
        assertEquals(List.of("smelt"), ManagerIndex.craftCan(b5, true));
        assertTrue(ManagerIndex.craftCan(null, true, true, true, true, true, true).isEmpty());
        // The B4 server's extras list no dyed special, no armour colour and no dyeable item: the bot says no dyed
        assertEquals(List.of("tipped", "stew", "copy"), ManagerIndex.craftCan(SpecialFixtures.EXTRAS, false, false, true, true, true, true));
        // Extras that lack an armour colour, the dyeable list or the special say less
        JsonObject j = JsonParser.parseString(b5.json().toString()).getAsJsonObject();
        JsonObject noColour = JsonParser.parseString(j.toString()).getAsJsonObject();
        noColour.getAsJsonObject("dyes").getAsJsonObject("black").remove("armor");
        assertEquals(List.of("tipped", "stew", "copy"), ManagerIndex.craftCan(relist(noColour), false, false, true, true, true, true));
        JsonObject noItems = JsonParser.parseString(j.toString()).getAsJsonObject();
        noItems.add("dyeable", new com.google.gson.JsonArray());
        assertEquals(List.of("tipped", "stew", "copy"), ManagerIndex.craftCan(relist(noItems), false, false, true, true, true, true));
        JsonObject noSpecial = JsonParser.parseString(j.toString()).getAsJsonObject();
        noSpecial.add("special", JsonParser.parseString("[\"rocket\",\"star\",\"star_fade\",\"tipped\",\"stew\",\"banner_copy\",\"map_copy\",\"book_copy\",\"shield\"]"));
        assertEquals(List.of("tipped", "stew", "copy"), ManagerIndex.craftCan(relist(noSpecial), false, false, true, true, true, true));
    }

    private static Extras relist(JsonObject j) {
        try {
            j.addProperty("hash", Extras.compute(j));
            return Extras.parse(j);
        } catch (RecipeStore.Invalid e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theKindsOfTheIndexFollowTheSameSwitches() {
        Extras b5 = DyedFixtures.EXTRAS;
        Variants.Kinds all = ManagerIndex.specKinds(b5, true, true, true, true);
        assertTrue(all.tipped() && all.stew());
        assertEquals(DYEABLE, all.dyeable());
        assertNull(ManagerIndex.specKinds(b5, true, true, true, false).dyeable(), "the toggle off: no dyed spec");
        assertNull(ManagerIndex.specKinds(b5, true, true, true).dyeable(), "the older entry point asks for none");
        assertNull(ManagerIndex.specKinds(SpecialFixtures.EXTRAS, true, true, true, true).dyeable(), "the B4 server's extras have no dyeable list");
        assertFalse(ManagerIndex.specKinds(null, true, true, true, true).any());
        assertFalse(ManagerIndex.specKinds(b5, false, false, false, false).any());
        assertTrue(ManagerIndex.specKinds(b5, false, false, false, true).any());
    }

    @Test
    void theHeartbeatSaysDyedLastAndOnlyWhatItSays() {
        ManagerIndex.CraftOrders base = new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "ok", 2048, List.of());
        ManagerIndex.CraftOrders on = base.withV2(DyedFixtures.SERVER_HASH, ManagerIndex.craftCan(DyedFixtures.EXTRAS, false, false, true, true, true, true));
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject o = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(on, null)), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("craftOrders");
        assertEquals(DyedFixtures.SERVER_HASH, o.get("extras").getAsString());
        List<String> can = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("can")) can.add(e.getAsString());
        assertEquals(List.of("tipped", "stew", "copy", "dyed"), can);
        for (String c : can) assertTrue(c.matches("^[a-z_]{1,24}$"), c);
        assertTrue(can.size() <= 16);
    }

    @Test
    void theDyedSpecIsAnIndexSpecOfTheDyedTablesItems() {
        assertEquals(6, DyedFixtures.DYED.dyeable().size());
        assertTrue(DyedSpecs.fitsItem("dyed:b02e26", "minecraft:wolf_armor", DyedFixtures.DYED));
        assertFalse(DyedSpecs.fitsItem("dyed:b02e26", "minecraft:white_wool", DyedFixtures.DYED));
        assertFalse(DyedSpecs.fitsItem("dyed:b02e26", "minecraft:wolf_armor", null));
        assertFalse(DyedSpecs.fitsItem("dyed:B02E26", "minecraft:wolf_armor", DyedFixtures.DYED));
    }

    @Test
    void theSavedDyedDataAndTheSpecAreForTheSixAllowedItemsOnly() {
        // A wool, a sword or any other item with a colour keeps no saved data, whatever the server lists (security review 1, L3)
        assertNull(VariantItems.dataOf("white_wool", color(0xB02E26, true), null));
        assertNull(VariantItems.dataOf("diamond_sword", color(0xB02E26, true), null));
        assertNull(Variants.specOf("white_wool", "D|b02e26", "minecraft:white_wool#e0e0e0e0", null), "a stash file that says so is not believed");
        assertNull(Variants.specOf("diamond_sword", "D|b02e26", "minecraft:diamond_sword#e0e0e0e1", null));
        for (String id : new String[] {"leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots", "leather_horse_armor", "wolf_armor"}) {
            assertEquals("D|b02e26", VariantItems.dataOf(id, color(0xB02E26, true), null), id);
            assertEquals("dyed:b02e26", Variants.specOf(id, "D|b02e26", "minecraft:" + id + "#a0a0a0a1", null), id);
        }
        // Even when a (hostile) server's extras list the wool, the index gate is the allowed ones
        Variants.Kinds wool = new Variants.Kinds(null, false, false).withDyeable(Set.of("minecraft:white_wool"));
        assertNull(Variants.indexSpec("white_wool", "D|b02e26", wool), "the kinds come from the tables, which only keep the six; a wool in a hand-made kind still needs saved data");
    }

    @Test
    void aStashSavedBeforeB5IsReadAgainOnceWhenTheBotFirstSaysDyed() {
        Stash s = stash();
        for (Stash.Box b : s.boxes) b.stale = false;
        assertEquals(0, s.dyedSpecGen, "a stash saved before B5 has no generation");
        assertFalse(s.rescanForDyed(false), "the dyed kind is off: nothing happens, and it stays unmarked");
        assertEquals(0, s.dyedSpecGen);
        for (Stash.Box b : s.boxes) assertFalse(b.stale);
        assertTrue(s.rescanForDyed(true), "the first time the bot says dyed");
        assertEquals(1, s.dyedSpecGen);
        for (Stash.Box b : s.boxes) assertTrue(b.stale, "every container is read again, as stash rescan does");
        for (Stash.Box b : s.boxes) b.stale = false;
        assertFalse(s.rescanForDyed(true), "only once");
        for (Stash.Box b : s.boxes) assertFalse(b.stale);
        // The generation is saved with the stash
        String json = new com.google.gson.Gson().toJson(s);
        assertTrue(json.contains("\"dyedSpecGen\":1"), json.substring(0, Math.min(200, json.length())));
        assertEquals(1, new com.google.gson.Gson().fromJson(json, Stash.class).dyedSpecGen);
        // An excluded container is not read again (as for stash rescan)
        Stash e = stash();
        e.boxes.get(0).excluded = true;
        for (Stash.Box b : e.boxes) b.stale = false;
        e.rescanForDyed(true);
        assertFalse(e.boxes.get(0).stale);
    }
}
