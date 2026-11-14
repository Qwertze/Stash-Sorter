package com.porter.qwertestash.farm;

import com.porter.qwerte.core.farm.ClickCompiler.ClickTarget;
import com.porter.qwerte.core.farm.FarmRecording;
import com.porter.qwerte.core.farm.FarmRecording.Container;
import com.porter.qwerte.core.farm.FarmRecording.Mark;
import com.porter.qwerte.core.farm.FarmRecording.Vec;
import com.porter.qwerte.core.farm.ProgramCompiler;
import com.porter.qwerte.core.farm.ProgramCompiler.RawAnchor;
import com.porter.qwerte.core.farm.ProgramCompiler.RawRecording;
import com.porter.qwerte.core.farm.ProgramCompiler.RawTick;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** A tiny world for FarmPlayer: kinematics (0.2 per tick walking, 0.065 sneaking), a path that walks to a block, programs built from it. */
final class Sim implements FarmPlayer.Port {
    static final int OX = 100, OY = 64, OZ = 200;
    static final double WALK = 0.2, SNEAK = 0.065;

    double x = OX + 0.5, y = OY, z = OZ + 0.5;
    float yaw, pitch;
    String dim = "minecraft:overworld";
    boolean chunks = true;
    boolean busy;
    boolean pathFails;
    boolean hasItems = true;
    String below = "minecraft:stone", feet = "minecraft:air";
    final List<FarmPlayer.Cmd> sent = new ArrayList<>();
    final List<String> selected = new ArrayList<>();
    int pathsStarted;
    /** The goal blocks of every path started, as {x, y, z}. */
    final List<int[]> goals = new ArrayList<>();
    private FarmPlayer.Cmd last;
    private SimPath path;

    final class SimPath implements FarmPlayer.Path {
        final int gx, gy, gz;
        S s = S.RUNNING;

        SimPath(int gx, int gy, int gz) {
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
        }

        public S state() {
            return s;
        }

        public void cancel() {
            if (s == S.RUNNING) s = S.FAILED;
        }

        void advance() {
            if (s != S.RUNNING) return;
            if (pathFails) {
                s = S.FAILED;
                return;
            }
            double tx = gx + 0.5, tz = gz + 0.5;
            double dx = tx - x, dz = tz - z, d = Math.hypot(dx, dz);
            if (d <= 0.45) {
                s = S.DONE;
                return;
            }
            double step = Math.min(0.5, d);
            x += dx / d * step;
            z += dz / d * step;
            y = gy;
        }
    }

    public double[] feet() {
        return new double[] {x, y, z};
    }

    public String dimension() {
        return dim;
    }

    public String blockAt(int bx, int by, int bz) {
        int fy = (int) Math.floor(y);
        return by == fy - 1 ? below : by == fy ? feet : "minecraft:air";
    }

    public boolean chunkLoaded(int bx, int bz) {
        return chunks;
    }

    public void submit(FarmPlayer.Cmd c) {
        sent.add(c);
        last = c;
    }

    public boolean select(String item) {
        selected.add(item);
        return hasItems;
    }

    /** The held slot already holds the chosen item (a test sets false to see a click wait, and the time limit). */
    boolean holdOk = true;
    int holdAsks;

    public boolean holding(String item) {
        holdAsks++;
        return holdOk;
    }

    public boolean pathfinderBusy() {
        return busy;
    }

    public FarmPlayer.Path startPath(int bx, int by, int bz) {
        pathsStarted++;
        goals.add(new int[] {bx, by, bz});
        path = new SimPath(bx, by, bz);
        return path;
    }

    /** One physics step: applies what was submitted since the last step, then moves a running path. */
    void physics() {
        FarmPlayer.Cmd c = last;
        last = null;
        if (c != null) {
            if (c.look()) {
                yaw = c.yaw();
                pitch = c.pitch();
            }
            if ((c.keys() & FarmRecording.KEY_FORWARD) != 0) {
                double v = (c.keys() & FarmRecording.KEY_SNEAK) != 0 ? SNEAK : WALK;
                double r = Math.toRadians(yaw);
                x += -Math.sin(r) * v;
                z += Math.cos(r) * v;
            }
        }
        if (path != null) path.advance();
    }

    double distanceToStand(double sx, double sy, double sz) {
        return Math.sqrt(sq(x - (OX + sx)) + sq(y - (OY + sy)) + sq(z - (OZ + sz)));
    }

    private static double sq(double d) {
        return d * d;
    }

    // ---- programs --------------------------------------------------------------------------------------------------

    /** Forward 20 ticks (to relative z 4.5), still, back 20 ticks, still: a closed loop of 80 ticks with anchors a0 (tick 0) and a1 (tick 20). */
    static FarmRecording walkLoop(String mode, Map<String, Object> params, IntFunction<RawTick> override) {
        List<RawTick> t = new ArrayList<>();
        double z = 0.5;
        for (int i = 0; i < 80; i++) {
            int keys = 0;
            double yaw = 0;
            if (i < 20) {
                keys = FarmRecording.KEY_FORWARD;
                yaw = 0;
            } else if (i >= 40 && i < 60) {
                keys = FarmRecording.KEY_FORWARD;
                yaw = 180;
            } else if (i >= 60) {
                yaw = 180;
            } else {
                yaw = 0;
            }
            RawTick ov = override == null ? null : override.apply(i);
            t.add(ov != null ? ov : new RawTick(keys, yaw, 0, 0, 0, false, 0, "minecraft:bone_meal", ClickTarget.MISS, new Vec(0.5, 0.0, z), List.of(), false));
            if (keys != 0) z += yaw == 0 ? WALK : -WALK;
        }
        List<RawAnchor> anchors = List.of(
            new RawAnchor("a0", new Vec(0.5, 0.0, 0.5), 0.0, 0.0, 0.3, "minecraft:stone", "minecraft:air", 0),
            new RawAnchor("a1", new Vec(0.5, 0.0, 4.5), 0.0, 0.0, 0.3, "minecraft:stone", "minecraft:air", 20));
        return ProgramCompiler.compile(new RawRecording("1.21.11", "1.21.4", mode, "minecraft:overworld", params, 0, 80, true, anchors,
            List.<Mark>of(), List.<Container>of(), List.of(), List.of(), t), false).program();
    }

    static Map<String, Object> loops(long max) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("repeat", true);
        p.put("maxLoops", max);
        return p;
    }

    /** afk_still at anchor a0. */
    static FarmRecording still() {
        List<RawTick> t = new ArrayList<>();
        for (int i = 0; i < 40; i++) t.add(new RawTick(0, 0, 0, 0, 0, false, 0, null, ClickTarget.MISS, new Vec(0.5, 0.0, 0.5), List.of(), false));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("anchor", "a0");
        List<RawAnchor> anchors = List.of(new RawAnchor("a0", new Vec(0.5, 0.0, 0.5), 0.0, 0.0, 0.3, "minecraft:stone", "minecraft:air", 0));
        return ProgramCompiler.compile(new RawRecording("1.21.11", "1.21.4", "afk_still", "minecraft:overworld", p, 0, 40, true, anchors,
            List.<Mark>of(), List.<Container>of(), List.of(), List.of(), t), false).program();
    }

    static FarmPlayer player(FarmRecording rec, Sim sim) {
        FarmPlayer p = new FarmPlayer(FarmProgram.of(rec), OX, OY, OZ, sim);
        p.expectDimension("minecraft:overworld");
        return p;
    }

    /** Runs bot ticks of {@code dtMs} until {@code until} says stop or {@code max} ticks passed; returns the clock. */
    static long run(FarmPlayer p, Sim sim, long now, long dtMs, int max, java.util.function.BooleanSupplier until) {
        for (int i = 0; i < max; i++) {
            if (until.getAsBoolean()) break;
            p.tick(now);
            sim.physics();
            now += dtMs;
        }
        return now;
    }

    static Map<String, Integer> counts(List<FarmPlayer.Cmd> cmds) {
        Map<String, Integer> m = new HashMap<>();
        for (var c : cmds) {
            if (c.left()) m.merge("left", 1, Integer::sum);
            if (c.right()) m.merge("right", 1, Integer::sum);
        }
        return m;
    }
}
