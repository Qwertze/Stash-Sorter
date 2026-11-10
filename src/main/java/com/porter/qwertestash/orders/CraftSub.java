package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The local sub-plan of a craft order (CRAFT-CONTRACT.md "The craft planner" and "Bot execution" step 3): the server's planner
 * algorithm run on the bot, but only with the recipe ids in the order's {@code craft.steps}, from the budget left of the order's
 * {@code take} (the pool {@code P}) plus the crafted surplus the bot carries (the surplus {@code S}), at most {@link #MAX_DEPTH}
 * levels below the item asked for. A bin's craft loads are planned with it one after the other, so the bot never takes more of an id
 * than the order's {@code take} and never uses an alternative outside it.
 * <ul>
 *     <li>{@code need(id, q)}: from {@code S}, then {@code P}; what is left is crafted by the cheapest recipe (the fewest stash items
 *     per item made, by the estimates of the order's start; ties by recipe id) that works, the others tried from a snapshot.</li>
 *     <li>A recipe's cells with the same alternatives are one group of {@code n * fills} items: pass 1 takes what the pool and surplus
 *     hold (the most plentiful alternative first), pass 2 crafts the rest, alternative by alternative (cheapest first), each as much as
 *     it can (found by a binary search), so a group can be made from two kinds (oak and birch planks for a chest).</li>
 *     <li>A lock "x from y" (x was crafted using y) forbids crafting y using x for the rest of the plan: no recipe loops.</li>
 * </ul>
 * Pure. One {@code Session} holds the state across several {@link #plan} calls (the way the server plans an order's rows one after the
 * other); a failed call changes nothing.
 */
public final class CraftSub {
    private CraftSub() {
    }

    public static final int MAX_DEPTH = 4;
    /** Calls of need() one session may make: past it a plan fails with {@link #TOO_LARGE}. */
    public static final int MAX_NODES = 50_000;
    public static final String TOO_LARGE = "plan too large";
    private static final double EPS = 1e-9;

    /** Never used as an ingredient or made (CRAFT-CONTRACT.md "Eligibility"): shulker boxes and bundles. */
    static boolean neverUse(String id) {
        return id.endsWith("shulker_box") || id.equals("minecraft:bundle") || id.endsWith("_bundle");
    }

    /**
     * Whether the server's planner may use a recipe: not one that makes a shulker box or a bundle, not a recolouring ({@code minecraft:dye_*}),
     * and every cell keeps an alternative once the never-use ones and the recipe's own result are taken out (that drops the smithing
     * template copies). The order's {@code craft.steps} only name eligible recipes; the test of the vectors runs on the whole table.
     */
    static boolean eligible(Recipes.Recipe r) {
        if (neverUse(r.result()) || r.id().startsWith("minecraft:dye_")) return false;
        for (List<String> cell : r.ingredients()) if (usable(cell, r).isEmpty()) return false;
        return true;
    }

    /** The alternatives of a cell without the never-use ones and the recipe's own result. */
    static List<String> usable(List<String> cell, Recipes.Recipe r) {
        List<String> out = new ArrayList<>();
        for (String alt : cell) if (!neverUse(alt) && !alt.equals(r.result()) && !out.contains(alt)) out.add(alt);
        return out;
    }

    /**
     * What a call made.
     * @param plan    the steps (merged per recipe, ingredients before what they make), what was taken from the pool, and the fills;
     *                {@code refusal} is the reason when it could not be made
     * @param surplus the crafted surplus after this call (including this item's own), by id; unchanged when it failed
     */
    public record Outcome(CraftPlanner.Plan plan, Map<String, Long> surplus) {
        public boolean ok() {
            return plan.ok();
        }
    }

    /** One step as planned, before the steps are merged. */
    private record Raw(Recipes.Recipe recipe, int fills, Map<String, Long> uses) {
    }

    /** x was crafted using y. */
    private record Lock(String made, String from) {
    }

    private static final class Snap {
        final Map<String, Long> pool, surplus, taken;
        final int steps;
        final Set<Lock> locks;

        Snap(Session s) {
            pool = new HashMap<>(s.pool);
            surplus = new HashMap<>(s.surplus);
            taken = new LinkedHashMap<>(s.taken);
            steps = s.steps.size();
            locks = new HashSet<>(s.locks);
        }
    }

    public static final class Session {
        final Recipes table;
        final Set<String> allowed;
        Map<String, Long> pool, surplus, taken = new LinkedHashMap<>();
        final List<Raw> steps = new ArrayList<>();
        Set<Lock> locks = new HashSet<>();
        final Map<String, Double> est = new HashMap<>();
        final Map<String, List<Recipes.Recipe>> making = new HashMap<>();
        int nodes;

        /**
         * @param allowed  the recipe ids that may be used (the order's craft.steps); null: every recipe in the table
         * @param estPool  the pool the estimates are made from (the order's start: its take), by id
         * @param pool     what may be taken from the stash now (the budget left of take), by id
         * @param surplus  what the bot carries of crafted items not used yet, by id
         */
        public Session(Recipes table, @Nullable Set<String> allowed, Map<String, Long> estPool, Map<String, Long> pool, Map<String, Long> surplus) {
            this.table = table;
            this.allowed = allowed;
            this.pool = positive(pool);
            this.surplus = positive(surplus);
            // The recipes that make each item, among the ones allowed
            if (allowed != null) {
                for (String id : allowed) {
                    Recipes.Recipe r = table.byId(id);
                    if (r == null) continue;
                    making.computeIfAbsent(r.result(), k -> new ArrayList<>()).add(r);
                }
                for (List<Recipes.Recipe> l : making.values()) l.sort((a, b) -> a.id().compareTo(b.id()));
            }
            estimate(estPool);
        }

        private static Map<String, Long> positive(Map<String, Long> m) {
            Map<String, Long> out = new HashMap<>();
            for (var e : m.entrySet()) if (e.getValue() != null && e.getValue() > 0) out.merge(Recipes.ns(e.getKey()), e.getValue(), Long::sum);
            return out;
        }

        List<Recipes.Recipe> recipesFor(String id) {
            if (allowed == null) {
                List<Recipes.Recipe> out = new ArrayList<>();
                for (Recipes.Recipe r : table.forResult(id)) if (eligible(r)) out.add(r);
                return out;
            }
            return making.getOrDefault(id, List.of());
        }

        /** est0 = 1 for an id the pool holds; four rounds of est(id) = min over recipes of (sum over cells of the cheapest alternative) / count. */
        private void estimate(Map<String, Long> estPool) {
            for (var e : estPool.entrySet()) if (e.getValue() != null && e.getValue() > 0) est.put(Recipes.ns(e.getKey()), 1.0);
            List<Recipes.Recipe> all = new ArrayList<>();
            if (allowed == null) {
                for (Recipes.Recipe r : table.all()) if (eligible(r)) all.add(r);
            }
            else for (String id : allowed) {
                Recipes.Recipe r = table.byId(id);
                if (r != null) all.add(r);
            }
            for (int round = 0; round < 4; round++) {
                for (Recipes.Recipe r : all) {
                    double c = cost(r);
                    if (c < est.getOrDefault(r.result(), Double.POSITIVE_INFINITY)) est.put(r.result(), c);
                }
            }
        }

        double estOf(String id) {
            return est.getOrDefault(id, Double.POSITIVE_INFINITY);
        }

        double cost(Recipes.Recipe r) {
            double sum = 0;
            for (List<String> cell : r.ingredients()) {
                double best = Double.POSITIVE_INFINITY;
                for (String alt : usable(cell, r)) best = Math.min(best, estOf(alt));
                sum += best;
                if (Double.isInfinite(sum)) return Double.POSITIVE_INFINITY;
            }
            return sum / Math.max(1, r.count());
        }

        /**
         * The recipes for an item with a finite cost: by cost (equal within EPS: by id). With none of finite cost the item cannot be made from
         * what the order holds, but its recipes are still tried (by id) so the failure names what is really missing ("not enough White
         * Wool" for a bed, not "not enough White Bed").
         */
        List<Recipes.Recipe> ranked(String id) {
            List<Recipes.Recipe> out = new ArrayList<>();
            for (Recipes.Recipe r : recipesFor(id)) if (!Double.isInfinite(cost(r))) out.add(r);
            if (out.isEmpty()) {
                out.addAll(recipesFor(id));
                out.sort((a, b) -> a.id().compareTo(b.id()));
                return out;
            }
            out.sort((a, b) -> {
                double ca = cost(a), cb = cost(b);
                if (Math.abs(ca - cb) > EPS) return Double.compare(ca, cb);
                return a.id().compareTo(b.id());
            });
            return out;
        }

        private long inS(String id) {
            return surplus.getOrDefault(id, 0L);
        }

        private long inP(String id) {
            return pool.getOrDefault(id, 0L);
        }

        /** Forgets the carried surplus of one item (it is not used for a plan of the item itself). */
        public void dropSurplus(String id) {
            surplus.remove(Recipes.ns(id));
        }

        /** The carried surplus now. */
        public Map<String, Long> surplus() {
            return Collections.unmodifiableMap(surplus);
        }

        /** What is left of the pool now. */
        public Map<String, Long> pool() {
            return Collections.unmodifiableMap(pool);
        }

        /**
         * Plans {@code count} of {@code item}. On success the state keeps what was taken and made; on failure it is as it was.
         */
        public Outcome plan(String item, int count) {
            String target = Recipes.ns(item);
            Snap before = new Snap(this);
            int stepsBefore = steps.size();
            Map<String, Long> takenBefore = new LinkedHashMap<>(taken);
            taken = new LinkedHashMap<>();
            nodes = 0;
            String why = need(target, count, 0, new ArrayList<>());
            if (why != null) {
                restore(before);
                taken = takenBefore;
                return new Outcome(new CraftPlanner.Plan(target, count, List.of(), Map.of(), 0, why), new HashMap<>(surplus));
            }
            List<Raw> mine = new ArrayList<>(steps.subList(stepsBefore, steps.size()));
            Map<String, Long> fromPool = taken;
            taken = merged(takenBefore, fromPool);
            List<CraftPlanner.Step> merged = merge(mine);
            int fills = 0;
            for (CraftPlanner.Step s : merged) fills += s.fills();
            return new Outcome(new CraftPlanner.Plan(target, count, Collections.unmodifiableList(merged), fromPool, fills, null), new HashMap<>(surplus));
        }

        /**
         * As {@link #plan}, but when {@code count} can't be made the most that can: found by a binary search (each try from the
         * state as it was). Zero when not even one can be made; {@code reason} then says why the full count failed.
         */
        public Outcome planUpTo(String item, int count) {
            Outcome full = plan(item, count);
            if (full.ok() || count <= 1) return full;
            int lo = 0, hi = count - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                // Each try from the state as it is: a try that works is undone, only the final choice is kept
                Snap before = new Snap(this);
                boolean ok = plan(item, mid).ok();
                restore(before);
                if (ok) lo = mid;
                else hi = mid - 1;
            }
            if (lo == 0) return full;
            return plan(item, lo);
        }

        private void restore(Snap s) {
            pool = s.pool;
            surplus = s.surplus;
            taken = s.taken;
            locks = s.locks;
            while (steps.size() > s.steps) steps.remove(steps.size() - 1);
        }

        private static Map<String, Long> merged(Map<String, Long> a, Map<String, Long> b) {
            Map<String, Long> out = new LinkedHashMap<>(a);
            for (var e : b.entrySet()) out.merge(e.getKey(), e.getValue(), Long::sum);
            return out;
        }

        /** Makes {@code qty} of {@code id} available: null when it can, else the reason it can't. */
        @Nullable String need(String id, long qty, int depth, List<String> path) {
            if (++nodes > MAX_NODES) return TOO_LARGE;
            long fromS = Math.min(inS(id), qty);
            if (fromS > 0) surplus.merge(id, -fromS, Long::sum);
            qty -= fromS;
            long fromP = Math.min(inP(id), qty);
            if (fromP > 0) {
                pool.merge(id, -fromP, Long::sum);
                taken.merge(id, fromP, Long::sum);
            }
            qty -= fromP;
            if (qty <= 0) return null;

            if (depth >= CraftSub.MAX_DEPTH) return CraftPlanner.TOO_DEEP + " (" + Recipes.pretty(id) + ")";
            if (path.contains(id)) return "a recipe loop (" + Recipes.pretty(id) + ")";
            List<Recipes.Recipe> rs = ranked(id);
            if (rs.isEmpty()) return noRecipe(id, qty);
            String first = null;
            for (Recipes.Recipe r : rs) {
                Snap before = new Snap(this);
                String why = use(r, id, qty, depth, path);
                if (why == null) return null;
                restore(before);
                if (first == null) first = why;
                if (nodes > MAX_NODES) return TOO_LARGE;
            }
            return first;
        }

        private String noRecipe(String id, long missing) {
            String station = table.station(id);
            if (station != null) {
                String where = switch (station) {
                    case "smelting" -> "a furnace";
                    case "blasting" -> "a blast furnace";
                    case "smoking" -> "a smoker";
                    case "campfire_cooking" -> "a campfire";
                    case "smithing" -> "a smithing table";
                    case "stonecutting" -> "a stonecutter";
                    case "transmute" -> "a dye recipe";
                    default -> "a special recipe";
                };
                return Recipes.pretty(id) + " needs " + where + " (" + missing + " short)";
            }
            return "not enough " + Recipes.pretty(id) + " (" + missing + " short)";
        }

        @Nullable String use(Recipes.Recipe r, String id, long qty, int depth, List<String> path) {
            int fills = (int) Math.min(Integer.MAX_VALUE, (qty + r.count() - 1) / r.count());
            // Cells that take the same alternatives are one group, in first-appearance order
            Map<List<String>, Integer> groups = new LinkedHashMap<>();
            for (List<String> cell : r.ingredients()) groups.merge(usable(cell, r), 1, Integer::sum);
            List<String> below = new ArrayList<>(path);
            below.add(id);
            Map<String, Long> uses = new LinkedHashMap<>();
            List<String> craftedAlts = new ArrayList<>();
            for (var g : groups.entrySet()) {
                long left = (long) g.getValue() * fills;
                List<String> alts = new ArrayList<>(new java.util.LinkedHashSet<>(g.getKey()));
                // Pass 1, stock: the most surplus + pool first, then by id
                alts.sort((a, b) -> {
                    int c = Long.compare(inS(b) + inP(b), inS(a) + inP(a));
                    return c != 0 ? c : a.compareTo(b);
                });
                for (String alt : alts) {
                    if (left <= 0) break;
                    long k = Math.min(left, inS(alt) + inP(alt));
                    if (k <= 0) continue;
                    String why = need(alt, k, depth + 1, below);
                    if (why != null) return why;
                    uses.merge(alt, k, Long::sum);
                    left -= k;
                }
                if (left <= 0) continue;
                // Pass 2, craft: alternatives with a finite estimate, not locked against this item, cheapest first
                List<String> crafty = new ArrayList<>();
                for (String alt : alts) {
                    if (Double.isInfinite(estOf(alt))) continue;
                    if (locks.contains(new Lock(alt, id))) continue;
                    crafty.add(alt);
                }
                crafty.sort((a, b) -> {
                    double ea = estOf(a), eb = estOf(b);
                    if (Math.abs(ea - eb) > EPS) return Double.compare(ea, eb);
                    return a.compareTo(b);
                });
                String firstWhy = null;
                for (String alt : crafty) {
                    if (left <= 0) break;
                    long k = largest(alt, left, depth + 1, below);
                    // What could not be made of it says why (the first reason is the one given)
                    if (k < left && firstWhy == null) firstWhy = whyNot(alt, left, depth + 1, below);
                    if (k <= 0) continue;
                    String why = need(alt, k, depth + 1, below);
                    if (why != null) return why;
                    uses.merge(alt, k, Long::sum);
                    left -= k;
                    craftedAlts.add(alt);
                }
                if (left > 0) {
                    if (firstWhy != null) return firstWhy;
                    String name = alts.size() == 1 ? Recipes.pretty(alts.get(0)) : CraftPlanner.label(alts);
                    return "not enough " + name + " (" + left + " short)";
                }
            }
            steps.add(new Raw(r, fills, uses));
            long extra = (long) fills * r.count() - qty;
            if (extra > 0) surplus.merge(id, extra, Long::sum);
            for (String alt : craftedAlts) locks.add(new Lock(id, alt));
            return null;
        }

        /** The largest k at most {@code want} for which need(alt, k) succeeds, each try from the state as it is (found by a binary search). */
        private long largest(String alt, long want, int depth, List<String> path) {
            if (tries(alt, want, depth, path)) return want;
            long lo = 0, hi = want - 1;
            while (lo < hi) {
                long mid = (lo + hi + 1) >>> 1;
                if (tries(alt, mid, depth, path)) lo = mid;
                else hi = mid - 1;
                if (nodes > MAX_NODES) return 0;
            }
            return lo;
        }

        /** Why {@code k} of this item can't be made (from the state as it is; nothing changes). */
        private @Nullable String whyNot(String alt, long k, int depth, List<String> path) {
            Snap before = new Snap(this);
            String why = need(alt, k, depth, path);
            restore(before);
            return why;
        }

        private boolean tries(String alt, long k, int depth, List<String> path) {
            Snap before = new Snap(this);
            boolean ok = need(alt, k, depth, path) == null;
            restore(before);
            return ok;
        }
    }

    /**
     * The steps merged per recipe id (fills and uses added up), ingredients before what they make: by height (0 = every input from the
     * pool or surplus, else 1 + the highest height among the steps making its inputs), then by first appearance.
     */
    static List<CraftPlanner.Step> merge(List<Raw> raws) {
        Map<String, Raw> byRecipe = new LinkedHashMap<>();
        for (Raw r : raws) {
            Raw old = byRecipe.get(r.recipe().id());
            if (old == null) byRecipe.put(r.recipe().id(), new Raw(r.recipe(), r.fills(), new LinkedHashMap<>(r.uses())));
            else {
                Map<String, Long> uses = new LinkedHashMap<>(old.uses());
                for (var e : r.uses().entrySet()) uses.merge(e.getKey(), e.getValue(), Long::sum);
                byRecipe.put(r.recipe().id(), new Raw(old.recipe(), old.fills() + r.fills(), uses));
            }
        }
        Map<String, Integer> height = new HashMap<>();
        Map<String, String> makerOf = new HashMap<>();
        for (Raw r : byRecipe.values()) makerOf.put(r.recipe().result(), r.recipe().id());
        for (int pass = 0; pass <= byRecipe.size(); pass++) {
            boolean changed = false;
            for (Raw r : byRecipe.values()) {
                int h = 0;
                for (String in : r.uses().keySet()) {
                    String maker = makerOf.get(in);
                    if (maker != null && !maker.equals(r.recipe().id())) h = Math.max(h, height.getOrDefault(maker, 0) + 1);
                }
                if (h != height.getOrDefault(r.recipe().id(), -1)) {
                    height.put(r.recipe().id(), h);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        List<Raw> order = new ArrayList<>(byRecipe.values());
        order.sort((a, b) -> Integer.compare(height.getOrDefault(a.recipe().id(), 0), height.getOrDefault(b.recipe().id(), 0)));
        List<CraftPlanner.Step> out = new ArrayList<>();
        for (Raw r : order) out.add(new CraftPlanner.Step(r.recipe(), r.fills(), height.getOrDefault(r.recipe().id(), 0), r.uses()));
        return out;
    }
}
