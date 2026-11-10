package com.porter.qwertestash.manager;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the dashboard shows for each of the plugin's settings (every public field of StashConfig): its label, help text,
 * section, limits and, where there is one, the {@code stash ...} command that changes it. The limits are the ones
 * StashCommand enforces; a setting with a command is changed by running it (so its side effects happen), one without is
 * set directly. Phase 4 adds specs for other scopes the same way.
 */
public final class SettingsSpec {
    private SettingsSpec() {
    }

    /** One setting. {@code command} is the command after "stash " with {@code {v}} for the value; null: set directly. */
    public static final class Spec {
        public final String label, help, section;
        public @Nullable Double min, max;
        public @Nullable String command;
        /** The command to run for a blank value, when that is a different one (like "carry clear"). */
        public @Nullable String blankCommand;
        /** A number that must be whole (the command takes an integer for a decimal field). */
        public boolean whole;
        public @Nullable String readOnly;
        public boolean hidden;
        /** A string (or each entry of a list) must match this. */
        public @Nullable Pattern pattern;
        public boolean nonBlank;
        public int maxLength = 1000;

        Spec(String label, String help, String section) {
            this.label = label;
            this.help = help;
            this.section = section;
        }

        Spec range(double min, double max) {
            this.min = min;
            this.max = max;
            return this;
        }

        Spec cmd(String command) {
            this.command = command;
            return this;
        }

        Spec blank(String command) {
            this.blankCommand = command;
            return this;
        }

        Spec whole() {
            this.whole = true;
            return this;
        }

        Spec locked(String reason) {
            this.readOnly = reason;
            return this;
        }

        Spec pattern(String regex) {
            this.pattern = Pattern.compile(regex);
            return this;
        }

        Spec nonBlank() {
            this.nonBlank = true;
            return this;
        }

        Spec length(int max) {
            this.maxLength = max;
            return this;
        }
    }

    /** The plugin's settings, in the order they are shown. Keyed by field name. */
    public static final Map<String, Spec> PLUGIN = new LinkedHashMap<>();

    private static Spec add(String field, String label, String section, String help) {
        Spec spec = new Spec(label, help, section);
        PLUGIN.put(field, spec);
        return spec;
    }

    static {
        String sorting = "Sorting";
        add("sorting", "Sorting", sorting, "Sorting is running (stash on/off).").cmd("{v}");
        add("sortItems", "Sort items", sorting, "Off is scan only: containers are read and kept up to date, nothing is moved.").cmd("sort {v}");
        add("mode", "Mode", sorting, "Sort by tier or by the signs on the containers.").cmd("mode {v}");
        add("stashName", "Stash name", sorting, "The stash this proxy works on. Each bot on the website needs its own name.").cmd("name {v}").nonBlank().length(64);
        add("stayOn", "Stay on", sorting, "Keep running after sorting, sorting again whenever a container changes.").cmd("stayOn {v}");
        add("tracking", "Tracking", sorting, "Keep saved stashes up to date and answer whisper searches, even while not sorting.").cmd("tracking {v}");

        String area = "Finding containers";
        add("radius", "Radius", area, "How far around the bot to look for new containers (blocks).").cmd("radius {v}").range(1, 64).whole();
        add("height", "Height", area, "How far above and below the bot to look (blocks).").cmd("height {v}").range(0, 32).whole();
        add("barrels", "Barrels", area, "Include barrels.").cmd("barrels {v}");

        String timing = "Timing and giving way";
        add("delayTicks", "Delay (ticks)", timing, "Ticks between inventory actions. Raise it if the server rejects clicks.").cmd("delay {v}").range(0, 40).whole();
        add("containerDelay", "Container Delay", timing, "Extra ticks of pause around opening containers: a container is not opened until this many ticks after the last one was closed, and the first click in a freshly opened one waits this long too. 0 is as fast as possible. A higher value is slower but gentler on the server; 2b2t can kick or rubber-band when containers are opened very quickly.").cmd("containerDelay {v}").range(0, 100).whole();
        add("settleTicks", "Settle (ticks)", timing, "Ticks to wait after opening a container before reading it.").range(0, 100).whole();
        add("quietSeconds", "Quiet time (s)", timing, "Seconds other modules must have been idle before sorting starts or carries on.").cmd("quiet {v}").range(0, 60).whole();
        add("reservePearlSlot", "Reserve pearl slot", timing, "Never carry things in the first hotbar slot: PearlPlus keeps its pearls there.").cmd("pearlSlot {v}");
        add("safeWalk", "Safe walking", timing, "Never parkour, jump off or fall more than one block while walking.").cmd("safewalk {v}");

        String watch = "Watching";
        add("watchAccess", "Watch chests", watch, "Notice when another player opens a chest of the stash and read it again.").cmd("watch {v}");
        add("accessRescanSeconds", "Watch delay (s)", watch, "Seconds a container must stay closed before it is read again.").cmd("watchDelay {v}").range(0, 60).whole();
        add("watchSigns", "Watch signs", watch, "Notice when a sign on a container changes.").cmd("watchSigns {v}");
        add("signSettleSeconds", "Sign delay (s)", watch, "Seconds a sign must be left alone before a change is acted on.").cmd("signDelay {v}").range(0, 60).whole();

        String signs = "Signs and labels";
        add("autoLabel", "Auto label", signs, "Signs mode: put up a sign for a new category on an empty container.").cmd("autoLabel {v}");
        add("unsignedOverflow", "Unsigned overflow", signs, "Signs mode: stacks with no room may go into an unsigned container with space.").cmd("unsignedOverflow {v}");
        add("kitSorting", "Kit sorting", signs, "Send shulkers that score as a kit (name, item mix, full, OP items; set on the website Kits tab) to the chest picked for them on the website. Off: kits are sorted like any shulker, and nothing about kits is reported.").cmd("kitSorting {v}");
        add("repairPass", "Repair mode", signs, "When idle, find low-durability items in the stash, repair the Mending ones with experience bottles from the stash and let sorting put them in their best chest; items without Mending go to the needs-repair chest picked on the website. Set up on the website's Repair panel. Off: nothing about repair passes is sent, no passes run and no needs-repair routing is done.").cmd("repairpass {v}");
        add("repairPassMaxItems", "Repair pass: items", signs, "The most items one repair pass takes.").range(1, 256).whole();
        add("repairPassMaxMinutes", "Repair pass: minutes", signs, "The most minutes one repair pass spends throwing bottles.").range(1, 120).whole();
        add("repairPassEveryMinutes", "Repair pass: gap (min)", signs, "The least minutes between two automatic repair passes.").range(0, 1440).whole();
        add("repairPassMaxBoxes", "Repair pass: boxes", signs, "The most unnamed shulker boxes one pass opens (0: none, whatever the website says).").range(0, 54).whole();
        add("repairPassMinGapManualMinutes", "Repair pass: gap for manual runs (min)", signs, "The least minutes between two repair passes, also for a manual run from the website or the command.").range(0, 1440).whole();
        add("repairPassMaxPerHour", "Repair pass: passes an hour", signs, "The most repair passes started in an hour, manual ones included (0: no limit).").range(0, 60).whole();
        add("repairMinReserve", "Repair: least bottle reserve", signs, "A pass keeps at least this many bottles in the stash, whatever the website's reserve is (the higher of the two counts; 0: the website's only).").range(0, 1000000).whole();
        add("kitOrders", "Kit orders", signs, "Take orders for kits of the website Kit Builder: complete copies are fetched, the others built from the stash and named. Needs kit sorting on.");
        add("kitRestock", "Kit restock", signs, "While idle, build copies of the Kit Builder templates, from unreserved stock (never crafted; experience bottles stay above the repair reserve), up to the keep count in the template chest. Restock only acts on kits whose keep is above 0 on the website, and keep defaults to 0, so nothing happens until you set a keep count on a kit there. Only the first 16 kits with a keep count (by id) are restocked. Needs kit orders on.");
        add("kitRestockPerHour", "Kit restock per hour", signs, "The most kits restock may start in an hour.").range(0, 60).whole();
        add("kitRestockMinEmpty", "Kit restock keeps empty shulkers", signs, "Restock leaves at least this many empty shulkers in the stash.").range(0, 54).whole();
        add("labelGapSeconds", "Label gap (s)", signs, "Seconds between two signs put up.").range(0, 3600).whole();

        String recheck = "Stay-on rechecks";
        add("recheckSeconds", "Recheck (s)", recheck, "Seconds between drop-off checks in stay-on mode.").cmd("recheck {v}").range(5, 3600).whole();
        add("smartRecheck", "Smart recheck", recheck, "Only look into drop-offs something can fill without opening them, less often while unchanged.").cmd("smartRecheck {v}");

        String shulkers = "Shulkers";
        add("repack", "Repack", shulkers, "Signs mode: take misplaced items out of mixed shulkers and pack loose items into matching ones.").cmd("repack {v}");
        add("newBulkAt", "New bulk shulker at (stacks)", shulkers, "Start a new bulk shulker once carrying this many stacks of one item (0 only tops up).").cmd("newBulkAt {v}").range(0, 27).whole();
        add("compact", "Compact", shulkers, "Pack a full shulker's worth of an item into one shulker.").cmd("compact {v}");
        add("compactTopUpAt", "Compact top-up at (stacks)", shulkers, "Top up a part-full bulk shulker once there are this many full stacks to add.").cmd("compactTopUpAt {v}").range(1, 26).whole();
        add("compactExclude", "Never compact", shulkers, "Item ids never compacted, like ender_pearl.").pattern("[a-z0-9_]{1,64}").length(64);
        add("splitDurability", "Split durability", shulkers, "Signs mode: keep used gear apart from gear at full durability.").cmd("splitDurability {v}");

        String slots = "Slots";
        add("carrySlots", "Carry slots", slots, "Slots to carry stash items in, like 28-36 (1-9 hotbar, 10-36 inventory). Blank uses every empty slot.")
            .cmd("carry {v}").blank("carry clear").pattern("[0-9, \\-]*").length(100);
        add("keepSlots", "Keep slots", slots, "Slots never to touch.").cmd("keep {v}").blank("keep clear").pattern("[0-9, \\-]*").length(100);

        String drop = "Auto drop";
        add("autoDrop", "Auto drop", drop, "Give back what a player dropped near the bot.").cmd("autoDrop {v}");
        add("autoDropRange", "Auto drop range", drop, "How far away (blocks) a player can be and still be given their item back.").cmd("autoDrop range {v}").range(1, 64).whole();

        String ground = "Ground items";
        add("collectGround", "Collect ground items", ground, "Pick up items lying in the stash area when no other player is in render distance.").cmd("collect {v}");
        add("collectRange", "Collect range", ground, "How far past the outermost containers (blocks) ground items are picked up.").cmd("collectRange {v}").range(0, 64).whole();

        String logging = "Logging";
        add("logLevel", "Log level", logging, "Quiet: step lines, errors and alerts. Normal: the usual messages. Debug: the reasons behind decisions.").cmd("log {v}");

        String search = "Whisper search";
        add("whisperSearch", "Whisper search", search, "Answer searches whispered by whitelisted players.").cmd("whisper {v}");
        add("whitelist", "Whitelist", search, "Players who may search and get alerts.").pattern("[A-Za-z0-9_]{1,16}").length(16);
        add("friends", "Friends", search, "Players (name or UUID) that do not count as someone near the bot: your own alts. They never hold back going back to a shulker or picking up ground items.").pattern("[A-Za-z0-9_-]{1,36}").length(36);
        add("prefix", "Prefix", search, "Whispers starting with this are searches. Blank treats every whitelisted whisper as a search.")
            .cmd("prefix {v}").blank("prefix clear").pattern("[A-Za-z0-9_.+\\-]*").length(32);

        String orders = "Orders";
        add("acceptOrders", "Accept orders", orders, "Take orders from the website: the bot builds shulkers from the stash and delivers them to the delivery container (`stash delivery`). Needs the Stash Manager on. Orders come before sorting.").cmd("orders {v}");
        add("useNamedShulkers", "Use named shulkers", orders, "Orders may deliver shulkers that have a custom name. Off: named shulkers are left alone.");
        add("freshMinutes", "Fresh for (min)", orders, "A container read within this many minutes is not read again when the sorter or an order starts, unless something marked it changed. 0: read everything again every time.").range(0, 30).whole();
        add("autoExpandDelivery", "Auto expand delivery storage", orders, "When every chest of the delivery storage is full, put another double chest up one block above the highest one (taken from the stash, or crafted when crafting is on) and keep filling the lowest chest with room. Off: an order waits for space. The chests' positions are not shown on the website.").cmd("delivery expand {v}");
        // The delivery container is set with `stash delivery` and its position is not shown on the website
        add("deliveryDimension", "Delivery dimension", orders, "Set with `stash delivery`.").locked("set with `stash delivery`").hidden = true;
        add("deliveryX", "Delivery X", orders, "Set with `stash delivery`.").locked("set with `stash delivery`").hidden = true;
        add("deliveryY", "Delivery Y", orders, "Set with `stash delivery`.").locked("set with `stash delivery`").hidden = true;
        add("deliveryZ", "Delivery Z", orders, "Set with `stash delivery`.").locked("set with `stash delivery`").hidden = true;
        // Nor is the anvil's, set with `stash anvil`
        add("anvilDimension", "Anvil dimension", orders, "Set with `stash anvil`.").locked("set with `stash anvil`").hidden = true;
        add("anvilX", "Anvil X", orders, "Set with `stash anvil`.").locked("set with `stash anvil`").hidden = true;
        add("anvilY", "Anvil Y", orders, "Set with `stash anvil`.").locked("set with `stash anvil`").hidden = true;
        add("anvilZ", "Anvil Z", orders, "Set with `stash anvil`.").locked("set with `stash anvil`").hidden = true;
        add("anvilSource", "Anvil source", orders, "Set with `stash anvil`, or found or placed by the bot.").locked("set with `stash anvil`, or found or placed by the bot").hidden = true;

        String crafting = "Crafting";
        add("enableCrafting", "Crafting", crafting, "Craft a plain shulker box from the stash (shells, a chest, planks, logs) when an order or compaction needs an empty one and there is none. Needs a crafting table (`stash craftingtable here`).").cmd("crafting {v}");
        add("craftMaxPerOrder", "Most per order", crafting, "Shulker boxes one order may have crafted.").range(0, 54).whole();
        add("craftMaxOpsPerTask", "Most grid fills per task", crafting, "Grid fills one craft task may make.").range(1, 512).whole();
        add("craftMaxPerHour", "Most per hour", crafting, "Shulker boxes crafted in any one hour.").range(0, 540).whole();
        add("craftOrders", "Craft orders", crafting, "Take orders with craft lines: items the stash can make from what it holds, crafted at the crafting table set for this stash on the website. Needs crafting on and a recipe table fetched from the server.").cmd("craftorders {v}");
        add("craftOrderMaxFills", "Most grid fills per craft order", crafting, "Grid fills one craft order may make. Sent to the server, which refuses bigger orders for this bot.").range(1, 100000).whole().cmd("craftorders maxfills {v}");
        add("craftOrderMaxPerHour", "Most craft order fills per hour", crafting, "Grid fills made for craft orders in any one hour.").range(0, 100000).whole().cmd("craftorders maxperhour {v}");
        add("craftNeverUse", "Never craft with", crafting, "Item ids never taken from the stash to craft with, like diamond_block.").pattern("[a-z0-9_]{1,64}").length(64);
        // The crafting table is set with `stash craftingtable` and its position is not shown on the website
        add("craftingTableDimension", "Crafting table dimension", crafting, "Set with `stash craftingtable`.").locked("set with `stash craftingtable`").hidden = true;
        add("craftingTableX", "Crafting table X", crafting, "Set with `stash craftingtable`.").locked("set with `stash craftingtable`").hidden = true;
        add("craftingTableY", "Crafting table Y", crafting, "Set with `stash craftingtable`.").locked("set with `stash craftingtable`").hidden = true;
        add("craftingTableZ", "Crafting table Z", crafting, "Set with `stash craftingtable`.").locked("set with `stash craftingtable`").hidden = true;
        add("craftingTableSource", "Crafting table source", crafting, "Set with `stash craftingtable`, or found, placed or crafted by the bot.").locked("set with `stash craftingtable`, or found, placed or crafted by the bot").hidden = true;

        String manager = "Stash Manager";
        add("manager", "Stash Manager", manager, "Send the stash index to the Stash Manager server and answer its rescans.").cmd("manager {v}");
        add("dashboard", "Dashboard", manager, "Let the Stash Manager website show and change these settings and run commands.").cmd("manager dashboard {v}");
        add("dashboardConsole", "Dashboard console", manager, "Let the website run console commands. Turn it on in the proxy terminal: `stash manager dashboard console on`.").locked("only the proxy terminal can change it");
        add("dashboardProxy", "Dashboard proxy settings", manager, "Let the website change the proxy's and other plugins' settings. Turn it on in the proxy terminal: `stash manager dashboard proxy on`.").locked("only the proxy terminal can change it");
        add("managerUrl", "Server address", manager, "Where the Stash Manager server is.").locked("change it with `stash manager url`");
        add("managerKey", "API key", manager, "The server's API key.").locked("secret");
        add("managerIncludeExcluded", "Include excluded", manager, "Also list containers excluded from sorting.").cmd("manager includeExcluded {v}");
        add("managerScanInterval", "Scan interval (s)", manager, "Seconds between checks of the stash for changes.").cmd("manager scanInterval {v}").range(2, 600).whole();
        add("managerBotId", "Bot id", manager, "Identifies this bot to the Stash Manager server.").locked("generated, not editable");
    }
}
