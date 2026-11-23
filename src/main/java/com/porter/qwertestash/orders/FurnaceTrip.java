package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * One trip to the furnace (CRAFT-CONTRACT-v1.2.md 14): walk there, open it, and for each batch put the fuel and then the input in, wait for the output,
 * take the output as it comes, and when it is all done take the leftover fuel (and anything else of ours) back out before the window is closed.
 * Modelled on {@link CraftTrip}: manual clicks through the {@link Port}, every step has a time limit, whatever goes wrong ends in an {@link Outcome},
 * one {@link #tick} per game tick on the bot tick thread. The world is behind {@link Port}, so the decisions are tested with a fake furnace.
 * <ul>
 *     <li><b>Batches</b> are at most 64 items (and a stack) of one input kind. The fuel of a step is put in per batch as the difference of two
 *     cumulative amounts ({@code ceil(cook * itemsSoFar / ticks)}), so what is left burning from one batch is used by the next, and the sum is never
 *     more than the {@code ceil} of the whole. The fuel goes in before the input (nothing burns while the input slot is empty, so the count is stable).</li>
 *     <li><b>Busy:</b> a furnace that holds something when the trip opens it is {@link CraftOrder#FURNACE_BUSY}, unless it is the bot's own and holds
 *     only what smelting uses (leftovers of an earlier run): that is taken out first. Nothing of a busy furnace is ever touched.</li>
 *     <li><b>Timeouts:</b> no progress (the input does not go down, nothing comes out) for {@code 2 * cook + 30 s} is {@link CraftOrder#SMELT_TIMEOUT}; so
 *     is the whole trip running over its limit. What is in the furnace is then taken out as for any other end.</li>
 *     <li><b>Journal:</b> every move that was seen in the game is entered in the {@link SmeltJournal} (the caller saves the order through
 *     {@code onChange}), so a restart can take up what is in the furnace ({@link #recover}).</li>
 *     <li><b>XP:</b> taking an output gives the bot the furnace's stored experience as orbs that fly to it; after the last take the trip waits a moment
 *     before it walks away, and reports the points gained ({@link Outcome#xpGained}).</li>
 * </ul>
 */
public final class FurnaceTrip {
    /** What the trip needs from the world. Calls that ask for something return false (or -1) when it can't be asked now. */
    public interface Port {
        boolean arrived();

        boolean walk();

        boolean pathing();

        /** A furnace window that is ours is open. */
        boolean furnaceOpen();

        /** Some other window of ours is open (a late answer to an earlier click): it is closed first. */
        boolean otherWindowOpen();

        /** Right-clicks the furnace. */
        boolean open();

        default boolean openPaused() {
            return false;
        }

        /** The block at the furnace is still a furnace (true while its chunk is not loaded: nothing is concluded from a guess). */
        default boolean furnaceStands() {
            return true;
        }

        default void opened() {
        }

        /** Inventory clicks of ours are still going out. */
        boolean busy();

        /** What the run may put into the furnace that the inventory holds, by kind (the item id, with its components when it has any). */
        Map<String, Integer> carried();

        /** The kind of stack in this furnace slot (0 input, 1 fuel, 2 output), or null when it is empty. */
        @Nullable String itemAt(int slot);

        int countAt(int slot);

        /** Brings this furnace slot up to {@code goal} of {@code kind} from carried stacks, as one request. False: nothing was sent. */
        boolean place(int slot, String kind, int goal);

        boolean holdingCursor();

        /** Puts the cursor stack into a free slot. @return the slot, or -1 when there is no room */
        int stowCursor();

        /** Moves the stack in this furnace slot into a carry slot it fits in whole (two left clicks). @return the carry slot, or -1 when there is no room (no click) */
        int take(int slot);

        boolean close();

        /** The experience points the bot holds now, or -1 when not known. */
        default double xp() {
            return -1;
        }
    }

    /**
     * One load of the furnace: {@code count} items of the input kind {@code in} (which make {@code count * perItem} of {@code out}), and the
     * {@code fuelInc} items of {@code fuel} to be added to the fuel slot before it (0: what is there burns on).
     */
    public record Batch(String recipe, String out, String in, int count, int perItem, @Nullable String fuel, int fuelInc) {
        public int outCount() {
            return count * perItem;
        }
    }

    /**
     * How the trip ended.
     * @param failure   null when everything was done, else the {@link CraftOrder} reason ({@code furnace_blocked}, {@code furnace_busy}, {@code smelt_timeout},
     *                  {@code inventory_full}, {@code ingredients_short}, {@code interrupted})
     * @param retry     it was cut short (the window closed, the bot gave way) and may be made once more
     * @param stuck     something is still in the furnace with nowhere to go: the window was left open for a person
     * @param collected what was taken out of the output slot, by item id (all of it is in the inventory now)
     * @param returned  what was taken back out of the input and fuel slots, by item id
     * @param xpGained  the experience points gained during the trip (0 when not known)
     */
    public record Outcome(@Nullable String failure, @Nullable String detail, boolean retry, boolean stuck, Map<String, Long> collected, Map<String, Long> returned, double xpGained) {
        public boolean done() {
            return failure == null;
        }

        /** How many of this item were taken out of the output slot. */
        public int made(String id) {
            return (int) Math.min(Integer.MAX_VALUE, collected.getOrDefault(Recipes.ns(id), 0L));
        }

        public int smelted() {
            long n = 0;
            for (long c : collected.values()) n += c;
            return (int) Math.min(Integer.MAX_VALUE, n);
        }
    }

    public enum Step { Walk, Open, Check, Clear, Fuel, Input, Wait, Take, Taken, Return, Settle, Close, Done }

    // Ticks (20 a second)
    public static final int WALK_TICKS = 20 * 60;
    public static final int OPEN_TICKS = 20 * 15;
    public static final int OPEN_TRIES = 4;
    public static final int OPEN_RETRY_TICKS = 40;
    public static final int PLACE_TICKS = 20 * 10;
    public static final int CHECK_TICKS = 20 * 5;
    public static final int SETTLE_TICKS = 4;
    public static final int CLOSE_TICKS = 20 * 10;
    public static final int RETURN_TICKS = 20 * 60;
    public static final int XP_SETTLE_TICKS = 30;
    public static final int MAX_PLACES = 24, MAX_TAKES = 80, MAX_STOWS = 8, MAX_REOPENS = 3;
    /** The trip may take this long beyond the time its items need to cook (a quarter more, and a minute a batch). */
    public static final int TRIP_BASE_TICKS = 20 * 180, BATCH_EXTRA_TICKS = 20 * 60;

    public static final String NOT_REACHED = "couldn't reach the furnace", NOT_OPENED = "couldn't open the furnace", CLOSED = "the furnace closed before it was done",
        NO_PROGRESS = "the furnace made nothing in time", NOT_TAKEN = "the furnace didn't give the output", NO_ROOM = "no room in the inventory for what the furnace made",
        GONE = "the furnace is not there any more", MISSING = "the inputs aren't all in the inventory", TOO_LONG = "smelting took too long", BUSY = "something else is in the furnace";

    /** The furnace's slots. */
    public static final int INPUT = 0, FUEL = 1, OUTPUT = 2;

    private final Port port;
    private final List<Batch> batches;
    private final SmeltJournal journal;
    private final Runnable onChange;
    private final int cookTicks;
    private final boolean ownFurnace, recoverMode;
    private final Set<String> known;
    private final int limit;

    private Step step = Step.Walk;
    private int ticks, stepTicks, sinceClick, tries, idx, reopens, places, takes, stows, settleLeft;
    private int lastProgress, lastIn = -1, lastOut = -1, fuelBase = -1, batchGot;
    private boolean fuelDone, inputDone, ours, wasEmpty, clearedOnKnownIds;
    private int takeSlot, takeN;
    private @Nullable String takeId;
    private Step afterTake = Step.Wait;
    private @Nullable Step resumeStep;
    private double xpBefore = -1;
    private final Map<String, Long> collected = new LinkedHashMap<>(), returned = new LinkedHashMap<>();
    private @Nullable Outcome outcome, pending;
    /** The ids the bot-level furnace debt (FurnaceDebt) says are the bot's own in this furnace, besides the journal's. */
    private Set<String> debtIds = Set.of();

    /**
     * @param batches    the loads in order (empty for a recovery)
     * @param ownFurnace the furnace is the bot's own (put down or crafted by it): leftovers of smelting in it are taken out instead of being busy
     * @param known      item ids a leftover may be made of for {@code ownFurnace} (the smelt inputs, outputs and fuels of the extras)
     * @param recover    take up what a stopped run left in the furnace: nothing is loaded, what cooks on is waited for and everything is taken out
     */
    public FurnaceTrip(Port port, List<Batch> batches, SmeltJournal journal, Runnable onChange, int cookTicks, boolean ownFurnace, Set<String> known, boolean recover) {
        this.port = port;
        this.batches = batches;
        this.journal = journal;
        this.onChange = onChange;
        this.cookTicks = Math.max(1, cookTicks);
        this.ownFurnace = ownFurnace;
        this.known = known;
        this.recoverMode = recover;
        long items = 0;
        for (Batch b : batches) items += b.count();
        if (recover) items = Smelting.MAX_BATCH;
        this.limit = (int) Math.min(Integer.MAX_VALUE / 2, TRIP_BASE_TICKS + items * this.cookTicks * 5 / 4 + (long) Math.max(1, batches.size()) * BATCH_EXTRA_TICKS);
    }

    /** A trip that takes up what a stopped run (the journal says which) left in the furnace: nothing is loaded. */
    public static FurnaceTrip recover(Port port, SmeltJournal journal, Runnable onChange, int cookTicks, boolean ownFurnace, Set<String> known) {
        return new FurnaceTrip(port, List.of(), journal, onChange, cookTicks, ownFurnace, known, true);
    }

    /**
     * The batches for the furnace steps of a plan: exactly one per step (CRAFT-CONTRACT-v1.2.md, B2 review: a step is one input kind and at most 64
     * items; a bot loads a step's whole input in one go, after its fuel, and never merges steps). The fuel is the step's own, of its first fuel kind.
     */
    public static List<Batch> batches(List<CraftPlanner.Step> steps, Smelting smelting, ToIntFunction<String> maxStack) {
        List<Batch> out = new ArrayList<>();
        for (CraftPlanner.Step s : steps) {
            if (!s.furnace()) continue;
            String fuel = null;
            long count = 0;
            if (s.fuel() != null) for (var e : s.fuel().entrySet()) {
                fuel = e.getKey();
                count = e.getValue();
                break;
            }
            for (var in : s.uses().entrySet()) {
                int n = (int) Math.min(in.getValue(), Math.max(1, Math.min(Smelting.MAX_BATCH, maxStack.applyAsInt(in.getKey()))));
                out.add(new Batch(s.recipe().id(), s.recipe().result(), in.getKey(), n, s.recipe().count(), fuel, (int) count));
                // (a step has one input kind; a second one is never merged into the first's load)
                count = 0;
            }
        }
        return out;
    }

    /** Leftovers of these ids (the furnace debt's) are the bot's own too, for a furnace of its own. */
    public FurnaceTrip withDebtIds(Set<String> ids) {
        this.debtIds = ids;
        return this;
    }

    public Step step() {
        return step;
    }

    public int limit() {
        return limit;
    }

    public @Nullable Outcome outcome() {
        return outcome;
    }

    /** Items taken out of the output slot so far, in all. */
    public int smelted() {
        long n = 0;
        for (long c : collected.values()) n += c;
        return (int) Math.min(Integer.MAX_VALUE, n);
    }

    /** The batch under way (0 first). */
    /** The furnace was seen empty when the trip opened it (so a later trip may prefer it). */
    public boolean wasEmpty() {
        return wasEmpty;
    }

    /** The furnace's leftovers were cleared only because they were things smelting uses, in the bot's own furnace (the journal did not say they were its own). */
    public boolean clearedOnKnownIds() {
        return clearedOnKnownIds;
    }

    public int batchIndex() {
        return idx;
    }

    /** The furnace is being worked on: its window may hold something of ours. */
    public boolean inUse() {
        return ours && outcome == null;
    }

    private void go(Step next) {
        step = next;
        stepTicks = 0;
    }

    private @Nullable Batch batch() {
        return idx < batches.size() ? batches.get(idx) : null;
    }

    private boolean furnaceEmpty() {
        for (int s = INPUT; s <= OUTPUT; s++) if (port.itemAt(s) != null) return false;
        return true;
    }

    private @Nullable String idOf(@Nullable String kind) {
        return kind == null ? null : Recipes.ns(CraftSlots.baseOf(kind));
    }

    private void pend(@Nullable String failure, @Nullable String detail, boolean retry) {
        if (pending == null) pending = new Outcome(failure, detail, retry, false, Map.of(), Map.of(), 0);
    }

    /** Ends the trip with this reason: what is ours in the furnace is taken out first (nothing of a furnace that isn't ours), then the window is closed. */
    private void end(@Nullable String failure, @Nullable String detail, boolean retry) {
        if (pending == null) pend(failure, detail, retry);
        afterTake = Step.Return;
        go(ours ? Step.Return : Step.Close);
    }

    /** Stops the trip now (the bot gives way, the order ends): the furnace is emptied, the window closed, the try may be made again. */
    public void abort(String why) {
        if (step == Step.Done || step == Step.Close || step == Step.Settle || step == Step.Return) return;
        pend(CraftOrder.INTERRUPTED, why, true);
        if (step == Step.Take || step == Step.Taken) {
            afterTake = Step.Return;
            return;
        }
        afterTake = Step.Return;
        if (!ours) go(Step.Close);
        else if (port.furnaceOpen()) go(Step.Return);
        else {
            // The window is closed: opened again to take what is the bot's out
            go(Step.Return);
            reopen();
        }
    }

    private void finish(boolean stuck) {
        Outcome was = pending;
        double now = port.xp();
        double xp = xpBefore < 0 || now < 0 ? 0 : Math.max(0, now - xpBefore);
        outcome = new Outcome(was == null ? null : was.failure(), was == null ? null : was.detail(), was != null && was.retry(), stuck, Map.copyOf(collected), Map.copyOf(returned), xp);
        go(Step.Done);
    }

    private void stuckTimer() {
        if (stepTicks > RETURN_TICKS) {
            pend(CraftOrder.INVENTORY_FULL, NO_ROOM, false);
            finish(true);
        }
    }

    public void tick() {
        if (step == Step.Done) return;
        ticks++;
        stepTicks++;
        sinceClick++;
        boolean winding = step == Step.Return || step == Step.Take || step == Step.Taken || step == Step.Close || step == Step.Settle;
        if (ticks > limit && pending == null && !winding) end(CraftOrder.SMELT_TIMEOUT, TOO_LONG, false);
        if (ticks > limit + RETURN_TICKS + CLOSE_TICKS * 2) {
            pend(CraftOrder.SMELT_TIMEOUT, TOO_LONG, false);
            finish(port.furnaceOpen() && ours && !furnaceEmpty());
            return;
        }
        // A stack on the cursor goes into a free slot before anything in the furnace is touched
        boolean working = step != Step.Walk && step != Step.Open && step != Step.Settle;
        if (working && port.furnaceOpen() && !port.busy() && sinceClick >= SETTLE_TICKS && port.holdingCursor()) {
            if (stows < MAX_STOWS && port.stowCursor() >= 0) {
                stows++;
                sinceClick = 0;
                return;
            }
            if (step == Step.Return || step == Step.Take || step == Step.Taken) stuckTimer();
            else end(CraftOrder.INVENTORY_FULL, NO_ROOM, false);
            return;
        }
        switch (step) {
            case Walk -> {
                if (port.arrived() && !port.furnaceStands()) {
                    // Arrived, and there is no furnace: no_furnace, and the record of it is dropped by the host
                    pend(CraftOrder.NO_FURNACE, GONE, false);
                    go(Step.Close);
                    return;
                }
                if (port.arrived()) {
                    go(Step.Open);
                    sinceClick = OPEN_RETRY_TICKS;
                    return;
                }
                if (stepTicks > WALK_TICKS) {
                    pend(CraftOrder.FURNACE_BLOCKED, NOT_REACHED, false);
                    go(Step.Close);
                    return;
                }
                if (!port.pathing()) port.walk();
            }
            case Open -> {
                if (!port.furnaceOpen() && !port.furnaceStands()) {
                    pend(CraftOrder.NO_FURNACE, GONE, false);
                    go(Step.Close);
                    return;
                }
                if (port.furnaceOpen()) {
                    port.opened();
                    if (resumeStep != null) {
                        Step back = resumeStep;
                        resumeStep = null;
                        go(back);
                        return;
                    }
                    xpBefore = port.xp();
                    go(Step.Check);
                    return;
                }
                if (port.otherWindowOpen()) {
                    port.close();
                    return;
                }
                // Held back by the container delay: no click, and no tick counts against the open timeouts or the retry pause
                if (port.openPaused()) {
                    ticks--;
                    stepTicks--;
                    sinceClick--;
                    return;
                }
                if (stepTicks > OPEN_TICKS || (tries >= OPEN_TRIES && sinceClick > OPEN_RETRY_TICKS)) {
                    // Opened before in this trip (and closed since): cut short, may be made again; never opened: the furnace can't be used
                    if (resumeStep != null) {
                        // Opened before and not any more: what is ours may still be inside
                        pend(CraftOrder.INTERRUPTED, CLOSED, false);
                        if (ours) {
                            finish(true);
                            return;
                        }
                    }
                    else pend(CraftOrder.FURNACE_BLOCKED, NOT_OPENED, false);
                    go(Step.Close);
                    return;
                }
                if (tries < OPEN_TRIES && sinceClick >= OPEN_RETRY_TICKS && !port.pathing() && port.open()) {
                    tries++;
                    sinceClick = 0;
                }
            }
            case Check -> {
                if (!port.furnaceOpen()) {
                    pend(CraftOrder.INTERRUPTED, CLOSED, true);
                    go(Step.Close);
                    return;
                }
                if (port.busy() || stepTicks < SETTLE_TICKS) return;
                if (recoverMode) {
                    // What a stopped run left: the input that still cooks is waited for (when there is fuel to cook it), then everything is taken out
                    ours = true;
                    boolean cooks = port.itemAt(INPUT) != null && port.itemAt(FUEL) != null;
                    lastIn = port.countAt(INPUT);
                    lastProgress = 0;
                    afterTake = cooks ? Step.Wait : Step.Return;
                    go(afterTake);
                    return;
                }
                if (furnaceEmpty()) {
                    ours = true;
                    wasEmpty = true;
                    go(Step.Fuel);
                    return;
                }
                if (ownFurnace && leftoverIsOurs(false)) {
                    ours = true;
                    afterTake = Step.Clear;
                    go(Step.Clear);
                    return;
                }
                // The bot's own furnace, leftovers of what smelting uses, nothing in the journal: cleared on that basis, unless a smelt is going on in it
                // (input and fuel both there: somebody else's cook is left alone)
                if (ownFurnace && leftoverIsOurs(true) && !(port.itemAt(INPUT) != null && port.itemAt(FUEL) != null)) {
                    ours = true;
                    clearedOnKnownIds = true;
                    afterTake = Step.Clear;
                    go(Step.Clear);
                    return;
                }
                pend(CraftOrder.FURNACE_BUSY, BUSY, false);
                go(Step.Close);
            }
            case Clear -> {
                if (!port.furnaceOpen()) {
                    reopen();
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                int slot = firstFilled();
                if (slot < 0) {
                    go(Step.Fuel);
                    return;
                }
                startTake(slot, Step.Clear);
            }
            case Fuel -> {
                if (!port.furnaceOpen()) {
                    reopen();
                    return;
                }
                Batch b = batch();
                if (b == null) {
                    end(null, null, false);
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                if (b.fuel() == null || b.fuelInc() <= 0) {
                    go(Step.Input);
                    return;
                }
                String there = port.itemAt(FUEL);
                // Another kind of fuel in the slot (an earlier step's) is taken out first
                if (there != null && !there.equals(b.fuel())) {
                    startTake(FUEL, Step.Fuel);
                    return;
                }
                if (fuelBase < 0) fuelBase = there == null ? 0 : port.countAt(FUEL);
                int goal = Math.min(64, fuelBase + b.fuelInc());
                if (there != null && port.countAt(FUEL) >= goal) {
                    if (!fuelDone) {
                        journal.placed(idOf(b.fuel()), b.fuelInc());
                        onChange.run();
                        fuelDone = true;
                    }
                    go(Step.Input);
                    return;
                }
                if (port.carried().getOrDefault(b.fuel(), 0) + port.countAt(FUEL) < goal) {
                    end(CraftOrder.INGREDIENTS_SHORT, MISSING + " (fuel)", false);
                    return;
                }
                if (places >= MAX_PLACES) {
                    end(CraftOrder.INTERRUPTED, "the fuel didn't go in", false);
                    return;
                }
                if (port.place(FUEL, b.fuel(), goal)) {
                    places++;
                    sinceClick = 0;
                }
                else if (stepTicks > PLACE_TICKS) end(CraftOrder.INTERRUPTED, "the fuel didn't go in", false);
            }
            case Input -> {
                if (!port.furnaceOpen()) {
                    reopen();
                    return;
                }
                Batch b = batch();
                if (b == null) {
                    end(null, null, false);
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                String there = port.itemAt(INPUT);
                if (there != null && there.equals(b.in()) && port.countAt(INPUT) >= b.count()) {
                    if (!inputDone) {
                        journal.placed(idOf(b.in()), b.count());
                        journal.loaded(new SmeltJournal.Active(b.recipe(), idOf(b.in()), b.count(), b.out(), b.outCount(), 0, b.fuel() == null ? "" : idOf(b.fuel())));
                        onChange.run();
                        inputDone = true;
                    }
                    lastIn = port.countAt(INPUT);
                    lastProgress = 0;
                    afterTake = Step.Wait;
                    go(Step.Wait);
                    return;
                }
                if (there != null && !there.equals(b.in())) {
                    startTake(INPUT, Step.Input);
                    return;
                }
                if (port.carried().getOrDefault(b.in(), 0) + port.countAt(INPUT) < b.count()) {
                    end(CraftOrder.INGREDIENTS_SHORT, MISSING, false);
                    return;
                }
                if (places >= MAX_PLACES) {
                    end(CraftOrder.INTERRUPTED, "the input didn't go in", false);
                    return;
                }
                if (port.place(INPUT, b.in(), b.count())) {
                    places++;
                    sinceClick = 0;
                }
                else if (stepTicks > PLACE_TICKS) end(CraftOrder.INTERRUPTED, "the input didn't go in", false);
            }
            case Wait -> {
                if (!port.furnaceOpen()) {
                    reopen();
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                int in = port.countAt(INPUT), out = port.countAt(OUTPUT);
                if (in != lastIn || out != lastOut) {
                    lastIn = in;
                    lastOut = out;
                    lastProgress = stepTicks;
                }
                if (in == 0 && port.itemAt(INPUT) == null) {
                    // Everything is cooked: the output is taken in one go, then the batch is done
                    if (out > 0) {
                        startTake(OUTPUT, Step.Wait);
                        return;
                    }
                    // The server may still be sending the last item: a few seconds' grace before a short batch counts as done
                    Batch cur = batch();
                    if (!recoverMode && cur != null && batchGot < cur.outCount() && stepTicks - lastProgress < 60) return;
                    batchGot = 0;
                    takes = 0;
                    journal.batchDone();
                    onChange.run();
                    if (recoverMode) {
                        afterTake = Step.Return;
                        go(Step.Return);
                        return;
                    }
                    idx++;
                    fuelBase = -1;
                    fuelDone = false;
                    inputDone = false;
                    places = 0;
                    if (idx >= batches.size()) {
                        end(null, null, false);
                        return;
                    }
                    go(Step.Fuel);
                    return;
                }
                if (stepTicks - lastProgress > 2 * cookTicks + 600) end(CraftOrder.SMELT_TIMEOUT, NO_PROGRESS, false);
            }
            case Take -> {
                if (!port.furnaceOpen()) {
                    reopen();
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                String there = port.itemAt(takeSlot);
                if (there == null || port.countAt(takeSlot) < 1) {
                    go(afterTake);
                    return;
                }
                if (++takes > MAX_TAKES) {
                    pend(CraftOrder.INTERRUPTED, NOT_TAKEN, false);
                    finish(true);
                    return;
                }
                takeId = idOf(there);
                takeN = port.countAt(takeSlot);
                int into = port.take(takeSlot);
                if (into < 0) {
                    takes--;
                    // No room: waited for. Taking things out at the end: for a minute, then the window is left open for a person. Mid-run: the trip ends
                    // (what is ours is then taken out the same way)
                    if (afterTake == Step.Return) stuckTimer();
                    else if (stepTicks > PLACE_TICKS) {
                        pend(CraftOrder.INVENTORY_FULL, NO_ROOM, false);
                        afterTake = Step.Return;
                        takeSlot = OUTPUT;
                        stepTicks = 0;
                    }
                    return;
                }
                sinceClick = 0;
                go(Step.Taken);
            }
            case Taken -> {
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                int now = port.itemAt(takeSlot) == null ? 0 : port.countAt(takeSlot);
                if (now >= takeN) {
                    // The server has not answered yet (or turned the click down): looked at again, then taken again
                    if (!port.furnaceOpen()) {
                        reopen();
                        return;
                    }
                    if (stepTicks > CHECK_TICKS) go(Step.Take);
                    return;
                }
                int got = takeN - now;
                String id = takeId == null ? "" : takeId;
                if (afterTake == Step.Clear) {
                    // Leftovers of an earlier run taken out of the way: nobody's product or input of this run, not entered anywhere
                }
                else if (takeSlot == OUTPUT) {
                    collected.merge(id, (long) got, Long::sum);
                    journal.collected(id, got);
                    batchGot += got;
                }
                else returned.merge(id, (long) got, Long::sum);
                onChange.run();
                lastProgress = 0;
                go(afterTake);
            }
            case Return -> {
                if (!port.furnaceOpen()) {
                    // Closed under us: opened again to take what is the bot's out (never closed over it)
                    reopen();
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                int slot = OUTPUT;
                while (slot >= INPUT && port.itemAt(slot) == null) slot--;
                if (slot < INPUT) {
                    journal.batchDone();
                    onChange.run();
                    settleLeft = collected.isEmpty() ? 0 : XP_SETTLE_TICKS;
                    go(settleLeft > 0 ? Step.Settle : Step.Close);
                    return;
                }
                startTake(slot, Step.Return);
            }
            case Settle -> {
                if (--settleLeft <= 0) go(Step.Close);
            }
            case Close -> {
                if (port.busy()) return;
                // Never closed with a stack on the cursor
                if (port.furnaceOpen() && port.holdingCursor() && stows < MAX_STOWS && port.stowCursor() >= 0) {
                    stows++;
                    sinceClick = 0;
                    return;
                }
                if (port.close() || stepTicks > CLOSE_TICKS) finish(false);
            }
            case Done -> {
            }
        }
    }

    private int firstFilled() {
        for (int s = INPUT; s <= OUTPUT; s++) if (port.itemAt(s) != null) return s;
        return -1;
    }

    private boolean leftoverIsOurs(boolean includeKnown) {
        for (int s = INPUT; s <= OUTPUT; s++) {
            String k = port.itemAt(s);
            // Only what the journal says the bot put in or took out is its own (the leftovers of a stopped run); anything else is somebody else's
            // (a furnace of the bot's own: also what smelting uses, so leftovers of a crash between a click and its journal entry are not taken for somebody else's)
            if (k == null) continue;
            boolean mine = journal.placed().containsKey(idOf(k)) || journal.produced().containsKey(idOf(k)) || debtIds.contains(idOf(k)) || (includeKnown && ownFurnace && known.contains(idOf(k)));
            if (!mine) return false;
        }
        return true;
    }

    private void startTake(int slot, Step back) {
        takeSlot = slot;
        afterTake = back;
        go(Step.Take);
    }

    /** The window was closed under the trip (by the server, or a player): it is opened again, a few times, and the trip goes on where it was. */
    private void reopen() {
        if (reopens >= MAX_REOPENS) {
            pend(CraftOrder.INTERRUPTED, CLOSED, false);
            // Something of ours may still be inside: stuck, never a clean end
            if (ours) finish(true);
            else go(Step.Close);
            return;
        }
        reopens++;
        tries = 0;
        sinceClick = OPEN_RETRY_TICKS;
        resumeStep = step == Step.Taken ? Step.Take : step;
        go(Step.Open);
    }
}
