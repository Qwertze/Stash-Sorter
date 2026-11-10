package com.porter.qwertestash.orders;

import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitNames;
import com.porter.qwertestash.stash.KitRouting;
import com.porter.qwertestash.stash.KitTemplate;
import com.porter.qwertestash.stash.KitTemplates;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Turns the kit lines of an order into units (KIT-CONTRACT v2, revision 4, "Kit orders: bot execution"). Pure data in, units out; the sorter makes the
 * scan and the survey from its saved containers.
 * <ol>
 *     <li>{@link #scan}: complete copies (anywhere pickable), incomplete copies (in the template's chain target), and which index keys can serve a slot;</li>
 *     <li>{@link #plan}: a Shulker unit per ready copy, a Bin unit per kit to build (base: the fullest incomplete copy that fits, else an empty shulker),
 *     its loads gathered by match key within {@code take} (exact slots: least damaged, then the lowest key; any-variant slots: plain first, then
 *     least damaged, then the lowest key), one stack of each kind first.</li>
 * </ol>
 * A kit whose loads can't all be had is still planned with what there is when it has two kinds ({@link Unit#kitShort}): it is never delivered, it is
 * stored as an incomplete copy. Any thread (pure).
 */
public final class KitPlanner {
    private KitPlanner() {
    }

    /** A copy of a template in the stash: the shulker, where it lies and how it matched. */
    public record Copy(KitTemplate template, Item item, Box box, String group, KitTemplates.Match match, int filled) {
    }

    /**
     * @param wanted  the index keys of the loose stacks and of the stacks inside shulkers that can serve a slot (for the survey)
     * @param matchOf the match key of each wanted index key
     * @param damage  the least damage seen on each wanted index key
     * @param ready   complete copies by template id
     * @param base    incomplete copies in the template's chain target by template id, the fullest first
     */
    public record Scan(Set<String> wanted, Map<String, String> matchOf, Map<String, Integer> damage, Map<String, List<Copy>> ready, Map<String, List<Copy>> base,
                       Set<String> named, Set<String> enchanted) {
        public Scan(Set<String> wanted, Map<String, String> matchOf, Map<String, Integer> damage, Map<String, List<Copy>> ready, Map<String, List<Copy>> base) {
            this(wanted, matchOf, damage, ready, base, Set.of(), Set.of());
        }

        /** The same scan with no incomplete copy to start from (a restock job that builds a new copy). */
        public Scan withoutBases() {
            return new Scan(wanted, matchOf, damage, ready, Map.of(), named, enchanted);
        }
    }

    /** The stacks of a shulker as the template match takes them. */
    public static List<KitTemplates.BoxStack> stacksOf(Item shulker) {
        List<KitTemplates.BoxStack> out = new ArrayList<>();
        for (Item in : shulker.contents) if (in != null && in.id != null) out.add(new KitTemplates.BoxStack("minecraft:" + in.id, in.matchKey(), in.count));
        return out;
    }

    private static String full(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    /**
     * @param pickable    boxes the bot may take a shulker or items from
     * @param chainTarget the container id the copies of a template are kept in (its chain's first usable level), or null
     */
    public static Scan scan(KitOrder.Snapshot snap, List<Box> boxes, Predicate<Box> pickable, Function<KitTemplate, @Nullable String> chainTarget) {
        Set<String> exact = new HashSet<>(), wild = new HashSet<>();
        for (KitOrder.Line l : snap.lines()) {
            for (KitTemplate.Slot s : l.template().slots()) {
                if (s == null) continue;
                if (s.any()) wild.add(s.base());
                else exact.add(s.match());
            }
        }
        Set<String> wanted = new HashSet<>();
        Map<String, String> matchOf = new HashMap<>();
        Map<String, Integer> damage = new HashMap<>();
        Map<String, List<Copy>> ready = new LinkedHashMap<>(), base = new LinkedHashMap<>();
        Set<String> named = new HashSet<>(), enchanted = new HashSet<>();
        for (Box box : boxes) {
            if (box.contents == null) continue;
            boolean usable = pickable.test(box);
            for (Item it : box.contents) {
                if (it == null) continue;
                if (it.isShulker()) {
                    if (!it.contents.isEmpty() && usable) {
                        List<KitTemplates.BoxStack> stacks = stacksOf(it);
                        String name = it.customName ? KitNames.normalize(it.customText) : null;
                        String group = ManagerIndex.shulkerKey(it);
                        for (KitOrder.Line l : snap.lines()) {
                            KitTemplates.Match m = KitTemplates.match(l.template(), stacks, name);
                            if (m.state() == KitTemplates.State.COMPLETE) ready.computeIfAbsent(l.template().id(), k -> new ArrayList<>()).add(new Copy(l.template(), it, box, group, m, 27));
                            else if (m.state() == KitTemplates.State.INCOMPLETE && KitRouting.canonicalId(box).equals(chainTarget.apply(l.template()))) {
                                int filled = 0;
                                for (KitTemplates.BoxStack b : stacks) filled += b.count();
                                base.computeIfAbsent(l.template().id(), k -> new ArrayList<>()).add(new Copy(l.template(), it, box, group, m, filled));
                            }
                        }
                    }
                    for (Item in : it.contents) note(in, exact, wild, wanted, matchOf, damage, named, enchanted);
                }
                else note(it, exact, wild, wanted, matchOf, damage, named, enchanted);
            }
        }
        for (List<Copy> list : base.values()) list.sort(Comparator.comparingInt((Copy c) -> -c.filled()).thenComparing(Copy::group));
        return new Scan(wanted, matchOf, damage, ready, base, named, enchanted);
    }

    private static void note(@Nullable Item in, Set<String> exact, Set<String> wild, Set<String> wanted, Map<String, String> matchOf, Map<String, Integer> damage,
                             Set<String> named, Set<String> enchanted) {
        if (in == null || in.id == null) return;
        String match = in.matchKey();
        if (!exact.contains(match) && !wild.contains(full(in.id))) return;
        String key = in.indexKey();
        wanted.add(key);
        matchOf.put(key, match);
        damage.merge(key, in.damage, Math::min);
        if (in.customName) named.add(key);
        if (in.enchantLevels != null && !in.enchantLevels.isEmpty()) enchanted.add(key);
    }

    /** @param units the units to add to the order (ready copies, then builds); shorts what is short already at the plan */
    public record Plan(List<Unit> units, List<KitResult.Short> shorts, List<String> notes) {
    }

    /**
     * The candidate index keys for a slot, in the order they are taken. Exact slots: unnamed before named, least damaged, the lowest key. Any-variant
     * slots: plain first, then least damaged, then the lowest key. {@code restock}: an any-variant slot never takes an enchanted stack, and takes a
     * named one only last (a named or enchanted stack is used only for a slot whose own match key is that variant).
     */
    static List<String> candidates(KitTemplate.Slot slot, Scan scan, StockPlanner.Survey survey, boolean restock) {
        List<String> out = new ArrayList<>();
        Set<String> all = new HashSet<>(survey.available().keySet());
        all.addAll(survey.shulkerAvailable().keySet());
        for (String key : all) {
            String m = scan.matchOf().get(key);
            if (m == null) continue;
            if (!(slot.any() ? KitTemplate.baseOf(m).equals(slot.base()) : m.equals(slot.match()))) continue;
            if (restock && slot.any() && scan.enchanted().contains(key)) continue;
            out.add(key);
        }
        Comparator<String> byDamage = Comparator.comparingInt((String k) -> scan.damage().getOrDefault(k, 0)).thenComparing(k -> k);
        Comparator<String> unnamedFirst = Comparator.comparing((String k) -> scan.named().contains(k) ? 1 : 0);
        if (slot.any()) out.sort(Comparator.comparing((String k) -> k.equals(slot.base()) ? 0 : 1).thenComparing(unnamedFirst).thenComparing(byDamage));
        else out.sort(unnamedFirst.thenComparing(byDamage));
        return out;
    }

    /** What the plan has drawn from the survey so far; copied before a kit or a slot and restored when it is given up. */
    private static final class Pool {
        final Map<String, Long> loose, inside;
        final Map<String, Integer> budget;
        final Map<StockPlanner.Var, Long> used;

        Pool(StockPlanner.Survey sv, Map<String, Integer> budget) {
            this.loose = new HashMap<>(sv.available());
            this.inside = new HashMap<>(sv.shulkerAvailable());
            this.budget = new HashMap<>(budget);
            this.used = new IdentityHashMap<>();
        }

        private Pool(Pool o) {
            this.loose = new HashMap<>(o.loose);
            this.inside = new HashMap<>(o.inside);
            this.budget = new HashMap<>(o.budget);
            this.used = new IdentityHashMap<>(o.used);
        }

        Pool copy() {
            return new Pool(this);
        }

        void restore(Pool o) {
            loose.clear();
            loose.putAll(o.loose);
            inside.clear();
            inside.putAll(o.inside);
            budget.clear();
            budget.putAll(o.budget);
            used.clear();
            used.putAll(o.used);
        }
    }

    private static List<StockPlanner.Var> only(List<StockPlanner.Var> vars, @Nullable String variant) {
        if (variant == null) return vars;
        List<StockPlanner.Var> out = new ArrayList<>();
        for (StockPlanner.Var v : vars) if (variant.equals(v.variant)) out.add(v);
        return out;
    }

    /**
     * @param emptiesAvailable the empty shulkers the order's other units have not taken (any colour)
     * @param claimedGroups    group keys of complete copies other orders hold, left out
     */
    public static Plan plan(KitOrder.Snapshot snap, Scan scan, StockPlanner.Survey survey, ToIntFunction<String> maxStack, int emptiesAvailable, Set<String> claimedGroups) {
        return plan(snap, scan, survey, maxStack, emptiesAvailable, claimedGroups, false);
    }

    /** @param restock a restock job: any-variant slots never take enchanted stacks and take named ones last (the budget is the caller's) */
    public static Plan plan(KitOrder.Snapshot snap, Scan scan, StockPlanner.Survey survey, ToIntFunction<String> maxStack, int emptiesAvailable, Set<String> claimedGroups,
                            boolean restock) {
        List<Unit> readyUnits = new ArrayList<>(), buildUnits = new ArrayList<>();
        List<KitResult.Short> shorts = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Map<String, Integer> budget = new HashMap<>();
        for (KitOrder.Take t : snap.take()) budget.merge(t.match(), t.count(), Integer::sum);
        Pool pool = new Pool(survey, budget);
        int empties = emptiesAvailable;
        Set<Item> taken = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        for (KitOrder.Line line : snap.lines()) {
            KitTemplate t = line.template();
            int found = 0;
            for (Copy c : scan.ready().getOrDefault(t.id(), List.of())) {
                if (found >= line.ready() || claimedGroups.contains(c.group()) || !taken.add(c.item())) continue;
                Unit u = new Unit(Unit.Kind.Shulker, c.group(), List.of());
                u.kitTemplate = t.id();
                u.kitReady = true;
                u.name = t.name();
                readyUnits.add(u);
                found++;
            }
            int build = line.count() - found;
            List<Copy> bases = new ArrayList<>(scan.base().getOrDefault(t.id(), List.of()));
            for (int i = 0; i < build; i++) {
                if (buildUnits.size() + readyUnits.size() >= Order.MAX_BINS) {
                    shorts.add(new KitResult.Short(t.id(), build - i, List.of(), KitOrder.CAP, null));
                    break;
                }
                // What this kit draws is given back when it is not built (no shulker, fewer than two kinds)
                Pool beforeKit = pool.copy();
                Copy baseCopy = null;
                List<Order.Lot> lots = new ArrayList<>();
                List<KitResult.Miss> missing = new ArrayList<>();
                Map<Integer, Item> held = new HashMap<>();
                for (Copy b : bases) {
                    Map<Integer, Item> fit = fitBase(t, b.item());
                    if (fit != null && taken.add(b.item())) {
                        baseCopy = b;
                        held = fit;
                        break;
                    }
                }
                if (baseCopy != null) bases.remove(baseCopy);
                // Stacks the kit holds so far: one per slot (a slot topped up merges into the stack the base holds)
                int stacks = 0;
                int slotsLeft = 0;
                for (KitTemplate.Slot sl : t.slots()) if (sl != null) slotsLeft++;
                for (int s = 0; s < t.slots().size(); s++) {
                    KitTemplate.Slot slot = t.slots().get(s);
                    if (slot == null) continue;
                    slotsLeft--;
                    Item have = held.get(s);
                    int need = slot.count(), max = Math.max(1, maxStack.applyAsInt(slot.base()));
                    List<String> keys;
                    String onlyVariant = null;
                    if (have != null) {
                        // The base holds part of the slot (or all of it): the missing count is added to that very stack (the same item and variant)
                        lots.add(new Order.Lot(have.indexKey(), have.variant, have.count));
                        stacks++;
                        need = slot.count() - have.count;
                        if (need <= 0) continue;
                        keys = List.of(have.indexKey());
                        onlyVariant = have.variant;
                    }
                    else keys = candidates(slot, scan, survey, restock);
                    String budgetKey = slot.any() ? slot.base() + "#*" : slot.match();
                    Pool beforeSlot = pool.copy();
                    int got = 0;
                    List<Order.Lot> mine = new ArrayList<>();
                    for (String key : keys) {
                        if (got >= need) break;
                        long loose = pool.loose.getOrDefault(key, 0L), inside = pool.inside.getOrDefault(key, 0L);
                        long usable = Math.min(Math.min(need - got, pool.budget.getOrDefault(budgetKey, 0)), loose + inside);
                        if (usable <= 0) continue;
                        long fromLoose = Math.min(usable, loose);
                        pool.loose.put(key, loose - fromLoose);
                        pool.inside.put(key, inside - (usable - fromLoose));
                        pool.budget.merge(budgetKey, (int) -usable, Integer::sum);
                        List<Order.Lot> part = new ArrayList<>(StockPlanner.allocate(key, fromLoose, max, only(survey.variants().getOrDefault(key, List.of()), onlyVariant), pool.used));
                        if (usable > fromLoose) part.addAll(StockPlanner.allocate(key, usable - fromLoose, max, only(survey.shulkerVariants().getOrDefault(key, List.of()), onlyVariant), pool.used));
                        mine.addAll(part);
                        got += (int) usable;
                    }
                    // Allocations of the same variant are one stack; a kit holds at most 27 stacks, so a slot that would need more (two variants) is unavailable
                    mine = StockPlanner.coalesce(mine, max);
                    // A topped-up slot adds no stack: its missing count merges into the base's
                    int added = have != null ? 0 : mine.size();
                    if (stacks + added + slotsLeft > KitTemplate.SLOTS) {
                        pool.restore(beforeSlot);
                        mine = new ArrayList<>();
                        got = 0;
                        added = 0;
                    }
                    stacks += added;
                    lots.addAll(mine);
                    if (got < need) missing.add(new KitResult.Miss(slot.match(), slot.key(), need - got));
                }
                int gathered = lots.size() - held.size();
                lots = oneOfEachFirst(lots);
                Unit u = new Unit(Unit.Kind.Bin, "", lots);
                u.kitTemplate = t.id();
                u.name = t.name();
                u.kitColor = t.color();
                u.kitBase = baseCopy == null ? null : baseCopy.group();
                u.kitGathered = Math.max(0, gathered);
                if (baseCopy == null && empties <= 0) {
                    pool.restore(beforeKit);
                    shorts.add(new KitResult.Short(t.id(), 1, List.of(), KitOrder.NO_SHULKER, null));
                    continue;
                }
                if (!missing.isEmpty()) {
                    u.kitShort = true;
                    u.kitMissing.addAll(missing);
                }
                // A kit of fewer than two kinds is not stored as an incomplete copy: nothing is started, nothing is taken
                if (u.kitShort && kinds(lots) < 2) {
                    pool.restore(beforeKit);
                    shorts.add(new KitResult.Short(t.id(), 1, missing, KitOrder.ITEMS_SHORT, KitResult.STORED_RETURNED));
                    continue;
                }
                if (baseCopy == null) empties--;
                buildUnits.add(u);
            }
        }
        List<Unit> units = new ArrayList<>(readyUnits);
        units.addAll(buildUnits);
        return new Plan(units, shorts, notes);
    }

    /** The distinct bases among the lots' keys. */
    static int kinds(List<Order.Lot> lots) {
        Set<String> bases = new HashSet<>();
        for (Order.Lot l : lots) bases.add(KitTemplate.baseOf(l.key()));
        return bases.size();
    }

    /** The first stack of each kind (by item key) goes first, so the kit has its range early; the rest keep their order. */
    static List<Order.Lot> oneOfEachFirst(List<Order.Lot> lots) {
        List<Order.Lot> first = new ArrayList<>(), rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Order.Lot l : lots) (seen.add(l.key()) ? first : rest).add(l);
        first.addAll(rest);
        return first;
    }

    /**
     * How an incomplete copy fits the template slot by slot: every stack of it sits in a slot of the template whose exact count it is (an exact slot of
     * its match key, or an any-variant slot of its item). Null when some stack does not fit (such a copy is not used as a base).
     * @return template slot index to the stack there
     */
    static @Nullable Map<Integer, Item> fitBase(KitTemplate t, Item shulker) {
        Map<Integer, Item> out = new HashMap<>();
        for (Item in : shulker.contents) {
            if (in == null || in.id == null) continue;
            int at = -1;
            // A slot of exactly its count first, else one it can still be topped up to
            for (int pass = 0; pass < 2 && at < 0; pass++) {
                for (int s = 0; s < t.slots().size() && at < 0; s++) {
                    KitTemplate.Slot slot = t.slots().get(s);
                    if (slot == null || out.containsKey(s)) continue;
                    if (pass == 0 ? slot.count() != in.count : slot.count() < in.count) continue;
                    if (slot.any() ? slot.base().equals(full(in.id)) : slot.match().equals(in.matchKey())) at = s;
                }
            }
            if (at < 0) return null;
            out.put(at, in);
        }
        return out;
    }
}
