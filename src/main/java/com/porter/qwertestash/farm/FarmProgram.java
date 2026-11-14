package com.porter.qwertestash.farm;

import com.porter.qwerte.core.farm.ClickCompiler;
import com.porter.qwerte.core.farm.FarmRecording;
import com.porter.qwerte.core.farm.ProgramCompiler;

import java.util.List;

/**
 * A program laid out per tick for playback: movement keys, look, the compiled clicks (A5), hotbar choices and the 20-tick samples.
 * All positions are relative to the origin block; nothing here is absolute. Pure.
 */
public final class FarmProgram {
    public final FarmRecording rec;
    public final int ticks;
    public final int[] keys;
    public final float[] yaw;
    public final float[] pitch;
    public final ClickCompiler.Click[] clickAt;
    /** Item id chosen at a tick (hotbar record), or null; the empty hand is "" . */
    public final String[] hotbarItem;
    /** Feet sample relative to the origin block corner, or null. */
    public final float[][] sample;
    public final boolean hasContainerOps;

    private FarmProgram(FarmRecording rec) {
        this.rec = rec;
        var h = rec.header();
        this.ticks = h.ticks();
        keys = new int[ticks];
        yaw = new float[ticks];
        pitch = new float[ticks];
        clickAt = new ClickCompiler.Click[ticks];
        hotbarItem = new String[ticks];
        sample = new float[ticks][];
        var a0 = h.anchors().get(0);
        int k = 0;
        float y = (float) a0.yaw();
        float p = (float) a0.pitch();
        int ri = 0;
        boolean container = false;
        List<FarmRecording.TrackRecord> tr = rec.track();
        for (int t = 0; t < ticks; t++) {
            while (ri < tr.size() && tr.get(ri).tick() == t) {
                FarmRecording.TrackRecord r = tr.get(ri++);
                if (r.keys() != null) k = r.keys();
                if (r.look() != null) {
                    y = r.look().yaw();
                    p = r.look().pitch();
                }
                if (r.hotbar() != null) hotbarItem[t] = r.hotbar().itemRef() == 0 ? "" : h.items().get(r.hotbar().itemRef() - 1);
                if (r.sample() != null) sample[t] = new float[] {r.sample().x(), r.sample().y(), r.sample().z()};
                if (r.container() != null) container = true;
            }
            keys[t] = k;
            yaw[t] = y;
            pitch[t] = p;
        }
        hasContainerOps = container;
        for (ClickCompiler.Click c : ProgramCompiler.clicks(rec).clicks()) {
            if (c.t() >= 0 && c.t() < ticks) clickAt[c.t()] = c;
        }
    }

    public static FarmProgram of(FarmRecording rec) {
        return new FarmProgram(rec);
    }

    /** The first container step or M3-only step the program holds, or null. M2 refuses these at the start (mode_not_ready). */
    public String m3Only() {
        if (hasContainerOps) return "container";
        var steps = rec.header().steps();
        for (var list : List.of(steps.start(), steps.stop())) {
            for (var s : list) {
                Object op = s.get("op");
                if ("wait-until".equals(op) || "take-from".equals(op) || "put-into".equals(op) || "hold-use".equals(op) || "attack".equals(op)) {
                    return String.valueOf(op);
                }
            }
        }
        return null;
    }
}
