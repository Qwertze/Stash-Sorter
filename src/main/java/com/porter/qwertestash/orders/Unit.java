package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * One shulker of an order: an existing packed shulker to deliver as it is, an empty shulker to deliver as it is, or a bin
 * of slot loads to build. The counters bound how often each step is tried. Plain data, kept by {@link OrderFlow}.
 */
public final class Unit {
    public enum Kind { Shulker, Empty, Bin }

    public final Kind kind;
    /** For Shulker: the shulkers[] group key. For Empty: the item key of the empty shulker. Blank for a Bin. */
    public final String key;
    public final List<Order.Lot> lots;

    /** The finished shulker was seen in the inventory. */
    public boolean have;
    /** The bin was built (the finished shulker exists). */
    public boolean built;
    /** The delivery container was seen to hold it after it left the inventory. */
    public boolean confirmed;
    public boolean verified, verifying, replanned, trimmed;
    public int fullTries, starts, fetches, begins, base;
    /** Times delivery storage was asked to grow for it (1.9.0), bounded by OrderFlow.MAX_EXPANDS. */
    public int expands;
    /** Expand/Recheck steps handed to the host in a row (bounded by OrderFlow.MAX_SPINS: a host that can't start them gets a wait). */
    public int spins;
    /** What the finished shulker holds, by item key, counted when it was first seen. */
    public Map<String, Integer> content = Map.of();

    /** The name the order wants on this shulker (trimmed, as the order gave it), or null for none. */
    public @Nullable String name;
    /** The shulker carries its name now. */
    public boolean nameDone;
    /** Why it is delivered without its name, once naming was given up on (null while it may still be named). */
    public @Nullable String nameFailure;
    /** Times the anvil was tried for it. */
    public int nameTries;
    /**
     * What the renamed shulker is known by, seen once the anvil gave it back (its item key for an empty shulker, its group key
     * for a packed one): a custom name changes those, and the shulker must still be recognised as this unit's.
     */
    public @Nullable String namedKey;

    /** When the host was first asked to craft this bin's loads without having started (milliseconds, 0 = not waiting): a wait is bounded. */
    public long craftLoadsWaitSince;
    /** Rounds of crafting this bin's loads started (each makes one chunk): bounded so a host that never finishes one does not hold the order. */
    public int craftLoadRounds;

    /** The index of the first load of this bin that is still to be crafted (flagged {@link Order.Lot#craft()}), or -1. */
    public int nextCraftLot() {
        for (int i = 0; i < lots.size(); i++) if (lots.get(i).craft() && !lots.get(i).base()) return i;
        return -1;
    }

    /** The index of the first load of this bin that is an item still to be enchanted ({@link Order.Lot#enchant()}), or -1. */
    public int nextEnchantLot() {
        for (int i = 0; i < lots.size(); i++) if (lots.get(i).enchant()) return i;
        return -1;
    }

    /** Craft trips started for it (an empty shulker crafted), and why crafting was given up on (null while it may be tried). */
    public int craftTries;
    /** The same two counters for the bases made for enchant plans (ENCHANT-CONTRACT draft 3.4), which are made first and stall on their own. */
    public long baseLoadsWaitSince;
    public int baseLoadRounds;
    public @Nullable String craftFailure;

    /**
     * Repaired items of this bin (1.13.0): {key now, key asked for}. A repaired item's key differs from the planned one (its
     * damage is part of it); the shulker's content is counted back to the keys the order asked for.
     */
    public final List<String[]> rekeys = new java.util.ArrayList<>();

    /** The template this unit is a kit of (1.16.0, kit orders), or null for an ordinary unit. */
    public @Nullable String kitTemplate;
    /** A kit unit that is a complete copy fetched from the stash (Shulker kind), not built. */
    public boolean kitReady;
    /** A kit that can't be completed (planned or found out while building): never delivered; named and stored when it holds two kinds. */
    public boolean kitShort;
    /** The colour of shulker the template asks for (a colour name, "plain" or null for any). */
    public @Nullable String kitColor;
    /** The group key of an incomplete copy used as the base of this build instead of an empty shulker, or null. */
    public @Nullable String kitBase;
    /** What the kit lacks, when {@link #kitShort}. */
    public final List<KitResult.Miss> kitMissing = new java.util.ArrayList<>();
    /** How many loads this build gathers from the stash (not counting what the base copy holds already). */
    public int kitGathered;

    public Unit(Kind kind, String key, List<Order.Lot> lots) {
        this.kind = kind;
        this.key = key;
        this.lots = lots;
    }
}
