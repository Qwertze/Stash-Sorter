package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The life of one order on the bot: claimed, planned, shulker by shulker built or fetched and delivered, then done,
 * failed or cancelled; paused while the bot cannot work, parked when it goes offline, saved so a restart carries on the
 * same plan. A port of the decisions of the addon's {@code OrderRunner} with every game object behind {@link Host}, so
 * the whole machine runs (and is tested) without a world. All of it is for the bot tick thread only.
 * <p>
 * The host does the walking, opening, clicking and placing; this decides what is next from what the host reports, and
 * what to tell the server ({@link #report()}).
 */
public final class OrderFlow {
    /** What the flow needs from the world. */
    public interface Host {
        /** The bot may work now: online, in the world, not held up by anything. */
        boolean gate();

        /** Containers last seen before a stop are still being read again, so what the stash holds is not known yet. */
        boolean rereading();

        /** How many containers are waiting to be read again. */
        int staleCount();

        boolean deliverySet();

        /**
         * Why the delivery container cannot be used (it could not be reached or opened, it is in another dimension), or null.
         * Set by delivery failures only, never by a read of the container.
         */
        @Nullable String deliveryProblem();

        /** Why the bot may not work now (shown as the phase), for a gate that is closed. */
        default String pauseReason() {
            return "the bot can't work now";
        }

        /** Whether the machinery that carries the order out is running at all (else parking takes effect at once). */
        default boolean machineRunning() {
            return true;
        }

        /** The carry slot holding the finished shulker of this unit, or -1. */
        int finishedSlot(Unit unit);

        /** What the finished shulker in that carry slot holds, by item key. */
        Map<String, Integer> contentOf(Unit unit, int slot);

        /** A shulker for this (Shulker or Empty) unit exists in the stash to be fetched. */
        boolean canFetch(Unit unit);

        /** Plans a fresh order from what the stash holds now. */
        StockPlanner.Plan plan(Order order);

        /** Whether the empty shulkers in the stash are enough for the units still to do; null if so, else why not. */
        @Nullable String checkEmpties(List<Unit> remaining);

        /** Writes the plan and progress, so a restart carries on. */
        void save(Saved saved);

        /** The order is over (reported): the saved state goes. */
        void clearSaved(String id);

        /** The saved plan of this order, or null. */
        @Nullable Saved saved(String id);

        /** The id of any saved order. */
        @Nullable String savedId();

        void say(String text);
    }

    public sealed interface Intent permits Idle, Build, Fetch, Deliver {
    }

    /** Nothing for the host to do for the order now. */
    public record Idle() implements Intent {
    }

    /** Build this bin: gather its loads, fill a shulker. The host reports {@link #built} or {@link #buildFailed}. */
    public record Build(Unit unit) implements Intent {
    }

    /** Take the shulker this unit asks for out of its container. */
    public record Fetch(Unit unit) implements Intent {
    }

    /** Walk to the delivery container and put the finished shulker in; {@code verify}: only look whether it is there. */
    public record Deliver(Unit unit, int slot, boolean verify) implements Intent {
    }

    public enum State { Running, Delivered, Failed, Cancelled }

    /** A snapshot of the order for the server. */
    public record Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered) {
        public boolean terminal() {
            return !status.equals("in_progress");
        }
    }

    /** The plan and progress as saved with the stash, in plain lists so it survives any JSON library. */
    public static final class Saved {
        public String id;
        public List<String[]> lines = new ArrayList<>();
        public List<UnitSave> units = new ArrayList<>();
        public int done;
        /** The unit being put into the delivery container (or -1), and what the container held before: a restart looks first. */
        public int deliveringIndex = -1;
        public int deliveringBase;
        public int shulkersDelivered;
        public Map<String, Integer> delivered = new LinkedHashMap<>();

        public static final class UnitSave {
            /** 0 Shulker, 1 Empty, 2 Bin. */
            public int kind;
            public String key = "";
            public List<String> lotKeys = new ArrayList<>();
            public List<String> lotVariants = new ArrayList<>();
            public List<Integer> lotCounts = new ArrayList<>();
        }
    }

    public static final long PAUSE_TIMEOUT_MS = 10 * 60 * 1000L;
    public static final long REREAD_TIMEOUT_MS = 10 * 60 * 1000L;
    public static final int MAX_FULL_TRIES = 2;
    public static final int MAX_STARTS = 8;
    public static final int MAX_BEGINS = 3;

    private final Host host;
    private final LongSupplier clock;

    private @Nullable Order order;
    private State state = State.Running;
    private final List<Unit> units = new ArrayList<>();
    private int index;
    private int shulkersDelivered;
    private final Map<String, Integer> delivered = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private String message = "";
    private String phase = "starting";
    private boolean cancelRequested, parked, planned, full;
    private long pausedSince, offeredAt, rereadProgressAt;
    private int lastStale = Integer.MAX_VALUE;
    private int skips;

    public OrderFlow(Host host) {
        this(host, System::currentTimeMillis);
    }

    public OrderFlow(Host host, LongSupplier clock) {
        this.host = host;
        this.clock = clock;
    }

    // State seen from outside

    /** An order is being worked on and planned: it comes before the sorter's own work. */
    public boolean planned() {
        return running() && planned;
    }

    public boolean running() {
        return order != null && state == State.Running;
    }

    /** There is an order, finished or not, that the server has not been told the end of yet. */
    public boolean holdsOrder() {
        return order != null;
    }

    public @Nullable String orderId() {
        return order == null ? null : order.id;
    }

    /** The server asked for this order to be stopped and it has not been yet. */
    public boolean cancelRequested() {
        return cancelRequested && running();
    }

    public State state() {
        return state;
    }

    public @Nullable Unit current() {
        return running() && index < units.size() ? units.get(index) : null;
    }

    public List<Unit> units() {
        return units;
    }

    public int index() {
        return index;
    }

    public String phase() {
        return phase;
    }

    public void setPhase(String p) {
        phase = p;
    }

    /** What the server asked for, per line key. */
    public Map<String, Integer> requested() {
        return order == null ? Map.of() : order.requested();
    }

    /** One line about the order in hand for `stash orders`; no side effects (unlike {@link #report}). */
    public @Nullable String summary() {
        Order o = order;
        if (o == null) return null;
        String id = OrderDriver.shortId(o.id);
        return state == State.Running ? id + ": " + phase + " (" + Math.min(index, units.size()) + "/" + units.size() + " shulkers)" : id + ": " + state.name().toLowerCase();
    }

    /** The title of the order's card in the dashboard's Now-doing list: no positions. */
    public @Nullable String activityTitle() {
        Order o = order;
        if (o == null) return null;
        String id = OrderDriver.shortId(o.id);
        return state == State.Running ? "Order " + id + ": " + phase : "Order " + id + " " + state.name().toLowerCase();
    }

    public @Nullable String info() {
        return order == null ? null : state == State.Running ? "order " + index + "/" + units.size() : state.name().toLowerCase();
    }

    /**
     * A shulker that was put in the delivery container counts as soon as it is seen there, not only when the next step is
     * decided: a cancel, a stop or a restart right after must not lose it (a resume would deliver it twice).
     */
    private void settleConfirmed() {
        if (order == null || state != State.Running || !planned || index >= units.size()) return;
        Unit u = units.get(index);
        if (!u.have || !u.confirmed || host.finishedSlot(u) != -1) return;
        for (var e : u.content.entrySet()) delivered.merge(e.getKey(), e.getValue(), Integer::sum);
        shulkersDelivered++;
        advance();
    }

    /** Forgets the order here (leaving the world, parking). The saved state is kept for a resume. */
    public void reset() {
        settleConfirmed();
        order = null;
        units.clear();
        index = 0;
        shulkersDelivered = 0;
        delivered.clear();
        problems.clear();
        cancelRequested = parked = planned = full = false;
        pausedSince = 0;
        state = State.Running;
        message = "";
        phase = "starting";
    }

    // Taking an order

    /**
     * @return null when the order is taken, else why not. While containers are still being read again the plan waits for
     *         them, and nothing is rejected for what is not known yet.
     */
    public @Nullable String offer(Order o) {
        if (order != null) return order.id.equals(o.id) ? null : "already working on another order";
        if (!host.deliverySet()) return "no delivery container is set";

        order = o;
        state = State.Running;
        units.clear();
        index = 0;
        shulkersDelivered = 0;
        delivered.clear();
        problems.clear();
        message = "";
        cancelRequested = parked = planned = full = false;
        pausedSince = 0;
        offeredAt = clock.getAsLong();
        rereadProgressAt = offeredAt;
        lastStale = Integer.MAX_VALUE;
        phase = "re-reading containers";

        if (!host.rereading()) {
            String why = completePlan();
            if (why != null) {
                reset();
                return why;
            }
        }
        return null;
    }

    /** Plans from what the stash holds now (or takes the saved plan of a resume), and saves it. Null when planned, else why not. */
    private @Nullable String completePlan() {
        Order o = order;
        if (o == null) return "no order";

        List<Unit> plan = new ArrayList<>();
        int start = 0;
        Saved saved = host.saved(o.id);
        List<String> notes = new ArrayList<>();
        boolean resumed = saved != null && !saved.units.isEmpty();

        if (resumed) {
            for (Saved.UnitSave p : saved.units) {
                List<Order.Lot> lots = new ArrayList<>();
                for (int i = 0; i < p.lotKeys.size(); i++) {
                    String v = i < p.lotVariants.size() ? p.lotVariants.get(i) : "";
                    lots.add(new Order.Lot(p.lotKeys.get(i), v == null || v.isEmpty() ? null : v, p.lotCounts.get(i)));
                }
                plan.add(new Unit(p.kind == 0 ? Unit.Kind.Shulker : p.kind == 1 ? Unit.Kind.Empty : Unit.Kind.Bin, p.key, lots));
            }
            start = Math.min(saved.done, plan.size());
            // A shulker may have gone into the delivery container just before the bot went offline: the container is looked at
            // before the unit is built again
            if (saved.deliveringIndex == start && start < plan.size()) {
                Unit u = plan.get(start);
                u.have = true;
                u.base = saved.deliveringBase;
                u.content = u.kind == Unit.Kind.Bin ? BinTracker.lotTotals(u.lots) : Map.of(u.key, 1);
            }
            // What was delivered before counts even if the rest cannot be done now
            shulkersDelivered = saved.shulkersDelivered;
            delivered.putAll(saved.delivered);
        }
        else {
            StockPlanner.Plan fresh = host.plan(o);
            if (fresh.failure() != null) return fresh.failure();
            plan.addAll(fresh.units());
            notes.addAll(fresh.notes());
        }

        String why = host.checkEmpties(new ArrayList<>(plan.subList(start, plan.size())));
        if (why != null) return why;

        units.clear();
        units.addAll(plan);
        index = start;
        problems.addAll(notes);
        planned = true;
        phase = "starting";

        if (!resumed) {
            Saved s = new Saved();
            s.id = o.id;
            for (Order.Line l : o.lines) s.lines.add(new String[] {l.type(), l.key(), String.valueOf(l.count())});
            for (Unit u : plan) {
                Saved.UnitSave p = new Saved.UnitSave();
                p.kind = u.kind == Unit.Kind.Shulker ? 0 : u.kind == Unit.Kind.Empty ? 1 : 2;
                p.key = u.key;
                for (Order.Lot lot : u.lots) {
                    p.lotKeys.add(lot.key());
                    p.lotVariants.add(lot.variant() == null ? "" : lot.variant());
                    p.lotCounts.add(lot.count());
                }
                s.units.add(p);
            }
            host.save(s);
        }
        host.say("Taking order " + o.id + ": " + (units.size() - index) + " shulkers to deliver" + (index > 0 ? ", " + index + " already done." : "."));
        return null;
    }

    /** The server wants this order stopped. It is acted on at the next safe point. */
    public boolean cancel(String id) {
        if (order == null || !order.id.equals(id)) return false;
        if (state == State.Running) cancelRequested = true;
        return true;
    }

    /** Stops working on the order but keeps it resumable: the server still holds it, and a later claim carries on. */
    public void park() {
        if (!running()) return;
        // Nothing is running to notice the flag: forgotten here at once (the plan stays saved)
        if (!host.machineRunning()) reset();
        else parked = true;
    }

    /** The server has the end of the order: it can be forgotten, and the next one claimed. */
    public void ack(String id) {
        if (order == null || !order.id.equals(id) || state == State.Running) return;
        host.clearSaved(id);
        reset();
    }

    /** The saved order the server should be asked about again, or null. */
    public @Nullable String resumeId() {
        return host.savedId();
    }

    /** Drops saved state of an order the server no longer holds for this bot. */
    public void dropSaved(@Nullable String keepId) {
        String saved = host.savedId();
        if (saved != null && !saved.equals(keepId)) host.clearSaved(saved);
    }

    public @Nullable Report report() {
        if (order == null) return null;
        settleConfirmed();
        long now = clock.getAsLong();
        // The wait for the re-read only runs out when it has stopped making progress: a big rescan can take long
        if (state == State.Running && !planned) {
            int stale = host.staleCount();
            if (stale < lastStale) {
                lastStale = stale;
                rereadProgressAt = now;
            }
            if (now - rereadProgressAt > REREAD_TIMEOUT_MS) finish(State.Failed, "the containers could not be read again in time");
        }
        if (state == State.Running) {
            if (!host.gate()) {
                if (pausedSince == 0) pausedSince = now;
                else if (now - pausedSince > PAUSE_TIMEOUT_MS) finish(State.Failed, "the bot was held up for too long");
                else phase = "paused: " + host.pauseReason();
            }
            else pausedSince = 0;
        }
        String status = switch (state) {
            case Running -> "in_progress";
            case Delivered -> "delivered";
            case Failed -> "failed";
            case Cancelled -> "cancelled";
        };
        String shown = state == State.Running && host.rereading() ? "re-reading containers" : phase;
        return new Report(order.id, status, shown, Math.min(index, units.size()), units.size(), message, shulkersDelivered, new LinkedHashMap<>(delivered));
    }

    // Reports from the host

    /** The host has read what the delivery container holds and is about to put the unit's shulker in: saved, so a restart can look first. */
    public void deliveryStarted(Unit u) {
        Order o = order;
        Saved s = o == null ? null : host.saved(o.id);
        if (s == null || index >= units.size() || units.get(index) != u) return;
        s.deliveringIndex = index;
        s.deliveringBase = u.base;
        host.save(s);
    }

    /** The delivery container had no room for the shulker. */
    public void deliveryFull() {
        full = true;
    }

    /** The shulker was seen in the delivery container after it left the inventory. */
    public void confirmed() {
        Unit u = current();
        if (u != null && u.have) u.confirmed = true;
    }

    /** A bin is about to be built. @return false after too many tries */
    public boolean noteBuildStart(Unit u) {
        return ++u.begins <= MAX_BEGINS;
    }

    /** The bin's finished shulker exists. */
    public void built(Unit u) {
        u.built = true;
    }

    /**
     * The bin could not be built. A base that could not be taken is tried again; items missing from where they were saved
     * (the containers were read again since) get one fresh look before the unit is given up on.
     * @return true if the unit will be planned again (the host should try once more), false if it was skipped
     */
    public boolean buildFailed(Unit u, String reason, boolean retryable) {
        if (retryable) return true;
        // Containers still to be read again may hold what is missing: wait for them
        if (host.rereading()) {
            u.begins = 0;
            phase = "re-reading containers";
            return true;
        }
        if (!u.replanned) {
            u.replanned = true;
            u.begins = 0;
            problems.add("replanned a shulker: " + reason);
            return true;
        }
        skip(reason);
        return false;
    }

    /** Gives the current unit up with a note, and goes on to the next. */
    public void skip(String why) {
        skips++;
        problems.add(why);
        advance();
    }

    // The next step

    public Intent next() {
        if (!running()) return new Idle();
        settleConfirmed();
        if (parked) {
            reset();
            return new Idle();
        }
        if (cancelRequested) {
            finish(State.Cancelled, "cancelled");
            return new Idle();
        }
        if (!host.gate()) {
            long now = clock.getAsLong();
            if (pausedSince == 0) pausedSince = now;
            else if (now - pausedSince > PAUSE_TIMEOUT_MS) {
                finish(State.Failed, "the bot was held up for too long");
                return new Idle();
            }
            phase = "paused: " + host.pauseReason();
            return new Idle();
        }
        pausedSince = 0;

        // Not planned yet: waits until the stash is read again, then plans from what is really there
        if (!planned) {
            if (host.rereading()) {
                phase = "re-reading containers";
                return new Idle();
            }
            String why = completePlan();
            if (why != null) {
                finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, why);
                return new Idle();
            }
        }

        skips = 0;
        for (int guard = 0; guard < 64; guard++) {
            // Units that cannot be done are skipped a few per tick, not all at once
            if (skips > 4) return new Idle();
            if (index >= units.size()) {
                finishAll();
                return new Idle();
            }

            String problem = host.deliverySet() ? host.deliveryProblem() : "the delivery container is gone";
            if (problem != null) {
                finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, problem);
                return new Idle();
            }

            Unit u = units.get(index);
            int slot = host.finishedSlot(u);

            if (slot != -1) {
                if (!u.have) {
                    u.have = true;
                    u.content = new HashMap<>(host.contentOf(u, slot));
                }
                // Back in the inventory: whatever was seen in the container was not it
                u.confirmed = false;
                u.verifying = false;
                if (full) {
                    full = false;
                    u.fullTries++;
                }
                if (u.fullTries >= MAX_FULL_TRIES) {
                    finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, "delivery container full (" + shulkersDelivered + "/" + units.size() + ")");
                    return new Idle();
                }
                if (++u.starts > MAX_STARTS) {
                    finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, "couldn't put the shulker into the delivery container (" + shulkersDelivered + "/" + units.size() + " delivered)");
                    return new Idle();
                }
                phase = "delivering " + (index + 1) + "/" + units.size();
                return new Deliver(u, slot, false);
            }

            if (u.have) {
                // It left the inventory: it counts only if the delivery container was seen to hold it
                if (u.confirmed) {
                    for (var e : u.content.entrySet()) delivered.merge(e.getKey(), e.getValue(), Integer::sum);
                    shulkersDelivered++;
                    advance();
                }
                else if (!u.verified) {
                    // Not seen in the container yet: look once more, in a freshly opened window, before giving up on it
                    u.verified = true;
                    u.verifying = true;
                    phase = "checking delivery " + (index + 1) + "/" + units.size();
                    return new Deliver(u, -1, true);
                }
                else skip("a shulker left my inventory but wasn't seen in the delivery container");
                continue;
            }

            if (u.kind == Unit.Kind.Bin) {
                if (u.built) {
                    skip("lost a finished shulker");
                    continue;
                }
                phase = "building " + (index + 1) + "/" + units.size();
                return new Build(u);
            }

            if (u.fetches >= 2) {
                skip("couldn't take the shulker out of the stash");
                continue;
            }
            if (!host.canFetch(u)) {
                skip(u.kind == Unit.Kind.Shulker ? "a packed shulker is no longer in the stash" : "no empty shulker of that kind is left");
                continue;
            }
            u.fetches++;
            phase = "fetching " + (index + 1) + "/" + units.size();
            return new Fetch(u);
        }
        return new Idle();
    }

    private void advance() {
        index++;
        Order o = order;
        Saved s = o == null ? null : host.saved(o.id);
        if (s != null) {
            s.done = index;
            s.deliveringIndex = -1;
            s.shulkersDelivered = shulkersDelivered;
            s.delivered.clear();
            s.delivered.putAll(delivered);
            host.save(s);
        }
    }

    // Ending

    private void finishAll() {
        Order o = order;
        if (o == null) return;
        List<String> shortLines = new ArrayList<>();
        for (var e : o.requested().entrySet()) {
            int got = delivered.getOrDefault(e.getKey(), 0);
            if (got < e.getValue()) shortLines.add(e.getKey() + " " + got + "/" + e.getValue());
        }

        String note = shortLines.isEmpty() ? "" : "delivered less than asked: " + String.join(", ", shortLines.size() > 6 ? shortLines.subList(0, 6) : shortLines) + (shortLines.size() > 6 ? ", ..." : "");
        if (!problems.isEmpty()) note += (note.isEmpty() ? "" : "; ") + String.join(", ", problems.size() > 3 ? problems.subList(0, 3) : problems);
        boolean anything = shulkersDelivered > 0;
        finish(anything ? State.Delivered : State.Failed, anything ? note : (note.isEmpty() ? "nothing could be delivered" : "nothing could be delivered: " + note));
    }

    /** Ends the order here with this outcome; the server is told by the next {@link #report()}. */
    public void finish(State end, String text) {
        if (order == null || state != State.Running) return;
        settleConfirmed();
        state = end;
        message = text;
        phase = end.name().toLowerCase();
        host.say("Order " + order.id + " " + phase + (text.isEmpty() ? "." : ": " + text));
    }
}
