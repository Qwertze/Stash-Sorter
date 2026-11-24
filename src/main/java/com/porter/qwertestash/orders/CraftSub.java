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

    /**
     * The constants of a plan: the crafting levels below the item asked for, the rounds of estimates, and the calls of need() allowed.
     * {@link #V1} is what every craft block before 1.22.0 is planned with; a v2 block ({@code craft.v: 2}, CRAFT-CONTRACT-v1.2.md 7) uses its own
     * {@code maxDepth} (12, or 1 when the order doesn't craft ingredients), 12 rounds and 200,000 calls.
     */
    public record Limits(int maxDepth, int rounds, int maxNodes) {
        public static final Limits V1 = new Limits(MAX_DEPTH, 4, MAX_NODES);

        /** The limits of a v2 block that says {@code maxDepth}. */
        public static Limits v2(int maxDepth) {
            return new Limits(Math.max(1, Math.min(CraftOrder.V2_MAX_DEPTH, maxDepth)), 12, 200_000);
        }

        /** The limits the bot plans an order's craft block with. */
        public static Limits of(Order.@Nullable Craft craft) {
            return craft != null && craft.v() == 2 ? v2(craft.maxDepth()) : V1;
        }
    }
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
    private record Raw(Recipes.Recipe recipe, int fills, Map<String, Long> uses, @Nullable Map<String, Long> fuel) {
    }

    /** x was crafted using y. */
    private record Lock(String made, String from) {
    }

    private static final class Snap {
        final Map<String, Long> pool, surplus, taken, parked;
        final int steps;
        final Set<Lock> locks;

        Snap(Session s) {
            pool = new HashMap<>(s.pool);
            surplus = new HashMap<>(s.surplus);
            taken = new LinkedHashMap<>(s.taken);
            parked = new HashMap<>(s.parked);
            steps = s.steps.size();
            locks = new HashSet<>(s.locks);
        }
    }

    public static final class Session {
        final Recipes table;
        final Set<String> allowed;
        final Limits limits;
        final @Nullable Smelting smelting;
        /** The special steps of the block (B3: rockets, stars), as recipes by plan id: a rocket or star is a pseudo id (item id and spec, see {@link Pseudo}). */
        final Map<String, Recipes.Recipe> special;
        /** The block's copies of banners, maps and written books by the product's pseudo id (B4): their crafts are made by {@link #needCopy}, not by the recipe search. */
        final Map<String, VariantSpecs.CopyFamily> copies;
        /**
         * Carried items of a copy's product that the item's own load must not use as stock (the surplus of the target is dropped, see {@link #dropSurplus}) but
         * that may be held as the source of a copy: the banner that stayed in the grid, a map left over from the chunk before. Per pseudo id.
         */
        Map<String, Long> parked = new HashMap<>();
        /** The map copies of this plan make one map more than asked for: more of the same line follows, and its first craft needs a source map (B4). */
        public boolean keepCopySource;
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
            this(table, allowed, estPool, pool, surplus, Limits.V1);
        }

        /** As above, planned with the limits of a v1 or a v2 craft block. */
        public Session(Recipes table, @Nullable Set<String> allowed, Map<String, Long> estPool, Map<String, Long> pool, Map<String, Long> surplus, Limits limits) {
            this(table, allowed, estPool, pool, surplus, limits, null);
        }

        /**
         * As above, with the smelting of the extras (B2): the smelt recipes among {@code allowed} are used like any other recipe, and each smelt step
         * also burns fuel (of one kind per step, see {@link #fuelFor}).
         */
        public Session(Recipes table, @Nullable Set<String> allowed, Map<String, Long> estPool, Map<String, Long> pool, Map<String, Long> surplus, Limits limits,
                       @Nullable Smelting smelting) {
            this(table, allowed, estPool, pool, surplus, limits, smelting, Map.of());
        }

        /**
         * As above, with the block's special steps (B3): each is a recipe with the exact cells of its grid (spec'd cells and results are pseudo ids), listed
         * in {@code allowed} by {@link FireworkSpecs#planId} like any other step. The pool and the surplus may hold pseudo ids too.
         */
        public Session(Recipes table, @Nullable Set<String> allowed, Map<String, Long> estPool, Map<String, Long> pool, Map<String, Long> surplus, Limits limits,
                       @Nullable Smelting smelting, Map<String, Recipes.Recipe> special) {
            this(table, allowed, estPool, pool, surplus, limits, smelting, special, Map.of());
        }

        /**
         * As above, with the block's copies of banners, maps and written books (B4, {@link VariantSpecs#families}): a copy of one key is made by a craft that
         * holds its source (a banner or book that stays in the grid and is handed back, a map that is used up and made again by the craft) and takes one
         * blank item (a banner of the same colour, an empty map, a writable book) for each copy, at most 8 blanks to a craft.
         */
        public Session(Recipes table, @Nullable Set<String> allowed, Map<String, Long> estPool, Map<String, Long> pool, Map<String, Long> surplus, Limits limits,
                       @Nullable Smelting smelting, Map<String, Recipes.Recipe> special, Map<String, VariantSpecs.CopyFamily> copies) {
            this.limits = limits;
            this.special = special;
            this.copies = copies;
            this.table = table;
            this.allowed = allowed;
            this.smelting = smelting;
            this.pool = positive(pool);
            this.surplus = positive(surplus);
            // The recipes that make each item, among the ones allowed
            if (allowed != null) {
                for (String id : allowed) {
                    Recipes.Recipe r = recipeById(id);
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

        /** A recipe of the table, or a smelt recipe of the extras, by id. */
        Recipes.@Nullable Recipe recipeById(String id) {
            Recipes.Recipe r = table.byId(id);
            if (r == null && smelting != null) r = smelting.asRecipe(id);
            if (r == null) r = special.get(id);
            return r;
        }

        /** The recipe is a furnace recipe. */
        boolean smelts(Recipes.Recipe r) {
            return smelting != null && smelting.isSmelt(r);
        }

        List<Recipes.Recipe> recipesFor(String id) {
            if (allowed == null) {
                List<Recipes.Recipe> out = new ArrayList<>();
                for (Recipes.Recipe r : table.forResult(id)) if (eligible(r)) out.add(r);
                return out;
            }
            return making.getOrDefault(id, List.of());
        }

        /** est0 = 1 for an id the pool holds; {@code limits.rounds()} (4, or 12 for a v2 block) rounds of est(id) = min over recipes of (sum over cells of the cheapest alternative) / count. */
        private void estimate(Map<String, Long> estPool) {
            for (var e : estPool.entrySet()) if (e.getValue() != null && e.getValue() > 0) est.put(Recipes.ns(e.getKey()), 1.0);
            List<Recipes.Recipe> all = new ArrayList<>();
            if (allowed == null) {
                for (Recipes.Recipe r : table.all()) if (eligible(r)) all.add(r);
            }
            else for (String id : allowed) {
                Recipes.Recipe r = recipeById(id);
                if (r != null) all.add(r);
            }
            for (int round = 0; round < limits.rounds(); round++) {
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
            // A smelt needs some fuel the order holds or can make: with none it can't be made (the fuel's own cost is a few percent of an item's, left out)
            if (smelts(r)) {
                boolean fuel = false;
                for (String f : smelting.fuelIds()) if (!f.equals(r.result()) && !Double.isInfinite(estOf(f))) fuel = true;
                if (!fuel) return Double.POSITIVE_INFINITY;
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
            String ref = Recipes.ns(id);
            Long gone = surplus.remove(ref);
            // A banner or map the chunk before left carried is no stock of this load, but it may be the source of a copy (B4)
            VariantSpecs.CopyFamily f = copies.get(ref);
            if (gone != null && gone > 0 && f != null && f.kind() != VariantSpecs.CopyKind.BOOK) parked.merge(ref, gone, Long::sum);
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
            // Grid fills only: a furnace step's fills are items smelted (CraftPlanner.smeltItems)
            int fills = CraftPlanner.gridFills(merged);
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
            parked = s.parked;
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
            if (++nodes > limits.maxNodes()) return TOO_LARGE;
            VariantSpecs.CopyFamily copy = copies.get(id);
            if (copy != null) return needCopy(copy, qty, depth, path);
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

            if (depth >= limits.maxDepth()) return CraftPlanner.TOO_DEEP + " (" + Recipes.pretty(id) + ")";
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
                if (nodes > limits.maxNodes()) return TOO_LARGE;
            }
            return first;
        }

        /** Takes {@code n} of an id from the surplus and then the pool, into the plan's take. */
        private void takeStock(String id, long n) {
            long fromS = Math.min(inS(id), n);
            if (fromS > 0) surplus.merge(id, -fromS, Long::sum);
            long fromP = Math.min(inP(id), n - fromS);
            if (fromP > 0) {
                pool.merge(id, -fromP, Long::sum);
                taken.merge(id, fromP, Long::sum);
            }
        }

        /**
         * Makes {@code qty} of a copy's product available (B4, CRAFT-CONTRACT-v1.2.md 15 and its B4 clarifications): from the stock first. What the stock does
         * not cover is made by crafts that hold the copy's source:
         * <ul>
         *     <li>a banner copy: the source banner stays in the grid and is handed back. It is never delivered, so one unit of the stock is kept as the source
         *     (or the one carried from the chunk before is used), and the rest of the stock is delivered; one blank banner and one craft for each copy that is
         *     still wanted;</li>
         *     <li>a map copy: the source map is used up and made again by the craft (a craft with {@code k} blanks makes {@code k + 1} maps), so a craft is at most
         *     8 blanks and the next one takes its source from the maps in hand: all the stock is delivered, and a blank map is one more map;</li>
         *     <li>a book copy: only from an original ({@code written_book@src:<key>}, which stays in the grid and is handed back); the copies are not in any
         *     stock; at most 8 writable books and that many copies to a craft.</li>
         * </ul>
         * A source that is not in hand (no stock, nothing carried) can't be bought: it is the shortage ({@code not enough ...}).
         * @return null when it can, else why not
         */
        private @Nullable String needCopy(VariantSpecs.CopyFamily f, long qty, int depth, List<String> path) {
            String r = f.product();
            boolean book = f.kind() == VariantSpecs.CopyKind.BOOK, map = f.kind() == VariantSpecs.CopyKind.MAP;
            long avail = inS(r) + inP(r);
            boolean banner = f.kind() == VariantSpecs.CopyKind.BANNER;
            // The server reserves one banner of the key as the source for the whole order (CraftService.stock): copies and loads come from the rest, so a later load
            // still has its source. A carried source (kept from the load before) is the reserve already. A map's source is one of its stock: while another load
            // follows, the stock is not all delivered (see below: one map more is made to carry on with)
            long deliverable = banner && parked.getOrDefault(r, 0L) < 1 ? Math.max(0, avail - 1) : avail;
            if (map ? avail >= qty && !keepCopySource : deliverable >= qty) {
                takeStock(r, qty);
                return null;
            }
            // Maps carried over from the chunk before are maps like any: they are delivered when that covers it (nothing is made; the host takes them into the load)
            if (map && avail == 0 && parked.getOrDefault(r, 0L) >= qty) {
                parked.merge(r, -qty, Long::sum);
                return null;
            }
            if (depth >= limits.maxDepth()) return CraftPlanner.TOO_DEEP + " (" + Recipes.pretty(r) + ")";
            if (path.contains(r)) return "a recipe loop (" + Recipes.pretty(r) + ")";
            long deliver, crafts;
            boolean srcFromStock = false, srcFromParked = false;
            if (book) {
                // The copies come from the surplus and the pool (none today), and the original is held, not used
                deliver = avail;
                crafts = qty - deliver;
                if (inS(f.source()) + inP(f.source()) < 1) return "not enough " + Recipes.pretty(f.source()) + " (1 short)";
            }
            else if (map) {
                // All the stock (up to the load) goes to the load (one of it is used as the source, and the craft makes it again)
                if (avail >= 1) deliver = Math.min(avail, qty);
                else if (parked.getOrDefault(r, 0L) >= 1) {
                    // Only a map carried over from the chunk before: it is used up, and what the crafts make is the load (one more than their blanks)
                    srcFromParked = true;
                    deliver = 1;
                }
                else return "not enough " + Recipes.pretty(r) + " (1 short)";
                crafts = qty - deliver;
                if (crafts <= 0 && !keepCopySource) return "not enough " + Recipes.pretty(r) + " (" + (qty - avail) + " short)";
            }
            else {
                // A banner: the carried source is kept, else one unit of the stock; the rest is delivered
                if (parked.getOrDefault(r, 0L) >= 1) deliver = avail;
                else if (avail >= 1) {
                    deliver = avail - 1;
                    srcFromStock = true;
                }
                else return "not enough " + Recipes.pretty(r) + " (1 short)";
                crafts = qty - deliver;
            }
            // One blank for each copy (or each net map) still wanted, taken from the surplus and the pool or crafted
            long blanks = crafts + (map && keepCopySource ? 1 : 0);
            List<String> below = new ArrayList<>(path);
            below.add(r);
            Map<String, Long> uses = new LinkedHashMap<>();
            List<String> craftedAlts = new ArrayList<>();
            List<String> blank = neverUse(f.blank()) ? List.of() : List.of(f.blank());
            if (blank.isEmpty()) return "not enough " + Recipes.pretty(f.blank()) + " (" + blanks + " short)";
            String why = group(blank, blanks, r, depth, below, uses, craftedAlts);
            if (why != null) return why;
            // The stock is taken (the source with it, for a banner or book): what is handed back is carried again afterwards
            if (map) {
                takeStock(r, Math.min(deliver, avail));
                if (srcFromParked) parked.merge(r, -1L, Long::sum);
            }
            else if (book) {
                takeStock(r, deliver);
                takeStock(f.source(), 1);
            }
            else takeStock(r, deliver + (srcFromStock ? 1 : 0));
            // The crafts: banners one by one, maps and books in crafts of at most 8 blanks and one shorter
            long per = f.kind() == VariantSpecs.CopyKind.BANNER ? 1 : VariantSpecs.COPY_BATCH;
            long full = blanks / per, rest = blanks % per;
            if (full > 0) steps.add(new Raw(f.recipeFor((int) per), (int) full, Map.of(f.blank(), per * full), null));
            if (rest > 0) steps.add(new Raw(f.recipeFor((int) rest), 1, Map.of(f.blank(), rest), null));
            for (String alt : craftedAlts) locks.add(new Lock(r, alt));
            return null;
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
                String why = group(g.getKey(), (long) g.getValue() * fills, id, depth, below, uses, craftedAlts);
                if (why != null) return why;
            }
            // A smelt is one step per input kind and batch of at most 64 items (CRAFT-CONTRACT-v1.2.md, B2 review), each with the fuel of its own
            // (one kind, arithmetic per step); a table recipe is one step with no fuel
            if (smelts(r)) {
                for (var in : uses.entrySet()) {
                    long left = in.getValue();
                    while (left > 0) {
                        int n = (int) Math.min(left, Smelting.MAX_BATCH);
                        Map<String, Long> fuel = new LinkedHashMap<>();
                        String why = fuelFor(id, n, depth, below, fuel);
                        if (why != null) return why;
                        Map<String, Long> one = new LinkedHashMap<>();
                        one.put(in.getKey(), (long) n);
                        steps.add(new Raw(r, n, one, fuel));
                        left -= n;
                    }
                }
            }
            else steps.add(new Raw(r, fills, uses, null));
            long extra = (long) fills * r.count() - qty;
            if (extra > 0) surplus.merge(id, extra, Long::sum);
            for (String alt : craftedAlts) locks.add(new Lock(id, alt));
            return null;
        }

        /**
         * Gets {@code left} items of one group of alternatives for a recipe of {@code id}: from the surplus and the pool first (the most plentiful alternative
         * first), then crafted, alternative by alternative (cheapest first), each as much as it can. What was used is added to {@code uses}, what was crafted to
         * {@code craftedAlts}.
         * @return null when the group is covered, else why not
         */
        private @Nullable String group(List<String> groupAlts, long left, String id, int depth, List<String> below, Map<String, Long> uses, List<String> craftedAlts) {
            List<String> alts = new ArrayList<>(new java.util.LinkedHashSet<>(groupAlts));
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
            if (left <= 0) return null;
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
            return null;
        }

        /**
         * The fuel of a smelt of {@code fills} items of {@code id} (CRAFT-CONTRACT-v1.2.md 14): one kind, {@code ceil(batch * cook / ticks)} per batch of at
         * most 64 items. The kind is the most plentiful (surplus + pool) of the fuels the order holds or can make, then by id; one that has to be made
         * (charcoal from logs, an earlier step of the same plan) is tried after the ones in stock. Not a fuel that is the item itself, is on the path (a
         * loop) or that was made using this item. @return null when it is found (recorded in {@code out}), else why not
         */
        private @Nullable String fuelFor(String id, int fills, int depth, List<String> below, Map<String, Long> out) {
            List<String> kinds = new ArrayList<>();
            for (String f : smelting.fuelIds()) {
                if (f.equals(id) || below.contains(f) || locks.contains(new Lock(f, id))) continue;
                if (inS(f) + inP(f) <= 0 && Double.isInfinite(estOf(f))) continue;
                kinds.add(f);
            }
            // The coal class first, then wood and the like; of each, what covers the batch (surplus + pool) before what has to be made (charcoal from logs
            // last); the most plentiful first, then by id (CRAFT-CONTRACT-v1.2.md, B2 review)
            kinds.sort((a, b) -> {
                boolean ca = inS(a) + inP(a) >= smelting.batchFuel(a, fills), cb = inS(b) + inP(b) >= smelting.batchFuel(b, fills);
                if (ca != cb) return ca ? -1 : 1;
                if (ca) {
                    int t = Integer.compare(Smelting.fuelTier(a), Smelting.fuelTier(b));
                    if (t != 0) return t;
                }
                int c = Long.compare(inS(b) + inP(b), inS(a) + inP(a));
                return c != 0 ? c : a.compareTo(b);
            });
            String first = null;
            for (String f : kinds) {
                long k = smelting.batchFuel(f, fills);
                // A fuel whose need for one batch is more than its stack is not used (it would not fit the fuel slot)
                if (k <= 0 || k > smelting.fuelStack(f)) continue;
                boolean made = inS(f) + inP(f) < k;
                Snap before = new Snap(this);
                String why = need(f, k, depth + 1, below);
                if (why == null) {
                    out.merge(f, k, Long::sum);
                    if (made) locks.add(new Lock(id, f));
                    return null;
                }
                restore(before);
                if (first == null) first = why;
                if (nodes > limits.maxNodes()) return TOO_LARGE;
            }
            return first != null ? first : "no fuel to smelt " + Recipes.pretty(id) + " with";
        }

        /** The largest k at most {@code want} for which need(alt, k) succeeds, each try from the state as it is (found by a binary search). */
        private long largest(String alt, long want, int depth, List<String> path) {
            if (tries(alt, want, depth, path)) return want;
            long lo = 0, hi = want - 1;
            while (lo < hi) {
                long mid = (lo + hi + 1) >>> 1;
                if (tries(alt, mid, depth, path)) lo = mid;
                else hi = mid - 1;
                if (nodes > limits.maxNodes()) return 0;
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
        // Table steps are merged per recipe; furnace steps never are (CRAFT-CONTRACT-v1.2.md, B2 review): each is one batch of one input kind with its own fuel
        Map<String, Raw> byKey = new LinkedHashMap<>();
        int furnaceSteps = 0;
        for (Raw r : raws) {
            if (r.fuel() != null) {
                byKey.put(r.recipe().id() + "#" + furnaceSteps++, r);
                continue;
            }
            Raw old = byKey.get(r.recipe().id());
            if (old == null) byKey.put(r.recipe().id(), new Raw(r.recipe(), r.fills(), new LinkedHashMap<>(r.uses()), null));
            else {
                Map<String, Long> uses = new LinkedHashMap<>(old.uses());
                for (var e : r.uses().entrySet()) uses.merge(e.getKey(), e.getValue(), Long::sum);
                byKey.put(r.recipe().id(), new Raw(old.recipe(), old.fills() + r.fills(), uses, null));
            }
        }
        Map<String, Integer> height = new HashMap<>();
        Map<String, List<String>> makersOf = new HashMap<>();
        for (var e : byKey.entrySet()) makersOf.computeIfAbsent(e.getValue().recipe().result(), k -> new ArrayList<>()).add(e.getKey());
        for (int pass = 0; pass <= byKey.size(); pass++) {
            boolean changed = false;
            for (var e : byKey.entrySet()) {
                Raw r = e.getValue();
                int h = 0;
                List<String> inputs = new ArrayList<>(r.uses().keySet());
                if (r.fuel() != null) inputs.addAll(r.fuel().keySet());
                for (String in : inputs) {
                    for (String maker : makersOf.getOrDefault(in, List.of())) {
                        if (!maker.equals(e.getKey())) h = Math.max(h, height.getOrDefault(maker, 0) + 1);
                    }
                }
                if (h != height.getOrDefault(e.getKey(), -1)) {
                    height.put(e.getKey(), h);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        List<Map.Entry<String, Raw>> order = new ArrayList<>(byKey.entrySet());
        order.sort((a, b) -> Integer.compare(height.getOrDefault(a.getKey(), 0), height.getOrDefault(b.getKey(), 0)));
        List<CraftPlanner.Step> out = new ArrayList<>();
        for (var e : order) {
            Raw r = e.getValue();
            out.add(new CraftPlanner.Step(r.recipe(), r.fills(), height.getOrDefault(e.getKey(), 0), r.uses(), r.fuel()));
        }
        return out;
    }
}
