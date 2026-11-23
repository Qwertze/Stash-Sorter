package com.porter.qwertestash.manager;

import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.orders.FurnaceDebt;
import com.porter.qwertestash.orders.SmeltJournal;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The bot-level furnace debt (B2 recheck): what the bot put in its furnace outlives the order that did it. */
class FurnaceDebtTest {
    private static SmeltJournal journal() {
        SmeltJournal j = new SmeltJournal();
        j.open();
        j.placed("minecraft:sand", 20);
        j.placed("minecraft:coal", 3);
        j.collected("minecraft:glass", 6);
        return j;
    }

    @Test
    void theDebtIsSavedAndReadBackWithTheFurnaceAndTheJournal() {
        String saved = FurnaceDebt.toSaved("minecraft:overworld", 10, 64, -20, journal());
        FurnaceDebt d = FurnaceDebt.parse(saved);
        assertNotNull(d);
        assertEquals("minecraft:overworld", d.dimension());
        assertEquals(10, d.x());
        assertEquals(-20, d.z());
        assertEquals(journal().placed(), d.journal().placed());
        assertEquals(journal().produced(), d.journal().produced());
        assertEquals(Set.of("minecraft:sand", "minecraft:coal", "minecraft:glass"), d.ids());
    }

    @Test
    void nothingIsOwedForAClosedOrEmptyJournalOrAnUnreadableRecord() {
        SmeltJournal closed = journal();
        closed.close();
        assertEquals("", FurnaceDebt.toSaved("d", 1, 2, 3, closed));
        SmeltJournal fresh = new SmeltJournal();
        fresh.open();
        // An open journal is a record even while empty: a crash between the first click and the first entry still leaves the furnace on record
        FurnaceDebt empty = FurnaceDebt.parse(FurnaceDebt.toSaved("d", 1, 2, 3, fresh));
        assertNotNull(empty);
        assertEquals(1, empty.x());
        assertTrue(empty.journal().isOpen());
        assertTrue(empty.ids().isEmpty());
        assertNull(FurnaceDebt.parse(""));
        assertNull(FurnaceDebt.parse(null));
        assertNull(FurnaceDebt.parse("not json"));
        assertNull(FurnaceDebt.parse("{\"dimension\":\"d\"}"));
    }

    @Test
    void aBigJournalIsCutToAboutEightKiBAndSaysSo() {
        SmeltJournal big = new SmeltJournal();
        big.open();
        for (int i = 0; i < 400; i++) {
            big.placed("minecraft:some_long_item_name_number_" + i + "_" + "x".repeat(60), i + 1);
            big.collected("minecraft:other_long_item_name_number_" + i + "_" + "y".repeat(60), i + 1);
        }
        assertTrue(FurnaceDebt.truncates(big));
        String saved = FurnaceDebt.toSaved("minecraft:overworld", 1, 2, 3, big);
        assertTrue(saved.length() <= FurnaceDebt.MAX_LENGTH, "length " + saved.length());
        FurnaceDebt d = FurnaceDebt.parse(saved);
        assertNotNull(d, "still readable");
        assertTrue(d.journal().placed().size() <= FurnaceDebt.MAX_ENTRIES);
        assertEquals(1, d.x());
        assertFalse(FurnaceDebt.truncates(journal()));
        // A record far beyond the cap is not read at all
        assertNull(FurnaceDebt.parse("x".repeat(3 * FurnaceDebt.MAX_LENGTH)));
    }

    @Test
    void theDebtHoldsAPositionSoItIsNeverOnTheWebsite() {
        StashConfig c = new StashConfig();
        c.craftFurnaceDebt = FurnaceDebt.toSaved("minecraft:the_end", 424242, 64, -515151, journal());
        ConfigSchema.Built built = ConfigSchema.build(c, ConfigSchema.PLUGIN);
        String schema = built.schema().toString(), values = built.values().toString();
        for (String secret : new String[] {"424242", "-515151", "the_end", "craftFurnaceDebt"}) {
            assertFalse(values.contains(secret), "values leak " + secret);
            assertFalse(schema.contains(secret), "schema leaks " + secret);
        }
        assertTrue(SettingsSpec.PLUGIN.get("craftFurnaceDebt").hidden);
    }

}
