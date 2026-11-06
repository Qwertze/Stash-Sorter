package com.porter.qwertestash.stash;

import com.aquarius.feature.player.World;
import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;

import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * The anvil order shulkers are named at, kept in the plugin's settings (dimension and block position) like the delivery
 * container ({@link DeliverySpot}). It is not a container: never sorted, read, indexed or searched. Its position is shown
 * only where the delivery container's is (the proxy terminal and the website console); the website's settings and
 * activity titles never have it, and the heartbeat only says whether one is set.
 */
public final class AnvilSpot {
    private AnvilSpot() {
    }

    public record Spot(String dimension, int x, int y, int z) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** Set with `stash anvil`; found standing in the stash area; placed by the bot from one in the stash. */
    public static final String SET = "set", FOUND = "found", PLACED = "placed", NONE = "none";

    // One immutable value that every thread reads whole (the four config fields are written one by one)
    private static volatile @Nullable Spot current;
    private static volatile boolean loaded;

    public static @Nullable Spot get() {
        if (!loaded) {
            synchronized (AnvilSpot.class) {
                if (!loaded) {
                    String dimension = CONFIG.anvilDimension;
                    current = dimension == null || dimension.isBlank() ? null : new Spot(dimension, CONFIG.anvilX, CONFIG.anvilY, CONFIG.anvilZ);
                    loaded = true;
                }
            }
        }
        return current;
    }

    public static boolean isSet() {
        return get() != null;
    }

    /** "set", "found", "placed", or "none" when there is no anvil (settings from before 1.8.1 read as "set"). */
    public static String source() {
        if (get() == null) return NONE;
        String s = CONFIG.anvilSource;
        return FOUND.equals(s) || PLACED.equals(s) ? s : SET;
    }

    /** Set by hand (`stash anvil`). */
    public static synchronized void set(String dimension, int x, int y, int z) {
        remember(dimension, x, y, z, SET);
    }

    /** Remembered as set, found or placed. */
    public static synchronized void remember(String dimension, int x, int y, int z, String source) {
        CONFIG.anvilSource = source;
        CONFIG.anvilX = x;
        CONFIG.anvilY = y;
        CONFIG.anvilZ = z;
        CONFIG.anvilDimension = dimension;
        current = new Spot(dimension, x, y, z);
        loaded = true;
    }

    public static synchronized void clear() {
        CONFIG.anvilDimension = "";
        CONFIG.anvilSource = "";
        CONFIG.anvilX = CONFIG.anvilY = CONFIG.anvilZ = 0;
        current = null;
        loaded = true;
    }

    /** The spot is in the dimension the bot is in now (true when either is not known). */
    public static boolean here(Spot spot) {
        String now = Stash.currentDimension();
        return now == null || com.porter.qwertestash.orders.OrderBlocker.sameDimension(now, spot.dimension());
    }

    /** Anvil, chipped anvil and damaged anvil. */
    public static boolean isAnvilName(@Nullable String blockName) {
        return blockName != null && (blockName.equals("anvil") || blockName.equals("chipped_anvil") || blockName.equals("damaged_anvil"));
    }

    /** The block there is an anvil; false when its chunk is not loaded. */
    public static boolean isAnvil(BlockPos pos) {
        if (!World.isChunkLoadedBlockPos(pos.x(), pos.z())) return false;
        return isAnvilName(World.getBlock(pos.x(), pos.y(), pos.z()).name());
    }

    /**
     * Whether the set anvil can be used, as far as the bot can tell from here: true when one is set and, if it is in this
     * dimension with its chunk loaded, the block there is an anvil (PROTOCOL.md heartbeat "naming.anvil").
     */
    public static boolean usable() {
        Spot spot = get();
        if (spot == null) return false;
        try {
            if (!here(spot) || !World.isChunkLoadedBlockPos(spot.x(), spot.z())) return true;
            return isAnvil(spot.pos());
        } catch (RuntimeException | LinkageError e) {
            // Not known: taken as set
            return true;
        }
    }

    /** "x, y, z in the_nether", or "(position hidden) in the_nether". */
    public static String describe(Spot spot, boolean coordinates) {
        return (coordinates ? spot.x() + ", " + spot.y() + ", " + spot.z() : "(position hidden)") + " in " + spot.dimension();
    }
}
