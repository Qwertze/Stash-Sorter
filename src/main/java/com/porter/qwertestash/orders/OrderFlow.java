package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
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

        /**
         * As {@link #plan(Order)}, with the loads of the order's craft lines (1.15.0) added to what is packed. A host that can't craft
         * is never given any.
         */
        default StockPlanner.Plan plan(Order order, List<Order.Lot> craftLots) {
            return plan(order);
        }

        /**
         * The recipe table craft orders are carried out by, or null when this bot can't carry them out (1.15.0): none was fetched, or
         * craft orders are off. Asked once, when the order is planned: the order keeps that table to its end.
         */
        default @Nullable Recipes craftTable() {
            return null;
        }

        /** The most grid fills one craft order may make. */
        default int craftMaxFills() {
            return 0;
        }

        /**
         * The units for the order's kit lines (1.16.0, KIT-CONTRACT v2): complete copies to fetch and kits to build, planned from what the stash
         * holds now; null when this host cannot build kits (every kit is then short with template_mismatch).
         */
        default KitPlanner.@Nullable Plan planKit(Order order, int emptiesTaken) {
            return null;
        }

        /**
         * A kit unit was begun and will not be delivered or stored (cancelled, given up, or short and not nameable): the shulker, if the bot carries one
         * with its items, is emptied and the items put back (KIT-CONTRACT v2 revision 4, step 4). Asked once per unit; the host finds the shulker.
         */
        default void kitEmptyBack(Unit unit) {
        }

        /** The stack size of an item (a namespaced id) for splitting a craft line into loads. */
        default int maxStack(String id) {
            return 64;
        }

        /**
         * A plan was taken up again after a restart (1.15.0): the crafted loads of these units and the surplus the ledger saved, that are still
         * carried, are set aside again (the host forgot which slots they were in). @return the surplus (by id) that is not carried any more:
         * it is refunded to the budget
         */
        default Map<String, Long> craftResumed(List<Unit> units, Map<String, Long> surplus) {
            return Map.of();
        }

        /** The order ends: these crafted stacks (by id) are carried and go back; the host watches them leave. */
        default void craftWatch(Map<String, Long> carried) {
        }

        /** Some stack of the ones watched is still carried (the put-back is not over). */
        default boolean craftReturning() {
            return false;
        }

        /** What of the watched stacks is still carried, by id. */
        default Map<String, Long> craftStillCarried() {
            return Map.of();
        }

        /** The crafted surplus carried now (made by the order and not used), by id; given back when the order ends. */
        default Map<String, Long> craftCarried() {
            return Map.of();
        }

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

        /**
         * What the delivery storage can take now (1.9.0): a chest with room, a chest to look at again, another chest to put up,
         * or nothing but waiting. The default is the old single container: always room (it is found full by delivering).
         */
        default DeliveryRoom deliveryRoom() {
            return DeliveryRoom.ROOM;
        }

        /** How many chests the delivery storage has (1 for a host with the single delivery container). */
        default int deliveryChests() {
            return 1;
        }
    }

    public sealed interface Intent permits Idle, Build, Fetch, Name, Deliver, Craft, CraftLoads, Expand, Recheck {
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

    /**
     * Make the craft loads of this bin (1.15.0): the first load flagged {@link Order.Lot#craft()} ({@link Unit#nextCraftLot}) is crafted
     * (one chunk of it at a time, as many as the free carry slots allow) before the bin is built. The host reports {@link #craftLoadsStarted},
     * then {@link #craftChunk} or {@link #craftLotShort}, or {@link #craftAbandon} when the table can't be used.
     */
    public record CraftLoads(Unit unit) implements Intent {
    }

    /**
     * Put another double chest up above the delivery storage (1.9.0) because every chest is full. The host reports nothing
     * back: what it did shows in {@link Host#deliveryRoom()} on the next step (room, or why not).
     */
    public record Expand(Unit unit) implements Intent {
    }

    /**
     * Open a delivery chest that was found full and may have been emptied since (a player was seen with it open, or it has
     * been a while) and read what it holds. {@code at} is a block of that chest.
     */
    public record Recheck(Unit unit, DeliveryBook.Pos at) implements Intent {
    }

    public enum State { Running, Delivered, Failed, Cancelled }

    /** A snapshot of the order for the server. */
    public record Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered,
                         @Nullable JsonObject repair, @Nullable JsonObject craft, @Nullable JsonObject kit) {
        public Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered,
                      @Nullable JsonObject repair, @Nullable JsonObject craft) {
            this(id, status, phase, done, total, message, shulkers, delivered, repair, craft, null);
        }

        public Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered,
                      @Nullable JsonObject repair) {
            this(id, status, phase, done, total, message, shulkers, delivered, repair, null, null);
        }

        public Report(String id, String status, String phase, int done, int total, String message, int shulkers, Map<String, Integer> delivered) {
            this(id, status, phase, done, total, message, shulkers, delivered, null, null, null);
        }

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
        /** Which chest of the delivery storage it was going into (x, y, z; 1.9.0), so a restart looks in that one; null for none or an old save. */
        public int @Nullable [] deliveringAt;
        public int shulkersDelivered;
        public Map<String, Integer> delivered = new LinkedHashMap<>();
        /** Shulkers delivered without the name the order wanted, and why (each reason once). */
        public int unnamed;
        public List<String> unnamedReasons = new ArrayList<>();
        /** Shulker boxes crafted for this order so far: they count towards its cap (1.8.0; 0 in older saves). */
        public int crafted;
        /** The repair result so far (Repair.Report.toSaved as JSON text), or null for none / an older save (1.13.0). */
        public @Nullable String repair;
        /** The craft ledger so far (CraftLedger.toSaved as JSON text), or null for none / an older save (1.15.0). */
        public @Nullable String craft;

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
            /** Repaired items of this unit: "key now" and "key asked for" alternating (1.13.0; empty in older saves). */
            public List<String> rekeys = new ArrayList<>();
            /** Kit units (1.16.0; empty in older saves): the template id, the colour asked for and the base copy's group key; a ready copy; built short. */
            public String kitTemplate = "", kitColor = "", kitBase = "";
            public boolean kitReady, kitShort;
            /** Per lot: 0 an ordinary load, 1 still to be crafted, 2 crafted (1.15.0; empty in older saves, which read as 0). */
            public List<Integer> lotCraft = new ArrayList<>();
        }
    }

    public static final long PAUSE_TIMEOUT_MS = 10 * 60 * 1000L;
    public static final long REREAD_TIMEOUT_MS = 10 * 60 * 1000L;
    public static final int MAX_FULL_TRIES = 2;
    /**
     * How long an order waits for room in the delivery storage (every chest full and none can be put up, or auto expand is off)
     * before it ends. Longer than PAUSE_TIMEOUT_MS: what is waited for is a player claiming an earlier order, which takes
     * longer than a queue or a player taking the controls; not unbounded, so a forgotten full chest does not hold the bot (and
     * every order after this one) for ever. The finished shulkers stay in the inventory throughout and go back to the stash
     * when the order ends.
     */
    public static final long SPACE_WAIT_TIMEOUT_MS = 2 * 60 * 60 * 1000L;
    /** Times one shulker asks the delivery storage to grow before the order waits instead. */
    public static final int MAX_EXPANDS = 6;
    /** Expand or Recheck steps in a row the host may fail to start before the order waits a minute instead. */
    public static final int MAX_SPINS = 30;
    public static final long SPIN_HOLD_MS = 60_000L;
    public static final String WAITING_FOR_SPACE = "waiting for delivery space: ";
    public static final int MAX_STARTS = 8;
    public static final int MAX_BEGINS = 3;
    /** Times the anvil is tried for one shulker before it is delivered unnamed (a try cut short by the server, say). */
    public static final int MAX_NAME_TRIES = 2;
    /** Craft trips for one unit before it is given up. */
    public static final int MAX_CRAFT_TRIES = 2;
    /** The host is asked to craft a bin's loads for this long at most before they are given up on (it never started). */
    public static final long CRAFT_START_WAIT_MS = 5 * 60 * 1000L;
    /** Rounds (chunks) of crafting one bin may take. */
    public static final int MAX_CRAFT_ROUNDS = 120;
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
    /** What the order asked to have repaired, and what came of it (1.13.0). Null when no line is wanted for repair. */
    private Repair.@Nullable Report repair;
    private java.util.Set<String> repairKeys = java.util.Set.of(), repairExplicit = java.util.Set.of();
    /** What the order's kit lines have made (1.16.0). Null when the order has none. */
    private @Nullable KitResult kitResult;
    /** Kit units whose outcome is recorded already (delivered, released short, or given up). */
    private final java.util.Set<Unit> kitSettled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** The order in hand is a kit restock job (never reported to the server). */
    public boolean isRestock() {
        return order != null && order.restock;
    }

    /** The kit result so far, for the host (colour notes) and the report. */
    public @Nullable KitResult kitResult() {
        return kitResult;
    }

    /** "kit done/total: name" for the unit being worked on, or null when it is not a kit. */
    private @Nullable String kitPhase() {
        if (kitResult == null || index >= units.size()) return null;
        Unit u = units.get(index);
        if (u.kitTemplate == null) return null;
        int total = 0, done = 0;
        for (int i = 0; i < units.size(); i++) {
            if (units.get(i).kitTemplate == null) continue;
            total++;
            if (i < index) done++;
        }
        String name = u.name == null ? "" : u.name;
        return "kit " + (done + 1) + "/" + total + (name.isEmpty() ? "" : ": " + (name.length() > 40 ? name.substring(0, 40) : name));
    }

    /** A begun kit that is not a finished one: only these are taken apart (a complete kit is filed in its chain target like a restocked one). */
    private static boolean emptiesBack(Unit u) {
        return u.kitTemplate != null && !u.kitReady && u.begins > 0 && !(u.built && !u.kitShort);
    }

    /** The reason code of a kit given up on, from the words the flow and the host use for it. */
    static String kitReasonOf(String why) {
        String w = why == null ? "" : why.toLowerCase(java.util.Locale.ROOT);
        if (w.contains("empty shulker") || w.contains("get a shulker")) return KitOrder.NO_SHULKER;
        if (w.contains("carry room") || w.contains("inventory")) return KitOrder.INVENTORY_FULL;
        if (w.contains("no headway") || w.contains("too many trips") || w.contains("held up") || w.contains("timed out")) return KitOrder.TIMEOUT;
        if (w.contains("cancel")) return KitOrder.CANCELLED;
        if (w.contains("not enough") || w.contains("no longer in the stash") || w.contains("nothing of it") || w.contains("none usable") || w.contains("no source")
            || w.contains("couldn't take")) return KitOrder.ITEMS_SHORT;
        return KitOrder.INTERRUPTED;
    }

    /** A kit unit has its outcome: true only the first time. */
    private boolean settleKit(Unit u) {
        return u.kitTemplate != null && kitResult != null && kitSettled.add(u);
    }

    /** What the order's craft lines have done (1.15.0). Null when the order has none. */
    private @Nullable CraftLedger craftLedger;
    /** When an order with craft lines ends, what is carried of it goes back: the report waits (a little) for that to be over. */
    private Map<String, Long> returnPlanned = Map.of();
    private long returnSince;
    private boolean returnSettled = true;
    /** The longest the final report waits for crafted items to be put back. */
    public static final long RETURN_WAIT_MS = 90_000L;
    private String message = "";
    private String phase = "starting";
    private boolean cancelRequested, parked, planned, full;
    private long pausedSince, buildPausedSince, offeredAt, rereadProgressAt, spaceWaitSince, spinHoldUntil;
    private @Nullable String lastWaitWhy;
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
        if (o.restock) return state == State.Running ? "Kit restock: " + phase : "Kit restock: " + state.name().toLowerCase();
        return state == State.Running ? id + ": " + phase + " (" + Math.min(index, units.size()) + "/" + units.size() + " shulkers)" : id + ": " + state.name().toLowerCase();
    }

    /** The title of the order's card in the dashboard's Now-doing list: no positions. */
    public @Nullable String activityTitle() {
        Order o = order;
        if (o == null) return null;
        String id = OrderDriver.shortId(o.id);
        if (o.restock) return state == State.Running ? "Kit restock: " + phase : "Kit restock " + state.name().toLowerCase();
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
        repair = null;
        craftLedger = null;
        returnPlanned = Map.of();
        returnSettled = true;
        repairKeys = repairExplicit = java.util.Set.of();
        cancelRequested = parked = planned = full = false;
        pausedSince = buildPausedSince = spaceWaitSince = spinHoldUntil = 0;
        lastWaitWhy = null;
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
        if (!host.deliverySet() && !o.restock) return "no delivery container is set";

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
        repairKeys = o.repairKeys();
        repairExplicit = o.repairExplicitKeys();
        repair = repairKeys.isEmpty() ? null : new Repair.Report();
        craftLedger = null;
        kitResult = null;
        kitSettled.clear();
        message = "";
        cancelRequested = parked = planned = full = false;
        pausedSince = buildPausedSince = spaceWaitSince = spinHoldUntil = 0;
        lastWaitWhy = null;
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
        List<Order.Lot> craftLots = List.of();
        if (o.hasCraft() && craftLedger == null) {
            craftLedger = startCraft(o);
            if (!resumed) {
                List<Order.Lot> made = craftLotsOf(o, craftLedger);
                if (made == null) {
                    host.say("The order's craft lines are cut into more than " + CraftOrder.MAX_LOTS + " loads: not crafted.");
                    craftLedger = CraftLedger.refused(o.craftLines(), craftLedger.table(), craftLedger.maxFills(), CraftOrder.RECIPE_MISMATCH);
                }
                else craftLots = made;
            }
        }

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
                if (p.kitTemplate != null && !p.kitTemplate.isEmpty()) {
                    u.kitTemplate = p.kitTemplate;
                    u.kitReady = p.kitReady;
                    u.kitShort = p.kitShort;
                    u.kitColor = p.kitColor == null || p.kitColor.isEmpty() ? null : p.kitColor;
                    u.kitBase = p.kitBase == null || p.kitBase.isEmpty() ? null : p.kitBase;
                }
                if (p.lotCraft != null) {
                    for (int i = 0; i < u.lots.size() && i < p.lotCraft.size(); i++) {
                        Integer c = p.lotCraft.get(i);
                        if (c != null && (c == Order.Lot.PENDING || c == Order.Lot.CRAFTED)) {
                            Order.Lot lot = u.lots.get(i);
                            u.lots.set(i, new Order.Lot(lot.key(), lot.variant(), lot.count(), c));
                        }
                    }
                }
                if (p.rekeys != null) for (int i = 0; i + 1 < p.rekeys.size(); i += 2) u.rekeys.add(new String[] {p.rekeys.get(i), p.rekeys.get(i + 1)});
                plan.add(u);
            }
            start = Math.min(saved.done, plan.size());
            // A shulker may have gone into the delivery container just before the bot went offline: the container is looked at
            // before the unit is built again
            if (saved.deliveringIndex == start && start < plan.size()) {
                Unit u = plan.get(start);
                u.have = true;
                u.base = saved.deliveringBase;
                u.content = u.kitTemplate != null ? Map.of(u.kitTemplate, 1) : u.kind == Unit.Kind.Bin ? Repair.translate(BinTracker.lotTotals(u.lots), u.rekeys) : Map.of(u.key, 1);
            }
            // What was delivered before counts even if the rest cannot be done now
            shulkersDelivered = saved.shulkersDelivered;
            delivered.putAll(saved.delivered);
            unnamed = saved.unnamed;
            if (saved.unnamedReasons != null) unnamedReasons.addAll(saved.unnamedReasons);
            crafted = Math.max(0, saved.crafted);
            if (repair != null && saved.repair != null) {
                try {
                    repair = Repair.Report.fromSaved(com.google.gson.JsonParser.parseString(saved.repair).getAsJsonObject());
                } catch (RuntimeException e) {
                    // A broken record is a fresh one: repairing never holds an order up
                }
            }
            if (craftLedger != null && saved.craft != null) {
                try {
                    craftLedger.restore(com.google.gson.JsonParser.parseString(saved.craft).getAsJsonObject());
                } catch (RuntimeException e) {
                    // A broken record is a fresh ledger: the order goes on with its full budget
                }
            }
        }
        else {
            StockPlanner.Plan fresh = craftLots.isEmpty() ? host.plan(o) : host.plan(o, craftLots);
            KitPlanner.Plan kitPlan = o.hasKit() ? kitPlanOf(o, fresh) : null;
            boolean kitUnits = kitPlan != null && !kitPlan.units().isEmpty();
            // An order of kit lines alone has no other unit: the stock planner's "nothing to deliver" is not a failure then
            if (fresh.failure() != null && !(kitUnits && fresh.failure().equals("nothing to deliver"))) {
                // An order that is only craft lines this bot can't craft fails with the reason, not just "nothing to deliver"
                String note = craftLedger == null ? null : craftLedger.note();
                return note == null || fresh.failure().contains(note) ? fresh.failure() : cutTo(fresh.failure(), MESSAGE_MAX - note.length() - 2) + "; " + note;
            }
            plan.addAll(fresh.units());
            notes.addAll(fresh.notes());
            // Kit units go after the shulker and empty-shulker lines and before the packed bins (ready copies first within them)
            if (kitPlan != null) {
                int at = 0;
                while (at < plan.size() && plan.get(at).kind != Unit.Kind.Bin) at++;
                plan.addAll(at, kitPlan.units());
                notes.addAll(kitPlan.notes());
            }
            // The i-th shulker of the plan (shulker lines, then empty shulkers, then bins) gets the i-th name; kit units are named for their template
            if (o.names != null) for (int i = 0; i < plan.size(); i++) if (plan.get(i).kitTemplate == null) plan.get(i).name = o.names.nameFor(i);
        }

        String why = host.checkEmpties(new ArrayList<>(plan.subList(start, plan.size())));
        if (why != null) return why;

        units.clear();
        units.addAll(plan);
        index = start;
        problems.addAll(notes);
        planned = true;
        phase = "starting";
        // A resumed order whose craft lines can't be carried out now (the table is gone, the check fails): what is left to craft is short
        if (resumed && craftLedger != null && craftLedger.refusedAtStart()) craftAbandon(CraftOrder.RECIPE_MISMATCH);
        if (resumed && craftLedger != null) {
            Map<String, Long> gone = host.craftResumed(new ArrayList<>(units.subList(Math.min(start, units.size()), units.size())), craftLedger.savedSurplus());
            if (!gone.isEmpty()) {
                craftLedger.refund(gone);
                saveCraft(null);
            }
        }

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
                if (u.kitTemplate != null) {
                    p.kitTemplate = u.kitTemplate;
                    p.kitReady = u.kitReady;
                    p.kitShort = u.kitShort;
                    p.kitColor = u.kitColor == null ? "" : u.kitColor;
                    p.kitBase = u.kitBase == null ? "" : u.kitBase;
                }
                for (Order.Lot lot : u.lots) p.lotCraft.add(lot.craftState());
                s.units.add(p);
            }
            if (craftLedger != null) s.craft = craftLedger.toSaved().toString();
            host.save(s);
        }
        if (o.restock) host.say("Kit restock: " + (units.size() - index) + " kit to build.");
        else host.say("Taking order " + o.id + ": " + (units.size() - index) + " shulkers to deliver" + (index > 0 ? ", " + index + " already done." : "."));
        return null;
    }

    // Kit orders (1.16.0, KIT-CONTRACT v2 "Kit orders")

    /**
     * Step 1 and the planning of the kit lines. The check comes before any click: a block that is not a valid snapshot makes every kit short with
     * template_mismatch (the rest of the order goes on). Null when there is nothing to build.
     */
    private KitPlanner.@Nullable Plan kitPlanOf(Order o, StockPlanner.Plan fresh) {
        KitOrder.Snapshot snap = o.kit;
        kitResult = new KitResult();
        if (snap != null) for (KitOrder.Line l : snap.lines()) kitResult.line(l.template().id(), l.template().name(), l.count());
        String bad = KitOrder.check(snap, host::maxStack);
        int taken = 0;
        for (Unit u : fresh.units()) if (u.kind == Unit.Kind.Bin || u.kind == Unit.Kind.Empty) taken++;
        KitPlanner.Plan plan = bad != null || snap == null ? null : host.planKit(o, taken);
        if (plan == null) {
            if (bad == null && snap != null) bad = "this bot cannot build kits";
            for (Order.Line l : o.kitLines()) kitResult.addShort(l.key(), l.count(), List.of(), KitOrder.TEMPLATE_MISMATCH, null);
            if (bad != null) problems.add("kits: " + bad);
            return null;
        }
        for (Unit u : plan.units()) if (u.kitReady) kitResult.ready(u.kitTemplate);
        for (KitResult.Short s : plan.shorts()) kitResult.addShort(s.template(), s.kits(), s.items(), s.reason(), s.stored());
        return plan;
    }

    // Craft orders (1.15.0, CRAFT-CONTRACT.md "Bot execution")

    /**
     * The ledger of a craft order: with the order's craft block checked against the bot's own recipe table ({@link CraftOrder#check}) before
     * anything is clicked. When the bot can't carry the order out (no table fetched, craft orders off, the check failed) every craft line is
     * short with {@link CraftOrder#RECIPE_MISMATCH} and nothing is crafted; the rest of the order goes on.
     */
    private CraftLedger startCraft(Order o) {
        Recipes table = host.craftTable();
        List<Order.Line> lines = o.craftLines();
        if (table == null) {
            host.say("This order has craft lines but this bot has no recipe table or craft orders are off: they are not crafted.");
            return CraftLedger.refused(lines, Recipes.empty(), 0, CraftOrder.RECIPE_MISMATCH);
        }
        String bad = CraftOrder.check(o.craft, table, host.craftMaxFills());
        if (bad != null) {
            host.say("The order's craft lines are not crafted: " + bad + ".");
            return CraftLedger.refused(lines, table, host.craftMaxFills(), CraftOrder.RECIPE_MISMATCH);
        }
        return new CraftLedger(o.craft, lines, table, host.craftMaxFills());
    }

    /** The loads of the craft lines (merged per key, cut into stacks), none when the ledger has every line short already. */
    private @Nullable List<Order.Lot> craftLotsOf(Order o, CraftLedger ledger) {
        List<Order.Lot> out = new ArrayList<>();
        if (ledger.shortTotal() > 0) return out;
        Map<String, Long> byKey = new LinkedHashMap<>();
        for (Order.Line l : o.craftLines()) byKey.merge(l.key(), (long) l.count(), Long::sum);
        // Counted before anything is made: a huge line must not allocate its loads
        long loads = 0;
        for (var e : byKey.entrySet()) loads += (e.getValue() + Math.max(1, host.maxStack(e.getKey())) - 1) / Math.max(1, host.maxStack(e.getKey()));
        if (loads > CraftOrder.MAX_LOTS) return null;
        for (var e : byKey.entrySet()) {
            int max = Math.max(1, host.maxStack(e.getKey()));
            for (Order.Lot lot : Order.expand(e.getKey(), (int) (long) e.getValue(), max)) out.add(Order.Lot.toCraft(lot.key(), lot.count()));
        }
        return out;
    }

    /** The order's craft ledger, or null when it has no craft lines. */
    public @Nullable CraftLedger craftLedger() {
        return craftLedger;
    }

    /**
     * The index of the load of this bin to craft next: of the loads still to craft the one whose craft line comes first in the order (the
     * server planned the lines in that order, so the shared surplus and the budget go the way it planned), else the first in the bin. -1 for none.
     */
    public int nextCraftLot(Unit u) {
        CraftLedger l = craftLedger;
        int best = -1, bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < u.lots.size(); i++) {
            Order.Lot lot = u.lots.get(i);
            if (!lot.craft()) continue;
            int rank = l == null ? 0 : l.rank(lot.key());
            if (best < 0 || rank < bestRank) {
                best = i;
                bestRank = rank;
            }
        }
        return best;
    }

    /** The host is starting a round of crafting for this bin (counted: a host that never finishes one does not hold the order). */
    public void craftLoadsStarted(Unit u) {
        u.craftLoadsWaitSince = 0;
        u.craftLoadRounds++;
    }

    /** "crafting 3/11 fills: Spruce Stairs": the phase while a craft load is made; {@code extra} counts fills made in the chunk under way. */
    public String craftPhase(String key, long extra) {
        CraftLedger l = craftLedger;
        long done = (l == null ? 0 : l.fills()) + Math.max(0, extra);
        long total = Math.max(done, l == null ? 0 : l.plannedFills());
        return "crafting " + done + "/" + total + " fills: " + Recipes.pretty(key);
    }

    /**
     * A chunk of the craft load at {@code index} was made: {@code made} of its items are in hand, as stacks of exactly the sizes the bin
     * expects, and it took {@code fillsMade} grid fills and {@code fromStock} from the stash. A load that came out smaller keeps the rest
     * to be crafted when {@code reason} is null, else the rest is short with that reason. With 0 made and no reason nothing changes (a
     * try cut short, to be made again).
     */
    public void craftChunk(Unit u, int index, int made, long fillsMade, Map<String, Long> fromStock, @Nullable String reason) {
        CraftLedger l = craftLedger;
        if (l == null || index < 0 || index >= u.lots.size() || !u.lots.get(index).craft()) return;
        Order.Lot lot = u.lots.get(index);
        int m = Math.max(0, Math.min(made, lot.count()));
        l.madeChunk(lot.key(), m, fillsMade, fromStock);
        if (m >= lot.count()) u.lots.set(index, lot.made());
        else if (m > 0) {
            u.lots.set(index, lot.withCount(m).made());
            // The rest is another load of the bin; a shulker holds 27 stacks, so with no slot left for it the rest is short
            if (reason == null && u.lots.size() < Order.SLOTS) u.lots.add(index + 1, lot.withCount(lot.count() - m));
            else l.addShort(lot.key(), lot.count() - m, reason == null ? CraftOrder.INVENTORY_FULL : reason);
        }
        else if (reason != null) {
            u.lots.remove(index);
            l.addShort(lot.key(), lot.count(), reason);
        }
        saveCraft(u);
    }

    /** The craft load at {@code index} can't be made: it is short with this reason, and the bin goes on without it. */
    public void craftLotShort(Unit u, int index, String reason) {
        CraftLedger l = craftLedger;
        if (l == null || index < 0 || index >= u.lots.size() || !u.lots.get(index).craft()) return;
        Order.Lot lot = u.lots.remove(index);
        l.addShort(lot.key(), lot.count(), reason);
        saveCraft(u);
    }

    /** Crafting can't go on at all (no table, the block is no table, it can't be reached): every craft load still to make is short with this reason. */
    public void craftAbandon(String reason) {
        CraftLedger l = craftLedger;
        if (l == null) return;
        for (Unit u : units) {
            for (int i = u.lots.size() - 1; i >= 0; i--) {
                Order.Lot lot = u.lots.get(i);
                if (!lot.craft()) continue;
                u.lots.remove(i);
                l.addShort(lot.key(), lot.count(), reason);
            }
        }
        saveCraft(null);
    }

    /** The ledger changed outside a chunk (a refund): it is written to the saved plan. */
    public void craftLedgerChanged() {
        saveCraft(null);
    }

    /** The unit's lots and the ledger are written to the saved plan, so a restart carries on from here. */
    private void saveCraft(@Nullable Unit changed) {
        Order o = order;
        Saved s = o == null ? null : host.saved(o.id);
        if (s == null) return;
        for (int at = 0; at < units.size() && at < s.units.size(); at++) {
            Unit u = units.get(at);
            if (changed != null && changed != u) continue;
            Saved.UnitSave p = s.units.get(at);
            p.lotKeys.clear();
            p.lotVariants.clear();
            p.lotCounts.clear();
            p.lotCraft.clear();
            for (Order.Lot lot : u.lots) {
                p.lotKeys.add(lot.key());
                p.lotVariants.add(lot.variant() == null ? "" : lot.variant());
                p.lotCounts.add(lot.count());
                p.lotCraft.add(lot.craftState());
            }
        }
        if (craftLedger != null) s.craft = craftLedger.toSaved().toString();
        host.save(s);
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

    /** The saved order has craft lines (its ledger is saved with it). */
    public boolean resumeHasCraft() {
        String id = host.savedId();
        Saved s = id == null ? null : host.saved(id);
        return s != null && s.craft != null;
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
        // An ended craft order reports once its crafted leftovers are put back (at most RETURN_WAIT_MS), so result.craft.returned is what went
        // back, not what was meant to
        if (state != State.Running && craftLedger != null && !returnSettled) {
            if (host.craftReturning() && now - returnSince < RETURN_WAIT_MS) {
                return new Report(order.id, "in_progress", "putting crafted items back", Math.min(index, units.size()), units.size(), message, shulkersDelivered,
                    new LinkedHashMap<>(delivered), repair == null ? null : repair.toJson(), null);
            }
            Map<String, Long> still = host.craftStillCarried(), back = new LinkedHashMap<>();
            for (var e : returnPlanned.entrySet()) {
                long n = e.getValue() - Math.max(0, still.getOrDefault(e.getKey(), 0L));
                if (n > 0) back.put(e.getKey(), n);
            }
            craftLedger.setReturned(back);
            returnSettled = true;
        }
        String kp = state == State.Running ? kitPhase() : null;
        String shown = state == State.Running && host.rereading() ? "re-reading containers" : kp != null && !phase.startsWith("paused") ? kp : phase;
        JsonObject kitJson = kitResult == null || !kitResult.any() ? null : kitResult.toJson();
        return new Report(order.id, status, shown, Math.min(index, units.size()), units.size(), message, shulkersDelivered, new LinkedHashMap<>(delivered),
            repair == null ? null : repair.toJson(), craftLedger == null || !craftLedger.any() ? null : craftLedger.toResult(), kitJson);
    }

    // Repair (1.13.0, REPAIR-CONTRACT.md)

    /** The keys of the item lines wanted for repair (none when the order did not ask for any). */
    public java.util.Set<String> repairKeys() {
        return running() ? repairKeys : java.util.Set.of();
    }

    /** The keys of the lines that asked for repair themselves; the others are wanted only when damaged (contract v1.2). */
    public java.util.Set<String> repairExplicitKeys() {
        return running() ? repairExplicit : java.util.Set.of();
    }

    /** The repair result of the order so far, or null when nothing is wanted for repair. The host fills it in; {@link #repairChanged} saves it. */
    public Repair.@Nullable Report repairReport() {
        return repair;
    }

    /** The host changed the repair result: it is saved with the plan, so a restart keeps it. */
    public void repairChanged() {
        Order o = order;
        Saved s = o == null || repair == null ? null : host.saved(o.id);
        if (s == null) return;
        s.repair = repair.toSaved().toString();
        host.save(s);
    }

    /**
     * A repaired item of this bin has another key now (its damage is part of it). The lot that was asked for under the old key
     * and variant takes the new ones, so the stack still matches the bin, and the shulker's content is counted back to the key
     * the order asked for. Saved with the plan.
     * @return false when the unit has no such lot
     */
    public boolean rekey(Unit u, String oldKey, @Nullable String oldVariant, String newKey, @Nullable String newVariant) {
        int at = -1;
        for (int i = 0; i < u.lots.size(); i++) {
            Order.Lot lot = u.lots.get(i);
            if (lot.key().equals(oldKey) && (oldVariant == null || lot.variant() == null || lot.variant().equals(oldVariant))) {
                at = i;
                break;
            }
        }
        if (at < 0) return false;
        Order.Lot lot = u.lots.get(at);
        // What the order asked for: the key before any earlier repair of this item
        String asked = oldKey;
        for (int i = u.rekeys.size() - 1; i >= 0; i--) {
            if (u.rekeys.get(i)[0].equals(oldKey)) {
                asked = u.rekeys.get(i)[1];
                u.rekeys.remove(i);
                break;
            }
        }
        u.lots.set(at, new Order.Lot(newKey, newVariant, lot.count()));
        if (!newKey.equals(asked)) u.rekeys.add(new String[] {newKey, asked});
        Order o = order;
        Saved s = o == null ? null : host.saved(o.id);
        int unit = units.indexOf(u);
        if (s != null && unit >= 0 && unit < s.units.size()) {
            Saved.UnitSave p = s.units.get(unit);
            if (at < p.lotKeys.size()) {
                p.lotKeys.set(at, newKey);
                p.lotVariants.set(at, newVariant == null ? "" : newVariant);
            }
            p.rekeys.clear();
            for (String[] r : u.rekeys) {
                p.rekeys.add(r[0]);
                p.rekeys.add(r[1]);
            }
            host.save(s);
        }
        return true;
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

    /** The host has started putting another chest up for this shulker: only runs really started count towards MAX_EXPANDS. */
    public void expandStarted(Unit u) {
        u.expands++;
    }

    /** A bin is about to be built. @return false after too many tries */
    public boolean noteBuildStart(Unit u) {
        return ++u.begins <= MAX_BEGINS;
    }

    /** The bin's finished shulker exists. */
    public void built(Unit u) {
        u.built = true;
        if (u.kitTemplate != null && kitResult != null && !u.kitShort) kitResult.built(u.kitTemplate);
    }

    /**
     * The bin could not be finished, but its shulker holds part of it: it is delivered as it is, not thrown back. From now on
     * the unit is what the shulker holds ({@code packed}, saved with the plan so a restart still knows the shulker for this
     * unit's), and the order's end note says it came short; "delivered less than asked" lists the lines.
     */
    public void builtShort(Unit u, List<Order.Lot> packed, String reason) {
        if (packed.isEmpty()) return;
        if (u.kitTemplate != null) {
            // A kit that cannot be completed is never delivered (it is named and kept when it holds two kinds, see next()); what it lacks is listed
            Map<String, Integer> have = BinTracker.lotTotals(packed);
            u.kitMissing.clear();
            for (Order.Lot lot : u.lots) {
                int got = have.getOrDefault(lot.key(), 0);
                int lack = Math.max(0, lot.count() - got);
                have.put(lot.key(), Math.max(0, got - lot.count()));
                if (lack > 0) u.kitMissing.add(new KitResult.Miss(lot.key(), lot.key(), lack));
            }
            u.lots.clear();
            u.lots.addAll(packed);
            u.kitShort = true;
            u.built = true;
            problems.add("kit " + (units.indexOf(u) + 1) + " built short: " + reason);
            return;
        }
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
        if (index < units.size()) {
            Unit ku = units.get(index);
            if (settleKit(ku)) kitResult.addShort(ku.kitTemplate, 1, ku.kitMissing, kitReasonOf(why), null);
            if (emptiesBack(ku)) host.kitEmptyBack(ku);
        }
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

            // A restock job delivers nothing: the sorter files its kit
            String problem = order != null && order.restock ? null : host.deliverySet() ? host.deliveryProblem() : "the delivery container is gone";
            if (problem != null) {
                finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, problem);
                return new Idle();
            }

            Unit u = units.get(index);
            int slot = host.finishedSlot(u);

            if (slot != -1) {
                if (!u.have) {
                    u.have = true;
                    u.content = new HashMap<>(Repair.translate(host.contentOf(u, slot), u.rekeys));
                }
                // Back in the inventory: whatever was seen in the container was not it
                u.confirmed = false;
                u.verifying = false;
                // Named first, if the order wants a name on it; naming never holds the order up
                if (u.name != null && !u.nameDone && u.nameFailure == null && !(u.kitShort && KitPlanner.kinds(u.lots) < 2)) {
                    Name name = naming(u, slot);
                    if (name != null) return name;
                }
                // A kit that cannot be completed is never delivered: named and kept (the sorter files it as an incomplete copy) when it holds two kinds,
                // else reported short and left to the put-back
                if (u.kitTemplate != null && (u.kitShort || (order != null && order.restock))) {
                    if (u.kitShort) {
                        boolean store = u.nameDone && KitPlanner.kinds(u.lots) >= 2;
                        // Not stored: the shulker is emptied and its items put back, never left mixed for the generic sorter
                        if (!store) host.kitEmptyBack(u);
                        if (settleKit(u)) kitResult.addShort(u.kitTemplate, 1, u.kitMissing, KitOrder.ITEMS_SHORT, store ? KitResult.STORED_INCOMPLETE : KitResult.STORED_RETURNED);
                    }
                    else settleKit(u);
                    // The kit (complete, or an incomplete copy) stays carried, named: the sorter files it in its chain target
                    advance();
                    continue;
                }
                if (full) {
                    full = false;
                    u.fullTries++;
                }
                // The delivery storage (1.9.0): which chest has room, whether to look at a chest a player may have emptied,
                // whether to put another up, or to wait. A host with the single container always says there is room.
                DeliveryRoom room = host.deliveryRoom();
                if (room.kind() == DeliveryRoom.Kind.RECHECK || room.kind() == DeliveryRoom.Kind.EXPAND) {
                    long at = clock.getAsLong();
                    if (at < spinHoldUntil) room = DeliveryRoom.waitFor("delivery storage full: could not start looking at or building a chest");
                    else if (++u.spins > MAX_SPINS) {
                        spinHoldUntil = at + SPIN_HOLD_MS;
                        u.spins = 0;
                        room = DeliveryRoom.waitFor("delivery storage full: could not start looking at or building a chest");
                    }
                }
                else if (room.kind() == DeliveryRoom.Kind.ROOM) {
                    u.expands = 0;
                    u.spins = 0;
                }
                if (room.kind() == DeliveryRoom.Kind.RECHECK && room.recheck() != null) {
                    phase = "checking delivery space";
                    return new Recheck(u, room.recheck());
                }
                if (room.kind() == DeliveryRoom.Kind.EXPAND) {
                    if (u.expands < MAX_EXPANDS) {
                        phase = "adding delivery storage";
                        return new Expand(u);
                    }
                    room = DeliveryRoom.waitFor("delivery storage full: could not put another chest up");
                }
                if (room.kind() == DeliveryRoom.Kind.WAIT) {
                    long now = clock.getAsLong();
                    String why = room.why() == null ? "delivery storage full" : room.why();
                    // Another reason is another chance to put a chest up
                    if (!why.equals(lastWaitWhy)) {
                        if (lastWaitWhy != null) u.expands = 0;
                        lastWaitWhy = why;
                    }
                    if (spaceWaitSince == 0) spaceWaitSince = now;
                    else if (now - spaceWaitSince > SPACE_WAIT_TIMEOUT_MS) {
                        finish(shulkersDelivered > 0 ? State.Delivered : State.Failed, "delivery container full (" + shulkersDelivered + "/" + units.size() + "): waited "
                            + (SPACE_WAIT_TIMEOUT_MS / 60_000) + " min for space, " + why);
                        return new Idle();
                    }
                    phase = "paused: " + WAITING_FOR_SPACE + why;
                    return new Idle();
                }
                spaceWaitSince = 0;
                if (u.fullTries >= MAX_FULL_TRIES + Math.max(0, host.deliveryChests() - 1)) {
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
                    if (settleKit(u)) kitResult.delivered(u.kitTemplate);
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
                // Its craft loads (1.15.0) are made first, a chunk at a time; what can't be made is short and the bin goes on without it
                if (craftLedger != null && u.nextCraftLot() >= 0) {
                    long now = clock.getAsLong();
                    if (u.craftLoadsWaitSince == 0) u.craftLoadsWaitSince = now;
                    if (now - u.craftLoadsWaitSince > CRAFT_START_WAIT_MS || u.craftLoadRounds >= MAX_CRAFT_ROUNDS) {
                        for (int i = u.lots.size() - 1; i >= 0; i--) if (u.lots.get(i).craft()) craftLotShort(u, i, CraftOrder.TIMEOUT);
                        continue;
                    }
                    phase = craftPhase(u.lots.get(nextCraftLot(u)).key(), 0);
                    return new CraftLoads(u);
                }
                // Nothing of it could be made, and it has nothing else: no shulker is built, and no reason is added (the craft note has it)
                if (u.lots.isEmpty()) {
                    advance();
                    continue;
                }
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
            if (repair != null) s.repair = repair.toSaved().toString();
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

        boolean anything = shulkersDelivered > 0 || o.restock;
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
        return withNotes(text, unnamedNote, craftedNote, null);
    }

    /** As above, and the note about items not fully repaired (1.13.0) after them. */
    static String withNotes(String text, @Nullable String unnamedNote, @Nullable String craftedNote, @Nullable String repairNote) {
        return withNotes(text, unnamedNote, craftedNote, repairNote, null);
    }

    /** As above, and the note about craft lines not crafted (1.15.0) last: "<n> item(s) not crafted: <reasons>". */
    static String withNotes(String text, @Nullable String unnamedNote, @Nullable String craftedNote, @Nullable String repairNote, @Nullable String craftOrderNote) {
        List<String> tail = new ArrayList<>();
        if (unnamedNote != null && !text.contains(ShulkerNames.UNNAMED_MARK)) tail.add(unnamedNote);
        if (craftedNote != null && !text.contains(craftedNote)) tail.add(craftedNote);
        if (repairNote != null && !text.contains(repairNote)) tail.add(repairNote);
        if (craftOrderNote != null && !text.contains(craftOrderNote)) tail.add(craftOrderNote);
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
        // The craft lines still open are short (cancelled, or the order ended first); what is carried of them goes back
        if (craftLedger != null) {
            craftLedger.closeOpen(end == State.Cancelled ? CraftOrder.CANCELLED : CraftOrder.INTERRUPTED);
            craftLedger.setDelivered(delivered, order.requested());
            returnPlanned = host.craftCarried();
            host.craftWatch(returnPlanned);
            returnSince = clock.getAsLong();
            returnSettled = false;
        }
        // The kits still open are short (cancelled, or the order ended first)
        if (kitResult != null) {
            for (int i = Math.max(0, index); i < units.size(); i++) {
                Unit ku = units.get(i);
                if (settleKit(ku)) {
                    kitResult.addShort(ku.kitTemplate, 1, ku.kitMissing, end == State.Cancelled ? KitOrder.CANCELLED : KitOrder.INTERRUPTED, null);
                    if (emptiesBack(ku)) host.kitEmptyBack(ku);
                }
            }
        }
        // Every way an order ends carries the one note about shulkers that went without their names
        // Added once: endNote may already hold it (perhaps cut short at MESSAGE_MAX); the whole message stays within MESSAGE_MAX
        text = withNotes(text, unnamedNote(), craftedNote(), repair == null ? null : repair.note(), craftLedger == null ? null : craftLedger.note());
        String kitNote = kitResult == null ? null : kitResult.note();
        if (kitNote != null && !text.contains(kitNote)) text = text.isEmpty() ? kitNote : cutTo(text, Math.max(20, MESSAGE_MAX - kitNote.length() - 2)) + "; " + kitNote;
        state = end;
        message = text;
        phase = end.name().toLowerCase();
        host.say((order.restock ? "Kit restock " : "Order " + order.id + " ") + phase + (text.isEmpty() ? "." : ": " + text));
    }
}
