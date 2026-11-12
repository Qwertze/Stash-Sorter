package com.porter.qwertestash.pearl;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.SafeJson;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * What the trip driver keeps on disk ({@code plugins/qwerte-stash/pearl-trip.json}), so a disconnect or a restart after the whisper can
 * be told apart from "nothing happened": the away mark, the trip's machine state, the place the bot whispered from (compared locally
 * for "still here", never logged, never sent) and the times. Written atomically; a file that is damaged, too big or of another
 * version is ignored (the bot then starts with no trip and no away mark, and the owner clears the server's mark with {@code home}).
 */
public final class PearlJournal {
    private static final int VERSION = 1;
    private static final long MAX_BYTES = 64 * 1024;
    private static final Gson GSON = new Gson();
    private static final java.util.Set<String> STEPS = java.util.Set.of("precheck", "arm_home", "ready", "load_sent", "awaiting_pull", "arrived", "pickup", "rearm", "done");
    private static final java.util.Set<String> STATES = java.util.Set.of("running", "paused", "failed", "completed", "cancelled");

    /** The whole file. Plain fields so Gson reads and writes it as it is. */
    public static final class Data {
        public int v = VERSION;
        public @Nullable String awayStation, awayTrip;
        public long awaySince;
        /** The trip, or null. */
        public @Nullable Trip trip;
        /** The ids of the local requests sent (so a restart does not send an old one again), newest last. */
        public java.util.List<String> localIds = new java.util.ArrayList<>();
        /** When the last {@code go} whisper was sent (the local five-minute spacing). */
        public long lastGoAt;
        /** When the last whisper of any kind was sent, and the times of those of the last hour (the global rate limit). */
        public long lastWhisperAt;
        public java.util.List<Long> whisperTimes = new java.util.ArrayList<>();
    }

    public static final class Trip {
        public @Nullable String id, action, station, dim, pearlId, loader, step, state, code, warning, pauseCode, rearmResult, teleportDim, ownError;
        public int whispers, tries, pearlsBefore = -1, pearlsAfter = -1;
        public boolean away, watch, hold, armHomeUsed, finalAcked;
        public long loadSentAt, deadlineAt, teleportAt, watchUntil, joinedAtSeen;
        /** Where the bot stood when it whispered: only compared locally. */
        public boolean haveFrom;
        public double fromX, fromY, fromZ;
        public @Nullable String fromDim;
    }

    private final Path file;

    public PearlJournal(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** What the file holds, or an empty record when there is none or it can't be trusted. */
    public Data load() {
        return load(System.currentTimeMillis());
    }

    /** As {@link #load()}, with the clock the times are checked against (a time from the future is not believed). */
    public Data load(long now) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) return new Data();
            JsonElement e = SafeJson.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(e instanceof JsonObject o) || !o.has("v") || o.get("v").getAsInt() != VERSION) return new Data();
            Data d = GSON.fromJson(o, Data.class);
            return d == null ? new Data() : sane(d, now);
        } catch (IOException | RuntimeException e) {
            return new Data();
        }
    }

    /** A value read from disk is checked like one from the network: anything that does not fit is dropped. */
    static Data sane(Data d, long now) {
        d.awaySince = Math.max(0, Math.min(d.awaySince, now));
        d.lastGoAt = Math.max(0, Math.min(d.lastGoAt, now));
        d.lastWhisperAt = Math.max(0, Math.min(d.lastWhisperAt, now));
        java.util.List<Long> times = new java.util.ArrayList<>();
        if (d.whisperTimes != null) for (Long t : d.whisperTimes) if (t != null && t > 0 && t <= now && now - t < 3_600_000L) times.add(t);
        while (times.size() > 10) times.remove(0);
        d.whisperTimes = times;
        if (d.awayStation != null && !PearlReply.STATION.matcher(d.awayStation).matches()) {
            d.awayStation = null;
        }
        if (d.awayTrip != null && !PearlReply.TRIP.matcher(d.awayTrip).matches()) d.awayTrip = null;
        if (d.localIds == null) d.localIds = new java.util.ArrayList<>();
        Trip t = d.trip;
        if (t != null) {
            // Every value is checked like one from the network: closed word lists, clamped counts, a valid dimension, finite numbers
            boolean words = t.step != null && STEPS.contains(t.step) && t.state != null && STATES.contains(t.state);
            long cap = now + 10 * 60_000L;
            t.deadlineAt = Math.max(0, Math.min(t.deadlineAt, cap));
            t.watchUntil = Math.max(0, Math.min(t.watchUntil, cap));
            t.teleportAt = Math.max(0, Math.min(t.teleportAt, cap));
            t.loadSentAt = Math.max(0, Math.min(t.loadSentAt, now));
            t.whispers = Math.max(0, Math.min(2, t.whispers));
            t.tries = Math.max(0, Math.min(3, t.tries));
            t.pearlsBefore = Math.max(-1, Math.min(2304, t.pearlsBefore));
            t.pearlsAfter = Math.max(-1, Math.min(2304, t.pearlsAfter));
            if (t.fromDim != null && !PearlReply.DIM.matcher(t.fromDim).matches()) t.haveFrom = false;
            if (!Double.isFinite(t.fromX) || !Double.isFinite(t.fromY) || !Double.isFinite(t.fromZ)) {
                t.haveFrom = false;
                t.fromX = t.fromY = t.fromZ = 0;
            }
            if (t.teleportDim != null && !PearlReply.DIM.matcher(t.teleportDim).matches()) t.teleportDim = null;
            if (t.code != null && !t.code.matches("[a-z_]{1,32}")) t.code = null;
            if (t.pauseCode != null && !t.pauseCode.matches("[a-z_]{1,32}")) t.pauseCode = null;
            if (t.ownError != null && !t.ownError.matches("[a-z_]{1,32}")) t.ownError = null;
            if (t.warning != null && !t.warning.matches("[a-z_]{1,32}")) t.warning = null;
            if (t.rearmResult != null && !t.rearmResult.matches("[A-Z_]{1,16}")) t.rearmResult = null;
            if (t.dim != null && !PearlReply.DIM.matcher(t.dim).matches()) t.dim = null;
            boolean ok = words && t.id != null && PearlReply.TRIP.matcher(t.id).matches() && t.action != null && (t.action.equals("go") || t.action.equals("back"))
                && t.step != null && t.state != null && (t.pearlId == null || PearlReply.PEARL_ID.matcher(t.pearlId).matches())
                && (t.loader == null || PearlReply.PLAYER.matcher(t.loader).matches()) && (t.station == null || PearlReply.STATION.matcher(t.station).matches());
            if (!ok) d.trip = null;
        }
        return d;
    }

    /** The file holds a position: readable and writable by its owner alone where the system can say so (POSIX permissions, else the File flags). */
    private static void ownerOnly(Path p) {
        try {
            Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | RuntimeException e) {
            java.io.File f = p.toFile();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
        }
    }

    /** Writes the file (a temporary file, then a move). A failure is reported to the caller, which only logs it. */
    public void save(Data d) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
        ownerOnly(tmp);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
