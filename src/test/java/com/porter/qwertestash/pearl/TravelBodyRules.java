package com.porter.qwertestash.pearl;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** The server's rules for the heartbeat's {@code pearlTravel} object (PearlBodies.travel, contract rev 6), as test code: null when the object is acceptable. */
final class TravelBodyRules {
    private TravelBodyRules() {
    }

    static final List<String> STEPS = List.of("precheck", "arm_home", "ready", "load_sent", "awaiting_pull", "arrived", "pickup", "rearm", "done");
    static final Set<String> PAUSES = Set.of("player_near", "loader_player_near", "loader_busy", "owner_offline", "loader_stale", "chamber_locked", "loader_locked",
        "low_health", "low_food", "just_joined", "busy_order", "busy_repair", "sorter_busy", "bot_offline");
    static final Set<String> KEYS = Set.of("v", "enabled", "at", "uuid", "game", "joinedAt", "client", "away", "pearls", "trip", "local");
    static final Set<String> TRIP_KEYS = Set.of("id", "step", "pause", "error", "whispers", "loadSentAt", "teleportAt", "teleportDim", "rearm", "pearls");

    private static boolean whole(@Nullable JsonElement e, long min, long max) {
        return e instanceof JsonPrimitive p && p.isNumber() && p.getAsBigDecimal().stripTrailingZeros().scale() <= 0 && p.getAsLong() >= min && p.getAsLong() <= max;
    }

    private static boolean str(@Nullable JsonElement e, Pattern p, boolean nullOk) {
        if (e == null || e.isJsonNull()) return nullOk;
        return e instanceof JsonPrimitive s && s.isString() && p.matcher(s.getAsString()).matches();
    }

    static @Nullable String check(JsonObject o) {
        for (String k : o.keySet()) if (!KEYS.contains(k)) return "unknown key " + k;
        if (!whole(o.get("v"), 1, 1)) return "v";
        if (!(o.get("enabled") instanceof JsonPrimitive en) || !en.isBoolean()) return "enabled";
        if (!whole(o.get("at"), 1, Long.MAX_VALUE / 4)) return "at";
        if (o.has("uuid") && !str(o.get("uuid"), Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"), true)) return "uuid";
        if (!str(o.get("game"), Pattern.compile("^(in|queue|out)$"), false)) return "game";
        if (o.has("joinedAt") && !o.get("joinedAt").isJsonNull() && !whole(o.get("joinedAt"), 1, Long.MAX_VALUE / 4)) return "joinedAt";
        if (!str(o.get("client"), Pattern.compile("^(plugin|addon)$"), true)) return "client";
        JsonElement away = o.get("away");
        if (away != null && !away.isJsonNull()) {
            if (!(away instanceof JsonObject a)) return "away";
            if (!str(a.get("station"), PearlReply.STATION, false) || !str(a.get("trip"), PearlReply.TRIP, true)) return "away fields";
            if (a.has("since") && !a.get("since").isJsonNull() && !whole(a.get("since"), 1, Long.MAX_VALUE / 4)) return "away.since";
        }
        if (!(o.get("pearls") instanceof JsonObject p) || !whole(p.get("carried"), 0, 2304)) return "pearls";
        JsonElement trip = o.get("trip");
        if (trip != null && !trip.isJsonNull()) {
            if (!(trip instanceof JsonObject t)) return "trip";
            for (String k : t.keySet()) if (!TRIP_KEYS.contains(k)) return "unknown trip key " + k;
            if (!str(t.get("id"), PearlReply.TRIP, false)) return "trip.id";
            if (!(t.get("step") instanceof JsonPrimitive st) || !STEPS.contains(st.getAsString())) return "trip.step";
            if (t.has("pause") && !t.get("pause").isJsonNull() && !PAUSES.contains(t.get("pause").getAsString())) return "trip.pause";
            if (t.has("error") && !t.get("error").isJsonNull() && !PearlTrip.BOT_ERRORS.contains(t.get("error").getAsString())) return "trip.error";
            if (t.has("whispers") && !t.get("whispers").isJsonNull() && !whole(t.get("whispers"), 0, 2)) return "trip.whispers";
            for (String f : new String[] {"loadSentAt", "teleportAt"}) if (t.has(f) && !t.get(f).isJsonNull() && !whole(t.get(f), 1, Long.MAX_VALUE / 4)) return "trip." + f;
            if (!str(t.get("teleportDim"), PearlReply.DIM, true)) return "trip.teleportDim";
            if (t.has("rearm") && !t.get("rearm").isJsonNull()) {
                if (!(t.get("rearm") instanceof JsonObject r) || !PearlTrip.REARM_RESULTS.contains(r.get("result").getAsString())) return "trip.rearm";
            }
            if (t.has("pearls") && t.get("pearls") instanceof JsonObject pl) {
                for (String f : new String[] {"before", "after"}) if (pl.has(f) && !pl.get(f).isJsonNull() && !whole(pl.get(f), 0, 2304)) return "trip.pearls." + f;
            }
        }
        JsonElement local = o.get("local");
        if (local != null && !local.isJsonNull()) {
            if (!(local instanceof JsonObject l)) return "local";
            if (!str(l.get("action"), Pattern.compile("^(go|back|home)$"), false)) return "local.action";
            if (!str(l.get("localId"), Pattern.compile("^l-[0-9a-f]{8}$"), l.get("action").getAsString().equals("home"))) return "local.localId";
            if (!str(l.get("station"), PearlReply.STATION, true)) return "local.station";
        }
        return null;
    }
}
