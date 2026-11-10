package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an order with kit lines made, for {@code result.kit} (KIT-CONTRACT v2, revision 4, "Result"): per template how many were wanted, ready,
 * built and delivered; what came short, per item, and whether the part-filled shulker was stored as an incomplete copy or its items went back; notes.
 * Numbers, keys and codes only. For the bot tick thread.
 */
public final class KitResult {
    public static final int MAX_LIST = 200;
    public static final String STORED_INCOMPLETE = "incomplete", STORED_RETURNED = "returned";
    public static final String COLOUR_SUBSTITUTED = "colour_substituted";

    /** An item a kit lacks: its match key, the template slot key and how many. */
    public record Miss(String match, String key, int count) {
    }

    /** @param stored {@link #STORED_INCOMPLETE} or {@link #STORED_RETURNED}, or null when nothing was started */
    public record Short(String template, int kits, List<Miss> items, String reason, @Nullable String stored) {
    }

    private static final class Made {
        String name = "";
        int wanted, ready, built, delivered;
    }

    private final Map<String, Made> made = new LinkedHashMap<>();
    private final List<Short> shorts = new ArrayList<>();
    private final Set<String> notes = new LinkedHashSet<>();

    public void line(String template, String name, int wanted) {
        Made m = made.computeIfAbsent(template, k -> new Made());
        m.name = name;
        m.wanted += wanted;
    }

    public void ready(String template) {
        made.computeIfAbsent(template, k -> new Made()).ready++;
    }

    public void built(String template) {
        made.computeIfAbsent(template, k -> new Made()).built++;
    }

    public void delivered(String template) {
        made.computeIfAbsent(template, k -> new Made()).delivered++;
    }

    public void note(String note) {
        notes.add(note);
    }

    public void addShort(String template, int kits, List<Miss> items, String reason, @Nullable String stored) {
        shorts.add(new Short(template, Math.max(1, kits), List.copyOf(items), reason, stored));
    }

    public List<Short> shorts() {
        return shorts;
    }

    public int shortKits() {
        int n = 0;
        for (Short s : shorts) n += s.kits();
        return n;
    }

    public boolean any() {
        return !made.isEmpty() || !shorts.isEmpty();
    }

    /** "<n> kit(s) not made: <reasons>", or null when every kit was made. */
    public @Nullable String note() {
        int n = shortKits();
        if (n == 0) return null;
        Set<String> reasons = new LinkedHashSet<>();
        for (Short s : shorts) reasons.add(s.reason());
        return n + (n == 1 ? " kit" : " kits") + " not made: " + String.join(", ", reasons);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray m = new JsonArray();
        for (var e : made.entrySet()) {
            if (m.size() >= MAX_LIST) break;
            JsonObject x = new JsonObject();
            x.addProperty("template", e.getKey());
            x.addProperty("name", e.getValue().name.length() > 50 ? e.getValue().name.substring(0, 50) : e.getValue().name);
            x.addProperty("wanted", e.getValue().wanted);
            x.addProperty("ready", e.getValue().ready);
            x.addProperty("built", e.getValue().built);
            x.addProperty("delivered", e.getValue().delivered);
            m.add(x);
        }
        o.add("made", m);
        JsonArray s = new JsonArray();
        for (Short sh : shorts) {
            if (s.size() >= MAX_LIST) break;
            JsonObject x = new JsonObject();
            x.addProperty("template", sh.template());
            x.addProperty("kits", sh.kits());
            JsonArray items = new JsonArray();
            for (Miss mi : sh.items()) {
                if (items.size() >= MAX_LIST) break;
                JsonObject io = new JsonObject();
                io.addProperty("match", mi.match());
                io.addProperty("key", mi.key());
                io.addProperty("count", mi.count());
                items.add(io);
            }
            x.add("items", items);
            x.addProperty("reason", sh.reason());
            if (sh.stored() != null) x.addProperty("stored", sh.stored());
            s.add(x);
        }
        o.add("short", s);
        JsonArray n = new JsonArray();
        for (String note : notes) n.add(note);
        o.add("notes", n);
        return o;
    }
}
