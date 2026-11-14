package com.porter.qwerte.core.farm;

import com.porter.qwerte.core.farm.FarmRecording.Rel;
import com.porter.qwerte.core.farm.FarmRecording.Vec;

/**
 * Anchor-relative position maths (contract A4). Everything here works on offsets from the origin block (the corner of
 * the block that anchor {@code a0} stands in); the absolute origin is never an input except in {@link #absolute}, which
 * the caller uses in memory only (the plugin's player cache) and must never write anywhere.
 */
public final class AnchorMath {
    /** Distances are compared with this slack so that 0.3 against 0.3 is "within" despite binary rounding. */
    private static final double EPS = 1e-9;

    private AnchorMath() {
    }

    /** A feet offset split into a whole-block offset and a stand point inside that block (3 decimals). */
    public record Split(Rel rel, Vec stand) {
    }

    private static int[] splitAxis(double offset) {
        // returns {rel, standMilli}; stand = standMilli / 1000
        double f = Math.floor(offset);
        long milli = Math.round((offset - f) * 1000.0);
        long rel = (long) f;
        if (milli >= 1000) {
            rel += 1;
            milli = 0;
        }
        if (rel < Integer.MIN_VALUE || rel > Integer.MAX_VALUE) {
            throw new FarmException("bad_program");
        }
        return new int[] {(int) rel, (int) milli};
    }

    /** rel = floor(offset); stand = offset - rel rounded to 3 decimals; a stand that rounds to 1.000 carries into rel. */
    public static Split split(double ox, double oy, double oz) {
        if (!Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)) {
            throw new FarmException("bad_program");
        }
        int[] x = splitAxis(ox);
        int[] y = splitAxis(oy);
        int[] z = splitAxis(oz);
        return new Split(new Rel(x[0], y[0], z[0]), new Vec(x[1] / 1000.0, y[1] / 1000.0, z[1] / 1000.0));
    }

    public static boolean inRange(int dx, int dy, int dz) {
        return Math.abs((long) dx) <= ProgramChecker.REL_XZ && Math.abs((long) dz) <= ProgramChecker.REL_XZ
                && Math.abs((long) dy) <= ProgramChecker.REL_Y;
    }

    /** The anchor's stand point as an offset from the origin block corner: rel + stand. */
    public static Vec standPoint(FarmRecording.Anchor a) {
        return new Vec(a.rel().x() + a.stand().x(), a.rel().y() + a.stand().y(), a.rel().z() + a.stand().z());
    }

    /** Translates an offset by the origin block: in-memory use only, the result is an absolute position. */
    public static Vec absolute(int originX, int originY, int originZ, Vec offset) {
        return new Vec(originX + offset.x(), originY + offset.y(), originZ + offset.z());
    }

    public static double distance(Vec a, Vec b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** At an anchor: within = 3D distance at most tol. */
    public static boolean within(double distance, double tol) {
        return distance <= tol + EPS;
    }

    /** Early desync between anchors (20-tick samples): distance > max(1.0, 3 x tol). */
    public static boolean earlyDesync(double distance, double tol) {
        return distance > Math.max(1.0, 3.0 * tol) + EPS;
    }

    /**
     * The origin cross-check: the client's block view minus the plugin's. Null when accepted, else {@code wrong_dim}
     * (different dimension, checked first) or {@code origin_mismatch} (an axis differs by more than 1).
     */
    public static String originCrossCheck(int diffX, int diffY, int diffZ, boolean sameDimension) {
        if (!sameDimension) {
            return "wrong_dim";
        }
        if (Math.abs((long) diffX) > 1 || Math.abs((long) diffY) > 1 || Math.abs((long) diffZ) > 1) {
            return "origin_mismatch";
        }
        return null;
    }
}
