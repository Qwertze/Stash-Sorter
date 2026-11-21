package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.Extras;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The smelting data of the extras block (CRAFT-CONTRACT-v1.2.md 13 and 14) as the planner and the furnace trip use it: the smelt recipes (written by
 * hand on the server, ids like {@code qwerte:smelt/glass}), the fuels with their burn times, and the cook time of one item. Built from the extras the bot
 * validated; unusable (null from {@link #of}) when the extras lack the smelt or the fuel table, which is also when the bot does not say {@code smelt} in
 * its {@code can}. Plain data, safe to share between threads.
 * <p>
 * A smelt recipe is also given to the planner as a one-cell shapeless {@link Recipes.Recipe} ({@link #recipe}), so the sub-plan treats it like any other
 * recipe: its {@code fills} are items smelted. Its fuel is worked out by the planner ({@link CraftSub}) with {@link #batchFuel}.
 */
public final class Smelting {
    /** The most items one furnace load holds (one stack), and so the most one batch smelts (CRAFT-CONTRACT-v1.2.md 14). */
    public static final int MAX_BATCH = 64;
    /** The cook time the contract gives (13.3) when the block says none. */
    public static final int DEFAULT_COOK = 200;
    /** The most items one order smelts (CRAFT-CONTRACT-v1.2.md 14): {@code min(256, maxSmelt)}. */
    public static final int MAX_SMELT = 256;

    /** One smelt recipe: the item ids the furnace accepts in its input slot (alternatives of one cell), what comes out and how many per input item. */
    public record Recipe(String id, List<String> in, String out, int count) {
    }

    private final int cookTicks;
    private final Map<String, Recipe> recipes;
    private final Map<String, Integer> fuel;

    private Smelting(int cookTicks, Map<String, Recipe> recipes, Map<String, Integer> fuel) {
        this.cookTicks = cookTicks;
        this.recipes = recipes;
        this.fuel = fuel;
    }

    /** The smelting of these extras, or null when they have no smelt table or no fuel table (or none at all). */
    public static @Nullable Smelting of(@Nullable Extras extras) {
        return extras == null ? null : of(extras.json());
    }

    /** As above, from the normalised extras JSON. */
    public static @Nullable Smelting of(@Nullable JsonObject json) {
        if (json == null) return null;
        try {
            Map<String, Recipe> recipes = new LinkedHashMap<>();
            if (json.has("smelt") && json.get("smelt").isJsonArray()) {
                for (JsonElement e : json.getAsJsonArray("smelt")) {
                    JsonObject s = e.getAsJsonObject();
                    List<String> in = new ArrayList<>();
                    for (JsonElement i : s.getAsJsonArray("in")) in.add(i.getAsString());
                    Collections.sort(in);
                    recipes.put(s.get("id").getAsString(), new Recipe(s.get("id").getAsString(), Collections.unmodifiableList(in), s.get("out").getAsString(),
                        Math.max(1, s.get("count").getAsInt())));
                }
            }
            Map<String, Integer> fuel = new LinkedHashMap<>();
            if (json.has("fuel") && json.get("fuel").isJsonArray()) {
                for (JsonElement e : json.getAsJsonArray("fuel")) {
                    JsonObject f = e.getAsJsonObject();
                    long ticks = f.get("ticks").getAsLong();
                    if (ticks >= 1 && ticks <= Integer.MAX_VALUE) fuel.put(f.get("id").getAsString(), (int) ticks);
                }
            }
            if (recipes.isEmpty() || fuel.isEmpty()) return null;
            int cook = json.has("cookTicks") && json.get("cookTicks").isJsonPrimitive() ? (int) Math.max(1, Math.min(1_000_000, json.get("cookTicks").getAsLong())) : DEFAULT_COOK;
            return new Smelting(cook, Collections.unmodifiableMap(recipes), Collections.unmodifiableMap(fuel));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public int cookTicks() {
        return cookTicks;
    }

    public @Nullable Recipe byId(String id) {
        return recipes.get(id);
    }

    public java.util.Collection<Recipe> all() {
        return recipes.values();
    }

    public boolean isFuel(String id) {
        return fuel.containsKey(id);
    }

    /** Burn time of a fuel in ticks, 0 for an item that is not one. */
    public int ticks(String id) {
        return fuel.getOrDefault(id, 0);
    }

    public List<String> fuelIds() {
        return new ArrayList<>(fuel.keySet());
    }

    /** The recipe as the planner's one-cell shapeless recipe: its cell lists the furnace's input alternatives, it makes {@code out} ({@code count} per item). */
    public Recipes.@Nullable Recipe asRecipe(String id) {
        Recipe r = recipes.get(id);
        if (r == null) return null;
        List<@Nullable List<String>> cells = new ArrayList<>();
        cells.add(r.in());
        return new Recipes.Recipe(r.id(), false, 0, 0, Collections.unmodifiableList(cells), r.out(), r.count());
    }

    /** True when this planner recipe is one of the smelt recipes (the same object {@link #asRecipe} made, or one with its id). */
    public boolean isSmelt(Recipes.Recipe r) {
        return recipes.containsKey(r.id());
    }

    /**
     * Fuel of one kind that {@code items} smelted items need when they are loaded in batches of at most {@link #MAX_BATCH}: for each batch
     * {@code ceil(batch * cookTicks / ticks)} (CRAFT-CONTRACT-v1.2.md 14). 0 for an item that is no fuel or a count of 0.
     */
    public long batchFuel(String fuelId, long items) {
        int ticks = ticks(fuelId);
        if (ticks <= 0 || items <= 0) return 0;
        long full = items / MAX_BATCH, rest = items % MAX_BATCH, n = 0;
        if (full > 0) n += full * ceilDiv(MAX_BATCH * (long) cookTicks, ticks);
        if (rest > 0) n += ceilDiv(rest * cookTicks, ticks);
        return n;
    }

    /** The fuels of the coal class (CRAFT-CONTRACT-v1.2.md, B2 review): chosen before any wood. */
    public static final java.util.Set<String> COAL_CLASS = java.util.Set.of("minecraft:coal", "minecraft:charcoal", "minecraft:coal_block", "minecraft:dried_kelp_block");

    /** The tier a fuel is tried in: 0 the coal class, 1 anything else (wood, sticks). */
    public static int fuelTier(String id) {
        return COAL_CLASS.contains(id) ? 0 : 1;
    }

    /** The stack size of a fuel: 64, or 16 for the few that stack to 16 (signs, buckets). A batch needing more of one fuel than this is not made with it. */
    public int fuelStack(String id) {
        return id.endsWith("_sign") || id.endsWith("bucket") ? 16 : 64;
    }

    /** Burn ticks that smelting {@code items} needs in all. */
    public long ticksFor(long items) {
        return Math.max(0, items) * cookTicks;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }

    /** The smelt recipes and fuels as the order's block needs them (a lookup for tests and the check), as plain JSON-free maps. */
    public Map<String, Integer> fuels() {
        return fuel;
    }

    /** A JSON array of the smelt recipe ids, for logs. */
    public JsonArray recipeIds() {
        JsonArray a = new JsonArray();
        for (String id : recipes.keySet()) a.add(id);
        return a;
    }
}
