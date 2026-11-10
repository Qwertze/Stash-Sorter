package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.orders.OrderBlocker;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Where the crafting table of a craft order may be, and why not (the heartbeat's {@code tableWhy}): in the stash's dimension, at height -64 to
 * 320, and within a margin of the stash area. A stash without a perimeter ({@code stash area}) has its containers' bounding box widened by the
 * search radius as its area: a perimeter is optional, most stashes have none. Pure.
 */
public final class CraftBounds {
    private CraftBounds() {
    }

    /** The reasons a table is unusable (CRAFT-CONTRACT heartbeat {@code craftOrders.tableWhy}). */
    public static final String NO_AREA = "no_area", NO_DIMENSION = "no_dimension", WRONG_DIMENSION = "wrong_dimension", TOO_FAR = "too_far", NOT_TABLE = "not_table",
        NO_STAND = "no_stand", PATH_FAILED = "path_failed", NOT_LOADED = "not_loaded", NO_MATERIAL = "no_material";

    /** The heartbeat state of a table whose chunk is not loaded now: "ok" when it was seen standing before, else "unloaded" (never checked). */
    public static String unloadedStatus(boolean seenBefore) {
        return seenBefore ? "ok" : "unloaded";
    }

    /**
     * The area craft tables must be near: the perimeter when there is one, else the box of the stash's containers widened by the search radius
     * and height, at most {@link AnvilFinder#MAX_WIDTH} across and {@link AnvilFinder#MAX_HEIGHT} high, around the bot when the stash is bigger
     * (the same region the table search looks through). Null when there is no perimeter and no container.
     * @param perimeter minX, minY, minZ, maxX, maxY, maxZ or null
     * @param boxes     where the containers are
     */
    public static int @Nullable [] area(int @Nullable [] perimeter, List<BlockPos> boxes, BlockPos feet, int radius, int height) {
        if (perimeter != null) return perimeter.clone();
        if (boxes.isEmpty()) return null;
        return AnvilFinder.region(null, boxes, feet, radius, height);
    }

    /**
     * The dimension the stash is in: as recorded; else the dimension the bot is in, but only when it really read some of the stash's containers
     * this session; else null.
     */
    public static @Nullable String dimension(@Nullable String recorded, @Nullable String readIn) {
        return recorded != null ? recorded : readIn;
    }

    /**
     * Where a stash is, for judging a crafting table, whatever the bot is doing: its perimeter, or else its containers. A table is near
     * a stash without a perimeter when it is within the search radius (and height) plus the margin of one of its containers. Nothing here
     * depends on where the bot stands.
     * @param perimeter minX, minY, minZ, maxX, maxY, maxZ or null
     */
    public record StashArea(int @Nullable [] perimeter, List<BlockPos> boxes, int radius, int height) {
        public boolean empty() {
            return perimeter == null && boxes.isEmpty();
        }
    }

    /** The block is within the margin of the perimeter, or (with none) of the radius and height around one of the containers. */
    public static boolean contains(StashArea a, BlockPos p, int margin) {
        if (a.perimeter() != null) return inside(a.perimeter(), p, margin);
        for (BlockPos b : a.boxes()) {
            if (Math.abs(p.x() - b.x()) <= a.radius() + margin && Math.abs(p.z() - b.z()) <= a.radius() + margin && Math.abs(p.y() - b.y()) <= a.height() + margin) return true;
        }
        return false;
    }

    /** Why this table may not be used for this stash, or null when it may (see {@link #boundsWhy}; the area here does not move with the bot). */
    public static @Nullable String areaWhy(@Nullable String stashDimension, StashArea area, CraftSettings.Table t, int margin) {
        if (stashDimension == null) return NO_DIMENSION;
        if (area.empty()) return NO_AREA;
        if (!OrderBlocker.sameDimension(stashDimension, t.dimension())) return WRONG_DIMENSION;
        if (t.y() < -64 || t.y() > 320) return TOO_FAR;
        return contains(area, t.pos(), margin) ? null : TOO_FAR;
    }

    /** The block is inside the area widened by the margin. */
    public static boolean inside(int @Nullable [] bounds, BlockPos p, int margin) {
        return bounds != null && p.x() >= bounds[0] - margin && p.x() <= bounds[3] + margin && p.y() >= bounds[1] - margin && p.y() <= bounds[4] + margin
            && p.z() >= bounds[2] - margin && p.z() <= bounds[5] + margin;
    }

    /**
     * Why this table may not be used, or null when it may.
     * @param stashDimension the dimension the stash is in (as recorded, or the one the bot reads it in), or null when not known
     * @param bounds         minX, minY, minZ, maxX, maxY, maxZ of the stash area, or null when there is none
     */
    public static @Nullable String boundsWhy(@Nullable String stashDimension, int @Nullable [] bounds, CraftSettings.Table t, int margin) {
        if (stashDimension == null) return NO_DIMENSION;
        if (bounds == null || bounds.length < 6) return NO_AREA;
        if (!OrderBlocker.sameDimension(stashDimension, t.dimension())) return WRONG_DIMENSION;
        if (t.y() < -64 || t.y() > 320) return TOO_FAR;
        boolean far = t.x() < bounds[0] - margin || t.x() > bounds[3] + margin || t.y() < bounds[1] - margin || t.y() > bounds[4] + margin
            || t.z() < bounds[2] - margin || t.z() > bounds[5] + margin;
        return far ? TOO_FAR : null;
    }

    /** True when the table must not be used (see {@link #boundsWhy}). */
    public static boolean outOfBounds(@Nullable String stashDimension, int @Nullable [] bounds, CraftSettings.Table t, int margin) {
        return boundsWhy(stashDimension, bounds, t, margin) != null;
    }
}
