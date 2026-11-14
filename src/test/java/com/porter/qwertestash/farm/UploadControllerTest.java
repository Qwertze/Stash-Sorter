package com.porter.qwertestash.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aquarius.feature.bridge.BridgeProtocol;
import com.porter.qwerte.core.farm.FarmRecording;
import com.porter.qwerte.core.farm.Packing;
import com.porter.qwertestash.farm.UploadController.FarmInfo;
import com.porter.qwertestash.farm.UploadController.Own;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** The upload handlers (FARM Part C, C6): the state machine, every hostile input, the switch, the rate limit, the origin cross-check. */
class UploadControllerTest {
    static final String UID = "0123456789abcdef";
    static final String FARM = "f-1a2b3c4d";

    final List<String> log = new ArrayList<>();
    final Set<String> known = new HashSet<>();
    final AcceptSwitch accept = new AcceptSwitch();
    long now = 5_000_000L;
    boolean farmerOn = true;
    boolean sessionOpen;
    String sessionFarm;
    Own own = new Own("minecraft:overworld", 100, 64, 200);
    Set<String> owners = new HashSet<>();
    int storedCalls;
    FarmStore store;
    UploadController ctl;

    final class P implements UploadController.Peer {
        final String id;
        final String name;
        boolean spectator;
        final List<byte[]> sent = new ArrayList<>();

        P(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public boolean spectator() {
            return spectator;
        }

        public void send(byte[] p) {
            sent.add(p);
        }

        Msg last() {
            return Msg.of(sent.get(sent.size() - 1));
        }

        int count() {
            return sent.size();
        }
    }

    record Msg(String topic, BridgeProtocol.Reader r) {
        static Msg of(byte[] b) {
            BridgeProtocol.Reader r = new BridgeProtocol.Reader(b);
            r.readVarInt();
            return new Msg(r.readString(), r);
        }
    }

    P peer = new P("u1", "Owner");

    @BeforeEach
    void setUp(@TempDir Path dir) {
        byte[] k = new byte[32];
        new Random(7).nextBytes(k);
        store = new FarmStore(dir.resolve("farm"), Base64.getEncoder().encodeToString(k), () -> now);
        known.add(FARM);
        owners.add("owner");
        accept.enable(AcceptSwitch.Source.TERMINAL, 10, now);
        env = new UploadController.Env() {
            public boolean farmerOn() {
                return farmerOn;
            }

            public boolean acceptOn(long n) {
                return accept.on(n);
            }

            public boolean sessionOpen() {
                return sessionOpen;
            }

            public boolean sessionOpenFor(String f) {
                return sessionOpen && f.equals(sessionFarm);
            }

            public boolean farmKnown(String f) {
                return known.contains(f);
            }

            public List<FarmInfo> farms() {
                return List.of(new FarmInfo(FARM, "Secret Name", "custom", false));
            }

            public boolean isOwner(UploadController.Peer p) {
                return owners.contains(p.name().toLowerCase());
            }

            public Own own() {
                return own;
            }

            public long now() {
                return now;
            }

            public void log(String line) {
                log.add(line);
            }

            public void stored(String f) {
                storedCalls++;
            }

            public String pluginVersion() {
                return "1.20.0";
            }

            public FarmStore store() {
                return store;
            }

            public AcceptSwitch accept() {
                return accept;
            }
        };
        ctl = new UploadController(env, Runnable::run);
    }

    UploadController.Env env;

    // ---- message builders -------------------------------------------------------------------------------------------

    static BridgeProtocol.Writer w(String topic) {
        BridgeProtocol.Writer w = new BridgeProtocol.Writer();
        w.writeVarInt(1);
        w.writeString(topic);
        return w;
    }

    static BridgeProtocol.Reader body(BridgeProtocol.Writer w) {
        BridgeProtocol.Reader r = new BridgeProtocol.Reader(w.toByteArray());
        r.readVarInt();
        r.readString();
        return r;
    }

    void hello(P p) {
        BridgeProtocol.Writer b = w(UploadController.T_HELLO);
        b.writeVarInt(1);
        b.writeString("0.12.0");
        b.writeString("1.21.11");
        ctl.onHello(p, body(b));
    }

    void begin(P p, String uid, String farm, long total, long chunkSize, long chunks, String sha, String hash) {
        BridgeProtocol.Writer b = w(UploadController.T_BEGIN);
        b.writeString(uid);
        b.writeString(farm);
        b.writeVarInt((int) total);
        b.writeVarInt((int) chunkSize);
        b.writeVarInt((int) chunks);
        b.writeString(sha);
        b.writeString(hash);
        ctl.onBegin(p, body(b));
    }

    void chunk(P p, String uid, int index, byte[] data, int declaredLength, int wordsToWrite) {
        BridgeProtocol.Writer b = w(UploadController.T_CHUNK);
        b.writeString(uid);
        b.writeVarInt(index);
        b.writeVarInt(declaredLength);
        for (int i = 0; i < wordsToWrite; i++) {
            int v = 0;
            for (int j = 0; j < 4; j++) {
                int at = i * 4 + j;
                v = (v << 8) | (at < data.length ? data[at] & 0xFF : 0);
            }
            b.writeInt(v);
        }
        ctl.onChunk(p, body(b));
    }

    void chunk(P p, String uid, int index, byte[] data) {
        chunk(p, uid, index, data, data.length, (data.length + 3) / 4);
    }

    void origin(P p, String uid, String dim, int x, int y, int z) {
        BridgeProtocol.Writer b = w(UploadController.T_ORIGIN);
        b.writeString(uid);
        b.writeString(dim);
        b.writeInt(x);
        b.writeInt(y);
        b.writeInt(z);
        ctl.onOrigin(p, body(b));
    }

    void end(P p, String uid) {
        BridgeProtocol.Writer b = w(UploadController.T_END);
        b.writeString(uid);
        ctl.onEnd(p, body(b));
    }

    static String sha(byte[] b) {
        return FarmRecording.sha256Hex(b);
    }

    static byte[][] split(byte[] data, int size) {
        int n = (data.length + size - 1) / size;
        byte[][] out = new byte[n][];
        for (int i = 0; i < n; i++) out[i] = java.util.Arrays.copyOfRange(data, i * size, Math.min(data.length, (i + 1) * size));
        return out;
    }

    /** A real program: the plain bytes and the packed bytes. */
    record Pkg(byte[] plain, byte[] packed) {
    }

    static Pkg program() {
        byte[] plain = Sim.walkLoop("custom", Sim.loops(0), null).encode();
        return new Pkg(plain, Packing.pack(plain));
    }

    void beginFor(P p, String uid, String farm, Pkg g, int chunkSize) {
        int chunks = (g.packed.length + chunkSize - 1) / chunkSize;
        begin(p, uid, farm, g.packed.length, chunkSize, chunks, sha(g.packed), sha(g.plain));
    }

    String code(P p) {
        Msg m = p.last();
        assertEquals("qwerte/farm/error", m.topic());
        m.r().readString();
        return m.r().readString();
    }

    String refusal(P p) {
        Msg m = p.last();
        assertEquals("qwerte/farm/refuse", m.topic());
        return m.r().readString();
    }

    void full(P p, String uid, String farm, Pkg g, int chunkSize) {
        beginFor(p, uid, farm, g, chunkSize);
        assertEquals("qwerte/farm/ack", p.last().topic());
        byte[][] parts = split(g.packed, chunkSize);
        for (int i = 0; i < parts.length; i++) chunk(p, uid, i, parts[i]);
        origin(p, uid, "minecraft:overworld", 100, 64, 200);
        end(p, uid);
    }

    // ---- hello -------------------------------------------------------------------------------------------------------

    @Test
    void helloIsRefusedWithoutTheFarmerRoleAndWithTheSwitchOffAndForASpectatorAndDuringASession() {
        farmerOn = false;
        hello(peer);
        assertEquals("not_farmer", refusal(peer));
        farmerOn = true;
        accept.disable();
        hello(peer);
        assertEquals("uploads_off", refusal(peer));
        accept.enable(AcceptSwitch.Source.TERMINAL, 5, now);
        P spec = new P("u2", "Owner");
        spec.spectator = true;
        hello(spec);
        assertEquals("busy", refusal(spec));
        sessionOpen = true;
        hello(peer);
        assertEquals("busy", refusal(peer));
    }

    @Test
    void readyNamesGoOnlyToTheProxyOwnersSession() {
        hello(peer);
        Msg m = peer.last();
        assertEquals("qwerte/farm/ready", m.topic());
        m.r().readString();
        assertEquals(24_576, m.r().readVarInt());
        assertEquals(2_097_152, m.r().readVarInt());
        assertEquals(1, m.r().readVarInt());
        assertEquals(FARM, m.r().readString());
        assertEquals("Secret Name", m.r().readString());

        P other = new P("u9", "Somebody");
        hello(other);
        Msg o = other.last();
        o.r().readString();
        o.r().readVarInt();
        o.r().readVarInt();
        o.r().readVarInt();
        assertEquals(FARM, o.r().readString());
        assertEquals("", o.r().readString(), "a session that is not the owner's gets an empty name");
    }

    @Test
    void aHostileHelloIsNotAnsweredAndThrowsNothing() {
        // proto 1 then a string length of 2^31-1 and nothing behind it
        BridgeProtocol.Writer b = w(UploadController.T_HELLO);
        b.writeVarInt(1);
        b.writeVarInt(Integer.MAX_VALUE);
        ctl.onHello(peer, body(b));
        assertEquals(0, peer.count());
        // a too long version string is too_old
        BridgeProtocol.Writer c = w(UploadController.T_HELLO);
        c.writeVarInt(1);
        c.writeString("x".repeat(33));
        c.writeString("1.21.11");
        ctl.onHello(peer, body(c));
        assertEquals("too_old", refusal(peer));
    }

    // ---- begin: the C6 numbers and strings ----------------------------------------------------------------------------

    @Test
    void beginNumbersAreCheckedBeforeAnythingIsAllocated() {
        String h = "a".repeat(64);
        begin(peer, UID, "draft", 0, 24576, 0, h, h);
        assertEquals("bad_program", code(peer));
        begin(peer, UID, "draft", 2_097_153, 24576, 86, h, h);
        assertEquals("too_big", code(peer));
        begin(peer, UID, "draft", 50_000, 24576, 2, h, h);
        assertEquals("bad_program", code(peer));
        begin(peer, UID, "draft", 50_000, 24_577, 3, h, h);
        assertEquals("bad_program", code(peer));
        begin(peer, UID, "draft", 5_000_000, 24576, 204, h, h); // far over, and chunks over 86
        assertEquals("too_big", code(peer));
        assertFalse(ctl.uploading());
        begin(peer, UID, "draft", 1000, 24576, 1, "zz", h);
        assertEquals("bad_hash", code(peer));
    }

    @Test
    void idsThatAreNotIdsAreRefusedAndNeverReachAPath() {
        String h = "a".repeat(64);
        begin(peer, "../../etc/passwd", "draft", 100, 100, 1, h, h);
        assertEquals("bad_program", code(peer));
        begin(peer, UID, "../../x", 100, 100, 1, h, h);
        assertEquals("bad_program", code(peer));
        begin(peer, UID, "f-1a2b3c4dd", 100, 100, 1, h, h);
        assertEquals("bad_program", code(peer));
        begin(peer, UID, "F-1A2B3C4D", 100, 100, 1, h, h);
        assertEquals("bad_program", code(peer));
        assertFalse(ctl.uploading());
    }

    @Test
    void aRealFarmIdThatIsNotInTheSyncedListIsRefusedBusy() {
        String h = "a".repeat(64);
        begin(peer, UID, "f-00000000", 100, 100, 1, h, h);
        assertEquals("busy", refusal(peer));
        assertFalse(ctl.uploading());
    }

    @Test
    void beginIsRefusedWhileASessionIsOpenForAnyFarmAndForDraft() {
        String h = "a".repeat(64);
        sessionOpen = true;
        sessionFarm = FARM;
        begin(peer, UID, FARM, 100, 100, 1, h, h);
        assertEquals("busy", refusal(peer));
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertEquals("busy", refusal(peer));
    }

    @Test
    void beginWithTheSwitchOffIsRefusedUploadsOff() {
        accept.disable();
        String h = "a".repeat(64);
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertEquals("uploads_off", refusal(peer));
    }

    @Test
    void theFifthUploadInTenMinutesIsRefusedBusy() {
        String h = "a".repeat(64);
        for (int i = 0; i < 4; i++) {
            begin(peer, UID, "draft", 100, 100, 1, h, h);
            assertEquals("qwerte/farm/ack", peer.last().topic(), "upload " + i);
            ctl.onAbort(peer, abortBody(UID));
        }
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertEquals("busy", refusal(peer));
        now += 10 * 60_000L + 1;
        accept.enable(AcceptSwitch.Source.TERMINAL, 5, now);
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertEquals("qwerte/farm/ack", peer.last().topic());
    }

    static BridgeProtocol.Reader abortBody(String uid) {
        BridgeProtocol.Writer b = w(UploadController.T_ABORT);
        b.writeString(uid);
        return body(b);
    }

    @Test
    void onlyOneUploadAtATime() {
        String h = "a".repeat(64);
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertEquals("qwerte/farm/ack", peer.last().topic());
        P second = new P("u2", "Other");
        begin(second, "fedcba9876543210", "draft", 100, 100, 1, h, h);
        assertEquals("busy", refusal(second));
    }

    // ---- chunks ------------------------------------------------------------------------------------------------------

    private byte[] filler(int n, long seed) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    private void beginRaw(byte[] packed, int chunkSize) {
        int chunks = (packed.length + chunkSize - 1) / chunkSize;
        begin(peer, UID, "draft", packed.length, chunkSize, chunks, sha(packed), "b".repeat(64));
        assertEquals("qwerte/farm/ack", peer.last().topic());
    }

    int ackNext() {
        Msg m = peer.last();
        assertEquals("qwerte/farm/ack", m.topic());
        m.r().readString();
        return m.r().readVarInt();
    }

    @Test
    void theWindowDropsAChunkTooFarAheadAndAcksTheNextIndexAgain() {
        byte[] data = filler(1000, 1);
        beginRaw(data, 100); // 10 chunks
        byte[][] parts = split(data, 100);
        chunk(peer, UID, 4, parts[4]);
        assertEquals(0, ackNext(), "index 4 is outside [0, 4): dropped, next stays 0");
        chunk(peer, UID, 0, parts[0]);
        assertEquals(1, ackNext());
        chunk(peer, UID, 3, parts[3]);
        assertEquals(1, ackNext());
        chunk(peer, UID, 1, parts[1]);
        chunk(peer, UID, 2, parts[2]);
        assertEquals(4, ackNext());
    }

    @Test
    void aRepeatedChunkIsAckedAndOneWithOtherBytesIsBadHash() {
        byte[] data = filler(1000, 2);
        beginRaw(data, 100);
        byte[][] parts = split(data, 100);
        chunk(peer, UID, 0, parts[0]);
        chunk(peer, UID, 0, parts[0]);
        assertEquals(1, ackNext());
        byte[] other = parts[0].clone();
        other[3] ^= 1;
        chunk(peer, UID, 0, other);
        assertEquals("bad_hash", code(peer));
        assertFalse(ctl.uploading(), "a bad chunk drops the upload");
    }

    @Test
    void chunkLengthsAreCheckedAgainstTheExpectedLengthBeforeAllocating() {
        byte[] data = filler(50_000, 3);
        beginRaw(data, 24_576); // 3 chunks: 24576, 24576, 848
        chunk(peer, UID, 1, new byte[100], 100, 25); // a short middle chunk
        assertEquals("bad_program", code(peer));
        beginAgain(data);
        chunk(peer, UID, 2, new byte[100], 100, 25); // wrong last length
        assertEquals("bad_program", code(peer));
        beginAgain(data);
        chunk(peer, UID, 0, new byte[1], Integer.MAX_VALUE, 1); // a hostile byteLength: nothing is allocated for it
        assertEquals("bad_program", code(peer));
        beginAgain(data);
        chunk(peer, UID, 3, new byte[100], 100, 25); // index past the count
        assertEquals("bad_program", code(peer));
    }

    private void beginAgain(byte[] data) {
        ctl.onAbort(peer, abortBody(UID));
        now += 1; // a new begin; the rate limit has room (4 per 10 minutes) in this test
        accept.enable(AcceptSwitch.Source.TERMINAL, 10, now);
        beginRaw(data, 24_576);
    }

    @Test
    void wordsMissingAfterTheDeclaredLengthIsBadProgram() {
        byte[] data = filler(1000, 4);
        beginRaw(data, 100);
        chunk(peer, UID, 0, filler(100, 9), 100, 10); // byteLength 100 but only 10 Int words (40 bytes) follow
        assertEquals("bad_program", code(peer));
        assertFalse(ctl.uploading());
    }

    @Test
    void aChunkFromAnotherConnectionOrAnotherIdIsIgnored() {
        byte[] data = filler(1000, 5);
        beginRaw(data, 100);
        P intruder = new P("u2", "Other");
        chunk(intruder, UID, 0, split(data, 100)[0]);
        assertEquals(0, intruder.count());
        chunk(peer, "ffffffffffffffff", 0, split(data, 100)[0]);
        assertEquals("qwerte/farm/ack", peer.last().topic());
        assertEquals(0, ackNext());
    }

    @Test
    void sixtyOneSecondsWithoutAChunkIsATimeout() {
        beginRaw(filler(1000, 6), 100);
        now += 59_000;
        ctl.tick(now);
        assertTrue(ctl.uploading());
        now += 2_000;
        ctl.tick(now);
        assertEquals("timeout", code(peer));
        assertFalse(ctl.uploading());
    }

    @Test
    void endBeforeEveryChunkArrivedIsBadProgram() {
        byte[] data = filler(1000, 7);
        beginRaw(data, 100);
        chunk(peer, UID, 0, split(data, 100)[0]);
        end(peer, UID);
        assertEquals("bad_program", code(peer));
    }

    @Test
    void aHandlerThatThrowsAnErrorAnswersAClosedCodeAndHoldsNothing() {
        known.clear();
        Set<String> boom = new HashSet<>() {
            @Override
            public boolean contains(Object o) {
                throw new OutOfMemoryError("test");
            }
        };
        ctl = new UploadController(new DelegatingEnv(boom), Runnable::run);
        String h = "a".repeat(64);
        begin(peer, UID, FARM, 100, 100, 1, h, h);
        assertEquals("too_big", code(peer));
        assertFalse(ctl.uploading());
    }

    /** An env whose farmKnown throws. */
    final class DelegatingEnv implements UploadController.Env {
        final Set<String> boom;

        DelegatingEnv(Set<String> boom) {
            this.boom = boom;
        }

        public boolean farmerOn() {
            return true;
        }

        public boolean acceptOn(long n) {
            return true;
        }

        public boolean sessionOpen() {
            return false;
        }

        public boolean sessionOpenFor(String f) {
            return false;
        }

        public boolean farmKnown(String f) {
            return boom.contains(f);
        }

        public List<FarmInfo> farms() {
            return List.of();
        }

        public boolean isOwner(UploadController.Peer p) {
            return false;
        }

        public Own own() {
            return own;
        }

        public long now() {
            return now;
        }

        public void log(String line) {
            log.add(line);
        }

        public void stored(String f) {
        }

        public String pluginVersion() {
            return "1.20.0";
        }

        public FarmStore store() {
            return store;
        }

        public AcceptSwitch accept() {
            return accept;
        }
    }

    /** The six upload cases that were open in farm-vectors.json, run from the file itself when it is next to this repo (qwerte-release). */
    @Test
    void theSixOpenVectorCasesRunFromTheVectorFile() throws Exception {
        Path file = Path.of(System.getProperty("farmVectors", "../qwerte-release/farm-vectors.json"));
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(file), "farm-vectors.json is not next to this repo");
        var root = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
        java.util.Map<String, com.google.gson.JsonObject> bad = new java.util.HashMap<>();
        for (var e : root.getAsJsonObject("upload").getAsJsonArray("bad")) bad.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
        String h = "a".repeat(64);
        byte[] data = filler(1000, 5);
        // words_missing: byteLength 100 but only 10 Int words follow
        beginRaw(data, 100);
        chunk(peer, UID, 0, filler(100, 9), 100, 10);
        assertEquals(bad.get("words_missing").get("error").getAsString(), code(peer));
        // begin_while_session_open
        sessionOpen = true;
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertEquals(bad.get("begin_while_session_open").get("error").getAsString(), refusal(peer));
        assertEquals("refuse", bad.get("begin_while_session_open").get("kind").getAsString());
        sessionOpen = false;
        // uploads_switch_off
        accept.disable();
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertEquals(bad.get("uploads_switch_off").get("error").getAsString(), refusal(peer));
        // real_farm_id_not_synced
        accept.enable(AcceptSwitch.Source.TERMINAL, 10, now);
        begin(peer, UID, "f-00000000", 100, 100, 1, h, h);
        assertEquals(bad.get("real_farm_id_not_synced").get("error").getAsString(), refusal(peer));
        // fifth_upload_in_10_minutes (one begin was accepted above for words_missing; three more fill the window)
        for (int i = 0; i < 3; i++) {
            begin(peer, UID, "draft", 100, 100, 1, h, h);
            assertEquals("qwerte/farm/ack", peer.last().topic());
            ctl.onAbort(peer, abortBody(UID));
        }
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertEquals(bad.get("fifth_upload_in_10_minutes").get("error").getAsString(), refusal(peer));
    }

    // ---- the whole upload ---------------------------------------------------------------------------------------------

    @Test
    void aRealUploadIsStoredSealedAndTheSwitchTurnsOff() {
        Pkg g = program();
        full(peer, UID, "draft", g, 100);
        Msg m = peer.last();
        assertEquals("qwerte/farm/stored", m.topic());
        assertEquals("draft", m.r().readString());
        assertEquals(sha(g.plain), m.r().readString());
        assertEquals(80, m.r().readVarInt());
        assertEquals(80, m.r().readVarInt());
        assertEquals(2, m.r().readVarInt());
        assertFalse(accept.on(now), "one successful upload turns the switch off");
        assertEquals(1, storedCalls);
        assertEquals(sha(g.plain), store.entry("draft").programHash());
        // the terminal line: mode, counts, 8 hex of the hash, a code; no position, and the log guard lets it through
        String line = log.get(log.size() - 1);
        assertTrue(line.contains("mode=custom") && line.contains("hash=" + sha(g.plain).substring(0, 8)) && line.contains("code=stored"), line);
        assertFalse(FarmLogGuard.denies("Module", "Farm: " + line), "the summary line must pass the position filter: " + line);
        // the sealed file is not the program
        assertNotNull(store.open("draft"));
    }

    @Test
    void aRealFarmIdIsStoredWhenItIsInTheSyncedList() {
        Pkg g = program();
        full(peer, UID, FARM, g, 24_576);
        assertEquals("qwerte/farm/stored", peer.last().topic());
        assertEquals(sha(g.plain), store.entry(FARM).programHash());
    }

    @Test
    void theClientsOriginIsOnlyACrossCheck() {
        Pkg g = program();
        beginFor(peer, UID, "draft", g, 24_576);
        for (int i = 0; i < split(g.packed, 24_576).length; i++) chunk(peer, UID, i, split(g.packed, 24_576)[i]);
        origin(peer, UID, "minecraft:overworld", 103, 64, 200); // 3 blocks off
        end(peer, UID);
        assertEquals("origin_mismatch", code(peer));
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void aDifferentDimensionIsWrongDimAndNoOriginIsNoOrigin() {
        Pkg g = program();
        beginFor(peer, UID, "draft", g, 24_576);
        chunk(peer, UID, 0, split(g.packed, 24_576)[0]);
        origin(peer, UID, "minecraft:the_nether", 100, 64, 200);
        end(peer, UID);
        assertEquals("wrong_dim", code(peer));

        accept.enable(AcceptSwitch.Source.TERMINAL, 10, now);
        beginFor(peer, UID, "draft", g, 24_576);
        chunk(peer, UID, 0, split(g.packed, 24_576)[0]);
        end(peer, UID); // no origin message at all
        assertEquals("no_origin", code(peer));

        accept.enable(AcceptSwitch.Source.TERMINAL, 10, now);
        own = null;
        beginFor(peer, UID, "draft", g, 24_576);
        chunk(peer, UID, 0, split(g.packed, 24_576)[0]);
        origin(peer, UID, "minecraft:overworld", 100, 64, 200);
        end(peer, UID);
        assertEquals("no_origin", code(peer));
    }

    @Test
    void wrongHashesAndGarbageProgramsAreRefusedWithClosedCodes() {
        Pkg g = program();
        // the packed hash is wrong
        int chunks = (g.packed.length + 24_575) / 24_576;
        begin(peer, UID, "draft", g.packed.length, 24_576, chunks, "c".repeat(64), sha(g.plain));
        chunk(peer, UID, 0, split(g.packed, 24_576)[0]);
        origin(peer, UID, "minecraft:overworld", 100, 64, 200);
        end(peer, UID);
        assertEquals("bad_hash", code(peer));
        // a well hashed upload of bytes that are not a program
        accept.enable(AcceptSwitch.Source.TERMINAL, 10, now);
        byte[] junk = Packing.pack(filler(500, 11));
        begin(peer, UID, "draft", junk.length, 24_576, 1, sha(junk), sha(filler(500, 11)));
        chunk(peer, UID, 0, junk);
        origin(peer, UID, "minecraft:overworld", 100, 64, 200);
        end(peer, UID);
        assertEquals("bad_program", code(peer));
        assertTrue(store.entries().isEmpty());
    }

    // ---- review fixes ------------------------------------------------------------------------------------------------

    @Test
    void theWorkerChecksTheRoleTheSwitchAndTheSessionAgainBeforeStoring() {
        for (int variant = 0; variant < 4; variant++) {
            peer = new P("u1", "Owner");
            now += 11 * 60_000L; // a fresh rate window for each variant
            accept.enable(AcceptSwitch.Source.TERMINAL, 10, now);
            java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
            ctl = new UploadController(env, queue::add);
            Pkg g = program();
            beginFor(peer, UID, "draft", g, 24_576);
            for (int i = 0; i < split(g.packed, 24_576).length; i++) chunk(peer, UID, i, split(g.packed, 24_576)[i]);
            origin(peer, UID, "minecraft:overworld", 100, 64, 200);
            end(peer, UID);
            assertEquals(1, queue.size());
            switch (variant) {
                case 0 -> farmerOn = false;
                case 1 -> accept.disable();
                case 2 -> sessionOpen = true;
                default -> {
                    sessionOpen = true;
                    sessionFarm = "draft";
                }
            }
            queue.poll().run();
            assertEquals("busy", code(peer), "variant " + variant);
            assertTrue(store.entries().isEmpty(), "nothing was stored");
            farmerOn = true;
            sessionOpen = false;
        }
    }

    @Test
    void nothingChangesAfterEndHandedTheUploadToTheWorker() {
        java.util.ArrayDeque<Runnable> queue = new java.util.ArrayDeque<>();
        ctl = new UploadController(env, queue::add);
        Pkg g = program();
        beginFor(peer, UID, "draft", g, 24_576);
        for (int i = 0; i < split(g.packed, 24_576).length; i++) chunk(peer, UID, i, split(g.packed, 24_576)[i]);
        origin(peer, UID, "minecraft:overworld", 100, 64, 200);
        end(peer, UID);
        int before = peer.count();
        origin(peer, UID, "minecraft:the_nether", 1, 2, 3); // ignored: ending
        chunk(peer, UID, 0, split(g.packed, 24_576)[0]);
        assertEquals(before, peer.count(), "no answer, no change");
        queue.poll().run();
        assertEquals("qwerte/farm/stored", peer.last().topic(), "the origin of the first message was the one used");
    }

    @Test
    void aFloodOfBadMessagesGetsAtMostSixAnswers() {
        String h = "a".repeat(64);
        for (int i = 0; i < 100; i++) begin(peer, "zz", "draft", 100, 100, 1, h, h);
        assertTrue(peer.count() <= 6, "answers " + peer.count());
        assertTrue(log.size() <= 6, "log lines " + log.size());
        now += 11_000;
        begin(peer, "zz", "draft", 100, 100, 1, h, h);
        assertTrue(peer.count() <= 7, "answered again after the window");
    }

    @Test
    void aConnectionThatGoesAwayTakesItsUploadWithIt() {
        String h = "a".repeat(64);
        begin(peer, UID, "draft", 100, 100, 1, h, h);
        assertTrue(ctl.uploading());
        ctl.peerGone("someone-else");
        assertTrue(ctl.uploading());
        ctl.peerGone(peer.id());
        assertFalse(ctl.uploading());
    }
}
