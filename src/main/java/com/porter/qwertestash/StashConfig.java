package com.porter.qwertestash;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings, saved by the proxy as {@code plugins/config/qwerte-stash.json}. Changed with the {@code stash} command.
 */
public class StashConfig {
    public enum Mode { Tiers, Signs }

    /** How much the sorter says: Quiet is step lines, errors and alerts only; Debug adds the detail behind every decision. */
    public enum LogLevel { Quiet, Normal, Debug }

    /** Sorting is running. */
    public boolean sorting = false;
    /** Keeping saved stashes up to date and answering whisper searches, even while not sorting. */
    public boolean tracking = true;

    public String stashName = "stash";
    public Mode mode = Mode.Tiers;
    /** How far around the bot to look for new containers. */
    public int radius = 8;
    /** How far above and below the bot to look. */
    public int height = 3;
    public boolean barrels = true;
    /** Ticks between inventory actions. Raise it if the server rejects clicks. */
    public int delayTicks = 3;
    /** Extra ticks of pause around opening containers: not opened until this long after the last was closed, and the first click in a new one waits this long too. 0 is as fast as possible. */
    public int containerDelay = 0;
    /** Ticks to wait after opening a container before reading it. */
    public int settleTicks = 6;

    /**
     * Seconds another module (pathing, inventory actions, an open container) must have been idle before sorting starts
     * or carries on. Sorting always gives way to other modules, this is how long it waits after they stop. Doubled for
     * each further interruption in a few minutes, up to a minute.
     */
    public int quietSeconds = 3;
    /** Never carry things in, or hold shulkers in, the first hotbar slot: that's where PearlPlus keeps its pearls. */
    public boolean reservePearlSlot = true;

    /**
     * Notice when another player opens a chest or barrel of the stash (the lid, from the server) and re-read it once
     * they're done, so what they put in or took out gets sorted.
     */
    public boolean watchAccess = true;
    /** Seconds a container must stay closed before it's re-read, so someone rummaging in it is only counted once. */
    public int accessRescanSeconds = 5;

    /**
     * Notice when a sign on a container is put up, changed or taken down, and sort by the new labels. A change is
     * acted on after the sign has been left alone this many seconds; a sign taken down (or left blank) for a minute.
     */
    public boolean watchSigns = true;
    public int signSettleSeconds = 10;

    /**
     * Signs mode: when an item has no container with a sign for it, and there is an empty unsigned container next to
     * the signed ones, put up a sign for a new category there (from signs the bot carries or finds in the stash) and
     * sort the item into it. Only ever places signs, never chests, and breaks nothing.
     */
    public boolean autoLabel = true;
    /**
     * Signs mode: stacks that nowhere they belong has room for may go into an unsigned container with space, after
     * the ones signed misc/other/overflow.
     */
    public boolean unsignedOverflow = true;
    /**
     * Kit sorting (1.14.0): a shulker box that scores as a kit (points for the word kit in its name, an item mix, all 27 slots used and an
     * OP item; the points and the threshold are set per stash on the Stash Manager website) goes to the chest picked for it there.
     * Off turns the whole feature off here: no kit routing, no reserved containers, no kit shulkers kept whole, nothing sent about kits.
     */
    public boolean kitSorting = true;
    /** Kit orders (1.16.0): take orders with kit lines (needs kit sorting on). */
    public boolean kitOrders = true;
    /** Repair passes (1.17.0, REPAIR-CONTRACT v2): off sends no repair keys, runs no passes and does no needs-repair routing. */
    public boolean repairPass = true;
    /** Items per pass, throwing minutes per pass, minutes between automatic passes, boxes opened per pass (0: none whatever the website says). */
    public int repairPassMaxItems = 64;
    public int repairPassMaxMinutes = 20;
    public int repairPassEveryMinutes = 30;
    public int repairPassMaxBoxes = 8;
    /** Local limits that also hold for manual runs: minutes between passes for a manual run, passes an hour, and a bottle reserve of at least this many (0: the website's only). */
    public int repairPassMinGapManualMinutes = 5;
    public int repairPassMaxPerHour = 4;
    public int repairMinReserve = 0;
    /**
     * Pearl travel (1.18.0, PEARL-CONTRACT v1.0): this bot may travel from its stash to a pearl station and back by whispering `load` to the station's loader
     * bot. Off by default: a trip moves the bot. Needs the Stash Manager on and PearlDrop in the proxy (the way back needs a pearl thrown at home).
     */
    public boolean pearlTravel = false;
    /** Pearl loader (1.18.0): with PearlPlus's auto load on, report the pearls of the travellers the owner approved to the Stash Manager server. */
    public boolean pearlLoader = true;
    /** Seconds a pearl that is not seen is still believed to be there after its owner (or this bot) came back into the world, and the least time a traveller is in the game before a trip. */
    public int pearlGraceSeconds = 45;
    /** Kit restock (1.16.0): while idle, build copies of templates with a keep count (needs kit orders on), up to {@code kitRestockPerHour} an hour. */
    public boolean kitRestock = true;
    public int kitRestockPerHour = 12;
    /** Restock leaves at least this many empty shulkers in the stash. */
    public int kitRestockMinEmpty = 2;

    /** Seconds between two signs put up, so it never looks like a build bot. */
    public int labelGapSeconds = 10;

    /** Keep running after sorting, sorting again whenever a container changes. */
    public boolean stayOn = false;
    /** Seconds between drop-off checks in stay-on mode. */
    public int recheckSeconds = 30;
    /**
     * Stay-on mode, smart recheck (the default): only drop-offs something can fill without opening them (a hopper,
     * dropper or dispenser next to or above them, or a chunk that isn't loaded so their lid can't be seen) are looked into,
     * each less often (up to every 5 minutes) while its contents stay unchanged; the others are read when their lid moves.
     * Off: every drop-off is looked into every {@code recheckSeconds}, as before.
     */
    public boolean smartRecheck = true;

    /**
     * Signs mode: take items that belong elsewhere out of mixed shulkers, and pack loose items into shulkers that
     * already hold only that item. Named shulkers, and shulkers when a sign asks for them whole (like "kits"), are
     * left as they are.
     */
    public boolean repack = true;
    /**
     * Start a new bulk shulker from an empty one once carrying this many stacks of one item. 0 only tops up existing
     * ones. Only used while compacting is off: with it on, new bulk shulkers are made by compacting.
     */
    public int newBulkAt = 9;

    /**
     * Signs mode: whenever a full shulker's worth of an item (27 stacks of it, anywhere in the stash) exists, pack it
     * into an empty shulker and put that back in the item's chest, and top up shulkers that are part full. Never makes
     * mixed shulkers. Named shulkers, kit chests and containers marked with {@code compactSkip} are left alone.
     */
    public boolean compact = true;
    /** Top up a part-full bulk shulker once there are this many full stacks to add (or enough to fill it). */
    public int compactTopUpAt = 9;
    /** Item ids never compacted, like {@code ender_pearl}. */
    public List<String> compactExclude = new ArrayList<>();

    /**
     * Signs mode: keep used gear (anything below full durability) apart from gear at full durability. A container whose
     * sign says "used" (like "Used Diamond Sword", or "Used" on a line of its own) takes only used items, every other
     * container only full ones, except misc/other/overflow, which take both. New signs for used items read "Used" and the
     * item's name. Compacting never packs used and full ones into the same shulker.
     */
    public boolean splitDurability = false;

    /** Slots to carry stash items in, like "28-36". 1-9 hotbar, 10-36 inventory from the top left. Blank uses every empty slot. */
    public String carrySlots = "";
    /** Slots never to touch. */
    public String keepSlots = "";

    /**
     * Give back what a player dropped near the bot: when the bot picks up an item a player threw, or left by breaking a
     * block, it looks at them and drops it again.
     */
    public boolean autoDrop = false;
    /** How far away (blocks) a player can be and still be given their item back. */
    public double autoDropRange = 16;

    /**
     * Pick up items lying on the ground in the stash area (the containers' bounding box widened by {@code collectRange}
     * blocks sideways and 4 up and down) that the bot did not drop or break out of a block itself, when no other player
     * is in render distance, and sort them like everything else.
     */
    public boolean collectGround = true;
    /** How far past the outermost containers (blocks, sideways) ground items are picked up. */
    public int collectRange = 8;

    /**
     * Move items: take, deposit, repack, compact, label and collect from the ground. Off is scan only: containers are
     * still read, kept up to date and searchable, but nothing is ever moved.
     */
    public boolean sortItems = true;

    /**
     * Safe walking: while the sorter walks it never parkours, jumps off or falls more than one block (an ordinary step
     * down is fine), and it holds sneak when standing at the edge of a drop of two blocks or more.
     */
    public boolean safeWalk = true;

    /**
     * What the sorter writes to the terminal. Quiet: step lines ("what now, what next"), errors and alerts (things that
     * need a person, also whispered to the whitelisted players). Normal: those and the usual messages. Debug: and the
     * reasons behind every decision. What quiet and normal leave out still goes to the debug log.
     */
    public LogLevel logLevel = LogLevel.Normal;

    public boolean whisperSearch = false;
    public List<String> whitelist = new ArrayList<>();
    /** Players (names, or UUIDs) that do not count as someone else near the bot, like the owner's own alts. Empty by default. Not the whisper whitelist. */
    public List<String> friends = new ArrayList<>();
    /** Whispers starting with this are searches. Blank treats every whitelisted whisper as a search. */
    public String prefix = "find";

    /**
     * Stash Manager: pushes an index of the saved stash to the Stash Manager web server on the local network, keeps a
     * heartbeat going and re-reads the containers when the website asks for a rescan. Works with sorting off.
     */
    public boolean manager = false;
    public String managerUrl = "http://127.0.0.1:8765";
    /** The server's API key. Never shown in command output or logs. */
    public String managerKey = "";
    /** Also list containers excluded from sorting. */
    public boolean managerIncludeExcluded = false;
    /** Seconds between checks of the stash for changes. Only changes are sent. */
    public int managerScanInterval = 10;
    /**
     * With the manager on, also keep a link to the Stash Manager website's dashboard: it shows these settings and the
     * console there and applies changes made on the website. Every connection is opened by this plugin.
     */
    public boolean dashboard = true;
    /**
     * The website may run console commands here. Off unless turned on in the proxy terminal: it can not be changed from the
     * dashboard, Discord or in game, nor through a multi command from there.
     */
    public boolean dashboardConsole = false;
    /** The website may change the proxy settings and other plugins settings. Same rule as dashboardConsole. */
    public boolean dashboardProxy = false;
    /** Identifies this bot to the Stash Manager server. Generated the first time the dashboard link starts. */
    public String managerBotId = "";

    // Stash orders (the website's order page). New in 1.6.0; every key has a default so older settings files load as before.

    /** Take orders from the Stash Manager website: build shulkers from the stash and deliver them to the delivery container. */
    public boolean acceptOrders = false;
    /** The delivery container: where finished shulkers are put. Set with `stash delivery`. deliveryDimension is blank when none is set. */
    public String deliveryDimension = "";
    public int deliveryX, deliveryY, deliveryZ;
    /** Orders may take from shulkers with a custom name, and use empty named ones as bases. Off: named shulkers are left alone. */
    public boolean useNamedShulkers = true;
    /** A container read within this many minutes is not read again when the sorter or an order starts, unless something marked it changed. 0: always read again. */
    public int freshMinutes = 5;

    // Shulker names for orders. New in 1.7.0; the keys have defaults so older settings files load as before.

    /**
     * The anvil order shulkers are named at: set with `stash anvil`. anvilDimension is blank when none is set. It is not a
     * container (never sorted or indexed), and may be outside the stash area.
     */
    public String anvilDimension = "";
    public int anvilX, anvilY, anvilZ;
    /**
     * How the anvil above came to be (1.8.1): "set" with `stash anvil` (also blank, for settings from before), "found" by the
     * bot standing in the stash area, or "placed" by the bot from an anvil in the stash.
     */
    public String anvilSource = "";

    // Delivery storage (1.9.0). Off by default; older settings files load as before.

    /**
     * When every chest of the delivery storage is full, put another double chest up one block above the highest one (taken from
     * the stash, or crafted when crafting is on), and fill the lowest chest with room first. Off: an order waits for space.
     */
    public boolean autoExpandDelivery = false;

    // Crafting (1.8.0, phase 1: plain shulker boxes for orders and compaction). Every key has a default so older settings files
    // load as before, with crafting off.

    /** Craft a plain shulker box when an order or compaction needs an empty one and the stash has none. */
    public boolean enableCrafting = false;
    /** Shulker boxes one order may have crafted. */
    public int craftMaxPerOrder = 27;
    /** Grid fills one craft task may make (a shulker box from logs is 1 + 1 + 2 fills). */
    public int craftMaxOpsPerTask = 128;
    /** Shulker boxes crafted in any one hour. */
    public int craftMaxPerHour = 54;
    /** Item ids never taken from the stash to craft with, like diamond_block. */
    public List<String> craftNeverUse = new ArrayList<>();
    /** The crafting table: set with `stash craftingtable`. craftingTableDimension is blank when none is set. Never sorted or indexed. */
    public String craftingTableDimension = "";
    public int craftingTableX, craftingTableY, craftingTableZ;
    /** How the crafting table came to be (1.10.0): "set" (blank reads as set), "found", "placed" or "crafted". */
    public String craftingTableSource = "";

    // Crafting from the stash (1.15.0, CRAFT-CONTRACT.md): craft lines of orders, crafted at the crafting table the website sets for the
    // stash. It needs crafting on (enableCrafting) and a recipe table fetched from the server.

    /** Take orders with craft lines. Needs crafting on; the heartbeat only claims it with a recipe table and this on. */
    public boolean craftOrders = true;
    /** Grid fills one order may make (the most the bot tells the server it crafts for one order). */
    public int craftOrderMaxFills = 2048;
    /** Grid fills made for craft orders in any one hour. */
    public int craftOrderMaxPerHour = 4096;
    /** Crafting tables the bot put down for craft orders (when, milliseconds), and for which order and how many (CraftTableCap). */
    public List<Long> craftTablePlacedAt = new ArrayList<>();
    public String craftTableOrder = "";
    public int craftTableOrderCount;
}
