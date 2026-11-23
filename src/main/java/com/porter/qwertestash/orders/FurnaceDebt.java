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
    /** The most entries of placed and produced a saved record keeps each, and the longest the saved text may be (about 8 KiB). */
    public static final int MAX_ENTRIES = 48, MAX_LENGTH = 8 * 1024;

    /** The record as the settings keep it, or "" for a closed journal. An open journal is a record even while empty: the furnace may hold what a crash left before the first entry. */
    public static String toSaved(String dimension, int x, int y, int z, SmeltJournal journal) {
        if (!journal.isOpen()) return "";
        SmeltJournal j = journal;
        for (int keep = MAX_ENTRIES; ; keep = keep / 2) {
            if (keep < MAX_ENTRIES || journal.placed().size() > MAX_ENTRIES || journal.produced().size() > MAX_ENTRIES) j = trimmed(journal, keep);
            JsonObject o = new JsonObject();
            o.addProperty("dimension", dimension);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.add("journal", j.toJson());
            String text = o.toString();
            if (text.length() <= MAX_LENGTH || keep == 0) return text;
        }
    }

    /** The journal has more entries than a saved record keeps (it is cut: the host logs it). */
    public static boolean truncates(SmeltJournal journal) {
        return journal.placed().size() > MAX_ENTRIES || journal.produced().size() > MAX_ENTRIES;
    }

    private static SmeltJournal trimmed(SmeltJournal src, int keep) {
        SmeltJournal j = new SmeltJournal();
        j.open();
        int n = 0;
        for (var e : src.placed().entrySet()) if (n++ < keep) j.placed(e.getKey(), e.getValue());
        n = 0;
        for (var e : src.produced().entrySet()) if (n++ < keep) j.collected(e.getKey(), e.getValue());
        if (src.active() != null) j.loaded(src.active());
        return j;
    }

    /** The record read back, or null for none or one that can't be read. */
    public static @Nullable FurnaceDebt parse(@Nullable String saved) {
        if (saved == null || saved.isBlank() || saved.length() > 2 * MAX_LENGTH) return null;
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
