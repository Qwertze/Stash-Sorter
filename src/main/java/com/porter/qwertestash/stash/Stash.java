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
        /** Excluded by the sorter because the perimeter does not hold it (a read container outside it is kept, not deleted); undone when it does. */
        public boolean outsideSkip;
        public boolean dropOff;
        /** Never a source of stacks or shulkers for compacting. */
        public boolean noCompact;
        /** The delivery container for stash orders: never sorted into, never a source, not in the index. Kept in step with the config. */
        public boolean delivery;
        /** A chest of the delivery storage that the bot put up above the delivery container (1.9.0); also {@link #delivery}. */
        public boolean deliveryExtra;
        /** When the contents were last read (ms since 1970), 0 if never or not known. A fresh read is not repeated on a restart. */
        public long readAt;
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

        /** The dimension the bot was in when it read this container this session (not saved). */
        public transient @Nullable String readIn;

        public boolean known() {
            return contents != null;
        }

        /** The survey may read it: not the delivery container, not excluded (by the user or by the perimeter), unread or stale. */
        public boolean wantsRead() {
            return !delivery && !excluded && (!known() || stale);
        }

        /** `stash exclude` and `stash dropoff` change nothing on a box the perimeter skips (it would be skipped again at once). */
        public boolean marksBlocked() {
            return outsideSkip;
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
        /** How often this shulker was gone back to after a problem (0: never). Read back so a restart keeps the cap and the quiet wait. */
        public int recoveries;
        public @Nullable CompactState compact;
    }

    /**
     * The delivery storage (1.9.0): the delivery container and the double chests put up above it, which of them are full. Positions
     * only; never shown on the website. Null in files saved before.
     */
    public List<com.porter.qwertestash.orders.DeliveryBook.Entry> deliveryStorage = new CopyOnWriteArrayList<>();

    /** The stash order being worked on: its plan and progress, so a restart carries on the same plan. Null when none. */
    public com.porter.qwertestash.orders.OrderFlow.@Nullable Saved activeOrder;

    /**
     * Inventory moves of a repair trip (armour and off hand stored away, an item and bottles taken in hand) not yet undone, as
     * "from>to" window slots (see Repair.formatLedger): after a restart in the middle of a trip they are undone first. Null or
     * empty when none (1.13.0).
     */
    public @Nullable List<String> repairGear;

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
    /**
     * The dimension the stash's containers stand in, recorded when one is first read (null in files saved before 1.7.2,
     * until the next read). Containers are never forgotten as broken unless the bot is in this dimension.
     */
    public @Nullable String dimension;
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
        return hasAreaIn(currentDimension());
    }

    /** As {@link #hasArea()} for a given dimension of the bot (null: not known, so the area applies); "minecraft:overworld" is "overworld". */
    public boolean hasAreaIn(@Nullable String now) {
        Area a = area;
        if (a == null) return false;
        return a.dimension == null || now == null || Dimensions.same(a.dimension, now);
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
     * Drops the never-read containers outside the perimeter (not the delivery one); read ones are kept and skipped ({@link #skipOutsideArea}).
     * @return how many were dropped
     */
    public int dropOutsideArea() {
        int removed = 0;
        if (hasArea()) {
            // Only a container that was never read goes: a read one is kept (with what is saved for it) and skipped
            int before = boxes.size();
            if (boxes.removeIf(box -> neverRead(box) && !userExcluded(box) && !boxInArea(box) && !box.delivery)) markDirty();
            removed = before - boxes.size();
        }
        skipOutsideArea();
        return removed;
    }

    /** A rescan: every container that is not excluded is read again. */
    public void markAllStale() {
        for (Box box : boxes) if (!box.excluded) box.stale = true;
    }

    /**
     * The one test for a container that counts for the stash as it is now: not a read container kept in the file but skipped
     * because the perimeter does not hold it. Everything that lists, plans from or counts containers asks this (the sorter, orders,
     * compacting and crafting also skip it through {@code excluded}, which {@link #skipOutsideArea} sets).
     */
    public boolean inScope(Box box) {
        return !box.outsideSkip;
    }

    /**
     * Keeps and skips: a container outside the perimeter that was read is marked excluded (so it is not surveyed, sorted or picked
     * from) and stays in the file; one that the perimeter holds again, or any when there is no perimeter, is let back in. Never
     * touches a container the user excluded, nor the delivery container.
     * @return how many were newly skipped
     */
    public int skipOutsideArea() {
        boolean area = hasArea();
        int skipped = 0;
        for (Box box : boxes) {
            boolean outside = area && !box.delivery && !boxInArea(box);
            if (outside && !box.excluded) {
                box.excluded = true;
                box.outsideSkip = true;
                skipped++;
                markDirty();
            }
            else if (!outside && box.outsideSkip) {
                box.excluded = false;
                box.outsideSkip = false;
                markDirty();
            }
        }
        return skipped;
    }

    /** Without a perimeter, a never-read container this far (blocks, flat) from every read one cannot belong to the stash. */
    public static final int FOREIGN_BLOCKS = 20000;
    /** How many read containers (not delivery ones) it takes to judge where the stash is. */
    public static final int FOREIGN_REFERENCES = 5;

    /** Excluded by the user (not by the sorter for being outside the perimeter): never deleted by any prune. */
    public static boolean userExcluded(Box box) {
        return box.excluded && !box.outsideSkip;
    }

    /** Never read: the contents are not known and were never read. */
    public static boolean neverRead(Box box) {
        return box.contents == null && box.readAt == 0;
    }

    /**
     * Which containers cannot belong to this stash, for the caller to remove. Only containers that were never read are ever
     * candidates (a read one was in the stash once, and is kept even when it looks strange). With a perimeter: never-read ones
     * outside it (delivery containers excepted). With no perimeter: never-read ones more than {@link #FOREIGN_BLOCKS} from the nearest
     * read, non-delivery container, and only when there are {@link #FOREIGN_REFERENCES} or more of those. Removes nothing itself.
     */
    public List<Box> foreignBoxes() {
        List<Box> gone = new ArrayList<>();
        if (hasArea()) {
            for (Box box : boxes) if (neverRead(box) && !userExcluded(box) && !box.delivery && !boxInArea(box)) gone.add(box);
        }
        else if (area == null) {
            List<Box> reference = new ArrayList<>();
            for (Box box : boxes) if (!neverRead(box) && !box.delivery && !box.deliveryExtra) reference.add(box);
            if (reference.size() >= FOREIGN_REFERENCES) {
                for (Box box : boxes) {
                    if (!neverRead(box) || userExcluded(box) || box.delivery || box.deliveryExtra) continue;
                    double nearest = Double.MAX_VALUE;
                    for (Box r : reference) nearest = Math.min(nearest, Math.hypot(box.pos.x() - r.pos.x(), box.pos.z() - r.pos.z()));
                    if (nearest > FOREIGN_BLOCKS) gone.add(box);
                }
            }
        }
        return gone;
    }

    /**
     * Whether a container can not be part of this stash: outside the perimeter, or (no perimeter) more than {@link #FOREIGN_BLOCKS}
     * from every container that was read (needs one). Only distance and the perimeter count, never whether the chunk is loaded.
     */
    public boolean implausible(Box box) {
        if (hasArea()) return !boxInArea(box) && !box.delivery;
        if (area != null) return false;
        double nearest = Double.MAX_VALUE;
        for (Box r : boxes) {
            if (neverRead(r) || r.delivery || r.deliveryExtra) continue;
            nearest = Math.min(nearest, Math.hypot(box.pos.x() - r.pos.x(), box.pos.z() - r.pos.z()));
        }
        return nearest != Double.MAX_VALUE && nearest > FOREIGN_BLOCKS;
    }

    /**
     * Copies the stash file beside itself before a save that removes containers, so nothing is lost for good. The newest three
     * copies are kept, under their own name ({@code .prune-bak-}) so they never push out a copy of an unreadable file.
     * @return false if no copy could be made (the caller must not go on to remove anything)
     */
    public boolean backUp() {
        Path path = file(name, server);
        if (!Files.exists(path)) return true;
        try {
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String prefix = path.getFileName() + ".prune-bak-";
            Files.copy(path, path.resolveSibling(prefix + stamp), StandardCopyOption.REPLACE_EXISTING);
            List<Path> copies = new ArrayList<>();
            try (var files = Files.list(path.getParent())) {
                files.filter(q -> q.getFileName().toString().startsWith(prefix)).forEach(copies::add);
            }
            copies.sort(Comparator.comparing((Path q) -> q.getFileName().toString()).reversed());
            for (int i = 3; i < copies.size(); i++) Files.deleteIfExists(copies.get(i));
            return true;
        } catch (IOException | RuntimeException e) {
            QwerteStashPlugin.LOG.error("Qwerte Stash: couldn't back up the stash file {}", path.getFileName(), e);
            return false;
        }
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
            // The delivery container is where orders are put, not part of the stash: never listed (with its position)
            if (!box.known() || box.delivery) continue;

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
            stash.deliveryStorage = stash.deliveryStorage == null ? new CopyOnWriteArrayList<>() : new CopyOnWriteArrayList<>(stash.deliveryStorage);
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
            backUpBroken(path);
            return null;
        }
    }

    /**
     * A stash file that could not be read is copied beside itself before anything can save a new, empty stash over it
     * (a failed load starts a new stash, which is written over the file the next time it is saved). The newest three
     * copies are kept.
     */
    private static void backUpBroken(Path path) {
        try {
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path copy = path.resolveSibling(path.getFileName() + ".bak-" + stamp);
            Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
            QwerteStashPlugin.LOG.error("Qwerte Stash: {} could not be read, a copy was saved as {}. A new empty stash replaces it the next time it is saved; restore the copy to get the old one back.", path.getFileName(), copy.getFileName());

            List<Path> copies = new ArrayList<>();
            try (var files = Files.list(path.getParent())) {
                files.filter(p -> p.getFileName().toString().startsWith(path.getFileName() + ".bak-")).forEach(copies::add);
            }
            copies.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
            for (int i = 3; i < copies.size(); i++) Files.deleteIfExists(copies.get(i));
        } catch (IOException | RuntimeException e) {
            QwerteStashPlugin.LOG.error("Qwerte Stash: couldn't back up the unreadable stash file {}", path.getFileName(), e);
        }
    }
}
