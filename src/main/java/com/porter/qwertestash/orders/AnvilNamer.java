package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

/**
 * One trip to the anvil for one order shulker: walk there, open it, put the shulker in the first slot and send the name,
 * wait for the result and its XP cost, take the result back into the carry slot the shulker came from, close. Every step
 * has a time limit, and whatever goes wrong ends in an {@link Outcome} that the order carries on from: the shulker is then
 * delivered unnamed. The world is behind {@link Port}, so the decisions are tested without the game; one {@link #tick}
 * per game tick, on the bot tick thread.
 */
public final class AnvilNamer {
    /** What the namer needs from the world. Calls that ask for something return false when it can't be asked now. */
    public interface Port {
        /** Within reach of the anvil. */
        boolean arrived();

        /** Asks to walk to within reach of the anvil. */
        boolean walk();

        /** A walk or open request of ours is still running. */
        boolean pathing();

        /** An anvil window that is ours is open. */
        boolean anvilOpen();

        /** Some other window of ours is open (a late answer to an earlier click): it is closed first. */
        boolean otherWindowOpen();

        /** Right-clicks the anvil. */
        boolean open();

        /** Inventory clicks of ours are still going out. */
        boolean busy();

        /** Moves the shulker in this carry slot into the anvil's first slot and sends the name, as one request. */
        boolean place(int slot, String name);

        /** The custom name of the stack in the anvil's result slot, "" when it has none, null when the slot is empty. */
        @Nullable String resultName();

        /** The anvil's first slot holds something. */
        boolean inputPresent();

        /** The XP cost the anvil showed since the shulker was put in, or -1 when none was seen. */
        int cost();

        /** The bot's XP level, or -1 when it is not known. */
        int xpLevel();

        /**
         * Takes the result into this carry slot (kept empty for it since the shulker left it), or, if something landed there
         * meanwhile, into another free slot. @return the slot it goes to, or -1 when it can't be taken now
         */
        int take(int slot);

        /**
         * Puts what is in the anvil's first slot back into this carry slot, or another free one.
         * @return the slot it goes to, or -1 when there is no room (nothing is clicked then)
         */
        int retrieve(int slot);

        /** The custom name of the stack in this carry slot, or null. */
        @Nullable String nameAt(int slot);

        /** Closes our open window; true once nothing of ours is open. */
        boolean close();
    }

    /**
     * How the trip ended.
     * @param failure why the shulker was not named, or null when it was
     * @param retry   the trip was cut short and may be made once more
     * @param anvilBad the anvil itself could not be used (reached, opened): not worth trying again for this order
     * @param stuck    the shulker is still in the anvil with nowhere to put it: the window was left open (never closed, which
     *                 could drop it), for a person to sort out
     */
    public record Outcome(@Nullable String failure, boolean retry, boolean anvilBad, boolean stuck) {
        public Outcome(@Nullable String failure, boolean retry, boolean anvilBad) {
            this(failure, retry, anvilBad, false);
        }

        public boolean named() {
            return failure == null;
        }
    }

    public enum Step { Walk, Open, Place, Result, Take, Check, Retrieve, Close, Done }

    // Ticks (20 a second)
    public static final int WALK_TICKS = 20 * 60;
    public static final int OPEN_TICKS = 20 * 15;
    public static final int OPEN_TRIES = 4;
    public static final int OPEN_RETRY_TICKS = 40;
    /** "No result within about 30 s": from when the name was sent. */
    public static final int RESULT_TICKS = 20 * 30;
    /** A result with no cost seen this long is taken with a cost of one level (a plain rename). */
    public static final int COST_WAIT_TICKS = 20 * 3;
    public static final int CHECK_TICKS = 20 * 5;
    public static final int SETTLE_TICKS = 6;
    public static final int CLOSE_TICKS = 20 * 10;
    /** How long the shulker is tried to be taken back out of the anvil, the window kept open, before it is left for a person. */
    public static final int RETRIEVE_TICKS = 20 * 60;
    /** A rename this dear is refused by a survival anvil ("Too Expensive!"). */
    public static final int TOO_EXPENSIVE = 40;

    public static final String NOT_REACHED = "couldn't reach the anvil", NOT_OPENED = "couldn't open the anvil",
        CLOSED = "the anvil closed before it was done", TOO_FEW_LEVELS = "too few XP levels", TOO_DEAR = "the anvil said it is too expensive",
        NO_RESULT = "no result from the anvil in time", NOT_TAKEN = "the anvil didn't give the shulker back named";

    private final Port port;
    private final int slot;
    private final String name;
    private Step step = Step.Walk;
    private int ticks, stepTicks, tries, sinceClick, shownFor;
    private boolean placed;
    // Where the shulker is now (its carry slot, or the free slot it went to)
    private int landed;
    private @Nullable Outcome outcome, pending;

    public AnvilNamer(Port port, int slot, String name) {
        this.port = port;
        this.slot = slot;
        this.name = name;
        this.landed = slot;
    }

    public Step step() {
        return step;
    }

    public int slot() {
        return slot;
    }

    /** The slot the shulker is in after the trip: its own carry slot, unless that had been taken meanwhile. */
    public int landedSlot() {
        return landed;
    }

    /** The end of the trip, once {@link #step} is Done. */
    public @Nullable Outcome outcome() {
        return outcome;
    }

    /** The shulker has been put into the anvil (and not taken back out yet). */
    public boolean shulkerInAnvil() {
        return placed && outcome == null && (step == Step.Result || step == Step.Take || step == Step.Check || step == Step.Retrieve);
    }

    private void go(Step next) {
        step = next;
        stepTicks = 0;
    }

    /** Ends with this outcome: the shulker is taken back out of the anvil first if it is in there, then the window closed. */
    private void end(Outcome o) {
        pending = o;
        go(placed ? Step.Retrieve : Step.Close);
    }

    /** Stops the trip now (the bot gives way, the order ends): the window is closed and the try may be made again. */
    public void abort(String why) {
        if (step == Step.Done) return;
        if (pending == null) pending = new Outcome(why, true, false);
        if (step != Step.Retrieve && step != Step.Close) go(placed ? Step.Retrieve : Step.Close);
    }

    public void tick() {
        if (step == Step.Done) return;
        ticks++;
        stepTicks++;
        sinceClick++;
        switch (step) {
            case Walk -> {
                if (port.arrived()) {
                    go(Step.Open);
                    sinceClick = OPEN_RETRY_TICKS;
                    return;
                }
                if (stepTicks > WALK_TICKS) {
                    end(new Outcome(NOT_REACHED, false, true));
                    return;
                }
                if (!port.pathing()) port.walk();
            }
            case Open -> {
                if (port.anvilOpen()) {
                    go(Step.Place);
                    return;
                }
                if (port.otherWindowOpen()) {
                    port.close();
                    return;
                }
                if (stepTicks > OPEN_TICKS || (tries >= OPEN_TRIES && sinceClick > OPEN_RETRY_TICKS)) {
                    end(new Outcome(NOT_OPENED, false, true));
                    return;
                }
                if (tries < OPEN_TRIES && sinceClick >= OPEN_RETRY_TICKS && !port.pathing() && port.open()) {
                    tries++;
                    sinceClick = 0;
                }
            }
            case Place -> {
                if (!port.anvilOpen()) {
                    end(new Outcome(CLOSED, true, false));
                    return;
                }
                if (port.busy()) return;
                if (port.place(slot, name)) {
                    placed = true;
                    go(Step.Result);
                }
                else if (stepTicks > CHECK_TICKS) end(new Outcome(CLOSED, true, false));
            }
            case Result -> {
                if (!port.anvilOpen()) {
                    // The server gives what was in the anvil back to the inventory when it closes
                    end(new Outcome(CLOSED, true, false));
                    return;
                }
                if (port.busy()) return;
                String shown = port.resultName();
                int cost = port.cost();
                // The result with our name, seen for a few ticks so the cost sent with it has arrived too
                shownFor = name.equals(shown) ? shownFor + 1 : 0;
                if (shownFor >= SETTLE_TICKS && (cost > 0 || shownFor > COST_WAIT_TICKS)) {
                    // A rename costs at least one level
                    int need = Math.max(1, cost);
                    int level = port.xpLevel();
                    if (need >= TOO_EXPENSIVE) {
                        end(new Outcome(TOO_DEAR, false, false));
                        return;
                    }
                    if (level >= 0 && level < need) {
                        end(new Outcome(TOO_FEW_LEVELS, false, false));
                        return;
                    }
                    int into = port.take(slot);
                    if (into >= 0) {
                        landed = into;
                        go(Step.Take);
                    }
                    return;
                }
                if (stepTicks > RESULT_TICKS) end(new Outcome(NO_RESULT, false, false));
            }
            case Take -> {
                if (port.busy() || stepTicks < SETTLE_TICKS) return;
                go(Step.Check);
            }
            case Check -> {
                if (name.equals(port.nameAt(landed))) {
                    end(new Outcome(null, false, false));
                    return;
                }
                if (port.busy()) return;
                // Still in the anvil (the server refused the take): it is taken back out unnamed
                if (stepTicks > CHECK_TICKS) end(new Outcome(NOT_TAKEN, false, false));
            }
            case Retrieve -> {
                // Closed (by the server): it gave back what was in it
                if (!port.anvilOpen()) {
                    go(Step.Close);
                    return;
                }
                if (port.busy() || sinceClick < SETTLE_TICKS) return;
                if (!port.inputPresent()) {
                    go(Step.Close);
                    return;
                }
                int into = port.retrieve(slot);
                if (into >= 0) {
                    landed = into;
                    sinceClick = 0;
                    return;
                }
                // Nowhere to put it: closing now could drop it on the ground. The window stays open and it is tried again,
                // and in the end it is left open for a person
                if (stepTicks > RETRIEVE_TICKS) {
                    Outcome was = pending;
                    outcome = new Outcome(was == null || was.failure() == null ? NO_RESULT : was.failure(), false, was != null && was.anvilBad(), true);
                    step = Step.Done;
                }
            }
            case Close -> {
                if (port.busy()) return;
                // Never closed with the shulker still in it
                if (port.anvilOpen() && port.inputPresent()) {
                    go(Step.Retrieve);
                    return;
                }
                if (port.close() || stepTicks > CLOSE_TICKS) {
                    outcome = pending == null ? new Outcome(NO_RESULT, false, false) : pending;
                    go(Step.Done);
                }
            }
            case Done -> {
            }
        }
    }
}
