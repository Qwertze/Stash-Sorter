package com.porter.qwertestash.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwertestash.stash.Item;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.TreeMap;

/**
 * The enchant fields of an index item entry (ENCHANT-CONTRACT v1.0, "Index additions"): {@code ench} (the stored enchantments of a book, the
 * enchantments of an item, namespaced ids with levels), {@code rc} (the stored repair cost, a whole number; left out when it is not known) and
 * {@code named} (custom-named: never offered as a base). Only items an anvil order can use carry them: enchanted books, enchanted items and
 * damageable gear (a plain sword is a base, so it carries an empty {@code ench}). The server requires {@code player} (the stash owner) in a
 * post that carries any of them; the index root always has it. Pure.
 */
public final class EnchantFields {
    private EnchantFields() {
    }

    /** The most enchantments one entry lists (the server's limit). */
    public static final int MAX_ENCH = 64;

    /** What is worked out once per index key. {@code rc} is -1 when unknown. */
    public record Fields(Map<String, Integer> ench, int rc, boolean named) {
    }

    /** The fields for an item, or null when the item is not one an enchant order can use. */
    public static @Nullable Fields of(Item item) {
        boolean book = "enchanted_book".equals(item.id);
        boolean enchanted = item.enchantLevels != null && !item.enchantLevels.isEmpty();
        if (!book && !enchanted && item.maxDamage <= 0) return null;
        if (item.isShulker()) return null;
        Map<String, Integer> ench = new TreeMap<>();
        if (item.enchantLevels != null) {
            for (String entry : item.enchantLevels) {
                int colon = entry.lastIndexOf(':');
                if (colon <= 0) continue;
                String id = com.porter.qwerte.core.enchant.McData.norm(entry.substring(0, colon));
                int lvl;
                try {
                    lvl = Integer.parseInt(entry.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (id.isEmpty() || lvl < 1 || lvl > 255) continue;
                ench.merge(id, lvl, Math::max);
            }
        }
        if (ench.size() > MAX_ENCH) return null;
        return new Fields(ench, item.repairCost, item.customName);
    }

    /** Writes the fields into an index item entry. */
    public static void write(JsonObject o, Fields f) {
        JsonArray a = new JsonArray();
        for (var e : f.ench().entrySet()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", "minecraft:" + e.getKey());
            j.addProperty("lvl", e.getValue());
            a.add(j);
        }
        o.add("ench", a);
        if (f.rc() >= 0) o.addProperty("rc", f.rc());
        if (f.named()) o.addProperty("named", true);
    }
}
