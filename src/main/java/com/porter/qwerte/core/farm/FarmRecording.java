package com.porter.qwerte.core.farm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A farm program (contract Part A, "FarmRecording v1"): the model and its binary codec.
 *
 * <p>Layout: {@code "QFP1" | u8 format = 1 | u32 headerLength | header (UTF-8 JSON) | track}. All positions in a program
 * are offsets from the origin anchor {@code a0}; there is no absolute coordinate anywhere in the model or the format.
 *
 * <p>{@link #decode(byte[])} never returns a program that failed the header rules (see {@link ProgramChecker}); every
 * failure is a {@link FarmException} whose message is a closed code ({@code bad_program}, {@code bad_track},
 * {@code format_unsupported}, {@code too_big}). The program hash is the SHA-256 of the plain bytes <em>as received</em>:
 * hash the received bytes with {@link #sha256Hex(byte[])}, do not re-encode (unknown header keys are dropped by decode).
 */
public final class FarmRecording {
    public static final String CORE_VERSION = "1.1.0";
    public static final int FORMAT = 1;
    public static final int MAX_HEADER_BYTES = 65_536;
    public static final int MAX_PLAIN_BYTES = 8 * 1024 * 1024;

    // ---- header model ----
    public record Rel(int x, int y, int z) {
    }

    public record Vec(double x, double y, double z) {
    }

    public record Template(String mode, Map<String, Object> params) {
        public Template {
            params = deepMap(params);
        }
    }

    public record Loop(int start, int end, boolean repeat) {
    }

    /** Fingerprint block ids (under the feet, at the feet); either may be null. */
    public record Fp(String below, String feet) {
    }

    public record Anchor(String id, Rel rel, Vec stand, double yaw, double pitch, double tol, Fp fp, int tick) {
    }

    public record Mark(String kind, int tick, Integer container) {
    }

    public record Container(Rel rel, String block, String role) {
    }

    /** Steps are closed-vocabulary maps with an "op" key (A7); {@link ProgramChecker} validates every key and value. */
    public record Steps(List<Map<String, Object>> start, List<Map<String, Object>> stop) {
        public Steps {
            start = copySteps(start);
            stop = copySteps(stop);
        }

        private static List<Map<String, Object>> copySteps(List<Map<String, Object>> in) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> m : in) {
                out.add(deepMap(m));
            }
            return Collections.unmodifiableList(out);
        }
    }

    /** Deep, unmodifiable copy of a JSON-like tree (Maps keep their order); other values are kept as they are. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMap(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            out.put(e.getKey(), deepValue(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    private static Object deepValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            return deepMap((Map<String, Object>) m);
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) {
                out.add(deepValue(x));
            }
            return Collections.unmodifiableList(out);
        }
        return v;
    }

    public record Unsupported(String code, int tick) {
    }

    public record Header(int format, String core, String mcClient, String mcProxy, String mode, String dim,
                         Template template, int ticks, Loop loop, List<Anchor> anchors, List<Mark> marks,
                         List<Container> containers, Steps steps, List<String> items, List<String> entities,
                         List<String> blocks, List<Unsupported> unsupported) {
        public Header {
            anchors = List.copyOf(anchors);
            marks = List.copyOf(marks);
            containers = List.copyOf(containers);
            items = List.copyOf(items);
            entities = List.copyOf(entities);
            blocks = List.copyOf(blocks);
            unsupported = List.copyOf(unsupported);
        }
    }

    // ---- track model ----
    public static final int KEY_FORWARD = 1, KEY_BACK = 2, KEY_LEFT = 4, KEY_RIGHT = 8, KEY_JUMP = 16, KEY_SNEAK = 32,
            KEY_SPRINT = 64;
    public static final int ATTACK_NONE = 0, ATTACK_PRESS = 1, ATTACK_HOLD = 2;
    public static final int USE_NONE = 0, USE_PRESS = 1, USE_HOLD = 2;
    public static final int TARGET_MISS = 0, TARGET_BLOCK = 1, TARGET_ENTITY = 2;

    public record Look(float yaw, float pitch) {
    }

    public record Use(int mode, int hand) {
    }

    /** tag 0 miss; 1 block (dx, dy, dz relative to the origin block, face 0..5, index into blocks); 2 entity (index). */
    public record Target(int tag, int dx, int dy, int dz, int face, int index) {
        public static final Target MISS = new Target(0, 0, 0, 0, 0, 0);

        public static Target block(int dx, int dy, int dz, int face, int blockIndex) {
            return new Target(TARGET_BLOCK, dx, dy, dz, face, blockIndex);
        }

        public static Target entity(int entityIndex) {
            return new Target(TARGET_ENTITY, 0, 0, 0, 0, entityIndex);
        }
    }

    /** itemRef 0 = empty hand, n >= 1 = items[n-1]. */
    public record Hotbar(int slot, int itemRef) {
    }

    public record Sample(float x, float y, float z) {
    }

    /** tag 1 take-from (container, itemRef >= 1, count, 0 = all); tag 2 put-into (container, itemRef, 0 = all-but-kit). */
    public record ContainerAction(int tag, int container, int itemRef, int count) {
        public static ContainerAction take(int container, int itemRef, int count) {
            return new ContainerAction(1, container, itemRef, count);
        }

        public static ContainerAction put(int container, int itemRef) {
            return new ContainerAction(2, container, itemRef, 0);
        }
    }

    /** One changed-state record; null fields did not change. {@code tick} is absolute. */
    public record TrackRecord(int tick, Integer keys, Look look, Integer attack, Use use, Target target, Hotbar hotbar,
                              Sample sample, ContainerAction container, boolean swing) {
    }

    private final Header header;
    private final List<TrackRecord> track;

    private FarmRecording(Header header, List<TrackRecord> track) {
        this.header = header;
        this.track = List.copyOf(track);
    }

    /** Builds a program, running the full header and track validation; throws {@link FarmException} on a violation. */
    public static FarmRecording of(Header header, List<TrackRecord> track) {
        if (header == null || track == null) {
            throw new FarmException("bad_program");
        }
        ProgramChecker.validateHeader(header);
        ProgramChecker.validateTrack(header, track);
        return new FarmRecording(header, track);
    }

    public Header header() {
        return header;
    }

    public List<TrackRecord> track() {
        return track;
    }

    // ---- hashing ----
    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(sha256(data));
    }

    /** SHA-256 of the canonical plain encoding of this program. */
    public String programHash() {
        return sha256Hex(encode());
    }

    // ---- encode ----
    public byte[] encode() {
        try {
            return encode0();
        } catch (FarmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FarmException("bad_program");
        }
    }

    private byte[] encode0() {
        ProgramChecker.validateHeader(header);
        ProgramChecker.validateTrack(header, track);
        byte[] hb = JsonOut.write(headerTree(header)).getBytes(StandardCharsets.UTF_8);
        if (hb.length > MAX_HEADER_BYTES) {
            throw new FarmException("bad_program");
        }
        ByteArrayOutputStream o = new ByteArrayOutputStream(hb.length + 64 + track.size() * 8);
        o.writeBytes(new byte[] {'Q', 'F', 'P', '1', (byte) FORMAT});
        o.writeBytes(ByteBuffer.allocate(4).putInt(hb.length).array());
        o.writeBytes(hb);
        writeTrack(o);
        if (o.size() > MAX_PLAIN_BYTES) {
            throw new FarmException("too_big");
        }
        return o.toByteArray();
    }

    /** Params order is part of the canonical header: custom is repeat, maxLoops; any other mode is sorted by key. */
    private static Map<String, Object> canonicalParams(String mode, Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if ("custom".equals(mode)) {
            for (String k : new String[] {"repeat", "maxLoops"}) {
                if (in.containsKey(k)) {
                    out.put(k, in.get(k));
                }
            }
        }
        new java.util.TreeMap<>(in).forEach(out::putIfAbsent);
        return out;
    }

    private static final Map<String, List<String>> STEP_ORDER = Map.of(
            "walk-to-anchor", List.of("anchor"), "look", List.of("yaw", "pitch"), "use-block", List.of("rel", "face"),
            "attack", List.of("entity"), "hold-use", List.of("ticks"), "select", List.of("item"),
            "take-from", List.of("container", "item", "count"), "put-into", List.of("container", "item"),
            "wait", List.of("ticks"), "wait-until", List.of("what", "item", "count", "ticks", "percent"));

    /** Step keys in a fixed order: op first, then the per-op order, then any other key sorted (the checker refuses those). */
    private static List<Object> orderedSteps(List<Map<String, Object>> steps) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> s : steps) {
            Map<String, Object> o = new LinkedHashMap<>();
            if (s.containsKey("op")) {
                o.put("op", s.get("op"));
            }
            for (String k : STEP_ORDER.getOrDefault(String.valueOf(s.get("op")), List.of())) {
                if (s.containsKey(k)) {
                    o.put(k, s.get(k));
                }
            }
            new java.util.TreeMap<>(s).forEach(o::putIfAbsent);
            out.add(o);
        }
        return out;
    }

    private static List<Object> ints(int a, int b, int c) {
        return List.of(a, b, c);
    }

    private static Map<String, Object> headerTree(Header h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", h.format());
        m.put("core", h.core());
        m.put("mcClient", h.mcClient());
        m.put("mcProxy", h.mcProxy());
        m.put("mode", h.mode());
        m.put("dim", h.dim());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("mode", h.template().mode());
        t.put("params", canonicalParams(h.template().mode(), h.template().params()));
        m.put("template", t);
        m.put("ticks", h.ticks());
        Map<String, Object> lp = new LinkedHashMap<>();
        lp.put("start", h.loop().start());
        lp.put("end", h.loop().end());
        lp.put("repeat", h.loop().repeat());
        m.put("loop", lp);
        List<Object> as = new ArrayList<>();
        for (Anchor a : h.anchors()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("id", a.id());
            am.put("rel", ints(a.rel().x(), a.rel().y(), a.rel().z()));
            am.put("stand", List.of(a.stand().x(), a.stand().y(), a.stand().z()));
            am.put("yaw", a.yaw());
            am.put("pitch", a.pitch());
            am.put("tol", a.tol());
            if (a.fp() != null) {
                Map<String, Object> fp = new LinkedHashMap<>();
                if (a.fp().below() != null) {
                    fp.put("below", a.fp().below());
                }
                if (a.fp().feet() != null) {
                    fp.put("feet", a.fp().feet());
                }
                am.put("fp", fp);
            }
            am.put("tick", a.tick());
            as.add(am);
        }
        m.put("anchors", as);
        List<Object> ms = new ArrayList<>();
        for (Mark k : h.marks()) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("kind", k.kind());
            mm.put("tick", k.tick());
            if (k.container() != null) {
                mm.put("container", k.container());
            }
            ms.add(mm);
        }
        m.put("marks", ms);
        List<Object> cs = new ArrayList<>();
        for (Container c : h.containers()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("rel", ints(c.rel().x(), c.rel().y(), c.rel().z()));
            cm.put("block", c.block());
            cm.put("role", c.role());
            cs.add(cm);
        }
        m.put("containers", cs);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("start", orderedSteps(h.steps().start()));
        st.put("stop", orderedSteps(h.steps().stop()));
        m.put("steps", st);
        m.put("items", h.items());
        m.put("entities", h.entities());
        m.put("blocks", h.blocks());
        List<Object> us = new ArrayList<>();
        for (Unsupported u : h.unsupported()) {
            Map<String, Object> um = new LinkedHashMap<>();
            um.put("code", u.code());
            um.put("tick", u.tick());
            us.add(um);
        }
        m.put("unsupported", us);
        return m;
    }

    private static void varInt(ByteArrayOutputStream o, int v) {
        int x = v;
        while ((x & ~0x7F) != 0) {
            o.write((x & 0x7F) | 0x80);
            x >>>= 7;
        }
        o.write(x);
    }

    private static void u16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void f32(ByteArrayOutputStream o, float f) {
        o.writeBytes(ByteBuffer.allocate(4).putFloat(f).array());
    }

    private void writeTrack(ByteArrayOutputStream o) {
        int prev = 0;
        for (TrackRecord r : track) {
            varInt(o, r.tick() - prev);
            prev = r.tick();
            int flags = 0;
            flags |= r.keys() != null ? 1 : 0;
            flags |= r.look() != null ? 1 << 1 : 0;
            flags |= r.attack() != null ? 1 << 2 : 0;
            flags |= r.use() != null ? 1 << 3 : 0;
            flags |= r.target() != null ? 1 << 4 : 0;
            flags |= r.hotbar() != null ? 1 << 5 : 0;
            flags |= r.sample() != null ? 1 << 6 : 0;
            flags |= r.container() != null ? 1 << 7 : 0;
            flags |= r.swing() ? 1 << 8 : 0;
            u16(o, flags);
            if (r.keys() != null) {
                o.write(r.keys());
            }
            if (r.look() != null) {
                f32(o, r.look().yaw());
                f32(o, r.look().pitch());
            }
            if (r.attack() != null) {
                o.write(r.attack());
            }
            if (r.use() != null) {
                o.write(r.use().mode() | (r.use().hand() << 2));
            }
            if (r.target() != null) {
                Target t = r.target();
                o.write(t.tag());
                if (t.tag() == TARGET_BLOCK) {
                    u16(o, t.dx() & 0xFFFF);
                    u16(o, t.dy() & 0xFFFF);
                    u16(o, t.dz() & 0xFFFF);
                    o.write(t.face());
                    varInt(o, t.index());
                } else if (t.tag() == TARGET_ENTITY) {
                    varInt(o, t.index());
                }
            }
            if (r.hotbar() != null) {
                o.write(r.hotbar().slot());
                varInt(o, r.hotbar().itemRef());
            }
            if (r.sample() != null) {
                f32(o, r.sample().x());
                f32(o, r.sample().y());
                f32(o, r.sample().z());
            }
            if (r.container() != null) {
                ContainerAction c = r.container();
                o.write(c.tag());
                o.write(c.container());
                varInt(o, c.itemRef());
                if (c.tag() == 1) {
                    varInt(o, c.count());
                }
            }
        }
    }

    // ---- decode ----
    public static FarmRecording decode(byte[] plain) {
        try {
            return decode0(plain);
        } catch (FarmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new FarmException("bad_program"); // nothing else may escape, and nothing of the input is echoed
        }
    }

    private static FarmRecording decode0(byte[] plain) {
        if (plain == null) {
            throw new FarmException("bad_program");
        }
        if (plain.length > MAX_PLAIN_BYTES) {
            throw new FarmException("too_big");
        }
        if (plain.length < 5 || plain[0] != 'Q' || plain[1] != 'F' || plain[2] != 'P' || plain[3] != '1') {
            throw new FarmException("bad_program");
        }
        if (plain[4] != FORMAT) {
            throw new FarmException("format_unsupported");
        }
        if (plain.length < 9) {
            throw new FarmException("bad_program");
        }
        long hl = ((plain[5] & 0xFFL) << 24) | ((plain[6] & 0xFFL) << 16) | ((plain[7] & 0xFFL) << 8) | (plain[8] & 0xFFL);
        if (hl > MAX_HEADER_BYTES || hl > plain.length - 9L) {
            throw new FarmException("bad_program");
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plain, 9, (int) hl)).toString();
        } catch (CharacterCodingException e) {
            throw new FarmException("bad_program");
        }
        Header h = readHeader(asObj(BoundedJson.parse(json)));
        ProgramChecker.validateHeader(h);
        List<TrackRecord> tr = readTrack(plain, 9 + (int) hl, h);
        return new FarmRecording(h, tr);
    }

    // header typed readers: types only; ranges are ProgramChecker's job
    private static FarmException bp() {
        return new FarmException("bad_program");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObj(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw bp();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArr(Object o) {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw bp();
    }

    private static String asStr(Object o) {
        if (o instanceof String s) {
            return s;
        }
        throw bp();
    }

    private static int asInt(Object o) {
        if (o instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
            return (int) (long) l;
        }
        throw bp();
    }

    private static double asNum(Object o) {
        if (o instanceof Long l) {
            return l;
        }
        if (o instanceof Double d) {
            return d;
        }
        throw bp();
    }

    private static boolean asBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        throw bp();
    }

    private static Rel asRel(Object o) {
        List<Object> l = asArr(o);
        if (l.size() != 3) {
            throw bp();
        }
        return new Rel(asInt(l.get(0)), asInt(l.get(1)), asInt(l.get(2)));
    }

    private static Vec asVec(Object o) {
        List<Object> l = asArr(o);
        if (l.size() != 3) {
            throw bp();
        }
        return new Vec(asNum(l.get(0)), asNum(l.get(1)), asNum(l.get(2)));
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : asArr(o)) {
            out.add(asStr(x));
        }
        return out;
    }

    private static Header readHeader(Map<String, Object> m) {
        Object fmt = m.get("format");
        if (fmt == null || m.get("ticks") == null || m.get("loop") == null || m.get("anchors") == null) {
            throw bp();
        }
        if (asInt(fmt) != FORMAT) {
            throw new FarmException("format_unsupported");
        }
        String mode = m.containsKey("mode") ? asStr(m.get("mode")) : "";
        Template template;
        if (m.containsKey("template")) {
            Map<String, Object> t = asObj(m.get("template"));
            template = new Template(t.containsKey("mode") ? asStr(t.get("mode")) : mode,
                    t.containsKey("params") ? asObj(t.get("params")) : Map.of());
        } else {
            template = new Template(mode, Map.of());
        }
        Map<String, Object> lp = asObj(m.get("loop"));
        if (!lp.containsKey("start") || !lp.containsKey("end")) {
            throw bp();
        }
        Loop loop = new Loop(asInt(lp.get("start")), asInt(lp.get("end")),
                !lp.containsKey("repeat") || asBool(lp.get("repeat")));
        List<Anchor> anchors = new ArrayList<>();
        for (Object ao : asArr(m.get("anchors"))) {
            Map<String, Object> a = asObj(ao);
            if (!a.containsKey("id") || !a.containsKey("rel") || !a.containsKey("stand")) {
                throw bp();
            }
            Fp fp = null;
            if (a.containsKey("fp")) {
                Map<String, Object> f = asObj(a.get("fp"));
                fp = new Fp(f.containsKey("below") ? asStr(f.get("below")) : null,
                        f.containsKey("feet") ? asStr(f.get("feet")) : null);
            }
            anchors.add(new Anchor(asStr(a.get("id")), asRel(a.get("rel")), asVec(a.get("stand")),
                    a.containsKey("yaw") ? asNum(a.get("yaw")) : 0.0, a.containsKey("pitch") ? asNum(a.get("pitch")) : 0.0,
                    a.containsKey("tol") ? asNum(a.get("tol")) : 0.3, fp, a.containsKey("tick") ? asInt(a.get("tick")) : 0));
        }
        List<Mark> marks = new ArrayList<>();
        if (m.containsKey("marks")) {
            for (Object mo : asArr(m.get("marks"))) {
                Map<String, Object> k = asObj(mo);
                if (!k.containsKey("kind") || !k.containsKey("tick")) {
                    throw bp();
                }
                marks.add(new Mark(asStr(k.get("kind")), asInt(k.get("tick")),
                        k.containsKey("container") ? Integer.valueOf(asInt(k.get("container"))) : null));
            }
        }
        List<Container> containers = new ArrayList<>();
        if (m.containsKey("containers")) {
            for (Object co : asArr(m.get("containers"))) {
                Map<String, Object> c = asObj(co);
                if (!c.containsKey("rel") || !c.containsKey("block") || !c.containsKey("role")) {
                    throw bp();
                }
                containers.add(new Container(asRel(c.get("rel")), asStr(c.get("block")), asStr(c.get("role"))));
            }
        }
        List<Map<String, Object>> start = new ArrayList<>();
        List<Map<String, Object>> stop = new ArrayList<>();
        if (m.containsKey("steps")) {
            Map<String, Object> s = asObj(m.get("steps"));
            if (s.containsKey("start")) {
                for (Object so : asArr(s.get("start"))) {
                    start.add(asObj(so));
                }
            }
            if (s.containsKey("stop")) {
                for (Object so : asArr(s.get("stop"))) {
                    stop.add(asObj(so));
                }
            }
        }
        List<Unsupported> unsupported = new ArrayList<>();
        if (m.containsKey("unsupported")) {
            for (Object uo : asArr(m.get("unsupported"))) {
                Map<String, Object> u = asObj(uo);
                if (!u.containsKey("code") || !u.containsKey("tick")) {
                    throw bp();
                }
                unsupported.add(new Unsupported(asStr(u.get("code")), asInt(u.get("tick"))));
            }
        }
        return new Header(FORMAT, m.containsKey("core") ? asStr(m.get("core")) : "",
                m.containsKey("mcClient") ? asStr(m.get("mcClient")) : "",
                m.containsKey("mcProxy") ? asStr(m.get("mcProxy")) : "", mode,
                m.containsKey("dim") ? asStr(m.get("dim")) : "", template, asInt(m.get("ticks")), loop, anchors, marks,
                containers, new Steps(start, stop), m.containsKey("items") ? strList(m.get("items")) : List.of(),
                m.containsKey("entities") ? strList(m.get("entities")) : List.of(),
                m.containsKey("blocks") ? strList(m.get("blocks")) : List.of(), unsupported);
    }

    private static final class Rd {
        final byte[] b;
        int p;

        Rd(byte[] b, int p) {
            this.b = b;
            this.p = p;
        }

        boolean more() {
            return p < b.length;
        }

        int u8() {
            if (p >= b.length) {
                throw new FarmException("bad_track");
            }
            return b[p++] & 0xFF;
        }

        int u16() {
            return (u8() << 8) | u8();
        }

        int i16() {
            return (short) u16();
        }

        float f32() {
            if (p + 4 > b.length) {
                throw new FarmException("bad_track");
            }
            float f = ByteBuffer.wrap(b, p, 4).getFloat();
            p += 4;
            return f;
        }

        /** Minecraft VarInt, at most 5 bytes; a value beyond the int range is reported as bad_track. */
        int varInt() {
            long v = 0;
            for (int i = 0; i < 5; i++) {
                int x = u8();
                v |= (long) (x & 0x7F) << (7 * i);
                if ((x & 0x80) == 0) {
                    if (v > Integer.MAX_VALUE) {
                        throw new FarmException("bad_track");
                    }
                    return (int) v;
                }
            }
            throw new FarmException("bad_track");
        }
    }

    private static List<TrackRecord> readTrack(byte[] b, int from, Header h) {
        Rd r = new Rd(b, from);
        List<TrackRecord> out = new ArrayList<>();
        long tick = 0;
        boolean first = true;
        while (r.more()) {
            int delta = r.varInt();
            if (!first && delta < 1) {
                throw new FarmException("bad_track");
            }
            tick += delta;
            if (tick >= h.ticks()) {
                throw new FarmException("bad_track");
            }
            first = false;
            int flags = r.u16();
            if ((flags & 0xFE00) != 0) {
                throw new FarmException("bad_track");
            }
            Integer keys = null;
            Look look = null;
            Integer attack = null;
            Use use = null;
            Target target = null;
            Hotbar hotbar = null;
            Sample sample = null;
            ContainerAction container = null;
            if ((flags & 1) != 0) {
                keys = r.u8();
            }
            if ((flags & (1 << 1)) != 0) {
                look = new Look(r.f32(), r.f32());
            }
            if ((flags & (1 << 2)) != 0) {
                attack = r.u8();
            }
            if ((flags & (1 << 3)) != 0) {
                int u = r.u8();
                if ((u & 0xF8) != 0) {
                    throw new FarmException("bad_track");
                }
                use = new Use(u & 3, (u >> 2) & 1);
            }
            if ((flags & (1 << 4)) != 0) {
                int tag = r.u8();
                if (tag == TARGET_MISS) {
                    target = Target.MISS;
                } else if (tag == TARGET_BLOCK) {
                    int dx = r.i16();
                    int dy = r.i16();
                    int dz = r.i16();
                    int face = r.u8();
                    target = new Target(TARGET_BLOCK, dx, dy, dz, face, r.varInt());
                } else if (tag == TARGET_ENTITY) {
                    target = Target.entity(r.varInt());
                } else {
                    throw new FarmException("bad_track");
                }
            }
            if ((flags & (1 << 5)) != 0) {
                int slot = r.u8();
                hotbar = new Hotbar(slot, r.varInt());
            }
            if ((flags & (1 << 6)) != 0) {
                sample = new Sample(r.f32(), r.f32(), r.f32());
            }
            if ((flags & (1 << 7)) != 0) {
                int tag = r.u8();
                int c = r.u8();
                if (tag == 1) {
                    int item = r.varInt();
                    container = new ContainerAction(1, c, item, r.varInt());
                } else if (tag == 2) {
                    container = new ContainerAction(2, c, r.varInt(), 0);
                } else {
                    throw new FarmException("bad_track");
                }
            }
            boolean swing = (flags & (1 << 8)) != 0;
            TrackRecord rec = new TrackRecord((int) tick, keys, look, attack, use, target, hotbar, sample, container, swing);
            ProgramChecker.validateRecord(h, rec);
            out.add(rec);
        }
        return out;
    }
}
