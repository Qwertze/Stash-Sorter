package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Craft blocks with rockets and stars on the bot (CRAFT-CONTRACT-v1.2.md, milestone B3): the block as the real server writes it (PROTOCOL.md, "Block v2
 * fields"), the check with its grids, keys and specs (11.1), the preflight by key (11.2), the sub-plan over pseudo ids, the ledger's keys and the
 * {@code spec} on {@code made[]} and {@code short[]} (12).
 */
class CraftFireworkTest {
    private static final Recipes R = TestRecipes.table();
    private static final String HASH = FireworkFixtures.SERVER_HASH;
    private static final Set<String> CAN = Set.of("firework");
    private static final String STAR_KEY = "minecraft:firework_star#aaaabbbb";
    private static final String ROCKET = "minecraft:firework_rocket";
    private static final String SPEC = "rocket:2|small_ball;red;;", STAR_SPEC = "star:small_ball;red;;";

    private static JsonObject cell(String id, String spec) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (spec != null) o.addProperty("spec", spec);
        return o;
    }

    /** The block of an order for 6 rockets of {@link #SPEC} when the stash has 2 stars of the spec in stock: one rocket step of 2 fills. */
    static JsonObject rocketFromStock() {
        return JsonParser.parseString("""
            { "v": 2, "mc": "1.21.4", "recipes": "%s", "extras": "%s", "maxDepth": 12, "needs": ["firework"],
              "lines": [ { "key": "minecraft:firework_rocket", "count": 6, "spec": "%s", "orCraft": true } ],
              "steps": [ { "recipe": "qwerte:special/rocket", "result": "minecraft:firework_rocket", "spec": "%s", "count": 3, "fills": 2,
                           "grid": [ {"id":"minecraft:paper"}, {"id":"minecraft:gunpowder"}, {"id":"minecraft:gunpowder"},
                                     {"id":"minecraft:firework_star","spec":"%s"}, null, null, null, null, null ],
                           "inputs": [ { "id": "minecraft:paper", "count": 2 }, { "id": "minecraft:gunpowder", "count": 4 },
                                       { "id": "minecraft:firework_star", "spec": "%s", "key": "%s", "count": 2 } ] } ],
              "take": [ { "id": "minecraft:paper", "count": 2 }, { "id": "minecraft:gunpowder", "count": 4 },
                        { "id": "minecraft:firework_star", "key": "%s", "spec": "%s", "count": 2 } ],
              "leftovers": [],
              "fills": 2, "smelt": 0, "forEnchant": [] }
            """.formatted(R.hash(), HASH, SPEC, SPEC, STAR_SPEC, STAR_SPEC, STAR_KEY, STAR_KEY, STAR_SPEC)).getAsJsonObject();
    }

    /** 3 rockets of flight 1 with a lime star made from scratch: a star step, then a rocket step that uses it. */
    static JsonObject rocketAndStarFromScratch() {
        FireworkFixtures.Grid star = FireworkFixtures.grid(FireworkSpecs.RECIPE_STAR, "star:star;lime;;");
        FireworkFixtures.Grid rocket = FireworkFixtures.grid(FireworkSpecs.RECIPE_ROCKET, "rocket:1|star;lime;;");
        JsonObject o = new JsonObject();
        o.addProperty("v", 2);
        o.addProperty("mc", "1.21.4");
        o.addProperty("recipes", R.hash());
        o.addProperty("extras", HASH);
        o.addProperty("maxDepth", 12);
        o.add("needs", array("firework"));
        o.add("lines", JsonParser.parseString("[ { \"key\": \"minecraft:firework_rocket\", \"count\": 3, \"spec\": \"rocket:1|star;lime;;\", \"orCraft\": true } ]"));
        o.add("steps", new JsonArray());
        o.getAsJsonArray("steps").add(stepJson(star, 1));
        o.getAsJsonArray("steps").add(stepJson(rocket, 1));
        o.add("take", JsonParser.parseString("""
            [ { "id": "minecraft:paper", "count": 1 }, { "id": "minecraft:gunpowder", "count": 2 }, { "id": "minecraft:gold_nugget", "count": 1 }, { "id": "minecraft:lime_dye", "count": 1 } ]
            """));
        o.add("leftovers", new JsonArray());
        o.addProperty("fills", 2);
        o.addProperty("smelt", 0);
        return o;
    }

    private static JsonArray array(String... s) {
        JsonArray a = new JsonArray();
        for (String x : s) a.add(x);
        return a;
    }

    private static JsonObject stepJson(FireworkFixtures.Grid g, int fills) {
        Order.CraftStep s = g.step(fills);
        JsonObject o = new JsonObject();
        o.addProperty("recipe", s.recipe());
        o.addProperty("result", s.result());
        o.addProperty("spec", s.spec());
        o.addProperty("count", s.count());
        o.addProperty("fills", s.fills());
        JsonArray grid = new JsonArray();
        for (Order.Cell c : s.grid()) grid.add(c == null ? com.google.gson.JsonNull.INSTANCE : cell(c.id(), c.spec()));
        o.add("grid", grid);
        JsonArray in = new JsonArray();
        for (Order.Qty q : s.inputs()) {
            JsonObject x = cell(q.id(), q.spec());
            x.addProperty("count", q.count());
            in.add(x);
        }
        o.add("inputs", in);
        return o;
    }

    private static Order.Craft parse(JsonObject block) {
        Order.Craft c = Order.parseCraft(block);
        assertNotNull(c);
        assertFalse(c.malformed(), "the block is readable");
        return c;
    }

    private static String check(Order.Craft c, Set<String> can, String extras, FireworkSpecs.Tables tables) {
        return CraftOrder.check(c, R, 2048, extras, can, null, 0, tables);
    }

    private static String check(JsonObject block) {
        return check(parse(block), CAN, HASH, FireworkFixtures.TABLES);
    }

    private static JsonObject changed(Consumer<JsonObject> change) {
        JsonObject b = rocketFromStock();
        change.accept(b);
        return b;
    }

    // Reading the block

    @Test
    void theBlockIsReadWithItsSpecsKeysAndGrids() {
        Order.Craft c = parse(rocketFromStock());
        assertEquals(2, c.v());
        assertNull(c.reserved(), "B3's fields are carried out now: only B4's remainder stays reserved");
        assertTrue(c.usesSpecs());
        assertEquals(SPEC, c.lines().get(0).spec());
        Order.CraftStep s = c.steps().get(0);
        assertTrue(s.special());
        assertEquals(SPEC, s.spec());
        assertEquals(9, s.grid().size());
        assertEquals(new Order.Cell("minecraft:firework_star", STAR_SPEC), s.grid().get(3));
        assertNull(s.grid().get(4));
        assertEquals(STAR_KEY, s.inputs().get(2).key());
        assertEquals(STAR_SPEC, s.inputs().get(2).spec());
        Order.Qty take = c.take().get(2);
        assertEquals(STAR_KEY, take.key());
        assertEquals(STAR_SPEC, take.spec());
        assertEquals("minecraft:firework_star@" + STAR_SPEC, take.ref());
        assertNull(c.take().get(0).key());
        assertEquals(ROCKET + "@" + SPEC, s.resultRef());
    }

    @Test
    void aBlockWithoutSpecsReadsAsBefore() {
        Order.Craft c = parse(JsonParser.parseString("""
            { "v": 2, "mc": "1.21.4", "recipes": "%s", "extras": null, "maxDepth": 12, "needs": [],
              "lines": [ { "key": "minecraft:chest", "count": 1, "spec": null, "orCraft": true } ],
              "steps": [], "take": [ { "id": "minecraft:chest", "count": 1 } ], "leftovers": [], "fills": 0, "smelt": 0, "forEnchant": [] }
            """.formatted(R.hash())).getAsJsonObject());
        assertFalse(c.usesSpecs());
        assertNull(c.lines().get(0).spec());
        assertFalse(c.steps().stream().anyMatch(Order.CraftStep::special));
        // A v1 block never reads these fields at all
        JsonObject v1 = rocketFromStock();
        v1.addProperty("v", 1);
        v1.remove("maxDepth");
        v1.remove("extras");
        v1.remove("needs");
        assertFalse(parse(v1).usesSpecs());
    }

    @Test
    void aGridOrSpecOfTheWrongShapeMakesTheBlockUnreadable() {
        for (Consumer<JsonObject> bad : List.<Consumer<JsonObject>>of(
            b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("grid", "paper"),
            b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("spec", 3),
            b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("grid").set(0, new com.google.gson.JsonPrimitive("paper")),
            b -> b.getAsJsonArray("take").get(2).getAsJsonObject().addProperty("key", 5))) {
            Order.Craft c = Order.parseCraft(changed(bad));
            assertTrue(c != null && c.malformed());
        }
    }

    @Test
    void theOrderLinesKeepTheSpecOfACraftLine() {
        JsonObject o = JsonParser.parseString("""
            { "id": "o1", "lines": [ { "type": "item", "key": "minecraft:firework_rocket#ab12cd34", "count": 3, "orCraft": true },
                                     { "type": "craft", "key": "minecraft:firework_rocket", "count": 6, "spec": "%s", "orCraft": true },
                                     { "type": "craft", "key": "minecraft:chest", "count": 1 } ] }
            """.formatted(SPEC)).getAsJsonObject();
        Order order = Order.parse(o);
        assertNotNull(order);
        assertEquals(3, order.lines.size());
        assertNull(order.lines.get(0).spec(), "an item line has none");
        assertEquals(SPEC, order.lines.get(1).spec());
        assertEquals(ROCKET + "@" + SPEC, order.lines.get(1).ref());
        assertEquals("minecraft:chest", order.lines.get(2).ref());
        // A spec'd craft line is delivered under the key of what was made: not counted in requested()
        assertEquals(Map.of("minecraft:firework_rocket#ab12cd34", 3, "minecraft:chest", 1), order.requested());
    }

    // The check (11.1)

    @Test
    void theServersBlockPasses() {
        assertNull(check(rocketFromStock()));
        assertNull(check(rocketAndStarFromScratch()));
    }

    @Test
    void aBlockWithSpecsNeedsTheFireworkFeatureTheTablesAndTheBotsExtras() {
        Order.Craft c = parse(rocketFromStock());
        assertNotNull(check(c, Set.of(), HASH, FireworkFixtures.TABLES), "this bot can't make fireworks");
        assertNotNull(check(c, CAN, HASH, null), "no firework tables");
        assertNotNull(check(c, CAN, "0".repeat(40), FireworkFixtures.TABLES), "another extras hash");
        assertNotNull(check(c, CAN, null, FireworkFixtures.TABLES));
        assertNull(check(c, Set.of("smelt", "firework"), HASH, FireworkFixtures.TABLES));
        // The server always says it: a block that uses specs and doesn't need firework, or names no extras, is refused
        assertNotNull(check(changed(b -> b.add("needs", new JsonArray()))));
        assertNotNull(check(changed(b -> b.add("extras", com.google.gson.JsonNull.INSTANCE))));
        // The old seven-argument form (a B2 bot) has no tables: the block fails with recipe_mismatch there
        assertNotNull(CraftOrder.check(c, R, 2048, HASH, CAN, null, 0));
    }

    @Test
    void aSpecialStepIsRefusedWhenItsGridIsNotTheOneTheBotBuilds() {
        // Two cells swapped, a wrong item, a hole, a cell too many
        assertNotNull(check(changed(b -> {
            JsonArray g = b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("grid");
            var first = g.get(0);
            g.set(0, g.get(3));
            g.set(3, first);
        })));
        assertNotNull(check(changed(b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("grid").set(1, cell("minecraft:sugar", null)))));
        assertNotNull(check(changed(b -> {
            JsonArray g = b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("grid");
            g.set(2, com.google.gson.JsonNull.INSTANCE);
            g.set(4, cell("minecraft:gunpowder", null));
        })));
        assertNotNull(check(changed(b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("grid").set(4, cell("minecraft:gunpowder", null)))));
        // The star of another spec in the star cell
        assertNotNull(check(changed(b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("grid").set(3, cell("minecraft:firework_star", "star:small_ball;blue;;")))));
        // A step that says it makes another spec than its grid builds
        assertNotNull(check(changed(b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().addProperty("spec", "rocket:3|small_ball;red;;"))));
        // A step with a spec and no grid
        assertNotNull(check(changed(b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().remove("grid"))));
    }

    @Test
    void takesLinesAndLeftoversWithSpecsAreChecked() {
        // A take with a spec and no key, a key of another item, a key with no spec, a spec of another item, a spec that is not canonical
        assertNotNull(check(changed(b -> b.getAsJsonArray("take").get(2).getAsJsonObject().remove("key"))));
        assertNotNull(check(changed(b -> b.getAsJsonArray("take").get(2).getAsJsonObject().addProperty("key", "minecraft:firework_rocket#aaaabbbb"))));
        assertNotNull(check(changed(b -> b.getAsJsonArray("take").get(2).getAsJsonObject().addProperty("key", "minecraft:firework_star#XYZ"))));
        assertNotNull(check(changed(b -> b.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("key", "minecraft:paper#aaaa"))), "a key without a spec");
        assertNotNull(check(changed(b -> b.getAsJsonArray("take").get(2).getAsJsonObject().addProperty("spec", "rocket:1"))), "the spec of a rocket on a star");
        assertNotNull(check(changed(b -> b.getAsJsonArray("take").get(2).getAsJsonObject().addProperty("spec", "star:small_ball;red;"))));
        assertNotNull(check(changed(b -> b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("spec", "rocket:2|small_ball;red"))));
        assertNotNull(check(changed(b -> b.getAsJsonArray("leftovers").add(cellCount("minecraft:firework_star", "star:wrong", 1)))));
        // A leftover with a good spec is fine
        assertNull(check(changed(b -> b.getAsJsonArray("leftovers").add(cellCount("minecraft:firework_star", STAR_SPEC, 1)))));
    }

    private static JsonObject cellCount(String id, String spec, int n) {
        JsonObject o = cell(id, spec);
        o.addProperty("count", n);
        return o;
    }

    @Test
    void theLinesMustBeCoveredPerSpec() {
        // 7 rockets from 6 made
        assertNotNull(check(changed(b -> b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("count", 7))));
        // The same count of another spec is not covered by this step
        assertNotNull(check(changed(b -> b.getAsJsonArray("lines").get(0).getAsJsonObject().addProperty("spec", "rocket:2|small_ball;blue;;"))));
        // Stock of the spec covers a line too (a take of rockets, nothing made)
        JsonObject onlyTake = changed(b -> {
            b.add("steps", new JsonArray());
            b.addProperty("fills", 0);
            b.add("take", JsonParser.parseString("[ { \"id\": \"minecraft:firework_rocket\", \"key\": \"minecraft:firework_rocket#ab12cd34\", \"spec\": \"" + SPEC + "\", \"count\": 6 } ]"));
        });
        assertNull(check(onlyTake));
        JsonObject tooFew = onlyTake.deepCopy();
        tooFew.getAsJsonArray("take").get(0).getAsJsonObject().addProperty("count", 5);
        assertNotNull(check(tooFew));
    }

    @Test
    void theStepsCountAgainstTheGridFillCap() {
        Order.Craft c = parse(rocketFromStock());
        assertNull(CraftOrder.check(c, R, 2, HASH, CAN, null, 0, FireworkFixtures.TABLES));
        assertNotNull(CraftOrder.check(c, R, 1, HASH, CAN, null, 0, FireworkFixtures.TABLES));
    }

    @Test
    void b4FieldsStayReservedAndOlderBlocksRunAsBefore() {
        Order.Craft c = parse(changed(b -> b.getAsJsonArray("steps").get(0).getAsJsonObject().add("remainder", JsonParser.parseString("[ { \"id\": \"minecraft:bowl\", \"count\": 1 } ]"))));
        assertEquals("steps[].remainder", c.reserved());
        assertNotNull(check(c, CAN, HASH, FireworkFixtures.TABLES));
        // A spec-free block with a firework-capable bot passes as it always did
        Order.Craft plain = parse(JsonParser.parseString("""
            { "v": 2, "mc": "1.21.4", "recipes": "%s", "extras": null, "maxDepth": 12, "needs": [],
              "lines": [], "steps": [], "take": [], "leftovers": [], "fills": 0, "smelt": 0, "forEnchant": [] }
            """.formatted(R.hash())).getAsJsonObject());
        assertNull(check(plain, CAN, HASH, FireworkFixtures.TABLES));
        assertNull(check(plain, Set.of(), null, null));
    }

    // The preflight (11.2)

    @Test
    void thePreflightCountsASpecTakeByItsKey() {
        Order.Craft c = parse(rocketFromStock());
        Order order = new Order("o1", List.of(new Order.Line(Order.ITEM, "minecraft:firework_rocket#ab12cd34", 3), new Order.Line(Order.CRAFT, ROCKET, 6, false, SPEC)), null, false, c);
        Map<String, Long> need = Preflight.needs(order, c);
        assertEquals(3L, need.get("minecraft:firework_rocket#ab12cd34"));
        assertEquals(2L, need.get(STAR_KEY), "the take of the stars is by their key");
        assertEquals(2L, need.get("minecraft:paper"));
        assertFalse(need.containsKey("minecraft:firework_star"), "never by the bare id");
        // The stash has the paper and the gunpowder but only one star of that key
        List<Preflight.Shortfall> shorts = Preflight.check(need, Map.of("minecraft:firework_rocket#ab12cd34", 3L, "minecraft:paper", 9L, "minecraft:gunpowder", 9L, STAR_KEY, 1L, "minecraft:firework_star", 50L));
        assertEquals(1, shorts.size());
        assertEquals(STAR_KEY, shorts.get(0).key());
        assertEquals(1, shorts.get(0).missing());
        assertTrue(Preflight.message(shorts).contains("1 Firework Star"));
        assertEquals(STAR_KEY, Preflight.restock(shorts).get(0).getAsJsonObject().getAsJsonArray("options").get(0).getAsJsonArray().get(0).getAsJsonObject().getAsJsonArray("ids").get(0).getAsString());
        assertTrue(Preflight.check(need, Map.of("minecraft:firework_rocket#ab12cd34", 3L, "minecraft:paper", 2L, "minecraft:gunpowder", 4L, STAR_KEY, 2L)).isEmpty());
    }

    // The ledger and the sub-plan over pseudo ids

    private static CraftLedger ledger(JsonObject block, String key, int count, String spec) {
        Order.Craft c = parse(block);
        CraftLedger l = new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, key, count, false, spec)), R, 2048);
        return l;
    }

    @Test
    void theLedgerKnowsTheTakeByPseudoIdAndKey() {
        CraftLedger l = ledger(rocketFromStock(), ROCKET, 6, SPEC);
        assertEquals(2L, l.budget().get("minecraft:firework_star@" + STAR_SPEC));
        assertEquals(2L, l.take().get("minecraft:firework_star@" + STAR_SPEC));
        assertTrue(l.hasSpecs());
        assertEquals(Set.of("minecraft:paper", "minecraft:gunpowder", "minecraft:firework_star"), l.takeItemIds());
        assertEquals(Set.of(STAR_KEY), l.specKeys());
        assertEquals("minecraft:firework_star@" + STAR_SPEC, l.refOfKey(STAR_KEY));
        assertNull(l.refOfKey("minecraft:paper"));
        assertEquals(6L, l.wanted(ROCKET + "@" + SPEC));
        assertEquals(0L, l.wanted(ROCKET), "the plain key is another line");
        assertEquals(Map.of(STAR_KEY, 2L), l.keysFor(Map.of("minecraft:firework_star@" + STAR_SPEC, 2L, "minecraft:paper", 2L)));
    }

    @Test
    void aRocketIsPlannedFromTheStarsInStock() {
        CraftLedger l = ledger(rocketFromStock(), ROCKET, 6, SPEC);
        String ref = ROCKET + "@" + SPEC;
        CraftSub.Outcome o = l.session().plan(ref, 6);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(1, o.plan().steps().size());
        CraftPlanner.Step st = o.plan().steps().get(0);
        assertEquals(2, st.fills());
        assertEquals(3, st.recipe().count());
        assertEquals(ref, st.recipe().result());
        assertEquals(Map.of("minecraft:paper", 2L, "minecraft:gunpowder", 4L, "minecraft:firework_star@" + STAR_SPEC, 2L), o.plan().fromStock());
        assertEquals(2, o.plan().fills());
        // 7 would need a third fill, and there is no third star
        assertFalse(l.session().plan(ref, 7).ok());
        // A chunk of 4 makes 2 fills (6 rockets): the surplus of 2 is carried
        CraftSub.Outcome four = l.session().plan(ref, 4);
        assertTrue(four.ok());
        assertEquals(2L, four.surplus().get(ref));
        // Another spec has no recipe in this block
        CraftSub.Outcome other = l.session().plan(ROCKET + "@rocket:2|small_ball;blue;;", 3);
        assertFalse(other.ok());
        assertTrue(other.plan().refusal().contains("Firework Rocket (rocket, flight 2, 1 star)"), other.plan().refusal());
    }

    @Test
    void aStarIsMadeFirstWhenNoneIsInStock() {
        CraftLedger l = ledger(rocketAndStarFromScratch(), ROCKET, 3, "rocket:1|star;lime;;");
        CraftSub.Outcome o = l.session().plan(ROCKET + "@rocket:1|star;lime;;", 3);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(2, o.plan().steps().size());
        assertEquals("minecraft:firework_star@star:star;lime;;", o.plan().steps().get(0).recipe().result(), "the star before the rocket that uses it");
        assertEquals(ROCKET + "@rocket:1|star;lime;;", o.plan().steps().get(1).recipe().result());
        assertEquals(Map.of("minecraft:paper", 1L, "minecraft:gunpowder", 2L, "minecraft:gold_nugget", 1L, "minecraft:lime_dye", 1L), o.plan().fromStock());
        assertEquals(2, o.plan().fills());
        assertTrue(o.surplus().isEmpty());
        // The line asked for 3: the chunk is a stack of 3 (one fill of the 3-rocket recipe)
        assertEquals(3, o.plan().steps().get(1).made());
    }

    @Test
    void theChunkIsChargedToItsKeysAndThenTheBudgetIsGone() {
        CraftLedger l = ledger(rocketFromStock(), ROCKET, 6, SPEC);
        String star = "minecraft:firework_star@" + STAR_SPEC;
        // A first chunk used one star: the second chunk can take one more key-count only
        l.madeChunk(ROCKET + "@" + SPEC, 3, 1, Map.of("minecraft:paper", 1L, "minecraft:gunpowder", 2L, star, 1L));
        assertEquals(Map.of(STAR_KEY, 1L), l.keysFor(Map.of(star, 5L)), "never more than the take's entry");
        assertEquals(1L, l.budget().get(star));
        // Lost surplus is refunded to the key too
        l.refund(Map.of(star, 1L));
        assertEquals(Map.of(STAR_KEY, 2L), l.keysFor(Map.of(star, 5L)));
        assertEquals(2L, l.budget().get(star));
        // Saved and read back by a fresh ledger of the same block
        l.madeChunk(ROCKET + "@" + SPEC, 3, 1, Map.of(star, 1L));
        JsonObject saved = l.toSaved();
        assertTrue(saved.has("keyUsed"));
        CraftLedger again = ledger(rocketFromStock(), ROCKET, 6, SPEC);
        again.restore(saved);
        assertEquals(1L, again.budget().get(star));
        assertEquals(Map.of(STAR_KEY, 1L), again.keysFor(Map.of(star, 5L)));
        assertEquals(6L, again.made(ROCKET + "@" + SPEC));
    }

    @Test
    void theTakeSplitOverTwoKeysIsGatheredInTheServersOrder() {
        JsonObject b = rocketFromStock();
        JsonArray take = b.getAsJsonArray("take");
        take.remove(2);
        take.add(JsonParser.parseString("{ \"id\": \"minecraft:firework_star\", \"key\": \"minecraft:firework_star#aaaa0001\", \"spec\": \"" + STAR_SPEC + "\", \"count\": 1 }"));
        take.add(JsonParser.parseString("{ \"id\": \"minecraft:firework_star\", \"key\": \"minecraft:firework_star#aaaa0002\", \"spec\": \"" + STAR_SPEC + "\", \"count\": 1 }"));
        CraftLedger l = ledger(b, ROCKET, 6, SPEC);
        String star = "minecraft:firework_star@" + STAR_SPEC;
        assertEquals(2L, l.budget().get(star));
        assertEquals(Map.of("minecraft:firework_star#aaaa0001", 1L, "minecraft:firework_star#aaaa0002", 1L), l.keysFor(Map.of(star, 2L)));
        assertEquals(List.of("minecraft:firework_star#aaaa0001"), List.copyOf(l.keysFor(Map.of(star, 1L)).keySet()));
        // What the loads hold, by the key's pseudo id
        List<Order.Lot> loads = List.of(new Order.Lot("minecraft:firework_star#aaaa0001", 1), new Order.Lot("minecraft:paper", 2));
        assertEquals(Map.of(star, 1L, "minecraft:paper", 2L), CraftCharge.gathered(loads, l::refOfKey));
        assertEquals(Map.of("minecraft:firework_star", 1L, "minecraft:paper", 2L), CraftCharge.gathered(loads), "without the ledger it is the plain id, as before");
    }

    // The result (12)

    @Test
    void madeAndShortCarryTheSpecAndOtherLinesDontChange() {
        Order.Craft c = parse(rocketFromStock());
        CraftLedger l = new CraftLedger(c, List.of(new Order.Line(Order.CRAFT, ROCKET, 4, false, SPEC), new Order.Line(Order.CRAFT, "minecraft:chest", 2)), R, 2048);
        l.madeChunk(ROCKET + "@" + SPEC, 3, 1, Map.of());
        l.addShort(ROCKET + "@" + SPEC, 1, CraftOrder.RECIPE_MISMATCH);
        l.addShort("minecraft:chest", 2, CraftOrder.INGREDIENTS_SHORT);
        JsonObject r = l.toResult();
        JsonObject made = r.getAsJsonArray("made").get(0).getAsJsonObject();
        assertEquals(ROCKET, made.get("key").getAsString());
        assertEquals(SPEC, made.get("spec").getAsString());
        assertEquals(4, made.get("wanted").getAsInt());
        assertEquals(3, made.get("made").getAsInt());
        JsonObject chestMade = r.getAsJsonArray("made").get(1).getAsJsonObject();
        assertEquals("minecraft:chest", chestMade.get("key").getAsString());
        assertFalse(chestMade.has("spec"), "a plain line is reported as it always was");
        JsonObject sh = r.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(ROCKET, sh.get("key").getAsString());
        assertEquals(SPEC, sh.get("spec").getAsString());
        assertEquals("recipe_mismatch", sh.get("reason").getAsString());
        assertFalse(r.getAsJsonArray("short").get(1).getAsJsonObject().has("spec"));
        // What was used and left is by item id with the spec
        Map<String, Long> usedNow = new java.util.LinkedHashMap<>();
        usedNow.put("minecraft:firework_star@" + STAR_SPEC, 1L);
        usedNow.put("minecraft:paper", 1L);
        l.madeChunk("", 0, 0, usedNow);
        l.setReturned(Map.of("minecraft:firework_star@" + STAR_SPEC, 1L));
        JsonObject r2 = l.toResult();
        assertEquals("minecraft:firework_star", r2.getAsJsonArray("used").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(STAR_SPEC, r2.getAsJsonArray("used").get(0).getAsJsonObject().get("spec").getAsString());
        assertEquals(STAR_SPEC, r2.getAsJsonArray("returned").get(0).getAsJsonObject().get("spec").getAsString());
    }

    @Test
    void aCraftLoadOfASpecIsKeptUnderItsPseudoIdAndKnownByTheMadeStackAfterwards() {
        Order.Lot pending = Order.Lot.toCraft(ROCKET + "@" + SPEC, 3);
        assertTrue(pending.craft());
        Order.Lot made = pending.madeAs("minecraft:firework_rocket#ab12cd34");
        assertTrue(made.crafted());
        assertEquals("minecraft:firework_rocket#ab12cd34", made.key());
        assertEquals(3, made.count());
        assertEquals(ROCKET + "@" + SPEC, pending.madeAs(null).key(), "no key: as it was");
        assertEquals(ROCKET, Pseudo.base(ROCKET + "@" + SPEC));
        assertEquals(SPEC, Pseudo.spec(ROCKET + "@" + SPEC));
        assertNull(Pseudo.spec(ROCKET));
        assertEquals(ROCKET, Pseudo.of(ROCKET, null));
        assertTrue(Pseudo.is(ROCKET + "@" + SPEC));
        assertFalse(Pseudo.is(ROCKET));
    }
}
