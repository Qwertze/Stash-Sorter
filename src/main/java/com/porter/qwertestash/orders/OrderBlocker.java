package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

/**
 * The precise reason the bot cannot take an order right now, or null when it can. Sent in every heartbeat as
 * {@code orderBlocker} (at most 100 characters). Pure: the plugin fills a {@link Snapshot} from the game and asks. The
 * checks run in the order of the list in the record, so the most basic reason wins.
 */
public final class OrderBlocker {
    private OrderBlocker() {
    }

    /** The furthest the delivery container may be from the stash (blocks). */
    public static final int MAX_DELIVERY_DISTANCE = 300;

    /**
     * @param acceptOrders     the setting
     * @param deliverySet      a delivery container is set
     * @param inGame           the bot is online and alive in the world
     * @param inQueue          the bot is in the 2b2t queue
     * @param playerControlled a player is controlling the bot
     * @param hasStash         a stash is loaded
     * @param deliveryDimension the dimension the delivery container is in (null: unknown)
     * @param currentDimension the dimension the bot is in (null: unknown)
     * @param deliveryDistance blocks between the delivery container and the stash (or the bot when the stash has no area), or -1 if unknown
     * @param busyWith         what the bot is doing that an order must wait for ("an order", "finishing a shulker"), or null
     * @param freeCarrySlots   free inventory slots the bot may carry in
     * @param shulkersKnown    the stash holds, or the bot carries, a shulker box
     * @param stashRead        the stash was read at least once (an unread stash says nothing about shulkers)
     */
    public record Snapshot(boolean acceptOrders, boolean deliverySet, boolean inGame, boolean inQueue, boolean playerControlled, boolean hasStash,
                           @Nullable String deliveryDimension, @Nullable String currentDimension, double deliveryDistance,
                           @Nullable String busyWith, int freeCarrySlots, boolean shulkersKnown, boolean stashRead) {
    }

    public static @Nullable String compute(Snapshot s) {
        if (!s.acceptOrders()) return "orders off";
        if (!s.deliverySet()) return "no delivery container set";
        if (s.inQueue()) return "in the queue";
        if (!s.inGame()) return "bot not in game";
        if (s.playerControlled()) return "a player is controlling the bot";
        if (!s.hasStash()) return "no stash loaded";
        if (s.deliveryDimension() != null && s.currentDimension() != null && !sameDimension(s.deliveryDimension(), s.currentDimension())) {
            return "wrong dimension: delivery container is in " + s.deliveryDimension();
        }
        if (s.deliveryDistance() > MAX_DELIVERY_DISTANCE) return "delivery container too far from the stash (" + Math.round(s.deliveryDistance()) + " blocks)";
        if (s.busyWith() != null) return "busy: " + s.busyWith();
        if (s.freeCarrySlots() <= 0) return "inventory full";
        if (s.stashRead() && !s.shulkersKnown()) return "no shulkers in the stash";
        return null;
    }

    /** "minecraft:overworld" and "overworld" are the same dimension. */
    public static boolean sameDimension(@Nullable String a, @Nullable String b) {
        if (a == null || b == null) return false;
        return strip(a).equalsIgnoreCase(strip(b));
    }

    private static String strip(String d) {
        String t = d.trim();
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }
}
