package com.porter.qwertestash.manager;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTilesTest {
    /** A world of columns: top y and colour by (x,z), water by (x,y,z). Packed byte = colour * 4 + shade (LOW 1, NORMAL 2, HIGH 3). */
    static final class Fake implements MapTiles.ColumnSource {
        final Map<Long, int[]> cols = new HashMap<>();
        final Set<String> water = new HashSet<>();
        final Set<Long> unloaded = new HashSet<>();
        int minY = -64;
        int defaultTop = 64, defaultColor = 1;

        static long k(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        void col(int x, int z, int top, int color) {
            cols.put(k(x, z), new int[] {top, color});
        }

        void addWater(int x, int y, int z) {
            water.add(x + "," + y + "," + z);
        }

        @Override
        public boolean loaded(int cx, int cz) {
            return !unloaded.contains(k(cx, cz));
        }

        @Override
        public int minY() {
            return minY;
        }

        @Override
        public int topY(int x, int z) {
            int[] c = cols.get(k(x, z));
            return c == null ? defaultTop : c[0];
        }

        @Override
        public int mapColor(int x, int y, int z) {
            int[] c = cols.get(k(x, z));
            return c == null ? defaultColor : c[1];
        }

        @Override
        public boolean water(int x, int y, int z) {
            return water.contains(x + "," + y + "," + z);
        }

        @Override
        public byte packed(int color, MapTiles.Shade shade) {
            return (byte) (color * 4 + switch (shade) {
                case LOW -> 1;
                case NORMAL -> 2;
                case HIGH -> 3;
            });
        }
    }

    private static int shade(MapTiles.Tile t, int lx, int lz) {
        return t.colors()[lz * 16 + lx] & 3;
    }

    private static int height(MapTiles.Tile t, int lx, int lz) {
        int i = (lz * 16 + lx) * 2;
        return (short) ((t.heights()[i] & 0xFF) | (t.heights()[i + 1] << 8));
    }

    @Test
    void topBlockIsTheHighestWithAColour() {
        Fake f = new Fake();
        f.col(3, 4, 70, 7);
        f.col(3, 5, 70, 7);
        MapTiles.Tile t = MapTiles.encode(f, 0, 0);
        assertEquals(7 * 4 + 2, t.colors()[5 * 16 + 3]);
        assertEquals(70, height(t, 3, 5));
    }

    @Test
    void transparentColumnsAreSkipped() {
        Fake f = new Fake();
        f.col(2, 2, MapTiles.NONE, 0);
        MapTiles.Tile t = MapTiles.encode(f, 0, 0);
        assertEquals(0, t.colors()[2 * 16 + 2]);
        assertEquals(Short.MIN_VALUE, height(t, 2, 2));
        // The column below it has nothing to shade against: flat
        assertEquals(2, shade(t, 2, 3));
    }

    @Test
    void shadesAgainstTheNorthNeighbourAcrossATileEdge() {
        Fake f = new Fake();
        // Column x=0 of chunk (0,0), row 0: north is x=0,z=-1 in chunk (0,-1)
        f.col(0, -1, 60, 1);
        f.col(0, 0, 64, 1);
        assertEquals(3, shade(MapTiles.encode(f, 0, 0), 0, 0));

        f.col(0, -1, 70, 1);
        assertEquals(1, shade(MapTiles.encode(f, 0, 0), 0, 0));

        f.col(0, -1, 64, 1);
        assertEquals(2, shade(MapTiles.encode(f, 0, 0), 0, 0));

        // Not loaded: flat
        f.col(0, -1, 10, 1);
        f.unloaded.add(Fake.k(0, -1));
        assertEquals(2, shade(MapTiles.encode(f, 0, 0), 0, 0));
    }

    @Test
    void shadesWithinATileRowByRow() {
        Fake f = new Fake();
        f.col(1, 0, 64, 1);
        f.col(1, 1, 66, 1);
        f.col(1, 2, 66, 1);
        MapTiles.Tile t = MapTiles.encode(f, 0, 0);
        assertEquals(3, shade(t, 1, 1));
        assertEquals(2, shade(t, 1, 2));
    }

    @Test
    void waterShadeFollowsDepth() {
        Fake f = new Fake();
        // x+z even: no checkerboard term. Depth counts the blocks looked at below the surface, as the proxy's MapGenerator does
        f.col(0, 0, 64, MapTiles.WATER);
        f.addWater(0, 64, 0);
        assertEquals(12 * 4 + 3, MapTiles.encode(f, 0, 0).colors()[0]);

        for (int y = 63; y > 58; y--) f.addWater(0, y, 0);
        assertEquals(12 * 4 + 2, MapTiles.encode(f, 0, 0).colors()[0]);

        for (int y = 63; y > 40; y--) f.addWater(0, y, 0);
        assertEquals(12 * 4 + 1, MapTiles.encode(f, 0, 0).colors()[0]);
    }

    @Test
    void heightsAreInt16LittleEndian() {
        Fake f = new Fake();
        f.col(0, 0, 300, 1);
        f.col(1, 0, -64, 1);
        MapTiles.Tile t = MapTiles.encode(f, 0, 0);
        assertEquals(0x2C, t.heights()[0] & 0xFF);
        assertEquals(0x01, t.heights()[1] & 0xFF);
        assertEquals(-64, height(t, 1, 0));
        assertEquals(0xC0, t.heights()[2] & 0xFF);
        assertEquals(0xFF, t.heights()[3] & 0xFF);
    }

    @Test
    void base64HasTheLengthsTheServerChecks() {
        MapTiles.Tile t = MapTiles.encode(new Fake(), 4, -9);
        assertEquals(344, t.colorsBase64().length());
        assertEquals(684, t.heightsBase64().length());
        assertEquals(256, Base64.getDecoder().decode(t.colorsBase64()).length);
        assertEquals(512, Base64.getDecoder().decode(t.heightsBase64()).length);
    }

    @Test
    void checksumTellsTilesApart() {
        Fake f = new Fake();
        int a = MapTiles.encode(f, 0, 0).crc();
        assertEquals(a, MapTiles.encode(f, 0, 0).crc());
        f.col(5, 5, 90, 1);
        assertTrue(a != MapTiles.encode(f, 0, 0).crc());
    }
}
