package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.EnchantRules;

/**
 * Throws experience bottles until the bot's level reaches a target, for the anvil steps of an enchant order (ENCHANT-CONTRACT v1.0, "What is
 * minimised and how the bot pays"). Built from {@link RepairTrip}'s throw loop: one bottle a throw gap, in batches while far from the target,
 * single bottles once near it, and the level looked at after each batch has settled. It never throws more than the target needs (a bottle
 * gives 3 to 11 points, mean 7) beyond the one bottle that crosses the line, because XP above the step cost is paid by the next steps at
 * a higher level. Plain Java behind {@link Port}; one {@link #tick} per game tick, on the bot tick thread.
 */
public final class XpLeveler {
    /** Ticks between two throws and the most throws in one batch while far from the target. */
    public static final int THROW_GAP = 4, MAX_BATCH = 8;
    /** After a batch: at least this long, and at most this long, before the level is looked at. */
    public static final int SETTLE_MIN = 12, SETTLE_MAX = 60;
    /** Batches in a row that raised the points by nothing: the orbs go elsewhere (Mending gear, another player), so the leveler gives up. */
    public static final int MAX_DRY_BATCHES = 3;
    /** A click the port keeps refusing for this long is given up on. */
    public static final int STUCK_TICKS = 200;
    /** Within this many estimated bottles of the target the bot throws singles. */
    public static final int SINGLES_WITHIN = 3;

    /** What the leveler needs from the world. */
    public interface Port {
        /** A click of ours is outstanding, or the inventory queue is not free: wait. */
        boolean busy();

        /** The experience points the bot holds now, level and bar together (a fraction of a level counts). */
        double points();

        /** Bottles in the inventory in all (hand included). */
        int bottles();

        /** A bottle stack is in the hand a bottle is thrown from. When it is not and bottles exist, {@link #refill} is asked. */
        boolean bottleReady();

        /** Moves a bottle stack into that hand. False: refused, ask again. */
        boolean refill();

        /** Throws one bottle at the bot's feet. False: refused (the bot is turning to look down, say), ask again. */
        boolean throwBottle();

        /** Another player (not a friend) is within the radius, or something else wants the bot to hold still. */
        boolean playerNear();

        /** Experience orbs lying about within reach of the bot. */
        int orbsNear();

        /** The order ended, was cancelled or parked, or the bot can't work: stop. */
        boolean abort();
    }

    public enum Result {
        /** Still working. */
        Working,
        /** The level is at the target. */
        Reached,
        /** No bottles are left in the inventory and the target is not reached. */
        NoBottles,
        /** A player came near: stopped between throws (the caller waits and asks again). */
        Player,
        /** The order was aborted. */
        Aborted,
        /** Bottles were thrown and the points did not rise: the orbs go elsewhere. */
        Dry,
        /** A refused click for too long. */
        Stuck
    }

    private enum Phase { Throw, Settle }

    private final Port port;
    private final int target;
    private Phase phase = Phase.Throw;
    private int sinceThrow = THROW_GAP, batchLeft, settle, dry, stuck, thrown, startBottles = -1;
    private double pointsBefore = -1;
    private Result result = Result.Working;
    /** Bottles were thrown whose orbs have not been looked at yet. */
    private boolean unsettled;

    /** @param targetLevel the level to reach: at least this many levels when the throwing stops */
    public XpLeveler(Port port, int targetLevel) {
        this.port = port;
        this.target = Math.max(0, targetLevel);
    }

    public int target() {
        return target;
    }

    /** The result so far ({@link Result#Working} until the leveler is done). */
    public Result result() {
        return result;
    }

    public boolean done() {
        return result != Result.Working;
    }

    /** Bottles thrown by this leveler. */
    public int thrown() {
        return thrown;
    }

    /** The level the points amount to (the points of the target level reached or passed). */
    public static boolean reached(double points, int targetLevel) {
        return points + 1e-9 >= EnchantRules.points(targetLevel);
    }

    /** Estimated bottles still needed to get from these points to the target (7 points a bottle, at least 0). */
    public static int bottlesLeft(double points, int targetLevel) {
        double need = EnchantRules.points(targetLevel) - points;
        return need <= 0 ? 0 : (int) Math.ceil(need / EnchantRules.XP_PER_BOTTLE);
    }

    /** The throws of the next batch: singles near the target, else most of what is still needed (never all of it). */
    public static int batchSize(double points, int targetLevel) {
        int left = bottlesLeft(points, targetLevel);
        if (left <= SINGLES_WITHIN) return 1;
        return Math.max(1, Math.min(MAX_BATCH, (int) Math.floor(left * 0.7)));
    }

    public Result tick() {
        if (result != Result.Working) return result;
        if (port.abort()) return result = Result.Aborted;
        if (port.busy()) return Result.Working;
        if (startBottles < 0) startBottles = port.bottles();
        if (phase == Phase.Throw) {
            if (reached(port.points(), target)) return result = Result.Reached;
            if (port.playerNear()) return result = Result.Player;
            return throwing();
        }
        return settling();
    }

    private Result throwing() {
        double points = port.points();
        if (!port.bottleReady()) {
            if (port.bottles() <= 0) {
                // Bottles still in the air count: the orbs are looked at first
                if (unsettled) {
                    phase = Phase.Settle;
                    settle = 0;
                    return Result.Working;
                }
                return result = Result.NoBottles;
            }
            if (!port.refill() && ++stuck > STUCK_TICKS) return result = Result.Stuck;
            return Result.Working;
        }
        stuck = 0;
        if (batchLeft <= 0) {
            batchLeft = batchSize(points, target);
            if (pointsBefore < 0) pointsBefore = points;
        }
        sinceThrow++;
        if (sinceThrow >= THROW_GAP && port.throwBottle()) {
            sinceThrow = 0;
            thrown++;
            unsettled = true;
            if (--batchLeft <= 0) {
                phase = Phase.Settle;
                settle = 0;
            }
        }
        return Result.Working;
    }

    private Result settling() {
        settle++;
        boolean quiet = port.orbsNear() == 0;
        if (settle < SETTLE_MIN || (!quiet && settle < SETTLE_MAX)) return Result.Working;
        double points = port.points();
        if (pointsBefore >= 0 && points <= pointsBefore + 1e-9) {
            if (++dry >= MAX_DRY_BATCHES) return result = Result.Dry;
        }
        else dry = 0;
        pointsBefore = points;
        unsettled = false;
        batchLeft = 0;
        phase = Phase.Throw;
        sinceThrow = THROW_GAP;
        if (reached(points, target)) return result = Result.Reached;
        if (port.bottles() <= 0 && !port.bottleReady()) return result = Result.NoBottles;
        return Result.Working;
    }
}
