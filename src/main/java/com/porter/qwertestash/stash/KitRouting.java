package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Where a kit shulker goes (KIT-CONTRACT.md, "What the bot does"). {@link #decide} is the pure decision on a name and the settings;
 * {@link View} puts the settings against a stash: which box an id is, which boxes are kit-reserved. No game access, any thread.
 */
public final class KitRouting {
    private KitRouting() {
    }

    public enum Kind {
        /** Not a shulker, or it does not score as a kit: this feature has nothing to say. */
        NOT_KIT,
        /** A kit with neither a rule nor a kits chest (an unnamed kit has no rule): today's sorting. */
        NONE,
        /** The rule's container. */
        RULE,
        /** The kits chest. */
        KITS_CHEST,
        /** The matched kit template's own chest (v2). */
        TEMPLATE,
        /** The chest of the kit's category (v2). */
        CATEGORY,
        /** A container was chosen but is missing or can't take kits: no destination. */
        MISSING,
        /** Every chest in the kit's chain is read but full (or not usable now): no destination, no warning. */
        FULL
    }

    /** @param key the kit's name key, null for an unnamed kit and for NOT_KIT; @param container the chosen container id (a rule's or the kits chest), null for NOT_KIT and NONE */
    public record Decision(Kind kind, @Nullable String key, @Nullable String container) {
        static final Decision NOT_KIT = new Decision(Kind.NOT_KIT, null, null);

        /** The kit has a say in where it goes (a target, or none at all): today's sorting does not apply. */
        public boolean routed() {
            return kind == Kind.RULE || kind == Kind.KITS_CHEST || kind == Kind.TEMPLATE || kind == Kind.CATEGORY || kind == Kind.MISSING || kind == Kind.FULL;
        }
    }

    /**
     * Order, for a box that scores as a kit: a named kit's rule (its container, if it resolves and is pickable: the only destination), else the
     * kits chest the same way (an unnamed kit has no rule), else nothing set. A chosen container that is missing or not pickable is MISSING.
     * @param key      the normalised custom name, null when unnamed
     * @param kit      the box scores as a kit
     * @param pickable whether a container id resolves to a box that can take kits
     */
    public static Decision decide(@Nullable String key, boolean kit, KitSettings settings, Predicate<String> pickable) {
        if (!kit) return Decision.NOT_KIT;
        String chosen = key == null ? null : settings.rules().get(key);
        Kind kind = Kind.RULE;
        if (chosen == null) {
            chosen = settings.kitsChest();
            kind = Kind.KITS_CHEST;
        }
        if (chosen == null) return new Decision(Kind.NONE, key, null);
        return new Decision(pickable.test(chosen) ? kind : Kind.MISSING, key, chosen);
    }

    /** One level of a kit's chain: where the container comes from ("rule", "template", "category", "kitsChest") and its id. */
    public record Level(String source, String container) {
    }

    /**
     * A kit's chain (KIT-CONTRACT v2, "What the bot does"): the levels that are set, in order: (a) its rule's container, for a named kit with a rule;
     * (b) the matched template's chest; (c) its category's chest; (d) the general kits chest. A container that comes twice is listed at its first
     * level. Empty: today's sorting.
     */
    public static List<Level> chain(@Nullable String key, KitClassifier.Result why, KitSettings settings) {
        java.util.List<Level> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String rule = key == null ? null : settings.rules().get(key);
        if (rule != null && seen.add(rule)) out.add(new Level("rule", rule));
        KitSettings.Types types = settings.types();
        if (types != null) {
            if (why.template() != null) {
                KitTemplate t = types.template(why.template());
                if (t != null && t.chest() != null && seen.add(t.chest())) out.add(new Level("template", t.chest()));
            }
            String category = why.kit() ? why.type() : null;
            String chest = category == null ? null : types.chests().get(category);
            if (chest != null && seen.add(chest)) out.add(new Level("category", chest));
        }
        if (settings.kitsChest() != null && seen.add(settings.kitsChest())) out.add(new Level("kitsChest", settings.kitsChest()));
        return out;
    }

    /** The container the copies of a template are kept in: the first level of its chain (its name's rule, its chest, its category's chest, the kits chest), or null. */
    public static @Nullable String templateTarget(KitTemplate t, KitSettings settings) {
        KitClassifier.Result as = new KitClassifier.Result(true, KitClassifier.TEMPLATE, t.category(), 27, 0, 0, 0, 0, List.of(), List.of(), t.id(), List.of());
        List<Level> chain = chain(KitNames.normalize(t.name()), as, settings);
        return chain.isEmpty() ? null : chain.get(0).container();
    }

    /**
     * Where a kit goes along its chain.
     * @param level index into the chain of the chosen container, or -1 for none; {@code kind} what it is; {@code stays}: the kit is home and is not moved
     */
    public record Pick(Kind kind, int level, boolean stays) {
    }

    private static Kind kindOf(String source) {
        return switch (source) {
            case "rule" -> Kind.RULE;
            case "template" -> Kind.TEMPLATE;
            case "category" -> Kind.CATEGORY;
            default -> Kind.KITS_CHEST;
        };
    }

    /**
     * The destination along a chain:
     * <ul>
     *     <li>a kit to place, or stored elsewhere (current rank -1): the first usable container;</li>
     *     <li>a stored kit already in a chain container: the first usable container ranked above its current one; none, and it stays (it is home):
     *     a kit is never moved down its chain because its chest filled up;</li>
     *     <li>no usable container: MISSING when none of the chain's containers can take kits, else FULL.</li>
     * </ul>
     * @param pickable    the container resolves to a box that can take kits
     * @param usable      pickable, with at least one empty slot by the bot's current read
     * @param currentRank the chain index of the container the kit is stored in, or -1
     */
    public static Pick pick(List<Level> chain, Predicate<String> pickable, Predicate<String> usable, int currentRank) {
        if (chain.isEmpty()) return new Pick(Kind.NONE, -1, false);
        int upper = currentRank >= 0 ? currentRank : chain.size();
        for (int i = 0; i < upper; i++) if (usable.test(chain.get(i).container())) return new Pick(kindOf(chain.get(i).source()), i, false);
        if (currentRank >= 0) return new Pick(kindOf(chain.get(currentRank).source()), currentRank, true);
        boolean any = false;
        for (Level l : chain) any |= pickable.test(l.container());
        return new Pick(any ? Kind.FULL : Kind.MISSING, -1, false);
    }

    /** The id of a box: its smallest part (x, then y, then z). */
    public static String canonicalId(Box box) {
        BlockPos best = null;
        for (BlockPos p : box.parts) if (best == null || before(p, best)) best = p;
        if (best == null) best = box.pos;
        return KitSettings.id(best.x(), best.y(), best.z());
    }

    private static boolean before(BlockPos a, BlockPos b) {
        if (a.x() != b.x()) return a.x() < b.x();
        if (a.y() != b.y()) return a.y() < b.y();
        return a.z() < b.z();
    }

    /** Read, sortable, not excluded, not a drop-off and not a delivery container. */
    public static boolean pickable(@Nullable Box box) {
        return box != null && box.sortable() && !box.dropOff && !box.delivery;
    }

    /**
     * Whether a shulker box is a kit that the settings have a say over: it scores as a kit by the stash's scoring and a rule or the kits chest
     * applies to it (an unnamed kit: the kits chest), whether that chest can take it or is missing. Such a kit is kept whole (never unpacked or
     * repacked). With no settings, or none that apply to it (NONE), this is false and today's sorting is unchanged.
     */
    public static boolean keptWhole(@Nullable View view, @Nullable Item item) {
        return view != null && item != null && view.route(item).decision().routed();
    }

    /** What a decision is for one stack in a stash, plus the box it names. */
    public record Route(Decision decision, @Nullable Box box, boolean fallback) {
        public Route(Decision decision, @Nullable Box box) {
            this(decision, box, false);
        }

        public Kind kind() {
            return decision.kind();
        }
    }

    /**
     * The settings against one stash at one moment. Ids are resolved with {@code boxAt}, which matches any part, so an id keeps working
     * when a single chest becomes a double. Build a new one when the settings or the boxes change.
     */
    public static final class View {
        private final Stash stash;
        private final KitSettings settings;
        private final Map<String, Box> resolved = new HashMap<>();
        private final Set<Box> reserved;

        public View(Stash stash, KitSettings settings) {
            this.stash = stash;
            this.settings = settings;
            Set<Box> r = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String id : settings.rules().values()) add(r, id);
            if (settings.kitsChest() != null) add(r, settings.kitsChest());
            // v2: every category chest and every template chest is kit-reserved too
            if (settings.types() != null) {
                for (String id : settings.types().chests().values()) add(r, id);
                for (KitTemplate t : settings.types().templates()) if (t.chest() != null) add(r, t.chest());
            }
            this.reserved = r;
        }

        private void add(Set<Box> into, String id) {
            Box box = box(id);
            if (box != null) into.add(box);
        }

        public KitSettings settings() {
            return settings;
        }

        /** The box an id names, or null when there is none. */
        public @Nullable Box box(String id) {
            if (resolved.containsKey(id)) return resolved.get(id);
            BlockPos pos = KitSettings.position(id);
            Box box = pos == null ? null : stash.boxAt(pos);
            resolved.put(id, box);
            return box;
        }

        /** The kits chest and every rule target: nothing but kit shulkers goes in. */
        public boolean reserved(Box box) {
            return reserved.contains(box);
        }

        public boolean isEmpty() {
            return settings.isEmpty();
        }

        private static final Route NOT_KIT = new Route(Decision.NOT_KIT, null);

        /** What a stack classified as: the result of the classifier, and its key when it is a named kit. Worked out once per stack (stored stacks are replaced, never changed). */
        private record Scored(KitClassifier.Result result, @Nullable String key) {
            boolean kit() {
                return result.kit();
            }
        }

        private final Map<Item, Scored> scored = new IdentityHashMap<>();

        private Scored scored(Item item) {
            synchronized (scored) {
                Scored s = scored.get(item);
                if (s == null) {
                    s = classify(item);
                    // Carried stacks are new objects every time: the memo is cleared rather than allowed to grow without end
                    if (scored.size() > 20_000) scored.clear();
                    scored.put(item, s);
                }
                return s;
            }
        }

        private Scored classify(Item item) {
            KitTypes table = KitTypes.shared();
            // The custom name is normalised once: for the hints of the name and for the key
            String norm = item.customName ? KitNames.normalize(item.customText) : null;
            if (table == null) return new Scored(new KitClassifier.Result(false, KitClassifier.NO_PURPOSE, null, 0, 0, 0, 0, 0, List.of(), List.of(), null, List.of()), null);
            List<KitTemplates.BoxStack> slots = new java.util.ArrayList<>();
            for (Item inner : item.contents) if (inner != null && inner.id != null) slots.add(new KitTemplates.BoxStack("minecraft:" + inner.id, inner.matchKey(), inner.count));
            KitClassifier.Result r = KitClassifier.classify(table, slots, norm, settings.types());
            return new Scored(r, r.kit() && KitNames.usable(norm) && KitNames.validKey(norm) ? norm : null);
        }

        /** The classifier's result for this stack (a shulker box), for the report. */
        public KitClassifier.Result classification(Item item) {
            return scored(item).result();
        }

        /** Whether the stack is a kit by the classifier, whatever the settings route it to (or not). Scored once per stack. */
        public boolean isKit(Item item) {
            return item != null && item.isShulker() && scored(item).kit();
        }

        /** The box that holds this very stack, or null (a carried stack, or one not stored in the stash). */
        private @Nullable Box locate(Item item) {
            // One pass over the stash per view (a view is made again whenever the stash or the settings change), not one per stack asked about
            Map<Item, Box> index = where;
            if (index == null) {
                index = new IdentityHashMap<>();
                for (Box b : stash.boxes) {
                    Item[] contents = b.contents;
                    if (contents == null) continue;
                    for (Item i : contents) if (i != null) index.put(i, b);
                }
                where = index;
            }
            return index.get(item);
        }

        private Map<Item, Box> where;

        /** The boxes of a kit's chain that resolve, in order. */
        public List<Box> chainBoxes(Item item) {
            if (item == null || !item.isShulker() || settings.isEmpty()) return List.of();
            Scored s = scored(item);
            if (!s.kit()) return List.of();
            List<Box> out = new java.util.ArrayList<>();
            for (Level l : chain(s.key(), s.result(), settings)) {
                Box b = box(l.container());
                if (b != null) out.add(b);
            }
            return out;
        }

        public Route route(Item item) {
            if (item == null || !item.isShulker() || settings.isEmpty()) return NOT_KIT;
            Scored s = scored(item);
            if (!s.kit()) return NOT_KIT;
            List<Level> chain = chain(s.key(), s.result(), settings);
            if (chain.isEmpty()) return new Route(new Decision(Kind.NONE, s.key(), null), null);
            Box current = locate(item);
            int rank = -1;
            if (current != null) {
                for (int i = 0; i < chain.size() && rank < 0; i++) if (box(chain.get(i).container()) == current) rank = i;
            }
            Pick pick = pick(chain, id -> pickable(box(id)), id -> {
                Box b = box(id);
                return pickable(b) && b.free() > 0;
            }, rank);
            if (pick.level() < 0) return new Route(new Decision(pick.kind(), s.key(), chain.get(0).container()), null);
            String container = chain.get(pick.level()).container();
            return new Route(new Decision(pick.kind(), s.key(), container), box(container), pick.level() > 0 && !pick.stays());
        }

        /** Where a routed kit goes: the target alone, or nothing for MISSING. */
        public List<Box> destinations(Route route) {
            return route.box() != null && route.kind() != Kind.MISSING && route.kind() != Kind.FULL ? List.of(route.box()) : List.of();
        }
    }
}
