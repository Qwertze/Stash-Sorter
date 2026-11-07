package com.porter.qwertestash.manager;

import java.util.Base64;
import java.util.zip.CRC32;

/**
 * Turns one 16x16 chunk column grid into the live viewer's tile: 256 packed vanilla map bytes ("c", index lz*16+lx, 0 =
 * transparent) and 256 int16 little-endian top heights ("h", {@link Short#MIN_VALUE} = none). The shading is the vanilla map's
 * (and the proxy's MapGenerator's): each column against the one to its north, water by its depth. Everything the encoder needs
 * from the world comes through {@link ColumnSource}, so it can be tested without a proxy.
 */
public final class MapTiles {
    private MapTiles() {
    }

    /** "No block with a map colour in this column." */
    public static final int NONE = Integer.MIN_VALUE;
    /** Map colour id of water. */
    public static final int WATER = 12;

    /** The vanilla shades, as the proxy's {@code Brightness} (LOWEST is not used for a flat map). */
    public enum Shade {
        LOW, NORMAL, HIGH
    }

    /** What the encoder reads. Block coordinates are world coordinates. May be called from a thread other than the network's. */
    public interface ColumnSource {
        /** Whether the chunk is loaded. */
        boolean loaded(int chunkX, int chunkZ);

        /** Lowest block y of the world (water depth is not counted below it). */
        int minY();

        /** The highest y of the column whose block has a map colour other than 0, or {@link #NONE}. */
        int topY(int x, int z);

        /** The map colour id of the block (0 for none). */
        int mapColor(int x, int y, int z);

        boolean water(int x, int y, int z);

        /** The packed map byte (colour id * 4 + shade) the vanilla map uses. */
        byte packed(int mapColor, Shade shade);
    }

    /** An encoded tile: the raw bytes, and a checksum of both. */
    public record Tile(byte[] colors, byte[] heights, int crc) {
        public String colorsBase64() {
            return Base64.getEncoder().encodeToString(colors);
        }

        public String heightsBase64() {
            return Base64.getEncoder().encodeToString(heights);
        }
    }

    public static Tile encode(ColumnSource src, int chunkX, int chunkZ) {
        byte[] c = new byte[256];
        byte[] h = new byte[512];
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        int minY = src.minY();
        boolean northLoaded = src.loaded(chunkX, chunkZ - 1);
        for (int lx = 0; lx < 16; lx++) {
            // The height of the column to the north, carried down the column row by row like the vanilla map does
            int north = NONE;
            if (northLoaded) north = src.topY(baseX + lx, baseZ - 1);
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx, z = baseZ + lz;
                int i = lz * 16 + lx;
                int top = src.topY(x, z);
                if (top == NONE) {
                    c[i] = 0;
                    putShort(h, i, Short.MIN_VALUE);
                    north = NONE;
                    continue;
                }
                int color = src.mapColor(x, top, z);
                Shade shade;
                if (color == WATER) {
                    int depth = 0;
                    if (top > minY && src.water(x, top, z)) {
                        int y = top - 1;
                        boolean water;
                        do {
                            water = src.water(x, y, z);
                            y--;
                            depth++;
                        } while (y > minY && water);
                    }
                    double d = depth * 0.1 + ((x + z) & 1) * 0.2;
                    shade = d < 0.5 ? Shade.HIGH : d > 0.9 ? Shade.LOW : Shade.NORMAL;
                }
                else {
                    // No north column (not loaded, or nothing there): flat
                    double d = north == NONE ? 0 : (top - north) * 4.0 / 5.0 + (((x + z) & 1) - 0.5) * 0.4;
                    shade = d > 0.6 ? Shade.HIGH : d < -0.6 ? Shade.LOW : Shade.NORMAL;
                }
                north = top;
                c[i] = src.packed(color, shade);
                putShort(h, i, (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, top)));
            }
        }
        CRC32 crc = new CRC32();
        crc.update(c);
        crc.update(h);
        return new Tile(c, h, (int) crc.getValue());
    }

    private static void putShort(byte[] h, int i, short v) {
        h[i * 2] = (byte) v;
        h[i * 2 + 1] = (byte) (v >> 8);
    }
}
