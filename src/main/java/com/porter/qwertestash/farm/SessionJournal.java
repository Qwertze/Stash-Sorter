package com.porter.qwertestash.farm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * {@code farm/session.json} (D): farm id, task id, epoch, loop count, anchor id, track tick, counts and the {@code stopped} flag.
 * No positions. Plain JSON, owner-only, written by temporary file and atomic move. It is a hint only: a session is <b>never</b> resumed
 * from it (a restart starts nothing, the server offers the task again, D3b) and nothing in it is trusted for a position. The
 * {@code stopped} flag is what keeps a STOP after a restart, so it fails closed: a journal that exists but cannot be read counts as a local
 * STOP ({@link #loadError()}), and a write that failed is kept and tried again ({@link #retry()}). Thread-safe.
 */
public final class SessionJournal {
    public record State(String farmId, String taskId, int epoch, int loops, String anchor, int tick, int recoveries, boolean stopped, boolean localStop) {
        public static final State EMPTY = new State(null, null, 0, 0, null, 0, 0, false, false);

        /** STOP applies; {@code local} when the owner typed it in the terminal (only a local resume clears that one). */
        public State withStop(boolean stopped, boolean local) {
            return new State(farmId, taskId, epoch, loops, anchor, tick, recoveries, stopped, stopped && local);
        }
    }

    private final Path file;
    private State state = State.EMPTY;
    private String loadError;
    private State unsaved;
    private boolean writeLogged;

    public SessionJournal(Path file) {
        this.file = file;
    }

    public synchronized State load() {
        try {
            if (Files.exists(file)) {
                JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                state = new State(str(o, "farm"), str(o, "task"), num(o, "epoch"), num(o, "loops"), str(o, "anchor"), num(o, "tick"), num(o, "recoveries"),
                    o.has("stopped") && o.get("stopped").getAsBoolean(), o.has("localStop") && o.get("localStop").getAsBoolean());
            }
        } catch (IOException | RuntimeException e) {
            // A journal that is there but cannot be read may have held a STOP: fail closed, only a local resume clears it
            state = State.EMPTY.withStop(true, true);
            loadError = "journal_unreadable";
        }
        return state;
    }

    /** {@code journal_unreadable} when the file existed and could not be read at load, else null. */
    public synchronized String loadError() {
        return loadError;
    }

    /** True while a write has failed and not yet been made good. */
    public synchronized boolean unsaved() {
        return unsaved != null;
    }

    /** Tries the failed write again; returns true when nothing is left to write. */
    public synchronized boolean retry() {
        if (unsaved == null) return true;
        State s = unsaved;
        return save(s);
    }

    /** True once per failure series, so the caller logs the closed code {@code journal_write_failed} once. */
    public synchronized boolean takeWriteFailureToLog() {
        if (unsaved != null && !writeLogged) {
            writeLogged = true;
            return true;
        }
        return false;
    }

    public synchronized State get() {
        return state;
    }

    /**
     * Atomic read-modify-write: {@code change} is applied to the current state and the result is saved, under the journal's lock, so two
     * threads changing different fields (a STOP on the sync worker, a counter on the bot tick) cannot lose each other's update.
     * Returns the new state. {@code change} must be quick and must not call back into the journal from another thread.
     */
    public synchronized State update(java.util.function.UnaryOperator<State> change) {
        State next = change.apply(state);
        save(next);
        return next;
    }

    public synchronized boolean save(State s) {
        state = s;
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        if (s.farmId() != null) o.addProperty("farm", s.farmId());
        if (s.taskId() != null) o.addProperty("task", s.taskId());
        o.addProperty("epoch", s.epoch());
        o.addProperty("loops", s.loops());
        if (s.anchor() != null) o.addProperty("anchor", s.anchor());
        o.addProperty("tick", s.tick());
        o.addProperty("recoveries", s.recoveries());
        o.addProperty("stopped", s.stopped());
        o.addProperty("localStop", s.localStop());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(tmp);
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            unsaved = null;
            writeLogged = false;
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            unsaved = s; // kept: the caller logs a closed code and tries again
            return false;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static int num(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
    }
}
