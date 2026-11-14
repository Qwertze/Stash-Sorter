package com.porter.qwertestash.module;

import java.util.ArrayDeque;

/**
 * Tells when the bot is pinned (1.21.0): held in one place, the server teleporting it back every tick, which is what a block put
 * down inside its own hitbox does. Pure: times come in as milliseconds, so it is tested without the game.
 * <p>
 * Pinned is either of
 * <ul>
 * <li>{@link #SETBACKS} or more position corrections from the server within {@link #SETBACK_WINDOW_MS} (a bot that was held
 * at one spot got about 10 a second; one teleport, a login or a respawn is 1);</li>
 * <li>a walk that has not moved the bot {@link #STILL_RADIUS} blocks in {@link #STATIONARY_MS} while a block we put down
 * ourselves overlaps its box.</li>
 * </ul>
 * Pinned ends when no correction came in the last {@link #QUIET_MS} and no block of ours overlaps the box any more.
 * {@link #noteSetback} comes from the network thread; everything else from the bot tick.
 */
final class PinWatch {
    static final int SETBACKS = 20;
    static final long SETBACK_WINDOW_MS = 5_000;
    static final long STATIONARY_MS = 20_000;
    static final double STILL_RADIUS = 0.5;
    static final long QUIET_MS = 3_000;

    private final ArrayDeque<Long> setbacks = new ArrayDeque<>();
    private boolean pinned;
    private long pinnedAt;
    private String why = "";
    // The walk-still check: where the bot was when it last moved, since when, and since when a block of ours is in its box
    private double ax, ay, az;
    private long stillSince = -1;
    private long obstructedSince = -1;

    /** A server position correction (any thread). */
    synchronized void noteSetback(long ms) {
        setbacks.addLast(ms);
        while (setbacks.size() > 4 * SETBACKS) setbacks.pollFirst();
    }

    /**
     * Bot tick, every tick. @param walking the bot is meant to be going somewhere (a walk of ours is on)
     * @param obstructed a block we put down overlaps the bot's box
     * @return whether the bot is pinned now
     */
    synchronized boolean observe(long ms, double x, double y, double z, boolean walking, boolean obstructed) {
        while (!setbacks.isEmpty() && ms - setbacks.peekFirst() > SETBACK_WINDOW_MS) setbacks.pollFirst();
        double moved = Math.sqrt((x - ax) * (x - ax) + (y - ay) * (y - ay) + (z - az) * (z - az));
        if (!walking || stillSince < 0 || moved > STILL_RADIUS) {
            ax = x;
            ay = y;
            az = z;
            stillSince = ms;
        }
        if (!obstructed) obstructedSince = -1;
        else if (obstructedSince < 0) obstructedSince = ms;

        if (!pinned) {
            if (setbacks.size() >= SETBACKS) {
                pinned = true;
                pinnedAt = ms;
                why = "the server keeps moving it back (" + setbacks.size() + " corrections in " + SETBACK_WINDOW_MS / 1000 + " s)";
            }
            else if (walking && obstructed && ms - Math.max(stillSince, obstructedSince) >= STATIONARY_MS) {
                pinned = true;
                pinnedAt = ms;
                why = "a block it put down is in its way and it has not moved for " + STATIONARY_MS / 1000 + " s";
            }
            return pinned;
        }
        boolean quiet = setbacks.isEmpty() || ms - setbacks.peekLast() >= QUIET_MS;
        if (quiet && !obstructed) {
            pinned = false;
            setbacks.clear();
            stillSince = ms;
            ax = x;
            ay = y;
            az = z;
        }
        return pinned;
    }

    synchronized boolean pinned() {
        return pinned;
    }

    synchronized long pinnedAt() {
        return pinnedAt;
    }

    synchronized String why() {
        return why;
    }

    synchronized void reset() {
        setbacks.clear();
        pinned = false;
        stillSince = obstructedSince = -1;
    }
}
