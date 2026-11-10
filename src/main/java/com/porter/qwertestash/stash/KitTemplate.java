package com.porter.qwertestash.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A kit template of the Kit Builder (KIT-CONTRACT v2, revisions 3 and 4, "Kit templates"): a named, categorised list of 27 slots. Immutable.
 * A slot is an item key and its match key (the item with its custom name, damage, repair cost and contents left out) and a count; a key
 * {@code <id>#*} takes any variant. Everything read from the server or the cache file is checked here.
 */
public record KitTemplate(String id, String name, String category, @Nullable String chest, @Nullable String color, int keep, List<@Nullable Slot> slots) {
    private static final Pattern ID = Pattern.compile("^t-[0-9a-z]{8}$");
    private static final Pattern KEY = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}(#([0-9a-f]{8,40}|\\*))?$");
    public static final int SLOTS = 27;
    public static final Set<String> COLORS = Set.of("plain", "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
        "purple", "blue", "brown", "green", "red", "black");

    /** @param key an index key, or {@code <id>#*}; {@code match} its match key (equal to the key for {@code #*}) */
    public record Slot(String key, String match, int count) {
        public boolean any() {
            return key.endsWith("#*");
        }

        /** The item id without a component hash. */
        public String base() {
            return baseOf(key);
        }
    }

    /** Why an object was refused, for the log. */
    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    public static String baseOf(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    /** Checks and reads one template object. {@code count} up to 99 (the item's stack size is the server's check). */
    public static KitTemplate parse(@Nullable JsonElement element) throws Invalid {
        if (element == null || !element.isJsonObject()) throw new Invalid("a template is not an object");
        JsonObject o = element.getAsJsonObject();
        String id = text(o, "id", 11), name = text(o, "name", 200), category = text(o, "category", 40);
        if (!ID.matcher(id).matches()) throw new Invalid("a template id is not t- and 8 hex digits");
        if (name.isEmpty() || name.length() > 50 || name.indexOf('§') >= 0 || hasControl(name)) throw new Invalid("a template name is not usable");
        if (!KitNames.validKey(KitNames.normalize(name))) throw new Invalid("a template name is not a key");
        if (!KitSettings.validCategory(category)) throw new Invalid("a template category is not a type or a custom category id");
        String chest = null;
        JsonElement c = o.get("chest");
        if (c != null && !c.isJsonNull()) {
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || !KitSettings.validId(c.getAsString())) throw new Invalid("a template chest is not a container id");
            chest = c.getAsString();
        }
        String color = null;
        JsonElement col = o.get("color");
        if (col != null && !col.isJsonNull()) {
            if (!col.isJsonPrimitive() || !col.getAsJsonPrimitive().isString() || !COLORS.contains(col.getAsString())) throw new Invalid("a template colour is not known");
            color = col.getAsString();
        }
        int keep = 0;
        JsonElement k = o.get("keep");
        if (k != null && !k.isJsonNull()) {
            if (!k.isJsonPrimitive() || !k.getAsJsonPrimitive().isNumber() || !k.getAsString().matches("[0-9]{1,2}")) throw new Invalid("keep is not 0..27");
            keep = Integer.parseInt(k.getAsString());
            if (keep > 27) throw new Invalid("keep is not 0..27");
        }
        JsonElement s = o.get("slots");
        if (s == null || !s.isJsonArray() || s.getAsJsonArray().size() != SLOTS) throw new Invalid("a template does not have 27 slots");
        List<Slot> slots = new ArrayList<>();
        Set<String> bases = new HashSet<>();
        for (JsonElement e : s.getAsJsonArray()) {
            if (e.isJsonNull()) {
                slots.add(null);
                continue;
            }
            if (!e.isJsonObject()) throw new Invalid("a slot is not an object");
            JsonObject so = e.getAsJsonObject();
            String key = text(so, "key", 200);
            if (!KEY.matcher(key).matches()) throw new Invalid("a slot key is not an item key");
            String match = so.has("match") && !so.get("match").isJsonNull() ? text(so, "match", 200) : key;
            if (!KEY.matcher(match).matches() || (key.endsWith("#*") && !match.equals(key)) || !baseOf(match).equals(baseOf(key))) throw new Invalid("a slot match key does not fit its key");
            JsonElement n = so.get("count");
            if (n == null || !n.isJsonPrimitive() || !n.getAsJsonPrimitive().isNumber() || !n.getAsString().matches("[0-9]{1,2}")) throw new Invalid("a slot count is not 1..99");
            int count = Integer.parseInt(n.getAsString());
            if (count < 1) throw new Invalid("a slot count is not 1..99");
            String base = baseOf(key);
            if (base.endsWith("shulker_box")) throw new Invalid("a shulker box can't be in a kit");
            bases.add(base);
            slots.add(new Slot(key, match, count));
        }
        if (bases.size() < 2) throw new Invalid("a kit needs at least two kinds of item");
        return new KitTemplate(id, name, category, chest, color, keep, Collections.unmodifiableList(slots));
    }

    private static String text(JsonObject o, String field, int max) throws Invalid {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().length() > max) throw new Invalid(field + " is not usable");
        return e.getAsString();
    }

    private static boolean hasControl(String s) {
        return com.porter.qwertestash.orders.ShulkerNames.hasHiddenChar(s);
    }

    /** The canonical string v2: {@code "tpl-v2\n" + id, name, category, chest, colour, keep} each on a line, then the slots joined by ",", then a line end. */
    public String canonical() {
        StringBuilder sb = new StringBuilder("tpl-v2\n");
        sb.append(id).append('\n').append(name).append('\n').append(category).append('\n').append(chest == null ? "" : chest).append('\n')
            .append(color == null ? "" : color).append('\n').append(keep).append('\n');
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) sb.append(',');
            Slot s = slots.get(i);
            if (s != null) sb.append(s.key()).append(' ').append(s.match()).append(' ').append(s.count());
        }
        return sb.append('\n').toString();
    }

    /** SHA-1 hex of the canonical string. */
    public String hash() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(canonical().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("category", category);
        if (chest == null) o.add("chest", JsonNull.INSTANCE);
        else o.addProperty("chest", chest);
        if (color == null) o.add("color", JsonNull.INSTANCE);
        else o.addProperty("color", color);
        o.addProperty("keep", keep);
        JsonArray a = new JsonArray();
        for (Slot s : slots) {
            if (s == null) {
                a.add(JsonNull.INSTANCE);
                continue;
            }
            JsonObject so = new JsonObject();
            so.addProperty("key", s.key());
            so.addProperty("match", s.match());
            so.addProperty("count", s.count());
            a.add(so);
        }
        o.add("slots", a);
        return o;
    }
}
