package com.porter.qwerte.core.farm;

import com.porter.qwerte.core.farm.FarmRecording.Anchor;
import com.porter.qwerte.core.farm.FarmRecording.Container;
import com.porter.qwerte.core.farm.FarmRecording.Header;
import com.porter.qwerte.core.farm.FarmRecording.Mark;
import com.porter.qwerte.core.farm.FarmRecording.Rel;
import com.porter.qwerte.core.farm.FarmRecording.Target;
import com.porter.qwerte.core.farm.FarmRecording.TrackRecord;
import com.porter.qwerte.core.farm.FarmRecording.Unsupported;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The program checker (contract A2, A3, A7, A8). Closed vocabulary, every size limit, and the two safety rules: no
 * {@code minecraft:player} in {@code entities} (or an {@code attack} step), and every container within 6 blocks of an
 * anchor stand point. It runs in the addon (to show errors at once) and again in the plugin (an upload is never trusted).
 *
 * <p>Failure codes: {@code bad_program} for the header, {@code bad_track} for the track, {@code unsupported_actions} when
 * the program still lists unsupported entries ({@link #check}); {@code format_unsupported} comes from the decoder.
 */
public final class ProgramChecker {
    public static final Set<String> MODES = Set.of("afk_still", "custom", "afk_swing", "afk_use", "hold_use", "fishing",
            "raid", "gold_iron", "wither_shulker", "trading", "crop_replant");
    public static final Set<String> MARK_KINDS = Set.of("anchor", "loop_start", "loop_end", "collect", "supply",
            "start_action", "stop_action");
    public static final Pattern ID = Pattern.compile("^[a-z0-9_.-]+:[a-z0-9_./-]+$");
    private static final Pattern ANCHOR_ID = Pattern.compile("^a(0|[1-9][0-9]?)$");
    private static final Pattern UNSUPPORTED_CODE = Pattern.compile("^[a-z_]{1,32}$");

    public static final int MAX_TICKS = 72_000;
    public static final int MAX_LOOP = 24_000;
    public static final int MAX_ANCHORS = 64;
    public static final int MAX_MARKS = 256;
    public static final int MAX_CONTAINERS = 32;
    public static final int MAX_STEPS = 128;
    public static final int MAX_ITEMS = 256;
    public static final int MAX_ENTITIES = 32;
    public static final int MAX_BLOCKS = 64;
    public static final int MAX_UNSUPPORTED = 256;
    public static final int REL_XZ = 512;
    public static final int REL_Y = 384;
    public static final double CONTAINER_REACH = 6.0;
    public static final String PLAYER = "minecraft:player";

    private ProgramChecker() {
    }

    private static FarmException bp() {
        return new FarmException("bad_program");
    }

    private static FarmException bt() {
        return new FarmException("bad_track");
    }

    /** Header, track and "no unsupported entries". Throws {@link FarmException}. */
    public static void check(FarmRecording r) {
        try {
            validateHeader0(r.header());
            validateTrack0(r.header(), r.track());
            if (!r.header().unsupported().isEmpty()) {
                throw new FarmException("unsupported_actions");
            }
        } catch (FarmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FarmException("bad_program"); // null or malformed model: still only a closed code
        }
    }

    /** Decodes and checks plain program bytes. Throws {@link FarmException}. */
    public static FarmRecording checkPlain(byte[] plain) {
        FarmRecording r = FarmRecording.decode(plain);
        check(r);
        return r;
    }

    /** Same as {@link #checkPlain} but returns the closed code, or null when the program is clean. */
    public static String verify(byte[] plain) {
        try {
            checkPlain(plain);
            return null;
        } catch (FarmException e) {
            return e.code();
        } catch (RuntimeException e) {
            return "bad_program";
        }
    }

    static boolean idOk(String s) {
        return s != null && s.length() <= 64 && ID.matcher(s).matches();
    }

    static boolean relOk(Rel r) {
        return Math.abs((long) r.x()) <= REL_XZ && Math.abs((long) r.z()) <= REL_XZ && Math.abs((long) r.y()) <= REL_Y;
    }

    private static boolean finite(double d) {
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static boolean threeDecimals(double v) {
        double k = v * 1000.0;
        return Math.abs(k - Math.rint(k)) < 1e-6;
    }

    public static void validateHeader(Header h) {
        try {
            validateHeader0(h);
        } catch (FarmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw bp();
        }
    }

    private static boolean loneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    private static void validateHeader0(Header h) {
        if (h.format() != FarmRecording.FORMAT) {
            throw new FarmException("format_unsupported");
        }
        for (String s : new String[] {h.core(), h.mcClient(), h.mcProxy()}) {
            if (s == null || s.length() > 32 || loneSurrogate(s)) {
                throw bp();
            }
            for (int i = 0; i < s.length(); i++) {
                if (Character.isISOControl(s.charAt(i))) {
                    throw bp();
                }
            }
        }
        if (!MODES.contains(h.mode()) || !h.mode().equals(h.template().mode())) {
            throw bp();
        }
        if (!idOk(h.dim())) {
            throw bp();
        }
        if (h.ticks() < 1 || h.ticks() > MAX_TICKS) {
            throw bp();
        }
        FarmRecording.Loop lp = h.loop();
        if (lp.start() < 0 || lp.start() >= lp.end() || lp.end() > h.ticks() || lp.end() - lp.start() > MAX_LOOP) {
            throw bp();
        }
        if (h.items().size() > MAX_ITEMS || h.entities().size() > MAX_ENTITIES || h.blocks().size() > MAX_BLOCKS
                || h.marks().size() > MAX_MARKS || h.containers().size() > MAX_CONTAINERS
                || h.anchors().size() > MAX_ANCHORS || h.anchors().isEmpty()
                || h.unsupported().size() > MAX_UNSUPPORTED || h.steps().start().size() > MAX_STEPS
                || h.steps().stop().size() > MAX_STEPS) {
            throw bp();
        }
        for (String s : h.items()) {
            if (!idOk(s)) {
                throw bp();
            }
        }
        for (String s : h.entities()) {
            if (!idOk(s) || PLAYER.equals(s)) {
                throw bp();
            }
        }
        for (String s : h.blocks()) {
            if (!idOk(s)) {
                throw bp();
            }
        }
        // anchors
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < h.anchors().size(); i++) {
            Anchor a = h.anchors().get(i);
            if (a.id() == null || !ANCHOR_ID.matcher(a.id()).matches() || Integer.parseInt(a.id().substring(1)) > 63
                    || !ids.add(a.id())) {
                throw bp();
            }
            if (i == 0 && (!a.id().equals("a0") || a.rel().x() != 0 || a.rel().y() != 0 || a.rel().z() != 0)) {
                throw bp();
            }
            if (!relOk(a.rel())) {
                throw bp();
            }
            double[] st = {a.stand().x(), a.stand().y(), a.stand().z()};
            for (double v : st) {
                if (!finite(v) || v < 0.0 || v >= 1.0 || !threeDecimals(v)) {
                    throw bp();
                }
            }
            if (!finite(a.yaw()) || !finite(a.pitch()) || a.pitch() < -90.0 || a.pitch() > 90.0) {
                throw bp();
            }
            if (!finite(a.tol()) || a.tol() < 0.05 || a.tol() > 2.0) {
                throw bp();
            }
            if (a.fp() != null && ((a.fp().below() != null && !idOk(a.fp().below()))
                    || (a.fp().feet() != null && !idOk(a.fp().feet())))) {
                throw bp();
            }
            if (a.tick() < 0 || a.tick() >= h.ticks()) {
                throw bp();
            }
        }
        // template params
        validateParams(h, ids);
        // containers
        for (Container c : h.containers()) {
            if (!relOk(c.rel()) || !idOk(c.block()) || !("collect".equals(c.role()) || "supply".equals(c.role()))) {
                throw bp();
            }
            boolean near = false;
            for (Anchor a : h.anchors()) {
                double dx = c.rel().x() + 0.5 - (a.rel().x() + a.stand().x());
                double dy = c.rel().y() + 0.5 - (a.rel().y() + a.stand().y());
                double dz = c.rel().z() + 0.5 - (a.rel().z() + a.stand().z());
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= CONTAINER_REACH) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                throw bp();
            }
        }
        // marks
        for (Mark k : h.marks()) {
            if (!MARK_KINDS.contains(k.kind()) || k.tick() < 0 || k.tick() > h.ticks()) {
                throw bp();
            }
            boolean needs = "collect".equals(k.kind()) || "supply".equals(k.kind());
            if (needs && (k.container() == null || k.container() < 0 || k.container() >= h.containers().size())) {
                throw bp();
            }
            if (!needs && k.container() != null) {
                throw bp();
            }
        }
        for (Unsupported u : h.unsupported()) {
            if (u.code() == null || !UNSUPPORTED_CODE.matcher(u.code()).matches() || u.tick() < 0 || u.tick() >= h.ticks()) {
                throw bp();
            }
        }
        for (Map<String, Object> s : h.steps().start()) {
            validateStep(h, ids, s);
        }
        for (Map<String, Object> s : h.steps().stop()) {
            validateStep(h, ids, s);
        }
    }

    private static void validateParams(Header h, Set<String> anchorIds) {
        Map<String, Object> p = h.template().params();
        guardParams(p, 0, h.mode().equals("afk_still"));
        if (h.mode().equals("custom")) {
            for (Map.Entry<String, Object> e : p.entrySet()) {
                switch (e.getKey()) {
                    case "repeat" -> {
                        if (!(e.getValue() instanceof Boolean)) {
                            throw bp();
                        }
                    }
                    case "maxLoops" -> {
                        if (!(e.getValue() instanceof Long l) || l < 0 || l > 1_000_000) {
                            throw bp();
                        }
                    }
                    default -> throw bp();
                }
            }
        } else if (h.mode().equals("afk_still")) {
            for (String k : p.keySet()) {
                if (!k.equals("anchor")) {
                    throw bp();
                }
            }
            if (!(p.get("anchor") instanceof String a) || !anchorIds.contains(a)) {
                throw bp();
            }
        } else if (!p.isEmpty()) {
            // M3 modes have no per-mode schema yet: fail closed until their adapters define one
            throw bp();
        }
    }

    /**
     * Recursive guard over every params value: no {@code minecraft:player} anywhere and no key from
     * {@link PositionGuard#FORBIDDEN_KEYS} (the top-level {@code anchor} of afk_still is the one legitimate use of such a key
     * and is allowed only there).
     */
    private static void guardParams(Object o, int depth, boolean anchorKeyAllowed) {
        if (depth > BoundedJson.MAX_DEPTH) {
            throw bp();
        }
        if (o instanceof String s) {
            if (PLAYER.equals(s)) {
                throw bp();
            }
        } else if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    throw bp();
                }
                boolean allowed = depth == 0 && anchorKeyAllowed && k.equals("anchor");
                if (!allowed && PositionGuard.FORBIDDEN_KEYS.contains(k.toLowerCase(java.util.Locale.ROOT))) {
                    throw bp();
                }
                guardParams(e.getValue(), depth + 1, anchorKeyAllowed);
            }
        } else if (o instanceof List<?> l) {
            for (Object x : l) {
                guardParams(x, depth + 1, anchorKeyAllowed);
            }
        }
    }

    private static final Set<String> WAIT_UNTIL = Set.of("item_count", "inventory_full", "durability", "time");

    private static boolean isInt(Object o, long lo, long hi) {
        return o instanceof Long l && l >= lo && l <= hi;
    }

    private static boolean isNum(Object o) {
        return (o instanceof Long) || (o instanceof Double d && finite(d));
    }

    private static void keysIn(Map<String, Object> s, String... allowed) {
        Set<String> ok = Set.of(allowed);
        for (String k : s.keySet()) {
            if (!ok.contains(k)) {
                throw bp();
            }
        }
    }

    private static void validateStep(Header h, Set<String> anchorIds, Map<String, Object> s) {
        if (!(s.get("op") instanceof String op)) {
            throw bp();
        }
        int nContainers = h.containers().size();
        switch (op) {
            case "walk-to-anchor" -> {
                keysIn(s, "op", "anchor");
                if (!(s.get("anchor") instanceof String a) || !anchorIds.contains(a)) {
                    throw bp();
                }
            }
            case "look" -> {
                keysIn(s, "op", "yaw", "pitch");
                if (!isNum(s.get("yaw")) || !isNum(s.get("pitch")) || Math.abs(((Number) s.get("pitch")).doubleValue()) > 90) {
                    throw bp();
                }
            }
            case "use-block" -> {
                keysIn(s, "op", "rel", "face");
                if (!(s.get("rel") instanceof List<?> l) || l.size() != 3 || !isInt(l.get(0), -REL_XZ, REL_XZ)
                        || !isInt(l.get(1), -REL_Y, REL_Y) || !isInt(l.get(2), -REL_XZ, REL_XZ)
                        || !isInt(s.get("face"), 0, 5)) {
                    throw bp();
                }
            }
            case "attack" -> {
                keysIn(s, "op", "entity");
                if (!(s.get("entity") instanceof String e) || !idOk(e) || PLAYER.equals(e)) {
                    throw bp();
                }
            }
            // hold-use is accepted as written: the proxy never releases a use (A5.3), so the plugin bounds it by its ticks;
            // the unsupported-release rule applies to recorded holds, not to this step
            case "hold-use", "wait" -> {
                keysIn(s, "op", "ticks");
                if (!isInt(s.get("ticks"), 1, MAX_TICKS)) {
                    throw bp();
                }
            }
            case "select" -> {
                keysIn(s, "op", "item");
                if (!(s.get("item") instanceof String i) || !idOk(i)) {
                    throw bp();
                }
            }
            case "take-from" -> {
                keysIn(s, "op", "container", "item", "count");
                if (!isInt(s.get("container"), 0, nContainers - 1L) || !(s.get("item") instanceof String i) || !idOk(i)
                        || !isInt(s.get("count"), 0, 1_000_000)) {
                    throw bp();
                }
            }
            case "put-into" -> {
                keysIn(s, "op", "container", "item");
                if (!isInt(s.get("container"), 0, nContainers - 1L) || !(s.get("item") instanceof String i)
                        || !(idOk(i) || i.equals("all-but-kit"))) {
                    throw bp();
                }
            }
            case "wait-until" -> {
                keysIn(s, "op", "what", "item", "count", "ticks", "percent");
                if (!(s.get("what") instanceof String w) || !WAIT_UNTIL.contains(w)) {
                    throw bp();
                }
                if (s.containsKey("item") && !(s.get("item") instanceof String i && idOk(i))) {
                    throw bp();
                }
                if (s.containsKey("count") && !isInt(s.get("count"), 0, 1_000_000)) {
                    throw bp();
                }
                if (s.containsKey("ticks") && !isInt(s.get("ticks"), 1, MAX_TICKS)) {
                    throw bp();
                }
                if (s.containsKey("percent") && !isInt(s.get("percent"), 0, 100)) {
                    throw bp();
                }
                // each wait needs its own fields: no step may be a silent no-op
                boolean complete = switch (w) {
                    case "item_count" -> s.containsKey("item") && s.containsKey("count");
                    case "durability" -> s.containsKey("percent");
                    case "time" -> s.containsKey("ticks");
                    default -> true; // inventory_full
                };
                if (!complete) {
                    throw bp();
                }
            }
            default -> throw bp();
        }
    }

    public static void validateTrack(Header h, List<TrackRecord> track) {
        try {
            validateTrack0(h, track);
        } catch (FarmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw bt();
        }
    }

    private static boolean sampleOk(float f, int limit) {
        return !Float.isNaN(f) && !Float.isInfinite(f) && Math.abs(f) <= limit;
    }

    private static void validateTrack0(Header h, List<TrackRecord> track) {
        int prev = -1;
        for (TrackRecord r : track) {
            if (prev >= 0 && r.tick() <= prev) {
                throw bt();
            }
            validateRecord(h, r);
            prev = r.tick();
        }
    }

    /** Value rules for one record (A3). Everything out of range is {@code bad_track}. */
    static void validateRecord(Header h, TrackRecord r) {
        if (r.tick() < 0 || r.tick() >= h.ticks()) {
            throw bt();
        }
        if (r.keys() != null && (r.keys() < 0 || (r.keys() & 0x80) != 0 || r.keys() > 0xFF)) {
            throw bt();
        }
        if (r.look() != null) {
            float y = r.look().yaw();
            float p = r.look().pitch();
            if (Float.isNaN(y) || Float.isInfinite(y) || Float.isNaN(p) || Float.isInfinite(p) || p < -90f || p > 90f
                    || y < -360f || y > 360f) {
                throw bt();
            }
        }
        if (r.attack() != null && (r.attack() < 0 || r.attack() > 2)) {
            throw bt();
        }
        if (r.use() != null && (r.use().mode() < 0 || r.use().mode() > 2 || r.use().hand() < 0 || r.use().hand() > 1)) {
            throw bt();
        }
        Target t = r.target();
        if (t != null) {
            switch (t.tag()) {
                case FarmRecording.TARGET_MISS -> {
                }
                case FarmRecording.TARGET_BLOCK -> {
                    if (t.face() < 0 || t.face() > 5 || t.index() < 0 || t.index() >= h.blocks().size()
                            || !relOk(new Rel(t.dx(), t.dy(), t.dz()))) {
                        throw bt();
                    }
                }
                case FarmRecording.TARGET_ENTITY -> {
                    if (t.index() < 0 || t.index() >= h.entities().size()) {
                        throw bt();
                    }
                }
                default -> throw bt();
            }
        }
        if (r.hotbar() != null && (r.hotbar().slot() < 0 || r.hotbar().slot() > 8 || r.hotbar().itemRef() < 0
                || r.hotbar().itemRef() > h.items().size())) {
            throw bt();
        }
        if (r.sample() != null) {
            // offsets from the origin block corner: bounded a little beyond the relative range
            if (!sampleOk(r.sample().x(), REL_XZ + 2) || !sampleOk(r.sample().y(), REL_Y + 2)
                    || !sampleOk(r.sample().z(), REL_XZ + 2)) {
                throw bt();
            }
        }
        FarmRecording.ContainerAction c = r.container();
        if (c != null) {
            if (c.container() < 0 || c.container() >= h.containers().size() || c.itemRef() < 0
                    || c.itemRef() > h.items().size()) {
                throw bt();
            }
            if (c.tag() == 1) {
                if (c.itemRef() < 1 || c.count() < 0 || c.count() > 1_000_000) {
                    throw bt();
                }
            } else if (c.tag() == 2) {
                if (c.count() != 0) {
                    throw bt();
                }
            } else {
                throw bt();
            }
        }
    }
}
