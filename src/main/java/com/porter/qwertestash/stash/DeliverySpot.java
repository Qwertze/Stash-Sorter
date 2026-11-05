package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import org.jspecify.annotations.Nullable;

import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * The delivery container for stash orders, kept in the plugin's settings (dimension and block position). Its position is
 * never sent to the website: the heartbeat carries it for the order page (PROTOCOL.md "delivery"), but nothing shown in the
 * dashboard's settings or activity titles has it.
 */
public final class DeliverySpot {
    private DeliverySpot() {
    }

    /** How far the bot may look at a container for `stash delivery here` (blocks). */
    public static final double LOOK_REACH = 6.0;

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
            synchronized (DeliverySpot.class) {
                if (!loaded) {
                    String dimension = CONFIG.deliveryDimension;
                    current = dimension == null || dimension.isBlank() ? null : new Spot(dimension, CONFIG.deliveryX, CONFIG.deliveryY, CONFIG.deliveryZ);
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
        CONFIG.deliveryX = x;
        CONFIG.deliveryY = y;
        CONFIG.deliveryZ = z;
        CONFIG.deliveryDimension = dimension;
        current = new Spot(dimension, x, y, z);
        loaded = true;
        Stashes.version++;
    }

    public static synchronized void clear() {
        CONFIG.deliveryDimension = "";
        CONFIG.deliveryX = CONFIG.deliveryY = CONFIG.deliveryZ = 0;
        current = null;
        loaded = true;
        Stashes.version++;
    }

    /** The spot is in the dimension the bot is in now (true when either is not known). */
    public static boolean here(Spot spot) {
        String now = Stash.currentDimension();
        return now == null || com.porter.qwertestash.orders.OrderBlocker.sameDimension(now, spot.dimension());
    }

    /** "x, y, z in the_nether", or "(position hidden) in the_nether". */
    public static String describe(Spot spot, boolean coordinates) {
        return (coordinates ? spot.x() + ", " + spot.y() + ", " + spot.z() : "(position hidden)") + " in " + spot.dimension();
    }
}
