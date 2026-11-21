package com.porter.qwertestash.stash;

import com.aquarius.feature.player.World;
import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;

import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * The furnace craft orders smelt in (B2, CRAFT-CONTRACT-v1.2.md 14), kept in the plugin's settings (dimension and block position) like
 * {@link CraftingTableSpot}: found standing in the stash area, or put down by the bot from one in the stash or crafted by it, and left standing. It is
 * not a container: never sorted, read, indexed or searched. Its position is never shown on the website; the heartbeat's {@code botFurnace} carries it
 * (hidden from a forced streamer by the server), and the proxy terminal may show it.
 */
public final class FurnaceSpot {
    private FurnaceSpot() {
    }

    public record Spot(String dimension, int x, int y, int z) {
        public BlockPos pos() {
            return new BlockPos(x, y, z);
        }
    }

    /** One immutable value that every thread reads whole (the config fields are written one by one). */
    private static volatile @Nullable Spot current;
    private static volatile boolean loaded;

    public static @Nullable Spot get() {
        if (!loaded) {
            synchronized (FurnaceSpot.class) {
                if (!loaded) {
                    String dimension = CONFIG.craftFurnaceDimension;
                    current = dimension == null || dimension.isBlank() ? null : new Spot(dimension, CONFIG.craftFurnaceX, CONFIG.craftFurnaceY, CONFIG.craftFurnaceZ);
                    loaded = true;
                }
            }
        }
        return current;
    }

    public static boolean isSet() {
        return get() != null;
    }

    /** "found", "placed" or "crafted", or "none" when there is no furnace. */
    public static String source() {
        if (get() == null) return "none";
        String s = CONFIG.craftFurnaceSource;
        return FurnaceFinder.PLACED.equals(s) || FurnaceFinder.CRAFTED.equals(s) ? s : FurnaceFinder.FOUND;
    }

    public static synchronized void remember(String dimension, int x, int y, int z, String source) {
        CONFIG.craftFurnaceSource = source;
        CONFIG.craftFurnaceX = x;
        CONFIG.craftFurnaceY = y;
        CONFIG.craftFurnaceZ = z;
        CONFIG.craftFurnaceDimension = dimension;
        current = new Spot(dimension, x, y, z);
        loaded = true;
    }

    public static synchronized void clear() {
        CONFIG.craftFurnaceDimension = "";
        CONFIG.craftFurnaceSource = "";
        CONFIG.craftFurnaceX = CONFIG.craftFurnaceY = CONFIG.craftFurnaceZ = 0;
        current = null;
        loaded = true;
    }

    /** The spot is in the dimension the bot is in now (true when either is not known). */
    public static boolean here(Spot spot) {
        String now = Stash.currentDimension();
        return now == null || com.porter.qwertestash.orders.OrderBlocker.sameDimension(now, spot.dimension());
    }

    /** The block there is a plain furnace; false when its chunk is not loaded. */
    public static boolean isFurnace(BlockPos pos) {
        if (!World.isChunkLoadedBlockPos(pos.x(), pos.z())) return false;
        return FurnaceFinder.isFurnaceName(World.getBlock(pos.x(), pos.y(), pos.z()).name());
    }
}
