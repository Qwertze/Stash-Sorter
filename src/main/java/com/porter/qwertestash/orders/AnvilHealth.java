package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Whether the anvil is worth another trip in this order. An anvil that could not be reached or opened, that gave no result
 * in time, whose result never came back named, or that closed on the bot twice in a row is remembered as trouble for the
 * rest of the order and that anvil: later shulkers go unnamed at once, with the same reason, instead of each spending a
 * full trip on it. A new order or another anvil starts afresh. Bot tick thread only.
 */
public final class AnvilHealth {
    /** Closes in a row (each one may be tried again) after which the anvil counts as trouble. */
    public static final int CLOSED_IN_A_ROW = 2;

    private @Nullable String failure;
    private @Nullable String order;
    private @Nullable Object anvil;
    private int closed;
    private @Nullable String closedOrder;
    private @Nullable Object closedAnvil;

    /** Takes note of how a trip to this anvil for this order ended. */
    public void note(@Nullable String orderId, @Nullable Object anvilSpot, AnvilNamer.Outcome o) {
        if (!Objects.equals(orderId, closedOrder) || !Objects.equals(anvilSpot, closedAnvil)) {
            closed = 0;
            closedOrder = orderId;
            closedAnvil = anvilSpot;
        }
        String why = o.failure();
        if (o.named()) {
            closed = 0;
            return;
        }
        boolean trouble = o.anvilBad() || AnvilNamer.NO_RESULT.equals(why) || AnvilNamer.NOT_TAKEN.equals(why);
        if (AnvilNamer.CLOSED.equals(why)) trouble = ++closed >= CLOSED_IN_A_ROW;
        if (!trouble) return;
        failure = why;
        order = orderId;
        anvil = anvilSpot;
    }

    /** The anvil trouble remembered for this order and anvil, or null. */
    public @Nullable String problem(@Nullable String orderId, @Nullable Object anvilSpot) {
        return failure != null && Objects.equals(orderId, order) && Objects.equals(anvilSpot, anvil) ? failure : null;
    }
}
