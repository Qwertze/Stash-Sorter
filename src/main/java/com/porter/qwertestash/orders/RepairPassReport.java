package com.porter.qwertestash.orders;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The report of one repair pass (REPAIR-CONTRACT v2, {@code POST /api/v1/repair/report}): totals, the skip reasons and up to 200 items. The hash covers the pass
 * and its items, not the time, so an unchanged report is not sent again. Numbers, keys and codes only: no positions beyond container ids, never logged.
 */
public final class RepairPassReport {
    public static final int MAX_ITEMS = 200;
    public static final String RUNNING = "running", DONE = "done", YIELDED = "yielded", STOPPED = "stopped", FAILED = "failed";

    public record Item(String key, String status, @Nullable String reason, @Nullable String from, @Nullable String to, int damage, int maxDamage, @Nullable String box) {
    }

    public final long id;
    public final String trigger;
    public final long startedAt;
    public final int threshold;
    public String state = RUNNING;
    public long endedAt;
    public int repaired, partial, bottlesUsed, rehomed, toNeedsRepair, left, boxesOpened, boxesFailed;
    private final Map<String, Integer> skippedBy = new LinkedHashMap<>();
    private int skipped;
    private final List<Item> items = new ArrayList<>();

    public RepairPassReport(long id, String trigger, long startedAt, int threshold) {
        this.id = id;
        this.trigger = trigger;
        this.startedAt = startedAt;
        this.threshold = threshold;
    }

    public void add(Item item) {
        if (items.size() < MAX_ITEMS) items.add(item);
        switch (item.status()) {
            case "repaired" -> repaired++;
            case "partial" -> partial++;
            case "skipped" -> {
                skipped++;
                if (item.reason() != null) skippedBy.merge(item.reason(), 1, Integer::sum);
            }
            default -> {
            }
        }
    }

    public void skip(String key, String reason, @Nullable String from, int damage, int maxDamage, @Nullable String box) {
        add(new Item(key, "skipped", reason, from, null, damage, maxDamage, box));
    }

    public int skipped() {
        return skipped;
    }

    public List<Item> items() {
        return items;
    }

    public void end(String state, long now) {
        this.state = state;
        this.endedAt = now;
    }

    private JsonObject passJson() {
        JsonObject p = new JsonObject();
        p.addProperty("id", id);
        p.addProperty("trigger", trigger);
        p.addProperty("state", state);
        p.addProperty("startedAt", startedAt);
        p.addProperty("endedAt", endedAt);
        p.addProperty("threshold", threshold);
        p.addProperty("repaired", repaired);
        p.addProperty("partial", partial);
        p.addProperty("bottlesUsed", bottlesUsed);
        p.addProperty("rehomed", rehomed);
        p.addProperty("toNeedsRepair", toNeedsRepair);
        p.addProperty("skipped", skipped);
        p.addProperty("left", left);
        p.addProperty("boxesOpened", boxesOpened);
        p.addProperty("boxesFailed", boxesFailed);
        JsonObject by = new JsonObject();
        for (var e : skippedBy.entrySet()) by.addProperty(e.getKey(), e.getValue());
        p.add("skippedBy", by);
        return p;
    }

    private JsonArray itemsJson() {
        JsonArray a = new JsonArray();
        for (Item i : items) {
            JsonObject o = new JsonObject();
            o.addProperty("key", i.key().length() > 256 ? i.key().substring(0, 256) : i.key());
            o.addProperty("status", i.status());
            if (i.reason() == null) o.add("reason", JsonNull.INSTANCE);
            else o.addProperty("reason", i.reason());
            if (i.from() == null) o.add("from", JsonNull.INSTANCE);
            else o.addProperty("from", i.from());
            if (i.to() == null) o.add("to", JsonNull.INSTANCE);
            else o.addProperty("to", i.to());
            o.addProperty("damage", Math.max(0, i.damage()));
            o.addProperty("maxDamage", Math.max(0, i.maxDamage()));
            if (i.box() == null) o.add("box", JsonNull.INSTANCE);
            else o.addProperty("box", i.box());
            a.add(o);
        }
        return a;
    }

    /** The hash of the pass and its items without the times. */
    public String hash() {
        JsonObject p = passJson();
        p.remove("startedAt");
        p.remove("endedAt");
        String text = p + "\n" + itemsJson();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The request body; {@code containers} (the kit report's entries) only when kit sorting is off, else null. */
    public byte[] body(String server, String stash, String player, @Nullable JsonArray containers) {
        JsonObject o = new JsonObject();
        o.addProperty("version", 1);
        o.addProperty("server", server);
        o.addProperty("stash", stash);
        o.addProperty("player", player);
        o.addProperty("hash", hash());
        o.add("pass", passJson());
        o.add("items", itemsJson());
        if (containers != null) o.add("containers", containers);
        return o.toString().getBytes(StandardCharsets.UTF_8);
    }
}
