package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The kit settings of one stash (KIT-CONTRACT.md v1.1, "Kit settings"): a chest for every kit, a chest per kit name, and the scoring that
 * decides what a kit is. Immutable.
 * Everything that comes from the server or from the cache file is checked here, and an object that fails any check is refused whole.
 *
 * @param kitsChest a container id or null
 * @param rules     kit key to container id, at most {@link #MAX_RULES}, sorted by key
 * @param scoring   the point values and the threshold
 */
public record KitSettings(long rev, String hash, @Nullable String kitsChest, Map<String, String> rules, KitScore.Scoring scoring, @Nullable Types types) {
    /** Settings without the v2 part (an older server, or tests of the v1 parts). */
    public KitSettings(long rev, String hash, @Nullable String kitsChest, Map<String, String> rules, KitScore.Scoring scoring) {
        this(rev, hash, kitsChest, rules, scoring, null);
    }

    public static final int MAX_CATEGORIES = 16, MAX_TEMPLATES = 50, MAX_CHESTS = 23, MAX_TREAT_AS = 200;
    private static final Pattern CATEGORY = Pattern.compile("^c:[a-z0-9-]{1,24}$");

    /** A custom category: id {@code c:} and a slug, a name of 1..32 characters. */
    public record Category(String id, String name) {
    }

    /**
     * The v2 part of the settings (KIT-CONTRACT v2): {@code hash} is the v2 hash; {@code chests} category id to container id (the seven types and the
     * custom categories); {@code treatAs} normalised name to a category id or "none"; the custom {@code categories} and the kit {@code templates}.
     */
    public record Types(String hash, List<Category> categories, Map<String, String> chests, Map<String, String> treatAs, List<KitTemplate> templates) {
        public @Nullable KitTemplate template(String id) {
            for (KitTemplate t : templates) if (t.id().equals(id)) return t;
            return null;
        }

        public boolean hasCategory(String id) {
            for (Category c : categories) if (c.id().equals(id)) return true;
            return false;
        }
    }

    /** A category id: one of the seven types, or the shape of a custom category (that it exists is checked against the settings). */
    public static boolean validCategory(@Nullable String id) {
        return id != null && (KitTypes.TYPE_IDS.contains(id) || CATEGORY.matcher(id).matches());
    }
    public static final int MAX_RULES = 200;
    private static final Pattern ID = Pattern.compile("^-?[0-9]{1,8},-?[0-9]{1,4},-?[0-9]{1,8}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{40}$");

    /** What a stash has when it has nothing set. */
    public static final KitSettings EMPTY = new KitSettings(0, hashOf(null, Map.of(), KitScore.Scoring.DEFAULT), null, Map.of(), KitScore.Scoring.DEFAULT);

    /** Why an object was refused, for the log. */
    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    /** Whether no chest is set: no kits chest and no rule (the scoring alone routes nothing). */
    public boolean isEmpty() {
        if (types != null) {
            if (!types.chests().isEmpty()) return false;
            for (KitTemplate t : types.templates()) if (t.chest() != null) return false;
        }
        return kitsChest == null && rules.isEmpty();
    }

    /** A container id as the contract has it: decimal x,y,z in range. */
    public static boolean validId(@Nullable String id) {
        if (id == null || !ID.matcher(id).matches()) return false;
        try {
            String[] p = id.split(",");
            return Math.abs(Long.parseLong(p[0])) <= 30_000_000 && Math.abs(Long.parseLong(p[2])) <= 30_000_000
                && Long.parseLong(p[1]) >= -2048 && Long.parseLong(p[1]) <= 2048;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The id of a position. */
    public static String id(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** The position an id names, or null for an invalid id. */
    public static @Nullable BlockPos position(@Nullable String id) {
        if (!validId(id)) return null;
        String[] p = id.split(",");
        return new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }

    /**
     * SHA-1 (lowercase hex) of "v1\n" + the kits chest (or nothing) + "\n" + "score " + threshold + " " + name + " " + mix + " " + full + " " + op + "\n",
     * then key + "\t" + container + "\n" for each rule in key order (String.compareTo).
     */
    public static String hashOf(@Nullable String kitsChest, Map<String, String> rules, KitScore.Scoring s) {
        StringBuilder sb = new StringBuilder("v1\n").append(kitsChest == null ? "" : kitsChest).append('\n');
        sb.append("score ").append(s.threshold()).append(' ').append(s.name()).append(' ').append(s.mix()).append(' ').append(s.full()).append(' ').append(s.op())
            .append(' ').append(s.opMin()).append('\n');
        for (Map.Entry<String, String> e : new TreeMap<>(rules).entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks and reads a settings object ({@code rev}, {@code hash}, {@code kitsChest}, {@code rules}). Unknown fields are ignored.
     * @throws Invalid when anything is wrong: a type, a key that is not a normal kit name, a bad container id, more than 200 rules
     */
    public static KitSettings parse(@Nullable JsonElement element) throws Invalid {
        if (element == null || !element.isJsonObject()) throw new Invalid("not an object");
        JsonObject o = element.getAsJsonObject();

        long rev = wholeNumber(o.get("rev"), "rev");
        if (rev < 0) throw new Invalid("rev is negative");

        JsonElement h = o.get("hash");
        if (h == null || !h.isJsonPrimitive() || !h.getAsJsonPrimitive().isString() || !HASH.matcher(h.getAsString()).matches()) throw new Invalid("hash is not 40 lowercase hex digits");
        String hash = h.getAsString();

        String chest = null;
        JsonElement c = o.get("kitsChest");
        if (c != null && !c.isJsonNull()) {
            if (!c.isJsonPrimitive() || !c.getAsJsonPrimitive().isString() || !validId(c.getAsString())) throw new Invalid("kitsChest is not a container id");
            chest = c.getAsString();
        }

        JsonElement r = o.get("rules");
        if (r == null || !r.isJsonObject()) throw new Invalid("rules is not an object");
        JsonObject ro = r.getAsJsonObject();
        if (ro.size() > MAX_RULES) throw new Invalid("more than " + MAX_RULES + " rules");
        Map<String, String> rules = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : ro.entrySet()) {
            if (!KitNames.validKey(e.getKey())) throw new Invalid("a rule has a key that is not a normal name");
            JsonElement v = e.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString() || !validId(v.getAsString())) throw new Invalid("a rule has a container that is not an id");
            rules.put(e.getKey(), v.getAsString());
        }
        KitScore.Scoring scoring = parseScoring(o.get("scoring"));
        // The content has to hash to the hash it states: a mismatch is a damaged or tampered object
        if (!hashOf(chest, rules, scoring).equals(hash)) throw new Invalid("hash does not match the content");
        // The v2 part, when the server sent one: any failure refuses the whole object
        Types types = null;
        JsonElement t = o.get("types");
        if (t != null && !t.isJsonNull()) types = parseTypes(t, hash);
        return new KitSettings(rev, hash, chest, Collections.unmodifiableMap(rules), scoring, types);
    }

    private static Types parseTypes(JsonElement element, String v1Hash) throws Invalid {
        if (!element.isJsonObject()) throw new Invalid("types is not an object");
        JsonObject o = element.getAsJsonObject();
        JsonElement h = o.get("hash");
        if (h == null || !h.isJsonPrimitive() || !h.getAsJsonPrimitive().isString() || !HASH.matcher(h.getAsString()).matches()) throw new Invalid("types.hash is not 40 lowercase hex digits");

        List<Category> categories = new ArrayList<>();
        JsonElement ca = o.get("categories");
        if (ca != null && !ca.isJsonNull()) {
            if (!ca.isJsonArray() || ca.getAsJsonArray().size() > MAX_CATEGORIES) throw new Invalid("categories is not a list of at most " + MAX_CATEGORIES);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (JsonElement e : ca.getAsJsonArray()) {
                if (!e.isJsonObject()) throw new Invalid("a category is not an object");
                String id = str(e.getAsJsonObject().get("id")), name = str(e.getAsJsonObject().get("name"));
                if (id == null || !CATEGORY.matcher(id).matches() || !seen.add(id)) throw new Invalid("a category id is not usable");
                if (name == null || name.isEmpty() || name.length() > 32 || name.indexOf('§') >= 0 || hasControl(name)) throw new Invalid("a category name is not usable");
                categories.add(new Category(id, name));
            }
        }
        java.util.Set<String> known = new java.util.HashSet<>(KitTypes.TYPE_IDS);
        for (Category c : categories) known.add(c.id());

        Map<String, String> chests = new TreeMap<>();
        JsonElement ch = o.get("chests");
        if (ch != null && !ch.isJsonNull()) {
            if (!ch.isJsonObject() || ch.getAsJsonObject().size() > MAX_CHESTS) throw new Invalid("chests is not an object of at most " + MAX_CHESTS);
            for (var e : ch.getAsJsonObject().entrySet()) {
                String v = str(e.getValue());
                if (!known.contains(e.getKey()) || !validId(v)) throw new Invalid("a type chest is not a known category with a container id");
                chests.put(e.getKey(), v);
            }
        }
        Map<String, String> treatAs = new TreeMap<>();
        JsonElement ta = o.get("treatAs");
        if (ta != null && !ta.isJsonNull()) {
            if (!ta.isJsonObject() || ta.getAsJsonObject().size() > MAX_TREAT_AS) throw new Invalid("treatAs is not an object of at most " + MAX_TREAT_AS);
            for (var e : ta.getAsJsonObject().entrySet()) {
                String v = str(e.getValue());
                if (!KitNames.validKey(e.getKey()) || v == null || !(v.equals("none") || known.contains(v))) throw new Invalid("a treatAs entry is not a key and a category or none");
                treatAs.put(e.getKey(), v);
            }
        }
        List<KitTemplate> templates = new ArrayList<>();
        JsonElement te = o.get("templates");
        if (te != null && !te.isJsonNull()) {
            if (!te.isJsonArray() || te.getAsJsonArray().size() > MAX_TEMPLATES) throw new Invalid("templates is not a list of at most " + MAX_TEMPLATES);
            java.util.Set<String> ids = new java.util.HashSet<>(), names = new java.util.HashSet<>();
            for (JsonElement e : te.getAsJsonArray()) {
                KitTemplate t;
                try {
                    t = KitTemplate.parse(e);
                } catch (KitTemplate.Invalid ex) {
                    throw new Invalid(ex.getMessage());
                }
                if (!known.contains(t.category())) throw new Invalid("a template's category does not exist");
                if (!ids.add(t.id()) || !names.add(KitNames.normalize(t.name()))) throw new Invalid("two templates share an id or a name");
                templates.add(t);
            }
        }
        String hash = hashV2(v1Hash, categories, chests, treatAs, templates);
        if (!hash.equals(h.getAsString())) throw new Invalid("types.hash does not match the content");
        return new Types(hash, Collections.unmodifiableList(categories), Collections.unmodifiableMap(chests), Collections.unmodifiableMap(treatAs), Collections.unmodifiableList(templates));
    }

    private static @Nullable String str(@Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static boolean hasControl(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isISOControl(s.charAt(i))) return true;
        return false;
    }

    /**
     * The v2 hash: SHA-1 (lowercase hex) of {@code "v2\n" + v1 hash + "\n"}, then {@code "chest " + category + " " + id + "\n"} (the seven types in
     * type order, then the custom categories by id), {@code "cat " + id + "\t" + name + "\n"} per custom category by id, {@code "as " + key + "\t" +
     * value + "\n"} per treatAs by key and {@code "tpl " + id + " " + template hash + "\n"} per template by id.
     */
    public static String hashV2(String v1Hash, List<Category> categories, Map<String, String> chests, Map<String, String> treatAs, List<KitTemplate> templates) {
        StringBuilder sb = new StringBuilder("v2\n").append(v1Hash).append('\n');
        for (String type : KitTypes.TYPE_IDS) if (chests.containsKey(type)) sb.append("chest ").append(type).append(' ').append(chests.get(type)).append('\n');
        for (var e : new TreeMap<>(chests).entrySet()) if (!KitTypes.TYPE_IDS.contains(e.getKey())) sb.append("chest ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        for (Category c : categories.stream().sorted(java.util.Comparator.comparing(Category::id)).toList()) sb.append("cat ").append(c.id()).append('\t').append(c.name()).append('\n');
        for (var e : new TreeMap<>(treatAs).entrySet()) sb.append("as ").append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        for (KitTemplate t : templates.stream().sorted(java.util.Comparator.comparing(KitTemplate::id)).toList()) sb.append("tpl ").append(t.id()).append(' ').append(t.hash()).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A missing scoring is the defaults; a present one needs all five whole numbers in range. */
    private static KitScore.Scoring parseScoring(@Nullable JsonElement e) throws Invalid {
        if (e == null || e.isJsonNull()) return KitScore.Scoring.DEFAULT;
        if (!e.isJsonObject()) throw new Invalid("scoring is not an object");
        JsonObject s = e.getAsJsonObject();
        int threshold = (int) Math.max(-1, Math.min(1000, wholeNumber(s.get("threshold"), "scoring.threshold")));
        int name = (int) Math.max(-1, Math.min(1000, wholeNumber(s.get("name"), "scoring.name")));
        int mix = (int) Math.max(-1, Math.min(1000, wholeNumber(s.get("mix"), "scoring.mix")));
        int full = (int) Math.max(-1, Math.min(1000, wholeNumber(s.get("full"), "scoring.full")));
        int op = (int) Math.max(-1, Math.min(1000, wholeNumber(s.get("op"), "scoring.op")));
        // opMin is optional: a scoring from before it existed means 3
        int opMin = KitScore.Scoring.DEFAULT_OP_MIN;
        if (s.has("opMin") && !s.get("opMin").isJsonNull()) opMin = (int) Math.max(-1, Math.min(100_000, wholeNumber(s.get("opMin"), "scoring.opMin")));
        if (!KitScore.Scoring.valid(threshold, name, mix, full, op, opMin)) throw new Invalid("scoring is out of range");
        return new KitScore.Scoring(threshold, name, mix, full, op, opMin);
    }

    private static long wholeNumber(@Nullable JsonElement e, String what) throws Invalid {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new Invalid(what + " is not a number");
        JsonPrimitive p = e.getAsJsonPrimitive();
        String text = p.getAsString();
        if (text.length() > 15 || !text.matches("-?[0-9]+")) throw new Invalid(what + " is not a whole number");
        return Long.parseLong(text);
    }

    /** The object the server sends, and the cache file keeps. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("rev", rev);
        o.addProperty("hash", hash);
        if (kitsChest == null) o.add("kitsChest", com.google.gson.JsonNull.INSTANCE);
        else o.addProperty("kitsChest", kitsChest);
        JsonObject r = new JsonObject();
        for (Map.Entry<String, String> e : rules.entrySet()) r.addProperty(e.getKey(), e.getValue());
        o.add("rules", r);
        JsonObject s = new JsonObject();
        s.addProperty("threshold", scoring.threshold());
        s.addProperty("name", scoring.name());
        s.addProperty("mix", scoring.mix());
        s.addProperty("full", scoring.full());
        s.addProperty("op", scoring.op());
        s.addProperty("opMin", scoring.opMin());
        o.add("scoring", s);
        if (types != null) {
            JsonObject t = new JsonObject();
            t.addProperty("hash", types.hash());
            com.google.gson.JsonArray cats = new com.google.gson.JsonArray();
            for (Category c : types.categories()) {
                JsonObject co = new JsonObject();
                co.addProperty("id", c.id());
                co.addProperty("name", c.name());
                cats.add(co);
            }
            t.add("categories", cats);
            JsonObject chestsJson = new JsonObject();
            for (var e : types.chests().entrySet()) chestsJson.addProperty(e.getKey(), e.getValue());
            t.add("chests", chestsJson);
            JsonObject as = new JsonObject();
            for (var e : types.treatAs().entrySet()) as.addProperty(e.getKey(), e.getValue());
            t.add("treatAs", as);
            com.google.gson.JsonArray tpl = new com.google.gson.JsonArray();
            for (KitTemplate k : types.templates()) tpl.add(k.toJson());
            t.add("templates", tpl);
            o.add("types", t);
        }
        return o;
    }
}
