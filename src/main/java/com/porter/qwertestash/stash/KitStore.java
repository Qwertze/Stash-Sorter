package com.porter.qwertestash.stash;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.QwerteStashPlugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The kit settings the bot has cached, per (server, stash): the only source while the Stash Manager server can't be reached. Kept in
 * {@code plugins/qwerte-stash/kit-settings.json} and written atomically (a temp file moved over it). Every entry read back is
 * checked like one that comes from the server; one that fails is dropped. {@link #version()} goes up on every change, so the sorter
 * knows to plan again. Thread-safe.
 */
public final class KitStore {
    /** The most stashes kept (the oldest change goes first). */
    public static final int MAX_STASHES = 64;
    private static final int FILE_VERSION = 1;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private static final Gson GSON = new Gson();

    private record Key(String server, String stash) {
    }

    private static volatile @Nullable KitStore shared;

    /** The store of the running plugin. */
    public static KitStore shared() {
        KitStore s = shared;
        if (s == null) {
            synchronized (KitStore.class) {
                s = shared;
                if (s == null) shared = s = new KitStore(Path.of("plugins", "qwerte-stash", "kit-settings.json"));
            }
        }
        return s;
    }

    private final Path file;
    private final Map<Key, KitSettings> entries = new LinkedHashMap<>();
    private volatile int version;

    public KitStore(Path file) {
        this.file = file;
        load();
    }

    /** How many stashes are cached. */
    public synchronized int size() {
        return entries.size();
    }

    /** Goes up whenever the settings of any stash change. */
    public int version() {
        return version;
    }

    /** What is cached for this stash, or null when nothing is. */
    public synchronized @Nullable KitSettings get(String server, String stash) {
        return entries.get(new Key(server, stash));
    }

    /**
     * Replaces what is cached for this stash and saves. @return true when the settings changed (and the version went up)
     */
    public synchronized boolean put(String server, String stash, KitSettings settings) {
        Key key = new Key(server, stash);
        KitSettings old = entries.get(key);
        if (settings.equals(old)) return false;
        entries.remove(key);
        entries.put(key, settings);
        while (entries.size() > MAX_STASHES) entries.remove(entries.keySet().iterator().next());
        version++;
        save();
        return true;
    }

    private static void warn(String text, Object arg) {
        if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.warn(text, arg);
    }

    private static void error(String text, Throwable t) {
        if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.error(text, t);
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("file too big");
            JsonElement root = com.google.gson.JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("stashes") || !root.getAsJsonObject().get("stashes").isJsonArray()) throw new IOException("not a kit settings file");
            int dropped = 0;
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("stashes")) {
                try {
                    if (!e.isJsonObject()) throw new KitSettings.Invalid("not an object");
                    JsonObject o = e.getAsJsonObject();
                    String server = text(o, "server"), stash = text(o, "stash");
                    if (entries.size() >= MAX_STASHES) throw new KitSettings.Invalid("too many stashes");
                    entries.put(new Key(server, stash), KitSettings.parse(o));
                } catch (KitSettings.Invalid | RuntimeException ex) {
                    dropped++;
                }
            }
            if (dropped > 0) warn("Qwerte Stash: dropped {} unreadable entries of the kit settings cache", dropped);
        } catch (IOException | RuntimeException ex) {
            warn("Qwerte Stash: couldn't read the kit settings cache ({}), starting with none", ex.getClass().getSimpleName());
            entries.clear();
        }
    }

    private static String text(JsonObject o, String name) throws KitSettings.Invalid {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().length() > 256) throw new KitSettings.Invalid(name);
        return e.getAsString();
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        JsonArray array = new JsonArray();
        for (Map.Entry<Key, KitSettings> e : entries.entrySet()) {
            JsonObject o = e.getValue().toJson();
            o.addProperty("server", e.getKey().server());
            o.addProperty("stash", e.getKey().stash());
            array.add(o);
        }
        root.add("stashes", array);
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            // Written beside the file and moved over it, so a crash or a stop in the middle never leaves half a file
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            error("Qwerte Stash: couldn't save the kit settings cache", e);
        }
    }
}
