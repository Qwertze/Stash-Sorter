package com.porter.qwertestash.pearl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The loader report of PEARL-CONTRACT v1.0 rev 6 ("The loader report"): built from a {@link Snapshot} of the loader bot and the server's
 * {@code allow} list, with {@link #check} the same shape rules the server applies (so a body the server would refuse with 400 is never sent).
 * <p>
 * What never leaves: a pearl whose owner is not on the list, or is listed with another uuid than the one pinned; no coordinates exist in the
 * shape at all. A pearl of a listed but unpinned name is reported with an unknown owner (never loadable). Presence is {@code armed} only when the
 * pearl entity is seen; "not seen" is {@code empty} only when the bot has been in the world, the owner has been in the tab list and the chamber
 * has been in range for the grace period (an owner who rejoined may not have their pearl back yet), {@code owner_offline} when the owner is not in
 * the tab list, and {@code unknown} otherwise. Pure.
 */
public final class PearlReport {
    private PearlReport() {
    }

    public static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    public static final Pattern PEARL_ID = Pattern.compile("^[A-Za-z0-9_.-]{1,32}$");
    public static final Set<String> PRESENCE = Set.of("armed", "empty", "owner_offline", "unknown");
    public static final Set<String> LOAD_STATES = Set.of("queued", "loading", "loaded", "aborted");
    public static final Set<String> ABORT_REASONS = Set.of("bot_offline", "busy", "path_failed", "timeout", "not_authorised", "disconnected", "player_active",
        "wrong_dimension", "no_such_pearl", "reopen_failed");
    public static final int MAX_CHAMBERS = 32;

    /**
     * One pearl stored in PearlPlus.
     * @param owner            the uuid PearlPlus keyed the pearl by (the game server's)
     * @param present          the pearl entity is seen in its column now and the bot is in range of it
     * @param inRange          the bot is within presence range of the chamber (so "not seen" means something)
     * @param dimensionKnown   the bot is in the dimension the chamber was last seen armed in (else nothing can be said)
     * @param ownerOnline      the owner is in the tab list
     * @param ownerOnlineSince when the owner entered the tab list (0: not known)
     */
    public record Pearl(UUID owner, @Nullable String ownerName, String id, boolean present, boolean inRange, boolean dimensionKnown, boolean ownerOnline,
                        long ownerOnlineSince, long unseenForMs) {
        /** A pearl that has not been seen for long enough to be believed gone. */
        public Pearl(UUID owner, @Nullable String ownerName, String id, boolean present, boolean inRange, boolean dimensionKnown, boolean ownerOnline,
                     long ownerOnlineSince) {
            this(owner, ownerName, id, present, inRange, dimensionKnown, ownerOnline, ownerOnlineSince, Long.MAX_VALUE / 4);
        }
    }

    /** "Not seen" must have lasted this long before it is called empty (a pearl entity can flicker while chunks load). */
    public static final long UNSEEN_MS = 5_000L;

    /**
     * @param connected   the bot is in the game (not in the queue)
     * @param inGameSince when it entered the game this time (0: not in the game)
     * @param graceMs     the grace period before "not seen" is believed
     */
    public record Snapshot(String player, long now, boolean dropPearlAfterLoad, boolean temporaryMode, boolean connected, boolean inQueue, long inGameSince,
                           long graceMs, boolean busy, boolean playerNear, List<Pearl> pearls) {
    }

    /** The presence word of one pearl. */
    public static String presence(Pearl p, Snapshot s) {
        if (p.present()) return "armed";
        if (!s.connected() || s.inQueue() || s.inGameSince() <= 0 || s.now() - s.inGameSince() < s.graceMs()) return "unknown";
        if (!p.inRange() || !p.dimensionKnown()) return "unknown";
        if (!p.ownerOnline()) return "owner_offline";
        if (p.unseenForMs() < UNSEEN_MS) return "unknown";
        if (p.ownerOnlineSince() <= 0 || s.now() - p.ownerOnlineSince() < s.graceMs()) return "unknown";
        return "empty";
    }

    /** The chambers to report: only listed travellers with the pinned uuid (or listed and unpinned, as owner unknown). {@code since} gives the time a presence began. */
    public static JsonArray chambers(Snapshot s, List<LoadAuth.Allowed> allow, java.util.function.ToLongFunction<String> since) {
        JsonArray out = new JsonArray();
        for (Pearl p : s.pearls()) {
            if (out.size() >= MAX_CHAMBERS) break;
            if (!PEARL_ID.matcher(p.id()).matches() || p.ownerName() == null || !PLAYER.matcher(p.ownerName()).matches()) continue;
            boolean known = LoadAuth.permits(allow, p.ownerName(), p.owner());
            if (!known && !LoadAuth.unpinned(allow, p.ownerName())) continue;
            JsonObject c = new JsonObject();
            c.addProperty("id", p.id());
            if (known) c.addProperty("owner", p.ownerName());
            else c.add("owner", JsonNull.INSTANCE);
            c.addProperty("ownerKnown", known);
            String presence = presence(p, s);
            // An unpinned owner's pearl can't be told apart from a stranger's: it is only ever "armed" or "unknown"
            if (!known && !presence.equals("armed")) presence = "unknown";
            c.addProperty("presence", presence);
            long t = since.applyAsLong(p.id() + "|" + presence);
            if (t > 0) c.addProperty("since", t);
            else c.add("since", JsonNull.INSTANCE);
            out.add(c);
        }
        return out;
    }

    /** The whole body for {@code POST /api/v1/bot/pearls} (without botId) or the heartbeat's {@code pearlLoader}. */
    public static JsonObject body(Snapshot s, JsonArray chambers, @Nullable JsonObject load) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("player", s.player());
        o.addProperty("at", s.now());
        o.addProperty("dropPearlAfterLoad", s.dropPearlAfterLoad());
        o.addProperty("temporaryMode", s.temporaryMode());
        o.addProperty("busy", s.busy());
        o.addProperty("playerNear", s.playerNear());
        o.add("chambers", chambers);
        if (load == null) o.add("load", JsonNull.INSTANCE);
        else o.add("load", load);
        return o;
    }

    /** The server's own rules for a report (PearlBodies.loader): null when the body is acceptable, else why not. */
    public static @Nullable String check(JsonObject in) {
        try {
            if (!isWhole(in.get("v"), 1, 1)) return "v must be 1";
            if (!(in.get("player") instanceof JsonPrimitive p) || !p.isString() || !PLAYER.matcher(p.getAsString()).matches()) return "player is not valid";
            if (!isWhole(in.get("at"), 1, Long.MAX_VALUE / 4)) return "at is not valid";
            if (!isBool(in.get("dropPearlAfterLoad"), true)) return "dropPearlAfterLoad must be true or false";
            for (String f : new String[] {"temporaryMode", "busy", "playerNear"}) if (!isBool(in.get(f), false)) return f + " must be true or false";
            if (!(in.get("chambers") instanceof JsonArray ca) || ca.size() > MAX_CHAMBERS) return "chambers must be a list of at most 32";
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (JsonElement e : ca) {
                if (!(e instanceof JsonObject c)) return "a chamber must be an object";
                if (!(c.get("id") instanceof JsonPrimitive id) || !id.isString() || !PEARL_ID.matcher(id.getAsString()).matches()) return "a chamber id is not valid";
                if (!ids.add(id.getAsString())) return "a chamber appears twice";
                JsonElement owner = c.get("owner");
                if (owner != null && !owner.isJsonNull() && !(owner instanceof JsonPrimitive op && op.isString() && PLAYER.matcher(op.getAsString()).matches())) return "an owner is not valid";
                if (!isBool(c.get("ownerKnown"), false)) return "ownerKnown must be true or false";
                if (!(c.get("presence") instanceof JsonPrimitive pr) || !pr.isString() || !PRESENCE.contains(pr.getAsString())) return "a presence is not valid";
                JsonElement since = c.get("since");
                if (since != null && !since.isJsonNull() && !isWhole(since, 1, Long.MAX_VALUE / 4)) return "since is not valid";
            }
            JsonElement le = in.get("load");
            if (le == null || le.isJsonNull()) return null;
            if (!(le instanceof JsonObject l)) return "load must be an object or null";
            if (!(l.get("id") instanceof JsonPrimitive id) || !id.isString() || !PEARL_ID.matcher(id.getAsString()).matches()) return "load.id is not valid";
            if (!(l.get("requester") instanceof JsonPrimitive rq) || !rq.isString() || !PLAYER.matcher(rq.getAsString()).matches()) return "load.requester is not valid";
            if (!(l.get("state") instanceof JsonPrimitive st) || !st.isString() || !LOAD_STATES.contains(st.getAsString())) return "load.state is not valid";
            JsonElement reason = l.get("reason");
            if (reason != null && !reason.isJsonNull() && !(reason instanceof JsonPrimitive rp && rp.isString() && ABORT_REASONS.contains(rp.getAsString()))) return "load.reason is not valid";
            for (String f : new String[] {"at", "pearlGoneAt", "requesterNearAt"}) {
                JsonElement t = l.get(f);
                if (t != null && !t.isJsonNull() && !isWhole(t, 1, Long.MAX_VALUE / 4)) return "load." + f + " is not valid";
            }
            if (!isBool(l.get("pearlPresent"), false)) return "load.pearlPresent must be true or false";
            JsonElement re = l.get("reopened");
            if (re != null && !re.isJsonNull() && !isBool(re, true)) return "load.reopened must be true or false";
            return null;
        } catch (RuntimeException e) {
            return "unreadable";
        }
    }

    private static boolean isBool(@Nullable JsonElement e, boolean required) {
        if (e == null || e.isJsonNull()) return !required;
        return e instanceof JsonPrimitive p && p.isBoolean();
    }

    private static boolean isWhole(@Nullable JsonElement e, long min, long max) {
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || p.getAsString().length() > 20) return false;
        try {
            java.math.BigDecimal d = p.getAsBigDecimal();
            return d.stripTrailingZeros().scale() <= 0 && d.compareTo(java.math.BigDecimal.valueOf(min)) >= 0 && d.compareTo(java.math.BigDecimal.valueOf(max)) <= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** The reply of the server to a report: the list and the interval. */
    public record Reply(List<LoadAuth.Allowed> allow, long intervalMs) {
        public static final long DEFAULT_MS = 30_000L, MIN_MS = 5_000L, MAX_MS = 120_000L;

        public static Reply parse(@Nullable JsonObject o) {
            if (o == null) return new Reply(new ArrayList<>(), DEFAULT_MS);
            long ms = DEFAULT_MS;
            if (o.get("intervalMs") instanceof JsonPrimitive p && p.isNumber()) {
                try {
                    ms = Math.max(MIN_MS, Math.min(MAX_MS, p.getAsLong()));
                } catch (NumberFormatException e) {
                    ms = DEFAULT_MS;
                }
            }
            return new Reply(LoadAuth.parse(o.get("allow")), ms);
        }
    }
}
