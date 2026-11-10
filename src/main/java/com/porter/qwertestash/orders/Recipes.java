package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The crafting recipes of the game version the bot plays (1.21.4 on 2b2t). Since 1.15.0 the table is fetched from the Stash Manager
 * server ({@code GET /api/v1/bot/recipes?mc=}) and cached in the plugin's data folder; none is shipped in the plugin jar. Since
 * 1.21.2 the game server sends no full recipes, so nothing here comes from the proxy. Only crafting_shaped and crafting_shapeless are in the table; what is made
 * at another station (a furnace, a smithing table, ...) or by a special recipe is listed in {@code otherStations}.
 * Item ids are namespaced ("minecraft:chest"). Plain data, safe to share between threads once loaded.
 */
public final class Recipes {
    /**
     * One recipe. Shaped: {@code w} x {@code h} cells, row by row, a null cell is empty. Shapeless: the cells are the
     * ingredients and w = h = 0. Each cell lists the item ids it accepts.
     */
    public record Recipe(String id, boolean shaped, int w, int h, List<@Nullable List<String>> cells, String result, int count) {
        /** The cells that take an item, in order. */
        public List<List<String>> ingredients() {
            List<List<String>> out = new ArrayList<>();
            for (List<String> c : cells) if (c != null && !c.isEmpty()) out.add(c);
            return out;
        }

        /** Needs the 3x3 grid of a crafting table (more than 2 wide or high, or more than 4 ingredients). */
        public boolean needsTable() {
            return shaped ? w > 2 || h > 2 : ingredients().size() > 4;
        }
    }

    private final String version, hash;
    private final Map<String, List<Recipe>> byResult;
    private final Map<String, Recipe> byId;
    private final Map<String, String> otherStations;

    private Recipes(String version, String hash, Map<String, List<Recipe>> byResult, Map<String, Recipe> byId, Map<String, String> otherStations) {
        this.version = version;
        this.hash = hash;
        this.byId = byId;
        this.byResult = byResult;
        this.otherStations = otherStations;
    }

    private static volatile @Nullable Recipes current;

    /**
     * The table the bot crafts by: the one fetched from the Stash Manager server and cached (see {@code RecipeStore}), or null while
     * there is none. Since 1.15.0 no table is shipped in the plugin jar, so with null nothing is crafted (shulker boxes included) and
     * no craft orders are accepted. Replaced whole, read from any thread.
     */
    /** A table with no recipe at all (what a craft order is checked against when the bot has none: nothing matches). */
    public static Recipes empty() {
        return new Recipes("?", "", new HashMap<>(), new HashMap<>(), new HashMap<>());
    }

    public static @Nullable Recipes current() {
        return current;
    }

    public static void setCurrent(@Nullable Recipes table) {
        current = table;
    }

    /**
     * The contract's tableHash (CRAFT-CONTRACT.md "tableHash"): the lowercase hex SHA-1 of the UTF-8 text with one line per recipe,
     * sorted by id (String.compareTo): {@code id TAB type TAB w TAB h TAB result TAB count TAB body NEWLINE}. For a shaped recipe the
     * body is its cells joined by "|", each "-" when empty, else its alternatives joined by ","; for a shapeless one each
     * ingredient's alternatives joined by ",", those strings sorted and joined by "|". w and h are 0 for shapeless recipes.
     * {@code otherStations} and everything else in the table is not hashed.
     */
    public static String tableHash(JsonObject root) {
        List<String[]> rows = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("recipes")) {
            JsonObject o = e.getAsJsonObject();
            String type = o.get("type").getAsString();
            boolean shaped = type.equals("shaped");
            if (!shaped && !type.equals("shapeless")) continue;
            List<String> parts = new ArrayList<>();
            for (JsonElement c : o.getAsJsonArray(shaped ? "cells" : "ingredients")) {
                if (c.isJsonNull()) {
                    parts.add("-");
                    continue;
                }
                List<String> alts = new ArrayList<>();
                for (JsonElement a : c.getAsJsonArray()) alts.add(a.getAsString());
                parts.add(String.join(",", alts));
            }
            if (!shaped) Collections.sort(parts);
            int count = o.has("count") && !o.get("count").isJsonNull() ? o.get("count").getAsInt() : 1;
            String id = o.get("id").getAsString();
            String line = id + "\t" + type + "\t" + (shaped ? o.get("w").getAsInt() : 0) + "\t" + (shaped ? o.get("h").getAsInt() : 0)
                + "\t" + o.get("result").getAsString() + "\t" + count + "\t" + String.join("|", parts) + "\n";
            rows.add(new String[] {id, line});
        }
        rows.sort((a, b) -> a[0].compareTo(b[0]));
        StringBuilder text = new StringBuilder();
        for (String[] r : rows) text.append(r[1]);
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The tableHash of this table, computed from the file it was read from. */
    public String hash() {
        return hash;
    }

    public static Recipes parse(Reader reader) {
        return fromJson(JsonParser.parseReader(reader).getAsJsonObject());
    }

    /** A table from its parsed JSON (the hash is computed from it). A missing {@code count} is 1, here and in {@link #tableHash}. */
    public static Recipes fromJson(JsonObject root) {
        String hash = tableHash(root);
        String version = root.has("version") ? root.get("version").getAsString() : "?";
        Map<String, List<Recipe>> byResult = new HashMap<>();
        Map<String, Recipe> byId = new HashMap<>();
        for (JsonElement e : root.getAsJsonArray("recipes")) {
            JsonObject o = e.getAsJsonObject();
            String type = o.get("type").getAsString();
            boolean shaped = type.equals("shaped");
            if (!shaped && !type.equals("shapeless")) continue;
            List<List<String>> cells = new ArrayList<>();
            for (JsonElement c : o.getAsJsonArray(shaped ? "cells" : "ingredients")) cells.add(c.isJsonNull() ? null : ids(c.getAsJsonArray()));
            int w = shaped ? o.get("w").getAsInt() : 0, h = shaped ? o.get("h").getAsInt() : 0;
            Recipe r = new Recipe(o.get("id").getAsString(), shaped, w, h, Collections.unmodifiableList(cells), o.get("result").getAsString(),
                Math.max(1, o.has("count") && !o.get("count").isJsonNull() ? o.get("count").getAsInt() : 1));
            byResult.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
            byId.put(r.id(), r);
        }
        for (List<Recipe> list : byResult.values()) list.sort((a, b) -> a.id().compareTo(b.id()));
        Map<String, String> other = new HashMap<>();
        if (root.has("otherStations")) for (var s : root.getAsJsonObject("otherStations").entrySet()) other.put(s.getKey(), s.getValue().getAsString());
        return new Recipes(version, hash, byResult, byId, other);
    }

    private static List<String> ids(JsonArray a) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : a) out.add(e.getAsString());
        return Collections.unmodifiableList(out);
    }

    public String version() {
        return version;
    }

    public int size() {
        int n = 0;
        for (List<Recipe> l : byResult.values()) n += l.size();
        return n;
    }

    /** Every recipe in the table, by id. */
    public java.util.Collection<Recipe> all() {
        return byId.values();
    }

    /** The recipe with this id ("minecraft:spruce_stairs"), or null. */
    public @Nullable Recipe byId(String id) {
        return byId.get(id);
    }

    /** The crafting recipes that make this item (namespaced id), by recipe id. Empty when it isn't crafted. */
    public List<Recipe> forResult(String item) {
        return byResult.getOrDefault(ns(item), List.of());
    }

    /** "smelting", "smithing", "transmute", "special", ... for an item made elsewhere than a crafting grid, or null. */
    public @Nullable String station(String item) {
        return otherStations.get(ns(item));
    }

    /** Every item the recipes for this one can use, down to {@code depth} steps (the item itself left out). */
    public Set<String> inputsOf(String item, int depth) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String[]> todo = new ArrayDeque<>();
        todo.add(new String[] {ns(item), "0"});
        Set<String> seen = new java.util.HashSet<>();
        while (!todo.isEmpty()) {
            String[] next = todo.poll();
            int d = Integer.parseInt(next[1]);
            if (d >= depth || !seen.add(next[0])) continue;
            for (Recipe r : forResult(next[0])) {
                for (List<String> cell : r.ingredients()) {
                    for (String id : cell) {
                        out.add(id);
                        todo.add(new String[] {id, String.valueOf(d + 1)});
                    }
                }
            }
        }
        out.remove(ns(item));
        return out;
    }

    /** "chest" or "minecraft:chest" as "minecraft:chest". */
    public static String ns(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /** "minecraft:oak_log" as "Oak Log". */
    public static String pretty(String id) {
        String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return com.porter.qwertestash.stash.Item.prettyName(bare);
    }
}
