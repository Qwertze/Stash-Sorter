package com.porter.qwertestash.stash;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Whether a shulker's contents are a kit template (KIT-CONTRACT v2, revision 4, "Template match"). Per base id: for the template E[base][m] is the
 * total count of its exact slots with match key m and W[base] the total of its any-variant slots; for the box B[base][m] is the total count of
 * stacks with match key m. {@code covered} is the sum of min(B, E) and {@code excess} the rest of the box's stacks of that base.
 * <ul>
 *     <li>complete: every base in the box is in the template; for every exact match key B >= E; and excess == W for every base;</li>
 *     <li>incomplete: not complete, every base in the box is in the template, excess <= W for every base, the box carries the template's name
 *     (normalised) and holds at least two distinct base ids;</li>
 *     <li>else no match.</li>
 * </ul>
 * Slot positions, stack splits, custom names, damage and repair cost are ignored (they are not in a match key). Pure; any thread.
 */
public final class KitTemplates {
    private KitTemplates() {
    }

    public enum State { NONE, INCOMPLETE, COMPLETE }

    /** One occupied slot of a box: the item id (with namespace), its match key and its count. */
    public record BoxStack(String id, String match, int count) {
    }

    /** What is missing to make a box the template: the template slot key, its match key and how many. */
    public record Missing(String key, String match, int count) {
    }

    /** @param missing for INCOMPLETE: at most 8 items, by count descending then key */
    public record Match(State state, List<Missing> missing) {
        static final Match NONE = new Match(State.NONE, List.of());
    }

    public static Match match(KitTemplate t, List<BoxStack> box, @Nullable String boxNameNormalized) {
        Map<String, Map<String, Integer>> exact = new HashMap<>();
        Map<String, Integer> wild = new HashMap<>();
        Map<String, Map<String, String>> keyOf = new HashMap<>();
        for (KitTemplate.Slot s : t.slots()) {
            if (s == null) continue;
            if (s.any()) wild.merge(s.base(), s.count(), Integer::sum);
            else {
                exact.computeIfAbsent(s.base(), k -> new HashMap<>()).merge(s.match(), s.count(), Integer::sum);
                keyOf.computeIfAbsent(s.base(), k -> new HashMap<>()).putIfAbsent(s.match(), s.key());
            }
        }
        Map<String, Map<String, Integer>> have = new HashMap<>();
        for (BoxStack b : box) have.computeIfAbsent(KitTypes.full(b.id()), k -> new HashMap<>()).merge(b.match(), b.count(), Integer::sum);
        if (have.isEmpty()) return Match.NONE;
        java.util.Set<String> bases = new java.util.HashSet<>(exact.keySet());
        bases.addAll(wild.keySet());
        for (String base : have.keySet()) if (!bases.contains(base)) return Match.NONE;

        boolean complete = true, sub = true;
        List<Missing> missing = new ArrayList<>();
        for (String base : bases) {
            Map<String, Integer> e = exact.getOrDefault(base, Map.of()), b = have.getOrDefault(base, Map.of());
            int w = wild.getOrDefault(base, 0);
            int covered = 0, total = 0;
            for (int n : b.values()) total += n;
            for (var en : e.entrySet()) {
                int got = b.getOrDefault(en.getKey(), 0);
                covered += Math.min(got, en.getValue());
                if (got < en.getValue()) {
                    complete = false;
                    missing.add(new Missing(keyOf.get(base).get(en.getKey()), en.getKey(), en.getValue() - got));
                }
            }
            int excess = total - covered;
            if (excess != w) complete = false;
            if (excess > w) sub = false;
            if (excess < w) missing.add(new Missing(base + "#*", base + "#*", w - excess));
        }
        if (complete) return new Match(State.COMPLETE, List.of());
        String own = KitNames.normalize(t.name());
        if (sub && boxNameNormalized != null && boxNameNormalized.equals(own) && have.size() >= 2) {
            missing.sort(Comparator.comparingInt((Missing m) -> -m.count()).thenComparing(Missing::key));
            return new Match(State.INCOMPLETE, missing.size() > 8 ? new ArrayList<>(missing.subList(0, 8)) : missing);
        }
        return Match.NONE;
    }

    /** The first template (by id) the box is a complete copy of, else the one it is an incomplete copy of, else null. */
    public static @Nullable Found find(List<KitTemplate> templates, List<BoxStack> box, @Nullable String boxNameNormalized) {
        Found incomplete = null;
        Map<String, KitTemplate> byId = new TreeMap<>();
        for (KitTemplate t : templates) byId.put(t.id(), t);
        for (KitTemplate t : byId.values()) {
            Match m = match(t, box, boxNameNormalized);
            if (m.state() == State.COMPLETE) return new Found(t, m);
            if (m.state() == State.INCOMPLETE && incomplete == null) incomplete = new Found(t, m);
        }
        return incomplete;
    }

    public record Found(KitTemplate template, Match match) {
    }

    /** The distinct base ids of a box's stacks. */
    public static Set<String> bases(List<BoxStack> box) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (BoxStack b : box) out.add(KitTypes.full(b.id()));
        return out;
    }

    /** Counts per match key, for tests and the report. */
    public static Map<String, Integer> totals(List<BoxStack> box) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (BoxStack b : box) out.merge(b.match(), b.count(), Integer::sum);
        return out;
    }
}
