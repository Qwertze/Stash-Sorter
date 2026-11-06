package com.porter.qwertestash.orders;

import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which containers an order may take from, one rule for the plan, the replan ({@code trim}) and the run. Until 1.7.1 the
 * plan only skipped what never changes (the delivery container, excluded, drop-offs, unread, kit chests) while the run also
 * skipped containers it had given up on for now (stalled, a sign being changed, a trip that came back empty, a source
 * shulker that couldn't be taken): the plan counted stock the run would never touch, the shulker was given up as "not
 * enough left to pack", and the replan, made from the same survey, failed the same way.
 * <p>
 * Plain data in, plain data out: what the sorter knows of the moment comes in through {@link Live}.
 */
public final class SourceRules {
    private SourceRules() {
    }

    /** Why a container is not a source right now. */
    public enum Reject {
        /** For good, as far as this order goes: delivery, excluded, drop-off, unreachable, unread, kit chest. */
        OTHER("other"),
        /** Saved contents not read again since the bot came online. */
        STALE("stale"),
        /** Its sign is being changed. */
        SIGN_PENDING("signPending"),
        /** Stalled, its sign held, or the same trip came back empty and nothing has changed since. */
        BLOCKED("blocked"),
        /** A shulker in it could not be taken out. */
        BAD_UNPACK("badUnpack");

        public final String label;

        Reject(String label) {
            this.label = label;
        }
    }

    /** What the sorter knows now about a container. */
    public interface Live {
        /** Why the order may never take from it, or null. */
        @Nullable String skip(Box box);

        boolean signPending(Box box);

        /** Not worth a trip to take loose stacks (blocked for Job.Gather). */
        boolean blockedGather(Box box);

        /** Not worth a trip to fetch a shulker (blocked for Job.Fetch). */
        boolean blockedFetch(Box box);

        /** A source shulker in it could not be taken this run. */
        boolean badUnpack(Box box);
    }

    /** Why loose stacks can't be taken from this container now, or null when they can. */
    public static @Nullable Reject loose(Box box, Live live) {
        if (live.skip(box) != null) return Reject.OTHER;
        if (box.stale) return Reject.STALE;
        if (live.signPending(box)) return Reject.SIGN_PENDING;
        if (live.blockedGather(box)) return Reject.BLOCKED;
        return null;
    }

    /** Why a shulker can't be fetched from this container now to take items out of it, or null when it can. */
    public static @Nullable Reject unpack(Box box, Live live) {
        if (live.skip(box) != null) return Reject.OTHER;
        if (box.stale) return Reject.STALE;
        if (live.signPending(box)) return Reject.SIGN_PENDING;
        if (live.blockedFetch(box)) return Reject.BLOCKED;
        if (live.badUnpack(box)) return Reject.BAD_UNPACK;
        return null;
    }

    /** The note for the order's notes about stock skipped for a reason that passes. */
    public static String note(Reject r) {
        return switch (r) {
            case OTHER -> "in a container the order can't use";
            case STALE -> "in a container not read again yet";
            case SIGN_PENDING -> "in a container whose sign is being changed";
            case BLOCKED -> "in a container given up on for now";
            case BAD_UNPACK -> "in a shulker that couldn't be taken out";
        };
    }

    public static boolean looseStack(@Nullable Item it) {
        return it != null && !it.isShulker() && !it.isFilledBundle() && it.contents.isEmpty();
    }

    /** A shulker an order may unpack for another line: named ones only when allowed, never one the order asks for whole. */
    public static boolean usableShulker(Item it, boolean useNamedShulkers, Set<String> reserved) {
        return it.isShulker() && (useNamedShulkers || !it.customName) && !reserved.contains(ManagerIndex.shulkerKey(it));
    }

    /**
     * What the stash can give an order now: loose stacks first, then what lies inside shulkers, each only from containers
     * the run would take it from.
     * @param reserved the group keys of packed shulkers the order asks for whole (not unpacked for anything else)
     */
    public static StockPlanner.Survey survey(List<Box> boxes, Set<String> wanted, Set<String> reserved, boolean useNamedShulkers, Live live) {
        Map<String, Long> available = new HashMap<>();
        Map<String, Integer> maxStack = new HashMap<>();
        Set<String> filled = new HashSet<>(), staleSeen = new HashSet<>();
        Map<String, String> why = new HashMap<>();
        Map<String, Map<String, StockPlanner.Var>> variants = new HashMap<>();
        Map<String, Long> inShulkers = new HashMap<>();
        Map<String, Map<String, StockPlanner.Var>> shulkerVariants = new HashMap<>();
        for (Box box : boxes) {
            if (box.contents == null) continue;
            String skip = live.skip(box);
            // Only worked out when the box holds something wanted
            Reject looseWhy = null, unpackWhy = null;
            boolean looseKnown = false, unpackKnown = false;
            Set<String> seenHere = new HashSet<>();
            for (Item it : box.contents) {
                if (it == null) continue;
                String key = it.indexKey();
                if (wanted.contains(key)) {
                    if (!looseKnown) {
                        looseWhy = loose(box, live);
                        looseKnown = true;
                    }
                    if (skip != null) why.putIfAbsent(key, skip);
                    else if (it.isFilledBundle() || !it.contents.isEmpty()) filled.add(key);
                    else if (box.stale) seenHere.add(key);
                    else if (looseWhy != null) why.putIfAbsent(key, note(looseWhy));
                    else {
                        available.merge(key, (long) it.count, Long::sum);
                        maxStack.putIfAbsent(key, Math.max(1, it.maxStack));
                        variants.computeIfAbsent(key, k -> new HashMap<>()).computeIfAbsent(it.variant == null ? key : it.variant, v -> new StockPlanner.Var(v, 0)).count += it.count;
                    }
                }
                // What lies inside shulkers is a second source (loose stacks come first). A packed shulker the order asks for whole
                // is not unpacked for another line, and a named one only when named shulkers may be used
                if (it.isShulker()) {
                    boolean usable = skip == null && usableShulker(it, useNamedShulkers, reserved);
                    for (Item in : it.contents) {
                        if (in == null || !wanted.contains(in.indexKey())) continue;
                        String k = in.indexKey();
                        if (!unpackKnown) {
                            unpackWhy = unpack(box, live);
                            unpackKnown = true;
                        }
                        if (skip != null) why.putIfAbsent(k, skip);
                        else if (!usable) why.putIfAbsent(k, it.customName && !useNamedShulkers ? "in a named shulker (use-named-shulkers is off)" : "in a shulker the order asks for whole");
                        else if (box.stale) seenHere.add(k);
                        else if (in.isFilledBundle() || !in.contents.isEmpty()) filled.add(k);
                        else if (unpackWhy != null) why.putIfAbsent(k, note(unpackWhy));
                        else {
                            inShulkers.merge(k, (long) in.count, Long::sum);
                            maxStack.putIfAbsent(k, Math.max(1, in.maxStack));
                            shulkerVariants.computeIfAbsent(k, kk -> new HashMap<>()).computeIfAbsent(in.variant == null ? k : in.variant, v -> new StockPlanner.Var(v, 0)).count += in.count;
                        }
                    }
                }
            }
            staleSeen.addAll(seenHere);
        }
        staleSeen.removeAll(available.keySet());
        staleSeen.removeAll(inShulkers.keySet());
        return new StockPlanner.Survey(available, maxStack, filled, staleSeen, sorted(variants), why, inShulkers, sorted(shulkerVariants));
    }

    private static Map<String, List<StockPlanner.Var>> sorted(Map<String, Map<String, StockPlanner.Var>> variants) {
        Map<String, List<StockPlanner.Var>> sorted = new HashMap<>();
        for (var e : variants.entrySet()) {
            List<StockPlanner.Var> list = new ArrayList<>(e.getValue().values());
            list.sort((a, b) -> Long.compare(b.count, a.count));
            sorted.put(e.getKey(), list);
        }
        return sorted;
    }

    /**
     * The containers that hold this key (loose, or inside a shulker an order may unpack) but can't be taken from now, by
     * reason: one reason per container, its loose stacks' if it has any.
     */
    public static Map<Reject, Integer> rejected(List<Box> boxes, String key, Set<String> reserved, boolean useNamedShulkers, Live live) {
        Map<Reject, Integer> out = new EnumMap<>(Reject.class);
        for (Box box : boxes) {
            if (box.contents == null) continue;
            boolean hasLoose = false, hasInside = false;
            for (Item it : box.contents) {
                if (looseStack(it) && it.indexKey().equals(key)) hasLoose = true;
                else if (it != null && usableShulker(it, useNamedShulkers, reserved)) {
                    for (Item in : it.contents) if (looseStack(in) && in.indexKey().equals(key)) hasInside = true;
                }
            }
            Reject r = hasLoose ? loose(box, live) : hasInside ? unpack(box, live) : null;
            // A container with loose stacks the run may take is no rejection, whatever its shulkers are
            if (r != null) out.merge(r, 1, Integer::sum);
        }
        return out;
    }

    /** "2 stale, 1 blocked", or "none" when nothing was turned down. */
    public static String describe(Map<Reject, Integer> rejected) {
        List<String> parts = new ArrayList<>();
        for (var e : rejected.entrySet()) parts.add(e.getValue() + " " + e.getKey().label);
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    // Crafting inputs (1.8.0)

    /**
     * Why this stack may never be a crafting input, or null when it may: damaged, a shulker box (never an ingredient), a
     * stack that holds something (a filled bundle, a container item with contents or block-entity data: crafting with it
     * would destroy what is inside), or an item on the never-use list. Named and enchanted stacks may be used.
     */
    public static @Nullable String craftRefusal(@Nullable Item it, Set<String> neverUse) {
        if (it == null) return "empty";
        if (it.isShulker()) return "shulker boxes are never used to craft";
        if (it.damaged) return "damaged";
        if (it.isFilledBundle() || !it.contents.isEmpty() || it.blockData) return "it holds something";
        if (neverUse.contains(it.id) || neverUse.contains("minecraft:" + it.id)) return "on the never-use list";
        return null;
    }

    /**
     * What crafting may take now, by index key, from the containers an order may take from by the same rules as
     * {@link #survey}: loose stacks, and what lies inside shulkers an order may unpack.
     * @param baseIds the item ids (namespaced) crafting could use
     */
    public static CraftPool.Stock craftSurvey(List<Box> boxes, Set<String> baseIds, Set<String> reserved, boolean useNamedShulkers, Set<String> neverUse, Live live) {
        Map<String, Long> loose = new HashMap<>(), inside = new HashMap<>();
        Map<String, String> baseOf = new HashMap<>();
        Map<String, Integer> maxStack = new HashMap<>();
        for (Box box : boxes) {
            if (box.contents == null || live.skip(box) != null) continue;
            Reject looseWhy = null, unpackWhy = null;
            boolean looseKnown = false, unpackKnown = false;
            for (Item it : box.contents) {
                if (it == null) continue;
                if (baseIds.contains("minecraft:" + it.id) && craftRefusal(it, neverUse) == null) {
                    if (!looseKnown) {
                        looseWhy = loose(box, live);
                        looseKnown = true;
                    }
                    if (looseWhy == null) {
                        loose.merge(it.indexKey(), (long) it.count, Long::sum);
                        baseOf.put(it.indexKey(), "minecraft:" + it.id);
                        maxStack.putIfAbsent(it.indexKey(), Math.max(1, it.maxStack));
                    }
                }
                if (it.isShulker() && usableShulker(it, useNamedShulkers, reserved)) {
                    for (Item in : it.contents) {
                        if (in == null || !baseIds.contains("minecraft:" + in.id) || craftRefusal(in, neverUse) != null) continue;
                        if (!unpackKnown) {
                            unpackWhy = unpack(box, live);
                            unpackKnown = true;
                        }
                        if (unpackWhy != null) continue;
                        inside.merge(in.indexKey(), (long) in.count, Long::sum);
                        baseOf.put(in.indexKey(), "minecraft:" + in.id);
                        maxStack.putIfAbsent(in.indexKey(), Math.max(1, in.maxStack));
                    }
                }
            }
        }
        return new CraftPool.Stock(loose, inside, baseOf, maxStack);
    }
}
