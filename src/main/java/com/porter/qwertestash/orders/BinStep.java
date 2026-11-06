package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The next step of building an order shulker once what is carried has been counted, kept apart from the world so it can
 * be tested. Until 1.7.1 too few free carry slots to pick up a source shulker ended the shulker as "not enough left to
 * pack", however much was left in the stash: now what is carried and not part of the shulker is put away first.
 */
public final class BinStep {
    private BinStep() {
    }

    public enum Next {
        /** Back to the shulker with the carried stacks. */
        PACK,
        /** Take the next loads from a container. */
        GATHER,
        /** Fetch (or open a carried) shulker that holds the next load. */
        UNPACK,
        /** Put away carried stacks that are not part of the shulker, then carry on. */
        PUT_AWAY,
        /** Too little carry room, and nothing can be put away to make more. */
        NO_ROOM,
        /** Nothing the run may take from is left for the next load. */
        NO_SOURCE
    }

    /**
     * @param remaining    loads still to go into the shulker
     * @param carriedMatch carried stacks ready to go in
     * @param free         free carry slots
     * @param looseSource  a container with a loose stack for the next load may be taken from
     * @param shulkerSource a shulker (carried or in the stash) holds the next load
     * @param canPutAway   there is something carried that is not part of the shulker, somewhere to put it, and tries left
     */
    public static Next next(int remaining, int carriedMatch, int free, boolean looseSource, boolean shulkerSource, boolean canPutAway) {
        if (carriedMatch >= remaining || (carriedMatch > 0 && free <= 1)) return Next.PACK;
        if (!looseSource) {
            if (carriedMatch > 0) return Next.PACK;
            if (!shulkerSource) return Next.NO_SOURCE;
            // One slot for the shulker, one kept free for picking it up again
            if (free >= 2) return Next.UNPACK;
            return canPutAway ? Next.PUT_AWAY : Next.NO_ROOM;
        }
        // However much room there is, in rounds; one slot stays free
        if (batch(remaining, carriedMatch, free) > 0) return Next.GATHER;
        return canPutAway ? Next.PUT_AWAY : Next.NO_ROOM;
    }

    /** How many loads one gathering trip takes. */
    public static int batch(int remaining, int carriedMatch, int free) {
        return Math.min(remaining - carriedMatch, free - 1);
    }

    /** The loads of a bin as its shulker holds them now: a shulker delivered short is the unit's by exactly these. */
    public static List<Order.Lot> lotsOf(List<BinTracker.Held> packed) {
        List<Order.Lot> out = new ArrayList<>();
        for (BinTracker.Held h : packed) out.add(new Order.Lot(h.key(), h.variant(), h.count()));
        return out;
    }

    /**
     * Whether a carried shulker is the bin's own, part filled: it holds something, and nothing that isn't (part of) a load.
     */
    public static boolean partOf(List<Order.Lot> lots, List<BinTracker.Held> contents) {
        if (contents.isEmpty()) return false;
        for (BinTracker.Held h : contents) if (!BinTracker.owns(lots, h.key(), h.variant(), h.count())) return false;
        // Never more of an item than the loads ask for
        var want = BinTracker.lotTotals(lots);
        for (var e : BinTracker.totals(contents).entrySet()) if (e.getValue() > want.getOrDefault(e.getKey(), 0)) return false;
        return true;
    }

    /** A shulker carried now, as the short-delivery check sees it. */
    public record Carried(String id, boolean named, boolean sourceShulker, List<BinTracker.Held> inside) {
    }

    /**
     * What the bin's own shulker holds, picked from the carried shulkers, or null when it can't be told for sure (then the
     * bin is not delivered short; it is planned again or skipped as before). Only a bin whose shulker was empty when first
     * put down, whose colour is known, and that has something packed qualifies; the shulker must be that colour, unnamed,
     * not a source shulker, and hold exactly the item totals last seen packed in it. A partly emptied source shulker (after a
     * restart nothing marks it as one) can't pass that.
     */
    public static @Nullable List<BinTracker.Held> ownShulker(List<Order.Lot> lots, List<BinTracker.Held> lastPacked, boolean startedEmpty,
                                                            @Nullable String ownId, List<Carried> carried) {
        if (!startedEmpty || ownId == null || lastPacked.isEmpty()) return null;
        var want = BinTracker.totals(lastPacked);
        for (Carried c : carried) {
            if (c.named() || c.sourceShulker() || !ownId.equals(c.id())) continue;
            if (partOf(lots, c.inside()) && BinTracker.totals(c.inside()).equals(want)) return c.inside();
        }
        return null;
    }

    /** What to do about a load nothing may be taken for (1.8.0). */
    public enum Missing {
        /** The containers holding it were only given up on for now by the sorter itself: look at them again. */
        LOOK_AGAIN,
        /** Leave the load out and go on with the rest of the shulker. */
        LEAVE_OUT
    }

    /**
     * @param rejected  the containers holding the load that were turned down, by why
     * @param lookedAgain the order's blocks were cleared for this already, once this order plan
     */
    public static Missing missing(java.util.Map<SourceRules.Reject, Integer> rejected, boolean lookedAgain) {
        boolean selfOnly = !rejected.isEmpty() && rejected.keySet().stream().allMatch(r -> r == SourceRules.Reject.BLOCKED || r == SourceRules.Reject.BAD_UNPACK);
        return selfOnly && !lookedAgain ? Missing.LOOK_AGAIN : Missing.LEAVE_OUT;
    }

    /**
     * Where a source shulker emptied for an order goes back: where it came from when it may still go there and belongs
     * there; else where the sorter would put it; else any other container with room (null: none).
     */
    public static <B> @Nullable B spentHome(@Nullable B origin, boolean originUsable, boolean misplacedAtOrigin, @Nullable B belongs, @Nullable B other) {
        if (origin != null && originUsable && !misplacedAtOrigin) return origin;
        return belongs != null ? belongs : other;
    }
}
