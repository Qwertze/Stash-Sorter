package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.orders.Recipes;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingTableFinderTest {
    private static final Recipes R = Recipes.shipped();

    /** A fake world: the tables standing, the answer of a search, what the stash can give. */
    static final class Fake implements CraftingTableFinder.World {
        final Set<BlockPos> tables = new HashSet<>();
        @Nullable BlockPos found;
        boolean stock;
        CraftingTableFinder.Material material = CraftingTableFinder.Material.NONE;
        int searches;

        @Override
        public boolean isTable(BlockPos p) {
            return tables.contains(p);
        }

        @Override
        public @Nullable BlockPos search() {
            searches++;
            return found;
        }

        @Override
        public boolean stockHasTable() {
            return stock;
        }

        @Override
        public CraftingTableFinder.Material craftMaterial() {
            return material;
        }
    }

    private static final BlockPos A = new BlockPos(10, 64, 10);

    @Test
    void aTableThatIsThereIsUsedWithoutSearching() {
        Fake w = new Fake();
        w.tables.add(A);
        CraftingTableFinder.Decision d = CraftingTableFinder.decide(new CraftingTableFinder.Memory(A, "found"), w);
        assertEquals(CraftingTableFinder.Step.USE, d.step());
        assertEquals("found", d.source());
        assertFalse(d.forget());
        assertEquals(0, w.searches);
        // Settings from before 1.10.0 have no source: it reads as set
        assertEquals("set", CraftingTableFinder.decide(new CraftingTableFinder.Memory(A, ""), w).source());
    }

    @Test
    void aTableStandingInTheAreaIsFoundAndRemembered() {
        Fake w = new Fake();
        w.found = A;
        CraftingTableFinder.Decision d = CraftingTableFinder.decide(null, w);
        assertEquals(CraftingTableFinder.Step.FOUND, d.step());
        assertEquals(A, d.pos());
        assertEquals("found", d.source());
    }

    @Test
    void theNearestReachableTableInTheAreaWins() {
        BlockPos near = new BlockPos(2, 64, 0), far = new BlockPos(30, 64, 0), outside = new BlockPos(1, 64, 0), cornered = new BlockPos(0, 64, 3);
        BlockPos from = new BlockPos(0, 64, 0);
        // outside the perimeter is ignored even though it is nearest; cornered has nowhere to stand beside it
        BlockPos pick = CraftingTableFinder.nearest(List.of(far, near, outside, cornered), from, p -> p.x() != 1 && p.z() != 3);
        assertEquals(near, pick);
        assertNull(CraftingTableFinder.nearest(List.of(outside), from, p -> false));
        // Equal distance: by x, then y, then z
        BlockPos a = new BlockPos(3, 64, 0), b = new BlockPos(-3, 64, 0);
        assertEquals(b, CraftingTableFinder.nearest(List.of(a, b), from, p -> true));
    }

    @Test
    void aTableIsOnlyUsableWithASpotBesideItToStandOn() {
        AnvilFinder.Blocks none = new AnvilFinder.Blocks() {
            @Override
            public boolean loaded(int x, int z) {
                return true;
            }

            @Override
            public @Nullable String name(int x, int y, int z) {
                return null;
            }

            @Override
            public boolean standable(int x, int y, int z) {
                return false;
            }
        };
        assertFalse(CraftingTableFinder.standBeside(A, none));
        // A diagonal neighbour one block down counts (8 neighbours, dy -1..+1)
        AnvilFinder.Blocks diagonal = new AnvilFinder.Blocks() {
            @Override
            public boolean loaded(int x, int z) {
                return true;
            }

            @Override
            public @Nullable String name(int x, int y, int z) {
                return null;
            }

            @Override
            public boolean standable(int x, int y, int z) {
                return x == A.x() + 1 && z == A.z() + 1 && y == A.y() - 1;
            }
        };
        assertTrue(CraftingTableFinder.standBeside(A, diagonal));
    }

    @Test
    void withNoneStandingOneIsTakenFromTheStash() {
        Fake w = new Fake();
        w.stock = true;
        w.material = CraftingTableFinder.Material.PLANKS;
        CraftingTableFinder.Decision d = CraftingTableFinder.decide(null, w);
        assertEquals(CraftingTableFinder.Step.PLACE_FROM_STOCK, d.step());
        assertEquals("placed", d.source());
    }

    @Test
    void withNoStockOneIsCraftedFromPlanksOrLogs() {
        Fake w = new Fake();
        w.material = CraftingTableFinder.Material.PLANKS;
        CraftingTableFinder.Decision d = CraftingTableFinder.decide(null, w);
        assertEquals(CraftingTableFinder.Step.CRAFT, d.step());
        assertEquals("crafted", d.source());
        assertEquals(CraftingTableFinder.Material.PLANKS, d.material());
        w.material = CraftingTableFinder.Material.LOGS;
        assertEquals(CraftingTableFinder.Material.LOGS, CraftingTableFinder.decide(null, w).material());
    }

    @Test
    void whenNothingWorksTheReasonIsExact() {
        CraftingTableFinder.Decision d = CraftingTableFinder.decide(null, new Fake());
        assertEquals(CraftingTableFinder.Step.FAIL, d.step());
        assertEquals("no crafting table available", d.failure());
        assertEquals("none", d.source());
    }

    @Test
    void aRememberedTableThatIsGoneIsForgottenAndSearchedForAgain() {
        Fake w = new Fake();
        w.found = new BlockPos(20, 64, 20);
        CraftingTableFinder.Decision d = CraftingTableFinder.decide(new CraftingTableFinder.Memory(A, "placed"), w);
        assertTrue(d.forget());
        assertEquals(1, w.searches);
        assertEquals(CraftingTableFinder.Step.FOUND, d.step());
        // And with nothing else to be had it fails, still saying the old one is to be dropped
        CraftingTableFinder.Decision none = CraftingTableFinder.decide(new CraftingTableFinder.Memory(A, "set"), new Fake());
        assertTrue(none.forget());
        assertEquals(CraftingTableFinder.Step.FAIL, none.step());
    }

    @Test
    void aTableIsCraftedFromFourPlanksAnyWood() {
        for (String planks : new String[] {"minecraft:oak_planks", "minecraft:birch_planks", "minecraft:crimson_planks"}) {
            assertEquals(CraftingTableFinder.Material.PLANKS, CraftingTableFinder.material(R, Map.of(planks, 4L), Set.of()), planks);
        }
        assertEquals(CraftingTableFinder.Material.NONE, CraftingTableFinder.material(R, Map.of("minecraft:oak_planks", 3L), Set.of()));
        assertEquals(CraftingTableFinder.Material.NONE, CraftingTableFinder.material(R, Map.of(), Set.of()));
    }

    @Test
    void aTableIsCraftedFromLogsMadeIntoPlanksFirst() {
        assertEquals(CraftingTableFinder.Material.LOGS, CraftingTableFinder.material(R, Map.of("minecraft:spruce_log", 1L), Set.of()));
        assertEquals(CraftingTableFinder.Material.LOGS, CraftingTableFinder.material(R, Map.of("minecraft:oak_log", 3L), Set.of()));
        // Planks are used before logs are made into planks
        assertEquals(CraftingTableFinder.Material.PLANKS, CraftingTableFinder.material(R, Map.of("minecraft:oak_planks", 4L, "minecraft:oak_log", 3L), Set.of()));
    }

    @Test
    void theNeverUseListAndNoRecipesStopACraft() {
        assertEquals(CraftingTableFinder.Material.NONE, CraftingTableFinder.material(R, Map.of("minecraft:oak_planks", 8L), Set.of("minecraft:oak_planks")));
        assertEquals(CraftingTableFinder.Material.NONE, CraftingTableFinder.material(null, Map.of("minecraft:oak_planks", 8L), Set.of()));
        // A table in the pool needs no crafting: not a craft (it is taken from the stash instead)
        assertEquals(CraftingTableFinder.Material.NONE, CraftingTableFinder.material(R, Map.of("minecraft:crafting_table", 1L), Set.of()));
    }

    @Test
    void everyStepOfATableFitsTheInventoryGrid() {
        var plan = com.porter.qwertestash.orders.CraftPlanner.plan(R, CraftingTableFinder.TABLE_ID, 1, Map.of("minecraft:oak_log", 1L), CraftingTableFinder.limits(Set.of()));
        assertTrue(plan.ok(), plan.refusal());
        assertTrue(CraftingTableFinder.fitsInventoryGrid(plan));
        assertNotNull(plan.steps());
        // A shulker box needs the table's 3x3 grid for its chest: not a 2x2 plan
        var shulker = com.porter.qwertestash.orders.CraftPlanner.plan(R, "minecraft:shulker_box", 1, Map.of("minecraft:shulker_shell", 2L, "minecraft:oak_planks", 8L), CraftingTableFinder.limits(Set.of()));
        assertTrue(shulker.ok(), shulker.refusal());
        assertFalse(CraftingTableFinder.fitsInventoryGrid(shulker));
    }
}
