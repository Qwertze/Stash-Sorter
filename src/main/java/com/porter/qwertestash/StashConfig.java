package com.porter.qwertestash;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings, saved by the proxy as {@code plugins/config/qwerte-stash.json}. Changed with the {@code stash} command.
 */
public class StashConfig {
    public enum Mode { Tiers, Signs }

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
    /** Seconds between two signs put up, so it never looks like a build bot. */
    public int labelGapSeconds = 10;

    /** Keep running after sorting, sorting again whenever a container changes. */
    public boolean stayOn = false;
    /** Seconds between drop-off checks in stay-on mode. */
    public int recheckSeconds = 30;

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

    public boolean whisperSearch = false;
    public List<String> whitelist = new ArrayList<>();
    /** Whispers starting with this are searches. Blank treats every whitelisted whisper as a search. */
    public String prefix = "find";
}
