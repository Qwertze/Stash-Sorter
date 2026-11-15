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
    static class FakeLive implements SourceRules.Live {
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

        final Map<Item, String> shulkerWhy = new java.util.IdentityHashMap<>();

        @Override
        public @Nullable String shulkerWhy(Box box, Item shulker) {
            return shulkerWhy.get(shulker);
        }
    }

    private static Item arrowShulker() {
        return shulker(stack(ARROW, 64, 64));
    }

    /** The sorter's rule in miniature: refused only when kit sorting is on and the shulker is a complete kit. */
    private static SourceRules.Live kitLive(boolean kitSorting, Set<Item> completeKits) {
        return new FakeLive() {
            @Override
            public @Nullable String shulkerWhy(Box box, Item shulker) {
                return kitSorting && completeKits.contains(shulker) ? SourceRules.WHY_COMPLETE_KIT : null;
            }
        };
    }

    @Test
    void aCompleteKitInAPlainChestIsRefusedWithItsReason() {
        Item kit = arrowShulker();
        Box plain = box(1, kit);
        Set<Item> complete = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        complete.add(kit);
        StockPlanner.Survey s = SourceRules.survey(List.of(plain), Set.of(ARROW), Set.of(), false, kitLive(true, complete));
        assertNull(s.shulkerAvailable().get(ARROW));
        assertEquals("only in a complete kit (kits are never unpacked)", s.why().get(ARROW));
    }

    @Test
    void anIncompleteCopyOrAPlainShulkerInAKitOrRepairChestIsUsable() {
        // The sorter says nothing about them (not a complete kit), whatever box they sit in
        Box kitChest = box(1, arrowShulker());
        Box repairChest = box(2, arrowShulker());
        StockPlanner.Survey s = SourceRules.survey(List.of(kitChest, repairChest), Set.of(ARROW), Set.of(), false, kitLive(true, Set.of()));
        assertEquals(128L, s.shulkerAvailable().get(ARROW));
    }

    @Test
    void anIdenticalShulkerThatIsNotTheCompleteKitIsStillUsable() {
        Item kit = arrowShulker();
        Box kitBox = box(1, kit), other = box(2, arrowShulker());
        Set<Item> complete = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        complete.add(kit);
        SourceRules.Live live = kitLive(true, complete);
        StockPlanner.Survey s = SourceRules.survey(List.of(kitBox, other), Set.of(ARROW), Set.of(), false, live);
        assertEquals(64L, s.shulkerAvailable().get(ARROW));
        assertEquals(Map.of(SourceRules.Reject.COMPLETE_KIT, 1), SourceRules.rejected(List.of(kitBox, other), ARROW, Set.of(), false, live), "only the kit copy is turned down");
    }

    @Test
    void withKitSortingOffNothingIsRefused() {
        Item kit = arrowShulker();
        Box b = box(1, kit);
        Set<Item> complete = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        complete.add(kit);
        StockPlanner.Survey s = SourceRules.survey(List.of(b), Set.of(ARROW), Set.of(), false, kitLive(false, complete));
        assertEquals(64L, s.shulkerAvailable().get(ARROW));
    }

    @Test
    void precedenceIsNamedThenOwnLineThenCompleteKit() {
        Item own = arrowShulker();
        Set<Item> complete = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        complete.add(own);
        SourceRules.Live live = kitLive(true, complete);
        Set<String> reserved = Set.of(com.porter.qwertestash.manager.ManagerIndex.shulkerKey(own));
        assertEquals("in a shulker the order asks for whole", SourceRules.survey(List.of(box(1, own)), Set.of(ARROW), reserved, false, live).why().get(ARROW));
        Item named = arrowShulker();
        named.customName = true;
        complete.add(named);
        assertEquals("in a named shulker (use-named-shulkers is off)", SourceRules.survey(List.of(box(2, named)), Set.of(ARROW), Set.of(), false, live).why().get(ARROW));
    }

    @Test
    void craftSurveyRefusesACompleteKitButNotLooseStacksOrOthers() {
        Item kit = arrowShulker();
        Box a = box(1, kit, stack(ARROW, 5, 64)), b = box(2, arrowShulker());
        Set<Item> complete = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        complete.add(kit);
        CraftPool.Stock st = SourceRules.craftSurvey(List.of(a, b), Set.of(ARROW), Set.of(), false, Set.of(), kitLive(true, complete));
        assertEquals(64L, st.inShulkers().get(ARROW));
        assertEquals(5L, st.loose().get(ARROW));
    }

    @Test
    void completenessIsTheTemplateMatchKitClassifierMakes() {
        var t = new com.porter.qwertestash.stash.KitTemplate("t-abcdefgh", "Arrows", "pvp", null, null, 0, List.of(
            new com.porter.qwertestash.stash.KitTemplate.Slot(ARROW, ARROW, 64), new com.porter.qwertestash.stash.KitTemplate.Slot(PEARL, PEARL, 16)));
        assertTrue(SourceRules.isCompleteKit(List.of(t), shulker(stack(ARROW, 64, 64), stack(PEARL, 16, 16))));
        assertTrue(!SourceRules.isCompleteKit(List.of(t), shulker(stack(ARROW, 64, 64), stack(PEARL, 8, 16))), "wrong count");
        assertTrue(!SourceRules.isCompleteKit(List.of(t), shulker(stack(ARROW, 64, 64))), "missing item");
        assertTrue(!SourceRules.isCompleteKit(List.of(t), shulker(stack(ARROW, 64, 64), stack(PEARL, 16, 16), stack("minecraft:stick", 1, 64))), "extra item");
        assertTrue(!SourceRules.isCompleteKit(List.of(), shulker(stack(ARROW, 64, 64), stack(PEARL, 16, 16))));
    }

    @Test
    void rejectedNamesACompleteKitWhenItIsTheOnlyStock() {
        Item kit = arrowShulker();
        Box b = box(1, kit);
        Set<Item> complete = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        complete.add(kit);
        var rej = SourceRules.rejected(List.of(b), ARROW, Set.of(), false, kitLive(true, complete));
        assertEquals(Map.of(SourceRules.Reject.COMPLETE_KIT, 1), rej);
        assertEquals("1 completeKit", SourceRules.describe(rej));
        assertEquals("in a complete kit (kits are never unpacked)", SourceRules.note(SourceRules.Reject.COMPLETE_KIT));
        // Another container turned down for another reason keeps that reason
        FakeLive other = new FakeLive() {
            @Override
            public @Nullable String shulkerWhy(Box box, Item shulker) {
                return SourceRules.WHY_COMPLETE_KIT;
            }
        };
        other.blockedFetch.add(b);
        assertEquals(Map.of(SourceRules.Reject.BLOCKED, 1), SourceRules.rejected(List.of(b), ARROW, Set.of(), false, other));
    }

    @Test
    void ownShulkerKeysAreOnlyTheShulkerLines() {
        Unit a = new Unit(Unit.Kind.Shulker, "k1", List.of()), b = new Unit(Unit.Kind.Bin, "", List.of()), c = new Unit(Unit.Kind.Empty, "k2", List.of());
        assertEquals(Set.of("k1"), SourceRules.ownShulkerKeys(List.of(a, b, c)));
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
