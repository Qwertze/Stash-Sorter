package com.porter.qwertestash.manager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.porter.qwertestash.stash.AreaRules;
import com.porter.qwertestash.stash.CraftSettings;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitSettings;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds what the plugin sends to the Stash Manager server (see stash-manager-server/PROTOCOL.md): the index of the saved
 * stash with its hash, and the heartbeat. Works from a snapshot of the stash and plain data only (the contents arrays are
 * replaced and never changed in place), so it can run on any thread. Every string is cut to the protocol's limit.
 */
public final class ManagerIndex {
    private ManagerIndex() {
    }

    /** A finished index: the JSON body and the hash of its items, plus what identifies the stash. */
    public record Built(String server, String stash, String player, String hash, byte[] json, int known, int total, int stale, int itemTypes) {
    }

    /** What is worked out once per key. */
    private record Info(String key, String id, String name, String englishName, int maxStack, List<String> details, boolean enchanted, int damage, int maxDamage, boolean mending,
                        String match) {
    }

    private static final class Agg {
        final Info info;
        long count;

        Agg(Info info) {
            this.info = info;
        }
    }

    private static final class Shulker {
        final Info info;
        final String key;
        final List<int[]> slots = new ArrayList<>();
        final List<String> keys = new ArrayList<>();
        final List<String> matches = new ArrayList<>();
        int count;

        Shulker(Info info, String key) {
            this.info = info;
            this.key = key;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Gson GSON_NULLS = new GsonBuilder().serializeNulls().create();
    private static final int MAX_DEPTH = 4;
    private static final int MAX_TEXT = 256;
    private static final int OTHER_ORDER = 1_000_000;

    /**
     * @param englishName the item's English name for a registry id like {@code diamond_sword}, or null if unknown
     */
    public static Built build(Stash stash, boolean includeExcluded, String player, String mcVersion, @Nullable String dimension,
                              Function<String, @Nullable String> englishName) {
        Map<String, Info> infos = new LinkedHashMap<>();
        Map<String, Agg> groups = new LinkedHashMap<>();
        Map<String, Shulker> shulkers = new LinkedHashMap<>();
        int known = 0, total = 0, stale = 0;
        for (Box box : stash.boxes) {
            // The delivery container is where orders are put, not part of the stash being indexed
            if (box.delivery || !stash.inScope(box) || (box.excluded && !includeExcluded)) continue;
            total++;
            if (box.stale) stale++;
            Item[] contents = box.contents;
            if (contents == null) continue;
            known++;
            for (Item item : contents) {
                if (item == null) continue;
                add(groups, infos, item, 0, englishName);
                addShulker(shulkers, infos, item, englishName);
            }
        }

        List<Agg> sorted = new ArrayList<>(groups.values());
        sorted.sort(Comparator.comparing((Agg a) -> a.info.key()));

        JsonArray items = new JsonArray();
        for (Agg a : sorted) {
            Info i = a.info;
            JsonObject o = new JsonObject();
            o.addProperty("key", i.key());
            // The match key (1.16.0): the key without the custom name, damage and repair cost, for kit templates
            o.addProperty("match", i.match());
            o.addProperty("id", i.id());
            o.addProperty("name", i.name());
            o.addProperty("englishName", i.englishName());
            o.addProperty("count", a.count);
            o.addProperty("maxStack", i.maxStack());
            JsonArray details = new JsonArray();
            for (String d : i.details()) details.add(d);
            o.add("details", details);
            o.addProperty("enchanted", i.enchanted());
            // Only a damaged, damageable item has them (a damaged stack has a key of its own with split durability on)
            if (i.damage() > 0) {
                o.addProperty("damage", i.damage());
                o.addProperty("maxDamage", i.maxDamage());
            }
            // Only a stack with the Mending enchantment carries it (REPAIR-CONTRACT.md)
            if (i.mending()) o.addProperty("mending", true);
            // The plugin has no creative tabs: every item goes in "other"
            o.addProperty("tab", "other");
            o.addProperty("tabOrder", OTHER_ORDER);
            items.add(o);
        }

        List<Shulker> sortedShulkers = new ArrayList<>(shulkers.values());
        sortedShulkers.sort(Comparator.comparing((Shulker s) -> s.key));
        JsonArray shulkerArray = new JsonArray();
        for (Shulker sh : sortedShulkers) {
            JsonObject o = new JsonObject();
            o.addProperty("key", sh.key);
            o.addProperty("itemKey", sh.info.key());
            o.addProperty("id", sh.info.id());
            o.addProperty("name", sh.info.name());
            o.addProperty("count", sh.count);
            JsonArray contents = new JsonArray();
            for (int n = 0; n < sh.keys.size(); n++) {
                JsonObject c = new JsonObject();
                c.addProperty("slot", sh.slots.get(n)[0]);
                c.addProperty("key", sh.keys.get(n));
                c.addProperty("match", sh.matches.get(n));
                c.addProperty("count", sh.slots.get(n)[1]);
                contents.add(c);
            }
            o.add("contents", contents);
            shulkerArray.add(o);
        }
        String hash = sha1(GSON.toJson(items) + GSON.toJson(shulkerArray));

        String who = clamp(player, 256);
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("server", clamp(stash.server, 256));
        root.addProperty("stash", clamp(stash.name, 256));
        root.addProperty("player", who);
        root.addProperty("mcVersion", mcVersion);
        root.addProperty("dimension", namespaced(dimension));
        root.addProperty("generatedAt", System.currentTimeMillis());
        root.addProperty("indexHash", hash);
        JsonObject containers = new JsonObject();
        containers.addProperty("known", known);
        containers.addProperty("total", total);
        containers.addProperty("stale", stale);
        root.add("containers", containers);
        root.add("items", items);
        root.add("shulkers", shulkerArray);

        return new Built(clamp(stash.server, 256), clamp(stash.name, 256), who, hash, GSON.toJson(root).getBytes(StandardCharsets.UTF_8),
            known, total, stale, sorted.size());
    }

    /** {damage, maxDamage} to send for an item, or {0, 0} to send neither: only damageable (maxDamage > 0) and damaged (damage > 0). */
    static int[] durability(int damage, int maxDamage) {
        return maxDamage > 0 && damage > 0 ? new int[] {damage, maxDamage} : new int[] {0, 0};
    }

    /** "overworld" as "minecraft:overworld"; a name with a namespace is left alone. Empty when unknown. */
    public static String namespaced(@Nullable String dimension) {
        if (dimension == null || dimension.isBlank()) return "";
        String d = dimension.trim();
        return d.indexOf(':') >= 0 ? d : "minecraft:" + d;
    }

    /**
     * The heartbeat body. {@code rescanDone} is the website rescan id handled and not yet acknowledged, or -1;
     * {@code rescanBlocked} why a rescan can't start, or null. In this version no orders are taken and there is no
     * delivery container, so delivery is null and the order blocker says so.
     */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, null);
    }

    /** The delivery container the bot will fill: x, y, z and dimension (whole numbers, dimension cut to 100 characters). */
    public record Delivery(int x, int y, int z, String dimension) {
    }

    /**
     * @param delivery the delivery container, or null when none is set (sent as an explicit null, which clears it)
     * @param orderBlocker why orders cannot be taken right now, or null when they can (PROTOCOL.md "delivery status")
     */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, delivery, null);
    }

    /**
     * Whether the bot can name order shulkers (PROTOCOL.md "Shulker names"): {@code anvil} is true when an anvil is set and,
     * if its chunk is loaded, it is an anvil; {@code xpLevels} is the bot's level, or -1 when it is not known. No position.
     */
    public record Naming(boolean anvil, int xpLevels, @Nullable String anvilSource) {
        public Naming(boolean anvil, int xpLevels) {
            this(anvil, xpLevels, null);
        }
    }

    /**
     * Whether the bot crafts shulker boxes for orders (PROTOCOL.md "Crafting"): {@code enabled} is the setting, {@code table}
     * whether a crafting table is set (and, if its chunk is loaded, is one). No position.
     */
    public record Crafting(boolean enabled, boolean table, @Nullable String tableSource) {
        public Crafting(boolean enabled, boolean table) {
            this(enabled, table, null);
        }
    }

    /** @param naming sent as {@code naming}; null leaves the field out (a bot without naming support) */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery,
                                   @Nullable Naming naming) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, delivery, naming, null);
    }

    /**
     * The delivery storage (1.9.0, PROTOCOL.md "deliveryStorage"): how many chests there are (a double chest counts once), how
     * many of them are known to be full, whether another can be put up now, and why not. No positions.
     * @param autoExpand the setting
     * @param blocked    why no chest can be put up (or the order is held up by the storage), or null
     */
    public record DeliveryStorage(boolean autoExpand, int chests, int full, boolean canExpand, @Nullable String blocked) {
    }

    /** @param crafting sent as {@code crafting}; null leaves the field out (a bot without crafting support) */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery,
                                   @Nullable Naming naming, @Nullable Crafting crafting) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, delivery, naming, crafting, null);
    }

    /** @param storage sent as {@code deliveryStorage}; null leaves the field out (a bot without delivery storage, or none set) */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery,
                                   @Nullable Naming naming, @Nullable Crafting crafting, @Nullable DeliveryStorage storage) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, delivery, naming, crafting, storage, null);
    }

    /** @param deliveryHash sent as {@code deliveryHash} (1.11.0, the hash of the delivery contents report); null leaves the field out */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery,
                                   @Nullable Naming naming, @Nullable Crafting crafting, @Nullable DeliveryStorage storage, @Nullable String deliveryHash) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, delivery, naming, crafting, storage, deliveryHash, null);
    }

    /**
     * What the heartbeat says about kit sorting (1.14.0, KIT-CONTRACT.md "Heartbeat"). Both keys are written, as an explicit null when
     * the bot has nothing: {@code settingsHash} is the hash of the cached settings for this stash, {@code reportHash} the hash of the
     * current report.
     */
    public record Kits(@Nullable String settingsHash, @Nullable String reportHash, @Nullable String settingsHash2, @Nullable KitCaps caps) {
        public Kits(@Nullable String settingsHash, @Nullable String reportHash, @Nullable String settingsHash2) {
            this(settingsHash, reportHash, settingsHash2, null);
        }

        public Kits(@Nullable String settingsHash, @Nullable String reportHash) {
            this(settingsHash, reportHash, null, null);
        }
    }

    /** The {@code kitOrders} capability of the heartbeat (KIT-CONTRACT v2 "Heartbeat additions"): {@code craft} only when the bot also sends craftOrders enabled. */
    public record KitCaps(boolean enabled, boolean restock, boolean craft) {
    }

    /** @param kits sent as {@code kitsHash} and {@code kitsReportHash}; null leaves both out (kit sorting is off) */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery,
                                   @Nullable Naming naming, @Nullable Crafting crafting, @Nullable DeliveryStorage storage, @Nullable String deliveryHash,
                                   @Nullable Kits kits) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, delivery, naming, crafting, storage, deliveryHash, kits, null);
    }

    /** The crafting table states a bot reports (CRAFT-CONTRACT.md "Heartbeat"). */
    public static final String TABLE_UNSET = "unset", TABLE_OK = "ok", TABLE_UNLOADED = "unloaded", TABLE_NOT_TABLE = "not_table",
        TABLE_UNREACHABLE = "unreachable", TABLE_OTHER_DIMENSION = "other_dimension", TABLE_AUTO = "auto";

    /** The most crafting tables the heartbeat lists as seen in the stash area. */
    public static final int MAX_SEEN = 8;

    /**
     * What the heartbeat says about craft orders (1.15.0, CRAFT-CONTRACT.md "Heartbeat"): {@code v} 1, whether this bot takes them,
     * the tableHash of the recipe table it holds, its Minecraft version, the state of the website's crafting table, the most grid
     * fills it makes for one order, and up to 8 tables seen in the stash area ("x,y,z").
     */
    public record CraftOrders(boolean enabled, String recipes, String mc, String table, int maxFills, List<String> seen, @Nullable String tableWhy, @Nullable BotTable botTable,
                              boolean legacy) {
        public CraftOrders(boolean enabled, String recipes, String mc, String table, int maxFills, List<String> seen, @Nullable String tableWhy, @Nullable BotTable botTable) {
            this(enabled, recipes, mc, table, maxFills, seen, tableWhy, botTable, false);
        }

        public CraftOrders(boolean enabled, String recipes, String mc, String table, int maxFills, List<String> seen) {
            this(enabled, recipes, mc, table, maxFills, seen, null, null, false);
        }

        /** The 1.15.0 shape, for a server that answers 400 to the newer fields: "auto" is "unset", and there is no tableWhy and no botTable. */
        public CraftOrders asLegacy() {
            return new CraftOrders(enabled, recipes, mc, "auto".equals(table) ? TABLE_UNSET : table, maxFills, seen, null, null, true);
        }
    }

    /** A heartbeat the server refused with 400 while it carried craftOrders: it is an older server, and the 1.15.0 shape is used from then on. */
    public static boolean craftShapeRefused(int code, String body, boolean sentCraftOrders, boolean alreadyLegacy) {
        if (code != 400 || !sentCraftOrders || alreadyLegacy) return false;
        String text = body;
        try {
            JsonElement e = SafeJson.parse(body);
            if (e.isJsonObject() && e.getAsJsonObject().has("error") && e.getAsJsonObject().get("error").isJsonPrimitive()) text = e.getAsJsonObject().get("error").getAsString();
        } catch (RuntimeException ex) {
            // The text as it is
        }
        // The server names the field: "craftOrders.<field> ..."
        return text.contains("craftOrders") || text.contains("tableWhy") || text.contains("botTable") || text.contains("table");
    }

    /** The bot's own crafting table in use ("x,y,z" and how it came to be: found, placed or crafted); the server hides it from streamers. */
    public record BotTable(String pos, String source) {
    }

    /**
     * @param orders       the craftOrders object, or null when the bot can't carry out craft orders (sent as an explicit null: it is
     *                     never given one)
     * @param settingsHash the hash of the cached craft settings for this stash, or null when it has none (sent as an explicit null, so
     *                     the server answers with them); only written with {@code orders}
     */
    public record Craft(@Nullable CraftOrders orders, @Nullable String settingsHash) {
    }

    /**
     * What the heartbeat says about the stash area (1.15.2, AREA-CONTRACT.md "Heartbeat"). {@code hash} is the hash of the area the bot uses now;
     * {@code box}/{@code dimension} that area (null: none set); {@code derived} its working box when none is set; {@code edited} the area was changed
     * in game and the server has not taken it yet; {@code pendingHash}/{@code pendingWhy} an area received and not applied yet, and why (busy, far,
     * dimension); {@code containersOutside} how many containers lie outside the set area.
     */
    public record AreaReport(String hash, int @Nullable [] box, @Nullable String dimension, int @Nullable [] derived, boolean edited, @Nullable String pendingHash,
                             @Nullable String pendingWhy, int containersOutside) {
    }

    /** @param craft sent as {@code craftOrders} and {@code craftHash}; null leaves both out */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery,
                                   @Nullable Naming naming, @Nullable Crafting crafting, @Nullable DeliveryStorage storage, @Nullable String deliveryHash,
                                   @Nullable Kits kits, @Nullable Craft craft) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, delivery, naming, crafting, storage, deliveryHash, kits, craft, null);
    }

    /** What the heartbeat says about repair passes (REPAIR-CONTRACT v2): the hash of the cached settings (null: none, sent as null) and the pass object. */
    public record RepairHb(@Nullable String hash, @Nullable JsonObject pass) {
    }

    /** @param area sent as {@code area} (needs a player); null leaves it out */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery,
                                   @Nullable Naming naming, @Nullable Crafting crafting, @Nullable DeliveryStorage storage, @Nullable String deliveryHash,
                                   @Nullable Kits kits, @Nullable Craft craft, @Nullable AreaReport area) {
        return heartbeat(b, rescanDone, rescanBlocked, orderBlocker, delivery, naming, crafting, storage, deliveryHash, kits, craft, area, null);
    }

    /** @param repair sent as {@code repairHash} and {@code repairPass} (needs a player); null leaves both out (repair passes are off) */
    public static byte[] heartbeat(Built b, long rescanDone, @Nullable String rescanBlocked, @Nullable String orderBlocker, @Nullable Delivery delivery,
                                   @Nullable Naming naming, @Nullable Crafting crafting, @Nullable DeliveryStorage storage, @Nullable String deliveryHash,
                                   @Nullable Kits kits, @Nullable Craft craft, @Nullable AreaReport area, @Nullable RepairHb repair) {
        JsonObject o = new JsonObject();
        if (area != null && b.player() != null && !b.player().isBlank()) {
            JsonObject a = new JsonObject();
            a.addProperty("v", 1);
            a.addProperty("hash", area.hash());
            if (area.box() == null || area.dimension() == null) a.add("area", JsonNull.INSTANCE);
            else a.add("area", areaObject(area.box(), area.dimension()));
            if (area.derived() == null) a.add("derived", JsonNull.INSTANCE);
            else a.add("derived", boxArray(area.derived()));
            a.addProperty("edited", area.edited());
            if (area.pendingHash() == null) a.add("pending", JsonNull.INSTANCE);
            else {
                JsonObject pend = new JsonObject();
                pend.addProperty("hash", area.pendingHash());
                pend.addProperty("why", area.pendingWhy() == null ? AreaRules.BUSY : area.pendingWhy());
                a.add("pending", pend);
            }
            a.addProperty("containersOutside", Math.max(0, area.containersOutside()));
            o.add("area", a);
        }
        // The server answers 400 to craftOrders or craftHash without a player, and only gives the craft settings after checking the owner: with no
        // player known the craft fields are left out altogether
        if (craft != null && b.player() != null && !b.player().isBlank()) {
            CraftOrders c = craft.orders();
            if (c == null) o.add("craftOrders", JsonNull.INSTANCE);
            else {
                JsonObject co = new JsonObject();
                co.addProperty("v", 1);
                co.addProperty("enabled", c.enabled());
                co.addProperty("recipes", clamp(c.recipes(), 40));
                co.addProperty("mc", clamp(c.mc(), 32));
                co.addProperty("table", c.table());
                co.addProperty("maxFills", Math.max(1, Math.min(100_000, c.maxFills())));
                // Why the table can't be used (or null), and the bot's own table in use (or null): explicit nulls clear what the server kept
                if (c.legacy()) {
                    // Left out for an older server, which refuses fields it does not know
                }
                else if (c.tableWhy() == null) co.add("tableWhy", JsonNull.INSTANCE);
                else co.addProperty("tableWhy", c.tableWhy());
                if (c.legacy()) {
                }
                else if (c.botTable() == null) co.add("botTable", JsonNull.INSTANCE);
                else {
                    JsonObject bt = new JsonObject();
                    bt.addProperty("pos", c.botTable().pos());
                    bt.addProperty("source", c.botTable().source());
                    co.add("botTable", bt);
                }
                if (c.seen() != null && !c.seen().isEmpty()) {
                    JsonArray seen = new JsonArray();
                    for (String s : c.seen()) if (seen.size() < MAX_SEEN) seen.add(s);
                    co.add("seen", seen);
                }
                o.add("craftOrders", co);
                if (craft.settingsHash() == null) o.add("craftHash", JsonNull.INSTANCE);
                else o.addProperty("craftHash", clamp(craft.settingsHash(), 64));
            }
        }
        if (kits != null) {
            if (kits.settingsHash() == null) o.add("kitsHash", JsonNull.INSTANCE);
            else o.addProperty("kitsHash", clamp(kits.settingsHash(), 64));
            if (kits.reportHash() == null) o.add("kitsReportHash", JsonNull.INSTANCE);
            else o.addProperty("kitsReportHash", clamp(kits.reportHash(), 64));
            // v2 (1.16.0): the hash of the cached v2 part of the settings, null with no cache or one without types: the server then sends the settings
            if (kits.settingsHash2() == null) o.add("kitsHash2", JsonNull.INSTANCE);
            else o.addProperty("kitsHash2", clamp(kits.settingsHash2(), 64));
            // Only a bot that can carry out kit orders says so (absent otherwise); it needs the player like the craft fields
            if (kits.caps() != null && b.player() != null && !b.player().isBlank()) {
                JsonObject ko = new JsonObject();
                ko.addProperty("v", 1);
                ko.addProperty("enabled", kits.caps().enabled());
                ko.addProperty("restock", kits.caps().restock());
                ko.addProperty("craft", kits.caps().craft());
                o.add("kitOrders", ko);
            }
        }
        // Repair passes (2.0): the server only answers for a bot that sends repairHash; a bot with passes off sends neither key
        if (repair != null && b.player() != null && !b.player().isBlank()) {
            if (repair.hash() == null) o.add("repairHash", JsonNull.INSTANCE);
            else o.addProperty("repairHash", clamp(repair.hash(), 64));
            if (repair.pass() != null) o.add("repairPass", repair.pass());
        }
        if (deliveryHash != null && !deliveryHash.isEmpty()) o.addProperty("deliveryHash", clamp(deliveryHash, 64));
        o.addProperty("server", b.server());
        o.addProperty("stash", b.stash());
        o.addProperty("player", b.player());
        o.addProperty("indexHash", b.hash());
        // This bot repairs items with experience bottles for orders that ask for it (1.13.0, REPAIR-CONTRACT.md)
        o.addProperty("repair", true);
        if (rescanDone >= 0) o.addProperty("rescanDone", rescanDone);
        if (rescanBlocked == null || rescanBlocked.isEmpty()) o.add("rescanBlocked", JsonNull.INSTANCE);
        else o.addProperty("rescanBlocked", clamp(rescanBlocked, 100));
        if (delivery == null) o.add("delivery", JsonNull.INSTANCE);
        else {
            JsonObject d = new JsonObject();
            d.addProperty("x", delivery.x());
            d.addProperty("y", delivery.y());
            d.addProperty("z", delivery.z());
            d.addProperty("dimension", clamp(delivery.dimension(), 100));
            o.add("delivery", d);
        }
        if (orderBlocker == null || orderBlocker.isEmpty()) o.add("orderBlocker", JsonNull.INSTANCE);
        else o.addProperty("orderBlocker", clamp(orderBlocker, 100));
        if (naming != null) {
            JsonObject n = new JsonObject();
            n.addProperty("anvil", naming.anvil());
            n.addProperty("xpLevels", Math.max(-1, naming.xpLevels()));
            // How the anvil came to be (1.8.1): "set", "found", "placed" or "none". Never where it is
            if (naming.anvilSource() != null) n.addProperty("anvilSource", naming.anvilSource());
            o.add("naming", n);
        }
        if (crafting != null) {
            JsonObject c = new JsonObject();
            c.addProperty("enabled", crafting.enabled());
            c.addProperty("table", crafting.table());
            // How the table came to be (1.10.0): "set", "found", "placed", "crafted" or "none". Never where it is
            if (crafting.tableSource() != null) c.addProperty("tableSource", crafting.tableSource());
            o.add("crafting", c);
        }
        if (storage != null) {
            JsonObject d = new JsonObject();
            d.addProperty("autoExpand", storage.autoExpand());
            d.addProperty("chests", Math.max(0, storage.chests()));
            d.addProperty("full", Math.max(0, Math.min(storage.full(), storage.chests())));
            d.addProperty("canExpand", storage.canExpand());
            if (storage.blocked() == null || storage.blocked().isEmpty()) d.add("blocked", JsonNull.INSTANCE);
            else d.addProperty("blocked", clamp(storage.blocked(), 160));
            o.add("deliveryStorage", d);
        }
        return GSON_NULLS.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * What a heartbeat reply says about kits. {@code reportKnown}: the reply has a {@code needKitsReport} key (true or false), so the
     * server knows the report route. {@code needReport}: it wants the report. {@code settings}: a valid {@code kits} object, else null.
     * {@code invalid}: why a {@code kits} object was refused (null when there was none or it was fine).
     */
    public record KitsReply(boolean reportKnown, boolean needReport, @Nullable KitSettings settings, @Nullable String invalid) {
    }

    private static final KitsReply NO_KITS = new KitsReply(false, false, null, null);

    /** Reads the kit fields of a heartbeat reply. Missing or broken fields mean nothing was said; a bad {@code kits} object is refused whole. */
    public static KitsReply kitsReply(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return NO_KITS;
            JsonObject o = e.getAsJsonObject();
            boolean known = false, need = false;
            JsonElement n = o.get("needKitsReport");
            if (n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isBoolean()) {
                known = true;
                need = n.getAsBoolean();
            }
            KitSettings settings = null;
            String invalid = null;
            JsonElement k = o.get("kits");
            if (k != null && !k.isJsonNull()) {
                try {
                    settings = KitSettings.parse(k);
                } catch (KitSettings.Invalid ex) {
                    invalid = ex.getMessage();
                }
            }
            return new KitsReply(known, need, settings, invalid);
        } catch (RuntimeException ex) {
            return NO_KITS;
        }
    }

    /**
     * What a heartbeat reply says about repair passes. {@code known}: the reply has a {@code repairHash} key (the server supports passes). {@code settings}: a valid
     * {@code repairSettings} object, else null; {@code invalid} why one was refused. {@code run} / {@code stop}: the ids asked for (-1: none). {@code needReport}:
     * the server wants the report.
     */
    public record RepairReply(boolean known, @Nullable String hash, com.porter.qwertestash.stash.@Nullable RepairSettings settings, @Nullable String invalid, long run, long stop,
                              boolean needReport) {
    }

    private static final RepairReply NO_REPAIR = new RepairReply(false, null, null, null, -1, -1, false);

    private static long idOf(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || !e.getAsString().matches("[0-9]{1,15}")) return -1;
        return Long.parseLong(e.getAsString());
    }

    /** Reads the repair fields of a heartbeat reply. Missing or broken fields mean nothing was said; a bad settings object is refused whole. */
    public static RepairReply repairReply(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return NO_REPAIR;
            JsonObject o = e.getAsJsonObject();
            JsonElement h = o.get("repairHash");
            boolean known = h != null;
            String hash = hex40(h);
            com.porter.qwertestash.stash.@Nullable RepairSettings settings = null;
            String invalid = null;
            JsonElement s = o.get("repairSettings");
            if (s != null && !s.isJsonNull()) {
                try {
                    settings = com.porter.qwertestash.stash.RepairSettings.parse(s);
                } catch (com.porter.qwertestash.stash.RepairSettings.Invalid ex) {
                    invalid = ex.getMessage();
                }
            }
            JsonElement n = o.get("needRepairReport");
            boolean need = n != null && n.isJsonPrimitive() && n.getAsJsonPrimitive().isBoolean() && n.getAsBoolean();
            return new RepairReply(known, hash, settings, invalid, idOf(o.get("repairRun")), idOf(o.get("repairStop")), need);
        } catch (RuntimeException ex) {
            return NO_REPAIR;
        }
    }

    /**
     * What a heartbeat reply says about craft orders. {@code hash}: the server's hash of the stash's craft settings (null when the
     * reply has none: an older server, or its settings file couldn't be read). {@code settings}: a valid {@code craft} object, else
     * null. {@code invalid}: why a {@code craft} object was refused (null when there was none or it was fine). {@code recipesHash}:
     * the server's tableHash for the stash's version (null when not said).
     */
    public record CraftReply(@Nullable String hash, @Nullable CraftSettings settings, @Nullable String invalid, @Nullable String recipesHash) {
    }

    private static final CraftReply NO_CRAFT = new CraftReply(null, null, null, null);

    /** Reads the craft fields of a heartbeat reply. Missing or broken fields mean nothing was said; a bad {@code craft} object is refused whole. */
    public static CraftReply craftReply(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return NO_CRAFT;
            JsonObject o = e.getAsJsonObject();
            String hash = hex40(o.get("craftHash")), recipes = hex40(o.get("recipesHash"));
            CraftSettings settings = null;
            String invalid = null;
            JsonElement c = o.get("craft");
            if (c != null && !c.isJsonNull()) {
                try {
                    settings = CraftSettings.parse(c);
                } catch (CraftSettings.Invalid ex) {
                    invalid = ex.getMessage();
                }
            }
            return new CraftReply(hash, settings, invalid, recipes);
        } catch (RuntimeException ex) {
            return NO_CRAFT;
        }
    }

    private static @Nullable String hex40(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        String s = e.getAsString();
        return s.matches("^[0-9a-f]{40}$") ? s : null;
    }

    private static JsonArray boxArray(int[] box) {
        JsonArray a = new JsonArray();
        for (int v : box) a.add(v);
        return a;
    }

    /** An area object: {@code { box, dimension }}. */
    public static JsonObject areaObject(int[] box, String dimension) {
        JsonObject o = new JsonObject();
        o.add("box", boxArray(box));
        o.addProperty("dimension", dimension);
        return o;
    }

    /**
     * What a heartbeat reply says about the stash area. {@code hash}: the server's {@code areaHash} (null when absent: an older server).
     * {@code areaPresent}: the reply has an {@code area} key (an object, or null for "cleared"). {@code invalid}: why that area was refused.
     */
    public record AreaReply(@Nullable String hash, boolean areaPresent, AreaRules.@Nullable Parsed area, @Nullable String invalid) {
    }

    private static final AreaReply NO_AREA = new AreaReply(null, false, null, null);

    public static AreaReply areaReply(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return NO_AREA;
            JsonObject o = e.getAsJsonObject();
            String hash = hex40(o.get("areaHash"));
            if (!o.has("area")) return new AreaReply(hash, false, null, null);
            JsonElement a = o.get("area");
            if (a.isJsonNull()) return new AreaReply(hash, true, null, null);
            try {
                return new AreaReply(hash, true, AreaRules.parse(a), null);
            } catch (AreaRules.Invalid ex) {
                return new AreaReply(hash, true, null, ex.getMessage());
            }
        } catch (RuntimeException ex) {
            return NO_AREA;
        }
    }

    /** Whether a heartbeat reply says the server wants the index. Missing or broken means no. */
    public static boolean needsIndex(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            return e.isJsonObject() && e.getAsJsonObject().has("needIndex") && e.getAsJsonObject().get("needIndex").getAsBoolean();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** The rescan id of a heartbeat reply, or -1 when there is none. */
    public static long rescanId(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return -1;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("rescan") || o.get("rescan").isJsonNull()) return -1;
            return o.get("rescan").getAsLong();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    /** Whether a 409 body is the stash ownership refusal; the owner's name if so, "?" if it names none, else null. */
    public static @Nullable String ownerConflict(String body) {
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return null;
            JsonObject o = e.getAsJsonObject();
            if (!o.has("error") || !o.get("error").isJsonPrimitive() || !"stash name in use".equals(o.get("error").getAsString())) return null;
            if (o.has("owner") && o.get("owner").isJsonPrimitive()) return clamp(o.get("owner").getAsString(), 64);
            return "?";
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** At most {@code max} characters, cut at a word boundary and ending in an ellipsis when it was longer. */
    public static String clamp(@Nullable String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max - 1);
        if (cut < max / 2) cut = max - 1;
        return s.substring(0, cut).stripTrailing() + "…";
    }

    // Aggregating

    private static void add(Map<String, Agg> groups, Map<String, Info> infos, Item item, int depth, Function<String, @Nullable String> english) {
        Info found = infoOf(infos, item, english);
        groups.computeIfAbsent(found.key(), k -> new Agg(found)).count += item.count;
        if (depth >= MAX_DEPTH || item.contents == null) return;
        for (Item inner : item.contents) if (inner != null) add(groups, infos, inner, depth + 1, english);
    }

    private static Info infoOf(Map<String, Info> infos, Item item, Function<String, @Nullable String> english) {
        String key = clamp(item.indexKey(), MAX_TEXT);
        Info info = infos.get(key);
        if (info != null) return info;

        String en = english.apply(item.id);
        if (en == null || en.isBlank()) en = Item.prettyName(item.id);
        String name = item.customName && item.customText != null && !item.customText.isBlank() ? item.customText : en;
        List<String> details = new ArrayList<>();
        if (item.enchantLevels != null) {
            List<String> levels = new ArrayList<>(item.enchantLevels);
            levels.sort(null);
            for (String entry : levels) details.add(enchantment(entry));
        }
        if (item.potion != null) details.add(title(item.potion));
        boolean enchanted = item.enchants != null && !item.enchants.isEmpty();
        info = new Info(key, clamp("minecraft:" + item.id, MAX_TEXT), clamp(name, MAX_TEXT), clamp(en, MAX_TEXT), Math.max(1, item.maxStack), details, enchanted,
            durability(item.damage, item.maxDamage)[0], durability(item.damage, item.maxDamage)[1], item.hasMending(), clamp(item.matchKey(), MAX_TEXT));
        infos.put(key, info);
        return info;
    }

    /**
     * The shulkers[] group key of a shulker with something in it ("s:" and 10 hex digits of the SHA-1 of its item key and its
     * contents), or null for anything else. The same key addShulker files it under, so an order's {@code shulker} line finds it.
     */
    public static @Nullable String shulkerKey(Item item) {
        return shulkerKey(item, false);
    }

    /**
     * @param withoutName the key the shulker would have without its custom name: a packed shulker an order renamed is still
     *                    known by the key it was ordered by (when it had no name before)
     */
    public static @Nullable String shulkerKey(Item item, boolean withoutName) {
        if (!item.isShulker() || item.contents == null || item.contents.isEmpty()) return null;
        StringBuilder canon = new StringBuilder();
        int at = 0;
        boolean any = false;
        for (Item inner : item.contents) {
            int fallback = at++;
            if (inner == null) continue;
            int slot = inner.slot >= 0 ? inner.slot : fallback;
            if (slot > 26) continue;
            if (canon.length() > 0) canon.append(',');
            canon.append(slot).append(':').append(clamp(inner.indexKey(), MAX_TEXT)).append(':').append(inner.count);
            any = true;
        }
        if (!any) return null;
        return "s:" + sha1(clamp(withoutName ? item.keyWithoutName() : item.indexKey(), MAX_TEXT) + "|" + canon).substring(0, 10);
    }

    /** A top-level shulker with something in it, grouped with identical ones. */
    private static void addShulker(Map<String, Shulker> shulkers, Map<String, Info> infos, Item item, Function<String, @Nullable String> english) {
        if (!item.isShulker() || item.contents == null || item.contents.isEmpty()) return;
        List<int[]> slots = new ArrayList<>();
        List<String> keys = new ArrayList<>(), matches = new ArrayList<>();
        StringBuilder canon = new StringBuilder();
        int at = 0;
        for (Item inner : item.contents) {
            int fallback = at++;
            if (inner == null) continue;
            int slot = inner.slot >= 0 ? inner.slot : fallback;
            if (slot > 26) continue;
            Info innerInfo = infoOf(infos, inner, english);
            String k = innerInfo.key();
            slots.add(new int[] {slot, inner.count});
            keys.add(k);
            matches.add(innerInfo.match());
            if (canon.length() > 0) canon.append(',');
            canon.append(slot).append(':').append(k).append(':').append(inner.count);
        }
        if (slots.isEmpty()) return;

        Info info = infoOf(infos, item, english);
        String key = "s:" + sha1(info.key() + "|" + canon).substring(0, 10);
        Shulker sh = shulkers.get(key);
        if (sh == null) {
            sh = new Shulker(info, key);
            sh.slots.addAll(slots);
            sh.keys.addAll(keys);
            sh.matches.addAll(matches);
            shulkers.put(key, sh);
        }
        sh.count++;
    }

    // Names

    /** "sharpness:5" or "minecraft:sharpness:5" as "Sharpness V". */
    private static String enchantment(String entry) {
        int colon = entry.lastIndexOf(':');
        if (colon < 0) return title(entry);
        String name = entry.substring(0, colon);
        int sep = name.indexOf(':');
        if (sep >= 0) name = name.substring(sep + 1);
        try {
            return title(name) + level(Integer.parseInt(entry.substring(colon + 1)));
        } catch (NumberFormatException e) {
            return title(name);
        }
    }

    private static String level(int n) {
        String[] roman = {"", " I", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"};
        return n >= 1 && n < roman.length ? roman[n] : " " + n;
    }

    static String title(String path) {
        StringBuilder sb = new StringBuilder();
        for (String word : path.replace('/', '_').split("_")) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return sb.toString();
    }

    public static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
