package com.porter.qwertestash.orders;

import java.util.List;

/**
 * Chooses where the next load of an order is taken from, the way the addon does: a loose stack before one inside a shulker,
 * the variant the load names before another, a stack with exactly enough (or the smallest with more) before a short one,
 * and the nearest on a tie. Pure.
 */
public final class SourcePicker {
    private SourcePicker() {
    }

    /** @param id the caller's handle for it */
    public record Cand(int id, boolean inShulker, boolean sameVariant, int count, double distance) {
    }

    /** Lower is better. */
    public static double score(Cand c, int want) {
        double s = c.inShulker() ? 1e9 : 0;
        if (!c.sameVariant()) s += 1e6;
        s += c.count() >= want ? c.count() - want : 10_000 + (want - c.count());
        return s + c.distance() * 1e-3;
    }

    /** @return the {@link Cand#id} of the best candidate, or -1 when there is none */
    public static int pick(List<Cand> cands, int want) {
        Cand best = null;
        double bestScore = Double.MAX_VALUE;
        for (Cand c : cands) {
            double s = score(c, want);
            if (s < bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return best == null ? -1 : best.id();
    }
}
