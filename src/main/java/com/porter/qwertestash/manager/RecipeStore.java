package com.porter.qwertestash.manager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.QwerteStashPlugin;
import com.porter.qwertestash.orders.Recipes;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * The recipe table the bot crafts by (CRAFT-CONTRACT.md "Recipe tables for the bots"): fetched from the Stash Manager server
 * ({@code GET /api/v1/bot/recipes?mc=}), checked, and cached in the plugin's data folder, atomically, as
 * {@code recipes-<mc>.json}. Nothing is shipped in the plugin jar.
 * <ul>
 *     <li>The cache file holds {@code { format, mc, hash, table }}. On load the tableHash is computed again from the table and must
 *     equal the {@code hash} stored with it, else the file is discarded (damaged or changed by hand).</li>
 *     <li>A table from the server is checked structurally (sizes, ids, cell counts) before anything is parsed, its hash computed,
 *     and, when the answer carried an ETag, compared with it.</li>
 *     <li>Works on any thread; the file is written and moved over the old one in one step.</li>
 * </ul>
 */
public final class RecipeStore {
    public static final int FORMAT = 1;
    public static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int MAX_RECIPES = 4096, MAX_OTHER = 16384, MAX_ALTERNATIVES = 1024;
    private static final Pattern ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]{1,128}$");
    private static final Pattern MC = Pattern.compile("^[0-9A-Za-z._+-]{1,32}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{40}$");
    private static final Gson GSON = new Gson();

    /** Why a table was refused, for the log. */
    public static final class Invalid extends Exception {
        public Invalid(String why) {
            super(why);
        }
    }

    private static volatile @Nullable RecipeStore shared;

    /** The store of the running plugin ({@code plugins/qwerte-stash}). */
    public static RecipeStore shared() {
        RecipeStore s = shared;
        if (s == null) {
            synchronized (RecipeStore.class) {
                s = shared;
                if (s == null) shared = s = new RecipeStore(Path.of("plugins", "qwerte-stash"));
            }
        }
        return s;
    }

    private final Path dir;

    public RecipeStore(Path dir) {
        this.dir = dir;
    }

    /** Whether this is a Minecraft version string the cache file name can carry. */
    public static boolean validMc(@Nullable String mc) {
        return mc != null && MC.matcher(mc).matches();
    }

    public Path file(String mc) {
        return dir.resolve("recipes-" + mc + ".json");
    }

    /** "abc" as {@code "abc"}, the form of an ETag and of If-None-Match. */
    public static String quote(String hash) {
        return "\"" + hash + "\"";
    }

    /** The hash inside an ETag header value (quotes and a weak marker taken off), or null when it is not a 40 digit hex hash. */
    public static @Nullable String hashOfEtag(@Nullable String etag) {
        if (etag == null) return null;
        String e = etag.trim();
        if (e.startsWith("W/")) e = e.substring(2);
        if (e.length() >= 2 && e.startsWith("\"") && e.endsWith("\"")) e = e.substring(1, e.length() - 1);
        return HASH.matcher(e).matches() ? e : null;
    }

    /**
     * The cached table for this Minecraft version, or null when there is none or the file is not trusted: too big, not what it
     * should be, or its hash computed now is not the one stored with it (the file is deleted then).
     */
    public @Nullable Recipes load(String mc) {
        if (!validMc(mc)) return null;
        Path file = file(mc);
        try {
            if (!Files.isRegularFile(file)) return null;
            if (Files.size(file) > MAX_BYTES) throw new Invalid("file too big");
            JsonElement root = SafeJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) throw new Invalid("not an object");
            JsonObject o = root.getAsJsonObject();
            if (!o.has("hash") || !o.get("hash").isJsonPrimitive() || !HASH.matcher(o.get("hash").getAsString()).matches()) throw new Invalid("no hash");
            if (!o.has("table") || !o.get("table").isJsonObject()) throw new Invalid("no table");
            Recipes table = check(mc, o.getAsJsonObject("table"));
            if (!table.hash().equals(o.get("hash").getAsString())) throw new Invalid("the hash doesn't match the table");
            return table;
        } catch (IOException e) {
            // Could not be read now (locked, a disk hiccup): the file is not known to be bad, so it is left alone
            warn("Qwerte Stash: couldn't read the cached recipe table for {} ({})", mc, e.getClass().getSimpleName());
            return null;
        } catch (Invalid | RuntimeException e) {
            warn("Qwerte Stash: discarding the cached recipe table for {} ({})", mc, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // Nothing to do about it
            }
            return null;
        }
    }

    /**
     * Checks a table fetched from the server, computes its hash, and saves it as the cache for {@code mc}.
     * @param etag the ETag header of the answer or null; when it is a hash, it must equal the one computed from the body
     * @return the table
     */
    public Recipes accept(String mc, String body, @Nullable String etag) throws Invalid {
        if (!validMc(mc)) throw new Invalid("bad Minecraft version");
        if (body.length() > MAX_BYTES) throw new Invalid("table too big");
        JsonObject root;
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) throw new Invalid("not an object");
            root = e.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new Invalid("not JSON");
        }
        Recipes table = check(mc, root);
        String expected = hashOfEtag(etag);
        if (expected != null && !expected.equals(table.hash())) throw new Invalid("the table's hash " + table.hash() + " is not the ETag's");
        save(mc, root, table.hash());
        return table;
    }

    private void save(String mc, JsonObject table, String hash) throws Invalid {
        JsonObject file = new JsonObject();
        file.addProperty("format", FORMAT);
        file.addProperty("mc", mc);
        file.addProperty("hash", hash);
        file.add("table", table);
        try {
            Files.createDirectories(dir.toAbsolutePath());
            // Written beside the file and moved over it, so a crash or a stop in the middle never leaves half a file
            Path temp = file(mc).resolveSibling(file(mc).getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(file), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file(mc), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file(mc), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new Invalid("couldn't save: " + e.getClass().getSimpleName());
        }
    }

    /** Checks a table's shape and limits, then parses it (the hash is computed from it). */
    public static Recipes check(String mc, JsonObject root) throws Invalid {
        try {
            if (!root.has("version") || !root.get("version").isJsonPrimitive() || !mc.equals(root.get("version").getAsString())) throw new Invalid("not a table for " + mc);
            if (!root.has("recipes") || !root.get("recipes").isJsonArray()) throw new Invalid("no recipes");
            JsonArray recipes = root.getAsJsonArray("recipes");
            if (recipes.isEmpty() || recipes.size() > MAX_RECIPES) throw new Invalid(recipes.size() + " recipes");
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (JsonElement e : recipes) {
                JsonObject r = e.getAsJsonObject();
                String id = text(r, "id");
                if (!ids.add(id)) throw new Invalid("recipe " + id + " twice");
                String type = r.get("type").getAsString();
                text(r, "result");
                if (r.has("count") && !r.get("count").isJsonNull()) {
                    int count = r.get("count").getAsInt();
                    if (count < 1 || count > 99) throw new Invalid("recipe " + id + " makes " + count);
                }
                if (type.equals("shaped")) {
                    int w = r.get("w").getAsInt(), h = r.get("h").getAsInt();
                    if (w < 1 || w > 3 || h < 1 || h > 3) throw new Invalid("recipe " + id + " is " + w + "x" + h);
                    JsonArray cells = r.getAsJsonArray("cells");
                    if (cells.size() != w * h) throw new Invalid("recipe " + id + " has " + cells.size() + " cells");
                    boolean any = false;
                    for (JsonElement c : cells) {
                        if (c.isJsonNull()) continue;
                        alternatives(id, c.getAsJsonArray());
                        any = true;
                    }
                    if (!any) throw new Invalid("recipe " + id + " has no ingredient");
                }
                else if (type.equals("shapeless")) {
                    JsonArray in = r.getAsJsonArray("ingredients");
                    if (in.isEmpty() || in.size() > 9) throw new Invalid("recipe " + id + " has " + in.size() + " ingredients");
                    for (JsonElement c : in) alternatives(id, c.getAsJsonArray());
                }
                else throw new Invalid("recipe " + id + " is of type " + type);
            }
            if (root.has("otherStations")) {
                JsonObject other = root.getAsJsonObject("otherStations");
                if (other.size() > MAX_OTHER) throw new Invalid("too many other stations");
                for (var s : other.entrySet()) if (!ID.matcher(s.getKey()).matches() || !s.getValue().isJsonPrimitive() || s.getValue().getAsString().length() > 32) throw new Invalid("bad station entry");
            }
            return Recipes.fromJson(root);
        } catch (Invalid i) {
            throw i;
        } catch (RuntimeException ex) {
            throw new Invalid("malformed table (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private static String text(JsonObject o, String name) throws Invalid {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || !ID.matcher(e.getAsString()).matches()) throw new Invalid("bad " + name);
        return e.getAsString();
    }

    private static void alternatives(String id, JsonArray alts) throws Invalid {
        if (alts.isEmpty() || alts.size() > MAX_ALTERNATIVES) throw new Invalid("recipe " + id + " has a cell with " + alts.size() + " alternatives");
        for (JsonElement a : alts) {
            if (!a.isJsonPrimitive() || !a.getAsJsonPrimitive().isString() || !ID.matcher(a.getAsString()).matches()) throw new Invalid("recipe " + id + " has a bad ingredient");
        }
    }

    private static void warn(String text, Object a, Object b) {
        if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.warn(text, a, b);
    }
}
