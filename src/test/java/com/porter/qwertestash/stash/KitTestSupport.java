package com.porter.qwertestash.stash;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.TreeMap;

/** Test helper: kit settings JSON whose stated hash is the right one for its content. */
public final class KitTestSupport {
    private KitTestSupport() {
    }

    /**
     * The same settings JSON with {@code hash} replaced by the hash of its content (chest, scoring, rules). Content that cannot be read is
     * returned unchanged, so a test of a bad object still reaches the check that refuses it.
     */
    public static String withHash(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            JsonElement c = o.get("kitsChest");
            String chest = c == null || c.isJsonNull() ? null : c.getAsString();
            TreeMap<String, String> rules = new TreeMap<>();
            for (var e : o.getAsJsonObject("rules").entrySet()) rules.put(e.getKey(), e.getValue().getAsString());
            KitScore.Scoring scoring = o.has("scoring") ? scoring(o.getAsJsonObject("scoring")) : KitScore.Scoring.DEFAULT;
            o.addProperty("hash", KitSettings.hashOf(chest, rules, scoring));
            return o.toString();
        } catch (RuntimeException e) {
            return json;
        }
    }

    private static KitScore.Scoring scoring(JsonObject s) {
        int opMin = s.has("opMin") ? s.get("opMin").getAsInt() : KitScore.Scoring.DEFAULT_OP_MIN;
        return new KitScore.Scoring(s.get("threshold").getAsInt(), s.get("name").getAsInt(), s.get("mix").getAsInt(), s.get("full").getAsInt(),
            s.get("op").getAsInt(), opMin);
    }
}
