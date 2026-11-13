package com.porter.qwertestash.orders;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.SafeJson;

import java.util.function.LongSupplier;

/**
 * What the Stash Manager server last told the bot about the bottles that open enchant orders reserve (ENCHANT-CONTRACT v1.0, TL4-B1): the heartbeat reply's
 * {@code enchant.bottlesReserved}, the total over every open enchant order of the stash. Repair passes pass it as {@code heldOrderBottles} to
 * {@link RepairRules#usable}, so a repair can't spend bottles an order holds. An older server sends none (0); a value older than {@link #MAX_AGE_MS} is not
 * believed. Thread-safe: written when a heartbeat reply is read, read on the tick thread.
 */
public final class EnchantState {
    /** How long a reply's figure is used when no newer one came (a few missed heartbeats). */
    public static final long MAX_AGE_MS = 5 * 60 * 1000L;
    private static final long MAX_BOTTLES = 1_000_000L;

    private final LongSupplier clock;
    private volatile long reserved;
    private volatile long at = Long.MIN_VALUE / 4;

    public EnchantState(LongSupplier clock) {
        this.clock = clock;
    }

    public static final EnchantState SHARED = new EnchantState(System::currentTimeMillis);

    /** Reads {@code enchant.bottlesReserved} from a heartbeat reply body: -1 when there is none (an older server, a bot that is neither capable nor repair-capable). */
    public static long parse(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return -1;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("enchant") || !o.get("enchant").isJsonObject()) return -1;
            JsonObject en = o.getAsJsonObject("enchant");
            if (!en.has("bottlesReserved") || !en.get("bottlesReserved").isJsonPrimitive()) return -1;
            long n = en.get("bottlesReserved").getAsLong();
            return n < 0 || n > MAX_BOTTLES ? -1 : n;
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    /** A heartbeat reply came: its figure replaces the old one, a reply without one clears it (the server no longer holds any, or is older). */
    public void onHeartbeat(String body) {
        long n = parse(body);
        reserved = Math.max(0, n);
        at = clock.getAsLong();
    }

    /** The bottles open enchant orders reserve, 0 when unknown or stale. */
    public long bottlesReserved() {
        return clock.getAsLong() - at > MAX_AGE_MS ? 0 : reserved;
    }
}
