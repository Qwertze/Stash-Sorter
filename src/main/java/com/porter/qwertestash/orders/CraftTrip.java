package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * One trip to the crafting table: walk there, open it, then for each step of a {@link CraftPlanner.Plan} put exact counts
 * into the grid (manual clicks, never the recipe book), take the result with a left click on slot 0 into a carry slot (never
 * a shift-click), wait for the server to show the grid used up before the next take, and when it is all made take anything
 * left in the grid back out before the window is closed. A window with items in its grid is never closed: with no room to
 * take them back it is left open for a person. Every step has a time limit; whatever goes wrong ends in an {@link Outcome}.
 * The world is behind {@link Port}, so the decisions are tested without the game; one {@link #tick} per game tick, on the
 * bot tick thread. Modelled on {@link AnvilNamer}.
 */
public final class CraftTrip {
    /** What the trip needs from the world. Calls that ask for something return false (or -1) when it can't be asked now. */
    public interface Port {
        /** Within reach of the crafting table. */
        boolean arrived();

        /** Asks to walk to within reach of the table. */
        boolean walk();

        /** A walk or open request of ours is still running. */
        boolean pathing();

        /** A crafting table window that is ours is open. */
        boolean tableOpen();

        /** Some other window of ours is open (a late answer to an earlier click): it is closed first. */
        boolean otherWindowOpen();

        /** Right-clicks the table. */
        boolean open();

        /** The container delay holds the open click back this tick (the open timeouts and retry pause do not run meanwhile). */
        default boolean openPaused() {
            return false;
        }

        /** The table window has just been seen open. */
        default void opened() {
        }

        /** Inventory clicks of ours are still going out. */
        boolean busy();

        /**
         * What the carried stacks the craft may use hold, by kind ({@link CraftSlots#kind}: the item id, with its components
         * when it has any; stacks of different kinds can't share a grid slot).
         */
        Map<String, Integer> carried();

        /** The kind of stack in this table slot (0 result, 1-9 grid), or null when it is empty. */
        @Nullable String itemAt(int slot);

        /** How many are in this table slot (0 when empty). */
        int countAt(int slot);

        /** Brings this grid slot up to {@code count} of {@code item} (a kind) from one carried stack, as one request. */
        boolean place(int gridSlot, String item, int count);

        /** The stack size of a kind of carried stack (an item id, with its components when it has any): a batch never gives a grid slot more. */
        default int maxStack(String kind) {
            return 64;
        }

        /** A stack is on the cursor (a click of ours the server turned down, say). */
        boolean holdingCursor();

        /** Puts the cursor stack into a free slot. @return the slot, or -1 when there is no room (nothing is clicked then) */
        int stowCursor();

        /**
         * Takes the result (left click on slot 0) and puts it into a carry slot it fits in whole (one with the same item and
         * room, else a free one). @return the carry slot, or -1 when there is no room (nothing is clicked then)
         */
        int take(String item, int count);

        /** Moves what is in this grid slot back into a carry slot. @return the slot, or -1 when there is no room (no click) */
        int retrieve(int gridSlot);

        /** Closes our open window; true once nothing of ours is open. */
        boolean close();
    }

    /**
     * How the trip ended.
     * @param failure  why it stopped short, or null when everything was made
     * @param retry    it was cut short (the window closed, the bot gave way) and may be made once more
     * @param tableBad the table itself could not be used (reached, opened, no result): not tried again for this task
     * @param stuck    items are still in the grid with nowhere to put them: the window was left open for a person
     * @param made     how many of the wanted item were made and taken
     */
    public record Outcome(@Nullable String failure, boolean retry, boolean tableBad, boolean stuck, int made) {
        public boolean done() {
            return failure == null;
        }
    }

    public enum Step { Walk, Open, Prepare, Fill, Result, Taken, Retrieve, Close, Done }

    // Ticks (20 a second)
    public static final int WALK_TICKS = 20 * 60;
    public static final int OPEN_TICKS = 20 * 15;
    public static final int OPEN_TRIES = 4;
    public static final int OPEN_RETRY_TICKS = 40;
    public static final int FILL_TICKS = 20 * 10;
    public static final int RESULT_TICKS = 20 * 10;
    public static final int CHECK_TICKS = 20 * 5;
    public static final int SETTLE_TICKS = 4;
    public static final int CLOSE_TICKS = 20 * 10;
    public static final int RETRIEVE_TICKS = 20 * 60;
    /** The most items one grid slot is given at once. */
    public static final int MAX_PER_SLOT = 64;
    /** The whole trip may take this long, plus this much a grid fill; then it is ended (the grid emptied first). */
    public static final int TRIP_BASE_TICKS = 20 * 120, TRIP_FILL_TICKS = 20 * 20;
    /** Requests one step may make before it is given up: placing into the grid, taking results, taking the grid back out. */
    public static final int MAX_PLACES_PER_SLOT = 6, MAX_RETRIEVES = 30, MAX_STOWS = 8;

    public static final String NOT_REACHED = "couldn't reach the crafting table", NOT_OPENED = "couldn't open the crafting table",
        CLOSED = "the crafting table closed before it was done", NO_RESULT = "no result from the crafting table in time",
        NOT_TAKEN = "the crafting table didn't give the result", NO_ROOM = "no room in the inventory for what was crafted",
        MISSING = "the inputs aren't all in the inventory", GRID = "the grid didn't fill as it should",
        TOO_LONG = "the trip to the crafting table took too long";

    /** Grid slots of the inventory's 2x2 crafting grid (window slots 1-4; 5-8 are the armour). */
    public static final int INVENTORY_GRID = 4, TABLE_GRID = 9;

    private final Port port;
    /** 9 for a crafting table, {@link #INVENTORY_GRID} for the inventory's own grid (no walking, no window opened or closed). */
    private final int gridSlots;
    private final List<CraftPlanner.Step> steps;
    private final String item;
    private Step step = Step.Walk;
    private int ticks, stepTicks, tries, sinceClick;
    private int at, fillsLeft = -1, took, gridBefore, made, taken;
    private final int limit;
    private int places, takes, retrieves, stows;
    private CraftGrid.@Nullable Batch batch;
    private boolean placedAny;
    private @Nullable Outcome outcome, pending;

    public CraftTrip(Port port, CraftPlanner.Plan plan) {
        this(port, plan, TABLE_GRID);
    }

    /**
     * @param gridSlots {@link #TABLE_GRID}, or {@link #INVENTORY_GRID}: the trip then starts where the bot stands, "tableOpen" means
     *                  the inventory window is the current one, and the grid is emptied wherever the bot is (a window of another
     *                  module open meanwhile only delays that)
     */
    public CraftTrip(Port port, CraftPlanner.Plan plan, int gridSlots) {
        this.port = port;
        this.gridSlots = gridSlots;
        if (inventoryGrid()) this.step = Step.Open;
        this.steps = plan.steps();
        this.item = plan.item();
        this.limit = TRIP_BASE_TICKS + TRIP_FILL_TICKS * Math.max(1, plan.fills());
    }

    /** The tick limit of the whole trip. */
    public int limit() {
        return limit;
    }

    public Step step() {
        return step;
    }

    private boolean inventoryGrid() {
        return gridSlots == INVENTORY_GRID;
    }

    /** The grid may hold something of ours (the inventory's keeps its items whatever window is open). */
    private boolean gridLive() {
        return inventoryGrid() || port.tableOpen();
    }

    /** How many of the wanted item were made so far. */
    public int made() {
        return made;
    }

    /** Grid fills whose result was taken so far (every step's, not only the wanted item's). */
    public int fillsTaken() {
        return taken;
    }

    public @Nullable Outcome outcome() {
        return outcome;
    }

    /** The grid may hold something of ours now. */
    public boolean gridInUse() {
        return placedAny && outcome == null;
    }

    private void go(Step next) {
        step = next;
        stepTicks = 0;
    }

    private boolean gridEmpty() {
        for (int s = 1; s <= gridSlots; s++) if (port.itemAt(s) != null) return false;
        return true;
    }

    private int gridTotal() {
        int n = 0;
        for (int s = 1; s <= gridSlots; s++) if (port.itemAt(s) != null) n += port.countAt(s);
        return n;
    }

    /** The current batch. */
    private CraftGrid.Batch b() {
        CraftGrid.Batch now = batch;
        return now == null ? new CraftGrid.Batch(1, new String[10]) : now;
    }

    /** Taking the grid back out can't be done: after RETRIEVE_TICKS the trip ends with the window left open for a person. */
    private void stuckTimer() {
        if (stepTicks <= RETRIEVE_TICKS) return;
        Outcome was = pending;
        outcome = new Outcome(was == null || was.failure() == null ? NO_ROOM : was.failure(), false, was != null && was.tableBad(), true, made);
        step = Step.Done;
    }

    /** Ends with this outcome: anything in the grid is taken back out first, then the window closed. */
    private void end(@Nullable String failure, boolean retry, boolean tableBad) {
        pending = new Outcome(failure, retry, tableBad, false, made);
        go(gridLive() && !gridEmpty() ? Step.Retrieve : Step.Close);
    }

    /** Stops the trip now (the bot gives way, the order ends): the grid is emptied, the window closed, the try may be made again. */
    public void abort(String why) {
        if (step == Step.Done) return;
        if (pending == null) pending = new Outcome(why, true, false, false, made);
        if (step != Step.Retrieve && step != Step.Close) go(gridLive() && !gridEmpty() ? Step.Retrieve : Step.Close);
    }

    public void tick() {
        if (step == Step.Done) return;
        ticks++;
        stepTicks++;
        sinceClick++;
        // The whole trip is bounded: past its limit it ends, the grid taken back out first; past that too, it stops for good
        if (ticks > limit && pending == null) end(TOO_LONG, false, false);
        if (ticks > limit + RETRIEVE_TICKS + CLOSE_TICKS) {
            Outcome was = pending;
            boolean left = gridLive() && (!gridEmpty() || port.holdingCursor());
            outcome = new Outcome(was == null || was.failure() == null ? TOO_LONG : was.failure(), false, was != null && was.tableBad(), left, made);
            step = Step.Done;
            return;
        }
        // A stack on the cursor goes into a free slot before anything in the grid is touched
        if ((step == Step.Fill || step == Step.Result || step == Step.Retrieve || step == Step.Close) && port.tableOpen() && !port.busy()
            && sinceClick >= SETTLE_TICKS && port.holdingCursor()) {
            if (stows < MAX_STOWS && port.stowCursor() >= 0) {
                stows++;
                sinceClick = 0;
                return;
            }
            if (step == Step.Fill || step == Step.Result) end(NO_ROOM, false, false);
            else if (step == Step.Close) go(Step.Retrieve);
            else stuckTimer();
            return;
        }
        switch (step) {
            case Walk -> {
                if (port.arrived()) {
                    go(Step.Open);
                    sinceClick = OPEN_RETRY_TICKS;
                    return;
                }
                if (stepTicks > WALK_TICKS) {
                    end(NOT_REACHED, false, true);
                    return;
                }
                if (!port.pathing()) port.walk();
            }
            case Open -> {
                if (port.tableOpen()) {
                    port.opened();
                    // Something left in the grid (not ours, or from before) is taken out before anything is put in
                    if (!gridEmpty()) {
                        placedAny = true;
                        pending = null;
                        go(Step.Retrieve);
                        return;
                    }
                    go(Step.Prepare);
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
                    end(NOT_OPENED, false, true);
                    return;
                }
                if (tries < OPEN_TRIES && sinceClick >= OPEN_RETRY_TICKS && !port.pathing() && port.open()) {
                    tries++;
                    sinceClick = 0;
                }
            }
            case Prepare -> {
                if (!port.tableOpen()) {
                    end(CLOSED, true, false);
                    return;
                }
                if (port.busy()) return;
                if (fillsLeft == 0) {
                    at++;
                    fillsLeft = -1;
                }
                if (at >= steps.size()) {
                    end(null, false, false);
                    return;
                }
                CraftPlanner.Step st = steps.get(at);
                if (fillsLeft < 0) fillsLeft = st.fills();
                batch = CraftGrid.batch(st.recipe(), port.carried(), fillsLeft, MAX_PER_SLOT, inventoryGrid() ? 2 : 3, port::maxStack);
                if (batch == null) {
                    end(MISSING + " (" + Recipes.pretty(st.recipe().result()) + ")", false, false);
                    return;
                }
                took = 0;
                places = 0;
                takes = 0;
                go(Step.Fill);
            }
            case Fill -> {
                if (!port.tableOpen()) {
                    end(CLOSED, true, false);
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                CraftGrid.Batch b = batch;
                for (int s = 1; s <= gridSlots; s++) {
                    String want = b.item()[s];
                    String there = port.itemAt(s);
                    if (want == null) {
                        if (there != null) {
                            end(GRID, false, false);
                            return;
                        }
                        continue;
                    }
                    if (there == null || (there.equals(want) && port.countAt(s) < b.fills())) {
                        // Empty, or short of the batch (another stack tops it up). Only requests that went out count, a few
                        // for each slot of the batch
                        if (places >= MAX_PLACES_PER_SLOT * Math.max(1, b.cells())) {
                            end(GRID, false, false);
                            return;
                        }
                        if (port.place(s, want, b.fills())) {
                            places++;
                            placedAny = true;
                            sinceClick = 0;
                        }
                        else if (stepTicks > FILL_TICKS) end(GRID, false, false);
                        return;
                    }
                    if (!there.equals(want) || port.countAt(s) != b.fills()) {
                        // The server may still be answering: a wrong slot that stays wrong ends it
                        if (stepTicks > FILL_TICKS) end(GRID, false, false);
                        return;
                    }
                }
                go(Step.Result);
            }
            case Result -> {
                if (!port.tableOpen()) {
                    end(CLOSED, true, false);
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                CraftPlanner.Step st = steps.get(at);
                String result = st.recipe().result();
                if (!result.equals(port.itemAt(CraftGrid.RESULT)) || port.countAt(CraftGrid.RESULT) < 1) {
                    if (stepTicks > RESULT_TICKS) end(NO_RESULT, false, true);
                    return;
                }
                gridBefore = gridTotal();
                if (++takes > 3 * b().fills() + 3) {
                    end(NOT_TAKEN, false, true);
                    return;
                }
                int into = port.take(result, port.countAt(CraftGrid.RESULT));
                if (into < 0) {
                    if (stepTicks > RESULT_TICKS) end(NO_ROOM, false, false);
                    return;
                }
                sinceClick = 0;
                go(Step.Taken);
            }
            case Taken -> {
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                CraftGrid.Batch b = batch;
                // The server's update: the grid used up by one fill (until then the take may still be refused)
                if (gridTotal() != gridBefore - b.cells()) {
                    if (!port.tableOpen()) {
                        end(CLOSED, true, false);
                        return;
                    }
                    if (stepTicks > CHECK_TICKS) end(NOT_TAKEN, false, true);
                    return;
                }
                took++;
                taken++;
                CraftPlanner.Step st = steps.get(at);
                if (at == steps.size() - 1 && st.recipe().result().equals(item)) made += st.recipe().count();
                if (took < b.fills()) {
                    go(Step.Result);
                    return;
                }
                fillsLeft -= b.fills();
                go(Step.Prepare);
            }
            case Retrieve -> {
                // Closed (by the server): it gave back what was in the grid. The inventory's grid keeps its items while another
                // window is open: they are waited for, never given up on
                if (!port.tableOpen()) {
                    if (inventoryGrid()) stuckTimer();
                    else go(Step.Close);
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                int slot = -1;
                for (int s = 1; s <= gridSlots && slot < 0; s++) if (port.itemAt(s) != null) slot = s;
                if (slot < 0) {
                    // Cleared what was left from before: the trip carries on
                    if (pending == null) {
                        go(Step.Prepare);
                        return;
                    }
                    go(Step.Close);
                    return;
                }
                int into = retrieves < MAX_RETRIEVES ? port.retrieve(slot) : -1;
                if (into >= 0) {
                    retrieves++;
                    sinceClick = 0;
                    return;
                }
                // Nowhere to put it: closing now could drop it. The window stays open and it is tried again, and in the end it
                // is left open for a person
                stuckTimer();
            }
            case Close -> {
                if (port.busy()) return;
                // Never closed with something in the grid, or on the cursor
                if (gridLive() && (!gridEmpty() || port.holdingCursor())) {
                    go(Step.Retrieve);
                    return;
                }
                if (port.close() || stepTicks > CLOSE_TICKS) {
                    outcome = pending == null ? new Outcome(NO_RESULT, false, false, false, made) : pending;
                    go(Step.Done);
                }
            }
            case Done -> {
            }
        }
    }
}
