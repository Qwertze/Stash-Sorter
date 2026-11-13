package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.core.enchant.McData;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The enchant block of an order (ENCHANT-CONTRACT v1.0 "Order create, retry, lines", PROTOCOL.md "Enchant orders"): the lines with the server's
 * plan per item, and {@code take}, the exact index keys (with counts) the order reserved, which is the only stock the bot may use. Plain data;
 * the server's numbers are claims: the bot plans again with the real items ({@link EnchantReplan}) and this plan is only what the cost
 * is compared with. Enchantment ids are kept plain ({@code sharpness}), item keys as the server writes them.
 */
public final class EnchantOrder {
    private EnchantOrder() {
    }

    public static final String BOTTLE_KEY = "minecraft:experience_bottle";
    /** Caps on what is read (the server's caps are lower: 16 items, 256 take entries). */
    public static final int MAX_ITEMS = 16, MAX_LINES = 8, MAX_TAKE = 256, MAX_MATERIALS = 14, MAX_STEPS = 13, MAX_ENCH = 64;
    /** What a plan or a take entry may say (a block is the server's claim: nothing past what an order can hold is believed). */
    public static final int MAX_COUNT = 100_000, MAX_POINTS = 1_000_000, MAX_BOTTLES = EnchantCarry.bottleCeiling(EnchantCarry.MAX_CARRY);

    /** A material of a plan: {@code kind} is "base" or "book", {@code key} the index key, the enchantments and the stored repair cost. */
    public record Mat(String kind, String key, Map<String, Integer> ench, int rc, boolean crafted) {
        /** A material taken from the stash (every material before ENCHANT-CONTRACT draft 3.4). */
        public Mat(String kind, String key, Map<String, Integer> ench, int rc) {
            this(kind, key, ench, rc, false);
        }

        public boolean book() {
            return "book".equals(kind);
        }
    }

    /** A step: left and right item numbers, the cost in levels and the stored repair cost of the result. */
    public record Step(int left, int right, int cost, int rc) {
    }

    /** The server's plan for one item. */
    public record ItemPlan(List<Mat> materials, List<Step> steps, Map<String, Integer> levels, Map<String, Integer> extras, List<String> curse, int points,
                           int bottles) {
        public int leaves() {
            return materials.size();
        }

        /** The index keys of the books, with counts. */
        public Map<String, Integer> bookKeys() {
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Mat m : materials) if (m.book()) out.merge(m.key(), 1, Integer::sum);
            return out;
        }

        public @Nullable String baseKey() {
            for (Mat m : materials) if (!m.book()) return m.key();
            return null;
        }

        /**
         * The base is made by the order's craft block (draft 3.4 {@code crafted: true}): a fresh item of the plain id {@link #baseKey}, held in the inventory
         * already, not taken from the stash and not in the order's {@code take}.
         */
        public boolean baseCrafted() {
            for (Mat m : materials) if (!m.book()) return m.crafted();
            return false;
        }

        public int books() {
            int n = 0;
            for (Mat m : materials) if (m.book()) n++;
            return n;
        }
    }

    /** One line: the item key, how many, the ordered enchantments and one plan per item. */
    public record Line(String key, int count, Map<String, Integer> enchants, List<ItemPlan> plans) {
    }

    /** An index key and a count the order reserved. */
    public record Take(String key, int count) {
    }

    /** One item of the order, numbered over all lines in order. */
    public record ItemRef(int n, int line, int inLine, String key, Map<String, Integer> enchants, ItemPlan plan) {
    }

    /** The block. {@code malformed}: it is there but could not be read: every enchant line is short. */
    public record Block(int v, String mc, List<Line> lines, List<Take> take, List<String> curse, boolean malformed) {
        public static Block unreadable() {
            return new Block(0, "", List.of(), List.of(), List.of(), true);
        }

        /** Every item of every line, numbered in order. */
        public List<ItemRef> items() {
            List<ItemRef> out = new ArrayList<>();
            for (int l = 0; l < lines.size(); l++) {
                Line line = lines.get(l);
                for (int k = 0; k < line.plans().size(); k++) out.add(new ItemRef(out.size(), l, k, line.key(), line.enchants(), line.plans().get(k)));
            }
            return out;
        }

        public Map<String, Long> takeMap() {
            Map<String, Long> out = new LinkedHashMap<>();
            for (Take t : take) out.merge(t.key(), (long) t.count(), Long::sum);
            return out;
        }
    }

    /** The plain id of a namespaced one ("minecraft:sharpness" as "sharpness"). */
    public static String plain(String id) {
        return McData.norm(id);
    }

    /** The namespaced id ("sharpness" as "minecraft:sharpness"). */
    public static String ns(String id) {
        String p = plain(id);
        return p.indexOf(':') >= 0 ? p : "minecraft:" + p;
    }

    /** Reads the {@code enchant} field of an order: null for none (absent or null), {@link Block#unreadable()} for one that is there but wrong. */
    public static @Nullable Block parse(@Nullable JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        try {
            if (!e.isJsonObject()) return Block.unreadable();
            JsonObject o = e.getAsJsonObject();
            int v = o.get("v").getAsInt();
            String mc = o.get("mc").getAsString();
            List<Line> lines = new ArrayList<>();
            JsonArray la = o.getAsJsonArray("lines");
            if (la.size() > MAX_LINES) return Block.unreadable();
            int items = 0;
            for (JsonElement x : la) {
                JsonObject lo = x.getAsJsonObject();
                String key = lo.get("key").getAsString();
                int count = lo.get("count").getAsInt();
                Map<String, Integer> enchants = levels(lo.get("enchants"));
                List<ItemPlan> plans = new ArrayList<>();
                JsonArray pa = lo.getAsJsonArray("plans");
                for (JsonElement p : pa) plans.add(plan(p.getAsJsonObject()));
                items += plans.size();
                if (items > MAX_ITEMS || count < 1 || plans.size() > count) return Block.unreadable();
                lines.add(new Line(key, count, enchants, plans));
            }
            List<Take> take = new ArrayList<>();
            JsonArray ta = o.getAsJsonArray("take");
            if (ta.size() > MAX_TAKE) return Block.unreadable();
            for (JsonElement x : ta) {
                JsonObject t = x.getAsJsonObject();
                long c = t.get("count").getAsLong();
                if (c < 0 || c > MAX_COUNT) return Block.unreadable();
                take.add(new Take(t.get("key").getAsString(), (int) c));
            }
            List<String> curse = new ArrayList<>();
            if (o.has("curse") && o.get("curse").isJsonArray()) for (JsonElement c : o.getAsJsonArray("curse")) curse.add(plain(c.getAsString()));
            return new Block(v, mc, lines, take, curse, false);
        } catch (RuntimeException ex) {
            return Block.unreadable();
        }
    }

    private static ItemPlan plan(JsonObject p) {
        List<Mat> mats = new ArrayList<>();
        JsonArray ma = p.getAsJsonArray("materials");
        if (ma.size() > MAX_MATERIALS || ma.isEmpty()) throw new IllegalArgumentException("materials");
        for (JsonElement m : ma) {
            JsonObject mo = m.getAsJsonObject();
            String kind = mo.get("kind").getAsString();
            if (!kind.equals("base") && !kind.equals("book")) throw new IllegalArgumentException("kind");
            boolean crafted = mo.has("crafted") && mo.get("crafted").isJsonPrimitive() && mo.get("crafted").getAsJsonPrimitive().isBoolean() && mo.get("crafted").getAsBoolean();
            Mat mat = new Mat(kind, mo.get("key").getAsString(), levels(mo.get("ench")), Math.max(0, mo.has("rc") ? mo.get("rc").getAsInt() : 0), crafted);
            // A crafted base is a fresh item of a plain id: anything else is not a plan this bot follows
            if (crafted && (mat.book() || !mat.ench().isEmpty() || mat.rc() != 0 || mat.key().indexOf('#') >= 0)) throw new IllegalArgumentException("a crafted base is a fresh plain item");
            mats.add(mat);
        }
        if (!"base".equals(mats.get(0).kind())) throw new IllegalArgumentException("the first material is the base");
        List<Step> steps = new ArrayList<>();
        JsonArray sa = p.getAsJsonArray("steps");
        if (sa.size() > MAX_STEPS) throw new IllegalArgumentException("steps");
        for (JsonElement s : sa) {
            JsonObject so = s.getAsJsonObject();
            steps.add(new Step(so.get("left").getAsInt(), so.get("right").getAsInt(), so.get("cost").getAsInt(), Math.max(0, so.get("rc").getAsInt())));
        }
        List<String> curse = new ArrayList<>();
        if (p.has("curse") && p.get("curse").isJsonArray()) for (JsonElement c : p.getAsJsonArray("curse")) curse.add(plain(c.getAsString()));
        long points = p.get("points").getAsLong(), bottles = p.get("bottles").getAsLong();
        // A plan the carry formula could never hold, or with absurd numbers, is not read (the server's own ceiling is 2,048 bottles an item)
        if (points < 0 || points > MAX_POINTS || bottles < 0 || bottles > MAX_BOTTLES) throw new IllegalArgumentException("numbers");
        for (int i = 0; i < steps.size(); i++) {
            Step st = steps.get(i);
            if (st.left() < 0 || st.right() < 0 || st.left() >= mats.size() + i || st.right() >= mats.size() + i || st.left() == st.right() || st.cost() < 1 || st.cost() > 39) throw new IllegalArgumentException("step");
        }
        return new ItemPlan(mats, steps, levels(p.get("levels")), levels(p.get("extras")), curse, (int) points, (int) bottles);
    }

    /** {@code [ {id, lvl} ]} (or null) as plain id to level; sorted. */
    static Map<String, Integer> levels(@Nullable JsonElement e) {
        Map<String, Integer> out = new TreeMap<>();
        if (e == null || e.isJsonNull()) return out;
        JsonArray a = e.getAsJsonArray();
        if (a.size() > MAX_ENCH) throw new IllegalArgumentException("too many enchantments");
        for (JsonElement x : a) {
            JsonObject o = x.getAsJsonObject();
            int lvl = o.get("lvl").getAsInt();
            if (lvl < 1 || lvl > 255) throw new IllegalArgumentException("level");
            out.put(plain(o.get("id").getAsString()), lvl);
        }
        return out;
    }

    /** Plain id to level as {@code [ {id, lvl} ]} with namespaced ids (the wire form). */
    public static JsonArray levelsJson(Map<String, Integer> levels) {
        JsonArray a = new JsonArray();
        for (var e : new TreeMap<>(levels).entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", ns(e.getKey()));
            o.addProperty("lvl", e.getValue());
            a.add(o);
        }
        return a;
    }
}
