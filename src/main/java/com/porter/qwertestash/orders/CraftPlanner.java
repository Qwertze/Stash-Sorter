package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out how to craft an item from what the stash can give: which recipes, how many grid fills of each (deepest first,
 * the order they are made in) and what is taken from the stash. Pure: the stash comes in as a pool of counts by item id
 * (namespaced), already cut down to what crafting may use (see {@link CraftPool} and {@code SourceRules.craftSurvey}).
 * <ul>
 *     <li>At most {@link #MAX_DEPTH} crafting steps deep (shulker box, chest, planks) and {@link #MAX_NODES} items looked at;
 *     a recipe loop is refused.</li>
 *     <li>Needs are taken in bulk before they are rounded to whole grid fills: 2 chests are 16 planks are 4 logs.</li>
 *     <li>Of the items a cell accepts, those in stock are used first, the most plentiful first, then by id; what still lacks
 *     is crafted, from the alternative whose own inputs are most plentiful.</li>
 *     <li>A refusal always says why: shulker shells can't be crafted, an item needs a furnace, no crafting table is set,
 *     too deep, a cap is reached, not enough of something.</li>
 * </ul>
 */
public final class CraftPlanner {
    private CraftPlanner() {
    }

    public static final int MAX_DEPTH = 3;
    public static final int MAX_NODES = 256;
    public static final String NO_TABLE = "needs a crafting table (`stash craftingtable here`)";
    public static final String SHELLS = "shulker shells can't be crafted";
    public static final String TOO_DEEP = "too deep";

    /** One recipe made {@code fills} times; {@code uses} is what goes into the grid in all, by item id. */
    public record Step(Recipes.Recipe recipe, int fills, int depth, Map<String, Long> uses) {
        public long made() {
            return (long) fills * recipe.count();
        }
    }

    /**
     * The way to craft {@code count} of {@code item}, or why not ({@code refusal}).
     * @param steps     in the order they are made: what a step uses is made by the steps before it
     * @param fromStock what is taken from the stash, by item id
     * @param fills     grid fills in all
     */
    public record Plan(String item, int count, List<Step> steps, Map<String, Long> fromStock, int fills, @Nullable String refusal) {
        public boolean ok() {
            return refusal == null;
        }
    }

    /**
     * What may be done.
     * @param table        a crafting table is set (every step is made at the table)
     * @param maxFills     grid fills one task may make
     * @param leftForOrder how many more this order may craft
     * @param leftThisHour how many more may be crafted this hour
     * @param neverUse     item ids never taken from the stash
     */
    public record Limits(boolean table, int maxFills, int leftForOrder, int leftThisHour, Set<String> neverUse) {
        public static Limits open() {
            return new Limits(true, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Set.of());
        }
    }

    public static Plan plan(Recipes recipes, String item, int count, Map<String, Long> pool, Limits limits) {
        String target = Recipes.ns(item);
        if (count <= 0) return refuse(target, count, "nothing to craft");
        if (!limits.table()) return refuse(target, count, NO_TABLE);
        if (count > limits.leftForOrder()) return refuse(target, count, "cap reached (" + Math.max(0, limits.leftForOrder()) + " more this order)");
        if (count > limits.leftThisHour()) return refuse(target, count, "cap reached (" + Math.max(0, limits.leftThisHour()) + " more this hour)");

        State s = new State(recipes, pool, limits.neverUse());
        String why = s.need(target, count, 0, new ArrayList<>());
        if (why != null) return refuse(target, count, why);
        int fills = 0;
        for (Step st : s.steps) fills += st.fills();
        if (fills > limits.maxFills()) return refuse(target, count, "cap reached (" + fills + " grid fills, at most " + limits.maxFills() + " a task)");
        return new Plan(target, count, Collections.unmodifiableList(s.steps), s.fromStock, fills, null);
    }

    private static Plan refuse(String item, int count, String why) {
        return new Plan(item, count, List.of(), Map.of(), 0, why);
    }

    /** The most of {@code item} (up to {@code upTo}) that can be crafted from the pool within the limits. */
    public static int maxCraftable(Recipes recipes, String item, int upTo, Map<String, Long> pool, Limits limits) {
        int hi = Math.min(upTo, Math.min(limits.leftForOrder(), limits.leftThisHour()));
        if (hi <= 0 || !limits.table()) return 0;
        if (plan(recipes, item, hi, pool, limits).ok()) return hi;
        int lo = 0;
        // Largest n with a plan: more needs more of everything, so a plan for n means one for every smaller n
        while (lo < hi - 1) {
            int mid = (lo + hi) >>> 1;
            if (plan(recipes, item, mid, pool, limits).ok()) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** "2 Shulker Box from 4 Shulker Shell + 2 Chest; 2 Chest from 16 Oak Planks; 16 Oak Planks from 4 Oak Log". */
    public static String describe(Plan p) {
        if (!p.ok()) return p.count() + " " + Recipes.pretty(p.item()) + ": " + p.refusal();
        List<String> parts = new ArrayList<>();
        for (int i = p.steps().size() - 1; i >= 0; i--) {
            Step st = p.steps().get(i);
            List<String> uses = new ArrayList<>();
            for (var e : st.uses().entrySet()) uses.add(e.getValue() + " " + Recipes.pretty(e.getKey()));
            parts.add(st.made() + " " + Recipes.pretty(st.recipe().result()) + " from " + String.join(" + ", uses));
        }
        return String.join("; ", parts);
    }

    /** "4 Shulker Shell, 4 Oak Log": what the plan takes from the stash. */
    public static String stockLine(Plan p) {
        List<String> parts = new ArrayList<>();
        for (var e : p.fromStock().entrySet()) parts.add(e.getValue() + " " + Recipes.pretty(e.getKey()));
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    private static final class State {
        final Recipes recipes;
        final Set<String> neverUse;
        Map<String, Long> pool, crafted = new HashMap<>(), fromStock = new LinkedHashMap<>();
        List<Step> steps = new ArrayList<>();
        int nodes;

        State(Recipes recipes, Map<String, Long> pool, Set<String> neverUse) {
            this.recipes = recipes;
            this.pool = new HashMap<>();
            for (var e : pool.entrySet()) if (e.getValue() != null && e.getValue() > 0) this.pool.merge(Recipes.ns(e.getKey()), e.getValue(), Long::sum);
            java.util.Set<String> never = new java.util.HashSet<>();
            for (String n : neverUse) never.add(Recipes.ns(n));
            this.neverUse = never;
        }

        long available(String id) {
            return crafted.getOrDefault(id, 0L) + (neverUse.contains(id) ? 0 : pool.getOrDefault(id, 0L));
        }

        record Snap(Map<String, Long> pool, Map<String, Long> crafted, Map<String, Long> fromStock, int steps) {
        }

        Snap snap() {
            return new Snap(new HashMap<>(pool), new HashMap<>(crafted), new LinkedHashMap<>(fromStock), steps.size());
        }

        void restore(Snap s) {
            pool = s.pool();
            crafted = s.crafted();
            fromStock = s.fromStock();
            while (steps.size() > s.steps()) steps.remove(steps.size() - 1);
        }

        /** Makes {@code qty} of {@code id} available: from what was crafted already, from stock, else crafted. Null when it can. */
        @Nullable String need(String id, long qty, int depth, List<String> path) {
            if (++nodes > MAX_NODES) return "too many steps";
            long fromCrafted = Math.min(crafted.getOrDefault(id, 0L), qty);
            if (fromCrafted > 0) crafted.merge(id, -fromCrafted, Long::sum);
            qty -= fromCrafted;
            long fromPool = neverUse.contains(id) ? 0 : Math.min(pool.getOrDefault(id, 0L), qty);
            if (fromPool > 0) {
                pool.merge(id, -fromPool, Long::sum);
                fromStock.merge(id, fromPool, Long::sum);
            }
            qty -= fromPool;
            if (qty <= 0) return null;

            List<Recipes.Recipe> made = recipes.forResult(id);
            if (made.isEmpty()) return noRecipe(id, qty);
            if (depth >= MAX_DEPTH) return TOO_DEEP + " (" + Recipes.pretty(id) + ")";
            if (path.contains(id)) return "a recipe loop (" + Recipes.pretty(id) + ")";
            String first = null;
            for (Recipes.Recipe r : made) {
                Snap before = snap();
                String why = use(r, id, qty, depth, path);
                if (why == null) return null;
                restore(before);
                if (first == null) first = why;
            }
            return first;
        }

        String noRecipe(String id, long missing) {
            if (id.equals("minecraft:shulker_shell")) return SHELLS + " (" + missing + " short)";
            String station = recipes.station(id);
            if (station != null) {
                String where = switch (station) {
                    case "smelting" -> "a furnace";
                    case "blasting" -> "a blast furnace";
                    case "smoking" -> "a smoker";
                    case "campfire_cooking" -> "a campfire";
                    case "smithing" -> "a smithing table";
                    case "stonecutting" -> "a stonecutter";
                    case "transmute" -> "a dye recipe (not done yet)";
                    default -> "a special recipe";
                };
                return Recipes.pretty(id) + " needs " + where + " (" + missing + " short)";
            }
            return "not enough " + Recipes.pretty(id) + " (" + missing + " short)";
        }

        @Nullable String use(Recipes.Recipe r, String id, long qty, int depth, List<String> path) {
            int fills = (int) Math.min(Integer.MAX_VALUE, (qty + r.count() - 1) / r.count());
            // Cells that take the same items are one need
            Map<List<String>, Integer> groups = new LinkedHashMap<>();
            for (List<String> cell : r.ingredients()) groups.merge(cell, 1, Integer::sum);
            List<String> below = new ArrayList<>(path);
            below.add(id);
            Map<String, Long> uses = new LinkedHashMap<>();
            for (var g : groups.entrySet()) {
                long remaining = (long) g.getValue() * fills;
                List<String> alts = new ArrayList<>(new java.util.LinkedHashSet<>(g.getKey()));
                // What is in stock first, the most plentiful first, then by id
                alts.sort((a, b) -> {
                    int c = Long.compare(available(b), available(a));
                    return c != 0 ? c : a.compareTo(b);
                });
                for (String alt : alts) {
                    long take = Math.min(available(alt), remaining);
                    if (take <= 0) continue;
                    String why = need(alt, take, depth + 1, below);
                    if (why != null) return why;
                    uses.merge(alt, take, Long::sum);
                    remaining -= take;
                    if (remaining == 0) break;
                }
                if (remaining > 0) {
                    // Crafted: from the alternative whose own inputs are most plentiful
                    List<String> order = new ArrayList<>(alts);
                    Map<String, Long> feed = new HashMap<>();
                    for (String alt : order) {
                        long n = 0;
                        for (String in : recipes.inputsOf(alt, 1)) n += available(in);
                        feed.put(alt, n);
                    }
                    order.sort((a, b) -> {
                        int c = Long.compare(feed.get(b), feed.get(a));
                        return c != 0 ? c : a.compareTo(b);
                    });
                    String first = null;
                    boolean done = false;
                    for (String alt : order) {
                        Snap before = snap();
                        String why = need(alt, remaining, depth + 1, below);
                        if (why == null) {
                            uses.merge(alt, remaining, Long::sum);
                            done = true;
                            break;
                        }
                        restore(before);
                        if (first == null) first = why;
                        if (nodes > MAX_NODES) break;
                    }
                    if (!done) return order.size() > 1 ? "not enough " + label(order) + " (" + remaining + " short" + (first == null ? "" : ": " + first) + ")" : first;
                }
            }
            steps.add(new Step(r, fills, depth, uses));
            long surplus = (long) fills * r.count() - qty;
            if (surplus > 0) crafted.merge(id, surplus, Long::sum);
            return null;
        }
    }

    /** "planks" for every kind of planks, else the first item's name and "or another". */
    static String label(List<String> alts) {
        String common = null;
        for (String a : alts) {
            int at = a.lastIndexOf('_');
            String last = at < 0 ? a.substring(a.indexOf(':') + 1) : a.substring(at + 1);
            if (common == null) common = last;
            else if (!common.equals(last)) return Recipes.pretty(alts.get(0)) + " or another";
        }
        return common == null ? "items" : common;
    }
}
