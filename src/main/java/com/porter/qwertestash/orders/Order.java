package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An order from the Stash Manager server: what to take from the stash, as lines, and the packer that turns item lines
 * into shulker loads. Plain Java (no game types), a port of the Meteor addon's {@code Order}; the packing is the one
 * PROTOCOL.md specifies, so the website's preview and the bot agree.
 */
public final class Order {
    public static final String ITEM = "item", SHULKER = "shulker";
    /** The most shulkers one order may need (one double chest). */
    public static final int MAX_BINS = 54;
    public static final int SLOTS = 27;

    /** One line: {@code type} is "item" (key is an items[] key) or "shulker" (key is a shulkers[] group key). */
    public record Line(String type, String key, int count) {
    }

    /**
     * The stacks to put in one slot: an item key, how many, and (once the stash was looked at) the exact variant, the hash
     * over every component, so the stack is taken from a stack that can really merge with it. Null: any variant.
     */
    public record Lot(String key, @Nullable String variant, int count) {
        public Lot(String key, int count) {
            this(key, null, count);
        }

        public Lot withVariant(@Nullable String v) {
            return new Lot(key, v, count);
        }
    }

    public final String id;
    public final List<Line> lines;
    /** The names the order's shulkers get, or null for none. */
    public final @Nullable ShulkerNames names;

    public Order(String id, List<Line> lines) {
        this(id, lines, null);
    }

    public Order(String id, List<Line> lines, @Nullable ShulkerNames names) {
        this.id = id;
        this.lines = lines;
        this.names = names;
    }

    /** Reads an order object from the server. Null when it has no id or no usable line. */
    public static @Nullable Order parse(@Nullable JsonObject o) {
        if (o == null || !o.has("id") || !o.has("lines") || !o.get("lines").isJsonArray()) return null;
        List<Line> lines = new ArrayList<>();
        JsonArray array = o.getAsJsonArray("lines");
        for (JsonElement e : array) {
            if (!e.isJsonObject()) continue;
            JsonObject l = e.getAsJsonObject();
            try {
                String type = l.get("type").getAsString();
                String key = l.get("key").getAsString();
                int count = l.get("count").getAsInt();
                if ((type.equals(ITEM) || type.equals(SHULKER)) && !key.isEmpty() && count >= 1) lines.add(new Line(type, key, count));
            } catch (RuntimeException ex) {
                // A broken line is left out and shows up as undelivered
            }
        }
        String id;
        try {
            id = o.get("id").getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
        // A broken names field is read as no names: naming never fails an order
        return id.isEmpty() || lines.isEmpty() ? null : new Order(id, lines, ShulkerNames.parse(o.get("names")));
    }

    /** The total asked for per line key, lines with the same key merged. */
    public Map<String, Integer> requested() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Line l : lines) out.merge(l.key(), l.count(), Integer::sum);
        return out;
    }

    /** Splits a total of one item into slot loads: full stacks, the remainder last. */
    public static List<Lot> expand(String key, int total, int maxStack) {
        List<Lot> lots = new ArrayList<>();
        int max = Math.max(1, maxStack);
        for (int left = total; left > 0; left -= max) lots.add(new Lot(key, Math.min(max, left)));
        return lots;
    }

    /**
     * Packs slot loads into shulkers of 27 slots, any mix of items allowed. Loads of one item are kept together (keys in
     * ascending order), the items with the most loads first, the largest load first within one, then they fill the
     * shulkers in sequence: the fewest shulkers is ceil(loads / 27).
     */
    public static List<List<Lot>> pack(List<Lot> lots) {
        Map<String, List<Lot>> byKey = new TreeMap<>();
        for (Lot lot : lots) byKey.computeIfAbsent(lot.key(), k -> new ArrayList<>()).add(lot);

        List<List<Lot>> groups = new ArrayList<>(byKey.values());
        groups.sort((a, b) -> b.size() - a.size());

        List<Lot> ordered = new ArrayList<>();
        for (List<Lot> g : groups) {
            g.sort((a, b) -> b.count() - a.count());
            ordered.addAll(g);
        }

        List<List<Lot>> bins = new ArrayList<>();
        for (Lot lot : ordered) {
            if (bins.isEmpty() || bins.get(bins.size() - 1).size() >= SLOTS) bins.add(new ArrayList<>());
            bins.get(bins.size() - 1).add(lot);
        }
        return bins;
    }
}
