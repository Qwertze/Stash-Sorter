package com.porter.qwertestash.stash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.QwerteStashPlugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The shared kit-types table (KIT-CONTRACT v2, "kit-types.json"): the seven kit types with their words and minimum kinds, and the types of
 * items by id, suffix and prefix. Data, not code: the resource is a byte for byte copy of the contract's file. {@link #fingerprint()} is
 * what every codebase tests. A table that can't be read or whose types are not exactly the seven, in order, is refused: {@link #shared()}
 * is then null and no box is ever a kit. Immutable; any thread.
 */
public final class KitTypes {
    public static final String RESOURCE = "/qwerte-stash/kit-types.json";
    public static final String FINGERPRINT_V1 = "c3ffa89424c6998aebcc40339e9d750df410952beb31e5e2dc05d7a59d663e3e";
    /** The seven types, in tie-break order. */
    public static final List<String> TYPE_IDS = List.of("pvp", "travel", "redstone", "farming", "building", "starter", "other");

    /** @param minKinds the distinct item kinds a kit of this type needs; {@code words} the words in a name that hint at it */
    public record Type(String id, String name, int minKinds, List<String> words) {
    }

    /** Why a table was refused. */
    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    private final List<Type> types;
    private final Map<String, List<String>> items, suffixes, prefixes;
    private final Map<String, Integer> order = new LinkedHashMap<>();

    private KitTypes(List<Type> types, Map<String, List<String>> items, Map<String, List<String>> suffixes, Map<String, List<String>> prefixes) {
        this.types = List.copyOf(types);
        this.items = items;
        this.suffixes = suffixes;
        this.prefixes = prefixes;
        for (int i = 0; i < types.size(); i++) order.put(types.get(i).id(), i);
    }

    private static volatile @Nullable KitTypes shared;
    private static volatile boolean tried;

    /** The table of the running plugin, or null when it can't be read (then no box is ever a kit). Logged once. */
    public static @Nullable KitTypes shared() {
        if (!tried) {
            synchronized (KitTypes.class) {
                if (!tried) {
                    try (InputStream in = KitTypes.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) throw new Invalid("the table is missing from the plugin jar");
                        shared = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                    } catch (IOException | Invalid | RuntimeException e) {
                        if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.warn("Qwerte Stash: the kit types table can't be used ({}); no box is a kit", e.getMessage());
                    }
                    tried = true;
                }
            }
        }
        return shared;
    }

    public static KitTypes parse(Reader reader) throws Invalid {
        try {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<Type> types = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("types")) {
                JsonObject o = e.getAsJsonObject();
                List<String> words = new ArrayList<>();
                for (JsonElement w : o.getAsJsonArray("words")) words.add(w.getAsString());
                types.add(new Type(o.get("id").getAsString(), o.get("name").getAsString(), o.get("minKinds").getAsInt(), List.copyOf(words)));
            }
            List<String> ids = new ArrayList<>();
            for (Type t : types) ids.add(t.id());
            if (!ids.equals(TYPE_IDS)) throw new Invalid("the types are not the seven expected, in order");
            return new KitTypes(types, lists(root.getAsJsonObject("items"), ids), lists(root.getAsJsonObject("suffixes"), ids), lists(root.getAsJsonObject("prefixes"), ids));
        } catch (Invalid i) {
            throw i;
        } catch (RuntimeException e) {
            throw new Invalid("malformed table");
        }
    }

    private static Map<String, List<String>> lists(JsonObject o, List<String> known) throws Invalid {
        Map<String, List<String>> out = new TreeMap<>();
        for (var e : o.entrySet()) {
            List<String> ts = new ArrayList<>();
            JsonArray a = e.getValue().getAsJsonArray();
            for (JsonElement t : a) {
                if (!known.contains(t.getAsString())) throw new Invalid("an unknown type in the table");
                ts.add(t.getAsString());
            }
            out.put(e.getKey(), List.copyOf(ts));
        }
        return out;
    }

    public List<Type> types() {
        return types;
    }

    public @Nullable Type type(String id) {
        Integer i = order.get(id);
        return i == null ? null : types.get(i);
    }

    /** The position of a type in the tie-break order, or -1. */
    public int rank(String id) {
        return order.getOrDefault(id, -1);
    }

    /** "chest" or "minecraft:chest" as "minecraft:chest". */
    public static String full(String id) {
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /**
     * The types of an item id (the first is its primary type): the table's entry; else, for the minecraft namespace, the longest suffix the path
     * ends with, then the longest prefix it starts with; else none.
     */
    public List<String> typesOf(String id) {
        String i = full(id);
        List<String> direct = items.get(i);
        if (direct != null) return direct;
        int colon = i.indexOf(':');
        if (!i.substring(0, colon).equals("minecraft")) return List.of();
        String path = i.substring(colon + 1);
        String best = null;
        for (String s : suffixes.keySet()) if (path.endsWith(s) && (best == null || s.length() > best.length())) best = s;
        if (best != null) return suffixes.get(best);
        for (String p : prefixes.keySet()) if (path.startsWith(p) && (best == null || p.length() > best.length())) best = p;
        return best == null ? List.of() : prefixes.get(best);
    }

    /**
     * Lowercase SHA-256 hex of: for each type in file order {@code "type:id:minKinds:words sorted joined by ,\n"}; then {@code "item:id:types\n"} per item
     * sorted by id; then {@code "suffix:..."} by key; then {@code "prefix:..."} by key.
     */
    public String fingerprint() {
        StringBuilder s = new StringBuilder();
        for (Type t : types) {
            List<String> w = new ArrayList<>(t.words());
            java.util.Collections.sort(w);
            s.append("type:").append(t.id()).append(':').append(t.minKinds()).append(':').append(String.join(",", w)).append('\n');
        }
        for (var e : items.entrySet()) s.append("item:").append(e.getKey()).append(':').append(String.join(",", e.getValue())).append('\n');
        for (var e : suffixes.entrySet()) s.append("suffix:").append(e.getKey()).append(':').append(String.join(",", e.getValue())).append('\n');
        for (var e : prefixes.entrySet()) s.append("prefix:").append(e.getKey()).append(':').append(String.join(",", e.getValue())).append('\n');
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
