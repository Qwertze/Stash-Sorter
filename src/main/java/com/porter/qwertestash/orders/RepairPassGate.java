package com.porter.qwertestash.orders;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * When a repair pass may start and what the heartbeat says about it (REPAIR-CONTRACT v2, "The pass" and "Heartbeat"). Pure; the sorter fills in the facts.
 */
public final class RepairPassGate {
    private RepairPassGate() {
    }

    public enum State { OFF, IDLE, WAITING, RUNNING, YIELDING, BLOCKED }

    /** The reasons of {@code repairPass.why}. */
    public static final String DISABLED = "disabled", NO_SETTINGS = "no_settings", RESERVE = "reserve", NO_CANDIDATES = "no_candidates", ORDER = "order", PLAYER_NEAR = "player_near",
        FOREIGN = "foreign", SORTER_OFF = "sorter_off", OFFLINE = "offline", COOLDOWN = "cooldown", INVENTORY_FULL = "inventory_full", KIT_CONFLICT = "kit_conflict";

    public record Decision(State state, @Nullable String why, boolean start) {
        static Decision go() {
            return new Decision(State.IDLE, null, true);
        }

        static Decision wait(String why) {
            return new Decision(State.WAITING, why, false);
        }
    }

    /**
     * @param localOn        the bot setting {@code repair-pass}
     * @param settingsKnown  settings are cached (a manual run with none uses the defaults)
     * @param enabled        the settings' {@code enabled}; {@code auto} their automatic switch
     * @param manual         a manual run is queued (a website {@code repairRun} or the local command)
     * @param online         in game and not in the queue
     * @param sorterOn       the sorter is running
     * @param orderBusy      an order is held or resumable, or the last heartbeat reply said orders are pending
     * @param foreign        a foreign module is working
     * @param idle           the sorter is resting: no job, nothing carried or stuck
     * @param playerNear     a player is within 8 blocks
     * @param quietElapsed   the quiet time has elapsed
     * @param kitConflict    the needs-repair chest is also kit-reserved
     * @param hasCandidates  at least one mend candidate whose bottlesFor fits usable
     * @param anyMend        there are mend candidates at all (they may not fit usable)
     * @param inventoryFull  no room to carry a batch
     * @param sinceLastAutoMs time since the last auto pass ended, and {@code everyMs} the minimum gap
     */
    public record Facts(boolean localOn, boolean settingsKnown, boolean enabled, boolean auto, boolean manual, boolean online, boolean sorterOn, boolean orderBusy, boolean foreign,
                        boolean idle, boolean playerNear, boolean quietElapsed, boolean kitConflict, boolean hasCandidates, boolean anyMend, boolean inventoryFull,
                        long sinceLastAutoMs, long everyMs) {
    }

    public static Decision decide(Facts f) {
        if (!f.localOn()) return new Decision(State.OFF, null, false);
        if (f.settingsKnown() && !f.enabled()) return new Decision(State.BLOCKED, DISABLED, false);
        if (!f.settingsKnown() && !f.manual()) return new Decision(State.BLOCKED, NO_SETTINGS, false);
        if (f.kitConflict()) return new Decision(State.BLOCKED, KIT_CONFLICT, false);
        if (!f.manual() && !f.auto()) return new Decision(State.IDLE, null, false);
        if (!f.online()) return Decision.wait(OFFLINE);
        if (!f.sorterOn()) return Decision.wait(SORTER_OFF);
        if (f.orderBusy()) return Decision.wait(ORDER);
        if (f.foreign()) return Decision.wait(FOREIGN);
        if (!f.hasCandidates()) return f.anyMend() ? Decision.wait(RESERVE) : new Decision(State.IDLE, NO_CANDIDATES, false);
        if (f.playerNear()) return Decision.wait(PLAYER_NEAR);
        if (!f.idle() || !f.quietElapsed()) return new Decision(State.WAITING, null, false);
        if (f.inventoryFull()) return new Decision(State.BLOCKED, INVENTORY_FULL, false);
        if (!f.manual() && f.sinceLastAutoMs() < f.everyMs()) return Decision.wait(COOLDOWN);
        return Decision.go();
    }

    /** How long a queued manual run is kept (a run asked for while the bot was busy for longer is dropped). */
    public static final long MANUAL_TTL_MS = 10 * 60_000L;

    /**
     * Whether a queued manual run is still wanted: it expires after {@link #MANUAL_TTL_MS}, and is dropped while repair is disabled on the website
     * ({@code enabledKnownFalse}).
     */
    public static boolean manualLive(boolean pending, long askedAt, long now, boolean enabledKnownFalse) {
        return pending && !enabledKnownFalse && now - askedAt <= MANUAL_TTL_MS;
    }

    /**
     * The local limits that also hold for manual runs: the least gap since the last pass for a manual run, and the most passes an hour (0 or less: no limit).
     */
    public static boolean limitsOk(boolean manual, long sinceLastPassMs, int minGapManualMinutes, int startedLastHour, int maxPerHour) {
        if (manual && sinceLastPassMs < Math.max(0, minGapManualMinutes) * 60_000L) return false;
        return maxPerHour <= 0 || startedLastHour < maxPerHour;
    }

    /** The reserve a pass keeps: the website's, or the local minimum when that is higher. */
    public static int reserve(int settingsReserve, int localMinimum) {
        return Math.max(settingsReserve, Math.max(0, localMinimum));
    }

    /** The {@code repairPass} object of the heartbeat. Whole numbers only, no positions. */
    public static JsonObject beat(State state, @Nullable String why, long pass, @Nullable String trigger, int done, int total, boolean progress, int mend, int mendBottles,
                                  int needsRepair, int inShulkers, int inBoxes, int boxes, long stash, int reserve, long usable, long runDone, long stopDone, @Nullable String reportHash) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("state", state.name().toLowerCase(java.util.Locale.ROOT));
        if (why == null || (state != State.WAITING && state != State.BLOCKED && state != State.IDLE)) o.add("why", JsonNull.INSTANCE);
        else o.addProperty("why", why);
        o.addProperty("pass", Math.max(0, pass));
        if (trigger == null) o.add("trigger", JsonNull.INSTANCE);
        else o.addProperty("trigger", trigger);
        if (progress) {
            JsonObject p = new JsonObject();
            p.addProperty("done", Math.max(0, done));
            p.addProperty("total", Math.max(0, total));
            o.add("progress", p);
        }
        else o.add("progress", JsonNull.INSTANCE);
        JsonObject c = new JsonObject();
        c.addProperty("mend", Math.max(0, mend));
        c.addProperty("mendBottles", Math.max(0, mendBottles));
        c.addProperty("needsRepair", Math.max(0, needsRepair));
        c.addProperty("inShulkers", Math.max(0, inShulkers));
        c.addProperty("inBoxes", Math.max(0, inBoxes));
        c.addProperty("boxes", Math.max(0, boxes));
        o.add("candidates", c);
        JsonObject b = new JsonObject();
        b.addProperty("stash", Math.max(0, stash));
        b.addProperty("reserve", Math.max(0, reserve));
        b.addProperty("usable", Math.max(0, usable));
        o.add("bottles", b);
        // Absent keeps what the server has: a restarted bot has acknowledged nothing yet and must not lower an id
        if (runDone > 0) o.addProperty("runDone", runDone);
        if (stopDone > 0) o.addProperty("stopDone", stopDone);
        if (reportHash == null) o.add("reportHash", JsonNull.INSTANCE);
        else o.addProperty("reportHash", reportHash);
        return o;
    }
}
