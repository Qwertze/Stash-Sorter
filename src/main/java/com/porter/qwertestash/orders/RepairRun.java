package com.porter.qwertestash.orders;

import java.util.ArrayList;
import java.util.List;

/**
 * The repairs of one batch of carried items across its trips: which items are still to do, how many throwing ticks the order has
 * left, whether to try again after an interruption (once), and what to report about every item in the end
 * (REPAIR-CONTRACT.md "What the bot does", points 4 and 5). Plain Java; bot tick thread.
 */
public final class RepairRun {
    /** An item still to repair: the key the order asked for, where it is, how damaged it was and is. */
    public static final class Item {
        public final String key;
        public int slot;
        public final int startDamage;
        public int damage, maxDamage;
        /** Why the last go did not finish it. */
        public String reason = Repair.INTERRUPTED;

        public Item(String key, int slot, int damage, int maxDamage) {
            this.key = key;
            this.slot = slot;
            this.startDamage = damage;
            this.damage = damage;
            this.maxDamage = maxDamage;
        }
    }

    private final Repair.Report report;
    private final List<Item> pending = new ArrayList<>();
    private int ticksUsed, attempts;
    private boolean retryWanted;

    /** The time used and the retry made are the order's (kept in the report): this run adds to them. */
    public RepairRun(Repair.Report report, List<Item> items) {
        this.report = report;
        this.pending.addAll(items);
    }

    /** The order has no throwing time left. */
    public static boolean exhausted(Repair.Report report) {
        return report.ticks() >= Repair.MAX_THROW_TICKS;
    }

    public List<Item> pending() {
        return pending;
    }

    public int attempts() {
        return attempts;
    }

    /** Throwing ticks this run has used. */
    public int ticksUsed() {
        return ticksUsed;
    }

    /** Throwing ticks left for the order. */
    public int budget() {
        return Math.max(0, Repair.MAX_THROW_TICKS - report.ticks());
    }

    /** The items for the next trip. */
    public List<RepairTrip.Target> targets() {
        List<RepairTrip.Target> out = new ArrayList<>();
        for (Item i : pending) out.add(new RepairTrip.Target(i.key, i.slot));
        return out;
    }

    /** Bottles to take from the stash for what is still to do. */
    public int bottlesNeeded() {
        List<Integer> damages = new ArrayList<>();
        for (Item i : pending) damages.add(i.damage);
        return Repair.bottlesToTake(damages);
    }

    /**
     * A trip is over: its bottles and time are counted, items repaired are reported, the others keep their new damage and the
     * reason. @return true when another trip should be made (the first one was cut short by a player or by the orbs going, and
     * there is time left); false when the run is over and has reported everything
     */
    public boolean tripDone(RepairTrip trip) {
        attempts++;
        ticksUsed += trip.ticks();
        report.addTicks(trip.ticks());
        report.thrown(trip.bottlesUsed());
        List<Item> left = new ArrayList<>();
        int at = 0;
        for (Item item : pending) {
            // The trip reports every target, in the order it was given them
            RepairTrip.Outcome o = at < trip.outcomes().size() ? trip.outcomes().get(at) : null;
            at++;
            if (o == null) {
                // The trip never got to it: left as it is, with the reason the trip stopped for
                item.reason = trip.cause() == RepairTrip.Cause.NoBottles ? Repair.NO_BOTTLES : Repair.INTERRUPTED;
                left.add(item);
                continue;
            }
            item.slot = o.slot();
            item.damage = o.damage();
            if (o.maxDamage() > 0) item.maxDamage = o.maxDamage();
            if (o.status() == RepairTrip.Status.Repaired) {
                report.repaired(1);
                continue;
            }
            item.reason = o.reason() == null ? Repair.INTERRUPTED : o.reason();
            left.add(item);
        }
        pending.clear();
        pending.addAll(left);

        retryWanted = !pending.isEmpty() && trip.cause().retryable() && report.retries() < Repair.MAX_ATTEMPTS - 1 && budget() > 0;
        if (retryWanted) report.addRetry();
        else finish();
        return retryWanted;
    }

    /** The trip could not be made at all (no bottles to be had, the bot gave way): everything left is reported with this reason. */
    public void giveUp(String reason) {
        for (Item i : pending) i.reason = reason;
        finish();
    }

    /** Reports every item still pending: partly repaired ones as partial, the rest as skipped. Done once. */
    private void finish() {
        for (Item i : pending) {
            if (i.damage < i.startDamage) report.partial(i.key, i.damage, i.maxDamage, i.reason);
            else report.skip(i.key, i.reason);
        }
        pending.clear();
    }
}
