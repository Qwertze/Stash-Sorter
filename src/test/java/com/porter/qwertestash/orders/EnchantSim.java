package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.Anvil;
import com.porter.qwerte.core.enchant.EnchantRules;
import com.porter.qwerte.core.enchant.McData;
import com.porter.qwerte.core.enchant.Stack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A fake world for {@link EnchantTrip}: an inventory of stacks, the bot's experience, and an anvil that works by the real rules ({@link Anvil}), so a
 * run through it checks the trip against the same costs the planner assumed. Flags let a test make the world misbehave (a higher cost, a wrong result,
 * a broken anvil, a player nearby, a force-cancel).
 */
final class EnchantSim implements EnchantTrip.Port {
    final McData data = McData.load(EnchantKit.MC);
    final String itemKey;
    final TreeMap<Integer, EnchantItems.Held> inv = new TreeMap<>();
    double points;
    int bottles;
    int handBottles;
    final List<String> log = new ArrayList<>();
    final List<Integer> stepsDone = new ArrayList<>();
    final List<Integer> shownCosts = new ArrayList<>();

    // The anvil
    boolean anvilBlock = true, open, arrived = true;
    int walkTicks, openTicks;
    EnchantItems.Held left, right, result;
    int shownCost = -1;
    /** Added to the cost the anvil shows (and so to what it takes); the result stays what the rules make. */
    int costBump;
    /** A result with these enchantments instead of what the rules make (a wrong result), or null. */
    @Nullable Map<String, Integer> wrongResult;
    /** Breaks the anvil on the next pickup (and lets the window close). */
    boolean breakOnTake;
    /** Puts this many items in the left slot (a stack of more than one). */
    int leftStack = 1;
    /** Takes any click for the given number of ticks as outstanding. */
    int busyTicks;
    /** Times the window closes on its own right after the items were put in (they come back to the inventory). */
    int dropWindows;
    /** Times a take is accepted but the result stays in the slot. */
    int skipTakes;

    // The world around
    boolean playerNear, controlled, abort, forceCancelled, noRoomForGear;
    int gearMoves, restores;
    int throwsRefused;
    int freeSlot = 30;
    boolean gearAway;

    EnchantSim(String itemKey) {
        this.itemKey = itemKey;
    }

    EnchantSim give(List<EnchantItems.Held> held) {
        for (EnchantItems.Held h : held) inv.put(h.slot(), h);
        return this;
    }

    EnchantSim xp(double p) {
        points = p;
        return this;
    }

    /** What the bot carries by what it is, for the end state. */
    List<EnchantItems.Held> stacks() {
        return new ArrayList<>(inv.values());
    }

    int levelNow() {
        return EnchantKit.levelOf(points);
    }

    // XpLeveler.Port

    @Override
    public boolean busy() {
        if (busyTicks > 0) {
            busyTicks--;
            return true;
        }
        return false;
    }

    @Override
    public double points() {
        return points;
    }

    @Override
    public int bottles() {
        return bottles + handBottles;
    }

    @Override
    public boolean bottleReady() {
        return handBottles > 0;
    }

    @Override
    public boolean refill() {
        if (handBottles > 0) return true;
        if (bottles <= 0) return true;
        int n = Math.min(64, bottles);
        bottles -= n;
        handBottles += n;
        return false;
    }

    @Override
    public boolean throwBottle() {
        if (throwsRefused > 0) {
            throwsRefused--;
            return false;
        }
        if (handBottles <= 0) return false;
        handBottles--;
        points += 7;
        return true;
    }

    @Override
    public int orbsNear() {
        return 0;
    }

    @Override
    public boolean abort() {
        return abort;
    }

    // EnchantTrip.Port

    @Override
    public boolean forceCancelled() {
        return forceCancelled;
    }

    @Override
    public boolean controlled() {
        return controlled;
    }

    @Override
    public boolean playerNear() {
        return playerNear;
    }

    @Override
    public int stowGear() {
        if (noRoomForGear) return -1;
        if (!gearAway) {
            gearAway = true;
            gearMoves++;
        }
        return 1;
    }

    @Override
    public boolean restoreGear() {
        if (gearAway) {
            gearAway = false;
            restores++;
        }
        return true;
    }

    @Override
    public List<EnchantItems.Held> carried() {
        return new ArrayList<>(inv.values());
    }

    @Override
    public int level() {
        return levelNow();
    }

    @Override
    public boolean arrived() {
        return arrived;
    }

    @Override
    public boolean walk() {
        if (++walkTicks >= 3) arrived = true;
        return true;
    }

    @Override
    public boolean pathing() {
        return false;
    }

    @Override
    public boolean anvilBlock() {
        return anvilBlock;
    }

    @Override
    public boolean anvilOpen() {
        return open;
    }

    @Override
    public boolean otherWindowOpen() {
        return false;
    }

    @Override
    public boolean open() {
        if (!anvilBlock) return false;
        open = true;
        openTicks++;
        return true;
    }

    @Override
    public boolean place(int l, int r) {
        if (!open || left != null || right != null) return false;
        left = inv.remove(l);
        right = inv.remove(r);
        if (left == null || right == null) throw new IllegalStateException("placed an empty slot");
        compute();
        if (dropWindows > 0) {
            dropWindows--;
            retrieve();
            open = false;
        }
        return true;
    }

    private void compute() {
        Stack ls = new Stack(left.book(), left.ench(), left.rc()), rs = new Stack(right.book(), right.ench(), right.rc());
        Anvil.Result r = Anvil.combine(data, itemKey, ls, leftStack, rs);
        if (r.result() == null) {
            result = null;
            shownCost = 0;
            return;
        }
        Map<String, Integer> ench = wrongResult != null ? wrongResult : r.result().enchants();
        result = new EnchantItems.Held(2, left.key(), left.id(), 1, left.book(), ench, r.result().repairCost(), false);
        shownCost = r.cost() + costBump;
    }

    @Override
    public int leftCount() {
        return left == null ? 0 : leftStack;
    }

    @Override
    public int rightCount() {
        return right == null ? 0 : 1;
    }

    @Override
    public int cost() {
        return left == null ? -1 : shownCost;
    }

    @Override
    public EnchantItems.@Nullable Held result() {
        return left == null ? null : result;
    }

    @Override
    public boolean take() {
        if (skipTakes > 0 && result != null) {
            skipTakes--;
            return true;
        }
        if (result == null || shownCost >= 40 || levelNow() < shownCost) return false;
        int lv = levelNow() - shownCost;
        points = EnchantRules.points(lv) + (points - EnchantRules.points(levelNow()));
        EnchantItems.Held got = new EnchantItems.Held(freeSlot++, result.key(), result.id(), 1, result.book(), result.ench(), result.rc(), false);
        inv.put(got.slot(), got);
        result = null;
        left = null;
        right = null;
        shownCost = -1;
        if (breakOnTake) {
            anvilBlock = false;
            open = false;
            breakOnTake = false;
        }
        return true;
    }

    @Override
    public boolean retrieve() {
        if (left != null) {
            inv.put(left.slot(), left);
            left = null;
        }
        if (right != null) {
            inv.put(right.slot(), right);
            right = null;
        }
        result = null;
        shownCost = -1;
        return true;
    }

    @Override
    public boolean close() {
        open = false;
        return true;
    }

    @Override
    public void stepDone(int step, int shown) {
        stepsDone.add(step);
        shownCosts.add(shown);
    }

    @Override
    public void say(String text) {
        log.add(text);
    }

    /** Ticks the trip until it is done (or the limit): the number of ticks used. */
    int run(EnchantTrip t, int limit) {
        int n = 0;
        while (!t.done() && n < limit) {
            t.tick();
            n++;
        }
        return n;
    }
}
