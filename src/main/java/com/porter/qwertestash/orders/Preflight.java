package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The check a bot makes before it withdraws anything for a v2 craft order (CRAFT-CONTRACT-v1.2.md 11.2): the stash, counted the way the bot's index
 * counts it (stash containers only, the bot's own inventory left out, complete-kit shulkers' contents left out), must cover every item line of the
 * order (plain lines and the stock parts of {@code orCraft} lines) plus the block's {@code craft.take}, key by key. Shulker lines, shulker box item
 * lines, kit lines and enchant lines keep their own checks. When something is short the order is failed at once and nothing is taken, unpacked or
 * crafted. Pure.
 */
public final class Preflight {
    private Preflight() {
    }

    /** The most {@code restock} entries a report carries, and the shortfalls named in the message (CRAFT-CONTRACT-v1.2.md 8.3, 11.2). */
    public static final int MAX_RESTOCK = 32, MESSAGE_SHORTFALLS = 3;

    /** @param key an index key (an item line's key, or a take id) and how many of it the stash lacks */
    public record Shortfall(String key, long missing) {
    }

    /**
     * What the order needs of each key, in the order the keys come: item lines (not shulker boxes) first, then the take of the craft block.
     * @param craft the order's craft block (a v2 block that passed its check), or null
     */
    public static Map<String, Long> needs(Order order, Order.Craft craft) {
        Map<String, Long> need = new LinkedHashMap<>();
        for (Order.Line l : order.lines) {
            if (!l.type().equals(Order.ITEM) || StockPlanner.isShulkerKey(l.key())) continue;
            need.merge(l.key(), (long) l.count(), Long::sum);
        }
        // A take entry with a key (B3, a rocket or star) reserves that index key (CRAFT-CONTRACT-v1.2.md 9); one without reserves its id
        if (craft != null) for (Order.Qty t : craft.take()) need.merge(t.key() != null ? t.key() : t.id(), (long) t.count(), Long::sum);
        return need;
    }

    /** What the stash lacks of {@code need}, by key, in the order of {@code need}. Empty: the stash covers it. */
    public static List<Shortfall> check(Map<String, Long> need, Map<String, Long> stock) {
        List<Shortfall> out = new ArrayList<>();
        for (var e : need.entrySet()) {
            long have = Math.max(0, stock.getOrDefault(e.getKey(), 0L));
            if (have < e.getValue()) out.add(new Shortfall(e.getKey(), e.getValue() - have));
        }
        return out;
    }

    /** "Not enough in the stash any more to make this order: 7 Paper, 3 Chest. Restock and retry." (at most 3 shortfalls, then "and N more"). */
    public static String message(List<Shortfall> shortfalls) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < shortfalls.size() && i < MESSAGE_SHORTFALLS; i++) {
            Shortfall s = shortfalls.get(i);
            parts.add(s.missing() + " " + Recipes.pretty(idOf(s.key())));
        }
        String list = String.join(", ", parts);
        if (shortfalls.size() > MESSAGE_SHORTFALLS) list += ", and " + (shortfalls.size() - MESSAGE_SHORTFALLS) + " more";
        return "Not enough in the stash any more to make this order: " + list + ". Restock and retry.";
    }

    /**
     * The {@code restock} list of the report (CRAFT-CONTRACT-v1.2.md 8.3): one entry per short key with a single option {@code [{ids: [key], count}]}.
     * At most {@link #MAX_RESTOCK} entries.
     */
    public static JsonArray restock(List<Shortfall> shortfalls) {
        JsonArray out = new JsonArray();
        for (Shortfall s : shortfalls) {
            if (out.size() >= MAX_RESTOCK) break;
            JsonArray ids = new JsonArray();
            ids.add(s.key());
            JsonObject item = new JsonObject();
            item.add("ids", ids);
            item.addProperty("count", s.missing());
            JsonArray option = new JsonArray();
            option.add(item);
            JsonArray options = new JsonArray();
            options.add(option);
            JsonObject entry = new JsonObject();
            entry.add("options", options);
            out.add(entry);
        }
        return out;
    }

    /** The item id of an index key: the part before a component hash ({@code minecraft:chest#ab12cd34} is {@code minecraft:chest}). */
    static String idOf(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }
}
