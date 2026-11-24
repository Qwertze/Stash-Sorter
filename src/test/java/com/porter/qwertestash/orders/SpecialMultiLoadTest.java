package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.porter.qwertestash.orders.SpecialFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lines of banner and map copies that are cut into several loads (review of B4, H1, H2, M3, M4): every load is planned with {@link CraftBatch#choose} and charged
 * with {@code madeChunk}, with what the bot carries between loads written back as the surplus, so a later load always has its source, a shield and copies of one
 * key share the stock as the server's plans do, the grid fills stay within the server's count, and the slot a source is split into is counted.
 */
class SpecialMultiLoadTest {
    private static final String B = BANNER + "@copy:" + K_BANNER, M = MAP + "@copy:" + K_MAP;

    private static CraftLedger ledger(JsonObject block, int cap, List<Order.Line> lines) {
        Order.Craft c = parse(block);
        assertEquals(null, check(c, CAN), "the block passes its check");
        return new CraftLedger(c, lines, R, cap);
    }

    private static Order.Line line(String id, String spec, int n) {
        return new Order.Line(Order.CRAFT, id, n, false, spec);
    }

    /** What a chunk leaves in the bot's hands: carried, plus what was taken from the stash, plus what the crafts added, less the load. */
    private record Run(long delivered, long loads, long fills, String failure) {
    }

    private static long netMade(CraftPlanner.Plan p) {
        long n = 0;
        for (CraftPlanner.Step s : p.steps()) n += (long) s.fills() * VariantSpecs.netCount(s.recipe().id(), s.recipe().count());
        return n;
    }

    /** Makes the loads of one line one after the other, as the sorter does; {@code carried} is the bot's carried count of the line's item. */
    private static Run runLine(CraftLedger l, String ref, int q, int stack, int free, Map<String, Long> carriedAll) {
        long delivered = 0, loads = 0;
        while (delivered < q) {
            int n = (int) Math.min(stack, q - delivered);
            l.setSurplus(carriedAll);
            CraftBatch.Choice c = CraftBatch.choose(l, ref, n, free, l.maxFills() - l.fills(), id -> stack, need -> need.size());
            if (!c.ok()) return new Run(delivered, loads, l.fills(), c.reason() + " " + c.detail());
            for (var e : c.plan().fromStock().entrySet()) carriedAll.merge(e.getKey(), e.getValue(), Long::sum);
            carriedAll.merge(ref, netMade(c.plan()) - c.count(), Long::sum);
            for (CraftPlanner.Step s : c.plan().steps()) {
                // The blanks a step used are gone
                for (var u : s.uses().entrySet()) carriedAll.merge(u.getKey(), -u.getValue(), Long::sum);
            }
            carriedAll.values().removeIf(v -> v <= 0);
            l.madeChunk(ref, c.count(), c.plan().fills(), c.plan().fromStock());
            delivered += c.count();
            loads++;
        }
        return new Run(delivered, loads, l.fills(), null);
    }

    @Test
    void aBannerLineOfTwoLoadsKeepsItsSourceForTheSecond() {
        // 16 in stock, 20 asked, banners stack to 16: the server keeps one as the source, delivers 15 and copies 5
        CraftLedger l = ledger(bannerCopy(16, 20), 2048, List.of(line(BANNER, "copy:" + K_BANNER, 20)));
        Map<String, Long> carried = new HashMap<>();
        Run r = runLine(l, B, 20, 16, 27, carried);
        assertEquals(null, r.failure(), r.failure());
        assertEquals(20, r.delivered());
        assertEquals(2, r.loads());
        assertEquals(5, r.fills(), "the server's 5 crafts, no more");
        assertEquals(1L, carried.getOrDefault(B, 0L), "the source is what is still carried: it goes back, it is never delivered");
        assertEquals(0L, l.budget().getOrDefault(B, 0L));
    }

    @Test
    void aBannerLineThatStockCoversExceptTheSourceAndSmallLoads() {
        // S=3, q=3: 2 delivered, 1 made (the server's plan), in one load
        CraftLedger l = ledger(bannerCopy(3, 3), 2048, List.of(line(BANNER, "copy:" + K_BANNER, 3)));
        l.setSurplus(Map.of());
        CraftBatch.Choice c = CraftBatch.choose(l, B, 3, 27, 2048, id -> 16, need -> need.size());
        assertTrue(c.ok(), c.reason() + " " + c.detail());
        assertEquals(3, c.count());
        assertEquals(Map.of(B, 3L, BANNER, 1L), c.plan().fromStock(), "2 delivered and the source, one blank");
        assertEquals(1, c.plan().fills());
    }

    @Test
    void aMapLineOfTwoLoadsAlwaysHasASourceForTheSecond() {
        // 64 in stock, 100 asked, maps stack to 64: all 64 go to the first load and one map more is made to carry on with
        CraftLedger l = ledger(mapCopy(64, 100), 2048, List.of(line(MAP, "copy:" + K_MAP, 100)));
        Map<String, Long> carried = new HashMap<>();
        Run r = runLine(l, M, 100, 64, 27, carried);
        assertEquals(null, r.failure(), r.failure());
        assertEquals(100, r.delivered());
        assertEquals(2, r.loads());
        assertEquals(0L, carried.getOrDefault(M, 0L), "nothing is left over: the kept map is the last of the second load");
        assertEquals(0L, l.budget().getOrDefault("minecraft:map", 0L), "the 36 blanks the server counted, not one more");
        assertTrue(r.fills() <= 6);
    }

    @Test
    void aMapLineThatStockCoversIsOneLoadWithNoCraft() {
        CraftLedger l = ledger(mapCopy(5, 6), 2048, List.of(line(MAP, "copy:" + K_MAP, 6)));
        l.setSurplus(Map.of());
        CraftBatch.Choice c = CraftBatch.choose(l, M, 6, 27, 2048, id -> 64, need -> need.size());
        assertTrue(c.ok(), c.reason() + " " + c.detail());
        assertEquals(Map.of(M, 5L, "minecraft:map", 1L), c.plan().fromStock());
    }

    @Test
    void aShieldAndCopiesOfOneKeyShareTheStockInEitherOrderOfTheLines() {
        for (boolean shieldFirst : new boolean[] {true, false}) {
            JsonObject b = shieldAndCopies(shieldFirst);
            Order.Line shield = line("minecraft:shield", "shield:" + K_BANNER, 1), copies = line(BANNER, "copy:" + K_BANNER, 2);
            CraftLedger l = ledger(b, 2048, shieldFirst ? List.of(shield, copies) : List.of(copies, shield));
            Map<String, Long> carried = new HashMap<>();
            String shieldRef = "minecraft:shield@shield:" + K_BANNER;
            Run first = shieldFirst ? runLine(l, shieldRef, 1, 1, 27, carried) : runLine(l, B, 2, 16, 27, carried);
            assertEquals(null, first.failure(), (shieldFirst ? "shield" : "copies") + " first: " + first.failure());
            Run second = shieldFirst ? runLine(l, B, 2, 16, 27, carried) : runLine(l, shieldRef, 1, 1, 27, carried);
            assertEquals(null, second.failure(), (shieldFirst ? "copies" : "shield") + " second: " + second.failure());
            assertEquals(0L, l.budget().getOrDefault(B, 0L), "both banners of the key are used (one is the source, which comes back)");
            assertEquals(0L, l.budget().getOrDefault(BANNER, 0L), "two blanks and no more");
        }
    }

    @Test
    void theReCutNeverUsesMoreFillsThanTheCapOfAnOrderTheServerAccepted() {
        // 16 in stock, 20 asked: the server counted 5 crafts and the bot's cap is exactly 5, but the loads are shrunk to 4 by the free slots
        CraftLedger l = ledger(bannerCopy(16, 20), 5, List.of(line(BANNER, "copy:" + K_BANNER, 20)));
        Run r = runLine(l, B, 20, 4, 27, new HashMap<>());
        assertEquals(null, r.failure(), r.failure());
        assertEquals(20, r.delivered());
        assertEquals(5, l.fills(), "one craft a copy: the same 5");
        // With a cap below the server's count (a bot that changed its cap) a load is short with the cap's reason, never made past it
        CraftLedger under = new CraftLedger(parse(bannerCopy(16, 20)), List.of(line(BANNER, "copy:" + K_BANNER, 20)), R, 3);
        Run u = runLine(under, B, 20, 4, 27, new HashMap<>());
        assertTrue(u.failure() != null && u.failure().contains("cap"), String.valueOf(u.failure()));
        assertTrue(under.fills() <= 3);
    }

    @Test
    void theSlotTheSourceIsSplitInto_isCounted() {
        CraftLedger l = ledger(bannerCopy(3, 3), 2048, List.of(line(BANNER, "copy:" + K_BANNER, 3)));
        l.setSurplus(Map.of());
        int minimal = -1;
        for (int free = 0; free < 30 && minimal < 0; free++) if (CraftBatch.choose(l, B, 3, free, 2048, id -> 16, need -> need.size()).count() == 3) minimal = free;
        CraftBatch.Choice c = CraftBatch.choose(l, B, 3, 27, 2048, id -> 16, need -> need.size());
        int loads = c.plan().fromStock().size();
        assertEquals(CraftBatch.room(c.plan(), loads, B, id -> 16) + 1, minimal, "the room of the plan, and a slot for the source to be split into");
        assertTrue(CraftBatch.choose(l, B, 3, minimal - 1, 2048, id -> 16, need -> need.size()).count() < 3);
    }
}
