package com.porter.qwertestash.manager;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The extras block served with the recipes (CRAFT-CONTRACT-v1.2.md section 13): smelting, fuel, dyes, special recipes and stew, written by hand
 * on the server per Minecraft version. In B1 every section is empty; the bot still checks the block, computes its hash again (section 13.2)
 * and reports it in the heartbeat ({@code craftOrders.extras}), so a later milestone's server can tell whether the bot has the same data.
 * <p>
 * A block whose hash (computed here from its content) is not the one it states is refused and never used. Pure; any thread.
 */
public final class Extras {
    /** The hash of an extras block with every section empty ({@code "extras-v1\n"}); the contract's first hash vector. */
    public static final String EMPTY_HASH = "c927f92c1e5bc11c5a34a36b24103c44cf61d038";

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");
    private static final Pattern NAME = Pattern.compile("^[a-z_]{1,32}$");
    private static final Pattern COLOR = Pattern.compile("^[0-9a-f]{6}$");
    private static final int MAX_SMELT = 1024, MAX_FUEL = 256, MAX_SPECIAL = 64, MAX_DYES = 32, MAX_ROLES = 32, MAX_IDS = 256, MAX_STEW = 128, MAX_DYEABLE = 256;
    private static final long MAX_TICKS = 100_000_000L;

    private final String hash;
    private final JsonObject json;

    private Extras(String hash, JsonObject json) {
        this.hash = hash;
        this.json = json;
    }

    /** The hash of this block (40 lowercase hex), checked against its content. */
    public String hash() {
        return hash;
    }

    /** The normalised copy of the block: validated fields only. Not to be changed. */
    public JsonObject json() {
        return json;
    }

    /**
     * Checks a block's shape and limits, computes its hash and compares it with the one it states.
     * @throws RecipeStore.Invalid when it is not what the contract says, or its hash is not the hash of its content
     */
    public static Extras parse(JsonObject o) throws RecipeStore.Invalid {
        try {
            if (!o.has("v") || !o.get("v").isJsonPrimitive() || o.get("v").getAsInt() != 1) throw new RecipeStore.Invalid("extras of another version");
            String stated = string(o, "hash");
            if (!HASH.matcher(stated).matches()) throw new RecipeStore.Invalid("extras without a hash");
            String computed = compute(o);
            if (!computed.equals(stated)) throw new RecipeStore.Invalid("the extras' hash " + computed + " is not the stated one");
            return new Extras(computed, normalised(o, computed));
        } catch (RecipeStore.Invalid i) {
            throw i;
        } catch (RuntimeException ex) {
            throw new RecipeStore.Invalid("malformed extras (" + ex.getClass().getSimpleName() + ")");
        }
    }

    /** A copy made from the validated fields only (CRAFT-CONTRACT-v1.2.md 13): unknown keys, here and inside entries, are dropped. {@code compute} has checked every field. */
    private static JsonObject normalised(JsonObject o, String hash) {
        JsonObject n = new JsonObject();
        n.addProperty("v", 1);
        n.addProperty("hash", hash);
        if (present(o, "cookTicks")) n.addProperty("cookTicks", o.get("cookTicks").getAsLong());
        JsonArray smelt = new JsonArray();
        for (JsonElement e : o.has("smelt") && o.get("smelt").isJsonArray() ? o.getAsJsonArray("smelt") : new JsonArray()) {
            JsonObject s = e.getAsJsonObject(), c = new JsonObject();
            c.add("id", s.get("id"));
            c.add("in", copyStrings(s.getAsJsonArray("in")));
            c.add("out", s.get("out"));
            c.addProperty("count", s.get("count").getAsLong());
            smelt.add(c);
        }
        n.add("smelt", smelt);
        JsonArray fuel = new JsonArray();
        for (JsonElement e : o.has("fuel") && o.get("fuel").isJsonArray() ? o.getAsJsonArray("fuel") : new JsonArray()) {
            JsonObject f = e.getAsJsonObject(), c = new JsonObject();
            c.add("id", f.get("id"));
            c.addProperty("ticks", f.get("ticks").getAsLong());
            fuel.add(c);
        }
        n.add("fuel", fuel);
        n.add("special", present(o, "special") ? copyStrings(o.getAsJsonArray("special")) : new JsonArray());
        JsonObject dyes = new JsonObject();
        if (present(o, "dyes")) for (Map.Entry<String, JsonElement> d : o.getAsJsonObject("dyes").entrySet()) {
            JsonObject v = d.getValue().getAsJsonObject(), c = new JsonObject();
            if (present(v, "armor")) c.add("armor", v.get("armor"));
            if (present(v, "firework")) c.add("firework", v.get("firework"));
            dyes.add(d.getKey(), c);
        }
        n.add("dyes", dyes);
        JsonObject stars = new JsonObject();
        if (present(o, "starItems")) for (Map.Entry<String, JsonElement> r : o.getAsJsonObject("starItems").entrySet()) stars.add(r.getKey(), copyStrings(r.getValue().getAsJsonArray()));
        n.add("starItems", stars);
        JsonObject stew = new JsonObject();
        if (present(o, "stew")) for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("stew").entrySet()) {
            JsonObject v = s.getValue().getAsJsonObject(), c = new JsonObject();
            c.add("effect", v.get("effect"));
            c.addProperty("ticks", v.get("ticks").getAsLong());
            stew.add(s.getKey(), c);
        }
        n.add("stew", stew);
        n.add("dyeable", present(o, "dyeable") ? copyStrings(o.getAsJsonArray("dyeable")) : new JsonArray());
        return n;
    }

    private static JsonArray copyStrings(JsonArray a) {
        JsonArray out = new JsonArray();
        for (JsonElement e : a) out.add(e.getAsString());
        return out;
    }

    /**
     * The contract's extrasHash (section 13.2): the lowercase hex SHA-1 of the UTF-8 text made of the line {@code extras-v1} and then these
     * lines, sorted together (String.compareTo), each ended by LF: {@code cook TAB ticks} (only when cookTicks is there), {@code smelt TAB id TAB
     * out TAB count TAB in-sorted-joined-by-comma}, {@code fuel TAB id TAB ticks}, {@code special TAB kind}, {@code dye TAB name TAB armor TAB
     * firework}, {@code star TAB role TAB ids-sorted-joined-by-comma}, {@code stew TAB flower TAB effect TAB ticks} and {@code dyeable TAB id}.
     * A dye without an armor or a firework colour writes that field empty (the contract is silent on it; B3 and B5 confirm).
     * @throws RecipeStore.Invalid when a section is not what the contract says
     */
    public static String compute(JsonObject o) throws RecipeStore.Invalid {
        List<String> lines = new ArrayList<>();
        if (present(o, "cookTicks")) lines.add("cook\t" + whole(o.get("cookTicks"), 1, 1_000_000, "cookTicks"));
        for (JsonElement e : array(o, "smelt", MAX_SMELT)) {
            JsonObject s = object(e);
            List<String> in = ids(s.get("in"), 1, 64);
            Collections.sort(in);
            lines.add("smelt\t" + id(s, "id") + "\t" + id(s, "out") + "\t" + whole(s.get("count"), 1, 64, "smelt count") + "\t" + String.join(",", in));
        }
        for (JsonElement e : array(o, "fuel", MAX_FUEL)) {
            JsonObject f = object(e);
            lines.add("fuel\t" + id(f, "id") + "\t" + whole(f.get("ticks"), 1, MAX_TICKS, "fuel ticks"));
        }
        for (JsonElement e : array(o, "special", MAX_SPECIAL)) {
            String kind = text(e);
            if (!NAME.matcher(kind).matches()) throw new RecipeStore.Invalid("bad special kind");
            lines.add("special\t" + kind);
        }
        if (present(o, "dyes")) {
            JsonObject dyes = object(o.get("dyes"));
            if (dyes.size() > MAX_DYES) throw new RecipeStore.Invalid("too many dyes");
            for (Map.Entry<String, JsonElement> d : dyes.entrySet()) {
                if (!NAME.matcher(d.getKey()).matches()) throw new RecipeStore.Invalid("bad dye name");
                JsonObject v = object(d.getValue());
                lines.add("dye\t" + d.getKey() + "\t" + color(v, "armor") + "\t" + color(v, "firework"));
            }
        }
        if (present(o, "starItems")) {
            JsonObject roles = object(o.get("starItems"));
            if (roles.size() > MAX_ROLES) throw new RecipeStore.Invalid("too many star roles");
            for (Map.Entry<String, JsonElement> r : roles.entrySet()) {
                if (!NAME.matcher(r.getKey()).matches()) throw new RecipeStore.Invalid("bad star role");
                List<String> list = ids(r.getValue(), 0, MAX_IDS);
                Collections.sort(list);
                lines.add("star\t" + r.getKey() + "\t" + String.join(",", list));
            }
        }
        if (present(o, "stew")) {
            JsonObject stew = object(o.get("stew"));
            if (stew.size() > MAX_STEW) throw new RecipeStore.Invalid("too many stew flowers");
            for (Map.Entry<String, JsonElement> s : stew.entrySet()) {
                if (!ID.matcher(s.getKey()).matches()) throw new RecipeStore.Invalid("bad stew flower");
                JsonObject v = object(s.getValue());
                lines.add("stew\t" + s.getKey() + "\t" + id(v, "effect") + "\t" + whole(v.get("ticks"), 1, MAX_TICKS, "stew ticks"));
            }
        }
        for (JsonElement e : array(o, "dyeable", MAX_DYEABLE)) {
            String id = text(e);
            if (!ID.matcher(id).matches()) throw new RecipeStore.Invalid("bad dyeable id");
            lines.add("dyeable\t" + id);
        }
        Collections.sort(lines);
        StringBuilder text = new StringBuilder("extras-v1\n");
        for (String l : lines) text.append(l).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The hash inside one half of an ETag such as {@code "<tableHash>+<extrasHash>"}: the part after the {@code +}, or null when there is none. */
    public static @Nullable String hashOfEtag(@Nullable String etag) {
        if (etag == null) return null;
        String e = etag.trim();
        if (e.startsWith("W/")) e = e.substring(2);
        if (e.length() >= 2 && e.startsWith("\"") && e.endsWith("\"")) e = e.substring(1, e.length() - 1);
        int plus = e.indexOf('+');
        if (plus < 0) return null;
        String h = e.substring(plus + 1);
        return HASH.matcher(h).matches() ? h : null;
    }

    // Reading helpers

    private static boolean present(JsonObject o, String name) {
        return o.has(name) && !o.get(name).isJsonNull();
    }

    private static JsonArray array(JsonObject o, String name, int max) throws RecipeStore.Invalid {
        if (!present(o, name)) return new JsonArray();
        if (!o.get(name).isJsonArray()) throw new RecipeStore.Invalid(name + " is not a list");
        JsonArray a = o.getAsJsonArray(name);
        if (a.size() > max) throw new RecipeStore.Invalid("too many " + name);
        return a;
    }

    private static JsonObject object(@Nullable JsonElement e) throws RecipeStore.Invalid {
        if (e == null || !e.isJsonObject()) throw new RecipeStore.Invalid("not an object");
        return e.getAsJsonObject();
    }

    private static String text(@Nullable JsonElement e) throws RecipeStore.Invalid {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new RecipeStore.Invalid("not a string");
        return e.getAsString();
    }

    private static String string(JsonObject o, String name) throws RecipeStore.Invalid {
        return text(o.get(name));
    }

    private static String id(JsonObject o, String name) throws RecipeStore.Invalid {
        String s = string(o, name);
        if (!ID.matcher(s).matches()) throw new RecipeStore.Invalid("bad " + name);
        return s;
    }

    private static List<String> ids(@Nullable JsonElement e, int min, int max) throws RecipeStore.Invalid {
        if (e == null || !e.isJsonArray()) throw new RecipeStore.Invalid("not a list of ids");
        JsonArray a = e.getAsJsonArray();
        if (a.size() < min || a.size() > max) throw new RecipeStore.Invalid("a list of " + a.size() + " ids");
        List<String> out = new ArrayList<>();
        for (JsonElement x : a) {
            String s = text(x);
            if (!ID.matcher(s).matches()) throw new RecipeStore.Invalid("bad id");
            out.add(s);
        }
        return out;
    }

    private static String color(JsonObject o, String name) throws RecipeStore.Invalid {
        if (!present(o, name)) return "";
        String c = string(o, name);
        if (!COLOR.matcher(c).matches()) throw new RecipeStore.Invalid("bad " + name + " colour");
        return c;
    }

    private static String whole(@Nullable JsonElement e, long min, long max, String what) throws RecipeStore.Invalid {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new RecipeStore.Invalid(what + " is not a number");
        double d = e.getAsDouble();
        long n = e.getAsLong();
        if (d != n || n < min || n > max) throw new RecipeStore.Invalid(what + " out of range");
        return Long.toString(n);
    }

    // The extras the bot holds

    private static volatile @Nullable Extras current;

    /** The validated extras of the cached or fetched recipes, or null (an older server sent none, or the block was refused). Replaced whole, read from any thread. */
    public static @Nullable Extras current() {
        return current;
    }

    public static void setCurrent(@Nullable Extras extras) {
        current = extras;
    }

    /** The hash the heartbeat reports ({@code craftOrders.extras}): the held extras' hash, or null. */
    public static @Nullable String currentHash() {
        Extras e = current;
        return e == null ? null : e.hash;
    }
}
