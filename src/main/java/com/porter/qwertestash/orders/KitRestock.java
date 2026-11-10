package com.porter.qwertestash.orders;

import com.porter.qwertestash.stash.KitTemplate;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The decision of kit restock (KIT-CONTRACT v2, revision 4, "Restock"): which template to work on next while the bot is idle, and the state and reason
 * the kit report shows. Pure: the sorter makes the {@link Candidate}s from its containers and acts on the {@link Decision}. Per template with
 * {@code keep > 0}, counted in its chain target: a template with fewer complete copies than {@code keep} is topped up first when an incomplete copy
 * exists (across all templates, most missing first, then by id), else a new copy is built when complete + incomplete is below {@code keep}.
 * An incomplete copy never counts towards {@code keep}. Not wired to a job yet (see the changelog): the decision and its tests only.
 */
public final class KitRestock {
    private KitRestock() {
    }

    /** The reasons of {@code restock.reason} in the kit report. */
    public static final String ORDERS = "orders", ITEMS_SHORT = "items_short", BOTTLE_RESERVE = "bottle_reserve", NO_SHULKER = "no_shulker", CHEST_FULL = "chest_full",
        CHEST_MISSING = "chest_missing", CAP = "cap", NAMING = "naming", TOPPING_UP = "topping_up";

    public enum State { OFF, IDLE, BUILDING, WAITING, BLOCKED }

    /**
     * Whether a restock job may start: no order held or saved to resume, the first heartbeat reply of this connection heard (it says whether orders
     * wait), orders switched on, and the bot free to work.
     */
    public static boolean mayStart(boolean holdsOrder, boolean resumable, boolean heard, boolean ordersOn, boolean paused) {
        return !holdsOrder && !resumable && heard && ordersOn && !paused;
    }

    /** Whether a running restock job must stop now: an order waits, restock or kit orders or orders are switched off, or the bot cannot work. */
    public static boolean mustStop(boolean ordersWaiting, boolean restockOn, boolean kitOrdersOn, boolean ordersOn, boolean paused) {
        return ordersWaiting || !restockOn || !kitOrdersOn || !ordersOn || paused;
    }

    /** Bottles kept in the stash when a template holds experience bottles (REPAIR-CONTRACT v2: one shulker of 27 stacks). */
    public static final int BOTTLE_RESERVE_COUNT = 1728;
    public static final String BOTTLE = "minecraft:experience_bottle";

    /**
     * What a restock job may take for one copy of a template: the totals of its slots by match key (any-variant slots by {@code <id>#*}), with
     * experience bottles cut to {@code bottlesUsable} (the stash's bottles less the reserve).
     */
    public static List<KitOrder.Take> takeFor(KitTemplate t, long bottlesUsable) {
        java.util.Map<String, Integer> totals = new java.util.LinkedHashMap<>();
        for (KitTemplate.Slot s : t.slots()) {
            if (s == null) continue;
            totals.merge(s.any() ? s.base() + "#*" : s.match(), s.count(), Integer::sum);
        }
        List<KitOrder.Take> out = new ArrayList<>();
        for (var e : totals.entrySet()) {
            int n = e.getValue();
            if (KitTemplate.baseOf(e.getKey()).equals(BOTTLE)) n = (int) Math.max(0, Math.min(n, bottlesUsable));
            if (n > 0) out.add(new KitOrder.Take(e.getKey(), n));
        }
        return out;
    }

    /** The most templates one restock decision looks at. */
    public static final int MAX_CANDIDATES = 16;

    /** The templates a restock decision looks at: those with a keep count, by id, the first {@link #MAX_CANDIDATES}; the rest are not restocked. */
    public static List<KitTemplate> candidates(List<KitTemplate> all) {
        List<KitTemplate> keepers = new ArrayList<>();
        for (KitTemplate t : all) if (t.keep() > 0) keepers.add(t);
        keepers.sort(Comparator.comparing(KitTemplate::id));
        return keepers.size() > MAX_CANDIDATES ? new ArrayList<>(keepers.subList(0, MAX_CANDIDATES)) : keepers;
    }

    /** The unreserved bottles: what the stash holds less the reserve, never negative. */
    public static long bottlesUsable(long inStash) {
        return Math.max(0, inStash - BOTTLE_RESERVE_COUNT);
    }

    /**
     * One template with a keep count. {@code target}: its chain target resolved (null: none set); {@code targetUsable}: it can take kits (room for a new copy);
     * {@code topUpPossible}: an incomplete copy exists and at least one missing item is available from unreserved stock; {@code buildPossible}: a new
     * copy can be filled completely from unreserved stock; {@code buildPartial}: it can be filled with two kinds or more; {@code bottleBlocked}: what
     * is missing is experience bottles and taking them would go below the repair reserve.
     */
    public record Candidate(KitTemplate template, @Nullable String target, boolean targetPickable, boolean targetFree, int complete, int incomplete,
                            boolean topUpPossible, boolean buildPossible, boolean buildPartial, boolean bottleBlocked) {
        int keep() {
            return template.keep();
        }

        int missing() {
            return Math.max(0, keep() - complete);
        }
    }

    /**
     * @param enabled          kit restock is on (and kit orders are possible)
     * @param orderBusy        an order is held, or the last heartbeat reply had orders pending
     * @param builtLastHour    kits restock made in the last hour, and the cap per hour
     * @param emptiesAfter     empty shulkers that would remain after taking one
     * @param naming           an anvil is available (without one a copy is built unnamed, which still matches, but is reported)
     */
    public record Inputs(boolean enabled, boolean orderBusy, int builtLastHour, int perHourCap, int emptiesAfter, int minEmpty, boolean naming, List<Candidate> candidates) {
    }

    /** @param topUp the job tops up the fullest incomplete copy of the template; otherwise it builds a new copy */
    public record Decision(State state, @Nullable String template, @Nullable String reason, boolean topUp) {
        static Decision of(State s, @Nullable String template, @Nullable String reason) {
            return new Decision(s, template, reason, false);
        }
    }

    public static Decision decide(Inputs in) {
        if (!in.enabled()) return Decision.of(State.OFF, null, null);
        List<Candidate> list = new ArrayList<>();
        for (Candidate c : in.candidates()) if (c.keep() > 0 && c.complete() < c.keep()) list.add(c);
        if (list.isEmpty()) return Decision.of(State.IDLE, null, null);
        list.sort(Comparator.comparingInt((Candidate c) -> -c.missing()).thenComparing(c -> c.template().id()));
        Candidate first = list.get(0);
        // Orders come first, and the cap per hour
        if (in.orderBusy()) return Decision.of(State.WAITING, first.template().id(), ORDERS);
        if (in.perHourCap() <= 0 || in.builtLastHour() >= in.perHourCap()) return Decision.of(State.WAITING, first.template().id(), CAP);

        // Top-ups first, across all templates
        for (Candidate c : list) {
            if (c.target() == null || !c.targetPickable()) continue;
            if (c.incomplete() > 0 && c.topUpPossible()) return new Decision(State.BUILDING, c.template().id(), TOPPING_UP, true);
        }
        // Then new copies, the first that can be done; else the first template's reason
        Decision firstReason = null;
        for (Candidate c : list) {
            Decision d = newCopy(in, c);
            if (d.state() == State.BUILDING) return d;
            if (firstReason == null) firstReason = d;
        }
        return firstReason == null ? Decision.of(State.IDLE, null, null) : firstReason;
    }

    private static Decision newCopy(Inputs in, Candidate c) {
        String id = c.template().id();
        if (c.target() == null || !c.targetPickable()) return Decision.of(State.BLOCKED, id, CHEST_MISSING);
        // Every complete or incomplete copy counts towards "how many to build"; a template with an incomplete copy but nothing to top it up with waits
        if (c.complete() + c.incomplete() >= c.keep()) return Decision.of(State.WAITING, id, c.incomplete() > 0 ? ITEMS_SHORT : null);
        if (!c.targetFree()) return Decision.of(State.BLOCKED, id, CHEST_FULL);
        if (in.emptiesAfter() < in.minEmpty()) return Decision.of(State.WAITING, id, NO_SHULKER);
        if (!c.buildPossible() && !c.buildPartial()) return Decision.of(State.WAITING, id, c.bottleBlocked() ? BOTTLE_RESERVE : ITEMS_SHORT);
        if (!in.naming()) return new Decision(State.BUILDING, id, NAMING, false);
        return Decision.of(State.BUILDING, id, null);
    }
}
