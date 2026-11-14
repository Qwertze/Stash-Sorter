package com.porter.qwerte.core.farm;

import com.porter.qwerte.core.farm.ClickCompiler.ClickTarget;
import com.porter.qwerte.core.farm.ClickCompiler.TickState;
import com.porter.qwerte.core.farm.FarmRecording.Anchor;
import com.porter.qwerte.core.farm.FarmRecording.ContainerAction;
import com.porter.qwerte.core.farm.FarmRecording.Fp;
import com.porter.qwerte.core.farm.FarmRecording.Header;
import com.porter.qwerte.core.farm.FarmRecording.Hotbar;
import com.porter.qwerte.core.farm.FarmRecording.Look;
import com.porter.qwerte.core.farm.FarmRecording.Sample;
import com.porter.qwerte.core.farm.FarmRecording.Steps;
import com.porter.qwerte.core.farm.FarmRecording.Target;
import com.porter.qwerte.core.farm.FarmRecording.Template;
import com.porter.qwerte.core.farm.FarmRecording.TrackRecord;
import com.porter.qwerte.core.farm.FarmRecording.Unsupported;
import com.porter.qwerte.core.farm.FarmRecording.Use;
import com.porter.qwerte.core.farm.FarmRecording.Vec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a raw per-tick recording into a program (contract Part A, A4 and A5) and a program back into per-tick click
 * requests for the player.
 *
 * <p>Input is offsets only: positions in {@link RawRecording} are already relative to the origin block (the addon
 * subtracts the origin in memory), so the compiler never sees an absolute coordinate and none can reach the program.
 *
 * <p>{@link #compile} keeps what the player needs in the track (key state, look, attack and use state, target, hotbar,
 * samples, closed container steps) and runs the click-timing rules ({@link ClickCompiler}) to find the holds the proxy
 * cannot release; they are listed in {@code header.unsupported} (or neutralised when {@code skipUnsupported} is set).
 * At playback {@link #clicks(FarmRecording)} turns the track into the per-tick requests: tick k's yaw and pitch with
 * tick k's click.
 */
public final class ProgramCompiler {
    private ProgramCompiler() {
    }

    /** One raw container action; RAW_CLICK (slot clicks) is dropped by the compiler (A5.4). */
    public record RawContainerAction(Type type, int container, String item, int count) {
        public enum Type {
            TAKE, PUT, PUT_ALL_BUT_KIT, RAW_CLICK
        }
    }

    /**
     * The state at one tick. {@code keys} is the movement mask (KEY_* bits), {@code attack} and {@code use} are 0 none, 1
     * press (first tick), 2 hold; {@code hotbarSlot} 0..8 with the selected {@code item} (null for an empty hand);
     * {@code feet} is the feet offset from the origin block corner or null when not sampled this tick.
     */
    public record RawTick(int keys, double yaw, double pitch, int attack, int use, boolean offHandUse, int hotbarSlot,
                          String item, ClickTarget target, Vec feet, List<RawContainerAction> containerActions,
                          boolean swing) {
        public RawTick {
            containerActions = List.copyOf(containerActions);
            Objects.requireNonNull(target);
        }
    }

    /** An anchor as recorded: the feet offset from the origin block corner (split into rel and stand by the compiler). */
    public record RawAnchor(String id, Vec offset, double yaw, double pitch, double tol, String fpBelow, String fpFeet,
                            int tick) {
    }

    public record RawRecording(String mcClient, String mcProxy, String mode, String dim, Map<String, Object> params,
                               int loopStart, int loopEnd, boolean loopRepeat, List<RawAnchor> anchors,
                               List<FarmRecording.Mark> marks, List<FarmRecording.Container> containers,
                               List<Map<String, Object>> startSteps, List<Map<String, Object>> stopSteps,
                               List<RawTick> ticks) {
    }

    public record Result(FarmRecording program, List<Unsupported> unsupported) {
    }

    private static final String CONTAINER_MULTI = "container_multi";

    /**
     * @param skipUnsupported when true, a held use the proxy cannot release is replaced by "no use" for the whole press
     *                        (the owner marked it skip), and {@code header.unsupported} stays empty
     */
    public static Result compile(RawRecording raw, boolean skipUnsupported) {
        try {
            return compile0(raw, skipUnsupported);
        } catch (FarmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FarmException("bad_program");
        }
    }

    private static Result compile0(RawRecording raw, boolean skipUnsupported) {
        int n = raw.ticks().size();
        if (n < 1 || n > ProgramChecker.MAX_TICKS) {
            throw new FarmException("bad_program");
        }
        List<RawTick> ticks = new ArrayList<>(raw.ticks());
        // 1. click-timing pass on the raw states
        List<TickState> states = new ArrayList<>(n);
        for (int t = 0; t < n; t++) {
            RawTick r = ticks.get(t);
            states.add(new TickState(t, r.yaw(), r.pitch(), r.attack(), r.use(), r.offHandUse(), r.item(), r.target()));
        }
        List<Unsupported> unsupported = new ArrayList<>(ClickCompiler.compile(states).unsupported());
        if (skipUnsupported) {
            for (int[] run : ClickCompiler.unsupportedRuns(states)) {
                for (int k = run[0]; k <= run[1]; k++) {
                    RawTick r = ticks.get(k);
                    ticks.set(k, new RawTick(r.keys(), r.yaw(), r.pitch(), r.attack(), 0, false, r.hotbarSlot(), r.item(),
                            r.target(), r.feet(), r.containerActions(), r.swing()));
                }
            }
            unsupported.clear();
        }
        // 2. anchors
        List<Anchor> anchors = new ArrayList<>();
        Map<String, Anchor> anchorById = new LinkedHashMap<>();
        for (RawAnchor a : raw.anchors()) {
            AnchorMath.Split sp = AnchorMath.split(a.offset().x(), a.offset().y(), a.offset().z());
            Fp fp = a.fpBelow() == null && a.fpFeet() == null ? null : new Fp(a.fpBelow(), a.fpFeet());
            Anchor an = new Anchor(a.id(), sp.rel(), sp.stand(), noNegZero(normYaw(a.yaw())), noNegZero(a.pitch()), a.tol(), fp, a.tick());
            anchors.add(an);
            anchorById.put(a.id(), an);
        }
        if (anchors.isEmpty()) {
            throw new FarmException("bad_program");
        }
        // 3. tables in first-use order, then the track
        List<String> items = new ArrayList<>();
        List<String> entities = new ArrayList<>();
        List<String> blocks = new ArrayList<>();
        java.util.Set<Integer> anchorTicks = new java.util.HashSet<>();
        for (Anchor a : anchors) {
            anchorTicks.add(a.tick());
        }
        List<TrackRecord> track = new ArrayList<>();
        Anchor a0 = anchors.get(0);
        int pKeys = 0;
        float pYaw = (float) a0.yaw();
        float pPitch = (float) a0.pitch();
        int pAttack = 0;
        int pUse = 0; // mode | hand << 2
        Target pTarget = Target.MISS;
        int pSlot = -1;
        int pItemRef = -1;
        for (int t = 0; t < n; t++) {
            RawTick r = ticks.get(t);
            if (!Double.isFinite(r.yaw()) || !Double.isFinite(r.pitch())) {
                throw new FarmException("bad_track");
            }
            Integer keys = r.keys() != pKeys ? Integer.valueOf(r.keys()) : null;
            float yaw = noNegZero((float) normYaw(r.yaw()));
            float pitch = noNegZero((float) Math.max(-90.0, Math.min(90.0, r.pitch())));
            Look look = (yaw != pYaw || pitch != pPitch) ? new Look(yaw, pitch) : null;
            // a re-press (press on two consecutive ticks) is written again: a record that repeats "press" starts a new press,
            // so expand() reproduces the clicks ClickCompiler computes from the raw states
            Integer attack = (r.attack() != pAttack || r.attack() == 1) ? Integer.valueOf(r.attack()) : null;
            int useByte = r.use() | (r.offHandUse() ? 4 : 0);
            Use use = (useByte != pUse || r.use() == 1) ? new Use(r.use(), r.offHandUse() ? 1 : 0) : null;
            Target target = null;
            Target cur = toTarget(r.target(), entities, blocks);
            if (!cur.equals(pTarget)) {
                target = cur;
            }
            int itemRef = r.item() == null ? 0 : indexOf(items, r.item()) + 1;
            Hotbar hotbar = (r.hotbarSlot() != pSlot || itemRef != pItemRef) ? new Hotbar(r.hotbarSlot(), itemRef) : null;
            Sample sample = null;
            if (r.feet() != null && (t % 20 == 0 || anchorTicks.contains(t))) {
                sample = new Sample((float) r.feet().x(), (float) r.feet().y(), (float) r.feet().z());
            }
            ContainerAction ca = null;
            boolean extra = false;
            for (RawContainerAction a : r.containerActions()) {
                if (a.type() == RawContainerAction.Type.RAW_CLICK) {
                    continue; // raw slot clicks are dropped (A5.4)
                }
                if (ca != null) {
                    extra = true;
                    continue;
                }
                int ref = a.item() == null ? 0 : indexOf(items, a.item()) + 1;
                ca = switch (a.type()) {
                    case TAKE -> ContainerAction.take(a.container(), ref, a.count());
                    case PUT -> ContainerAction.put(a.container(), ref);
                    default -> ContainerAction.put(a.container(), 0);
                };
            }
            if (extra && !skipUnsupported) {
                unsupported.add(new Unsupported(CONTAINER_MULTI, t));
            }
            if (keys != null || look != null || attack != null || use != null || target != null || hotbar != null
                    || sample != null || ca != null || r.swing()) {
                track.add(new TrackRecord(t, keys, look, attack, use, target, hotbar, sample, ca, r.swing()));
            }
            pKeys = r.keys();
            pYaw = yaw;
            pPitch = pitch;
            pAttack = r.attack();
            pUse = useByte;
            pTarget = cur;
            pSlot = r.hotbarSlot();
            pItemRef = itemRef;
        }
        // 4. header
        Template template = new Template(raw.mode(), canonicalParams(raw.mode(), raw.params()));
        Header h = new Header(FarmRecording.FORMAT, FarmRecording.CORE_VERSION, raw.mcClient(), raw.mcProxy(), raw.mode(),
                raw.dim(), template, n, new FarmRecording.Loop(raw.loopStart(), raw.loopEnd(), raw.loopRepeat()),
                anchors, raw.marks(), raw.containers(), new Steps(raw.startSteps(), raw.stopSteps()), items, entities,
                blocks, unsupported);
        FarmRecording program = FarmRecording.of(h, track);
        return new Result(program, program.header().unsupported());
    }

    /** Key order is part of the canonical header (so the same program gives the same hash): custom is repeat, maxLoops. */
    private static Map<String, Object> canonicalParams(String mode, Map<String, Object> in) {
        if (!"custom".equals(mode)) {
            return in;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : new String[] {"repeat", "maxLoops"}) {
            if (in.containsKey(k)) {
                out.put(k, in.get(k));
            }
        }
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.putIfAbsent(e.getKey(), e.getValue()); // unknown keys stay, the checker refuses them
        }
        return out;
    }

    private static double noNegZero(double d) {
        return d == 0.0 ? 0.0 : d;
    }

    private static float noNegZero(float f) {
        return f == 0f ? 0f : f;
    }

    private static double normYaw(double yaw) {
        double y = ((yaw + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return y;
    }

    private static int indexOf(List<String> table, String id) {
        int i = table.indexOf(id);
        if (i < 0) {
            table.add(id);
            i = table.size() - 1;
        }
        return i;
    }

    private static Target toTarget(ClickTarget t, List<String> entities, List<String> blocks) {
        return switch (t.kind()) {
            case MISS -> Target.MISS;
            case ENTITY -> Target.entity(indexOf(entities, t.entity()));
            case BLOCK -> Target.block(t.dx(), t.dy(), t.dz(), t.face(), indexOf(blocks, t.block()));
        };
    }

    // ---- playback side ----

    /**
     * Expands a program's carried-forward track into one state per tick (index = tick). A "press" carried past its own
     * tick reads as "hold" on later ticks. Tick 0 starts as: no keys, a0's look, attack and use none, target miss, empty hand.
     */
    public static List<TickState> expand(FarmRecording r) {
        Header h = r.header();
        int n = h.ticks();
        List<TickState> out = new ArrayList<>(n);
        Anchor a0 = h.anchors().get(0);
        double yaw = a0.yaw();
        double pitch = a0.pitch();
        int attack = 0;
        int attackTick = -1;
        int use = 0;
        boolean off = false;
        int useTick = -1;
        ClickTarget target = ClickTarget.MISS;
        String item = null;
        int ri = 0;
        List<TrackRecord> tr = r.track();
        for (int t = 0; t < n; t++) {
            while (ri < tr.size() && tr.get(ri).tick() == t) {
                TrackRecord rec = tr.get(ri++);
                if (rec.look() != null) {
                    yaw = rec.look().yaw();
                    pitch = rec.look().pitch();
                }
                if (rec.attack() != null) {
                    attack = rec.attack();
                    attackTick = t;
                }
                if (rec.use() != null) {
                    use = rec.use().mode();
                    off = rec.use().hand() == 1;
                    useTick = t;
                }
                if (rec.target() != null) {
                    Target tg = rec.target();
                    target = switch (tg.tag()) {
                        case FarmRecording.TARGET_BLOCK -> ClickTarget.block(tg.dx(), tg.dy(), tg.dz(), tg.face(),
                                h.blocks().get(tg.index()));
                        case FarmRecording.TARGET_ENTITY -> ClickTarget.entity(h.entities().get(tg.index()));
                        default -> ClickTarget.MISS;
                    };
                }
                if (rec.hotbar() != null) {
                    item = rec.hotbar().itemRef() == 0 ? null : h.items().get(rec.hotbar().itemRef() - 1);
                }
            }
            int a = attack == 1 && attackTick < t ? 2 : attack;
            int u = use == 1 && useTick < t ? 2 : use;
            out.add(new TickState(t, yaw, pitch, a, u, off && u != 0, item, target));
        }
        return out;
    }

    /** The per-tick click requests the player submits for a program: {@code compile(expand(program))}. */
    public static ClickCompiler.Plan clicks(FarmRecording r) {
        return ClickCompiler.compile(expand(r));
    }
}
