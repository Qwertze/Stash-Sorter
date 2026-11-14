package com.porter.qwertestash.farm;

import com.aquarius.feature.bridge.BridgeProtocol;
import com.porter.qwerte.core.farm.AnchorMath;
import com.porter.qwerte.core.farm.FarmException;
import com.porter.qwerte.core.farm.FarmRecording;
import com.porter.qwerte.core.farm.Packing;
import com.porter.qwerte.core.farm.UploadSession;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * The plugin's half of the upload protocol (FARM Part C). The handlers run on a netty IO thread: they validate, copy bytes into
 * the one {@link UploadSession} and answer; inflating, checking and sealing run on one worker (C4). Order is check, then
 * allocate (C6). Every handler catches {@link Throwable}, answers a closed code and releases what it held. No message or log line
 * built here carries a position, a name or input text; the terminal summary has the mode, counts, 8 hex of the hash and a code.
 *
 * <p>The proxy is seen only through {@link Peer} (the session) and {@link Env}; messages are read from the proxy's own
 * {@link BridgeProtocol.Reader} and written with its {@link BridgeProtocol.Writer}.
 */
public final class UploadController {
    public static final String T_HELLO = "qwerte/farm/hello", T_BEGIN = "qwerte/farm/begin", T_CHUNK = "qwerte/farm/chunk",
        T_ORIGIN = "qwerte/farm/origin", T_END = "qwerte/farm/end", T_ABORT = "qwerte/farm/abort";
    public static final List<String> TOPICS = List.of(T_HELLO, T_BEGIN, T_CHUNK, T_ORIGIN, T_END, T_ABORT);
    private static final String NO_ID = "0000000000000000";

    /** One Bridge connection. */
    public interface Peer {
        String id();

        String name();

        boolean spectator();

        /** Sends a Bridge payload to the client (safe from any thread). */
        void send(byte[] payload);
    }

    /** One farm as the ready message lists it. */
    public record FarmInfo(String id, String name, String mode, boolean hasProgram) {
    }

    /** The plugin's own player cache at the {@code origin} message: dimension and block position. Null when there is none. */
    public record Own(String dim, int bx, int by, int bz) {
        @Override
        public String toString() {
            return "Own[]"; // never the position
        }
    }

    public interface Env {
        boolean farmerOn();

        boolean acceptOn(long nowMs);

        /** Any farm session is open or parked on this farmer. */
        boolean sessionOpen();

        boolean sessionOpenFor(String farmId);

        /** The farm is in the list from the last successful sync. */
        boolean farmKnown(String farmId);

        List<FarmInfo> farms();

        /** The connection belongs to the proxy owner (a local setting). */
        boolean isOwner(Peer peer);

        /** The plugin's own view of where the bot is, null when it has none. */
        Own own();

        long now();

        /** One terminal line (mode, counts, hash prefix and a code). */
        void log(String line);

        /** A program was stored: ask for a sync soon. */
        void stored(String farmId);

        String pluginVersion();

        /** The store; used on the worker only. */
        FarmStore store();

        AcceptSwitch accept();
    }

    private final Env env;
    private final UploadRate rate = new UploadRate();
    private final Executor worker;

    private static final class Active {
        final Peer peer;
        final String uploadId;
        final String farmId;
        final long total;
        final long chunkSize;
        final long chunks;
        final String programHash;
        final UploadSession session;
        String originDim;
        int ox, oy, oz;
        boolean haveClientOrigin;
        Own own;
        boolean ending;

        Active(Peer peer, String uploadId, String farmId, long total, long chunkSize, long chunks, String programHash, UploadSession session) {
            this.peer = peer;
            this.uploadId = uploadId;
            this.farmId = farmId;
            this.total = total;
            this.chunkSize = chunkSize;
            this.chunks = chunks;
            this.programHash = programHash;
            this.session = session;
        }
    }

    private Active active;

    /** Replies per peer: a flood of bad messages gets at most this many answers (and log lines) per window, then silence (review item 22). */
    private static final int MAX_REPLIES = 6;
    private static final long REPLY_WINDOW_MS = 10_000;
    private final java.util.Map<String, java.util.ArrayDeque<Long>> replies = new java.util.HashMap<>();

    private synchronized boolean mayReply(Peer p) {
        long now = env.now();
        if (replies.size() > 256) replies.values().removeIf(q -> q.isEmpty() || now - q.peekLast() > REPLY_WINDOW_MS);
        java.util.ArrayDeque<Long> q = replies.computeIfAbsent(p.id(), k -> new java.util.ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > REPLY_WINDOW_MS) q.pollFirst();
        if (q.size() >= MAX_REPLIES) return false;
        q.addLast(now);
        return true;
    }

    public UploadController(Env env, Executor worker) {
        this.env = env;
        this.worker = worker;
    }

    // ---- output --------------------------------------------------------------------------------------------------------

    private static BridgeProtocol.Writer start(String topic) {
        BridgeProtocol.Writer w = new BridgeProtocol.Writer();
        w.writeVarInt(BridgeProtocol.PROTOCOL_VERSION);
        w.writeString(topic);
        return w;
    }

    private void refuse(Peer p, String code) {
        if (!mayReply(p)) return;
        BridgeProtocol.Writer w = start("qwerte/farm/refuse");
        w.writeString(code);
        p.send(w.toByteArray());
        env.log("Farm upload refused: code=" + code);
    }

    private void error(Peer p, String uploadId, String code) {
        if (!mayReply(p)) return;
        BridgeProtocol.Writer w = start("qwerte/farm/error");
        w.writeString(uploadId == null || !UploadSession.uploadIdOk(uploadId) ? NO_ID : uploadId);
        w.writeString(code);
        p.send(w.toByteArray());
        env.log("Farm upload failed: code=" + code);
    }

    private void ack(Peer p, String uploadId, int next) {
        BridgeProtocol.Writer w = start("qwerte/farm/ack");
        w.writeString(uploadId);
        w.writeVarInt(next);
        p.send(w.toByteArray());
    }

    // ---- handlers (network thread) ---------------------------------------------------------------------------------

    private synchronized void drop(Peer p) {
        if (active != null && active.peer.id().equals(p.id())) active = null;
    }

    /** The gate refusals shared by hello and begin: a closed refuse code, or null. */
    private String gate(Peer p) {
        if (!env.farmerOn()) return "not_farmer";
        if (p.spectator()) return "busy";
        if (!env.acceptOn(env.now())) return "uploads_off";
        if (env.sessionOpen()) return "busy";
        return null;
    }

    public void onHello(Peer p, BridgeProtocol.Reader r) {
        try {
            int proto = r.readVarInt();
            String version = r.readString();
            String mc = r.readString();
            if (proto != 1 || version.length() > 32 || mc.length() > 32) {
                refuse(p, "too_old");
                return;
            }
            String g = gate(p);
            if (g != null) {
                refuse(p, g);
                return;
            }
            boolean names = env.isOwner(p);
            List<FarmInfo> farms = env.farms();
            BridgeProtocol.Writer w = start("qwerte/farm/ready");
            w.writeString(env.pluginVersion());
            w.writeVarInt(UploadSession.MAX_CHUNK);
            w.writeVarInt(Packing.MAX_PACKED);
            int n = Math.min(farms.size(), 64);
            w.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                FarmInfo f = farms.get(i);
                w.writeString(f.id());
                w.writeString(names ? f.name() : ""); // names only for the proxy owner's session (L3)
                w.writeString(f.mode());
                w.writeBoolean(f.hasProgram());
            }
            p.send(w.toByteArray());
        } catch (Throwable t) {
            // a malformed hello gets no answer: nothing was allocated, nothing is held
        }
    }

    public synchronized void onBegin(Peer p, BridgeProtocol.Reader r) {
        String uploadId = null;
        try {
            uploadId = r.readString();
            String farmId = r.readString();
            long total = r.readVarInt();
            long chunkSize = r.readVarInt();
            long chunks = r.readVarInt();
            String sha = r.readString();
            String programHash = r.readString();
            String g = gate(p);
            if (g != null) {
                refuse(p, g);
                return;
            }
            if (active != null) {
                refuse(p, "busy");
                return;
            }
            // C6.3 strings, then C6.6 numbers; nothing is allocated until all of them pass
            if (!UploadSession.uploadIdOk(uploadId) || !UploadSession.farmIdOk(farmId)) {
                error(p, uploadId, "bad_program");
                return;
            }
            String num = UploadSession.checkBegin(total, chunkSize, chunks);
            if (num != null) {
                error(p, uploadId, num);
                return;
            }
            if (!UploadSession.hashOk(sha) || !UploadSession.hashOk(programHash)) {
                error(p, uploadId, "bad_hash");
                return;
            }
            // C6.5: a real farm id must be in the synced list and have no open session
            if (!farmId.equals(FarmStore.DRAFT)) {
                if (!env.farmKnown(farmId)) {
                    refuse(p, "busy"); // vector real_farm_id_not_synced: refuse busy (the addon may then use draft)
                    return;
                }
                if (env.sessionOpenFor(farmId)) {
                    refuse(p, "busy");
                    return;
                }
            }
            long now = env.now();
            if (!rate.tryAcquire(now)) {
                refuse(p, "busy");
                return;
            }
            UploadSession s = new UploadSession(uploadId, total, chunkSize, chunks, sha, programHash, now);
            active = new Active(p, uploadId, farmId, total, chunkSize, chunks, programHash, s);
            ack(p, uploadId, 0);
        } catch (FarmException e) {
            error(p, uploadId, e.code());
        } catch (OutOfMemoryError e) {
            active = null;
            error(p, uploadId, "too_big");
        } catch (Throwable t) {
            active = null;
            error(p, uploadId, "bad_program");
        }
    }

    public synchronized void onChunk(Peer p, BridgeProtocol.Reader r) {
        String uploadId = null;
        try {
            uploadId = r.readString();
            Active a = active;
            if (a == null || !a.peer.id().equals(p.id()) || !a.uploadId.equals(uploadId) || a.ending) {
                return; // not ours, or already handed to the worker: ignored, not answered
            }
            int index = r.readVarInt();
            int byteLength = r.readVarInt();
            if (index < 0 || index >= a.chunks) throw new FarmException("bad_program");
            int expected = UploadSession.expectedLength(a.total, a.chunkSize, a.chunks, index);
            if (byteLength != expected || byteLength < 1 || byteLength > a.chunkSize) throw new FarmException("bad_program");
            int words = (byteLength + 3) / 4;
            if (!r.hasRemaining()) throw new FarmException("bad_program");
            byte[] data = new byte[byteLength];
            try {
                for (int i = 0; i < words; i++) {
                    int v = r.readInt();
                    for (int b = 0; b < 4; b++) {
                        int at = i * 4 + b;
                        if (at < byteLength) data[at] = (byte) (v >>> (24 - 8 * b));
                    }
                }
            } catch (IndexOutOfBoundsException e) {
                throw new FarmException("bad_program");
            }
            a.session.chunk(index, data, env.now());
            ack(p, uploadId, a.session.next());
        } catch (FarmException e) {
            drop(p);
            error(p, uploadId, e.code());
        } catch (OutOfMemoryError e) {
            drop(p);
            error(p, uploadId, "too_big");
        } catch (Throwable t) {
            drop(p);
            error(p, uploadId, "bad_program");
        }
    }

    public synchronized void onOrigin(Peer p, BridgeProtocol.Reader r) {
        String uploadId = null;
        try {
            uploadId = r.readString();
            Active a = active;
            if (a == null || !a.peer.id().equals(p.id()) || !a.uploadId.equals(uploadId) || a.ending) return;
            String dim = r.readString();
            int bx = r.readInt();
            int by = r.readInt();
            int bz = r.readInt();
            if (!UploadSession.dimOk(dim)) throw new FarmException("bad_program");
            a.originDim = dim;
            a.ox = bx;
            a.oy = by;
            a.oz = bz;
            a.haveClientOrigin = true;
            a.own = env.own(); // the plugin's own view at this moment (A4)
        } catch (FarmException e) {
            drop(p);
            error(p, uploadId, e.code());
        } catch (Throwable t) {
            drop(p);
            error(p, uploadId, "bad_program");
        }
    }

    public synchronized void onEnd(Peer p, BridgeProtocol.Reader r) {
        String uploadId = null;
        try {
            uploadId = r.readString();
            Active a = active;
            if (a == null || !a.peer.id().equals(p.id()) || !a.uploadId.equals(uploadId) || a.ending) return;
            if (!a.session.complete()) throw new FarmException("bad_program");
            a.ending = true;
            final Active job = a;
            worker.execute(() -> finishOnWorker(job));
        } catch (FarmException e) {
            drop(p);
            error(p, uploadId, e.code());
        } catch (Throwable t) {
            drop(p);
            error(p, uploadId, "bad_program");
        }
    }

    public synchronized void onAbort(Peer p, BridgeProtocol.Reader r) {
        try {
            String id = r.readString();
            if (active != null && active.peer.id().equals(p.id()) && active.uploadId.equals(id)) active = null;
        } catch (Throwable t) {
            // nothing held that a bad abort could free
        }
    }

    /** The connection went away: its upload is dropped (nobody is left to answer). */
    public synchronized void peerGone(String peerId) {
        if (active != null && active.peer.id().equals(peerId) && !active.ending) active = null;
        replies.remove(peerId);
    }

    /** The 60 s timeout (called from the sync worker, so it runs while a controller is connected; any thread). */
    public synchronized void tick(long nowMs) {
        Active a = active;
        if (a != null && !a.ending && a.session.timedOut(nowMs)) {
            active = null;
            error(a.peer, a.uploadId, "timeout");
        }
    }

    /** True while an upload is in flight. */
    public synchronized boolean uploading() {
        return active != null;
    }

    // ---- worker ---------------------------------------------------------------------------------------------------------

    private void finishOnWorker(Active a) {
        String farmId = a.farmId;
        try {
            // What was true at begin may not be now: the role, the switch and the session state are asked again (review item 9)
            if (!env.farmerOn() || !env.acceptOn(env.now()) || env.sessionOpen() || (!farmId.equals(FarmStore.DRAFT) && env.sessionOpenFor(farmId))) {
                throw new FarmException("busy");
            }
            UploadSession.Finished f = a.session.finish(); // hashes, inflate with the 8 MiB counter, checker
            FarmRecording program = f.program();
            // C5.2: the origin is the plugin's own; the client's is a cross-check. The fields are read under the lock (no more writes: ending is set)
            Own own;
            String originDim;
            int cx, cy, cz;
            boolean haveOrigin;
            synchronized (this) {
                own = a.own;
                originDim = a.originDim;
                cx = a.ox;
                cy = a.oy;
                cz = a.oz;
                haveOrigin = a.haveClientOrigin;
            }
            if (own == null || !haveOrigin) throw new FarmException("no_origin");
            boolean sameDim = own.dim().equals(originDim) && own.dim().equals(program.header().dim());
            String cross = AnchorMath.originCrossCheck(cx - own.bx(), cy - own.by(), cz - own.bz(), sameDim);
            if (cross != null) throw new FarmException(cross);
            FarmStore store = env.store();
            store.put(farmId, f.plain(), program, own.dim(), own.bx(), own.by(), own.bz());
            synchronized (this) {
                if (active == a) active = null;
            }
            env.accept().uploaded();
            var h = program.header();
            BridgeProtocol.Writer w = start("qwerte/farm/stored");
            w.writeString(farmId);
            w.writeString(FarmRecording.sha256Hex(f.plain()));
            w.writeVarInt(h.ticks());
            w.writeVarInt(h.loop().end() - h.loop().start());
            w.writeVarInt(h.anchors().size());
            a.peer.send(w.toByteArray());
            env.log("Farm upload stored: mode=" + h.mode() + " ticks=" + h.ticks() + " loop=" + (h.loop().end() - h.loop().start())
                + " anchors=" + h.anchors().size() + " unsupported=" + h.unsupported().size() + " hash=" + FarmRecording.sha256Hex(f.plain()).substring(0, 8)
                + " code=stored");
            env.stored(farmId);
        } catch (FarmException e) {
            fail(a, e.code());
        } catch (OutOfMemoryError e) {
            fail(a, "too_big");
        } catch (Throwable t) {
            fail(a, "bad_program");
        }
    }

    private void fail(Active a, String code) {
        synchronized (this) {
            if (active == a) active = null;
        }
        error(a.peer, a.uploadId, code);
    }
}
