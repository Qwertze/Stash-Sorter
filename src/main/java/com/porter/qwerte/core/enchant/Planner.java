package com.porter.qwerte.core.enchant;

import com.porter.qwerte.core.enchant.PlanRequest.Stock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds the cheapest anvil order for one item (ENCHANT-FARM-plan.md section 2.3).
 *
 * <p>Pipeline: validate the order, pick sources (the base item and which books, including up to
 * {@link EnchantRules#MAX_COMBINE_DEPTH} levels of combining equal books), search all merge trees of each source set
 * exactly ({@link MergeSearch}), keep the best. Objective: fewest XP points, then fewer extras on the item (counted as
 * enchantments), then fewer levels, fewer steps and a lower final repair cost.
 *
 * <p>Work is bounded: identical stock entries count once, the number of distinct books and bases is capped, and the
 * whole call has a deterministic work budget ({@link Limits}). When a cap or the budget cuts the search short the plan
 * is flagged {@code truncated}; a refusal in that state says so.
 *
 * <p>Refusal codes: {@code nothing_wanted}, {@code unknown_enchant}, {@code invalid_level}, {@code duplicate},
 * {@code wrong_item}, {@code conflict}, {@code bad_request} (order validation), and {@code no_base},
 * {@code base_unusable}, {@code books_short}, {@code too_expensive}, {@code too_complex} (planning).
 */
public final class Planner {
    private Planner() {
    }

    public static final String NOTHING_WANTED = "nothing_wanted";
    public static final String UNKNOWN_ENCHANT = "unknown_enchant";
    public static final String INVALID_LEVEL = "invalid_level";
    public static final String DUPLICATE = "duplicate";
    public static final String WRONG_ITEM = "wrong_item";
    public static final String CONFLICT = "conflict";
    public static final String BAD_REQUEST = "bad_request";
    public static final String NO_BASE = "no_base";
    public static final String BASE_UNUSABLE = "base_unusable";
    public static final String BOOKS_SHORT = "books_short";
    public static final String TOO_EXPENSIVE = "too_expensive";
    public static final String TOO_COMPLEX = "too_complex";

    /** Bounds of one plan call. {@code maxMillis} 0 means no wall-clock limit (the work budget is deterministic). */
    public record Limits(long maxWork, long maxMillis, int maxBases, int maxStockEntries, int maxSets) {
        public static final Limits DEFAULT = new Limits(EnchantRules.MAX_WORK_UNITS, 0, EnchantRules.MAX_BASES_TRIED,
                EnchantRules.MAX_STOCK_ENTRIES, EnchantRules.MAX_SETS);
    }

    /**
     * Checks an order against the game rules: known enchantments, levels 1 to max, the item takes each one, no
     * duplicates, no conflicting pair, at most {@link EnchantRules#MAX_WANTED} enchantments. Returns the refusal, or
     * null when the order is valid.
     */
    public static Plan validate(McData d, String item, List<PlanRequest.Wanted> wanted) {
        String it = McData.norm(item);
        if (wanted == null || wanted.isEmpty()) {
            return Plan.refused(NOTHING_WANTED, "No enchantments were chosen", 0, false);
        }
        if (wanted.size() > EnchantRules.MAX_WANTED) {
            return Plan.refused(TOO_COMPLEX, "At most " + EnchantRules.MAX_WANTED + " enchantments can be ordered on one item", 0, false);
        }
        Set<String> seen = new HashSet<>();
        List<String> ids = new ArrayList<>();
        for (PlanRequest.Wanted w : wanted) {
            if (w == null || w.id() == null) {
                return Plan.refused(BAD_REQUEST, "An enchantment entry is missing", 0, false);
            }
            McData.Enchantment e = d.get(w.id());
            String id = McData.norm(w.id());
            if (e == null) {
                return Plan.refused(UNKNOWN_ENCHANT, "Unknown enchantment " + id, 0, false);
            }
            if (w.level() < 1 || w.level() > e.maxLevel()) {
                return Plan.refused(INVALID_LEVEL, Names.display(id) + " goes from I to " + Names.roman(e.maxLevel())
                        + " in Minecraft " + d.mc() + ", not " + w.level(), 0, false);
            }
            if (!seen.add(id)) {
                return Plan.refused(DUPLICATE, Names.display(id) + " was chosen twice", 0, false);
            }
            if (!d.supports(id, it)) {
                return Plan.refused(WRONG_ITEM, Names.display(id) + " cannot be put on " + it, 0, false);
            }
            ids.add(id);
        }
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                if (d.conflicts(ids.get(i), ids.get(j))) {
                    return Plan.refused(CONFLICT, Names.display(ids.get(i)) + " and " + Names.display(ids.get(j))
                            + " cannot be on the same item", 0, false);
                }
            }
        }
        return null;
    }

    /** Plans with the default limits. */
    public static Plan plan(McData d, PlanRequest req) {
        return plan(d, req, Limits.DEFAULT);
    }

    /**
     * Plans the cheapest order, or returns a refusal with a plain reason. Never returns null and never throws.
     *
     * <p>Curses: the plan is the best one that carries no curse nobody ordered. Only when none exists is the best plan that
     * does carry one returned, with {@link Plan#cursed()} listing the curse ids. That flag is not a refusal: the server asks
     * the orderer first. Both passes share one work budget and one deadline.
     */
    public static Plan plan(McData d, PlanRequest req, Limits limits) {
        try {
            Budget budget = new Budget(limits.maxWork(), limits.maxMillis());
            Pass strict = planPass(d, req, limits, true, budget);
            if (strict.plan.ok() || strict.curseSkipped == 0 || budget.exhausted()) {
                return strict.plan.withWorkUnits(budget.used());
            }
            Pass loose = planPass(d, req, limits, false, budget);
            if (!loose.plan.ok()) {
                return strict.plan.withWorkUnits(budget.used()); // the curse-free reason is the one worth showing
            }
            Plan p = loose.plan;
            Set<String> wantedIds = new HashSet<>();
            for (PlanRequest.Wanted w : req.wanted()) {
                wantedIds.add(McData.norm(w.id()));
            }
            List<String> cursed = new ArrayList<>();
            for (String id : p.levels().keySet()) {
                if (d.isCurse(id) && !wantedIds.contains(id)) {
                    cursed.add(id);
                }
            }
            return new Plan(true, null, null, p.materials(), p.steps(), p.levels(), p.extras(), p.totalLevels(), p.points(),
                    p.bottles(), p.finalRepairCost(), p.setsTried(), p.truncated(), cursed, p.timedOut(), budget.used());
        } catch (RuntimeException e) {
            return Plan.refused(BAD_REQUEST, "The request could not be planned", 0, false);
        }
    }

    private static final class Pass {
        final Plan plan;
        final int curseSkipped;

        Pass(Plan plan, int curseSkipped) {
            this.plan = plan;
            this.curseSkipped = curseSkipped;
        }
    }

    private static Pass planPass(McData d, PlanRequest req, Limits limits, boolean strictCurses, Budget budget) {
        int[] skipped = {0};
        Plan p = planInner(d, req, limits, strictCurses, skipped, budget);
        return new Pass(p, skipped[0]);
    }

    private static Plan planInner(McData d, PlanRequest req, Limits limits, boolean strictCurses, int[] curseSkipped,
                                  Budget budget) {
        if (d == null || req == null || req.item() == null) {
            return Plan.refused(BAD_REQUEST, "The request is incomplete", 0, false);
        }
        String item = McData.norm(req.item());
        Plan bad = validate(d, item, req.wanted());
        if (bad != null) {
            return bad;
        }
        List<Stock> rawBases = req.bases() == null ? List.of() : req.bases();
        List<Stock> rawBooks = req.books() == null ? List.of() : req.books();
        if (rawBases.size() > EnchantRules.MAX_STOCK_LIST_SIZE || rawBooks.size() > EnchantRules.MAX_STOCK_LIST_SIZE) {
            return Plan.refused(TOO_COMPLEX, "The stash holds too many different items to plan an order from", 0, false);
        }
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (PlanRequest.Wanted w : req.wanted()) {
            wanted.put(McData.norm(w.id()), w.level());
        }
        boolean truncated = false;

        // Bases: identical ones count once; unusable ones are skipped; the most promising few are tried.
        Grouped bases = group(rawBases, false, d, item, wanted, strictCurses, budget);
        if (bases.usable > EnchantRules.MAX_RAW_BASE_ENTRIES) {
            return Plan.refused(TOO_COMPLEX, "The stash holds too many different items to plan an order from", 0, false);
        }
        if (bases.anyCandidate == 0) {
            return Plan.refused(NO_BASE, "There is no " + item + " in stock to enchant", 0, false);
        }
        if (bases.entries.isEmpty()) {
            return Plan.refused(BASE_UNUSABLE, "Every " + item + " in stock carries enchantments that were not ordered", 0, false);
        }
        bases.entries.sort(Comparator.<Stock>comparingInt(s -> unsatisfied(s.stack(), wanted))
                .thenComparingInt(s -> s.stack().repairCost()));
        List<Stock> baseEntries = new ArrayList<>(bases.entries);
        List<Integer> baseOrig = new ArrayList<>();
        for (Stock s : baseEntries) {
            baseOrig.add(bases.origOf.get(s));
        }
        if (baseEntries.size() > limits.maxBases()) {
            baseEntries = new ArrayList<>(baseEntries.subList(0, limits.maxBases()));
            truncated = true;
        }

        // Books: only usable ones that carry an ordered enchantment, identical ones count once, the cheapest kept.
        Grouped bookGroup = group(rawBooks, true, d, item, wanted, strictCurses, budget);
        curseSkipped[0] = bookGroup.curseSkipped;
        if (bookGroup.usable > EnchantRules.MAX_RAW_BOOK_ENTRIES) {
            return Plan.refused(TOO_COMPLEX, "The stash holds too many different items to plan an order from", 0, false);
        }
        List<Stock> books = new ArrayList<>(bookGroup.entries);
        books.sort(Comparator.<Stock>comparingLong(s -> bookValue(d, s.stack())).thenComparingInt(s -> bookGroup.origOf.get(s)));
        if (books.size() > limits.maxStockEntries()) {
            books = new ArrayList<>(books.subList(0, limits.maxStockEntries()));
            truncated = true;
        }
        List<Integer> bookOrig = new ArrayList<>();
        for (Stock s : books) {
            bookOrig.add(bookGroup.origOf.get(s));
        }
        Sources src = new Sources(d, books, wanted, budget);

        Candidate best = null;
        int tried = 0;
        int capFailed = 0;
        int otherFailed = 0;
        int skippedBig = 0;
        boolean enumCut = false;
        String missing = null;
        boolean tooComplex = false;
        for (int bi = 0; bi < baseEntries.size() && !budget.exhausted(); bi++) {
            Stock bs = baseEntries.get(bi);
            Sources.Result sets = src.sets(bs.stack(), limits.maxSets());
            truncated |= sets.truncated;
            enumCut |= sets.truncated;
            if (sets.sets.isEmpty()) {
                if (missing == null) {
                    missing = sets.missing;
                }
                continue;
            }
            int n = Math.min(sets.sets.size(), limits.maxSets());
            truncated |= sets.sets.size() > limits.maxSets();
            for (int si = 0; si < n; si++) {
                List<Integer> set = sets.sets.get(si);
                List<MergeSearch.Leaf> leaves = new ArrayList<>();
                leaves.add(new MergeSearch.Leaf(true, bs.stack().enchants(), bs.stack().repairCost()));
                for (int b : set) {
                    Stack st = books.get(b).stack();
                    leaves.add(new MergeSearch.Leaf(false, st.enchants(), st.repairCost()));
                }
                MergeSearch.Outcome out;
                try {
                    out = MergeSearch.run(d, item, leaves, wanted, budget.slice(EnchantRules.MAX_WORK_PER_SET, EnchantRules.MAX_NODES_PER_SET));
                } catch (IllegalArgumentException e) {
                    tooComplex = true;
                    continue;
                }
                if (out.aborted) {
                    if (budget.exhausted()) {
                        break;
                    }
                    truncated = true; // this one set was too big: skip it and go on with the next
                    skippedBig++;
                    continue;
                }
                tried++;
                MergeSearch.Node node = out.best();
                if (node == null) {
                    if (out.sawCap) {
                        capFailed++;
                    } else {
                        otherFailed++;
                    }
                    continue;
                }
                Candidate c = new Candidate(out, node, bi, set);
                if (best == null || c.betterThan(best)) {
                    best = c;
                }
            }
        }
        truncated |= budget.exhausted() || src.capped;
        boolean timedOut = budget.timedOut();
        if (best == null) {
            String suffix = truncated ? " (the search was cut short because there are very many books or options)" : "";
            if (budget.exhausted() || skippedBig > 0 || enumCut && tried == 0) {
                return Plan.refused(TOO_COMPLEX, "The books in stock make this order too large to plan; try fewer enchantments or fewer books",
                        tried, true, timedOut);
            }
            if (tried > 0 && otherFailed == 0 && capFailed > 0) {
                return Plan.refused(TOO_EXPENSIVE, "Every order needs a step over " + EnchantRules.MAX_STEP_COST
                        + " levels (Too Expensive)" + suffix, tried, truncated);
            }
            if (tried > 0 && capFailed > 0) {
                return Plan.refused(BOOKS_SHORT, "No order of the books in stock gives exactly the ordered levels with every step at most "
                        + EnchantRules.MAX_STEP_COST + " levels" + suffix, tried, truncated);
            }
            if (tooComplex && tried == 0) {
                return Plan.refused(TOO_COMPLEX, "The books in stock carry too many different enchantments to plan", tried, truncated);
            }
            String why = tried > 0 ? "No order of the books in stock gives exactly the ordered levels"
                    : missing != null ? missing : "The books in stock cannot make the ordered levels";
            return Plan.refused(BOOKS_SHORT, why + suffix, tried, truncated);
        }
        return build(req, best, baseEntries, books, baseOrig, bookOrig, tried, truncated, timedOut);
    }

    private static int unsatisfied(Stack base, Map<String, Integer> wanted) {
        int n = 0;
        for (Map.Entry<String, Integer> w : wanted.entrySet()) {
            if (base.enchants().getOrDefault(w.getKey(), 0) < w.getValue()) {
                n++;
            }
        }
        return n;
    }

    /** Stock entries after dropping unusable ones and merging identical (enchantments, repair cost) entries. */
    private static final class Grouped {
        final List<Stock> entries = new ArrayList<>();
        final Map<Stock, Integer> origOf = new java.util.IdentityHashMap<>();
        int anyCandidate;
        int curseSkipped;
        int usable; // usable entries before identical ones are merged
    }

    private static Grouped group(List<Stock> raw, boolean books, McData d, String item, Map<String, Integer> wanted, boolean strict,
                                Budget budget) {
        Grouped g = new Grouped();
        Map<String, Integer> pos = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Stock> first = new LinkedHashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            Stock s = raw.get(i);
            if (s == null || s.stack() == null || s.count() <= 0 || s.stack().book() != books) {
                continue;
            }
            if (!budget.spend(2 + s.stack().enchants().size())) {
                break; // pre-processing is charged to the budget; the caller sees it exhausted
            }
            g.anyCandidate++;
            boolean ok = books ? usableBook(d, item, s.stack(), wanted, strict) : usableBase(d, s.stack(), wanted);
            if (!ok) {
                if (books && strict && usableBook(d, item, s.stack(), wanted, false)) {
                    g.curseSkipped++;
                }
                continue;
            }
            g.usable++;
            String key = s.stack().enchants() + "#" + s.stack().repairCost();
            counts.merge(key, (long) s.count(), Long::sum);
            if (!first.containsKey(key)) {
                first.put(key, s);
                pos.put(key, i);
            }
        }
        for (Map.Entry<String, Stock> e : first.entrySet()) {
            Stock merged = new Stock(e.getValue().stack(), (int) Math.min(counts.get(e.getKey()), 1_000_000L));
            g.entries.add(merged);
            g.origOf.put(merged, pos.get(e.getKey()));
        }
        return g;
    }

    private static long bookValue(McData d, Stack st) {
        long v = EnchantRules.priorWorkCost(st.repairCost(), 0);
        for (Map.Entry<String, Integer> e : st.enchants().entrySet()) {
            McData.Enchantment en = d.get(e.getKey());
            v += (long) en.bookMult() * e.getValue();
        }
        return v;
    }

    private static boolean usableBase(McData d, Stack base, Map<String, Integer> wanted) {
        for (Map.Entry<String, Integer> e : base.enchants().entrySet()) {
            McData.Enchantment en = d.get(e.getKey());
            Integer w = wanted.get(e.getKey());
            if (en == null || e.getValue() == null || e.getValue() < 1 || e.getValue() > en.maxLevel()) {
                return false;
            }
            if (w == null || e.getValue() > w) {
                return false; // bases must not carry unwanted enchantments
            }
        }
        return true;
    }

    /**
     * A book is usable when its enchantments are known, levels run 1..max, it has no conflicting pair, no ordered
     * enchantment is above its ordered level, it carries at least one ordered enchantment, and (when {@code strict})
     * it carries no curse that was not ordered and that the item would take (a curse the item cannot take is dropped by
     * the anvil, so it is harmless).
     */
    private static boolean usableBook(McData d, String item, Stack book, Map<String, Integer> wanted, boolean strict) {
        if (book.enchants().isEmpty()) {
            return false;
        }
        boolean relevant = false;
        List<String> ids = new ArrayList<>(book.enchants().keySet());
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            McData.Enchantment e = d.get(id);
            Integer lv = book.enchants().get(id);
            if (e == null || lv == null || lv < 1 || lv > e.maxLevel()) {
                return false;
            }
            Integer w = wanted.get(id);
            if (w != null && lv > w) {
                return false;
            }
            if (w != null) {
                relevant = true;
            }
            if (strict && w == null && d.isCurse(id) && d.supports(id, item)) {
                return false;
            }
            for (int j = i + 1; j < ids.size(); j++) {
                if (d.conflicts(id, ids.get(j))) {
                    return false;
                }
            }
        }
        return relevant;
    }

    private static final class Candidate {
        final MergeSearch.Outcome out;
        final MergeSearch.Node node;
        final int baseIndex;
        final List<Integer> books;

        Candidate(MergeSearch.Outcome out, MergeSearch.Node node, int baseIndex, List<Integer> books) {
            this.out = out;
            this.node = node;
            this.baseIndex = baseIndex;
            this.books = books;
        }

        boolean betterThan(Candidate o) {
            if (node.points != o.node.points) {
                return node.points < o.node.points;
            }
            int a = out.extras(node.ench);
            int b = o.out.extras(o.node.ench);
            if (a != b) {
                return a < b;
            }
            if (node.levels != o.node.levels) {
                return node.levels < o.node.levels;
            }
            if (node.steps != o.node.steps) {
                return node.steps < o.node.steps;
            }
            return node.rc < o.node.rc;
        }
    }

    private static Plan build(PlanRequest req, Candidate c, List<Stock> bases, List<Stock> books, List<Integer> baseOrig,
                              List<Integer> bookOrig, int tried, boolean truncated, boolean timedOut) {
        List<Plan.Material> mats = new ArrayList<>();
        Stock bs = bases.get(c.baseIndex);
        mats.add(new Plan.Material("base", bs.stack().enchants(), bs.stack().repairCost(), 1, baseOrig.get(c.baseIndex)));
        for (int b : c.books) {
            Stack st = books.get(b).stack();
            mats.add(new Plan.Material("book", st.enchants(), st.repairCost(), 1, bookOrig.get(b)));
        }
        List<Plan.Step> steps = new ArrayList<>();
        emit(c.node, mats.size(), steps);
        Map<String, Integer> levels = c.out.decode(c.node.ench);
        Map<String, Integer> extras = new LinkedHashMap<>();
        Set<String> wantedIds = new HashSet<>();
        for (PlanRequest.Wanted w : req.wanted()) {
            wantedIds.add(McData.norm(w.id()));
        }
        for (Map.Entry<String, Integer> e : levels.entrySet()) {
            if (!wantedIds.contains(e.getKey())) {
                extras.put(e.getKey(), e.getValue());
            }
        }
        return new Plan(true, null, null, mats, steps, levels, extras, c.node.levels, c.node.points,
                EnchantRules.bottles(c.node.points), c.node.rc, tried, truncated, List.of(), timedOut, 0);
    }

    /** Post-order: returns the item number of the node. Leaf i is material i (leaf order = material order). */
    private static int emit(MergeSearch.Node n, int materialCount, List<Plan.Step> steps) {
        if (n.leaf >= 0) {
            return n.leaf;
        }
        int l = emit(n.left, materialCount, steps);
        int r = emit(n.right, materialCount, steps);
        steps.add(new Plan.Step(l, r, n.cost, n.rc, EnchantRules.points(n.cost)));
        return materialCount + steps.size() - 1;
    }

    /**
     * Replays a plan through {@link Anvil} and returns the problems found (empty when it is sound): every step needs a
     * single item on the left, must give a result, cost at most 39 and match the recorded cost and repair cost, and
     * the final enchantments must match {@code plan.levels()}.
     */
    public static List<String> verify(McData d, String item, Plan plan) {
        List<String> problems = new ArrayList<>();
        if (!plan.ok()) {
            problems.add("the plan was refused: " + plan.code());
            return problems;
        }
        List<Stack> items = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (Plan.Material m : plan.materials()) {
            items.add(new Stack(!"base".equals(m.kind()), m.enchants(), m.repairCost()));
            counts.add(m.count());
        }
        int mat = plan.materials().size();
        for (int i = 0; i < plan.steps().size(); i++) {
            Plan.Step s = plan.steps().get(i);
            if (s.left() < 0 || s.right() < 0 || s.left() >= items.size() || s.right() >= items.size()) {
                problems.add("step " + i + " uses an item that does not exist yet");
                return problems;
            }
            int leftCount = counts.get(s.left());
            if (leftCount != 1) {
                problems.add("step " + i + " has " + leftCount + " items on the left (it must be exactly one)");
            }
            Anvil.Result r = Anvil.combine(d, item, items.get(s.left()), leftCount, items.get(s.right()));
            if (r.result() == null) {
                problems.add("step " + i + " gives no result");
                return problems;
            }
            if (r.cost() != s.cost()) {
                problems.add("step " + i + " costs " + r.cost() + " but the plan says " + s.cost());
            }
            if (r.cost() > EnchantRules.MAX_STEP_COST) {
                problems.add("step " + i + " costs " + r.cost() + " (over " + EnchantRules.MAX_STEP_COST + ")");
            }
            if (r.result().repairCost() != s.repairCost()) {
                problems.add("step " + i + " leaves repair cost " + r.result().repairCost() + " but the plan says " + s.repairCost());
            }
            items.add(r.result());
            counts.add(1);
        }
        if (mat + plan.steps().size() != items.size()) {
            problems.add("internal numbering mismatch");
        }
        Stack fin = items.get(items.size() - 1);
        if (!fin.enchants().equals(new TreeMap<>(plan.levels()))) {
            problems.add("the finished item has " + fin.enchants() + " but the plan says " + plan.levels());
        }
        return problems;
    }

    /** Source choice: which books go with a base, cheapest first. Bounded by the work budget and by early feasibility pruning. */
    private static final class Sources {
        final McData d;
        final List<Stock> books;
        final Map<String, Integer> wanted;
        final Budget budget;
        final Map<String, List<List<Integer>>> memo = new java.util.HashMap<>();
        /** True when a list cap (parts or chain options) cut options off. */
        boolean capped;

        Sources(McData d, List<Stock> books, Map<String, Integer> wanted, Budget budget) {
            this.d = d;
            this.books = books;
            this.wanted = wanted;
            this.budget = budget;
        }

        static final class Result {
            final List<List<Integer>> sets = new ArrayList<>();
            boolean truncated;
            String missing;
        }

        private int level(int stockIdx, String id) {
            return books.get(stockIdx).stack().enchants().getOrDefault(id, 0);
        }

        private long value(List<Integer> set) {
            long v = 0;
            for (int b : set) {
                v += bookValue(d, books.get(b).stack());
            }
            return v;
        }

        /** Multisets of books that can make enchantment e at level k (a book at k, or two lower, to the depth limit). */
        List<List<Integer>> makeable(String e, int k, int depth) {
            String key = e + "/" + k + "/" + depth;
            List<List<Integer>> cached = memo.get(key);
            if (cached != null) {
                return cached;
            }
            List<List<Integer>> out = new ArrayList<>();
            for (int b = 0; b < books.size(); b++) {
                if (level(b, e) == k) {
                    out.add(List.of(b));
                }
            }
            if (depth > 0 && k > 1) {
                List<List<Integer>> sub = makeable(e, k - 1, depth - 1);
                int cap = Math.min(sub.size(), EnchantRules.MAX_COMBINE_PARTS);
                if (sub.size() > cap) {
                    capped = true;
                }
                for (int i = 0; i < cap; i++) {
                    for (int j = i; j < cap; j++) {
                        List<Integer> both = new ArrayList<>(sub.get(i));
                        both.addAll(sub.get(j));
                        out.add(both);
                    }
                }
            }
            out.sort(Comparator.<List<Integer>>comparingLong(this::value).thenComparingInt(List::size));
            memo.put(key, out);
            return out;
        }

        Result sets(Stack base, int maxSets) {
            Result res = new Result();
            List<String[]> unsat = new ArrayList<>(); // id, level, base level
            for (Map.Entry<String, Integer> w : wanted.entrySet()) {
                int b = base.enchants().getOrDefault(w.getKey(), 0);
                if (b < w.getValue()) {
                    unsat.add(new String[]{w.getKey(), String.valueOf(w.getValue()), String.valueOf(b)});
                }
            }
            List<List<List<Integer>>> options = new ArrayList<>();
            for (String[] u : unsat) {
                String e = u[0];
                int l = Integer.parseInt(u[1]);
                int b = Integer.parseInt(u[2]);
                List<List<Integer>> opt = new ArrayList<>(makeable(e, l, EnchantRules.MAX_COMBINE_DEPTH));
                if (b >= 1 && b < l) {
                    List<List<Integer>> chain = new ArrayList<>();
                    chain.add(new ArrayList<>());
                    for (int k = b; k < l && !chain.isEmpty(); k++) {
                        List<List<Integer>> next = new ArrayList<>();
                        outer:
                        for (List<Integer> prefix : chain) {
                            for (List<Integer> m : makeable(e, k, EnchantRules.MAX_COMBINE_DEPTH)) {
                                if (next.size() >= EnchantRules.MAX_CHAIN_OPTIONS) {
                                    capped = true;
                                    break outer;
                                }
                                List<Integer> c = new ArrayList<>(prefix);
                                c.addAll(m);
                                next.add(c);
                            }
                        }
                        chain = next;
                    }
                    opt.addAll(chain);
                }
                if (opt.isEmpty()) {
                    res.missing = missingText(e, l);
                    return res;
                }
                options.add(opt);
            }
            for (List<List<Integer>> o : options) {
                o.sort(Comparator.<List<Integer>>comparingLong(this::value).thenComparingInt(List::size));
            }
            Set<List<Integer>> found = new LinkedHashSet<>();
            if (unsat.isEmpty()) {
                found.add(List.of());
            } else {
                bestFirst(unsat, options, maxSets, found, res);
            }
            if (found.isEmpty() && res.missing == null) {
                res.missing = "There are not enough books in stock for the ordered enchantments";
            }
            res.sets.addAll(found); // generated cheapest first
            return res;
        }

        /** One choice of an option per unsatisfied enchantment, with the summed value of those options. */
        private record Vec(int[] idx, long value) {
        }

        /**
         * Enumerates choice vectors in order of increasing summed value (k-best over the product of the option lists), so
         * the sets generated first are the cheapest by that lower-bound proxy and a cut keeps the best of them.
         */
        private void bestFirst(List<String[]> unsat, List<List<List<Integer>>> options, int maxSets, Set<List<Integer>> found,
                               Result res) {
            int n = unsat.size();
            java.util.PriorityQueue<Vec> pq = new java.util.PriorityQueue<>(Comparator.<Vec>comparingLong(Vec::value)
                    .thenComparing((x, y) -> java.util.Arrays.compare(x.idx(), y.idx())));
            Set<String> seen = new HashSet<>();
            int[] zero = new int[n];
            pq.add(new Vec(zero, vecValue(options, zero)));
            seen.add(java.util.Arrays.toString(zero));
            while (!pq.isEmpty()) {
                if (found.size() >= maxSets) {
                    res.truncated = true;
                    return;
                }
                if (!budget.spend(1 + n)) {
                    res.truncated = true;
                    return;
                }
                Vec v = pq.poll();
                emit(unsat, options, v.idx(), found);
                for (int u = 0; u < n; u++) {
                    if (v.idx()[u] + 1 < options.get(u).size()) {
                        int[] next = v.idx().clone();
                        next[u]++;
                        if (seen.size() >= EnchantRules.MAX_GENERATED_SETS) {
                            res.truncated = true; // the frontier is capped: the cheapest sets so far are kept
                        } else if (seen.add(java.util.Arrays.toString(next))) {
                            pq.add(new Vec(next, vecValue(options, next)));
                        }
                    }
                }
            }
        }

        private long vecValue(List<List<List<Integer>>> options, int[] idx) {
            long v = 0;
            for (int u = 0; u < idx.length; u++) {
                v += value(options.get(u).get(idx[u]));
            }
            return v;
        }

        /** Builds the book set of one choice vector (an enchantment already made by a chosen book needs no option); adds it when it fits the stock. */
        private void emit(List<String[]> unsat, List<List<List<Integer>>> options, int[] idx, Set<List<Integer>> found) {
            List<Integer> picks = new ArrayList<>();
            int[] used = new int[books.size()];
            for (int u = 0; u < unsat.size(); u++) {
                String e = unsat.get(u)[0];
                int l = Integer.parseInt(unsat.get(u)[1]);
                boolean covered = false;
                for (int p : picks) {
                    if (level(p, e) == l) {
                        covered = true;
                        break;
                    }
                }
                if (covered) {
                    continue;
                }
                for (int b : options.get(u).get(idx[u])) {
                    if (++used[b] > books.get(b).count()) {
                        return;
                    }
                    picks.add(b);
                }
                if (picks.size() + 1 > EnchantRules.MAX_LEAVES) {
                    return;
                }
            }
            Collections.sort(picks);
            found.add(picks);
        }

        private static String missingText(String e, int l) {
            if (l <= 1) {
                return Names.leveled(e, l) + " is missing and there is no book for it";
            }
            return Names.leveled(e, l) + " is missing and there are no two " + Names.leveled(e, l - 1) + " books";
        }
    }

}
