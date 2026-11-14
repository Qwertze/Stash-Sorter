package com.porter.qwerte.core.farm;

import com.porter.qwerte.core.farm.FarmRecording.Unsupported;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The click-timing compile rules (contract A5), as pure functions over per-tick states. The proxy's Bot differs from a
 * vanilla client, so recorded input becomes what the proxy should do:
 * <ol>
 *   <li>attack press on an entity or air: one left click on the press tick; the hold ticks click nothing. Attack on a
 *       block (press or hold): a click on every tick (block breaking).</li>
 *   <li>use press: one right click on the press tick; a use held for more than one tick clicks every 4 ticks counted
 *       from the press tick (or from the first held tick when the track starts mid-hold).</li>
 *   <li>a held use (more than one tick) with an item in {@link #HOLD_USE_ITEMS}, or in the off hand (the off-hand item is
 *       not recorded), clicks nothing and lists {@code hold_use_release} at the press tick; the proxy never releases a
 *       use. A one-tick click with such an item is allowed.</li>
 *   <li>rotation: tick k's yaw and pitch go with tick k's click (no look-ahead); the farmer refuses to start with
 *       {@code unsafe_rotation} if {@code botRotateBeforeInteract} is false. clickRequiresRotation is always true.</li>
 *   <li>both clicks on one tick are one {@link Click} with left and right set.</li>
 * </ol>
 * Ticks are recorded ticks; the player maps them to wall-clock time (D3), so the 4-tick cadence is in track ticks.
 */
public final class ClickCompiler {
    /** Use cadence of a held right click, in ticks. */
    public static final int USE_CADENCE = 4;

    /**
     * Items whose use is a hold that needs a release (food and drink, bows, crossbow, trident, shield, spyglass, goat
     * horn, brush). Hand-maintained: the core has no Minecraft data. Extend it here when a version adds one.
     */
    public static final Set<String> HOLD_USE_ITEMS = Set.of("minecraft:bow", "minecraft:crossbow", "minecraft:trident",
            "minecraft:shield", "minecraft:spyglass", "minecraft:goat_horn", "minecraft:brush", "minecraft:milk_bucket",
            "minecraft:potion", "minecraft:honey_bottle", "minecraft:ominous_bottle", "minecraft:apple",
            "minecraft:baked_potato", "minecraft:beef", "minecraft:beetroot", "minecraft:beetroot_soup", "minecraft:bread",
            "minecraft:carrot", "minecraft:chicken", "minecraft:chorus_fruit", "minecraft:cod", "minecraft:cooked_beef",
            "minecraft:cooked_chicken", "minecraft:cooked_cod", "minecraft:cooked_mutton", "minecraft:cooked_porkchop",
            "minecraft:cooked_rabbit", "minecraft:cooked_salmon", "minecraft:cookie", "minecraft:dried_kelp",
            "minecraft:enchanted_golden_apple", "minecraft:glow_berries", "minecraft:golden_apple",
            "minecraft:golden_carrot", "minecraft:melon_slice", "minecraft:mushroom_stew", "minecraft:mutton",
            "minecraft:poisonous_potato", "minecraft:porkchop", "minecraft:potato", "minecraft:pufferfish",
            "minecraft:pumpkin_pie", "minecraft:rabbit", "minecraft:rabbit_stew", "minecraft:rotten_flesh",
            "minecraft:salmon", "minecraft:spider_eye", "minecraft:suspicious_stew", "minecraft:sweet_berries",
            "minecraft:tropical_fish");

    private ClickCompiler() {
    }

    public enum Kind {
        MISS, BLOCK, ENTITY
    }

    /** What the crosshair is on. A block target carries its offset from the origin block; no absolute position. */
    public record ClickTarget(Kind kind, String entity, int dx, int dy, int dz, int face, String block) {
        public static final ClickTarget MISS = new ClickTarget(Kind.MISS, null, 0, 0, 0, 0, null);

        public static ClickTarget entity(String type) {
            return new ClickTarget(Kind.ENTITY, type, 0, 0, 0, 0, null);
        }

        public static ClickTarget block(int dx, int dy, int dz, int face, String block) {
            return new ClickTarget(Kind.BLOCK, null, dx, dy, dz, face, block);
        }
    }

    /** One tick of input. {@code attack} and {@code use}: 0 none, 1 press, 2 hold. {@code item}: selected item or null. */
    public record TickState(int t, double yaw, double pitch, int attack, int use, boolean offHandUse, String item,
                            ClickTarget target) {
        public TickState(int t, double yaw, double pitch, int attack, int use, String item, ClickTarget target) {
            this(t, yaw, pitch, attack, use, false, item, target);
        }
    }

    /** A request to submit on tick {@code t}, carrying that tick's own yaw and pitch. */
    public record Click(int t, boolean left, boolean right, double yaw, double pitch, ClickTarget target) {
    }

    public record Plan(List<Click> clicks, List<Unsupported> unsupported) {
    }

    /** The code listed for a held use that the proxy cannot release. */
    public static final String HOLD_USE_RELEASE = "hold_use_release";

    /** Compiles states (index order, normally t = 0..n-1) into clicks and unsupported entries. */
    public static Plan compile(List<TickState> ticks) {
        List<Click> clicks = new ArrayList<>();
        List<Unsupported> unsupported = new ArrayList<>();
        int n = ticks.size();
        boolean[] rightClick = new boolean[n];
        // use runs: maximal stretches with use != none, restarted by every press
        int i = 0;
        while (i < n) {
            if (ticks.get(i).use() == 0) {
                i++;
                continue;
            }
            int start = i;
            int j = i + 1;
            while (j < n && ticks.get(j).use() != 0 && ticks.get(j).use() != 1) {
                j++;
            }
            int length = j - start;
            TickState first = ticks.get(start);
            if (length == 1) {
                rightClick[start] = true;
            } else if ((first.item() != null && HOLD_USE_ITEMS.contains(first.item())) || first.offHandUse()) {
                unsupported.add(new Unsupported(HOLD_USE_RELEASE, first.t()));
            } else {
                for (int k = start; k < j; k += USE_CADENCE) {
                    rightClick[k] = true;
                }
            }
            i = j;
        }
        for (int k = 0; k < n; k++) {
            TickState s = ticks.get(k);
            boolean left = false;
            if (s.attack() != 0) {
                if (s.target().kind() == Kind.BLOCK) {
                    left = true;
                } else {
                    boolean pressEdge = s.attack() == 1 || k == 0 || ticks.get(k - 1).attack() == 0;
                    left = pressEdge;
                }
            }
            if (left || rightClick[k]) {
                clicks.add(new Click(s.t(), left, rightClick[k], s.yaw(), s.pitch(), s.target()));
            }
        }
        return new Plan(clicks, unsupported);
    }

    /**
     * The tick ranges {first, last} (inclusive, list indexes) of held uses that {@link #compile} refuses, so a caller can
     * neutralise them (the addon's "skip" choice) and compile again.
     */
    public static List<int[]> unsupportedRuns(List<TickState> ticks) {
        List<int[]> runs = new ArrayList<>();
        int n = ticks.size();
        int i = 0;
        while (i < n) {
            if (ticks.get(i).use() == 0) {
                i++;
                continue;
            }
            int start = i;
            int j = i + 1;
            while (j < n && ticks.get(j).use() != 0 && ticks.get(j).use() != 1) {
                j++;
            }
            TickState first = ticks.get(start);
            if (j - start > 1 && ((first.item() != null && HOLD_USE_ITEMS.contains(first.item())) || first.offHandUse())) {
                runs.add(new int[] {start, j - 1});
            }
            i = j;
        }
        return runs;
    }
}
