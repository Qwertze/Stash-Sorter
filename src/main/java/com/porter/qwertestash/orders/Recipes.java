package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
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
 * The crafting recipes of the game version the bot plays (1.21.4 on 2b2t), read from the table shipped in the plugin
 * ({@code qwerte-stash/recipes/1.21.4.json}, made offline from the game jar). Since 1.21.2 the server sends no full recipes
 * any more, so nothing here comes from the proxy. Only crafting_shaped and crafting_shapeless are in the table; what is made
 * at another station (a furnace, a smithing table, ...) or by a special recipe is listed in {@code otherStations}.
 * Item ids are namespaced ("minecraft:chest"). Plain data, safe to share between threads once loaded.
 */
public final class Recipes {
    public static final String RESOURCE = "/qwerte-stash/recipes/1.21.4.json";

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

    private final String version;
    private final Map<String, List<Recipe>> byResult;
    private final Map<String, String> otherStations;

    private Recipes(String version, Map<String, List<Recipe>> byResult, Map<String, String> otherStations) {
        this.version = version;
        this.byResult = byResult;
        this.otherStations = otherStations;
    }

    private static volatile @Nullable Recipes shipped;

    /** The table shipped in the plugin jar, read once. */
    public static Recipes shipped() {
        Recipes r = shipped;
        if (r != null) return r;
        synchronized (Recipes.class) {
            if (shipped == null) {
                try (InputStream in = Recipes.class.getResourceAsStream(RESOURCE)) {
                    if (in == null) throw new IllegalStateException("the recipe table " + RESOURCE + " is missing from the plugin jar");
                    shipped = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("couldn't read the recipe table: " + e.getMessage(), e);
                }
            }
            return shipped;
        }
    }

    public static Recipes parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        String version = root.has("version") ? root.get("version").getAsString() : "?";
        Map<String, List<Recipe>> byResult = new HashMap<>();
        for (JsonElement e : root.getAsJsonArray("recipes")) {
            JsonObject o = e.getAsJsonObject();
            String type = o.get("type").getAsString();
            boolean shaped = type.equals("shaped");
            if (!shaped && !type.equals("shapeless")) continue;
            List<List<String>> cells = new ArrayList<>();
            for (JsonElement c : o.getAsJsonArray(shaped ? "cells" : "ingredients")) cells.add(c.isJsonNull() ? null : ids(c.getAsJsonArray()));
            int w = shaped ? o.get("w").getAsInt() : 0, h = shaped ? o.get("h").getAsInt() : 0;
            Recipe r = new Recipe(o.get("id").getAsString(), shaped, w, h, Collections.unmodifiableList(cells), o.get("result").getAsString(), Math.max(1, o.get("count").getAsInt()));
            byResult.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
        }
        for (List<Recipe> list : byResult.values()) list.sort((a, b) -> a.id().compareTo(b.id()));
        Map<String, String> other = new HashMap<>();
        if (root.has("otherStations")) for (var s : root.getAsJsonObject("otherStations").entrySet()) other.put(s.getKey(), s.getValue().getAsString());
        return new Recipes(version, byResult, other);
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
