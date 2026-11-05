package com.porter.qwertestash.orders;

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
    /** What the finished shulker holds, by item key, counted when it was first seen. */
    public Map<String, Integer> content = Map.of();

    public Unit(Kind kind, String key, List<Order.Lot> lots) {
        this.kind = kind;
        this.key = key;
        this.lots = lots;
    }
}
