package com.porter.qwertestash.pearl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.porter.qwertestash.manager.SafeJson;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The loader role of the plugin (PEARL-CONTRACT v1.0 rev 6, "The loader report"): looks at PearlPlus through a {@link Source} every half second,
 * follows the loads with a {@link LoadTracker}, and sends {@code POST /api/v1/bot/pearls} through a {@link Transport} every {@code intervalMs} (30 s by
 * default) and at once when a load changes state, the loader records {@code pearlGoneAt} / {@code requesterNearAt}, or a chamber's presence changes.
 * <p>
 * Trust: the {@code allow} list of the server's last answer is the only authority. Before the first answer the report carries no chambers. A load of a
 * pearl whose owner is not permitted ({@link LoadAuth#permits}: listed, and the uuid PearlPlus keyed the pearl by equals the owner-pinned one) is not
 * reported at all, and neither is any chamber of such an owner. The list is forgotten ten minutes after the last answer. A body that fails
 * {@link PearlReport#check} is never sent. Nothing logged here carries a name, an id or a coordinate.
 * <p>
 * Thread: the dashboard link's "dashboard-state" thread (one caller; reads of the game's caches are guarded, like the activity feed's). Not the tick thread.
 */
public final class PearlReporter {
    /** What PearlReporter reads from the bot. */
    public interface Source {
        /** The loader role is on (the setting, and PearlPlus's auto load). */
        boolean enabled();

        /** The bot's account name, or "" when not known. */
        String player();

        long now();

        /** Looks at the world once. {@code watching}: the pearl of the last load, whose entity and requester are asked about. */
        Reading read(List<LoadAuth.Allowed> allow, LoadTracker.@Nullable Target watching, long graceMs);
    }

    /**
     * @param snapshot     pearls, connection and settings ({@code busy} is the reporter's to set)
     * @param active       the load in progress now, for any owner (the reporter filters), or null
     * @param watchPresent the watched pearl's entity is seen; {@code watchInRange}: the bot is in range of it; {@code watchNear}: its owner is within 16 blocks
     * @param tries        the path tries of the load in progress
     */
    public record Reading(PearlReport.Snapshot snapshot, LoadTracker.@Nullable Target active, boolean watchPresent, boolean watchInRange, boolean watchNear, int tries) {
    }

    /** The result of a POST: the status code and the body; null when it could not be sent at all (no address or key). */
    public record Result(int code, String body) {
    }

    public interface Transport {
        @Nullable Result send(JsonObject body);

        /** Said once when the server refuses the loader (409): a line for the terminal, no names. */
        void refused(int code);
    }

    public static final long ALLOW_TTL_MS = 10 * 60_000L;
    private static final long MIN_GAP_MS = 2_000L, RETRY_MS = 30_000L, REFUSED_MS = 60_000L;
    /** The grace period (ms) before a pearl that is not seen counts as gone: set by the caller from the setting. */
    public volatile long graceMs = 45_000L;

    private final Source source;
    private final Transport transport;
    private final LoadTracker tracker = new LoadTracker();
    private static volatile List<LoadAuth.Allowed> shared = List.of();

    /** The approved travellers of the last server answer, for texts that name a requester (the activity feed): nobody before an answer. */
    public static List<LoadAuth.Allowed> sharedAllow() {
        return shared;
    }

    private List<LoadAuth.Allowed> allow = List.of();
    private long allowAt, lastSent, blockedUntil, intervalMs = PearlReport.Reply.DEFAULT_MS;
    private String lastSig = "";
    private final Map<String, long[]> since = new HashMap<>();
    private boolean refusedSaid;
    private int sent, refused;

    public PearlReporter(Source source, Transport transport) {
        this.source = source;
        this.transport = transport;
    }

    /** For the tests and the status command. */
    public LoadTracker tracker() {
        return tracker;
    }

    public List<LoadAuth.Allowed> allow() {
        return allow;
    }

    public int sent() {
        return sent;
    }

    public int refused() {
        return refused;
    }

    private void setAllow(List<LoadAuth.Allowed> list) {
        allow = list;
        shared = list;
    }

    private void forget() {
        setAllow(List.of());
        allowAt = 0;
        lastSent = 0;
        lastSig = "";
        since.clear();
        tracker.reset();
        intervalMs = PearlReport.Reply.DEFAULT_MS;
    }

    /** The next tick sends a report whatever the interval (after the settings changed). */
    public void sendSoon() {
        lastSent = 0;
        blockedUntil = 0;
    }

    /** Called about every half second. */
    public void tick() {
        if (!source.enabled()) {
            if (allowAt != 0 || lastSent != 0) forget();
            return;
        }
        String player = source.player();
        if (!PearlReport.PLAYER.matcher(player).matches()) return;
        long now = source.now();
        if (allowAt != 0 && now - allowAt > ALLOW_TTL_MS) {
            setAllow(List.of());
            allowAt = 0;
        }
        LoadTracker.Target watched = tracker.watching();
        if (watched != null && !LoadAuth.permits(allow, watched.ownerName(), watched.owner())) {
            // The approval is gone: the old load is not told any more (a null load goes out at once)
            tracker.reset();
            lastSig = "";
        }
        Reading r = source.read(allow, tracker.watching(), graceMs);
        LoadTracker.Target active = r.active();
        if (active != null && !LoadAuth.permits(allow, active.ownerName(), active.owner())) active = null;
        tracker.sample(new LoadTracker.Input(now, active, r.snapshot().connected(), r.watchPresent(), r.watchInRange(), r.watchNear(), r.tries()));

        PearlReport.Snapshot s = r.snapshot();
        PearlReport.Snapshot withBusy = new PearlReport.Snapshot(s.player(), s.now(), s.dropPearlAfterLoad(), s.temporaryMode(), s.connected(), s.inQueue(),
            s.inGameSince(), s.graceMs(), tracker.busy() || r.active() != null, s.playerNear(), s.pearls());
        JsonArray chambers = PearlReport.chambers(withBusy, allow, key -> sinceOf(key, now));
        String sig = signature(chambers, tracker.busy());
        boolean loadChanged = tracker.takeChanged();
        boolean due = lastSent == 0 || now - lastSent >= intervalMs || loadChanged || !sig.equals(lastSig);
        if (!due || now < blockedUntil || now - lastSent < MIN_GAP_MS) return;

        JsonObject body = PearlReport.body(withBusy, chambers, tracker.load());
        if (PearlReport.check(body) != null) {
            // Our own bug: never sent (the server would answer 400). Said once per change at debug level by the caller's log, nothing here
            blockedUntil = now + RETRY_MS;
            return;
        }
        Result result;
        try {
            result = transport.send(body);
        } catch (RuntimeException e) {
            result = null;
        }
        lastSent = now;
        lastSig = sig;
        if (result == null) {
            blockedUntil = now + RETRY_MS;
            return;
        }
        if (result.code() == 200) {
            sent++;
            blockedUntil = 0;
            refusedSaid = false;
            try {
                JsonElement e = SafeJson.parse(result.body());
                PearlReport.Reply reply = PearlReport.Reply.parse(e instanceof JsonObject o ? o : null);
                setAllow(reply.allow());
                allowAt = now;
                intervalMs = reply.intervalMs();
            } catch (RuntimeException e) {
                // A 200 without a readable list: the old list stays until it expires
            }
        }
        else if (result.code() == 409) {
            refused++;
            blockedUntil = now + REFUSED_MS;
            // The server will not use this report: what we believed about approval is gone
            setAllow(List.of());
            allowAt = 0;
            if (!refusedSaid) {
                refusedSaid = true;
                transport.refused(409);
            }
        }
        else blockedUntil = now + RETRY_MS;
    }

    private long sinceOf(String key, long now) {
        int bar = key.lastIndexOf('|');
        String id = key.substring(0, bar), presence = key.substring(bar + 1);
        long[] e = since.get(id);
        String known = knownPresence.get(id);
        if (e == null || known == null || !known.equals(presence)) {
            e = new long[] {now};
            since.put(id, e);
            knownPresence.put(id, presence);
        }
        return e[0];
    }

    private final Map<String, String> knownPresence = new HashMap<>();

    private static String signature(JsonArray chambers, boolean busy) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : chambers) {
            JsonObject c = e.getAsJsonObject();
            sb.append(c.get("id").getAsString()).append(':').append(c.get("presence").getAsString()).append(':').append(c.get("ownerKnown").getAsBoolean()).append(';');
        }
        return sb.append(busy).toString();
    }
}
