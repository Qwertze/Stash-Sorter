package com.porter.qwertestash.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwerte.core.farm.PositionGuard;
import com.porter.qwertestash.farm.FarmCodes;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The last check on a body before it leaves for the Stash Manager server (FARM P2, P3, TASK G1, G2 and the closed vocabularies):
 * no key named like a position at any depth, no string that looks like a position, and for a farm report every field in its
 * closed vocabulary. A failure returns a closed code and nothing about the offending value. Pure.
 */
public final class ReportGuard {
    private ReportGuard() {
    }

    private static final Pattern ANCHOR = Pattern.compile("^a[0-9]{1,2}$");

    /** G1 and G2 over a whole body. Returns null when clean, else {@code position_field} or {@code position_like}. */
    public static String positions(JsonElement e) {
        return walk(e, 0);
    }

    private static String walk(JsonElement e, int depth) {
        if (depth > 32) return "position_field"; // fail closed
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            for (var en : e.getAsJsonObject().entrySet()) {
                if (PositionGuard.FORBIDDEN_KEYS.contains(en.getKey().toLowerCase(Locale.ROOT))) return "position_field";
                String r = walk(en.getValue(), depth + 1);
                if (r != null) return r;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                String r = walk(x, depth + 1);
                if (r != null) return r;
            }
        } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            if (PositionGuard.looksLikePosition(e.getAsString())) return "position_like";
        }
        return null;
    }

    private static boolean intIn(JsonElement e, long lo, long hi) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        String s = e.getAsString();
        if (s.contains(".") || s.contains("e") || s.contains("E")) return false;
        try {
            long v = Long.parseLong(s);
            return v >= lo && v <= hi;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** The closed form of a farm report. Returns null when valid, else {@code bad_vocab}. */
    public static String farmReport(JsonObject b) {
        try {
            if (!FarmCodes.PHASES.contains(b.get("phase").getAsString())) return "bad_vocab";
            JsonObject p = b.getAsJsonObject("progress");
            if (!intIn(p.get("done"), 0, 1_000_000) || !intIn(p.get("total"), 0, 1_000_000) || p.size() != 2) return "bad_vocab";
            JsonElement cp = b.get("checkpoint");
            if (cp != null && !cp.isJsonNull()) {
                JsonObject c = cp.getAsJsonObject();
                if (!FarmCodes.CHECKPOINT_STEPS.contains(c.get("step").getAsString())) return "bad_vocab";
                for (var en : c.getAsJsonObject("data").entrySet()) {
                    if (!FarmCodes.CHECKPOINT_KEYS.contains(en.getKey())) return "bad_vocab";
                    boolean ok = switch (en.getKey()) {
                        case "loop" -> intIn(en.getValue(), 0, 1_000_000);
                        case "tick" -> intIn(en.getValue(), 0, 72_000);
                        case "recoveries" -> intIn(en.getValue(), 0, 1_000);
                        default -> en.getValue().isJsonPrimitive() && ANCHOR.matcher(en.getValue().getAsString()).matches()
                            && Integer.parseInt(en.getValue().getAsString().substring(1)) <= 63;
                    };
                    if (!ok) return "bad_vocab";
                }
            }
            JsonElement res = b.get("result");
            if (res != null && !res.isJsonNull()) {
                JsonObject r = res.getAsJsonObject();
                if (r.size() != FarmCodes.RESULT_KEYS.size() || !r.keySet().containsAll(FarmCodes.RESULT_KEYS)) return "bad_vocab";
                if (!intIn(r.get("loops"), 0, Integer.MAX_VALUE) || !intIn(r.get("recoveries"), 0, Integer.MAX_VALUE)
                    || !intIn(r.get("runtimeMs"), 0, Integer.MAX_VALUE) || !FarmCodes.known(r.get("endedBy").getAsString())
                    || !(r.get("gained") instanceof JsonArray g) || g.size() != 0) return "bad_vocab";
            }
            String state = b.get("state").getAsString();
            JsonElement er = b.get("error");
            boolean hasErr = er != null && !er.isJsonNull();
            if (hasErr) {
                JsonObject o = er.getAsJsonObject();
                String code = o.get("code").getAsString();
                if (o.size() != 1 || !FarmCodes.known(code)) return "bad_vocab";
                // the code must belong to the state (server 1.5.0 note 7): failed, cancelled and paused carry their own group, running and completed none
                if (!FarmCodes.stateOf(code).equals(state) || state.equals("completed") || state.equals("running")) return "bad_vocab";
            } else if (state.equals("failed") || state.equals("cancelled") || state.equals("paused")) {
                return "bad_vocab";
            }
            return null;
        } catch (RuntimeException ex) {
            return "bad_vocab";
        }
    }
}
