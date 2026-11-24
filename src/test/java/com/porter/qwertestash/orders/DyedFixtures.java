package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.manager.Extras;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The B5 test data. The extras are the body the real server (stash-manager-server f672392, {@code HandRecipes}) serves for 1.21.4: ten special kinds, the
 * 16 dyes with their armour and firework colours and the six dyeable items; the vectors ({@code dye-vectors-b5.txt}) were written by that server's own
 * {@code DyeMath}. The blocks are written as the server's planner writes them (PROTOCOL.md "Dyed leather armour and wolf armour": one dyeing recipe, the item
 * in cell 0, the dyes in palette order, a first coat that is the result of an earlier step and names no key, two coats interleaved in chunks of at most 8).
 * Nothing here comes from game files.
 */
public final class DyedFixtures {
    private DyedFixtures() {
    }

    /** The extras hash the real B5 server gives its 1.21.4 extras (the same for 1.21.11 today). */
    public static final String SERVER_HASH = "b8472dca02f00fd47bc32f3d0c6921d9d070d059";

    public static final Extras EXTRAS;
    public static final DyedSpecs.Tables DYED;
    public static final VariantSpecs.Tables TABLES;
    public static final FireworkSpecs.Tables FIREWORKS;
    public static final int[] PALETTE;
    public static final Recipes R = TestRecipes.table();
    /** The bot's test table with the leather chestplate recipe the real table has (8 leather), so a block can craft its base. */
    public static final Recipes LR = withLeather();

    private static Recipes withLeather() {
        try (InputStream in = DyedFixtures.class.getResourceAsStream("/recipes-test.json")) {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            root.getAsJsonArray("recipes").add(JsonParser.parseString("""
                { "id": "minecraft:leather_chestplate", "type": "shaped", "w": 3, "h": 3,
                  "cells": [ ["minecraft:leather"], null, ["minecraft:leather"], ["minecraft:leather"], ["minecraft:leather"], ["minecraft:leather"],
                             ["minecraft:leather"], ["minecraft:leather"], ["minecraft:leather"] ],
                  "result": "minecraft:leather_chestplate", "count": 1 }"""));
            return Recipes.parse(new java.io.StringReader(root.toString()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static {
        try (InputStream in = DyedFixtures.class.getResourceAsStream("/extras-b5-1.21.4.json")) {
            EXTRAS = Extras.parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        DYED = DyedSpecs.Tables.of(EXTRAS);
        TABLES = VariantSpecs.Tables.of(EXTRAS);
        FIREWORKS = FireworkSpecs.Tables.of(EXTRAS);
        PALETTE = DYED.palette();
    }

    public static final Set<String> CAN = Set.of("firework", "tipped", "stew", "copy", "dyed");
    public static final List<String> NAMES = FireworkSpecs.DYES;

    public static final String CHEST = "minecraft:leather_chestplate", BOOTS = "minecraft:leather_boots", HORSE = "minecraft:leather_horse_armor", WOLF = "minecraft:wolf_armor";
    public static final String RED = "dyed:b02e26", ORANGE = "dyed:d78331", BLACK = "dyed:000000";
    public static final String K_RED1 = "minecraft:leather_chestplate#a0a0a0a1", K_RED2 = "minecraft:leather_chestplate#a0a0a0a2", K_BLACK = "minecraft:leather_boots#c0c0c0c1";

    // ---------- the colour arithmetic ----------

    public static int[] mix(String... dyes) {
        int[] counts = new int[16];
        for (String d : dyes) counts[NAMES.indexOf(d)]++;
        return counts;
    }

    // ---------- the vectors of the server's DyeMath ----------

    /** One line of dye-vectors-b5.txt: the start colour (-1 undyed), the dyes per palette slot and the colour the server's DyeMath gives. */
    public record Vector(int start, int[] counts, int result) {
    }

    /** A two-coat path the server's table lists: the final colour, the first coat's colour, and the dyes of each coat. */
    public record Two(int result, int mid, int[] first, int[] second) {
    }

    private static int[] ints(String s) {
        String[] p = s.split(",");
        int[] out = new int[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Integer.parseInt(p[i]);
        return out;
    }

    public static List<Vector> vectors() {
        List<Vector> out = new ArrayList<>();
        for (String line : lines()) {
            if (line.startsWith("#") || line.isBlank()) continue;
            String[] p = line.split("\\|");
            out.add(new Vector(p[0].equals("-1") ? -1 : Integer.parseInt(p[0], 16), ints(p[1]), Integer.parseInt(p[2], 16)));
        }
        return out;
    }

    public static List<Two> twoCoats() {
        List<Two> out = new ArrayList<>();
        for (String line : lines()) {
            if (!line.startsWith("#two|")) continue;
            String[] p = line.split("\\|");
            out.add(new Two(Integer.parseInt(p[1], 16), Integer.parseInt(p[2], 16), ints(p[3]), ints(p[4])));
        }
        return out;
    }

    private static List<String> lines() {
        try (InputStream in = DyedFixtures.class.getResourceAsStream("/dye-vectors-b5.txt")) {
            List<String> out = new ArrayList<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) out.add(l);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- blocks, as the server writes them ----------

    public static String q(String id, int count) {
        return "{ \"id\": \"" + id + "\", \"count\": " + count + " }";
    }

    public static String q(String id, String key, String spec, int count) {
        return "{ \"id\": \"" + id + "\", \"key\": \"" + key + "\", \"spec\": \"" + spec + "\", \"count\": " + count + " }";
    }

    /** An input that is the first coat of an earlier step: a dyed spec and no key. */
    public static String coat(String id, String spec, int count) {
        return "{ \"id\": \"" + id + "\", \"spec\": \"" + spec + "\", \"count\": " + count + " }";
    }

    public static String cell(String id) {
        return "{ \"id\": \"" + id + "\" }";
    }

    public static String cell(String id, String spec) {
        return "{ \"id\": \"" + id + "\", \"spec\": \"" + spec + "\" }";
    }

    public static String dyeId(int palette) {
        return "minecraft:" + NAMES.get(palette) + "_dye";
    }

    /** The nine cells of a grid as JSON: the cells given, then null. */
    public static String grid(List<String> cells) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 9; i++) sb.append(i == 0 ? "" : ",").append(i < cells.size() ? cells.get(i) : "null");
        return sb.append("]").toString();
    }

    /** The grid of a dyeing step: the item (with the first coat's spec, or none), then the dyes in palette order, repeats kept. */
    public static List<String> dyeGrid(String item, String mid, int[] counts) {
        List<String> cells = new ArrayList<>();
        cells.add(mid == null ? cell(item) : cell(item, mid));
        for (int i = 0; i < counts.length; i++) for (int k = 0; k < counts[i]; k++) cells.add(cell(dyeId(i)));
        return cells;
    }

    /** The inputs of a dyeing step: the item (or the first coat), then each dye once per cell and fill. */
    public static String dyeInputs(String item, String mid, int[] counts, int fills) {
        StringBuilder sb = new StringBuilder("[ ").append(mid == null ? q(item, fills) : coat(item, mid, fills));
        for (int i = 0; i < counts.length; i++) if (counts[i] > 0) sb.append(", ").append(q(dyeId(i), counts[i] * fills));
        return sb.append(" ]").toString();
    }

    /** One dyeing step as the server writes it. */
    public static String step(String item, String result, String mid, int[] counts, int fills) {
        return "{ \"recipe\": \"qwerte:special/dyed\", \"result\": \"" + item + "\", \"spec\": \"" + result + "\", \"count\": 1, \"fills\": " + fills + ", \"grid\": "
            + grid(dyeGrid(item, mid, counts)) + ", \"inputs\": " + dyeInputs(item, mid, counts, fills) + " }";
    }

    public static JsonObject block(String lines, String steps, String take, String leftovers, int fills) {
        return block(R, lines, steps, take, leftovers, fills);
    }

    public static JsonObject block(Recipes table, String lines, String steps, String take, String leftovers, int fills) {
        return JsonParser.parseString("""
            { "v": 2, "mc": "1.21.4", "recipes": "%s", "extras": "%s", "maxDepth": 12, "needs": ["dyed"],
              "lines": %s, "steps": %s, "take": %s, "leftovers": %s, "fills": %d, "smelt": 0, "forEnchant": [] }
            """.formatted(table.hash(), SERVER_HASH, lines, steps, take, leftovers, fills)).getAsJsonObject();
    }

    /** {@code n} red chestplates whose base is crafted from 8 leather each by a table step first (the server writes the base's step before the dyeing). */
    public static JsonObject redFromLeather(int n) {
        String table = "{ \"recipe\": \"minecraft:leather_chestplate\", \"result\": \"" + CHEST + "\", \"count\": 1, \"fills\": " + n + ", \"inputs\": [ " + q("minecraft:leather", 8 * n) + " ] }";
        return block(LR, "[" + line(CHEST, RED, n) + "]", "[" + table + "," + step(CHEST, RED, null, mix("red"), n) + "]",
            "[ " + q("minecraft:leather", 8 * n) + ", " + q("minecraft:red_dye", n) + " ]", "[]", 2 * n);
    }

    public static String line(String item, String spec, int count) {
        return "{ \"key\": \"" + item + "\", \"count\": " + count + ", \"spec\": \"" + spec + "\", \"orCraft\": false }";
    }

    /** {@code want} red chestplates with {@code stock} in stock (the key {@link #K_RED1}): the rest are dyed with one red dye each on a plain chestplate. */
    public static JsonObject red(int stock, int want) {
        int made = want - stock;
        String take = (stock > 0 ? q(CHEST, K_RED1, RED, stock) + ", " : "") + q(CHEST, made) + ", " + q("minecraft:red_dye", made);
        return block("[" + line(CHEST, RED, want) + "]", "[" + step(CHEST, RED, null, mix("red"), made) + "]", "[ " + take + " ]", "[]", made);
    }

    /** {@code n} orange chestplates: a red and a yellow dye each (the yellow dye first: the contract's order). */
    public static JsonObject orange(int n) {
        return block("[" + line(CHEST, ORANGE, n) + "]", "[" + step(CHEST, ORANGE, null, mix("red", "yellow"), n) + "]",
            "[ " + q(CHEST, n) + ", " + q("minecraft:yellow_dye", n) + ", " + q("minecraft:red_dye", n) + " ]", "[]", n);
    }

    /** {@code n} pieces of a two-coat colour of {@code item}, written as the server does: for each chunk of at most 8 the first coat, then the second. */
    public static JsonObject twoCoat(String item, Two path, int n) {
        String mid = DyedSpecs.specOf(path.mid()), result = DyedSpecs.specOf(path.result());
        StringBuilder steps = new StringBuilder();
        int fills = 0;
        for (int left = n; left > 0; left -= 8) {
            int c = Math.min(8, left);
            if (steps.length() > 0) steps.append(",");
            steps.append(step(item, mid, null, path.first(), c)).append(",").append(step(item, result, mid, path.second(), c));
            fills += 2 * c;
        }
        StringBuilder take = new StringBuilder(q(item, n));
        for (int i = 0; i < 16; i++) if (path.first()[i] + path.second()[i] > 0) take.append(", ").append(q(dyeId(i), (path.first()[i] + path.second()[i]) * n));
        return block("[" + line(item, result, n) + "]", "[" + steps + "]", "[ " + take + " ]", "[]", fills);
    }

    public static Order.Craft parse(JsonObject block) {
        Order.Craft c = Order.parseCraft(block);
        if (c == null || c.malformed()) throw new AssertionError("the block can't be read: " + block);
        return c;
    }

    /** The check by a bot that does everything of B3, B4 and B5, with the extras above. */
    public static String check(JsonObject block) {
        return check(parse(block), CAN);
    }

    public static String check(Order.Craft c, Set<String> can) {
        return check(c, can, R);
    }

    public static String check(Order.Craft c, Set<String> can, Recipes table) {
        return CraftOrder.check(c, table, 2048, SERVER_HASH, can, null, 0, FIREWORKS, TABLES, DYED);
    }
}
