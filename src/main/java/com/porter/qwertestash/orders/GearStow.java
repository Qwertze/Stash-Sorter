package com.porter.qwertestash.orders;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the bot's own gear out of the way of the XP while an enchant order throws bottles (ENCHANT-CONTRACT v1.0, "Bot rules" 6; the owner's decision of
 * 2026-11-12: the bottles are thrown from the MAIN hand, so the off hand, a totem included, is never touched). Damaged Mending armour is stored in free
 * inventory slots (it would take the orbs), and a bottle stack goes into a hotbar slot that is selected: one that holds bottles already, else the held one
 * when it is empty, else an empty one, else any that is not kept (its occupant is stored first). Every move is kept in a ledger the port saves, so a restart
 * puts it back (StashSorter.startGearUndo reads the same ledger), and {@link #restore()} undoes the moves, last first, and selects the hotbar slot
 * that was held before. Plain Java behind {@link RepairTrip.Port}; the window numbering is the player inventory window's: 5-8 armour, 9-35 inventory,
 * 36-44 hotbar. All of it is for the bot tick thread.
 */
public final class GearStow {
    private final RepairTrip.Port port;
    private final List<int[]> ledger = new ArrayList<>();
    private final List<int[]> queue = new ArrayList<>();
    private boolean planned;
    private int stuck, hand = -1, originalHeld = -1;

    public GearStow(RepairTrip.Port port) {
        this.port = port;
    }

    /** Takes up the moves an earlier stow left (a trip made again in the same session): they are put back with this one's. */
    public GearStow seed(List<int[]> pending) {
        for (int[] m : pending) ledger.add(m.clone());
        return this;
    }

    /** Moves made and not yet undone. */
    public List<int[]> pending() {
        return ledger;
    }

    /** Damaged Mending armour worn now (the pieces that would take the XP). */
    public int damagedMendingArmour() {
        int n = 0;
        for (int w = RepairTrip.ARMOUR_FIRST; w <= RepairTrip.ARMOUR_LAST; w++) {
            RepairTrip.Stack s = port.at(w);
            if (s != null && s.mending() && s.damage() > 0) n++;
        }
        return n;
    }

    /** The window slot the bottles are thrown from (a hotbar slot), or -1 before it was chosen. */
    public int handSlot() {
        return hand;
    }

    /** A bottle stack is in the selected hotbar slot: the main hand. */
    public boolean bottleReady() {
        if (hand < 0) return false;
        RepairTrip.Stack s = port.at(hand);
        return s != null && s.bottle() && port.held() == hand - RepairTrip.HOTBAR_FIRST;
    }

    /** The window slot of the biggest bottle stack in the inventory (not the hand slot), or -1. */
    private int bottleStack() {
        int best = -1, count = 0;
        for (int w = RepairTrip.INVENTORY_FIRST; w <= RepairTrip.HOTBAR_LAST; w++) {
            if (w == hand || port.keep(w)) continue;
            RepairTrip.Stack s = port.at(w);
            if (s != null && s.bottle() && s.count() > count) {
                best = w;
                count = s.count();
            }
        }
        return best;
    }

    private int pickHand() {
        int heldWindow = RepairTrip.HOTBAR_FIRST + Math.max(0, port.held());
        for (int w = RepairTrip.HOTBAR_FIRST; w <= RepairTrip.HOTBAR_LAST; w++) {
            RepairTrip.Stack s = port.at(w);
            if (!port.keep(w) && s != null && s.bottle()) return w;
        }
        if (!port.keep(heldWindow) && port.at(heldWindow) == null) return heldWindow;
        for (int w = RepairTrip.HOTBAR_FIRST; w <= RepairTrip.HOTBAR_LAST; w++) if (!port.keep(w) && port.at(w) == null) return w;
        for (int w = RepairTrip.HOTBAR_FIRST; w <= RepairTrip.HOTBAR_LAST; w++) if (!port.keep(w)) return w;
        return -1;
    }

    /**
     * One tick of putting the gear away.
     *
     * @return 0 while moves are going on, 1 when the gear is away and a bottle stack is in the selected hotbar slot (or there are no bottles: nothing to put
     *         there), -1 when there is no room for what has to be stored
     */
    public int stow() {
        if (port.busy()) return 0;
        if (!planned) {
            planned = true;
            originalHeld = port.held();
            hand = pickHand();
            if (hand < 0) return -1;
            List<Integer> free = new ArrayList<>(port.freeSlots());
            free.remove((Integer) hand);
            for (int w = RepairTrip.ARMOUR_FIRST; w <= RepairTrip.ARMOUR_LAST; w++) {
                RepairTrip.Stack s = port.at(w);
                if (s != null && s.mending() && s.damage() > 0) {
                    if (free.isEmpty()) return -1;
                    queue.add(new int[] {w, free.remove(0)});
                }
            }
            RepairTrip.Stack in = port.at(hand);
            if (in != null && !in.bottle()) {
                if (free.isEmpty()) return -1;
                queue.add(new int[] {hand, free.remove(0)});
            }
        }
        if (!queue.isEmpty()) {
            int[] m = queue.get(0);
            if (port.at(m[0]) == null || port.at(m[1]) != null) {
                // Not as it was when it was planned: nothing is moved blindly
                queue.clear();
                return -1;
            }
            if (!port.move(m[0], m[1])) {
                if (++stuck > RepairTrip.STUCK_TICKS) {
                    queue.clear();
                    return -1;
                }
                return 0;
            }
            stuck = 0;
            queue.remove(0);
            ledger.add(m);
            port.ledger(ledger);
            port.afterClick();
            return 0;
        }
        return refill() ? 1 : 0;
    }

    /**
     * A bottle stack into the hand slot when it holds none (the stack there was used up, or none was put there yet) and that slot selected. True once a
     * stack is in the hand, or when the inventory holds no bottle at all (there is nothing to move); false while a move or a selection is still to be made.
     */
    public boolean refill() {
        if (hand < 0) return false;
        RepairTrip.Stack in = port.at(hand);
        if (in == null) {
            int from = bottleStack();
            if (from < 0) return true;
            if (port.busy()) return false;
            if (port.move(from, hand)) {
                ledger.add(new int[] {from, hand});
                port.ledger(ledger);
                port.afterClick();
            }
            return false;
        }
        if (!in.bottle()) return false;
        if (port.held() != hand - RepairTrip.HOTBAR_FIRST) {
            if (!port.busy()) port.select(hand - RepairTrip.HOTBAR_FIRST);
            return false;
        }
        return true;
    }

    /**
     * One tick of putting everything back, last move first (as RepairTrip's restore: a used-up stack just stays gone, a place taken meanwhile gives
     * another free slot), then the hotbar slot held before is selected again. True once the ledger is empty.
     */
    public boolean restore() {
        if (port.busy()) return false;
        while (!ledger.isEmpty()) {
            int[] m = ledger.get(ledger.size() - 1);
            int dest = m[0];
            RepairTrip.Stack there = port.at(m[1]);
            if (there != null && port.at(dest) != null) {
                List<Integer> free = port.freeSlots();
                dest = free.isEmpty() ? -1 : free.get(0);
            }
            if (there != null && dest >= 0 && !Repair.fitsSlot(dest, there.id())) {
                port.dropped(m);
                dest = -1;
            }
            if (there == null || dest < 0) {
                ledger.remove(ledger.size() - 1);
                port.ledger(ledger);
                continue;
            }
            if (!port.move(m[1], dest)) {
                if (++stuck > RepairTrip.STUCK_TICKS) return true; // left in the saved ledger, to be undone by a later start
                return false;
            }
            stuck = 0;
            ledger.remove(ledger.size() - 1);
            port.ledger(ledger);
            port.afterClick();
            return false;
        }
        if (originalHeld >= 0 && port.held() != originalHeld && !port.select(originalHeld)) return false;
        planned = false;
        hand = -1;
        originalHeld = -1;
        return true;
    }
}
