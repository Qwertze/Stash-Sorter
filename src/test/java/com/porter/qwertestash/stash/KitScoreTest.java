package com.porter.qwertestash.stash;

import com.porter.qwertestash.stash.KitScore.Result;
import com.porter.qwertestash.stash.KitScore.Scoring;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The kit score and its contract vectors (KIT-CONTRACT.md v1.1), against the real shared table. */
class KitScoreTest {
    private static final KitItems TABLE = KitItems.shared();

    /** n slots, cycling through the ids. */
    private static List<String> slots(int n, String... ids) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add("minecraft:" + ids[i % ids.length]);
        return out;
    }

    private static void check(List<String> ids, String name, int partName, int mix, int full, int op, int total, boolean kit) {
        Result r = KitScore.score(ids, name, Scoring.DEFAULT, TABLE);
        assertEquals(new Result(partName, mix, full, op, total, kit), r, "name=" + name + " slots=" + ids.size());
    }

    @Test
    void theTableIsTheRealOneAndLoaded() {
        assertFalse(TABLE.op().isEmpty());
        assertFalse(TABLE.groups().isEmpty());
        assertEquals(10, Scoring.DEFAULT.max());
    }

    @Test
    void contractVectors() {
        check(slots(27, "netherite_helmet", "netherite_chestplate", "netherite_sword", "totem_of_undying", "end_crystal", "obsidian", "respawn_anchor",
            "glowstone", "ender_pearl", "golden_apple"), "Crystal Kit", 2, 3, 1, 4, 10, true);
        check(slots(27, "netherite_helmet", "netherite_chestplate", "netherite_leggings", "netherite_boots", "potion"), null, 0, 3, 1, 4, 8, true);
        check(slots(27, "white_dye", "orange_dye", "magenta_dye", "light_blue_dye", "yellow_dye", "lime_dye", "pink_dye", "gray_dye", "light_gray_dye",
            "cyan_dye", "purple_dye", "blue_dye", "brown_dye", "green_dye", "red_dye", "black_dye"), "Dye Kit", 2, 3, 1, 0, 6, true);
        check(slots(27, "white_concrete", "orange_concrete", "magenta_concrete", "light_blue_concrete", "yellow_concrete", "lime_concrete", "pink_concrete",
            "gray_concrete", "light_gray_concrete", "cyan_concrete", "purple_concrete", "blue_concrete", "brown_concrete", "green_concrete", "red_concrete",
            "black_concrete"), null, 0, 3, 1, 0, 4, true);
        check(slots(10, "totem_of_undying"), null, 0, 0, 0, 4, 4, false);
        check(slots(27, "totem_of_undying"), null, 0, 0, 1, 4, 5, true);
        check(slots(27, "diorite"), null, 0, 0, 1, 0, 1, false);
        check(List.of(), "kit", 2, 0, 0, 0, 2, false);
        // 3 elytras plus firework rockets, 27 slots
        List<String> elytras = new ArrayList<>(slots(24, "firework_rocket"));
        elytras.addAll(slots(3, "elytra"));
        check(elytras, null, 0, 0, 1, 4, 5, true);
        // mixed storage: 1 diamond sword plus cobblestone, dirt and logs, 10 slots: one OP item is not enough
        List<String> storage = new ArrayList<>(List.of("diamond_sword"));
        storage.addAll(slots(9, "cobblestone", "dirt", "oak_log"));
        check(storage, null, 0, 0, 0, 0, 0, false);
        // 3 totems plus cobblestone and dirt, 8 slots
        List<String> mixed = new ArrayList<>(slots(3, "totem_of_undying"));
        mixed.addAll(slots(5, "cobblestone", "dirt"));
        check(mixed, null, 0, 0, 0, 4, 4, true);
        // 4 totems only: single type, not full
        check(slots(4, "totem_of_undying"), null, 0, 0, 0, 4, 4, false);
    }

    @Test
    void opItemsAreCountedPerItemNotPerSlot() {
        // 1 sword plus 2 god apples in one stack: 3 OP items in 2 slots
        List<KitScore.Stack> stacks = List.of(new KitScore.Stack("diamond_sword", 1), new KitScore.Stack("enchanted_golden_apple", 2), new KitScore.Stack("dirt", 64));
        assertEquals(4, KitScore.scoreStacks(stacks, null, Scoring.DEFAULT, TABLE).op());
        assertTrue(KitScore.scoreStacks(stacks, null, Scoring.DEFAULT, TABLE).kit());
        // Three slots of OP items would be three items too, two stacks of one are not enough
        assertEquals(0, KitScore.scoreStacks(List.of(new KitScore.Stack("totem_of_undying", 1), new KitScore.Stack("dirt", 1)), null, Scoring.DEFAULT, TABLE).op());
        // A stack of 3 totems alone: 3 items, but one slot of a single type is not a kit
        List<KitScore.Stack> one = List.of(new KitScore.Stack("totem_of_undying", 3));
        assertEquals(4, KitScore.scoreStacks(one, null, Scoring.DEFAULT, TABLE).op());
        assertFalse(KitScore.scoreStacks(one, null, Scoring.DEFAULT, TABLE).kit());
        // The count of every listed item adds up across different ones
        List<KitScore.Stack> sum = List.of(new KitScore.Stack("totem_of_undying", 1), new KitScore.Stack("elytra", 1), new KitScore.Stack("end_crystal", 1));
        assertEquals(4, KitScore.scoreStacks(sum, null, Scoring.DEFAULT, TABLE).op());
    }

    @Test
    void theStashsOpMinDecides() {
        List<String> two = new ArrayList<>(slots(2, "totem_of_undying"));
        two.addAll(slots(4, "dirt", "stone"));
        assertEquals(0, KitScore.score(two, null, Scoring.DEFAULT, TABLE).op());
        assertEquals(4, KitScore.score(two, null, new Scoring(4, 2, 3, 1, 4, 2), TABLE).op());
        assertEquals(0, KitScore.score(slots(27, "totem_of_undying", "stone"), null, new Scoring(4, 2, 3, 1, 4, 15), TABLE).op(), "14 totems against opMin 15");
        assertEquals(4, KitScore.score(slots(27, "totem_of_undying", "stone"), null, new Scoring(4, 2, 3, 1, 4, 14), TABLE).op());
        assertTrue(Scoring.valid(4, 2, 3, 1, 4, 1728));
        assertFalse(Scoring.valid(4, 2, 3, 1, 4, 0));
        assertFalse(Scoring.valid(4, 2, 3, 1, 4, 1729));
        assertEquals(3, Scoring.DEFAULT.opMin());
    }

    @Test
    void stackCountsAreKeptInsideTheShulkerItem() {
        Item box = new Item();
        box.id = "blue_shulker_box";
        Item apples = new Item();
        apples.id = "enchanted_golden_apple";
        apples.count = 2;
        Item sword = new Item();
        sword.id = "diamond_sword";
        sword.count = 1;
        Item dirt = new Item();
        dirt.id = "dirt";
        dirt.count = 64;
        box.contents.addAll(List.of(apples, sword, dirt));
        assertEquals(4, KitScore.of(box, Scoring.DEFAULT, TABLE).op(), "1 sword plus 2 god apples");
    }

    @Test
    void namesGiveTheNamePointsOnlyForTheWordKit() {
        List<String> ids = slots(27, "totem_of_undying", "stone");
        assertEquals(2, KitScore.score(ids, "PvP Kit", Scoring.DEFAULT, TABLE).name());
        assertEquals(2, KitScore.score(ids, "§6my-kits!", Scoring.DEFAULT, TABLE).name());
        assertEquals(0, KitScore.score(ids, "Toolkit", Scoring.DEFAULT, TABLE).name());
        assertEquals(0, KitScore.score(ids, "Mixed Concrete", Scoring.DEFAULT, TABLE).name());
        assertEquals(0, KitScore.score(ids, null, Scoring.DEFAULT, TABLE).name());
    }

    @Test
    void aGroupNeedsItsMinOfDistinctIds() {
        // gear min 3: two ids are not a mix, three are, the same id three times is one
        assertEquals(0, KitScore.score(slots(5, "netherite_helmet", "elytra"), null, Scoring.DEFAULT, TABLE).mix());
        assertEquals(3, KitScore.score(slots(5, "netherite_helmet", "elytra", "shield"), null, Scoring.DEFAULT, TABLE).mix());
        assertEquals(0, KitScore.score(slots(20, "netherite_helmet"), null, Scoring.DEFAULT, TABLE).mix());
        // concrete min 5: four colours are not enough
        assertEquals(0, KitScore.score(slots(27, "white_concrete", "orange_concrete", "lime_concrete", "red_concrete"), null, Scoring.DEFAULT, TABLE).mix());
        assertEquals(3, KitScore.score(slots(27, "white_concrete", "orange_concrete", "lime_concrete", "red_concrete", "blue_concrete"), null, Scoring.DEFAULT, TABLE).mix());
        // Ids of different groups do not add up: two gear ids and three concrete ids
        assertEquals(0, KitScore.score(slots(10, "elytra", "shield", "white_concrete", "red_concrete", "blue_concrete"), null, Scoring.DEFAULT, TABLE).mix());
        // Mix points are given once however many groups match
        assertEquals(3, KitScore.score(slots(27, "elytra", "shield", "bow", "white_wool", "red_wool", "blue_wool", "lime_wool", "pink_wool"), null, Scoring.DEFAULT, TABLE).mix());
    }

    @Test
    void idsWithOrWithoutTheNamespaceAreTheSame() {
        assertEquals(KitScore.score(List.of("totem_of_undying"), null, Scoring.DEFAULT, TABLE), KitScore.score(List.of("minecraft:totem_of_undying"), null, Scoring.DEFAULT, TABLE));
        assertEquals(4, KitScore.score(List.of("totem_of_undying", "minecraft:totem_of_undying", "totem_of_undying", "stone"), null, Scoring.DEFAULT, TABLE).op());
    }

    @Test
    void theSingleTypeRuleAndTheEmptyBox() {
        // Whatever the total, one item in a part-full box is never a kit
        Scoring generous = new Scoring(1, 10, 10, 10, 10);
        assertFalse(KitScore.score(slots(26, "totem_of_undying"), "Kit", generous, TABLE).kit());
        assertTrue(KitScore.score(slots(27, "totem_of_undying"), "Kit", generous, TABLE).kit());
        // Two kinds in a part-full box is not single-type
        assertTrue(KitScore.score(slots(8, "totem_of_undying", "stone"), null, Scoring.DEFAULT, TABLE).kit());
        assertFalse(KitScore.score(List.of(), "Kit", generous, TABLE).kit(), "an empty box is never a kit");
    }

    @Test
    void thresholdAndPointsAreTheStashsOwn() {
        List<String> ids = slots(27, "totem_of_undying", "stone");
        assertEquals(5, KitScore.score(ids, null, Scoring.DEFAULT, TABLE).total());
        assertTrue(KitScore.score(ids, null, new Scoring(5, 2, 3, 1, 4), TABLE).kit());
        assertFalse(KitScore.score(ids, null, new Scoring(6, 2, 3, 1, 4), TABLE).kit());
        assertFalse(KitScore.score(ids, null, new Scoring(4, 2, 3, 1, 0), TABLE).kit(), "op worth nothing: only full is left");
        assertEquals(3 + 1, KitScore.score(slots(27, "totem_of_undying", "stone"), "kit", new Scoring(4, 3, 3, 1, 0), TABLE).total());
    }

    @Test
    void aTableWithoutItemsOnlyScoresNameAndFull() {
        Result r = KitScore.score(slots(27, "totem_of_undying", "stone"), "Kit", Scoring.DEFAULT, KitItems.EMPTY);
        assertEquals(new Result(2, 0, 1, 0, 3, false), r);
    }

    @Test
    void aTableOfItsOwn() {
        KitItems table = new KitItems(Set.of("minecraft:stone"), List.of(new KitItems.Group("pair", 2, Set.of("minecraft:dirt", "minecraft:sand"))));
        assertEquals(new Result(0, 3, 0, 4, 7, true), KitScore.score(List.of("dirt", "sand", "stone", "stone", "stone"), null, Scoring.DEFAULT, table));
    }

    @Test
    void scoringRanges() {
        assertTrue(Scoring.valid(1, 0, 0, 0, 0));
        assertTrue(Scoring.valid(40, 10, 10, 10, 10));
        assertFalse(Scoring.valid(0, 2, 3, 1, 4));
        assertFalse(Scoring.valid(41, 2, 3, 1, 4));
        assertFalse(Scoring.valid(4, 11, 3, 1, 4));
        assertFalse(Scoring.valid(4, 2, -1, 1, 4));
    }

    @Test
    void aStackThatIsNotAShulkerIsNeverAKit() {
        Item cobble = new Item();
        cobble.id = "cobblestone";
        cobble.count = 64;
        assertEquals(Result.NONE, KitScore.of(cobble, Scoring.DEFAULT, TABLE));

        Item box = new Item();
        box.id = "blue_shulker_box";
        box.customName = true;
        box.customText = "Kit";
        for (int i = 0; i < 27; i++) {
            Item inner = new Item();
            inner.id = i % 2 == 0 ? "totem_of_undying" : "stone";
            box.contents.add(inner);
        }
        assertTrue(KitScore.of(box, Scoring.DEFAULT, TABLE).kit());
    }
}
