package com.porter.qwertestash.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.ItemKeys;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Fireworks;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.PotionContents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test helper: the shared vector files of the kit contract (test resources {@code kit2/}, copies of the contract's files byte for byte) and the
 * conversion of the structured stacks in them into what the plugin keys: a stack's components are built as the game's component objects and
 * the plugin's own {@link ItemKeys#matchKey} is applied, so the vectors judge the real algorithm.
 */
public final class Kit2Vectors {
    private Kit2Vectors() {
    }

    public static JsonObject read(String name) {
        try (InputStream in = Kit2Vectors.class.getResourceAsStream("/kit2/" + name)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] bytes(String name) {
        try (InputStream in = Kit2Vectors.class.getResourceAsStream("/kit2/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Map<String, Integer> IDS = new HashMap<>();

    /** A stable int for a registry name (enchantment or potion): the tests only need the same name to give the same number. */
    private static int numberOf(String name) {
        return IDS.computeIfAbsent(name, n -> 100 + IDS.size());
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** The component patch of a vector stack: enchantments, potion contents, fireworks, custom name, damage, repair cost. */
    public static DataComponents components(JsonObject stack) {
        DataComponents c = new DataComponents(new HashMap<>());
        if (!stack.has("components")) return c;
        for (var e : stack.getAsJsonObject("components").entrySet()) {
            JsonElement v = e.getValue();
            switch (e.getKey()) {
                case "minecraft:enchantments" -> {
                    Int2IntOpenHashMap levels = new Int2IntOpenHashMap();
                    for (var en : v.getAsJsonObject().entrySet()) levels.put(numberOf(en.getKey()), en.getValue().getAsInt());
                    c.put(DataComponentTypes.ENCHANTMENTS, ItemEnchantments.builder().enchantments(levels).build());
                }
                case "minecraft:potion_contents" -> c.put(DataComponentTypes.POTION_CONTENTS, PotionContents.builder().potionId(numberOf(v.getAsJsonObject().get("potion").getAsString())).build());
                case "minecraft:fireworks" -> c.put(DataComponentTypes.FIREWORKS, Fireworks.builder().flightDuration(v.getAsJsonObject().get("flight_duration").getAsInt())
                    .explosions(new ArrayList<>()).build());
                case "minecraft:custom_name" -> c.put(DataComponentTypes.CUSTOM_NAME, Component.text(v.getAsString()));
                case "minecraft:damage" -> c.put(DataComponentTypes.DAMAGE, v.getAsInt());
                case "minecraft:repair_cost" -> c.put(DataComponentTypes.REPAIR_COST, v.getAsInt());
                default -> throw new IllegalStateException("a component the test does not build: " + e.getKey());
            }
        }
        return c;
    }

    /** The plugin's match key of a vector stack. */
    public static String matchKey(JsonObject stack) {
        return ItemKeys.matchKey(shortId(stack.get("id").getAsString()), components(stack), false);
    }

    /** The occupied slots of a vector box, in slot order, as the classifier and the template match take them. */
    public static List<KitTemplates.BoxStack> box(JsonArray slots) {
        List<KitTemplates.BoxStack> out = new ArrayList<>();
        for (JsonElement e : slots) {
            if (e.isJsonNull()) continue;
            JsonObject s = e.getAsJsonObject();
            out.add(new KitTemplates.BoxStack(s.get("id").getAsString(), matchKey(s), s.get("count").getAsInt()));
        }
        return out;
    }

    /** A template made of a vector's slots: an {@code any} slot takes any variant, the others the exact match key. */
    public static KitTemplate template(String name, JsonArray slots) {
        List<KitTemplate.Slot> out = new ArrayList<>();
        for (JsonElement e : slots) {
            if (e.isJsonNull()) {
                out.add(null);
                continue;
            }
            JsonObject s = e.getAsJsonObject();
            String id = s.get("id").getAsString();
            if (s.has("any") && s.get("any").getAsBoolean()) out.add(new KitTemplate.Slot(id + "#*", id + "#*", s.get("count").getAsInt()));
            else {
                String match = matchKey(s);
                out.add(new KitTemplate.Slot(match, match, s.get("count").getAsInt()));
            }
        }
        return new KitTemplate("t-00000001", name, "pvp", null, null, 0, java.util.Collections.unmodifiableList(out));
    }
}
