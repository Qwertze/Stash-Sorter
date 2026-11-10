package com.porter.qwertestash.stash;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * How many crafting tables the bot may put down for craft orders: one for an order and three in an hour. The counts are kept in the settings
 * file, so a restart does not start them over. Pure.
 */
public final class CraftTableCap {
    private CraftTableCap() {
    }

    public static final int PER_ORDER = 1, PER_HOUR = 3;
    public static final long HOUR_MS = 60 * 60 * 1000L;

    /** @param times when tables were put down (milliseconds); {@code orderId} the last order one was put down for and {@code orderCount} how many */
    public record State(List<Long> times, @Nullable String orderId, int orderCount) {
        public static State empty() {
            return new State(List.of(), null, 0);
        }
    }

    public static int lastHour(State s, long now) {
        int n = 0;
        for (long t : s.times()) if (now - t < HOUR_MS && now >= t) n++;
        return n;
    }

    public static int forOrder(State s, String order) {
        return order.equals(s.orderId()) ? s.orderCount() : 0;
    }

    public static boolean allowed(State s, String order, long now) {
        return forOrder(s, order) < PER_ORDER && lastHour(s, now) < PER_HOUR;
    }

    /** A table is put down for this order now. */
    public static State record(State s, String order, long now) {
        List<Long> times = new ArrayList<>();
        for (long t : s.times()) if (now - t < HOUR_MS && now >= t) times.add(t);
        times.add(now);
        return new State(times, order, forOrder(s, order) + 1);
    }
}
