package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.porter.qwertestash.QwerteStashPlugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

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
        /** Never a source of stacks or shulkers for compacting. */
        public boolean noCompact;
        /** Saved contents that have not been read again since the bot came online. */
        public transient boolean stale;
        /** The text the sorter itself wrote on this container's sign, and where; null for a sign a player put up. */
        public @Nullable String autoLabel;
        public @Nullable BlockPos autoSignPos;

        /** The sorter couldn't put a sign on this container this run. */
        public transient boolean labelFailed;
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

    /** A shulker with stacks in it the bot put down and could not pick up again. */
    public static class Orphan {
        public BlockPos pos;
        public String item;
        public int stacks;
        public String when;
        /** The dimension it stands in; null in files saved before this was kept. */
        public @Nullable String dimension;

        public Orphan(BlockPos pos, String item, int stacks, String when) {
            this.pos = pos;
            this.item = item;
            this.stacks = stacks;
            this.when = when;
            this.dimension = currentDimension();
        }
    }

    public List<Orphan> orphans = new ArrayList<>();

    /**
     * The compaction plan that was under way, as saved in {@link InFlight}.
     */
    public static class CompactState {
        public String key;
        public Item reference;
        public boolean topUp;
        public int startSize, target;
        public @Nullable BlockPos sourceBox;
        public int sourceSlot = -1;
        public @Nullable BlockPos placedPos;
        public int placedSize;
        public int jobs;
        public boolean aborting;
    }

    /**
     * What the bot was in the middle of when it went offline or was stopped with a shulker of its own standing in the
     * world, so that the next start can finish it: open the shulker, finish packing or cleaning, break it, pick it up
     * and put it away. Kept in the stash file; null when nothing is under way.
     */
    public static class InFlight {
        public static final int VERSION = 1;
        public int version = VERSION;
        public String savedAt;
        public @Nullable String dimension;
        /** Fill or Clean. */
        public String job;
        /** The step it was at: Hold, Place, Open, Work, Break, Collect, Return or Leave. */
        public @Nullable String handle;
        public @Nullable String returnTo;
        /** Where the shulker stands. */
        public @Nullable BlockPos placed;
        /** The block it is, like {@code red_shulker_box}. */
        public @Nullable String placedItemId;
        public int placedStacks = -1;
        public @Nullable String fillKey;
        public @Nullable String cleanMainId;
        public @Nullable BlockPos lastGoodStand;
        /** The container the job was at, if it had one. */
        public @Nullable BlockPos target;
        public @Nullable CompactState compact;
    }

    /** The carry slots holding things the sorter took from the stash: slot index to item id. */
    public Map<Integer, String> held = new HashMap<>();
    public @Nullable InFlight inFlight;
    /** Where the live held map is read from when saving, set by the sorter. */
    public transient @Nullable Supplier<Map<Integer, String>> heldSource;

    /**
     * The perimeter: two corners (any order) and the dimension they were set in. One immutable object, so both corners
     * always change together.
     */
    public static final class Area {
        public final int x1, y1, z1, x2, y2, z2;
        public final @Nullable String dimension;

        public Area(int[] a, int[] b, @Nullable String dimension) {
            this.x1 = a[0];
            this.y1 = a[1];
            this.z1 = a[2];
            this.x2 = b[0];
            this.y2 = b[1];
            this.z2 = b[2];
            this.dimension = dimension;
        }
    }

    public @Nullable Area area;
    /** A corner set on its own with {@code area here1} / {@code here2}, waiting for the other. */
    public int @Nullable [] cornerA, cornerB;

    /** The dimension the bot is in, or null if that isn't known right now. */
    public static @Nullable String currentDimension() {
        try {
            return com.aquarius.feature.player.World.getCurrentDimension().name();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A perimeter is set, in whatever dimension. */
    public boolean areaSet() {
        return area != null;
    }

    /** A perimeter is set and applies here: the bot is in the dimension it was set in. */
    public boolean hasArea() {
        Area a = area;
        if (a == null) return false;
        String now = currentDimension();
        return a.dimension == null || now == null || a.dimension.equals(now);
    }
    /** The largest perimeter: width, height, depth. */
    public static final int MAX_WIDTH = 256, MAX_HEIGHT = 128, MAX_DEPTH = 256;

    /**
     * The perimeter as minX, minY, minZ, maxX, maxY, maxZ (inclusive blocks), or null if there is none.
     */
    public int @Nullable [] bounds() {
        Area a = area;
        if (a == null || !hasArea()) return null;
        return boundsOf(a);
    }

    /** minX, minY, minZ, maxX, maxY, maxZ of a perimeter. */
    public static int[] boundsOf(Area a) {
        return new int[] {Math.min(a.x1, a.x2), Math.min(a.y1, a.y2), Math.min(a.z1, a.z2), Math.max(a.x1, a.x2), Math.max(a.y1, a.y2), Math.max(a.z1, a.z2)};
    }

    /** Width, height and depth of the perimeter. */
    public static int[] sizeOf(int[] b) {
        return new int[] {b[3] - b[0] + 1, b[4] - b[1] + 1, b[5] - b[2] + 1};
    }

    /** "[x1, y1, z1] to [x2, y2, z2] (W×H×D)" */
    public static String describe(int[] b) {
        int[] size = sizeOf(b);
        return "[" + b[0] + ", " + b[1] + ", " + b[2] + "] to [" + b[3] + ", " + b[4] + ", " + b[5] + "] (" + size[0] + "×" + size[1] + "×" + size[2] + ")";
    }

    /** Whether the block is inside the perimeter widened by {@code margin} blocks. True if there is no perimeter. */
    public boolean inArea(BlockPos pos, int margin) {
        int[] b = bounds();
        return b == null || (pos.x() >= b[0] - margin && pos.x() <= b[3] + margin && pos.y() >= b[1] - margin && pos.y() <= b[4] + margin
            && pos.z() >= b[2] - margin && pos.z() <= b[5] + margin);
    }

    /** A container belongs to the perimeter if any block of it is inside. */
    public boolean boxInArea(Box box) {
        for (BlockPos part : box.parts) if (inArea(part, 0)) return true;
        return false;
    }

    /**
     * Drops the containers outside the perimeter, with what is saved for them.
     * @return how many
     */
    public int dropOutsideArea() {
        if (!hasArea()) return 0;
        int before = boxes.size();
        if (boxes.removeIf(box -> !boxInArea(box))) markDirty();
        return before - boxes.size();
    }
    public String name;
    public String server;
    // Copy-on-write: searches and commands iterate it from other threads while the sorter adds and drops boxes
    public List<Box> boxes = new CopyOnWriteArrayList<>();
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
        return folder().resolve(baseName(name, server) + ".json");
    }

    private static String baseName(String name, String server) {
        return (server + "_" + name).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    /** Where the readable list of what is in the stash is kept. */
    public Path indexPath() {
        return folder().resolve(baseName(name, server) + ".index.txt");
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
            Supplier<Map<Integer, String>> source = heldSource;
            if (source != null) held = new HashMap<>(source.get());
            // Written beside the file and moved over it, so a crash or a stop in the middle never leaves half a stash
            Path target = file(name, server);
            Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            StashIndex.touch(this);
        } catch (IOException e) {
            QwerteStashPlugin.LOG.error("Couldn't save stash {}", name, e);
        }
    }

    /**
     * After loading: a record of a version this code doesn't know is dropped, and the saved item in a compaction plan
     * gets the lists the rest of the code relies on.
     */
    private void normalizeInFlight() {
        InFlight record = inFlight;
        if (record == null) return;
        if (record.version != InFlight.VERSION) {
            QwerteStashPlugin.LOG.warn("Qwerte Stash: ignoring a saved in-flight record of version {}", record.version);
            inFlight = null;
            return;
        }
        CompactState compact = record.compact;
        if (compact != null && compact.reference != null) {
            Item reference = compact.reference;
            if (reference.contents == null) reference.contents = new ArrayList<>();
            reference.normalizeName();
            if (reference.tags == null) reference.tags = new ArrayList<>();
            if (reference.enchants == null) reference.enchants = new ArrayList<>();
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
            if (stash.orphans == null) stash.orphans = new ArrayList<>();
            if (stash.held == null) stash.held = new HashMap<>();
            stash.normalizeInFlight();
            stash.boxes = stash.boxes == null ? new CopyOnWriteArrayList<>() : new CopyOnWriteArrayList<>(stash.boxes);
            stash.boxes.removeIf(box -> box == null || box.parts == null || box.parts.isEmpty());
            for (Box box : stash.boxes) {
                if (box.labels == null) box.labels = new ArrayList<>();
                if (box.contents != null) {
                    for (Item item : box.contents) {
                        if (item == null) continue;
                        if (item.contents == null) item.contents = new ArrayList<>();
                        item.normalizeName();
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
