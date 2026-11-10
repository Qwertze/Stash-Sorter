package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A player inventory window for RepairTrip: 46 slots, a held hotbar slot, bottles thrown from the off hand that turn into orbs a
 * few ticks later and mend a damaged Mending item (armour first, as the worst case, then the off hand, then the held one).
 */
final class FakeRepairPort implements RepairTrip.Port {
    final RepairTrip.Stack[] slots = new RepairTrip.Stack[46];
    int held = 0;
    int durabilityPerBottle = 14;
    /** The orbs vanish without mending anything (a player took them). */
    boolean orbsEaten;
    boolean playerNear, abort, refuseMoves, refuseThrow, refuseSelect;
    int dropped;
    /** Players come near after this many throws (-1: never). */
    int playerAfterThrows = -1;
    final java.util.Set<Integer> keep = new java.util.HashSet<>();
    final List<List<int[]>> ledgers = new ArrayList<>();
    int throwsDone, moves, selects, ticks;
    /** Durability that went to something that was not the item in the off hand. */
    int mendedElsewhere;
    private final List<Integer> orbs = new ArrayList<>();

    static RepairTrip.Stack bottles(int n) {
        return new RepairTrip.Stack("experience_bottle", n, 0, 0, false);
    }

    static RepairTrip.Stack tool(String id, int damage, int max) {
        return new RepairTrip.Stack(id, 1, damage, max, true);
    }

    /** The world moves on one tick: orbs that reached the bot mend something. */
    void world() {
        ticks++;
        for (int i = 0; i < orbs.size(); i++) orbs.set(i, orbs.get(i) - 1);
        while (!orbs.isEmpty() && orbs.get(0) <= 0) {
            orbs.remove(0);
            if (orbsEaten) continue;
            int[] order = {5, 6, 7, 8, 36 + held, 45};
            for (int w : order) {
                RepairTrip.Stack s = slots[w];
                if (s != null && s.mending() && s.damage() > 0) {
                    slots[w] = new RepairTrip.Stack(s.id(), s.count(), Math.max(0, s.damage() - durabilityPerBottle), s.maxDamage(), true);
                    if (w != 36 + held) mendedElsewhere += durabilityPerBottle;
                    break;
                }
            }
        }
    }

    @Override
    public boolean busy() {
        return false;
    }

    @Override
    public RepairTrip.@Nullable Stack at(int window) {
        return slots[window];
    }

    @Override
    public boolean move(int from, int to) {
        if (refuseMoves) return false;
        if (slots[from] == null || slots[to] != null) throw new AssertionError("bad move " + from + " -> " + to);
        slots[to] = slots[from];
        slots[from] = null;
        moves++;
        return true;
    }

    @Override
    public boolean select(int hotbar) {
        if (refuseSelect) return false;
        held = hotbar;
        selects++;
        return true;
    }

    @Override
    public int held() {
        return held;
    }

    @Override
    public boolean throwBottle() {
        if (refuseThrow) return false;
        RepairTrip.Stack s = slots[45];
        if (s == null || !s.bottle()) throw new AssertionError("threw without bottles in the off hand");
        slots[45] = s.count() == 1 ? null : bottles(s.count() - 1);
        orbs.add(3);
        throwsDone++;
        if (playerAfterThrows >= 0 && throwsDone >= playerAfterThrows) playerNear = true;
        return true;
    }

    @Override
    public boolean playerNear() {
        return playerNear;
    }

    @Override
    public int orbsNear() {
        return orbs.size();
    }

    @Override
    public int bottles() {
        int n = 0;
        for (int w = 9; w <= 45; w++) if (slots[w] != null && slots[w].bottle()) n += slots[w].count();
        return n;
    }

    @Override
    public List<Integer> freeSlots() {
        List<Integer> out = new ArrayList<>();
        for (int w = 9; w <= 44; w++) if (slots[w] == null && !keep.contains(w)) out.add(w);
        return out;
    }

    @Override
    public boolean keep(int window) {
        return keep.contains(window);
    }

    @Override
    public boolean abort() {
        return abort;
    }

    @Override
    public void ledger(List<int[]> moves) {
        List<int[]> copy = new ArrayList<>();
        for (int[] m : moves) copy.add(m.clone());
        ledgers.add(copy);
    }

    @Override
    public void dropped(int[] move) {
        dropped++;
    }

    @Override
    public void afterClick() {
    }

    /** Runs the trip to its end (or the tick limit). */
    RepairTrip run(RepairTrip trip, int limit) {
        for (int i = 0; i < limit && !trip.done(); i++) {
            world();
            trip.tick();
        }
        return trip;
    }

    List<int[]> lastLedger() {
        return ledgers.isEmpty() ? List.of() : ledgers.get(ledgers.size() - 1);
    }
}
