package com.porter.qwertestash.pearl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * The {@code pearlTrip} object of a heartbeat reply (PEARL-CONTRACT v1.0 rev 6), read strictly: a field of the wrong type or shape
 * is as good as missing (a flag is false, a name is null), and a reply whose trip id or action is not valid is refused whole
 * ({@link #parse} returns null). The loader name and the pearl id are the only things that ever reach the chat, so they are held to the
 * shape of a player name and of a pearl id here, and nowhere else is a name taken from the network.
 */
public record PearlReply(String id, String action, String state, @Nullable String step, @Nullable String station, @Nullable String dim,
                         @Nullable String loader, @Nullable String pearlId, boolean oneWay, boolean go, boolean armHome, boolean retry,
                         boolean rearm, boolean hold, boolean cancel, int whispers, long pullDeadlineAt, @Nullable String code) {
    public static final Pattern TRIP = Pattern.compile("^t-[0-9a-f]{12}$");
    public static final Pattern STATION = Pattern.compile("^ps-[0-9a-f]{8}$");
    public static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    public static final Pattern PEARL_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");
    public static final Pattern DIM = Pattern.compile("^[a-z0-9_.-]{1,32}:[a-z0-9_./-]{1,64}$");
    private static final Pattern CODE = Pattern.compile("^[a-z_]{1,32}$");

    /** The trip of a reply, or null when there is none or it is not valid. */
    public static @Nullable PearlReply parse(@Nullable JsonElement e) {
        if (!(e instanceof JsonObject o)) return null;
        String id = text(o, "id", TRIP);
        String action = text(o, "action", Pattern.compile("^(go|back)$"));
        if (id == null || action == null) return null;
        String state = text(o, "state", Pattern.compile("^(pending|running|paused|completed|failed|cancelled)$"));
        if (state == null) return null; // a reply without a known state is refused whole, like one with a bad id
        if (state.equals("pending")) state = PearlTrip.RUNNING;
        return new PearlReply(id, action, state, text(o, "step", Pattern.compile("^[a-z_]{1,16}$")), text(o, "station", STATION), text(o, "dim", DIM),
            text(o, "loader", PLAYER), text(o, "pearlId", PEARL_ID), flag(o, "oneWay"), flag(o, "go"), flag(o, "armHome"), flag(o, "retry"),
            flag(o, "rearm"), flag(o, "hold"), flag(o, "cancel"), (int) whole(o, "whispers", 0, 2), whole(o, "pullDeadlineAt", 1, Long.MAX_VALUE / 4),
            text(o, "code", CODE));
    }

    /** The same reply without its retry flag. */
    public PearlReply withoutRetry() {
        return new PearlReply(id, action, state, step, station, dim, loader, pearlId, oneWay, go, armHome, false, rearm, hold, cancel, whispers, pullDeadlineAt, code);
    }

    private static @Nullable String text(JsonObject o, String f, Pattern p) {
        JsonElement e = o.get(f);
        if (!(e instanceof JsonPrimitive pr) || !pr.isString()) return null;
        String s = pr.getAsString();
        return s.length() <= 80 && p.matcher(s).matches() ? s : null;
    }

    private static boolean flag(JsonObject o, String f) {
        return o.get(f) instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
    }

    private static long whole(JsonObject o, String f, long min, long max) {
        if (!(o.get(f) instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 18) return 0;
        try {
            double d = p.getAsDouble();
            long v = (long) d;
            return d == v && v >= min && v <= max ? v : 0;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Whether a name is fit to be whispered to. */
    public static boolean validLoader(@Nullable String name) {
        return name != null && PLAYER.matcher(name).matches();
    }
}
