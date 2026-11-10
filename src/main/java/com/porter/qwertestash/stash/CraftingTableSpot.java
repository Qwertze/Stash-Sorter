package com.porter.qwertestash.stash;

import com.aquarius.feature.player.World;
import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;

import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * The crafting table the bot crafts at, kept in the plugin's settings (dimension and block position) like the anvil
 * ({@link AnvilSpot}). It is not a container: never sorted, read, indexed or searched. Its position is shown only where the
 * delivery container's is (the proxy terminal and the website console); the website's settings and activity titles never
 * have it, and the heartbeat only says whether one is set.
 */
public final class CraftingTableSpot {
    private CraftingTableSpot() {
    }

    public record Spot(String dimension, int x, int y, int z) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** Set with `stash craftingtable`; found standing in the stash area; placed by the bot from one in the stash; crafted and placed. */
    public static final String SET = "set", FOUND = "found", PLACED = "placed", CRAFTED = "crafted", NONE = "none";

    // One immutable value that every thread reads whole (the four config fields are written one by one)
    private static volatile @Nullable Spot current;
    private static volatile boolean loaded;
    // The table the website set for the stash, while a craft order is crafting at it (1.15.0): it is used instead of ours, whichever is set,
    // and is never remembered, forgotten or shown as ours
    private static volatile @Nullable Spot website;

    /** Craft orders craft at this table until it is called again with null; it overrides the table of `stash craftingtable` meanwhile. */
    public static void useWebsite(@Nullable Spot spot) {
        website = spot;
    }

    /** The table in use is the website's, for a craft order. */
    public static boolean websiteActive() {
        return website != null;
    }

    // While a craft order cannot use the table set by hand (it is outside the limits), the table the bot finds, places or crafts for it is kept
    // in this slot, in memory, and the table in the settings is left exactly as it is
    private static volatile @Nullable Spot side;
    private static volatile String sideSource = NONE;
    private static volatile boolean sideOn;

    /** Craft orders use a slot of their own instead of the table in the settings (on), or the settings' table again (off). */
    public static void sideMode(boolean on) {
        sideOn = on;
    }

    public static boolean sideModeOn() {
        return sideOn;
    }

    /** Forgets the table of the side slot (the world keeps it; a search finds it again). */
    public static void sideClear() {
        side = null;
        sideSource = NONE;
    }

    public static @Nullable Spot get() {
        Spot w = website;
        if (w != null) return w;
        if (sideOn) return side;
        return persisted();
    }

    /** The table in the settings, whatever slot is in use now. */
    public static @Nullable Spot persisted() {
        if (!loaded) {
            synchronized (CraftingTableSpot.class) {
                if (!loaded) {
                    String dimension = CONFIG.craftingTableDimension;
                    current = dimension == null || dimension.isBlank() ? null : new Spot(dimension, CONFIG.craftingTableX, CONFIG.craftingTableY, CONFIG.craftingTableZ);
                    loaded = true;
                }
            }
        }
        return current;
    }

    public static boolean isSet() {
        return get() != null;
    }

    /** "set", "found", "placed", "crafted", or "none" when there is no table (settings from before 1.10.0 read as "set"). */
    public static String source() {
        if (website != null) return SET;
        if (sideOn) return side == null ? NONE : sideSource;
        return persistedSource();
    }

    /** The settings' table: how it came to be (as {@link #source}), or "none". */
    public static String persistedSource() {
        if (persisted() == null) return NONE;
        String s = CONFIG.craftingTableSource;
        return FOUND.equals(s) || PLACED.equals(s) || CRAFTED.equals(s) ? s : SET;
    }

    /** Set by hand (`stash craftingtable`). */
    public static synchronized void set(String dimension, int x, int y, int z) {
        remember(dimension, x, y, z, SET);
    }

    /** Remembered as set, found, placed or crafted. */
    public static synchronized void remember(String dimension, int x, int y, int z, String source) {
        if (sideOn) {
            side = new Spot(dimension, x, y, z);
            sideSource = source;
            return;
        }
        CONFIG.craftingTableSource = source;
        CONFIG.craftingTableX = x;
        CONFIG.craftingTableY = y;
        CONFIG.craftingTableZ = z;
        CONFIG.craftingTableDimension = dimension;
        current = new Spot(dimension, x, y, z);
        loaded = true;
    }

    public static synchronized void clear() {
        if (sideOn) {
            sideClear();
            return;
        }
        CONFIG.craftingTableDimension = "";
        CONFIG.craftingTableSource = "";
        CONFIG.craftingTableX = CONFIG.craftingTableY = CONFIG.craftingTableZ = 0;
        current = null;
        loaded = true;
    }

    /** The spot is in the dimension the bot is in now (true when either is not known). */
    public static boolean here(Spot spot) {
        String now = Stash.currentDimension();
        return now == null || com.porter.qwertestash.orders.OrderBlocker.sameDimension(now, spot.dimension());
    }

    public static boolean isTableName(@Nullable String blockName) {
        return "crafting_table".equals(blockName);
    }

    /** The block there is a crafting table; false when its chunk is not loaded. */
    public static boolean isTable(BlockPos pos) {
        if (!World.isChunkLoadedBlockPos(pos.x(), pos.z())) return false;
        return isTableName(World.getBlock(pos.x(), pos.y(), pos.z()).name());
    }

    /**
     * Whether the set table can be used, as far as the bot can tell from here: true when one is set and, if it is in this
     * dimension with its chunk loaded, the block there is a crafting table (PROTOCOL.md heartbeat "crafting.table").
     */
    public static boolean usable() {
        Spot spot = get();
        if (spot == null) return false;
        try {
            if (!here(spot) || !World.isChunkLoadedBlockPos(spot.x(), spot.z())) return true;
            return isTable(spot.pos());
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** "x, y, z in the_nether", or "(position hidden) in the_nether". */
    public static String describe(Spot spot, boolean coordinates) {
        return (coordinates ? spot.x() + ", " + spot.y() + ", " + spot.z() : "(position hidden)") + " in " + spot.dimension();
    }
}
