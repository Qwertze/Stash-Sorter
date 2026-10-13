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

    /** Keep running after sorting, sorting again whenever a container changes. */
    public boolean stayOn = false;
    /** Seconds between drop-off checks in stay-on mode. */
    public int recheckSeconds = 30;

    /** Slots to carry stash items in, like "28-36". 1-9 hotbar, 10-36 inventory from the top left. Blank uses every empty slot. */
    public String carrySlots = "";
    /** Slots never to touch. */
    public String keepSlots = "";

    public boolean whisperSearch = false;
    public List<String> whitelist = new ArrayList<>();
    /** Whispers starting with this are searches. Blank treats every whitelisted whisper as a search. */
    public String prefix = "find";
}
