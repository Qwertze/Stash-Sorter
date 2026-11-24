package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.porter.qwertestash.QwerteStashPlugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
            SnapshotFiles.backup(path, ".prune-bak-");
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
    /**
     * Saved with the stash: 1 once the containers were read again for the dyed kind (B5). A stash saved before it has the stacks' {@code fw} unset for dyed
     * armour (the colour is not kept anywhere else), so the first time the bot says {@code dyed} every container is read again.
     */
    public int dyedSpecGen;

    /**
     * B5: the bot says {@code dyed} now. The first time for a stash saved before B5, every container is marked to be read again (as {@code stash rescan} does) so
     * the colours of its dyed armour are kept. @return true when it did (the caller saves and bumps the stash version)
     */
    public boolean rescanForDyed(boolean dyedOn) {
        if (!dyedOn || dyedSpecGen >= 1) return false;
        dyedSpecGen = 1;
        markAllStale();
        return true;
    }
    private transient boolean dirty;
    /**
     * Set when the file on disk could not be read and could not be copied aside yet (see {@link #loadFrom}): this stash is an empty one that must not
     * be written over it until a copy of it exists. {@link #save} then keeps the stash dirty and tries again, with a growing wait.
     */
    private transient volatile boolean protectsFile;
    private transient volatile long protectRetryAt;
    private transient volatile int protectFailures;

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

    /** Where the stash files are; tests point it at a temporary folder. */
    static volatile Path stashFolder = Path.of("plugins", "qwerte-stash", "stashes");

    private static Path folder() {
        return stashFolder;
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

    /**
     * Deletes the saved stash and its readable index, and drops any index write still queued for it. Recovery copies ({@code .bak-}, {@code .prune-bak-})
     * are kept. Both files are tried even when the first fails.
     * @throws IOException when a file could not be deleted (the first problem; the other is added as suppressed)
     */
    public static void delete(String name) throws IOException {
        delete(name, currentServer());
    }

    static void delete(String name, String server) throws IOException {
        Path data = file(name, server);
        IOException failure = null;
        try {
            // Index first: its queued write is invalidated before the files go, so it can not bring the index back afterwards
            StashIndex.forget(folder().resolve(baseName(name, server) + ".index.txt"));
        } catch (IOException e) {
            failure = e;
        }
        try {
            Files.deleteIfExists(data);
        } catch (IOException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) {
            logError("Couldn't delete stash {}", name, failure);
            throw failure;
        }
    }

    /** The most bytes of a saved stash that are loaded (256 MiB: real ones are a few MiB). */
    public static final long MAX_FILE_BYTES = 256L << 20;

    /**
     * A saved file above {@code max} bytes is renamed to {@code <file>.oversized-<timestamp>} (logged), so the next save can't overwrite it and the user's data is
     * kept. @return the new path, or null when the file is within the bound (or could not be renamed: then it is logged and left alone, and null is returned)
     */
    private static void logError(String text, Object... args) {
        if (QwerteStashPlugin.LOG != null) QwerteStashPlugin.LOG.error(text, args);
    }

    static @Nullable Path setAsideIfOversized(Path path, long max) throws IOException {
        long size = Files.size(path);
        if (size <= max) return null;
        Path aside = path.resolveSibling(path.getFileName() + ".oversized-" + System.currentTimeMillis());
        try {
            Files.move(path, aside);
            logError("Qwerte Stash: the saved stash file is {} MiB, more than the {} MiB read at most; it was renamed to {} and the stash starts fresh.", size >> 20, max >> 20, aside.getFileName());
            return aside;
        } catch (IOException e) {
            logError("Qwerte Stash: the saved stash file is {} MiB (more than {} MiB) and could not be renamed aside: {}", size >> 20, max >> 20, e.toString());
            return null;
        }
    }

    public void save() {
        save(false);
    }

    /**
     * @param force a stash that holds back from replacing an unreadable file tries to release it now, whatever the wait since its last try (the last
     *              save before a disconnect must not be lost to the backoff)
     */
    public void save(boolean force) {
        try {
            if (protectsFile) {
                Release release = releaseProtection(force);
                if (release != Release.FREE) return; // still held (dirty, tried again later), or the file was taken over and there is nothing new to write
            }
            Supplier<Map<Integer, String>> source = heldSource;
            if (source != null) held = new HashMap<>(source.get());
            // Staged under a name of its own and moved over the file, so a crash or a stop in the middle never leaves half a stash, and a failure leaves no temp file
            Path target = file(name, server);
            SnapshotFiles.write(target, GSON.toJson(this));
            // The file holds positions and the order journal: readable by its owner alone where the system can say so (best effort, as the pearl journal)
            com.porter.qwertestash.pearl.PearlJournal.ownerOnly(target);
            dirty = false;
            StashIndex.touch(this);
        } catch (IOException e) {
            logError("Couldn't save stash {}", name, e);
        }
    }

    /** Marks this (new, empty) stash as not allowed to replace the file on disk until that file has been read again or copied aside. */
    void protectExistingFile() {
        protectsFile = true;
        dirty = true;
    }

    /** True while this stash holds back from replacing the file on disk (see {@link #protectExistingFile}). */
    boolean protectsFile() {
        return protectsFile;
    }

    private enum Release { HELD, ADOPTED, FREE }

    /**
     * The file is read again first (it may have been only briefly unreadable):
     * <ul>
     *   <li>it parses now: its content is taken over (boxes this stash scanned meanwhile are merged in), nothing is overwritten
     *       ({@link Release#ADOPTED}, or {@link Release#FREE} when merged boxes are to be written);</li>
     *   <li>it is invalid: {@link #loadFrom} copied it aside, and the stash may replace it ({@link Release#FREE});</li>
     *   <li>it still can not be read or copied: {@link Release#HELD}, logged once, retried no sooner than a wait that doubles from 2 seconds to a minute.</li>
     * </ul>
     */
    private synchronized Release releaseProtection(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now < protectRetryAt) return Release.HELD;
        Path path = file(name, server);
        Loaded loaded = loadFrom(path, name, server, protectFailures == 0);
        Stash disk = loaded.stash();
        if (disk != null) {
            int mine = boxes.size();
            adopt(disk);
            mergeBoxes(disk);
            protectsFile = false;
            protectFailures = 0;
            logError("Qwerte Stash: {} could be read again and was taken over{}.", path.getFileName(), mine > 0 ? " (" + mine + " containers scanned meanwhile kept)" : "");
            // Anything this stash did meanwhile is newer than the file: it is written merged. Otherwise the file is exactly what is held
            dirty = mine > 0;
            return mine > 0 ? Release.FREE : Release.ADOPTED;
        }
        if (!loaded.protect()) {
            protectsFile = false;
            protectFailures = 0;
            return Release.FREE;
        }
        if (protectFailures++ == 0) {
            logError("Qwerte Stash: {} could not be read or copied aside, so it is not overwritten; trying again in the background.", path.getFileName());
        }
        protectRetryAt = now + Math.min(60_000L, 2_000L << Math.min(5, protectFailures - 1));
        return Release.HELD;
    }

    /**
     * Takes over the saved fields of a stash read from disk, without overwriting anything this stash set meanwhile: a field is taken from the file only
     * while this stash still holds its default (null, empty, zero, false); otherwise the in-memory value stays and the field name is logged (never the
     * value). Lists ({@code orphans}, {@code deliveryStorage}) are merged into the existing list object. Boxes are merged by {@link #mergeBoxes}.
     */
    private void adopt(Stash disk) {
        for (java.lang.reflect.Field field : Stash.class.getDeclaredFields()) {
            int m = field.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(m) || java.lang.reflect.Modifier.isTransient(m) || java.lang.reflect.Modifier.isFinal(m)) continue;
            String fieldName = field.getName();
            if (fieldName.equals("name") || fieldName.equals("server") || fieldName.equals("boxes")) continue;
            try {
                field.setAccessible(true);
                Object mine = field.get(this), theirs = field.get(disk);
                if ((fieldName.equals("orphans") || fieldName.equals("deliveryStorage")) && mine instanceof List<?> list && theirs instanceof List<?> other) {
                    @SuppressWarnings("unchecked")
                    List<Object> into = (List<Object>) list;
                    for (Object o : other) {
                        // No equals on these records: the same entry is told by its saved form
                        String saved = GSON.toJson(o);
                        if (into.stream().noneMatch(x -> GSON.toJson(x).equals(saved))) into.add(o);
                    }
                }
                else if (isDefault(mine)) field.set(this, theirs);
                else if (!isDefault(theirs)) logError("Qwerte Stash: {} was set while the file could not be read; the value in memory is kept, not the saved one.", fieldName);
            } catch (IllegalAccessException | RuntimeException e) {
                logError("Qwerte Stash: couldn't take over {}: {}", fieldName, e.toString());
            }
        }
    }

    private static boolean isDefault(@Nullable Object value) {
        if (value == null) return true;
        if (value instanceof Number n) return n.doubleValue() == 0;
        if (value instanceof Boolean b) return !b;
        if (value instanceof java.util.Collection<?> c) return c.isEmpty();
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        if (value.getClass().isArray()) return java.lang.reflect.Array.getLength(value) == 0;
        return false;
    }

    /**
     * Containers of the file this stash does not have are added. For one at the same position the contents this stash read meanwhile are newer and stay,
     * but the marks the user set (excluded, drop-off, no-compact) and the labels are carried over from the file. ({@code stale} is not saved, so there is
     * nothing of it to carry.)
     */
    private void mergeBoxes(Stash disk) {
        for (Box d : disk.boxes) {
            if (d == null || d.pos == null) continue;
            Box mine = null;
            for (Box x : boxes) {
                if (x != null && d.pos.equals(x.pos)) {
                    mine = x;
                    break;
                }
            }
            if (mine == null) {
                boxes.add(d);
                continue;
            }
            mine.excluded |= d.excluded;
            mine.dropOff |= d.dropOff;
            mine.noCompact |= d.noCompact;
            if (mine.labels == null || mine.labels.isEmpty()) mine.labels = d.labels;
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

    /** What reading a stash file gave: the stash, or none; {@code protect} when none was read although there is a file that must not be lost. */
    record Loaded(@Nullable Stash stash, boolean protect) {
    }

    /**
     * The stash in {@code path}. Never throws (it runs on the tick path):
     * <ul>
     *   <li>no file: no stash;</li>
     *   <li>a file that is not a stash (invalid content): a copy is made first ({@code .bak-}), then no stash, so a new empty one may replace it;</li>
     *   <li>a file that could not be read (I/O error), or whose copy failed: no stash and {@code protect}, so the new empty stash leaves the file untouched
     *       and stays dirty until a copy of it can be made.</li>
     * </ul>
     */
    static Loaded loadFrom(Path path, String name, String server) {
        return loadFrom(path, name, server, true);
    }

    /** @param verbose false for a retry: what went wrong was logged on the first try */
    static Loaded loadFrom(Path path, String name, String server, boolean verbose) {
        try {
            // A generous bound: a stash file this big is not one of ours and is never read whole into memory. It is renamed aside (never overwritten by a
            // later save) and the stash starts fresh
            if (Files.size(path) > MAX_FILE_BYTES) {
                Path aside = setAsideIfOversized(path, MAX_FILE_BYTES);
                return new Loaded(null, aside == null); // not read; if it could not be renamed it is left alone, not overwritten
            }
        } catch (NoSuchFileException e) {
            return new Loaded(null, false);
        } catch (IOException | RuntimeException e) {
            if (verbose) logError("Qwerte Stash: couldn't look at the stash file {}: {}", path.getFileName(), e.toString());
            return new Loaded(null, true);
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new Loaded(null, false);
        } catch (MalformedInputException e) {
            if (verbose) logError("Qwerte Stash: the stash file {} is not valid text: {}", path.getFileName(), e.toString());
            return new Loaded(null, !keepCopy(path));
        } catch (IOException | RuntimeException e) {
            if (verbose) logError("Qwerte Stash: couldn't read the stash file {}: {}", path.getFileName(), e.toString());
            return new Loaded(null, true);
        }
        try {
            Stash stash = GSON.fromJson(text, Stash.class);
            if (stash == null) throw new JsonParseException("empty stash document");
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
            return new Loaded(stash, false);
        } catch (RuntimeException e) {
            if (verbose) logError("Qwerte Stash: the stash file {} is not a valid stash: {}", path.getFileName(), e.toString());
            return new Loaded(null, !keepCopy(path));
        }
    }

    /** The stash called {@code name} on this server; see {@link #loadFrom}. */
    static Loaded load(String name) {
        String server = currentServer();
        Path path = file(name, server);
        // Staging files a crash left behind (older than an hour: none is being written that long)
        SnapshotFiles.sweepStale(path.toAbsolutePath().getParent(), 60L * 60 * 1000);
        return loadFrom(path, name, server);
    }

    /**
     * A stash file that could not be understood is copied beside itself before anything can save a new, empty stash over it. The newest three copies
     * are kept.
     * @return false when no copy could be made: the file must then not be overwritten
     */
    private static boolean keepCopy(Path path) {
        try {
            Path copy = SnapshotFiles.backup(path, ".bak-");
            logError("Qwerte Stash: {} could not be read, a copy was saved as {}. A new empty stash replaces it the next time it is saved; restore the copy to get the old one back.", path.getFileName(), copy.getFileName());
            return true;
        } catch (IOException | RuntimeException e) {
            logError("Qwerte Stash: couldn't back up the unreadable stash file {}: {}", path.getFileName(), e.toString());
            return false;
        }
    }
}
