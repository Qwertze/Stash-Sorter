package com.porter.qwertestash.orders;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.7.2: the plan, the replan and the run take sources by one rule. In 1.7.1 the plan counted the arrows in a container the
 * run had given up on for now, the shulker was given up as "not enough left to pack", the replan (made from the same
 * survey) failed the same way, and the order ended "nothing could be delivered".
 */
class SourceRulesTest {
    private static final String ARROW = "minecraft:arrow", PEARL = "minecraft:ender_pearl";

    private static Item stack(String key, int count, int max) {
        Item it = new Item();
        it.id = key.substring("minecraft:".length());
        it.name = Item.prettyName(it.id);
        it.count = count;
        it.maxStack = max;
        it.key = key;
        it.variant = key;
        return it;
    }

    private static Item shulker(Item... inside) {
        Item s = stack("minecraft:red_shulker_box", 1, 1);
        s.contents = new ArrayList<>();
        for (int i = 0; i < inside.length; i++) {
            inside[i].slot = i;
            s.contents.add(inside[i]);
        }
        return s;
    }

    private static Box box(int x, Item... items) {
        BlockPos p = new BlockPos(x, 64, 0);
        Box b = new Box(p, new ArrayList<>(List.of(p)));
        b.contents = new Item[27];
        for (int i = 0; i < items.length; i++) b.contents[i] = items[i];
        return b;
    }

    /** The sorter's runtime state, as sets of containers. */
    static final class FakeLive implements SourceRules.Live {
        final Set<Box> blockedGather = new HashSet<>(), blockedFetch = new HashSet<>(), signPending = new HashSet<>(), badUnpack = new HashSet<>(), skipped = new HashSet<>();

        @Override
        public @Nullable String skip(Box box) {
            return skipped.contains(box) ? "in an excluded container" : null;
        }

        @Override
        public boolean signPending(Box box) {
            return signPending.contains(box);
        }

        @Override
        public boolean blockedGather(Box box) {
            return blockedGather.contains(box);
        }

        @Override
        public boolean blockedFetch(Box box) {
            return blockedFetch.contains(box);
        }

        @Override
        public boolean badUnpack(Box box) {
            return badUnpack.contains(box);
        }
    }

    @Test
    void aContainerTheRunHasGivenUpOnIsNotCountedByThePlan() {
        Box weapons = box(27, stack(ARROW, 64, 64), stack(ARROW, 64, 64));
        Box other = box(30, stack(ARROW, 10, 64));
        FakeLive live = new FakeLive();
        live.blockedGather.add(weapons);

        StockPlanner.Survey s = SourceRules.survey(List.of(weapons, other), Set.of(ARROW), Set.of(), false, live);
        assertEquals(10L, s.available().get(ARROW), "only the arrows the run would take");
        assertEquals("in a container given up on for now", s.why().get(ARROW));

        live.blockedGather.clear();
        s = SourceRules.survey(List.of(weapons, other), Set.of(ARROW), Set.of(), false, live);
        assertEquals(138L, s.available().get(ARROW));
    }

    @Test
    void aSignBeingChangedAndAShulkerThatCouldNotBeTakenAreSkippedToo() {
        Box signs = box(1, stack(PEARL, 16, 16));
        Box totems = box(7, shulker(stack(ARROW, 64, 64)));
        FakeLive live = new FakeLive();
        live.signPending.add(signs);
        live.badUnpack.add(totems);

        StockPlanner.Survey s = SourceRules.survey(List.of(signs, totems), Set.of(ARROW, PEARL), Set.of(), false, live);
        assertNull(s.available().get(PEARL));
        assertNull(s.shulkerAvailable().get(ARROW));
        assertEquals("in a container whose sign is being changed", s.why().get(PEARL));
        assertEquals("in a shulker that couldn't be taken out", s.why().get(ARROW));
    }

    @Test
    void lostStacksAndShulkerContentsFollowTheirOwnRules() {
        // A trip for loose stacks that came back empty does not rule out the shulkers in that container, and the other way round
        Box b = box(3, stack(ARROW, 32, 64), shulker(stack(PEARL, 16, 16)));
        FakeLive live = new FakeLive();
        live.blockedGather.add(b);
        assertEquals(SourceRules.Reject.BLOCKED, SourceRules.loose(b, live));
        assertNull(SourceRules.unpack(b, live));
        StockPlanner.Survey s = SourceRules.survey(List.of(b), Set.of(ARROW, PEARL), Set.of(), false, live);
        assertNull(s.available().get(ARROW));
        assertEquals(16L, s.shulkerAvailable().get(PEARL));

        live.blockedGather.clear();
        live.blockedFetch.add(b);
        assertNull(SourceRules.loose(b, live));
        assertEquals(SourceRules.Reject.BLOCKED, SourceRules.unpack(b, live));
    }

    @Test
    void aReplanExcludesBlockedContainers() {
        // Planned while both containers were usable: four stacks of arrows
        Box a = box(1, stack(ARROW, 64, 64), stack(ARROW, 64, 64));
        Box b = box(2, stack(ARROW, 64, 64), stack(ARROW, 64, 64));
        FakeLive live = new FakeLive();
        Order order = new Order("o1", List.of(new Order.Line(Order.ITEM, ARROW, 256)));
        StockPlanner.Plan plan = StockPlanner.plan(order, SourceRules.survey(List.of(a, b), Set.of(ARROW), Set.of(), false, live), Map.of("minecraft:shulker_box", 1), k -> k);
        assertNull(plan.failure());
        List<Order.Lot> lots = plan.units().get(0).lots;
        assertEquals(4, lots.size());

        // The run has given up on one of them since: the replan (trim) only keeps what the other can give
        live.blockedGather.add(b);
        List<Order.Lot> trimmed = StockPlanner.trim(lots, SourceRules.survey(List.of(a, b), Set.of(ARROW), Set.of(), false, live));
        assertEquals(2, trimmed.size());
        assertEquals(128, trimmed.stream().mapToInt(Order.Lot::count).sum());

        // Nothing usable at all: the replan is empty, and the unit is skipped with a note instead of failing again
        live.blockedGather.add(a);
        assertTrue(StockPlanner.trim(lots, SourceRules.survey(List.of(a, b), Set.of(ARROW), Set.of(), false, live)).isEmpty());
    }

    @Test
    void theRejectedContainersAreCountedByReason() {
        Box stale = box(1, stack(ARROW, 64, 64));
        stale.stale = true;
        Box blocked = box(2, stack(ARROW, 64, 64));
        Box sign = box(3, stack(ARROW, 64, 64));
        Box bad = box(4, shulker(stack(ARROW, 64, 64)));
        Box excluded = box(5, stack(ARROW, 1, 64));
        Box fine = box(6, stack(ARROW, 5, 64));
        Box unrelated = box(7, stack(PEARL, 16, 16));
        FakeLive live = new FakeLive();
        live.blockedGather.add(blocked);
        live.signPending.add(sign);
        live.badUnpack.add(bad);
        live.skipped.add(excluded);

        var counts = SourceRules.rejected(List.of(stale, blocked, sign, bad, excluded, fine, unrelated), ARROW, Set.of(), false, live);
        assertEquals(Map.of(SourceRules.Reject.STALE, 1, SourceRules.Reject.BLOCKED, 1, SourceRules.Reject.SIGN_PENDING, 1,
            SourceRules.Reject.BAD_UNPACK, 1, SourceRules.Reject.OTHER, 1), counts);
        assertEquals("1 other, 1 stale, 1 signPending, 1 blocked, 1 badUnpack", SourceRules.describe(counts));
        assertEquals("none", SourceRules.describe(Map.of()));
    }
}
