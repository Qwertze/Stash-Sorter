package com.porter.qwertestash.orders;

import com.aquarius.mc.item.ItemRegistry;
import com.porter.qwertestash.stash.FireworkItems;
import com.porter.qwertestash.stash.Item;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Fireworks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.porter.qwertestash.orders.FireworkFixtures.fw;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec of a rocket or star from its real 1.21.4 components (CRAFT-CONTRACT-v1.2.md 3.1, vectors S1 to S20), the reading of a spec back, and the check of a
 * special step's grid against the bot's own extras (11.1 and 15). The grids are the ones the real server's planner writes (FireworkFixtures).
 */
class FireworkSpecsTest {
    private static final FireworkSpecs.Tables T = FireworkFixtures.TABLES;

    // Real components, as the proxy decodes them

    private static Fireworks.FireworkExplosion explosion(int shape, int[] colors, int[] fades, boolean trail, boolean twinkle) {
        return Fireworks.FireworkExplosion.builder().shapeId(shape).colors(colors).fadeColors(fades).hasTrail(trail).hasTwinkle(twinkle).build();
    }

    private static DataComponents rocketPatch(int flight, Fireworks.FireworkExplosion... ex) {
        DataComponents c = new DataComponents(new HashMap<>());
        c.put(DataComponentTypes.FIREWORKS, Fireworks.builder().flightDuration(flight).explosions(new ArrayList<>(List.of(ex))).build());
        return c;
    }

    private static DataComponents starPatch(Fireworks.FireworkExplosion e) {
        DataComponents c = new DataComponents(new HashMap<>());
        c.put(DataComponentTypes.FIREWORK_EXPLOSION, e);
        return c;
    }

    private static final int[] NONE = {};

    /** The spec the plugin derives for a stack with this patch: through the stack's saved data, as the index does. */
    private static String specOf(String id, DataComponents patch) {
        var defaults = ItemRegistry.REGISTRY.get(id).components();
        String data = FireworkItems.dataOf(id, patch, defaults);
        return FireworkSpecs.specOf(data, T);
    }

    // S1 to S13: against real component objects

    @Test
    void s1AndS2_aRocketWithNoStarsIsItsFlight() {
        // [UNVERIFIED, B3] A rocket crafted with 1 gunpowder has no patch: the registry's default (flight 1, no explosions) is what the plain item is
        assertEquals("rocket:1", specOf("firework_rocket", new DataComponents(new HashMap<>())));
        assertEquals("rocket:3", specOf("firework_rocket", rocketPatch(3)));
        // A patch that holds the default value says the same
        assertEquals("rocket:1", specOf("firework_rocket", rocketPatch(1)));
    }

    @Test
    void s3AndS4_rocketsWithStars() {
        assertEquals("rocket:2|small_ball;red;;", specOf("firework_rocket", rocketPatch(2, explosion(0, new int[] {fw("red")}, NONE, false, false))));
        assertEquals("rocket:1|large_ball;blue;;t|burst;white,black;red;w", specOf("firework_rocket", rocketPatch(1,
            explosion(1, new int[] {fw("blue")}, NONE, true, false),
            explosion(4, new int[] {fw("white"), fw("black")}, new int[] {fw("red")}, false, true))));
    }

    @Test
    void s5ToS7_stars() {
        assertEquals("star:large_ball;red,blue;white;tw", specOf("firework_star", starPatch(explosion(1, new int[] {fw("red"), fw("blue")}, new int[] {fw("white")}, true, true))));
        assertEquals("star:small_ball;blue,red;;", specOf("firework_star", starPatch(explosion(0, new int[] {fw("blue"), fw("red")}, NONE, false, false))));
        assertEquals("star:creeper;red,red;;", specOf("firework_star", starPatch(explosion(3, new int[] {fw("red"), fw("red")}, NONE, false, false))));
        // Every shape id
        String[] shapes = {"small_ball", "large_ball", "star", "creeper", "burst"};
        for (int i = 0; i < shapes.length; i++) {
            assertEquals("star:" + shapes[i] + ";lime;;", specOf("firework_star", starPatch(explosion(i, new int[] {fw("lime")}, NONE, false, false))));
        }
    }

    @Test
    void s8_sevenStarsAtFlightOne() {
        Fireworks.FireworkExplosion lime = explosion(2, new int[] {fw("lime")}, NONE, false, false);
        Fireworks.FireworkExplosion[] seven = new Fireworks.FireworkExplosion[7];
        java.util.Arrays.fill(seven, lime);
        assertEquals("rocket:1" + "|star;lime;;".repeat(7), specOf("firework_rocket", rocketPatch(1, seven)));
    }

    @Test
    void s9_sevenStarsAtFlightTwoHaveNoSpec() {
        Fireworks.FireworkExplosion lime = explosion(2, new int[] {fw("lime")}, NONE, false, false);
        Fireworks.FireworkExplosion[] seven = new Fireworks.FireworkExplosion[7];
        java.util.Arrays.fill(seven, lime);
        assertNull(specOf("firework_rocket", rocketPatch(2, seven)));
        // Six stars at flight 2 and eight at flight 0's neighbour are the edges: 8 - flight stars is the most
        Fireworks.FireworkExplosion[] six = new Fireworks.FireworkExplosion[6];
        java.util.Arrays.fill(six, lime);
        assertNotNull(specOf("firework_rocket", rocketPatch(2, six)));
    }

    @Test
    void s10_nineColoursHaveNoSpec_eightHave() {
        int[] nine = new int[9], eight = new int[8];
        java.util.Arrays.fill(nine, fw("red"));
        java.util.Arrays.fill(eight, fw("red"));
        assertNull(specOf("firework_star", starPatch(explosion(0, nine, NONE, false, false))));
        assertNotNull(specOf("firework_star", starPatch(explosion(0, eight, NONE, false, false))));
        assertNull(specOf("firework_star", starPatch(explosion(0, eight, nine, false, false))), "nine fades have none either");
        assertNull(specOf("firework_star", starPatch(explosion(0, NONE, NONE, false, false))), "a star needs a colour");
    }

    @Test
    void s11_aColourNotInTheTableHasNoSpec() {
        assertNull(specOf("firework_star", starPatch(explosion(0, new int[] {0x123456}, NONE, false, false))));
        assertNull(specOf("firework_star", starPatch(explosion(0, new int[] {fw("red")}, new int[] {0x123456}, false, false))));
        assertNull(specOf("firework_rocket", rocketPatch(1, explosion(0, new int[] {0x123456}, NONE, false, false))));
    }

    @Test
    void s12_aFlightOutsideOneToThreeHasNoSpec() {
        assertNull(specOf("firework_rocket", rocketPatch(0)));
        assertNull(specOf("firework_rocket", rocketPatch(4)));
        assertNull(specOf("firework_rocket", rocketPatch(-1)));
    }

    @Test
    void l2_dataOutsideTheGrammarsBoundsIsNotKept() {
        int[] nine = new int[9];
        java.util.Arrays.fill(nine, fw("red"));
        Fireworks.FireworkExplosion ok = explosion(0, new int[] {fw("red")}, NONE, false, false);
        assertNull(FireworkItems.dataOf("firework_star", starPatch(explosion(0, nine, NONE, false, false)), null));
        assertNull(FireworkItems.dataOf("firework_star", starPatch(explosion(0, new int[] {fw("red")}, nine, false, false)), null));
        assertNull(FireworkItems.dataOf("firework_rocket", rocketPatch(4), null));
        assertNull(FireworkItems.dataOf("firework_rocket", rocketPatch(0), null));
        assertNull(FireworkItems.dataOf("firework_rocket", rocketPatch(1, explosion(0, nine, NONE, false, false)), null));
        Fireworks.FireworkExplosion[] many = new Fireworks.FireworkExplosion[9];
        java.util.Arrays.fill(many, ok);
        assertNull(FireworkItems.dataOf("firework_rocket", rocketPatch(1, many), null));
        assertNotNull(FireworkItems.dataOf("firework_rocket", rocketPatch(1, ok), null));
    }

    @Test
    void l1_onlyTheIngredientsTheGameAcceptsSurviveInTheTables() {
        com.google.gson.JsonObject j = FireworkFixtures.extrasJson().deepCopy();
        com.google.gson.JsonObject si = j.getAsJsonObject("starItems");
        si.getAsJsonArray("large_ball").add("minecraft:tnt");
        si.getAsJsonArray("trail").add("minecraft:sugar");
        si.add("shiny", new com.google.gson.JsonArray());
        si.getAsJsonArray("shiny").add("minecraft:diamond");
        FireworkSpecs.Tables t = FireworkSpecs.Tables.of(j);
        assertNotNull(t);
        assertEquals(java.util.List.of("minecraft:fire_charge"), t.starItems().get("large_ball"));
        assertEquals(java.util.List.of("minecraft:diamond"), t.starItems().get("trail"));
        assertFalse(t.starItems().containsKey("shiny"));
        // A role with nothing accepted left has no recipe
        si.add("burst", new com.google.gson.JsonArray());
        si.getAsJsonArray("burst").add("minecraft:stick");
        FireworkSpecs.Tables t2 = FireworkSpecs.Tables.of(j);
        assertFalse(t2.starItems().containsKey("burst"));
        assertNull(FireworkSpecs.expected(FireworkSpecs.RECIPE_STAR, FireworkSpecs.parse("star:burst;red;;"), t2));
    }

    @Test
    void theStillCarriedSurplusIsCountedByItsSpecLikeTheSurplus() {
        com.porter.qwertestash.stash.Item star = new com.porter.qwertestash.stash.Item();
        star.id = "firework_star";
        star.fw = FireworkSpecs.encodeStar(FireworkSpecs.Explosion.of(0, new int[] {fw("red")}, new int[0], false, false));
        com.porter.qwertestash.stash.Item gp = new com.porter.qwertestash.stash.Item();
        gp.id = "gunpowder";
        assertEquals("minecraft:firework_star@star:small_ball;red;;", FireworkItems.refOf(star, T));
        assertEquals("minecraft:gunpowder", FireworkItems.refOf(gp, T));
        assertEquals("minecraft:firework_star", FireworkItems.refOf(star, null), "no tables: the plain id, as before");
    }

    @Test
    void s13_anotherComponentInThePatchHasNoSpec() {
        DataComponents named = rocketPatch(2, explosion(0, new int[] {fw("red")}, NONE, false, false));
        named.put(DataComponentTypes.CUSTOM_NAME, Component.text("Boom"));
        assertNull(specOf("firework_rocket", named));
        DataComponents damaged = starPatch(explosion(0, new int[] {fw("red")}, NONE, false, false));
        damaged.put(DataComponentTypes.REPAIR_COST, 3);
        assertNull(specOf("firework_star", damaged));
        // A rocket with a lore, or a plain one renamed
        DataComponents lore = new DataComponents(new HashMap<>());
        lore.put(DataComponentTypes.LORE, List.of(Component.text("x")));
        assertNull(specOf("firework_rocket", lore));
    }

    @Test
    void aStarWithNoExplosionAndOtherItemsHaveNoSpec() {
        assertNull(specOf("firework_star", new DataComponents(new HashMap<>())), "an empty star");
        assertNull(FireworkItems.dataOf("diamond", new DataComponents(new HashMap<>()), null));
        // custom_data (ViaVersion adds it on some servers) is not in the key either: it does not take the spec away
        DataComponents viaversion = rocketPatch(2, explosion(0, new int[] {fw("red")}, NONE, false, false));
        viaversion.put(DataComponentTypes.CUSTOM_DATA, new com.viaversion.nbt.mini.MNBT());
        assertEquals("rocket:2|small_ball;red;;", specOf("firework_rocket", viaversion));
    }

    @Test
    void theColourBitsAboveRgbAreIgnored() {
        assertEquals("star:small_ball;red;;", specOf("firework_star", starPatch(explosion(0, new int[] {fw("red") | 0xFF000000}, NONE, false, false))));
    }

    // S14 to S20 belong to B4 and B5: this build derives nothing for them (it sends only the kinds in its can)

    @Test
    void s14ToS20_otherKindsAreNotDerivedInB3() {
        assertNull(FireworkItems.dataOf("leather_chestplate", new DataComponents(new HashMap<>()), null));
        assertNull(FireworkItems.dataOf("tipped_arrow", new DataComponents(new HashMap<>()), null));
        assertNull(FireworkItems.dataOf("suspicious_stew", new DataComponents(new HashMap<>()), null));
        assertNull(FireworkItems.dataOf("white_wool", new DataComponents(new HashMap<>()), null));
        assertNull(FireworkSpecs.parse("dyed:b02e26"));
        assertNull(FireworkSpecs.parse("tipped:minecraft:long_poison"));
        assertNull(FireworkSpecs.parse("stew:minecraft:night_vision/100"));
    }

    @Test
    void theItemKeepsTheDataAndTheSpecComesFromItAgainstTheTableOfTheMoment() {
        var data = ItemRegistry.REGISTRY.get("firework_rocket");
        ItemStack stack = new ItemStack(data.id(), 5, rocketPatch(2, explosion(0, new int[] {fw("red")}, NONE, false, false)));
        Item item = Item.of(stack);
        assertNotNull(item.fw);
        assertTrue(item.indexKey().startsWith("minecraft:firework_rocket#"));
        assertEquals("rocket:2|small_ball;red;;", FireworkSpecs.specOf(item.fw, T));
        // A plain rocket (S1) is the plain key with the spec rocket:1
        Item plain = Item.of(new ItemStack(data.id(), 64));
        assertEquals("minecraft:firework_rocket", plain.indexKey());
        assertEquals("rocket:1", FireworkSpecs.specOf(plain.fw, T));
        // A renamed rocket has no data, so no spec
        DataComponents named = rocketPatch(2);
        named.put(DataComponentTypes.CUSTOM_NAME, Component.text("x"));
        assertNull(Item.of(new ItemStack(data.id(), 1, named)).fw);
        // Saved before B3 (no data), or with no tables: none
        assertNull(FireworkSpecs.specOf(null, T));
        assertNull(FireworkSpecs.specOf(item.fw, null));
        assertNull(FireworkSpecs.specOf("garbage", T));
        assertNull(FireworkSpecs.specOf("R9|0:1:2:", T));
    }

    // The grammar read back (3)

    @Test
    void theContractsValidSpecsParseAndTheirCanonicalFormIsThemselves() {
        for (String s : new String[] {"rocket:1", "rocket:3", "rocket:2|small_ball;red;;", "rocket:1|large_ball;blue;;t|burst;white,black;red;w", "star:large_ball;red,blue;white;tw",
            "star:small_ball;blue,red;;", "star:creeper;red,red;;", "rocket:1" + "|star;lime;;".repeat(7)}) {
            FireworkSpecs.Parsed p = FireworkSpecs.parse(s);
            assertNotNull(p, s);
            assertEquals(s, p.canonical());
        }
    }

    @Test
    void theContractsInvalidSpecsAreRefused() {
        String[] bad = {"rocket:0", "rocket:4", "rocket:01", "rocket:2|small_ball;;;", "star:small_ball;red;;wt", "star:Small_ball;red;;", "star:small_ball;red;",
            "star:small_ball;red,,blue;;", "star:small_ball;red;;t ", " rocket:1", "rocket:1|", "star:", "rocket:", "", "rocket:2" + "|star;lime;;".repeat(7),
            "star:small_ball;red,red,red,red,red,red,red,red,red;;", "star:cube;red;;", "star:small_ball;pinkish;;", "rocket:1|small_ball;red;;;x"};
        for (String s : bad) assertNull(FireworkSpecs.parse(s), "[" + s + "]");
        assertNull(FireworkSpecs.parse("rocket:1|" + "a".repeat(2049)));
        assertNull(FireworkSpecs.parse(null));
    }

    // The grid (11.1, 15) against the grids the real server writes

    @Test
    void everyGridTheServerWritesPassesTheCheck_forOneAndManyFills() {
        List<FireworkFixtures.Grid> grids = FireworkFixtures.grids();
        assertTrue(grids.size() >= 20, "the fixture has the rocket, star, fade and creeper-head recipes: " + grids.size());
        boolean rocket = false, star = false, fade = false;
        for (FireworkFixtures.Grid g : grids) {
            for (int fills : new int[] {1, 7, 64}) assertNull(FireworkSpecs.checkStep(g.step(fills), T), g.recipe() + " " + g.spec() + " x" + fills);
            rocket |= g.recipe().equals(FireworkSpecs.RECIPE_ROCKET);
            star |= g.recipe().equals(FireworkSpecs.RECIPE_STAR);
            fade |= g.recipe().equals(FireworkSpecs.RECIPE_FADE);
        }
        assertTrue(rocket && star && fade);
    }

    @Test
    void theBotsOwnExpectedCellsContainTheServersGrid() {
        for (FireworkFixtures.Grid g : FireworkFixtures.grids()) {
            FireworkSpecs.Parsed p = FireworkSpecs.parse(g.spec());
            List<List<Order.Cell>> want = FireworkSpecs.expected(g.recipe(), p, T);
            assertNotNull(want, g.spec());
            int n = 0;
            for (Order.Cell c : g.cells()) if (c != null) n++;
            assertEquals(n, want.size(), g.spec());
            for (int i = 0; i < n; i++) assertTrue(want.get(i).contains(g.cells().get(i)), g.spec() + " slot " + (i + 1));
        }
    }

    @Test
    void theCreeperHasOneRecipePerHeadAndTheBotAcceptsAnyOne() {
        int heads = 0;
        for (FireworkFixtures.Grid g : FireworkFixtures.grids()) {
            if (g.recipe().equals(FireworkSpecs.RECIPE_STAR) && g.spec().equals("star:creeper;red,red;;")) {
                heads++;
                assertTrue(FireworkFixtures.TABLES.starItems().get("creeper").contains(g.cells().get(1).id()));
            }
        }
        assertEquals(7, heads);
        // A creeper star with a fire charge in the shape slot is refused
        FireworkFixtures.Grid g = FireworkFixtures.grid(FireworkSpecs.RECIPE_STAR, "star:creeper;red,red;;");
        List<Order.Cell> cells = new ArrayList<>(g.cells());
        cells.set(1, new Order.Cell("minecraft:fire_charge", null));
        assertNotNull(FireworkSpecs.checkStep(withCells(g, cells, 2), T));
    }

    private static Order.CraftStep withCells(FireworkFixtures.Grid g, List<Order.Cell> cells, int fills) {
        Order.CraftStep ok = g.step(fills);
        return new Order.CraftStep(ok.recipe(), ok.result(), ok.count(), ok.fills(), ok.inputs(), false, List.of(), ok.spec(), cells);
    }

    @Test
    void aGridThatDiffersInAnyWayIsRefused() {
        FireworkFixtures.Grid star = FireworkFixtures.grid(FireworkSpecs.RECIPE_STAR, "star:large_ball;red,blue;white;tw".replace(";white;", ";;"));
        // The colours in the other order (the order is the spec's, and the game appends in slot order)
        List<Order.Cell> swapped = new ArrayList<>(star.cells());
        int a = -1, b = -1;
        for (int i = 0; i < 9; i++) {
            Order.Cell c = star.cells().get(i);
            if (c != null && c.id().equals("minecraft:red_dye")) a = i;
            if (c != null && c.id().equals("minecraft:blue_dye")) b = i;
        }
        swapped.set(a, star.cells().get(b));
        swapped.set(b, star.cells().get(a));
        assertNotNull(FireworkSpecs.checkStep(withCells(star, swapped, 3), T), "colours in the wrong order");
        // An extra cell, a missing cell, a hole in the middle, a wrong item, eight or ten cells
        List<Order.Cell> extra = new ArrayList<>(star.cells());
        extra.set(8, new Order.Cell("minecraft:paper", null));
        assertNotNull(FireworkSpecs.checkStep(withCells(star, extra, 3), T), "an extra cell");
        List<Order.Cell> hole = new ArrayList<>(star.cells());
        hole.set(2, null);
        assertNotNull(FireworkSpecs.checkStep(withCells(star, hole, 3), T), "a hole");
        List<Order.Cell> eight = new ArrayList<>(star.cells()).subList(0, 8);
        assertNotNull(FireworkSpecs.checkStep(withCells(star, eight, 3), T), "eight cells");
        List<Order.Cell> ten = new ArrayList<>(star.cells());
        ten.add(null);
        assertNotNull(FireworkSpecs.checkStep(withCells(star, ten, 3), T), "ten cells");
        List<Order.Cell> wrong = new ArrayList<>(star.cells());
        wrong.set(0, new Order.Cell("minecraft:sugar", null));
        assertNotNull(FireworkSpecs.checkStep(withCells(star, wrong, 3), T), "no gunpowder");
        assertNotNull(FireworkSpecs.checkStep(withCells(star, new ArrayList<>(java.util.Collections.nCopies(9, null)), 3), T), "an empty grid");
    }

    @Test
    void theRocketsGridHasPaperGunpowderPerFlightAndTheStarsInOrder() {
        FireworkFixtures.Grid r = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:3|star;lime;;|creeper;red,red;;|small_ball;yellow,orange,pink;white,cyan;tw|burst;purple;;t|large_ball;gray;light_gray;w");
        assertEquals("minecraft:paper", r.cells().get(0).id());
        for (int i = 1; i <= 3; i++) assertEquals("minecraft:gunpowder", r.cells().get(i).id());
        assertEquals("star:star;lime;;", r.cells().get(4).spec());
        assertEquals("star:large_ball;gray;light_gray;w", r.cells().get(8).spec());
        assertNull(FireworkSpecs.checkStep(r.step(2), T));
        // Two stars swapped: another rocket
        List<Order.Cell> swapped = new ArrayList<>(r.cells());
        swapped.set(4, r.cells().get(5));
        swapped.set(5, r.cells().get(4));
        assertNotNull(FireworkSpecs.checkStep(withCells(r, swapped, 2), T));
        // A fade star in a grid whose spec asks for the plain one
        List<Order.Cell> faded = new ArrayList<>(r.cells());
        faded.set(4, new Order.Cell("minecraft:firework_star", "star:star;lime;white;"));
        assertNotNull(FireworkSpecs.checkStep(withCells(r, faded, 2), T));
    }

    @Test
    void theStepsOtherFieldsAreChecked() {
        FireworkFixtures.Grid r = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:2|small_ball;red;;");
        Order.CraftStep ok = r.step(2);
        assertNull(FireworkSpecs.checkStep(ok, T));
        // 3 rockets a fill [UNVERIFIED in game]: another count is refused
        assertNotNull(FireworkSpecs.checkStep(new Order.CraftStep(ok.recipe(), ok.result(), 2, ok.fills(), ok.inputs(), false, List.of(), ok.spec(), ok.grid()), T));
        // The wrong item for the kind, a non-canonical spec, no spec, a recipe that isn't special, no fills
        assertNotNull(FireworkSpecs.checkStep(new Order.CraftStep(ok.recipe(), FireworkSpecs.STAR, 3, ok.fills(), ok.inputs(), false, List.of(), ok.spec(), ok.grid()), T));
        assertNotNull(FireworkSpecs.checkStep(new Order.CraftStep(ok.recipe(), ok.result(), 3, ok.fills(), ok.inputs(), false, List.of(), "rocket:02", ok.grid()), T));
        assertNotNull(FireworkSpecs.checkStep(new Order.CraftStep(ok.recipe(), ok.result(), 3, ok.fills(), ok.inputs(), false, List.of(), null, ok.grid()), T));
        assertNotNull(FireworkSpecs.checkStep(new Order.CraftStep("minecraft:paper", ok.result(), 3, ok.fills(), ok.inputs(), false, List.of(), ok.spec(), ok.grid()), T));
        assertNotNull(FireworkSpecs.checkStep(new Order.CraftStep(ok.recipe(), ok.result(), 3, 0, ok.inputs(), false, List.of(), ok.spec(), ok.grid()), T));
        // The recipe for the spec's other kind (a star spec on the rocket recipe)
        assertNotNull(FireworkSpecs.checkStep(new Order.CraftStep(FireworkSpecs.RECIPE_STAR, FireworkSpecs.STAR, 1, 2, ok.inputs(), false, List.of(), "star:small_ball;red;;", ok.grid()), T));
        // Inputs that are not the grid times the fills
        List<Order.Qty> less = new ArrayList<>(ok.inputs());
        less.set(0, new Order.Qty(less.get(0).id(), less.get(0).count() - 1, null, less.get(0).spec()));
        assertNotNull(FireworkSpecs.checkStep(new Order.CraftStep(ok.recipe(), ok.result(), 3, 2, less, false, List.of(), ok.spec(), ok.grid()), T));
        // No firework tables: the bot can't judge it
        assertNotNull(FireworkSpecs.checkStep(ok, null));
    }

    @Test
    void aRecipeTheExtrasDoNotListIsRefused() {
        // The same tables without star_fade: a fade step is refused, a star step is fine
        com.google.gson.JsonObject j = FireworkFixtures.extrasJson();
        com.google.gson.JsonArray special = new com.google.gson.JsonArray();
        special.add("rocket");
        special.add("star");
        j.add("special", special);
        FireworkSpecs.Tables noFade = FireworkSpecs.Tables.of(j);
        assertNotNull(noFade);
        FireworkFixtures.Grid fade = null;
        for (FireworkFixtures.Grid g : FireworkFixtures.grids()) if (g.recipe().equals(FireworkSpecs.RECIPE_FADE)) fade = g;
        assertNotNull(fade);
        assertNull(FireworkSpecs.checkStep(fade.step(2), T));
        assertNotNull(FireworkSpecs.checkStep(fade.step(2), noFade));
        // Tables need rocket, star, dyes and star items
        special.remove(0);
        assertNull(FireworkSpecs.Tables.of(j));
        assertNull(FireworkSpecs.Tables.of((com.google.gson.JsonObject) null));
        assertNull(FireworkSpecs.Tables.of(new com.google.gson.JsonObject()));
    }

    // The recipe the sub-plan uses

    @Test
    void aSpecialStepIsAShapelessRecipeOfItsCellsMakingThePseudoId() {
        FireworkFixtures.Grid r = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:2|small_ball;red;;");
        Order.CraftStep s = r.step(3);
        Recipes.Recipe rec = FireworkSpecs.recipeOf(s);
        assertEquals("minecraft:firework_rocket@rocket:2|small_ball;red;;", rec.result());
        assertEquals(3, rec.count());
        assertFalse(rec.shaped());
        assertEquals(List.of(List.of("minecraft:paper"), List.of("minecraft:gunpowder"), List.of("minecraft:gunpowder"), List.of("minecraft:firework_star@star:small_ball;red;;")), rec.ingredients());
        assertTrue(rec.id().startsWith("qwerte:special/rocket#"));
        assertEquals(rec.id(), FireworkSpecs.planId(s));
        // Slots 1, 2, 3, ...: the grid row-major with the empty cells last
        assertEquals(List.of(1, 2, 3, 4), java.util.Arrays.stream(CraftGrid.slots(rec)).boxed().toList());
        // Two grids of one recipe and spec (two heads) are two plan ids
        FireworkFixtures.Grid a = null, b = null;
        for (FireworkFixtures.Grid g : FireworkFixtures.grids()) {
            if (g.recipe().equals(FireworkSpecs.RECIPE_STAR) && g.spec().equals("star:creeper;red,red;;")) {
                if (a == null) a = g;
                else if (b == null) b = g;
            }
        }
        assertNotEquals(FireworkSpecs.planId(a.step(1)), FireworkSpecs.planId(b.step(1)));
        assertEquals("qwerte:special/rocket", FireworkSpecs.planId(new Order.CraftStep("qwerte:special/rocket", "minecraft:x", 1, 1, List.of())), "a step with no grid keeps its recipe id");
    }

    private static void assertNotEquals(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b);
    }
}
