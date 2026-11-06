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

        /**
         * Why the shulker of this unit can't be named now (no anvil set, the block there is not an anvil, too few XP levels),
         * or null when the anvil can be tried. A host that can't name shulkers says so.
         */
        default @Nullable String namingProblem(Unit unit) {
            return "this bot can't name shulkers";
        }

        /** The custom name the shulker in this carry slot has, or null. */
        default @Nullable String nameOn(Unit unit, int slot) {
            return null;
        }

        /**
         * Why a shulker may not be put down now (shulker handling is paused), or null. Only building a bin puts one down:
         * this holds Build steps alone, while delivering, naming and fetching shulkers already made go on.
         */
        default @Nullable String buildPauseReason() {
            return null;
        }

        /**
         * An order is about to be planned (claimed, or resumed from its saved plan): what the host gave up on for now
         * (containers stalled, trips that came back empty) is forgotten, so the plan and the run see the same sources.
         */
        default void planning() {
        }

        /** Crafting is on (and this host can craft): units without an empty shulker get a Craft step first. */
        default boolean craftingOn() {
            return false;
        }

        /** An empty shulker for this bin is at hand (carried, or in the stash to be fetched). */
        default boolean emptyAvailable(Unit unit) {
            return true;
        }

        /** Why a shulker box can't be crafted for this unit now (no table, a cap, too few inputs), or null when it can be tried. */
        default @Nullable String craftProblem(Unit unit) {
            return "this bot can't craft";
        }

        /** How many shulker boxes to craft on this trip, for this unit and the ones after it that will need one too (at least 1). */
        default int craftCount(Unit unit) {
            return 1;
        }
    }

    public sealed interface Intent permits Idle, Build, Fetch, Name, Deliver, Craft {
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

    /**
     * Walk to the anvil and give the finished shulker in this carry slot its name, then put it back in the same slot. The host
     * reports {@link #named} or {@link #nameFailed}; whatever happens, the shulker is delivered next.
     */
    public record Name(Unit unit, int slot, String name) implements Intent {
    }

    /** Walk to the delivery container and put the finished shulker in; {@code verify}: only look whether it is there. */
    public record Deliver(Unit unit, int slot, boolean verify) implements Intent {
    }

    /**
     * Craft {@code count} plain shulker boxes (this unit has no empty shulker): gather the inputs, make them at the crafting
     * table, put the rest back. The host reports {@link #craftStarted}, then {@link #crafted} or {@link #craftFailed}.
     */
    public record Craft(Unit unit, int count) implements Intent {
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
        /** Shulkers delivered without the name the order wanted, and why (each reason once). */
        public int unnamed;
        public List<String> unnamedReasons = new ArrayList<>();
        /** Shulker boxes crafted for this order so far: they count towards its cap (1.8.0; 0 in older saves). */
        public int crafted;

        public static final class UnitSave {
            /** 0 Shulker, 1 Empty, 2 Bin. */
            public int kind;
            public String key = "";
            public List<String> lotKeys = new ArrayList<>();
            public List<String> lotVariants = new ArrayList<>();
            public List<Integer> lotCounts = new ArrayList<>();
            /** The name the order wants on it ("" for none), and how naming went (kept for the unit being delivered). */
            public String name = "";
            public boolean nameDone;
            public String nameFailure = "";
            public String namedKey = "";
        }
    }

    public static final long PAUSE_TIMEOUT_MS = 10 * 60 * 1000L;
    public static final long REREAD_TIMEOUT_MS = 10 * 60 * 1000L;
    public static final int MAX_FULL_TRIES = 2;
    public static final int MAX_STARTS = 8;
    public static final int MAX_BEGINS = 3;
    /** Times the anvil is tried for one shulker before it is delivered unnamed (a try cut short by the server, say). */
    public static final int MAX_NAME_TRIES = 2;
    /** Craft trips for one unit before it is given up. */
    public static final int MAX_CRAFT_TRIES = 2;
    public static final String PLAIN_SHULKER = "minecraft:shulker_box";
    /** What a unit given up for want of an empty shulker says when crafting one did not work either. */
    public static final String NO_EMPTY_CRAFT = "no empty shulker; couldn't craft one: ";

    private final Host host;
    private final LongSupplier clock;

    private @Nullable Order order;
    private State state = State.Running;
    private final List<Unit> units = new ArrayList<>();
    private int index;
    private int shulkersDelivered;
    private final Map<String, Integer> delivered = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private int unnamed;
    private final List<String> unnamedReasons = new ArrayList<>();
    private int crafted;
    private String message = "";
    private String phase = "starting";
    private boolean cancelRequested, parked, planned, full;
    private long pausedSince, buildPausedSince, offeredAt, rereadProgressAt;
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

    /** The order is to be parked (the bot goes offline, orders were turned off) and has not been yet. */
    public boolean parkRequested() {
        return parked && running();
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
        countUnnamed(u);
        advance();
    }

    /** A delivered shulker that should have had a name and has none counts towards the order's one note about it. */
    private void countUnnamed(Unit u) {
        if (u.name == null || u.nameDone) return;
        unnamed++;
        String why = u.nameFailure == null ? "naming didn't finish" : u.nameFailure;
        if (!unnamedReasons.contains(why)) unnamedReasons.add(why);
    }

    /** The order's note about shulkers delivered without their names, or null. */
    public @Nullable String unnamedNote() {
        return ShulkerNames.unnamedNote(unnamed, unnamedReasons);
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
        unnamed = 0;
        unnamedReasons.clear();
        crafted = 0;
        cancelRequested = parked = planned = full = false;
        pausedSince = buildPausedSince = 0;
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
        unnamed = 0;
        unnamedReasons.clear();
        crafted = 0;
        message = "";
        cancelRequested = parked = planned = full = false;
        pausedSince = buildPausedSince = 0;
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
        host.planning();

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
                Unit u = new Unit(p.kind == 0 ? Unit.Kind.Shulker : p.kind == 1 ? Unit.Kind.Empty : Unit.Kind.Bin, p.key, lots);
                // The names were saved with the plan; a plan saved before names existed takes them from the order
                u.name = p.name != null && !p.name.isBlank() ? p.name : o.names == null ? null : o.names.nameFor(plan.size());
                u.nameDone = p.nameDone;
                u.nameFailure = p.nameFailure == null || p.nameFailure.isEmpty() ? null : p.nameFailure;
                u.namedKey = p.namedKey == null || p.namedKey.isEmpty() ? null : p.namedKey;
                plan.add(u);
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
            unnamed = saved.unnamed;
            if (saved.unnamedReasons != null) unnamedReasons.addAll(saved.unnamedReasons);
            crafted = Math.max(0, saved.crafted);
        }
        else {
            StockPlanner.Plan fresh = host.plan(o);
            if (fresh.failure() != null) return fresh.failure();
            plan.addAll(fresh.units());
            notes.addAll(fresh.notes());
            // The i-th shulker of the plan (shulker lines, then empty shulkers, then bins) gets the i-th name
            if (o.names != null) for (int i = 0; i < plan.size(); i++) plan.get(i).name = o.names.nameFor(i);
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
                p.name = u.name == null ? "" : u.name;
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
                else if (now - pausedSince > PAUSE_TIMEOUT_MS) finish(State.Failed, "the bot was held up for too long (" + host.pauseReason() + ")");
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
        if (index < s.units.size()) {
            // How naming went, so a resume that finds it in the delivery container counts it right
            Saved.UnitSave p = s.units.get(index);
            p.nameDone = u.nameDone;
            p.nameFailure = u.nameFailure == null ? "" : u.nameFailure;
            p.namedKey = u.namedKey == null ? "" : u.namedKey;
        }
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
     * The bin could not be finished, but its shulker holds part of it: it is delivered as it is, not thrown back. From now on
     * the unit is what the shulker holds ({@code packed}, saved with the plan so a restart still knows the shulker for this
     * unit's), and the order's end note says it came short; "delivered less than asked" lists the lines.
     */
    public void builtShort(Unit u, List<Order.Lot> packed, String reason) {
        if (packed.isEmpty()) return;
        u.lots.clear();
        u.lots.addAll(packed);
        u.built = true;
        int at = units.indexOf(u);
        problems.add("shulker " + (at + 1) + " delivered short: " + reason);
        Order o = order;
        Saved s = o == null ? null : host.saved(o.id);
        if (s != null && at >= 0 && at < s.units.size()) {
            Saved.UnitSave p = s.units.get(at);
            p.lotKeys.clear();
            p.lotVariants.clear();
            p.lotCounts.clear();
            for (Order.Lot lot : packed) {
                p.lotKeys.add(lot.key());
                p.lotVariants.add(lot.variant() == null ? "" : lot.variant());
                p.lotCounts.add(lot.count());
            }
            host.save(s);
        }
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
                finish(State.Failed, "the bot was held up for too long (" + host.pauseReason() + ")");
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
                // Named first, if the order wants a name on it; naming never holds the order up
                if (u.name != null && !u.nameDone && u.nameFailure == null) {
                    Name name = naming(u, slot);
                    if (name != null) return name;
                }
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
                // The stack about to go in decides: one that carries the name is not counted as unnamed
                if (u.name != null && !u.nameDone) {
                    String wanted = ShulkerNames.normalise(u.name);
                    if (wanted != null && wanted.equals(host.nameOn(u, slot))) u.nameDone = true;
                }
                phase = "delivering " + (index + 1) + "/" + units.size();
                return new Deliver(u, slot, false);
            }

            if (u.have) {
                // It left the inventory: it counts only if the delivery container was seen to hold it
                if (u.confirmed) {
                    for (var e : u.content.entrySet()) delivered.merge(e.getKey(), e.getValue(), Integer::sum);
                    shulkersDelivered++;
                    countUnnamed(u);
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
                // Building puts a shulker down: it waits while shulker handling is paused (as long as PAUSE_TIMEOUT_MS allows)
                String paused = host.buildPauseReason();
                if (paused != null) {
                    long now = clock.getAsLong();
                    if (buildPausedSince == 0) buildPausedSince = now;
                    else if (now - buildPausedSince > PAUSE_TIMEOUT_MS) {
                        finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, "the bot was held up for too long (" + paused + ")");
                        return new Idle();
                    }
                    phase = "paused: " + paused;
                    return new Idle();
                }
                buildPausedSince = 0;
                // No empty shulker for it: one is crafted first, when crafting is on
                if (host.craftingOn() && !host.emptyAvailable(u)) {
                    Craft c = crafting(u);
                    if (c != null) return c;
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
                // An empty plain shulker box that is not in the stash is crafted, when crafting is on
                if (u.kind == Unit.Kind.Empty && PLAIN_SHULKER.equals(u.key) && host.craftingOn()) {
                    Craft c = crafting(u);
                    if (c != null) return c;
                    continue;
                }
                skip(u.kind == Unit.Kind.Shulker ? "a packed shulker is no longer in the stash" : "no empty shulker of that kind is left");
                continue;
            }
            u.fetches++;
            phase = "fetching " + (index + 1) + "/" + units.size();
            return new Fetch(u);
        }
        return new Idle();
    }

    /** The Craft step for a unit with no empty shulker, or null when it was given up (skipped, with the reason). */
    private @Nullable Craft crafting(Unit u) {
        String why = u.craftFailure;
        if (why == null && u.craftTries >= MAX_CRAFT_TRIES) why = "it didn't work in " + MAX_CRAFT_TRIES + " tries";
        if (why == null) why = host.craftProblem(u);
        if (why != null) {
            skip(NO_EMPTY_CRAFT + why);
            return null;
        }
        phase = "crafting a shulker for " + (index + 1) + "/" + units.size();
        return new Craft(u, Math.max(1, host.craftCount(u)));
    }

    /** The host has started gathering for a craft of this unit: only trips really started count towards MAX_CRAFT_TRIES. */
    public void craftStarted(Unit u) {
        u.craftTries++;
    }

    /** {@code n} shulker boxes were crafted for the order (counted towards its cap, saved at once). */
    public void crafted(Unit u, int n) {
        if (n <= 0) return;
        crafted += n;
        u.craftFailure = null;
        Order o = order;
        Saved s = o == null ? null : host.saved(o.id);
        if (s != null) {
            s.crafted = crafted;
            host.save(s);
        }
    }

    /**
     * Crafting for this unit didn't work. @param retry it was cut short (the bot gave way, the window closed) or may work from
     * a fresh look at the stash: tried once more while MAX_CRAFT_TRIES allows; otherwise the unit is given up with this reason
     */
    public void craftFailed(Unit u, String reason, boolean retry) {
        if (retry && u.craftTries < MAX_CRAFT_TRIES) return;
        u.craftFailure = reason;
    }

    /** Shulker boxes crafted for this order so far. */
    public int craftedCount() {
        return crafted;
    }

    /** "crafted 2 Shulker Box", or null when none was. */
    public @Nullable String craftedNote() {
        return crafted <= 0 ? null : "crafted " + crafted + " Shulker Box";
    }

    /** The anvil step for this shulker, or null when it is named already or will go unnamed (the reason is kept). */
    private @Nullable Name naming(Unit u, int slot) {
        String name = ShulkerNames.normalise(u.name);
        if (name == null) {
            u.nameFailure = "a name that breaks the rules";
            return null;
        }
        if (name.equals(host.nameOn(u, slot))) {
            u.nameDone = true;
            return null;
        }
        if (u.nameTries >= MAX_NAME_TRIES) {
            u.nameFailure = "naming didn't finish";
            return null;
        }
        String why = host.namingProblem(u);
        if (why != null) {
            u.nameFailure = why;
            return null;
        }
        phase = "naming " + (index + 1) + "/" + units.size();
        return new Name(u, slot, name);
    }

    /** The host has started the trip to the anvil for this shulker: only trips really made count towards MAX_NAME_TRIES. */
    public void namingStarted(Unit u) {
        u.nameTries++;
    }

    /** The shulker came back from the anvil with its name. Saved at once, so a restart before delivery still knows it. */
    public void named(Unit u) {
        u.nameDone = true;
        u.nameFailure = null;
        saveNaming(u);
    }

    /**
     * The anvil could not name it. @param retry it was cut short (the window closed, the bot gave way) and may be tried once
     * more; otherwise it is delivered unnamed with this reason
     */
    public void nameFailed(Unit u, String reason, boolean retry) {
        if (retry && u.nameTries < MAX_NAME_TRIES) return;
        u.nameFailure = reason;
        saveNaming(u);
    }

    /** Writes how naming went for this unit into the saved plan. */
    private void saveNaming(Unit u) {
        Order o = order;
        Saved s = o == null ? null : host.saved(o.id);
        int at = units.indexOf(u);
        if (s == null || at < 0 || at >= s.units.size()) return;
        Saved.UnitSave p = s.units.get(at);
        p.nameDone = u.nameDone;
        p.nameFailure = u.nameFailure == null ? "" : u.nameFailure;
        p.namedKey = u.namedKey == null ? "" : u.namedKey;
        host.save(s);
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
            s.unnamed = unnamed;
            s.unnamedReasons = new ArrayList<>(unnamedReasons);
            s.crafted = crafted;
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

        boolean anything = shulkersDelivered > 0;
        // The unnamed note is added by finish, at the end and whole
        finish(anything ? State.Delivered : State.Failed, endNote(anything, problems, shortLines));
    }

    /** The server keeps this much of an order's message. */
    static final int MESSAGE_MAX = 300;

    /**
     * The message an order ends with, main reason first so the server's cut never loses it: why shulkers were given up
     * (newest first, "replanned" notes last), then what came up short. Cut at {@link #MESSAGE_MAX} on a word.
     */
    static String endNote(boolean anything, List<String> problems, List<String> shortLines) {
        return endNote(anything, problems, shortLines, null);
    }

    /** @param unnamed the one note about shulkers delivered without their names, after the reasons shulkers were given up */
    static String endNote(boolean anything, List<String> problems, List<String> shortLines, @Nullable String unnamed) {
        List<String> reasons = new ArrayList<>();
        for (int i = problems.size() - 1; i >= 0; i--) if (!problems.get(i).startsWith("replanned") && !reasons.contains(problems.get(i))) reasons.add(problems.get(i));
        if (unnamed != null) reasons.add(unnamed);
        for (String p : problems) if (p.startsWith("replanned") && !reasons.contains(p)) reasons.add(p);
        StringBuilder b = new StringBuilder(anything ? "" : "nothing could be delivered");
        if (!reasons.isEmpty()) b.append(b.isEmpty() ? "" : ": ").append(String.join("; ", reasons));
        if (!shortLines.isEmpty()) {
            b.append(b.isEmpty() ? "" : "; ").append("delivered less than asked: ").append(String.join(", ", shortLines.size() > 6 ? shortLines.subList(0, 6) : shortLines));
            if (shortLines.size() > 6) b.append(", ...");
        }
        return cut(b.toString());
    }

    private static String cut(String note) {
        return cutTo(note, MESSAGE_MAX);
    }

    /** At most {@code max} characters, cut on a word with " ..." when it is longer. */
    static String cutTo(String note, int max) {
        if (note.length() <= max) return note;
        int cut = note.lastIndexOf(' ', max - 4);
        return note.substring(0, cut > max / 2 ? cut : Math.max(0, max - 4)) + " ...";
    }

    /** The message with the unnamed note added once at its end, whole, the rest cut to make room: within MESSAGE_MAX. */
    static String withUnnamedNote(String text, @Nullable String note) {
        return withNotes(text, note, null);
    }

    /**
     * The message with the unnamed note and the crafted note added once at its end, whole, the rest cut to make room: within
     * MESSAGE_MAX.
     */
    static String withNotes(String text, @Nullable String unnamedNote, @Nullable String craftedNote) {
        List<String> tail = new ArrayList<>();
        if (unnamedNote != null && !text.contains(ShulkerNames.UNNAMED_MARK)) tail.add(unnamedNote);
        if (craftedNote != null && !text.contains(craftedNote)) tail.add(craftedNote);
        if (tail.isEmpty()) return cut(text);
        String note = String.join("; ", tail);
        if (text.isEmpty()) return cut(note);
        int room = MESSAGE_MAX - note.length() - 2;
        if (room < 20) return cut(note);
        return cutTo(text, room) + "; " + note;
    }

    /** Ends the order here with this outcome; the server is told by the next {@link #report()}. */
    public void finish(State end, String text) {
        if (order == null || state != State.Running) return;
        settleConfirmed();
        // Every way an order ends carries the one note about shulkers that went without their names
        // Added once: endNote may already hold it (perhaps cut short at MESSAGE_MAX); the whole message stays within MESSAGE_MAX
        text = withNotes(text, unnamedNote(), craftedNote());
        state = end;
        message = text;
        phase = end.name().toLowerCase();
        host.say("Order " + order.id + " " + phase + (text.isEmpty() ? "." : ": " + text));
    }
}
