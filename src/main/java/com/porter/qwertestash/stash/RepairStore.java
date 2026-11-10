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
 * The repair settings the bot has cached, per (server, stash), in {@code plugins/qwerte-stash/repair-settings.json}, written atomically. Every entry read
 * back is checked like one from the server; one that fails is dropped. {@link #version()} goes up on every change so the sorter plans again. Thread-safe.
 */
public final class RepairStore {
    public static final int MAX_STASHES = 64;
    private static final int FILE_VERSION = 1;
    private static final long MAX_FILE_BYTES = 1024L * 1024;
    private static final Gson GSON = new Gson();

    private record Key(String server, String stash) {
    }

    private static volatile @Nullable RepairStore shared;

    public static RepairStore shared() {
        RepairStore s = shared;
        if (s == null) {
            synchronized (RepairStore.class) {
                s = shared;
                if (s == null) shared = s = new RepairStore(Path.of("plugins", "qwerte-stash", "repair-settings.json"));
            }
        }
        return s;
    }

    private final Path file;
    private final Map<Key, RepairSettings> entries = new LinkedHashMap<>();
    // The highest website run and stop ids this bot acknowledged, per stash: a restart must not run an old request again
    private final Map<Key, long[]> ids = new LinkedHashMap<>();
    private volatile int version;

    public RepairStore(Path file) {
        this.file = file;
        load();
    }

    public int version() {
        return version;
    }

    public synchronized @Nullable RepairSettings get(String server, String stash) {
        return entries.get(new Key(server, stash));
    }

    /** The run and stop ids acknowledged for this stash ({0, 0} when none). */
    public synchronized long[] ids(String server, String stash) {
        long[] v = ids.get(new Key(server, stash));
        return v == null ? new long[] {0, 0} : v.clone();
    }

    /** Remembers the ids acknowledged (the larger of what is kept and what is given) and saves when they changed. */
    public synchronized void putIds(String server, String stash, long run, long stop) {
        Key key = new Key(server, stash);
        long[] old = ids.get(key);
        long r = Math.max(run, old == null ? 0 : old[0]), s = Math.max(stop, old == null ? 0 : old[1]);
        if (old != null && old[0] == r && old[1] == s) return;
        ids.remove(key);
        ids.put(key, new long[] {r, s});
        while (ids.size() > MAX_STASHES) ids.remove(ids.keySet().iterator().next());
        save();
    }

    /** Replaces what is cached for this stash and saves. @return true when the settings changed (and the version went up) */
    public synchronized boolean put(String server, String stash, RepairSettings settings) {
        Key key = new Key(server, stash);
        if (settings.equals(entries.get(key))) return false;
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

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("file too big");
            JsonElement root = com.porter.qwertestash.manager.SafeJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("stashes") || !root.getAsJsonObject().get("stashes").isJsonArray()) throw new IOException("not a repair settings file");
            int dropped = 0;
            for (JsonElement e : root.getAsJsonObject().getAsJsonArray("stashes")) {
                try {
                    if (!e.isJsonObject()) throw new RepairSettings.Invalid("not an object");
                    JsonObject o = e.getAsJsonObject();
                    String server = text(o, "server"), stash = text(o, "stash");
                    if (entries.size() >= MAX_STASHES) throw new RepairSettings.Invalid("too many stashes");
                    entries.put(new Key(server, stash), RepairSettings.parse(o));
                } catch (RepairSettings.Invalid | RuntimeException ex) {
                    dropped++;
                }
            }
            if (dropped > 0) warn("Qwerte Stash: dropped {} unreadable entries of the repair settings cache", dropped);
            JsonElement idList = root.getAsJsonObject().get("ids");
            if (idList != null && idList.isJsonArray()) {
                for (JsonElement e : idList.getAsJsonArray()) {
                    try {
                        JsonObject o = e.getAsJsonObject();
                        long run = whole(o, "runDone"), stop = whole(o, "stopDone");
                        if (ids.size() < MAX_STASHES) ids.put(new Key(text(o, "server"), text(o, "stash")), new long[] {run, stop});
                    } catch (RepairSettings.Invalid | RuntimeException ex) {
                        // A damaged entry is dropped: the server sends the latest request again at worst
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            warn("Qwerte Stash: couldn't read the repair settings cache ({}), starting with none", ex.getClass().getSimpleName());
            entries.clear();
        }
    }

    private static long whole(JsonObject o, String name) throws RepairSettings.Invalid {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !e.getAsString().matches("[0-9]{1,15}")) throw new RepairSettings.Invalid(name);
        return Long.parseLong(e.getAsString());
    }

    private static String text(JsonObject o, String name) throws RepairSettings.Invalid {
        JsonElement e = o.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() || e.getAsString().length() > 256) throw new RepairSettings.Invalid(name);
        return e.getAsString();
    }

    private void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", FILE_VERSION);
        JsonArray array = new JsonArray();
        for (Map.Entry<Key, RepairSettings> e : entries.entrySet()) {
            JsonObject o = e.getValue().toJson();
            o.addProperty("server", e.getKey().server());
            o.addProperty("stash", e.getKey().stash());
            array.add(o);
        }
        root.add("stashes", array);
        JsonArray idArray = new JsonArray();
        for (Map.Entry<Key, long[]> e : ids.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("server", e.getKey().server());
            o.addProperty("stash", e.getKey().stash());
            o.addProperty("runDone", e.getValue()[0]);
            o.addProperty("stopDone", e.getValue()[1]);
            idArray.add(o);
        }
        root.add("ids", idArray);
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.error("Qwerte Stash: couldn't save the repair settings cache", e);
        }
    }
}
