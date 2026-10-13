package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.porter.qwertestash.QwerteStashPlugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.aquarius.Globals.CONFIG;

/**
 * A named stash: its containers, what was in them when last seen, and which ones are excluded from sorting or used as
 * drop-off points. Saved per server under {@code plugins/qwerte-stash/stashes}, so a stash is only scanned once and
 * then kept up to date as containers are opened.
 */
public class Stash {
    public static class Box {
        public BlockPos pos;
        public List<BlockPos> parts;
        public List<String> labels = new ArrayList<>();
        /** One entry per slot, null for an empty slot. Null when it hasn't been read yet. */
        public Item @Nullable [] contents;
        public boolean excluded;
        public boolean dropOff;

        public transient boolean unreachable;
        public transient int order;

        public Box(BlockPos pos, List<BlockPos> parts) {
            this.pos = pos;
            this.parts = parts;
        }

        public boolean known() {
            return contents != null;
        }

        public int free() {
            int count = 0;
            for (Item item : contents) if (item == null) count++;
            return count;
        }

        /**
         * Joins in sorting: not excluded, read at least once and possible to open.
         */
        public boolean sortable() {
            return known() && !unreachable && !excluded;
        }

        public boolean has(BlockPos pos) {
            return parts.contains(pos);
        }
    }

    public String name;
    public String server;
    public List<Box> boxes = new ArrayList<>();
    private transient boolean dirty;

    public Stash(String name, String server) {
        this.name = name;
        this.server = server;
    }

    public @Nullable Box boxAt(BlockPos pos) {
        for (Box box : boxes) if (box.has(pos)) return box;
        return null;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public int knownCount() {
        return (int) boxes.stream().filter(Box::known).count();
    }

    public int itemCount() {
        int count = 0;
        for (Box box : boxes) {
            if (!box.known()) continue;
            for (Item item : box.contents) if (item != null) count += item.count;
        }
        return count;
    }

    // Searching

    /**
     * One kind of item found in the stash: how many in total, and where.
     */
    public static class Result {
        public final Item sample;
        public final List<Spot> spots = new ArrayList<>();
        public int total;
        int score;

        Result(Item sample) {
            this.sample = sample;
        }

        public Spot best() {
            return spots.stream().max(Comparator.comparingInt(Spot::count)).orElse(null);
        }
    }

    public record Spot(Box box, int count, boolean inShulker) {}

    /**
     * Everything matching {@code query}, best match first, counting items inside shulkers too.
     */
    public List<Result> search(String query) {
        List<Result> results = new ArrayList<>();
        if (Categories.normalize(query).isEmpty()) return results;

        for (Box box : boxes) {
            if (!box.known()) continue;

            for (Item item : box.contents) {
                if (item == null) continue;
                add(results, query, item, box, false);
                for (Item inner : item.contents) add(results, query, inner, box, true);
            }
        }

        results.sort(Comparator.<Result>comparingInt(r -> -r.score).thenComparingInt(r -> -r.total));
        return results;
    }

    private static void add(List<Result> results, String query, Item item, Box box, boolean inShulker) {
        int score = Categories.searchScore(query, item);
        if (score <= 0) return;

        Result result = null;
        for (Result r : results) {
            if (r.sample.sameAs(item)) {
                result = r;
                break;
            }
        }
        if (result == null) {
            result = new Result(item);
            result.score = score;
            results.add(result);
        }

        result.total += item.count;
        for (int i = 0; i < result.spots.size(); i++) {
            Spot s = result.spots.get(i);
            if (s.box() == box && s.inShulker() == inShulker) {
                result.spots.set(i, new Spot(box, s.count() + item.count, inShulker));
                return;
            }
        }
        result.spots.add(new Spot(box, item.count, inShulker));
    }

    // Saving and loading

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

    private static Path folder() {
        return Path.of("plugins", "qwerte-stash", "stashes");
    }

    private static Path file(String name, String server) {
        String safe = (server + "_" + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return folder().resolve(safe + ".json");
    }

    public static String currentServer() {
        String address = CONFIG.client.server.address;
        return address == null || address.isBlank() ? "unknown" : address;
    }

    public static boolean exists(String name) {
        return Files.exists(file(name, currentServer()));
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(file(name, currentServer()));
        } catch (IOException e) {
            QwerteStashPlugin.LOG.error("Couldn't delete stash {}", name, e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(folder());
            Files.writeString(file(name, server), GSON.toJson(this), StandardCharsets.UTF_8);
            dirty = false;
        } catch (IOException e) {
            QwerteStashPlugin.LOG.error("Couldn't save stash {}", name, e);
        }
    }

    /**
     * The stash called {@code name} on this server, or null if there isn't one saved.
     */
    public static @Nullable Stash load(String name) {
        Path path = file(name, currentServer());
        if (!Files.exists(path)) return null;

        try {
            Stash stash = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Stash.class);
            if (stash == null) return null;
            if (stash.boxes == null) stash.boxes = new ArrayList<>();
            stash.boxes.removeIf(box -> box.parts == null || box.parts.isEmpty());
            for (Box box : stash.boxes) {
                if (box.labels == null) box.labels = new ArrayList<>();
                if (box.contents != null) {
                    for (Item item : box.contents) {
                        if (item == null) continue;
                        if (item.contents == null) item.contents = new ArrayList<>();
                        if (item.tags == null) item.tags = new ArrayList<>();
                        if (item.enchants == null) item.enchants = new ArrayList<>();
                    }
                }
            }
            return stash;
        } catch (IOException | RuntimeException e) {
            QwerteStashPlugin.LOG.error("Couldn't load stash {}", name, e);
            return null;
        }
    }
}
