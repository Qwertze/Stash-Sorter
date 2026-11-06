package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Plans an order from what the stash can really give, a port of the addon's {@code OrderRunner.planFresh}. Plain data in,
 * plain data out: the sorter makes the {@link Survey} from its saved containers.
 * <p>
 * Stacks that differ in any component (ViaVersion's marker, say) cannot share a slot, so every stack of a line is taken
 * from one variant (the smallest that has enough, else the biggest) and a line may be split over two variants.
 */
public final class StockPlanner {
    private StockPlanner() {
    }

    /** Stacks of one key that are identical in every component, so they can share a slot. */
    public static final class Var {
        public final String variant;
        public long count;

        public Var(String variant, long count) {
            this.variant = variant;
            this.count = count;
        }
    }

    /**
     * What the stash can give.
     * @param available how many loose items of each wanted key there are in usable containers
     * @param maxStack  the stack size of each key found
     * @param filled    keys only found as filled containers or bundles (not orderable)
     * @param staleOnly keys only found in containers not read again yet
     * @param variants  per key, the variants and how many of each (biggest first)
     * @param why       per key, why some of it cannot be used
     * @param shulkerAvailable how many of each wanted key lie inside shulkers in usable containers (a second source: loose stacks come first)
     * @param shulkerVariants  per key, the variants inside shulkers and how many of each
     */
    public record Survey(Map<String, Long> available, Map<String, Integer> maxStack, Set<String> filled, Set<String> staleOnly,
                         Map<String, List<Var>> variants, Map<String, String> why,
                         Map<String, Long> shulkerAvailable, Map<String, List<Var>> shulkerVariants) {
        /** A survey of loose stacks only. */
        public Survey(Map<String, Long> available, Map<String, Integer> maxStack, Set<String> filled, Set<String> staleOnly,
                      Map<String, List<Var>> variants, Map<String, String> why) {
            this(available, maxStack, filled, staleOnly, variants, why, Map.of(), Map.of());
        }
    }

    /** @param failure why the order cannot be done at all, or null */
    public record Plan(List<Unit> units, List<String> notes, @Nullable String failure) {
    }

    /** "minecraft:red_shulker_box" or "minecraft:red_shulker_box#ab12cd34": a shulker box of any colour. */
    public static boolean isShulkerKey(String key) {
        int hash = key.indexOf('#');
        String id = hash < 0 ? key : key.substring(0, hash);
        return id.endsWith("shulker_box");
    }

    /**
     * @param emptiesByKey the empty unnamed shulkers the stash holds, by item key
     * @param nameOf       a readable name for a key, for the notes
     */
    public static Plan plan(Order order, Survey survey, Map<String, Integer> emptiesByKey, Function<String, String> nameOf) {
        List<Unit> shulkerUnits = new ArrayList<>(), emptyUnits = new ArrayList<>();
        List<Order.Lot> lots = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long stacks = 0;
        Map<String, Long> left = new HashMap<>(survey.available());
        Map<String, Integer> empties = new HashMap<>(emptiesByKey);
        Map<Var, Long> used = new IdentityHashMap<>();
        Map<String, Long> shulkerLeft = new HashMap<>();

        for (Order.Line l : order.lines) {
            if ((l.type().equals(Order.SHULKER) || isShulkerKey(l.key())) && shulkerUnits.size() + emptyUnits.size() + (long) l.count() > Order.MAX_BINS) {
                // Checked before anything is counted out: a line of millions would never finish
                return new Plan(List.of(), notes, "too big: that is more than " + Order.MAX_BINS + " shulkers");
            }
            if (l.type().equals(Order.SHULKER)) {
                for (int i = 0; i < l.count(); i++) shulkerUnits.add(new Unit(Unit.Kind.Shulker, l.key(), List.of()));
                continue;
            }

            // Shulkers can't go in shulkers: an item line for a shulker box is empty shulkers, delivered as they are
            if (isShulkerKey(l.key())) {
                int have = empties.getOrDefault(l.key(), 0);
                int take = Math.min(l.count(), have);
                empties.put(l.key(), have - take);
                for (int i = 0; i < take; i++) emptyUnits.add(new Unit(Unit.Kind.Empty, l.key(), List.of()));
                if (take < l.count()) notes.add(nameOf.apply(l.key()) + ": only " + take + " of " + l.count() + " free");
                continue;
            }

            long looseHave = survey.available().getOrDefault(l.key(), 0L), shulkerHave = survey.shulkerAvailable().getOrDefault(l.key(), 0L);
            if (looseHave + shulkerHave <= 0) {
                String name = nameOf.apply(l.key());
                notes.add(survey.filled().contains(l.key()) ? name + ": can't order filled containers/bundles"
                    : survey.staleOnly().contains(l.key()) ? name + ": only in containers I couldn't read again"
                    : survey.why().containsKey(l.key()) ? name + ": only " + survey.why().get(l.key())
                    : name + ": none usable in the stash");
                continue;
            }

            long usable = Math.min(l.count(), left.getOrDefault(l.key(), 0L) + shulkerLeft.getOrDefault(l.key(), shulkerHave));
            long fromLoose = Math.min(usable, left.getOrDefault(l.key(), 0L));
            left.put(l.key(), left.getOrDefault(l.key(), 0L) - fromLoose);
            shulkerLeft.put(l.key(), shulkerLeft.getOrDefault(l.key(), shulkerHave) - (usable - fromLoose));
            if (usable < l.count()) {
                String reason = survey.why().get(l.key());
                notes.add(nameOf.apply(l.key()) + ": only " + usable + " of " + l.count() + " usable" + (reason == null ? "" : " (the rest " + reason + ")"));
            }
            if (usable <= 0) continue;

            int max = Math.max(1, survey.maxStack().getOrDefault(l.key(), 64));
            // Loose stacks first; what they can't give is taken out of shulkers
            List<Order.Lot> mine = new ArrayList<>(allocate(l.key(), fromLoose, max, survey.variants().getOrDefault(l.key(), List.of()), used));
            if (usable > fromLoose) mine.addAll(allocate(l.key(), usable - fromLoose, max, survey.shulkerVariants().getOrDefault(l.key(), List.of()), used));
            mine = coalesce(mine, max);
            stacks += mine.size();
            if (stacks > (long) Order.MAX_BINS * Order.SLOTS) return new Plan(List.of(), notes, "too big: that is more than " + Order.MAX_BINS + " shulkers");
            lots.addAll(mine);
        }

        List<List<Order.Lot>> bins = Order.pack(lots);
        if (shulkerUnits.size() + emptyUnits.size() + bins.size() > Order.MAX_BINS) {
            return new Plan(List.of(), notes, "too big: that is more than " + Order.MAX_BINS + " shulkers");
        }

        List<Unit> plan = new ArrayList<>(shulkerUnits);
        plan.addAll(emptyUnits);
        for (List<Order.Lot> bin : bins) plan.add(new Unit(Unit.Kind.Bin, "", new ArrayList<>(bin)));
        if (plan.isEmpty()) return new Plan(plan, notes, notes.isEmpty() ? "nothing to deliver" : "nothing can be done: " + notes.get(0));
        return new Plan(plan, notes, null);
    }

    /**
     * Loads of one key and variant that were drawn from two sources are one: partial loads are summed and cut into full
     * stacks and one remainder, so a bin never holds two loads that would merge in a slot.
     */
    public static List<Order.Lot> coalesce(List<Order.Lot> lots, int max) {
        Map<String, Integer> partial = new java.util.LinkedHashMap<>();
        Map<String, Order.Lot> sample = new HashMap<>();
        List<Order.Lot> out = new ArrayList<>();
        for (Order.Lot lot : lots) {
            if (lot.count() >= max) {
                out.add(lot);
                continue;
            }
            String id = lot.key() + "|" + lot.variant();
            partial.merge(id, lot.count(), Integer::sum);
            sample.putIfAbsent(id, lot);
        }
        for (var e : partial.entrySet()) {
            Order.Lot s = sample.get(e.getKey());
            int left = e.getValue();
            for (; left > max; left -= max) out.add(new Order.Lot(s.key(), s.variant(), max));
            if (left > 0) out.add(new Order.Lot(s.key(), s.variant(), left));
        }
        return out;
    }

    /** The variant to take {@code want} from: the smallest with that much left, else the one with the most left; null if none. */
    public static @Nullable Var bestFit(List<Var> variants, Map<Var, Long> used, int want) {
        Var fit = null, biggest = null;
        long fitRest = Long.MAX_VALUE, biggestRest = 0;
        for (Var v : variants) {
            long rest = v.count - used.getOrDefault(v, 0L);
            if (rest <= 0) continue;
            if (rest >= want && rest < fitRest) {
                fit = v;
                fitRest = rest;
            }
            if (rest > biggestRest) {
                biggest = v;
                biggestRest = rest;
            }
        }
        return fit != null ? fit : biggest;
    }

    /**
     * The loads of a shulker planned once more from a fresh survey, the way {@link #plan} counts stock: loose stacks
     * first, then what lies inside shulkers. What is not there any more is dropped; at most {@link Order#SLOTS} loads.
     */
    public static List<Order.Lot> trim(List<Order.Lot> lots, Survey survey) {
        Map<String, Long> totals = new java.util.LinkedHashMap<>();
        for (Order.Lot lot : lots) totals.merge(lot.key(), (long) lot.count(), Long::sum);
        Map<Var, Long> used = new IdentityHashMap<>();
        List<Order.Lot> out = new ArrayList<>();
        for (var e : totals.entrySet()) {
            String key = e.getKey();
            long loose = survey.available().getOrDefault(key, 0L), inShulkers = survey.shulkerAvailable().getOrDefault(key, 0L);
            long usable = Math.min(e.getValue(), loose + inShulkers);
            if (usable <= 0) continue;
            long fromLoose = Math.min(usable, loose);
            int max = Math.max(1, survey.maxStack().getOrDefault(key, 64));
            List<Order.Lot> mine = new ArrayList<>(allocate(key, fromLoose, max, survey.variants().getOrDefault(key, List.of()), used));
            if (usable > fromLoose) mine.addAll(allocate(key, usable - fromLoose, max, survey.shulkerVariants().getOrDefault(key, List.of()), used));
            out.addAll(coalesce(mine, max));
        }
        if (out.size() > Order.SLOTS) out = new ArrayList<>(out.subList(0, Order.SLOTS));
        return out;
    }

    /** Slot loads for a line of {@code total} items: one stack at a time, each from the best fitting variant. */
    public static List<Order.Lot> allocate(String key, long total, int max, List<Var> variants, Map<Var, Long> used) {
        List<Order.Lot> out = new ArrayList<>();
        long remaining = total;
        while (remaining > 0) {
            int want = (int) Math.min(remaining, max);
            while (want > 0) {
                Var v = bestFit(variants, used, want);
                if (v == null) return out;
                int take = (int) Math.min(want, v.count - used.getOrDefault(v, 0L));
                used.merge(v, (long) take, Long::sum);
                out.add(new Order.Lot(key, v.variant, take));
                want -= take;
                remaining -= take;
            }
        }
        return out;
    }
}
