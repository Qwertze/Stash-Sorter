package com.porter.qwertestash.module;

import java.util.Set;

/** Horizontal chunk geometry for an inclusive, ordered stash bounding box. */
final class PerimeterColumns {
    static final long NONE = Long.MIN_VALUE;

    private PerimeterColumns() {
    }

    record Point(int x, int z) { }

    static int x(long column) {
        return (int) (column >> 32);
    }

    static int z(long column) {
        return (int) column;
    }

    static long[] covering(int[] bounds) {
        int firstX = bounds[0] >> 4, lastX = bounds[3] >> 4;
        int firstZ = bounds[2] >> 4, lastZ = bounds[5] >> 4;
        long[] columns = new long[(lastX - firstX + 1) * (lastZ - firstZ + 1)];
        int next = 0;
        for (int cx = firstX; cx <= lastX; cx++) {
            for (int cz = firstZ; cz <= lastZ; cz++) {
                columns[next++] = ((long) cx << 32) | (cz & 0xffffffffL);
            }
        }
        return columns;
    }

    /** The midpoint of the portion of this column inside the perimeter, rounded toward zero. */
    static Point waypoint(long column, int[] bounds) {
        int startX = x(column) << 4, startZ = z(column) << 4;
        int x0 = Math.max(bounds[0], startX), x1 = Math.min(bounds[3], startX + 15);
        int z0 = Math.max(bounds[2], startZ), z1 = Math.min(bounds[5], startZ + 15);
        return new Point((int) (((long) x0 + x1) / 2), (int) (((long) z0 + z1) / 2));
    }

    /** Equal distances keep the first eligible column in scan order. */
    static long nearestPending(long[] columns, int[] bounds, int feetX, int feetZ,
                               Set<Long> explored, Set<Long> unreachable) {
        long nearest = NONE;
        double best = Double.MAX_VALUE;
        for (long column : columns) {
            if (explored.contains(column) || unreachable.contains(column)) continue;
            Point point = waypoint(column, bounds);
            double distance = Math.hypot((double) point.x() - feetX, (double) point.z() - feetZ);
            if (distance < best) {
                best = distance;
                nearest = column;
            }
        }
        return nearest;
    }
}
