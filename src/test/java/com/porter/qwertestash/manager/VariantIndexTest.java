package com.porter.qwertestash.manager;

import com.aquarius.mc.block.BlockPos;
import com.aquarius.mc.item.ItemRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.orders.SpecialFixtures;
import com.porter.qwertestash.orders.VariantSpecs;
import com.porter.qwertestash.orders.Variants;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.VariantItems;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.BannerPatternLayer;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.PotionContents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.SuspiciousStewEffect;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Milestone B4 on the bot's manager side (CRAFT-CONTRACT-v1.2.md 2.1, 3, 4 and 13.2): S17 to S20 derived from real component objects, the specs of tipped
 * arrows, lingering potions and stew in the index only for a bot that says so (and only from its owner), the heartbeat's {@code can} behind the three settings,
 * and the extras of the real B4 server accepted with the server's own hash.
 */
class VariantIndexTest {
    private static final Variants.Kinds BOTH = new Variants.Kinds(null, true, true);

    private static int potion(String name) {
        for (int i = 0; i < 100; i++) if (name.equals(Item.potionName(i))) return i;
        throw new IllegalArgumentException(name);
    }

    private static DataComponents potionPatch(int id, int color, List<org.geysermc.mcprotocollib.protocol.data.game.item.component.MobEffectInstance> effects, String name) {
        DataComponents c = new DataComponents(new HashMap<>());
        c.put(DataComponentTypes.POTION_CONTENTS, PotionContents.builder().potionId(id).customColor(color).customEffects(effects).customName(name).build());
        return c;
    }

    private static DataComponents stewPatch(SuspiciousStewEffect... effects) {
        DataComponents c = new DataComponents(new HashMap<>());
        c.put(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS, new ArrayList<>(List.of(effects)));
        return c;
    }

    /** The spec the plugin derives for a stack with this patch: through the stack's saved data, as the index does. */
    private static String specOf(String id, DataComponents patch) {
        var defaults = ItemRegistry.REGISTRY.get(id).components();
        return Variants.indexSpec(VariantItems.dataOf(id, patch, defaults), BOTH);
    }

    private static SuspiciousStewEffect stewEffect(Effect e, int ticks) {
        return new SuspiciousStewEffect(e.ordinal(), ticks);
    }

    // S17 to S20, from real components

    @Test
    void s17_aTippedArrowOfAPotionIsItsPotion() {
        assertEquals("tipped:minecraft:long_poison", specOf("tipped_arrow", potionPatch(potion("long_poison"), -1, List.of(), null)));
        assertEquals("tipped:minecraft:night_vision", specOf("tipped_arrow", potionPatch(potion("night_vision"), -1, List.of(), null)));
        // A lingering potion carries the spec of the arrows it makes (the clarification of 2026-11-23)
        assertEquals("tipped:minecraft:long_poison", specOf("lingering_potion", potionPatch(potion("long_poison"), -1, List.of(), null)));
        // Custom data (some servers' ViaVersion adds it) is left out of the judgement, as the key leaves it out
        DataComponents withData = potionPatch(potion("strength"), -1, List.of(), null);
        withData.put(DataComponentTypes.CUSTOM_DATA, new com.viaversion.nbt.mini.MNBT());
        assertEquals("tipped:minecraft:strength", specOf("tipped_arrow", withData));
    }

    @Test
    void s18_aTippedArrowWithACustomColourEffectsOrNameOrAnotherComponentHasNoSpec() {
        assertNull(specOf("tipped_arrow", potionPatch(potion("long_poison"), 0x123456, List.of(), null)), "S18: custom colour");
        assertNull(specOf("tipped_arrow", potionPatch(potion("long_poison"), 0, List.of(), null)), "a custom colour of rgb 0 is a colour");
        assertNull(specOf("tipped_arrow", potionPatch(potion("long_poison"), -1, List.of(), "Poison")), "a custom name inside the potion contents");
        DataComponents named = potionPatch(potion("long_poison"), -1, List.of(), null);
        named.put(DataComponentTypes.CUSTOM_NAME, Component.text("Arrows"));
        assertNull(specOf("tipped_arrow", named));
        DataComponents lore = potionPatch(potion("long_poison"), -1, List.of(), null);
        lore.put(DataComponentTypes.LORE, List.of(Component.text("x")));
        assertNull(specOf("tipped_arrow", lore));
        // No potion at all, and a potion that is not in the registry the plugin knows
        assertNull(specOf("tipped_arrow", potionPatch(-1, -1, List.of(), null)));
        assertNull(specOf("tipped_arrow", potionPatch(9999, -1, List.of(), null)));
        assertNull(specOf("tipped_arrow", new DataComponents(new HashMap<>())), "a plain tipped arrow has no potion");
        // A drinkable potion and a splash potion are no variants of this grammar
        assertNull(specOf("potion", potionPatch(potion("long_poison"), -1, List.of(), null)));
        assertNull(specOf("splash_potion", potionPatch(potion("long_poison"), -1, List.of(), null)));
    }

    @Test
    void s19_aSuspiciousStewOfOneEffectIsItsEffectAndTicks() {
        assertEquals("stew:minecraft:night_vision/100", specOf("suspicious_stew", stewPatch(stewEffect(Effect.NIGHT_VISION, 100))));
        assertEquals("stew:minecraft:saturation/7", specOf("suspicious_stew", stewPatch(stewEffect(Effect.SATURATION, 7))));
        assertEquals("stew:minecraft:fire_resistance/80", specOf("suspicious_stew", stewPatch(stewEffect(Effect.FIRE_RESISTANCE, 80))));
        assertEquals("stew:minecraft:jump_boost/120", specOf("suspicious_stew", stewPatch(stewEffect(Effect.JUMP_BOOST, 120))));
        assertEquals("stew:minecraft:night_vision/1000000", specOf("suspicious_stew", stewPatch(stewEffect(Effect.NIGHT_VISION, 1_000_000))));
    }

    @Test
    void s20_aStewOfTwoEffectsNoneOrOutOfBoundsHasNoSpec() {
        assertNull(specOf("suspicious_stew", stewPatch(stewEffect(Effect.NIGHT_VISION, 100), stewEffect(Effect.POISON, 100))), "S20");
        assertNull(specOf("suspicious_stew", stewPatch()), "none");
        assertNull(specOf("suspicious_stew", new DataComponents(new HashMap<>())), "a plain stew");
        assertNull(specOf("suspicious_stew", stewPatch(stewEffect(Effect.NIGHT_VISION, 0))));
        assertNull(specOf("suspicious_stew", stewPatch(stewEffect(Effect.NIGHT_VISION, 1_000_001))));
        assertNull(specOf("suspicious_stew", stewPatch(new SuspiciousStewEffect(500, 100))), "an effect id the plugin doesn't know");
        DataComponents named = stewPatch(stewEffect(Effect.NIGHT_VISION, 100));
        named.put(DataComponentTypes.CUSTOM_NAME, Component.text("Stew"));
        assertNull(specOf("suspicious_stew", named));
    }

    // What the stash keeps of banners, maps, books and shields

    @Test
    void aBookKeepsItsGenerationAndABannerMapOrShieldItsMarkNeverAnIndexSpec() {
        DataComponents book = new DataComponents(new HashMap<>());
        book.put(DataComponentTypes.WRITTEN_BOOK_CONTENT, WrittenBookContent.builder().generation(1).build());
        assertEquals("G1", VariantItems.dataOf("written_book", book, null));
        book.put(DataComponentTypes.WRITTEN_BOOK_CONTENT, WrittenBookContent.builder().generation(0).build());
        assertEquals("G0", VariantItems.dataOf("written_book", book, null));
        assertEquals(0, Variants.generationOf("G0"));
        assertEquals(2, Variants.generationOf("G2"));
        assertEquals(-1, Variants.generationOf(null));
        assertEquals(-1, Variants.generationOf("C"));
        // The spec of an original is the source's, of a copy none; of a banner or a map the copy spec of its own key
        assertEquals("src:minecraft:written_book#aa", Variants.specOf("written_book", "G0", "minecraft:written_book#aa", null));
        assertNull(Variants.specOf("written_book", "G1", "minecraft:written_book#bb", null));
        assertEquals("copy:minecraft:white_banner#aa", Variants.specOf("white_banner", "C", "minecraft:white_banner#aa", null));
        assertEquals("copy:minecraft:filled_map#aa", Variants.specOf("filled_map", "C", "minecraft:filled_map#aa", null));
        assertNull(Variants.specOf("white_banner", "C", "minecraft:white_banner", null), "a plain banner (no key hash) is only a blank");
        // None of them is ever an index spec (CRAFT-CONTRACT-v1.2.md 4)
        for (String data : new String[] {"C", "G0", "G1"}) assertNull(Variants.indexSpec(data, new Variants.Kinds(SpecialFixtures.FIREWORKS, true, true)));
        // A banner with no pattern, a map without a map id and a shield without patterns are plain
        assertNull(VariantItems.dataOf("white_banner", new DataComponents(new HashMap<>()), null));
        assertNull(VariantItems.dataOf("filled_map", new DataComponents(new HashMap<>()), null));
        assertNull(VariantItems.dataOf("shield", new DataComponents(new HashMap<>()), null));
        DataComponents patterned = new DataComponents(new HashMap<>());
        patterned.put(DataComponentTypes.BANNER_PATTERNS, new ArrayList<BannerPatternLayer>());
        assertNull(VariantItems.dataOf("white_banner", patterned, null), "an empty list is no pattern");
        DataComponents map = new DataComponents(new HashMap<>());
        map.put(DataComponentTypes.MAP_ID, 5);
        assertEquals("C", VariantItems.dataOf("filled_map", map, null));
    }

    @Test
    void theGenerationOfABookOfAKeyIsFoundLooseAndInsideAShulkerAndUnknownIsNotARefusal() {
        Item original = stack("written_book", "bb00bb00", "G0", 1), copy = stack("written_book", "cc00cc00", "G1", 1), unknown = stack("written_book", "dd00dd00", null, 1);
        assertEquals(0, VariantItems.generationIn(original, "minecraft:written_book#bb00bb00"));
        assertEquals(1, VariantItems.generationIn(copy, "minecraft:written_book#cc00cc00"));
        assertEquals(-1, VariantItems.generationIn(unknown, "minecraft:written_book#dd00dd00"), "a stash saved before the generation was kept: unknown, not a copy");
        assertEquals(-1, VariantItems.generationIn(original, "minecraft:written_book#cc00cc00"), "another key");
        Item shulker = stack("white_shulker_box", "11112222", null, 1);
        shulker.contents.add(copy);
        assertEquals(1, VariantItems.generationIn(shulker, "minecraft:written_book#cc00cc00"));
        assertEquals(-1, VariantItems.generationIn(shulker, "minecraft:written_book#bb00bb00"));
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

    private static Stash stash() {
        Stash stash = new Stash("s", "2b2t.org");
        Stash.Box box = new Stash.Box(new BlockPos(1, 2, 3), new ArrayList<>(List.of(new BlockPos(1, 2, 3))));
        box.contents = new Item[27];
        box.contents[0] = stack("tipped_arrow", "a0a0a0a0", "T|long_poison", 5);
        box.contents[1] = stack("lingering_potion", "b0b0b0b0", "T|long_poison", 2);
        box.contents[2] = stack("suspicious_stew", "c0c0c0c0", "W|minecraft:night_vision|100", 3);
        box.contents[3] = stack("white_banner", "a1b2c3d4", "C", 2);
        box.contents[4] = stack("filled_map", "0000abcd", "C", 1);
        box.contents[5] = stack("written_book", "bb00bb00", "G0", 1);
        box.contents[6] = stack("tipped_arrow", "d0d0d0d0", null, 1);
        box.contents[7] = stack("arrow", null, null, 40);
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
    void theIndexCarriesTippedAndStewSpecsAndNeverACopyOrShieldSpec() {
        Map<String, JsonObject> items = index("Bot", BOTH);
        assertEquals("tipped:minecraft:long_poison", items.get("minecraft:tipped_arrow#a0a0a0a0").get("spec").getAsString());
        assertEquals("tipped:minecraft:long_poison", items.get("minecraft:lingering_potion#b0b0b0b0").get("spec").getAsString(), "the lingering potion too");
        assertEquals("stew:minecraft:night_vision/100", items.get("minecraft:suspicious_stew#c0c0c0c0").get("spec").getAsString());
        assertFalse(items.get("minecraft:tipped_arrow#d0d0d0d0").has("spec"), "S18: a tipped arrow with no spec");
        for (String k : new String[] {"minecraft:white_banner#a1b2c3d4", "minecraft:filled_map#0000abcd", "minecraft:written_book#bb00bb00", "minecraft:arrow"}) assertFalse(items.get(k).has("spec"), k);
    }

    @Test
    void eachKindIsSentOnlyWhenTheBotSaysItAndOnlyWithAPlayer() {
        Map<String, JsonObject> tippedOnly = index("Bot", new Variants.Kinds(null, true, false));
        assertTrue(tippedOnly.get("minecraft:tipped_arrow#a0a0a0a0").has("spec"));
        assertFalse(tippedOnly.get("minecraft:suspicious_stew#c0c0c0c0").has("spec"));
        Map<String, JsonObject> stewOnly = index("Bot", new Variants.Kinds(null, false, true));
        assertFalse(stewOnly.get("minecraft:tipped_arrow#a0a0a0a0").has("spec"));
        assertTrue(stewOnly.get("minecraft:suspicious_stew#c0c0c0c0").has("spec"));
        for (JsonObject o : index("Bot", Variants.Kinds.NONE).values()) assertFalse(o.has("spec"));
        // No player: the server would refuse the whole index for a spec, so none is sent
        for (JsonObject o : index("", BOTH).values()) assertFalse(o.has("spec"));
        // The index of a bot that sends none is what it was before B4: the same hash
        ManagerIndex.Built before = ManagerIndex.build(stash(), false, "Bot", "1.21.4", "overworld", id -> null);
        assertEquals(before.hash(), ManagerIndex.buildKinds(stash(), false, "Bot", "1.21.4", "overworld", id -> null, null, Variants.Kinds.NONE).hash());
        ManagerIndex.Built with = ManagerIndex.buildKinds(stash(), false, "Bot", "1.21.4", "overworld", id -> null, null, BOTH);
        assertNotEquals(before.hash(), with.hash(), "the spec is part of the hash (it changes once)");
        assertEquals(with.hash(), ManagerIndex.buildKinds(stash(), false, "Bot", "1.21.4", "overworld", id -> null, null, BOTH).hash());
        // Firework kinds as in B3 still work through the old entry point
        assertEquals(before.hash(), ManagerIndex.build(stash(), false, "Bot", "1.21.4", "overworld", id -> null, null, (com.porter.qwertestash.orders.FireworkSpecs.Tables) null).hash());
    }

    // The heartbeat

    @Test
    void theCanSaysTippedStewAndCopyBehindTheirSettingsAndTheExtrasTables() {
        Extras b4 = SpecialFixtures.EXTRAS;
        assertEquals(SpecialFixtures.SERVER_HASH, b4.hash());
        assertEquals(List.of("smelt", "firework", "tipped", "stew", "copy"), ManagerIndex.craftCan(b4, true, true, true, true, true));
        assertEquals(List.of("tipped", "stew", "copy"), ManagerIndex.craftCan(b4, false, false, true, true, true));
        assertEquals(List.of("tipped"), ManagerIndex.craftCan(b4, false, false, true, false, false));
        assertEquals(List.of("stew"), ManagerIndex.craftCan(b4, false, false, false, true, false));
        assertEquals(List.of("copy"), ManagerIndex.craftCan(b4, false, false, false, false, true));
        assertTrue(ManagerIndex.craftCan(b4, false, false, false, false, false).isEmpty(), "every toggle off says none");
        // The older entry points say what they said
        assertEquals(List.of("smelt", "firework"), ManagerIndex.craftCan(b4, true, true));
        assertEquals(List.of("smelt"), ManagerIndex.craftCan(b4, true));
        assertTrue(ManagerIndex.craftCan(null, true, true, true, true, true).isEmpty());
        // Extras that list no B4 kind, a stew kind with no flower, or only some of the copy kinds, say less
        JsonObject j = JsonParser.parseString(b4.json().toString()).getAsJsonObject();
        JsonObject noStew = JsonParser.parseString(j.toString()).getAsJsonObject();
        noStew.add("stew", new JsonObject());
        assertEquals(List.of("tipped", "copy"), ManagerIndex.craftCan(relist(noStew), false, false, true, true, true));
        JsonObject noShield = JsonParser.parseString(j.toString()).getAsJsonObject();
        noShield.add("special", JsonParser.parseString("[\"rocket\",\"star\",\"star_fade\",\"tipped\",\"stew\",\"banner_copy\",\"map_copy\",\"book_copy\"]"));
        assertEquals(List.of("tipped", "stew"), ManagerIndex.craftCan(relist(noShield), false, false, true, true, true), "copy needs all four kinds");
        JsonObject b3 = JsonParser.parseString(j.toString()).getAsJsonObject();
        b3.add("special", JsonParser.parseString("[\"rocket\",\"star\",\"star_fade\"]"));
        assertEquals(List.of("firework"), ManagerIndex.craftCan(relist(b3), false, true, true, true, true), "a B3 server's extras: no B4 kind");
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
        Extras b4 = SpecialFixtures.EXTRAS;
        Variants.Kinds all = ManagerIndex.specKinds(b4, true, true, true);
        assertNotNull(all.fireworks());
        assertTrue(all.tipped());
        assertTrue(all.stew());
        Variants.Kinds none = ManagerIndex.specKinds(b4, false, false, false);
        assertFalse(none.any());
        assertFalse(ManagerIndex.specKinds(null, true, true, true).any());
        assertEquals(new Variants.Kinds(null, true, false), ManagerIndex.specKinds(b4, false, true, false));
    }

    @Test
    void theHeartbeatSaysTheB4Kinds() {
        ManagerIndex.CraftOrders base = new ManagerIndex.CraftOrders(true, "a".repeat(40), "1.21.4", "ok", 2048, List.of());
        ManagerIndex.CraftOrders on = base.withV2(SpecialFixtures.SERVER_HASH, ManagerIndex.craftCan(SpecialFixtures.EXTRAS, false, false, true, true, true));
        ManagerIndex.Built b = ManagerIndex.build(new Stash("s", "2b2t.org"), false, "Bot", "1.21.4", "overworld", id -> null);
        JsonObject o = JsonParser.parseString(new String(ManagerIndex.heartbeat(b, -1, null, null, null, null, null, null, null, null, new ManagerIndex.Craft(on, null)), StandardCharsets.UTF_8))
            .getAsJsonObject().getAsJsonObject("craftOrders");
        assertEquals(SpecialFixtures.SERVER_HASH, o.get("extras").getAsString());
        List<String> can = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("can")) can.add(e.getAsString());
        assertEquals(List.of("tipped", "stew", "copy"), can);
        for (String c : can) assertTrue(c.matches("^[a-z_]{1,24}$"), c);
    }

    // The extras of the real server

    @Test
    void theRealB4ServersExtrasAreAcceptedWithTheServersHashAndHoldTheStewTable() throws Exception {
        Extras e = SpecialFixtures.EXTRAS;
        assertEquals(SpecialFixtures.SERVER_HASH, e.hash());
        assertEquals(SpecialFixtures.SERVER_HASH, Extras.compute(e.json()), "the normalised copy hashes to the same value");
        assertEquals(9, e.json().getAsJsonArray("special").size());
        assertEquals(16, e.json().getAsJsonObject("stew").size());
        VariantSpecs.Tables t = VariantSpecs.Tables.of(e);
        assertNotNull(t);
        assertTrue(t.canTipped() && t.canStew() && t.canCopy());
        assertEquals(List.of("minecraft:poppy", "minecraft:torchflower"), t.flowersOf("minecraft:night_vision", 100));
        assertEquals(List.of("minecraft:blue_orchid", "minecraft:dandelion"), t.flowersOf("minecraft:saturation", 7));
        assertEquals(4, t.flowersOf("minecraft:weakness", 180).size());
        assertTrue(t.flowersOf("minecraft:night_vision", 101).isEmpty());
        // Each stew line is part of the hash: another duration of one flower is another extras block
        JsonObject changed = JsonParser.parseString(e.json().toString()).getAsJsonObject();
        changed.getAsJsonObject("stew").getAsJsonObject("minecraft:poppy").addProperty("ticks", 101);
        assertNotEquals(SpecialFixtures.SERVER_HASH, Extras.compute(changed));
        assertEquals(Set.of("rocket", "star", "star_fade", "tipped", "stew", "banner_copy", "map_copy", "book_copy", "shield"), t.special());
    }
}
