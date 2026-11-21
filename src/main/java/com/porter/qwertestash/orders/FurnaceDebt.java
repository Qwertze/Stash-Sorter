package com.porter.qwertestash.orders;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the bot still owes its furnace (B2): the furnace (dimension and position) and the smelt journal's placed and produced items, kept at the bot's
 * level in the settings, written whenever the journal changes and cleared when the journal is closed (the furnace was emptied and the chunk accounted
 * for). An order's ledger holds the same journal, but it is lost when the order ends (cancelled, failed, finished, or timed out while the bot was offline,
 * queued or player-controlled); this record survives that. The next furnace use, or an idle bot, takes the furnace up first and the items go back to the
 * stash by the usual put-back. Never shown on the website (it holds a position). Pure.
 */
public record FurnaceDebt(String dimension, int x, int y, int z, SmeltJournal journal) {
    /** The record as the settings keep it, or "" when the journal holds nothing (nothing was put in or taken out yet, or it is closed). */
    public static String toSaved(String dimension, int x, int y, int z, SmeltJournal journal) {
        if (!journal.isOpen() || (journal.placed().isEmpty() && journal.produced().isEmpty() && journal.active() == null)) return "";
        JsonObject o = new JsonObject();
        o.addProperty("dimension", dimension);
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.add("journal", journal.toJson());
        return o.toString();
    }

    /** The record read back, or null for none or one that can't be read. */
    public static @Nullable FurnaceDebt parse(@Nullable String saved) {
        if (saved == null || saved.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(saved).getAsJsonObject();
            SmeltJournal j = SmeltJournal.fromJson(o.get("journal"));
            if (!j.isOpen()) return null;
            return new FurnaceDebt(o.get("dimension").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(), j);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The item ids the bot put in or took out: what in this furnace is the bot's own. */
    public Set<String> ids() {
        Set<String> out = new LinkedHashSet<>(journal.placed().keySet());
        out.addAll(journal.produced().keySet());
        return out;
    }
}
