package com.porter.qwertestash.stash;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The kit classifier (KIT-CONTRACT v2, "Classification" with revisions 3 and 4): works out from a shulker's contents what kind of kit it is, or why
 * it is not one. The bots compute it; the server and the pages only show it. All arithmetic is in integers.
 * <ol>
 *     <li>no occupied slot: not a kit, {@code empty};</li>
 *     <li>exactly one distinct id: not a kit, {@code stock} (or {@code stock_neutral} when the item has no type), whatever the name;</li>
 *     <li>the name's key has a {@code treatAs}: "none" is {@code override_none}, a category {@code override};</li>
 *     <li>the contents match a kit template: complete is {@code template}, incomplete {@code template_incomplete};</li>
 *     <li>a type passes (covered >= 1, kinds >= minKinds and the share test: half when lowered by the name, else three quarters): the best passing
 *     type, {@code assortment} or {@code named_assortment};</li>
 *     <li>nothing passes: not a kit; the closest type gives {@code no_purpose}, {@code few_kinds} or {@code mixed}.</li>
 * </ol>
 * Pure; any thread.
 */
public final class KitClassifier {
    private KitClassifier() {
    }

    /** The codes of {@code why.code}. */
    public static final String ASSORTMENT = "assortment", NAMED_ASSORTMENT = "named_assortment", OVERRIDE = "override", TEMPLATE = "template",
        TEMPLATE_INCOMPLETE = "template_incomplete", EMPTY = "empty", STOCK = "stock", STOCK_NEUTRAL = "stock_neutral", NO_PURPOSE = "no_purpose",
        FEW_KINDS = "few_kinds", MIXED = "mixed", OVERRIDE_NONE = "override_none";

    /**
     * @param type     the category of a kit, or the type shown in the reason of a box that is not one; null when there is none
     * @param need     for mixed and few_kinds; {@code minKinds} the same; both 0 otherwise
     * @param template the id of the matched template
     * @param missing  for template_incomplete: at most 8 template slot keys and how many are missing
     */
    public record Result(boolean kit, String code, @Nullable String type, int slots, int covered, int kinds, int need, int minKinds, List<String> items, List<String> words,
                         @Nullable String template, List<KitTemplates.Missing> missing) {
    }

    private static final Pattern WORD_CHARS = Pattern.compile("^[a-z]{1,32}$");

    /** A word (not regex) in a normalised name, with no letter or combining mark on either side. */
    static boolean wordIn(@Nullable String name, String word) {
        if (name == null || name.isEmpty()) return false;
        int from = 0;
        while (true) {
            int i = name.indexOf(word, from);
            if (i < 0) return false;
            boolean before = i > 0 && lm(name.codePointBefore(i));
            int end = i + word.length();
            boolean after = end < name.length() && lm(name.codePointAt(end));
            if (!before && !after) return true;
            from = i + 1;
        }
    }

    private static boolean lm(int cp) {
        int t = Character.getType(cp);
        return Character.isLetter(cp) || t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK || t == Character.ENCLOSING_MARK;
    }

    /**
     * @param slots the occupied slots in slot order (a bundle is one item)
     * @param name  the normalised custom name, or null; the key is this name when it is a valid key
     * @param types the settings' v2 part (treatAs, templates), or null (an older server)
     */
    public static Result classify(KitTypes table, List<KitTemplates.BoxStack> slots, @Nullable String name, KitSettings.@Nullable Types types) {
        int n = slots.size();
        if (n == 0) return new Result(false, EMPTY, null, 0, 0, 0, 0, 0, List.of(), List.of(), null, List.of());

        // 2: one kind of item is stock
        Set<String> distinct = new LinkedHashSet<>();
        for (KitTemplates.BoxStack s : slots) distinct.add(KitTypes.full(s.id()));
        if (distinct.size() == 1) {
            String x = distinct.iterator().next();
            List<String> ts = table.typesOf(x);
            if (ts.isEmpty()) return new Result(false, STOCK_NEUTRAL, null, n, 0, 0, 0, 0, List.of(x), List.of(), null, List.of());
            return new Result(false, STOCK, ts.get(0), n, n, 1, 0, 0, List.of(x), List.of(), null, List.of());
        }

        // 3: set by hand for this name
        String key = KitNames.validKey(name) ? name : null;
        if (key != null && types != null) {
            String v = types.treatAs().get(key);
            if (v != null) {
                if (v.equals("none")) return new Result(false, OVERRIDE_NONE, null, n, 0, 0, 0, 0, List.of(), List.of(), null, List.of());
                int[] st = stats(table, slots, v);
                return new Result(true, OVERRIDE, v, n, st[0], st[1], 0, 0, top(table, slots, v), List.of(), null, List.of());
            }
        }

        // 4: a kit template, complete or part built
        if (types != null && !types.templates().isEmpty()) {
            KitTemplates.Found f = KitTemplates.find(types.templates(), slots, name);
            if (f != null) {
                boolean complete = f.match().state() == KitTemplates.State.COMPLETE;
                return new Result(true, complete ? TEMPLATE : TEMPLATE_INCOMPLETE, f.template().category(), n, 0, 0, 0, 0, List.of(), List.of(), f.template().id(),
                    f.match().missing());
            }
        }

        // 5 and 6: the types
        List<KitTypes.Type> all = table.types();
        boolean kitWord = wordIn(name, "kit") || wordIn(name, "kits");
        Map<String, int[]> st = new LinkedHashMap<>();
        Set<String> hinted = new LinkedHashSet<>();
        Map<String, List<String>> matched = new HashMap<>();
        for (KitTypes.Type t : all) {
            st.put(t.id(), stats(table, slots, t.id()));
            for (String w : t.words()) {
                if (wordIn(name, w)) {
                    hinted.add(t.id());
                    matched.computeIfAbsent(t.id(), k -> new ArrayList<>()).add(w);
                }
            }
        }
        List<KitTypes.Type> passing = new ArrayList<>();
        for (KitTypes.Type t : all) {
            int[] s = st.get(t.id());
            boolean lowered = kitWord || hinted.contains(t.id());
            boolean share = lowered ? 2 * s[0] >= n : 4 * s[0] >= 3 * n;
            if (s[0] >= 1 && s[1] >= t.minKinds() && share) passing.add(t);
        }
        if (!passing.isEmpty()) {
            KitTypes.Type best = passing.stream().min(Comparator.<KitTypes.Type, Boolean>comparing(t -> !hinted.contains(t.id()))
                .thenComparingInt(t -> -st.get(t.id())[2]).thenComparingInt(t -> -st.get(t.id())[0]).thenComparingInt(t -> -st.get(t.id())[1])
                .thenComparingInt(t -> table.rank(t.id()))).get();
            int[] s = st.get(best.id());
            boolean quarters = 4 * s[0] >= 3 * n;
            List<String> words = new ArrayList<>();
            if (!quarters) {
                for (String w : matched.getOrDefault(best.id(), List.of())) if (WORD_CHARS.matcher(w).matches() && !words.contains(w) && words.size() < 4) words.add(w);
                if (kitWord) {
                    for (String w : List.of("kit", "kits")) if (wordIn(name, w) && !words.contains(w) && words.size() < 4) words.add(w);
                }
            }
            return new Result(true, quarters ? ASSORTMENT : NAMED_ASSORTMENT, best.id(), n, s[0], s[1], 0, 0, top(table, slots, best.id()), words, null, List.of());
        }

        KitTypes.Type closest = null;
        for (KitTypes.Type t : all) {
            int[] s = st.get(t.id());
            if (s[0] < 1) continue;
            if (closest == null) {
                closest = t;
                continue;
            }
            int[] c = st.get(closest.id());
            if (s[0] > c[0] || (s[0] == c[0] && (s[2] > c[2] || (s[2] == c[2] && s[1] > c[1])))) closest = t;
        }
        if (closest == null) return new Result(false, NO_PURPOSE, null, n, 0, 0, 0, 0, top(table, slots, null), List.of(), null, List.of());
        int[] s = st.get(closest.id());
        boolean lowered = kitWord || hinted.contains(closest.id());
        int need = lowered ? (n + 1) / 2 : (3 * n + 3) / 4;
        return new Result(false, s[0] >= need ? FEW_KINDS : MIXED, closest.id(), n, s[0], s[1], need, closest.minKinds(), top(table, slots, closest.id()), List.of(), null, List.of());
    }

    /** {covered slots, distinct kinds among them, slots whose primary type it is} for a type. */
    private static int[] stats(KitTypes table, List<KitTemplates.BoxStack> slots, String type) {
        int covered = 0, primary = 0;
        Set<String> kinds = new LinkedHashSet<>();
        for (KitTemplates.BoxStack s : slots) {
            String id = KitTypes.full(s.id());
            List<String> ts = table.typesOf(id);
            if (ts.contains(type)) {
                covered++;
                kinds.add(id);
            }
            if (!ts.isEmpty() && ts.get(0).equals(type)) primary++;
        }
        return new int[] {covered, kinds.size(), primary};
    }

    /** The ids counted for a type (every id when {@code type} is null), by slot count descending then id; the first 3. */
    private static List<String> top(KitTypes table, List<KitTemplates.BoxStack> slots, @Nullable String type) {
        Map<String, Integer> counts = new HashMap<>();
        for (KitTemplates.BoxStack s : slots) {
            String id = KitTypes.full(s.id());
            if (type == null || table.typesOf(id).contains(type)) counts.merge(id, 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < 3; i++) out.add(entries.get(i).getKey());
        return out;
    }
}
