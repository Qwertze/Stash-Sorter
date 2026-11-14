package com.porter.qwertestash.stash;

/**
 * The bot's own hitbox against a block cell (1.21.0). A block put down where the box is leaves the bot pinned: the server
 * keeps teleporting it back. The box is the real one (0.6 wide, 1.8 high, from the feet up), taken a little wider than it is,
 * and it is tested with the real position, so a bot off the middle of its cell, or standing above the floor (a slab, a carpet,
 * a half step), is covered. Pure.
 */
public final class BotBox {
    private BotBox() {
    }

    /** The player's half width. */
    public static final double PLAYER_HALF_WIDTH = 0.3;
    /** Taken a little wider than it is (the proxy's own margin for placing next to the bot is the same). */
    public static final double MARGIN = 0.02;
    public static final double HALF_WIDTH = PLAYER_HALF_WIDTH + MARGIN;
    public static final double HEIGHT = 1.8;

    /** The box of a bot with its feet at (x, y, z) reaches into the cell (cx, cy, cz). */
    public static boolean overlapsCell(double x, double y, double z, int cx, int cy, int cz) {
        return cx < x + HALF_WIDTH && cx + 1 > x - HALF_WIDTH
            && cz < z + HALF_WIDTH && cz + 1 > z - HALF_WIDTH
            && cy < y + HEIGHT && cy + 1 > y;
    }
}
