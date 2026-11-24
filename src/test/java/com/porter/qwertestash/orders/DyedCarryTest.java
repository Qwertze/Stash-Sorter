package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.porter.qwertestash.orders.DyedFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A first coat that is carried when a two-coat chunk stops or the bot restarts (CRAFT-CONTRACT-v1.2.md B5): it is put back with the other leftovers and listed in
 * {@code returned[]} with its {@code dyed:<mid>} spec, never in {@code made[]} or {@code used[]}; after a restart it is set aside again by its pseudo id or, when
 * it is gone, given back to the budget without counting as anything. And the base pool: a take-named dyed stock key is never counted in the plain pool.
 */
class DyedCarryTest {
    private static final Two TWO = twoCoats().get(0);
    private static final String RESULT = DyedSpecs.specOf(TWO.result()), MID = DyedSpecs.specOf(TWO.mid());
    private static final String REF = CHEST + "@" + RESULT, MID_REF = CHEST + "@" + MID;

    private static CraftLedger ledger() {
        return new CraftLedger(parse(twoCoat(CHEST, TWO, 1)), List.of(new Order.Line(Order.CRAFT, CHEST, 1, false, RESULT)), R, 2048);
    }

    private static long count(JsonArray list, String id, String spec) {
        for (var e : list) {
            JsonObject o = e.getAsJsonObject();
            String key = o.has("id") ? "id" : "key";
            if (o.get(key).getAsString().equals(id) && (spec == null ? !o.has("spec") : o.has("spec") && o.get("spec").getAsString().equals(spec))) return o.has("count") ? o.get("count").getAsLong() : o.get("made").getAsLong();
        }
        return 0;
    }

    /** What the first coat gathered and consumed: the plain piece and the first coat's dyes (the second coat's dyes were put back, not charged). */
    private static Map<String, Long> firstCoatCharge() {
        Map<String, Long> m = new HashMap<>();
        m.put(CHEST, 1L);
        for (int i = 0; i < 16; i++) if (TWO.first()[i] > 0) m.put(dyeId(i), (long) TWO.first()[i]);
        return m;
    }

    @Test
    void aChunkStoppedAfterTheFirstCoatPutsItBackAndListsItReturnedWithItsMidSpecNeitherMadeNorUsed() {
        CraftLedger l = ledger();
        l.setSurplus(Map.of());
        CraftSub.Outcome o = l.session().plan(REF, 1);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(2, o.plan().steps().size());
        // The first coat was made (a fill) and the second step failed or the bot was stopped: nothing of the line was made
        l.madeChunk(REF, 0, 1, firstCoatCharge());
        l.setSurplus(Map.of(MID_REF, 1L));
        assertEquals(1L, l.surplus().get(MID_REF));
        // The order ends: the first coat goes back with the leftovers, and the line is short
        l.setReturned(Map.of(MID_REF, 1L));
        l.closeOpen(CraftOrder.INTERRUPTED);
        JsonObject r = l.toResult();
        assertEquals(1, count(r.getAsJsonArray("returned"), CHEST, MID), r.toString());
        for (var e : r.getAsJsonArray("returned")) assertEquals(CHEST, e.getAsJsonObject().get("id").getAsString());
        assertEquals(0, count(r.getAsJsonArray("made"), CHEST, MID), "the first coat is no line");
        assertEquals(0, r.getAsJsonArray("made").get(0).getAsJsonObject().get("made").getAsInt(), "nothing of the line was made");
        assertEquals(RESULT, r.getAsJsonArray("made").get(0).getAsJsonObject().get("spec").getAsString());
        for (var e : r.getAsJsonArray("used")) assertFalse(e.getAsJsonObject().has("spec") && e.getAsJsonObject().get("spec").getAsString().startsWith("dyed:"), "no dyed piece is used from stock: " + e);
        assertEquals(1, count(r.getAsJsonArray("used"), CHEST, null), "the plain piece that was dyed");
        JsonObject sh = r.getAsJsonArray("short").get(0).getAsJsonObject();
        assertEquals(RESULT, sh.get("spec").getAsString());
        assertEquals("interrupted", sh.get("reason").getAsString());
        assertEquals(1, sh.get("count").getAsInt());
    }

    @Test
    void aCarriedFirstCoatIsTheNextChunksStartAndNothingIsTakenForIt() {
        CraftLedger l = ledger();
        l.setSurplus(Map.of(MID_REF, 1L));
        CraftSub.Outcome o = l.session().plan(REF, 1);
        assertTrue(o.ok(), o.plan().refusal());
        assertEquals(1, o.plan().steps().size(), "only the second coat");
        assertFalse(o.plan().fromStock().containsKey(CHEST), "no plain piece for a first coat that is in hand: " + o.plan().fromStock());
        for (int i = 0; i < 16; i++) if (TWO.first()[i] > 0 && TWO.second()[i] == 0) assertFalse(o.plan().fromStock().containsKey(dyeId(i)), "the first coat's dyes stay in the stash");
        assertNull(o.plan().refusal());
    }

    @Test
    void aRestartWithAFirstCoatCarriedSetsItAsideAgainByItsPseudoIdAndSavesItAsSurplusOnly() {
        CraftLedger l = ledger();
        l.madeChunk(REF, 0, 1, firstCoatCharge());
        l.setSurplus(Map.of(MID_REF, 1L));
        JsonObject saved = l.toSaved();
        assertEquals(1L, saved.getAsJsonObject("surplus").get(MID_REF).getAsLong());
        assertFalse(saved.getAsJsonObject("made").has(MID_REF), "never made, as a line");
        assertFalse(saved.getAsJsonObject("used").has(MID_REF), "never used from stock");
        CraftLedger back = ledger();
        back.restore(saved);
        assertEquals(1L, back.savedSurplus().get(MID_REF));
        // The host lists a carried dyed piece by its pseudo id; the slot is found again
        CraftResume.Result found = CraftResume.remark(List.of(), back.savedSurplus(), List.of(new CraftResume.Stack(7, MID_REF, 1)));
        assertEquals(MID_REF, found.surplusSlots().get(7));
        assertTrue(found.refund().isEmpty());
        // Gone with the restart: given back, which is nothing for a piece that was never budget, and counted as nothing
        CraftResume.Result lost = CraftResume.remark(List.of(), back.savedSurplus(), List.of());
        assertEquals(1L, lost.refund().get(MID_REF));
        back.refund(lost.refund());
        assertEquals(0L, back.budget().getOrDefault(MID_REF, 0L), "a first coat is no budget");
        JsonObject r = back.toResult();
        assertEquals(0, count(r.getAsJsonArray("used"), CHEST, MID));
        assertEquals(0, count(r.getAsJsonArray("returned"), CHEST, MID));
        assertEquals(0, count(r.getAsJsonArray("made"), CHEST, MID));
    }

    @Test
    void aTakeNamedDyedStockKeyIsNeverCountedInThePlainBasePool() {
        CraftPool.Stock s = new CraftPool.Stock(Map.of(CHEST, 5L, K_RED1, 3L), Map.of(), Map.of(), Map.of(CHEST, 1, K_RED1, 1));
        long plain = 0;
        for (Order.Lot lot : CraftPool.lots(s, Map.of(CHEST, 8L), List.of(), true)) {
            assertEquals(CHEST, lot.key(), "a dyed key is no base");
            plain += lot.count();
        }
        assertEquals(5, plain);
        // The take's own key reaches its pieces, and only by that key
        long keyed = 0;
        for (Order.Lot lot : CraftPool.lotsByKey(s.plainAnd(Set.of(K_RED1)), Map.of(K_RED1, 3L), List.of())) {
            assertEquals(K_RED1, lot.key());
            keyed += lot.count();
        }
        assertEquals(3, keyed);
        assertFalse(s.plainOnly().loose().containsKey(K_RED1));
    }
}
