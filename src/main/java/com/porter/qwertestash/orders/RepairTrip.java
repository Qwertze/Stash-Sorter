package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One go at repairing carried items with experience bottles, step by step on the bot tick (REPAIR-CONTRACT.md "What the bot does").
 * The bot's own equipped damaged Mending gear is stored first so it takes no XP, the bottles are held in the off hand (a bottle can only be thrown from a hand) and the damaged item in the main hand (Mending counts
 * both), the bottles are thrown at the bot's feet in
 * small batches while it stands still looking down, and the durability is looked at after each batch. Whatever happens
 * (done, out of bottles, a player near, the orbs gone, the time up, the order cancelled) everything that was moved is moved back.
 * <p>
 * Plain Java behind {@link Port}, so the whole thing is tested without a world. Window slots are the player inventory window's:
 * 5-8 armour, 9-35 inventory, 36-44 hotbar, 45 off hand. All of it is for the bot tick thread.
 */
public final class RepairTrip {
    /** Ticks between two throws, and throws in one batch. */
    public static final int THROW_GAP = 4, BATCH = 3;
    /** After a batch: at least this long, and at most this long, before the durability is looked at. */
    public static final int SETTLE_MIN = 12, SETTLE_MAX = 60;
    /** A click the port keeps refusing for this long is given up on (what is not back is left in the saved ledger). */
    public static final int STUCK_TICKS = 200;
    public static final int ARMOUR_FIRST = 5, ARMOUR_LAST = 8, OFFHAND = 45, HOTBAR_FIRST = 36, HOTBAR_LAST = 44, INVENTORY_FIRST = 9;

    /** A stack as the trip needs to see it. */
    public record Stack(String id, int count, int damage, int maxDamage, boolean mending) {
        public boolean bottle() {
            return id.equals("experience_bottle") || id.equals("minecraft:experience_bottle");
        }
    }

    /** What the bot gives the trip. */
    public interface Port {
        /** A click of ours is outstanding, or the inventory queue is not free: wait. */
        boolean busy();

        /** The stack in a window slot (see the class comment), or null when it is empty. */
        @Nullable Stack at(int window);

        /** Moves the stack in {@code from} onto the empty slot {@code to}. False: refused, ask again. */
        boolean move(int from, int to);

        /** Selects the hotbar slot (0-8) as the held one. False: refused. */
        boolean select(int hotbar);

        /** The held hotbar slot (0-8). */
        int held();

        /** Throws one bottle from the off hand at the bot's feet, looking down. False: refused. */
        boolean throwBottle();

        /** Another player (not a friend) is within {@link Repair#PLAYER_RADIUS} blocks. */
        boolean playerNear();

        /** Experience orbs lying about within reach of the bot. */
        int orbsNear();

        /** Bottles in the inventory (window slots 9-44) in all. */
        int bottles();

        /** Empty window slots 9-44 that something may be stored in (not the slots the bot never touches). */
        List<Integer> freeSlots();

        /** A hotbar slot (window number) that is never touched. */
        boolean keep(int window);

        /** The order ended, was cancelled or parked, or the bot can't work: stop and put everything back. */
        boolean abort();

        /** The moves made and not yet undone, kept so that a restart can undo them. */
        void ledger(List<int[]> moves);

        /** The click delay. */
        void afterClick();

        /** A saved move was dropped because it no longer fits what is there (an item that does not go in that slot). */
        default void dropped(int[] move) {
        }
    }

    public enum Step { Start, Setup, Equip, Throw, Settle, Unequip, Restore, Done }

    public enum Cause {
        None, Player, Orbs, Timeout, NoBottles, NoRoom, Aborted;

        /** Worth another go once the player is gone, or the orbs. */
        public boolean retryable() {
            return this == Player || this == Orbs;
        }
    }

    public enum Status { Repaired, Partial, Untouched }

    /** An item to repair: the key the order asked for, and where it is (window slot). */
    public record Target(String key, int slot) {
    }

    /** How one item ended. {@code slot} is where it is now; {@code reason} is one of Repair's reasons, null when repaired. */
    public record Outcome(String key, int slot, int startDamage, int damage, int maxDamage, Status status, @Nullable String reason) {
    }

    private final Port port;
    private final List<Target> targets;
    private final int budget;

    private Step step = Step.Start;
    private Cause cause = Cause.None;
    private final List<Outcome> outcomes = new ArrayList<>();
    private final List<int[]> ledger = new ArrayList<>();
    private final List<int[]> queue = new ArrayList<>();
    private int ti, hand = -1, mainHand = -1, originalHeld = -1, startBottles, ticks, stuck, batchLeft, sinceThrow, settle;
    private int startDamage, maxDamage, damageBefore, noProgress;
    private int equippedFrom = -1;
    private boolean equipped;
    /** Bottles were thrown whose orbs have not been looked at yet; no more bottles to throw once they have. */
    private boolean unsettled, outOfBottles;

    /**
     * @param budget throwing ticks left for the order (the trip stops when they are used): at most {@link Repair#MAX_THROW_TICKS}
     */
    public RepairTrip(Port port, List<Target> targets, int budget) {
        this.port = port;
        this.targets = targets;
        this.budget = budget;
    }

    /** A trip that only puts back what a saved ledger lists (a restart in the middle of a trip). */
    public static RepairTrip undo(Port port, List<int[]> saved) {
        RepairTrip t = new RepairTrip(port, List.of(), 0);
        t.ledger.addAll(saved);
        t.step = Step.Restore;
        return t;
    }

    public Step step() {
        return step;
    }

    public boolean done() {
        return step == Step.Done;
    }

    public Cause cause() {
        return cause;
    }

    public List<Outcome> outcomes() {
        return outcomes;
    }

    /** Throwing ticks used by this trip. */
    public int ticks() {
        return ticks;
    }

    /** Moves not yet put back (non-empty only when the trip gave up on a refused click). */
    public List<int[]> pending() {
        return ledger;
    }

    /** Bottles used, once the trip is done: what the inventory lost. */
    public int bottlesUsed() {
        return Math.max(0, startBottles - endBottles);
    }

    private int endBottles;

    // The tick

    public void tick() {
        if (step == Step.Done) return;
        if (port.busy()) return;

        if (step == Step.Throw || step == Step.Settle) {
            ticks++;
            Cause stop = stopCause();
            if (stop != Cause.None) {
                cause = stop;
                finishItem();
                return;
            }
        }
        else if (port.abort() && step != Step.Restore && step != Step.Unequip) {
            cause = Cause.Aborted;
            if (equipped) {
                finishItem();
                return;
            }
            leaveTargets();
            step = Step.Restore;
            return;
        }

        switch (step) {
            case Start -> start();
            case Setup -> setup();
            case Equip -> equip();
            case Throw -> throwing();
            case Settle -> settling();
            case Unequip -> unequip();
            case Restore -> restore();
            case Done -> {
            }
        }
    }

    private Cause stopCause() {
        if (port.abort()) return Cause.Aborted;
        if (port.playerNear()) return Cause.Player;
        if (ticks > budget) return Cause.Timeout;
        return Cause.None;
    }

    // Start: what has to be moved out of the way

    private void start() {
        startBottles = port.bottles();
        endBottles = startBottles;
        if (targets.isEmpty()) {
            step = Step.Restore;
            return;
        }
        originalHeld = port.held();

        // The hotbar slot for the bottles: one that holds them already, else the held one if it is empty, else any empty one, else any
        int pick = -1;
        for (int w = HOTBAR_FIRST; w <= HOTBAR_LAST && pick < 0; w++) {
            Stack s = port.at(w);
            if (!port.keep(w) && !isTarget(w) && s != null && s.bottle()) pick = w;
        }
        for (int w = HOTBAR_FIRST; w <= HOTBAR_LAST && pick < 0; w++) {
            if (!port.keep(w) && !isTarget(w) && port.at(w) == null && w - HOTBAR_FIRST == originalHeld) pick = w;
        }
        for (int w = HOTBAR_FIRST; w <= HOTBAR_LAST && pick < 0; w++) {
            if (!port.keep(w) && !isTarget(w) && port.at(w) == null) pick = w;
        }
        for (int w = HOTBAR_FIRST; w <= HOTBAR_LAST && pick < 0; w++) {
            if (!port.keep(w) && !isTarget(w)) pick = w;
        }
        if (pick < 0) {
            giveUp(Cause.NoRoom);
            return;
        }
        mainHand = pick;
        hand = pick;

        List<Integer> free = new ArrayList<>(port.freeSlots());
        free.remove((Integer) mainHand);
        // Damaged Mending gear on the bot would take the XP
        for (int w = ARMOUR_FIRST; w <= ARMOUR_LAST; w++) {
            Stack s = port.at(w);
            if (s != null && s.mending() && s.damage() > 0) {
                if (free.isEmpty()) {
                    giveUp(Cause.NoRoom);
                    return;
                }
                queue.add(new int[] {w, free.remove(0)});
            }
        }
        // The bottles go in the off hand (a bottle is thrown from a hand), the item in the main hand, which has to be empty
        Stack off = port.at(OFFHAND);
        Stack inMain = port.at(mainHand);
        boolean offBottles = off != null && off.bottle();
        int bottleFrom = -1;
        if (!offBottles) {
            bottleFrom = bottleStack(OFFHAND, mainHand);
            if (bottleFrom < 0 && inMain != null && inMain.bottle()) bottleFrom = mainHand;
            if (bottleFrom < 0) {
                giveUp(Cause.NoBottles);
                return;
            }
            if (off != null) {
                if (free.isEmpty()) {
                    giveUp(Cause.NoRoom);
                    return;
                }
                queue.add(new int[] {OFFHAND, free.remove(0)});
            }
        }
        if (inMain != null && bottleFrom != mainHand) {
            if (free.isEmpty()) {
                giveUp(Cause.NoRoom);
                return;
            }
            queue.add(new int[] {mainHand, free.remove(0)});
        }
        if (!offBottles) queue.add(new int[] {bottleFrom, OFFHAND});
        step = Step.Setup;
    }

    private boolean isTarget(int window) {
        for (Target t : targets) if (t.slot() == window) return true;
        return false;
    }

    /** Moves an earlier trip left undone (a refused click): this trip puts them back too, and never overwrites them. */
    public RepairTrip seed(List<int[]> pending) {
        for (int[] m : pending) ledger.add(m.clone());
        return this;
    }

    /** The window slot of the biggest stack of bottles other than {@code except}, or -1. */
    private int bottleStack(int except) {
        return bottleStack(except, -1);
    }

    private int bottleStack(int except, int except2) {
        int best = -1, count = 0;
        for (int w = INVENTORY_FIRST; w <= HOTBAR_LAST; w++) {
            if (w == except || w == except2 || port.keep(w)) continue;
            Stack s = port.at(w);
            if (s != null && s.bottle() && s.count() > count) {
                best = w;
                count = s.count();
            }
        }
        return best;
    }

    private void giveUp(Cause why) {
        cause = why;
        leaveTargets();
        step = Step.Restore;
    }

    /** The items not worked on are left as they are, with the reason the cause gives. */
    private void leaveTargets() {
        while (ti < targets.size()) {
            Target t = targets.get(ti++);
            Stack s = port.at(t.slot());
            outcomes.add(new Outcome(t.key(), t.slot(), s == null ? 0 : s.damage(), s == null ? 0 : s.damage(), s == null ? 0 : s.maxDamage(), Status.Untouched, reasonOf(cause)));
        }
    }

    private static String reasonOf(Cause c) {
        return c == Cause.NoBottles ? Repair.NO_BOTTLES : Repair.INTERRUPTED;
    }

    // Setup: the queued moves one by one, then the bottles in hand

    private void setup() {
        if (!queue.isEmpty()) {
            int[] m = queue.get(0);
            if (port.at(m[0]) == null || port.at(m[1]) != null) {
                // Not as it was when it was planned: nothing is moved blindly
                queue.clear();
                giveUp(Cause.Aborted);
                return;
            }
            if (!port.move(m[0], m[1])) {
                if (++stuck > STUCK_TICKS) {
                    queue.clear();
                    giveUp(Cause.Aborted);
                }
                return;
            }
            stuck = 0;
            queue.remove(0);
            ledger.add(m);
            port.ledger(ledger);
            port.afterClick();
            return;
        }
        if (port.held() != mainHand - HOTBAR_FIRST && !port.select(mainHand - HOTBAR_FIRST)) {
            if (++stuck > STUCK_TICKS) {
                giveUp(Cause.Aborted);
            }
            return;
        }
        stuck = 0;
        step = Step.Equip;
    }

    // Equip: the next item into the off hand

    private void equip() {
        if (ti >= targets.size()) {
            step = Step.Restore;
            return;
        }
        Target t = targets.get(ti);
        Stack s = port.at(t.slot());
        if (s == null || s.damage() <= 0 || port.at(mainHand) != null) {
            // Gone, or repaired some other way: not worked on
            outcomes.add(new Outcome(t.key(), t.slot(), s == null ? 0 : s.damage(), s == null ? 0 : s.damage(), s == null ? 0 : s.maxDamage(),
                s != null && s.damage() <= 0 ? Status.Repaired : Status.Untouched, s != null && s.damage() <= 0 ? null : Repair.INTERRUPTED));
            ti++;
            return;
        }
        if (!port.move(t.slot(), mainHand)) {
            if (++stuck > STUCK_TICKS) {
                cause = Cause.Aborted;
                leaveTargets();
                step = Step.Restore;
            }
            return;
        }
        stuck = 0;
        ledger.add(new int[] {t.slot(), mainHand});
        port.ledger(ledger);
        equipped = true;
        unsettled = false;
        outOfBottles = false;
        equippedFrom = t.slot();
        startDamage = s.damage();
        maxDamage = s.maxDamage();
        damageBefore = s.damage();
        noProgress = 0;
        batchLeft = 0;
        sinceThrow = THROW_GAP;
        step = Step.Throw;
        port.afterClick();
    }

    // Throw: one bottle at a time, a batch, then the durability

    private void throwing() {
        Stack off = port.at(mainHand);
        if (off == null) {
            // The item left the hand: something else took it
            cause = Cause.Aborted;
            equipped = false;
            outcomes.add(new Outcome(targets.get(ti).key(), equippedFrom, startDamage, damageBefore, maxDamage, Status.Untouched, Repair.INTERRUPTED));
            ledger.removeIf(m -> m[1] == mainHand && m[0] == equippedFrom);
            port.ledger(ledger);
            ti++;
            leaveTargets();
            step = Step.Restore;
            return;
        }
        if (off.damage() <= 0) {
            damageBefore = 0;
            finishItem();
            return;
        }
        Stack inHand = port.at(OFFHAND);
        if (inHand == null || !inHand.bottle()) {
            int from = bottleStack(OFFHAND);
            if (from < 0) {
                if (unsettled) {
                    // The last bottles' orbs are still on their way: looked at first
                    outOfBottles = true;
                    step = Step.Settle;
                    settle = 0;
                    damageBefore = off.damage();
                    return;
                }
                cause = Cause.NoBottles;
                finishItem();
                return;
            }
            if (inHand == null) {
                if (port.move(from, OFFHAND)) {
                    ledger.add(new int[] {from, OFFHAND});
                    port.ledger(ledger);
                    port.afterClick();
                }
                return;
            }
            // Something else lies in the bottles' slot: not worked around
            cause = Cause.Aborted;
            finishItem();
            return;
        }
        if (batchLeft <= 0) batchLeft = Math.max(1, Math.min(BATCH, Repair.bottlesFor(off.damage())));
        sinceThrow++;
        if (sinceThrow >= THROW_GAP && port.throwBottle()) {
            sinceThrow = 0;
            unsettled = true;
            if (--batchLeft <= 0) {
                step = Step.Settle;
                settle = 0;
                damageBefore = off.damage();
            }
        }
    }

    private void settling() {
        settle++;
        boolean quiet = port.orbsNear() == 0;
        if (settle < SETTLE_MIN || (!quiet && settle < SETTLE_MAX)) return;
        Stack off = port.at(mainHand);
        if (off == null) {
            cause = Cause.Aborted;
            step = Step.Throw;
            return;
        }
        unsettled = false;
        if (off.damage() < damageBefore) {
            noProgress = 0;
            damageBefore = off.damage();
            if (outOfBottles) {
                outOfBottles = false;
                cause = off.damage() <= 0 ? Cause.None : Cause.NoBottles;
                finishItem();
                return;
            }
            step = Step.Throw;
            return;
        }
        // Thrown and nothing mended: the orbs went to somebody else, or never came (with no bottles left it is just that)
        cause = outOfBottles ? Cause.NoBottles : Cause.Orbs;
        outOfBottles = false;
        finishItem();
    }

    /** The item in the off hand is done with (for whatever reason): its outcome is noted and it goes back. */
    private void finishItem() {
        Stack off = port.at(mainHand);
        Target t = targets.get(ti);
        int damage = off == null ? damageBefore : off.damage();
        int max = off == null ? maxDamage : off.maxDamage();
        if (damage <= 0) outcomes.add(new Outcome(t.key(), equippedFrom, startDamage, 0, max, Status.Repaired, null));
        else if (damage < startDamage) outcomes.add(new Outcome(t.key(), equippedFrom, startDamage, damage, max, Status.Partial, reasonOf(cause == Cause.None ? Cause.NoBottles : cause)));
        else outcomes.add(new Outcome(t.key(), equippedFrom, startDamage, damage, max, Status.Untouched, reasonOf(cause == Cause.None ? Cause.NoBottles : cause)));
        step = Step.Unequip;
    }

    private void unequip() {
        Stack off = port.at(mainHand);
        if (off != null) {
            int back = equippedFrom;
            if (port.at(back) != null) {
                List<Integer> free = port.freeSlots();
                back = free.isEmpty() ? -1 : free.get(0);
            }
            if (back < 0) {
                // Nowhere to put it: it stays in the hand, in the ledger, and is put back by the restore
                setLastSlot(mainHand);
                step = Step.Restore;
                leaveTargetsAfterUnequip();
                return;
            }
            if (!port.move(mainHand, back)) {
                if (++stuck > STUCK_TICKS) {
                    setLastSlot(mainHand);
                    step = Step.Restore;
                    leaveTargetsAfterUnequip();
                }
                return;
            }
            stuck = 0;
            int at = outcomes.size() - 1;
            if (back != equippedFrom && at >= 0) {
                Outcome o = outcomes.get(at);
                outcomes.set(at, new Outcome(o.key(), back, o.startDamage(), o.damage(), o.maxDamage(), o.status(), o.reason()));
            }
            port.afterClick();
        }
        final int from = equippedFrom;
        ledger.removeIf(m -> m[0] == from && m[1] == mainHand);
        port.ledger(ledger);
        equipped = false;
        equippedFrom = -1;
        ti++;
        if (cause != Cause.None) leaveTargets();
        step = ti >= targets.size() || cause != Cause.None ? Step.Restore : Step.Equip;
    }

    private void setLastSlot(int slot) {
        int at = outcomes.size() - 1;
        if (at < 0) return;
        Outcome o = outcomes.get(at);
        outcomes.set(at, new Outcome(o.key(), slot, o.startDamage(), o.damage(), o.maxDamage(), o.status(), o.reason()));
    }

    private void leaveTargetsAfterUnequip() {
        ti++;
        equipped = false;
        if (cause == Cause.None) cause = Cause.Aborted;
        leaveTargets();
    }

    // Restore: everything moved goes back, last first

    private void restore() {
        while (!ledger.isEmpty()) {
            int[] m = ledger.get(ledger.size() - 1);
            // Moved back only when it is still where it was put: a used-up stack just stays gone
            int dest = m[0];
            if (port.at(m[1]) != null && port.at(dest) != null) {
                // Its old place is taken now: any free slot will do
                List<Integer> free = port.freeSlots();
                dest = free.isEmpty() ? -1 : free.get(0);
            }
            Stack there = port.at(m[1]);
            if (there != null && !Repair.fitsSlot(dest, there.id())) {
                // A stale entry: the item is not one that goes in that place
                port.dropped(m);
                dest = -1;
            }
            if (there == null || dest < 0) {
                ledger.remove(ledger.size() - 1);
                port.ledger(ledger);
                continue;
            }
            if (!port.move(m[1], dest)) {
                if (++stuck > STUCK_TICKS) {
                    // Left in the saved ledger, to be undone by a later start
                    finish();
                }
                return;
            }
            stuck = 0;
            ledger.remove(ledger.size() - 1);
            port.ledger(ledger);
            port.afterClick();
            return;
        }
        if (originalHeld >= 0 && port.held() != originalHeld && !port.select(originalHeld)) return;
        finish();
    }

    private void finish() {
        endBottles = port.bottles();
        step = Step.Done;
    }
}
