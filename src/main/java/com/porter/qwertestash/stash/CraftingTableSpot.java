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

    // One immutable value that every thread reads whole (the four config fields are written one by one)
    private static volatile @Nullable Spot current;
    private static volatile boolean loaded;

    public static @Nullable Spot get() {
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

    public static synchronized void set(String dimension, int x, int y, int z) {
        CONFIG.craftingTableX = x;
        CONFIG.craftingTableY = y;
        CONFIG.craftingTableZ = z;
        CONFIG.craftingTableDimension = dimension;
        current = new Spot(dimension, x, y, z);
        loaded = true;
    }

    public static synchronized void clear() {
        CONFIG.craftingTableDimension = "";
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
