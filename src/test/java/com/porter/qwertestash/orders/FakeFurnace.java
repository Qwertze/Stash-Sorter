package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * A furnace the FurnaceTrip tests run against: three slots, an inventory of carried stacks by kind, and the cooking of vanilla (one item per cook time,
 * the input going down when an item is done, one fuel item lit when there is input and nothing burns, burning on with the window closed). Clicks take
 * effect at once; {@code noRoom} refuses every take, {@code frozen} stops the cooking, {@code closeAt} closes the window once at that tick.
 */
final class FakeFurnace implements FurnaceTrip.Port {
    final String[] kind = new String[3];
    final int[] count = new int[3];
    final Map<String, Integer> inventory = new HashMap<>();
    int cook = 200, burnLeft, progress, tick, closeAt = -1, opens;
    final Map<String, Integer> burnTicks = new HashMap<>(Map.of("minecraft:coal", 1600, "minecraft:charcoal", 1600));
    final Map<String, String> smelts = new HashMap<>(Map.of("minecraft:sand", "minecraft:glass", "minecraft:red_sand", "minecraft:glass"));
    boolean refuseOpen, refuseOpenAfterClose, burnOnlyWhileSmelting, open, noRoom, frozen, arrived = true;
    int xp = 0;

    FakeFurnace put(int slot, String k, int n) {
        kind[slot] = k;
        count[slot] = n;
        return this;
    }

    FakeFurnace carry(String k, int n) {
        inventory.merge(k, n, Integer::sum);
        return this;
    }

    boolean furnaceEmpty() {
        return kind[0] == null && kind[1] == null && kind[2] == null;
    }

    int total(String k) {
        int n = inventory.getOrDefault(k, 0);
        for (int s = 0; s < 3; s++) if (k.equals(kind[s])) n += count[s];
        return n;
    }

    /** One game tick of the furnace. */
    void tick() {
        tick++;
        if (tick == closeAt) {
            open = false;
            closeAt = -1;
            if (refuseOpenAfterClose) refuseOpen = true;
        }
        if (frozen) return;
        String in = kind[0];
        boolean canSmelt = in != null && count[0] > 0 && smelts.containsKey(in) && (kind[2] == null || kind[2].equals(smelts.get(in)));
        // Vanilla burns a lit fuel down whether or not anything cooks; the flag makes it burn only while it cooks (an idealised furnace for exact-total tests)
        if (burnLeft > 0 && (!burnOnlyWhileSmelting || canSmelt)) burnLeft--;
        if (canSmelt && burnLeft == 0 && kind[1] != null && count[1] > 0) {
            burnLeft = burnTicks.getOrDefault(kind[1], 0);
            if (burnLeft > 0 && --count[1] == 0) kind[1] = null;
        }
        if (canSmelt && burnLeft > 0) {
            if (++progress >= cook) {
                progress = 0;
                if (--count[0] == 0) kind[0] = null;
                kind[2] = smelts.get(in);
                count[2]++;
                xp++;
            }
        }
        else progress = Math.max(0, progress - 2);
    }

    @Override
    public boolean arrived() {
        return arrived;
    }

    @Override
    public boolean walk() {
        return true;
    }

    @Override
    public boolean pathing() {
        return false;
    }

    @Override
    public boolean furnaceOpen() {
        return open;
    }

    @Override
    public boolean otherWindowOpen() {
        return false;
    }

    @Override
    public boolean open() {
        if (refuseOpen) return false;
        open = true;
        opens++;
        return true;
    }

    @Override
    public boolean busy() {
        return false;
    }

    @Override
    public Map<String, Integer> carried() {
        return inventory;
    }

    @Override
    public @Nullable String itemAt(int slot) {
        return open ? kind[slot] : null;
    }

    @Override
    public int countAt(int slot) {
        return open && kind[slot] != null ? count[slot] : 0;
    }

    @Override
    public boolean place(int slot, String k, int goal) {
        if (!open) return false;
        if (kind[slot] != null && !kind[slot].equals(k)) return false;
        int have = kind[slot] == null ? 0 : count[slot];
        int move = Math.min(goal - have, inventory.getOrDefault(k, 0));
        if (move <= 0) return false;
        inventory.merge(k, -move, Integer::sum);
        kind[slot] = k;
        count[slot] = have + move;
        return true;
    }

    @Override
    public boolean holdingCursor() {
        return false;
    }

    @Override
    public int stowCursor() {
        return -1;
    }

    @Override
    public int take(int slot) {
        if (!open || kind[slot] == null || noRoom) return -1;
        inventory.merge(kind[slot], count[slot], Integer::sum);
        kind[slot] = null;
        count[slot] = 0;
        return 1;
    }

    @Override
    public boolean close() {
        open = false;
        return true;
    }

    @Override
    public double xp() {
        return xp;
    }
}
