package com.porter.qwertestash.module;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PerimeterColumnsTest {
    @Test
    void crossingZeroKeepsBothSignedAxesAndColumnOrder() {
        int[] bounds = {-17, -80, -1, 0, 200, 0};
        long[] columns = PerimeterColumns.covering(bounds);
        int[][] expected = {{-2, -1}, {-2, 0}, {-1, -1}, {-1, 0}, {0, -1}, {0, 0}};
        assertEquals(expected.length, columns.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], PerimeterColumns.x(columns[i]));
            assertEquals(expected[i][1], PerimeterColumns.z(columns[i]));
        }
    }

    @Test
    void partialColumnsUseTheClippedMidpointRoundedTowardZero() {
        int[] bounds = {-18, 0, 14, -1, 1, 16};
        long[] columns = PerimeterColumns.covering(bounds);
        assertEquals(new PerimeterColumns.Point(-17, 14), PerimeterColumns.waypoint(columns[0], bounds));
        assertEquals(new PerimeterColumns.Point(-8, 16), PerimeterColumns.waypoint(columns[3], bounds));
    }

    @Test
    void equalDistancesKeepScanOrderAndExcludedColumnsNeverWin() {
        int[] bounds = {0, -15, 0, 47, 15, 0};
        long[] columns = PerimeterColumns.covering(bounds);
        assertEquals(columns[0], PerimeterColumns.nearestPending(columns, bounds, 15, 0, Set.of(), Set.of()));
        assertEquals(columns[2], PerimeterColumns.nearestPending(columns, bounds, 39, 0, Set.of(), Set.of()));
        assertEquals(columns[1], PerimeterColumns.nearestPending(columns, bounds, 39, 0, Set.of(columns[2]), Set.of(columns[0])));
        assertEquals(PerimeterColumns.NONE, PerimeterColumns.nearestPending(columns, bounds, 39, 0,
            Set.of(columns[0], columns[2]), Set.of(columns[1])));
    }

    @Test
    void everyBlockInAnUnalignedBoxBelongsToExactlyOneEnumeratedColumn() {
        for (int start : List.of(-33, -16, -1, 0, 15, 16, 29)) {
            int[] bounds = {start, 0, -start, start + 19, 0, -start + 17};
            Set<List<Integer>> expected = new HashSet<>();
            for (int x = bounds[0]; x <= bounds[3]; x++) {
                for (int z = bounds[2]; z <= bounds[5]; z++) expected.add(List.of(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
            }
            for (long column : PerimeterColumns.covering(bounds)) {
                assertTrue(expected.remove(List.of(PerimeterColumns.x(column), PerimeterColumns.z(column))));
                var waypoint = PerimeterColumns.waypoint(column, bounds);
                assertTrue(waypoint.x() >= bounds[0] && waypoint.x() <= bounds[3]);
                assertTrue(waypoint.z() >= bounds[2] && waypoint.z() <= bounds[5]);
            }
            assertTrue(expected.isEmpty());
        }
    }
}
