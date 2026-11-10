package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.porter.qwertestash.QwerteStashPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;

/**
 * When kit restock started its jobs (epoch milliseconds), for the per-hour cap (KIT-CONTRACT v2, revision 4, "Restock"). Kept in
 * {@code plugins/qwerte-stash/kit-restock.json} and written atomically, so a restart does not reset the cap. Stamps older than an hour are dropped.
 * Any thread (synchronised); the file holds numbers only.
 */
public final class KitRestockStamps {
    public static final long HOUR_MS = 60 * 60 * 1000L;
    private static final int MAX = 600;
    private static KitRestockStamps shared;

    public static synchronized KitRestockStamps shared() {
        if (shared == null) shared = new KitRestockStamps(Path.of("plugins", "qwerte-stash", "kit-restock.json"));
        return shared;
    }

    private final Path file;
    private final ArrayDeque<Long> stamps = new ArrayDeque<>();

    public KitRestockStamps(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 64 * 1024) return;
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!e.isJsonArray()) return;
            for (JsonElement x : e.getAsJsonArray()) {
                if (stamps.size() >= MAX) break;
                if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isNumber()) stamps.add(x.getAsLong());
            }
            // Oldest first; a stamp more than an hour ahead of the clock is a damaged record and is dropped, one a little ahead counts as now
            long now = System.currentTimeMillis();
            java.util.List<Long> sorted = new java.util.ArrayList<>(stamps);
            java.util.Collections.sort(sorted);
            stamps.clear();
            for (long s : sorted) if (s <= now + HOUR_MS) stamps.add(Math.min(s, now));
        } catch (IOException | RuntimeException ex) {
            // A broken file is an empty record: the cap starts over
            stamps.clear();
        }
    }

    /** How many jobs started in the hour before {@code now}. */
    public synchronized int lastHour(long now) {
        prune(now);
        return stamps.size();
    }

    /** Records a job start and saves. */
    public synchronized void add(long now) {
        prune(now);
        stamps.addLast(now);
        while (stamps.size() > MAX) stamps.pollFirst();
        save();
    }

    private void prune(long now) {
        // Stamps from the future (a clock set back) count as now
        while (!stamps.isEmpty() && now - stamps.peekFirst() > HOUR_MS) stamps.pollFirst();
    }

    private void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonArray a = new JsonArray();
            for (long s : stamps) a.add(s);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, a.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.warn("Could not save the kit restock record: {}", ex.toString());
        }
    }
}
