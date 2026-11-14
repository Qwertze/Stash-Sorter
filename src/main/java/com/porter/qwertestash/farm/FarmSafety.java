package com.porter.qwertestash.farm;

import java.util.List;

/**
 * The safety signals of D7, as a pure state machine fed one {@link Sample} per bot tick: someone near (pause inputs within 8
 * blocks, end {@code player_near} after the farm's radius for its seconds), low health that is falling, no food with nothing to
 * eat, no totem in the off hand for 2 s, no free slot for 30 s. No position goes in or out: distances only, and no name.
 */
public final class FarmSafety {
    public static final double PAUSE_RADIUS = 8.0;
    public static final long TOTEM_GRACE_MS = 2_000;
    public static final long FULL_MS = 30_000;

    /** The farm's thresholds (E1); the defaults are the contract's. */
    public record Thresholds(int healthStop, int food, int totems, int playerRadius, int playerSeconds, int invFullPct) {
        public static final Thresholds DEFAULT = new Thresholds(10, 6, 1, 16, 10, 90);
    }

    /**
     * @param nonFriendDistances distances (blocks) of every player in view that is not a friend
     * @param hasFood            something to eat is in the inventory (AutoEat has food)
     * @param totemInOffhand     a totem is in the off hand
     * @param freeSlots          empty slots in the inventory
     */
    public record Sample(double health, int food, boolean hasFood, boolean totemInOffhand, int freeSlots, List<Double> nonFriendDistances) {
    }

    /** @param endCode a code that ends the session, or null; @param pauseInputs inputs pause at once */
    public record Verdict(String endCode, boolean pauseInputs) {
    }

    private final Thresholds th;
    private double lastHealth = Double.NaN;
    private long nearSince = -1;
    private long noTotemSince = -1;
    private long fullSince = -1;

    public FarmSafety(Thresholds th) {
        this.th = th == null ? Thresholds.DEFAULT : th;
    }

    public Verdict evaluate(Sample s, long now) {
        double nearest = Double.MAX_VALUE;
        for (double d : s.nonFriendDistances()) nearest = Math.min(nearest, d);
        boolean pause = nearest <= PAUSE_RADIUS;
        if (nearest <= th.playerRadius()) {
            if (nearSince < 0) nearSince = now;
            if (now - nearSince >= th.playerSeconds() * 1000L) return new Verdict("player_near", true);
        } else {
            nearSince = -1;
        }
        boolean low = s.health() <= th.healthStop();
        boolean known = !Double.isNaN(lastHealth);
        boolean prevLow = known && lastHealth <= th.healthStop();
        // "at or below and falling": it dropped since the last sample, or it was already low and has not risen
        if (low && known && (s.health() < lastHealth || (prevLow && s.health() <= lastHealth))) return new Verdict("low_health", true);
        lastHealth = s.health();
        if (s.food() <= th.food() && !s.hasFood()) return new Verdict("no_food", pause);
        if (th.totems() > 0) {
            if (s.totemInOffhand()) {
                noTotemSince = -1;
            } else {
                if (noTotemSince < 0) noTotemSince = now;
                if (now - noTotemSince >= TOTEM_GRACE_MS) return new Verdict("no_totem", pause);
            }
        }
        if (s.freeSlots() <= 0) {
            if (fullSince < 0) fullSince = now;
            if (now - fullSince >= FULL_MS) return new Verdict("inventory_full", pause);
        } else {
            fullSince = -1;
        }
        return new Verdict(null, pause);
    }
}
