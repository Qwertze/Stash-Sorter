package com.porter.qwertestash.module;

import com.porter.qwertestash.stash.BotBox;
import com.porter.qwertestash.stash.PlaceSearch;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the sorter does about being pinned (1.21.0), apart from the game. Pure.
 */
final class PinPolicy {
    private PinPolicy() {
    }

    enum Move { Break, StepOut, Wait }

    /** A block of ours that may be mined back is in the bot's box: it is broken first, a few tries, then the bot steps out. */
    static final int BREAK_TRIES = 3;
    /** The bot has moved less than this (blocks) between two tries: it is held in place, not just being corrected while it walks. */
    static final double STILL = 0.2;

    /**
     * The next try, when the last ones got nowhere. {@code attempt} counts from 0.
     * @param minable a block that may be mined back is in the way
     * @param moved how far the bot got since the last try; a bot that is moving is only paused, never pushed
     * @param hasJob the sorter has something to do (idle or resting: pause only)
     */
    static Move next(int attempt, boolean minable, double moved, boolean hasJob) {
        if (moved > STILL) return Move.Wait;
        if (minable && attempt < BREAK_TRIES) return Move.Break;
        if (!hasJob) return Move.Wait;
        // Stepping out is tried on the other attempts, with a rest between: the server may be busy correcting
        return attempt % 2 == 0 ? Move.StepOut : Move.Wait;
    }

    // What the bot may stand next to when it steps out

    /** Blocks never stepped into or onto: fluids, fire, magma and the like (and a cell that is not loaded). */
    static final Set<String> HAZARDS = Set.of("water", "lava", "bubble_column", "fire", "soul_fire", "magma_block", "cactus", "sweet_berry_bush",
        "powder_snow", "campfire", "soul_campfire", "wither_rose", "cobweb", "unloaded");

    interface Ground {
        /** The block name, "air" for none, "unloaded" for a chunk that is not loaded. */
        String name(int x, int y, int z);

        /** A block that stops the body (a full collision block). */
        boolean solid(int x, int y, int z);
    }

    /** The bot can stand with its feet in this cell: room for the body, a solid safe floor (or one block down), no hazard. */
    static boolean safeStep(int x, int y, int z, Ground g) {
        for (int dy = 0; dy <= 1; dy++) {
            if (g.solid(x, y + dy, z) || HAZARDS.contains(g.name(x, y + dy, z))) return false;
        }
        if (HAZARDS.contains(g.name(x, y - 1, z))) return false;
        if (g.solid(x, y - 1, z)) return true;
        // A drop of one block at most
        return g.solid(x, y - 2, z) && !HAZARDS.contains(g.name(x, y - 2, z));
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * The direction to step out in, from the feet cell: the four directions in turn, the ones nearest the preferred direction first, and
     * only one whose next cell is safe (and the one after it too, unless a wall stops the body there). Null when none is: wait.
     */
    static int @Nullable [] chooseStep(int fx, int fy, int fz, double prefDx, double prefDz, Ground g) {
        List<int[]> order = new ArrayList<>(List.of(DIRS));
        order.sort((p, q) -> Double.compare(q[0] * prefDx + q[1] * prefDz, p[0] * prefDx + p[1] * prefDz));
        for (int[] d : order) {
            if (!safeStep(fx + d[0], fy, fz + d[1], g)) continue;
            int x2 = fx + 2 * d[0], z2 = fz + 2 * d[1];
            if (safeStep(x2, fy, z2, g) || g.solid(x2, fy, z2)) return d;
        }
        return null;
    }

    /** What may be mined back to free the bot: an anvil or a crafting table, outside the stash area; never a container or a shulker. */
    static boolean minable(String blockName, boolean inStashOrContainer) {
        if (inStashOrContainer) return false;
        return blockName.equals("anvil") || blockName.equals("chipped_anvil") || blockName.equals("damaged_anvil") || blockName.equals("crafting_table");
    }

    /** One cell of the bot's box, for the scan after a restart. */
    record Cell(int x, int y, int z, String name, boolean inStashOrContainer) {
    }

    /** The first cell of the box that may be mined back, or null (the scan after a restart, when nothing remembered explains the pin). */
    static @Nullable Cell firstMinable(List<Cell> box) {
        for (Cell c : box) if (minable(c.name(), c.inStashOrContainer())) return c;
        return null;
    }

    // The pinned alerts

    enum Alert { None, Pinned, NeedsHelp }
    static final long FIRST_ALERT_MS = 60_000, REPEAT_ALERT_MS = 5 * 60_000L, HELP_ALERT_MS = 30 * 60_000L;

    /** Which alert is due: one after a minute, then every 5 minutes, and once a different one after 30 minutes. */
    static Alert alert(long pinnedMs, long lastAlertAtMs, boolean helpSent) {
        if (pinnedMs >= HELP_ALERT_MS && !helpSent) return Alert.NeedsHelp;
        if (lastAlertAtMs < 0) return pinnedMs >= FIRST_ALERT_MS ? Alert.Pinned : Alert.None;
        return pinnedMs - lastAlertAtMs >= REPEAT_ALERT_MS ? Alert.Pinned : Alert.None;
    }

    /** Why a block may not be asked for at this cell because the bot's own body is in it, or null. */
    static @Nullable String placeRefusal(double x, double y, double z, int cx, int cy, int cz) {
        return BotBox.overlapsCell(x, y, z, cx, cy, cz) ? PlaceSearch.BODY : null;
    }

    /**
     * A walk that ran out of time or found no way fails its job only when the bot was free to move. A walk that a pin cut short
     * (the pin is on, or ended after the job began) got nowhere because of the pin and is tried again.
     */
    static boolean walkFailureCounts(boolean pinnedNow, long jobStartedMs, long lastPinEndMs) {
        return !pinnedNow && (lastPinEndMs < 0 || lastPinEndMs <= jobStartedMs);
    }

    /**
     * Why delivering failed (the sticky reason of the order flow), and whether it was a walk that failed. A walk failure that
     * a pin caused is forgotten when the bot is free again; one with no pin near it stays until the delivery spot changes or the
     * sorter restarts, as before.
     */
    static final class DeliveryFailure {
        private @Nullable String reason;
        private boolean walk;
        private long atMs;

        void fail(String why, boolean walkFailed, long ms) {
            reason = why;
            walk = walkFailed;
            atMs = ms;
        }

        void clear() {
            reason = null;
            walk = false;
        }

        @Nullable String reason() {
            return reason;
        }

        /** A pin began or ended: a walk failure from {@code sinceMs} on was the pin's, not the spot's. @return whether one was cleared */
        boolean forgetWalkSince(long sinceMs) {
            if (reason != null && walk && atMs >= sinceMs) {
                clear();
                return true;
            }
            return false;
        }
    }

    /** Things marked unreachable, with when: the ones marked around a pin are given back when it ends. */
    static final class RecentMarks<T> {
        private final Map<T, Long> at = new LinkedHashMap<>();

        void mark(T t, long ms) {
            at.put(t, ms);
        }

        void forget(T t) {
            at.remove(t);
        }

        /** Takes out and returns everything marked at or after {@code sinceMs}. */
        List<T> takeSince(long sinceMs) {
            List<T> out = new ArrayList<>();
            var it = at.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue() >= sinceMs) {
                    out.add(e.getKey());
                    it.remove();
                }
            }
            return out;
        }

        void clear() {
            at.clear();
        }
    }
}
