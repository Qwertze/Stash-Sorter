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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The shared kit-items table (KIT-CONTRACT.md v1.1, "kit-items.json"): the OP items and the item groups of the item mix. Data, not code: the
 * resource is a byte for byte copy of the contract's file. {@link #fingerprint()} is what the tests (and every other codebase) check.
 * Ids are registry ids with the namespace ("minecraft:totem_of_undying"). Immutable; any thread.
 */
public final class KitItems {
    /** An item mix group: it counts when the box holds at least {@code min} distinct ids of {@code items}. */
    public record Group(String id, int min, Set<String> items) {
    }

    public static final String RESOURCE = "/qwerte-stash/kit-items.json";

    /** The table as the contract defines it for table version 1. */
    public static final String FINGERPRINT_V1 = "ed47369c84e5a477e55669f4a9be7f47217a50499d7955d2da7c1959d07e1bf7";

    private final Set<String> op;
    private final List<Group> groups;

    public KitItems(Set<String> op, List<Group> groups) {
        this.op = Set.copyOf(op);
        this.groups = List.copyOf(groups);
    }

    /** No OP items and no groups: nothing is ever worth mix or op points. */
    public static final KitItems EMPTY = new KitItems(Set.of(), List.of());

    private static volatile @Nullable KitItems shared;

    /** The table of the running plugin, loaded from the resource on first use (the plugin asks for it at load). Never null. */
    public static KitItems shared() {
        KitItems t = shared;
        if (t == null) {
            synchronized (KitItems.class) {
                t = shared;
                if (t == null) shared = t = loadResource();
            }
        }
        return t;
    }

    private static KitItems loadResource() {
        try (InputStream in = KitItems.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("resource missing");
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                KitItems table = parse(JsonParser.parseReader(reader));
                if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.debug("Qwerte Stash: kit items table loaded ({} OP items, {} groups, fingerprint {})", table.op.size(), table.groups.size(), table.fingerprint());
                return table;
            }
        } catch (IOException | RuntimeException e) {
            if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.error("Qwerte Stash: couldn't read the kit items table ({}), kits score without item points", e.toString());
            return EMPTY;
        }
    }

    /** Reads the table's JSON ({@code op}: ids, {@code groups}: {id, min, items}). @throws RuntimeException on a wrong shape */
    public static KitItems parse(JsonElement root) {
        JsonObject o = root.getAsJsonObject();
        Set<String> op = new LinkedHashSet<>();
        for (JsonElement e : o.getAsJsonArray("op")) op.add(e.getAsString());
        List<Group> groups = new ArrayList<>();
        for (JsonElement g : o.getAsJsonArray("groups")) {
            JsonObject go = g.getAsJsonObject();
            JsonArray items = go.getAsJsonArray("items");
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement e : items) ids.add(e.getAsString());
            int min = go.get("min").getAsInt();
            if (min < 1) throw new IllegalArgumentException("group min");
            groups.add(new Group(go.get("id").getAsString(), min, ids));
        }
        return new KitItems(op, groups);
    }

    public Set<String> op() {
        return op;
    }

    public List<Group> groups() {
        return groups;
    }

    /**
     * SHA-256 (lowercase hex) of the UTF-8 string "op:" + sorted(op).join(",") + "\n", then id + ":" + min + ":" + sorted(items).join(",") + "\n" for
     * each group sorted by id.
     */
    public String fingerprint() {
        StringBuilder sb = new StringBuilder("op:").append(String.join(",", op.stream().sorted().toList())).append('\n');
        for (Group g : groups.stream().sorted(Comparator.comparing(Group::id)).toList()) {
            sb.append(g.id()).append(':').append(g.min()).append(':').append(String.join(",", g.items().stream().sorted().toList())).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
