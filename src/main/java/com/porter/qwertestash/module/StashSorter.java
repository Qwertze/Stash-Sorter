package com.porter.qwertestash.module;

import com.aquarius.cache.data.entity.Entity;
import com.aquarius.cache.data.entity.EntityLiving;
import com.aquarius.cache.data.inventory.Container;
import com.aquarius.event.client.ClientBotTick;
import com.aquarius.event.client.ClientDisconnectEvent;
import com.aquarius.feature.inventory.InventoryActionRequest;
import com.aquarius.feature.inventory.actions.ClickItem;
import com.aquarius.feature.inventory.actions.CloseContainer;
import com.aquarius.feature.inventory.actions.InventoryAction;
import com.aquarius.feature.inventory.actions.MoveToHotbarSlot;
import com.aquarius.feature.inventory.actions.RenameItem;
import com.aquarius.feature.inventory.actions.SetHeldItem;
import com.aquarius.feature.inventory.actions.ShiftClick;
import com.aquarius.feature.pathfinder.PathingRequestFuture;
import com.aquarius.feature.pathfinder.goals.GoalBlock;
import com.aquarius.feature.pathfinder.goals.GoalNear;
import com.aquarius.feature.pathfinder.goals.GoalXZ;
import com.aquarius.feature.player.ClickResult;
import com.aquarius.feature.player.ClickTarget;
import com.aquarius.feature.player.Input;
import com.aquarius.feature.player.InputRequest;
import com.aquarius.feature.player.InputRequestFuture;
import com.aquarius.feature.player.World;
import com.aquarius.mc.block.BlockPos;
import com.aquarius.mc.block.Direction;
import com.aquarius.mc.block.properties.api.BlockStateProperties;
import com.aquarius.mc.item.ItemData;
import com.aquarius.mc.item.ItemRegistry;
import com.aquarius.module.impl.AbstractFieldModule;
import com.aquarius.network.codec.PacketHandlerCodec;
import com.aquarius.network.codec.PacketHandlerStateCodec;
import com.aquarius.util.RequestFuture;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.StashConfig.Mode;
import com.porter.qwertestash.stash.Categories;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.orders.AnvilNamer;
import com.porter.qwertestash.orders.BaseSlots;
import com.porter.qwertestash.orders.BinTracker;
import com.porter.qwertestash.orders.ClickPlanner;
import com.porter.qwertestash.orders.CraftBatch;
import com.porter.qwertestash.orders.CraftCharge;
import com.porter.qwertestash.orders.CraftResume;
import com.porter.qwertestash.orders.CraftGrid;
import com.porter.qwertestash.orders.CraftHealth;
import com.porter.qwertestash.orders.CraftLedger;
import com.porter.qwertestash.orders.CraftOrder;
import com.porter.qwertestash.orders.CraftPlanner;
import com.porter.qwertestash.orders.CraftPool;
import com.porter.qwertestash.orders.CraftSlots;
import com.porter.qwertestash.orders.CraftSub;
import com.porter.qwertestash.orders.ChestStack;
import com.porter.qwertestash.orders.CraftTrip;
import com.porter.qwertestash.orders.Codes;
import com.porter.qwertestash.orders.EnchantCarry;
import com.porter.qwertestash.orders.EnchantGuard;
import com.porter.qwertestash.orders.EnchantItems;
import com.porter.qwertestash.orders.EnchantLedger;
import com.porter.qwertestash.orders.EnchantOrder;
import com.porter.qwertestash.orders.EnchantPreflight;
import com.porter.qwertestash.orders.EnchantState;
import com.porter.qwertestash.orders.EnchantTrip;
import com.porter.qwertestash.orders.GearStow;
import com.porter.qwertestash.orders.DeliveryBook;
import com.porter.qwertestash.orders.DeliveryRoom;
import com.porter.qwertestash.orders.Recipes;
import com.porter.qwertestash.orders.Order;
import com.porter.qwertestash.orders.Repair;
import com.porter.qwertestash.orders.RepairRun;
import com.porter.qwertestash.orders.RepairTrip;
import com.porter.qwertestash.orders.SourcePicker;
import com.porter.qwertestash.orders.SourceRules;
import com.porter.qwertestash.orders.BinStep;
import com.porter.qwertestash.orders.OrderFlow;
import com.porter.qwertestash.orders.RepairMemory;
import com.porter.qwertestash.orders.RepairPassGate;
import com.porter.qwertestash.orders.RepairPassReport;
import com.porter.qwertestash.orders.RepairRules;
import com.porter.qwertestash.stash.RepairSettings;
import com.porter.qwertestash.stash.RepairStore;
import com.google.gson.JsonObject;
import com.porter.qwertestash.orders.StockPlanner;
import com.porter.qwertestash.orders.Unit;
import com.porter.qwertestash.stash.AnvilFinder;
import com.porter.qwertestash.stash.AnvilSpot;
import com.porter.qwertestash.stash.Containers;
import com.porter.qwertestash.stash.AreaPending;
import com.porter.qwertestash.stash.AreaRules;
import com.porter.qwertestash.stash.AreaSync;
import com.porter.qwertestash.stash.CraftBounds;
import com.porter.qwertestash.stash.CraftSettings;
import com.porter.qwertestash.stash.CraftTableCap;
import com.porter.qwertestash.stash.CraftTableState;
import com.porter.qwertestash.stash.CraftStore;
import com.porter.qwertestash.stash.CraftingTableFinder;
import com.porter.qwertestash.stash.CraftingTableSpot;
import com.porter.qwertestash.stash.DeliverySpot;
import com.porter.qwertestash.stash.GoneBoxes;
import com.porter.qwertestash.stash.GoneWatch;
import com.porter.qwertestash.stash.PlaceSearch;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.KitRouting;
import com.porter.qwertestash.stash.KitSettings;
import com.porter.qwertestash.stash.KitStore;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.StashIndex;
import com.porter.qwertestash.stash.Stashes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.value.ChestValue;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.value.ChestValueType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockEventPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundSignUpdatePacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.INPUTS;
import static com.aquarius.Globals.MODULE_LOG;
import static com.aquarius.Globals.INVENTORY;import static com.aquarius.Globals.MODULE;
import static com.github.rfresh2.EventConsumer.of;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * Sorts a saved stash of chests and barrels, either into tiers (shulkers first, then stackables, then singles) or into
 * whichever container's signs best describe each item. The stash is scanned once and saved, then kept up to date by
 * {@link StashTracker}. Only runs while nobody is controlling the bot: it pauses while you're connected.
 * <p>
 * It never interrupts other modules. Nothing is started while another module is pathing, running inventory actions or
 * has a container open, until it has been quiet for {@code quietSeconds}; if one starts in the middle of a job, the
 * sorter lets go of its window, path and clicks and waits (see {@link Activity}). Its inventory requests are at the
 * lowest priority, so PearlPlus and the like go first.
 */
public class StashSorter extends AbstractFieldModule {
    // Generous limits, 2b2t can be laggy
    private static final int OPEN_TRIES = 6;
    private static final int WALK_TIMEOUT = 20 * 60;
    private static final int CLICK_TIMEOUT = 20 * 5;
    // Opening a container must not go on forever even if every try is answered with the wrong window
    private static final int OPEN_TIMEOUT = 20 * 30;
    // Stack-on-the-cursor recoveries allowed per job, and per attempt to close a container
    private static final int MAX_RECOVERIES = 3;

    // Below every other module's inventory requests (PearlPlus 1000, AutoMend 4000 and up), so theirs go first and can
    // replace one of ours that hasn't started. AbstractFieldModule's own 3000 would do the opposite
    private static final int SORTER_PRIORITY = 1;
    // Requests for one container that fail before it is skipped as unreachable
    private static final int MAX_BOX_FAILURES = 3;
    // Inventory index of the slot PearlManager moves pearls to: MoveToHotbarAction.SLOT_1, the first hotbar slot
    private static final int PEARL_SLOT = 0;
    // Giving way: how long we try to close our own window (counted while the proxy refuses our requests); the longest
    // quiet time we ask of others; the window that interruptions are counted over; how many one job takes before it's
    // planned again; and the gaps between the messages about waiting
    private static final int CLEANUP_TIMEOUT = 20 * 10;
    private static final int MAX_QUIET_TICKS = 20 * 60;
    private static final int INTERRUPTION_WINDOW = 20 * 60 * 5;
    private static final int MAX_JOB_INTERRUPTIONS = 5;
    private static final int REASON_REPEAT_TICKS = 20 * 30;
    private static final int REMINDER_TICKS = 20 * 60 * 10;
    // A window this soon after our click on a container, with nobody else active, is the answer to it
    private static final int OPEN_ANSWER_MS = 5000;
    // News of our own opening and closing of a container can arrive this late; and a container we read isn't re-read
    // because of another player's visit for this long
    private static final int OWN_WINDOW_GRACE_MS = 10_000;
    private static final int REREAD_COOLDOWN_MS = 60_000;

    private enum Job { Survey, Take, Deposit, Dump, Fetch, Clean, Fill, Compact, FetchSigns, Label, Gather, Ground, Explore, Unpack, Deliver, Name, Craft, Anvil, Chest, Repair, Enchant }

    private enum Step { Walking, Opening, Working }

    // Repacking a shulker in the world: put it down, open it, move items, break it, pick it back up. Return is the
    // walk back to it after giving way
    private enum Handle { Hold, Place, Open, Work, Break, Collect, Return, Leave }

    // Running: sorting. Yielding: someone else started something, we're letting go of our window, path and clicks.
    // Waiting: letting go is done, waiting for them to finish and be quiet for a while
    private enum Gate { Running, Yielding, Waiting }

    private static final int HANDLE_TIMEOUT = 20 * 10;
    private static final int COLLECT_TIMEOUT = 20 * 120;

    private int wantSlot = -1;
    private int handledSlot;
    private @Nullable String fillId;
    private @Nullable String mainId;
    private volatile @Nullable Handle handle;
    private int handleTicks;
    private @Nullable BlockPos placed;
    private int swapBackFrom = -1, swapBackTo = -1;
    // What the shulker count is measured against to tell the shulker in the world was put down, and picked up again
    private final ShulkerPickup.Watch pickupWatch = new ShulkerPickup.Watch();
    private boolean repackFailed;
    private final Set<String> packTried = new HashSet<>();
    // What the shulker being fetched has to be, checked against the live window before it is taken
    private Predicate<Item> fetchWants = it -> it.isShulker() && !it.customName;
    // How many more stacks the gathering trip in hand may take
    private int gatherLeft;
    private @Nullable ItemData placedItem;
    // Carry slots whose items we put there ourselves from the stash. Anything the bot owns that looks like gear, food,
    // totems or pearls and wasn't put there by us is the bot's own, never carried, packed or put away
    private final Map<Integer, String> stashHeld = new ConcurrentHashMap<>();

    private @Nullable Stash stash;
    private final Set<Integer> carry = new LinkedHashSet<>();

    // Tiers mode: which boxes each tier fills
    private final Map<Integer, List<Box>> tierBoxes = new HashMap<>();

    private volatile @Nullable Job job;
    private @Nullable Box target;
    private volatile Step step = Step.Walking;
    private int stepTicks;
    private int tries;
    private int wait;
    private int clickTicks;
    private int moved;
    private @Nullable GoalNear goal;

    // What we're doing with the shared inventory queue, Baritone and window, and what others are doing with them
    private final Activity activity = new Activity();
    private volatile Gate gate = Gate.Running;

    // Swapping a carried stack with one in the container: carried stack to the cursor, cursor onto the slot, what was
    // there into the carry slot. The cursor is checked against what each click should have left on it
    private int swapStep;
    private int swapCarry, swapSlot;
    private @Nullable Item swapStack, swapDisplaced;
    private int recoveries;
    private int closeTries;

    // Finishing a job takes several ticks when a stack has to be put away from the cursor first
    private boolean finishing;
    private int finishTicks;

    // Opening: which window is ours. Only one that opened after our own click, of the size this box has
    private long openRequestedAt = Long.MAX_VALUE;
    private int openIdBefore;
    private long lastRejectedAt;
    private boolean targetOpen;
    private boolean cursorReported;
    // Ticks seen, and the tick before which the cursor isn't looked at again after a recovery click: the proxy predicts
    // that click locally and the server's answer arrives later
    private volatile long tickCount;
    private long settleUntil;

    // Containers where the last job got nothing done, skipped until something moves somewhere else
    private final Set<Box> stalled = new HashSet<>();
    private final Set<Box> compacted = new HashSet<>();
    // Failed requests per container, kept across giving way and re-planning; cleared when one opens, and on a restart
    private final Map<Box, Integer> boxFailures = new HashMap<>();
    private int actions;

    // Stay-on mode
    private boolean idle;
    private int idleTicks;
    private int seenVersion;

    // Giving way to other modules
    private volatile long lastForeignTick = Long.MIN_VALUE / 4;
    private volatile Activity.@Nullable Reason lastReason;
    private volatile boolean foreignNow;
    // Since when we've been held up (-1 if we aren't), and what we last said about it
    private long waitStart = -1;
    private long reasonLoggedAt;
    private Activity.@Nullable Reason reasonLogged;
    private long reminderAt;
    private final ArrayDeque<Long> interruptions = new ArrayDeque<>();
    private int jobInterruptions;
    private int cleanupRejected;
    private boolean cleanupGaveUp;
    // When we last clicked on a container to open it (wall clock), whatever job that was for, and whether another module
    // has been seen doing anything since. A window that opens soon after, with nobody else active, is the answer to it
    private volatile long lastOpenClickAt;
    private volatile @Nullable BlockPos lastOpenClickPos;
    // Containers another player used and has left, handed over by StashTracker from the network thread
    private final Queue<BlockPos> staleBoxes = new ConcurrentLinkedQueue<>();
    // When our own window for each container was last seen open, to tell our openings from other players'
    private final Map<Box, Long> ownWindowAt = new ConcurrentHashMap<>();
    // Visits by other players held back because we had just read that container, and when they ended
    private final Map<Box, Long> deferredAccess = new ConcurrentHashMap<>();
    private boolean foreignSinceClick;
    private boolean cursorStuckLogged;
    // The handle step to go back to once we're back at the shulker
    private @Nullable Handle returnTo;

    // Logging. Quiet shows step lines, errors and alerts; Normal also today's info and warnings; Debug also the reasons,
    // as info lines marked [debug]. What a level hides is written at debug level, so the debug log keeps it

    private static StashConfig.LogLevel logLevel() {
        StashConfig.LogLevel level = CONFIG.logLevel;
        return level == null ? StashConfig.LogLevel.Normal : level;
    }

    private static boolean quietLog() {
        return logLevel() == StashConfig.LogLevel.Quiet;
    }

    private static boolean debugLog() {
        return logLevel() == StashConfig.LogLevel.Debug;
    }

    @Override
    public void info(String msg) {
        if (quietLog()) MODULE_LOG.debug("{}{}", moduleLogPrefix, msg);
        else super.info(msg);
    }

    @Override
    public void info(String msg, Object... args) {
        if (quietLog()) MODULE_LOG.debug(moduleLogPrefix + msg, args);
        else super.info(msg, args);
    }

    @Override
    public void warn(String msg) {
        if (quietLog()) MODULE_LOG.debug("{}{}", moduleLogPrefix, msg);
        else super.warn(msg);
    }

    @Override
    public void warn(String msg, Object... args) {
        if (quietLog()) MODULE_LOG.debug(moduleLogPrefix + msg, args);
        else super.warn(msg, args);
    }

    @Override
    public void debug(String msg) {
        if (debugLog()) super.info("[debug] " + msg);
        else if (!quietLog()) super.debug(msg);
    }

    @Override
    public void debug(String msg, Object... args) {
        if (debugLog()) super.info("[debug] " + msg, args);
        else if (!quietLog()) super.debug(msg, args);
    }

    /**
     * A debug line whose text is only built when it is going to be written: shown at debug level, in the debug log
     * when the proxy writes one, nowhere when quiet.
     */
    private void debug(Supplier<String> text) {
        if (quietLog()) return;
        if (debugLog()) MODULE_LOG.info("{}[debug] {}", moduleLogPrefix, text.get());
        else if (debugLogWritten()) MODULE_LOG.debug("{}{}", moduleLogPrefix, text.get());
    }

    // Debug lines that are said once for each key until sorting starts over
    private final Set<String> debugSaid = new HashSet<>();

    private void debugOnce(String key, Supplier<String> text) {
        if (quietLog() || !(debugLog() || debugLogWritten()) || !debugSaid.add(key)) return;
        debug(text);
    }

    private static boolean debugLogWritten() {
        try {
            return com.aquarius.Globals.CONFIG.debug.debugLogs;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Something that needs a person: always shown, and whispered to the whitelisted players (once per text in 10
     * minutes, one whisper in 5 seconds, never while the bot is offline).
     */
    private void alert(String msg, Object... args) {
        String text = org.slf4j.helpers.MessageFormatter.arrayFormat(msg, args).getMessage();
        MODULE_LOG.warn("{}{}", moduleLogPrefix, text);
        Alerts.raise(this, "Stash Sorter: " + text);
    }

    /**
     * An alert for something that was already said as an error: only the whisper is added.
     */
    private void alertDm(String text) {
        Alerts.raise(this, "Stash Sorter: " + text);
    }

    // Step lines: what the sorter does now and what comes next, shown at every log level and never twice in a row
    private String lastStep = "";
    private final Map<String, long[]> stepProgress = new HashMap<>();

    private void step(String text) {
        String line = "Stash: " + text;
        if (line.equals(lastStep)) return;
        lastStep = line;
        com.porter.qwertestash.manager.ActivityFeed.INSTANCE.event("Stash Sorter", "info", text);
        MODULE_LOG.info("{}{}", moduleLogPrefix, line);
    }

    /**
     * Progress lines: one every 10% or every 60 seconds, whichever comes first.
     */
    private void stepProgress(String key, int done, int total, Supplier<String> text) {
        long now = System.currentTimeMillis();
        long bucket = total <= 0 ? 0 : (long) done * 10 / total;
        long[] last = stepProgress.get(key);
        if (last != null && last[0] == bucket && now - last[1] < 60_000) return;
        stepProgress.put(key, new long[] {bucket, now});
        step(text.get());
    }

    private static String xyz(BlockPos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }

    /**
     * A container by its sign if it has one, else by what it is.
     */
    private static String boxName(Box box) {
        return box.labels.isEmpty() ? blockName(box) : "\"" + box.labels.getFirst() + "\"";
    }

    private @Nullable Box nearestTo(Box from, Collection<Box> list) {
        return list.stream().min(Comparator.comparingDouble(b -> distSq(from.pos, b.pos))).orElse(null);
    }

    /**
     * The step line for a job on a container that is about to start. Only side-effect-free queries.
     */
    private void stepJob(Job next, Box box) {
        switch (next) {
            case Survey -> {
                int total = (int) stash.boxes.stream().filter(b -> !b.excluded).count();
                int read = (int) stash.boxes.stream().filter(b -> !b.excluded && b.known() && !b.stale).count();
                stepProgress("read", read, total, () -> "reading containers: " + read + "/" + total + "; next: " + blockName(box) + " at " + xyz(box.pos) + ".");
            }
            case Take -> {
                int stacks = 0;
                Set<Box> places = new LinkedHashSet<>();
                for (Item item : box.contents) {
                    if (item == null || !misplaced(item, box)) continue;
                    stacks++;
                    places.addAll(destinationsFor(item));
                }
                places.remove(box);
                Box nearest = nearestTo(box, places);
                step("sorting: taking " + stacks + " stacks from " + boxName(box) + " at " + xyz(box.pos) + "; next: put them away ("
                    + (nearest == null ? "wherever there is room)" : places.size() + " containers, nearest " + xyz(nearest.pos) + ")."));
            }
            case Deposit -> {
                int stacks = 0;
                Set<Box> others = new LinkedHashSet<>();
                for (int slot : looseCarried()) {
                    List<Box> homes = destinationsFor(inventoryItem(slot));
                    if (homes.contains(box)) stacks++;
                    else others.addAll(homes.stream().filter(b -> b.free() > 0 || misplacedCount(b) > 0).toList());
                }
                Box source = nearest(stash.boxes.stream().filter(b -> b != box && b.sortable() && !b.dropOff && takeableSlot(b) != -1).toList());
                step("sorting: putting " + stacks + " stacks into " + boxName(box) + " at " + xyz(box.pos) + "; next: " + others.size() + " more stops, then "
                    + (source == null ? "look for more." : "take from " + xyz(source.pos) + "."));
            }
            default -> {
            }
        }
    }

    @Override
    public boolean enabledSetting() {
        return CONFIG.sorting || driven;
    }

    // Driven by the Stash Manager (the website's rescan): the module runs without being turned on, in a read-only mode. It
    // explores the area and reads unread and stale containers, nothing else: no taking, depositing, compacting,
    // labelling, ground pickup, resuming a shulker or carrying. It switches itself off when nothing is left to read.
    // Turning sorting on or off takes over from it (see takeOver).
    private volatile boolean driven;
    private volatile @Nullable String driveProblem;

    /** Driven by the Stash Manager and sorting is not on. */
    private boolean readOnly() {
        return driven && !CONFIG.sorting;
    }

    /** Items are moved: sorting is on and sort-items too. Driven reads never move anything. */
    private boolean sorts() {
        return CONFIG.sortItems && !readOnly();
    }

    public boolean isDriven() {
        return driven;
    }

    /** Why the last driven read stopped before it could start, or null. */
    public @Nullable String driveProblem() {
        return driveProblem;
    }

    /**
     * For the Stash Manager, from the bot tick: starts or stops the read-only loop. Starting does nothing while sorting is
     * on (it reads by itself then). Stopping lets go of everything exactly as turning the module off does.
     */
    public void drive(boolean want) {
        if (want) {
            if (driven || CONFIG.sorting) return;
            driveProblem = null;
            driven = true;
            syncEnabledFromConfig();
        }
        else if (driven) {
            driven = false;
            syncEnabledFromConfig();
        }
    }

    /**
     * Sorting is about to be turned on or off by the user: a driven run ends first, so that turning it on starts a full
     * run (module off, then on again) and turning it off leaves nothing running.
     */
    public void takeOver(boolean on) {
        if (!driven) return;
        driven = false;
        if (on) disable();
    }

    /** A driven run is over: the module switches itself off. */
    private void endDrive() {
        driven = false;
        syncEnabledFromConfig();
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            // After the other modules (default 0, Baritone included) have had their tick, so what they started this tick
            // is visible to us, and before the inventory queue (-5000) sends our clicks
            of(ClientBotTick.class, -1000, this::onTick),
            of(ClientBotTick.Starting.class, e -> restart()),
            // A player taking the account over stops the bot's ticks: a shulker being collected is left where it is for now
            of(ClientBotTick.Stopped.class, e -> collectInterrupted(playerControlling() ? "a player took control of the bot" : "the bot's ticks stopped")),
            of(ClientDisconnectEvent.class, e -> {
                disconnecting = true;
                restart();
            })
        );
    }

    /** A player is connected to the proxy and controlling the account. */
    private static boolean playerControlling() {
        try {
            return com.aquarius.Proxy.getInstance().hasActivePlayer();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // The shulker of ours broken last, where and when (ms) its spot went clear: what drops next to it right after is its drop
    private ShulkerPickup.@Nullable Break lastBreak;

    /** The spot of the shulker in the world went clear: whatever drops next to it now is ours. */
    private void noteBreak(BlockPos at) {
        ItemData item = placedItem;
        lastBreak = new ShulkerPickup.Break(item != null ? item.name() : null, at.x(), at.y(), at.z(), System.currentTimeMillis());
    }

    /** The colours being collected (or recovered) off the ground now, {@link ShulkerPickup#ANY} for an unknown one. */
    private Set<String> collectingNow() {
        if (handle != Handle.Collect || placed == null) return Set.of();
        ItemData item = placedItem;
        return Set.of(item != null ? item.name() : ShulkerPickup.ANY);
    }

    /**
     * For AutoDrop (bot tick): this picked up shulker is very likely ours, so it is never given away whoever seemed to
     * throw or break it. Only while collecting a shulker of its colour, or for one that appeared next to the spot of a
     * shulker of ours right after it was broken (see ShulkerPickup.keepFromReturn).
     * @param spawnKnown whether the item's cell and the time (ms) it appeared are known
     */
    public boolean ownsShulker(@Nullable String itemId, boolean isShulker, boolean spawnKnown, int x, int y, int z, long spawnAtMs) {
        return ShulkerPickup.keepFromReturn(itemId, isShulker, collectingNow(), lastBreak, spawnKnown, x, y, z, spawnAtMs);
    }

    /**
     * Opening and reading containers, so the tracker leaves them to us. Not while we're giving way or waiting, or
     * while only walking to a container: then it reads whatever window is open, unless that's one of ours.
     */
    private @Nullable AutoDrop autoDropModule;

    private AutoDrop autoDrop() {
        AutoDrop found = autoDropModule;
        if (found == null) autoDropModule = found = MODULE.get(AutoDrop.class);
        return found;
    }

    /**
     * In the middle of a job, so nothing else should take the inventory or the bot's aim.
     */
    public boolean busyWithJob() {
        return job != null || handle != null;
    }

    /**
     * A shulker is being picked up off the ground on purpose.
     */
    public boolean isCollecting() {
        return handle == Handle.Collect || job == Job.Ground;
    }

    public boolean isWorking() {
        if (!isEnabled()) return false;
        if (gate == Gate.Running && job != null && job != Job.Label && (step != Step.Walking || handle != null)) return true;

        int id = openContainerId();
        return id != 0 && activity.isOwnWindow(id, containerOpenedAt());
    }

    // Turning on and off

    @Override
    public void onEnable() {
        resetGiveWay();
        restart();
    }

    /**
     * Starts over from the current state: when turned on, and when the bot (re)connects.
     */
    private void restart() {
        // A shulker of ours standing in the world is saved to be finished on the next start, and said loudly, before
        // anything is forgotten
        boolean offline = disconnecting;
        disconnecting = false;
        snapshotInFlight(offline ? "Disconnected" : "Stopped");
        pendingResume = null;
        lastBreak = null;
        resuming = false;
        resumeVerify = false;
        resumeLeaveAlone = false;
        resumeDepositFirst = false;
        resumeDimensionSaid = false;
        resumeBlockId = null;
        // This also runs every tick while the bot isn't in game: only the first time has anything to let go of
        if (stash != null || job != null || gate != Gate.Running || waitStart >= 0) resetGiveWay();
        job = null;
        target = null;
        moved = 0;
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        lastSummary = null;
        lastCompactWhy = "";
        // A login (or a start over): nothing seen of the world before counts for forgetting broken containers
        goneWatch.reset(System.currentTimeMillis());
        failedSurvey.clear();
        surveyRetry.clear();
        unconfirmedRuns.clear();
        closedEarlyReported.clear();
        boxVisits.clear();
        circular.clear();
        loopReported.clear();
        futile.clear();
        noOpReported.clear();
        signAbortRuns.clear();
        signAbortReported.clear();
        signHeld.clear();
        wait = 0;
        idle = false;
        clearStalled();
        stuckCarried.clear();
        boxFailures.clear();
        ownWindowAt.clear();
        reservedSigns.clear();
        labelStep = null;
        labelsPlaced = 0;
        labelFailures = 0;
        labelFailWarned = false;
        blankSigns.clear();
        blankTries.clear();
        labelCapWarned = noSignsWarned = false;
        lastLabelTick = Long.MIN_VALUE / 4;
        nextLabelScan = 0;
        ownSignUntil.clear();
        restoreActions = null;
        heldFrom = heldTo = heldBefore = -1;
        deferredAccess.clear();
        signDirtyAt.clear();
        signGoneSince.clear();
        nextSpotRefresh = 0;
        nextSignSweep = tickCount + SIGN_SWEEP_TICKS;
        compacted.clear();
        tierBoxes.clear();
        stash = null;
        handle = null;
        breakRetries = 0;
        repackFailed = false;
        packTried.clear();
        compact = null;
        activeBin = null;
        naming = null;
        namingUnit = null;
        craft = null;
        crafter = null;
        tableGoal = null;
        inventoryGridRescue();
        inventoryTrip = false;
        tableStuckInv = false;
        tableResetState();
        undoAnvilSwapNow();
        anvilRun = null;
        resetChestRun();
        resetRepair();
        resetEnchant();
        anvilSpot = null;
        anvilStep = null;
        craftKeep.clear();
        orderCraftLots.clear();
        kitEmptyQueue.clear();
        orderCraftSurplus.clear();
        orderReleasedBases.clear();
        CraftingTableSpot.useWebsite(null);
        CraftingTableSpot.sideMode(false);
        CraftingTableSpot.sideClear();
        craftCard = null;
        craftCardDetail = null;
        orders.clearDeliveryFailure();
        orderSources.clear();
        sourceShulkers.clear();
        unpackingNow = null;
        unpackFetchOrigin = null;
        badUnpack.clear();
        orderLeftoverTried = false;
        putBackNoOps.clear();
        putBackGiveUps = 0;
        anvilSkip.clear();
        anvilNoneFor = null;
        compactCooldown.clear();
        gatherLeft = 0;
        failedSpots.clear();
        lost.reset();
        shulkerRetries.clear();
        recoveryPending = false;
        resetJobState();
        cursorReported = false;
        unpackBlocked.clear();
        unpackFuture = null;
        routePending = false;
        groundId = -1;
        groundFinishing = false;
        groundSkipped.clear();
        nextGroundCheck = 0;
        groundBatch = false;
        groundBatchCount = 0;
        surveyed = 0;
        lastStep = "";
        stepProgress.clear();
        debugSaid.clear();
        dropOffDue.clear();
        dropOffInterval.clear();
        dropOffSig.clear();

        if (!CACHE.getPlayerCache().isAlive() || CACHE.getPlayerCache().getPlayerInventory().size() < 45) {
            // Not in game yet: ClientBotTick.Starting calls this again once we are
            return;
        }

        // What was taken from the stash and is still in the carry slots, from before the bot went offline: known before
        // the carry slots are chosen, so it is carried and put away and not taken for the bot's own
        Stash saved = Stashes.current();
        if (saved != null) {
            for (Map.Entry<Integer, String> held : saved.held.entrySet()) {
                Item there = held.getKey() == null || held.getKey() < 0 || held.getKey() > 35 ? null : inventoryItem(held.getKey());
                if (held.getValue() != null && there != null && there.id.equals(held.getValue())) stashHeld.putIfAbsent(held.getKey(), held.getValue());
            }
        }
        if ((!readOnly() || orders.active() || (saved != null && !saved.held.isEmpty())) && !pickCarrySlots()) return;

        boolean existed = saved != null;
        stash = Stashes.currentOrNew();
        stash.heldSource = () -> stashHeld;
        pendingResume = readOnly() && !orders.active() ? null : stash.inFlight;
        // A shulker that was gone back to after a problem keeps its count and the quiet wait across a restart
        if (pendingResume != null && pendingResume.recoveries > 0 && pendingResume.placed != null) {
            shulkerRetries.seed(pendingResume.placed, pendingResume.recoveries);
            recoveryPending = true;
            recoveryGate.arm(tickCount);
            info("Shulker handling: a shulker left after a problem is kept at {} (gone back to {} times so far), going back once the area is quiet.", fmt(pendingResume.placed), pendingResume.recoveries);
        }
        syncDeliveryBox();
        exploreSeen = Stashes.exploreVersion;
        resetExplore();

        int added = addNearbyBoxes();
        dropForeignBoxes();
        for (Box box : stash.boxes) {
            box.unreachable = false;
            Containers.readSigns(box);
        }
        orderBoxes();
        // Players may have changed things while the bot was away: every container is read again before anything is
        // sorted or packed. What was saved stays in use for searching until each one is read
        if (!readOnly() || orders.active()) markStaleForStart();
        tiesReported.clear();
        strayReported.clear();
        unrecognisedReported.clear();
        if (CONFIG.mode == Mode.Signs && !readOnly()) unrecognisedSigns(true);

        // With a perimeter the containers are found by exploring it first, and checked then
        if (stash.hasArea()) {
            info("Stash \"{}\": exploring the stash area {} first, then reading every container again{}.", stash.name,
                Stash.describe(stash.bounds()), sorts() ? " before sorting" : " (scan only)");
            stepStarting();
            if (added > 0 || !existed) stash.save();
            return;
        }
        if (!checkEnough()) return;
        stepStarting();

        int unknown = stash.boxes.size() - stash.knownCount();
        if (!existed) info("New stash \"{}\": scanning {} containers{}.", stash.name, stash.boxes.size(), sorts() ? ", then sorting" : " (scan only)");
        else if (unknown > 0) info("Stash \"{}\": {} containers, scanning {} new ones first and reading the rest again{}.", stash.name, stash.boxes.size(), unknown, sorts() ? "" : " (scan only)");
        else if (!stash.boxes.isEmpty()) info("Stash \"{}\": {} containers, reading all of them again{}, as the bot was away.", stash.name, stash.boxes.size(), sorts() ? " before sorting" : " (scan only)");
        else info("Stash \"{}\": {} containers, {}.", stash.name, stash.boxes.size(), sorts() ? "sorting by " + (CONFIG.mode == Mode.Tiers ? "tier" : "sign") : "scan only");

        if (added > 0 || !existed) stash.save();
    }

    /**
     * The step line for a start: what is to be done, in order.
     */
    private void stepStarting() {
        List<String> parts = new ArrayList<>();
        if (stash.hasArea()) parts.add("explore the area");
        long toRead = stash.boxes.stream().filter(b -> !b.excluded).count();
        if (toRead > 0) parts.add("read " + toRead + " containers");
        if (sorts()) parts.add("sort by " + (CONFIG.mode == Mode.Tiers ? "tier" : "sign"));
        step("starting \"" + stash.name + "\", " + stash.boxes.stream().filter(b -> !b.excluded).count() + " containers; next: "
            + (parts.isEmpty() ? "carry on" : String.join(", then ", parts)) + ".");
    }

    // Set by the unstuck command, from whatever thread runs commands, and taken up on the next tick
    private volatile boolean unstuckAsked;

    /**
     * For the unstuck command: whatever the bot is doing is dropped on the next tick and sorting starts over. With
     * sorting off nothing ticks, so only a saved shulker record is cleared.
     * @return what was dropped, for the reply
     */
    public String requestUnstuck() {
        if (isEnabled() && notReady()) return "The bot isn't in game, nothing to drop. Turn sorting off and on, or try again once it is in game.";
        if (isEnabled()) {
            String doing = handle != null ? "shulker step " + handle + (placed != null ? " at " + xyz(placed) : "")
                : job != null ? "job " + job : "nothing in particular";
            unstuckAsked = true;
            return "Dropping " + doing + " and starting over.";
        }
        Stash current = Stashes.current();
        if (current == null || current.inFlight == null) return "Sorting is off and no unfinished shulker is saved.";
        BlockPos at = current.inFlight.placed;
        current.inFlight = null;
        current.markDirty();
        current.save();
        return "Sorting is off. Forgot the unfinished shulker at " + (at == null ? "?" : xyz(at)) + ", it won't be resumed.";
    }

    /**
     * Drops the current job and anything left to resume, then starts over as if sorting had just been turned on. A
     * shulker of ours still standing is left where it is and listed with the orphaned ones, not finished.
     */
    private void unstuck() {
        int openId = openContainerId();
        if (openId != 0 && ownsWindow()) closeOnDisable(openId, containerOpenedAt());
        activity.releaseOurPathing();
        restoreRules();
        activity.forgetWindow();

        BlockPos left = standingShulker();
        if (left == null && pendingResume != null) left = pendingResume.placed;
        if (left != null && Containers.isLoaded(left) && World.getBlock(left.x(), left.y(), left.z()).name().endsWith("shulker_box")) {
            CompactPlan plan = compact;
            recordOrphan(left, plan != null ? plan.reference.name : fillId != null ? fillId : "items", plan != null ? plan.placedSize : 0);
            alert("Unstuck: left the shulker at {} where it stands, it is listed with the orphaned shulkers.", xyz(left));
        }
        else if (handle == Handle.Collect && placed != null) {
            alert("Unstuck: the shulker from {} may still be on the ground there, check for it.", xyz(placed));
        }
        Stash saved = stash != null ? stash : Stashes.current();
        if (saved != null && saved.inFlight != null) {
            saved.inFlight = null;
            saved.markDirty();
            saved.save();
        }
        breakAsked = null;
        pickupAskedAt = -1000;
        pickupAsks = 0;

        // Nothing standing any more as far as restart() can tell, so it saves no record to resume
        compact = null;
        handle = null;
        placed = null;
        job = null;
        pendingResume = null;
        alert("Unstuck: dropped what I was doing, starting over.");
        resetGiveWay();
        restart();
    }

    /**
     * Clears everything about giving way and what we've asked of the proxy, when sorting starts over or the bot
     * reconnects. Anything of ours still outstanding went with the old session.
     */
    private void resetGiveWay() {
        activity.releaseOurPathing();
        activity.reset();
        restoreRules();
        gate = Gate.Running;
        lastForeignTick = Long.MIN_VALUE / 4;
        lastReason = null;
        waitStart = -1;
        reasonLogged = null;
        synchronized (interruptions) {
            interruptions.clear();
        }
        lastOpenClickAt = 0;
        foreignSinceClick = false;
        jobInterruptions = 0;
        cleanupRejected = 0;
        cleanupGaveUp = false;
        returnTo = null;
        settleUntil = 0;
    }

    @Override
    public void onDisable() {
        // Only our own window is closed, and only our own path stopped: other modules may be using the same ones
        int openId = openContainerId();
        if (openId != 0 && ownsWindow()) closeOnDisable(openId, containerOpenedAt());
        activity.releaseOurPathing();
        restoreRules();
        activity.forgetWindow();
        gate = Gate.Running;
        waitStart = -1;
        if (job == Job.Label && labelFace != null && labelCritical()) alert("Stopped while putting up a sign, there may be a blank one at {}.", labelFace.sign());
        MODULE.get(StashTracker.class).stopAwaitingEditor();
        // The hand and the hotbar as they were, if the bot can still do it: best effort, nothing ticks the module now
        List<InventoryAction> back = new ArrayList<>();
        if (heldFrom != -1) back.add(new MoveToHotbarSlot(invSlot(heldFrom), MoveToHotbarAction.from(heldTo)));
        if (heldBefore != -1) back.add(new SetHeldItem(heldBefore));
        InventoryAction anvilBack = anvilSwapBackAction();
        if (anvilBack != null) back.add(anvilBack);
        // Gear a repair trip stowed, and the item and bottles it had in hand, go back (best effort; what is not done stays in the saved
        // ledger and is undone at the next start)
        Stash gearStash = orders.st();
        if (gearStash != null && gearStash.repairGear != null && !gearStash.repairGear.isEmpty() && openContainerId() == 0 && !holdingCursor()) {
            for (int[] m : Repair.undoPlan(Repair.parseLedger(gearStash.repairGear), this::windowId)) {
                back.add(new ClickItem(0, m[0], ClickItemAction.LEFT_CLICK));
                back.add(new ClickItem(0, m[1], ClickItemAction.LEFT_CLICK));
            }
            gearStash.repairGear = null;
            gearStash.markDirty();
            gearStash.save();
        }
        if (!back.isEmpty() && openContainerId() == 0) {
            INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(back).priority(SORTER_PRIORITY).build());
        }
        heldFrom = heldTo = heldBefore = -1;
        labelStep = null;
        reservedSigns.clear();
        // A shulker standing in the world is kept in the stash file, and finished on the next start
        CompactPlan inFlight = compact;
        snapshotInFlight("Stopped");
        if (inFlight != null && inFlight.placedPos == null && findPlanShulker() >= 0) warn("Stopped while compacting, a part-filled shulker of {} is in the bot's inventory.", inFlight.reference.name);
        compact = null;
        handle = null;
        naming = null;
        namingUnit = null;
        craft = null;
        crafter = null;
        tableGoal = null;
        inventoryGridRescue();
        inventoryTrip = false;
        tableStuckInv = false;
        tableResetState();
        undoAnvilSwapNow();
        anvilRun = null;
        resetChestRun();
        resetRepair();
        resetEnchant();
        anvilSpot = null;
        anvilStep = null;
        craftKeep.clear();
        orderCraftLots.clear();
        kitEmptyQueue.clear();
        orderCraftSurplus.clear();
        orderReleasedBases.clear();
        CraftingTableSpot.useWebsite(null);
        CraftingTableSpot.sideMode(false);
        CraftingTableSpot.sideClear();
        craftCard = null;
        craftCardDetail = null;
        breakRetries = 0;
        groundId = -1;
        groundFinishing = false;
        exploreChunk = NO_CHUNK;

        // Containers used by others that were waiting to be read again are marked, to be read on the next run
        for (Box held : deferredAccess.keySet()) staleBoxes.add(held.pos);
        deferredAccess.clear();
        signDirtyAt.clear();
        signGoneSince.clear();
        signOwners.clear();
        nextSpotRefresh = 0;
        nextSignSweep = tickCount + SIGN_SWEEP_TICKS;
        MODULE.get(StashTracker.class).watchSigns(Set.of());
        BlockPos used;
        while ((used = staleBoxes.poll()) != null) {
            Box box = stash == null ? null : stash.boxAt(used);
            if (box == null || box.excluded) continue;
            box.stale = true;
        }

        if (stash != null && stash.isDirty()) stash.save();
        if (!carriedSlots().isEmpty()) alert("Stopped while carrying {} stacks from the stash, they are in the bot inventory.", carriedSlots().size());

        job = null;
        target = null;
        stash = null;
        resetJobState();
    }

    /**
     * Per-job click and open state, cleared when a job starts, when sorting starts over and when it stops.
     */
    private void resetJobState() {
        signAbort = false;
        swapStep = 0;
        swapStack = swapDisplaced = null;
        recoveries = 0;
        closeTries = 0;
        clickTicks = 0;
        finishing = false;
        finishTicks = 0;
        openRequestedAt = Long.MAX_VALUE;
        lastRejectedAt = 0;
        targetOpen = false;
        startSigSet = endSigSet = false;
    }

    // What the carry slots and the target container held when the job's window opened, to tell whether clicks changed anything
    private long startSig, endSig;
    private boolean startSigSet, endSigSet;

    private long stateSig() {
        long h = 17;
        for (int slot : carry) {
            Item item = inventoryItem(slot);
            h = h * 31 + (item == null ? 0 : item.id.hashCode() * 131L + item.count);
        }
        if (target != null && target.known()) {
            for (Item item : target.contents) h = h * 31 + (item == null ? 0 : item.id.hashCode() * 131L + item.count);
        }
        return h;
    }

    private void stop() {
        CONFIG.sorting = false;
        driven = false;
        syncEnabledFromConfig();
    }

    private @Nullable String problem;

    /**
     * Why sorting last stopped before it could start, for the command to report. Null if it didn't.
     */
    public @Nullable String problem() {
        return problem;
    }

    public void clearProblem() {
        problem = null;
    }

    /**
     * Stops with a reason, shown in the terminal, in-game and in the command's reply.
     */
    private void fail(String reason) {
        // Driven by the Stash Manager: nobody turned the sorter on, so there is nothing to alert; the manager shows why
        if (readOnly()) {
            driveProblem = reason;
            warn("Stash Manager read stopped: {}", reason);
            stop();
            return;
        }
        problem = reason;
        error(reason);
        alertDm(reason);
        inGameAlertActivePlayer("<red>" + reason);
        stop();
    }

    /**
     * Works out which slots to carry stash items in, from the config or, by default, every empty slot.
     */
    private boolean pickCarrySlots() {
        carry.clear();
        Set<Integer> keep = keepSlots();
        Set<Integer> chosen = parseSlots(CONFIG.carrySlots);
        boolean designated = !CONFIG.carrySlots.isBlank();

        for (int i = 0; i < 36; i++) {
            if (keep.contains(i)) continue;
            if (designated && !chosen.contains(i)) continue;
            Item there = inventoryItem(i);
            // Stacks from the stash still in the inventory from before (a reconnect, or sorting turned off and on) are
            // carried again, so they are put away and not left behind as if they were the bot's own
            if (there == null || there.id.equals(stashHeld.get(i))) carry.add(i);
        }

        if (designated && carry.size() < chosen.size()) {
            warn("{} of the carry slots have items in them, they'll be left alone.", chosen.size() - carry.size());
        }

        // Scan only moves nothing, so it needs no room; sorting looks again when it is switched on
        if (carry.isEmpty() && sorts()) {
            int filled = 0;
            for (int i = 0; i < 36; i++) if (inventoryItem(i) != null) filled++;
            String slots = String.format(" The bot has %d of 36 inventory slots filled%s.", filled,
                keep.isEmpty() ? "" : ", and " + keep.size() + " are keep slots");
            fail((designated ? "The carry slots are all full or kept. Empty at least one of them." : "No empty inventory slot to carry items in.") + slots);
            return false;
        }
        return true;
    }

    /**
     * "1-9, 28-36" into inventory indexes. 1-9 is the hotbar and 10-36 the main inventory, matching index + 1.
     */
    public static Set<Integer> parseSlots(String text) {
        Set<Integer> slots = new HashSet<>();
        for (String part : text.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try {
                String[] range = part.split("-");
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int n = Math.max(1, Math.min(from, to)); n <= Math.min(36, Math.max(from, to)); n++) slots.add(n - 1);
            } catch (NumberFormatException ignored) {
                // Skip anything that isn't a number or range
            }
        }
        return slots;
    }

    // The sorting loop

    private void onTick(ClientBotTick event) {
        try {
            // A dimension change starts the gone-container checks over, whatever the sorter is doing
            goneWatch.observe(Stash.currentDimension(), System.currentTimeMillis());
            tick();
        } catch (RuntimeException e) {
            // Never leave Baritone's settings overridden for everyone else
            restoreRules();
            throw e;
        }
    }

    private void tick() {
        if (notReady()) return;
        Alerts.pump(this);
        // Away from the stash on a pearl trip (1.18.0): nothing is sorted, read, ordered or repaired until the bot is home again
        if (com.porter.qwertestash.pearl.AwayGuard.away()) return;
        // A trip is about to leave: no new job starts (one that is running is finished first, the precheck waits for it)
        if (com.porter.qwertestash.pearl.AwayGuard.quiesced() && travelHold() == null) return;
        tickCount++;
        if (unstuckAsked) {
            unstuckAsked = false;
            unstuck();
            return;
        }
        if (stash == null) {
            restart();
            return;
        }

        // What other modules are doing, every tick: sorting only goes on while it's nothing
        claimWindow();
        watchSignSpots();
        Box shown = target;
        if (shown != null && handle == null && ownsWindow()) ownWindowAt.put(shown, System.currentTimeMillis());
        boolean foreignWindow = openContainerId() != 0 && !ownsWindow();
        Activity.Reason foreign = activity.foreign(tickCount, foreignWindow);
        foreignNow = foreign != null;
        if (foreign != null) {
            lastForeignTick = tickCount;
            lastReason = foreign;
            if (foreign != Activity.Reason.Container) foreignSinceClick = true;
        }
        pruneInterruptions();

        // Whatever the state (yielding, waiting, resting), the pathfinder's settings are ours only while a request of ours
        // is outstanding
        if (!activity.ownPathing()) restoreRules();

        switch (gate) {
            case Yielding -> {
                tickYielding();
                return;
            }
            case Waiting -> {
                if (!tickWaiting(foreign)) return;
            }
            case Running -> {
                // Stay-on mode with nothing to do holds nothing of anyone's, so there's nothing to give way
                if (foreign != null && !restingIdle() && !labelCritical()) {
                    beginYield(foreign);
                    tickYielding();
                    return;
                }
            }
        }

        // Baritone's break and place settings are ours only while a request that needs them is outstanding
        if (!activity.ownPathing()) restoreRules();

        // Standing at a ledge with the sorter in control: sneak, unless anyone else is moving the bot
        if (foreign == null) ledgeGuard();

        if (wait > 0) {
            wait--;
            return;
        }

        // The search for an anvil standing goes on a tick's worth at a time
        if (anvilScanNow != null) advanceAnvilScan();
        // The website's table stands in for ours only while a chunk of a craft order runs
        if (CraftingTableSpot.websiteActive() && (craft == null || craft.oc == null)) CraftingTableSpot.useWebsite(null);
        // And the side slot of the bot's own table for an order lasts as long as the order does
        if (CraftingTableSpot.sideModeOn() && !orders.flow.running()) {
            CraftingTableSpot.sideMode(false);
            CraftingTableSpot.sideClear();
        }
        if (tableScanNow != null) advanceTableScan();
        // With craft orders on, the crafting tables standing in the stash area are looked for now and then: the website offers them as a quick pick
        else if (stash != null && job == null && craftOrdersBase() && Alerts.online() && tickCount - tableScanDoneAt > TABLE_SEEN_TICKS) startTableScan();

        // Items stuck in the crafting table's grid: nothing else until they are taken back out (or the table is closed)
        if (tableStuck && holdTable()) return;

        if (finishing) {
            finishJob();
            return;
        }

        if (job == null) {
            // Another stash name, or another server: everything starts over for it
            if (!stash.name.equals(CONFIG.stashName) || !stash.server.equals(Stash.currentServer())) {
                restart();
                return;
            }
            pruneHeld();
            restoreHand();
            if (!deferredAccess.isEmpty()) releaseDeferred();
            if (!staleBoxes.isEmpty()) applyStale();
            applySignChanges();
            applyPendingArea();
            if (sortSeen != sorts() && !sortChanged()) return;
            if (exploreSeen != Stashes.exploreVersion || !com.porter.qwertestash.stash.Dimensions.same(exploreDimension, Stash.currentDimension())) exploreChanged();
            if (idle && orders.flow.holdsOrder()) idle = false;
            if (idle && !wakeUp()) {
                // Stay-on mode with nothing to sort: ground items are looked at now and then, once nothing else is going on
                if (foreign == null && quietElapsed() && openContainerId() == 0 && !holdingCursor()) startGround(true);
                discoverNew();
                return;
            }

            // Nothing is started while another module is working, nor until it has been quiet for a while
            if (foreign != null) {
                beginYield(foreign);
                tickYielding();
                return;
            }
            if (!quietElapsed() || holdingCursor()) {
                beginWait();
                return;
            }

            if (openContainerId() != 0) {
                // Something of ours left open: close it, with whatever is on the cursor put away first
                if (closeSafely() == Close.Stuck) return;
                wait = CONFIG.delayTicks;
                return;
            }
            if (!nextJob()) return;
        }

        if (job != null && job != Job.Ground && job != Job.Explore && leftStashArea()) return;

        // The bot's own lid on a delivery chest is not a player's (see lidSeen)
        if (job == Job.Deliver && target != null && (step == Step.Opening || step == Step.Working)) {
            lidBox = target;
            lidMs = System.currentTimeMillis();
        }

        if (job == Job.Label) tickLabel();
        else if (job == Job.Ground) tickGround();
        else if (job == Job.Explore) tickExplore();
        else if (job == Job.Unpack) tickUnpack();
        else if (job == Job.Name) tickName();
        else if (job == Job.Repair) tickRepair();
        else if (job == Job.Enchant) tickEnchant();
        else if (job == Job.Craft) tickCraft();
        else if (job == Job.Anvil) tickAnvilPlace();
        else if (job == Job.Chest) tickChestPlace();
        else if (handle != null) tickHandling();
        else {
            stepTicks++;
            if (++jobRunTicks > JOB_WATCHDOG_TICKS && target != null) {
                watchdog();
                return;
            }

            switch (step) {
                case Walking -> tickWalking();
                case Opening -> tickOpening();
                case Working -> tickWorking();
            }
        }

        // The proxy refused one of our requests: somebody else's is ahead of it
        if (gate == Gate.Running && !labelCritical() && activity.rejectedAt(tickCount)) beginYield(Activity.Reason.Inventory);
    }

    // Scan only

    // The setting as last acted on, which is only done between jobs
    private boolean sortSeen = true;

    /**
     * Sorting was switched on or off. The job in hand has finished by now; from the next one on, scan only reads and
     * sorting does the lot, and everything given up on while it was off is looked at again.
     * @return false if the sorter stopped because of it
     */
    private boolean sortChanged() {
        sortSeen = sorts();
        idle = false;
        clearStalled();
        futile.clear();
        stuckCarried.clear();
        stuckWarned = false;
        if (!sorts()) {
            if (!readOnly()) info("Scan only: containers are read and kept up to date, nothing is moved.");
            return true;
        }
        info("Sorting items again.");
        // Scan only did not need room to carry things in: sorting does
        if (carry.isEmpty() && !pickCarrySlots()) return false;
        return true;
    }

    // Exploring the stash area (the perimeter). The bot only knows blocks in chunks that are loaded, so it walks until
    // each 16x16 column of the box has been in view, one at a time, nearest first, and reads the loaded ones for
    // containers. It never digs or builds on the way (rules(false, false) on every request), and a column it can't get to
    // is skipped for good.

    private static final int EXPLORE_WAYPOINT_TIMEOUT = 20 * 40;
    private static final int EXPLORE_ARRIVED_TICKS = 40;
    private static final int EXPLORE_REASK_TICKS = 60;
    private static final int EXPLORE_PROGRESS_TICKS = 20 * 15;
    private double exploreBest;
    private int exploreProgressTicks;
    private static final long NO_CHUNK = Long.MIN_VALUE;

    private boolean exploreDone = true;
    private boolean exploreAnnounced;
    private boolean areaWasSet;
    private int exploreSeen;
    // Chunk columns of the box already read for containers, and the ones that couldn't be got within sight of
    private final Set<Long> exploredChunks = new HashSet<>();
    private final Set<Long> unreachableChunks = new HashSet<>();
    private long exploreChunk = NO_CHUNK;
    private int exploreWaypointTicks, exploreArrivedTicks;
    private long exploreAskedAt;
    private boolean exploreStartedInside;

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    /**
     * Every chunk column the box touches.
     */
    private static long[] areaChunks(int[] b) {
        int width = (b[3] >> 4) - (b[0] >> 4) + 1, depth = (b[5] >> 4) - (b[2] >> 4) + 1;
        long[] chunks = new long[width * depth];
        int n = 0;
        for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) {
            for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) chunks[n++] = chunkKey(cx, cz);
        }
        return chunks;
    }

    // The columns of the box in hand, worked out once per exploration, and when it began (ticks)
    private long[] exploreKeys = new long[0];
    private long exploreStartTick;
    private @Nullable String exploreDimension;
    private static final long EXPLORE_MAX_TICKS = 20L * 60 * 30;

    /**
     * Starts exploration over: every column is to be looked at again.
     */
    private void resetExplore() {
        exploredChunks.clear();
        unreachableChunks.clear();
        exploreChunk = NO_CHUNK;
        discoverQueue.clear();
        nextDiscovery = 0;
        exploreAnnounced = false;
        exploreKeys = new long[0];
        exploreDimension = Stash.currentDimension();
        exploreDone = stash == null || !stash.hasArea();
        areaWasSet = stash != null && stash.hasArea();
    }

    /**
     * The perimeter changed, or everything is to be read again: containers that were given up on get another chance, the
     * ones outside the new box go, and the box is explored afresh. Between jobs.
     */
    private void exploreChanged() {
        // Read before resetExplore takes the new one: a change of dimension says nothing about which containers were broken
        boolean sameDimension = com.porter.qwertestash.stash.Dimensions.same(exploreDimension, Stash.currentDimension());
        exploreSeen = Stashes.exploreVersion;
        for (Box box : stash.boxes) box.unreachable = false;
        failedSurvey.clear();
        surveyRetry.clear();
        boxFailures.clear();
        clearStalled();
        futile.clear();
        idle = false;
        boolean hadArea = areaWasSet;

        if (stash.hasArea()) {
            int total = stash.boxes.size();
            int dropped = stash.dropOutsideArea();
            int skipped = (int) stash.boxes.stream().filter(b -> b.outsideSkip).count();
            if (dropped > 0 || skipped > 0) info("Containers outside the stash area: {} never-read ones dropped, {} read ones kept in the file and skipped ({} left).", dropped, skipped, total - dropped);
            resetExplore();
            orderBoxes();
        }
        else {
            resetExplore();
            // The perimeter is gone: containers around the bot again, and the ones skipped for being outside it are let back in
            stash.skipOutsideArea();
            if (hadArea) {
                addNearbyBoxes();
                for (Box box : stash.boxes) if (!box.known()) Containers.readSigns(box);
                orderBoxes();
            }
        }
        // Either way ("Rescan stash" and `stash rescan` come through here): containers a player broke are forgotten
        if (sameDimension) dropGoneBoxes();
        stash.markDirty();
        stash.save();
    }

    // Broken containers. Without a perimeter nothing else ever notices one: a read of it fails, is tried again every minute,
    // and the bot walks to the air there for ever. Checked between jobs (resting, a rescan, a perimeter change) and when a
    // job is about to click on one, with the blocks the proxy has loaded; a chunk that isn't loaded keeps its containers
    private long nextGoneCheck;
    private boolean goneSuspicionSaid;
    // When a box may be forgotten: the stash's dimension, proof this world is the stash's, a settle time, two checks
    private final GoneWatch goneWatch = new GoneWatch();

    /** Every block of it is loaded and none is a chest or barrel. */
    private static boolean goneBox(Box b) {
        return GoneBoxes.gone(b, Containers::isLoaded, p -> Containers.isContainer(p, true));
    }

    private static @Nullable BlockPos remainingHalf(Box b) {
        return GoneBoxes.remainingHalf(b, Containers::isLoaded, p -> Containers.isContainer(p, true));
    }

    /**
     * Why no box may be forgotten now, or null. The proxy's world after a login, in the queue or after a dimension change is
     * not the stash's however empty it looks there: see {@link GoneWatch}.
     */
    private @Nullable String goneGate(long now) {
        if (stash == null) return "no stash";
        if (!Alerts.online()) return "the bot is not in game";
        try {
            if (com.aquarius.Proxy.getInstance().isInQueue()) return "in the queue";
        } catch (RuntimeException | LinkageError e) {
            return "the proxy's state isn't known";
        }
        // A perimeter, or the delivery container, in another dimension: the blocks loaded here are not the stash's
        if (stash.areaSet() && !stash.hasArea()) return "the perimeter is in another dimension";
        DeliverySpot.Spot spot = DeliverySpot.get();
        if (spot != null && !DeliverySpot.here(spot)) return "the delivery container is in another dimension";
        int standing = GoneBoxes.stillThere(stash.boxes, Containers::isLoaded, p -> Containers.isContainer(p, true));
        return goneWatch.blocker(stash.dimension, Stash.currentDimension(), standing, now);
    }

    /**
     * A queued stash area (from the website or from `stash area`, see AreaPending) is applied here, on the tick, and only between tasks (AreaRules.busyWhy);
     * a website area further than 128 blocks from the bot waits, and so does one for another dimension. Applying replaces the area, saves it and
     * runs the usual change path (the sorter explores it afresh). A changed area never cuts a walk, order, box or pass short.
     */
    private void applyPendingArea() {
        AreaPending.Request r = AreaPending.get();
        Stash s = stash;
        if (r == null || s == null) return;
        BlockPos feet = playerFeet();
        String busy = AreaRules.busyWhy(orders.flow.holdsOrder() || orders.flow.resumeId() != null, job != null, handle != null || compact != null || activeBin != null,
            s.inFlight != null || pendingResume != null, repair != null || repairTrip != null || craft != null || anvilRun != null || chestRun != null || naming != null,
            putBackTrip || !activeCarried().isEmpty());
        // The dimension of a command's area is resolved now: the one it was queued with, else the bot's
        String dimension = AreaSync.resolveDimension(r.dimension(), Stash.currentDimension());
        if (r.box() != null && dimension == null) {
            AreaPending.setWhy(AreaRules.BUSY);
            return;
        }
        String why = AreaRules.pendingWhy(r.box(), dimension, Stash.currentDimension(), r.website(), feet.x() + 0.5, feet.z() + 0.5, busy);
        AreaPending.setWhy(why == null ? AreaRules.BUSY : why);
        if (why != null) return;
        if (r.box() == null) {
            s.area = null;
        }
        else {
            s.area = new Stash.Area(new int[] {r.box()[0], r.box()[1], r.box()[2]}, new int[] {r.box()[3], r.box()[4], r.box()[5]}, dimension);
        }
        s.cornerA = s.cornerB = null;
        // Only the request that was applied is removed: one queued meanwhile stays. A change made in game is reported until the server has this area
        AreaPending.clear(r);
        if (!r.website()) AreaPending.markEdited(AreaRules.hashOf(r.box(), r.box() == null ? null : dimension));
        Stashes.exploreVersion++;
        Stashes.version++;
        s.markDirty();
        s.save();
        info("The stash area was {}.", r.box() == null ? "cleared" : "changed");
    }

    /** A container of the stash was read: the stash's dimension is recorded (once), and this world is shown to be the stash's. */
    private void noteStashRead(Box box) {
        // A read that worked ends the waiting and the back-off
        failedSurvey.remove(box);
        surveyRetry.succeeded(box);
        if (box.delivery || stash == null) return;
        String here = Stash.currentDimension();
        if (here == null) return;
        box.readIn = here;
        if (stash.dimension == null) {
            stash.dimension = here;
            stash.markDirty();
        }
        if (com.porter.qwertestash.orders.OrderBlocker.sameDimension(stash.dimension, here)) goneWatch.read(here, System.currentTimeMillis());
    }

    /**
     * Between jobs: forgets the containers that are gone (never the delivery container), with everything kept about them,
     * and builds a double chest whose saved half was broken again from the half left. Only past {@link #goneGate} and the
     * two-check rule, and never when everything in view looks gone at once.
     * @return how many were forgotten or rebuilt
     */
    private int dropGoneBoxes() {
        if (stash == null) return 0;
        long now = System.currentTimeMillis();
        if (goneGate(now) != null) return 0;
        List<Box> looksGone = stash.boxes.stream().filter(b -> !b.delivery && goneBox(b)).toList();
        List<Box> gone = GoneBoxes.toDrop(stash.boxes, Containers::isLoaded, p -> Containers.isContainer(p, true));
        if (looksGone.isEmpty()) goneSuspicionSaid = false;
        else if (gone.isEmpty() && !goneSuspicionSaid) {
            goneSuspicionSaid = true;
            warn("{} containers look broken, but no container around here is still there: not forgetting them (another dimension?).", looksGone.size());
        }
        List<Box> halves = stash.boxes.stream().filter(b -> !b.delivery && remainingHalf(b) != null).toList();
        List<Box> candidates = new ArrayList<>(gone);
        candidates.addAll(halves);
        List<Box> sure = goneWatch.confirmed(candidates, now);
        if (sure.isEmpty()) return 0;

        int dropped = 0, rebuilt = 0;
        for (Box b : sure) {
            if (halves.contains(b) && rebuildHalf(b)) {
                rebuilt++;
                continue;
            }
            forgetBox(b);
            stash.boxes.remove(b);
            dropped++;
        }
        goneDone(dropped, rebuilt, sure);
        return dropped + rebuilt;
    }

    private void goneDone(int dropped, int rebuilt, List<Box> which) {
        stash.markDirty();
        stash.save();
        StashIndex.touch(stash);
        orderBoxes();
        idle = false;
        if (dropped > 0) info("{} containers are gone, forgetting them.", dropped);
        if (rebuilt > 0) info("{} double chests lost a half, reading what is left of them again.", rebuilt);
        debug(() -> "Gone or halved containers at " + which.stream().map(b -> String.valueOf(b.pos)).collect(java.util.stream.Collectors.joining(", ")) + ".");
    }

    /**
     * A double chest whose saved half was broken: the box is the half left now, to be read again. Kept as the same box, so
     * its marks stay. False when that half already belongs to another saved box (this one is then just forgotten).
     */
    private boolean rebuildHalf(Box b) {
        BlockPos rest = remainingHalf(b);
        if (rest == null) return false;
        Box other = stash.boxAt(rest);
        if (other != null && other != b) return false;
        BlockPos was = b.pos;
        String prefix = was + ":";
        futile.keySet().removeIf(k -> k.startsWith(prefix));
        badUnpack.remove(was);
        goneWatch.forget(was);
        b.pos = rest;
        b.parts = new ArrayList<>(Containers.makeBox(rest).parts);
        b.contents = null;
        failedSurvey.remove(b);
        surveyRetry.succeeded(b);
        stalled.remove(b);
        Containers.readSigns(b);
        return true;
    }

    /**
     * The job's own container is gone: only it is forgotten (or built again from its other half), past the same checks as
     * {@link #dropGoneBoxes}, and the job ends without counting as a failed read or an unreachable container. Other gone
     * containers wait for the next check between jobs: the job or the order may still be heading for one of them.
     * @return true if the job ended so
     */
    private boolean dropIfGone() {
        Box box = target;
        if (box == null || box.delivery || stash == null) return false;
        boolean half = remainingHalf(box) != null;
        if (!half && !goneBox(box)) return false;
        long now = System.currentTimeMillis();
        if (goneGate(now) != null) return false;
        // The everything-in-view guard holds for the job's container too
        if (!half && !GoneBoxes.toDrop(stash.boxes, Containers::isLoaded, p -> Containers.isContainer(p, true)).contains(box)) return false;
        if (!goneWatch.confirmedOne(box, now)) return false;
        if (half && rebuildHalf(box)) {
            goneDone(0, 1, List.of(box));
            // Its contents are unknown now: the job ends, and the half left is read before anything else is done with it
            target = null;
            finishJob();
            return true;
        }
        forgetBox(box);
        stash.boxes.remove(box);
        goneDone(1, 0, List.of(box));
        target = null;
        finishJob();
        return true;
    }

    /** What is kept about a container, gone with it. Positions the order keeps (where stacks came from) find another home. */
    private void forgetBox(Box b) {
        stalled.remove(b);
        signHeld.remove(b);
        compacted.remove(b);
        boxFailures.remove(b);
        ownWindowAt.remove(b);
        deferredAccess.remove(b);
        unconfirmedRuns.remove(b);
        closedEarlyReported.remove(b);
        boxVisits.remove(b);
        circular.remove(b);
        loopReported.remove(b);
        signAbortRuns.remove(b);
        signAbortReported.remove(b);
        signAborted.remove(b);
        signSeen.remove(b);
        signRecheck.remove(b);
        signDirtyAt.remove(b);
        signGoneSince.remove(b);
        ownSignUntil.remove(b);
        dropOffDue.remove(b);
        dropOffInterval.remove(b);
        dropOffSig.remove(b);
        failedSurvey.remove(b);
        surveyRetry.succeeded(b);
        strayReported.remove(b);
        unrecognisedReported.remove(b);
        if (anchored.contains(b)) {
            Set<Box> left = new HashSet<>(anchored);
            left.remove(b);
            anchored = left;
        }
        for (List<Box> tier : tierBoxes.values()) tier.remove(b);
        String prefix = b.pos + ":";
        futile.keySet().removeIf(k -> k.startsWith(prefix));
        badUnpack.remove(b.pos);
        goneWatch.forget(b.pos);
        // A compaction topping up a shulker kept there gives up ("the shulker to top up has moved")
        if (compact != null && compact.sourceBox == b) compact.sourceBox = null;
    }

    /**
     * The nearest container within reach of where the bot stands that is unread or stale and may be read now.
     */
    private @Nullable Box nearbyUnread() {
        if (stash == null) return null;
        return nearest(stash.boxes.stream()
            .filter(b -> !b.excluded && !b.delivery && (!b.known() || b.stale) && !b.unreachable && !failedSurvey.contains(b) && !signPending(b)
                && arrivedAt(new GoalNear(b.pos, REACH_RANGE_SQ)))
            .toList());
    }

    private boolean startExplore() {
        int[] b = stash.bounds();
        if (b == null) return false;
        job = Job.Explore;
        target = null;
        exploreChunk = NO_CHUNK;
        exploreStartedInside = stash.inArea(playerFeet(), 2);
        if (!exploreAnnounced) {
            exploreAnnounced = true;
            exploreKeys = areaChunks(b);
            exploreStartTick = tickCount;
            info("Exploring the stash area: {} chunks to look at.", exploreKeys.length);
        }
        return true;
    }

    private void endExplore() {
        activity.releaseOurPathing();
        restoreRules();
        exploreChunk = NO_CHUNK;
        job = null;
        target = null;
        wait = CONFIG.delayTicks;
    }

    private void tickExplore() {
        int[] b = stash.bounds();
        if (b == null || exploreSeen != Stashes.exploreVersion || !com.porter.qwertestash.stash.Dimensions.same(exploreDimension, Stash.currentDimension())) {
            // Changed under us: between jobs it starts over
            endExplore();
            return;
        }
        if (exploreKeys.length == 0) exploreKeys = areaChunks(b);
        long[] chunks = exploreKeys;

        // One run is not allowed to go on for ever: what is known is what is used
        if (tickCount - exploreStartTick > EXPLORE_MAX_TICKS) {
            for (long key : chunks) if (!exploredChunks.contains(key)) unreachableChunks.add(key);
            warn("Exploring the stash area took over 30 minutes, going on with what is known ({} chunks not seen).", unreachableChunks.size());
            finishExplore();
            return;
        }

        // A loaded column is read at once, one a tick
        for (long key : chunks) {
            if (exploredChunks.contains(key) || unreachableChunks.contains(key)) continue;
            if (World.isChunkLoadedChunkPos(chunkX(key), chunkZ(key))) {
                scanChunk(key, b);
                exploredChunks.add(key);
                return;
            }
        }

        // The column being walked to is in view now
        if (exploreChunk != NO_CHUNK && exploredChunks.contains(exploreChunk)) {
            activity.releaseOurPathing();
            restoreRules();
            exploreChunk = NO_CHUNK;
        }

        // Between waypoints: containers within reach that haven't been read (or are stale) are read before moving on
        if (exploreChunk == NO_CHUNK && nearbyUnread() != null) {
            endExplore();
            return;
        }

        if (exploreChunk == NO_CHUNK) {
            // The nearest column not yet seen
            BlockPos feet = playerFeet();
            long nearest = NO_CHUNK;
            double nearestDistance = Double.MAX_VALUE;
            for (long key : chunks) {
                if (exploredChunks.contains(key) || unreachableChunks.contains(key)) continue;
                int[] w = waypoint(key, b);
                double d = Math.hypot(w[0] - feet.x(), w[1] - feet.z());
                if (d < nearestDistance) {
                    nearestDistance = d;
                    nearest = key;
                }
            }
            if (nearest == NO_CHUNK) {
                finishExplore();
                return;
            }
            exploreChunk = nearest;
            final long pick = nearest;
            stepProgress("explore", exploredChunks.size(), chunks.length, () -> "exploring the area: " + exploredChunks.size() + "/" + chunks.length + " chunks; next: chunk at " + chunkX(pick) + " " + chunkZ(pick) + ".");
            debug("Exploring: walking to the chunk at {}, {}, {} of {} seen, {} out of reach.", chunkX(pick), chunkZ(pick), exploredChunks.size(), chunks.length, unreachableChunks.size());
            exploreBest = Double.MAX_VALUE;
            exploreProgressTicks = 0;
            exploreWaypointTicks = exploreArrivedTicks = 0;
            exploreAskedAt = -1;
        }

        exploreWaypointTicks++;
        int[] w = waypoint(exploreChunk, b);
        BlockPos feet = playerFeet();
        if (exploreStartedInside && !stash.inArea(feet, 2)) {
            warn("Skipping the chunk at {}: the way there leaves the stash area.", fmt(new BlockPos(w[0], feet.y(), w[1])));
            skipChunk(null);
            return;
        }
        if (exploreWaypointTicks > EXPLORE_WAYPOINT_TIMEOUT) {
            skipChunk("it took longer than 40 seconds");
            return;
        }
        // A failure only counts once this waypoint has been asked for: an earlier settled one is not about it
        if (exploreAskedAt >= 0 && activity.ownFailureSettled()) {
            skipChunk("no way there was found");
            return;
        }
        double distance = Math.hypot(CACHE.getPlayerCache().getX() - (w[0] + 0.5), CACHE.getPlayerCache().getZ() - (w[1] + 0.5));
        if (distance < exploreBest - 1) {
            exploreBest = distance;
            exploreProgressTicks = exploreWaypointTicks;
        }
        if (exploreWaypointTicks - exploreProgressTicks > EXPLORE_PROGRESS_TICKS) {
            skipChunk("no headway for 15 seconds");
            return;
        }
        if (distance <= 2) {
            // At the spot and still not in view: there is nothing loaded there to look at
            if (++exploreArrivedTicks > EXPLORE_ARRIVED_TICKS) skipChunk("it never loaded");
            return;
        }
        exploreArrivedTicks = 0;

        if (activity.mayRequest() && (exploreAskedAt < 0 || tickCount - exploreAskedAt > EXPLORE_REASK_TICKS)) {
            exploreAskedAt = tickCount;
            // Never dig or build to get there
            rules(false, false);
            GoalXZ walkTo = new GoalXZ(w[0], w[1]);
            PathingRequestFuture future = BARITONE.pathTo(walkTo);
            activity.notePath(future, BARITONE.getCustomGoalProcess(), walkTo, tickCount);
        }
    }

    /**
     * Where to stand to bring a chunk column into view: the middle of the part of it inside the box. The height isn't
     * known for a column that isn't loaded, so the pathfinder is only given x and z.
     */
    private static int[] waypoint(long key, int[] b) {
        int x0 = Math.max(b[0], chunkX(key) << 4), x1 = Math.min(b[3], (chunkX(key) << 4) + 15);
        int z0 = Math.max(b[2], chunkZ(key) << 4), z1 = Math.min(b[5], (chunkZ(key) << 4) + 15);
        return new int[] {(x0 + x1) / 2, (z0 + z1) / 2};
    }

    private void skipChunk(@Nullable String why) {
        if (why != null) debug("Not exploring the chunk at {}, {}: {}.", chunkX(exploreChunk), chunkZ(exploreChunk), why);
        unreachableChunks.add(exploreChunk);
        exploreChunk = NO_CHUNK;
        activity.releaseOurPathing();
        restoreRules();
    }

    /**
     * Adds the containers in this loaded column of the box and forgets saved ones that aren't there any more. The
     * column's block entities say where the chests and barrels are, so no block of the box is looked at but those.
     */
    private void scanChunk(long key, int[] b) {
        int cx = chunkX(key), cz = chunkZ(key);
        var chunk = CACHE.getChunkCache().get(cx, cz);
        if (chunk == null) return;

        List<org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo> entities;
        synchronized (chunk.getBlockEntities()) {
            entities = new ArrayList<>(chunk.getBlockEntities());
        }
        for (var entity : entities) {
            var type = entity.getType();
            boolean chest = type == org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType.CHEST
                || type == org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType.TRAPPED_CHEST
                || (CONFIG.barrels && type == org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType.BARREL);
            if (!chest) continue;
            // The coordinates of a block entity are within its column
            int x = (cx << 4) + entity.getX(), y = entity.getY(), z = (cz << 4) + entity.getZ();
            if (x < b[0] || x > b[3] || y < b[1] || y > b[4] || z < b[2] || z > b[5]) continue;
            BlockPos p = new BlockPos(x, y, z);
            if (!Containers.isContainer(p, CONFIG.barrels) || stash.boxAt(p) != null) continue;
            Box box = Containers.makeBox(p);
            stash.boxes.add(box);
            Containers.readSigns(box);
            stash.markDirty();
            scanAdded++;
        }
        int before = stash.boxes.size();
        if (stash.boxes.removeIf(box -> (box.pos.x() >> 4) == cx && (box.pos.z() >> 4) == cz && Containers.isLoaded(box.pos) && !Containers.isContainer(box.pos, true))) {
            stash.markDirty();
            scanRemoved += before - stash.boxes.size();
        }
    }

    // New containers in the perimeter (a chest put up later) are looked for every 5 seconds while resting: the loaded columns
    // of the box, a few a tick
    private static final int DISCOVER_GAP_TICKS = 100;
    private static final int DISCOVER_PER_TICK = 4;
    private final ArrayDeque<Long> discoverQueue = new ArrayDeque<>();
    private long nextDiscovery;
    private int scanAdded, scanRemoved;

    private void discoverNew() {
        if (stash == null) return;
        // Broken containers are looked for with or without a perimeter (scanChunk below only covers the perimeter)
        if (tickCount >= nextGoneCheck) {
            nextGoneCheck = tickCount + DISCOVER_GAP_TICKS;
            dropGoneBoxes();
        }
        if (!stash.hasArea() || !exploreDone) return;
        int[] b = stash.bounds();
        if (discoverQueue.isEmpty()) {
            if (tickCount < nextDiscovery) return;
            nextDiscovery = tickCount + DISCOVER_GAP_TICKS;
            for (long key : areaChunks(b)) {
                if (World.isChunkLoadedChunkPos(chunkX(key), chunkZ(key))) discoverQueue.add(key);
            }
            scanAdded = scanRemoved = 0;
        }
        for (int i = 0; i < DISCOVER_PER_TICK && !discoverQueue.isEmpty(); i++) scanChunk(discoverQueue.poll(), b);
        if (discoverQueue.isEmpty() && (scanAdded > 0 || scanRemoved > 0)) {
            if (scanAdded > 0) info("Found {} new containers in the stash area.", scanAdded);
            if (scanRemoved > 0) info("{} containers in the stash area are gone.", scanRemoved);
            scanAdded = scanRemoved = 0;
            orderBoxes();
            idle = false;
            stash.save();
        }
    }

    private void finishExplore() {
        exploreDone = true;
        idle = false;
        info("Explored the stash area: found {} containers ({} chunks couldn't be reached).", stash.boxes.size(), unreachableChunks.size());
        endExplore();
        orderBoxes();
        if (stash.isDirty()) stash.save();
        checkEnough();
    }

    /**
     * Whether there are enough containers (and signs) to go on.
     */
    private boolean checkEnough() {
        // Scan only needs one container to read, sorting needs two to sort between
        long sortable = stash.boxes.stream().filter(b -> !b.excluded && (!b.dropOff || !sorts())).count();
        int needed = sorts() ? 2 : 1;
        if (sortable < needed) {
            fail(String.format("Found %d container(s) to %s %s, need at least %d.", sortable, sorts() ? "sort" : "scan",
                stash.hasArea() ? "in the stash area" : "within " + CONFIG.radius + " blocks of the bot", needed));
            return false;
        }

        // Scan only reads containers: signs are not needed for that
        if (sorts() && CONFIG.mode == Mode.Signs && stash.boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            fail("None of the containers have signs on them. Put signs on them, or use tiers mode.");
            return false;
        }
        return true;
    }

    /**
     * Said once for a container, which is then left alone until the next full read or a new perimeter.
     */
    private void reportUnreachable(Box box) {
        if (box.delivery) {
            alert("Can't reach the delivery container.");
            return;
        }
        alert("Can't reach the {} at {} without breaking or placing blocks{}, skipping it.", blockName(box), fmt(box.pos),
            CONFIG.safeWalk ? ", jumping or falling" : "");
    }

    private void reportCantOpen(Box box) {
        if (box.delivery) {
            alert("Couldn't open the delivery container.");
            return;
        }
        alert("Couldn't open the {} at {}, skipping it.", blockName(box), fmt(box.pos));
    }

    private static String blockName(Box box) {
        return World.getBlock(box.pos.x(), box.pos.y(), box.pos.z()).name().replace('_', ' ');
    }

    // Emptying a bundle, in place. With the inventory window and an empty cursor, a right click on the bundle takes one stack
    // out of it onto the cursor (the server does that; the proxy predicts something else, and the server's answer
    // corrects it, so nothing is done until the cursor really shows an ordinary stack), a left click on an empty carry slot
    // puts that stack down, and that is repeated while the bundle has stacks and carry slots are free. The bundle never
    // leaves its slot and is never left-clicked onto a slot (see bundleHazard).

    private enum Unpack { Take, Taken, Put, Putting }

    private int unpackSlot = -1, unpackTicks, unpackTaken, unpackTarget = -1;
    private Unpack unpackStage = Unpack.Take;
    private @Nullable RequestFuture unpackFuture;
    // Bundles not tried again before this time (ms), by inventory slot: after a try that left one still full
    private final Map<Integer, Long> unpackBlocked = new HashMap<>();
    private static final long UNPACK_BLOCK_MS = 30_000;
    private static final int UNPACK_MAX_STACKS = 27;

    private void blockBundle(int slot) {
        unpackBlocked.put(slot, System.currentTimeMillis() + UNPACK_BLOCK_MS);
    }

    private int freeCarrySlotExcept(int except) {
        for (int slot : carry) if (slot != except && inventoryItem(slot) == null) return slot;
        return -1;
    }

    private boolean nextUnpackJob() {
        if (openContainerId() != 0 || holdingCursor() || bundleOnCursor()) return false;
        long now = System.currentTimeMillis();
        unpackBlocked.values().removeIf(until -> until <= now);
        for (int slot : carriedSlots()) {
            if (unpackBlocked.containsKey(slot)) continue;
            Item item = inventoryItem(slot);
            if (item == null || !item.isFilledBundle()) continue;
            if (freeCarrySlotExcept(slot) == -1) continue;
            job = Job.Unpack;
            step("emptying a bundle; next: put away " + Math.max(1, item.contents.size()) + " stacks.");
            target = null;
            unpackSlot = slot;
            unpackFuture = null;
            unpackStage = Unpack.Take;
            unpackTicks = unpackTaken = 0;
            unpackTarget = -1;
            return true;
        }
        return false;
    }

    private void tickUnpack() {
        unpackTicks++;
        if (unpackTicks > 20 * 60) {
            warn("Emptying a bundle is taking too long, giving up on it for now.");
            giveUpUnpack();
            return;
        }

        switch (unpackStage) {
            case Take -> {
                if (openContainerId() != 0 || busy() || cursorSettling()) return;
                if (holdingCursor() || bundleOnCursor()) {
                    giveUpUnpack();
                    return;
                }
                Item bundle = inventoryItem(unpackSlot);
                // Just after a take the slot can still show the proxy's guess: given a moment to be corrected
                if (bundle == null) {
                    if (unpackTicks > 40) giveUpUnpack();
                    return;
                }
                if (!bundle.isFilledBundle()) {
                    finishUnpack();
                    return;
                }
                if (freeCarrySlotExcept(unpackSlot) == -1 || unpackTaken >= UNPACK_MAX_STACKS) {
                    finishUnpack();
                    return;
                }
                clickRefused = false;
                RequestFuture future = submit(new ClickItem(0, invSlot(unpackSlot), ClickItemAction.RIGHT_CLICK));
                if (future == RequestFuture.rejected) return;
                unpackFuture = future;
                unpackStage = Unpack.Taken;
                unpackTicks = 0;
            }
            case Taken -> {
                // Done when the cursor really holds a stack that is not a bundle: the correction of the proxy's guess
                RequestFuture future = unpackFuture;
                if (future == null || !future.isDone()) return;
                if (holdingCursor() && !bundleOnCursor()) {
                    unpackStage = Unpack.Put;
                    unpackTicks = 0;
                    return;
                }
                if (unpackTicks > 40) {
                    warn("The server didn't give a stack out of the bundle, leaving it as it is.");
                    giveUpUnpack();
                }
            }
            case Put -> {
                if (busy() || cursorSettling()) return;
                int slot = freeCarrySlotExcept(unpackSlot);
                Item held = Item.of(mouseStack());
                if (slot == -1 || held == null) {
                    giveUpUnpack();
                    return;
                }
                RequestFuture future = submit(new ClickItem(0, invSlot(slot), ClickItemAction.LEFT_CLICK));
                if (future == RequestFuture.rejected) return;
                // Stash cargo from now on: tools or food in a bundle are not the bot's own
                stashHeld.put(slot, held.id);
                unpackTarget = slot;
                unpackFuture = future;
                unpackStage = Unpack.Putting;
                unpackTicks = 0;
            }
            case Putting -> {
                RequestFuture future = unpackFuture;
                if (future == null || !future.isDone()) return;
                if (holdingCursor()) {
                    if (unpackTicks > 40) giveUpUnpack();
                    return;
                }
                unpackTaken++;
                settleUntil = tickCount + Math.max(CONFIG.delayTicks, 4);
                unpackStage = Unpack.Take;
                unpackTicks = 0;
            }
        }
    }

    /**
     * Over, for now: said if anything came out, and the bundle held back for a while if it is still full.
     */
    private void finishUnpack() {
        Item bundle = inventoryItem(unpackSlot);
        if (unpackTaken > 0) info("Emptied a bundle: {} stacks taken out.", unpackTaken);
        if (bundle != null && bundle.isFilledBundle()) blockBundle(unpackSlot);
        endUnpack();
    }

    private void giveUpUnpack() {
        blockBundle(unpackSlot);
        // Whatever is left on the cursor is put away by the waiting that follows (returnCursor)
        if (unpackTaken > 0) info("Emptied a bundle: {} stacks taken out.", unpackTaken);
        endUnpack();
    }

    private void endUnpack() {
        unpackFuture = null;
        unpackTarget = -1;
        job = null;
        target = null;
        wait = CONFIG.delayTicks;
    }

    // Bundles taken out of a chest or a shulker with a shift-click land in the first free slot the server picks, which may
    // not be a carry slot. A bundle that appears outside the carry slots after such a take is moved into one with a hotbar
    // swap (which the proxy handles for a bundle, unlike a click), so a bundle taken from the stash always ends up carried.
    private boolean routePending;
    private long routeUntil;
    private final Set<Integer> routeBefore = new HashSet<>();

    private void noteBundleTaken() {
        if (!routePending) {
            routeBefore.clear();
            for (int i = 0; i < 36; i++) {
                Item item = inventoryItem(i);
                if (item != null && item.isBundle()) routeBefore.add(i);
            }
        }
        routePending = true;
        routeUntil = tickCount + 60;
    }

    /**
     * @return true while a taken bundle is being looked for or moved: nothing else is planned meanwhile
     */
    private boolean routeBundle() {
        if (!routePending) return false;
        if (tickCount > routeUntil) {
            routePending = false;
            return false;
        }
        if (busy() || openContainerId() != 0 || holdingCursor() || bundleOnCursor()) return true;

        for (int i = 0; i < 36; i++) {
            Item item = inventoryItem(i);
            if (item == null || !item.isBundle() || routeBefore.contains(i)) continue;
            if (carry.contains(i)) {
                routePending = false;
                return false;
            }
            // A free carry slot on the other side of the hotbar edge: a hotbar swap moves between hotbar and main inventory
            int to = -1;
            for (int slot : carry) {
                if (inventoryItem(slot) == null && (i < 9) != (slot < 9)) {
                    to = slot;
                    break;
                }
            }
            if (to == -1) {
                routePending = false;
                warn("A bundle landed in slot {}, outside the carry slots, and there is no free carry slot to move it to.", i + 1);
                return false;
            }
            InventoryAction swap = i < 9
                ? new MoveToHotbarSlot(invSlot(to), MoveToHotbarAction.from(i))
                : new MoveToHotbarSlot(invSlot(i), MoveToHotbarAction.from(to));
            if (submit(swap) != RequestFuture.rejected) routeUntil = tickCount + 40;
            return true;
        }
        // Not shown yet: the inventory update is a few ticks behind the shift-click
        return true;
    }

    // Picking up items lying in the stash area. Only ones the bot didn't drop or break out of a block itself (see
    // GroundItems), only when no other player is loaded, only ones that have lain still a while, and only between jobs:
    // when everything is put away, before compacting, and while resting in stay-on mode. What is picked up is stash cargo
    // and sorted like everything else.

    private static final long GROUND_STILL_MS = 3_000;
    private static final long GROUND_SKIP_MS = 5 * 60_000;
    private static final int GROUND_TIMEOUT = 20 * 20;
    private static final int GROUND_CHECK_TICKS = 10;
    private static final int GROUND_VERTICAL = 4;
    private static final int GROUND_FINISH_TICKS = 15;
    private static final int GROUND_ARRIVED_TICKS = 40;
    private static final int GROUND_REASK_TICKS = 20 * 3;

    private @Nullable GroundItems groundModule;
    private int groundId = -1;
    private @Nullable BlockPos groundCell;
    private @Nullable ItemStack groundStack;
    private String groundLabel = "";
    private String groundItemLabel = "";
    private String groundItemId = "";
    private int groundTicks, groundArrivedTicks, groundFinishTicks;
    private long groundAskedAt;
    private boolean groundFinishing, groundTaken, groundStartedInside;
    // Moving what landed outside the carry slots into one
    private @Nullable RequestFuture groundMove;
    // What every slot held when the trip began, to tell which of them the pickup went into
    private final String[] groundBeforeIds = new String[36];
    private final int[] groundBeforeCounts = new int[36];
    // Items given up on (unreachable, or not picked up in time), until when
    private final Map<Integer, Long> groundSkipped = new HashMap<>();
    private long nextGroundCheck;
    private boolean groundWaitLogged;
    // What the status shows, worked out on the tick thread (the command thread only reads it)
    private volatile String groundStatus = "nothing on the ground";
    private int surveyed;

    private GroundItems groundItems() {
        GroundItems found = groundModule;
        if (found == null) groundModule = found = MODULE.get(GroundItems.class);
        return found;
    }

    /**
     * For the status: whether ground items are being collected, and what is holding that up.
     */
    public String groundState() {
        if (!CONFIG.collectGround) return "off";
        if (!sorts()) return "off (scan only)";
        return groundStatus;
    }

    private static String fmt(BlockPos pos) {
        return "[" + pos.x() + ", " + pos.y() + ", " + pos.z() + "]";
    }

    /**
     * The stash area: every container's bounding box widened by the collect range sideways and 4 blocks up and down.
     * Returns minX, minY, minZ, maxX, maxY, maxZ as world coordinates, or null if there are no containers.
     */
    private double @Nullable [] groundArea() {
        if (stash == null) return null;
        int range0 = Math.max(0, CONFIG.collectRange);
        int[] perimeter = stash.bounds();
        // With a perimeter it is the box, wherever the containers are in it
        if (perimeter != null) {
            return new double[] {perimeter[0] - range0, perimeter[1] - GROUND_VERTICAL, perimeter[2] - range0,
                perimeter[3] + 1 + range0, perimeter[4] + 1 + GROUND_VERTICAL, perimeter[5] + 1 + range0};
        }
        if (stash.boxes.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Box box : stash.boxes) {
            for (BlockPos part : box.parts) {
                minX = Math.min(minX, part.x());
                minY = Math.min(minY, part.y());
                minZ = Math.min(minZ, part.z());
                maxX = Math.max(maxX, part.x());
                maxY = Math.max(maxY, part.y());
                maxZ = Math.max(maxZ, part.z());
            }
        }
        if (minX == Integer.MAX_VALUE) return null;
        int range = Math.max(0, CONFIG.collectRange);
        return new double[] {minX - range, minY - GROUND_VERTICAL, minZ - range, maxX + 1 + range, maxY + 1 + GROUND_VERTICAL, maxZ + 1 + range};
    }

    private static boolean inArea(double[] area, double x, double y, double z, double margin) {
        return x >= area[0] - margin && x <= area[3] + margin && y >= area[1] - margin && y <= area[4] + margin
            && z >= area[2] - margin && z <= area[5] + margin;
    }

    /**
     * Lava, fire, cactus and the like at the item or under it, or a chunk that isn't loaded: not worth a trip.
     */
    private static boolean hazardAt(BlockPos cell) {
        if (!World.isChunkLoadedBlockPos(cell.x(), cell.z())) return true;
        for (int dy = 0; dy >= -1; dy--) {
            switch (World.getBlock(cell.x(), cell.y() + dy, cell.z()).name()) {
                case "lava", "fire", "soul_fire", "cactus", "magma_block", "sweet_berry_bush", "powder_snow" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }

    /**
     * Starts a trip for the nearest item on the ground that may be picked up, if one may be started now. Called between
     * jobs only: when everything else is done (always), and while resting in stay-on mode (every few ticks).
     */
    private boolean startGround(boolean throttled) {
        if (!CONFIG.collectGround || !sorts()) return false;
        // Not over a job, a handle, giving way, a compaction (a shulker may be standing) or a finishing job
        if (job != null || handle != null || finishing || gate != Gate.Running || stash == null || compact != null) return false;
        if (throttled) {
            if (tickCount < nextGroundCheck) return false;
            nextGroundCheck = tickCount + GROUND_CHECK_TICKS;
        }

        long wall = System.currentTimeMillis();
        groundSkipped.values().removeIf(until -> until <= wall);

        GroundItems ground = groundItems();
        String near = ground.playerNearby();
        if (near == null) groundWaitLogged = false;

        double[] area = groundArea();
        BlockPos feet = playerFeet();
        Entity best = null;
        ItemStack bestStack = null;
        double bestDistance = Double.MAX_VALUE;
        int eligible = 0;
        if (area != null) {
            for (Entity entity : ground.candidates(GROUND_STILL_MS)) {
                if (groundSkipped.containsKey(entity.getEntityId())) {
                    debugOnce("ground" + entity.getEntityId(), () -> "Ground item " + entity.getEntityId() + " at " + fmt(cell(entity)) + " skipped: given up on lately.");
                    continue;
                }
                if (!inArea(area, entity.getX(), entity.getY(), entity.getZ(), 0)) {
                    debugOnce("ground" + entity.getEntityId(), () -> "Ground item " + entity.getEntityId() + " at " + fmt(cell(entity)) + " skipped: outside the stash area.");
                    continue;
                }
                ItemStack stack = GroundItems.stackOf(entity);
                if (stack == null || stack.getAmount() <= 0 || hazardAt(cell(entity))) {
                    debugOnce("ground" + entity.getEntityId(), () -> "Ground item " + entity.getEntityId() + " at " + fmt(cell(entity)) + " skipped: " + (stack == null || stack.getAmount() <= 0 ? "no readable stack" : "lava, fire or another hazard there") + ".");
                    continue;
                }
                eligible++;
                double distance = distSq(feet, cell(entity));
                if (distance >= bestDistance) continue;
                best = entity;
                bestStack = stack;
                bestDistance = distance;
            }
        }
        if (best == null || bestStack == null) {
            groundStatus = "nothing on the ground";
            return false;
        }

        // A player in render distance is assumed to be about to pick their things up: nothing is touched until they are gone
        if (near != null) {
            groundStatus = "waiting for " + near + " to leave (" + eligible + " items)";
            if (!groundWaitLogged) {
                groundWaitLogged = true;
                debug("Ground items waiting: {} is nearby.", near);
            }
            return false;
        }
        if (freeCarrySlot() == -1) {
            groundStatus = "no free carry slot";
            return false;
        }
        // Nothing of anyone's in the way
        if (openContainerId() != 0 || holdingCursor() || !activity.mayRequest()) return false;

        Item picked = Item.of(bestStack);
        if (picked == null) return false;

        groundId = best.getEntityId();
        groundCell = cell(best);
        groundStack = bestStack;
        groundItemId = picked.id;
        groundItemLabel = picked.label();
        groundLabel = bestStack.getAmount() + " " + picked.label();
        groundTicks = groundArrivedTicks = groundFinishTicks = 0;
        groundAskedAt = -1;
        groundFinishing = groundTaken = false;
        groundMove = null;
        groundStartedInside = inArea(area, feet.x() + 0.5, feet.y(), feet.z() + 0.5, 2);
        for (int i = 0; i < 36; i++) {
            Item there = inventoryItem(i);
            groundBeforeIds[i] = there == null ? null : there.id;
            groundBeforeCounts[i] = there == null ? 0 : there.count;
        }
        job = Job.Ground;
        target = null;
        idle = false;
        groundStatus = "picking up " + groundLabel;
        info("Picking up {} {} from the ground at {}.", bestStack.getAmount(), picked.label(), fmt(groundCell));
        step("ground: picking up " + eligible + " stacks near " + xyz(groundCell) + "; next: put them away.");
        return true;
    }

    private static BlockPos cell(Entity entity) {
        return new BlockPos((int) Math.floor(entity.getX()), (int) Math.floor(entity.getY()), (int) Math.floor(entity.getZ()));
    }

    private void tickGround() {
        groundTicks++;
        GroundItems ground = groundItems();
        if (!CONFIG.collectGround || !sorts()) {
            endGround(false);
            return;
        }

        // Another player came into view: dropped at once, and nothing more is done about ground items until they are gone
        if (!groundFinishing) {
            String near = ground.playerNearby();
            if (near != null) {
                debug("{} is nearby, stopping the pickup of {} from the ground.", near, groundLabel);
                endGround(false);
                return;
            }
        }

        Entity item = CACHE.getEntityCache().get(groundId);
        if (ground.takenCount(groundId) >= 0) groundTaken = true;
        boolean gone = item == null || item.isRemoved();
        if (groundFinishing || groundTaken || gone) {
            if (!groundFinishing) {
                groundFinishing = true;
                activity.releaseOurPathing();
                restoreRules();
            }
            finishGround();
            return;
        }

        // The item moved (water, a push): go to where it is now
        BlockPos at = cell(item);
        if (!at.equals(groundCell)) {
            groundCell = at;
            groundAskedAt = -1;
            groundArrivedTicks = 0;
            if (hazardAt(at)) {
                giveUpOnGround("it is somewhere dangerous");
                return;
            }
        }

        // Never outside the stash area, the bot included (a bot that set out from outside is left to walk back in)
        double[] area = groundArea();
        BlockPos feet = playerFeet();
        if (area == null || !inArea(area, item.getX(), item.getY(), item.getZ(), 0)) {
            giveUpOnGround("it left the stash area");
            return;
        }
        if (groundStartedInside && !inArea(area, feet.x() + 0.5, feet.y(), feet.z() + 0.5, 2)) {
            warn("Skipping the {} at {}: the way there leaves the stash area.", groundItemLabel, fmt(groundCell));
            endGround(true);
            return;
        }
        if (groundTicks > GROUND_TIMEOUT) {
            giveUpOnGround("it took longer than 20 seconds");
            return;
        }
        if (activity.ownFailureSettled()) {
            giveUpOnGround("no way to it was found");
            return;
        }

        double dx = CACHE.getPlayerCache().getX() - item.getX(), dz = CACHE.getPlayerCache().getZ() - item.getZ();
        double dy = CACHE.getPlayerCache().getY() - item.getY();
        if (Math.abs(dx) < 1.2 && Math.abs(dz) < 1.2 && Math.abs(dy) < 1.5) {
            // On top of it: the server has a moment to hand it over
            if (++groundArrivedTicks > GROUND_ARRIVED_TICKS) giveUpOnGround("standing on it didn't pick it up");
            return;
        }
        groundArrivedTicks = 0;

        if (activity.mayRequest() && (groundAskedAt < 0 || tickCount - groundAskedAt > GROUND_REASK_TICKS)) {
            groundAskedAt = tickCount;
            // Never dig or build on the way
            rules(false, false);
            GoalBlock walkTo = new GoalBlock(groundCell);
            PathingRequestFuture future = BARITONE.pathTo(walkTo);
            activity.notePath(future, BARITONE.getCustomGoalProcess(), walkTo, tickCount);
        }
    }

    /**
     * The item is gone from the world: picked up by us (the inventory shows it a moment later), or despawned or taken by
     * someone else. Only slots that gained exactly that item are claimed as stash cargo.
     */
    // A stack was just picked up off the ground: more are fetched before any of them is put away, while two carry slots are free
    private boolean groundBatch;
    private int groundBatchCount;
    private static final int MAX_GROUND_BATCH = 30;

    private void finishGround() {
        RequestFuture move = groundMove;
        if (move != null) {
            if (move.isDone() || ++groundFinishTicks > 20 * 5) endGround(false);
            return;
        }

        ItemStack want = groundStack;
        int[] gain = new int[36];
        int total = 0;
        boolean anyExact = false;
        for (int i = 0; i < 36; i++) {
            Item now = inventoryItem(i);
            if (now == null || !now.id.equals(groundItemId)) continue;
            int was = groundItemId.equals(groundBeforeIds[i]) ? groundBeforeCounts[i] : 0;
            if (now.count <= was) continue;
            gain[i] = now.count - was;
            total += gain[i];
            if (sameComponents(want, now)) anyExact = true;
        }
        if (total > 0) groundBatch = true;
        if (total == 0) {
            if (++groundFinishTicks >= GROUND_FINISH_TICKS) {
                if (groundTaken) debug("Picked up {} from the ground but the inventory never showed it.", groundLabel);
                endGround(false);
            }
            return;
        }

        int moveFrom = -1;
        Set<Integer> keep = keepSlots();
        for (int i = 0; i < 36; i++) {
            if (gain[i] == 0) continue;
            Item now = inventoryItem(i);
            // The components may read differently on the ground and in a slot: only then is the item id enough
            if (anyExact && !sameComponents(want, now)) continue;
            boolean pre = groundItemId.equals(groundBeforeIds[i]);
            if (carry.contains(i)) {
                // An empty carry slot, or stash cargo already there: stash cargo from now on, so tools and food picked up
                // this way are not taken for the bot's own
                if (!pre || groundItemId.equals(stashHeld.get(i))) stashHeld.put(i, groundItemId);
            }
            // Landed in a free slot outside the carry slots (and not one that is kept): moved into a carry slot below. Merged
            // into a stack of the bot's own, it can't be told apart and stays where it is
            else if (!pre && !keep.contains(i) && moveFrom < 0) moveFrom = i;
        }

        if (moveFrom >= 0) {
            if (openContainerId() != 0 || holdingCursor() || busy()) {
                if (++groundFinishTicks > GROUND_FINISH_TICKS * 4) endGround(false);
                return;
            }
            int free = freeCarrySlot();
            if (free != -1) {
                RequestFuture future = submit(new ClickItem(0, invSlot(moveFrom), ClickItemAction.LEFT_CLICK), new ClickItem(0, invSlot(free), ClickItemAction.LEFT_CLICK));
                if (future == RequestFuture.rejected) {
                    if (++groundFinishTicks > GROUND_FINISH_TICKS * 4) endGround(false);
                    return;
                }
                stashHeld.put(free, groundItemId);
                groundMove = future;
                groundFinishTicks = 0;
                return;
            }
        }
        endGround(false);
    }

    private static boolean sameComponents(@Nullable ItemStack want, @Nullable Item have) {
        if (want == null || have == null || have.raw == null) return true;
        return Objects.equals(want.getDataComponentsOrEmpty(), have.raw.getDataComponentsOrEmpty());
    }

    private void giveUpOnGround(String why) {
        info("Couldn't pick up the {} from the ground at {}: {}. Leaving it alone for 5 minutes.", groundItemLabel, groundCell == null ? "?" : fmt(groundCell), why);
        endGround(true);
    }

    private void endGround(boolean skip) {
        if (skip && groundId >= 0) groundSkipped.put(groundId, System.currentTimeMillis() + GROUND_SKIP_MS);
        activity.releaseOurPathing();
        restoreRules();
        groundId = -1;
        groundFinishing = groundTaken = false;
        groundMove = null;
        job = null;
        target = null;
        wait = CONFIG.delayTicks;
        groundStatus = "nothing on the ground";
    }

    // Containers other players used

    /**
     * Another player opened this container of the stash and has closed it again. It's read again through the normal
     * jobs: while sorting that's at the next chance, with the other modules given way to. With sorting off it's only
     * marked, to be read the next time it runs.
     */
    public void accessed(BlockPos pos) {
        Stash current = Stashes.current();
        Box box = current == null ? null : current.boxAt(pos);
        if (box == null) return;

        if (isEnabled()) {
            // One we've just read ourselves isn't read again for a minute: a late lid event from our own opening, on
            // a laggy server, would otherwise start a loop. The visit is kept, once per container, for after that
            Long readAt = ownWindowAt.get(box);
            long now = System.currentTimeMillis();
            if (readAt != null && now - readAt < REREAD_COOLDOWN_MS) deferredAccess.putIfAbsent(box, now);
            else staleBoxes.add(pos);
            return;
        }

        // What was saved stays in use (searching, planning) until the container is read again
        box.stale = true;
    }

    /**
     * Visits held back by the cooldown are read once it has passed, or dropped if we have read the container again
     * since the visit, which has shown what it holds now.
     */
    private void releaseDeferred() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Box, Long> entry : deferredAccess.entrySet()) {
            Box box = entry.getKey();
            Long readAt = ownWindowAt.get(box);
            if (readAt != null && now - readAt < REREAD_COOLDOWN_MS) continue;

            deferredAccess.remove(box);
            if (readAt == null || readAt <= entry.getValue()) staleBoxes.add(box.pos);
        }
    }

    /**
     * Whether we opened the container just now ourselves, as opposed to somebody else.
     */
    public boolean openedByUs(Box box) {
        long now = System.currentTimeMillis();
        BlockPos clicked = lastOpenClickPos;
        long at = lastOpenClickAt;
        if (clicked != null && at != 0 && now - at <= OPEN_ANSWER_MS && box.has(clicked)) return true;

        // Our window for it is open (this is stamped every tick it is), or was a moment ago, or the click on it is
        // still on its way: the server's news can be late
        Long windowAt = ownWindowAt.get(box);
        if (windowAt != null && now - windowAt <= OWN_WINDOW_GRACE_MS) return true;
        return clicked != null && box.has(clicked) && activity.ownPathing();
    }

    /**
     * Forgets what is known of the containers that were used, so they're read again. Between jobs, so nothing being
     * worked on changes under it.
     */
    private void applyStale() {
        BlockPos pos;
        while ((pos = staleBoxes.poll()) != null) {
            Box box = stash.boxAt(pos);
            if (box == null || box.excluded) continue;

            // Marked, not wiped: searching and planning go on with what is saved until it is read again
            box.stale = true;
            clearStalled();
            compacted.remove(box);
            forgetDropOffBackoff(box);
            idle = false;
            info("A player used the chest at {}, re-checking it.", box.pos);
        }
    }

    // Labelling: the librarian. An item no container has a sign for gets a new category on an empty chest next to the
    // signed ones, with a sign the bot puts up itself. The bot never places or breaks a chest, and never breaks a sign.

    private static final int MAX_LABELS_PER_RUN = 20;
    private static final int LABEL_INPUT_PRIORITY = 1;
    private static final int LABEL_WALK_TIMEOUT = 20 * 60;
    private static final int LABEL_WAIT_TICKS = 100;
    private static final int LABEL_MAX_CLICKS = 3;
    private static final int MAX_LABEL_FAILURES = 2;
    private int labelFailures;
    private boolean labelFailWarned;
    private static final int SIGN_LINE_LENGTH = 15;
    private static final DateTimeFormatter SIGN_DATE = DateTimeFormatter.ofPattern("MM-dd-yyyy");

    private enum LabelStep { Walk, Hold, Aim, Click, Editor, Write, Verify, Reopen }

    // What the label being planned or put up is for, for the step line
    private String labelItemName = "the items";

    private record LabelPlan(String text, boolean sameLabel, Box box, Containers.Face face) {
    }

    private static final class Group {
        final Item sample;
        int stacks;
        boolean sameLabel;

        Group(Item sample) {
            this.sample = sample;
        }
    }

    // The sign being put up, if any
    private volatile @Nullable LabelStep labelStep;
    private @Nullable LabelStep labelResume;
    private @Nullable String labelText;
    private String @Nullable [] labelLines;
    private Containers.@Nullable Face labelFace;
    private int labelTicks;
    private int labelClicks;
    private boolean labelReopened, reopenRequested;
    private boolean planHadGroups, planHadCandidates;
    private Set<Box> anchored = Set.of();
    private float aimYaw, aimPitch;
    private @Nullable InputRequestFuture clickFuture;
    private int heldFrom = -1, heldTo = -1, heldBefore = -1;
    private @Nullable InventoryAction[] restoreActions;

    // Signs the bot carries for labelling, not to be put away; when the last sign went up; how many this run
    private final Set<Integer> reservedSigns = new HashSet<>();
    private long lastLabelTick = Long.MIN_VALUE / 4;
    private int labelsPlaced;
    private boolean labelCapWarned, noSignsWarned;
    // Blank signs the bot put up itself and could not write on, and how often writing them has been tried
    private final Map<BlockPos, LabelPlan> blankSigns = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> blankTries = new HashMap<>();
    private long nextLabelScan;
    private int labelScanVersion = -1;
    private final Map<Box, Long> ownSignUntil = new HashMap<>();
    private volatile String labelsSummary = "Nothing looked at yet.";

    /**
     * Between the jobs that read the stash: if something is homeless, a chest to label and a sign to write it on exist,
     * start on it.
     * @return true when a job was started
     */
    private boolean nextLabelJob() {
        if (!CONFIG.autoLabel || CONFIG.mode != Mode.Signs) {
            reservedSigns.clear();
            return false;
        }
        if (!blankSigns.isEmpty() && tickCount - lastLabelTick >= CONFIG.labelGapSeconds * 20L) {
            var it = blankSigns.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                LabelPlan old = entry.getValue();
                BlockPos at = entry.getKey();
                Containers.Signs now = Containers.readSignsIfLoaded(old.box());
                // Gone, or written on by someone: nothing left to do
                if (!signThere(at) || now == null || !now.labels().isEmpty() || !old.box().labels.isEmpty()) {
                    it.remove();
                    continue;
                }
                it.remove();
                info("Going back to the blank sign at {} to write \"{}\" on it.", at, old.text());
                startLabel(old);
                labelResume = LabelStep.Reopen;
                labelReopened = false;
                return true;
            }
        }
        if (labelFailures >= MAX_LABEL_FAILURES) {
            if (!labelFailWarned) {
                labelFailWarned = true;
                warn("Putting up signs failed {} times this run, not labelling any more until sorting is restarted. Stacks with no home go to a misc or unsigned container.", labelFailures);
            }
            reservedSigns.clear();
            return false;
        }
        if (labelsPlaced >= MAX_LABELS_PER_RUN) {
            if (!labelCapWarned) {
                labelCapWarned = true;
                warn("Put up {} signs this run, which is the most in one run. Not labelling any more until sorting is restarted.", labelsPlaced);
            }
            reservedSigns.clear();
            return false;
        }
        if (tickCount - lastLabelTick < CONFIG.labelGapSeconds * 20L) return false;

        // Looking at everything costs: only when something changed, or now and then
        if (reservedSigns.isEmpty() && tickCount < nextLabelScan && Stashes.version == labelScanVersion) return false;
        nextLabelScan = tickCount + 100;
        labelScanVersion = Stashes.version;

        LabelPlan plan = planLabel();
        if (plan == null) {
            // Signs fetched for this are kept while there is still homeless things and chests to put them on, and put away
            // once, when there is not: otherwise they go back in a container only to be fetched again
            if (!(planHadGroups && planHadCandidates)) reservedSigns.clear();
            return false;
        }

        // Signs fetched from the stash or carried before the bot's own, which are the last thing to use
        int signSlot = findSignSlot(false);
        if (signSlot == -1) {
            List<Box> holding = stash.boxes.stream().filter(b -> b.sortable() && !blocked(b, Job.FetchSigns) && !signPending(b) && holdsSigns(b)).toList();
            Box from = nearest(holding);
            if (from == null || freeCarrySlot() == -1) {
                int own = findSignSlot(true);
                if (own != -1) {
                    noSignsWarned = false;
                    return startLabel(plan);
                }
                if (from != null) return false;
                if (!noSignsWarned) {
                    noSignsWarned = true;
                    alert("No signs to label new categories (\"{}\" and others), so those items go to the catch-all container. Put signs in the bot's inventory or in any container of the stash.", plan.text());
                }
                return false;
            }
            return start(Job.FetchSigns, from);
        }
        noSignsWarned = false;
        if (carry.contains(signSlot)) reservedSigns.add(signSlot);

        return startLabel(plan);
    }

    private static boolean holdsSigns(Box box) {
        for (Item item : box.contents) if (item != null && Containers.isSignItem(item.id)) return true;
        return false;
    }

    /**
     * Takes a stack of signs out of the container the job is at into a free carry slot, where they are kept for labelling.
     */
    private boolean fetchSignsOne(Container c) {
        if (actions > 0) return false;

        int into = freeCarrySlot();
        if (into == -1) return false;
        for (int i = 0; i < Stashes.slots(c); i++) {
            Item item = Item.of(c.getItemStack(i));
            if (item == null || !Containers.isSignItem(item.id)) continue;
            clicks(c, i, toWindow(into, c));
            stashHeld.put(into, item.id);
            reservedSigns.add(into);
            return true;
        }
        return false;
    }

    /**
     * A slot with signs in it that isn't kept, carry slots first.
     */
    private int findSignSlot(boolean own) {
        Set<Integer> keep = keepSlots();
        for (int slot : reservedSigns) {
            Item item = inventoryItem(slot);
            if (item != null && Containers.isSignItem(item.id)) return slot;
        }
        for (int slot : carry) {
            Item item = inventoryItem(slot);
            if (item != null && Containers.isSignItem(item.id)) return slot;
        }
        // The bot's own signs, in any other slot, only as a last resort
        if (!own) return -1;
        for (int slot = 0; slot < 36; slot++) {
            Item item = keep.contains(slot) ? null : inventoryItem(slot);
            if (item != null && Containers.isSignItem(item.id)) return slot;
        }
        return -1;
    }

    // Planning

    /**
     * What is homeless, grouped by the sign it would get, and the chest to put it on: the largest group first, on the
     * nearest empty unsigned chest that has signed ones next to it (a double one for a lot of stacks).
     */
    private @Nullable LabelPlan planLabel() {
        planHadGroups = planHadCandidates = false;
        anchored = anchoredBoxes();
        Map<Box, Containers.Face> candidates = labelCandidateBoxes();
        planHadCandidates = !candidates.isEmpty();
        if (candidates.isEmpty()) {
            labelsSummary = "No empty, unsigned chest within 2 blocks of a signed one to put a new category on.";
            return null;
        }

        Map<String, Item> known = knownItems();
        Map<String, Integer> current = new HashMap<>();
        for (Item item : known.values()) current.put(item.kindKey(), signedScore(item));
        Map<String, String> chosen = new HashMap<>();

        List<Item> sources = new ArrayList<>();
        for (int slot : activeCarried()) {
            Item item = inventoryItem(slot);
            if (item != null) sources.add(item);
        }
        for (Box box : stash.boxes) {
            if (!box.known() || box.unreachable || box.excluded) continue;
            if (!(box.dropOff || box.labels.isEmpty() || isMisc(box))) {
                // Used items kept apart that are in a container signed for full ones, and have nowhere to go
                if (CONFIG.splitDurability) {
                    for (Item item : box.contents) if (item != null && usedApart(labelSubject(item)) && destinationsFor(item).isEmpty()) sources.add(item);
                }
                continue;
            }
            for (Item item : box.contents) if (item != null) sources.add(item);
        }

        Map<String, Group> groups = new HashMap<>();
        for (Item item : sources) {
            if (Containers.isSignItem(item.id)) continue;
            // A kit with a chest of its own is never homeless, and never gets a new category
            if (kitRouted(item)) continue;
            Item subject = labelSubject(item);
            if (mixedShulker(item) && !keepWholeBoxes().isEmpty()) continue;

            List<Box> dests = destinationsFor(item);
            boolean homeless = dests.isEmpty() || dests.stream().allMatch(this::isMisc);
            String text;
            boolean same = false;
            if (homeless) {
                if (!chosen.containsKey(subject.kindKey())) chosen.put(subject.kindKey(), chooseLabel(subject, known, current));
                text = chosen.get(subject.kindKey());
            }
            else {
                // A container for it exists, but every one is full: another chest with the same sign
                if (dests.stream().anyMatch(b -> b.free() > 0 || misplacedCount(b) > 0)) continue;
                text = signTextOf(dests, usedApart(subject));
                same = true;
            }
            if (text == null) continue;

            Group group = groups.computeIfAbsent(text, t -> new Group(subject));
            group.stacks++;
            group.sameLabel |= same;
        }

        StringBuilder summary = new StringBuilder();
        for (var entry : groups.entrySet()) summary.append(entry.getKey()).append(" x").append(entry.getValue().stacks).append(", ");
        labelsSummary = groups.isEmpty() ? "Nothing is homeless." : "Homeless: " + summary.substring(0, summary.length() - 2) + ". "
            + candidates.size() + " chest(s) to label.";
        planHadGroups = !groups.isEmpty();
        if (groups.isEmpty()) return null;

        String best = null;
        for (var entry : groups.entrySet()) {
            if (best == null || entry.getValue().stacks > groups.get(best).stacks
                || (entry.getValue().stacks == groups.get(best).stacks && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
            }
        }
        Group group = groups.get(best);

        List<Box> options = new ArrayList<>(candidates.keySet());
        if (group.stacks > 27) {
            List<Box> doubles = options.stream().filter(b -> b.parts.size() > 1).toList();
            if (!doubles.isEmpty()) options = new ArrayList<>(doubles);
        }
        Box box = nearest(options);
        labelItemName = group.sample.label();
        return box == null ? null : new LabelPlan(best, group.sameLabel, box, candidates.get(box));
    }

    /**
     * What a stack is labelled by: what a shulker is mostly full of, else the stack itself.
     */
    private Item labelSubject(Item item) {
        if (item.isShulker()) {
            Item inside = Categories.dominantContent(item);
            if (inside != null) return inside;
        }
        return item;
    }

    /**
     * Empty chests with no sign at all (a blank one counts) that a new category can go on: close to a signed one, not
     * reserved by anything else, with a free side to put the sign on.
     */
    private Map<Box, Containers.Face> labelCandidateBoxes() {
        Map<Box, Containers.Face> out = new LinkedHashMap<>();
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff || box.labelFailed || !box.labels.isEmpty() || box.autoLabel != null || kitReserved(box)) continue;
            if (signPending(box) || staleBoxes.contains(box.pos) || deferredAccess.containsKey(box)) continue;

            boolean empty = true;
            for (Item item : box.contents) if (item != null) empty = false;
            if (!empty || !nearSigned(box)) continue;

            Containers.Signs signs = Containers.readSignsIfLoaded(box);
            if (signs == null || signs.anyBlock()) continue;
            List<Containers.Face> faces = Containers.signFaces(box, stash);
            if (faces == null || faces.isEmpty()) continue;
            out.put(box, faces.getFirst());
        }
        return out;
    }

    /**
     * A chest only gets a sign when it is next to the stash the player built: within 2 blocks of a container the player
     * signed, or of one the bot signed that is itself linked, a step of 2 blocks at a time, to one the player signed. A
     * stash grows from the player's own signs, never from something the bot started on its own.
     */
    private boolean nearSigned(Box box) {
        for (Box other : anchored) {
            if (other != box && chebyshev(box, other) <= 2) return true;
        }
        return false;
    }

    private Set<Box> anchoredBoxes() {
        Set<Box> found = new HashSet<>();
        List<Box> queue = new ArrayList<>();
        for (Box box : stash.boxes) {
            if (!box.labels.isEmpty() && box.autoLabel == null) {
                found.add(box);
                queue.add(box);
            }
        }
        for (int i = 0; i < queue.size(); i++) {
            Box from = queue.get(i);
            for (Box box : stash.boxes) {
                if (box.autoLabel != null && !box.labels.isEmpty() && !found.contains(box) && chebyshev(from, box) <= 2) {
                    found.add(box);
                    queue.add(box);
                }
            }
        }
        return found;
    }

    private static int chebyshev(Box a, Box b) {
        int best = Integer.MAX_VALUE;
        for (BlockPos p : a.parts) {
            for (BlockPos q : b.parts) {
                best = Math.min(best, Math.max(Math.abs(p.x() - q.x()), Math.max(Math.abs(p.y() - q.y()), Math.abs(p.z() - q.z()))));
            }
        }
        return best;
    }

    /**
     * Every kind of stack in the stash and being carried, one of each (used and full count as two with split durability).
     */
    private Map<String, Item> knownItems() {
        Map<String, Item> known = new HashMap<>();
        for (Box box : stash.boxes) {
            if (!box.known()) continue;
            for (Item item : box.contents) {
                if (item == null) continue;
                known.putIfAbsent(item.kindKey(), item);
                Item inside = item.isShulker() ? Categories.dominantContent(item) : null;
                if (inside != null) known.putIfAbsent(inside.kindKey(), inside);
            }
        }
        for (int slot : carriedSlots()) {
            Item item = inventoryItem(slot);
            if (item != null) known.putIfAbsent(item.kindKey(), item);
        }
        return known;
    }

    /**
     * The best any container with a sign scores an item, 0 if none does. Catch-all containers score 1.
     */
    private int signedScore(Item item) {
        Categories.Described described = Categories.describe(item);
        int best = 0;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff) continue;
            best = Math.max(best, Categories.boxScore(scoredLabels(box), described));
        }
        return best;
    }

    /**
     * The first line of a new sign for this item, or null if nothing it could say is safe. In turn the category names it
     * fits, then its own name. A name must come back from reading the sign as exactly this item, a category as that
     * category, and it must not pull an item that already has a container (a real match, 40 or more) to another: those
     * must not score higher under the new sign than under their own. Short enough to fit a line of a sign.
     */
    private @Nullable String chooseLabel(Item item, Map<String, Item> known, Map<String, Integer> current) {
        Categories.Described described = Categories.describe(item);
        String name = Item.prettyName(item.id);
        // Used items kept apart get a container of their own, by name: "Used Diamond Sword"
        List<String> candidates = usedApart(item) ? List.of(USED + name) : Categories.labelCandidates(item);

        for (String text : candidates) {
            String[] lines = signLines(text);
            if (lines == null) continue;
            boolean byName = text.equals(name) || text.equals(USED + name);
            // A used items sign is read as a whole (see scoredLabels)
            List<String> labels = usedApart(item) ? List.of(text) : Containers.labelsOf(List.of(lines));

            int got = Categories.boxScore(labels, described);
            if (got < (byName ? 100 : 40)) continue;

            boolean steals = false;
            for (Item other : known.values()) {
                int home = current.getOrDefault(other.kindKey(), 0);
                // Only a real home is protected: a weak match or the catch-all would just as well be somewhere else
                if (home < 40 || other.kindKey().equals(item.kindKey())) continue;
                int now = Categories.boxScore(labels, Categories.describe(other));
                if (now > home) {
                    steals = true;
                    break;
                }
            }
            if (!steals) return text;
        }
        return null;
    }

    /**
     * The words the signs of these full containers have in common to copy: a line that is a word we know, not a date.
     * For used items kept apart, that line with "Used" in front unless it says used already, and never "Used" alone.
     */
    private @Nullable String signTextOf(List<Box> boxes, boolean used) {
        for (Box box : boxes) {
            if (used && box.autoLabel != null && Categories.marksUsed(box.autoLabel) && labelBody(box.autoLabel) != null) return box.autoLabel;
            // A used sign written by hand over several lines: the lines together ("Used Netherite Chestplate"), not one of them
            List<String> labels = used ? box.labels.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList() : box.labels;
            for (String label : labels) {
                if (label.matches(".*\\d+[-/.]\\d+[-/.]\\d+.*") || !Categories.recognises(label)) continue;
                if (!used) {
                    if (label.length() <= SIGN_LINE_LENGTH) return label;
                    continue;
                }
                if (Categories.isUsedOnly(label)) continue;
                String text = Categories.marksUsed(label) ? label : USED + label;
                if (labelBody(text) != null) return text;
            }
        }
        return null;
    }

    private static final String USED = "Used ";

    /**
     * Split durability is on and this is a used item: it goes in a container of its own, apart from full ones.
     */
    private static boolean usedApart(Item item) {
        return CONFIG.splitDurability && item.damaged;
    }

    /**
     * The four lines of a new sign saying this, with the date: on the first line, or for used items too long for one,
     * "Used" on a line of its own and the name over the next ones. Null if it doesn't fit.
     */
    private static String @Nullable [] signLines(String text) {
        List<String> body = labelBody(text);
        if (body == null) return null;
        String[] lines = {"", "", "", ""};
        for (int i = 0; i < body.size(); i++) lines[i] = body.get(i);
        if (body.size() < 4) lines[body.size()] = signDate();
        return lines;
    }

    /**
     * The lines a label is written over, without the date, or null if it doesn't fit on a sign.
     */
    private static @Nullable List<String> labelBody(String text) {
        if (text.length() <= SIGN_LINE_LENGTH) return List.of(text);
        if (!text.startsWith(USED)) return null;
        List<String> lines = new ArrayList<>();
        lines.add(USED.trim());
        StringBuilder line = new StringBuilder();
        for (String word : text.substring(USED.length()).split(" ")) {
            if (word.length() > SIGN_LINE_LENGTH) return null;
            if (!line.isEmpty() && line.length() + 1 + word.length() > SIGN_LINE_LENGTH) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines.size() <= 4 ? lines : null;
    }

    /**
     * What a container's sign is scored by: its lines, or for a used items sign the bot wrote, the label as a whole. Its
     * name can be over several lines ("Netherite" / "Chestplate"), and each alone would take more than it should.
     */
    private static List<String> scoredLabels(Box box) {
        if (CONFIG.splitDurability && box.autoLabel != null && Categories.marksUsed(box.autoLabel)) return List.of(box.autoLabel);
        return box.labels;
    }

    private static String signDate() {
        return LocalDate.now().format(SIGN_DATE);
    }

    // Doing it

    private boolean startLabel(LabelPlan plan) {
        markJobStart();
        job = Job.Label;
        target = plan.box();
        step = Step.Walking;
        handle = null;
        breakRetries = 0;
        actions = 0;
        tries = 0;
        stepTicks = 0;
        jobInterruptions = 0;
        resetJobState();

        labelText = plan.text();
        step("labelling: putting up \"" + plan.text() + "\" on the chest at " + xyz(plan.box().pos) + "; next: sort " + labelItemName + " into it.");
        labelLines = signLines(plan.text());
        if (labelLines == null) labelLines = new String[] {plan.text(), signDate(), "", ""};
        labelFace = plan.face();
        labelStep = LabelStep.Walk;
        labelResume = null;
        labelTicks = 0;
        labelClicks = 0;
        labelReopened = false;
        reopenRequested = false;
        clickFuture = null;
        return true;
    }

    /**
     * After the click on the chest, until the text is written, the sign must not be left half done: nothing is given
     * way to in those few ticks, one packet touches nothing shared.
     */
    private boolean labelCritical() {
        LabelStep s = labelStep;
        return job == Job.Label && (s == LabelStep.Editor || s == LabelStep.Write || s == LabelStep.Verify || s == LabelStep.Reopen);
    }

    private void tickLabel() {
        labelTicks++;
        if (busy()) return;

        switch (labelStep) {
            case Walk -> labelWalk();
            case Hold -> labelHold();
            case Aim -> labelAim();
            case Click -> labelClick();
            case Editor -> labelEditor();
            case Write -> labelWrite();
            case Verify -> labelVerify();
            case Reopen -> labelReopen();
        }
    }

    /**
     * To the block the sign will stand in, in front of the chest: from there the face is straight ahead.
     */
    private void labelWalk() {
        BlockPos cell = labelFace.stand();
        if (playerFeet().equals(cell)) {
            activity.releaseOurPathing();
            labelStep = labelResume != null ? labelResume : LabelStep.Hold;
            labelResume = null;
            labelTicks = 0;
            return;
        }
        if (labelTicks > LABEL_WALK_TIMEOUT) {
            activity.releaseOurPathing();
            labelFailed("Couldn't get to where the sign at " + labelFace.sign() + " can be put up from (" + cell + ").");
            return;
        }
        if (activity.mayRequest()) {
            rules(false, false);
            GoalBlock goal = new GoalBlock(cell);
            PathingRequestFuture future = BARITONE.pathTo(goal);
            activity.notePath(future, BARITONE.getCustomGoalProcess(), goal, tickCount);
            wait = 10;
        }
    }

    /**
     * A sign in a hotbar slot that isn't a kept one, and in the hand: where it already is, else swapped in (and back
     * afterwards).
     */
    private void labelHold() {
        int src = findSignSlot(true);
        if (src == -1) {
            labelFailed("The signs ran out.");
            return;
        }

        Set<Integer> keep = keepSlots();
        int hotbar = -1;
        if (src < 9) hotbar = src;
        else {
            for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i) && inventoryItem(i) == null) hotbar = i;
            for (int i = 0; i < 9 && hotbar == -1; i++) if (!keep.contains(i)) hotbar = i;
        }
        if (hotbar == -1) {
            labelFailed("No hotbar slot I'm allowed to hold a sign in.");
            return;
        }

        List<InventoryAction> request = new ArrayList<>();
        if (hotbar != src) {
            request.add(new MoveToHotbarSlot(invSlot(src), MoveToHotbarAction.from(hotbar)));
            heldFrom = src;
            heldTo = hotbar;
        }
        int held = CACHE.getPlayerCache().getHeldItemSlot();
        if (held != hotbar) {
            if (heldBefore == -1) heldBefore = held;
            request.add(new SetHeldItem(hotbar));
        }
        if (!request.isEmpty() && submit(request.toArray(new InventoryAction[0])) == RequestFuture.rejected) {
            heldFrom = heldTo = -1;
            return;
        }

        labelStep = LabelStep.Aim;
        labelTicks = 0;
        wait = CONFIG.delayTicks;
    }

    /**
     * One tick of sneaking, looking at the middle of the chest's side. The click needs the look to be already there when
     * it is made, and the server needs to have been told we sneak a tick before, or the click opens the chest.
     */
    private void labelAim() {
        BlockPos part = labelFace.part();
        Direction dir = labelFace.dir();
        double px = CACHE.getPlayerCache().getX(), py = CACHE.getPlayerCache().getY(), pz = CACHE.getPlayerCache().getZ();
        // A point just inside the chest's side, and the eyes of a sneaking player
        double tx = part.x() + 0.5 + dir.x() * 0.4, ty = part.y() + 0.5, tz = part.z() + 0.5 + dir.z() * 0.4;
        double eye = py + 1.27;
        double distance = Math.sqrt((tx - px) * (tx - px) + (tz - pz) * (tz - pz));
        aimYaw = (float) (Math.toDegrees(Math.atan2(tz - pz, tx - px)) - 90.0);
        aimPitch = (float) -Math.toDegrees(Math.atan2(ty - eye, distance));

        InputRequestFuture future = INPUTS.submit(InputRequest.builder().owner(this)
            .input(Input.builder().sneaking(true).build()).yaw(aimYaw).pitch(aimPitch).priority(LABEL_INPUT_PRIORITY).build());
        activity.noteInput(future, tickCount);
        if (future == InputRequestFuture.rejected) return;

        labelStep = LabelStep.Click;
        labelTicks = 0;
    }

    /**
     * The right click on the chest's side, still sneaking and still looking at it: the sign goes against that side.
     */
    private void labelClick() {
        BlockPos part = labelFace.part();
        // Should it open the chest after all, it's ours to close
        lastOpenClickPos = part;
        openRequestedAt = lastOpenClickAt = System.currentTimeMillis();
        foreignSinceClick = false;

        InputRequestFuture future = INPUTS.submit(InputRequest.builder().owner(this)
            .input(Input.builder().sneaking(true).rightClick(true)
                .clickTarget(new ClickTarget.BlockPosition(part.x(), part.y(), part.z())).clickRequiresRotation(true).build())
            .yaw(aimYaw).pitch(aimPitch).priority(LABEL_INPUT_PRIORITY).build());
        activity.noteInput(future, tickCount);
        if (future == InputRequestFuture.rejected) return;

        clickFuture = future;
        MODULE.get(StashTracker.class).awaitEditor(labelFace.sign());
        labelStep = LabelStep.Editor;
        labelTicks = 0;
    }

    /**
     * Was it a click on the chest's side, and has the server opened the editor for the sign it put up?
     */
    private void labelEditor() {
        StashTracker tracker = MODULE.get(StashTracker.class);
        if (openContainerId() != 0) {
            // Sneaking didn't take and it opened the chest: close it and go again
            if (closeSafely() == Close.Stuck) return;
            tracker.stopAwaitingEditor();
            labelClickAgain("the chest opened instead of taking a sign");
            return;
        }

        InputRequestFuture future = clickFuture;
        if (future != null && future.isDone()) {
            clickFuture = null;
            boolean onBlock = future.isAccepted() && future.getClickResult() instanceof ClickResult.RightClickResult result
                && result.getType() == ClickResult.RightClickResult.RightClickType.USE_ITEM_ON_BLOCK;
            if (!onBlock && !signThere(labelFace.sign())) {
                tracker.stopAwaitingEditor();
                labelClickAgain("the click didn't land on the chest");
                return;
            }
        }

        BlockPos spot = labelFace.sign();
        boolean thereNow = World.getBlock(spot.x(), spot.y(), spot.z()).name().endsWith("_wall_sign");
        if (acceptEditor(tracker)) {
            labelStep = LabelStep.Write;
            labelTicks = 0;
        }
        else if (labelTicks > LABEL_WAIT_TICKS) {
            tracker.stopAwaitingEditor();
            if (!thereNow) labelClickAgain("no sign appeared");
            else if (!labelReopened) reopenSign();
            else labelFailed("The sign at " + spot + " was put up but its editor never opened.");
        }
    }

    private boolean signThere(BlockPos spot) {
        return World.getBlock(spot.x(), spot.y(), spot.z()).name().endsWith("_wall_sign");
    }

    /**
     * The editor that opened is for the sign we put up: at the place we expected, or at another that is a sign on the
     * target container (it is then the sign that is written). One anywhere else is none of ours and is left alone.
     */
    private boolean acceptEditor(StashTracker tracker) {
        if (tracker.editorOpened()) {
            editorFront = tracker.editorFront();
            return true;
        }
        BlockPos at = tracker.editorAt();
        if (at == null || !Containers.signSpots(target).contains(at) || !signThere(at)) return false;

        debug("The sign editor opened at {}, not {}: writing there, it is on the chest at {}.", at, labelFace.sign(), target.pos);
        labelFace = new Containers.Face(at, labelFace.dir(), labelFace.part(), labelFace.stand());
        editorFront = tracker.editorFront();
        return true;
    }

    private boolean editorFront = true;

    private void labelClickAgain(String why) {
        if (++labelClicks >= LABEL_MAX_CLICKS) {
            labelFailed("Couldn't put a sign on the chest at " + target.pos + ": " + why + ".");
            return;
        }
        labelStep = LabelStep.Aim;
        labelTicks = 0;
    }

    /**
     * A sign that's there but whose editor didn't open: once, clicked again without sneaking, which opens it for its
     * author.
     */
    private void reopenSign() {
        labelReopened = true;
        reopenRequested = false;
        labelStep = LabelStep.Reopen;
        labelTicks = 0;
    }

    private void labelReopen() {
        BlockPos spot = labelFace.sign();
        StashTracker tracker = MODULE.get(StashTracker.class);
        if (acceptEditor(tracker)) {
            labelStep = LabelStep.Write;
            labelTicks = 0;
            return;
        }
        if (!reopenRequested) {
            // Never over what another module is doing: wait for it, and give up if it goes on
            if (foreignNow || (BARITONE.isActive() && !activity.ownPathing())) {
                if (labelTicks > LABEL_WAIT_TICKS) {
                    tracker.stopAwaitingEditor();
                    labelFailed("The sign at " + spot + " was put up but another module kept the bot busy, so it couldn't be opened.");
                }
                return;
            }
            // A plain right click on the sign itself, not sneaking and looking at it: a sign in the hand is not put
            // against it then, and the author's click opens the editor. Not through the pathfinder, which tries to
            // place the held block against the clicked one
            BlockPos part = labelFace.part();
            Direction dir = labelFace.dir();
            double px = CACHE.getPlayerCache().getX(), py = CACHE.getPlayerCache().getY(), pz = CACHE.getPlayerCache().getZ();
            // The sign lies against the chest's side: its end of the block
            double tx = spot.x() + 0.5 - dir.x() * 0.4, ty = spot.y() + 0.5, tz = spot.z() + 0.5 - dir.z() * 0.4;
            double distance = Math.sqrt((tx - px) * (tx - px) + (tz - pz) * (tz - pz));
            aimYaw = (float) (Math.toDegrees(Math.atan2(tz - pz, tx - px)) - 90.0);
            aimPitch = (float) -Math.toDegrees(Math.atan2(ty - (py + 1.62), distance));

            InputRequestFuture future = INPUTS.submit(InputRequest.builder().owner(this)
                .input(Input.builder().sneaking(false).rightClick(true)
                    .clickTarget(new ClickTarget.BlockPosition(spot.x(), spot.y(), spot.z())).clickRequiresRotation(true).build())
                .yaw(aimYaw).pitch(aimPitch).priority(LABEL_INPUT_PRIORITY).build());
            activity.noteInput(future, tickCount);
            if (future == InputRequestFuture.rejected) return;

            reopenRequested = true;
            labelTicks = 0;
            tracker.awaitEditor(spot);
        }
        else if (labelTicks > LABEL_WAIT_TICKS) {
            tracker.stopAwaitingEditor();
            labelFailed("The sign at " + spot + " was put up but its editor never opened.");
        }
    }

    /**
     * The text, in the one packet the editor sends when it's closed.
     */
    private void labelWrite() {
        BlockPos spot = labelFace.sign();
        MODULE.get(StashTracker.class).stopAwaitingEditor();
        activity.releaseOurPathing();
        sendClientPacketAsync(new ServerboundSignUpdatePacket(spot.x(), spot.y(), spot.z(), labelLines, editorFront));
        debug("Writing \"{}\" on the sign at {} ({} side).", labelText, spot, editorFront ? "front" : "back");
        ownSignUntil.put(target, tickCount + 20 * 120L);
        labelStep = LabelStep.Verify;
        labelTicks = 0;
    }

    /**
     * Reads the sign back, and only then is the chest a category.
     */
    private void labelVerify() {
        Containers.Signs read = Containers.readSignsIfLoaded(target);
        if (read != null && saysLabel(read.labels(), labelText)) {
            Box box = target;
            box.labels.clear();
            box.labels.addAll(read.labels());
            box.autoLabel = labelText;
            box.autoSignPos = labelFace.sign();
            stash.markDirty();
            stash.save();
            Stashes.version++;
            seenVersion = Stashes.version;
            clearStalled();
            stuckCarried.clear();
            stuckWarned = false;
            idle = false;
            labelsPlaced++;
            lastLabelTick = tickCount;
            info("Labelled the chest at {} as \"{}\".", box.pos, labelText);
            endLabel();
        }
        else if (labelTicks > LABEL_WAIT_TICKS) {
            if (!labelReopened) reopenSign();
            else labelFailed("The sign at " + labelFace.sign() + " never showed what was written on it.");
        }
    }

    /**
     * Whether a sign's lines say this label: as a line of its own, or every line it was written over (see signLines).
     */
    private static boolean saysLabel(List<String> labels, @Nullable String text) {
        if (text == null) return false;
        if (labels.contains(text)) return true;
        List<String> body = labelBody(text);
        return body != null && body.size() > 1 && labels.containsAll(body);
    }

    /**
     * Gives up on this chest for the run. A sign left blank is said so: the bot never breaks one.
     */
    private void labelFailed(String why) {
        BlockPos spot = labelFace.sign();
        boolean blank = signThere(spot);
        BlockPos opened = MODULE.get(StashTracker.class).editorAt();
        if (opened != null && !opened.equals(spot)) why += " (an editor opened at " + opened + ")";
        // A blank sign the bot put up is written later, once, by clicking it again
        if (blank && labelText != null && blankTries.merge(spot, 1, Integer::sum) <= 2) {
            blankSigns.put(spot, new LabelPlan(labelText, false, target, labelFace));
        }
        target.labelFailed = true;
        warn("{}{}", why, blank ? " A blank sign is left at " + spot + ", write on it or remove it." : "");
        MODULE.get(StashTracker.class).stopAwaitingEditor();
        // Whatever request was still going (the walk, a click) is let go of, so it is not taken for someone else's
        activity.releaseOurPathing();
        labelFailures++;
        lastLabelTick = tickCount;
        endLabel();
    }

    /**
     * The job is over: the hand and the hotbar are put back as they were.
     */
    private void endLabel() {
        activity.releaseOurPathing();
        List<InventoryAction> back = new ArrayList<>();
        if (heldFrom != -1) back.add(new MoveToHotbarSlot(invSlot(heldFrom), MoveToHotbarAction.from(heldTo)));
        if (heldBefore != -1) back.add(new SetHeldItem(heldBefore));
        heldFrom = heldTo = heldBefore = -1;
        if (!back.isEmpty()) {
            InventoryAction[] actions = back.toArray(new InventoryAction[0]);
            if (submit(actions) == RequestFuture.rejected) restoreActions = actions;
        }

        job = null;
        target = null;
        labelStep = null;
        labelLines = null;
        labelFace = null;
        clickFuture = null;
        wait = CONFIG.delayTicks;
    }

    /**
     * Retries the hand put back that was refused, when the inventory is free.
     */
    private void restoreHand() {
        InventoryAction[] actions = restoreActions;
        if (actions == null || busy()) return;
        restoreActions = null;
        if (submit(actions) == RequestFuture.rejected) restoreActions = actions;
    }

    /**
     * Giving way before the click leaves nothing behind but the hand, and the job is planned again. After it, a sign is
     * up, so it goes back to it.
     */
    private void resumeLabel() {
        LabelStep step = labelStep;
        boolean placed = step == LabelStep.Editor || step == LabelStep.Write || step == LabelStep.Verify || step == LabelStep.Reopen;
        if (placed) {
            labelResume = LabelStep.Reopen;
            labelStep = LabelStep.Walk;
            labelTicks = 0;
            labelReopened = false;
            reopenRequested = false;
            return;
        }
        endLabel();
    }

    /**
     * For the command: what is homeless, what is labelled, what failed and what is waiting.
     */
    public String labelsReport() {
        StringBuilder out = new StringBuilder(labelsSummary).append('\n');
        Stash current = Stashes.current();
        if (current == null) return out.append("No stash yet.").toString();

        int auto = 0;
        StringBuilder failed = new StringBuilder();
        for (Box box : current.boxes) {
            if (box.autoLabel != null) auto++;
            if (box.labelFailed) failed.append(failed.isEmpty() ? "" : ", ").append(box.pos.x()).append(' ').append(box.pos.y()).append(' ').append(box.pos.z());
        }
        out.append("Labelled by the bot: ").append(auto).append(", this run: ").append(labelsPlaced).append("/").append(MAX_LABELS_PER_RUN).append('\n');
        if (!failed.isEmpty()) out.append("Gave up on: ").append(failed).append('\n');
        out.append(CONFIG.autoLabel ? "Auto labelling is on." : "Auto labelling is off.");
        return out.toString();
    }

    // Signs put up, changed or taken down

    /**
     * Keeps the tracker told where signs of the stash can be, and collects what it saw happen there: every tick, so a
     * job on a container whose sign just changed notices.
     */
    /**
     * Signs are watched when sorting by them, and in scan only in either mode: the labels are kept up to date.
     */
    private boolean signsWatched() {
        return CONFIG.watchSigns && (CONFIG.mode == Mode.Signs || !sorts());
    }

    private void watchSignSpots() {
        StashTracker tracker = MODULE.get(StashTracker.class);
        if (!signsWatched()) {
            // Nothing is watched, so the tracker is told to stop queueing
            if (signsPublished) {
                signsPublished = false;
                signOwners.clear();
                signDirtyAt.clear();
                tracker.watchSigns(Set.of());
            }
            return;
        }

        if (tickCount >= nextSpotRefresh) {
            nextSpotRefresh = tickCount + 40;
            signOwners.clear();
            for (Box box : stash.boxes) {
                for (BlockPos spot : Containers.signSpots(box)) signOwners.computeIfAbsent(spot, s -> new ArrayList<>(2)).add(box);
            }
            signsPublished = true;
            tracker.watchSigns(Set.copyOf(signOwners.keySet()));
        }

        tracker.drainSignEvents(pos -> {
            List<Box> owners = signOwners.get(pos);
            if (owners == null) return;
            for (Box box : owners) {
                // Anything happening at a block next to a container (a neighbouring chest, a lid, a block update that
                // changed nothing) is not a sign changing: only what the signs say now differing from what we have is
                Containers.Signs now = Containers.readSignsIfLoaded(box);
                if (now == null) {
                    if (!signDirtyAt.containsKey(box)) signDirtyAt.put(box, tickCount);
                    continue;
                }
                if (signDirtyAt.containsKey(box)) {
                    // Already unsettled: only a different text pushes the settling back
                    if (!now.labels().equals(signSeen.get(box))) {
                        signDirtyAt.put(box, tickCount);
                        signSeen.put(box, now.labels());
                    }
                    continue;
                }
                if (now.labels().equals(box.labels)) {
                    // The proxy's copy of the world may not have caught up with the event yet: looked at once more
                    signRecheck.putIfAbsent(box, tickCount + 2);
                    continue;
                }
                signDirtyAt.put(box, tickCount);
                signSeen.put(box, now.labels());
            }
        });

        for (Map.Entry<Box, Long> entry : new ArrayList<>(signRecheck.entrySet())) {
            if (tickCount < entry.getValue()) continue;
            signRecheck.remove(entry.getKey());
            Box box = entry.getKey();
            Containers.Signs now = Containers.readSignsIfLoaded(box);
            if (now != null && !now.labels().equals(box.labels) && !signDirtyAt.containsKey(box)) {
                signDirtyAt.put(box, tickCount);
                signSeen.put(box, now.labels());
            }
        }
    }

    /**
     * A sign on this container is being changed, so what it takes is not settled.
     */
    private boolean signPending(Box box) {
        return CONFIG.watchSigns && signDirtyAt.containsKey(box);
    }

    /**
     * Between jobs: reads again the signs that something happened at once they've been left alone for a while, and all
     * of them now and then, which catches what was missed (a chunk loading in, a lost packet).
     */
    private void applySignChanges() {
        if (!signsWatched()) return;

        if (tickCount >= nextSignSweep) {
            nextSignSweep = tickCount + SIGN_SWEEP_TICKS;
            sweepSigns();
        }

        long settle = CONFIG.signSettleSeconds * 20L;
        for (Map.Entry<Box, Long> entry : new ArrayList<>(signDirtyAt.entrySet())) {
            if (tickCount - entry.getValue() < settle) continue;
            signDirtyAt.remove(entry.getKey());
            signSeen.remove(entry.getKey());
            signAbortRuns.remove(entry.getKey());
            if (signHeld.remove(entry.getKey())) stalled.remove(entry.getKey());
            // A job on it was ended because its sign was unsettled, and it was left out of the plans meanwhile: it gets
            // another look even if the text turns out the same. An unchanged sign settling otherwise holds nothing back
            if (signAborted.remove(entry.getKey())) {
                clearStalled();
                idle = false;
            }
            else if (!stuckCarried.isEmpty()) idle = false;
            checkSigns(entry.getKey());
        }

        // A sign gone or blank for the whole grace period is really gone
        for (Map.Entry<Box, Long> entry : new ArrayList<>(signGoneSince.entrySet())) {
            if (tickCount - entry.getValue() >= SIGN_REMOVAL_GRACE) checkSigns(entry.getKey());
        }
    }

    private void sweepSigns() {
        for (Box box : stash.boxes) checkSigns(box);
        signDirtyAt.keySet().removeIf(box -> !stash.boxes.contains(box));
        signGoneSince.keySet().removeIf(box -> !stash.boxes.contains(box));
    }

    /**
     * Compares what the signs of a container say now with what we have. A different text is used as it is. No text is
     * only used after a minute of it: a sign taken down and put up again (to fix a typo, say) is not a removal, and a
     * sign just placed is blank until its text arrives. Nothing is changed for a container that isn't in range.
     */
    private void checkSigns(Box box) {
        Containers.Signs read = Containers.readSignsIfLoaded(box);
        if (read == null) return;

        List<String> now = read.labels();
        if (now.equals(box.labels)) {
            signGoneSince.remove(box);
            return;
        }

        if (now.isEmpty()) {
            Long since = signGoneSince.get(box);
            if (since == null) {
                signGoneSince.put(box, tickCount);
                return;
            }
            if (tickCount - since < SIGN_REMOVAL_GRACE) return;
        }

        signGoneSince.remove(box);
        applyLabels(box, now);
    }

    /**
     * The one place a container's labels change after the first read. What was sorted by the old sign is sorted again
     * by the new one: the planner already moves whatever is in the wrong container, so it only has to be told to look.
     */
    private void applyLabels(Box box, List<String> now) {
        List<String> before = new ArrayList<>(box.labels);
        box.labels.clear();
        box.labels.addAll(now);

        // A player's text on a sign the sorter wrote is theirs from then on
        if (box.autoLabel != null && !saysLabel(now, box.autoLabel)) {
            box.autoLabel = null;
            box.autoSignPos = null;
        }

        stash.markDirty();
        Stashes.version++;
        seenVersion = Stashes.version;
        clearStalled();
        stuckCarried.clear();
        stuckWarned = false;
        compacted.remove(box);
        signHeld.remove(box);
        signAbortRuns.remove(box);
        futile.clear();
        strayReported.remove(box);
        unrecognisedReported.remove(box);
        idle = false;

        Long mine = ownSignUntil.get(box);
        if (mine == null || tickCount > mine) {
            String was = before.isEmpty() ? "no sign" : before.getFirst(), is = now.isEmpty() ? "no sign" : now.getFirst();
            if (sorts()) info("Sign at {} changed: \"{}\" -> \"{}\", re-sorting.", box.pos, was, is);
            else info("Sign at {} changed: \"{}\" -> \"{}\" (scan only).", fmt(box.pos), was, is);
        }
        unrecognisedSigns(true);
    }

    // Giving way to other modules

    /**
     * Stay-on mode with nothing to do holds nothing of anyone's, so there's nothing to give way.
     */
    private boolean restingIdle() {
        return idle && job == null && !finishing && !activity.ownInventoryLive() && !(openContainerId() != 0 && ownsWindow());
    }

    /**
     * How long other modules must have been quiet before we start. The configured time, doubled for each further
     * interruption in the last few minutes so a module that keeps coming back isn't raced, up to a minute.
     */
    private int quietTicks() {
        int base = Math.max(0, CONFIG.quietSeconds) * 20;
        if (base == 0) return 0;

        // Read from the command thread too (the status line), so this only counts and never changes anything
        int recent = 0;
        long now = tickCount;
        synchronized (interruptions) {
            for (long at : interruptions) if (now - at <= INTERRUPTION_WINDOW) recent++;
        }
        long scaled = recent > 1 ? (long) base << Math.min(recent - 1, 10) : base;
        return (int) Math.min(scaled, MAX_QUIET_TICKS);
    }

    private void pruneInterruptions() {
        synchronized (interruptions) {
            while (!interruptions.isEmpty() && tickCount - interruptions.peekFirst() > INTERRUPTION_WINDOW) interruptions.pollFirst();
        }
    }

    private boolean quietElapsed() {
        return tickCount - lastForeignTick >= quietTicks();
    }

    /**
     * Another module started something. Everything of ours is let go of first: the path, the clicks, the window.
     */
    private void beginYield(Activity.Reason reason) {
        debug("Giving way: {} ({}), job {}, handle {}.", reason, activity.foreignWho != null ? activity.foreignWho : "-", job, handle);
        if (reason == Activity.Reason.Pathing && waitStart < 0) info("Giving way to {}.", activity.foreignWho != null ? activity.foreignWho : "another module's pathing");
        collectInterrupted("giving way to " + reason.label + (playerControlling() ? " (a player is controlling the bot)" : ""));
        lastForeignTick = tickCount;
        lastReason = reason;
        gate = Gate.Yielding;
        wait = 0;
        cleanupRejected = 0;
        cleanupGaveUp = false;

        // Our own request failing looks the same as another module replacing it. Failing on the same container again
        // and again is given up on like any other container that can't be reached, and doesn't grow the back-off
        boolean failed = activity.superseded() && job != null && handle == null && target != null;
        if (failed && boxFailures.merge(target, 1, Integer::sum) >= MAX_BOX_FAILURES && !target.unreachable) {
            target.unreachable = true;
            if (step == Step.Walking) reportUnreachable(target);
            else reportCantOpen(target);
        }

        // A repair trip puts the gear back before it lets go (it carries on with that while giving way, see tickRepairWhileAway)
        if (job == Job.Repair && repairTrip != null && !repairUndoMode) repairYieldAbort = true;
        // An enchant trip puts the anvil's contents and the gear back the same way before it lets go (the journal carries the item on)
        if (job == Job.Enchant && enchantTrip != null) enchantYield = true;
        activity.releaseOurPathing();
        restoreRules();
        if (job != null) {
            synchronized (interruptions) {
                if (!failed || interruptions.size() < 2) interruptions.addLast(tickCount);
            }
            jobInterruptions++;
        }
        if (stash.isDirty()) stash.save();
        startWaiting(reason);
    }

    /** While giving way with a repair trip in hand, the trip is told to stop and ticks on to put what it moved back. */
    private void tickRepairWhileAway() {
        if (job == Job.Repair && repairTrip != null && repairYieldAbort && !busy()) tickRepair();
        if (job == Job.Enchant && enchantTrip != null && enchantYield && !busy()) tickEnchant();
    }

    private void tickYielding() {
        tickRepairWhileAway();
        noteWaiting(foreignNow ? lastReason : null);
        if (cleanupStep()) {
            swapStep = 0;
            gate = Gate.Waiting;
        }
    }

    /**
     * One step of letting go of our window: our own clicks finish first, then the cursor stack is put away and the
     * window closed in one request, at our own low priority so we never push in. Only a window we opened is touched.
     * @return true when nothing of ours is left to let go of
     */
    private boolean cleanupStep() {
        if (activity.ownInventoryLive()) return false;

        if (openContainerId() == 0 || !ownsWindow()) return true;
        if (cleanupGaveUp && gate == Gate.Yielding) return true;
        // The close is tried again every tick until the proxy takes it: a window held open blocks other modules' clicks
        if (holdingCursor() && cursorSettling()) return false;
        // A crafting table of ours is never closed with something in its grid: it is taken back into the inventory first,
        // and with no room for it the window is left open for a person
        // The cursor stack is put away first, then the grid
        if (tableWindow() != null && craftGridHolds()) {
            int r = craftClearStep();
            if (r > 0) return false;
            if (r < 0) {
                markTableStuck();
                if (!craftStuckSaid) {
                    craftStuckSaid = true;
                    tableStuckAlertAt = tickCount;
                    alert("Giving way with items in the crafting table's grid and no free slot for them: the crafting table is left open. Make room, then close it.");
                }
                return true;
            }
        }
        else craftStuckSaid = false;

        RequestFuture future = closeOnce(false);
        if (future == null) return true;

        activity.noteSubmit(future, true, tickCount);
        if (future == RequestFuture.rejected && !cleanupGaveUp && ++cleanupRejected > CLEANUP_TIMEOUT) {
            cleanupGaveUp = true;
            warn("Couldn't close the container I had open to let another module work, inventory actions kept running. Carrying on.");
        }
        return false;
    }

    private void beginWait() {
        gate = Gate.Waiting;
        restoreRules();
        startWaiting(quietElapsed() ? null : lastReason);
    }

    /**
     * A stack left on the cursor with no window open (the server doesn't always hand it back) is put into a free slot,
     * through the player's own window and at our own priority. With someone else's window open it's theirs to deal
     * with, and with no free slot it stays: nothing is dropped.
     */
    private void returnCursor() {
        if (activity.ownInventoryLive() || inventoryBusy() || cursorSettling()) return;

        // A bundle can't be clicked into a slot: closing the inventory window gives the server's cursor stack back
        if (bundleOnCursor()) {
            noteClose();
            if (submit(new CloseContainer(0)) != RequestFuture.rejected) settleUntil = tickCount + Math.max(CONFIG.delayTicks, 4);
            return;
        }

        Set<Integer> keep = keepSlots();
        for (int slot = 0; slot < 36; slot++) {
            if (keep.contains(slot) || inventoryItem(slot) != null) continue;
            if (submit(new ClickItem(0, invSlot(slot), ClickItemAction.LEFT_CLICK)) != RequestFuture.rejected) {
                settleUntil = tickCount + Math.max(CONFIG.delayTicks, 4);
            }
            return;
        }

        if (!cursorStuckLogged) {
            cursorStuckLogged = true;
            warn("There's a stack on the cursor and no free slot to put it in, waiting until there is one.");
        }
    }

    /**
     * Letting go is done: waiting for them to finish, and then to be quiet for a while.
     * @return true when waiting is over and sorting carries on this tick
     */
    private boolean tickWaiting(Activity.@Nullable Reason foreign) {
        noteWaiting(foreign);
        tickRepairWhileAway();

        // A late answer to our own click: a window of ours opened while we were waiting
        if (openContainerId() != 0 && ownsWindow()) {
            cleanupStep();
            return false;
        }
        if (foreign != null || !quietElapsed()) return false;
        if (holdingCursor()) {
            if (openContainerId() == 0) returnCursor();
            return false;
        }
        cursorStuckLogged = false;

        endWaiting();
        gate = Gate.Running;
        resumeJob();
        return true;
    }

    private static String waitLabel(Activity.@Nullable Reason reason) {
        return reason != null ? reason.label : "a stack on the cursor";
    }

    /**
     * Says once, in the terminal, that we're held up and by what. Not in game: this can go on for a long time.
     */
    private void startWaiting(Activity.@Nullable Reason reason) {
        if (waitStart >= 0) return;
        waitStart = tickCount;
        reminderAt = tickCount + REMINDER_TICKS;
        reasonLogged = reason;
        reasonLoggedAt = tickCount;
        info("Waiting for another module ({}) before sorting.", waitLabel(reason));
        stepWaiting(reason);
    }

    /**
     * A new reason is only mentioned after half a minute, and a reminder comes every ten minutes of waiting.
     */
    private void noteWaiting(Activity.@Nullable Reason foreign) {
        if (waitStart < 0) return;
        if (foreign != null && foreign != reasonLogged && tickCount - reasonLoggedAt > REASON_REPEAT_TICKS) {
            reasonLogged = foreign;
            reasonLoggedAt = tickCount;
            info("Still waiting for another module, now ({}).", foreign.label);
            stepWaiting(foreign);
        }
        if (tickCount >= reminderAt) {
            reminderAt = tickCount + REMINDER_TICKS;
            info("Still waiting for another module ({}), {} minutes so far.", waitLabel(lastReason), (tickCount - waitStart) / (20 * 60));
        }
    }

    /**
     * What ends the rest in stay-on mode, for the idle step line.
     */
    private String wakesIt() {
        String base = "sort again when a container, sign or the ground changes";
        if (!sorts()) return base;

        boolean anyDropOff = stash.boxes.stream().anyMatch(b -> b.dropOff && !b.unreachable);
        if (!anyDropOff) return base;
        if (!CONFIG.smartRecheck) return base + ", or the drop-off check in " + CONFIG.recheckSeconds + "s";

        // Only drop-offs something can fill quietly are checked, and each less often while it stays unchanged
        long soonest = Long.MAX_VALUE;
        for (Box box : stash.boxes) {
            if (!box.dropOff || box.unreachable || changesQuietly(box) == null) continue;
            soonest = Math.min(soonest, Math.max(0, dropOffDue.getOrDefault(box, 0L) - tickCount));
        }
        if (soonest == Long.MAX_VALUE) return base + " (drop-offs are only read when their lid moves)";
        return base + ", or the next hopper-fed drop-off check in " + Math.max(CONFIG.recheckSeconds, (soonest + 19) / 20) + "s (backed off while unchanged)";
    }

    private void stepWaiting(Activity.@Nullable Reason reason) {
        step("waiting: " + waitLabel(reason) + "; next: carry on after " + (quietTicks() + 19) / 20 + "s of quiet.");
    }

    private void endWaiting() {
        if (waitStart >= 0 && tickCount - waitStart > quietTicks()) info("Resuming.");
        final long waited = waitStart;
        // Time spent giving way to other modules doesn't count against finishing a shulker left from before
        if (resuming) resumeDeadline = ShulkerRecovery.deadlineAfterWait(resumeDeadline, waited, tickCount);
        if (waited >= 0) debug(() -> "Waiting over after " + (tickCount - waited) / 20 + " s (quiet needed " + quietTicks() / 20 + " s).");
        waitStart = -1;
        reasonLogged = null;
    }

    /**
     * Picks up where we left off. A job on a container is dropped and planned again: the world has changed under it,
     * and the containers are read as they are now. It isn't counted as getting nothing done.
     */
    private void resumeJob() {
        if (job == null) return;
        if (job == Job.Label) {
            resumeLabel();
            return;
        }
        if (job == Job.Unpack) {
            endUnpack();
            return;
        }
        if (job == Job.Name) {
            // The anvil window was let go of: the shulker is tried again (or delivered unnamed) from where it is now
            // tickName lets the namer finish the abort (take the shulker back out of the anvil, then close it) before it ends
            AnvilNamer n = naming;
            if (n != null) n.abort("gave way to another module");
            else endNaming(new AnvilNamer.Outcome(AnvilNamer.CLOSED, true, false));
            return;
        }
        if (job == Job.Repair) {
            // Nothing in the world was let go of: the trip carries on where it was (it stops itself if someone is near)
            return;
        }
        if (job == Job.Enchant) {
            // The trip was told to stop (enchantYield) and put the anvil's contents and the gear back: it ends itself, and the journal carries the item on
            return;
        }
        if (job == Job.Anvil) {
            // Gave way while putting an anvil down: walked to again from where the bot is
            activity.releaseOurPathing();
            anvilStep = AnvilStep.Walk;
            anvilStepTicks = 0;
            return;
        }
        if (job == Job.Chest) {
            // Gave way while putting a chest up: walked to again from where the bot is
            activity.releaseOurPathing();
            if (chestStep != null) chestStep = ChestStep.Walk;
            chestStepTicks = 0;
            return;
        }
        if (job == Job.Craft) {
            // The table window was let go of: the trip empties the grid (if it is still open) and is made again
            CraftTrip t = crafter;
            if (t != null) t.abort("gave way to another module");
            else endCraftTrip(new CraftTrip.Outcome(CraftTrip.CLOSED, true, false, false, 0));
            return;
        }
        if (job == Job.Explore) {
            // What has been seen is kept: it carries on from there
            endExplore();
            return;
        }
        if (job == Job.Ground) {
            // Looked at again from scratch: the item may have gone, or a player come
            endGround(false);
            return;
        }
        if (handle != null) {
            resumeHandle();
            return;
        }

        if (job == Job.Compact && target != null) compacted.remove(target);
        putBackTrip = false;
        job = null;
        target = null;
        jobInterruptions = 0;
        resetJobState();
    }

    /**
     * Repacking has a shulker in the world, so it carries on from the same step instead of starting over: walking back
     * to the shulker first if we were moved away from it.
     */
    private void resumeHandle() {
        boolean inWorld = placed != null && placed(placed);

        // Interrupted over and over before anything was put down: plan something else
        if (jobInterruptions > MAX_JOB_INTERRUPTIONS && !inWorld) {
            endHandling();
            jobInterruptions = 0;
            return;
        }

        Handle next = switch (handle) {
            case Hold -> Handle.Hold;
            case Place -> inWorld && pickupWatch.putDown(countInInv(this::isShulkerBox)) ? Handle.Open : Handle.Hold;
            case Open, Work -> Handle.Open;
            case Break -> Handle.Break;
            case Collect -> Handle.Collect;
            case Return -> returnTo != null ? returnTo : Handle.Open;
            case Leave -> Handle.Leave;
        };

        boolean needsShulker = next == Handle.Open || next == Handle.Break;
        if (needsShulker && inWorld && !arrivedAt(new GoalNear(placed, REACH_RANGE_SQ))) {
            returnTo = next;
            handle = Handle.Return;
            handleTicks = 0;
            return;
        }
        enterHandle(next);
    }

    private void enterHandle(Handle next) {
        handle = next;
        handleTicks = 0;
        returnTo = null;
        // The window was closed on the way: open it again, and only a window that opens after the new click is ours
        if (next == Handle.Open) {
            openRequestedAt = Long.MAX_VALUE;
            openIdBefore = openContainerId();
        }
        if (next == Handle.Collect) collectStarted();
    }

    /**
     * Never dig or build our way between containers: Baritone's own settings are overridden right before a request
     * that needs them and put back as soon as that request is over (checked every tick), and whenever we stop, wait or
     * give way, or the tick fails, so other modules never see our values.
     */
    private void rules(boolean breaking, boolean placing) {
        setBreakingAllowed(breaking);
        setPlacingAllowed(placing);
        applySafeWalk();
    }

    private void restoreRules() {
        restoreBreaking();
        restorePlacing();
        restoreSafeWalk();
    }

    // Safe walking: the pathfinder's settings while a request of ours is outstanding, saved and put back like the rules
    // above. Only these Config.Client.Extra.Pathfinder fields are touched: allowParkour, allowParkourAscend,
    // allowParkourPlace, allowDiagonalDescend, maxFallHeightNoWater (1: only the ordinary one-block step down is left,
    // a fall of two or more is refused) and allowLongFall
    private boolean safeSaved;
    private boolean savedParkour, savedParkourAscend, savedParkourPlace, savedDiagonalDescend, savedLongFall;
    private int savedMaxFall;

    private void applySafeWalk() {
        if (!CONFIG.safeWalk) {
            restoreSafeWalk();
            return;
        }
        var pf = com.aquarius.Globals.CONFIG.client.extra.pathfinder;
        if (!safeSaved) {
            savedParkour = pf.allowParkour;
            savedParkourAscend = pf.allowParkourAscend;
            savedParkourPlace = pf.allowParkourPlace;
            savedDiagonalDescend = pf.allowDiagonalDescend;
            savedLongFall = pf.allowLongFall;
            savedMaxFall = pf.maxFallHeightNoWater;
            safeSaved = true;
        }
        pf.allowParkour = false;
        pf.allowParkourAscend = false;
        pf.allowParkourPlace = false;
        pf.allowDiagonalDescend = false;
        pf.allowLongFall = false;
        pf.maxFallHeightNoWater = 1;
    }

    private void restoreSafeWalk() {
        if (!safeSaved) return;
        var pf = com.aquarius.Globals.CONFIG.client.extra.pathfinder;
        // Only what still holds the value we set: a setting the user changed meanwhile is theirs
        if (!pf.allowParkour) pf.allowParkour = savedParkour;
        if (!pf.allowParkourAscend) pf.allowParkourAscend = savedParkourAscend;
        if (!pf.allowParkourPlace) pf.allowParkourPlace = savedParkourPlace;
        if (!pf.allowDiagonalDescend) pf.allowDiagonalDescend = savedDiagonalDescend;
        if (!pf.allowLongFall) pf.allowLongFall = savedLongFall;
        if (pf.maxFallHeightNoWater == 1) pf.maxFallHeightNoWater = savedMaxFall;
        safeSaved = false;
    }

    // Ledge guard. Standing within 0.3 blocks of the edge of the block the bot is on, with a drop of two blocks or more on
    // that side (the neighbouring cell and the one below it are air, passable or fluid), it sneaks, which keeps it from
    // walking off. Done through the input queue, one tick at a time, at the lowest priority, and only when nothing else has
    // asked for movement this tick (Baritone walking, another module): stopping is just not asking again.
    private static final double LEDGE_MARGIN = 0.3;

    private double ledgeX, ledgeZ;
    private int ledgeStill;

    private void ledgeGuard() {
        double px = CACHE.getPlayerCache().getX(), pz = CACHE.getPlayerCache().getZ();
        ledgeStill = Math.abs(px - ledgeX) < 0.005 && Math.abs(pz - ledgeZ) < 0.005 ? ledgeStill + 1 : 0;
        ledgeX = px;
        ledgeZ = pz;

        if (!CONFIG.safeWalk || gate != Gate.Running || INPUTS.hasActiveRequest()) return;
        // Only standing still at a container with its window being worked on: not while walking, not around the click that
        // opens or uses something, not for a sign or a shulker
        if (job == null || handle != null || step != Step.Working || ledgeStill < 2) return;
        if (job == Job.Label || job == Job.Ground || job == Job.Explore || job == Job.Unpack) return;
        if (BARITONE.isActive() || activity.ownPathing() || openContainerId() == 0) return;
        if (!ledgeNear()) return;
        INPUTS.submit(InputRequest.builder().owner(this).input(Input.builder().sneaking(true).build()).priority(Integer.MIN_VALUE + 1).build());
    }

    private boolean ledgeNear() {
        double px = CACHE.getPlayerCache().getX(), py = CACHE.getPlayerCache().getY(), pz = CACHE.getPlayerCache().getZ();
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
        // The block stood on
        int by = (int) Math.floor(py - 1e-3);
        if (!World.isChunkLoadedBlockPos(bx, bz)) return false;
        double fx = px - bx, fz = pz - bz;
        int sx = fx < LEDGE_MARGIN ? -1 : fx > 1 - LEDGE_MARGIN ? 1 : 0;
        int sz = fz < LEDGE_MARGIN ? -1 : fz > 1 - LEDGE_MARGIN ? 1 : 0;
        if (sx == 0 && sz == 0) return false;
        // Not standing on anything is not standing at an edge
        if (hollow(bx, by, bz)) return false;
        if (sx != 0 && dropAt(bx + sx, by, bz)) return true;
        if (sz != 0 && dropAt(bx, by, bz + sz)) return true;
        return sx != 0 && sz != 0 && dropAt(bx + sx, by, bz + sz);
    }

    /**
     * A drop of two blocks or more beside the block stood on: the cell next to it at its level and the one under that
     * both have nothing to stand on.
     */
    private static boolean dropAt(int x, int groundY, int z) {
        return World.isChunkLoadedBlockPos(x, z) && hollow(x, groundY, z) && hollow(x, groundY - 1, z);
    }

    private static boolean hollow(int x, int y, int z) {
        var block = World.getBlock(x, y, z);
        if (block.isAir()) return true;
        String name = block.name();
        return name.equals("water") || name.equals("lava") || name.equals("bubble_column")
            || com.aquarius.feature.pathfinder.movement.MovementHelper.fullyPassable(new BlockPos(x, y, z));
    }


    // Drop-off checks in stay-on mode: when each is next due (ticks) and how far apart its checks are (seconds). The gap
    // starts at recheckSeconds, doubles each time a check finds the drop-off empty up to 5 minutes, and starts over when
    // something is found in it or another player uses it
    private static final int DROP_OFF_MAX_SECONDS = 300;
    private final Map<Box, Long> dropOffDue = new HashMap<>();
    private final Map<Box, Integer> dropOffInterval = new HashMap<>();
    private static final int[][] FACES = {{0, 1, 0}, {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};

    /**
     * Why a drop-off can get something without its lid moving, or null if it can't: its chunk isn't loaded so the lid can't
     * be seen, or a hopper, dropper or dispenser is next to it or above it.
     */
    private @Nullable String changesQuietly(Box box) {
        for (BlockPos part : box.parts) {
            if (!Containers.isLoaded(part)) return "its chunk is not loaded, so its lid can't be seen";
            for (int[] d : FACES) {
                String name = World.getBlock(part.x() + d[0], part.y() + d[1], part.z() + d[2]).name();
                if (name.equals("hopper") || name.equals("dropper") || name.equals("dispenser")) return "a " + name + " is next to it";
            }
        }
        return null;
    }

    /**
     * A drop-off was read: found empty, its next check is later; found something, or used by a player, it starts over.
     */
    private void noteDropOffRead(Box box, boolean recheck) {
        if (!box.dropOff || !box.known()) return;
        long sig = 17;
        for (Item item : box.contents) sig = sig * 31 + (item == null ? 0 : item.id.hashCode() * 131L + item.count);
        Long before = dropOffSig.put(box, sig);
        // Only a check of its own moves the back-off; our own takes just update what was last seen
        if (!recheck) return;

        // Found something: what it holds differs from the last time it was read (the first read: it is not empty)
        boolean empty = box.free() == box.contents.length;
        boolean changed = before == null ? !empty : before != sig;
        int interval = dropOffInterval.getOrDefault(box, Math.max(5, CONFIG.recheckSeconds));
        interval = changed ? Math.max(5, CONFIG.recheckSeconds) : Math.min(DROP_OFF_MAX_SECONDS, Math.max(interval, CONFIG.recheckSeconds) * 2);
        dropOffInterval.put(box, interval);
        dropOffDue.put(box, tickCount + interval * 20L);
        final int next = interval;
        debug(() -> "Drop-off at " + box.pos + " read " + (changed ? "changed" : "unchanged") + ", next check in " + next + " s.");
    }

    // What each drop-off held when last read, to tell whether a check found something new
    private final Map<Box, Long> dropOffSig = new HashMap<>();

    private void forgetDropOffBackoff(Box box) {
        dropOffInterval.remove(box);
        dropOffDue.remove(box);
    }

    /**
     * Stay-on mode, waiting for something to sort: a container that changed, or a drop-off due for a check.
     */
    private int failedClearTicks;

    private boolean wakeUp() {
        idleTicks++;

        // Reads that failed are tried again about once a minute while resting
        // Each failed read has its own schedule (1, 2, then 5 minutes, SurveyRetry), checked every second against the tick count
        if (++failedClearTicks >= 20) {
            failedClearTicks = 0;
            // Broken containers first: a read of one would only fail again
            dropGoneBoxes();
            if (!failedSurvey.isEmpty()) {
                int due = surveyRetry.release(failedSurvey, tickCount);
                if (due > 0) debug("Retrying {} containers whose read failed.", due);
            }
        }

        // Resting never goes on while there is something unread or stale to read, a container a player used, a changed
        // perimeter or a rescan: whatever marked them, the sorter wakes at once
        String wakeWhy = null;
        if (recoveryPending && pendingResume != null && tickCount >= recoveryWakeAt && recoveryWake()) wakeWhy = "going back to a shulker left after a problem";
        else if (!staleBoxes.isEmpty()) wakeWhy = "a container was used by a player";
        else if (exploreSeen != Stashes.exploreVersion) wakeWhy = "the perimeter changed or a rescan was asked for";
        else if (stash.boxes.stream().anyMatch(b -> !b.excluded && (!b.known() || b.stale) && !b.unreachable && !failedSurvey.contains(b) && !signPending(b))) wakeWhy = "there are unread or stale containers";
        if (wakeWhy != null) {
            final String reason = wakeWhy;
            debug(() -> "Waking from rest: " + reason + ".");
            idle = false;
            return true;
        }

        // Scan only moves nothing into the drop-offs' reach: their lids and signs are watched, which covers changes
        if (sorts() && idleTicks >= CONFIG.recheckSeconds * 20) {
            idleTicks = 0;
            boolean any = false;
            for (Box box : stash.boxes) {
                if (!box.dropOff || box.unreachable) continue;
                if (CONFIG.smartRecheck) {
                    // Only what can fill without a lid moving is walked to, and less often while it stays empty
                    long due = dropOffDue.getOrDefault(box, 0L);
                    if (tickCount < due) continue;
                    String why = changesQuietly(box);
                    if (why == null) {
                        debugOnce("dropoff" + box.pos, () -> "Drop-off at " + box.pos + " is not rechecked on a timer: nothing can fill it without its lid moving.");
                        continue;
                    }
                    debug(() -> "Drop-off at " + box.pos + " is rechecked: " + why + ", every " + dropOffInterval.getOrDefault(box, CONFIG.recheckSeconds) + " s.");
                }
                if (box.excluded) continue;
                box.stale = true;
                any = true;
            }
            if (any) {
                idle = false;
                return true;
            }
        }

        if (Stashes.version != seenVersion) {
            seenVersion = Stashes.version;
            clearStalled();
            futile.clear();
            // Containers waiting for a failed read to be tried again keep waiting: only a rescan or a new perimeter clears them
            unconfirmedRuns.clear();
            stuckCarried.clear();
            stuckWarned = false;
            boxFailures.clear();
            compacted.clear();
            packTried.clear();
            sweepSigns();
            unrecognisedSigns(true);
            idle = false;
            return true;
        }

        return false;
    }

    /**
     * Picks the next job. Read every container we don't know yet, then put away what we're carrying, then pick up
     * what's in the wrong place.
     * @return false when there's nothing left to do
     */
    private boolean nextJob() {
        long began = System.nanoTime();
        try {
            boolean started = pickJob();
            final long micros = (System.nanoTime() - began) / 1000;
            debug(() -> "Planning took " + micros + " us: " + (started ? "started " + job : "no job started") + ".");
            return started;
        } finally {
            destCache = null;
            misplacedCache = null;
            takeRoom = null;
            carriedHomesCache = null;
            signedCache = null;
            kitRoutingSnap = null;
        }
    }

    /**
     * Per-planning-pass memos, only set while nextJob is choosing. Nothing destinationsFor or misplacedCount read (box
     * contents, labels, flags, mode, tier boxes) changes during a pass, so within it equal inputs give equal answers.
     * Dropped when the pass ends, so a later change can never be served stale. Items have no value-based key here, so
     * destinations are remembered per Item object: the same stack in a box is asked about many times in one pass.
     */
    private @Nullable Map<Item, List<Box>> destCache;
    private @Nullable Map<Box, Integer> misplacedCache;

    /**
     * Containers with something to take out that has room to go to.
     */
    private List<Box> takeSources() {
        return stash.boxes.stream().filter(b -> b.sortable() && !b.stale && !blocked(b, Job.Take) && !signPending(b) && takeableSlot(b) != -1).toList();
    }

    /**
     * The nearest container to take from, when taking comes before putting away: at least max(2, a third of the carry
     * slots) are free, and it is nearer to the bot than the container the carried stacks would go to.
     */
    private @Nullable Box takeFirst(Box place) {
        if (freeCarryCount() < Math.max(2, carry.size() / 3)) return null;
        Box source = nearest(takeSources());
        if (source == null) return null;
        BlockPos feet = playerFeet();
        return distSq(feet, nearestPart(source)) < distSq(feet, nearestPart(place)) ? source : null;
    }

    private boolean pickJob() {
        // The perimeter was set in another dimension: nothing here is the stash's
        if (stash.areaSet() && !stash.hasArea()) {
            long now = System.currentTimeMillis();
            if (areaWaitSince == 0) areaWaitSince = now;
            // Said again every minute, with both dimensions, so the wait is never silent
            if (now - areaWaitLogAt >= 60_000L) {
                areaWaitLogAt = now;
                info("The stash area is in another dimension ({}) and the bot is in {}: waiting until it is back there.", stash.area.dimension, Stash.currentDimension());
            }
            return false;
        }
        elsewhereSaid = false;
        areaWaitSince = 0;
        areaWaitLogAt = 0;
        // What a repair cut short by a restart moved is put back before anything else is read, explored or resumed
        if (repair == null && repairTrip == null && startGearUndo()) return true;
        // The delivery container is kept out of the sort (flagged and excluded) from the moment it is set
        syncDeliveryBox();
        checkOrphans();
        // A shulker of ours left standing when the bot went offline or was stopped is finished first, before anything is
        // explored, read or sorted
        if (pendingResume != null && (!recoveryPending || recoveryReady()) && startResume()) {
            if (recoveryPending) info("Shulker handling: the area is quiet, going back to the shulker.");
            recoveryPending = false;
            return true;
        }
        // A perimeter is explored before anything is read or sorted, but what is within reach of where the bot stands is
        // read first, so the walk past it isn't wasted
        if (stash.hasArea() && !exploreDone) {
            Box here = nearbyUnread();
            if (here != null) {
                debug(() -> "Exploring: reading the container at " + here.pos + " within reach before moving on.");
                return start(Job.Survey, here);
            }
            if (startExplore()) return true;
        }

        // What the resume brought back is put away before the containers are read again
        if (resumeDepositFirst && activeCarried().isEmpty()) resumeDepositFirst = false;
        List<Box> unreadBoxes = resumeDepositFirst ? List.of() : stash.boxes.stream().filter(b -> b.wantsRead() && !b.unreachable && !failedSurvey.contains(b) && !signPending(b)).toList();
        Box unread = nearest(unreadBoxes);
        if (unread != null) {
            debug(() -> "Branch survey: " + unreadBoxes.size() + " containers unread or stale.");
            return start(Job.Survey, unread);
        }

        // A craft under way goes on until it is over: its inputs are carried, the shulker it makes is waited for
        if (craft != null) {
            if (continueCraft()) return true;
            if (craft != null) return false;
        }
        // So does taking an anvil from the stash and putting it down, for naming
        if (anvilRun != null) {
            if (continueAnvilRun()) return true;
            if (anvilRun != null) return false;
        }
        // And putting another chest up for the delivery storage
        if (chestRun != null) {
            if (continueChestRun()) return true;
            if (chestRun != null) return false;
        }
        // An enchant item under way goes on until it is over: its materials are carried, its steps are journalled
        if (enchantRun != null) {
            if (continueEnchant()) return true;
            if (enchantRun != null) return false;
        }

        // What a repair cut short by a restart moved is put back first, then a repair under way goes on until it is over
        if (repair != null) {
            if (continueRepair()) return true;
            if (repair != null) return false;
        }

        // A repair pass under way goes on until it is over or gives way
        if (pass != null) {
            if (continuePass()) return true;
            if (pass != null) return false;
        }

        // Orders come before sorting (and keep a driven run going while one is in hand)
        if (orders.engaged()) return nextOrderJob();

        // Scan only: everything is read and kept up to date, nothing is moved
        if (!sorts()) {
            finishSorting();
            return false;
        }

        plan();
        destCache = new IdentityHashMap<>();
        misplacedCache = new HashMap<>();
        signedCache = new HashMap<>();
        takeRoom = null;
        carriedHomesCache = null;

        // Jobs started since the backlog (stacks out of place, and carried) last reached a new low: a pass that keeps
        // starting jobs without getting anywhere is going in circles, so say so and stop rather than reopen forever
        int backlog = looseCarried().size();
        for (Box box : stash.boxes) if (box.sortable()) backlog += misplacedCount(box);
        if (backlog < passMin) {
            passMin = backlog;
            passJobs = 0;
            // Real headway: containers given up on for going back and forth get another chance
            stalled.removeAll(circular);
            circular.clear();
            boxVisits.clear();
        }
        final int backlogNow = backlog;
        debug(() -> "Plan: backlog " + backlogNow + ", passJobs " + passJobs + ", passMin " + (passMin == Integer.MAX_VALUE ? "-" : String.valueOf(passMin))
            + ", carrying " + carriedSlots().size() + " stacks, " + stalled.size() + " containers stalled, " + circular.size() + " circular.");
        if (passJobs > 6 * stash.boxes.size() + 60) {
            alert("Sorting went in circles ({} jobs without getting closer to done), stopping this pass. {} stacks out of place, carrying {}.",
                passJobs, backlog - looseCarried().size(), looseCarried().size());
            finishSorting();
            return false;
        }

        // Compacting carries on until it is done or given up on: nothing else is started meanwhile
        if (compact != null && continueCompaction()) return true;

        // A homeless item gets a new category before it is put in the catch-all: this comes before putting things away
        if (nextLabelJob()) return true;

        if (repackOn() && nextRepackJob()) return true;

        // A bundle just taken from the stash is moved into a carry slot first, and one with something in it emptied
        // before anything is put away
        if (routeBundle()) return false;
        if (nextUnpackJob()) return true;

        // Picking things up off the ground goes on in a batch while there is room and something eligible is left, and only
        // then are they put away
        if (groundBatch) {
            if (groundBatchCount < MAX_GROUND_BATCH && freeCarryCount() >= 2 && startGround(false)) {
                groundBatchCount++;
                debug(() -> "Ground batch goes on: " + freeCarryCount() + " free carry slots, " + carriedSlots().size() + " stacks carried.");
                return true;
            }
            groundBatch = false;
            groundBatchCount = 0;
        }

        // Stacks given up on are tried again every pass: a container read, emptied or signed since can give them a place
        stuckCarried.entrySet().removeIf(held -> {
            Item item = inventoryItem(held.getKey());
            return item == null || !item.id.equals(held.getValue()) || canPlace(item);
        });
        if (stuckCarried.isEmpty()) stuckWarned = false;

        List<Integer> carried = activeCarried();
        if (!carried.isEmpty()) {
            List<Box> places = new ArrayList<>();
            for (int slot : carried) {
                for (Box box : destinationsFor(inventoryItem(slot))) {
                    if (!blocked(box, Job.Deposit) && !signPending(box) && (box.free() > 0 || misplacedCount(box) > 0)) places.add(box);
                }
            }

            Box place = nearest(places);
            if (place != null) {
                // With room to spare in the carry slots, a container to take from that is nearer than the one to put away
                // into is visited first: one trip picks up more before it walks to the deposit
                Box early = takeFirst(place);
                if (early != null) {
                    debug(() -> "Branch take first: " + freeCarryCount() + " free carry slots, the container to take from is nearer than the deposit at " + place.pos + ".");
                    return start(Job.Take, early);
                }
                debug(() -> "Branch deposit: " + carried.size() + " stacks carried, " + places.size() + " candidate containers.");
                return start(Job.Deposit, place);
            }

            // Nowhere it belongs has room: only a container signed misc, other or overflow takes it. Never one with a
            // sign for something else, and never an unsigned one: a signed container only holds what its sign says
            Box spare = spareBox();
            if (spare != null) return start(Job.Dump, spare);

            // What the resume brought back has nowhere to go from what is saved: the containers are read first, which can
            // make room
            if (resumeDepositFirst) {
                resumeDepositFirst = false;
                return pickJob();
            }

            // Nothing takes it for now. It stays in the carry slots, out of the plans, while the rest carries on, and is
            // looked at again when something in the stash changes. The carry slots never include a kept slot, or the
            // pearl slot
            for (int slot : carried) stuckCarried.put(slot, inventoryItem(slot).id);
            if (!stuckWarned) {
                stuckWarned = true;
                alert("Found nowhere with room for {} stacks I'm carrying (no container they belong in has space, and none signed misc/other/overflow does" + (CONFIG.unsignedOverflow && CONFIG.mode == Mode.Signs ? ", and no unsigned container whose signs could be read does" : "") + "), keeping them in the bot's inventory and carrying on with the rest.", carried.size());
            }
            long gear = carried.stream().filter(s -> inventoryItem(s) != null && ownKind(inventoryItem(s))).count();
            if (gear > 0) alert("Still carrying gear or food from the stash ({} stacks) with nowhere to put it.", gear);
        }

        // Mixed shulkers sitting in the stash: fetch one to clean out
        if (repackOn() && freeCarryCount() >= 2) {
            Box best = null;
            int bestSlot = -1;
            for (Box box : stash.boxes) {
                if (!box.sortable() || box.stale || blocked(box, Job.Fetch) || signPending(box) || kitReserved(box)) continue;
                for (int i = 0; i < box.contents.length; i++) {
                    if (!strays(box.contents[i]).isEmpty() && (best == null || closer(box, best))) {
                        best = box;
                        bestSlot = i;
                        break;
                    }
                }
            }
            if (best != null) return fetch(best, bestSlot);
        }

        // With every carry slot full there's nothing to pick up into: a trip would only open the container and stall it
        List<Box> sources = freeCarrySlot() == -1 ? List.of() : takeSources();
        Box source = nearest(sources);
        if (source != null) {
            debug(() -> "Branch take: " + sources.size() + " containers have something to take.");
            return start(Job.Take, source);
        }
        if (freeCarrySlot() == -1) debug("Branch take skipped: no free carry slot.");

        // Downstack: merge partial stacks of the same item, once per container per run
        Box messy = nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !stalled.contains(b) && !signPending(b) && !compacted.contains(b) && mergeable(b) != null).toList());
        if (messy != null) {
            compacted.add(messy);
            return start(Job.Compact, messy);
        }

        // A sign that is being changed decides what is left to do: wait for it before calling it done
        if (CONFIG.watchSigns && !signDirtyAt.isEmpty()) return false;

        // Everything is put away: items lying on the ground in the stash area are fetched next, and then put away too
        if (startGround(false)) return true;

        // Idle: a repair pass (after drop-offs and needs-repair routing), then kit restock, before compaction
        if (startRepairPass()) return true;
        if (startRestock()) return true;

        // Everything is where it belongs: full shulkers' worth of anything are packed, which frees chest slots
        if (startCompaction() && continueCompaction()) return true;
        // Compaction found no empty shulker and a craft for one was started
        if (craft != null && job != null) return true;

        finishSorting();
        return false;
    }


    // Compacting: a full shulker's worth of an item is packed into one shulker, so it takes one slot instead of 27

    private static final int COMPACT_COOLDOWN = 20 * 60 * 10;
    private static final int COMPACT_DONE_COOLDOWN = 20 * 10;
    private static final int PLACE_GAP_TICKS = 20 * 5;

    private static final class CompactPlan {
        final String key;
        final Item reference;
        final boolean topUp;
        // Stacks the shulker held when this began, and should hold when it is done
        final int startSize, target;
        // Where the part-full shulker to top up is kept
        @Nullable Box sourceBox;
        int sourceSlot = -1;
        // The shulker standing in the world between batches, and what it held when last worked on
        @Nullable BlockPos placedPos;
        int placedSize;
        // The item id the plan's shulker was put down as (placedItem may be another one since, unpacked between batches)
        @Nullable String shulkerId;
        int jobs, acquireTries, slotHint = -1;
        // Trips made to put carried stacks away to make room for an order's shulker
        int roomTrips;
        boolean aborting;
        // Resumed after the bot was offline or stopped: it only finishes the batch in the shulker, then breaks it and is done
        boolean finishOnly;
        // An order's shulker: the loads it is to hold. Everything above is then about this one shulker, not a bulk item
        @Nullable Bin bin;

        CompactPlan(String key, Item reference, boolean topUp, int startSize, int target) {
            this.key = key;
            this.reference = reference;
            this.topUp = topUp;
            this.startSize = startSize;
            this.target = target;
        }
    }

    private record Partial(Box box, int slot, int size) {
    }

    /** A stash order's shulker being built: its loads, and what the shulker holds so far. */
    private static final class Bin {
        final Unit unit;
        final List<Order.Lot> lots;
        List<BinTracker.Held> packed = new ArrayList<>();
        boolean completed;
        @Nullable String failure;
        // What the bin held and carried when it last made headway, and when: a bin that makes none for a while is given up on
        long progressSig = Long.MIN_VALUE, progressAt;
        // Its own shulker: the item id, and whether it was empty when first put down (only then can one be delivered short)
        @Nullable String shulkerId;
        boolean startKnown, startedEmpty;
        // Keys of loads no container could give (1.8.0): left out, the rest is still gathered and packed
        final Set<String> unavailable = new LinkedHashSet<>();
        // The containers given up on for now were looked at again for this bin (once per bin, 1.8.1)
        boolean lookedAgain;

        Bin(Unit unit) {
            this.unit = unit;
            this.lots = unit.lots;
        }
    }

    private @Nullable CompactPlan compact;
    private final Map<String, Long> compactCooldown = new HashMap<>();
    private volatile int compactedShulkers, slotsFreed;
    private int runPacked, runFreed;
    private volatile String compactBlocker = "";
    private long lastPlaceTick = Long.MIN_VALUE / 4;
    // A shulker could not be taken back: no more are put down
    // The shulker that stopped it, until it is picked up or gone (or sorting restarts). Transient problems are not a stop:
    // see abandonShulker
    private final ShulkerRecovery.LostMarker<BlockPos> lost = new ShulkerRecovery.LostMarker<>();
    // How often each shulker spot was gone back to after giving up on it, and when going back is allowed (a back-off, then
    // a quiet area). What is gone back to is the in-flight record (pendingResume)
    private final ShulkerRecovery.Retries<BlockPos> shulkerRetries = new ShulkerRecovery.Retries<>();
    private final ShulkerRecovery.QuietGate recoveryGate = new ShulkerRecovery.QuietGate();
    private boolean recoveryPending;
    // When the "waiting for <player>" line may be said again, and when an idle sorter next looks whether the area is quiet
    private long recoveryWaitLogAt, recoveryWakeAt;

    // A shulker of ours standing in the world (compacting between rounds, or repacking) when the bot goes offline or is
    // stopped is not forgotten: what was under way is kept in the stash file (Stash.InFlight) and finished on the next
    // start, before anything is explored or read again. Things this record is saved at are the ones that change the
    // world: the shulker put down, left between batches, a break asked for, picked up (which clears it), a plan started,
    // finished or given up on, a shulker lost or abandoned, and the bot disconnecting or being stopped.

    // The record read from the stash file at the start, waiting to be resumed (or held, in another dimension)
    private Stash.@Nullable InFlight pendingResume;
    // Finishing it is under way
    private boolean resuming;
    // The standing shulker has not been checked yet: the block when we get there, and what it holds when it is opened
    private boolean resumeVerify;
    private @Nullable String resumeBlockId;
    private long resumeDeadline;
    // The resumed stacks are put away before the containers are read again
    private boolean resumeDepositFirst;
    private boolean resumeDimensionSaid;
    // The shulker turned out not to be ours: closed and left alone
    private boolean resumeLeaveAlone;
    private boolean disconnecting;
    private static final int RESUME_MAX_TICKS = 20 * 60 * 10;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Where a shulker of ours stands (or lies, once broken) that must not be forgotten, or null.
     */
    private @Nullable BlockPos standingShulker() {
        CompactPlan plan = compact;
        if (plan != null && plan.placedPos != null) return plan.placedPos;
        // Once it is put down it stays recorded whatever the world cache says: going offline clears that. Only a place that was
        // asked for and not seen yet is checked against the world
        if (handle != null && placed != null && handle != Handle.Hold) return placed;
        return null;
    }

    /**
     * What is under way now, as it would be saved, or null when no shulker of ours is out in the world.
     */
    private Stash.@Nullable InFlight buildInFlight() {
        BlockPos at = standingShulker();
        if (at == null) return null;

        CompactPlan plan = compact;
        Stash.InFlight record = new Stash.InFlight();
        record.savedAt = STAMP.format(java.time.LocalDateTime.now());
        record.dimension = Stash.currentDimension();
        record.job = job == Job.Clean ? Job.Clean.name() : Job.Fill.name();
        record.handle = handle != null ? handle.name() : Handle.Leave.name();
        record.returnTo = returnTo != null ? returnTo.name() : null;
        record.placed = at;
        String block = null;
        // The plan's own shulker is saved as what it was put down as, not as the last shulker placed (one unpacked since)
        if (plan != null && at.equals(plan.placedPos) && plan.shulkerId != null) block = plan.shulkerId;
        else if (placedItem != null) block = placedItem.name();
        else if (Containers.isLoaded(at)) {
            String name = World.getBlock(at.x(), at.y(), at.z()).name();
            if (name.endsWith("shulker_box")) block = name;
        }
        record.placedItemId = block;
        record.placedStacks = plan != null ? plan.placedSize : -1;
        record.fillKey = fillId;
        record.cleanMainId = job == Job.Clean ? mainId : null;
        record.lastGoodStand = lastGoodStand;
        record.recoveries = shulkerRetries.count(at);
        record.target = job != null && target != null ? target.pos : null;
        if (plan != null) {
            Stash.CompactState state = new Stash.CompactState();
            state.key = plan.key;
            state.reference = plan.reference;
            state.topUp = plan.topUp;
            state.startSize = plan.startSize;
            state.target = plan.target;
            state.sourceBox = plan.sourceBox == null ? null : plan.sourceBox.pos;
            state.sourceSlot = plan.sourceSlot;
            state.placedPos = plan.placedPos;
            state.placedSize = plan.placedSize;
            state.jobs = plan.jobs;
            state.aborting = plan.aborting;
            record.compact = state;
        }
        return record;
    }

    /**
     * Writes what is under way to the stash file, or removes the record when nothing is. A record that was read at
     * the start and not yet taken up is left as it is.
     */
    private void syncInFlight() {
        Stash current = stash;
        if (current == null || pendingResume != null) return;

        Stash.InFlight record = buildInFlight();
        if (record == null && current.inFlight == null) return;
        current.inFlight = record;
        current.markDirty();
        current.save();
        debug(() -> record == null ? "In-flight record cleared." : "In-flight record saved: " + record.job + " at " + record.placed + ", step " + record.handle + ".");
    }

    /**
     * The bot is going offline or being stopped: if a shulker of ours is standing, it is saved to be finished on the
     * next start, and said loudly.
     * @param lead "Disconnected" or "Stopped"
     */
    private void snapshotInFlight(String lead) {
        // What is carried from the stash is saved too, when it changed since the last save
        if (stash != null && !stashHeld.equals(stash.held)) {
            stash.markDirty();
            stash.save();
        }
        BlockPos at = standingShulker();
        if (at == null || stash == null) return;
        syncInFlight();
        alert("{} mid-job: shulker at {} kept, it will be finished on return.", lead, xyz(at));
    }

    /**
     * Records a shulker that couldn't be dealt with, to be told about and picked up by hand.
     */
    private void recordOrphan(BlockPos at, String item, int stacks) {
        Stash current = stash != null ? stash : Stashes.current();
        if (current == null) return;
        current.orphans.removeIf(o -> o.pos.equals(at));
        current.orphans.add(new Stash.Orphan(at, item, stacks, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.LocalDateTime.now())));
        current.markDirty();
        current.save();
    }

    /**
     * Orphans that are gone from the world (someone picked them up) are forgotten. Only where the chunk is loaded in the
     * dimension they stand in, and never the shulker being finished.
     */
    private void checkOrphans() {
        if (stash == null) return;
        if (lost.sync(stash.orphans.stream().map(o -> o.pos).toList())) lostEnded("its record is gone");
        if (stash.orphans.isEmpty()) return;
        String here = Stash.currentDimension();
        BlockPos busy = standingShulker();
        boolean changed = false;
        for (Stash.Orphan orphan : new ArrayList<>(stash.orphans)) {
            if (orphan.pos.equals(busy) || (pendingResume != null && orphan.pos.equals(pendingResume.placed))) continue;
            if (orphan.dimension != null && here != null && !com.porter.qwertestash.stash.Dimensions.same(orphan.dimension, here)) continue;
            if (!Containers.isLoaded(orphan.pos) || World.getBlock(orphan.pos.x(), orphan.pos.y(), orphan.pos.z()).name().endsWith("shulker_box")) continue;
            stash.orphans.remove(orphan);
            changed = true;
            info("The shulker holding {} at {} is gone (someone picked it up), forgetting it.", orphan.item, fmt(orphan.pos));
            if (lost.cleared(orphan.pos)) lostEnded("the lost shulker at " + fmt(orphan.pos) + " is gone");
        }
        if (changed) {
            stash.markDirty();
            stash.save();
        }
    }

    /**
     * Takes up the record read at the start, before anything is explored or read: back to the shulker, open it, finish
     * what was being done, break it, pick it up. Held back when it is in another dimension.
     * @return true when a job was started
     */
    private boolean startResume() {
        Stash.InFlight record = pendingResume;
        if (record == null) return false;

        if (record.placed == null) {
            dropResume("it has no position");
            return false;
        }
        String here = Stash.currentDimension();
        if (record.dimension != null && here != null && !com.porter.qwertestash.stash.Dimensions.same(record.dimension, here)) {
            if (!resumeDimensionSaid) {
                resumeDimensionSaid = true;
                alert("A shulker of mine is standing at {} in {}, the bot is in {}. No shulkers are put down, and it is finished once the bot is back there.",
                    xyz(record.placed), record.dimension, here);
            }
            return false;
        }

        BlockPos at = record.placed;
        boolean loaded = Containers.isLoaded(at);
        boolean standing = loaded && World.getBlock(at.x(), at.y(), at.z()).name().endsWith("shulker_box");
        boolean compacting = record.compact != null && record.compact.key != null && record.compact.reference != null;
        boolean cleaning = !compacting && Job.Clean.name().equals(record.job);
        if (!compacting && !cleaning && record.fillKey == null) {
            dropResume("it doesn't say what the shulker was for");
            return false;
        }

        CompactPlan plan = null;
        if (compacting) {
            Stash.CompactState saved = record.compact;
            plan = new CompactPlan(saved.key, saved.reference, saved.topUp, saved.startSize, saved.target);
            plan.sourceBox = saved.sourceBox == null ? null : stash.boxAt(saved.sourceBox);
            plan.sourceSlot = saved.sourceSlot;
            plan.placedPos = at;
            plan.placedSize = Math.max(0, saved.placedSize);
            plan.shulkerId = record.placedItemId;
            plan.jobs = saved.jobs;
            plan.aborting = saved.aborting;
            plan.finishOnly = true;
            if (saved.key.startsWith(ORDER_KEY)) {
                Unit unit = orders.unitFromKey(saved.key);
                if (unit == null) {
                    dropResume("the order it was for is gone");
                    return false;
                }
                // The bin given up on for a pause that passes is still here (activeBin): it goes on with what it had packed, and
                // the shulker, once picked up, goes out as that (short) or is planned again, never gathered a second time.
                // After a restart nothing is left of it and the stacks in the shulker are put away first (resumeDepositFirst)
                Bin kept = activeBin;
                if (kept != null && kept.unit == unit && !kept.completed) {
                    kept.failure = null;
                    plan.bin = kept;
                    debug("Resuming with the order bin that was given up on: {} stacks packed.", kept.packed.size());
                }
                else plan.bin = new Bin(unit);
                activeBin = plan.bin;
            }
        }
        // Already back in the inventory (never put down, or picked up before the record could be cleared): nothing to walk to
        boolean askedOnly = Handle.Place.name().equals(record.handle) || Handle.Hold.name().equals(record.handle);
        if (!standing && (loaded || askedOnly) && shulkerBackInInventory(record, plan, cleaning)) {
            pendingResume = null;
            recoveryPending = false;
            clearOrphan(at);
            stash.inFlight = null;
            stash.markDirty();
            stash.save();
            resumeDepositFirst = true;
            long left = stash.boxes.stream().filter(b -> !b.excluded).count();
            step("resume finished; next: re-read " + left + " containers.");
            return false;
        }
        String what = plan != null ? plan.reference.name : cleaning ? "a mixed shulker" : record.fillKey;
        String stacks = record.placedStacks >= 0 ? record.placedStacks + " stacks" : "some stacks";

        compact = plan;
        startHandling(cleaning ? Job.Clean : Job.Fill, -1, plan != null ? plan.key : record.fillKey);
        mainId = record.cleanMainId;
        placed = at;
        lastGoodStand = record.lastGoodStand;
        placedItem = record.placedItemId == null ? null : ItemRegistry.REGISTRY.get(record.placedItemId);
        resumeBlockId = record.placedItemId;
        returnRetries = 0;
        breakRetries = 0;
        brokenSince = -1;
        resuming = true;
        resumeLeaveAlone = false;
        resumeDeadline = tickCount + RESUME_MAX_TICKS;
        resumeDepositFirst = true;
        pendingResume = null;

        long toRead = stash.boxes.stream().filter(b -> !b.excluded).count();
        step("resuming: shulker of " + what + " (" + stacks + ") at " + xyz(at) + "; next: open it, finish packing, break and pick it up, store it, then re-read " + toRead + " containers.");

        // What a shulker picked up will be told from
        pickupWatch.standing(countInInv(this::isShulkerBox), shulkerSlots());

        // Gone from where it stood (broken, or taken since) or already broken: the dropped shulker is looked for nearby, and
        // if it isn't found the bound ends it with an alert. Walking there first if the place isn't loaded
        // (a record saved at the Collect step with the shulker block still standing is opened and checked, then broken)
        if (loaded ? !standing : Handle.Collect.name().equals(record.handle)) {
            resumeVerify = false;
            // Far from where it was: walked back to first (the dropped shulker is only seen from close by)
            boolean near = loaded && distSq(playerFeet(), at) <= COLLECT_NEAR_SQ;
            handle = near ? Handle.Collect : Handle.Return;
            returnTo = near ? null : Handle.Collect;
            if (near) collectStarted();
        }
        else {
            resumeVerify = true;
            handle = Handle.Return;
            returnTo = Handle.Open;
        }
        handleTicks = 0;
        syncInFlight();
        return true;
    }

    /**
     * The saved record can't be used: forgotten.
     */
    private boolean shulkerBackInInventory(Stash.InFlight record, @Nullable CompactPlan plan, boolean cleaning) {
        for (int i = 0; i < 36; i++) {
            Item item = inventoryItem(i);
            if (item == null || !item.isShulker() || item.customName) continue;
            if (record.placedItemId != null && !record.placedItemId.equals(item.id)) continue;
            if (plan != null) {
                if (item.contents.isEmpty() ? plan.placedSize == 0 && !plan.topUp : isPlanShulker(item, plan)) return true;
            }
            else if (cleaning) {
                if (record.cleanMainId != null && item.contents.stream().anyMatch(c -> c.kindKey().equals(record.cleanMainId))) return true;
            }
            else if (record.fillKey != null && !item.contents.isEmpty() && item.contents.stream().allMatch(c -> record.fillKey.equals(c.bulkKey()))) return true;
        }
        return false;
    }

    /**
     * A place was asked for: the shulker may go down any moment, so it is recorded (in-flight and orphan) now, and cleared
     * again if the place fails.
     */
    private void noteSpotAsked() {
        CompactPlan plan = compact;
        if (placed != null) recordOrphan(placed, plan != null ? plan.reference.name : fillId != null ? fillId : "items", plan != null ? plan.placedSize : 0);
        syncInFlight();
    }

    private void dropResume(String why) {
        Stash.InFlight record = pendingResume;
        alert("Dropping the saved shulker record: {}. If a shulker of mine is still standing{}, pick it up by hand.", why,
            record != null && record.placed != null ? " at " + xyz(record.placed) : "");
        if (record != null && record.placed != null) recordOrphan(record.placed, "items", Math.max(0, record.placedStacks));
        pendingResume = null;
        recoveryPending = false;
        if (record != null && record.placed != null) shulkerRetries.forget(record.placed);
        if (stash != null) {
            stash.inFlight = null;
            stash.markDirty();
            stash.save();
        }
    }

    /**
     * Whether what the shulker holds fits what the record says it was for. Null if it does, else what is wrong.
     */
    private @Nullable String resumeContentsProblem(Container c) {
        CompactPlan plan = compact;
        for (int i = 0; i < 27; i++) {
            Item here = Item.of(c.getItemStack(i));
            if (here == null) continue;
            if (plan != null) {
                if (plan.bin != null) {
                    if (!BinTracker.owns(plan.bin.lots, here.indexKey(), here.variant, here.count)) return "it holds " + here.label() + ", which isn't part of the order";
                }
                else if (!plan.key.equals(here.bulkKey())) return "it holds " + here.label() + ", not " + plan.reference.name;
            }
            else if (job == Job.Fill) {
                if (fillId == null || !fillId.equals(here.bulkKey())) return "it holds " + here.label() + ", not " + fillId;
            }
        }
        if (plan == null && job == Job.Clean && mainId != null) {
            boolean any = false, main = false;
            for (int i = 0; i < 27; i++) {
                Item here = Item.of(c.getItemStack(i));
                if (here == null) continue;
                any = true;
                if (here.kindKey().equals(mainId)) main = true;
            }
            if (any && !main) return "none of it is " + mainId;
        }
        return null;
    }

    /**
     * The shulker at the position isn't the one that was put down: left standing, said, recorded. The window (if open) is
     * closed first by the Leave step.
     */
    private void resumeMismatch(String why) {
        BlockPos at = placed;
        alert("The shulker at {} isn't the one I put down ({}), leaving it alone.", at == null ? "?" : xyz(at), why);
        if (at != null) recordOrphan(at, compact != null ? compact.reference.name : fillId != null ? fillId : "items", 0);
        resumeVerify = false;
        resumeLeaveAlone = true;
        handle = Handle.Leave;
        handleTicks = 0;
    }

    /**
     * Resuming ends with the shulker picked up, or abandoned.
     */
    private void endResume() {
        if (!resuming) return;
        resuming = false;
        resumeVerify = false;
        // Pending again after a problem: not finished
        if (pendingResume != null) return;
        long toRead = stash == null ? 0 : stash.boxes.stream().filter(b -> !b.excluded && (!b.known() || b.stale)).count();
        step("resume finished; next: re-read " + toRead + " containers.");
    }


    private void clearOrphan(@Nullable BlockPos at) {
        Stash current = stash != null ? stash : Stashes.current();
        if (current == null || at == null) return;
        if (current.orphans.removeIf(o -> o.pos.equals(at))) {
            current.markDirty();
            current.save();
        }
        shulkerRetries.forget(at);
        if (lost.cleared(at)) lostEnded("the shulker at " + fmt(at) + " was picked up");
    }

    private boolean compactOn() {
        return CONFIG.compact && CONFIG.mode == Mode.Signs && !repackFailed && !lost.lost() && pendingResume == null && tickCount >= repackPausedUntil;
    }

    /**
     * For the command: what compacting has done and what is holding it back.
     */
    public String compactStatus() {
        String blocker = compactBlocker;
        CompactPlan plan = compact;
        return compactedShulkers + " shulkers packed, " + slotsFreed + " slots freed"
            + (plan != null ? ", packing " + plan.reference.name : blocker.isEmpty() ? "" : ", last blocker: " + blocker)
            + (lost.lost() ? ", stopped: a shulker could not be picked up" : "");
    }

    private boolean compactExcluded(Item item) {
        return CONFIG.compactExclude.contains(item.id);
    }

    /**
     * A container its stacks and shulkers may be taken from: not a drop-off, a kit chest, one marked compactSkip, or
     * one whose sign is changing.
     */
    private boolean compactSource(Box b) {
        return b.sortable() && !b.stale && !b.dropOff && !b.noCompact && !signPending(b) && !kitReserved(b) && b.labels.stream().noneMatch(Categories::isKeepWhole);
    }

    /**
     * Compacting only touches what has a real category to go back to.
     */
    private boolean hasHome(Item item) {
        List<Box> homes = destinationsFor(item);
        return !homes.isEmpty() && homes.stream().noneMatch(this::isMisc);
    }

    private @Nullable String offhandId() {
        Item offhand = Item.of(CACHE.getPlayerCache().getPlayerInventory().get(45));
        return offhand == null ? null : offhand.id;
    }

    /**
     * An unnamed shulker that is empty, or holds only this plan's item.
     */
    private boolean isPlanShulker(@Nullable Item s, CompactPlan plan) {
        if (s == null || !s.isShulker()) return false;
        // The base of a kit build is an incomplete copy: it carries its template name
        if (s.customName && !(plan.bin != null && plan.bin.unit.kitBase != null)) return false;
        if (plan.bin != null) {
            for (Item inside : s.contents) if (!BinTracker.owns(plan.bin.lots, inside.indexKey(), inside.variant, inside.count)) return false;
            return true;
        }
        if (s.contents.isEmpty()) return !plan.topUp;
        for (Item inside : s.contents) if (!plan.key.equals(inside.bulkKey())) return false;
        return true;
    }

    private boolean matchesPlan(@Nullable Item item) {
        CompactPlan plan = compact;
        if (plan != null && plan.bin != null) return item != null && !item.isShulker() && BinTracker.owns(plan.bin.lots, item.indexKey(), item.variant, item.count);
        return plan != null && item != null && item.isFullStack() && plan.key.equals(item.bulkKey());
    }

    /**
     * The carry slot holding the plan's shulker, by what is in it and not where it is: slots change when a shulker is
     * moved to the hotbar or picked up again. -1 if there is none.
     */
    private int findPlanShulker() {
        CompactPlan plan = compact;
        if (plan == null) return -1;
        if (plan.slotHint >= 0 && carry.contains(plan.slotHint) && !sourceShulkers.containsKey(plan.slotHint) && isPlanShulker(inventoryItem(plan.slotHint), plan)) return plan.slotHint;

        int found = -1;
        for (int slot : carriedSlots()) {
            if (sourceShulkers.containsKey(slot)) continue;
            Item item = inventoryItem(slot);
            if (!isPlanShulker(item, plan)) continue;
            if (found == -1 || !item.contents.isEmpty()) found = slot;
        }
        plan.slotHint = found;
        return found;
    }

    /**
     * Carry slots set aside for compacting: its shulker and the full stacks it is gathering. Nothing else touches them.
     */
    private Set<Integer> reservedSlots() {
        // A craft's inputs and the shulker boxes it made are set aside too
        Set<Integer> crafting = craftReserved();
        Set<Integer> anvil = anvilReserved();
        if (!anvil.isEmpty()) {
            crafting = new HashSet<>(crafting);
            crafting.addAll(anvil);
        }
        Set<Integer> chests = chestReserved();
        if (!chests.isEmpty()) {
            crafting = new HashSet<>(crafting);
            crafting.addAll(chests);
        }
        if (compact == null) return crafting;
        Set<Integer> reserved = new HashSet<>(crafting);
        int shulker = findPlanShulker();
        if (shulker >= 0) reserved.add(shulker);
        for (int slot : carriedSlots()) if (matchesPlan(inventoryItem(slot))) reserved.add(slot);
        return reserved;
    }

    /**
     * What is carried and free to be put away: everything but what compacting has set aside.
     */
    private List<Integer> looseCarried() {
        Set<Integer> setAside = reservedSlots();
        List<Integer> slots = new ArrayList<>();
        for (int slot : carriedSlots()) {
            if (setAside.contains(slot)) continue;
            // A bundle with something in it is emptied first, never put away as it is
            Item item = inventoryItem(slot);
            if (item != null && item.isFilledBundle()) continue;
            slots.add(slot);
        }
        return slots;
    }

    /**
     * Known not to be a shulker any more: the chunk is loaded and the block is something else. Not loaded is not known.
     */
    private boolean goneAt(BlockPos pos) {
        return Containers.isLoaded(pos) && !World.getBlock(pos.x(), pos.y(), pos.z()).name().endsWith("shulker_box");
    }

    private boolean hasFull(Box box, String key) {
        for (Item item : box.contents) if (item != null && item.isFullStack() && key.equals(item.bulkKey())) return true;
        return false;
    }

    /**
     * How many full stacks of the plan's item the stash has in containers they may be taken from.
     */
    private int availableFull(CompactPlan plan) {
        if (plan.bin != null) return 0;
        int count = 0;
        for (Box box : stash.boxes) {
            if (!compactSource(box) || blocked(box, Job.Gather)) continue;
            for (Item item : box.contents) if (item != null && item.isFullStack() && plan.key.equals(item.bulkKey())) count++;
        }
        return count;
    }

    private @Nullable Box compactSourceBox(CompactPlan plan) {
        if (plan.bin != null) return binSourceBox(plan);
        return nearest(stash.boxes.stream().filter(b -> compactSource(b) && !blocked(b, Job.Gather) && hasFull(b, plan.key)).toList());
    }

    private @Nullable Partial partialOf(Box box, int slot, Item shulker) {
        if (shulker.customName || shulker.contents.isEmpty() || shulker.contents.size() >= 27) return null;
        String key = shulker.contents.get(0).bulkKey();
        if (key == null) return null;
        for (Item inside : shulker.contents) if (!key.equals(inside.bulkKey())) return null;
        return new Partial(box, slot, shulker.contents.size());
    }

    /**
     * Looks for something to compact: first topping up a shulker of an item that is part full, else 27 full stacks of
     * an item with an empty shulker to put them in. Only with the stash otherwise settled and nothing carried.
     * @return true when a plan was made
     */
    private boolean startCompaction() {
        if (!CONFIG.compact) return false;
        if (CONFIG.mode != Mode.Signs) {
            compactWhy("it needs signs mode");
            return false;
        }
        if (!compactOn()) {
            compactWhy(lost.lost() ? "stopped, a shulker could not be picked up" : "paused for a few minutes after a shulker problem");
            return false;
        }
        if (!activeCarried().isEmpty()) {
            compactWhy("still carrying " + activeCarried().size() + " stacks to put away");
            return false;
        }

        Map<String, Integer> full = new LinkedHashMap<>();
        Map<String, Map<Box, Integer>> where = new HashMap<>();
        Map<String, Item> sample = new HashMap<>();
        Map<String, Partial> partials = new HashMap<>();
        Map<String, Boolean> homed = new HashMap<>();
        for (Box box : stash.boxes) {
            if (!compactSource(box)) continue;
            for (int i = 0; i < box.contents.length; i++) {
                Item item = box.contents[i];
                if (item == null) continue;
                if (item.isShulker()) {
                    Partial part = partialOf(box, i, item);
                    if (part == null) continue;
                    Item first = item.contents.get(0);
                    String key = first.bulkKey();
                    if (compactExcluded(first) || !homed.computeIfAbsent(key, k -> hasHome(first))) continue;
                    Partial had = partials.get(key);
                    if (had == null || part.size() < had.size()) partials.put(key, part);
                    sample.putIfAbsent(key, first);
                    continue;
                }
                String key = item.bulkKey();
                if (key == null || !item.isFullStack() || compactExcluded(item)) continue;
                // An item a repair pass took out of a box and could not repair is not packed into a shulker again for 24 h
                if (repairMemory.noRepackHold(item.variant != null ? item.variant : item.indexKey())) continue;
                if (!homed.computeIfAbsent(key, k -> hasHome(item))) continue;
                full.merge(key, 1, Integer::sum);
                where.computeIfAbsent(key, k -> new HashMap<>()).merge(box, 1, Integer::sum);
                sample.putIfAbsent(key, item);
            }
        }

        CompactPlan best = null;
        int bestScore = 0;
        String blocker = "";
        for (var entry : full.entrySet()) {
            String key = entry.getKey();
            int stacks = entry.getValue();
            Long cool = compactCooldown.get(key);
            if (cool != null && tickCount < cool) {
                blocker = "cooling down for " + sample.get(key).name;
                continue;
            }

            Partial part = partials.get(key);
            CompactPlan plan;
            int score;
            int room = part == null ? 0 : 27 - part.size();
            // Things that don't stack (tools, armor, flint and steel...) are packed as soon as there are two, and added
            // to a part-full shulker as soon as there is one; a shulker of them is filled with as many as there are
            boolean single = sample.get(key).maxStack == 1;
            if (part != null && stacks >= (single ? 1 : Math.min(room, CONFIG.compactTopUpAt))) {
                int add = Math.min(room, stacks);
                plan = new CompactPlan(key, sample.get(key), true, part.size(), part.size() + add);
                plan.sourceBox = part.box();
                plan.sourceSlot = part.slot();
                score = 1000 + add;
            }
            else if (single ? stacks >= 2 : stacks >= 27) {
                int target = single ? Math.min(27, stacks) : 27;
                plan = new CompactPlan(key, sample.get(key), false, 0, target);
                score = target;
            }
            else {
                debug(() -> "Compaction candidate " + sample.get(key).name + ": " + stacks + " full stacks"
                    + (part == null ? "" : ", part-full shulker " + part.size() + "/27 (needs " + (single ? 1 : Math.min(room, CONFIG.compactTopUpAt)) + " to top up)") + ", too few.");
                continue;
            }
            debug(() -> "Compaction candidate " + sample.get(key).name + ": " + stacks + " full stacks, " + (plan.topUp ? "top up" : "new shulker") + ", score " + score + ".");
            if (best == null || score > bestScore) {
                best = plan;
                bestScore = score;
            }
        }

        if (best == null) {
            compactBlocker = blocker;
            String closest = "no stacks of anything with a category";
            int most = 0;
            for (var entry : full.entrySet()) {
                if (entry.getValue() <= most) continue;
                most = entry.getValue();
                Box chest = where.get(entry.getKey()).entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                closest = sample.get(entry.getKey()).name + " " + most + "/27, most in the chest at " + chest.pos;
            }
            compactWhy(blocker.isEmpty() ? "nothing has a full shulker's worth (closest: " + closest + ")" : blocker + " (closest: " + closest + ")");
            return false;
        }
        // The shulker's slot, a stack to carry and one slot kept free for picking up (the shulker may already be carried)
        compact = best;
        int needFree = findPlanShulker() >= 0 ? 2 : 3;
        compact = null;
        if (freeCarryCount() < needFree) {
            compactBlocker = "carry room " + freeCarryCount() + " < " + needFree;
            compactWhy("not enough carry room for " + best.reference.name + " (" + freeCarryCount() + " free slots, need " + needFree + ")");
            return false;
        }
        if (!best.topUp) {
            compact = best;
            boolean available = findPlanShulker() >= 0 || findEmptyShulker() != null;
            compact = null;
            if (!available) {
                compactBlocker = "no empty shulker";
                // One is crafted, when crafting is on: the craft runs, and compaction looks again once it is over
                if (startCompactionCraft(best.reference.name)) {
                    compactBlocker = "crafting a shulker";
                    return false;
                }
                compactWhy("no empty shulker for " + best.reference.name + " (" + best.target + " stacks waiting)");
                return false;
            }
        }

        compact = best;
        compactBlocker = "";
        lastCompactWhy = "";
        info("Compacting {} stacks of {} into a shulker, frees {} slots.", best.target - best.startSize, best.reference.name, best.target - best.startSize - (best.topUp ? 0 : 1));
        return true;
    }

    // What compacting last said was holding it back, so it is said again only when that changes
    private String lastCompactWhy = "";

    private void compactWhy(String text) {
        if (text.equals(lastCompactWhy)) return;
        lastCompactWhy = text;
        info("Compaction: {}.", text);
    }

    private record Found(Box box, int slot) {
    }

    /**
     * An unnamed empty shulker in the stash that may be taken: in a chest signed for shulkers first, then the nearest,
     * never one in a kit chest or a container marked compactSkip, and not one the offhand shulker would clash with.
     */
    /** The shulker with this group key in a container the bot may take from, or null. */
    private @Nullable Found findShulkerByKey(String key) {
        Found best = null;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.stale || box.dropOff || box.delivery || blocked(box, Job.Fetch) || signPending(box) || box.contents == null) continue;
            for (int i = 0; i < box.contents.length; i++) {
                Item it = box.contents[i];
                if (it == null || !it.isShulker() || !key.equals(ManagerIndex.shulkerKey(it))) continue;
                if (best == null || closer(box, best.box())) best = new Found(box, i);
                break;
            }
        }
        return best;
    }

    /** The item id of an empty shulker of a template colour ("plain" is the undyed one). */
    static String colourShulkerId(String colour) {
        return colour.equals("plain") ? "shulker_box" : colour + "_shulker_box";
    }

    /** As {@link #findEmptyShulker()}, only shulkers of this colour (a template colour name, or "plain"). */
    private @Nullable Found findEmptyShulker(String colour) {
        String id = colourShulkerId(colour), offhand = offhandId();
        Found best = null;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.stale || box.noCompact || signPending(box) || blocked(box, Job.Fetch) || kitReserved(box) || box.contents == null) continue;
            for (int i = 0; i < box.contents.length; i++) {
                Item item = box.contents[i];
                if (!emptyShulker(item) || item.id.equals(offhand) || !item.id.equals(id)) continue;
                if (best == null || closer(box, best.box())) best = new Found(box, i);
                break;
            }
        }
        return best;
    }

    private @Nullable Found findEmptyShulker() {
        String offhand = offhandId();
        Found best = null;
        boolean bestSigned = false;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.stale || box.noCompact || signPending(box) || blocked(box, Job.Fetch) || kitReserved(box)) continue;
            if (box.labels.stream().anyMatch(Categories::isKeepWhole)) continue;
            boolean signed = box.labels.stream().anyMatch(l -> l.toLowerCase(java.util.Locale.ROOT).contains("shulker"));
            for (int i = 0; i < box.contents.length; i++) {
                Item item = box.contents[i];
                if (!emptyShulker(item) || item.id.equals(offhand)) continue;
                if (best == null || (signed && !bestSigned) || (signed == bestSigned && closer(box, best.box()))) {
                    best = new Found(box, i);
                    bestSigned = signed;
                }
                break;
            }
        }
        return best;
    }

    private @Nullable String abortReason(CompactPlan plan) {
        if (plan.bin != null) {
            if (lost.lost() || pendingResume != null || tickCount < repackPausedUntil) return com.porter.qwertestash.orders.OrderBlocker.SHULKER_PAUSED;
            // The time cap counts from the last headway (a stack taken, a stack packed), not from the start: a big shulker takes long
            Bin bin = plan.bin;
            long sig = bin.packed.size() * 1_000_003L;
            for (BinTracker.Held h : carriedHeld(bin)) sig += h.count() * 31L + 1;
            long now = System.currentTimeMillis();
            if (sig != bin.progressSig || bin.progressAt == 0) {
                bin.progressSig = sig;
                bin.progressAt = now;
            }
            else if (now - bin.progressAt > BIN_STALL_MS) return "no headway for " + BIN_STALL_MS / 60_000 + " minutes";
            return plan.jobs > 12 + 4 * plan.target ? "it took too many trips" : null;
        }
        if (!compactOn()) return "compacting is off or paused";
        if (plan.jobs > 12 + 4 * plan.target) return "it took too many trips";
        if (!hasHome(plan.reference)) return "its category is gone";
        return null;
    }

    /**
     * Gives the plan up. A shulker standing in the world is still fetched back first.
     */
    private void abortCompaction(String why) {
        abortCompaction(why, null);
    }

    /**
     * @param detail for an order's shulker: what was looked at (the next load, the containers turned down and why), said
     *               with the reason at every log level, as the order's other step lines are
     */
    private void abortCompaction(String why, @Nullable String detail) {
        CompactPlan plan = compact;
        if (plan == null) return;
        compactCooldown.put(plan.key, tickCount + COMPACT_COOLDOWN);
        compactBlocker = why;
        if (plan.bin != null && !plan.aborting) {
            step("order: gave up on the shulker being built: " + why + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")")
                + "; next: " + (plan.placedPos != null ? "pick it up, then " : "") + "deliver what it holds or plan it again.");
        }
        else debug("Compacting {} given up on: {}.", plan.reference.name, why);
        plan.aborting = true;
        if (plan.bin != null && plan.bin.failure == null) plan.bin.failure = why;
        syncInFlight();
    }

    /**
     * The next job of the plan in hand, or false when it is over (done or given up on) and normal sorting carries on.
     */
    private boolean continueCompaction() {
        CompactPlan plan = compact;
        if (plan == null) return false;

        // A resumed plan is over once its shulker has been picked up
        if (plan.finishOnly && !plan.aborting && plan.placedPos == null) {
            compactCooldown.put(plan.key, tickCount + COMPACT_DONE_COOLDOWN);
            compact = null;
            syncInFlight();
            return false;
        }

        if (!plan.aborting) {
            String why = abortReason(plan);
            if (why != null) abortCompaction(why);
        }
        if (plan.aborting) {
            if (plan.placedPos != null && !goneAt(plan.placedPos)) return startPackJob(plan);
            if (plan.placedPos != null) return recoverLost(plan);
            compact = null;
            return false;
        }
        if (plan.placedPos != null && goneAt(plan.placedPos)) return recoverLost(plan);

        boolean placedNow = plan.placedPos != null;
        int shulkerSlot = placedNow ? -1 : findPlanShulker();
        int have = placedNow ? plan.placedSize : shulkerSlot >= 0 ? inventoryItem(shulkerSlot).contents.size() : -1;
        int carriedMatch = 0;
        for (int slot : carriedSlots()) if (matchesPlan(inventoryItem(slot))) carriedMatch++;
        int free = freeCarryCount();
        int skipped = 0;
        if (plan.bin != null) {
            Bin bin = plan.bin;
            if (!placedNow && shulkerSlot >= 0) bin.packed = heldOf(inventoryItem(shulkerSlot).contents);
            if (have >= 0) have = bin.lots.size() - BinTracker.notPacked(bin.lots, bin.packed).size();
            carriedMatch = BinTracker.ready(bin.lots, bin.packed, carriedHeld(bin)).size();
            skipped = BinTracker.skipped(bin.lots, bin.packed, carriedHeld(bin), bin.unavailable);
            // Everything that can be had is in it: it goes out short of the loads left out, with them named
            if (have >= 0 && have < plan.target && have + skipped >= plan.target && carriedMatch == 0) {
                abortCompaction("not enough left to pack: no source for " + String.join(", ", bin.unavailable));
                return continueCompaction();
            }
        }

        if (have >= plan.target) {
            if (placedNow) return startPackJob(plan);
            completeCompaction(plan, have);
            return false;
        }
        if (have < 0) {
            if (++plan.acquireTries > 2) {
                abortCompaction("couldn't get a shulker");
                return continueCompaction();
            }
            return acquireShulker(plan);
        }

        // Loads left out for want of a source are not waited for
        int remaining = plan.target - have - skipped;
        if (carriedMatch >= remaining || (carriedMatch > 0 && free <= 1)) return startPackJob(plan);

        Box source = compactSourceBox(plan);
        if (plan.bin != null) {
            // An order's shulker: BinStep decides. Too little carry room is not a lack of sources: what is carried and not part
            // of the shulker is put away first
            boolean shulkerSource = source == null && carriedMatch == 0 && hasUnpackSource(plan.bin);
            switch (BinStep.next(remaining, carriedMatch, free, source != null, shulkerSource, canMakeRoom(plan))) {
                case PACK -> {
                    return startPackJob(plan);
                }
                case UNPACK -> {
                    if (startUnpack(plan)) return true;
                    if (skipLoad(plan.bin)) return continueCompaction();
                    abortCompaction("not enough left to pack", noSourceDetail(plan.bin));
                    return continueCompaction();
                }
                case PUT_AWAY -> {
                    if (makeRoom(plan)) return true;
                    abortCompaction("no carry room", null);
                    return continueCompaction();
                }
                case NO_ROOM -> {
                    abortCompaction("no carry room", freeCarryCount() + " free carry slots, nothing to put away");
                    return continueCompaction();
                }
                case NO_SOURCE -> {
                    if (skipLoad(plan.bin)) return continueCompaction();
                    abortCompaction("not enough left to pack", noSourceDetail(plan.bin));
                    return continueCompaction();
                }
                case GATHER -> {
                    // Below, as for compacting
                }
            }
        }
        if (source == null) {
            if (carriedMatch > 0) return startPackJob(plan);
            // No loose stack is left for the next load: it is taken out of a shulker, one slot kept free for picking it up again
            if (plan.bin != null && free >= 2 && startUnpack(plan)) return true;
            if (plan.bin != null && skipLoad(plan.bin)) return continueCompaction();
            abortCompaction("not enough left to pack");
            return continueCompaction();
        }

        // However much room there is, in rounds: a batch, into the shulker, the next batch. One slot stays free
        int batch = Math.min(remaining - carriedMatch, free - 1);
        if (batch <= 0) {
            abortCompaction("no carry room");
            return continueCompaction();
        }
        gatherLeft = batch;
        final int packed = have;
        step((plan.bin != null ? "order: gathering " + batch + " stacks (" + packed + "/" + plan.target + " in the shulker)" : "compacting " + plan.reference.name + ": gathering " + batch + " stacks (" + packed + "/" + plan.target + " packed)")
            + "; next: pack them into the shulker" + (plan.bin != null ? "." : " at " + xyz(plan.placedPos != null ? plan.placedPos : playerFeet()) + "."));
        plan.jobs++;
        return start(Job.Gather, source);
    }

    private boolean acquireShulker(CompactPlan plan) {
        Box box;
        int slot;
        Predicate<Item> want;
        if (plan.topUp) {
            box = plan.sourceBox;
            slot = plan.sourceSlot;
            if (box == null || !box.known() || slot < 0 || slot >= box.contents.length || !isPlanShulker(box.contents[slot], plan)) {
                abortCompaction("the shulker to top up has moved");
                return continueCompaction();
            }
            want = it -> isPlanShulker(it, plan);
        }
        else if (plan.bin != null && plan.bin.unit.kitBase != null) {
            // A kit build starts from the incomplete copy found for it (its group key), not an empty shulker
            String baseKey = plan.bin.unit.kitBase;
            Found found = findShulkerByKey(baseKey);
            if (found == null) {
                abortCompaction("the incomplete copy to top up has moved");
                return continueCompaction();
            }
            box = found.box();
            slot = found.slot();
            want = it -> it != null && it.isShulker() && baseKey.equals(ManagerIndex.shulkerKey(it));
        }
        else {
            // A kit build takes an empty shulker of the template colour, else any (noted colour_substituted when the finished kit is delivered)
            Found found = plan.bin != null && plan.bin.unit.kitColor != null ? findEmptyShulker(plan.bin.unit.kitColor) : null;
            if (found == null) found = findEmptyShulker();
            if (found == null) {
                abortCompaction("no empty shulker");
                return continueCompaction();
            }
            box = found.box();
            slot = found.slot();
            String offhand = offhandId();
            Item chosen = box.contents[slot];
            String wantId = plan.bin != null && plan.bin.unit.kitTemplate != null && chosen != null ? chosen.id : null;
            want = it -> emptyShulker(it) && !it.id.equals(offhand) && (wantId == null || wantId.equals(it.id));
        }
        plan.jobs++;
        wantSlot = slot;
        fetchWants = want;
        return start(Job.Fetch, box);
    }

    /**
     * Back to the shulker standing in the world, or the one in hand, to put the carried stacks in. When nothing more is
     * wanted in it, it is broken and picked up.
     */
    private boolean startPackJob(CompactPlan plan) {
        // The stacks of an order the order wants repaired are repaired before they go into the shulker
        if (plan.bin != null && !plan.aborting && beginRepair(plan)) return true;
        if (repairBegan) return false;
        plan.jobs++;
        if (plan.placedPos != null) {
            startHandling(Job.Fill, -1, plan.key);
            placed = plan.placedPos;
            handle = Handle.Return;
            returnTo = Handle.Open;
            // Back to the plan's own shulker: the colour to look for once it is broken, and the shulker count to measure the
            // pickup against, are this one's again, not those of a shulker put down and picked up since (one unpacked)
            backToStanding(plan);
            return true;
        }
        int slot = findPlanShulker();
        if (slot < 0) {
            abortCompaction("the shulker is missing");
            return continueCompaction();
        }
        return startHandling(Job.Fill, slot, plan.key);
    }

    /**
     * The shulker that was put down is gone from where it was: said loudly, and looked for on the ground.
     */
    private boolean recoverLost(CompactPlan plan) {
        BlockPos at = plan.placedPos;
        plan.placedPos = null;
        plan.aborting = true;
        error("The shulker holding {} stacks of {} that I put down at {} is gone. Looking for it on the ground.", plan.placedSize, plan.reference.name, at);
        inGameAlertActivePlayer("<red>The shulker at " + at + " is gone.");
        alertDm("The shulker holding " + plan.placedSize + " stacks of " + plan.reference.name + " at " + at + " is gone, looking for it on the ground.");
        startHandling(Job.Fill, -1, plan.key);
        placed = at;
        handle = Handle.Collect;
        // The plan's shulker is what is looked for, not the last one put down (which may be another colour)
        backToStanding(plan, at);
        collectStarted();
        syncInFlight();
        return true;
    }

    /** The plan's shulker standing (or lying) at its spot is the one handled now: see backToStanding(CompactPlan, BlockPos). */
    private void backToStanding(CompactPlan plan) {
        backToStanding(plan, plan.placedPos);
    }

    /**
     * The plan's shulker, out of the inventory at this spot, is the one handled now: placedItem (the filter for what is
     * looked for and picked up) is its colour again, or null for any shulker when that isn't known, and the pickup is
     * measured from the shulkers held now.
     */
    private void backToStanding(CompactPlan plan, @Nullable BlockPos at) {
        String id = ShulkerPickup.lookFor(plan.shulkerId, at == null ? null : shulkerBlockAt(at));
        placedItem = id == null ? null : ItemRegistry.REGISTRY.get(id);
        if (plan.shulkerId == null && id != null) plan.shulkerId = id;
        pickupWatch.standing(countInInv(this::isShulkerBox), shulkerSlots());
    }

    /** The shulker block standing here, or null when there is none or the chunk isn't loaded. */
    private @Nullable String shulkerBlockAt(BlockPos at) {
        if (!Containers.isLoaded(at)) return null;
        String name = World.getBlock(at.x(), at.y(), at.z()).name();
        return name.endsWith("shulker_box") ? name : null;
    }

    /** The inventory slots (0-35) that hold a shulker now. */
    private List<Integer> shulkerSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            Item item = inventoryItem(i);
            if (item != null && item.isShulker()) slots.add(i);
        }
        return slots;
    }

    private void completeCompaction(CompactPlan plan, int size) {
        if (plan.bin != null) {
            plan.bin.completed = true;
            debug(() -> "Order shulker built: " + plan.bin.lots.size() + " stacks.");
            compact = null;
            syncInFlight();
            return;
        }
        int stacks = size - plan.startSize;
        if (!plan.topUp) {
            compactedShulkers++;
            runPacked++;
        }
        slotsFreed += stacks - (plan.topUp ? 0 : 1);
        runFreed += stacks - (plan.topUp ? 0 : 1);
        info("Packed {} stacks of {} into a shulker.", stacks, plan.reference.name);
        compactCooldown.put(plan.key, tickCount + COMPACT_DONE_COOLDOWN);
        compact = null;
        syncInFlight();

        // Real headway: the circle watchdog starts over
        passMin = Integer.MAX_VALUE;
        passJobs = 0;
        boxVisits.clear();
        stalled.removeAll(circular);
        circular.clear();
        clearStalled();
    }

    /**
     * Takes up to the trip's number of full stacks of the plan's item into free carry slots, checking each against
     * the plan's key as it is in the window.
     */
    private boolean gatherOne(Container c) {
        // A craft's inputs, or an anvil to put down
        Bin gathering = repairBottles != null ? repairBottles : compact == null ? gatheringBin() : null;
        if (gathering != null) {
            if (gatherLeft <= 0 || freeCarryCount() <= 1) return false;
            return takeLot(c, gathering, target.pos, true);
        }
        CompactPlan plan = compact;
        if (plan == null || gatherLeft <= 0 || freeCarryCount() <= 1) return false;
        if (plan.bin != null) return gatherBinOne(c, plan);

        int into = freeCarrySlot();
        if (into == -1) return false;
        for (int i = 0; i < Stashes.slots(c); i++) {
            Item item = Item.of(c.getItemStack(i));
            if (item == null || !item.isFullStack() || !plan.key.equals(item.bulkKey())) continue;
            clicks(c, i, toWindow(into, c));
            stashHeld.put(into, item.id);
            gatherLeft--;
            return true;
        }
        return false;
    }

    /**
     * Puts one more reserved stack into the open shulker, until it holds what the plan wants. The shulker's own
     * contents are checked first: anything that is not the plan's item and the plan is given up on.
     */
    private boolean packPlanOne(Container c, CompactPlan plan) {
        if (plan.aborting) return false;
        if (plan.bin != null) return packBinOne(c, plan);

        int size = 0;
        for (int i = 0; i < 27; i++) {
            Item here = Item.of(c.getItemStack(i));
            if (here == null) continue;
            if (!plan.key.equals(here.bulkKey())) {
                warn("The shulker I'm packing holds something that isn't {}, not adding to it.", plan.reference.name);
                abortCompaction("the shulker holds something else");
                return false;
            }
            size++;
        }
        if (size >= plan.target) return false;

        for (int slot : carriedSlots()) {
            if (!matchesPlan(inventoryItem(slot))) continue;
            shiftClick(c, toWindow(slot, c));
            moved++;
            return true;
        }
        return false;
    }

    /**
     * The batch is in. More wanted and more to be had: the window is closed and the shulker stays where it is while the
     * next batch is gathered. Otherwise it is broken and picked up.
     */
    private void afterPacking(Container c, CompactPlan plan) {
        if (plan.bin != null) {
            afterPackingBin(c, plan);
            return;
        }
        int size = 0;
        for (int i = 0; i < 27; i++) if (Item.of(c.getItemStack(i)) != null) size++;
        plan.placedSize = size;

        // Stacks are waiting and nothing went in: it is not going to, so don't come back for it
        int waiting = 0;
        for (int slot : carriedSlots()) if (matchesPlan(inventoryItem(slot))) waiting++;
        if (actions == 0 && waiting > 0) {
            abortCompaction("nothing could be packed");
            handle = Handle.Break;
            handleTicks = 0;
            wait = CONFIG.delayTicks;
            return;
        }

        // A resumed shulker is only finished, never left standing for another batch
        // With fewer than 3 stacks left to add (to reach the target, or to be had in the stash) the shulker is broken and
        // picked up instead of left standing for another trip
        int stacksLeft = Math.min(plan.target - size, availableFull(plan));
        boolean more = !plan.aborting && !plan.finishOnly && size < plan.target && compactSourceBox(plan) != null && freeCarryCount() >= 2 && stacksLeft >= 3;
        if (!plan.aborting && !plan.finishOnly && size < plan.target && !more && stacksLeft < 3) {
            debug(() -> "Compacting " + plan.reference.name + ": " + stacksLeft + " stacks left to add, breaking the shulker instead of leaving it standing.");
        }
        handle = more ? Handle.Leave : Handle.Break;
        handleTicks = 0;
        wait = CONFIG.delayTicks;
        if (more) plan.placedPos = placed;
        syncInFlight();
    }

    private void handleLeave() {
        if (openContainerId() != 0) {
            closeSafely();
            return;
        }
        // A shulker that turned out not to be ours is left exactly as it is
        if (resumeLeaveAlone) {
            resumeLeaveAlone = false;
            resuming = false;
            compact = null;
            endHandling();
            return;
        }
        // Left standing: the plan knows where, and the next batch comes back to it
        CompactPlan plan = compact;
        if (plan != null) plan.placedPos = placed;
        endHandling();
    }

    /**
     * A spot beside the bot for a shulker that is safe: nothing below it that would empty it (a hopper in any
     * direction) and no chest under it, which would not open with a block on top.
     */
    private @Nullable BlockPos safeSpot(@Nullable BlockPos avoid) {
        Set<BlockPos> seen = new HashSet<>();
        if (avoid != null) seen.add(avoid);
        BlockPos last = avoid;
        Map<String, Integer> rejected = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            BlockPos spot = selectSpotBeside(last);
            if (spot == null) {
                rejected.merge("no open air cell left beside the bot", 1, Integer::sum);
                break;
            }
            if (!seen.add(spot)) break;
            String why = unsafeBecause(spot);
            if (why == null) return spot;
            debug("Not putting a shulker at {}: {}.", spot, why);
            rejected.merge(why, 1, Integer::sum);
            last = spot;
        }
        info("No spot for a shulker beside the bot at {}: {}.", playerFeet(),
            rejected.entrySet().stream().map(e -> e.getValue() + "x " + e.getKey()).collect(java.util.stream.Collectors.joining(", ")));
        return null;
    }

    /**
     * Why a shulker mustn't be put at this spot, or null if it may.
     */
    private @Nullable String unsafeBecause(BlockPos spot) {
        if (failedSpots.contains(spot)) return "placing there failed before";
        if (stash.boxAt(spot) != null) return "a stash container is there";
        // What is dropped there when the shulker is broken has to be walkable to: floor under it, and open on two sides
        // at least (the bot's own side and one more), not a gap between containers
        if (hollow(spot.x(), spot.y() - 1, spot.z())) return "there is no floor under it";
        int open = 0;
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (hollow(spot.x() + d[0], spot.y(), spot.z() + d[1]) && stash.boxAt(spot.add(d[0], 0, d[1])) == null) open++;
        }
        if (open < 2) return "it is boxed in, an item dropped there could not be reached";
        // Another player or a mob whose box, a little wider than it is, overlaps the spot's cell blocks the place. Not the
        // bot itself, and not items, experience, frames or armor stands
        int botId = CACHE.getPlayerCache().getEntityId();
        for (var entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity == null || entity.getEntityId() == botId || !(entity instanceof EntityLiving)) continue;
            if (entity.getEntityType() == EntityType.ARMOR_STAND) continue;
            double width = 0.6, height = 1.8;
            try {
                width = entity.dimensions().getX();
                height = entity.dimensions().getY();
            } catch (RuntimeException ignored) {
                // Normal player size
            }
            double margin = width / 2 + 0.3;
            if (entity.getX() + margin > spot.x() && entity.getX() - margin < spot.x() + 1
                && entity.getZ() + margin > spot.z() && entity.getZ() - margin < spot.z() + 1
                && entity.getY() < spot.y() + 1 && entity.getY() + height > spot.y()) {
                return "a player or mob is standing in the way";
            }
        }
        BlockPos below = spot.add(0, -1, 0);
        for (Box box : stash.boxes) if (box.has(below)) return "it is on top of the container at " + box.pos;
        int[][] around = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            if (World.getBlock(spot.x() + d[0], spot.y() + d[1], spot.z() + d[2]).name().equals("hopper")) return "there is a hopper next to it";
        }
        return null;
    }

    // Repacking shulkers

    private boolean repackOn() {
        return CONFIG.repack && CONFIG.mode == Mode.Signs && !repackFailed && pendingResume == null && tickCount >= repackPausedUntil;
    }

    /**
     * Cleans a carried mixed shulker, or packs carried items into a bulk shulker, fetching one if needed.
     * @return true when a job was started
     */
    private boolean nextRepackJob() {
        // Compacting has the shulkers and the stacks set aside for it
        if (compact != null) return false;

        // A carried shulker with items in it that belong elsewhere: clean it out right here
        if (freeCarrySlot() != -1) {
            for (int slot : carriedSlots()) {
                Item shulker = inventoryItem(slot);
                if (strays(shulker).isEmpty()) continue;
                mainId = Categories.dominantContent(shulker).kindKey();
                step("repacking: cleaning a mixed shulker beside " + xyz(playerFeet()) + "; next: break and pick it up, then put away " + strays(shulker).size() + " stacks.");
                return startHandling(Job.Clean, slot, null);
            }
        }

        // Loose items with a bulk shulker to go in
        for (int slot : carriedSlots()) {
            Item loose = inventoryItem(slot);
            String id = loose == null ? null : loose.bulkKey();
            if (id == null || packTried.contains(id)) continue;
            int stacks = carriedStacksOf(id);
            // With compacting on, new bulk shulkers are made by compacting: a full shulker's worth, never a part-filled one
            boolean startNew = !CONFIG.compact && CONFIG.newBulkAt > 0 && stacks >= CONFIG.newBulkAt;

            for (int s : carriedSlots()) {
                Item shulker = inventoryItem(s);
                if (bulkWithRoom(shulker, id) || (startNew && emptyShulker(shulker))) return startHandling(Job.Fill, s, id);
            }

            if (freeCarrySlot() != -1) {
                Box from = null;
                int fromSlot = -1;
                for (Box box : stash.boxes) {
                    if (!box.sortable() || box.stale || box.dropOff || blocked(box, Job.Fetch) || signPending(box) || kitReserved(box)) continue;
                    for (int i = 0; i < box.contents.length; i++) {
                        Item s = box.contents[i];
                        if ((bulkWithRoom(s, id) || (startNew && emptyShulker(s))) && (from == null || closer(box, from))) {
                            from = box;
                            fromSlot = i;
                            break;
                        }
                    }
                }
                if (from != null) return fetch(from, fromSlot);
            }

            // Nowhere to pack it: it's put away loose
            packTried.add(id);
        }

        return false;
    }

    private boolean fetch(Box box, int slot) {
        wantSlot = slot;
        fetchWants = it -> it.isShulker() && !it.customName;
        return start(Job.Fetch, box);
    }

    /**
     * Takes the one shulker this trip came for into a free carry slot, to repack it.
     * @return false once it's taken, or if it's gone
     */
    private boolean fetchOne(Container c) {
        if (wantSlot < 0 || actions > 0) return false;

        int into = freeCarrySlot();
        // What is really in that slot now, not what was saved
        Item there = wantSlot < Stashes.slots(c) ? Item.of(c.getItemStack(wantSlot)) : null;
        if (into == -1 || there == null || !fetchWants.test(there)) return false;

        clicks(c, wantSlot, toWindow(into, c));
        stashHeld.put(into, there.id);
        // A shulker an order fetched goes back where it came from if the order does not deliver it
        if (orders.flow.holdsOrder() && target != null) orderSources.put(into, new OrderSource(there.id, target.pos));
        if (unpackFetchOrigin != null) {
            sourceShulkers.put(into, new SourceShulker(unpackFetchOrigin));
            unpackFetchOrigin = null;
        }
        wantSlot = -1;
        return true;
    }

    /**
     * Items inside a shulker that belong in another signed container than what the shulker is mostly full of. Empty
     * when the shulker isn't one to clean: not a shulker, named, all one item, or wanted whole by a sign like "kits".
     */
    private List<Item> strays(@Nullable Item shulker) {
        if (shulker == null || !shulker.isShulker() || shulker.customName) return List.of();

        Item main = Categories.dominantContent(shulker);
        if (main == null) return List.of();

        // A kit that settings route (or would route to a missing chest) is kept whole; with no setting for it, repacking is as in 1.13.0
        if (kitWhole(shulker)) return List.of();

        // A chest signed for kits takes mixed shulkers whole instead
        if (!keepWholeBoxes().isEmpty()) return List.of();

        List<Item> out = new ArrayList<>();
        for (Item inside : shulker.contents) {
            if (inside.isFilledBundle() || (!inside.kindKey().equals(main.kindKey()) && !bestSigned(inside).isEmpty())) out.add(inside);
        }
        return out;
    }

    /**
     * A shulker holding only {@code id}, with room for more.
     */
    private static boolean bulkWithRoom(@Nullable Item shulker, @Nullable String key) {
        if (key == null || shulker == null || !shulker.isShulker() || shulker.customName || shulker.contents.isEmpty()) return false;

        boolean room = shulker.contents.size() < 27;
        for (Item inside : shulker.contents) {
            if (!key.equals(inside.bulkKey())) return false;
            if (inside.count < inside.maxStack) room = true;
        }
        return room;
    }

    private static boolean emptyShulker(@Nullable Item shulker) {
        return shulker != null && shulker.isShulker() && !shulker.customName && shulker.contents.isEmpty();
    }

    private int carriedStacksOf(String key) {
        int count = 0;
        for (int slot : carriedSlots()) {
            Item item = inventoryItem(slot);
            if (item != null && key.equals(item.bulkKey())) count++;
        }
        return count;
    }

    private int freeCarryCount() {
        int count = 0;
        for (int slot : carry) if (inventoryItem(slot) == null) count++;
        return count;
    }

    private boolean closer(Box a, Box b) {
        BlockPos feet = playerFeet();
        return distSq(feet, nearestPart(a)) < distSq(feet, nearestPart(b));
    }

    private boolean startHandling(Job next, int slot, @Nullable String fill) {
        if (compact == null) passJobs++;
        markJobStart();
        job = next;
        target = null;
        handledSlot = slot;
        fillId = fill;
        handle = Handle.Hold;
        handleTicks = 0;
        placed = null;
        placeWalk = null;
        placeSearches = 0;
        placeSkip.clear();
        pickupWatch.forgetBreak();
        actions = 0;
        jobInterruptions = 0;
        resetJobState();
        return true;
    }

    /**
     * One step of repacking a shulker beside the bot: place, open, move items, break, pick back up.
     */
    private void tickHandling() {
        handleTicks++;
        // Finishing a shulker left from before is bounded: ten minutes, or the usual retries, then it is reported and left
        if (resuming && tickCount > resumeDeadline && placed != null) {
            activity.releaseOurPathing();
            restoreRules();
            abandonShulker("Couldn't finish the shulker at " + xyz(placed) + " within 10 minutes.");
            return;
        }
        if (busy()) return;

        switch (handle) {
            case Hold -> handleHold();
            case Place -> handlePlace();
            case Open -> handleOpen();
            case Work -> handleWork();
            case Break -> handleBreak();
            case Collect -> handleCollect();
            case Return -> handleReturn();
            case Leave -> handleLeave();
        }
    }

    /**
     * Walks back to the shulker we put down, after giving way took us away from it.
     */
    private int returnRetries, standRetries;
    // Close enough to a dropped shulker to see and fetch it (squared blocks)
    private static final double COLLECT_NEAR_SQ = 6 * 6;
    // Spots where a place failed, not tried again until one works or sorting starts over
    private final Set<BlockPos> failedSpots = new HashSet<>();
    private @Nullable BlockPos lastGoodStand;
    // The last request to break the shulker in the world, so it is asked for once and not again every few ticks
    private @Nullable BlockPos breakAsked;
    private long breakAskedAt;
    // When the shulker's spot first looked clear after breaking it, and how often a "broken" shulker turned out to still
    // be standing (the server refused the break) for the shulker in the world now
    private long brokenSince = -1;
    private int breakRetries;
    private static final int BREAK_SETTLE_TICKS = 10;
    // A refused break (the server puts the shulker back) is asked again after a growing wait, not at once: 5 s, 15 s, 30 s, 60 s, 90 s
    private static final int MAX_BREAK_RETRIES = 5;
    private static final int[] BREAK_BACKOFF_TICKS = {20 * 5, 20 * 15, 20 * 30, 20 * 60, 20 * 90};

    private static int breakBackoff(int retries) {
        return BREAK_BACKOFF_TICKS[Math.max(0, Math.min(retries, BREAK_BACKOFF_TICKS.length) - 1)];
    }

    /**
     * The shulker was seen gone and is standing again: the server refused the break and put it back. Bot tick thread.
     */
    private void breakRolledBack() {
        activity.releaseOurPathing();
        restoreRules();
        breakAsked = null;
        brokenSince = -1;
        if (++breakRetries > MAX_BREAK_RETRIES) {
            abandonShulker("Couldn't break the shulker at " + fmt(placed) + ", the server put it back " + MAX_BREAK_RETRIES + " times.", true);
            return;
        }
        int wait = breakBackoff(breakRetries);
        alert("The server put the shulker at {} back after it was broken ({}/{}). Trying again in {} s.", fmt(placed), breakRetries, MAX_BREAK_RETRIES, wait / 20);
        // Counts as time spent waiting: the break timeout only runs once this is over
        handleTicks = -wait;
    }

    private void handleReturn() {
        // A chunk that isn't loaded says nothing about the shulker: walk to it first
        boolean loaded = placed != null && Containers.isLoaded(placed);
        // Going to look for a dropped shulker: close by is enough (and there is nothing standing to reach)
        boolean toCollect = returnTo == Handle.Collect;
        if (placed == null || (loaded && (toCollect ? distSq(playerFeet(), placed) <= COLLECT_NEAR_SQ : (!placed(placed) || arrivedAt(new GoalNear(placed, REACH_RANGE_SQ)))))) {
            activity.releaseOurPathing();
            returnRetries = 0;
            enterHandle(returnTo != null ? returnTo : Handle.Open);
            return;
        }

        if (handleTicks > WALK_TIMEOUT) {
            activity.releaseOurPathing();
            if (++returnRetries > 3) {
                abandonShulker("Couldn't get back to the shulker at " + placed + ".");
                return;
            }
            warn("Couldn't get back to the shulker at {} yet, trying again in a while ({}/3).", placed, returnRetries);
            handleTicks = 0;
            wait = 20 * 30;
            return;
        }

        if (activity.mayRequest()) {
            requestWalk(placed);
            wait = 10;
        }
    }

    /**
     * Gets the shulker into the first hotbar slot the pathfinder looks in for that item, so it places this one and
     * not another shulker of the same color.
     */
    private void handleHold() {
        Item shulker = inventoryItem(handledSlot);
        if (shulker == null || !shulker.isShulker()) {
            endHandling();
            return;
        }

        Item offhand = Item.of(CACHE.getPlayerCache().getPlayerInventory().get(45));
        if (offhand != null && offhand.id.equals(shulker.id)) {
            // Compacting can use a shulker of another colour; the old repacking has no choice
            CompactPlan plan = compact;
            if (plan != null) {
                for (int slot : carriedSlots()) {
                    Item other = inventoryItem(slot);
                    if (other != null && !other.id.equals(offhand.id) && isPlanShulker(other, plan)) {
                        handledSlot = slot;
                        return;
                    }
                }
                abortCompaction("the offhand holds a shulker of the same color");
                endHandling();
                return;
            }
            warn("The offhand holds a shulker of the same color, so I'm not repacking shulkers this run.");
            repackFailed = true;
            endHandling();
            return;
        }

        // The first hotbar slot that's allowed, with no same-colored shulker before it
        Set<Integer> keep = keepSlots();
        int hotbar = -1;
        for (int i = 0; i < 9; i++) {
            if (i == handledSlot) {
                hotbar = i;
                break;
            }
            if (keep.contains(i)) {
                Item there = inventoryItem(i);
                if (there != null && there.id.equals(shulker.id)) break;
                continue;
            }
            hotbar = i;
            break;
        }
        if (hotbar == -1) {
            warn("No hotbar slot I'm allowed to use to place shulkers, so I'm not repacking shulkers this run.");
            repackFailed = true;
            endHandling();
            return;
        }

        if (hotbar != handledSlot) {
            // Refused: someone else's request is ahead, we give way at the end of the tick and try again after
            if (submit(new MoveToHotbarSlot(invSlot(handledSlot), MoveToHotbarAction.from(hotbar))) == RequestFuture.rejected) return;
            if (inventoryItem(hotbar) != null) {
                swapBackFrom = handledSlot;
                swapBackTo = hotbar;
            }
            handledSlot = hotbar;
            wait = CONFIG.delayTicks;
            return;
        }

        // Shulkers are not put down in quick succession: that is what a build bot does
        if (tickCount - lastPlaceTick < PLACE_GAP_TICKS) {
            wait = 10;
            return;
        }
        // On the way to a spot found nearby
        if (placeWalk != null && activity.ownPathing()) {
            // An order that is cancelled or parked meanwhile: nothing is put down, the shulker stays carried
            if (orderShulkerCalledOff()) {
                activity.releaseOurPathing();
                restoreRules();
                placeWalk = null;
                endHandling();
                return;
            }
            if (handleTicks > WALK_TIMEOUT) {
                activity.releaseOurPathing();
                restoreRules();
                placeSkip.add(placeWalk.spot());
                placeWalk = null;
            }
            return;
        }
        if (!activity.mayRequest()) return;
        placed = null;
        PlaceSearch.Found walked = placeWalk;
        placeWalk = null;
        if (walked != null) {
            // Got there: the spot that was found, if it is still good; the walk did not get there: that spot is skipped
            boolean there = distSq(playerFeet(), walked.stand()) <= 2;
            BlockPos at = walked.spot();
            if (there && placeView.open(at.x(), at.y(), at.z()) && unsafeBecause(at) == null && !placeGuarded(at)) placed = at;
            else placeSkip.add(walked.spot());
        }
        if (placed == null) placed = safeSpot(null);
        if (placed != null && placeGuarded(placed)) placed = null;
        if (placed == null) {
            // No room beside the bot: the nearest good spot within 5 blocks it can walk to, and walk there
            if (orderShulkerCalledOff()) {
                endHandling();
                return;
            }
            PlaceSearch.Result found = placeSearches < MAX_PLACE_SEARCHES ? PlaceSearch.search(playerFeet(), placeView, placeSkip) : null;
            if (found != null && found.found() != null) {
                placeSearches++;
                PlaceSearch.Found f = found.found();
                placeWalk = f;
                handleTicks = 0;
                step("shulker: no room to put it down beside the bot; walking to a spot " + f.steps() + " blocks away (" + String.format("%.1f", Math.sqrt(distSq(playerFeet(), f.spot()))) + " blocks off) to put it down there.");
                rules(false, false);
                PathingRequestFuture future = BARITONE.pathTo(new GoalBlock(f.stand()));
                activity.notePath(future, BARITONE.getCustomGoalProcess(), null, tickCount);
                traceRequest("walk to a spot to put a shulker down", future);
                return;
            }
            String why = found == null ? "tried " + MAX_PLACE_SEARCHES + " spots nearby" : found.why();
            pauseShulkers("no spot within " + PlaceSearch.RADIUS + " blocks: " + why);
            endHandling();
            return;
        }

        placeFailures = 0;
        if (compact != null && unpackingNow == null) {
            compact.placedSize = shulker.contents.size();
            compact.shulkerId = shulker.id;
            // An order's own shulker, known from the first time it goes down: its colour, and whether it was empty then
            Bin own = compact.bin;
            if (own != null && !own.startKnown) {
                own.startKnown = true;
                own.startedEmpty = shulker.contents.isEmpty();
                own.shulkerId = shulker.id;
            }
        }
        pickupWatch.placing(countInInv(this::isShulkerBox));
        lastPlaceTick = tickCount;
        placedItem = ItemRegistry.REGISTRY.get(shulker.id);
        requestPlace(placed, placedItem);
        handle = Handle.Place;
        handleTicks = 0;
        noteSpotAsked();
    }

    private void handlePlace() {
        if (placed(placed) && pickupWatch.putDown(countInInv(this::isShulkerBox))) {
            activity.releaseOurPathing();
            restoreRules();
            if (compact != null && unpackingNow == null) compact.placedPos = placed;
            lastGoodStand = playerFeet();
            failedSpots.clear();
            handle = Handle.Open;
            syncInFlight();
            openRequestedAt = Long.MAX_VALUE;
            openIdBefore = openContainerId();
            handleTicks = 0;
            wait = CONFIG.delayTicks;
            return;
        }

        // The place request failed by itself (an entity in the way, nothing to place against): let go of it, so it is
        // not taken for another module, and try the next spot; after three, repacking waits a while
        if (activity.ownFailureSettled() && handleTicks > 2) {
            BlockPos failed = placed;
            failedSpots.add(failed);
            activity.releaseOurPathing();
            restoreRules();
            clearOrphan(failed);
            placeSkip.add(failed);
            BlockPos next = ++placeFailures >= 3 ? null : safeSpot(failed);
            if (next != null && placeGuarded(next)) next = null;
            if (next == null || next.equals(failed)) {
                // No other spot beside the bot: back to holding it, which looks for one within 5 blocks before it pauses
                placed = null;
                handle = Handle.Hold;
                handleTicks = 0;
                return;
            }
            placed = next;
            lastPlaceTick = tickCount;
            requestPlace(placed, placedItem);
            handleTicks = 0;
            noteSpotAsked();
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT) {
            activity.releaseOurPathing();
            restoreRules();
            clearOrphan(placed);
            pauseShulkers("couldn't put the shulker down");
            endHandling();
        }
    }

    private int placeFailures;
    // The search for somewhere to put a shulker down when there is no room beside the bot: the spot being walked to, how many
    // searches this shulker had, and spots not to try again for it
    private PlaceSearch.@Nullable Found placeWalk;
    private int placeSearches;
    private final Set<BlockPos> placeSkip = new HashSet<>();
    private static final int MAX_PLACE_SEARCHES = 4;

    /** The order whose shulker is being put down is cancelled or parked: nothing more is put down for it. */
    private boolean orderShulkerCalledOff() {
        CompactPlan plan = compact;
        boolean orderShulker = (plan != null && plan.bin != null) || activeBin != null || craft != null;
        return orderShulker && orders.flow.holdsOrder() && (orders.flow.cancelRequested() || orders.flow.parkRequested() || !orders.flow.running());
    }

    /** On or next to the delivery container, the anvil or the crafting table, or on top of a stash container. */
    private boolean placeGuarded(BlockPos spot) {
        for (BlockPos keep : guardedSpots()) {
            if (Math.abs(keep.x() - spot.x()) <= 1 && Math.abs(keep.y() - spot.y()) <= 1 && Math.abs(keep.z() - spot.z()) <= 1) return true;
        }
        BlockPos below = spot.add(0, -1, 0);
        for (Box box : stash.boxes) if (box.has(below)) return true;
        return false;
    }

    private List<BlockPos> guardedSpots() {
        List<BlockPos> out = new ArrayList<>();
        DeliverySpot.Spot d = DeliverySpot.get();
        if (d != null && DeliverySpot.here(d)) {
            out.add(d.pos());
            // Both halves of a double chest
            Box db = stash.boxAt(d.pos());
            if (db != null) out.addAll(db.parts);
        }
        // The chests put up above it (1.9.0)
        for (Box b : stash.boxes) if (b.delivery && b.deliveryExtra) out.addAll(b.parts);
        com.porter.qwertestash.stash.AnvilSpot.Spot a = namingSpot();
        if (a != null && AnvilSpot.here(a)) out.add(a.pos());
        CraftingTableSpot.Spot t = CraftingTableSpot.get();
        if (t != null && CraftingTableSpot.here(t)) out.add(t.pos());
        return out;
    }

    /** The world as the place search sees it. */
    private final PlaceSearch.View placeView = new PlaceSearch.View() {
        @Override
        public boolean open(int x, int y, int z) {
            return World.getBlock(x, y, z).isAir() && !playerBoxIntersectsCell(x, y, z);
        }

        @Override
        public boolean floor(int x, int y, int z) {
            return !hollow(x, y, z);
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return com.aquarius.feature.pathfinder.movement.MovementHelper.canWalkOn(x, y - 1, z)
                && com.aquarius.feature.pathfinder.movement.MovementHelper.canWalkThrough(x, y, z)
                && com.aquarius.feature.pathfinder.movement.MovementHelper.canWalkThrough(x, y + 1, z);
        }

        @Override
        public @Nullable String unsafe(BlockPos spot) {
            return unsafeBecause(spot);
        }

        @Override
        public boolean inArea(BlockPos spot) {
            return !stash.hasArea() || stash.inArea(spot, 0);
        }

        @Override
        public boolean guarded(BlockPos spot) {
            return placeGuarded(spot);
        }
    };

    /** The bot's own body is in this cell (it can't put a shulker where it stands). */
    private boolean playerBoxIntersectsCell(int x, int y, int z) {
        BlockPos feet = playerFeet();
        return feet.x() == x && feet.z() == z && (feet.y() == y || feet.y() + 1 == y);
    }

    private long repackPausedUntil;
    // What started the pause in repackPausedUntil, for the order's "paused: ..." and the order blocker
    private String shulkerPauseWhy = "";

    /**
     * Shulkers are not put down for 3 minutes after a problem. Said at every log level (Quiet too) and in the activity
     * feed, since it also holds orders back.
     */
    private void pauseShulkers(String why) {
        shulkerPauseWhy = why;
        repackPausedUntil = tickCount + 20 * 60 * 3;
        String text = Character.toUpperCase(why.charAt(0)) + why.substring(1) + ": not putting shulkers down for 3 minutes (repacking, compacting and orders wait).";
        MODULE_LOG.warn("{}{}", moduleLogPrefix, text);
        com.porter.qwertestash.manager.ActivityFeed.INSTANCE.event("Stash Sorter", "warn", text);
    }

    /** Why shulkers may not be put down now (the addon's shulkersOk), or null. */
    private @Nullable String shulkerPause() {
        return com.porter.qwertestash.orders.OrderBlocker.shulkerPause(lost.lost(), pendingResume != null, repackPausedUntil - tickCount, shulkerPauseWhy);
    }

    private void handleOpen() {
        // Put back whatever was swapped out of the hotbar to place it
        if (swapBackFrom != -1) {
            if (inventoryItem(swapBackTo) == null
                && submit(new MoveToHotbarSlot(invSlot(swapBackFrom), MoveToHotbarAction.from(swapBackTo))) == RequestFuture.rejected) {
                return;
            }
            swapBackFrom = swapBackTo = -1;
            wait = CONFIG.delayTicks;
            return;
        }

        int id = openContainerId();
        Container c = id != 0 ? openContainer() : null;
        if (c != null && isShulkerWindow(c) && containerOpenedAt() >= openRequestedAt) {
            activity.claimWindow(id, containerOpenedAt());
            pickupWatch.slots(shulkerSlots());
            handle = Handle.Work;
            handleTicks = 0;
            wait = openWait();
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT) {
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        // Something else is open: not the shulker's window. One that answered our click is ours to close, and the click
        // is made again. One that didn't isn't, so give way to whoever has it
        if (c != null) {
            long openedAt = containerOpenedAt();
            if (openedAt != lastRejectedAt) {
                lastRejectedAt = openedAt;
                warn("Ignoring the window that is open (id {}, {} slots, {}), it isn't the shulker I just opened (before the click: id {}).",
                    id, Stashes.slots(c), c.getType(), openIdBefore);
            }
            if (!ownsWindow()) {
                beginYield(Activity.Reason.Container);
                return;
            }
            if (closeSafely() == Close.Stuck) return;
            wait = CONFIG.delayTicks;
            return;
        }

        if ((handleTicks == 1 || handleTicks % 20 == 0) && activity.mayRequest()) {
            // The container delay: the click waits, and the tick is not counted against the open timeout or the retry cadence
            if (openPaused()) {
                handleTicks--;
                return;
            }
            if (!placed(placed)) {
                // A shulker left from before that isn't there any more: its item is looked for nearby
                if (resuming) {
                    resumeVerify = false;
                    handle = Handle.Collect;
                    handleTicks = 0;
                    collectStarted();
                }
                else endHandling();
                return;
            }
            if (resuming && resumeVerify && resumeBlockId != null && Containers.isLoaded(placed)) {
                String block = World.getBlock(placed.x(), placed.y(), placed.z()).name();
                if (!block.equals(resumeBlockId)) {
                    resumeMismatch("it is a " + block + ", not a " + resumeBlockId);
                    return;
                }
            }
            openIdBefore = openContainerId();
            openRequestedAt = lastOpenClickAt = System.currentTimeMillis();
            foreignSinceClick = false;
            requestOpen(placed);
        }
    }

    private void handleWork() {
        Container c = openContainerId() != 0 ? openContainer() : null;
        if (c == null || !isShulkerWindow(c)) {
            // A shulker left from before that hasn't been looked into yet is opened again, not broken unseen
            if (resumeVerify) enterHandle(Handle.Open);
            else {
                handle = Handle.Break;
                handleTicks = 0;
            }
            return;
        }
        if (resumeVerify) {
            String problem = resumeContentsProblem(c);
            if (problem != null) {
                resumeMismatch(problem);
                return;
            }
            resumeVerify = false;
            final int expected = compact != null ? compact.placedSize : -1;
            debug(() -> "The shulker at " + placed + " holds what the record says" + (expected >= 0 ? " (expected " + expected + " stacks)" : "") + ".");
        }
        if (holdingCursor()) {
            // Out of tries: Break closes it, and says so if the stack really has nowhere to go
            if (recoverBounded(c)) wait = CONFIG.delayTicks;
            else {
                handle = Handle.Break;
                handleTicks = 0;
            }
            return;
        }

        CompactPlan plan = job == Job.Fill ? compact : null;
        clickRefused = false;
        boolean busy = job == Job.Clean ? (unpackingNow != null ? takeFromShulker(c) : takeStray(c)) : plan != null ? packPlanOne(c, plan) : packOne(c);
        // A click that was refused (see bundleHazard) did nothing: the shulker is closed up instead of asking again
        if (clickRefused) {
            clickRefused = false;
            busy = false;
            if (job == Job.Clean && unpackingNow == null) repackFailed = true;
        }
        if (busy) {
            actions++;
            wait = CONFIG.delayTicks;
            return;
        }

        if (plan != null) {
            afterPacking(c, plan);
            return;
        }

        if (job == Job.Fill && actions == 0 && fillId != null) packTried.add(fillId);
        // Nothing to take out after all: stop trying, or we'd keep reopening the same shulker
        if (job == Job.Clean && actions == 0) {
            if (unpackingNow != null) {
                // Nothing the order needs came out of it: it is not tried again, and goes back
                badUnpack.add(unpackingNow.origin);
                unpackingNow.opened = true;
            }
            else repackFailed = true;
        }

        handle = Handle.Break;
        handleTicks = 0;
        wait = CONFIG.delayTicks;
    }

    /**
     * Takes one stack that belongs elsewhere out of the open shulker, into a free carry slot.
     */
    private boolean takeStray(Container c) {
        int into = freeCarrySlot();
        // One slot stays free, for picking the shulker back up
        if (into == -1 || freeCarryCount() <= 1) return false;

        for (int i = 0; i < 27; i++) {
            Item item = Item.of(c.getItemStack(i));
            if (item == null || item.kindKey().equals(mainId) || (!item.isFilledBundle() && bestSigned(item).isEmpty())) continue;
            if (item.isBundle()) {
                noteBundleTaken();
                shiftClick(c, i);
                moved++;
                return true;
            }
            clicks(c, i, toWindow(into, c));
            stashHeld.put(into, item.id);
            moved++;
            return true;
        }
        return false;
    }

    /**
     * Puts one carried stack of the item being packed into the open shulker.
     */
    private boolean packOne(Container c) {
        for (int slot : carriedSlots()) {
            Item item = inventoryItem(slot);
            if (item == null || fillId == null || !fillId.equals(item.bulkKey())) continue;

            for (int i = 0; i < 27; i++) {
                Item here = Item.of(c.getItemStack(i));
                if (here == null || (here.sameAs(item) && here.count < here.maxStack)) {
                    shiftClick(c, toWindow(slot, c));
                    moved++;
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private void handleBreak() {
        if (openContainerId() != 0) {
            // Never leaves with something on the cursor, and never while a click is still going out
            closeSafely();
            return;
        }

        // The pathfinder reports a break as soon as the block looks gone here, but the server can still refuse it and put
        // the shulker back: it only counts as broken once the spot has stayed clear for a moment
        if (!placed(placed)) {
            if (brokenSince < 0) {
                brokenSince = tickCount;
                noteBreak(placed);
            }
            if (tickCount - brokenSince < BREAK_SETTLE_TICKS) return;
            activity.releaseOurPathing();
            restoreRules();
            brokenSince = -1;
            handle = Handle.Collect;
            handleTicks = 0;
            collectStarted();
            return;
        }
        if (brokenSince >= 0) {
            breakRolledBack();
            return;
        }
        // It can't be picked up while it stands: what is held now is what picking it up adds to
        pickupWatch.sawStanding(countInInv(this::isShulkerBox));

        // A shulker left from before is only broken once it has been looked into and is the block that was put down
        if (resumeVerify) {
            activity.releaseOurPathing();
            restoreRules();
            abandonShulker("Couldn't look into the shulker at " + xyz(placed) + " to check it is the one I put down, so it is left standing.");
            return;
        }
        if (resuming && resumeBlockId != null && Containers.isLoaded(placed)) {
            String block = World.getBlock(placed.x(), placed.y(), placed.z()).name();
            if (!block.equals(resumeBlockId)) {
                activity.releaseOurPathing();
                restoreRules();
                resumeMismatch("it is a " + block + ", not a " + resumeBlockId);
                return;
            }
        }

        // Waiting out the back-off after a refused break (handleTicks counts up from a negative number)
        if (handleTicks < 0) return;
        if (handleTicks > HANDLE_TIMEOUT * 2) {
            activity.releaseOurPathing();
            restoreRules();
            abandonShulker("Couldn't break the shulker at " + placed + ", it's still there.");
            return;
        }

        // Breaking it needs a free slot to pick it up into: otherwise it would end up on the ground
        if (activity.mayRequest()) {
            if (!freeInventorySlot()) {
                if (handleTicks % 200 == 1) alert("No free inventory slot to pick the shulker at {} up into, leaving it where it is until there is one.", placed);
                if (handleTicks > 20 * 60 * 5) abandonShulker("No free inventory slot to pick the shulker at " + placed + " up into.");
                return;
            }
            // The pathfinder logs a start for every request: once is enough while it works on it
            if (!placed.equals(breakAsked) || tickCount - breakAskedAt > 20 * 4) {
                breakAsked = placed;
                breakAskedAt = tickCount;
                requestBreak(placed);
                syncInFlight();
            }
        }
    }

    private void handleCollect() {
        int got = pickedUpSlot();
        if (got == SLOT_UNKNOWN) {
            // The count grew but no slot shows the shulker: only a count that stays up, with nothing like ours left on the
            // ground, is a pickup, and collecting goes on meanwhile. After a minute of it, it is a pickup whatever lies there
            if (unknownSince < 0) unknownSince = tickCount;
            switch (ShulkerPickup.unknownSlot((int) Math.min(Integer.MAX_VALUE, tickCount - unknownSince), shulkerItemNearby() != null)) {
                case Wait -> {
                    return;
                }
                case NotPickedUp -> {
                    // Kept: the count staying up counts on, so a shulker like ours that isn't ours can't hold this up forever
                    got = NOT_PICKED_UP;
                }
                case PickedUp -> {
                }
            }
        }
        else unknownSince = -1;
        if (got != NOT_PICKED_UP) {
            activity.releaseOurPathing();

            // Picked up: make sure it's in a carry slot, so it gets put away
            boolean identified = false;
            if (got == SLOT_UNKNOWN) {
                // It is in the inventory, only not known where: the plan's shulker is no longer standing anywhere, so an
                // empty spot is not taken for a lost shulker (and looked for on the ground) on the next tick
                CompactPlan plan = unpackingNow != null ? null : compact;
                if (plan != null) {
                    plan.placedPos = null;
                    plan.slotHint = -1;
                }
                step("collecting: picked up the " + lookingFor() + " near " + (placed == null ? "?" : xyz(placed)) + ", slot not known.");
            }
            if (got >= 0) {
                Item gotItem = inventoryItem(got);
                step("collecting: picked up the " + (gotItem != null ? gotItem.id : lookingFor()) + " into slot " + (got + 1) + ".");
                int i = got;
                Item item = inventoryItem(i);
                CompactPlan plan = unpackingNow != null ? null : compact;
                int landed = i;
                if (plan != null) {
                    plan.placedPos = null;
                    plan.slotHint = i;
                    if (!plan.aborting && item.contents.size() != plan.placedSize) {
                        error("The shulker of {} I picked up holds {} stacks, I expected {} ({} missing). Something may have taken from it.",
                            plan.reference.name, item.contents.size(), plan.placedSize, plan.placedSize - item.contents.size());
                        compactCooldown.put(plan.key, tickCount + COMPACT_COOLDOWN);
                    }
                    clearOrphan(placed);
                }
                if (!carry.contains(i)) {
                    int free = freeCarrySlot();
                    if (free != -1) {
                        // Refused: tried again after giving way, with the shulker still in the inventory
                        if (submit(new ClickItem(0, invSlot(i), ClickItemAction.LEFT_CLICK), new ClickItem(0, invSlot(free), ClickItemAction.LEFT_CLICK)) == RequestFuture.rejected) return;
                        landed = free;
                    }
                    else warn("The repacked shulker ended up outside the carry slots, in slot {}.", i + 1);
                }
                if (unpackingNow != null) {
                    // The source shulker is back in the inventory, opened: it goes back to where it came from
                    sourceShulkers.values().remove(unpackingNow);
                    unpackingNow.opened = true;
                    sourceShulkers.put(landed, unpackingNow);
                    stashHeld.put(landed, item.id);
                }
                identified = true;
            }
            if (!identified) alert("Picked up a shulker near {} but couldn't tell which slot it is in, check the bot's inventory.", placed == null ? "?" : xyz(placed));
            clearOrphan(placed);
            endHandling();
            return;
        }

        // The break didn't go through after all: the server put the shulker back where it stood. It is broken again rather
        // than looked for on the ground, where it never was
        if (stillStanding()) {
            activity.releaseOurPathing();
            restoreRules();
            handle = Handle.Break;
            breakRolledBack();
            return;
        }

        if (handleTicks > 0 && handleTicks % (20 * 30) == 0) alert("Still looking for the {} near {}, {} seconds so far.", lookingFor(), placed == null ? "?" : xyz(placed), handleTicks / 20);
        if (handleTicks > COLLECT_TIMEOUT) {
            activity.releaseOurPathing();
            debug("Couldn't pick the shulker back up: {}.", pickupDiagnosis());
            abandonShulker("Couldn't pick the shulker back up, it's on the ground near " + fmt(placed) + ".");
            return;
        }

        Entity inSight = collectSight();
        // A walk or pickup of ours that gets no nearer to the shulker for a while is let go of and asked for again
        if (inSight != null && activity.ownPathing() && tickCount - collectProgressAt > COLLECT_NO_PROGRESS_TICKS) {
            activity.releaseOurPathing();
            restoreRules();
            collectProgressAt = tickCount;
            collectBest = Double.MAX_VALUE;
            pickupAskedAt = -1000;
            step("collecting: the pathfinder got no nearer to the " + lookingFor() + " in " + COLLECT_NO_PROGRESS_TICKS / 20 + "s; asking again.");
            return;
        }

        collectPickup();
    }

    // Collecting the broken shulker: since when the shulker count has been up with no slot to show for it, whether the
    // dropped shulker was in sight when last said, when that was, and the nearest the bot has come to it and when
    private long unknownSince = -1;
    private boolean collectSeen;
    private long collectSaidAt;
    private double collectBest = Double.MAX_VALUE;
    private long collectProgressAt;
    private static final int COLLECT_SIGHT_LOG_TICKS = 20 * 10;
    private static final int COLLECT_FLIP_TICKS = 20 * 5;
    private static final int COLLECT_NO_PROGRESS_TICKS = 20 * 10;

    /** What a broken shulker is looked for as, for the log. */
    private String lookingFor() {
        ItemData item = placedItem;
        return item != null ? item.name() : "shulker (any colour)";
    }

    /**
     * Collecting starts (or starts again after giving way): said at every log level, with the item looked for.
     */
    private void collectStarted() {
        unknownSince = -1;
        collectSeen = false;
        // The first sighting is said at once
        collectSaidAt = tickCount - COLLECT_FLIP_TICKS;
        collectBest = Double.MAX_VALUE;
        collectProgressAt = tickCount;
        step("collecting: looking for the " + lookingFor() + " on the ground near " + (placed == null ? "?" : xyz(placed)) + "; next: pick it up.");
    }

    /**
     * The dropped shulker in sight, or null. Whether it is in sight and how far the bot is from it is said at every log
     * level: when it comes into or goes out of sight, and every 10 seconds while it is in sight.
     */
    private @Nullable Entity collectSight() {
        Entity item = shulkerItemNearby();
        // Coming into or going out of sight is said at most every 5 seconds, so an item at the edge of range doesn't flood
        boolean flipAllowed = tickCount - collectSaidAt >= COLLECT_FLIP_TICKS;
        if (item == null) {
            if (collectSeen && flipAllowed) {
                collectSeen = false;
                collectSaidAt = tickCount;
                step("collecting: the " + lookingFor() + " is out of sight; next: ask the pathfinder to pick it up.");
            }
            return null;
        }
        double d = Math.sqrt(distSq(playerFeet(), cell(item)));
        if (d < collectBest - 0.5) {
            collectBest = d;
            collectProgressAt = tickCount;
        }
        if ((!collectSeen && flipAllowed) || (collectSeen && tickCount - collectSaidAt >= COLLECT_SIGHT_LOG_TICKS)) {
            collectSeen = true;
            collectSaidAt = tickCount;
            step(String.format(java.util.Locale.ROOT, "collecting: the %s is in sight at %s, %.1f blocks from the bot; next: pick it up.",
                lookingFor(), xyz(cell(item)), d));
        }
        return item;
    }

    /**
     * The collecting step is cut short by something else wanting the bot: said at every log level.
     */
    private void collectInterrupted(String why) {
        if (handle != Handle.Collect) return;
        step("collecting interrupted: " + why + "; next: carry on collecting the " + lookingFor() + ".");
    }

    private static final int NOT_PICKED_UP = -1;
    private static final int SLOT_UNKNOWN = -2;
    private static final int PICKUP_GRACE_TICKS = 20 * 3;

    /**
     * Where the shulker we broke is in the inventory once it has been picked up: its slot, SLOT_UNKNOWN when it was
     * picked up but can't be told apart, or NOT_PICKED_UP.
     * <p>
     * The shulker count alone was not enough: the proxy's inventory can show a shulker that is no longer there (a click
     * into a container whose window closed before the answer came), and then the count never comes back up and the bot
     * kept looking on the ground for a shulker it was holding. A shulker resumed after a reconnect may already be held,
     * too. So once nothing of ours is lying in sight for a few seconds, a held shulker that matches it counts as picked up.
     */
    private int pickedUpSlot() {
        int now = countInInv(this::isShulkerBox);
        boolean grew = pickupWatch.grew(now);
        if (grew) {
            for (int i = 0; i < 36; i++) {
                Item item = inventoryItem(i);
                if (item != null && item.isShulker() && !pickupWatch.heldBefore(i)) return i;
            }
            for (int i = 0; i < 36; i++) if (looksLikePlaced(inventoryItem(i))) return i;
            return SLOT_UNKNOWN;
        }
        // Only standing close by, with the spot clear (a refused break puts the block back) and nothing lying in sight,
        // and only when exactly one held shulker could be it: otherwise it is looked for on the ground as before
        if (placed == null || handleTicks <= PICKUP_GRACE_TICKS || distSq(playerFeet(), placed) > 6 * 6 || shulkerItemNearby() != null) return NOT_PICKED_UP;
        if (Containers.isLoaded(placed) && World.getBlock(placed.x(), placed.y(), placed.z()).name().endsWith("shulker_box")) return NOT_PICKED_UP;
        int held = -1;
        for (int i = 0; i < 36; i++) {
            if (!looksLikePlaced(inventoryItem(i))) continue;
            if (held != -1) return NOT_PICKED_UP;
            held = i;
        }
        if (held != -1) debug("No dropped shulker near {} and the bot holds one like it in slot {}, taking it as picked up.", fmt(placed), held + 1);
        return held != -1 ? held : NOT_PICKED_UP;
    }

    /**
     * Whether a held item could be the shulker we put down: the same colour, holding what the job put in or left in it.
     */
    private boolean looksLikePlaced(@Nullable Item item) {
        if (item == null || !item.isShulker()) return false;
        if (placedItem != null && !item.id.equals(placedItem.name())) return false;
        if (item.contents.isEmpty()) return false;
        CompactPlan plan = compact;
        if (plan != null) {
            if (item.contents.size() != plan.placedSize) return false;
            if (plan.bin != null) {
                for (Item inside : item.contents) if (!BinTracker.owns(plan.bin.lots, inside.indexKey(), inside.variant, inside.count)) return false;
                return true;
            }
            for (Item inside : item.contents) if (!plan.key.equals(inside.bulkKey())) return false;
            return true;
        }
        if (job == Job.Fill && fillId != null) {
            for (Item inside : item.contents) if (!fillId.equals(inside.bulkKey())) return false;
            return true;
        }
        if (job == Job.Clean && mainId != null) {
            for (Item inside : item.contents) if (!mainId.equals(inside.kindKey())) return false;
            return true;
        }
        return false;
    }

    // Baritone's pickup process is "active" while it looks for the item and gives up control the moment it finds none, so it
    // used to be asked again every tick (each ask logged by the pathfinder). It is asked at most every 3 seconds, never while
    // it or anything else has the pathfinder, and every other ask walks to the item's block instead, if the item is in sight
    private long pickupAskedAt = -1000;
    private int pickupAsks;

    private void collectPickup() {
        if (BARITONE.isActive() || BARITONE.getFollowProcess().isActive() || activity.ownPathing()) return;
        if (tickCount - pickupAskedAt < 60) return;
        pickupAskedAt = tickCount;
        pickupAsks++;
        // Each new request gets its own time to make headway
        collectProgressAt = tickCount;
        collectBest = Double.MAX_VALUE;

        Entity item = shulkerItemNearby();
        if (item != null && pickupAsks % 2 == 0) {
            rules(false, false);
            // The item's cell or one beside it, whichever can be reached: its own cell may be one the bot can't stand in
            GoalNear walkTo = new GoalNear(cell(item), 1);
            PathingRequestFuture future = BARITONE.pathTo(walkTo);
            activity.notePath(future, BARITONE.getCustomGoalProcess(), walkTo, tickCount);
            debug("Walking to the shulker on the ground at {}.", fmt(cell(item)));
            return;
        }
        requestPickup();
    }

    /**
     * The dropped shulker, if it can be seen near where it stood.
     */
    private @Nullable Entity shulkerItemNearby() {
        // Asked several times a tick while collecting: the entities are looked through once per tick, spot and colour
        BlockPos at = placed;
        ItemData item = placedItem;
        if (nearbyTick == tickCount && Objects.equals(nearbyAt, at) && nearbyItem == item) return nearbyFound;
        Entity found = findShulkerItemNearby();
        nearbyTick = tickCount;
        nearbyAt = at;
        nearbyItem = item;
        nearbyFound = found;
        return found;
    }

    private long nearbyTick = Long.MIN_VALUE;
    private @Nullable BlockPos nearbyAt;
    private @Nullable ItemData nearbyItem;
    private @Nullable Entity nearbyFound;

    private @Nullable Entity findShulkerItemNearby() {
        if (placed == null) return null;
        Entity best = null;
        double bestDistance = 12 * 12;
        for (Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity == null || entity.getEntityType() != EntityType.ITEM || entity.isRemoved()) continue;
            ItemStack stack = GroundItems.stackOf(entity);
            if (stack == null) continue;
            if (placedItem != null ? stack.getId() != placedItem.id() : !isShulkerStack(stack)) continue;
            double d = distSq(placed, cell(entity));
            if (d < bestDistance) {
                bestDistance = d;
                best = entity;
            }
        }
        return best;
    }

    /**
     * The shulker we broke is a shulker block again where it stood, and no dropped one of it is in sight: the break was
     * refused by the server.
     */
    private boolean stillStanding() {
        if (placed == null || !Containers.isLoaded(placed)) return false;
        if (!World.getBlock(placed.x(), placed.y(), placed.z()).name().endsWith("shulker_box")) return false;
        return shulkerItemNearby() == null;
    }

    private static boolean isShulkerStack(ItemStack stack) {
        Item item = Item.of(stack);
        return item != null && item.isShulker();
    }

    /**
     * For the log when the shulker couldn't be got back: what is on the ground near it, and what the bot has room for.
     */
    private String pickupDiagnosis() {
        Entity item = shulkerItemNearby();
        return (item == null ? "no dropped shulker is in sight" : "a dropped shulker lies at " + fmt(cell(item)) + ", the bot is at " + fmt(playerFeet()))
            + ", " + (freeInventorySlot() ? "there is room for it" : "the inventory has no free slot");
    }

    // Whether the job began with the bot inside the perimeter plus 2 blocks: then leaving that area on the way ends the job
    private boolean jobStartedInside;

    private void markJobStart() {
        jobStartedInside = stash != null && stash.hasArea() && stash.inArea(playerFeet(), 2);
    }

    /**
     * A walk that began inside the perimeter (and 2 blocks around it) has taken the bot out of it: the target is given up.
     * Ground trips and exploring check this themselves.
     * @return true if the job was ended
     */
    private boolean leftStashArea() {
        if (!jobStartedInside || stash == null || !stash.hasArea() || stash.inArea(playerFeet(), 2)) return false;

        if (job == Job.Label) {
            if (labelStep != LabelStep.Walk) return false;
            activity.releaseOurPathing();
            restoreRules();
            labelFailed("Skipping the " + blockName(target) + " at " + fmt(target.pos) + ": the way there leaves the stash area.");
            return true;
        }
        if (handle != null) {
            if ((handle != Handle.Return && handle != Handle.Collect) || placed == null) return false;
            activity.releaseOurPathing();
            restoreRules();
            abandonShulker("Skipping the shulker box at " + fmt(placed) + ": the way there leaves the stash area.", false);
            return true;
        }
        if (step == Step.Walking && target != null) {
            activity.releaseOurPathing();
            target.unreachable = true;
            warn("Skipping the {} at {}: the way there leaves the stash area.", blockName(target), fmt(target.pos));
            finishJob();
            return true;
        }
        return false;
    }

    private void endHandling() {
        unpackingNow = null;
        handle = null;
        placed = null;
        pickupWatch.forgetBreak();
        breakRetries = 0;
        brokenSince = -1;
        job = null;
        // Whatever shulker of ours is still standing stays in the record; a picked up or abandoned one clears it
        syncInFlight();
        if (stash.isDirty()) stash.save();
        endResume();
        wait = CONFIG.delayTicks;
    }

    /**
     * A shulker that could not be broken or picked up: said loudly, left where it is, and no more are put down until
     * the bot is started again. Sorting carries on.
     */
    private void abandonShulker(String why) {
        abandonShulker(why, true);
    }

    /**
     * Bot tick thread. A shulker that could not be finished or picked up. The cause may be gone by now (a player near it,
     * the server rolling a break back), so unless {@code mayRetry} is false, or this shulker was already gone back to
     * {@link ShulkerRecovery#MAX_RECOVERIES} times, it is not a stop: its record is kept and the bot goes back to it
     * (finishing it, or picking it up) once the area has been quiet for a while; orders wait meanwhile ("finishing a
     * shulker"). Only a hard stop sets {@code lost}, which ends by itself when the shulker is picked up or gone.
     */
    private void abandonShulker(String why, boolean mayRetry) {
        CompactPlan plan = compact;
        String what = plan != null ? plan.reference.name : fillId != null ? fillId : "items";
        int stacks = plan != null ? plan.placedSize : 0;
        String held = (stacks > 0 ? stacks + " stacks" : "some stacks") + " of " + what;
        BlockPos at = placed;
        if (handle == Handle.Collect) step("collecting: gave up on the " + lookingFor() + " near " + (at == null ? "?" : xyz(at)) + ".");

        // Nothing standing and nothing on the ground in sight, close by: it is gone (taken, or despawned), not waiting to be fetched
        boolean gone = at != null && handle == Handle.Collect && Containers.isLoaded(at)
            && !World.getBlock(at.x(), at.y(), at.z()).name().endsWith("shulker_box")
            && distSq(playerFeet(), at) <= 12 * 12 && shulkerItemNearby() == null;
        Stash.InFlight record = null;
        if (mayRetry && !gone && at != null && stash != null) {
            if (shulkerRetries.allow(at)) {
                // Saved as given up on: a resumed plan picks the shulker up and does not pack on
                if (compact != null) compact.aborting = true;
                record = buildInFlight();
            }
            else info("Shulker handling: the shulker at {} was gone back to {} times already, not again.", fmt(at), ShulkerRecovery.MAX_RECOVERIES);
        }

        if (resuming) info("Shulker handling: going back to the shulker at {} failed at step {}: {}", at == null ? "?" : fmt(at), handle, why);
        String text = why + " It holds " + held + ".";
        if (record != null) {
            alert("{} Going back to it once nobody is near for {} s.", text, ShulkerRecovery.QUIET_TICKS / 20);
            info("Shulker handling PAUSED (will retry, attempt {}/{}): {} Going back to the shulker at {} after {} s, and {} s with nobody within {} blocks.",
                shulkerRetries.count(at), ShulkerRecovery.MAX_RECOVERIES, why, fmt(at), ShulkerRecovery.BACKOFF_TICKS / 20, ShulkerRecovery.QUIET_TICKS / 20, (int) ShulkerRecovery.NEAR_BLOCKS);
            recordOrphan(at, what, stacks);
            pendingResume = record;
            stash.inFlight = record;
            stash.markDirty();
            stash.save();
            recoveryPending = true;
            recoveryGate.arm(tickCount);
        }
        else if (gone) {
            clearOrphan(at);
            alert("{} The shulker is no longer there (taken by someone, or gone).", text);
            info("Shulker handling PAUSED (3 min): the shulker at {} is gone, nothing to fetch. {}", fmt(at), why);
            pauseShulkers("a shulker of mine is gone from " + fmt(at));
        }
        else {
            error("{} No more shulkers are put down until it is picked up or gone, or sorting is restarted.", text);
            inGameAlertActivePlayer("<red>" + why);
            alertDm(text);
            if (at != null) {
                recordOrphan(at, what, stacks);
                lost.mark(at);
                info("Shulker handling PAUSED (stopped): {} Stays stopped until the shulker at {} is picked up or gone, or sorting is restarted.", why, fmt(at));
            }
            else pauseShulkers(why);
        }
        if (compact != null) {
            compact.aborting = true;
            // A pause that passes: the order's bin is tried again, not failed
            if (compact.bin != null && compact.bin.failure == null) {
                compact.bin.failure = record != null || gone || at == null ? com.porter.qwertestash.orders.OrderBlocker.SHULKER_PAUSED + ": " + why : why;
            }
        }
        compact = null;
        resuming = false;
        resumeVerify = false;
        endHandling();
    }

    /** The stop ended: the shulker it was for is picked up or gone. Bot tick thread. */
    private void lostEnded(String how) {
        info("Shulker handling RESUMED: {}.", how);
        com.porter.qwertestash.manager.ActivityFeed.INSTANCE.event("Stash Sorter", "info", "Shulker handling resumed: " + how + ".");
    }

    /** Once a second while resting: whether the area is quiet enough to go back now. */
    private boolean recoveryWake() {
        recoveryWakeAt = tickCount + 20;
        return recoveryReady();
    }

    /** Whether going back to the shulker kept after a problem may start now: the back-off is over and nobody is near. */
    private boolean recoveryReady() {
        Stash.InFlight record = pendingResume;
        if (record == null || record.placed == null) return true;
        BlockPos feet = playerFeet();
        GroundItems ground = groundItems();
        String near = ground.playerWithin(record.placed.x() + 0.5, record.placed.z() + 0.5, ShulkerRecovery.NEAR_BLOCKS);
        if (near == null) near = ground.playerWithin(feet.x() + 0.5, feet.z() + 0.5, ShulkerRecovery.NEAR_BLOCKS);
        boolean ready = recoveryGate.ready(tickCount, near != null);
        if (ready && recoveryGate.forced()) info("Shulker handling: {} was still within {} blocks after {} minutes of waiting, going back to the shulker anyway.", near, (int) ShulkerRecovery.NEAR_BLOCKS, ShulkerRecovery.MAX_WAIT_TICKS / 1200);
        else if (!ready && near != null && tickCount >= recoveryWaitLogAt) {
            recoveryWaitLogAt = tickCount + 20 * 60;
            info("Shulker handling: waiting for {} within {} blocks to go before going back to the shulker.", near, (int) ShulkerRecovery.NEAR_BLOCKS);
        }
        return ready;
    }

    private boolean freeInventorySlot() {
        Set<Integer> keep = keepSlots();
        for (int i = 0; i < 36; i++) if (!keep.contains(i) && inventoryItem(i) == null) return true;
        return false;
    }

    private void failHandling(String why) {
        warn("{} Stopping.", why);
        handle = null;
        breakRetries = 0;
        job = null;
        stop();
    }

    private static int invSlot(int index) {
        return index < 9 ? 36 + index : index;
    }

    // Consecutive jobs per container that clicked but ended before the result could be read, and containers that closed
    // on us this pass (said once)
    private final Map<Box, Integer> unconfirmedRuns = new HashMap<>();
    private final Set<Box> closedEarlyReported = new HashSet<>();
    // Jobs at each container since the backlog last reached a new low, the ones given up on for going back and forth, and
    // the ones already reported this pass
    private final Map<Box, Integer> boxVisits = new HashMap<>();
    private final Set<Box> circular = new HashSet<>();
    private final Set<Box> loopReported = new HashSet<>();
    // Jobs that opened a container and clicked nothing, with what the carry slots and the container held then (as read
    // with the window closed): not repeated until one of them changes, however often stalled is cleared meanwhile
    private final Map<String, Long> futile = new HashMap<>();
    private final Set<String> noOpReported = new HashSet<>();
    // Containers whose jobs were cut short by a sign changing: how often, said once, and the ones given up on until the
    // sign's text really differs
    private final Map<Box, Integer> signAbortRuns = new HashMap<>();
    private final Set<Box> signAbortReported = new HashSet<>();
    private final Set<Box> signHeld = new HashSet<>();
    // What each unsettled sign said when last looked at, and containers to look at once more shortly
    private final Map<Box, List<String>> signSeen = new HashMap<>();
    private final Map<Box, Long> signRecheck = new HashMap<>();

    private void clearStalled() {
        stalled.clear();
        stalled.addAll(signHeld);
    }

    private String futileKey(Box box, Job kind) {
        return box.pos + ":" + kind;
    }

    private long futileSig(Box box) {
        long h = 17;
        for (int slot : carry) {
            Item item = inventoryItem(slot);
            h = h * 31 + (item == null ? 0 : item.id.hashCode() * 131L + item.count);
        }
        if (box.known()) for (Item item : box.contents) h = h * 31 + (item == null ? 0 : item.id.hashCode() * 131L + item.count);
        return h;
    }

    /**
     * Not worth a trip for this kind of job: it is stalled, or the same trip came back empty-handed and neither the
     * carry slots nor the container have changed since.
     */
    private boolean blocked(Box box, Job kind) {
        if (stalled.contains(box) || signHeld.contains(box)) return true;
        Long was = futile.get(futileKey(box, kind));
        return was != null && was == futileSig(box);
    }

    private @Nullable String lastSummary;
    private int passJobs;
    private int passMin = Integer.MAX_VALUE;
    // Containers that could not be read when visited: not tried again until something changes
    private final Set<Box> failedSurvey = new HashSet<>();
    private final SurveyRetry<Box> surveyRetry = new SurveyRetry<>();

    private void failSurvey(Box box) {
        // Once per wait: a box already waiting is not counted again (finishJob calls this too)
        if (!failedSurvey.add(box)) return;
        long at = surveyRetry.failed(box, tickCount);
        debug(() -> "Read of the container at " + box.pos + " failed, tried again in " + (at - tickCount) / 20 + " s.");
    }

    private void finishSorting() {
        signAbortReported.clear();
        boxVisits.clear();
        circular.clear();
        loopReported.clear();
        closedEarlyReported.clear();
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        if (stash.isDirty()) stash.save();
        StashIndex.touch(stash);
        restoreRules();
        if (sorts()) reportStrays();
        if (!readOnly()) unrecognisedSigns(true);

        if (!sorts()) {
            long unopened = stash.boxes.stream().filter(b -> b.unreachable).count();
            String note = unopened > 0 ? String.format(" Couldn't open %d containers.", unopened) : "";
            if (readOnly()) {
                // Driven by the Stash Manager: done, the module switches itself off and nothing walks until the next rescan
                info("Done reading for the Stash Manager: {} containers read.{}", stash.knownCount(), note);
                step("done: read " + stash.knownCount() + " containers for the Stash Manager; stopping.");
                surveyed = 0;
                endDrive();
                return;
            }
            if (CONFIG.stayOn) {
                if (surveyed > 0 || !note.equals(lastSummary)) info("Scanned {} containers (scan only).{} Waiting for changes.", surveyed, note);
                if (surveyed > 0 || !note.equals(lastSummary)) step("idle: read " + stash.knownCount() + " containers; next: re-read a container when it changes.");
                lastSummary = note;
                surveyed = 0;
                idle = true;
                idleTicks = 0;
                seenVersion = Stashes.version;
                return;
            }
            info("Done, scanned {} containers (scan only).{}", surveyed, note);
            step("done: read " + stash.knownCount() + " containers; stopping.");
            inGameAlertActivePlayer("<green>Stash scanned: " + stash.knownCount() + " containers read.");
            surveyed = 0;
            stop();
            return;
        }

        long unreachable = stash.boxes.stream().filter(b -> b.unreachable).count();
        String extra = unreachable > 0 ? String.format(" Couldn't open %d containers, they were left alone.", unreachable) : "";
        final int packedNow = runPacked;
        if (runPacked > 0) extra += String.format(" Packed %d shulkers, freed %d slots.", runPacked, runFreed);
        runPacked = runFreed = 0;
        if (!stuckCarried.isEmpty()) extra += String.format(" %d stacks I am carrying have nowhere to go.", stuckCarried.size());
        long leftInDropOffs = stash.boxes.stream().filter(b -> b.dropOff && b.known() && !b.unreachable).mapToLong(b -> b.contents.length - b.free()).sum();
        if (leftInDropOffs > 0) extra += String.format(" %d stacks are still in drop-offs, there's no room left in the stash.", leftInDropOffs);

        if (CONFIG.stayOn) {
            // Waking up for a drop-off check that finds nothing says nothing; a changed summary does
            if (moved > 0 || !extra.equals(lastSummary)) info("Sorted, moved {} stacks.{} Waiting for more to sort.", moved, extra);
            if (moved > 0 || !extra.equals(lastSummary)) step("idle: sorted, moved " + moved + " stacks; next: " + wakesIt() + ".");
            lastSummary = extra;
            moved = 0;
            idle = true;
            idleTicks = 0;
            seenVersion = Stashes.version;
            return;
        }

        info("Done, moved {} stacks.{}", moved, extra);
        step("done: moved " + moved + " stacks, packed " + packedNow + " shulkers; stopping.");
        inGameAlertActivePlayer("<green>Stash sorted: moved " + moved + " stacks.");
        stop();
    }

    private boolean start(Job next, Box box) {
        // Compacting has its own limits: its trips are not what the circle watchdog counts, nor are an order's put-back trips
        if (next != Job.Gather && next != Job.Deliver && !(next == Job.Fetch && compact != null) && !(next == Job.Dump && putBackTrip)) {
            passJobs++;
            boxVisits.merge(box, 1, Integer::sum);
        }
        // Only the Dump it was set for is a put-back trip
        if (next != Job.Dump) putBackTrip = false;
        signAbort = false;
        if (next == Job.Survey) surveyed++;
        markJobStart();
        job = next;
        target = box;
        actions = 0;
        tries = 0;
        stepTicks = 0;
        walkAsked = false;
        walkFails = 0;
        walkBest = Double.MAX_VALUE;
        walkProgressAt = 0;
        goal = new GoalNear(box.pos, REACH_RANGE_SQ);
        step = Step.Walking;
        jobInterruptions = 0;
        resetJobState();
        jobStartMs = System.currentTimeMillis();
        chainCount = 0;
        jobRunTicks = 0;
        stepJob(next, box);
        debug(() -> "Job " + next + " at " + box.pos + " (" + blockName(box) + (box.labels.isEmpty() ? "" : ", sign " + box.labels) + "), "
            + String.format("%.1f", Math.sqrt(distSq(playerFeet(), nearestPart(box)))) + " blocks away; passJobs " + passJobs + ", passMin "
            + (passMin == Integer.MAX_VALUE ? "-" : String.valueOf(passMin)) + ", visits to it " + boxVisits.getOrDefault(box, 0) + ", carrying " + carriedSlots().size() + " stacks.");
        return true;
    }

    // When the job in hand began (wall clock), for its duration in the debug log
    private long jobStartMs;

    private void finishJob() {
        if (openContainerId() != 0) {
            // Never closes with something on the cursor, and never while a click is still going out
            Close result = closeSafely();
            if (result == Close.Stuck) return;
            if (result == Close.Wait) {
                finishing = true;
                if (++finishTicks > CLICK_TIMEOUT) {
                    error("Couldn't close the container, the inventory actions never finished. Stopping.");
                    stop();
                }
                return;
            }
        }
        finishing = false;
        finishTicks = 0;
        swapStep = 0;
        targetOpen = false;
        if (job == Job.Fetch && unpackFetchOrigin != null) {
            // A source shulker that could not be taken is not asked for again
            // Not for a container that was forgotten meanwhile (broken): its position means nothing now
            if (actions == 0 && stash.boxAt(unpackFetchOrigin) != null) badUnpack.add(unpackFetchOrigin);
            unpackFetchOrigin = null;
        }

        // Opened it and got nothing done: don't keep coming back while nothing else changes
        // Clicks that changed neither the carry slots nor the container count for nothing: they never went through
        // Clicks were made but the window went before the result could be read: an unconfirmed attempt, which is neither
        // progress nor proof of none. A box is stalled after two of them in a row
        boolean confirmed = endSigSet && startSigSet;
        boolean unconfirmed = actions > 0 && !confirmed;
        boolean progressed = actions > 0 && confirmed && startSig != endSig;
        final Job doneJob = job;
        final Box doneBox = target;
        if (doneBox != null && doneBox.dropOff) noteDropOffRead(doneBox, doneJob == Job.Survey);
        debug(() -> "Job " + doneJob + (doneBox == null ? "" : " at " + doneBox.pos) + " finished after " + (System.currentTimeMillis() - jobStartMs) + " ms: " + actions
            + " actions, " + (confirmed ? "confirmed" : "unconfirmed") + ", " + (progressed ? "progressed" : "no progress") + (signAbort ? ", ended by a pending sign" : "") + ".");
        if (actions == 0 && !signAbort && job != null && job != Job.Survey && target != null && !target.unreachable && !(putBackTrip && job == Job.Dump)) {
            futile.put(futileKey(target, job), futileSig(target));
            debug("{} at {} opened and clicked nothing: not repeated until the carry slots or that container change.", job, target.pos);
        }
        if (actions > 0 && target != null) signAbortRuns.remove(target);
        int visits = target == null ? 0 : boxVisits.getOrDefault(target, 0);
        if (visits >= 3 && job != Job.Survey && loopReported.add(target)) {
            debug("Container at {} has been the target of {} jobs without the backlog shrinking: last job {}, {} actions, {}, {}, {}, carrying {} stacks.",
                target.pos, visits, job, actions, confirmed ? "confirmed" : "unconfirmed", progressed ? "changed something" : "changed nothing",
                signAbort ? "ended by a pending sign" : "not a sign abort", carriedSlots().size());
        }
        // An order putting its own things back is never what stalls a container, unless it keeps putting nothing there
        boolean ownPutBack = putBackTrip && job == Job.Dump;
        putBackTrip = false;
        // A put-back that put something in clears the count for that container
        if (ownPutBack && actions > 0 && target != null) putBackNoOps.remove(target);
        if (ownPutBack && actions == 0 && target != null && putBackNoOps.merge(target, 1, Integer::sum) >= 2) {
            stalled.add(target);
            debug("Container at {} stalled: two put-back trips to it put nothing in.", target.pos);
            if (++putBackGiveUps >= 2) orderLeftoverTried = true;
        }
        // Going back and forth between containers while the backlog doesn't shrink: given up on, however each job looks
        boolean circle = !ownPutBack && visits >= 4 && job != Job.Survey && target != null && !signAbort;
        if (circle) {
            circular.add(target);
            stalled.add(target);
            debug("Container at {} stalled: {} jobs on it without the backlog shrinking (going in circles).", target.pos, visits);
        }
        else if (job == Job.Survey && target != null && (!target.known() || target.stale) && !signAbort) {
            failSurvey(target);
            debug("Container at {} could not be read, not tried again until something changes.", target.pos);
        }
        else if (job != null && job != Job.Survey && target != null && !target.unreachable && !ownPutBack) {
            if (unconfirmed && !signAbort) {
                if (unconfirmedRuns.merge(target, 1, Integer::sum) >= 2) {
                    stalled.add(target);
                    debug("Container at {} stalled: two jobs in a row clicked but ended before the result could be read.", target.pos);
                }
            }
            else if (!progressed && !signAbort) {
                stalled.add(target);
                debug("Container at {} stalled: the {} job changed nothing.", target.pos, job);
            }
            else if (progressed) {
                unconfirmedRuns.remove(target);
                clearStalled();
                stalled.addAll(circular);
                stuckCarried.clear();
            }
        }

        startSigSet = endSigSet = false;

        // The last container too: the progress line is also said when the read is complete (there is no next one to name)
        if (job == Job.Survey) {
            int total = (int) stash.boxes.stream().filter(b -> !b.excluded).count();
            int read = (int) stash.boxes.stream().filter(b -> !b.excluded && b.known() && !b.stale).count();
            if (total > 0 && read == total) stepProgress("read", read, total, () -> "reading containers: " + read + "/" + total + "; done.");
        }

        if (stash.isDirty()) stash.save();
        signAbort = false;
        job = null;
        target = null;
        wait = CONFIG.delayTicks;
    }
    // A read gives up on a window that doesn't open after about 4 seconds; other jobs wait longer
    private static final int SURVEY_OPEN_TIMEOUT = 20 * 4;

    private int openTimeout() {
        return job == Job.Survey ? SURVEY_OPEN_TIMEOUT : OPEN_TIMEOUT;
    }


    /**
     * Bot tick thread. Drops the containers that cannot belong to this stash (outside the perimeter, or far from all the others),
     * each said at INFO. The file is saved at once, so a bad entry from before is cleaned on the first start.
     */
    private void dropForeignBoxes() {
        if (stash == null) return;
        List<Box> foreign = stash.foreignBoxes();
        if (foreign.isEmpty()) return;
        // The file as it was is kept beside it before the save that drops them
        if (!stash.backUp()) {
            warn("{} never-read containers look foreign but the stash file could not be copied first, so none is dropped.", foreign.size());
            return;
        }
        for (Box box : foreign) {
            forgetBox(box);
            stash.boxes.remove(box);
            info("Dropped the never-read container at {}: it is {} (the stash file was copied to .prune-bak- first).", fmt(box.pos), stash.hasArea() ? "outside the stash area" : "over " + Stash.FOREIGN_BLOCKS + " blocks from every container read");
        }
        stash.markDirty();
        stash.save();
    }

    // Failed walk requests for the container in hand (no path), counted once each; a few in a row give the container up at once
    private boolean walkAsked;
    private int walkFails;
    private static final int WALK_FAIL_LIMIT = 4;
    // A read walks at most this long without getting a block nearer to the container
    private static final int SURVEY_NO_PROGRESS_TICKS = 20 * 30;
    private double walkBest;
    private int walkProgressAt;

    /**
     * Bot tick thread. Why the container in hand plainly can not be read, or null: its whole spot is loaded and none of it is a
     * container (air), or its chunk is not loaded and it is far off or the walk to it took too long.
     */
    private @Nullable String unreadableNow() {
        Box box = target;
        if (box == null || box.delivery || stash == null) return null;
        boolean anyLoaded = false, anyContainer = false;
        for (BlockPos part : box.parts) {
            if (!Containers.isLoaded(part)) continue;
            anyLoaded = true;
            if (Containers.isContainer(part, true)) anyContainer = true;
        }
        if (anyLoaded) return anyContainer ? null : "there is no container there (" + blockName(box) + ")";
        // Not loaded says nothing (only the view distance is): it is walked to, and the no-progress and no-path limits end a
        // walk that gets nowhere. Only a container that can not be part of the stash is skipped
        return stash.implausible(box) ? "its chunk is not loaded and it is nowhere near the stash" : null;
    }

    /**
     * Bot tick thread. A container that can not be read is skipped at once, with the reason at INFO. A read is marked stale and
     * tried again later (failedSurvey); other jobs give it up as unreachable.
     */
    private void skipUnreadable(String why) {
        Box box = target;
        if (box == null) return;
        activity.releaseOurPathing();
        info("Skipping the container at {}: {}.", fmt(box.pos), why);
        giveUpWalk();
    }

    /**
     * Bot tick thread. A walk to the container in hand got nowhere. A read is marked stale and tried again later (failedSurvey),
     * never marked unreachable for good; other jobs give the container up as before.
     */
    private void giveUpWalk() {
        activity.releaseOurPathing();
        if (dropIfGone()) return;
        if (job == Job.Survey && target != null) {
            target.stale = true;
            failSurvey(target);
            finishJob();
            return;
        }
        giveUp(false);
    }

    private void giveUp(boolean opening) {
        activity.releaseOurPathing();
        // A container a player broke is forgotten, not tried again (failedSurvey) or marked unreachable
        if (dropIfGone()) return;
        // A read that never opened goes to failedSurvey (marked stale, tried again within a minute while resting, or when a
        // container changes), never to unreachable. Other jobs, and walks, give the container up as before
        if (job == Job.Survey && opening && target != null) {
            final Box box = target;
            debug(() -> "Reading the container at " + box.pos + " failed (it never opened), marked stale to try again later.");
            target.stale = true;
            failSurvey(target);
            finishJob();
            return;
        }
        if (job == Job.Deliver) {
            boolean looked = orders.recheck;
            orders.recheck = false;
            // A chest put up above the delivery container, or one only being looked at, that can't be reached or opened is passed over
            // (marked full, looked at again later); the order goes on with the others
            if (looked || (target != null && target.deliveryExtra)) {
                debug("A delivery chest at {} couldn't be {}, passing it over.", target == null ? "?" : target.pos, opening ? "opened" : "reached");
                deliveryChestFull(target);
                finishJob();
                return;
            }
            // Only a delivery that failed makes the delivery container unusable (and it ends the order with this reason)
            orders.failDelivery(opening ? "couldn't open the delivery container" : "couldn't reach the delivery container");
            alert("Couldn't {} the delivery container, nothing more can be delivered.", opening ? "open" : "reach");
            finishJob();
            return;
        }
        target.unreachable = true;
        if (opening) reportCantOpen(target);
        else reportUnreachable(target);
        finishJob();
    }

    // Steps

    private void tickWalking() {
        if (arrivedAt(goal)) {
            activity.releaseOurPathing();
            step = Step.Opening;
            stepTicks = 0;
            return;
        }

        if (stepTicks > WALK_TIMEOUT) {
            if (job == Job.Survey) info("Skipping the container at {}: {} s of walking and not there.", fmt(target.pos), WALK_TIMEOUT / 20);
            giveUpWalk();
            return;
        }

        // Nothing there to read, or nowhere it can be walked to: skipped at once, not after a minute of pathing
        String unreadable = unreadableNow();
        if (unreadable != null) {
            skipUnreadable(unreadable);
            return;
        }
        // A read that gets no nearer for 30 s is given up on (the walk as a whole may take a minute)
        double d = Math.sqrt(distSq(playerFeet(), nearestPart(target)));
        if (d < walkBest - 1) {
            walkBest = d;
            walkProgressAt = stepTicks;
            walkFails = 0;
        }
        else if (job == Job.Survey && stepTicks - walkProgressAt > SURVEY_NO_PROGRESS_TICKS) {
            info("Skipping the container at {}: no nearer to it in {} s.", fmt(target.pos), SURVEY_NO_PROGRESS_TICKS / 20);
            giveUpWalk();
            return;
        }
        // The pathfinder says there is no way, request after request: no use asking again
        if (walkAsked && activity.ownFailureSettled()) {
            walkAsked = false;
            if (++walkFails >= WALK_FAIL_LIMIT) {
                info("Skipping the container at {}: the pathfinder found no way to it {} times.", fmt(target.pos), walkFails);
                giveUpWalk();
                return;
            }
        }

        // Anything else on Baritone would have had us give way by now, so this is only ever our own path
        if (activity.mayRequest()) {
            goal = requestWalk(nearestPart(target));
            walkAsked = true;
            wait = 10;
        }
    }

    private void tickOpening() {
        int id = openContainerId();
        Container c = id != 0 ? openContainer() : null;

        if (c != null) {
            String why = openRejection(c);
            if (why == null) {
                debug(() -> "Opened the " + blockName(target) + " at " + target.pos + " " + (containerOpenedAt() - openRequestedAt) + " ms after the click (try " + tries + ", " + stepTicks + " ticks).");
                step = Step.Working;
                boxFailures.remove(target);
                stepTicks = 0;
                targetOpen = true;
                Stashes.read(stash, target, c);
                noteStashRead(target);
                wait = openWait();
                return;
            }

            // Not the window our click opened: an ender chest, a plugin menu, one left over from before
            long openedAt = containerOpenedAt();
            if (openedAt != lastRejectedAt) {
                lastRejectedAt = openedAt;
                warn("Ignoring the window that is open (id {}, before the click: id {}) for the container at {}: {}.", id, openIdBefore, target.pos, why);
            }

            // One that isn't ours, someone else is using it: it's left open and we give way. One of ours from an
            // earlier job is closed, and the click made again, which the OPEN_TRIES below bound
            // A window that opened right after our own click is the answer to it, even if it is not the chest we meant (the size
            // changed, a menu): it is closed and the click made again, which the tries bound. Waiting for another module to close
            // it could last for ever
            if (!ownsWindow() && lastOpenClickAt != 0 && openedAt >= lastOpenClickAt && openedAt - lastOpenClickAt <= 5_000 && id != openIdBefore) {
                debug("Window {} opened {} ms after our click and was refused ({}): taking it as ours to close.", id, openedAt - lastOpenClickAt, why);
                activity.claimWindow(id, openedAt);
            }
            if (!ownsWindow()) {
                beginYield(Activity.Reason.Container);
                return;
            }
            if (closeSafely() == Close.Stuck) return;
            wait = CONFIG.delayTicks;
            if (stepTicks > openTimeout()) giveUp(true);
            return;
        }

        if (tries >= OPEN_TRIES || stepTicks > openTimeout()) {
            giveUp(true);
            return;
        }

        // The last click is still on its way to the container: asking again would start it over. Nor over anyone else's
        if (activity.ownPathing() || !activity.mayRequest()) return;

        // The container delay: not before this long after the last close; the tick is not counted against the open timeouts or tries
        if (openPaused()) {
            stepTicks--;
            return;
        }

        // Broken since it was saved: forgotten instead of clicked at for ever
        if (dropIfGone()) return;
        tries++;
        BlockPos pos = nearestPart(target);
        MODULE.get(StashTracker.class).clicked(pos);
        openIdBefore = id;
        openRequestedAt = lastOpenClickAt = System.currentTimeMillis();
        foreignSinceClick = false;
        requestOpen(pos);
        wait = CONFIG.settleTicks;
    }

    /**
     * Why an open window can't be the box's container, or null if it is: opened after our own click, a chest-sized
     * window of the size this box has. A box that has become a double chest (or been halved) since it was saved is
     * brought up to date when the window's size shows it.
     */
    private @Nullable String openRejection(Container c) {
        if (containerOpenedAt() < openRequestedAt) return "it was already open before the click that should have opened it";

        ContainerType type = c.getType();
        if (type != ContainerType.GENERIC_9X3 && type != ContainerType.GENERIC_9X6) return "it is a " + type + " window, not a chest or barrel";

        int slots = Stashes.slots(c);
        if (slots == target.parts.size() * 27) return null;
        if (refreshTargetParts() && slots == target.parts.size() * 27) return null;
        return "it has " + slots + " slots but the container is saved as " + target.parts.size() * 27;
    }

    /**
     * Re-reads which blocks make up the target container, when a chest next to it was placed or broken since it was
     * saved. Its contents are read again, and a saved box that is now just its other half is dropped, keeping its marks.
     * @return true if the parts changed
     */
    private boolean refreshTargetParts() {
        if (!Containers.isLoaded(target.pos)) return false;

        List<BlockPos> now = Containers.makeBox(target.pos).parts;
        if (now.equals(target.parts)) return false;

        if (now.size() != target.parts.size()) target.contents = null;
        target.parts = new ArrayList<>(now);
        Containers.readSigns(target);
        for (int i = stash.boxes.size() - 1; i >= 0; i--) {
            Box other = stash.boxes.get(i);
            if (other == target || !target.has(other.pos)) continue;
            carryMarks(other, target);
            stash.boxes.remove(i);
        }
        stash.markDirty();
        return true;
    }

    /**
     * What a box that is being dropped as a duplicate was marked as carries over to the box that stays.
     */
    private static void carryMarks(Box from, Box to) {
        to.excluded |= from.excluded;
        to.dropOff |= from.dropOff;
        to.outsideSkip |= from.outsideSkip;
    }

    private void tickWorking() {
        // A sign on this container changed: what it takes depends on it now, so don't carry on with the old idea
        if (CONFIG.watchSigns && target != null && signDirtyAt.containsKey(target)) {
            // Not the container's fault: it is looked at again once its sign has settled, so it is not stalled
            signAbort = true;
            signAborted.add(target);
            if (signAbortReported.add(target)) debug("Sign at {} reported changing, waiting for it to settle.", target.pos);
            // The same container aborted again and again without a click: held until its sign text really changes
            if (actions == 0 && signAbortRuns.merge(target, 1, Integer::sum) >= 2) {
                signHeld.add(target);
                debug("Container at {} held: its sign was reported changing {} times without a click, waiting for the text to differ.", target.pos, signAbortRuns.getOrDefault(target, 0));
                stalled.add(target);
            }
            finishJob();
            return;
        }

        Container c = openContainerId() != 0 ? openContainer() : null;
        if (c == null || !isTargetWindow(c)) {
            // Closed on us, or swapped for something else, by the server
            if (!signAbort && closedEarlyReported.add(target)) {
                warn("Container at {} closed before I finished with it ({}), the server may have closed it.", target.pos, c == null ? "window gone" : "another window opened");
            }
            finishJob();
            return;
        }

        // Wait for the last clicks to go through before deciding what's next
        // Only our own clicks count towards the timeout: another module's are given way to, not waited out here
        if (busy()) {
            // Our request should be done within a few clicks' time: when it isn't (the proxy threw on one of the clicks, and
            // logged it), it is given up on and the container left, not waited for or asked again
            if (ourBusy() && ++clickTicks > clickTimeout()) {
                warn("The inventory clicks at {} aren't completing, giving up on it for now.", fmt(target.pos));
                stalled.add(target);
                clickTicks = 0;
                finishJob();
            }
            return;
        }
        clickTicks = 0;

        // A stack left on the cursor goes away before anything else is clicked. A swap in progress has one there on purpose
        if (swapStep == 0 && holdingCursor()) {
            if (recoverBounded(c)) wait = CONFIG.delayTicks;
            else {
                endSig = stateSig();
                endSigSet = true;
                finishJob();
            }
            return;
        }

        Stashes.read(stash, target, c);
        noteStashRead(target);

        // Both ends of the comparison are read through the open window, before anything is clicked and once the last
        // click is through, so they come from the same source
        if (actions == 0 && !startSigSet) {
            startSig = stateSig();
            startSigSet = true;
        }

        clickRefused = false;
        boolean busy = switch (job) {
            case Survey, Clean, Fill, Label, Ground, Explore, Unpack, Name, Craft, Anvil, Chest, Repair, Enchant -> false;
            case FetchSigns -> fetchSignsOne(c);
            case Take -> takeOne(c);
            case Fetch -> fetchOne(c);
            case Compact -> compactOne(c);
            case Deposit -> depositOne(c);
            case Dump -> dumpOne(c);
            case Gather -> gatherOne(c);
            case Deliver -> deliverOne(c);
        };

        // A click that was refused (see bundleHazard) did nothing: asking for the same one again would never end
        if (clickRefused) {
            clickRefused = false;
            busy = false;
        }
        if (busy) {
            actions++;
            wait = CONFIG.delayTicks;
        }
        else {
            endSig = stateSig();
            endSigSet = true;
            if (actions == 0 && !signAbort && noOpReported.add(futileKey(target, job))) {
                String held = job == Job.Fetch && wantSlot >= 0 && wantSlot < target.contents.length
                    ? (target.contents[wantSlot] == null ? "empty" : target.contents[wantSlot].id) : "-";
                debug("{} at {} did nothing: {} free carry slots, {} carried, slot {} holds {}.",
                    job, target.pos, freeCarryCount(), carriedSlots().size(), wantSlot, held);
            }
            if (tryChain()) return;
            finishJob();
        }
    }

    // Ticks one container job has been running (not counting giving way), and the longest it may: every wait in it has a
    // bound of its own, this is the last one
    private int jobRunTicks;
    private static final int JOB_WATCHDOG_TICKS = 20 * 240;

    /**
     * A container job has run far longer than any of its steps may: it is ended, the container marked to be read again later
     * (a read) or stalled (a move), and the window closed.
     */
    private void watchdog() {
        final Job was = job;
        final Box box = target;
        final Step at = step;
        debug(() -> "Watchdog: " + was + " at " + box.pos + " has been running for " + jobRunTicks / 20 + " s (step " + at + "), ending it.");
        activity.releaseOurPathing();
        restoreRules();
        if (was == Job.Survey) {
            box.stale = true;
            failSurvey(box);
        }
        else stalled.add(box);
        jobRunTicks = 0;
        finishJob();
    }

    // How many jobs in a row the open window of one container has been used for
    private int chainCount;
    private static final int MAX_CHAIN = 4;

    /**
     * The job at the open container made headway and the same container has something else for us (something to take
     * out, or partial stacks to merge): the job is switched in place, without closing the window and opening it again.
     * Counted like a new job for the circle watchdog.
     * @return true if the job was switched
     */
    private boolean tryChain() {
        if (job != Job.Deposit && job != Job.Dump && job != Job.Take && job != Job.Compact) return false;
        if (target == null || signAbort || finishing || swapStep != 0 || holdingCursor() || chainCount >= MAX_CHAIN) return false;
        if (CONFIG.watchSigns && signDirtyAt.containsKey(target)) return false;
        boolean progressed = actions > 0 && endSigSet && startSigSet && startSig != endSig;
        if (!progressed || stalled.contains(target) || circular.contains(target)) return false;

        Job next = null;
        if (job != Job.Take && sorts() && freeCarrySlot() != -1 && takeableSlot(target) != -1) next = Job.Take;
        else if (job != Job.Compact && !target.dropOff && !compacted.contains(target) && mergeable(target) != null) next = Job.Compact;
        if (next == null) return false;

        // The finished job counts as a success, as finishJob would have counted it
        unconfirmedRuns.remove(target);
        clearStalled();
        stalled.addAll(circular);
        stuckCarried.clear();
        signAbortRuns.remove(target);

        final Job was = job;
        final Job chosen = next;
        final Box here = target;
        debug(() -> "Chaining on the open " + blockName(here) + " at " + here.pos + ": " + was + " done, " + chosen + " next.");
        if (next == Job.Compact) compacted.add(target);
        passJobs++;
        boxVisits.merge(target, 1, Integer::sum);
        chainCount++;
        job = next;
        actions = 0;
        startSigSet = endSigSet = false;
        jobStartMs = System.currentTimeMillis();
        wait = CONFIG.delayTicks;
        stepJob(next, target);
        return true;
    }

    // Moving items

    /**
     * Moves one stack that belongs somewhere else into a free carry slot.
     * @return false when there's nothing more to take, or nowhere to carry it
     */
    private boolean takeOne(Container c) {
        int into = freeCarrySlot();
        if (into == -1) return false;

        int slot = takeableSlot(target);
        if (slot == -1) return false;

        // A bundle is moved with a shift-click, the only move the proxy does right for one: it lands in the first free
        // slot, and is emptied once it is in a carry slot
        if (target.contents[slot].isBundle()) {
            noteBundleTaken();
            shiftClick(c, slot);
            return true;
        }

        // Straight into a carry slot, so it can never land in or top up one of the bot's own stacks
        clicks(c, slot, toWindow(into, c));
        stashHeld.put(into, target.contents[slot].id);
        return true;
    }

    /**
     * A slot in this box holding something that belongs elsewhere, where that somewhere still has room once what
     * we're already carrying is put away. -1 if there's none.
     */
    private int takeableSlot(Box source) {
        // What each destination can still take, so we don't pick up more than we can put away. The map is worked out once
        // per planning pass; each carried stack counts against the first place it would go other than this container
        Map<Box, Integer> base = takeRoomBase();
        Map<Box, Integer> claimed = new HashMap<>();
        for (List<Box> homes : carriedHomes()) {
            for (Box box : homes) {
                if (box != source && base.containsKey(box)) {
                    claimed.merge(box, 1, Integer::sum);
                    break;
                }
            }
        }

        // Drop-offs are emptied even when nowhere particular has room: whatever doesn't fit goes to a misc chest,
        // or any container with space
        int spare = source.dropOff ? spareRoom(source) : 0;

        for (int i = 0; i < source.contents.length; i++) {
            Item item = source.contents[i];
            if (item == null || !misplaced(item, source)) continue;
            // One free slot for the bundle and one to start emptying it into
            if (item.isFilledBundle()) {
                if (freeCarryCount() >= 2) return i;
                continue;
            }
            if (destinationsFor(item).stream().anyMatch(b -> b != source && base.containsKey(b) && base.get(b) - claimed.getOrDefault(b, 0) > 0)) return i;
            if (spare > 0) return i;
        }

        return -1;
    }

    // Memos for takeableSlot, only while a planning pass is choosing (when nothing they read can change): the room each
    // possible destination has before anything carried is counted, and the places each carried stack could go
    private @Nullable Map<Box, Integer> takeRoom;
    private @Nullable List<List<Box>> carriedHomesCache;

    private Map<Box, Integer> takeRoomBase() {
        Map<Box, Integer> cached = takeRoom;
        if (cached != null) return cached;

        Map<Box, Integer> room = new HashMap<>();
        for (Box box : stash.boxes) {
            // Only where a deposit could go: not a container being skipped or whose sign hasn't settled
            if (box.sortable() && !box.dropOff && !stalled.contains(box) && !signPending(box)) room.put(box, box.free() + misplacedCount(box));
        }
        if (destCache != null) takeRoom = room;
        return room;
    }

    private List<List<Box>> carriedHomes() {
        List<List<Box>> cached = carriedHomesCache;
        if (cached != null) return cached;

        List<List<Box>> homes = new ArrayList<>();
        for (int carried : looseCarried()) homes.add(destinationsFor(inventoryItem(carried)));
        if (destCache != null) carriedHomesCache = homes;
        return homes;
    }

    /**
     * Puts one carried stack that belongs here into this container. When it's full, swaps it with something in here
     * that belongs elsewhere instead, so full containers can still be sorted.
     * @return false when nothing more can be put here
     */
    private boolean depositOne(Container c) {
        if (swapStep > 0) return continueSwap(c);

        for (int slot : looseCarried()) {
            Item item = inventoryItem(slot);
            if (!destinationsFor(item).contains(target)) continue;

            if (canFit(item)) {
                shiftClick(c, toWindow(slot, c));
                moved++;
                return true;
            }

            // A swap goes through the cursor, which the proxy gets wrong for a bundle on either side
            if (item.isBundle()) continue;

            for (int i = 0; i < target.contents.length; i++) {
                Item here = target.contents[i];
                if (here == null || here.isBundle() || !misplaced(here, target) || here.sameAs(item)) continue;

                // Carried stack onto the cursor, swap it into the slot, then what was there into the carry slot, one click
                // at a time with the cursor checked in between
                swapCarry = toWindow(slot, c);
                swapSlot = i;
                swapStack = snapshot(item);
                swapDisplaced = snapshot(here);
                stashHeld.put(slot, here.id);
                swapStep = 1;
                clicks(c, swapCarry);
                moved++;
                return true;
            }
        }

        return false;
    }

    /**
     * Puts carried stacks down in a container with space, when nowhere they belong has room for them.
     */
    private boolean dumpOne(Container c) {
        for (int slot : looseCarried()) {
            // A source shulker the bin in hand has yet to be emptied of stays: it was fetched for that
            if (pendingSourceShulker(slot) || !canFit(inventoryItem(slot))) continue;
            shiftClick(c, toWindow(slot, c));
            moved++;
            return true;
        }
        return false;
    }

    private boolean canFit(Item item) {
        for (Item here : target.contents) {
            if (here == null) return true;
            if (here.sameAs(item) && here.count < here.maxStack) return true;
        }
        return false;
    }

    /**
     * Continues a swap: checks the cursor holds what the last click should have left on it, then makes the next click.
     * A mismatch means a click didn't go through (or the server changed things), so the swap is dropped and whatever is
     * on the cursor is put away.
     */
    private boolean continueSwap(Container c) {
        Item expected = swapStep == 1 ? swapStack : swapDisplaced;
        if (!sameStack(Item.of(mouseStack()), expected)) {
            swapStep = 0;
            return recoverBounded(c);
        }

        clicks(c, swapStep == 1 ? swapSlot : swapCarry);
        swapStep = swapStep == 1 ? 2 : 0;
        return true;
    }

    /**
     * A copy of a stack as it is now, for comparing against later.
     */
    private static @Nullable Item snapshot(@Nullable Item item) {
        return item == null || item.raw == null ? item : Item.of(item.raw.clone());
    }

    /**
     * The same item, data and count. Both being live stacks, their components compare as they are on the wire.
     */
    private static boolean sameStack(@Nullable Item a, @Nullable Item b) {
        if (a == null || b == null) return a == b;
        if (a.count != b.count || !a.id.equals(b.id)) return false;
        if (a.raw != null && b.raw != null) return Objects.equals(a.raw.getDataComponentsOrEmpty(), b.raw.getDataComponentsOrEmpty());
        return a.sameAs(b);
    }

    // Inventory requests

    /**
     * Submits the actions as one request, so nothing gets in between, and remembers it to know when it's finished.
     * The proxy only takes a request while none is running, so callers check {@link #busy()} first. Always at
     * {@link #SORTER_PRIORITY}: a refused request means another module's is ahead of it.
     */
    private RequestFuture submit(InventoryAction... actions) {
        // A left click with a bundle on the cursor onto an empty slot throws in the proxy (and is predicted wrongly where it
        // does not): never sent. Said now and then, and reported as done so nothing waits for it. The caller sees
        // clickRefused and ends what it was doing instead of asking again
        if (bundleHazard(actions)) {
            clickRefused = true;
            if (bundleWarned.add(tickCount / 600)) warn("Not putting a bundle down by clicking an empty slot (the proxy can't), leaving it where it is.");
            return RequestFuture.immediateFuture(true);
        }
        RequestFuture future = INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(actions).priority(SORTER_PRIORITY).build());
        activity.noteSubmit(future, false, tickCount);
        debug(() -> "Clicks " + (future == RequestFuture.rejected ? "refused" : "submitted") + ": " + java.util.Arrays.toString(actions) + ".");
        return future;
    }

    private boolean elsewhereSaid;
    private final Set<Long> bundleWarned = new HashSet<>();
    // A submit was refused by bundleHazard since the caller last looked
    private boolean clickRefused;

    /**
     * Whether these clicks would, one after the other, leave a bundle on the cursor and then click it onto an empty slot.
     * The proxy's ClickItem handles a bundle on the cursor as "insert the slot's stack into it": on an empty slot that is
     * a null dereference (and, where it isn't, a cursor predicted empty that isn't). Everything else with a bundle is
     * left to it: picking one up, right-clicking an empty slot to take a stack out of the bundle, shift-clicks.
     */
    private boolean bundleHazard(InventoryAction[] actions) {
        boolean onCursor = bundleOnCursor();
        for (InventoryAction action : actions) {
            if (!(action instanceof ClickItem click)) continue;
            ItemStack there;
            if (click.getContainerId() == 0) {
                List<ItemStack> inventory = CACHE.getPlayerCache().getPlayerInventory();
                there = click.getSlotId() >= 0 && click.getSlotId() < inventory.size() ? inventory.get(click.getSlotId()) : null;
            }
            else {
                Container open = openContainer();
                there = open == null ? null : open.getItemStack(click.getSlotId());
            }
            Item item = Item.of(there);
            if (onCursor && item == null && click.getClickItemAction() == ClickItemAction.LEFT_CLICK) return true;
            // A left click with nothing on the cursor picks the slot's stack up; with a bundle on it, the bundle stays
            if (!onCursor) onCursor = item != null && item.isBundle() && click.getClickItemAction() == ClickItemAction.LEFT_CLICK;
        }
        return false;
    }

    private boolean bundleOnCursor() {
        Item held = Item.of(mouseStack());
        return held != null && held.isBundle();
    }

    /**
     * Some inventory request is running, or one of ours was just submitted and hasn't started yet (the proxy doesn't
     * count it as running until its first action has gone out). Nothing is submitted while this is so.
     */
    private boolean busy() {
        return inventoryBusy() || ourBusy();
    }

    /**
     * A request of ours is still waiting or running.
     */
    /**
     * How long one request of ours may take, in ticks: a few clicks, each a configured delay apart.
     */
    private int clickTimeout() {
        return 40 + 8 * Math.max(0, CONFIG.delayTicks);
    }

    private boolean ourBusy() {
        return activity.ownInventoryLive();
    }

    @Override
    protected void shiftClick(Container c, int slot) {
        submit(new ShiftClick(c.getContainerId(), slot, ShiftClickItemAction.LEFT_CLICK));
    }

    @Override
    protected void leftClick(Container c, int slot) {
        submit(new ClickItem(c.getContainerId(), slot, ClickItemAction.LEFT_CLICK));
    }

    @Override
    protected void closeContainer() {
        noteClose();
        RequestFuture future = INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(new CloseContainer()).priority(SORTER_PRIORITY).build());
        activity.noteSubmit(future, true, tickCount);
    }

    /**
     * Left-clicks these window slots in order, as one request so nothing gets in between.
     */
    private void clicks(Container c, int... slots) {
        ClickItem[] actions = new ClickItem[slots.length];
        for (int i = 0; i < slots.length; i++) actions[i] = new ClickItem(c.getContainerId(), slots[i], ClickItemAction.LEFT_CLICK);
        submit(actions);
    }

    // Baritone requests. There is one Baritone for every module, and stopping it stops all of them, so we only ever
    // ask for things and remember what we asked for, to take back just that. They go straight to Baritone and not
    // through the pathToNear, open, place and breakAt helpers, which don't give the request back

    /**
     * Walks to within reach of a block.
     * @return the goal, to tell when we're there
     */
    private GoalNear requestWalk(BlockPos pos) {
        return requestWalk(pos, String.valueOf(pos));
    }

    /** @param label what the debug line calls the place (never the anvil's position) */
    private GoalNear requestWalk(BlockPos pos, String label) {
        rules(false, false);
        GoalNear near = new GoalNear(pos, REACH_RANGE_SQ);
        PathingRequestFuture future = BARITONE.pathTo(near);
        activity.notePath(future, BARITONE.getCustomGoalProcess(), near, tickCount);
        traceRequest("walk to within reach of " + label, future);
        return near;
    }

    // The container delay (ContainerPacing): when the last container of ours was closed, on the bot tick counter. Written by every close
    // we make, read before every open click
    private volatile long lastCloseTick = ContainerPacing.NEVER;

    private void noteClose() {
        lastCloseTick = tickCount;
    }

    /** Bot tick thread. The open click has to wait: the last close was less than the container delay ago. */
    private boolean openPaused() {
        return ContainerPacing.hold(CONFIG.containerDelay, tickCount, lastCloseTick);
    }

    /** Ticks to wait before the first click in a window that has just opened. */
    private int openWait() {
        return ContainerPacing.openWait(CONFIG.delayTicks, CONFIG.containerDelay);
    }

    private void requestOpen(BlockPos pos) {
        requestOpen(pos, String.valueOf(pos));
    }

    private void requestOpen(BlockPos pos, String label) {
        lastOpenClickPos = pos;
        rules(false, false);
        PathingRequestFuture future = BARITONE.rightClickBlock(pos.x(), pos.y(), pos.z());
        activity.notePath(future, BARITONE.getInteractWithProcess(), null, tickCount);
        traceRequest("open (right click) " + label, future);
    }

    private void requestPlace(BlockPos pos, ItemData item) {
        rules(false, true);
        PathingRequestFuture future = BARITONE.placeBlock(pos.x(), pos.y(), pos.z(), item);
        activity.notePath(future, BARITONE.getInteractWithProcess(), null, tickCount);
        traceRequest("place " + item.name() + " at " + pos, future);
    }

    private void requestBreak(BlockPos pos) {
        rules(true, false);
        PathingRequestFuture future = BARITONE.breakBlock(pos.x(), pos.y(), pos.z(), true);
        activity.notePath(future, BARITONE.getInteractWithProcess(), null, tickCount);
        traceRequest("break " + pos, future);
    }

    private void requestPickup() {
        rules(false, false);
        // Just the shulker, not whatever else lies around
        PathingRequestFuture future = placedItem != null ? BARITONE.pickup(placedItem) : BARITONE.pickup();
        activity.notePath(future, BARITONE.getFollowProcess(), null, tickCount);
        traceRequest("pick up " + (placedItem != null ? placedItem.name() : "dropped items"), future);
    }

    /**
     * Debug only: what was asked of the pathfinder, and how it ended and how long it took.
     */
    private void traceRequest(String what, PathingRequestFuture future) {
        if (quietLog() || !(debugLog() || debugLogWritten())) return;
        long began = System.currentTimeMillis();
        debug("Asked the pathfinder to {}.", what);
        try {
            future.addExecutedListener(done -> debug(() -> "Pathfinder request to " + what + " ended after " + (System.currentTimeMillis() - began)
                + " ms (accepted " + done.isAccepted() + ", completed " + done.isCompleted() + ")."));
        } catch (RuntimeException ignored) {
            // Only a log line
        }
    }

    // The cursor

    /**
     * Whether a stack is on the cursor.
     */
    private boolean holdingCursor() {
        return Item.of(mouseStack()) != null;
    }

    /**
     * recoverCursor, but gives up after a few tries in one job, so a click the server keeps rejecting can't loop forever.
     * Giving up counts as getting nothing done, so the box is skipped until something else moves.
     * @return false when it gave up, or there's nowhere to put the stack
     */
    private boolean recoverBounded(Container c) {
        if (!holdingCursor()) return true;
        if (cursorSettling()) return true;
        if (++recoveries > MAX_RECOVERIES) {
            actions = 0;
            return false;
        }
        return recoverCursor(c);
    }

    /**
     * Puts whatever is on the cursor away with one click. Nothing is clicked while a request is running.
     * @return false if there's nowhere to put it, or it can't be clicked right now
     */
    private boolean recoverCursor(Container c) {
        if (busy()) return false;

        int slot = recoverySlot(c);
        if (slot == -1) return false;

        leftClick(c, slot);
        settleUntil = tickCount + Math.max(CONFIG.delayTicks, 4);
        return true;
    }

    private boolean cursorSettling() {
        return tickCount < settleUntil;
    }

    /**
     * The window slot to put the cursor stack in, or -1. In order: an empty carry slot, any other empty slot of the bot's
     * that isn't a keep slot (both predicted exactly by the proxy), a carried stack of the same item with room (a merge,
     * which the proxy predicts as a swap), and only if the open window is the verified window of the container being
     * worked on, an empty slot in it.
     */
    private int recoverySlot(Container c) {
        Item held = Item.of(mouseStack());
        if (held == null || held.isBundle()) return -1;

        for (int slot : carry) {
            if (inventoryItem(slot) == null) return toWindow(slot, c);
        }

        Set<Integer> keep = keepSlots();
        for (int slot = 0; slot < 36; slot++) {
            if (!keep.contains(slot) && inventoryItem(slot) == null) return toWindow(slot, c);
        }

        for (int slot : carry) {
            Item there = inventoryItem(slot);
            if (there != null && there.sameAs(held) && there.count < there.maxStack) return toWindow(slot, c);
        }

        if (targetOpen && target != null && isTargetWindow(c)) {
            for (int i = 0; i < Stashes.slots(c); i++) {
                if (Item.of(c.getItemStack(i)) == null) return i;
            }
        }
        return -1;
    }

    private enum Close { Done, Wait, Stuck }

    /**
     * Closes the open container, but only when that can't lose anything: not while an inventory request is running, and
     * not with a stack on the cursor, which is put away first. Call it every tick until it says Done.
     * <ul>
     * <li>Done: nothing open, or the close was sent, or the window isn't ours: that's never closed</li>
     * <li>Wait: a click is going out, call again next tick</li>
     * <li>Stuck: the stack on the cursor has nowhere to go. Said so, the container closed anyway (the server puts the
     * stack in the inventory or drops it) and sorting stopped, so the caller must return without touching state</li>
     * </ul>
     */
    private Close closeSafely() {
        if (openContainerId() == 0 || !ownsWindow()) return Close.Done;
        if (busy()) return Close.Wait;
        // A crafting table of ours with something in its grid: the cursor and the grid are taken back out first. With no room
        // it is kept open (holdTable), never closed on them
        if (tableWindow() != null && craftGridHolds()) {
            int r = craftClearStep();
            if (r > 0) return Close.Wait;
            if (r < 0) {
                if (!tableStuck) {
                    markTableStuck();
                    tableStuckAlertAt = tickCount;
                    alert("Items are in the crafting table's grid and the inventory has no free slot for them: the crafting table is left open. Make room and the bot takes them back out.");
                }
                return Close.Stuck;
            }
        }

        Container c = openContainer();
        if (bundleOnCursor()) {
            // Not clicked anywhere: the server puts it back in the inventory when the window closes
            closeContainer();
            return Close.Done;
        }
        if (holdingCursor()) {
            if (cursorSettling()) return Close.Wait;
            boolean exhausted = ++closeTries > MAX_RECOVERIES;
            if (c != null && !exhausted && recoverCursor(c)) return Close.Wait;

            reportCursor(exhausted ? "it is still there after " + MAX_RECOVERIES + " attempts to put it away" : "no free slot", true);
            closeContainer();
            stop();
            return Close.Stuck;
        }

        closeTries = 0;
        closeContainer();
        return Close.Done;
    }

    /**
     * @param stopping sorting is stopping because of it. Giving way to another module doesn't stop anything
     */
    private void reportCursor(String reason, boolean stopping) {
        if (cursorReported) return;
        cursorReported = true;
        error("Couldn't put away the stack on the cursor, " + reason + ". " + (stopping ? "Stopping; the" : "The")
            + " server will put it back in the inventory or drop it when the container closes.");
    }

    // Closing for good as the module turns off. Nothing ticks the module after that, so a small scheduler retries
    // until the request is accepted, for a few seconds at most
    private static final ScheduledExecutorService DISABLE_CLOSER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "qwerte-stash-close");
        t.setDaemon(true);
        return t;
    });
    private static final int DISABLE_RETRY_MS = 100;
    private static final int DISABLE_RETRIES = 50;

    /**
     * @param id the window to close and when it opened: if it's no longer the one open by the time of a retry, it was
     * closed by someone and what's open now isn't ours to close
     */
    private void closeOnDisable(int id, long openedAt) {
        // Already on its way, from the stop that got us here
        if (activity.closingLive()) return;

        // Everything the retries need is read here, on the tick thread: they run on another one
        int slot = cursorSlotForClose(true);
        // A crafting table's grid is taken back into free slots in the same request; what has no room is said
        disableGridMoves = gridMovesForClose(slot);
        if (submitClose(id, slot) == RequestFuture.rejected) retryClose(DISABLE_RETRIES, id, openedAt, slot);
    }

    /**
     * The window slot the cursor stack goes to before closing, or -1 if there's none or nothing on the cursor.
     */
    private int cursorSlotForClose(boolean stopping) {
        if (!holdingCursor()) return -1;
        Container c = openContainer();
        int slot = c != null ? recoverySlot(c) : -1;
        if (slot == -1) reportCursor("no free slot", stopping);
        return slot;
    }

    /**
     * The cursor stack put away and the window closed in one request, at our own low priority.
     */
    // Clicks that take a crafting table's grid back out before it is closed as the module turns off
    private volatile List<InventoryAction> disableGridMoves = List.of();

    /** The grid of an open crafting table of ours, into free inventory slots (not the cursor's): two left clicks each. */
    private List<InventoryAction> gridMovesForClose(int cursorWindowSlot) {
        Container c = tableWindow();
        if (c == null) return List.of();
        List<InventoryAction> out = new ArrayList<>();
        Set<Integer> keep = keepSlots();
        List<Integer> free = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            if (keep.contains(i) || inventoryItem(i) != null || toWindow(i, c) == cursorWindowSlot) continue;
            free.add(toWindow(i, c));
        }
        int left = 0;
        for (int g = 1; g <= 9; g++) {
            if (Item.of(c.getItemStack(g)) == null) continue;
            if (free.isEmpty()) {
                left++;
                continue;
            }
            out.add(new ClickItem(c.getContainerId(), g, ClickItemAction.LEFT_CLICK));
            out.add(new ClickItem(c.getContainerId(), free.remove(0), ClickItemAction.LEFT_CLICK));
        }
        if (left > 0) warn("Stopping with {} stacks in the crafting table's grid and no free slot for them: the server puts them back in the inventory or drops them as it closes.", left);
        else if (!out.isEmpty()) info("Stopping: taking {} stacks back out of the crafting table's grid before closing it.", out.size() / 2);
        return out;
    }

    private RequestFuture submitClose(int id, int slot) {
        List<InventoryAction> request = new ArrayList<>();
        if (slot != -1) request.add(new ClickItem(id, slot, ClickItemAction.LEFT_CLICK));
        if (!isEnabled()) {
            for (InventoryAction a : disableGridMoves) if (a instanceof ClickItem click && click.getContainerId() == id) request.add(a);
        }
        request.add(new CloseContainer());
        noteClose();
        return INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(request).priority(SORTER_PRIORITY).build());
    }

    /**
     * One attempt: the cursor stack is put away and the window closed in one request, so nothing has to wait for the
     * click. At our own low priority.
     * @param stopping sorting is stopping, for what is said if the cursor stack has nowhere to go
     * @return the request, {@code RequestFuture.rejected} if the proxy refused because an inventory request was
     * running, or null if nothing is open
     */
    private @Nullable RequestFuture closeOnce(boolean stopping) {
        int id = openContainerId();
        if (id == 0) return null;

        return submitClose(id, cursorSlotForClose(stopping));
    }

    private void retryClose(int left, int id, long openedAt, int slot) {
        DISABLE_CLOSER.schedule(() -> {
            try {
                if (isEnabled() || openContainerId() != id || containerOpenedAt() != openedAt) return;
                if (submitClose(id, slot) != RequestFuture.rejected) info("Closed the open container after stopping.");
                else if (left > 1) retryClose(left - 1, id, openedAt, slot);
                else warn("Couldn't close the open container after stopping, inventory actions kept running.");
            } catch (RuntimeException e) {
                warn("Couldn't close the open container after stopping: {}", e.toString());
            }
        }, DISABLE_RETRY_MS, TimeUnit.MILLISECONDS);
    }

    // Which window is whose

    private static long containerOpenedAt() {
        return CACHE.getPlayerCache().getInventoryCache().getContainerOpenedAt();
    }

    /**
     * The open window is one we opened, so it's ours to read and to close. Another module's is neither: it's left
     * alone and waited for. A window becomes ours in {@link #claimWindow()} and stays ours until it closes.
     */
    private boolean ownsWindow() {
        int id = openContainerId();
        return id != 0 && activity.isOwnWindow(id, containerOpenedAt());
    }

    /**
     * Takes over a window that is the answer to our own click, once per tick before anything is decided. That is one
     * that opened after the click for the current target and passes its checks (type and size of the container, or a
     * shulker box when repacking), or, even if it fails them, one that opened within a few seconds of our last click on
     * a container while no other module has been seen doing anything: an ender chest or a menu we clicked by mistake,
     * or a late answer to a click of a job that has ended. Ours to close, never to read.
     */
    private void claimWindow() {
        int id = openContainerId();
        if (id == 0) return;

        long openedAt = containerOpenedAt();
        if (activity.isOwnWindow(id, openedAt)) return;

        Container c = openContainer();
        if (c == null) return;

        boolean ours = false;
        if (openedAt >= openRequestedAt) ours = handle != null ? isShulkerWindow(c) : job == Job.Name || job == Job.Enchant ? c.getType() == ContainerType.ANVIL : job == Job.Craft ? c.getType() == ContainerType.CRAFTING : target != null && openRejection(c) == null;
        if (!ours && lastOpenClickAt != 0 && openedAt >= lastOpenClickAt && openedAt - lastOpenClickAt <= OPEN_ANSWER_MS && !foreignSinceClick) ours = true;
        if (ours) activity.claimWindow(id, openedAt);
    }

    /**
     * The slots never touched: the configured ones, and the first hotbar slot when PearlPlus is given it, which is
     * where it wants its pearls.
     */
    private static Set<Integer> keepSlots() {
        Set<Integer> keep = parseSlots(CONFIG.keepSlots);
        if (CONFIG.reservePearlSlot) keep.add(PEARL_SLOT);
        return keep;
    }

    /**
     * The open window is a chest or barrel of the size the target container has.
     */
    private boolean isTargetWindow(Container c) {
        ContainerType type = c.getType();
        return (type == ContainerType.GENERIC_9X3 || type == ContainerType.GENERIC_9X6) && Stashes.slots(c) == target.parts.size() * 27;
    }

    private static boolean isShulkerWindow(Container c) {
        return c.getType() == ContainerType.SHULKER_BOX && Stashes.slots(c) == 27;
    }

    // Planning

    /**
     * Works out where everything belongs. Signs mode needs no planning, each item is matched on its own.
     */
    private void plan() {
        tierBoxes.clear();
        // The kit view is looked at again once per pass, whatever the cheap checks say
        kitState = null;
        kitRoutingSnap = kitRoutingLive();
        if (CONFIG.mode != Mode.Tiers) return;

        // Kit-reserved containers hold kits only, and kits that have a chest of their own are not part of the tiers
        KitRouting.View kitsNow = kitView();
        List<Box> ordered = stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && (kitsNow == null || !kitsNow.reserved(b))).sorted(Comparator.comparingInt(b -> b.order)).toList();

        // How many stacks of each tier there are, including drop-offs and what we're carrying
        int[] counts = new int[5];
        for (Box box : stash.boxes) {
            if (!box.sortable()) continue;
            for (Item item : box.contents) if (item != null && !kitRouted(item)) counts[tier(item)]++;
        }
        for (int slot : carriedSlots()) {
            Item carriedItem = inventoryItem(slot);
            if (carriedItem == null || !kitRouted(carriedItem)) counts[tier(carriedItem)]++;
        }

        // Fill the containers in order, a tier at a time. A container can end up shared by two tiers.
        int index = 0, used = 0;
        for (int t = 0; t < counts.length; t++) {
            List<Box> list = new ArrayList<>();
            int need = counts[t];

            while (need > 0 && index < ordered.size()) {
                Box box = ordered.get(index);
                if (!list.contains(box)) list.add(box);

                int take = Math.min(need, box.contents.length - used);
                need -= take;
                used += take;

                if (used >= box.contents.length) {
                    index++;
                    used = 0;
                }
            }

            // More of this tier than room: the overflow can sit in the last container
            if (list.isEmpty() && !ordered.isEmpty()) list.add(ordered.get(Math.min(index, ordered.size() - 1)));
            tierBoxes.put(t, list);
        }
    }

    /**
     * Where an item should go: the boxes it belongs in, all equally good. Empty if it doesn't belong anywhere in
     * particular, in which case it's left where it is. Never an excluded or drop-off container.
     */
    private List<Box> destinationsFor(@Nullable Item item) {
        if (item == null) return List.of();
        if (destCache == null) return computeDestinations(item);
        return destCache.computeIfAbsent(item, this::computeDestinations);
    }

    private List<Box> computeDestinations(Item item) {
        // A shulker that scores as a kit goes where the website says, before Tiers, signs and contents are looked at
        KitRouting.View kitRules = kitView();
        if (kitRules != null) {
            KitRouting.Route route = kitRules.route(item);
            if (route.decision().routed() && kitRoutingOn()) {
                if (route.kind() == KitRouting.Kind.MISSING) kitMissing(route.decision());
                else if (route.fallback()) kitFallback(route.decision());
                return kitRules.destinations(route);
            }
        }

        // A low item with no Mending goes to the needs-repair chest, after kit routing and before Tiers, signs and contents
        List<Box> needsRepair = repairDestinations(item);
        if (needsRepair != null) return needsRepair;

        if (CONFIG.mode == Mode.Tiers) return tierBoxes.getOrDefault(tier(item), List.of());

        // A shulker goes where what it's mostly full of goes: a shulker of totems belongs with the totems. Only when
        // no sign describes its contents does it fall back to signs about shulkers themselves, like "shulkers" or "kits".
        // Mixed shulkers go whole to a chest signed for kits, when there is one
        if (mixedShulker(item)) {
            List<Box> kits = keepWholeBoxes();
            if (!kits.isEmpty()) return kits;
        }

        Item inside = Categories.dominantContent(item);
        if (inside != null) {
            List<Box> byContents = bestSigned(inside);
            if (!byContents.isEmpty()) return byContents;
        }

        // An empty bundle: a chest signed for bundles (or containers), else the one signed for shulkers, else the catch-alls
        if (item.isBundle()) {
            List<Box> own = bestSigned(item);
            return own.isEmpty() ? bestSigned(EMPTY_SHULKER) : own;
        }

        return bestSigned(item);
    }

    /**
     * The containers whose signs describe this item best, all tied, or empty if no sign describes it.
     */
    private List<Box> bestSigned(Item item) {
        Map<SignKey, List<Box>> cache = signedCache;
        if (cache == null) return computeBestSigned(item);
        return cache.computeIfAbsent(signKey(item), key -> computeBestSigned(item));
    }

    // Within a planning pass the answer depends on the stack's kind only, not on which stack it is: equal id and data give
    // equal answers, so a thousand stacks of cobblestone are scored against the signs once
    private @Nullable Map<SignKey, List<Box>> signedCache;

    private record SignKey(String id, Object detail) {
    }

    private static SignKey signKey(Item item) {
        if (item.raw != null) return new SignKey(item.id, item.raw.getDataComponentsOrEmpty().toString());
        // A saved stack has no components: everything the signs are scored on is in its own fields
        return new SignKey(item.id, java.util.Arrays.asList(item.name, item.customName, item.customText, item.damaged, item.maxStack, item.food, item.music,
            item.block, item.fullBlock, item.tags, item.enchants, item.enchantLevels, item.potion, item.typeData, item.contents.size(), item.bundleStacks));
    }

    private List<Box> computeBestSigned(Item item) {
        int best = 0;
        List<Box> result = new ArrayList<>();
        Categories.Described described = Categories.describe(item);
        KitRouting.View kitsNow = kitView();
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff || (kitsNow != null && kitsNow.reserved(box))) continue;

            int score = Categories.boxScore(scoredLabels(box), described);

            if (score > best) {
                best = score;
                result.clear();
            }
            if (score == best && score > 0) result.add(box);
        }
        if (result.size() > 1) reportTie(item, result);
        return result;
    }

    // Stands in for an empty shulker box when looking for the chest signed for those
    private static final Item EMPTY_SHULKER = new Item();

    static {
        EMPTY_SHULKER.id = "shulker_box";
        EMPTY_SHULKER.name = "Shulker Box";
        EMPTY_SHULKER.count = 1;
        EMPTY_SHULKER.maxStack = 1;
    }

    // Item ids already reported as fitting two differently signed containers equally, so it is said once
    private final Set<String> tiesReported = new HashSet<>();
    private final Set<Box> strayReported = new HashSet<>();
    private final Set<Box> unrecognisedReported = new HashSet<>();
    private volatile int unrecognisedCount;
    // Carried stacks nothing has room for right now: slot to item id, left out of the plans until something changes
    private final Map<Integer, String> stuckCarried = new HashMap<>();
    private boolean stuckWarned;
    // The job in hand is being ended because the sign of its container changed, which is no reason to stall it
    private boolean signAbort;
    private final Set<Box> signAborted = new HashSet<>();

    // Signs: where one can be and whose it is, when something last happened at each container's, and since when it has
    // had none (or a blank one)
    private static final int SIGN_REMOVAL_GRACE = 20 * 60;
    private static final int SIGN_SWEEP_TICKS = 20 * 30;
    private final Map<BlockPos, List<Box>> signOwners = new HashMap<>();
    private boolean signsPublished;
    private final Map<Box, Long> signDirtyAt = new HashMap<>();
    private final Map<Box, Long> signGoneSince = new HashMap<>();
    private long nextSpotRefresh, nextSignSweep;

    /**
     * Two containers with different signs describe an item equally well: it goes to the nearest, as before. Several
     * containers with the same sign are no tie, that is just a category with more than one chest.
     */
    private void reportTie(Item item, List<Box> tied) {
        Set<String> first = new HashSet<>(tied.getFirst().labels);
        boolean different = false;
        for (Box other : tied) if (!new HashSet<>(other.labels).equals(first)) different = true;
        String key = item.id + "|" + tied.stream().map(b -> b.pos.toString()).sorted().collect(java.util.stream.Collectors.joining(";"));
        if (different && tiesReported.add(key)) {
            warn("{} fits {} containers with different signs equally well ({}), it goes to the nearest.", item.label(), tied.size(),
                String.join(" / ", tied.stream().map(b -> b.labels.isEmpty() ? "?" : b.labels.getFirst()).distinct().toList()));
        }
    }

    /**
     * Says once for each container whose sign is none of the things we know, and counts them. Nothing is ever put in
     * one: no item matches its sign, and it isn't a catch-all either.
     */
    private int unrecognisedSigns(boolean say) {
        int count = 0;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff || box.labels.isEmpty() || kitReserved(box)) continue;
            if (box.labels.stream().anyMatch(Categories::recognises)) continue;
            count++;
            if (say && unrecognisedReported.add(box)) {
                alert("I don't recognise the sign on the container at {} (\"{}\"), so nothing is sorted into it.", box.pos, String.join(" | ", box.labels));
            }
        }
        unrecognisedCount = count;
        return count;
    }

    /**
     * Anything in a container that its sign doesn't describe and that can't go anywhere better is left where it is,
     * and said once. Catch-all and kit containers hold what they hold.
     */
    private void reportStrays() {
        if (CONFIG.mode != Mode.Signs) return;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff || box.labels.isEmpty() || isMisc(box) || kitReserved(box)) continue;
            if (box.labels.stream().anyMatch(Categories::isKeepWhole) || box.labels.stream().noneMatch(Categories::recognises)) continue;

            // Shulkers go by what is in them, so this asks where each stack belongs rather than scoring its name
            int strays = 0;
            for (Item item : box.contents) {
                if (item == null) continue;
                List<Box> places = destinationsFor(item);
                if (places.contains(box)) continue;
                // One with somewhere to go that has room is moved, not left
                if (places.stream().anyMatch(b -> b.free() > 0 || misplacedCount(b) > 0)) continue;
                strays++;
            }
            if (strays > 0 && strayReported.add(box)) {
                warn("The container at {} signed \"{}\" holds {} stacks its sign doesn't describe, and there's nowhere better for them (or no room there), leaving them.",
                    box.pos, box.labels.getFirst(), strays);
            }
        }
    }

    /**
     * In the wrong place: everything in a drop-off that has somewhere to go, or anything in a container it doesn't
     * belong in.
     */
    private boolean misplaced(Item item, Box box) {
        // A bundle with something in it is taken out wherever it is, to be emptied
        if (item.isFilledBundle()) return true;
        // Nothing stays in a drop-off
        if (box.dropOff) return true;

        List<Box> places = destinationsFor(item);
        return !places.isEmpty() && !places.contains(box);
    }

    /**
     * Whether a carried stack has somewhere to go right now: a container it belongs in that has room, or one signed
     * misc, other or overflow that has.
     */
    private boolean canPlace(Item item) {
        for (Box box : destinationsFor(item)) {
            if (!stalled.contains(box) && !signPending(box) && (box.free() > 0 || misplacedCount(box) > 0)) return true;
        }
        return spareBox() != null;
    }

    /**
     * Where a stack with no room in the containers it belongs in can be put down: a container signed misc, other or
     * overflow first; failing that an unsigned one with space, which is a free container waiting for a sign.
     */
    private @Nullable Box spareBox() {
        Box misc = nearest(stash.boxes.stream().filter(b -> takesAnything(b, true)).toList());
        if (misc != null) return misc;

        // Unsigned: one already in use before an empty one, which a new sign could still be put on
        List<Box> unsigned = stash.boxes.stream().filter(b -> takesAnything(b, false)).toList();
        Box used = nearest(unsigned.stream().filter(b -> b.free() < b.contents.length).toList());
        return used != null ? used : nearest(unsigned);
    }

    private boolean takesAnything(Box b, boolean signedMisc) {
        if (!b.sortable() || b.dropOff || b.free() <= 0 || blocked(b, Job.Dump) || signPending(b) || kitReserved(b)) return false;
        if (signedMisc) return isMisc(b);

        // Only in signs mode, and only a container whose sign state has been read: no sign, not even a blank one
        if (!CONFIG.unsignedOverflow || CONFIG.mode != Mode.Signs || !b.labels.isEmpty() || b.autoLabel != null || b.labelFailed) return false;
        Containers.Signs signs = Containers.readSignsIfLoaded(b);
        return signs != null && signs.labels().isEmpty() && !signs.anyBlock();
    }

    /**
     * A container signed for anything: misc, other, overflow and the like.
     */
    private boolean isMisc(Box box) {
        return box.labels.stream().anyMatch(Categories::isCatchAll);
    }

    /**
     * Free slots in the containers that take anything (signed misc, other, overflow), outside drop-offs and
     * {@code except}, less what we're already carrying.
     */
    private int spareRoom(Box except) {
        int free = 0;
        for (Box box : stash.boxes) if (box != except && (takesAnything(box, true) || takesAnything(box, false))) free += box.free();
        return free - looseCarried().size();
    }

    /**
     * Two partial stacks of the same item in this container, {first, second}, or null if there are none to merge.
     */
    private static int @Nullable [] mergeable(Box box) {
        Item[] c = box.contents;
        for (int i = 0; i < c.length; i++) {
            Item a = c[i];
            if (a == null || a.maxStack <= 1 || a.count >= a.maxStack) continue;
            for (int j = c.length - 1; j > i; j--) {
                Item b = c[j];
                if (b != null && a.sameAs(b) && b.count < b.maxStack) return new int[] {i, j};
            }
        }
        return null;
    }

    /**
     * Merges one later partial stack into an earlier one: pick it up, drop it on the earlier stack, put back what's left.
     */
    private boolean compactOne(Container c) {
        int[] pair = mergeable(target);
        if (pair == null) return false;

        clicks(c, pair[1], pair[0], pair[1]);
        return true;
    }

    /**
     * Chests signed for kits, which take mixed shulkers whole.
     */
    private List<Box> keepWholeBoxes() {
        KitRouting.View kitsNow = kitView();
        return stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && (kitsNow == null || !kitsNow.reserved(b)) && b.labels.stream().anyMatch(Categories::isKeepWhole)).toList();
    }

    // Kit sorting (1.14.0, KIT-CONTRACT.md "What the bot does"). The settings come from the cache the Stash Manager reply fills
    // (KitStore, also while offline). The view is the settings against this stash: which box an id is and which boxes are kit-reserved.
    // It is rebuilt when the settings, the stash or its boxes change, and the checks to know that are plain int compares.
    // One immutable holder, read without a lock: the hit path is a volatile read and four compares.
    private record KitState(@Nullable Stash stash, int store, int version, int boxes, KitRouting.@Nullable View view) {
    }

    private volatile @Nullable KitState kitState;
    // Whether kit routing was allowed when the planning pass began; it does not change during the pass. Null outside a pass.
    private volatile @Nullable Boolean kitRoutingSnap;
    // A kit whose chest is missing or cannot take kits is said once per key and container until the settings change
    private final Set<String> kitWarned = new HashSet<>();
    private int kitWarnedStore = -1;

    /** The kit settings against the stash, or null when kit sorting is off or nothing is set. Tick thread. */
    private KitRouting.@Nullable View kitView() {
        Stash st = stash;
        if (!CONFIG.kitSorting || st == null) return null;
        int store = KitStore.shared().version(), version = Stashes.version, boxes = st.boxes.size();
        KitState s = kitState;
        if (s != null && s.stash() == st && s.store() == store && s.version() == version && s.boxes() == boxes) return s.view();
        return rebuildKitView(st, store, version, boxes);
    }

    private synchronized KitRouting.@Nullable View rebuildKitView(Stash st, int store, int version, int boxes) {
        // The warnings start again only when the settings changed; a view rebuilt every planning pass must not repeat them
        if (store != kitWarnedStore) {
            synchronized (kitWarned) {
                kitWarned.clear();
            }
            kitWarnedStore = store;
        }
        KitSettings settings = KitStore.shared().get(ManagerIndex.clamp(st.server, 256), ManagerIndex.clamp(st.name, 256));
        KitRouting.View view = settings == null ? null : new KitRouting.View(st, settings);
        kitState = new KitState(st, store, version, boxes, view);
        return view;
    }

    /** The kits chest or the chest of a kit rule: nothing but kit shulkers goes in, and it is out of overflow, sign scoring, the tiers, compacting, repacking and labelling. */
    private boolean kitReserved(Box box) {
        KitRouting.View v = kitView();
        // The needs-repair chest is reserved the same way: nothing but needs-repair items is sorted into it
        return (v != null && v.reserved(box)) || repairReserved(box);
    }

    /**
     * Whether kit routing may apply to what is in hand: never while an order, a repair or a put-back holds the carried stacks (those go
     * where the order, the repair or the put-back says, whatever their names). Planning passes run with none of them going.
     */
    private boolean kitRoutingOn() {
        Boolean snap = kitRoutingSnap;
        return snap != null ? snap : kitRoutingLive();
    }

    /** Nothing that carries stacks for its own purpose is going: no order, repair (and its trip), craft, naming, chest or put-back. */
    private boolean kitRoutingLive() {
        return repair == null && repairTrip == null && craft == null && anvilRun == null && naming == null && !putBackTrip && !orders.engaged();
    }

    /** The chest a kit shulker is routed to (a rule's or the kits chest), whatever is going on, or null. */
    private @Nullable Box kitHome(@Nullable Item item) {
        KitRouting.View v = kitView();
        if (v == null || item == null) return null;
        KitRouting.Route r = v.route(item);
        return v.destinations(r).isEmpty() ? null : r.box();
    }

    /** The containers of a kit's chain (its rule's, its template's, its category's, the general kits chest) that resolve; empty for anything else. */
    private List<Box> kitChain(@Nullable Item item) {
        KitRouting.View v = kitView();
        return v == null || item == null ? List.of() : v.chainBoxes(item);
    }

    /** A put-back may go to a kit-reserved chest only when every stack going there is a kit routed to it. */
    private boolean originAllowed(Box from, List<Integer> slots) {
        if (!kitReserved(from)) return true;
        if (slots.isEmpty()) return false;
        for (int slot : slots) {
            Item it = inventoryItem(slot);
            if (kitChain(it).contains(from)) continue;
            // The needs-repair chest takes back the needs-repair items routed to it
            List<Box> routed = it == null ? null : repairDestinations(it);
            if (routed != null && routed.contains(from)) continue;
            return false;
        }
        return true;
    }

    /** Kit sorting is on and a rule or the kits chest applies to this kit (found or missing): it is never unpacked or repacked. */
    private boolean kitWhole(Item shulker) {
        return CONFIG.kitSorting && KitRouting.keptWhole(kitView(), shulker);
    }

    /** A kit shulker that has a say in where it goes (a chest, or none to take it). */
    private boolean kitRouted(Item item) {
        KitRouting.View v = kitView();
        return v != null && v.route(item).decision().routed() && kitRoutingOn();
    }

    /** A kit goes to a lower level of its chain (its own chest or its category's is full or missing): said once per kit and level until the settings change. */
    private void kitFallback(KitRouting.Decision d) {
        boolean first;
        synchronized (kitWarned) {
            first = kitWarned.add("fallback\t" + (d.key() == null ? "\u0000" : d.key()) + "\t" + d.kind());
        }
        if (first) info("A kit goes to {} because the chests before it are full or missing.", d.kind() == KitRouting.Kind.KITS_CHEST ? "the general kits chest" : "its category's chest");
    }

    private void kitMissing(KitRouting.Decision d) {
        boolean first;
        synchronized (kitWarned) {
            // A null key (unnamed) is a sentinel no name can be, so an unnamed kit and a kit named "null" are told apart
            first = kitWarned.add((d.key() == null ? "\u0000" : d.key()) + "\t" + d.container());
        }
        String what = d.key() == null ? "an unnamed kit" : "the kit \"" + d.key() + "\"";
        if (first) {
            warn("The chest picked for {} ({}) is missing or can't take kits (not read yet, excluded, a drop-off or unreachable). Such a kit stays where it is, and one from a drop-off goes to overflow.", what, d.container());
        }
        // The container id is a position, like the positions the other warnings give; the proxy log is not streamer-facing
    }

    /** The shulker boxes in the bot's inventory (main inventory and hotbar), for the kit report. Tick thread. */
    public List<Item> inventoryShulkers() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        List<Item> out = new ArrayList<>();
        for (int window = 9; window <= 44 && window < inv.size(); window++) {
            ItemStack stack = inv.get(window);
            // Only a shulker box is read into an Item: this runs every few seconds
            if (stack == null || stack.getAmount() <= 0) continue;
            ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
            if (data == null || !data.name().endsWith("shulker_box")) continue;
            Item item = Item.of(stack);
            if (item != null && item.isShulker()) out.add(item);
        }
        return out;
    }

    /**
     * A shulker holding more than one kind of item.
     */
    private static boolean mixedShulker(@Nullable Item item) {
        if (item == null || !item.isShulker()) return false;
        return item.contents.stream().map(i -> i.id).distinct().count() > 1;
    }

    private int misplacedCount(Box box) {
        if (misplacedCache == null) return countMisplaced(box);
        Integer cached = misplacedCache.get(box);
        if (cached != null) return cached;
        int count = countMisplaced(box);
        misplacedCache.put(box, count);
        return count;
    }

    private int countMisplaced(Box box) {
        int count = 0;
        for (Item item : box.contents) if (item != null && misplaced(item, box)) count++;
        return count;
    }

    /**
     * 0 filled shulkers, 1 empty shulkers, 2 stacks to 64, 3 stacks to 16, 4 everything else.
     */
    private static int tier(@Nullable Item item) {
        if (item == null) return 4;
        if (item.isShulker()) return item.contents.isEmpty() ? 1 : 0;
        if (item.maxStack == 64) return 2;
        if (item.maxStack == 16) return 3;
        return 4;
    }

    // Containers

    /**
     * Adds containers around the bot that aren't in the stash yet, and drops ones that have been broken.
     * @return how many were added
     */
    private int addNearbyBoxes() {
        // A perimeter in another dimension is left alone
        if (stash.areaSet() && !stash.hasArea()) return 0;
        BlockPos center = playerFeet();
        int r = CONFIG.radius, h = CONFIG.height;
        int added = 0;

        if (stash.boxes.removeIf(box -> Containers.isLoaded(box.pos) && !Containers.isContainer(box.pos, true))) stash.markDirty();

        // A chest next to a saved one may have made it a double (or a double been halved): bring the parts up to date
        // and forget one that is now just the other half of an earlier box, keeping its marks. Changed boxes get read again.
        for (Box box : stash.boxes) {
            if (!Containers.isLoaded(box.pos)) continue;
            List<BlockPos> now = Containers.makeBox(box.pos).parts;
            if (now.equals(box.parts)) continue;

            boolean resized = now.size() != box.parts.size();
            box.parts = new ArrayList<>(now);
            if (resized) box.contents = null;
            stash.markDirty();
        }
        for (int i = stash.boxes.size() - 1; i > 0; i--) {
            for (int j = 0; j < i; j++) {
                if (stash.boxes.get(j).has(stash.boxes.get(i).pos)) {
                    carryMarks(stash.boxes.get(i), stash.boxes.get(j));
                    stash.boxes.remove(i);
                    stash.markDirty();
                    break;
                }
            }
        }

        // With a perimeter, exploring it finds the containers, and only those inside stay
        if (stash.hasArea()) {
            if (stash.dropOutsideArea() > 0) stash.markDirty();
            return 0;
        }
        stash.skipOutsideArea();

        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -h; y <= h; y++) {
                    BlockPos p = center.add(x, y, z);
                    if (!Containers.isContainer(p, CONFIG.barrels) || stash.boxAt(p) != null) continue;
                    stash.boxes.add(Containers.makeBox(p));
                    added++;
                }
            }
        }

        if (added > 0) stash.markDirty();
        return added;
    }

    private void orderBoxes() {
        // Tiers fill the nearest containers first
        BlockPos start = playerFeet();
        List<Box> sorted = new ArrayList<>(stash.boxes);
        sorted.sort(Comparator.<Box>comparingDouble(b -> distSq(start, b.pos))
            .thenComparingInt(b -> b.pos.y()).thenComparingInt(b -> b.pos.x()).thenComparingInt(b -> b.pos.z()));
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).order = i;
    }

    // Inventory

    /**
     * What's in inventory slot {@code index} (0-8 hotbar, 9-35 main). Read through the open window while a container
     * is open, because the inventory cache is stale then.
     */
    private @Nullable Item inventoryItem(int index) {
        ItemStack stack;
        Container c = openContainerId() != 0 ? openContainer() : null;
        if (c != null && Stashes.slots(c) > 0) stack = c.getItemStack(toWindow(index, c));
        else stack = CACHE.getPlayerCache().getPlayerInventory().get(index < 9 ? 36 + index : index);
        return Item.of(stack);
    }

    /**
     * Carried stacks that something could take right now, not the ones already found to have nowhere to go.
     */
    private List<Integer> activeCarried() {
        List<Integer> slots = new ArrayList<>();
        Set<Integer> setAside = reservedSlots();
        for (int slot : carriedSlots()) {
            if (setAside.contains(slot)) continue;
            Item item = inventoryItem(slot);
            // Signs kept for labelling are not put away
            if (item != null && reservedSigns.contains(slot) && Containers.isSignItem(item.id)) continue;
            // A bundle with something in it is emptied first (Job.Unpack), not put away as it is
            if (item != null && item.isFilledBundle()) continue;
            if (item != null && !item.id.equals(stuckCarried.get(slot))) slots.add(slot);
        }
        return slots;
    }

    /**
     * Inventory slots holding stash items we're carrying.
     */
    private List<Integer> carriedSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot : carry) {
            Item item = inventoryItem(slot);
            if (item == null) continue;
            // Something a player dropped that is about to be given back is neither the stash's nor the bot's own
            if (item.raw != null && autoDrop().isReturning(item.raw)) continue;
            // Gear, food, totems and pearls we didn't put there ourselves are the bot's own
            if (ownKind(item) && !item.id.equals(stashHeld.get(slot))) continue;
            slots.add(slot);
        }
        return slots;
    }

    /**
     * Between jobs, with no click outstanding: a slot we filled that is now empty or holds something else is no longer ours.
     */
    private void pruneHeld() {
        if (busy() || openContainerId() != 0) return;
        stashHeld.entrySet().removeIf(entry -> {
            Item item = inventoryItem(entry.getKey());
            return item == null || !item.id.equals(entry.getValue());
        });
    }

    /**
     * What the bot keeps for itself: tools, weapons, armor, food, totems and ender pearls.
     */
    private static boolean ownKind(Item item) {
        String id = item.id;
        if (item.food || id.equals("totem_of_undying") || id.equals("ender_pearl")) return true;
        for (String suffix : List.of("_sword", "_axe", "_pickaxe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings", "_boots")) {
            if (id.endsWith(suffix)) return true;
        }
        return switch (id) {
            case "bow", "crossbow", "trident", "shield", "fishing_rod", "shears", "elytra", "mace", "flint_and_steel" -> true;
            default -> false;
        };
    }

    private int freeCarrySlot() {
        for (int slot : carry) if (inventoryItem(slot) == null) return slot;
        return -1;
    }

    /**
     * Where inventory slot {@code index} sits in an open container's window: the main inventory follows the
     * container's slots, then the hotbar.
     */
    private static int toWindow(int index, Container c) {
        int size = Stashes.slots(c);
        return index < 9 ? size + 27 + index : size + index - 9;
    }

    // Distances

    private BlockPos nearestPart(Box box) {
        BlockPos feet = playerFeet();
        return box.parts.stream().min(Comparator.comparingDouble(p -> distSq(feet, p))).orElse(box.pos);
    }

    private @Nullable Box nearest(List<Box> list) {
        BlockPos feet = playerFeet();
        return list.stream().min(Comparator.comparingDouble(b -> distSq(feet, nearestPart(b)))).orElse(null);
    }

    private static double distSq(BlockPos a, BlockPos b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    // Status

    /**
     * ", N unrecognised signs" when containers have signs we don't understand. The status is asked for from the
     * command thread, so it only reads the count the sorter worked out on its own thread.
     */
    private String unrecognisedNote() {
        int count = unrecognisedCount;
        return CONFIG.mode != Mode.Signs || count == 0 ? "" : ", " + count + " unrecognised sign" + (count == 1 ? "" : "s");
    }

    /** For the dashboard: the last step line ("what now, what next"), or null. */
    public @Nullable String activityDetail() {
        String step = lastStep;
        String text = step == null || step.isBlank() ? null : step.startsWith("Stash: ") ? step.substring(7) : step;
        // The website blurs the detail, not the title: positions of the job in hand are here
        Box at = target;
        if (job != null && at != null && !(text != null && text.contains(String.valueOf(at.pos.x())))) {
            String where = "at " + at.pos.x() + " " + at.pos.y() + " " + at.pos.z();
            return text == null ? where : text + " (" + where + ")";
        }
        return text;
    }

    /** For the dashboard: containers read of those to read, while some are waiting to be read; null when none are. */
    public int @Nullable [] readProgress() {
        Stash current = stash;
        if (current == null) return null;
        int total = 0, read = 0;
        for (Box box : current.boxes) {
            if (box.excluded) continue;
            total++;
            if (box.known() && !box.stale) read++;
        }
        return total == 0 || read == total ? null : new int[] {read, total};
    }

    public String statusLine() {
        return statusLine(true);
    }

    /** The status for the dashboard's card title: the same, with no positions (they are in {@link #activityDetail}). */
    public String statusLineNoPositions() {
        return statusLine(false);
    }

    private String statusLine(boolean positions) {
        if (!isEnabled()) return "off";
        if (stash == null) return "waiting for the bot to be in game";
        if (readOnly()) return "reading containers for the Stash Manager (website rescan)";
        if (gate != Gate.Running) {
            int quiet = quietTicks();
            long left = foreignNow ? quiet : Math.max(0, quiet - (tickCount - lastForeignTick));
            return "waiting: " + waitLabel(foreignNow || !quietElapsed() ? lastReason : null) + ", " + (left + 19) / 20 + "s quiet left";
        }
        return sortStatus(positions) + scanNote() + ", ground items: " + groundState();
    }

    /** A container for a status line: by position, or (for the dashboard's titles) just "a container". */
    private static String at(boolean positions, @Nullable Box box) {
        return positions && box != null ? String.valueOf(box.pos) : "a container";
    }

    private String scanNote() {
        return sorts() ? "" : " (scan only)";
    }

    private String sortStatus(boolean positions) {
        if (idle) return (sorts() ? "sorted, waiting for more (stay-on)" : "scanned, waiting for changes (stay-on)") + unrecognisedNote();
        if (job == null) return (sorts() ? "planning" : "scanning") + unrecognisedNote();
        return switch (job) {
            case Survey -> "reading " + at(positions, target);
            case Take -> "taking from " + at(positions, target);
            case Deposit -> "putting away into " + at(positions, target);
            case Dump -> orders.flow.holdsOrder() || !orderSources.isEmpty() ? "putting leftovers from an order back" : "putting down extras in " + at(positions, target);
            case Fetch -> orders.flow.holdsOrder() ? "fetching a shulker for an order" : "fetching a shulker from " + at(positions, target);
            case Clean -> "cleaning out a mixed shulker";
            case Fill -> compact != null && compact.bin != null ? "filling an order shulker" : compact != null ? "packing " + compact.reference.name + " into a shulker" : "packing " + fillId + " into a bulk shulker";
            case Compact -> "merging stacks in " + at(positions, target);
            case FetchSigns -> "fetching signs from " + at(positions, target);
            case Label -> "labelling " + (positions ? "the chest at " + target.pos : "a chest") + " as \"" + labelText + "\"";
            case Gather -> craft != null && compact == null ? "gathering what a shulker box is crafted from" : compact != null && compact.bin != null ? "gathering stacks for an order" : "gathering stacks to compact from " + at(positions, target);
            case Unpack -> "emptying a bundle";
            case Deliver -> "delivering an order shulker";
            case Name -> "naming an order shulker at the anvil";
            case Craft -> "crafting a shulker box at the crafting table";
            case Anvil -> "putting an anvil down to name order shulkers at";
            case Chest -> "putting another chest up for the delivery storage";
            case Repair -> "repairing order items with experience bottles";
            case Enchant -> "enchanting an order item at the anvil";
            case Explore -> "exploring the stash area (" + exploredChunks.size() + " chunks seen, " + unreachableChunks.size() + " out of reach)";
            case Ground -> "picking up " + groundLabel + " from the ground" + (positions ? " at " + (groundCell == null ? "?" : fmt(groundCell)) : "");
        } + " (" + moved + " moved)" + unrecognisedNote();
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Stash orders (the website's order page). The decisions are OrderFlow's (plain Java, tested without a world); this runs
    // them with the sorter's own jobs: a bin is built with the compaction machinery (an order's shulker is a CompactPlan
    // with a Bin), shulkers are fetched with Job.Fetch, and finished ones are put in the delivery container with
    // Job.Deliver. Orders come before sorting, and the sorter is the one owner of the bot's actions: nothing else runs at
    // the same time. Loose stacks are taken first; what they can't give is taken out of shulkers in the stash (a source shulker
    // is fetched, placed, emptied of what the order needs, picked up and put back where it came from at once).

    private static final String ORDER_KEY = "order:";
    private static final long BIN_STALL_MS = 10 * 60_000L;

    private final OrderGlue orders = new OrderGlue();
    // The bin being built, from when it is started until its plan is over
    private @Nullable Bin activeBin;
    // Carry slots holding what an order took, and the container each stack came from: what an order leaves over goes back
    private record OrderSource(String itemId, BlockPos pos) {
    }

    private final Map<Integer, OrderSource> orderSources = new HashMap<>();

    /** A shulker taken from the stash to be emptied of what the order needs, and where it came from: it goes back there. */
    private static final class SourceShulker {
        final BlockPos origin;
        /** A kit that could not be completed: every stack comes out (not only what a bin needs), and the shulker goes to a container with room. */
        boolean emptyAll;
        boolean opened;
        int tries;

        SourceShulker(BlockPos origin) {
            this.origin = origin;
        }
    }

    // Carry slot to the shulker held as a source; the one being worked on in the world now; the container a fetch is for; sources
    // that gave nothing (not asked again until the next start)
    private final Map<Integer, SourceShulker> sourceShulkers = new HashMap<>();
    private @Nullable SourceShulker unpackingNow;
    private @Nullable BlockPos unpackFetchOrigin;
    private final Set<BlockPos> badUnpack = new HashSet<>();
    private boolean orderLeftoverTried;

    /** For the Stash Manager (bot tick): the order in hand and everything the protocol needs to know about it. */

    // ------------------------------------------------------------------------------------------------------------------
    // Repair passes (1.17.0, REPAIR-CONTRACT v2). When the bot is idle it finds low-durability items in the stash, takes the Mending ones (loose, or out of
    // unnamed shulker boxes it opens) and bottles from the stash, throws the bottles with the v1.2 trip (RepairTrip), and lets normal sorting put every item
    // in its best chest. Items without Mending go to the needs-repair chest by the sorter's own routing. The decisions are RepairRules, RepairPassGate and
    // RepairMemory (plain Java, tested); this runs them on the bot tick thread. Compiles and is unit tested in its parts; it has not been run in the game.

    private enum PassPhase { Plan, Items, Throw, Finish }

    private static final class PassRun {
        final long id;
        final String trigger;
        final RepairPassReport report;
        final Repair.Report thrown = new Repair.Report();
        PassPhase phase = PassPhase.Plan;
        @Nullable Bin bin;
        RepairRules.@Nullable Batch batch;
        int done, total, gatherJobs, needsAtStart, boxesOpened;
        @Nullable String yieldWhy;
        boolean stopped;
        final Set<String> skippedSeen = new HashSet<>();
        final Set<String> boxesSeen = new HashSet<>();
        /** Items tried with no headway in this pass (not got, or not repaired at all): left out of its later plans, manual or not. */
        final Set<String> noProgress = new HashSet<>();
        final Map<String, RepairRules.Cand> lotCands = new HashMap<>();
        final long startTick;

        PassRun(long id, String trigger, RepairPassReport report, long startTick) {
            this.id = id;
            this.trigger = trigger;
            this.report = report;
            this.startTick = startTick;
        }
    }

    private @Nullable PassRun pass;
    private @Nullable Predicate<Item> unpackFilter;
    private final RepairMemory repairMemory = new RepairMemory(System::currentTimeMillis);
    private long repairPassCounter, repairLastAutoEnd, repairNextEvalAt, repairBeatEvalAt, repairLastStopId;
    private volatile boolean repairManualPending;
    private volatile long repairManualAt;
    private long repairLastPassEnd;
    private final com.porter.qwertestash.orders.KitRestockStamps repairStamps = new com.porter.qwertestash.orders.KitRestockStamps(java.nio.file.Path.of("plugins", "qwerte-stash", "repair-passes.json"));
    private volatile boolean repairStopPending;
    private volatile @Nullable RepairPassReport repairLastReport;
    private volatile RepairPassGate.Decision repairDecision = new RepairPassGate.Decision(RepairPassGate.State.OFF, null, false);
    private volatile RepairCounts repairCounts = RepairCounts.NONE;
    private @Nullable String repairWarnedConflict, repairWarnedMissing;

    /** What the heartbeat says about candidates and bottles. */
    record RepairCounts(int mend, int mendBottles, int needsRepair, int inShulkers, int inBoxes, int boxes, long stash, int reserve, long usable) {
        static final RepairCounts NONE = new RepairCounts(0, 0, 0, 0, 0, 0, 0, RepairSettings.DEFAULT_RESERVE, 0);
    }

    public RepairMemory repairMemory() {
        return repairMemory;
    }

    private @Nullable RepairSettings repairSettings() {
        Stash st = stash;
        return st == null ? null : RepairStore.shared().get(ManagerIndex.clamp(st.server, 256), ManagerIndex.clamp(st.name, 256));
    }

    /** The settings a pass runs with: the cache, else the defaults (a local run with none cached). */
    private RepairSettings repairSettingsOrDefaults() {
        RepairSettings s = repairSettings();
        return s == null ? RepairSettings.DEFAULTS : s;
    }

    /** A manual run was asked for (the website's repairRun, or the command): runs when the bot is idle, orders first. Any thread. */
    public void repairRun() {
        repairManualPending = true;
        repairManualAt = System.currentTimeMillis();
        repairStopPending = false;
        repairNextEvalAt = 0;
    }

    /** A stop was asked for: a queued manual run is dropped and a running pass yields. Any thread. */
    public void repairStop() {
        repairManualPending = false;
        // Only a pass in hand has anything to stop: a stop with none must not make the next one give way at once
        if (pass != null) repairStopPending = true;
    }

    // -- Needs-repair routing and the reserved chest ------------------------------------------------------------------

    private long repairViewVersion = Long.MIN_VALUE;
    private @Nullable Box repairChestBox;
    private boolean repairRoutingOn;

    private void repairViewRefresh() {
        Stash st = stash;
        long version = ((long) RepairStore.shared().version() << 40) ^ ((long) Stashes.version << 8) ^ (st == null ? 0 : st.boxes.size()) ^ (CONFIG.repairPass ? 1 : 0)
            ^ (CONFIG.kitSorting ? 2 : 0) ^ ((long) KitStore.shared().version() << 20);
        if (version == repairViewVersion) return;
        repairViewVersion = version;
        repairChestBox = null;
        repairRoutingOn = false;
        RepairSettings s = repairSettings();
        if (st == null || s == null || !CONFIG.repairPass || !s.enabled() || s.needsRepairChest() == null) return;
        Box chest = null;
        for (Box b : st.boxes) {
            if (s.needsRepairChest().equals(KitRouting.canonicalId(b))) {
                chest = b;
                break;
            }
        }
        // A chest id the stash has no container for: routing is off (warned once), needs-repair items are sorted as today
        if (chest == null) {
            if (!s.needsRepairChest().equals(repairWarnedMissing)) {
                repairWarnedMissing = s.needsRepairChest();
                warn("The needs-repair chest the website set is not a container of this stash: needs-repair routing is off until it is.");
            }
            return;
        }
        repairWarnedMissing = null;
        // A chest that is also kit-reserved is the kits': kit reservation wins and needs-repair routing is off (warned once)
        KitRouting.View kits = kitView();
        if (chest != null && kits != null && kits.reserved(chest)) {
            if (!s.needsRepairChest().equals(repairWarnedConflict)) {
                repairWarnedConflict = s.needsRepairChest();
                warn("The needs-repair chest is also reserved for kits: needs-repair routing is off until one of them is changed.");
            }
            return;
        }
        repairWarnedConflict = null;
        repairRoutingOn = true;
        repairChestBox = chest;
    }

    /** The needs-repair chest the sorter reserves (nothing else is put in it), or null while routing is off. */
    private boolean repairReserved(Box box) {
        repairViewRefresh();
        return repairRoutingOn && repairChestBox == box;
    }

    /** A loose item that is low and has no Mending, with a needs-repair chest set: that chest is its only destination (or none while it can't take it). */
    private @Nullable List<Box> repairDestinations(Item item) {
        repairViewRefresh();
        RepairSettings s = repairSettings();
        if (!repairRoutingOn || s == null || !kitRoutingOn()) return null;
        if (!RepairRules.needsRepair(item, s.threshold())) return null;
        Box chest = repairChestBox;
        if (chest == null || !KitRouting.pickable(chest) || chest.free() <= 0) {
            if (chest == null || !KitRouting.pickable(chest)) repairMissing(s.needsRepairChest());
            return List.of();
        }
        return List.of(chest);
    }

    private void repairMissing(@Nullable String id) {
        boolean first;
        synchronized (kitWarned) {
            first = kitWarned.add("repair\t" + id);
        }
        if (first) warn("The needs-repair chest is missing or can't take items. Such an item stays where it is, and one from a drop-off goes to overflow.");
    }

    // -- Evaluating: candidates, bottles, whether a pass may start -------------------------------------------------------

    private long repairBottles() {
        Stash st = stash;
        if (st == null) return 0;
        int carried = carriedBottles();
        String key = Stashes.version + "|" + carried + "|" + System.currentTimeMillis() / 60_000L;
        if (key.equals(repairBottleKey)) return repairBottleCache;
        StockPlanner.Survey sv = orders.survey(st, Set.of(Repair.BOTTLE));
        repairBottleCache = sv.available().getOrDefault(Repair.BOTTLE, 0L) + sv.shulkerAvailable().getOrDefault(Repair.BOTTLE, 0L) + carried;
        repairBottleKey = key;
        return repairBottleCache;
    }

    /** The reserve a pass keeps: the website's, or the local minimum when higher. */
    private int repairReserve(RepairSettings s) {
        return RepairPassGate.reserve(s.reserve(), CONFIG.repairMinReserve);
    }

    // The scan and the bottle count are kept per version of the stash, of the settings and of the minute (box cooldowns run out with time)
    private String repairScanKey = "";
    private RepairRules.@Nullable Scan repairScanCache;
    private String repairBottleKey = "";
    private long repairBottleCache;

    private RepairRules.Scan repairScan(RepairSettings s) {
        String key = Stashes.version + "|" + RepairStore.shared().version() + "|" + s.hash() + "|" + System.currentTimeMillis() / 60_000L + "|" + CONFIG.repairPassMaxBoxes + "|" + stash.boxes.size();
        RepairRules.Scan cached = repairScanCache;
        if (cached != null && key.equals(repairScanKey)) return cached;
        RepairRules.Scan fresh = repairScanNow(s);
        repairScanKey = key;
        repairScanCache = fresh;
        return fresh;
    }

    private RepairRules.Scan repairScanNow(RepairSettings s) {
        Stash st = stash;
        return RepairRules.scan(st.boxes, b -> b.sortable() && !b.stale && !b.dropOff && !b.delivery && !b.excluded && b.known() && !kitReserved(b), s.threshold(),
            s.shulkers() && CONFIG.repairPassMaxBoxes > 0, this::repairKitLike, repairMemory::boxOnCooldown, s.needsRepairChest() != null);
    }

    /** A box the kit rules call a kit; with no kit settings yet, the default kit score decides, so a kit-looking unnamed box stays closed. */
    private boolean repairKitLike(Item it) {
        KitRouting.View kits = kitView();
        if (kits != null) return kits.isKit(it);
        return com.porter.qwertestash.stash.KitScore.of(it, com.porter.qwertestash.stash.KitScore.Scoring.DEFAULT, com.porter.qwertestash.stash.KitItems.shared()).kit();
    }

    private @Nullable double[] repairPos(String id) {
        for (Box b : stash.boxes) {
            if (id.equals(KitRouting.canonicalId(b))) {
                BlockPos p = nearestPart(b);
                return new double[] {p.x() + 0.5, p.z() + 0.5};
            }
        }
        return null;
    }

    private int repairArmour() {
        int armour = 0;
        for (int w = 5; w <= 8; w++) {
            RepairTrip.Stack worn = repairPort.at(w);
            if (worn != null && worn.mending() && worn.damage() > 0) armour++;
        }
        return armour;
    }

    /**
     * Works out the candidates and whether a pass may start. {@code idle}: called from the sorter's resting point (only then can it say start). Throttled; the
     * answer is kept for the heartbeat. Tick thread.
     */
    private RepairPassGate.Decision repairEvaluate(boolean idle) {
        long now = System.currentTimeMillis();
        if (!CONFIG.repairPass) {
            repairDecision = new RepairPassGate.Decision(RepairPassGate.State.OFF, null, false);
            return repairDecision;
        }
        if (now < (idle ? repairNextEvalAt : repairBeatEvalAt)) return idle ? new RepairPassGate.Decision(repairDecision.state(), repairDecision.why(), false) : repairDecision;
        if (idle) repairNextEvalAt = now + 5_000L;
        else repairBeatEvalAt = now + 10_000L;
        Stash st = stash;
        RepairSettings cached = repairSettings();
        RepairSettings s = cached == null ? RepairSettings.DEFAULTS : cached;
        // A queued manual run is dropped while repair is disabled on the website, and expires after 10 minutes
        if (repairManualPending && !RepairPassGate.manualLive(true, repairManualAt, now, cached != null && !cached.enabled())) repairManualPending = false;
        boolean manual = repairManualPending;
        RepairRules.Scan scan;
        long bottles;
        long usable;
        boolean hasCand = false, anyMend = false;
        if (st == null) {
            scan = new RepairRules.Scan(List.of(), List.of(), List.of(), 0, Map.of());
            bottles = 0;
        }
        else {
            scan = repairScan(s);
            bottles = repairBottles();
        }
        usable = RepairRules.usable(bottles, EnchantState.SHARED.bottlesReserved(), repairReserve(s));
        List<RepairRules.Cand> all = new ArrayList<>(scan.mend());
        for (RepairRules.BoxStop b : scan.boxes()) all.addAll(b.mend());
        for (RepairRules.Cand c : all) {
            if (!manual && repairMemory.recentlyFailed(c.memoryKey())) continue;
            anyMend = true;
            if (c.bottles() <= usable) {
                hasCand = true;
                break;
            }
        }
        repairViewRefresh();
        boolean kitConflict = cached != null && cached.needsRepairChest() != null && CONFIG.repairPass && !repairRoutingOn && st != null && repairWarnedConflict != null;
        RepairPassGate.Facts f = new RepairPassGate.Facts(CONFIG.repairPass, cached != null, s.enabled(), s.auto(), manual, Alerts.online(), isEnabled(),
            orders.flow.holdsOrder() || orders.flow.resumeId() != null || restockOrdersWaiting || pass != null, foreignNow, idle && pass == null, idle && repairPlayerNear(true), quietElapsed(),
            kitConflict, hasCand, anyMend, freeCarryCount() < 4, now - repairLastAutoEnd, CONFIG.repairPassEveryMinutes * 60_000L);
        RepairPassGate.Decision d = RepairPassGate.decide(f);
        // The local limits hold for manual runs too: a gap since the last pass, and the passes an hour
        if (d.start() && !RepairPassGate.limitsOk(manual, now - repairLastPassEnd, CONFIG.repairPassMinGapManualMinutes, repairStamps.lastHour(now), CONFIG.repairPassMaxPerHour)) {
            d = new RepairPassGate.Decision(RepairPassGate.State.WAITING, RepairPassGate.COOLDOWN, false);
        }
        // The pass is running: that is the state; a decision "start" is only for the idle point
        repairDecision = d;
        repairCounts = new RepairCounts(scan.mend().size(), scan.mendBottles(), scan.needs().size(), scan.inShulkers(), scan.inBoxes(), scan.boxes().size(), bottles, repairReserve(s), usable);
        return idle ? d : new RepairPassGate.Decision(d.state(), d.why(), false);
    }

    /** What the heartbeat's repairPass says, from the last evaluation and the pass in hand. Tick thread. */
    public JsonObject repairBeat(long runDone, long stopDone, @Nullable String reportHash) {
        PassRun p = pass;
        RepairPassGate.Decision d = repairEvaluate(false);
        RepairCounts c = repairCounts;
        RepairPassGate.State state = d.state();
        String why = d.why();
        boolean progress = false;
        int done = 0, total = 0;
        long id = repairLastReport == null ? repairPassCounter : repairPassCounter;
        String trigger = repairLastReport == null ? null : repairLastReport.trigger;
        if (p != null) {
            state = p.yieldWhy != null ? RepairPassGate.State.YIELDING : RepairPassGate.State.RUNNING;
            why = null;
            progress = true;
            done = p.done;
            total = p.total;
            id = p.id;
            trigger = p.trigger;
        }
        else if (CONFIG.repairPass && !Alerts.online()) {
            state = RepairPassGate.State.WAITING;
            why = RepairPassGate.OFFLINE;
        }
        else if (CONFIG.repairPass && !isEnabled() && state != RepairPassGate.State.BLOCKED) {
            state = RepairPassGate.State.WAITING;
            why = RepairPassGate.SORTER_OFF;
        }
        return RepairPassGate.beat(state, why, id, trigger, done, total, progress, c.mend(), c.mendBottles(), c.needsRepair(), c.inShulkers(), c.inBoxes(), c.boxes(), c.stash(), c.reserve(),
            c.usable(), runDone, stopDone, reportHash);
    }

    public @Nullable RepairPassReport repairLastReport() {
        return repairLastReport;
    }

    /** The pass in hand or the last one, for the report route: its current state. */
    public @Nullable RepairPassReport repairReportNow() {
        PassRun p = pass;
        return p != null ? p.report : repairLastReport;
    }

    /** `stash repair status` */
    public List<String> repairStatus() {
        List<String> out = new ArrayList<>();
        RepairSettings s = repairSettings();
        RepairCounts c = repairCounts;
        PassRun p = pass;
        out.add("Repair mode: " + (CONFIG.repairPass ? (s == null ? "on, no settings from the server yet (a manual run uses the defaults)" : s.enabled() ? "on" + (s.auto() ? ", automatic" : ", manual only") : "off on the website") : "off here"));
        RepairPassGate.Decision d = repairDecision;
        out.add("State: " + (p != null ? (p.yieldWhy != null ? "yielding (" + p.yieldWhy + ")" : "running " + p.done + "/" + p.total) : d.state().name().toLowerCase(java.util.Locale.ROOT) + (d.why() == null ? "" : " (" + d.why() + ")")));
        out.add("Candidates: " + c.mend() + " Mending items (" + c.mendBottles() + " bottles), " + c.needsRepair() + " without Mending, " + c.inBoxes() + " in boxes it will open, "
            + c.inShulkers() + " in boxes it won't");
        out.add("Bottles: " + c.stash() + " in the stash, reserve " + c.reserve() + ", usable " + c.usable());
        RepairPassReport r = repairLastReport;
        if (r != null) out.add("Last pass: " + r.state + ", " + r.repaired + " repaired, " + r.partial + " partly, " + r.skipped() + " skipped, " + r.bottlesUsed + " bottles");
        return out;
    }

    // -- Starting, running and ending a pass ----------------------------------------------------------------------------

    /** The sorter is resting: a pass starts when the gate says so (auto, or a manual run). Tick thread. */
    private boolean startRepairPass() {
        // Not before the server has said whether orders wait (the first heartbeat reply after connecting), as restock does
        if (pass != null || repair != null || repairTrip != null || stash == null || !restockHeard) return false;
        RepairPassGate.Decision d = repairEvaluate(true);
        if (!d.start()) return false;
        boolean manual = repairManualPending;
        RepairSettings s = repairSettingsOrDefaults();
        long id = ++repairPassCounter;
        repairStopPending = false;
        repairStamps.add(System.currentTimeMillis());
        RepairPassReport report = new RepairPassReport(id, manual ? "manual" : "auto", System.currentTimeMillis(), s.threshold());
        PassRun p = new PassRun(id, manual ? "manual" : "auto", report, tickCount);
        RepairSettings cached = repairSettings();
        p.needsAtStart = needsInChest(cached);
        pass = p;
        repairLastReport = report;
        info("Repair pass {} starts ({}).", id, p.trigger);
        return continuePass();
    }

    private int needsInChest(@Nullable RepairSettings s) {
        repairViewRefresh();
        Box chest = repairChestBox;
        if (chest == null || chest.contents == null || s == null) return 0;
        int n = 0;
        for (Item it : chest.contents) if (it != null && RepairRules.damageable(it) && !it.hasMending() && RepairRules.low(it.damage, it.maxDamage, s.threshold())) n++;
        return n;
    }

    /** Why a pass must give way now (orders always first), or null. */
    private @Nullable String passYieldReason(PassRun p) {
        if (repairStopPending) return RepairRules.STOPPED;
        if (orders.flow.holdsOrder() || orders.flow.resumeId() != null || restockOrdersWaiting) return RepairRules.ORDER;
        if (!CONFIG.repairPass || !isEnabled()) return RepairRules.STOPPED;
        RepairSettings s = repairSettings();
        if (s != null && !s.enabled()) return RepairRules.STOPPED;
        if (orders.pauseReason() != null) return RepairRules.INTERRUPTED;
        return null;
    }

    private boolean continuePass() {
        PassRun p = pass;
        if (p == null) return false;
        if (job != null) return true;
        if (repair != null) return false;
        if (p.phase != PassPhase.Finish && p.phase != PassPhase.Throw) {
            String why = passYieldReason(p);
            if (why == null && repairPlayerNear(true)) why = RepairRules.PLAYER_NEAR;
            if (why == null && p.thrown.ticks() >= CONFIG.repairPassMaxMinutes * 1200) why = RepairRules.STOPPED;
            if (why != null) {
                p.yieldWhy = why;
                p.stopped = why.equals(RepairRules.STOPPED);
                p.phase = PassPhase.Finish;
            }
        }
        switch (p.phase) {
            case Plan -> {
                return passPlan(p);
            }
            case Items -> {
                return passItems(p);
            }
            case Throw -> {
                return passThrow(p);
            }
            case Finish -> {
                // And again before the end: no emptied box is left in the inventory
                if (handle == null && returnSpentShulker()) return true;
                finishPass(p);
                return false;
            }
        }
        return false;
    }

    private boolean passPlan(PassRun p) {
        RepairSettings s = repairSettingsOrDefaults();
        if (!orders.gate()) return false;
        if (p.report.items().size() + p.total >= CONFIG.repairPassMaxItems && p.total >= CONFIG.repairPassMaxItems) {
            p.phase = PassPhase.Finish;
            return continuePass();
        }
        RepairRules.Scan scan = repairScan(s);
        scan = RepairRules.without(scan, p.noProgress);
        // Items that came back unrepaired are not retried by automatic passes for 6 h; a manual pass retries them
        boolean manual = p.trigger.equals("manual");
        if (!manual) {
            List<RepairRules.Cand> mend = new ArrayList<>();
            for (RepairRules.Cand c : scan.mend()) if (!repairMemory.recentlyFailed(c.memoryKey())) mend.add(c);
            scan = new RepairRules.Scan(mend, scan.needs(), scan.boxes(), scan.inShulkers(), scan.inShulkerReasons());
        }
        long usable = RepairRules.usable(repairBottles(), EnchantState.SHARED.bottlesReserved(), repairReserve(s));
        BlockPos feet = playerFeet();
        Map<String, double[]> positions = new HashMap<>();
        RepairRules.Plan plan = RepairRules.plan(scan, usable, freeCarryCount(), repairArmour(), repairPort.at(45) != null, feet.x() + 0.5, feet.z() + 0.5,
            id -> positions.computeIfAbsent(id, this::repairPos), Math.max(0, CONFIG.repairPassMaxItems - p.total), Math.max(0, CONFIG.repairPassMaxBoxes - p.boxesOpened));
        for (RepairRules.Skipped sk : plan.skipped()) {
            if (p.skippedSeen.add(sk.cand().key() + "|" + sk.cand().container() + "|" + sk.cand().slot())) {
                p.report.skip(sk.cand().key(), sk.reason(), sk.cand().container(), sk.cand().damage(), sk.cand().maxDamage(), sk.cand().box());
            }
        }
        p.report.left = plan.left();
        RepairRules.Batch batch = plan.batch();
        if (batch.empty()) {
            p.phase = PassPhase.Finish;
            return continuePass();
        }
        List<Order.Lot> lots = new ArrayList<>();
        p.lotCands.clear();
        for (RepairRules.Cand c : batch.loose()) addLot(p, lots, c);
        for (RepairRules.BoxStop b : batch.boxes()) {
            for (RepairRules.Cand c : b.mend()) addLot(p, lots, c);
        }
        for (RepairRules.Cand c : batch.extraNeeds()) addLot(p, lots, c);
        p.batch = batch;
        p.total += batch.items();
        Unit unit = new Unit(Unit.Kind.Bin, "", lots);
        p.bin = new Bin(unit);
        p.gatherJobs = 0;
        p.phase = PassPhase.Items;
        step("repair: taking " + batch.items() + " item" + (batch.items() == 1 ? "" : "s") + " from the stash to repair.");
        return passItems(p);
    }

    private void addLot(PassRun p, List<Order.Lot> lots, RepairRules.Cand c) {
        lots.add(new Order.Lot(c.key(), c.variant(), 1));
        p.lotCands.put(c.key() + "|" + c.variant(), c);
    }

    /** Boxes a pass may open: unnamed, not a kit, not opened by a pass lately, holding a mend candidate; at most the cap. */
    private boolean passBoxOk(PassRun p, Item shulker) {
        if (shulker.customName) return false;
        if (repairKitLike(shulker)) return false;
        String key = ManagerIndex.shulkerKey(shulker);
        if (key == null || repairMemory.boxOnCooldown(key)) return false;
        if (!p.boxesSeen.contains(key) && p.boxesOpened >= CONFIG.repairPassMaxBoxes) return false;
        RepairSettings s = repairSettings();
        return s == null || s.shulkers();
    }

    private boolean passItems(PassRun p) {
        Bin bin = p.bin;
        if (bin == null) {
            p.phase = PassPhase.Finish;
            return continuePass();
        }
        if (!orders.gate()) return false;
        // A box that was emptied of what the pass needed goes back before the next load is looked for
        if (handle == null) {
            sourceShulkers.entrySet().removeIf(e -> {
                Item held = inventoryItem(e.getKey());
                return held == null || !held.isShulker();
            });
            if (returnSpentShulker()) return true;
        }
        BinTracker.Need need = needOf(bin);
        if (need == null) {
            p.phase = PassPhase.Throw;
            return continuePass();
        }
        if (++p.gatherJobs > 8 + 3 * bin.lots.size()) return passItemsGiveUp(p, RepairRules.INTERRUPTED);
        if (freeCarryCount() < 2) return passItemsGiveUp(p, RepairRules.NO_ROOM);
        Box source = binSourceFor(bin);
        if (source != null) {
            gatherLeft = Math.max(1, Math.min(bin.lots.size(), freeCarryCount() - 1));
            return start(Job.Gather, source);
        }
        // Not loose anywhere: out of an unnamed box, one at a time, after a look that nobody is near
        RepairRules.Cand cand = p.lotCands.get(need.lot().key() + "|" + need.lot().variant());
        if (cand != null && cand.box() != null) {
            if (shulkerPause() != null) return passItemsDrop(p, need, RepairRules.INTERRUPTED);
            try {
                if (groundItems().anyPlayerWithin(CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getZ(), 16.0) != null) return passItemsDrop(p, need, RepairRules.PLAYER_NEAR);
            } catch (RuntimeException e) {
                return passItemsDrop(p, need, RepairRules.PLAYER_NEAR);
            }
            unpackFilter = it -> passBoxOk(p, it);
            boolean started;
            try {
                started = startBinUnpack(bin, "repair: ");
            } finally {
                unpackFilter = null;
            }
            if (started) {
                if (cand.box() != null && p.boxesSeen.add(cand.box())) {
                    p.boxesOpened++;
                    p.report.boxesOpened++;
                    repairMemory.boxOpened(cand.box());
                }
                return true;
            }
            p.report.boxesFailed++;
            return passItemsDrop(p, need, RepairRules.BOX_FAILED);
        }
        return passItemsDrop(p, need, RepairRules.INTERRUPTED);
    }

    /** This load cannot be had: its item is reported skipped with the reason, and the rest of the batch goes on. */
    private boolean passItemsDrop(PassRun p, BinTracker.Need need, String reason) {
        Bin bin = p.bin;
        RepairRules.Cand c = p.lotCands.remove(need.lot().key() + "|" + need.lot().variant());
        if (c != null) {
            p.report.skip(c.key(), reason, c.container(), c.damage(), c.maxDamage(), c.box());
            p.noProgress.add(c.memoryKey());
        }
        if (bin != null) bin.lots.remove(need.index());
        return continuePass();
    }

    private boolean passItemsGiveUp(PassRun p, String reason) {
        Bin bin = p.bin;
        if (bin != null) {
            for (BinTracker.Held h : carriedHeld(bin)) {
                // Carried stacks are the ones that were got; the others are reported
            }
            BinTracker.Need need;
            while ((need = needOf(bin)) != null) {
                RepairRules.Cand c = p.lotCands.remove(need.lot().key() + "|" + need.lot().variant());
                if (c != null) {
                    p.report.skip(c.key(), reason, c.container(), c.damage(), c.maxDamage(), c.box());
                    p.noProgress.add(c.memoryKey());
                }
                bin.lots.remove(need.index());
            }
        }
        p.phase = PassPhase.Throw;
        return continuePass();
    }

    private boolean passThrow(PassRun p) {
        Bin bin = p.bin;
        if (bin == null || repair != null) return false;
        if (!orders.gate()) return false;
        List<RepairRun.Item> items = new ArrayList<>();
        Map<RepairRun.Item, RepairRules.Cand> cands = new IdentityHashMap<>();
        List<RepairCand> rcs = new ArrayList<>();
        for (BinTracker.Held h : BinTracker.ready(bin.lots, bin.packed, carriedHeld(bin))) {
            RepairRules.Cand c = p.lotCands.get(h.key() + "|" + h.variant());
            if (c == null) c = p.lotCands.get(h.key() + "|" + null);
            Item it = inventoryItem(h.slot());
            if (it == null || c == null || !c.mending()) continue;
            String why = Repair.skipReason(it.isShulker(), it.hasMending(), it.damage, it.maxStack);
            if (why != null) continue;
            RepairRun.Item ri = new RepairRun.Item(h.key(), windowOf(h.slot()), it.damage, it.maxDamage);
            items.add(ri);
            cands.put(ri, c);
            rcs.add(new RepairCand(h.key(), h.variant(), windowOf(h.slot())));
        }
        if (items.isEmpty() || RepairRun.exhausted(p.thrown)) {
            p.phase = PassPhase.Finish;
            return continuePass();
        }
        RepairCtl ctl = new RepairCtl(bin, new RepairRun(p.thrown, items));
        ctl.pass = p;
        ctl.bottleCap = p.batch == null ? -1 : p.batch.bottles();
        for (int i = 0; i < items.size(); i++) {
            ctl.cands.put(items.get(i), rcs.get(i));
            ctl.passCands.put(items.get(i), cands.get(items.get(i)));
        }
        repair = ctl;
        step("repair pass: repairing " + items.size() + " item" + (items.size() == 1 ? "" : "s") + " with experience bottles; next: take bottles from the stash.");
        return continueRepair();
    }

    /** The throw is over: what became of each item (repaired, partly, not) goes in the report and the memory. */
    private void passThrowDone(RepairCtl r) {
        PassRun p = r.pass;
        if (p == null) return;
        for (var e : r.passCands.entrySet()) {
            RepairRun.Item ri = e.getKey();
            RepairRules.Cand c = e.getValue();
            RepairCand rc = r.cands.get(ri);
            p.done++;
            String key = c.key();
            if (ri.damage <= 0) {
                String to = null;
                p.report.add(new RepairPassReport.Item(key, "repaired", null, c.container(), to, 0, ri.maxDamage, c.box()));
                if (rc != null) repairMemory.repaired(c.key(), rc.key);
                Item now = rc == null ? null : inventoryItem(indexOf(rc.slot));
                if (now != null) {
                    List<Box> homes = destinationsFor(now);
                    boolean originIsHome = false;
                    for (Box b : homes) originIsHome |= c.container().equals(KitRouting.canonicalId(b));
                    if (!homes.isEmpty() && !originIsHome) p.report.rehomed++;
                }
            }
            else if (ri.damage < ri.startDamage) {
                p.report.add(new RepairPassReport.Item(key, "partial", null, c.container(), null, ri.damage, ri.maxDamage, c.box()));
                repairMemory.failed(c.memoryKey());
                if (c.box() != null) repairMemory.noRepack(c.memoryKey());
            }
            else {
                String reason = p.yieldWhy != null ? (p.stopped ? RepairRules.STOPPED : p.yieldWhy.equals(RepairRules.ORDER) ? RepairRules.ORDER : RepairRules.INTERRUPTED)
                    : ri.reason.equals(Repair.NO_BOTTLES) ? RepairRules.NO_BOTTLES : RepairRules.INTERRUPTED;
                p.report.skip(key, reason, c.container(), ri.damage, ri.maxDamage, c.box());
                p.noProgress.add(c.memoryKey());
                if (p.yieldWhy == null) repairMemory.failed(c.memoryKey());
                if (c.box() != null) repairMemory.noRepack(c.memoryKey());
            }
        }
        p.report.bottlesUsed = p.thrown.bottlesUsed();
        p.phase = PassPhase.Plan;
        // Another batch is planned afresh, unless something asked the pass to give way
        if (p.yieldWhy != null) p.phase = PassPhase.Finish;
        else if (p.total >= CONFIG.repairPassMaxItems || p.thrown.ticks() >= CONFIG.repairPassMaxMinutes * 1200) p.phase = PassPhase.Finish;
    }

    private void finishPass(PassRun p) {
        long now = System.currentTimeMillis();
        String state;
        if (p.stopped) state = RepairPassReport.STOPPED;
        else if (p.yieldWhy != null) state = RepairPassReport.YIELDED;
        else state = RepairPassReport.DONE;
        p.report.bottlesUsed = p.thrown.bottlesUsed();
        RepairSettings cached = repairSettings();
        p.report.toNeedsRepair = Math.max(0, needsInChest(cached) - p.needsAtStart);
        p.report.end(state, now);
        info("Repair pass {} {}: {} repaired, {} partly, {} skipped, {} bottles.", p.id, state, p.report.repaired, p.report.partial, p.report.skipped(), p.report.bottlesUsed);
        repairLastReport = p.report;
        repairLastPassEnd = now;
        if (p.trigger.equals("auto") && !state.equals(RepairPassReport.YIELDED)) repairLastAutoEnd = now;
        if (p.trigger.equals("manual") && !state.equals(RepairPassReport.YIELDED)) repairManualPending = false;
        if (state.equals(RepairPassReport.STOPPED)) repairStopPending = false;
        pass = null;
        unpackFilter = null;
        repairNextEvalAt = 0;
        // Whatever is still carried (repaired items, items not repaired, bottles) is the sorter's again and goes to its best chest
    }

    // Kit restock (1.16.0, KIT-CONTRACT v2 revision 4 "Restock"). An idle job: the sorter offers the order flow a restock order (never reported to the
    // server, never saved), so the kit is planned, built, named and carried by the code kit orders use; the finished kit is filed by the sorter.
    // Compiles and is unit tested in its parts; it has not been run in the game.

    private static final String RESTOCK_ID = "restock-";
    private volatile boolean restockHeard;
    private int restockSeq;
    private long restockNextAt;
    private @Nullable String restockTemplate;
    private boolean restockTopUp;
    private volatile boolean restockOrdersWaiting;
    private volatile com.porter.qwertestash.orders.KitRestock.Decision restockDecision = new com.porter.qwertestash.orders.KitRestock.Decision(com.porter.qwertestash.orders.KitRestock.State.OFF, null, null, false);

    /** Whether an order waits for the bot (the last heartbeat reply said so): restock gives way. Tick thread. */
    public void restockHint(boolean ordersWaiting, boolean heard) {
        restockOrdersWaiting = ordersWaiting;
        restockHeard = heard;
    }

    /** The restock state for the kit report. Any thread. */
    public com.porter.qwertestash.orders.KitRestock.Decision restockState() {
        OrderFlow f = orders.flow;
        if (f.isRestock() && f.running()) {
            return new com.porter.qwertestash.orders.KitRestock.Decision(com.porter.qwertestash.orders.KitRestock.State.BUILDING, restockTemplate,
                restockTopUp ? com.porter.qwertestash.orders.KitRestock.TOPPING_UP : null, restockTopUp);
        }
        if (!CONFIG.kitSorting || !CONFIG.kitOrders || !CONFIG.kitRestock) return new com.porter.qwertestash.orders.KitRestock.Decision(com.porter.qwertestash.orders.KitRestock.State.OFF, null, null, false);
        return restockDecision;
    }

    /** A restock job in hand gives way at once to a waiting order, a player, the queue or a switch-off: it is cancelled, and what it carries is put back. Tick thread. */
    public void restockYieldCheck() {
        OrderFlow f = orders.flow;
        if (!f.isRestock() || !f.running()) return;
        if (com.porter.qwertestash.orders.KitRestock.mustStop(restockOrdersWaiting, CONFIG.kitRestock && CONFIG.kitSorting, CONFIG.kitOrders, CONFIG.acceptOrders, orders.pauseReason() != null)) {
            String id = f.orderId();
            if (id != null && f.cancel(id)) debug("Kit restock stops: an order, a player or a switch-off.");
        }
    }

    private boolean startRestock() {
        com.porter.qwertestash.orders.KitRestock.State off = com.porter.qwertestash.orders.KitRestock.State.OFF;
        if (!CONFIG.kitSorting || !CONFIG.kitOrders || !CONFIG.kitRestock) {
            restockDecision = new com.porter.qwertestash.orders.KitRestock.Decision(off, null, null, false);
            return false;
        }
        OrderFlow flow = orders.flow;
        Stash st = stash;
        // Not while an order is held or saved to be resumed, nor before the server has said whether orders wait (after connecting), nor with orders off
        if (st == null || pass != null || repair != null || !com.porter.qwertestash.orders.KitRestock.mayStart(flow.holdsOrder(), flow.resumeId() != null, restockHeard, CONFIG.acceptOrders, orders.pauseReason() != null)) return false;
        long now = System.currentTimeMillis();
        if (now < restockNextAt) return false;
        restockNextAt = now + 30_000;
        KitSettings settings = KitStore.shared().get(ManagerIndex.clamp(st.server, 256), ManagerIndex.clamp(st.name, 256));
        KitRouting.View view = kitView();
        com.porter.qwertestash.orders.KitRestock.Decision idle = new com.porter.qwertestash.orders.KitRestock.Decision(com.porter.qwertestash.orders.KitRestock.State.IDLE, null, null, false);
        if (settings == null || settings.types() == null || settings.types().templates().isEmpty() || view == null) {
            restockDecision = idle;
            return false;
        }
        long bottles = com.porter.qwertestash.orders.KitRestock.bottlesUsable(orders.bottlesInStash());
        int empties = orders.emptiesTotal();
        // At most MAX_RESTOCK_CANDIDATES templates with a keep count (by id) are looked at, with one scan of the stash between them
        List<com.porter.qwertestash.stash.KitTemplate> keepers = com.porter.qwertestash.orders.KitRestock.candidates(settings.types().templates());
        List<com.porter.qwertestash.orders.KitRestock.Candidate> cands = new ArrayList<>();
        if (!keepers.isEmpty()) {
            List<com.porter.qwertestash.orders.KitOrder.Line> unionLines = new ArrayList<>();
            for (com.porter.qwertestash.stash.KitTemplate t : keepers) unionLines.add(new com.porter.qwertestash.orders.KitOrder.Line(t, 1, 0, 1));
            OrderGlue.RestockStock stock = orders.restockStock(new com.porter.qwertestash.orders.KitOrder.Snapshot(1, unionLines, List.of(), false));
            if (stock != null) for (com.porter.qwertestash.stash.KitTemplate t : keepers) cands.add(restockCandidate(t, settings, view, bottles, empties, stock));
        }
        com.porter.qwertestash.orders.KitRestockStamps stamps = com.porter.qwertestash.orders.KitRestockStamps.shared();
        boolean naming = anvilWay() != AnvilFinder.Way.NONE;
        com.porter.qwertestash.orders.KitRestock.Decision d = com.porter.qwertestash.orders.KitRestock.decide(new com.porter.qwertestash.orders.KitRestock.Inputs(true,
            restockOrdersWaiting, stamps.lastHour(now), CONFIG.kitRestockPerHour, empties - 1, CONFIG.kitRestockMinEmpty, naming, cands));
        restockDecision = d;
        if (d.state() != com.porter.qwertestash.orders.KitRestock.State.BUILDING || d.template() == null) return false;
        com.porter.qwertestash.stash.KitTemplate t = settings.types().template(d.template());
        if (t == null) return false;
        Order o = new Order(RESTOCK_ID + (++restockSeq), List.of(new Order.Line(Order.KIT, t.id(), 1)), null, false, null, restockSnapshot(t, bottles));
        o.restock = true;
        o.restockTopUp = d.topUp();
        String why = flow.offer(o);
        if (why != null) {
            restockNextAt = now + 10 * 60_000L;
            info("Kit restock for {} did not start: {}.", t.id(), why);
            return false;
        }
        stamps.add(now);
        restockTemplate = t.id();
        restockTopUp = d.topUp();
        info("Kit restock: {} for template {}.", d.topUp() ? "topping up a copy" : "building a copy", t.id());
        return true;
    }

    private static com.porter.qwertestash.orders.KitOrder.Snapshot restockSnapshot(com.porter.qwertestash.stash.KitTemplate t, long bottles) {
        return new com.porter.qwertestash.orders.KitOrder.Snapshot(1, List.of(new com.porter.qwertestash.orders.KitOrder.Line(t, 1, 0, 1)),
            com.porter.qwertestash.orders.KitRestock.takeFor(t, bottles), false);
    }

    /** What restock needs to know about a template: its copies in the chain target and whether the stash can fill a new copy or top one up. */
    private com.porter.qwertestash.orders.KitRestock.Candidate restockCandidate(com.porter.qwertestash.stash.KitTemplate t, KitSettings settings, KitRouting.View view, long bottles, int empties,
                                                                                OrderGlue.RestockStock stock) {
        String target = KitRouting.templateTarget(t, settings);
        Box box = target == null ? null : view.box(target);
        boolean pick = KitRouting.pickable(box) && box != null && !box.stale;
        int complete = 0, incomplete = 0;
        if (box != null && box.contents != null) {
            for (Item it : box.contents) {
                if (it == null || !it.isShulker() || it.contents.isEmpty()) continue;
                String name = it.customName ? com.porter.qwertestash.stash.KitNames.normalize(it.customText) : null;
                com.porter.qwertestash.stash.KitTemplates.State state = com.porter.qwertestash.stash.KitTemplates.match(t, com.porter.qwertestash.orders.KitPlanner.stacksOf(it), name).state();
                if (state == com.porter.qwertestash.stash.KitTemplates.State.COMPLETE) complete++;
                else if (state == com.porter.qwertestash.stash.KitTemplates.State.INCOMPLETE) incomplete++;
            }
        }
        com.porter.qwertestash.orders.KitOrder.Snapshot snap = restockSnapshot(t, bottles);
        com.porter.qwertestash.orders.KitPlanner.Plan fresh = orders.planWith(stock, snap, empties, true);
        boolean build = false, partial = false;
        if (fresh != null && !fresh.units().isEmpty()) {
            Unit u = fresh.units().get(0);
            build = !u.kitShort;
            partial = true;
        }
        boolean topUp = false;
        if (incomplete > 0) {
            com.porter.qwertestash.orders.KitPlanner.Plan top = orders.planWith(stock, snap, empties, false);
            if (top != null) for (Unit u : top.units()) topUp |= u.kitBase != null && u.kitGathered > 0;
        }
        boolean bottleSlot = false;
        for (com.porter.qwertestash.stash.KitTemplate.Slot s : t.slots()) bottleSlot |= s != null && s.base().equals(com.porter.qwertestash.orders.KitRestock.BOTTLE);
        boolean bottleBlocked = bottleSlot && !build && !partial && bottles <= 0;
        return new com.porter.qwertestash.orders.KitRestock.Candidate(t, target, pick, box != null && box.free() > 0, complete, incomplete, topUp, build, partial, bottleBlocked);
    }

    // The wait for the area's dimension (1.16.1): since when, and the last time it was said
    private volatile long areaWaitSince;
    private long areaWaitLogAt;

    /** Why the stash area keeps the sorter waiting for 2 minutes or more (it is in another dimension), or null. Any thread. */
    public @Nullable String areaWait(long now) {
        Stash s = stash;
        if (s == null) return null;
        return com.porter.qwertestash.stash.Dimensions.waitReport(s, areaWaitSince, now, Stash.currentDimension());
    }

    public long areaWaitSince() {
        return areaWaitSince;
    }

    public OrderFlow orderFlow() {
        return orders.flow;
    }

    /**
     * Why a pearl trip can't leave now, as the contract's wait code, or null when the sorter is at a checkpoint (1.18.0): {@code busy_order} with an order in hand,
     * {@code busy_repair} during a repair pass, {@code sorter_busy} while a container job, a shulker, a carry, a craft or a driven read is on. Bot tick only.
     */
    public @Nullable String travelHold() {
        if (orders.flow.holdsOrder() || orders.flow.resumeId() != null) return "busy_order";
        if (pass != null || repair != null || repairTrip != null) return "busy_repair";
        Stash s = stash;
        if (job != null || finishing || handle != null || compact != null || activeBin != null || (s != null && s.inFlight != null) || pendingResume != null
            || craft != null || anvilRun != null || chestRun != null || naming != null || putBackTrip || tableStuck || driven || holdingCursor() || openContainerId() != 0) {
            return "sorter_busy";
        }
        return null;
    }

    /** What an order has to wait for (finishing a shulker), or null. Bot tick only. */
    public @Nullable String orderBusy() {
        if (orders.flow.holdsOrder()) return "working on an order";
        if (compact != null && compact.bin == null) return "finishing a compaction";
        if (pendingResume != null || standingShulker() != null) return "finishing a shulker";
        return shulkerPause();
    }

    /** Free inventory slots an order may carry in. Bot tick only. */
    public int freeOrderSlots() {
        if (!CACHE.getPlayerCache().isAlive() || CACHE.getPlayerCache().getPlayerInventory().size() < 45) return 0;
        Set<Integer> keep = keepSlots();
        Set<Integer> chosen = CONFIG.carrySlots.isBlank() ? null : parseSlots(CONFIG.carrySlots);
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (keep.contains(i) || (chosen != null && !chosen.contains(i))) continue;
            if (inventoryItem(i) == null) free++;
        }
        return free;
    }

    /** The stash holds, or the bot carries, a shulker box. Bot tick only. */
    public boolean shulkersKnown() {
        Stash st = orders.st();
        if (st != null) {
            for (Box box : st.boxes) {
                if (box.contents == null || box.delivery || box.excluded || !st.inScope(box)) continue;
                for (Item item : box.contents) if (item != null && item.isShulker()) return true;
            }
        }
        for (int i = 0; i < 36; i++) {
            Item item = inventoryItem(i);
            if (item != null && item.isShulker()) return true;
        }
        return false;
    }

    /** At least one container of the stash has been read. */
    public boolean stashReadOnce() {
        Stash st = orders.st();
        return st != null && st.knownCount() > 0;
    }

    /** Wakes the sorter (stay-on mode) for an order. */
    public void orderTaken() {
        idle = false;
    }

    /** The containers still to be read before a plan can be trusted, for the status. */
    public int staleContainers() {
        return orders.staleCount();
    }

    /** Containers read within the fresh window are not read again when a run starts; the rest are marked stale. */
    private void markStaleForStart() {
        long now = System.currentTimeMillis();
        long window = Math.max(0, CONFIG.freshMinutes) * 60_000L;
        for (Box box : stash.boxes) {
            if (box.excluded) continue;
            boolean fresh = window > 0 && box.known() && box.readAt > 0 && now - box.readAt < window;
            box.stale = !fresh;
        }
    }

    /**
     * Keeps the stash's box for the delivery container in step with the setting: flagged and excluded (never sorted into, read
     * or taken from), created if there is none, and the flag taken off a box that is not the delivery container any more.
     * @return the delivery box, or null when none is set (or it is in another dimension)
     */
    // The sync is made once per tick (and again when the delivery container or the stash changes)
    private long syncedTick = -1, syncedVersion = -1;
    private @Nullable Box syncedBox;
    private @Nullable Stash syncedStash;

    private @Nullable Box syncDeliveryBox() {
        if (syncedTick == tickCount && syncedVersion == Stashes.version && syncedStash == stash) return syncedBox;
        Box found = syncDeliveryBoxNow();
        syncedTick = tickCount;
        syncedVersion = Stashes.version;
        syncedStash = stash;
        syncedBox = found;
        return found;
    }

    private @Nullable Box syncDeliveryBoxNow() {
        Stash st = stash;
        if (st == null) return null;
        DeliverySpot.Spot spot = DeliverySpot.get();
        if (spot != null && !DeliverySpot.here(spot)) return null;
        BlockPos want = spot == null ? null : spot.pos();
        DeliveryBook book = new DeliveryBook(st.deliveryStorage);

        Box found = null;
        for (Box b : st.boxes) if (want != null && b.has(want)) found = b;
        if (want == null) {
            int dropped = 0;
            for (Box b : st.boxes) if (b.delivery) {
                if (b.deliveryExtra) dropped++;
                unflagDelivery(st, b);
            }
            warnExtrasDropped(dropped);
            if (st.deliveryStorage != null && !st.deliveryStorage.isEmpty()) {
                book.syncPrimary(null);
                st.markDirty();
            }
            return null;
        }
        if (found == null) {
            found = Containers.isLoaded(want) && Containers.isContainer(want, true) ? Containers.makeBox(want) : new Box(want, new ArrayList<>(List.of(want)));
            st.boxes.add(found);
            st.markDirty();
        }
        if (!found.delivery || !found.excluded || found.dropOff || found.deliveryExtra) {
            found.delivery = true;
            found.deliveryExtra = false;
            found.excluded = true;
            found.dropOff = false;
            found.noCompact = true;
            st.markDirty();
        }

        // The delivery storage follows the container (1.9.0): a different container forgets the chests put up above the old one
        int before = st.deliveryStorage.size();
        List<DeliveryBook.Pos> parts = new ArrayList<>();
        for (BlockPos p : found.parts) parts.add(dpos(p));
        book.syncPrimary(parts);
        // A double chest that was put in twice (or grew into one) is one chest
        if (book.dedupe()) st.markDirty();
        if (st.deliveryStorage.size() != before) st.markDirty();

        Set<Box> mine = new HashSet<>();
        mine.add(found);
        for (DeliveryBook.Entry e : book.sorted()) {
            if (e.primary) continue;
            BlockPos bp = bpos(e.first());
            Box eb = st.boxAt(bp);
            // A chest that is gone (broken by a player) leaves the storage
            if (Containers.isLoaded(bp) && !Containers.isContainer(bp, false)) {
                book.remove(e);
                if (eb != null) st.boxes.remove(eb);
                st.markDirty();
                continue;
            }
            if (eb == null) {
                eb = Containers.isLoaded(bp) ? Containers.makeBox(bp) : new Box(bp, new ArrayList<>(List.of(bp)));
                st.boxes.add(eb);
                st.markDirty();
            }
            // A single chest that became a double (or half of one broken): the halves are what the world says
            if (Containers.isLoaded(bp)) {
                Box fresh = Containers.makeBox(bp);
                if (fresh.parts.size() != eb.parts.size() || !fresh.parts.containsAll(eb.parts)) {
                    eb.parts = fresh.parts;
                    eb.contents = null;
                    List<DeliveryBook.Pos> p2 = new ArrayList<>();
                    for (BlockPos p : fresh.parts) p2.add(dpos(p));
                    e.at = new DeliveryBook(new ArrayList<>()).add(p2).at;
                    st.markDirty();
                }
            }
            if (!eb.delivery || !eb.deliveryExtra || !eb.excluded || eb.dropOff) {
                eb.delivery = true;
                eb.deliveryExtra = true;
                eb.excluded = true;
                eb.dropOff = false;
                eb.noCompact = true;
                st.markDirty();
            }
            mine.add(eb);
        }
        int dropped = 0;
        for (Box b : new ArrayList<>(st.boxes)) {
            if (!b.delivery || mine.contains(b)) continue;
            // The same chest as one of the storage's (a duplicate box): gone; any other is an ordinary container again
            boolean twin = false;
            if (b.deliveryExtra) for (Box m : mine) for (BlockPos p : b.parts) if (m.has(p)) twin = true;
            if (twin) {
                st.boxes.remove(b);
                st.markDirty();
                continue;
            }
            if (b.deliveryExtra) dropped++;
            unflagDelivery(st, b);
        }
        warnExtrasDropped(dropped);
        return found;
    }

    private void warnExtrasDropped(int n) {
        if (n <= 0) return;
        String text = n + " extra delivery chest" + (n == 1 ? " is" : "s are") + " now ordinary stash containers; their contents will be sorted.";
        alert(text);
    }

    private static void unflagDelivery(Stash st, Box b) {
        // A box made only to stand for the delivery container, never read, is not an ordinary container of the stash
        if (Stash.neverRead(b)) {
            st.boxes.remove(b);
            st.markDirty();
            return;
        }
        b.delivery = false;
        b.deliveryExtra = false;
        b.excluded = false;
        b.noCompact = false;
        st.markDirty();
    }

    private static List<BinTracker.Held> heldOf(List<Item> items) {
        List<BinTracker.Held> out = new ArrayList<>();
        int at = 0;
        for (Item item : items) {
            int fallback = at++;
            if (item == null) continue;
            out.add(new BinTracker.Held(item.slot >= 0 ? item.slot : fallback, item.indexKey(), item.variant, item.count));
        }
        return out;
    }

    /**
     * What the bin's own shulker holds once it was given up on and picked up again, or null when it can't be told for sure
     * (see {@link BinStep#ownShulker}): only then is the bin delivered short.
     */
    private @Nullable List<BinTracker.Held> partialBin(Bin bin) {
        List<BinStep.Carried> carried = new ArrayList<>();
        for (int slot : carriedSlots()) {
            Item it = inventoryItem(slot);
            if (it == null || !it.isShulker()) continue;
            carried.add(new BinStep.Carried(it.id, it.customName, sourceShulkers.containsKey(slot), heldOf(it.contents)));
        }
        return BinStep.ownShulker(bin.lots, bin.packed, bin.startedEmpty, bin.shulkerId, carried);
    }

    /** The carried stacks that could be part of the bin. */
    private List<BinTracker.Held> carriedHeld(Bin bin) {
        List<BinTracker.Held> out = new ArrayList<>();
        // An order's craft gathers its inputs fresh: the crafted loads and surplus already carried are not what it is gathering
        // The same for an enchant item's gather: a base the order crafted (ENCHANT-CONTRACT draft 3.4) is not the base it gathers from the stash
        EnchantRun gathering = enchantRun;
        Set<Integer> aside = (craft != null && craft.oc != null && bin == craft.bin) || (gathering != null && bin == gathering.bin) ? orderCraftHeld() : Set.of();
        // The bases made for enchant plans (draft 3.4) are never packed into any bin: a plain stack of the same id in the bin's own lots must not take one
        List<Integer> bases = baseSlots();
        if (!bases.isEmpty()) {
            aside = new HashSet<>(aside);
            aside.addAll(bases);
        }
        for (int slot : carriedSlots()) {
            Item it = inventoryItem(slot);
            if (it == null || it.isShulker() || aside.contains(slot)) continue;
            if (BinTracker.owns(bin.lots, it.indexKey(), it.variant, it.count)) out.add(new BinTracker.Held(slot, it.indexKey(), it.variant, it.count));
        }
        return out;
    }

    /**
     * What the sorter knows now about each container, for {@link SourceRules}: the one rule the order's plan, its replan and
     * the run all take sources by.
     */
    private final SourceRules.Live sourceLive = new SourceRules.Live() {
        @Override
        public @Nullable String skip(Box box) {
            return orders.skipReason(box);
        }

        @Override
        public boolean signPending(Box box) {
            return StashSorter.this.signPending(box);
        }

        @Override
        public boolean blockedGather(Box box) {
            return blocked(box, Job.Gather);
        }

        @Override
        public boolean blockedFetch(Box box) {
            return blocked(box, Job.Fetch);
        }

        @Override
        public boolean badUnpack(Box box) {
            return badUnpack.contains(box.pos);
        }
    };

    /** A container an order may take loose stacks from now. */
    private boolean binSource(Box b) {
        return SourceRules.loose(b, sourceLive) == null;
    }

    /**
     * An order is claimed or a shulker of it planned again: what was given up on for now is forgotten, so the plan is made
     * from the sources the run will really use, and a replan can change something. Each of these only ever saves a trip
     * that looked pointless; a trip that really is pointless puts it back after one more try:
     * stalled (a job changed nothing, two unconfirmed runs, going in circles; signHeld stays in it), futile (opened, clicked
     * nothing, nothing changed since), circular and the visit counts of the circle watchdog, unconfirmedRuns, and badUnpack
     * (a source shulker that couldn't be taken). Kept: signHeld (a sign being edited), failedSurvey and unreachable (reads and
     * walks that failed), the compaction cooldowns.
     */
    private void clearOrderBlocks() {
        int was = stalled.size() + futile.size() + badUnpack.size();
        clearStalled();
        circular.clear();
        boxVisits.clear();
        futile.clear();
        unconfirmedRuns.clear();
        badUnpack.clear();
        if (was > 0) debug("Order planning: forgot {} containers and shulkers given up on for now.", was);
    }

    private static boolean looseStack(@Nullable Item it) {
        return SourceRules.looseStack(it);
    }

    /** The nearest container with a stack for the next load the bin needs. */
    private @Nullable Box binSourceBox(CompactPlan plan) {
        Bin bin = plan.bin;
        BinTracker.Need need = needOf(bin);
        if (need == null) return null;
        String variant = need.topUpSlot() >= 0 && inventoryItem(need.topUpSlot()) != null ? inventoryItem(need.topUpSlot()).variant : null;
        return nearest(stash.boxes.stream().filter(b -> binSource(b) && hasStackFor(b, need.lot().key(), variant)).toList());
    }

    private static boolean hasStackFor(Box box, String key, @Nullable String variant) {
        for (Item it : box.contents) {
            if (!looseStack(it) || !it.indexKey().equals(key)) continue;
            if (variant == null || variant.equals(it.variant)) return true;
        }
        return false;
    }

    /**
     * Takes (part of) the next load the bin needs out of the open container into a carry slot, with the fewest clicks that
     * leave exactly that many (the stack in the chest keeps the rest), judged by the stacks as the window shows them now.
     */
    private boolean gatherBinOne(Container c, CompactPlan plan) {
        return takeLot(c, plan.bin, target.pos, true);
    }

    /** Takes the next load out of the open shulker (a source shulker placed in the world), keeping one carry slot free. */
    private boolean takeFromShulker(Container c) {
        SourceShulker s = unpackingNow;
        if (s != null && s.emptyAll) return takeEverything(c);
        // The bin being built, or the craft being gathered
        Bin bin = activeBin != null ? activeBin : gatheringBin();
        if (s == null || bin == null || freeCarryCount() <= 1) return false;
        return takeLot(c, bin, s.origin, false);
    }

    /** Takes the next stack out of the open shulker into the inventory, keeping one carry slot free (a kit being emptied back). */
    private boolean takeEverything(Container c) {
        if (freeCarryCount() <= 1) return false;
        for (int i = 0; i < 27; i++) {
            if (Item.of(c.getItemStack(i)) == null) continue;
            shiftClick(c, toWindow(i, c));
            moved++;
            return true;
        }
        return false;
    }

    private boolean takeLot(Container c, Bin bin, @Nullable BlockPos origin, boolean counted) {
        BinTracker.Need need = needOf(bin);
        if (need == null) return false;
        int into = need.topUpSlot() >= 0 ? need.topUpSlot() : freeCarrySlot();
        if (into == -1) return false;
        Order.Lot lot = need.lot();
        int want = lot.count() - need.have();
        Item partial = need.topUpSlot() >= 0 ? inventoryItem(need.topUpSlot()) : null;

        // The stack to take from: an identical variant with exactly enough, or the smallest with more, else the largest short
        // one. A stack of another variant of the item is used only when there is none of the wanted one (the variant seen when
        // the order was planned can differ from the live one); the load then follows the stack it came from
        int bestSlot = -1;
        Item best = null;
        long bestScore = Long.MAX_VALUE;
        for (int i = 0; i < Stashes.slots(c); i++) {
            Item it = Item.of(c.getItemStack(i));
            if (!looseStack(it) || !it.indexKey().equals(lot.key())) continue;
            if (partial != null && !Objects.equals(partial.variant, it.variant)) continue;
            boolean same = lot.variant() == null || lot.variant().equals(it.variant);
            long score = (same ? 0 : 1_000_000L) + (it.count >= want ? it.count - want : 10_000L + (it.maxStack - it.count));
            if (score < bestScore) {
                bestScore = score;
                best = it;
                bestSlot = i;
            }
        }
        if (best == null) return false;

        int take = Math.min(want, best.count);
        int goal = (need.have()) + take;
        ClickPlanner.Clicks clicks = ClickPlanner.plan(Math.max(1, best.maxStack), best.count, need.have(), goal);
        if (clicks == null || clicks.ops().length == 0) return false;

        if (lot.variant() == null || !lot.variant().equals(best.variant)) bin.lots.set(need.index(), lot.withVariant(best.variant));
        int carryWindow = toWindow(into, c);
        ClickItem[] actions = new ClickItem[clicks.ops().length];
        for (int i = 0; i < actions.length; i++) {
            int op = clicks.ops()[i];
            actions[i] = new ClickItem(c.getContainerId(), op < 2 ? bestSlot : carryWindow, op % 2 == 1 ? ClickItemAction.RIGHT_CLICK : ClickItemAction.LEFT_CLICK);
        }
        submit(actions);
        stashHeld.put(into, best.id);
        if (origin != null) orderSources.put(into, new OrderSource(best.id, origin));
        if (counted && goal >= lot.count()) gatherLeft--;
        return true;
    }

    /** Puts one carried stack that completes a load into the open shulker. A stack in there that is not the order's: the bin is given up. */
    private boolean packBinOne(Container c, CompactPlan plan) {
        Bin bin = plan.bin;
        List<BinTracker.Held> inside = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            Item here = Item.of(c.getItemStack(i));
            if (here == null) continue;
            if (!BinTracker.owns(bin.lots, here.indexKey(), here.variant, here.count)) {
                warn("The shulker I'm filling holds something that isn't part of the order, not adding to it.");
                abortCompaction("the shulker holds something else");
                return false;
            }
            inside.add(new BinTracker.Held(i, here.indexKey(), here.variant, here.count));
        }
        bin.packed = inside;
        List<BinTracker.Held> ready = BinTracker.ready(bin.lots, inside, carriedHeld(bin));
        if (ready.isEmpty()) return false;
        shiftClick(c, toWindow(ready.get(0).slot(), c));
        moved++;
        return true;
    }

    private void afterPackingBin(Container c, CompactPlan plan) {
        Bin bin = plan.bin;
        List<BinTracker.Held> inside = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            Item here = Item.of(c.getItemStack(i));
            if (here != null) inside.add(new BinTracker.Held(i, here.indexKey(), here.variant, here.count));
        }
        bin.packed = inside;
        plan.placedSize = inside.size();
        // What is carried next is new stacks: they are looked at for repair again
        repairChecked.clear();

        // Stacks are waiting and nothing went in: it is not going to, so don't come back for it
        int waiting = BinTracker.ready(bin.lots, inside, carriedHeld(bin)).size();
        if (actions == 0 && waiting > 0) {
            abortCompaction("nothing could be packed");
            handle = Handle.Break;
            handleTicks = 0;
            wait = CONFIG.delayTicks;
            return;
        }

        // Loads left out for want of a source don't count as more to come
        int open = BinTracker.notPacked(bin.lots, inside).size() - BinTracker.skipped(bin.lots, inside, carriedHeld(bin), bin.unavailable);
        boolean more = !plan.aborting && !plan.finishOnly && open > 0 && (binSourceBox(plan) != null || findUnpackSource(plan.bin) != null) && freeCarryCount() >= 2;
        handle = more ? Handle.Leave : Handle.Break;
        handleTicks = 0;
        wait = CONFIG.delayTicks;
        if (more) plan.placedPos = placed;
        syncInFlight();
    }

    // Putting the finished shulker in the delivery container

    private boolean deliverOne(Container c) {
        if (orders.recheck) {
            recheckRead(c);
            return false;
        }
        Unit u = orders.flow.current();
        if (u == null) return false;
        int slots = Stashes.slots(c);
        int count = 0;
        boolean room = false;
        for (int i = 0; i < slots; i++) {
            Item here = Item.of(c.getItemStack(i));
            if (here == null) room = true;
            else if (orders.matches(u, here)) count++;
        }
        // What it held before anything went in: a later look at it (the shulker has left the inventory) is judged by this
        if (!orders.baseTaken) {
            if (!u.verifying) {
                u.base = count;
                // Which chest of the storage it goes into, saved with the plan so a restart looks in the right one
                orders.lastDeliveryBox = target;
                OrderFlow.Saved saved = orders.saved(String.valueOf(orders.flow.orderId()));
                if (saved != null && target != null) saved.deliveringAt = new int[] {target.pos.x(), target.pos.y(), target.pos.z()};
                orders.flow.deliveryStarted(u);
            }
            orders.baseTaken = true;
        }
        if (u.have && count > u.base) {
            orders.flow.confirmed();
            return false;
        }
        if (u.verifying) return false;

        int slot = orders.finishedSlot(u);
        if (slot == -1) return false;
        if (!room) {
            deliveryChestFull(target);
            orders.flow.deliveryFull();
            return false;
        }
        shiftClick(c, toWindow(slot, c));
        moved++;
        return true;
    }

    // Taking items out of shulkers in the stash

    private boolean holds(Item shulker, String key) {
        for (Item in : shulker.contents) if (looseStack(in) && in.indexKey().equals(key)) return true;
        return false;
    }

    /** The nearest shulker in the stash with a stack for the next load of the bin, as ranked by {@link SourcePicker}. */
    private @Nullable Found findUnpackSource(Bin bin) {
        BinTracker.Need need = needOf(bin);
        if (need == null) return null;
        String key = need.lot().key();
        int want = need.lot().count() - need.have();
        Set<String> reserved = orders.reservedShulkerKeys();
        List<SourcePicker.Cand> cands = new ArrayList<>();
        List<Found> found = new ArrayList<>();
        for (Box box : stash.boxes) {
            if (SourceRules.unpack(box, sourceLive) != null) continue;
            for (int i = 0; i < box.contents.length; i++) {
                Item s = box.contents[i];
                if (s == null || !s.isShulker() || (s.customName && !CONFIG.useNamedShulkers) || !holds(s, key)) continue;
                // A repair pass opens only the boxes it chose: unnamed, not a kit, not opened lately, within the box cap
                Predicate<Item> only = unpackFilter;
                if (only != null && !only.test(s)) continue;
                if (reserved.contains(ManagerIndex.shulkerKey(s))) continue;
                int count = 0;
                boolean same = false;
                for (Item in : s.contents) {
                    if (!looseStack(in) || !in.indexKey().equals(key)) continue;
                    count = Math.max(count, in.count);
                    if (need.lot().variant() == null || need.lot().variant().equals(in.variant)) same = true;
                }
                cands.add(new SourcePicker.Cand(found.size(), true, same, count, distSq(playerFeet(), nearestPart(box))));
                found.add(new Found(box, i));
            }
        }
        int pick = SourcePicker.pick(cands, want);
        return pick < 0 ? null : found.get(pick);
    }

    /** Starts taking the next load out of a shulker: one already carried, else one fetched from the stash. */
    private boolean startUnpack(CompactPlan plan) {
        Bin bin = plan.bin;
        BinTracker.Need need = needOf(bin);
        if (need == null) return false;
        String key = need.lot().key();
        for (var e : sourceShulkers.entrySet()) {
            Item held = inventoryItem(e.getKey());
            if (!e.getValue().opened && held != null && holds(held, key)) {
                unpackingNow = null;
                startHandling(Job.Clean, e.getKey(), null);
                unpackingNow = e.getValue();
                step("order: taking items out of a shulker; next: put it back where it came from.");
                return true;
            }
        }
        Found f = findUnpackSource(bin);
        if (f == null) return false;
        unpackFetchOrigin = f.box().pos;
        wantSlot = f.slot();
        fetchWants = it -> it.isShulker() && holds(it, key);
        step("order: fetching a shulker that holds the items; next: take them out of it.");
        return start(Job.Fetch, f.box());
    }

    /** Whether a shulker (carried and not opened yet, or in the stash) holds the next load of the bin. */
    private boolean hasUnpackSource(Bin bin) {
        BinTracker.Need need = needOf(bin);
        if (need == null) return false;
        String key = need.lot().key();
        for (var e : sourceShulkers.entrySet()) {
            Item held = inventoryItem(e.getKey());
            if (!e.getValue().opened && held != null && holds(held, key)) return true;
        }
        return findUnpackSource(bin) != null;
    }

    /** The next load of this bin to fetch, the loads left out for want of a source skipped. */
    private BinTracker.@Nullable Need needOf(Bin bin) {
        return BinTracker.nextNeed(bin.lots, bin.packed, carriedHeld(bin), bin.unavailable);
    }

    /**
     * The next load of the bin has no source (1.8.0; until then the whole bin was given up and went out short with what it
     * had). A container turned down only because the sorter itself gave up on it for now (blocked, a source shulker that
     * couldn't be taken) is looked at again once per order plan first. Otherwise the load is left out, said with why, and
     * the bin goes on with the rest. @return true when the bin can go on (look again), false when there was no load to skip
     */
    private boolean skipLoad(Bin bin) {
        BinTracker.Need need = needOf(bin);
        if (need == null) return false;
        String key = need.lot().key();
        var rejected = SourceRules.rejected(stash.boxes, key, orders.reservedShulkerKeys(), CONFIG.useNamedShulkers, sourceLive);
        if (BinStep.missing(rejected, bin.lookedAgain) == BinStep.Missing.LOOK_AGAIN) {
            bin.lookedAgain = true;
            clearOrderBlocks();
            step("order: the containers holding " + key + " were only given up on for now (" + SourceRules.describe(rejected) + "); looking at them again (once for this shulker).");
            return true;
        }
        bin.unavailable.add(key);
        step("order: no source for " + key + " x" + (need.lot().count() - need.have()) + " (containers holding it turned down: " + SourceRules.describe(rejected) + "); leaving it out and packing the rest.");
        return true;
    }

    /** What a bin abort for want of sources says: the next load, and the containers holding it that were turned down, by why. */
    private @Nullable String noSourceDetail(Bin bin) {
        BinTracker.Need need = needOf(bin);
        if (need == null) return null;
        String key = need.lot().key();
        var rejected = SourceRules.rejected(stash.boxes, key, orders.reservedShulkerKeys(), CONFIG.useNamedShulkers, sourceLive);
        return "next load " + key + " x" + (need.lot().count() - need.have()) + "; containers holding it turned down: " + SourceRules.describe(rejected);
    }

    private static final int MAX_ROOM_TRIPS = 3;

    /** A source shulker carried for the bin in hand that has not been opened yet: not put away to make room. */
    private boolean pendingSourceShulker(int slot) {
        SourceShulker s = sourceShulkers.get(slot);
        return s != null && !s.opened && ((compact != null && compact.bin != null) || gatheringBin() != null);
    }

    /** Carried stacks that are not part of the bin in hand and may be put away to make carry room. */
    private List<Integer> roomCandidates() {
        List<Integer> out = new ArrayList<>();
        for (int slot : looseCarried()) if (inventoryItem(slot) != null && !pendingSourceShulker(slot)) out.add(slot);
        return out;
    }

    /** Where carried stacks go to make room: where the first came from if it has room, else the nearest container with room. */
    private @Nullable Box roomHome(List<Integer> slots) {
        if (slots.isEmpty()) return null;
        OrderSource first = orderSources.get(slots.get(0));
        Box from = first == null ? null : stash.boxAt(first.pos());
        if (from != null && from.sortable() && !from.dropOff && !from.delivery && from.free() > 0 && !blocked(from, Job.Dump) && !signPending(from) && originAllowed(from, slots)) return from;
        return nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !b.delivery && b.free() > 0 && !blocked(b, Job.Dump) && !signPending(b) && !kitReserved(b)).toList());
    }

    private boolean canMakeRoom(CompactPlan plan) {
        return plan.bin != null && plan.roomTrips < MAX_ROOM_TRIPS && roomHome(roomCandidates()) != null;
    }

    /** Puts away what is carried and not part of the bin, so the next load (or the source shulker) can be picked up. */
    private boolean makeRoom(CompactPlan plan) {
        List<Integer> slots = roomCandidates();
        Box home = roomHome(slots);
        if (home == null) return false;
        plan.roomTrips++;
        plan.jobs++;
        step("order: putting away " + slots.size() + " carried stacks that aren't part of the shulker (" + freeCarryCount() + " free carry slots); next: carry on with the shulker.");
        putBackTrip = true;
        return start(Job.Dump, home);
    }

    /**
     * Where a source shulker goes back to: where it came from, unless it doesn't belong there now (emptied of what made it
     * belong, say an empty shulker in a chest signed for totems: the sorter would take it straight back out, and that
     * container would end up given up on for going in circles). Then where the sorter would put it, and failing that the
     * nearest container with room.
     */
    private @Nullable Box spentShulkerHome(SourceShulker s, @Nullable Item shulker) {
        Box home = stash.boxAt(s.origin);
        boolean inChain = home != null && kitChain(shulker).contains(home);
        boolean usable = home != null && home.sortable() && !home.dropOff && !home.delivery && home.free() > 0 && !blocked(home, Job.Dump)
            && (!kitReserved(home) || inChain);
        // A kit shulker is not out of place in one of the reserved chests of its chain, whatever the signs would say
        boolean misplacedThere = usable && shulker != null && !inChain && misplaced(shulker, home);
        if (usable && !misplacedThere) return home;
        Box belongs = shulker == null ? null
            : nearest(destinationsFor(shulker).stream().filter(b -> b.sortable() && !b.dropOff && !b.delivery && b.free() > 0 && !blocked(b, Job.Dump) && !signPending(b)).toList());
        Box other = belongs != null ? null : nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !b.delivery && b.free() > 0 && !blocked(b, Job.Dump) && !signPending(b) && !kitReserved(b)
            && (shulker == null || !misplaced(shulker, b))).toList());
        return BinStep.spentHome(home, usable, misplacedThere, belongs, other);
    }

    // The Dump in hand puts an order's own things back (a source shulker, leftovers): it never counts against the container,
    // unless two such trips to it put nothing in (then it is stalled; after two of those, leftovers stay carried)
    private boolean putBackTrip;
    private final Map<Box, Integer> putBackNoOps = new HashMap<>();
    private int putBackGiveUps;

    /** A source shulker that was emptied of what the order needed (or never could be) goes back to its container, or one with room. */
    private boolean returnSpentShulker() {
        for (var e : sourceShulkers.entrySet()) {
            SourceShulker s = e.getValue();
            if (!(s.opened || (compact == null && gatheringBin() == null)) || s.tries >= 3) continue;
            s.tries++;
            Box home = spentShulkerHome(s, inventoryItem(e.getKey()));
            if (home == null) {
                s.tries = 3;
                alert("No container with room for a shulker I took items out of, it stays in the bot's inventory.");
                continue;
            }
            step("order: putting a shulker back.");
            putBackTrip = true;
            return start(Job.Dump, home);
        }
        return false;
    }

    // Kits that could not be completed or stored (KIT-CONTRACT v2 revision 4, step 4): the shulker is emptied back, its items put back with the rest
    private final List<Unit> kitEmptyQueue = new ArrayList<>();
    private static final BlockPos NOWHERE = new BlockPos(0, -1000, 0);

    private boolean startKitEmpty() {
        // Putting a shulker down waits while shulker handling is paused
        if (compact != null || kitEmptyQueue.isEmpty() || shulkerPause() != null) return false;
        Unit u = kitEmptyQueue.remove(0);
        // Only an unnamed shulker of that unit: a kit stored as an incomplete copy (named) is never taken apart
        int slot = com.porter.qwertestash.orders.KitEmpty.pick(u, carriedSlots(), this::inventoryItem, sourceShulkers.keySet());
        if (slot < 0) return false;
        SourceShulker src = new SourceShulker(NOWHERE);
        src.emptyAll = true;
        sourceShulkers.put(slot, src);
        unpackingNow = null;
        startHandling(Job.Clean, slot, null);
        unpackingNow = src;
        step("kit: emptying a kit that could not be completed; next: put its items and the shulker back.");
        return true;
    }

    // The order step of the planner

    private boolean nextOrderJob() {
        OrderFlow flow = orders.flow;
        // A shulker left standing from before is finished first
        if (pendingResume != null) return false;
        syncDeliveryBox();

        // So is a compaction that was under way when the order was claimed (it ends or is given up on; nothing of the order
        // starts while its shulker may be standing in the world)
        if (compact != null && compact.bin == null) {
            if (continueCompaction()) return true;
            if (compact != null) return false;
        }

        // Source shulkers that were emptied of what the order needed go back where they came from before anything else
        if (handle == null) {
            sourceShulkers.entrySet().removeIf(e -> {
                Item held = inventoryItem(e.getKey());
                return held == null || !held.isShulker();
            });
            if (returnSpentShulker()) return true;
            if (startKitEmpty()) return true;
        }

        // The bin being built has the compaction machinery until its plan is over. A cancel stops it at once, but a shulker standing
        // in the world is still picked up first
        if (activeBin != null) {
            if (flow.cancelRequested() && compact != null && compact.bin == activeBin && !compact.aborting) abortCompaction("the order was cancelled");
            if (compact != null && compact.bin == activeBin && continueCompaction()) return true;
            // A repair began (or goes on) inside it: the bin is not over, the repair is gone on with next tick
            if (repairBegan || repair != null) {
                repairBegan = false;
                return false;
            }
            Bin bin = activeBin;
            activeBin = null;
            if (compact != null && compact.bin == bin) compact = null;
            if (bin.completed) flow.built(bin.unit);
            else {
                String why = bin.failure == null ? "the shulker was not finished" : bin.failure;
                // A pause in shulker handling that began mid-build passes: the bin is tried again once it is over
                boolean retryable = com.porter.qwertestash.orders.OrderBlocker.retryableBuildFailure(bin.failure);
                // A shulker that holds part of the bin goes out as it is (short) instead of being put away
                List<BinTracker.Held> part = retryable || flow.cancelRequested() ? null : partialBin(bin);
                if (part != null) {
                    int planned = bin.unit.lots.size();
                    flow.builtShort(bin.unit, BinStep.lotsOf(part), why);
                    step("order: shulker " + (flow.index() + 1) + "/" + flow.units().size() + " goes out short, " + part.size() + " of " + planned
                        + " stacks: " + why + "; next: deliver it.");
                }
                else flow.buildFailed(bin.unit, why, retryable);
            }
        }

        // Over (or never started), even while the server has not been told the end yet: what is carried goes back
        if (!flow.running()) return returnLeftovers();

        OrderFlow.Intent intent = flow.next();
        if (intent instanceof OrderFlow.Build b) return startBin(b.unit());
        if (intent instanceof OrderFlow.Fetch f) return startFetch(f.unit());
        if (intent instanceof OrderFlow.Name n) return startName(n);
        if (intent instanceof OrderFlow.Craft c) return startOrderCraft(c);
        if (intent instanceof OrderFlow.CraftLoads cl) return startOrderCraftLoads(cl);
        if (intent instanceof OrderFlow.EnchantLoads el) return startEnchant(el);
        if (intent instanceof OrderFlow.Deliver d) return startDeliver(d);
        if (intent instanceof OrderFlow.Expand x) return startExpand(x);
        if (intent instanceof OrderFlow.Recheck rc) return startRecheck(rc);
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Repairing an order's items with experience bottles (1.13.0, REPAIR-CONTRACT.md). When the carried stacks of a bin are about
    // to go into the shulker, the ones the order wants repaired (and that are Mending, damaged, single) are repaired first:
    // bottles are taken from the stash (Job.Gather, the repair's own bin of bottle loads), the steps in the world are
    // RepairTrip's (Job.Repair: its view of the bot is repairPort), leftover bottles go back (Job.Dump). The decisions are Repair,
    // RepairRun and RepairTrip's (plain Java, tested); this only runs them on the bot tick thread. A repaired item has another key
    // (its damage is part of it): its lot in the bin takes the new key (OrderFlow.rekey) so it still matches and is packed.
    // Repairing never fails or holds up an order: whatever goes wrong, the items go out as they are.

    private enum RepairPhase { Bottles, Trip, Wait, PutBack }

    /** A carried item being repaired: the key and variant its lot in the bin has now, and its window slot. */
    private static final class RepairCand {
        String key;
        @Nullable String variant;
        int slot;

        RepairCand(String key, @Nullable String variant, int slot) {
            this.key = key;
            this.variant = variant;
            this.slot = slot;
        }
    }

    private static final class RepairCtl {
        final Bin bin;
        final RepairRun run;
        final Map<RepairRun.Item, RepairCand> cands = new IdentityHashMap<>();
        RepairPhase phase = RepairPhase.Bottles;
        @Nullable Bin bottles;
        int jobs, putBackTries;
        long waitSince, quietSince;
        boolean leaving, undoTried, slotsChecked;
        /** A repair pass's throw (1.17.0): the pass, the bottles it may take (-1: as the order needs) and the candidate each item is. */
        @Nullable PassRun pass;
        int bottleCap = -1;
        final Map<RepairRun.Item, RepairRules.Cand> passCands = new IdentityHashMap<>();

        RepairCtl(Bin bin, RepairRun run) {
            this.bin = bin;
            this.run = run;
        }
    }

    private static final int REPAIR_INPUT_PRIORITY = 1;
    /** Ticks to wait for a player to leave before the second go, at most, and quiet needed before it. */
    private static final long REPAIR_WAIT_TICKS = 20 * 60L, REPAIR_QUIET_TICKS = 20 * 5L;

    private @Nullable RepairCtl repair;
    // The bottle loads being gathered: gatherOne takes from this one while it is set
    private @Nullable Bin repairBottles;
    private @Nullable RepairTrip repairTrip;
    // The trip in hand only undoes a saved ledger (a restart in the middle of a trip)
    private boolean repairUndoMode;
    // A repair began inside continueCompaction: its false is not "the bin is over" (see nextOrderJob)
    private boolean repairBegan;
    // Carried stacks already looked at for repair since the last pack (slot and variant), the order they were for, and the throwing
    // ticks it has used
    private final Set<String> repairChecked = new HashSet<>();
    private @Nullable String repairOrderId;
    // A foreign module took over while the trip ran: it is told to put everything back, and does so even while giving way
    private boolean repairYieldAbort;

    /** The item id in a player-inventory window slot, or null. */
    private @Nullable String windowId(int window) {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        Item it = window >= 0 && window < inv.size() ? Item.of(inv.get(window)) : null;
        return it == null ? null : it.id;
    }

    private static int windowOf(int index) {
        return invSlot(index);
    }

    private static int indexOf(int window) {
        return window >= 36 ? window - 36 : window;
    }

    /** What the order asked for under this key: the one before any repair changed it. */
    private static String askedKey(Unit unit, String key) {
        for (String[] r : unit.rekeys) if (r[0].equals(key)) return r[1];
        return key;
    }

    private int carriedBottles() {
        int n = 0;
        for (int slot : carriedSlots()) {
            Item it = inventoryItem(slot);
            if (it != null && it.id.equals("experience_bottle")) n += it.count;
        }
        return n;
    }

    /**
     * The bin's carried stacks are about to be packed: those wanted for repair are looked at, and a repair is begun for the ones
     * that can be. @return true when a job was started; false when nothing is to be repaired, or the repair is waiting (then
     * {@link #repairBegan} is set)
     */
    private boolean beginRepair(CompactPlan plan) {
        OrderFlow flow = orders.flow;
        Repair.Report report = flow.repairReport();
        Set<String> wanted = flow.repairKeys();
        Set<String> explicit = flow.repairExplicitKeys();
        repairBegan = false;
        if (plan.bin == null || plan.aborting || report == null || wanted.isEmpty() || repair != null || repairTrip != null) return false;
        String id = flow.orderId();
        if (id != null && !id.equals(repairOrderId)) {
            repairOrderId = id;
            repairChecked.clear();
        }
        Bin bin = plan.bin;
        List<RepairRun.Item> items = new ArrayList<>();
        List<RepairCand> cands = new ArrayList<>();
        boolean noted = false;
        for (BinTracker.Held h : BinTracker.ready(bin.lots, bin.packed, carriedHeld(bin))) {
            String asked = askedKey(bin.unit, h.key());
            if (!wanted.contains(asked)) continue;
            String sig = h.slot() + "|" + h.variant();
            if (repairChecked.contains(sig)) continue;
            Item it = inventoryItem(h.slot());
            if (it == null) continue;
            repairChecked.add(sig);
            // Under "repair all" only damaged lines are wanted (contract v1.2): the rest get no entry at all
            if (!Repair.wanted(explicit.contains(asked), it.damage)) continue;
            // Checked live: a shulker box, Mending, damaged and a single item; one entry for each item
            String why = Repair.skipReason(it.isShulker(), it.hasMending(), it.damage, it.maxStack);
            if (why != null) {
                for (int n = Math.max(1, it.count); n > 0; n--) report.skip(asked, why);
                noted = true;
                continue;
            }
            items.add(new RepairRun.Item(asked, windowOf(h.slot()), it.damage, it.maxDamage));
            cands.add(new RepairCand(h.key(), h.variant(), windowOf(h.slot())));
        }
        if (noted) flow.repairChanged();
        if (items.isEmpty()) return false;
        // The order's throwing time is used up: nothing is gathered or moved
        if (RepairRun.exhausted(report)) {
            for (RepairRun.Item i : items) report.skip(i.key, Repair.INTERRUPTED);
            flow.repairChanged();
            return false;
        }

        RepairCtl ctl = new RepairCtl(bin, new RepairRun(report, items));
        for (int i = 0; i < items.size(); i++) ctl.cands.put(items.get(i), cands.get(i));
        repair = ctl;
        info("Order: repairing {} items with experience bottles before they are packed.", items.size());
        step("order: repairing " + items.size() + " item" + (items.size() == 1 ? "" : "s") + " with experience bottles; next: take bottles from the stash.");
        boolean started = continueRepair();
        repairBegan = !started;
        return started;
    }

    /** The next job of the repair in hand. @return true when one was started; false when it waits or is over ({@link #repair} is null then) */
    private boolean continueRepair() {
        RepairCtl r = repair;
        if (r == null) return false;
        if (job != null) return true;
        if (!orders.gate()) return false;
        OrderFlow flow = orders.flow;
        // The order ended, was cancelled or is parked: nothing more is thrown; what was gathered goes back with the leftovers
        // A pass gives way at every safe point: an order, a player, a stop (it puts the bottles back and ends the throw)
        if (!r.leaving && r.pass != null && passYieldReason(r.pass) != null) {
            r.pass.yieldWhy = passYieldReason(r.pass);
            r.run.giveUp(Repair.INTERRUPTED);
            r.leaving = true;
            r.phase = RepairPhase.PutBack;
        }
        if (r.pass == null && !r.leaving && (!flow.running() || flow.cancelRequested() || flow.parkRequested())) {
            // A park is not an end: the run is dropped without a report and the order carries on from its saved state. Any other
            // end reports what was not done
            if (flow.parkRequested() || !flow.holdsOrder()) r.run.pending().clear();
            else r.run.giveUp(Repair.INTERRUPTED);
            r.leaving = true;
            r.phase = RepairPhase.PutBack;
        }
        switch (r.phase) {
            case Bottles -> {
                int need = r.run.bottlesNeeded();
                if (r.bottleCap >= 0) need = Math.min(need, r.bottleCap);
                int have = carriedBottles();
                if (have >= need || need == 0) {
                    r.phase = RepairPhase.Trip;
                    return continueRepair();
                }
                if (++r.jobs > 3 + (need + 63) / 64) {
                    // As many as could be got
                    r.phase = RepairPhase.Trip;
                    return continueRepair();
                }
                if (!r.slotsChecked) {
                    r.slotsChecked = true;
                    // Room for the gear that is stowed and the bottles, before anything is gathered or moved
                    int armour = 0;
                    for (int w = 5; w <= 8; w++) {
                        RepairTrip.Stack worn = repairPort.at(w);
                        if (worn != null && worn.mending() && worn.damage() > 0) armour++;
                    }
                    int slotsNeeded = Repair.slotsNeeded(armour, repairPort.at(45) != null, need - have);
                    if (repairPort.freeSlots().size() < slotsNeeded) {
                        r.run.giveUp(Repair.INTERRUPTED);
                        step("order: not enough free inventory slots (" + repairPort.freeSlots().size() + " of " + slotsNeeded + ") to put my gear aside for repairing, delivering the items as they are.");
                        return leaveRepair(r);
                    }
                }
                if (freeCarryCount() < 2) {
                    if (have > 0) {
                        r.phase = RepairPhase.Trip;
                        return continueRepair();
                    }
                    r.run.giveUp(Repair.INTERRUPTED);
                    step("order: no carry room for experience bottles, delivering the items as they are.");
                    return leaveRepair(r);
                }
                Bin bottles = r.bottles;
                if (bottles == null) {
                    bottles = new Bin(new Unit(Unit.Kind.Bin, "", new ArrayList<>(Order.expand(Repair.BOTTLE, need - have, 64))));
                    r.bottles = bottles;
                }
                Box source = binSourceFor(bottles);
                if (source == null) {
                    if (have > 0) {
                        r.phase = RepairPhase.Trip;
                        return continueRepair();
                    }
                    r.run.giveUp(Repair.NO_BOTTLES);
                    step("order: no experience bottles in the stash, delivering the items as they are.");
                    return leaveRepair(r);
                }
                repairBottles = bottles;
                gatherLeft = bottles.lots.size();
                step("order: taking " + (need - have) + " experience bottles from the stash to repair " + r.run.pending().size() + " items; next: throw them.");
                return start(Job.Gather, source);
            }
            case Trip -> {
                repairBottles = null;
                repairUndoMode = false;
                // Moves an earlier trip could not undo are put back first, and a new trip never overwrites them
                Stash st = orders.st();
                if (st != null && st.repairGear != null && !st.repairGear.isEmpty() && !r.undoTried) {
                    r.undoTried = true;
                    if (startGearUndo()) return true;
                }
                repairTrip = new RepairTrip(repairPort, r.run.targets(), r.run.budget()).seed(st == null ? List.of() : Repair.parseLedger(st.repairGear));
                startRepairJob("order: repairing " + r.run.pending().size() + " items with experience bottles; next: put the bottles left back.");
                return true;
            }
            case Wait -> {
                long now = tickCount;
                if (repairPlayerNear()) r.quietSince = 0;
                else if (r.quietSince == 0) r.quietSince = now;
                if (r.quietSince != 0 && now - r.quietSince >= REPAIR_QUIET_TICKS) {
                    r.phase = RepairPhase.Bottles;
                    r.jobs = 0;
                    r.bottles = null;
                    r.slotsChecked = false;
                    return continueRepair();
                }
                if (now - r.waitSince > REPAIR_WAIT_TICKS) {
                    r.run.giveUp(Repair.INTERRUPTED);
                    return leaveRepair(r);
                }
                return false;
            }
            case PutBack -> {
                if (r.putBackTries < 3) {
                    List<Integer> bottleSlots = new ArrayList<>();
                    int n = 0;
                    for (int slot : looseCarried()) {
                        Item it = inventoryItem(slot);
                        if (it != null && it.id.equals("experience_bottle")) {
                            bottleSlots.add(slot);
                            n += it.count;
                        }
                    }
                    if (!bottleSlots.isEmpty()) {
                        r.putBackTries++;
                        if (putBack(bottleSlots, "order: putting " + n + " experience bottles back")) return true;
                    }
                }
                return endRepair(r);
            }
        }
        return false;
    }

    /** The repair is over for good: the bottles still carried go back before it ends. */
    private boolean leaveRepair(RepairCtl r) {
        r.leaving = true;
        r.phase = RepairPhase.PutBack;
        return continueRepair();
    }

    private boolean endRepair(RepairCtl r) {
        if (r.pass != null) passThrowDone(r);
        repair = null;
        repairBottles = null;
        repairTrip = null;
        // The time spent repairing is not a bin that makes no headway
        r.bin.progressAt = System.currentTimeMillis();
        orders.flow.repairChanged();
        Repair.Report report = orders.flow.repairReport();
        if (report != null) info("Order: repairs so far: {} repaired, {} bottles thrown, {} not fully repaired.", report.repaired(), report.bottlesUsed(), report.notRepaired());
        return false;
    }

    private void startRepairJob(String line) {
        markJobStart();
        job = Job.Repair;
        target = null;
        actions = 0;
        tries = 0;
        stepTicks = 0;
        jobRunTicks = 0;
        step = Step.Working;
        resetJobState();
        // The bot throws where it stands: leaving the stash area is no reason to end it
        jobStartedInside = false;
        step(line);
    }

    private void tickRepair() {
        RepairTrip t = repairTrip;
        if (t == null) {
            job = null;
            step = Step.Walking;
            return;
        }
        t.tick();
        if (t.done()) endRepairTrip(t);
    }

    private void endRepairTrip(RepairTrip t) {
        repairTrip = null;
        repairYieldAbort = false;
        job = null;
        target = null;
        step = Step.Walking;
        wait = CONFIG.delayTicks;
        if (repairUndoMode) {
            repairUndoMode = false;
            if (!t.pending().isEmpty()) warn("A repair that was cut short left {} inventory moves undone; they are tried again at the next start.", t.pending().size());
            return;
        }
        RepairCtl r = repair;
        if (r == null) return;
        List<RepairRun.Item> before = new ArrayList<>(r.run.pending());
        boolean again = r.run.tripDone(t);
        debug(() -> "Repair trip over (" + t.cause() + "): " + t.bottlesUsed() + " bottles used, " + t.ticks() + " ticks.");

        // Each item has another key now: its lot takes it, so the stack is still the bin's; and it is the sorter's again
        for (int i = 0; i < before.size() && i < t.outcomes().size(); i++) {
            RepairCand cand = r.cands.get(before.get(i));
            if (cand == null) continue;
            RepairTrip.Outcome o = t.outcomes().get(i);
            Item now = inventoryItem(indexOf(o.slot()));
            if (now == null || now.isShulker()) continue;
            cand.slot = o.slot();
            if (!now.indexKey().equals(cand.key) || !Objects.equals(now.variant, cand.variant)) {
                if (orders.flow.rekey(r.bin.unit, cand.key, cand.variant, now.indexKey(), now.variant)) {
                    cand.key = now.indexKey();
                    cand.variant = now.variant;
                }
            }
            stashHeld.put(indexOf(o.slot()), now.id);
            repairChecked.add(indexOf(o.slot()) + "|" + now.variant);
        }
        orders.flow.repairChanged();

        if (again) {
            r.phase = RepairPhase.Wait;
            r.waitSince = tickCount;
            r.quietSince = 0;
            step("order: the repair was cut short (" + (t.cause() == RepairTrip.Cause.Player ? "someone came near" : "the orbs went") + "); next: try once more when it is quiet.");
        }
        else r.phase = RepairPhase.PutBack;
    }

    /** Another player (not a friend) is within the radius of where the bot stands. */
    private boolean repairPlayerNear() {
        return repairPlayerNear(pass != null);
    }

    /** {@code friendsCount}: a repair pass (orbs follow any player); an order's repair leaves friends out as before. */
    private boolean repairPlayerNear(boolean friendsCount) {
        try {
            double x = CACHE.getPlayerCache().getX(), z = CACHE.getPlayerCache().getZ();
            return (friendsCount ? groundItems().anyPlayerWithin(x, z, Repair.PLAYER_RADIUS) : groundItems().playerWithin(x, z, Repair.PLAYER_RADIUS)) != null;
        } catch (RuntimeException e) {
            // Not known: treated as someone near, the safe side
            return true;
        }
    }

    /** A saved ledger of moves a stopped repair left is undone first. @return true when the job to do it was started */
    private boolean startGearUndo() {
        Stash s = orders.st();
        if (s == null || s.repairGear == null || s.repairGear.isEmpty() || repairTrip != null) return false;
        if (!Alerts.online() || openContainerId() != 0 || holdingCursor() || CACHE.getPlayerCache().getPlayerInventory().size() < 46) return false;
        List<int[]> moves = Repair.parseLedger(s.repairGear);
        if (moves.isEmpty()) {
            s.repairGear = null;
            s.markDirty();
            s.save();
            return false;
        }
        repairUndoMode = true;
        repairTrip = RepairTrip.undo(repairPort, moves);
        info("Putting back the gear and items a repair moved before the bot stopped.");
        startRepairJob("repair: putting back what a stopped repair moved; next: carry on.");
        return true;
    }

    private void resetRepair() {
        pass = null;
        unpackFilter = null;
        repair = null;
        repairBottles = null;
        repairTrip = null;
        repairUndoMode = false;
        repairBegan = false;
        repairChecked.clear();
        repairOrderId = null;
        repairYieldAbort = false;
    }

    /** The trip's view of the bot: the player inventory window, clicks through the inventory queue, the throw through the input queue. */
    private final RepairTrip.Port repairPort = new RepairTrip.Port() {
        @Override
        public boolean busy() {
            return StashSorter.this.busy() || openContainerId() != 0;
        }

        @Override
        public RepairTrip.@Nullable Stack at(int window) {
            List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
            if (window < 0 || window >= inv.size()) return null;
            Item it = Item.of(inv.get(window));
            return it == null ? null : new RepairTrip.Stack(it.id, it.count, it.damage, it.maxDamage, it.hasMending());
        }

        @Override
        public boolean move(int from, int to) {
            // Checked again here: a stale move never leaves a stack on the cursor
            if (holdingCursor() || windowId(from) == null || windowId(to) != null) return false;
            RequestFuture f = submit(new ClickItem(0, from, ClickItemAction.LEFT_CLICK), new ClickItem(0, to, ClickItemAction.LEFT_CLICK));
            return f != RequestFuture.rejected;
        }

        @Override
        public boolean select(int hotbar) {
            return submit(new SetHeldItem(hotbar)) != RequestFuture.rejected;
        }

        @Override
        public int held() {
            return CACHE.getPlayerCache().getHeldItemSlot();
        }

        @Override
        public boolean throwBottle() {
            // Looking straight down first: the bottle breaks at the bot's feet and the orbs come to it
            float pitch = com.aquarius.Globals.BOT.getPitch();
            float yaw = com.aquarius.Globals.BOT.getYaw();
            if (Math.abs(pitch - 90f) > 0.5f) {
                InputRequestFuture look = INPUTS.submit(InputRequest.builder().owner(StashSorter.this).yaw(yaw).pitch(90f).priority(REPAIR_INPUT_PRIORITY).build());
                activity.noteInput(look, tickCount);
                return false;
            }
            InputRequestFuture f = INPUTS.submit(InputRequest.builder().owner(StashSorter.this)
                .input(Input.builder().rightClick(true).hand(org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand.OFF_HAND)
                    .clickTarget(ClickTarget.None.INSTANCE).clickRequiresRotation(true).build())
                .yaw(yaw).pitch(90f).priority(REPAIR_INPUT_PRIORITY).build());
            activity.noteInput(f, tickCount);
            return f != InputRequestFuture.rejected;
        }

        @Override
        public boolean playerNear() {
            return repairPlayerNear();
        }

        @Override
        public int orbsNear() {
            double px = CACHE.getPlayerCache().getX(), py = CACHE.getPlayerCache().getY(), pz = CACHE.getPlayerCache().getZ();
            int n = 0;
            for (Entity e : CACHE.getEntityCache().getEntities().values()) {
                if (e == null || e.isRemoved() || e.getEntityType() != EntityType.EXPERIENCE_ORB) continue;
                double dx = e.getX() - px, dy = e.getY() - py, dz = e.getZ() - pz;
                if (dx * dx + dy * dy + dz * dz <= 16.0) n++;
            }
            return n;
        }

        @Override
        public int bottles() {
            int n = 0;
            for (int w = 9; w <= 45; w++) {
                RepairTrip.Stack s = at(w);
                if (s != null && s.bottle()) n += s.count();
            }
            return n;
        }

        @Override
        public List<Integer> freeSlots() {
            List<Integer> out = new ArrayList<>();
            Set<Integer> keep = keepSlots();
            // The main inventory first, the hotbar last
            for (int w = 9; w <= 44; w++) {
                if (keep.contains(indexOf(w)) || at(w) != null) continue;
                out.add(w);
            }
            return out;
        }

        @Override
        public boolean keep(int window) {
            return keepSlots().contains(indexOf(window));
        }

        @Override
        public boolean abort() {
            if (repairUndoMode) return false;
            if (repairYieldAbort) return true;
            OrderFlow flow = orders.flow;
            // A closed gate (a player in control, the queue) only holds the tick back: the trip carries on when it opens again
            return !flow.running() || flow.cancelRequested() || flow.parkRequested();
        }

        @Override
        public void ledger(List<int[]> moves) {
            Stash s = orders.st();
            if (s == null) return;
            List<String> now = moves.isEmpty() ? null : Repair.formatLedger(moves);
            if (Objects.equals(now, s.repairGear)) return;
            s.repairGear = now;
            s.markDirty();
            s.save();
        }

        @Override
        public void dropped(int[] move) {
            warn("Repair: dropped a saved move ({} to {}): the item there does not go in that slot.", move[1], move[0]);
        }

        @Override
        public void afterClick() {
            wait = Math.max(wait, CONFIG.delayTicks);
        }
    };

    // ------------------------------------------------------------------------------------------------------------------
    // Enchant orders (1.19.0, ENCHANT-CONTRACT v1.0). An enchant item is a load of its bin that is made at the anvil before the bin is built
    // (OrderFlow.EnchantLoads comes again until the item is finished or short). One EnchantRun is one item: the preflight (the anvil, the base,
    // the books, the anvil spare and all the bottles taken by the order's take keys only, the carry check, the plan made again with the real
    // items), the steps (Job.Enchant: EnchantTrip, with XpLeveler throwing and GearStow putting the gear away), a top-up of bottles when they ran
    // out, and the put-back of what is left. The decisions are pure and tested (EnchantPreflight, EnchantTrip, EnchantLedger, ...); this runs
    // them on the bot tick thread. Every step is journalled in the order's saved plan, so a restart carries on at the right step. Nothing
    // here is ever logged or reported with a position.

    private enum EnchantPhase { Prepare, Gather, Trip, TopUp, PutBack }

    private static final class EnchantRun {
        final Unit unit;
        final int n;
        EnchantPhase phase = EnchantPhase.Prepare, afterGather = EnchantPhase.Prepare;
        @Nullable EnchantPreflight pre;
        @Nullable Bin bin;
        EnchantOrder.@Nullable ItemPlan real;
        boolean anvilChecked, leaving;
        int lastHave, putBackTries, topUpLevels;
        long waitUntilTick;
        @Nullable String waitWhy;
        /** What was about to be put back, by index key, to refund the budget with what really left. */
        Map<String, Long> putBackBefore = new HashMap<>();

        EnchantRun(Unit unit, int n) {
            this.unit = unit;
            this.n = n;
        }
    }

    private @Nullable EnchantRun enchantRun;
    private @Nullable EnchantTrip enchantTrip;
    private @Nullable GearStow gearStow;
    private @Nullable GoalNear enchantGoal;
    private boolean enchantYield;

    private static String mcVersionNow() {
        try {
            String v = com.aquarius.Globals.MC_VERSION;
            if (v != null && !v.isBlank()) return v;
        } catch (RuntimeException | LinkageError ignored) {
            // The build's own version below
        }
        return com.porter.qwertestash.BuildConstants.MC_VERSION;
    }

    /** Why enchant lines can't be carried out now (a {@link Codes} reason), or null: the option, the Minecraft version, the proxy's own modules. */
    private @Nullable String enchantProblemNow(EnchantOrder.@Nullable Block block) {
        if (!CONFIG.enchantOrders) return Codes.BUSY;
        String mc = mcVersionNow();
        if (!com.porter.qwerte.core.enchant.McData.SUPPORTED.contains(mc)) return Codes.MC_UNSUPPORTED;
        if (block != null && !block.mc().equals(mc)) return Codes.MC_MISMATCH;
        List<String> enabled = com.porter.qwertestash.manager.ProxySources.enabledModuleNames();
        if (EnchantGuard.conflict(enabled) != null) return Codes.MODULE_CONFLICT;
        return null;
    }

    /** The {@code enchant.carry} of the heartbeat: the free carry slots after the gear is stowed. */
    public int enchantCarry() {
        GearStow g = new GearStow(repairPort);
        return EnchantCarry.heartbeatCarry(freeCarryCount(), g.damagedMendingArmour(), false);
    }

    /** The bot's experience points now: the level and the bar together. */
    private static double xpPoints() {
        try {
            var me = CACHE.getPlayerCache().getThePlayer();
            if (me == null || !Alerts.online()) return 0;
            int level = me.getLevel();
            double next = com.porter.qwerte.core.enchant.EnchantRules.points(level + 1) - com.porter.qwerte.core.enchant.EnchantRules.points(level);
            return com.porter.qwerte.core.enchant.EnchantRules.points(level) + Math.max(0, Math.min(1, me.getExperience())) * next;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    /** A carried stack as the anvil sees it. */
    private EnchantItems.@Nullable Held enchantHeld(int slot, @Nullable Item it) {
        if (it == null || it.isShulker()) return null;
        com.porter.qwertestash.manager.EnchantFields.Fields f = com.porter.qwertestash.manager.EnchantFields.of(it);
        return new EnchantItems.Held(slot, it.indexKey(), it.id, it.count, "enchanted_book".equals(it.id), f == null ? Map.of() : f.ench(), Math.max(0, it.repairCost), it.customName);
    }

    /** Every carried stack of the order (not the bot's own gear, not a shulker), in carry slots. */
    private List<EnchantItems.Held> enchantCarriedStacks() {
        List<EnchantItems.Held> out = new ArrayList<>();
        for (int slot : carriedSlots()) {
            EnchantItems.Held h = enchantHeld(slot, inventoryItem(slot));
            if (h != null) out.add(h);
        }
        return out;
    }

    /** The index keys the enchant work of this order knows: the take keys and the keys of finished items waiting for their bin. */
    private Set<String> enchantKeys() {
        Set<String> keys = new HashSet<>();
        EnchantLedger l = orders.flow.enchantLedger();
        if (l != null) keys.addAll(l.block().takeMap().keySet());
        keys.addAll(finishedEnchantKeys());
        return keys;
    }

    /** The keys of finished enchant items that wait for their bin to be built. */
    private Set<String> finishedEnchantKeys() {
        Set<String> out = new HashSet<>();
        List<Unit> all = orders.flow.units();
        for (int i = Math.min(orders.flow.index(), all.size()); i < all.size(); i++) {
            Unit u = all.get(i);
            if (u.built || u.have) continue;
            for (Order.Lot lot : u.lots) if (lot.crafted() && lot.key().indexOf('#') >= 0) out.add(lot.key());
        }
        return out;
    }

    /** The carry slots holding finished enchant items that wait for their bin (never put back, never used as materials). */
    private Set<Integer> enchantKeptSlots() {
        Set<Integer> out = new HashSet<>();
        List<String> keys = new ArrayList<>(finishedEnchantKeysList());
        for (int slot : carriedSlots()) {
            Item it = inventoryItem(slot);
            if (it == null) continue;
            int at = keys.indexOf(it.indexKey());
            if (at >= 0) {
                keys.remove(at);
                out.add(slot);
            }
        }
        return out;
    }

    private List<String> finishedEnchantKeysList() {
        List<String> out = new ArrayList<>();
        List<Unit> all = orders.flow.units();
        for (int i = Math.min(orders.flow.index(), all.size()); i < all.size(); i++) {
            Unit u = all.get(i);
            if (u.built || u.have) continue;
            for (Order.Lot lot : u.lots) if (lot.crafted() && lot.key().indexOf('#') >= 0) out.add(lot.key());
        }
        return out;
    }

    /** The carried stacks that are the materials of this item (by the plan's keys, the bottles, the anvils of the take), not the finished items of other bins. */
    private List<EnchantItems.Held> enchantOwned(EnchantRun r) {
        EnchantLedger l = orders.flow.enchantLedger();
        Set<String> keys = new HashSet<>();
        if (l != null) keys.addAll(l.block().takeMap().keySet());
        Set<Integer> kept = enchantKeptSlots();
        // The bases the order's craft made are not taken from the stash: never charged to the take budget, never put back by an item (draft 3.4)
        Set<Integer> crafted = orderCraftHeld();
        List<EnchantItems.Held> out = new ArrayList<>();
        for (EnchantItems.Held h : enchantCarriedStacks()) if (keys.contains(h.key()) && !kept.contains(h.slot()) && !crafted.contains(h.slot())) out.add(h);
        return out;
    }

    /**
     * The carried stacks the order's craft made as bases for its enchant plans (ENCHANT-CONTRACT draft 3.4): the crafted stacks set aside for the order
     * ({@link #orderCraftHeld}) that the made base loads of the bin claim, as many as there are such loads. Fresh or not is the preflight's to check.
     */
    private List<EnchantItems.Held> enchantMadeBases() {
        List<EnchantItems.Held> out = new ArrayList<>();
        for (int slot : baseSlots()) {
            EnchantItems.Held h = enchantHeld(slot, inventoryItem(slot));
            if (h != null) out.add(h);
        }
        return out;
    }

    /** A stack with no components, as the stash keys a plain item: what a crafted load or a made base is (the check {@code craftResumed} makes too). */
    private static boolean freshPlain(@Nullable Item it) {
        return it != null && it.indexKey().equals("minecraft:" + it.id);
    }

    /**
     * The carry slots holding the bases the order's craft made for its enchant plans and that wait for their items, plus the made bases the items did not use
     * ({@link #orderReleasedBases}): never packed into a shulker ({@link #carriedHeld}), never taken as a gathered base, never put back by an item. Claimed by
     * {@link BaseSlots}: as many per item id as the flow's made base loads say, fresh plain stacks only, so a slot whose base was used up and now holds the
     * enchanted result is not one.
     */
    private List<Integer> baseSlots() {
        if (orderCraftLots.isEmpty() && orderReleasedBases.isEmpty()) return List.of();
        List<Integer> out = new ArrayList<>();
        if (!orderCraftLots.isEmpty()) {
            List<BaseSlots.Stack> crafted = new ArrayList<>();
            for (var e : new ArrayList<>(orderCraftLots.entrySet())) {
                Item it = inventoryItem(e.getKey());
                if (it != null && it.id.equals(e.getValue()) && !it.isShulker()) crafted.add(new BaseSlots.Stack(e.getKey(), "minecraft:" + it.id, freshPlain(it)));
            }
            out.addAll(BaseSlots.claim(orders.flow.madeBases(), crafted));
        }
        for (int slot : orderReleasedBases) if (freshPlain(inventoryItem(slot)) && !out.contains(slot)) out.add(slot);
        java.util.Collections.sort(out);
        return out;
    }

    /** What the order's enchant work carries now, by index key (for the end-of-order put-back and the report). */
    private Map<String, Long> enchantCarriedCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        Set<String> keys = enchantKeys();
        Set<Integer> crafted = orderCraftHeld();
        for (EnchantItems.Held h : enchantCarriedStacks()) if (keys.contains(h.key()) && !crafted.contains(h.slot())) out.merge(h.key(), (long) Math.max(1, h.count()), Long::sum);
        return out;
    }

    /** The free carry slots there will be once the damaged Mending gear is stowed, plus the slots the order's stacks take now. */
    private int enchantLiveCarry(List<EnchantItems.Held> owned) {
        GearStow g = new GearStow(repairPort);
        int gear = Repair.slotsNeeded(g.damagedMendingArmour(), false, 0);
        return Math.max(0, freeCarryCount() - gear) + owned.size();
    }

    /** The bottles the stash holds (loose and in shulkers), not what is carried. */
    private long stashBottlesNow() {
        Stash st = orders.st();
        if (st == null) return 0;
        StockPlanner.Survey sv = orders.survey(st, Set.of(Repair.BOTTLE));
        return sv.available().getOrDefault(Repair.BOTTLE, 0L) + sv.shulkerAvailable().getOrDefault(Repair.BOTTLE, 0L);
    }

    /** Starts the item of OrderFlow.EnchantLoads. @return true when a job was started */
    private boolean startEnchant(OrderFlow.EnchantLoads el) {
        if (compact != null || craft != null || anvilRun != null || chestRun != null || repair != null || repairTrip != null || enchantRun != null) return false;
        OrderFlow flow = orders.flow;
        EnchantLedger ledger = flow.enchantLedger();
        Unit u = el.unit();
        int at = u.nextEnchantLot();
        if (ledger == null || at < 0) return false;
        int n = u.lots.get(at).enchantItem();
        if (n < 0 || n >= ledger.items().size()) {
            // A lot the ledger does not know: left out
            u.lots.remove(at);
            return false;
        }
        EnchantRun r = new EnchantRun(u, n);
        enchantRun = r;
        step("order: enchanting item " + (n + 1) + "/" + ledger.items().size() + "; next: " + (ledger.item(n).status == EnchantLedger.Status.RUNNING ? "carry on with its steps." : "gather what it needs."));
        return continueEnchant();
    }

    /** The next job of the enchant item in hand. @return true when one was started; false when it waits or is over ({@link #enchantRun} is null then) */
    private boolean continueEnchant() {
        EnchantRun r = enchantRun;
        if (r == null) return false;
        if (job != null) return true;
        if (!orders.gate()) return false;
        OrderFlow flow = orders.flow;
        EnchantLedger ledger = flow.enchantLedger();
        if (ledger == null) {
            enchantRun = null;
            return false;
        }
        // The order ended, was cancelled before the item's first step, or is parked: what was gathered goes back; the journal stays (a park is not an end)
        if (r.phase != EnchantPhase.PutBack && (!flow.running() || flow.cancelRequested() || flow.parkRequested())) {
            r.leaving = true;
            r.phase = EnchantPhase.PutBack;
            step("enchanting: stopped, the order ended; next: put back what was gathered.");
        }
        EnchantLedger.Item item = ledger.item(r.n);
        switch (r.phase) {
            case Prepare -> {
                return enchantPrepare(r, flow, ledger, item);
            }
            case Gather -> {
                return enchantGather(r, flow, ledger);
            }
            case Trip -> {
                return enchantStartTrip(r, flow, ledger, item);
            }
            case TopUp -> {
                return enchantTopUp(r, flow, ledger);
            }
            case PutBack -> {
                return enchantPutBack(r, flow, ledger);
            }
        }
        return false;
    }

    /** The preflight ends the item before its first step: it is short with this reason and everything goes back. */
    private boolean enchantRefuse(EnchantRun r, String code, String why) {
        OrderFlow flow = orders.flow;
        String safe = Codes.safe(code);
        step("enchanting: item " + (r.n + 1) + " refused (" + safe + "): " + why + "; next: put back what was gathered.");
        flow.enchantItemShort(r.unit, r.n, safe);
        // A refusal for the order as a whole (the option went off, a module came on, the version differs) applies to every item still to make
        if (safe.equals(Codes.MODULE_CONFLICT) || safe.equals(Codes.MC_MISMATCH) || safe.equals(Codes.MC_UNSUPPORTED)) flow.enchantAbandon(safe);
        r.phase = EnchantPhase.PutBack;
        return continueEnchant();
    }

    private enum AnvilReady { Ready, Wait, Started, None }

    /** An anvil to work at: the one set or found, else one put down (from the stash, or the spare carried). The search is spread over ticks. */
    private AnvilReady enchantAnvil() {
        AnvilSpot.Spot spot = namingSpot();
        AnvilFinder.Way way = spot == null || anvilSkip.contains(spot.pos()) || (AnvilSpot.here(spot) && Containers.isLoaded(spot.pos()) && !AnvilSpot.isAnvil(spot.pos()))
            ? anvilWay() : AnvilFinder.Way.USE;
        if (way == AnvilFinder.Way.WAIT) return AnvilReady.Wait;
        spot = namingSpot();
        if (way == AnvilFinder.Way.USE && spot != null) return AnvilReady.Ready;
        if (way == AnvilFinder.Way.PLACE && startAnvilRun()) return AnvilReady.Started;
        if (anvilRun != null) return AnvilReady.Wait;
        return AnvilReady.None;
    }

    private boolean enchantPrepare(EnchantRun r, OrderFlow flow, EnchantLedger ledger, EnchantLedger.Item item) {
        // The journal: the item is past its preflight; the carried stacks are looked for again by what they are
        if (item.status == EnchantLedger.Status.RUNNING && item.real != null) {
            r.real = item.real;
            r.phase = EnchantPhase.Trip;
            return continueEnchant();
        }
        if (item.status != EnchantLedger.Status.PENDING) {
            // Ended already (a restart between the end and the bin): the lot is dropped
            // (a journal that could not be resumed is here too: the item is short and what is carried goes back)
            flow.enchantItemShort(r.unit, r.n, item.reason == null ? Codes.CANCELLED : item.reason);
            r.phase = EnchantPhase.PutBack;
            return continueEnchant();
        }
        EnchantOrder.ItemRef ref = ledger.block().items().get(r.n);
        String problem = enchantProblemNow(ledger.block());
        if (problem != null) return enchantRefuse(r, problem, "this bot can't make enchant items now");
        if (r.pre == null) r.pre = new EnchantPreflight(ref, ledger.block().mc(), new HashSet<>(ledger.block().curse()), ledger, System::currentTimeMillis);
        String stalled = r.pre.stalled();
        if (stalled != null) return enchantRefuse(r, stalled, "the preflight made no headway");
        flow.setPhase("enchanting " + (r.n + 1) + "/" + ledger.items().size() + ": preparing");

        // The anvil first: one from the stash is put down before the spare is taken
        if (!r.anvilChecked) {
            switch (enchantAnvil()) {
                case Wait -> {
                    return false;
                }
                case Started -> {
                    return true;
                }
                case None -> {
                    return enchantRefuse(r, Codes.NO_ANVIL, "no anvil stands in the stash area and none could be put down");
                }
                case Ready -> r.anvilChecked = true;
            }
        }
        List<EnchantItems.Held> owned = enchantOwned(r);
        // A plan whose base the order's craft made (draft 3.4) uses one of the crafted stacks in hand: nothing is taken for it, and its slot counts in the carry formula
        List<EnchantItems.Held> made = ref.plan().baseCrafted() ? enchantMadeBases() : List.of();
        // The crafted bases of later items are already in the inventory, so they are simply not free (the server counts them as occupied: need + L); this item's own
        // crafted base is added back, as the owned stacks are (EnchantCarry.liveCarry)
        EnchantPreflight.Decision d = r.pre.decide(owned, made, enchantLiveCarry(owned) + EnchantCarry.ownBaseSlot(ref.plan().baseCrafted(), !made.isEmpty()));
        if (d instanceof EnchantPreflight.Refuse f) return enchantRefuse(r, f.code(), f.why());
        if (d instanceof EnchantPreflight.Ready ready) {
            r.real = ready.real();
            ledger.begin(r.n, ready.real());
            flow.enchantChanged();
            info("Enchanting: item {}/{} planned with the real items: {} steps, {} XP points.", r.n + 1, ledger.items().size(), ready.real().steps().size(), ready.real().points());
            r.phase = EnchantPhase.Trip;
            return continueEnchant();
        }
        EnchantPreflight.Gather g = (EnchantPreflight.Gather) d;
        List<Order.Lot> lots = new ArrayList<>();
        for (EnchantPreflight.Lot lot : g.lots()) lots.addAll(Order.expand(lot.key(), lot.count(), Math.max(1, stackOf(baseIdOf(lot.key())))));
        r.bin = new Bin(new Unit(Unit.Kind.Bin, "", lots));
        r.lastHave = 0;
        r.afterGather = EnchantPhase.Prepare;
        r.phase = EnchantPhase.Gather;
        return continueEnchant();
    }

    private boolean enchantGather(EnchantRun r, OrderFlow flow, EnchantLedger ledger) {
        Bin bin = r.bin;
        if (bin == null) {
            r.phase = r.afterGather;
            return continueEnchant();
        }
        BinTracker.Need need = needOf(bin);
        if (need == null) {
            r.bin = null;
            r.phase = r.afterGather;
            return continueEnchant();
        }
        if (r.pre != null) {
            int have = carriedHeld(bin).size();
            r.pre.tripMade(have > r.lastHave);
            r.lastHave = have;
            String stalled = r.pre.stalled();
            if (stalled != null && r.afterGather == EnchantPhase.Prepare) return enchantRefuse(r, stalled, "gathering made no headway");
        }
        flow.setPhase("enchanting " + (r.n + 1) + "/" + ledger.items().size() + ": gathering");
        if (freeCarryCount() < 2) {
            if (r.afterGather == EnchantPhase.Prepare) return enchantRefuse(r, Codes.INVENTORY_FULL, "no carry room to gather the materials");
            // A top-up with no room goes on with what is held
            r.bin = null;
            r.phase = r.afterGather;
            return continueEnchant();
        }
        Box source = binSourceFor(bin);
        if (source != null) {
            gatherLeft = bin.lots.size();
            step("enchanting: gathering " + Recipes.pretty(Recipes.ns(baseIdOf(need.lot().key()))) + " (" + carriedHeld(bin).size() + "/" + bin.lots.size() + " stacks in hand); next: the anvil.");
            return start(Job.Gather, source);
        }
        // Taking from a shulker puts it down: not while shulker handling is paused (tried again; the preflight's clock runs meanwhile)
        if (shulkerPause() != null && findUnpackSource(bin) != null) return false;
        if (freeCarryCount() >= 2 && startBinUnpack(bin, "enchanting: ")) return true;
        if (r.afterGather != EnchantPhase.Prepare) {
            // A top-up that finds nothing more: the trip waits for bottles (see TopUp)
            r.bin = null;
            r.phase = EnchantPhase.TopUp;
            return continueEnchant();
        }
        String key = need.lot().key();
        EnchantOrder.ItemPlan plan = ledger.block().items().get(r.n).plan();
        String code = key.equals(plan.baseKey()) ? Codes.NO_BASE : key.equals(EnchantOrder.BOTTLE_KEY) ? Codes.XP_SHORT : ledger.anvilKeys().contains(key) ? Codes.NO_ANVIL : Codes.BOOKS_SHORT;
        return enchantRefuse(r, code, "the stash has no more of what the order reserved (" + noSourceDetail(bin) + ")");
    }

    private boolean enchantStartTrip(EnchantRun r, OrderFlow flow, EnchantLedger ledger, EnchantLedger.Item item) {
        EnchantOrder.ItemPlan real = r.real != null ? r.real : item.real;
        if (real == null) {
            r.phase = EnchantPhase.Prepare;
            return continueEnchant();
        }
        // A running item is never failed by a module that came on (the contract's "cannot fail once started"): past its first step it waits, with the status
        // saying which module to turn off, and goes on once it is off (a force-cancel still ends it at the next boundary). Before the first step it is refused.
        String module = EnchantGuard.conflict(com.porter.qwertestash.manager.ProxySources.enabledModuleNames());
        if (module != null) {
            if (item.stepsDone < 1) return enchantEndShort(r, Codes.MODULE_CONFLICT, module + " is on");
            if (EnchantGuard.pausesRunningItem(Codes.MODULE_CONFLICT, flow.forceCancelled())) {
                flow.setPhase("waiting: turn off " + module);
                return false;
            }
        }
        // The anvil must stand: one that broke on the last pickup is replaced by the spare (or one found); none means the item can't go on
        switch (enchantAnvil()) {
            case Wait -> {
                return false;
            }
            case Started -> {
                return true;
            }
            case None -> {
                String code = ledger.anvilsBroken() > 0 ? Codes.ANVIL_BROKE : Codes.NO_ANVIL;
                return enchantEndShort(r, code, "no anvil to go on with");
            }
            case Ready -> {
            }
        }
        EnchantOrder.ItemRef ref = ledger.block().items().get(r.n);
        enchantYield = false;
        EnchantTrip t;
        try {
            t = new EnchantTrip(enchantPort, ledger.block().mc(), ref.key(), real, item.stepsDone);
        } catch (RuntimeException e) {
            return enchantEndShort(r, Codes.MC_UNSUPPORTED, "no enchantment table for this Minecraft version");
        }
        enchantTrip = t;
        markJobStart();
        job = Job.Enchant;
        target = null;
        actions = 0;
        tries = 0;
        stepTicks = 0;
        jobRunTicks = 0;
        step = Step.Working;
        resetJobState();
        // The anvil may be well outside the stash area
        jobStartedInside = false;
        AnvilSpot.Spot spot = namingSpot();
        enchantGoal = spot == null ? null : new GoalNear(spot.pos(), REACH_RANGE_SQ);
        step("order: enchanting item " + (r.n + 1) + "/" + ledger.items().size() + " (" + (real.steps().size() - item.stepsDone) + " steps to go); next: put what is left back.");
        return true;
    }

    private void tickEnchant() {
        EnchantTrip t = enchantTrip;
        EnchantRun r = enchantRun;
        if (t == null || r == null) {
            endEnchantJob();
            return;
        }
        OrderFlow flow = orders.flow;
        t.tick();
        EnchantTrip.Stage st = t.stage();
        if (st != EnchantTrip.Stage.Walk && st != EnchantTrip.Stage.Open) step = Step.Working;
        // Between steps the bot may report (a force-cancel is acknowledged by the report); in the middle of one it may not
        boolean mid = st == EnchantTrip.Stage.Place || st == EnchantTrip.Stage.Cost || st == EnchantTrip.Stage.Take || st == EnchantTrip.Stage.Settle || st == EnchantTrip.Stage.Retrieve;
        flow.enchantAtBoundary(!mid);
        EnchantLedger ledger = flow.enchantLedger();
        if (ledger != null && t.waiting() != null) flow.setPhase("paused: " + t.waiting());
        else if (ledger != null) flow.setPhase("enchanting " + (r.n + 1) + "/" + ledger.items().size() + ": step " + Math.min(t.step() + 1, Math.max(1, t.steps())) + "/" + t.steps());
        if (t.done()) endEnchantTrip(t, r);
    }

    private void endEnchantJob() {
        enchantTrip = null;
        enchantGoal = null;
        enchantYield = false;
        activity.releaseOurPathing();
        restoreRules();
        job = null;
        target = null;
        step = Step.Walking;
        wait = CONFIG.delayTicks;
        orders.flow.enchantAtBoundary(true);
    }

    /** The trip is over: what it ended with decides what comes next (put the spare down, fetch bottles, put the leftovers back, or just go on). */
    private void endEnchantTrip(EnchantTrip t, EnchantRun r) {
        EnchantTrip.Outcome o = t.outcome();
        OrderFlow flow = orders.flow;
        EnchantLedger ledger = flow.enchantLedger();
        int used = t.bottlesUsed();
        endEnchantJob();
        if (ledger == null || o == null) {
            enchantRun = null;
            return;
        }
        ledger.addBottles(used);
        ledger.anvilBroke(o.anvilsBroken());
        flow.enchantChanged();
        debug(() -> "Enchant trip over: " + o.kind() + ", " + o.stepsDone() + " steps done, " + used + " bottles used.");
        switch (o.kind()) {
            case FINISHED -> {
                EnchantItems.Held got = null;
                if (o.spec() != null) for (EnchantItems.Held h : enchantCarriedStacks()) if (o.spec().matches(h) && !enchantKeptSlots().contains(h.slot())) got = h;
                Item it = got == null ? null : inventoryItem(got.slot());
                if (it == null) {
                    enchantEndShort(r, Codes.WRONG_RESULT, "the finished item is not in the inventory");
                    return;
                }
                // The finished stack is the bin's lot now, and it is the order's (never the sorter's) until the bin is built
                stashHeld.put(got.slot(), it.id);
                flow.enchantItemDone(r.unit, r.n, it.indexKey(), it.variant, got.ench());
                info("Enchanting: item {}/{} is finished ({} bottles used so far).", r.n + 1, ledger.items().size(), ledger.bottlesUsed());
                r.phase = EnchantPhase.PutBack;
            }
            case SHORT -> enchantEndShort(r, o.reason() == null ? Codes.WRONG_RESULT : o.reason(), "the anvil work could not be finished");
            case FORCE_CANCELLED -> enchantEndShort(r, Codes.CANCELLED, "the order was cancelled");
            case NEED_ANVIL -> {
                // The spare is put down (or another found) and the trip is made again from the journal
                // An anvil that is still standing but could not be reached or opened is given up on; one that broke is simply gone (its spot may be used again)
                AnvilSpot.Spot was = namingSpot();
                if (was != null && Containers.isLoaded(was.pos()) && AnvilSpot.isAnvil(was.pos())) anvilSkip.add(was.pos());
                r.phase = EnchantPhase.Trip;
            }
            case NEED_BOTTLES -> {
                r.topUpLevels = o.levels();
                r.phase = EnchantPhase.TopUp;
            }
            case ABORTED -> {
                // Everything is put back where the journal can find it again; the item is carried on when the bot may work
                enchantRun = null;
            }
        }
    }

    /** The item ends short: its lot is left out of the bin and everything carried goes back (the finished steps' work is lost with it). */
    private boolean enchantEndShort(EnchantRun r, String code, String why) {
        String safe = Codes.safe(code);
        step("enchanting: item " + (r.n + 1) + " is short (" + safe + "): " + why + "; next: put back what is carried.");
        orders.flow.enchantItemShort(r.unit, r.n, safe);
        r.phase = EnchantPhase.PutBack;
        return continueEnchant();
    }

    /** More bottles when the held ones ran out before the level was reached (D3): allowed into the repair reserve; with none at all it waits and says so. */
    private boolean enchantTopUp(EnchantRun r, OrderFlow flow, EnchantLedger ledger) {
        if (tickCount < r.waitUntilTick) return false;
        long missing = Math.max(1, Math.round(com.porter.qwerte.core.enchant.EnchantRules.points(r.topUpLevels) - xpPoints()));
        int want = (int) Math.min(2048, (missing * 115 / 100 + com.porter.qwerte.core.enchant.EnchantRules.XP_PER_BOTTLE - 1) / com.porter.qwerte.core.enchant.EnchantRules.XP_PER_BOTTLE + 3);
        long stash = stashBottlesNow();
        long allow = EnchantLedger.topUpAllowance(stash, EnchantState.SHARED.bottlesReserved(), ledger.taken(EnchantOrder.BOTTLE_KEY));
        // At most twice the item's planned bottles are ever fetched on top (a runaway Mending drain or a wrong plan can't empty the stash)
        EnchantOrder.ItemPlan planned = ledger.item(r.n).real != null ? ledger.item(r.n).real : ledger.block().items().get(r.n).plan();
        long cap = Math.max(0, 2L * planned.bottles() - ledger.topped(r.n));
        String waitWhy = null;
        if (freeCarryCount() < 2) waitWhy = "no free carry slot for more bottles";
        else if (cap <= 0) waitWhy = "the top-up limit for this item (twice its planned bottles) is used";
        else if (allow <= 0) waitWhy = "out of experience bottles (the stash has " + stash + ")";
        if (waitWhy != null) {
            // Wait with a status message and look again (it never fails for this)
            flow.setPhase("waiting: " + waitWhy);
            if (!waitWhy.equals(r.waitWhy) || tickCount - r.waitUntilTick > 20 * 60) step("enchanting: " + waitWhy + "; waiting, looking again in 30 s.");
            r.waitWhy = waitWhy;
            r.waitUntilTick = tickCount + 20 * 30;
            return false;
        }
        r.waitWhy = null;
        want = (int) Math.min(want, Math.min(allow, cap));
        ledger.addTopped(r.n, want);
        List<Order.Lot> lots = new ArrayList<>(Order.expand(EnchantOrder.BOTTLE_KEY, want, 64));
        r.bin = new Bin(new Unit(Unit.Kind.Bin, "", lots));
        r.lastHave = 0;
        r.afterGather = EnchantPhase.Trip;
        r.phase = EnchantPhase.Gather;
        ledger.toppedUp();
        flow.enchantChanged();
        step("enchanting: out of bottles at step " + (ledger.item(r.n).stepsDone + 1) + "; taking " + want + " more from the stash (the repair reserve may be used).");
        return continueEnchant();
    }

    /** The slots of what the item leaves: everything carried except the finished items that wait for their bins. */
    private List<Integer> enchantLeft() {
        Set<Integer> kept = enchantKeptSlots();
        Set<Integer> held = orderCraftHeld();
        List<Integer> out = new ArrayList<>();
        for (int slot : looseCarried()) {
            if (kept.contains(slot) || held.contains(slot) || pendingSourceShulker(slot)) continue;
            out.add(slot);
        }
        return out;
    }

    private boolean enchantPutBack(EnchantRun r, OrderFlow flow, EnchantLedger ledger) {
        // Source shulkers emptied for the item go back first, then what is left of the materials
        if (handle == null && returnSpentShulker()) return true;
        if (r.putBackTries < 3) {
            List<Integer> left = enchantLeft();
            if (!left.isEmpty()) {
                r.putBackTries++;
                if (r.putBackBefore.isEmpty()) {
                    for (int slot : left) {
                        Item it = inventoryItem(slot);
                        if (it != null) r.putBackBefore.merge(it.indexKey(), (long) it.count, Long::sum);
                    }
                }
                if (putBack(left, "enchanting: putting " + left.size() + " leftover stacks back")) return true;
            }
        }
        // What went back is given back to the budget (the next item takes it again), what is still carried is not
        Map<String, Long> still = new HashMap<>();
        for (int slot : enchantLeft()) {
            Item it = inventoryItem(slot);
            if (it != null) still.merge(it.indexKey(), (long) it.count, Long::sum);
        }
        Map<String, Long> back = new HashMap<>();
        for (var e : r.putBackBefore.entrySet()) {
            long n = e.getValue() - Math.max(0, still.getOrDefault(e.getKey(), 0L));
            ledger.refund(e.getKey(), n);
            if (n > 0) back.put(e.getKey(), n);
        }
        // result.enchant.returned lists everything that went back to the stash, also when an item ended short and the order went on
        ledger.addReturned(back);
        enchantRun = null;
        flow.enchantChanged();
        return false;
    }

    private void resetEnchant() {
        enchantRun = null;
        enchantTrip = null;
        gearStow = null;
        enchantGoal = null;
        enchantYield = false;
        orders.flow.enchantAtBoundary(true);
    }

    /** The trip's view of the bot: the player inventory and the anvil window; walking and opening through the pathfinder, clicks through the inventory queue. */
    private final EnchantTrip.Port enchantPort = new EnchantTrip.Port() {
        private GearStow gear() {
            GearStow g = gearStow;
            if (g == null) {
                Stash s = orders.st();
                g = new GearStow(repairPort).seed(s == null || s.repairGear == null ? List.of() : Repair.parseLedger(s.repairGear));
                gearStow = g;
            }
            return g;
        }

        @Override
        public boolean busy() {
            return StashSorter.this.busy();
        }

        @Override
        public boolean abort() {
            OrderFlow flow = orders.flow;
            // A cancel before the first step ends the item at once; past it the cancel is queued (cancelRequested() is false then) and a force-cancel stops at a
            // step boundary through forceCancelled(), never in the middle of a step. The order ending, a park and a module that took over abort at any time
            if (enchantYield || !flow.running() || flow.parkRequested() || (flow.cancelRequested() && !flow.forceCancelled())) return true;
            // A force-cancel heard while the bot only walks, stows or levels up (nothing in the anvil) ends the trip at once, so a leveler's pause does not hold it
            EnchantTrip t = enchantTrip;
            if (flow.forceCancelled() && t != null) {
                EnchantTrip.Stage st = t.stage();
                return (st == EnchantTrip.Stage.Start || st == EnchantTrip.Stage.Stow || st == EnchantTrip.Stage.Level) && anvilWindow() == null;
            }
            return false;
        }

        @Override
        public boolean forceCancelled() {
            return orders.flow.forceCancelled();
        }

        @Override
        public boolean controlled() {
            return !orders.gate();
        }

        @Override
        public boolean playerNear() {
            return repairPlayerNear(false);
        }

        @Override
        public double points() {
            return xpPoints();
        }

        @Override
        public int level() {
            return StashSorter.xpLevel();
        }

        @Override
        public int bottles() {
            return repairPort.bottles();
        }

        @Override
        public boolean bottleReady() {
            return gear().bottleReady();
        }

        @Override
        public boolean refill() {
            return gear().refill();
        }

        @Override
        public boolean throwBottle() {
            // From the MAIN hand (the selected hotbar slot GearStow put the bottles in): the off hand, a totem included, is never touched. Looking straight down first
            if (!gear().bottleReady()) return false;
            float pitch = com.aquarius.Globals.BOT.getPitch();
            float yaw = com.aquarius.Globals.BOT.getYaw();
            if (Math.abs(pitch - 90f) > 0.5f) {
                InputRequestFuture look = INPUTS.submit(InputRequest.builder().owner(StashSorter.this).yaw(yaw).pitch(90f).priority(REPAIR_INPUT_PRIORITY).build());
                activity.noteInput(look, tickCount);
                return false;
            }
            InputRequestFuture f = INPUTS.submit(InputRequest.builder().owner(StashSorter.this)
                .input(Input.builder().rightClick(true).hand(org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand.MAIN_HAND)
                    .clickTarget(ClickTarget.None.INSTANCE).clickRequiresRotation(true).build())
                .yaw(yaw).pitch(90f).priority(REPAIR_INPUT_PRIORITY).build());
            activity.noteInput(f, tickCount);
            return f != InputRequestFuture.rejected;
        }

        @Override
        public int orbsNear() {
            return repairPort.orbsNear();
        }

        @Override
        public int stowGear() {
            return gear().stow();
        }

        @Override
        public boolean restoreGear() {
            GearStow g = gearStow;
            if (g == null) return true;
            if (!g.restore()) return false;
            gearStow = null;
            return true;
        }

        @Override
        public List<EnchantItems.Held> carried() {
            return enchantCarriedStacks();
        }

        @Override
        public boolean arrived() {
            return enchantGoal != null && arrivedAt(enchantGoal);
        }

        @Override
        public boolean walk() {
            AnvilSpot.Spot spot = namingSpot();
            if (spot == null || !activity.mayRequest()) return false;
            enchantGoal = requestWalk(spot.pos(), "the anvil");
            wait = 10;
            return true;
        }

        @Override
        public boolean pathing() {
            return activity.ownPathing();
        }

        @Override
        public boolean anvilBlock() {
            AnvilSpot.Spot spot = namingSpot();
            // Not known to be gone while the chunk is not loaded
            return spot != null && !anvilSkip.contains(spot.pos()) && (!Containers.isLoaded(spot.pos()) || AnvilSpot.isAnvil(spot.pos()));
        }

        @Override
        public boolean anvilOpen() {
            return anvilWindow() != null;
        }

        @Override
        public boolean otherWindowOpen() {
            return openContainerId() != 0 && ownsWindow() && anvilWindow() == null;
        }

        @Override
        public boolean openPaused() {
            return StashSorter.this.openPaused();
        }

        @Override
        public void opened() {
            if (CONFIG.containerDelay > 0) wait = Math.max(wait, openWait());
        }

        @Override
        public boolean open() {
            AnvilSpot.Spot spot = namingSpot();
            if (spot == null || !activity.mayRequest() || activity.ownPathing()) return false;
            openIdBefore = openContainerId();
            openRequestedAt = lastOpenClickAt = System.currentTimeMillis();
            foreignSinceClick = false;
            requestOpen(spot.pos(), "the anvil");
            wait = CONFIG.settleTicks;
            return true;
        }

        @Override
        public boolean place(int left, int right) {
            Container c = anvilWindow();
            if (c == null || holdingCursor() || inventoryItem(left) == null || inventoryItem(right) == null) return false;
            if (Item.of(c.getItemStack(0)) != null || Item.of(c.getItemStack(1)) != null) return false;
            anvilCostBefore = anvilCostSeen;
            RequestFuture f = submit(new ClickItem(c.getContainerId(), toWindow(left, c), ClickItemAction.LEFT_CLICK), new ClickItem(c.getContainerId(), 0, ClickItemAction.LEFT_CLICK),
                new ClickItem(c.getContainerId(), toWindow(right, c), ClickItemAction.LEFT_CLICK), new ClickItem(c.getContainerId(), 1, ClickItemAction.LEFT_CLICK));
            if (f == RequestFuture.rejected) return false;
            actions++;
            wait = CONFIG.delayTicks;
            return true;
        }

        @Override
        public int leftCount() {
            Container c = anvilWindow();
            Item it = c == null ? null : Item.of(c.getItemStack(0));
            return it == null ? 0 : it.count;
        }

        @Override
        public int rightCount() {
            Container c = anvilWindow();
            Item it = c == null ? null : Item.of(c.getItemStack(1));
            return it == null ? 0 : it.count;
        }

        @Override
        public int cost() {
            Container c = anvilWindow();
            return c != null && anvilCostSeen > anvilCostBefore && anvilCostWindow == c.getContainerId() ? anvilCost : -1;
        }

        @Override
        public EnchantItems.@Nullable Held result() {
            Container c = anvilWindow();
            return c == null ? null : enchantHeld(2, Item.of(c.getItemStack(2)));
        }

        @Override
        public boolean take() {
            Container c = anvilWindow();
            if (c == null || holdingCursor()) return false;
            int into = freeCarrySlot();
            if (into == -1) return false;
            // One atomic quick move: with two clicks an anvil that breaks on the pickup leaves the result on the cursor as the window closes. The game puts a quick-moved
            // stack in the last empty inventory slot (hotbar 8 down to 0, then the main inventory 35 down to 9); only when that is a carry slot is it used, so
            // the stack never lands in a slot the sorter does not carry in (else the two clicks into a chosen carry slot are made)
            int quick = -1;
            for (int i = 8; i >= 0 && quick < 0; i--) if (inventoryItem(i) == null) quick = i;
            for (int i = 35; i >= 9 && quick < 0; i--) if (inventoryItem(i) == null) quick = i;
            RequestFuture f = quick >= 0 && carry.contains(quick)
                ? submit(new ShiftClick(c.getContainerId(), 2, ShiftClickItemAction.LEFT_CLICK))
                : submit(new ClickItem(c.getContainerId(), 2, ClickItemAction.LEFT_CLICK), new ClickItem(c.getContainerId(), toWindow(into, c), ClickItemAction.LEFT_CLICK));
            if (f == RequestFuture.rejected) return false;
            actions++;
            wait = CONFIG.delayTicks;
            return true;
        }

        @Override
        public boolean retrieve() {
            Container c = anvilWindow();
            if (c == null) return true;
            if (holdingCursor()) return false;
            for (int anvilSlot = 0; anvilSlot <= 1; anvilSlot++) {
                if (Item.of(c.getItemStack(anvilSlot)) == null) continue;
                int into = freeCarrySlot();
                if (into == -1) return false;
                RequestFuture f = submit(new ClickItem(c.getContainerId(), anvilSlot, ClickItemAction.LEFT_CLICK), new ClickItem(c.getContainerId(), toWindow(into, c), ClickItemAction.LEFT_CLICK));
                if (f != RequestFuture.rejected) wait = CONFIG.delayTicks;
                return false;
            }
            return true;
        }

        @Override
        public boolean close() {
            if (inventoryTrip || openContainerId() == 0 || !ownsWindow()) return true;
            closeSafely();
            return false;
        }

        @Override
        public void stepDone(int step, int shown) {
            OrderFlow flow = orders.flow;
            EnchantLedger l = flow.enchantLedger();
            EnchantRun r = enchantRun;
            if (l == null || r == null) return;
            l.stepDone(r.n, step);
            flow.enchantChanged();
            debug(() -> "Enchant step " + (step + 1) + " done, the anvil showed " + shown + " levels.");
        }

        @Override
        public void say(String text) {
            debug(() -> text);
        }
    };

    // Naming an order shulker at the anvil (Job.Name). The steps are AnvilNamer's; this is its view of the world. The anvil
    // is not a container of the stash: it is never read, sorted or indexed, and it may be outside the stash area

    private @Nullable AnvilNamer naming;
    private @Nullable Unit namingUnit;
    private @Nullable GoalNear anvilGoal;
    // The anvil's XP cost, from the container property the server sends (ClientboundContainerSetDataPacket, property 0 of an
    // anvil menu), and a counter of how many were seen, written on the network thread
    private volatile int anvilCostWindow = -1, anvilCost = -1;
    private volatile long anvilCostSeen;
    private long anvilCostBefore;

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("qwerte-stash-anvil")
            .setPriority(-1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                // Seen, never changed; whatever goes wrong here must not reach the packet pipeline
                .inbound(ClientboundContainerSetDataPacket.class, (packet, session) -> {
                    try {
                        if (packet.getRawProperty() == 0) {
                            anvilCostWindow = packet.getContainerId();
                            anvilCost = packet.getValue();
                            anvilCostSeen++;
                        }
                    } catch (RuntimeException e) {
                        // Only the cost is lost
                    }
                    return packet;
                })
                // The lid of a chest: how many have it open (1.9.0). Seen, never changed; a delivery chest a player opened and shut
                .inbound(ClientboundBlockEventPacket.class, (packet, session) -> {
                    try {
                        if (packet.getType() == ChestValueType.VIEWING_PLAYER_COUNT && packet.getValue() instanceof ChestValue cv) {
                            lidSeen(packet.getX(), packet.getY(), packet.getZ(), cv.getViewers());
                        }
                    } catch (RuntimeException e) {
                        // Only a lid is missed; the periodic look finds the chest
                    }
                    return packet;
                })
                .build())
            .build();
    }

    private boolean startName(OrderFlow.Name n) {
        if (compact != null && compact.bin == null) return false;
        AnvilSpot.Spot spot = namingSpot();
        AnvilFinder.Way way = spot == null || anvilSkip.contains(spot.pos()) || (AnvilSpot.here(spot) && Containers.isLoaded(spot.pos()) && !AnvilSpot.isAnvil(spot.pos()))
            ? anvilWay() : AnvilFinder.Way.USE;
        // The search for one standing is still going: the Name step comes again next tick
        if (way == AnvilFinder.Way.WAIT) return false;
        spot = namingSpot();
        if (way != AnvilFinder.Way.USE || spot == null) {
            // None set, found or placed: one from the stash is put down first (the Name step comes again after); else none at all
            if (way == AnvilFinder.Way.PLACE && startAnvilRun()) return true;
            if (anvilRun != null) return false;
            anvilNoneFor = String.valueOf(orders.flow.orderId());
            orders.flow.nameFailed(n.unit(), AnvilFinder.NO_ANVILS, false);
            return false;
        }
        markJobStart();
        job = Job.Name;
        target = null;
        actions = 0;
        tries = 0;
        stepTicks = 0;
        jobRunTicks = 0;
        step = Step.Walking;
        resetJobState();
        // The anvil may be well outside the stash area
        jobStartedInside = false;
        anvilGoal = new GoalNear(spot.pos(), REACH_RANGE_SQ);
        orders.flow.namingStarted(n.unit());
        namingUnit = n.unit();
        naming = new AnvilNamer(anvilPort, n.slot(), n.name());
        step("order: naming shulker " + (orders.flow.index() + 1) + "/" + orders.flow.units().size() + " at the anvil; next: deliver it.");
        return true;
    }

    private void tickName() {
        AnvilNamer n = naming;
        if (n == null || namingUnit == null) {
            endNaming(null);
            return;
        }
        // The order ended, or is to be cancelled or parked: the namer takes the shulker back out of the anvil, then closes it
        if (!orders.flow.running() || orders.flow.cancelRequested() || orders.flow.parkRequested()) n.abort("the order ended");
        n.tick();
        if (n.step() != AnvilNamer.Step.Walk && n.step() != AnvilNamer.Step.Open) step = Step.Working;
        if (n.step() == AnvilNamer.Step.Done) endNaming(n.outcome());
    }

    /** The trip to the anvil is over: the order is told how it went, and goes on to deliver the shulker. */
    private void endNaming(AnvilNamer.@Nullable Outcome outcome) {
        AnvilNamer n = naming;
        Unit u = namingUnit;
        naming = null;
        namingUnit = null;
        anvilGoal = null;
        activity.releaseOurPathing();
        restoreRules();
        job = null;
        target = null;
        step = Step.Walking;
        wait = CONFIG.delayTicks;
        if (u == null || outcome == null) return;
        orders.anvilHealth.note(orders.flow.orderId(), namingSpot(), outcome);
        if (outcome.stuck()) {
            // The window is left open for a person (closing it with a full inventory could drop the shulker): no longer ours
            // to close, so the sorter gives way until somebody closes it
            activity.forgetWindow();
            lastOpenClickAt = 0;
            openRequestedAt = Long.MAX_VALUE;
            alert("An order shulker is stuck in the anvil and the inventory has no free slot for it. The anvil is left open: make room, then close it.");
        }
        if (outcome.named()) {
            // What the renamed shulker is known by now, so it is still recognised as this unit's
            Item it = n == null ? null : inventoryItem(n.landedSlot());
            if (it != null) u.namedKey = u.kind == Unit.Kind.Shulker ? ManagerIndex.shulkerKey(it) : u.kind == Unit.Kind.Empty ? it.indexKey() : null;
            orders.flow.named(u);
            info("Order: named shulker {}/{}.", orders.flow.index() + 1, orders.flow.units().size());
            debug("Order: the name given was \"{}\".", u.name);
            return;
        }
        String why = outcome.failure() == null ? AnvilNamer.NO_RESULT : outcome.failure();
        orders.flow.nameFailed(u, why, outcome.retry() && !outcome.stuck());
        if (u.nameFailure != null) warn("Order: couldn't name shulker {}/{} ({}), delivering it unnamed.", orders.flow.index() + 1, orders.flow.units().size(), why);
        else debug("Order: naming shulker {}/{} was cut short ({}), trying again.", orders.flow.index() + 1, orders.flow.units().size(), why);
    }

    private static int xpLevel() {
        try {
            return Alerts.online() ? CACHE.getPlayerCache().getThePlayer().getLevel() : -1;
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    private @Nullable Container anvilWindow() {
        if (openContainerId() == 0 || !ownsWindow()) return null;
        Container c = openContainer();
        return c != null && c.getType() == ContainerType.ANVIL ? c : null;
    }

    /** The namer's view of the bot: walking and opening through the pathfinder, clicks through the inventory queue. */
    private final AnvilNamer.Port anvilPort = new AnvilNamer.Port() {
        @Override
        public boolean arrived() {
            return anvilGoal != null && arrivedAt(anvilGoal);
        }

        @Override
        public boolean walk() {
            AnvilSpot.Spot spot = namingSpot();
            if (spot == null || !activity.mayRequest()) return false;
            anvilGoal = requestWalk(spot.pos(), "the anvil");
            wait = 10;
            return true;
        }

        @Override
        public boolean pathing() {
            return activity.ownPathing();
        }

        @Override
        public boolean anvilOpen() {
            return anvilWindow() != null;
        }

        @Override
        public boolean otherWindowOpen() {
            return openContainerId() != 0 && ownsWindow() && anvilWindow() == null;
        }

        @Override
        public boolean openPaused() {
            return StashSorter.this.openPaused();
        }

        @Override
        public void opened() {
            // The first click in the anvil waits the click delay and the container delay (no change at 0)
            if (CONFIG.containerDelay > 0) wait = Math.max(wait, openWait());
        }

        @Override
        public boolean open() {
            AnvilSpot.Spot spot = namingSpot();
            if (spot == null || !activity.mayRequest() || activity.ownPathing()) return false;
            openIdBefore = openContainerId();
            openRequestedAt = lastOpenClickAt = System.currentTimeMillis();
            foreignSinceClick = false;
            requestOpen(spot.pos(), "the anvil");
            wait = CONFIG.settleTicks;
            return true;
        }

        @Override
        public boolean busy() {
            return StashSorter.this.busy();
        }

        @Override
        public boolean place(int slot, String name) {
            Container c = anvilWindow();
            if (c == null || inventoryItem(slot) == null || Item.of(c.getItemStack(0)) != null || holdingCursor()) return false;
            anvilCostBefore = anvilCostSeen;
            RequestFuture f = submit(new ClickItem(c.getContainerId(), toWindow(slot, c), ClickItemAction.LEFT_CLICK),
                new ClickItem(c.getContainerId(), 0, ClickItemAction.LEFT_CLICK), new RenameItem(c.getContainerId(), name));
            if (f == RequestFuture.rejected) return false;
            actions++;
            wait = CONFIG.delayTicks;
            return true;
        }

        @Override
        public @Nullable String resultName() {
            Container c = anvilWindow();
            Item it = c == null ? null : Item.of(c.getItemStack(2));
            if (it == null) return null;
            return it.customName && it.customText != null ? it.customText : "";
        }

        @Override
        public boolean inputPresent() {
            Container c = anvilWindow();
            return c != null && Item.of(c.getItemStack(0)) != null;
        }

        @Override
        public int cost() {
            Container c = anvilWindow();
            return c != null && anvilCostSeen > anvilCostBefore && anvilCostWindow == c.getContainerId() ? anvilCost : -1;
        }

        @Override
        public int xpLevel() {
            return StashSorter.xpLevel();
        }

        @Override
        public int take(int slot) {
            return fromAnvil(2, slot, true);
        }

        @Override
        public int retrieve(int slot) {
            return fromAnvil(0, slot, false);
        }

        /**
         * Moves the stack in this anvil slot to the shulker's own carry slot (empty since the shulker went in), else (an item
         * picked up meanwhile took it) another free carry slot: only carry slots, where the order looks for its shulker. -1: no
         * room (the namer keeps the anvil open and tries again), or not now.
         */
        private int fromAnvil(int anvilSlot, int own, boolean counted) {
            Container c = anvilWindow();
            if (c == null || holdingCursor()) return -1;
            int into = inventoryItem(own) == null ? own : freeCarrySlot();
            if (into == -1) return -1;
            RequestFuture f = submit(new ClickItem(c.getContainerId(), anvilSlot, ClickItemAction.LEFT_CLICK), new ClickItem(c.getContainerId(), toWindow(into, c), ClickItemAction.LEFT_CLICK));
            if (f == RequestFuture.rejected) return -1;
            if (counted) actions++;
            wait = CONFIG.delayTicks;
            return into;
        }

        @Override
        public @Nullable String nameAt(int slot) {
            Item it = inventoryItem(slot);
            return it != null && it.isShulker() && it.customName ? it.customText : null;
        }

        @Override
        public boolean close() {
            if (inventoryTrip || openContainerId() == 0 || !ownsWindow()) return true;
            closeSafely();
            return false;
        }
    };

    // ------------------------------------------------------------------------------------------------------------------
    // Crafting (1.8.0, phase 1): a plain shulker box is crafted when an order's bin or empty-shulker line, or compaction,
    // needs an empty shulker and the stash has none. The decisions are CraftPlanner's (what to make, from what), the trip to
    // the table is CraftTrip's; this is their view of the world. A craft is gathered like an order's bin (a Bin of loads
    // that only lives for the craft, taken with takeLot, out of shulkers with the same unpacking), made at the crafting table,
    // and what is left over goes back where it came from. The crafted shulker is kept (craftKeep) until the bin, the empty
    // line or compaction uses it, so nothing files it away first.

    private enum CraftPhase { Gather, Craft, PutBack }

    private static final class CraftRun {
        /** The order unit it is for, or null for compaction. */
        final @Nullable Unit unit;
        /** What the table health is remembered by: the order id, or "compaction". */
        final String task;
        CraftPlanner.Plan plan;
        /** How many were asked for, and trips to the table made. */
        final int wanted;
        int trips;
        /** The loads to gather. Only for the craft: never packed. */
        final Bin bin;
        CraftPhase phase = CraftPhase.Gather;
        int jobs, roomTrips, putBackTries, made;
        @Nullable String failure;
        boolean retry;
        /** Carry slots the craft owns now: what was gathered, what the table gave. */
        final Set<Integer> owned = new HashSet<>();
        /** Carry slots the table's shulker boxes were put into on this trip: only these are kept as crafted (1.8.1). */
        final List<Integer> resultSlots = new ArrayList<>();

        /** The chunk of an order's craft load this run makes (1.15.0), or null for the shulker box, chest and table crafts. */
        @Nullable OrderChunk oc;
        /** Why a chunk of an order's craft load stopped (a CraftOrder code) when the run itself knows, and whether the grid was left stuck. */
        @Nullable String code;
        boolean stuck;

        /** A crafting table is being crafted in the inventory's 2x2 grid (1.10.0): not a shulker box, no table needed. */
        boolean forTable;
        /** The table is for a craft order: its placement counts against the caps. */
        boolean forOrderCraft;
        /** The carry slot the crafted table went into (only a slot the table's own result went to), or -1. */
        int tableSlot = -1;

        /** Chests are being crafted for the delivery storage (1.9.0), not shulker boxes. */
        boolean forChests() {
            return CHEST.equals(plan.item());
        }

        CraftRun(@Nullable Unit unit, String task, CraftPlanner.Plan plan, List<Order.Lot> lots) {
            this.unit = unit;
            this.task = task;
            this.plan = plan;
            this.wanted = plan.count();
            this.bin = new Bin(new Unit(Unit.Kind.Bin, "", new ArrayList<>(lots)));
        }
    }

    private static final String COMPACTION_TASK = "compaction";
    private static final String CHEST = "minecraft:chest", CHEST_TASK = "delivery chests";
    private static final int CRAFT_KEEP_TICKS = 20 * 60 * 5;
    private static final int CRAFT_COMPACT_COOLDOWN = 20 * 60 * 10;
    /** Compaction asked for a table and none could be had: not asked again for 5 minutes (as the addon). */
    private static final int TABLE_COOLDOWN_TICKS = 20 * 60 * 5;

    private @Nullable CraftRun craft;
    private @Nullable CraftTrip crafter;
    private @Nullable GoalNear tableGoal;
    // Carry slots holding a crafted shulker box not used yet, and until when (ticks) it is kept from being put away
    private final Map<Integer, Long> craftKeep = new ConcurrentHashMap<>();
    private final CraftHealth craftHealth = new CraftHealth();
    private long craftCompactPausedUntil;
    // The dashboard's crafting card, worked out on the tick, read by the Stash Manager
    private volatile @Nullable String craftCard;
    private volatile @Nullable String craftCardDetail;

    /** Shulker boxes crafted in the last hour. */
    public int craftedLastHour() {
        return craftHealth.lastHour(System.currentTimeMillis());
    }

    /** The dashboard card's title while a craft is under way ("Crafting: Shulker Box x2"), or null. */
    public @Nullable String craftCardTitle() {
        return craftCard;
    }

    public @Nullable String craftCardDetail() {
        return craftCardDetail;
    }

    private static boolean craftingOn() {
        return CONFIG.enableCrafting;
    }

    private static @Nullable Recipes recipesOrNull() {
        try {
            return Recipes.current();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // What crafting a shulker box may use: everything its recipes need, three steps deep (shells, chests, planks, logs)
    private @Nullable Set<String> craftIds;

    private Set<String> craftIds() {
        Set<String> ids = craftIds;
        if (ids != null) return ids;
        Recipes r = recipesOrNull();
        ids = new HashSet<>();
        if (r != null) for (String id : r.inputsOf(OrderFlow.PLAIN_SHULKER, CraftPlanner.MAX_DEPTH)) if (!StockPlanner.isShulkerKey(id)) ids.add(id);
        craftIds = ids;
        return ids;
    }

    private Set<String> neverUse() {
        Set<String> out = new HashSet<>();
        // The command replaces the list whole (never changes it in place), so this one is read as it was
        List<String> listed = CONFIG.craftNeverUse;
        if (listed != null) for (String id : listed) if (id != null && !id.isBlank()) out.add(Recipes.ns(id.trim().toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    /** What crafting may take from the stash now, by the order source rules. */
    private CraftPool.Stock craftStock() {
        Stash s = orders.st();
        if (s == null) return CraftPool.Stock.empty();
        return SourceRules.craftSurvey(s.boxes, craftIds(), orders.reservedShulkerKeys(), CONFIG.useNamedShulkers, neverUse(), sourceLive);
    }

    /** Why the crafting table can't be used now (none set, another dimension, gone, trouble this task), or null. */
    private @Nullable String tableProblem(String task) {
        CraftingTableSpot.Spot spot = CraftingTableSpot.get();
        if (spot != null) {
            if (!CraftingTableSpot.here(spot)) return "the crafting table is in another dimension";
            boolean gone = Containers.isLoaded(spot.pos()) && !CraftingTableSpot.isTable(spot.pos());
            if (!gone) return craftHealth.problem(task, spot);
        }
        // None, or the one that was is gone: a craft goes on when one can be found, taken from the stash or crafted. Nothing is
        // done here and nothing is searched for (this is also the order pre-check): see ensureTable
        return tableObtainable() ? null : CraftingTableFinder.NONE + (spot != null ? ": the crafting table is gone (the block there isn't one)" : "");
    }

    /** A table is set and usable, or one could be had (the plan may count on it). */
    private boolean tableForPlan() {
        return CraftingTableSpot.isSet() || tableObtainable();
    }

    private CraftPlanner.Limits craftLimits(boolean forOrder) {
        int leftOrder = !forOrder ? Integer.MAX_VALUE : CONFIG.craftMaxPerOrder - orders.flow.craftedCount();
        int leftHour = CONFIG.craftMaxPerHour - craftedLastHour();
        return new CraftPlanner.Limits(tableForPlan(), Math.max(1, CONFIG.craftMaxOpsPerTask), leftOrder, leftHour, neverUse());
    }

    /** The units of the order still to do (the one in hand and after): what a craft must not take from. */
    private List<Unit> remainingUnits() {
        List<Unit> all = orders.flow.units();
        int from = Math.min(orders.flow.index(), all.size());
        return new ArrayList<>(all.subList(from, all.size()));
    }

    /** The carry slots holding an unnamed empty shulker box (any colour, or plain only). */
    private List<Integer> carriedEmpties(boolean plainOnly) {
        List<Integer> out = new ArrayList<>();
        for (int slot : carriedSlots()) {
            Item it = inventoryItem(slot);
            if (emptyShulker(it) && (!plainOnly || it.id.equals("shulker_box"))) out.add(slot);
        }
        return out;
    }

    /** Free carry slots a craft of {@code count} needs: its loads, a slot per step made, the results, and one spare. */
    private static int craftRoom(CraftRun r, int loads) {
        CraftPlanner.Plan plan = r.plan;
        // An order's craft load is stacks of an item: its result is a stack or two, not an item a slot
        if (r.oc != null) return CraftBatch.room(plan, loads, r.oc.key, StashSorter::stackOf);
        return loads + Math.max(0, plan.steps().size() - 1) + plan.count() + 1;
    }

    /** Plans a craft of {@code count} plain shulker boxes. @return the run, or null with why in {@code why[0]} */
    private @Nullable CraftRun planCraft(@Nullable Unit unit, String task, int count, String[] why) {
        Recipes recipes = recipesOrNull();
        if (recipes == null) {
            why[0] = "no recipe table yet (the Stash Manager server sends it; crafting stays off until it has)";
            return null;
        }
        String table = tableProblem(task);
        if (table != null) {
            why[0] = table;
            return null;
        }
        CraftPool.Stock stock = craftStock();
        List<Unit> others = unit == null ? List.of() : remainingUnits();
        CraftPlanner.Plan plan = CraftPlanner.plan(recipes, OrderFlow.PLAIN_SHULKER, count, CraftPool.residual(stock, others), craftLimits(unit != null));
        if (!plan.ok()) {
            why[0] = plan.refusal();
            return null;
        }
        List<Order.Lot> lots = CraftPool.lots(stock, plan.fromStock(), others);
        return new CraftRun(unit, task, plan, lots);
    }

    /** Plans a craft of {@code count} chests for the delivery storage (crafting is on). @return the run, or null with why in {@code why[0]} */
    private @Nullable CraftRun planChestCraft(int count, String[] why) {
        Recipes recipes = recipesOrNull();
        if (recipes == null) {
            why[0] = "no recipe table yet (the Stash Manager server sends it; crafting stays off until it has)";
            return null;
        }
        String table = tableProblem(CHEST_TASK);
        if (table != null) {
            why[0] = table;
            return null;
        }
        CraftPool.Stock stock = craftStock();
        List<Unit> others = orders.flow.holdsOrder() ? remainingUnits() : List.of();
        // Chests are not shulker boxes: the per-order and hourly shulker caps do not apply, the grid fills of one task still do
        CraftPlanner.Limits limits = new CraftPlanner.Limits(tableForPlan(), Math.max(1, CONFIG.craftMaxOpsPerTask), Integer.MAX_VALUE, Integer.MAX_VALUE, neverUse());
        CraftPlanner.Plan plan = CraftPlanner.plan(recipes, CHEST, count, CraftPool.residual(stock, others), limits);
        if (!plan.ok()) {
            why[0] = plan.refusal();
            return null;
        }
        return new CraftRun(null, CHEST_TASK, plan, CraftPool.lots(stock, plan.fromStock(), others));
    }

    /** Starts a craft for an order unit (OrderFlow.Craft). @return true when a job was started */
    private boolean startOrderCraft(OrderFlow.Craft c) {
        if (compact != null || craft != null || anvilRun != null) return false;
        String[] why = new String[1];
        Unit u = c.unit();
        String task = String.valueOf(orders.flow.orderId());
        CraftRun run = planCraft(u, task, c.count(), why);
        // Fewer than asked can still do this one
        if (run == null && c.count() > 1) run = planCraft(u, task, 1, why);
        if (run != null) {
            // The craft itself is possible (the preview): only now is a crafting table found, put down or crafted for it, and the
            // order's craft try is not spent before it exists
            boolean hadTable = CraftingTableSpot.isSet();
            switch (ensureTable(task, why)) {
                case Wait -> {
                    return false;
                }
                case Started -> {
                    return job != null;
                }
                case Failed -> run = null;
                case Ready -> {
                    if (!hadTable) {
                        run = planCraft(u, task, c.count(), why);
                        if (run == null && c.count() > 1) run = planCraft(u, task, 1, why);
                    }
                }
            }
        }
        if (run == null) {
            orders.flow.craftFailed(u, why[0], false);
            step("order: no empty shulker for shulker " + (orders.flow.index() + 1) + "/" + orders.flow.units().size() + ", couldn't craft one: " + why[0] + ".");
            return false;
        }
        orders.flow.craftStarted(u);
        return beginCraft(run, "order: shulker " + (orders.flow.index() + 1) + "/" + orders.flow.units().size() + " has no empty shulker");
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Crafting for orders (1.15.0, CRAFT-CONTRACT.md "Bot execution"). A craft line of an order is a load of its bin that is made at the
    // crafting table the website set for the stash (not the one `stash craftingtable` knows) before the bin is built. The decisions are
    // pure and tested: OrderFlow (which load is next, what is short and why, the saved ledger), CraftSub (the sub-plan from the order's
    // budget), CraftBatch (how much one chunk makes for the free carry slots). This runs a chunk as a CraftRun (gather from the stash,
    // trip to the table, put back what is left), then makes the result a stack of exactly the load's size and keeps it, and the
    // crafted surplus, in carry slots that nothing else touches until the bin is built (the surplus until the order ends). All on the
    // bot tick thread.

    /** The chunk of a craft load a CraftRun is making for an order. */
    private static final class OrderChunk {
        final Order.Lot lot;
        final String key;
        final int target, stack;
        long fillsMade;
        /** Carry slots the table's result went into on the chunk's trips (one slot: a chunk is never more than a stack). */
        final LinkedHashSet<Integer> resultSlots = new LinkedHashSet<>();
        boolean settled, splitSent;
        int splitTries, made, lotSlot = -1, waits;
        /** What was gathered from the stash for this chunk (by id), recorded when it began: the budget is charged from it, never from a later plan. */
        Map<String, Long> gathered = Map.of();
        long splitAt;
        @Nullable String code;

        OrderChunk(Order.Lot lot, int target, int stack) {
            this.lot = lot;
            this.key = Recipes.ns(lot.key());
            this.target = target;
            this.stack = stack;
        }

        String id() {
            return shortId(key);
        }
    }

    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private static int stackOf(String id) {
        try {
            var data = ItemRegistry.REGISTRY.get(shortId(Recipes.ns(CraftSlots.baseOf(id))));
            return data == null ? 64 : Math.max(1, data.stackSize());
        } catch (RuntimeException | LinkageError e) {
            return 64;
        }
    }

    // Carry slots holding a crafted load for the bin in hand (until it is built) and the crafted surplus carried between bins (until the
    // order ends), by slot and the item id in it. Nothing puts them away meanwhile (craftReserved).
    private final Map<Integer, String> orderCraftLots = new ConcurrentHashMap<>(), orderCraftSurplus = new ConcurrentHashMap<>();
    /** Slots of made bases that the enchant items did not use (released with their bin, draft 3.4): surplus of the craft, but never packed into a shulker either. */
    private final Set<Integer> orderReleasedBases = ConcurrentHashMap.newKeySet();
    // The stacks set aside for an order that ended, until they are put back (the final report waits for that, a little)
    private final Map<Integer, String> orderReturnWatch = new ConcurrentHashMap<>();
    // Grid fills made for craft orders in the last hour
    private final CraftHealth orderFills = new CraftHealth();
    // The website's table could not be reached or opened (twice in an order it is given up on), and when
    private CraftingTableSpot.@Nullable Spot tableTrouble;
    private long tableTroubleAt;
    // The website table as last seen standing (a crafting table in a loaded chunk)
    private CraftingTableSpot.@Nullable Spot tableSeen;
    // Per order: times the table could not be reached or opened, and times a chunk was cut short (each is tried once more)
    private int orderTableFails, orderInterrupts;
    private @Nullable String orderCounted;
    private static final long TABLE_SEEN_TICKS = 20L * 60 * 10;

    private @Nullable CraftSettings craftSettings() {
        Stash s = orders.st();
        return s == null ? null : CraftStore.shared().get(ManagerIndex.clamp(s.server, 256), ManagerIndex.clamp(s.name, 256));
    }

    private static CraftingTableSpot.Spot websiteSpot(CraftSettings.Table t) {
        return new CraftingTableSpot.Spot(t.dimension(), t.x(), t.y(), t.z());
    }

    /** Craft orders can be considered: the setting and crafting are on, and a recipe table was fetched. */
    public boolean craftOrdersBase() {
        return CONFIG.craftOrders && CONFIG.enableCrafting && Recipes.current() != null;
    }

    /**
     * Craft orders can be claimed: the above, and either a website table that works or, with none set, the auto path (the bot finds a table,
     * places one from the stash, or crafts one).
     */
    public boolean craftOrdersCapable() {
        if (!craftOrdersBase()) return false;
        CraftSettings cs = craftSettings();
        return CraftTableState.capable(true, cs != null && cs.table() != null, craftTableState());
    }

    /** Grid fills made for craft orders in the last hour. */
    public int craftOrderFillsLastHour() {
        return orderFills.lastHour(System.currentTimeMillis());
    }

    /** The dimension the stash is in: as recorded, else the one the bot was in when it read one of its containers this session; else null. */
    private @Nullable String effectiveDimension(Stash s) {
        String read = null;
        for (Box box : s.boxes) if (!box.delivery && box.readIn != null) read = box.readIn;
        return CraftBounds.dimension(s.dimension, read);
    }

    /** Where the stash is for judging a table: its perimeter, else its containers. Does not depend on where the bot stands. */
    private CraftBounds.StashArea stashArea(Stash s) {
        List<BlockPos> at = new ArrayList<>();
        for (Box box : s.boxes) if (!box.delivery) at.addAll(box.parts);
        return new CraftBounds.StashArea(s.bounds(), at, CONFIG.radius, CONFIG.height);
    }

    private CraftTableState.@Nullable State tableStateCache;
    private long tableStateAt = Long.MIN_VALUE / 4;
    private boolean @Nullable [] autoFlags;
    private long autoFlagsAt = Long.MIN_VALUE / 4;
    private int autoFlagsVersion = -1;

    /** The state of the crafting table for the heartbeat: the website's, or "auto" with none set. Worked out at most once a second. */
    public CraftTableState.State craftTableState() {
        CraftTableState.State s = tableStateCache;
        if (s != null && tickCount >= tableStateAt && tickCount - tableStateAt < 20) return s;
        s = computeTableState();
        tableStateCache = s;
        tableStateAt = tickCount;
        return s;
    }

    private CraftTableState.State computeTableState() {
        Stash st = orders.st();
        CraftSettings cs = craftSettings();
        CraftSettings.Table t = cs == null ? null : cs.table();
        String dim = st == null ? null : effectiveDimension(st);
        CraftBounds.StashArea area = st == null ? new CraftBounds.StashArea(null, List.of(), 0, 0) : stashArea(st);
        if (t != null) return CraftTableState.website(websiteWhy(t, dim, area));
        if (dim == null || area.empty()) return CraftTableState.auto(dim, !area.empty(), false, false, false, false);
        boolean[] f = autoFlags;
        // The survey of the stash (a table item, planks) is made again when the stash changed (at most every 10 s) and at least every minute
        if (f == null || tickCount < autoFlagsAt || tickCount - autoFlagsAt >= 1200 || (Stashes.version != autoFlagsVersion && tickCount - autoFlagsAt >= 200)) {
            autoFlagsVersion = Stashes.version;
            CraftingTableSpot.Spot own = CraftingTableSpot.get();
            boolean ownOk = false, found = false, stock = false, material = false;
            try {
                if (own != null && CraftingTableSpot.here(own) && CraftBounds.areaWhy(dim, area, new CraftSettings.Table(own.x(), own.y(), own.z(), own.dimension()), 64) == null
                    && (!Containers.isLoaded(own.pos()) || CraftingTableSpot.isTable(own.pos()))) ownOk = true;
                found = nearestScannedTable() != null;
                stock = !tableLots().isEmpty();
                material = tableMaterial() != CraftingTableFinder.Material.NONE;
            } catch (RuntimeException | LinkageError e) {
                // Not known now: counted as not there
            }
            f = new boolean[] {ownOk, found, stock, material};
            autoFlags = f;
            autoFlagsAt = tickCount;
        }
        return CraftTableState.auto(dim, true, f[0], f[1], f[2], f[3]);
    }

    /** Why the website table can't be used, or null when it can. */
    private @Nullable String websiteWhy(CraftSettings.Table t, @Nullable String dim, CraftBounds.StashArea area) {
        String w = CraftBounds.areaWhy(dim, area, t, 64);
        if (w != null) return w;
        CraftingTableSpot.Spot spot = websiteSpot(t);
        try {
            if (!CraftingTableSpot.here(spot)) return CraftBounds.WRONG_DIMENSION;
            // Once the table was seen standing, leaving its chunk unloaded does not make it unknown again ("not loaded" is only for one never seen)
            if (!Containers.isLoaded(spot.pos())) return spot.equals(tableSeen) ? null : CraftBounds.NOT_LOADED;
            if (!CraftingTableSpot.isTable(spot.pos())) {
                tableSeen = null;
                return CraftBounds.NOT_TABLE;
            }
            tableSeen = spot;
            if (spot.equals(tableTrouble) && tickCount - tableTroubleAt < 20L * 60 * 10) return CraftBounds.PATH_FAILED;
            if (!CraftingTableFinder.standBeside(spot.pos(), anvilBlocks)) return CraftBounds.NO_STAND;
        } catch (RuntimeException | LinkageError e) {
            return CraftBounds.NOT_LOADED;
        }
        return null;
    }

    /** The state of the crafting table for the heartbeat ({@code unset}, {@code ok}, {@code auto}, ...). Never a position. */
    public String craftTableStatus() {
        return craftTableState().status();
    }

    /** Why the crafting table can't be used (a CraftBounds code), or null. */
    public @Nullable String craftTableWhy() {
        return craftTableState().why();
    }

    /** The bot's own crafting table in use (found, placed, crafted or set by hand), when no website table is set. */
    public ManagerIndex.@Nullable BotTable botTable() {
        CraftSettings cs = craftSettings();
        if (cs != null && cs.table() != null) return null;
        CraftingTableSpot.Spot spot = CraftingTableSpot.get();
        if (spot == null || CraftingTableSpot.websiteActive()) return null;
        Stash st = orders.st();
        if (st == null || CraftBounds.areaWhy(effectiveDimension(st), stashArea(st), new CraftSettings.Table(spot.x(), spot.y(), spot.z(), spot.dimension()), 64) != null) return null;
        String source = CraftingTableSpot.source();
        return new ManagerIndex.BotTable(spot.x() + "," + spot.y() + "," + spot.z(), CraftingTableSpot.SET.equals(source) ? CraftingTableSpot.FOUND : source);
    }

    /** What `stash craftorders` says about the crafting table. No position. */
    public String craftOrderTableLine() {
        CraftSettings cs = craftSettings();
        CraftTableState.State state = craftTableState();
        String why = state.why() == null ? "" : ", " + state.why();
        if (cs == null) return "nothing heard from the server yet; the bot finds, places or crafts one when none is set (" + state.status() + why + ")";
        if (cs.table() == null) return "none set on the website: the bot finds, places or crafts one (" + state.status() + why + ")";
        return "set on the website (position hidden): " + state.status() + why;
    }

    /** Crafting tables standing in the stash area, as the last search found them ("x,y,z", at most 8): the website's quick pick. */
    public List<String> seenTables() {
        List<String> out = new ArrayList<>();
        try {
            for (BlockPos p : tableScanFound) {
                if (CraftingTableFinder.isTable(anvilBlocks.name(p.x(), p.y(), p.z()))) out.add(p.x() + "," + p.y() + "," + p.z());
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        java.util.Collections.sort(out);
        return out.size() > ManagerIndex.MAX_SEEN ? new ArrayList<>(out.subList(0, ManagerIndex.MAX_SEEN)) : out;
    }

    /** The website's table is not in the stash's dimension, or is more than 64 blocks outside the stash area: it is never walked to. */
    private boolean tableOutOfBounds(CraftSettings.Table t) {
        Stash s = orders.st();
        return s == null || CraftBounds.areaWhy(effectiveDimension(s), stashArea(s), t, 64) != null;
    }

    /**
     * The table in the settings against the limits, for a craft order with no website table. One set by hand that is outside them is left exactly
     * as it is, and craft orders use a table of their own meanwhile (CraftingTableSpot's side slot); one the bot found or placed that is outside
     * them is forgotten.
     */
    private void reconcileOwnTable(@Nullable String dim, CraftBounds.StashArea area) {
        CraftingTableSpot.Spot saved = CraftingTableSpot.persisted();
        if (saved == null) {
            CraftingTableSpot.sideMode(false);
            return;
        }
        if (CraftBounds.areaWhy(dim, area, new CraftSettings.Table(saved.x(), saved.y(), saved.z(), saved.dimension()), 64) == null) {
            CraftingTableSpot.sideMode(false);
            return;
        }
        if (CraftingTableSpot.SET.equals(CraftingTableSpot.persistedSource())) {
            CraftingTableSpot.sideMode(true);
            return;
        }
        CraftingTableSpot.sideMode(false);
        CraftingTableSpot.clear();
        tableChanged();
    }

    /** The reason the website's table can't be used for this order now (a CraftOrder code), or null. */
    private @Nullable String orderTableProblem(String task, @Nullable CraftSettings cs) {
        CraftSettings.Table t = cs == null ? null : cs.table();
        if (t == null) return CraftOrder.NO_TABLE;
        if (tableOutOfBounds(t)) return CraftOrder.TABLE_BLOCKED;
        CraftingTableSpot.Spot spot = websiteSpot(t);
        if (!CraftingTableSpot.here(spot)) return CraftOrder.TABLE_BLOCKED;
        if (Containers.isLoaded(spot.pos()) && !CraftingTableSpot.isTable(spot.pos())) return CraftOrder.TABLE_MISSING;
        if (craftHealth.problem(task, spot) != null) return CraftOrder.TABLE_BLOCKED;
        return null;
    }

    /** What is carried of crafted items not used yet (made by the order's chunks), by namespaced id. */
    private Map<String, Long> orderSurplusCounts() {
        Map<String, Long> out = new HashMap<>();
        for (var e : new ArrayList<>(orderCraftSurplus.entrySet())) {
            Item it = inventoryItem(e.getKey());
            if (it != null && it.id.equals(e.getValue())) out.merge("minecraft:" + it.id, (long) it.count, Long::sum);
        }
        return out;
    }

    /** The carry slots set aside for an order's crafting: crafted loads waiting for their bin, and the surplus. Cleared when the order is over. */
    private Set<Integer> orderCraftHeld() {
        if (orderCraftLots.isEmpty() && orderCraftSurplus.isEmpty()) return Set.of();
        if (!orders.flow.running()) {
            orderCraftLots.clear();
            orderCraftSurplus.clear();
            orderReleasedBases.clear();
            return Set.of();
        }
        // A crafted load is kept for as long as a bin still to build has it; one whose bin was skipped or given up on is let go
        List<Order.Lot> wanted = new ArrayList<>();
        List<Unit> all = orders.flow.units();
        for (int i = Math.min(orders.flow.index(), all.size()); i < all.size(); i++) {
            Unit u = all.get(i);
            if (u.built || u.have) continue;
            for (Order.Lot lot : u.lots) if (lot.crafted()) wanted.add(lot);
        }
        orderCraftLots.entrySet().removeIf(e -> {
            Item it = inventoryItem(e.getKey());
            // A crafted load is a plain stack: a slot that now holds something with components (an enchanted result in the slot a base vacated) is no longer one
            if (it == null || !it.id.equals(e.getValue()) || !freshPlain(it)) return true;
            for (int i = 0; i < wanted.size(); i++) {
                Order.Lot lot = wanted.get(i);
                if (Recipes.ns(lot.key()).equals("minecraft:" + it.id) && lot.count() == it.count) {
                    wanted.remove(i);
                    return false;
                }
            }
            return true;
        });
        orderCraftSurplus.entrySet().removeIf(e -> {
            Item it = inventoryItem(e.getKey());
            return it == null || !it.id.equals(e.getValue());
        });
        orderReleasedBases.removeIf(slot -> {
            Item it = inventoryItem(slot);
            return it == null || !orderCraftSurplus.containsKey(slot) || !freshPlain(it);
        });
        Set<Integer> out = new HashSet<>(orderCraftLots.keySet());
        out.addAll(orderCraftSurplus.keySet());
        return out;
    }

    /** The plain stock craft orders may take from: the ids of the order's take, plain stacks only (the server counts plain keys). */
    private CraftPool.Stock orderStock(CraftLedger ledger) {
        Stash s = orders.st();
        if (s == null) return CraftPool.Stock.empty();
        Set<String> ids = new HashSet<>(ledger.take().keySet());
        return SourceRules.craftSurvey(s.boxes, ids, orders.reservedShulkerKeys(), CONFIG.useNamedShulkers, neverUse(), sourceLive).plainOnly();
    }

    /** Starts the next chunk of crafting for a bin (OrderFlow.CraftLoads). @return true when a job was started */
    private boolean startOrderCraftLoads(OrderFlow.CraftLoads c) {
        if (compact != null || craft != null || anvilRun != null || chestRun != null || repair != null || repairTrip != null) return false;
        OrderFlow flow = orders.flow;
        Unit u = c.unit();
        CraftLedger ledger = flow.craftLedger();
        int idx = flow.nextCraftLot(u);
        if (ledger == null || idx < 0) return false;
        Order.Lot lot = u.lots.get(idx);
        String key = Recipes.ns(lot.key());
        String task = String.valueOf(flow.orderId());
        String here = "order: shulker " + (flow.index() + 1) + "/" + flow.units().size();

        // The website's table: none set, not a crafting table, or given up on: every craft line still to make is short, the order goes on
        CraftSettings cs = craftSettings();
        boolean website = cs != null && cs.table() != null;
        if (website) {
            String bad = orderTableProblem(task, cs);
            if (bad != null) {
                step(here + ": no crafting from now on (" + CraftOrder.describe(bad) + ").");
                flow.craftAbandon(bad);
                return false;
            }
        }
        else {
            // No website table: a table standing in the stash area is found, else one is taken from the stash and put down, else one is crafted
            // (ensureTable, as for shulker boxes), and the bot's own table is used from then on; if it goes missing this starts again
            Stash ownStash = orders.st();
            if (ownStash == null || tickCount < autoRetryAt) return false;
            String dimE = effectiveDimension(ownStash);
            CraftBounds.StashArea areaE = stashArea(ownStash);
            reconcileOwnTable(dimE, areaE);
            String[] twhy = new String[1];
            TableWay way;
            tableForOrder = task;
            try {
                way = ensureTable(task, twhy);
            } finally {
                tableForOrder = null;
            }
            switch (way) {
                case Wait -> {
                    return false;
                }
                case Started -> {
                    if (job != null || anvilRun != null || craft != null) {
                        autoStartFails = 0;
                        return true;
                    }
                    // Nothing was started: a failure, tried again after a short wait, and given up on after three
                    if (++autoStartFails >= 3) {
                        autoStartFails = 0;
                        step(here + ": no crafting table could be put down.");
                        flow.craftAbandon(CraftOrder.NO_TABLE);
                        return false;
                    }
                    autoRetryAt = tickCount + 100;
                    return false;
                }
                case Failed -> {
                    step(here + ": no crafting table to use: " + twhy[0] + ".");
                    flow.craftAbandon(CraftOrder.NO_TABLE);
                    return false;
                }
                case Ready -> autoStartFails = 0;
            }
            CraftingTableSpot.Spot own = CraftingTableSpot.get();
            if (own == null) return false;
            String limit = CraftBounds.areaWhy(dimE, areaE, new CraftSettings.Table(own.x(), own.y(), own.z(), own.dimension()), 64);
            String trouble = limit != null ? limit : tableProblem(task);
            if (trouble != null) {
                // A table that turns out unusable is given up on and the search is made once more before the lines are given up
                if (!task.equals(autoSkipFor)) {
                    autoSkipFor = task;
                    autoSkips = 0;
                }
                if (autoSkips++ < 1 && !CraftingTableSpot.SET.equals(CraftingTableSpot.source())) {
                    tableSkip.add(own.pos());
                    CraftingTableSpot.clear();
                    tableChanged();
                    step(here + ": this crafting table can't be used (" + trouble + "), looking for another.");
                    return false;
                }
                step(here + ": the crafting table can't be used (" + trouble + ").");
                flow.craftAbandon(CraftOrder.TABLE_BLOCKED);
                return false;
            }
        }

        // What a chunk makes: as much of the load as the budget, the caps and the free carry slots allow
        ledger.setSurplus(orderSurplusCounts());
        long fillsLeft = Math.min((long) ledger.maxFills() - ledger.fills(), (long) CONFIG.craftOrderMaxPerHour - craftOrderFillsLastHour());
        CraftPool.Stock stock = orderStock(ledger);
        List<Unit> others = remainingUnits();
        CraftBatch.Choice choice = CraftBatch.choose(ledger, key, lot.count(), freeCarryCount(), fillsLeft, StashSorter::stackOf, need -> CraftPool.lots(stock, need, others).size());
        if (!choice.ok()) {
            String reason = choice.reason() == null ? CraftOrder.INGREDIENTS_SHORT : choice.reason();
            step(here + ": " + lot.count() + " " + Recipes.pretty(key) + " can't be crafted: " + CraftOrder.describe(reason) + (choice.detail() == null ? "" : " (" + choice.detail() + ")") + ".");
            flow.craftLotShort(u, idx, reason);
            return false;
        }
        CraftPlanner.Plan plan = choice.plan();
        List<Order.Lot> gather = CraftPool.lots(stock, plan.fromStock(), others);
        // The stash must really give what the plan takes (the budget is the server's count, the stash is what is here now)
        Map<String, Long> got = new HashMap<>();
        for (Order.Lot g : gather) got.merge(Recipes.ns(baseIdOf(g.key())), (long) g.count(), Long::sum);
        for (var need : plan.fromStock().entrySet()) {
            if (got.getOrDefault(Recipes.ns(need.getKey()), 0L) < need.getValue()) {
                step(here + ": " + lot.count() + " " + Recipes.pretty(key) + " can't be crafted: the stash has no " + Recipes.pretty(need.getKey()) + " to give.");
                flow.craftLotShort(u, idx, CraftOrder.INGREDIENTS_SHORT);
                return false;
            }
        }

        CraftRun run = new CraftRun(u, task, plan, gather);
        run.oc = new OrderChunk(lot, choice.count(), stackOf(key));
        run.oc.gathered = CraftCharge.gathered(gather);
        // The surplus carried from earlier chunks is the craft's to use
        for (int slot : orderCraftSurplus.keySet()) if (inventoryItem(slot) != null) run.owned.add(slot);
        // The saved ledger no longer lists surplus this chunk may consume: a restart before the chunk is recorded refunds nothing it used
        ledger.chunkStarted();
        flow.craftLedgerChanged();
        if (website) CraftingTableSpot.useWebsite(websiteSpot(cs.table()));
        try {
            tableRunDim = Stash.currentDimension();
            flow.craftLoadsStarted(u);
            return beginCraft(run, here + " has " + lot.count() + " " + Recipes.pretty(key) + " to craft");
        } catch (RuntimeException e) {
            // The website's table is only the craft's for as long as the craft runs
            craft = null;
            CraftingTableSpot.useWebsite(null);
            throw e;
        }
    }

    /**
     * The chunk's trips are over: its result is made a stack of exactly the load's size (the rest, a few items of the last fill, is split
     * off), and the stacks to keep are set aside. @return true while clicks are going out or awaited (asked again next tick)
     */
    private boolean settleOrderChunk(CraftRun r) {
        OrderChunk oc = r.oc;
        if (oc == null || oc.settled) return false;
        if (busy() || holdingCursor() || openContainerId() != 0) {
            // A window of someone else that stays open: the chunk is given up on after ten seconds (what is carried goes back)
            if (++oc.waits > 200) {
                oc.code = CraftOrder.INTERRUPTED;
                oc.made = 0;
                oc.settled = true;
                return false;
            }
            return true;
        }
        int slot = -1;
        for (int s : oc.resultSlots) {
            Item it = inventoryItem(s);
            if (it == null || !it.id.equals(oc.id())) continue;
            if (slot < 0 || it.count > inventoryItem(slot).count) slot = s;
        }
        Item result = slot < 0 ? null : inventoryItem(slot);
        int have = result == null ? 0 : result.count;
        if (result != null && have > oc.target) {
            // A fill makes a few more than wanted: the stack is split, the extra stays carried as surplus
            int free = freeCarrySlot();
            ClickPlanner.Clicks clicks = free == -1 || oc.splitTries >= 3 ? null : ClickPlanner.plan(Math.max(1, result.maxStack), have, 0, have - oc.target);
            if (clicks == null || clicks.ops().length == 0) {
                // No room or no way to split it: the load is not made (everything goes back)
                oc.code = CraftOrder.INVENTORY_FULL;
                oc.made = 0;
                oc.lotSlot = -1;
                oc.settled = true;
                return false;
            }
            if (oc.splitSent && tickCount - oc.splitAt < 3) return true;
            ClickItem[] actions = new ClickItem[clicks.ops().length];
            for (int i = 0; i < actions.length; i++) {
                int op = clicks.ops()[i];
                actions[i] = new ClickItem(0, op < 2 ? invSlot(slot) : invSlot(free), op % 2 == 1 ? ClickItemAction.RIGHT_CLICK : ClickItemAction.LEFT_CLICK);
            }
            if (submit(actions) == RequestFuture.rejected) return true;
            oc.splitSent = true;
            oc.splitTries++;
            oc.splitAt = tickCount;
            stashHeld.put(free, result.id);
            r.owned.add(free);
            wait = CONFIG.delayTicks;
            return true;
        }
        oc.made = have;
        oc.lotSlot = result == null ? -1 : slot;
        oc.settled = true;
        // Kept: the load's stack until its bin is built, and (when anything was crafted) every other stack of the craft as surplus
        if (oc.lotSlot >= 0) orderCraftLots.put(oc.lotSlot, oc.id());
        if (oc.fillsMade > 0) {
            for (int s : new ArrayList<>(r.owned)) {
                Item it = inventoryItem(s);
                if (it == null || s == oc.lotSlot || it.isShulker() || sourceShulkers.containsKey(s)) continue;
                orderCraftSurplus.put(s, it.id);
            }
        }
        // Saved at once: the surplus counts are what is really carried now
        CraftLedger saving = orders.flow.craftLedger();
        if (saving != null) {
            saving.setSurplus(orderSurplusCounts());
            orders.flow.craftLedgerChanged();
        }
        return false;
    }

    /** The chunk is over (and put back): what it made and used is reported to the order, and the next round or the next bin goes on. */
    private void finishOrderChunk(CraftRun r) {
        OrderChunk oc = r.oc;
        OrderFlow flow = orders.flow;
        Unit u = r.unit;
        CraftingTableSpot.useWebsite(null);
        if (oc == null || u == null) return;
        long now = System.currentTimeMillis();
        if (oc.fillsMade > 0) orderFills.crafted(now, (int) Math.min(Integer.MAX_VALUE, oc.fillsMade));
        int idx = u.lots.indexOf(oc.lot);
        if (idx < 0 || !u.lots.get(idx).craft()) idx = flow.nextCraftLot(u);
        CraftLedger ledger = flow.craftLedger();
        if (ledger != null) ledger.setSurplus(orderSurplusCounts());
        boolean cancelled = flow.cancelRequested() || !flow.running();
        String code = oc.code != null ? oc.code : oc.made >= oc.target ? null : r.code != null ? r.code : CraftOrder.reasonOf(r.failure, r.stuck, cancelled);
        if (code == null && oc.made < oc.target) code = CraftOrder.INTERRUPTED;
        String orderId = String.valueOf(flow.orderId());
        if (!orderId.equals(orderCounted)) {
            orderCounted = orderId;
            orderTableFails = 0;
            orderInterrupts = 0;
        }
        // Charged from what was gathered, whatever a replan after an interrupted trip says it takes
        Map<String, Long> charged = CraftCharge.charge(oc.gathered, oc.fillsMade, Map.of());
        String here = "order: shulker " + (flow.index() + 1) + "/" + flow.units().size();
        if (idx < 0) return;
        if (code == null) {
            orderTableFails = 0;
            if (tableTrouble != null && CraftingTableSpot.get() == null) tableTrouble = null;
            step(here + ": crafted " + oc.made + " " + Recipes.pretty(oc.key) + ".");
            // A chunk smaller than the load leaves the rest of it to craft
            flow.craftChunk(u, idx, oc.made, oc.fillsMade, charged, null);
            return;
        }
        step(here + ": crafting " + Recipes.pretty(oc.key) + " stopped short (" + CraftOrder.describe(code) + "), " + oc.made + " of " + oc.target + " made.");
        if (CraftOrder.TABLE_BLOCKED.equals(code) || CraftOrder.TABLE_MISSING.equals(code) || CraftOrder.NO_TABLE.equals(code)) {
            CraftSettings cs = craftSettings();
            if (cs != null && cs.table() != null && CraftOrder.TABLE_BLOCKED.equals(code)) {
                tableTrouble = websiteSpot(cs.table());
                tableTroubleAt = tickCount;
            }
            // Not reached or not opened: one more go, then it is given up on for the rest of the order
            if (oc.made > 0) flow.craftChunk(u, idx, oc.made, oc.fillsMade, charged, code);
            if (CraftOrder.TABLE_BLOCKED.equals(code) && ++orderTableFails < 2 && oc.made == 0) {
                craftHealth.forget();
                flow.craftChunk(u, idx, 0, oc.fillsMade, charged, null);
                return;
            }
            flow.craftAbandon(code);
            return;
        }
        // Cut short by a player or a closing window: one more go, then it is interrupted
        if (CraftOrder.INTERRUPTED.equals(code) && oc.made == 0 && !cancelled && orderInterrupts < 1 && flow.running()) {
            orderInterrupts++;
            flow.craftChunk(u, idx, 0, oc.fillsMade, charged, null);
            return;
        }
        flow.craftChunk(u, idx, oc.made, oc.fillsMade, charged, code);
    }

    /** Compaction found no empty shulker: one is crafted, when crafting is on. @return true when a job was started */
    private boolean startCompactionCraft(String what) {
        if (!craftingOn() || craft != null || anvilRun != null || tickCount < craftCompactPausedUntil) return false;
        String[] why = new String[1];
        CraftRun run = planCraft(null, COMPACTION_TASK, 1, why);
        if (run != null) {
            // The shulker can be crafted (the preview): only then is a table found, put down or crafted
            boolean hadTable = CraftingTableSpot.isSet();
            switch (ensureTable(COMPACTION_TASK, why)) {
                case Wait -> {
                    return false;
                }
                case Started -> {
                    return job != null || anvilRun != null || craft != null;
                }
                case Failed -> {
                    run = null;
                    craftCompactPausedUntil = tickCount + TABLE_COOLDOWN_TICKS;
                }
                case Ready -> {
                    if (!hadTable) run = planCraft(null, COMPACTION_TASK, 1, why);
                }
            }
        }
        if (run == null) {
            if (tickCount + CRAFT_COMPACT_COOLDOWN > craftCompactPausedUntil && craftCompactPausedUntil < tickCount) craftCompactPausedUntil = tickCount + CRAFT_COMPACT_COOLDOWN;
            compactWhy("no empty shulker for " + what + "; couldn't craft one: " + why[0]);
            return false;
        }
        return beginCraft(run, "compaction: no empty shulker for " + what);
    }

    private boolean beginCraft(CraftRun run, String lead) {
        craft = run;
        CraftPlanner.Plan p = run.plan;
        craftCard = "Crafting: " + Recipes.pretty(p.item()) + " ×" + p.count();
        craftCardDetail = CraftPlanner.describe(p);
        step(lead + "; crafting: " + p.count() + " " + Recipes.pretty(p.item()) + " (" + CraftPlanner.describe(p) + "; from the stash: " + CraftPlanner.stockLine(p) + ", " + p.fills() + " grid fills); next: gather " + run.bin.lots.size() + " stacks.");
        if (continueCraft()) return true;
        return job != null;
    }

    /** The slots the craft's gathered stacks are in now. */
    private Set<Integer> craftHeldSlots(CraftRun run) {
        Set<Integer> out = new HashSet<>();
        for (BinTracker.Held h : carriedHeld(run.bin)) out.add(h.slot());
        return out;
    }

    /**
     * The next job of the craft in hand. @return true when one was started; false when nothing is to be done now (the craft
     * may be over: {@link #craft} is null then)
     */
    private boolean continueCraft() {
        CraftRun r = craft;
        if (r == null) return false;
        if (job != null) return true;
        // Not while the bot can't work (a player controls it, the queue, offline): the craft waits, nothing is dropped
        if (!orders.gate()) return false;
        // A table being crafted: a respawn or a change of dimension ends it, reported as failed to the one that asked
        if (r.forTable && r.phase != CraftPhase.PutBack && tableRunBroken()) {
            r.failure = "the bot respawned or changed dimension";
            r.retry = false;
            r.phase = CraftPhase.PutBack;
            step("crafting table: the bot respawned or changed dimension; next: put back what was gathered.");
        }
        // The order ended, or is to be cancelled or parked: what was gathered goes back
        if (r.unit != null && r.phase != CraftPhase.PutBack && (!orders.flow.running() || orders.flow.cancelRequested() || orders.flow.parkRequested())) {
            r.failure = "the order ended";
            r.retry = true;
            r.phase = CraftPhase.PutBack;
            step("crafting: stopped, the order ended; next: put back what was gathered.");
        }
        switch (r.phase) {
            case Gather -> {
                if (++r.jobs > 12 + 4 * Math.max(1, r.bin.lots.size())) return craftGaveUp(r, "it took too many trips", true);
                BinTracker.Need need = needOf(r.bin);
                if (need == null) {
                    r.phase = CraftPhase.Craft;
                    return startCraftTrip(r);
                }
                // Room for the loads still to come, a slot for each step's result, the shulker boxes made, and one spare
                int room = craftRoom(r, Math.max(0, r.bin.lots.size() - carriedHeld(r.bin).size()));
                if (freeCarryCount() < room) {
                    List<Integer> slots = roomCandidates();
                    slots.removeAll(craftReserved());
                    Box home = roomHome(slots);
                    if (r.roomTrips < MAX_ROOM_TRIPS && home != null && !slots.isEmpty()) {
                        r.roomTrips++;
                        step("crafting: putting away " + slots.size() + " carried stacks to make room (" + freeCarryCount() + " free carry slots, " + room + " wanted); next: gather the inputs.");
                        return start(Job.Dump, home);
                    }
                    return craftGaveUp(r, "not enough carry room (" + freeCarryCount() + " free slots, " + room + " wanted)", false);
                }
                Box source = binSourceFor(r.bin);
                if (source != null) {
                    gatherLeft = r.bin.lots.size();
                    step("crafting: gathering " + Recipes.pretty(Recipes.ns(baseIdOf(need.lot().key()))) + " (" + carriedHeld(r.bin).size() + "/" + r.bin.lots.size() + " stacks in hand); next: the crafting table.");
                    return start(Job.Gather, source);
                }
                // Taking from a shulker puts it down: not while shulker handling is paused (given up, tried again later)
                if (freeCarryCount() >= 2 && shulkerPause() != null && findUnpackSource(r.bin) != null) return craftGaveUp(r, "shulker handling is paused (" + shulkerPause() + ")", true);
                if (freeCarryCount() >= 2 && startCraftUnpack(r)) return true;
                return craftGaveUp(r, "not enough " + Recipes.pretty(Recipes.ns(baseIdOf(need.lot().key()))) + " left (" + noSourceDetail(r.bin) + ")", true);
            }
            case Craft -> {
                // Cut short before (the bot gave way): what is left is planned again from what is carried now
                if (r.trips > 0) {
                    Recipes recipes = recipesOrNull();
                    Map<String, Long> pool = new HashMap<>();
                    for (var e : craftPort.carried().entrySet()) pool.merge(CraftSlots.baseOf(e.getKey()), (long) e.getValue(), Long::sum);
                    CraftPlanner.Plan again;
                    CraftLedger ledger = r.oc == null ? null : orders.flow.craftLedger();
                    if (ledger != null && recipes != null && r.made < r.wanted) {
                        // An order's chunk is planned again from what is carried, with the order's recipes only
                        again = new CraftSub.Session(ledger.table(), ledger.allowed(), pool, pool, Map.of()).plan(r.plan.item(), r.wanted - r.made).plan();
                    }
                    else again = recipes == null || r.made >= r.wanted ? null
                        : CraftPlanner.plan(recipes, r.plan.item(), r.wanted - r.made, pool, CraftPlanner.Limits.open());
                    if (again == null || !again.ok()) return craftGaveUp(r, r.failure == null ? "what was gathered no longer makes one" : r.failure, true);
                    r.plan = again;
                }
                return startCraftTrip(r);
            }
            case PutBack -> {
                // Source shulkers emptied for the craft go back first, then what is left of the inputs and the steps between
                if (handle == null && returnSpentShulker()) return true;
                // An order's chunk: the result is made the load's size and set aside with the surplus, before the rest goes back
                if (r.oc != null && settleOrderChunk(r)) return true;
                if (r.putBackTries < 3) {
                    List<Integer> left = new ArrayList<>(looseCarried());
                    left.removeIf(slot -> craftKeep.containsKey(slot) || pendingSourceShulker(slot));
                    if (!left.isEmpty()) {
                        r.putBackTries++;
                        if (putBack(left, "crafting: putting " + left.size() + " leftover stacks back")) return true;
                    }
                }
                finishCraft(r);
                return false;
            }
        }
        return false;
    }

    /** The key of a load as its base item id ("minecraft:oak_log#ab12" as "minecraft:oak_log"). */
    private static String baseIdOf(String key) {
        int hash = key.indexOf('#');
        return hash < 0 ? key : key.substring(0, hash);
    }

    private boolean craftGaveUp(CraftRun r, String why, boolean retry) {
        r.failure = why;
        r.retry = retry;
        r.phase = CraftPhase.PutBack;
        step("crafting: gave up: " + why + "; next: put back what was gathered.");
        return continueCraft();
    }

    /** The craft is over: the order (or compaction) is told, and the card goes. */
    private void finishCraft(CraftRun r) {
        craft = null;
        craftCard = null;
        craftCardDetail = null;
        if (r.oc != null) {
            finishOrderChunk(r);
            return;
        }
        if (r.forTable) {
            finishTableCraft(r);
            return;
        }
        if (r.forChests()) {
            // Chests for the delivery storage (1.9.0): not shulker boxes, so not in the shulker counts; the chest run goes on
            if (r.made > 0) com.porter.qwertestash.manager.ActivityFeed.INSTANCE.event("Stash Sorter", "info", "Crafted " + r.made + " Chest for the delivery storage.");
            else chestCraftFailure = r.failure == null ? "nothing was made" : r.failure;
            step(r.made > 0 ? "crafting: done, " + r.made + " Chest made for the delivery storage." : "crafting: no chest made: " + (r.failure == null ? "unknown" : r.failure) + ".");
            return;
        }
        if (r.made > 0) {
            craftHealth.crafted(System.currentTimeMillis(), r.made);
            com.porter.qwertestash.manager.ActivityFeed.INSTANCE.event("Stash Sorter", "info", "Crafted " + r.made + " Shulker Box" + (r.unit != null ? " for an order." : " for compaction."));
        }
        if (r.unit != null) {
            if (r.made > 0) orders.flow.crafted(r.unit, r.made);
            if (r.made <= 0) orders.flow.craftFailed(r.unit, r.failure == null ? "nothing was made" : r.failure, r.retry);
        }
        else if (r.made <= 0) {
            craftCompactPausedUntil = tickCount + CRAFT_COMPACT_COOLDOWN;
            compactWhy("couldn't craft a shulker: " + (r.failure == null ? "nothing was made" : r.failure));
        }
        step(r.made > 0 ? "crafting: done, " + r.made + " Shulker Box made" + (r.failure == null ? "" : " (" + r.failure + ")") + "; next: " + (r.unit != null ? "carry on with the order." : "compact.")
            : "crafting: nothing made: " + (r.failure == null ? "unknown" : r.failure) + ".");
    }

    /** The nearest container with a loose stack for the next load of this bin (an order's or a craft's). */
    private @Nullable Box binSourceFor(Bin bin) {
        BinTracker.Need need = needOf(bin);
        if (need == null) return null;
        String variant = need.topUpSlot() >= 0 && inventoryItem(need.topUpSlot()) != null ? inventoryItem(need.topUpSlot()).variant : null;
        return nearest(stash.boxes.stream().filter(b -> binSource(b) && hasStackFor(b, need.lot().key(), variant)).toList());
    }

    /** Takes the next load of the craft out of a shulker: one already carried, else one fetched from the stash. */
    private boolean startCraftUnpack(CraftRun r) {
        BinTracker.Need need = needOf(r.bin);
        if (need == null) return false;
        String key = need.lot().key();
        for (var e : sourceShulkers.entrySet()) {
            Item held = inventoryItem(e.getKey());
            if (!e.getValue().opened && held != null && holds(held, key)) {
                startHandling(Job.Clean, e.getKey(), null);
                unpackingNow = e.getValue();
                step("crafting: taking items out of a shulker; next: put it back where it came from.");
                return true;
            }
        }
        Found f = findUnpackSource(r.bin);
        if (f == null) return false;
        unpackFetchOrigin = f.box().pos;
        wantSlot = f.slot();
        fetchWants = it -> it.isShulker() && holds(it, key);
        step("crafting: fetching a shulker that holds the inputs; next: take them out of it.");
        return start(Job.Fetch, f.box());
    }

    /**
     * Puts these carried stacks back: to the container the first came from if it has room, else the nearest with room. Works
     * while an order runs (returnLeftovers is only for after it). @return true when a job was started
     */
    private boolean putBack(List<Integer> slots, String line) {
        if (slots.isEmpty()) return false;
        OrderSource first = orderSources.get(slots.get(0));
        BlockPos src = first == null ? null : first.pos();
        Box from = src == null ? null : stash.boxAt(src);
        Box home = from != null && from.sortable() && !from.dropOff && !from.delivery && from.free() > 0 && !blocked(from, Job.Dump) && originAllowed(from, slots) ? from : null;
        if (home == null) home = nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !b.delivery && b.free() > 0 && !blocked(b, Job.Dump) && !signPending(b) && !kitReserved(b)).toList());
        if (home == null) return false;
        step(line + ".");
        putBackTrip = true;
        return start(Job.Dump, home);
    }

    private boolean startCraftTrip(CraftRun r) {
        boolean inv = r.forTable;
        CraftingTableSpot.Spot spot = CraftingTableSpot.get();
        if (!inv && r.oc != null && spot != null && CraftingTableSpot.here(spot) && Containers.isLoaded(spot.pos()) && !CraftingTableSpot.isTable(spot.pos())) {
            // The website's table is not ours to forget: the lines are short, the order goes on
            r.code = CraftOrder.TABLE_MISSING;
            return craftGaveUp(r, "the block at the website's crafting table isn't one", false);
        }
        if (!inv && spot != null && CraftingTableSpot.here(spot) && Containers.isLoaded(spot.pos()) && !CraftingTableSpot.isTable(spot.pos())) {
            // Gone since it was looked for: forgotten the same way, and the trip is not made to a block that isn't there
            tableSkip.add(spot.pos());
            CraftingTableSpot.clear();
            tableChanged();
            info("The crafting table I knew is gone, looking for another.");
            return craftGaveUp(r, CraftingTableFinder.NONE + ": the crafting table is gone", false);
        }
        if (!inv) {
            String problem = tableProblem(r.task);
            if (spot == null || problem != null) return craftGaveUp(r, spot == null ? CraftingTableFinder.NONE : problem, false);
        }
        // A trip made again keeps what the cut-short one made (chests, planks) as well as what was gathered
        Set<Integer> keepOwned = CraftSlots.retained(r.owned, slot -> inventoryItem(slot) != null, craftHeldSlots(r));
        r.owned.clear();
        r.owned.addAll(keepOwned);
        markJobStart();
        job = Job.Craft;
        target = null;
        actions = 0;
        tries = 0;
        stepTicks = 0;
        jobRunTicks = 0;
        step = Step.Walking;
        resetJobState();
        // The table may be well outside the stash area
        jobStartedInside = false;
        tableGoal = inv || spot == null ? null : new GoalNear(spot.pos(), REACH_RANGE_SQ);
        inventoryTrip = inv;
        crafter = new CraftTrip(craftPort, r.plan, inv ? CraftTrip.INVENTORY_GRID : CraftTrip.TABLE_GRID);
        step(inv ? "crafting table: crafting one in the inventory's 2x2 grid (" + CraftPlanner.describe(r.plan) + "); next: put it down."
            : "crafting: " + r.plan.count() + " " + Recipes.pretty(r.plan.item()) + " at the crafting table (" + CraftPlanner.describe(r.plan) + "); next: put back what is left.");
        return true;
    }

    private void tickCraft() {
        CraftTrip t = crafter;
        CraftRun r = craft;
        if (t == null || r == null) {
            endCraftTrip(null);
            return;
        }
        // The order ended, or is to be cancelled or parked: the trip takes everything back out of the grid, then closes
        if (r.unit != null && (!orders.flow.running() || orders.flow.cancelRequested() || orders.flow.parkRequested())) t.abort("the order ended");
        if (r.forTable && tableRunBroken()) t.abort("respawn or change of dimension");
        t.tick();
        if (r.oc != null) orders.flow.setPhase(orders.flow.craftPhase(r.oc.key, r.oc.fillsMade + t.fillsTaken()));
        if (t.step() != CraftTrip.Step.Walk && t.step() != CraftTrip.Step.Open) step = Step.Working;
        if (t.step() == CraftTrip.Step.Done) endCraftTrip(t.outcome());
    }

    /** The trip to the table is over: what was made is kept for its use, and what is left goes back next. */
    private void endCraftTrip(CraftTrip.@Nullable Outcome outcome) {
        CraftRun r = craft;
        CraftTrip finished = crafter;
        crafter = null;
        tableGoal = null;
        activity.releaseOurPathing();
        restoreRules();
        job = null;
        target = null;
        step = Step.Walking;
        wait = CONFIG.delayTicks;
        if (r == null) {
            if (!tableStuckInv) inventoryTrip = false;
            return;
        }
        if (outcome == null) outcome = new CraftTrip.Outcome(CraftTrip.CLOSED, true, false, false, 0);
        if (!r.forTable) {
            craftHealth.note(r.task, CraftingTableSpot.get(), outcome);
            // A found, placed or crafted table that can't be reached or opened is forgotten, so the next craft searches again; a
            // hand-set one never is
            if (outcome.tableBad() && (CraftTrip.NOT_REACHED.equals(outcome.failure()) || CraftTrip.NOT_OPENED.equals(outcome.failure()))) forgetTable();
        }
        if (outcome.stuck()) {
            markTableStuck();
            tableStuckAlertAt = tickCount;
            if (r.forTable) {
                // The inventory's own grid: nothing else is done until it is taken back out, tried again as slots free up (holdTable)
                tableStuckInv = true;
                alert("Items are stuck in the inventory's crafting grid (or on the cursor) and the inventory has no free slot for them. Nothing else is done: make room and the bot takes them back out.");
            }
            else {
                // The window is left open (closing it with a full inventory could drop what is in the grid), and kept ours: nothing
                // else is done until the grid is taken back out, which is tried again as slots free up (holdTable)
                alert("Items are stuck in the crafting table's grid and the inventory has no free slot for them. The crafting table is left open and nothing else is done: make room and the bot takes them back out, or close the table by hand.");
            }
        }
        if (!tableStuckInv) inventoryTrip = false;
        r.made += outcome.made();
        r.trips++;
        if (r.oc != null) {
            if (finished != null) r.oc.fillsMade += finished.fillsTaken();
            if (outcome.stuck()) r.stuck = true;
        }
        // Chests for the delivery storage are kept from being put away until they are put up
        if (r.forChests()) {
            long keepUntil = tickCount + CRAFT_KEEP_TICKS;
            for (int slot : new ArrayList<>(r.owned)) if (plainChest(inventoryItem(slot))) craftKeep.put(slot, keepUntil);
        }
        // The shulker boxes made are kept until they are used (the bin, the empty line, compaction)
        // Exactly the slots the table's result went into, never a source shulker emptied for the craft
        long until = tickCount + CRAFT_KEEP_TICKS;
        for (int slot : CraftSlots.keep(r.resultSlots, sourceShulkers.keySet(), slot -> {
            Item it = inventoryItem(slot);
            return emptyShulker(it) && it.id.equals("shulker_box");
        }, outcome.made())) craftKeep.put(slot, until);
        r.resultSlots.clear();
        // The table crafted is kept from being put away until it is put down; only the slot its own result went to
        if (r.forTable && r.tableSlot >= 0 && tableItem(inventoryItem(r.tableSlot))) craftKeep.put(r.tableSlot, until);
        r.phase = CraftPhase.PutBack;
        if (!outcome.done()) {
            r.failure = outcome.failure();
            r.retry = outcome.retry() && !outcome.stuck();
            // Cut short (the bot gave way, the window closed): the trip is made again with what is carried, a few times
            if (r.retry && r.trips < 3 && r.made < r.wanted && (r.unit == null || orders.flow.running())) r.phase = CraftPhase.Craft;
        }
        if (r.forTable) {
            if (!outcome.done()) warn("Crafting a crafting table stopped short ({}).", outcome.failure());
        }
        else if (outcome.done()) info("Crafted {} shulker boxes.", r.made);
        else warn("Crafting stopped short ({}), {} shulker boxes made.", outcome.failure(), r.made);
    }

    /** Crafted shulker boxes still waiting for their use: slots that hold one, not past their time. */
    private Set<Integer> craftKept() {
        if (craftKeep.isEmpty()) return Set.of();
        craftKeep.entrySet().removeIf(e -> {
            Item it = inventoryItem(e.getKey());
            // Kept for as long as an order is in hand; otherwise for a few minutes
            return (e.getValue() < tickCount && !orders.flow.holdsOrder() && chestRun == null && anvilRun == null) || !(emptyShulker(it) || plainChest(it) || tableItem(it));
        });
        return new HashSet<>(craftKeep.keySet());
    }

    /** What the craft in hand holds now (gathered and made), not to be put away until it is over. */
    private Set<Integer> craftReserved() {
        CraftRun r = craft;
        Set<Integer> held = orderCraftHeld();
        if (r == null && craftKeep.isEmpty() && held.isEmpty()) return Set.of();
        Set<Integer> out = new HashSet<>(craftKept());
        out.addAll(held);
        if (r == null || r.phase == CraftPhase.PutBack) return out;
        out.addAll(craftHeldSlots(r));
        for (int slot : r.owned) if (inventoryItem(slot) != null) out.add(slot);
        return out;
    }

    private @Nullable Container tableWindow() {
        if (openContainerId() == 0 || !ownsWindow()) return null;
        Container c = openContainer();
        return c != null && c.getType() == ContainerType.CRAFTING && Stashes.slots(c) == CraftGrid.TABLE_SLOTS ? c : null;
    }

    // The inventory's own 2x2 grid (window 0: result 0, grid 1-4) is used when a table is crafted (1.10.0); these read and click
    // the grid of whichever kind the trip is in
    private boolean inventoryTrip;
    private boolean tableStuckInv;
    private long invWaitSince = -1;

    private boolean gridOpen() {
        return inventoryTrip ? openContainerId() == 0 : tableWindow() != null;
    }

    private int gridCells() {
        return inventoryTrip ? CraftTrip.INVENTORY_GRID : CraftTrip.TABLE_GRID;
    }

    private @Nullable ItemStack gridStack(int slot) {
        if (inventoryTrip) {
            List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
            return slot >= 0 && slot < inv.size() ? inv.get(slot) : null;
        }
        Container c = tableWindow();
        return c == null ? null : c.getItemStack(slot);
    }

    private int gridWindowId() {
        if (inventoryTrip) return 0;
        Container c = tableWindow();
        return c == null ? -1 : c.getContainerId();
    }

    private int gridWindowSlot(int index) {
        if (inventoryTrip) return invSlot(index);
        Container c = tableWindow();
        return c == null ? -1 : toWindow(index, c);
    }

    /** Something is in the inventory's crafting grid (slots 1-4). */
    private boolean invGridHolds() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int s = 1; s <= CraftTrip.INVENTORY_GRID && s < inv.size(); s++) if (Item.of(inv.get(s)) != null) return true;
        return false;
    }

    /**
     * One step of emptying the inventory's crafting grid and the cursor into free slots.
     * @return 1 when a click was sent (or is going out, or another window is open and is waited for), 0 when both are empty, -1 when there is no room
     */
    private int invClearStep() {
        if (!invGridHolds() && !holdingCursor()) {
            invWaitSince = -1;
            return 0;
        }
        if (openContainerId() != 0) {
            if (invWaitSince < 0) invWaitSince = tickCount;
            // Our own window is let go of the normal way; another module's can only be waited for, and not for ever
            if (ownsWindow()) {
                closeSafely();
                return 1;
            }
            if (tickCount - invWaitSince > 20 * 30) {
                alert("Items are in the inventory's crafting grid, but another window has been open for 30 seconds: not waiting for it any more. They are taken back out when it closes or the bot is stopped.");
                invWaitSince = -1;
                return -2;
            }
            return 1;
        }
        invWaitSince = -1;
        if (busy()) return 1;
        boolean was = inventoryTrip;
        inventoryTrip = true;
        try {
            if (holdingCursor()) return craftPort.stowCursor() >= 0 ? 1 : -1;
            for (int g = 1; g <= CraftTrip.INVENTORY_GRID; g++) {
                if (Item.of(gridStack(g)) == null) continue;
                return craftPort.retrieve(g) >= 0 ? 1 : -1;
            }
            return 0;
        } finally {
            inventoryTrip = was;
        }
    }

    /**
     * The module stops or starts over (disconnect, disable): whatever is in the inventory's crafting grid or on the cursor goes into
     * free slots with one best-effort request. The clicks are made by slot, not through the sorter's own bookkeeping.
     */
    private void inventoryGridRescue() {
        inventoryGridRescue(5);
    }

    private void inventoryGridRescue(int tries) {
        try {
            if (openContainerId() != 0 || !CACHE.getPlayerCache().isAlive()) return;
            // Never races a click in flight: tried again shortly instead
            if (busy()) {
                if (tries > 0) DISABLE_CLOSER.schedule(() -> inventoryGridRescue(tries - 1), DISABLE_RETRY_MS, TimeUnit.MILLISECONDS);
                return;
            }
            boolean cursor = holdingCursor();
            if (!invGridHolds() && !cursor) return;
            List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
            List<Integer> free = new ArrayList<>();
            for (int i = 9; i <= 44 && i < inv.size(); i++) if (Item.of(inv.get(i)) == null) free.add(i);
            List<InventoryAction> acts = new ArrayList<>();
            int next = 0;
            if (cursor) {
                if (free.isEmpty()) {
                    warn("Stopped with a stack on the cursor and no free slot for it.");
                    return;
                }
                acts.add(new ClickItem(0, free.get(next++), ClickItemAction.LEFT_CLICK));
            }
            int left = 0;
            for (int g = 1; g <= CraftTrip.INVENTORY_GRID && g < inv.size(); g++) {
                if (Item.of(inv.get(g)) == null) continue;
                if (next >= free.size()) {
                    left++;
                    continue;
                }
                acts.add(new ClickItem(0, g, ClickItemAction.LEFT_CLICK));
                acts.add(new ClickItem(0, free.get(next++), ClickItemAction.LEFT_CLICK));
            }
            if (left > 0) warn("Stopped with {} stacks in the inventory's crafting grid and no free slot for them.", left);
            if (!acts.isEmpty()) INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(acts.toArray(new InventoryAction[0])).priority(SORTER_PRIORITY).build());
        } catch (RuntimeException e) {
            // Best effort
        }
    }

    /** The carry slots the craft may take from in the grid: what it gathered and what the table gave. */
    private List<Integer> craftUsable() {
        CraftRun r = craft;
        List<Integer> out = new ArrayList<>();
        if (r == null) return out;
        Set<String> never = neverUse();
        for (int slot : r.owned) {
            Item it = inventoryItem(slot);
            if (it != null && SourceRules.craftRefusal(it, never) == null) out.add(slot);
        }
        return out;
    }

    /**
     * A slot this whole stack goes into: a free carry slot first (the proxy predicts a left click on a stack as a swap, so a
     * merge is only a fallback), else a carry slot of the craft's with the same kind and room, else any free inventory slot;
     * -1 when none.
     */
    private int fitSlot(Item what, int count) {
        CraftRun order = craft;
        if (order != null && order.oc != null) return fitOrderSlot(order, what, count);
        int free = freeCarrySlot();
        if (free != -1) return free;
        CraftRun r = craft;
        if (r != null) {
            for (int slot : r.owned) {
                Item there = inventoryItem(slot);
                if (there != null && there.id.equals(what.id) && there.sameAs(what) && there.count + count <= there.maxStack) return slot;
            }
        }
        // No free carry slot is no room: nothing goes into a slot the bot keeps for itself
        return -1;
    }

    /**
     * Where a crafted stack of an order's craft goes (1.15.0): into a stack of the same kind the craft owns that has room for all of it
     * (the chunk's own result stack for the item itself, so it ends up one stack of the load's size; any the craft owns for a step's
     * product, so 16 fills of planks are not 16 slots), else a free carry slot. -1 when there is none.
     */
    private int fitOrderSlot(CraftRun r, Item what, int count) {
        OrderChunk oc = r.oc;
        boolean result = oc != null && what.id.equals(oc.id());
        int best = -1, bestCount = -1;
        for (int slot : result ? oc.resultSlots : r.owned) {
            Item there = inventoryItem(slot);
            if (there == null || !there.id.equals(what.id) || !there.sameAs(what) || there.count + count > there.maxStack) continue;
            if (there.count > bestCount) {
                best = slot;
                bestCount = there.count;
            }
        }
        return best != -1 ? best : freeCarrySlot();
    }

    /** Any free inventory slot (0-35) that is not kept, or -1. */
    private int freeInventoryIndex() {
        Set<Integer> keep = keepSlots();
        for (int i = 0; i < 36; i++) if (!keep.contains(i) && inventoryItem(i) == null) return i;
        return -1;
    }

    /** A stack's kind for the grid: its item id, with its components when it has any. */
    private static String kindOf(Item it) {
        return CraftSlots.kind(it.id, it.key, it.variant);
    }

    // Items left in the crafting table's grid with no room for them: the table is kept open and nothing else is done
    private boolean tableStuck;
    private long tableStuckAlertAt, tableStuckSince;
    private int tableAlerts;

    /**
     * One step of emptying the crafting table of ours: the cursor stack first, then the grid, each into a free slot.
     * @return 1 when a click was sent (or one is going out), 0 when the cursor and grid are empty, -1 when there is no room
     */
    private int craftClearStep() {
        Container c = tableWindow();
        if (c == null) return 0;
        if (busy()) return 1;
        if (holdingCursor()) return craftPort.stowCursor() >= 0 ? 1 : -1;
        for (int g = 1; g <= 9; g++) {
            if (Item.of(c.getItemStack(g)) == null) continue;
            return craftPort.retrieve(g) >= 0 ? 1 : -1;
        }
        return 0;
    }

    /**
     * The crafting table is held open with items in its grid: they are taken back out as soon as there is room, said now
     * and then until then. @return true while it holds the sorter
     */
    private boolean holdTable() {
        if (tableStuckInv) {
            int r = invClearStep();
            if (r == 0) {
                tableStuck = false;
                tableStuckInv = false;
                inventoryTrip = false;
                step("crafting: the inventory's crafting grid is empty again; carrying on.");
                return false;
            }
            if (r > 0) return true;
            if (r == -2) {
                // Stops holding the sorter; the grid is emptied when the other window closes (the sorter's own pass) or at teardown
                tableStuck = false;
                tableStuckInv = false;
                inventoryTrip = false;
                return false;
            }
            long minutes = (tickCount - tableStuckSince) / (20 * 60);
            if (minutes >= CraftSlots.alertMinutes(tableAlerts)) {
                tableAlerts++;
                alert("Items are still in the inventory's crafting grid and the inventory has no free slot ({} minutes now): make room and the bot takes them back out.", minutes);
            }
            return true;
        }
        if (tableWindow() == null) {
            tableStuck = false;
            craftStuckSaid = false;
            step("crafting: the crafting table window is closed; carrying on.");
            return false;
        }
        int r = craftClearStep();
        if (r == 0) {
            tableStuck = false;
            craftStuckSaid = false;
            step("crafting: the crafting table's grid is empty again; closing it and carrying on.");
            closeSafely();
            return true;
        }
        if (r > 0) return true;
        long minutes = (tickCount - tableStuckSince) / (20 * 60);
        // Said after 1, 5 and 15 minutes, then every hour
        if (minutes >= CraftSlots.alertMinutes(tableAlerts)) {
            tableAlerts++;
            alert("Items are still in the crafting table's grid and the inventory has no free carry slot ({} minutes now): make room (the bot takes them back out by itself) or close the table by hand.", minutes);
        }
        // A bounded way out: what is in the grid is only the craft's own inputs from the stash, so after a while the table is
        // closed and the server gives them back (or drops them at the bot's feet, with no room)
        if (minutes >= CraftSlots.STUCK_ESCAPE_MINUTES && CraftSlots.ownInputsOnly(gridIds(), craftIds())) {
            alert("The crafting table was held open for {} minutes with only crafting inputs in its grid and no room for them: closing it. The server puts them in the inventory, or drops them at the bot's feet; the inventory is full, so it is put away next.", minutes);
            tableStuck = false;
            craftStuckSaid = false;
            RequestFuture f = submitClose(openContainerId(), cursorSlotForClose(false));
            activity.noteSubmit(f, true, tickCount);
            // The inventory is full: what is carried is put away before anything else
            orderLeftoverTried = false;
            stuckCarried.clear();
            idle = false;
            return true;
        }
        return true;
    }

    /** The item ids in the crafting table's grid now. */
    private List<String> gridIds() {
        List<String> out = new ArrayList<>();
        Container c = tableWindow();
        if (c == null) return out;
        for (int g = 1; g <= 9; g++) {
            Item it = Item.of(c.getItemStack(g));
            if (it != null) out.add("minecraft:" + it.id);
        }
        return out;
    }

    /** The table is held open with items in its grid from now (the alerts back off from here). */
    private void markTableStuck() {
        if (!tableStuck) {
            tableStuckSince = tickCount;
            tableAlerts = 0;
        }
        tableStuck = true;
    }

    /** The trip's view of the bot: walking and opening through the pathfinder, clicks through the inventory queue. */
    private final CraftTrip.Port craftPort = new CraftTrip.Port() {
        @Override
        public boolean arrived() {
            return inventoryTrip || (tableGoal != null && arrivedAt(tableGoal));
        }

        @Override
        public boolean walk() {
            CraftingTableSpot.Spot spot = CraftingTableSpot.get();
            if (spot == null || !activity.mayRequest()) return false;
            tableGoal = requestWalk(spot.pos(), "the crafting table");
            wait = 10;
            return true;
        }

        @Override
        public boolean pathing() {
            return activity.ownPathing();
        }

        @Override
        public boolean tableOpen() {
            return gridOpen();
        }

        @Override
        public boolean otherWindowOpen() {
            return openContainerId() != 0 && ownsWindow() && (inventoryTrip || tableWindow() == null);
        }

        @Override
        public boolean openPaused() {
            return StashSorter.this.openPaused();
        }

        @Override
        public void opened() {
            // The first click in the table waits the click delay and the container delay (no change at 0)
            if (CONFIG.containerDelay > 0) wait = Math.max(wait, openWait());
        }

        @Override
        public boolean open() {
            CraftingTableSpot.Spot spot = CraftingTableSpot.get();
            if (inventoryTrip || spot == null || !activity.mayRequest() || activity.ownPathing()) return false;
            openIdBefore = openContainerId();
            openRequestedAt = lastOpenClickAt = System.currentTimeMillis();
            foreignSinceClick = false;
            requestOpen(spot.pos(), "the crafting table");
            wait = CONFIG.settleTicks;
            return true;
        }

        @Override
        public boolean busy() {
            return StashSorter.this.busy();
        }

        @Override
        public Map<String, Integer> carried() {
            Map<String, Integer> out = new HashMap<>();
            for (int slot : craftUsable()) {
                Item it = inventoryItem(slot);
                if (it != null) out.merge(kindOf(it), it.count, Integer::sum);
            }
            return out;
        }

        @Override
        public @Nullable String itemAt(int slot) {
            Item it = Item.of(gridStack(slot));
            return it == null ? null : kindOf(it);
        }

        @Override
        public int countAt(int slot) {
            Item it = Item.of(gridStack(slot));
            return it == null ? 0 : it.count;
        }

        @Override
        public boolean place(int gridSlot, String item, int goal) {
            if (!gridOpen() || holdingCursor() || gridSlot < 1 || gridSlot > gridCells()) return false;
            Item there = Item.of(gridStack(gridSlot));
            int have = there == null ? 0 : there.count;
            if (have >= goal) return false;
            // The source: a stack with exactly enough, else the smallest with more, else the biggest (topped up from another next)
            int src = -1;
            Item best = null;
            long bestScore = Long.MAX_VALUE;
            for (int slot : craftUsable()) {
                Item it = inventoryItem(slot);
                if (it == null || !kindOf(it).equals(item) || (there != null && !there.sameAs(it))) continue;
                int want = goal - have;
                long score = it.count >= want ? it.count - want : 10_000L - it.count;
                if (score < bestScore) {
                    bestScore = score;
                    best = it;
                    src = slot;
                }
            }
            if (best == null) return false;
            int take = Math.min(goal - have, best.count);
            ClickPlanner.Clicks clicks = ClickPlanner.plan(Math.max(1, best.maxStack), best.count, have, have + take);
            if (clicks == null || clicks.ops().length == 0) return false;
            int from = gridWindowSlot(src);
            int windowId = gridWindowId();
            ClickItem[] actionsNow = new ClickItem[clicks.ops().length];
            for (int i = 0; i < actionsNow.length; i++) {
                int op = clicks.ops()[i];
                actionsNow[i] = new ClickItem(windowId, op < 2 ? from : gridSlot, op % 2 == 1 ? ClickItemAction.RIGHT_CLICK : ClickItemAction.LEFT_CLICK);
            }
            if (submit(actionsNow) == RequestFuture.rejected) return false;
            actions++;
            wait = CONFIG.delayTicks;
            return true;
        }

        @Override
        public int maxStack(String kind) {
            return stackOf(kind);
        }

        @Override
        public boolean holdingCursor() {
            return StashSorter.this.holdingCursor();
        }

        @Override
        public int stowCursor() {
            Item held = Item.of(mouseStack());
            if (!gridOpen() || held == null) return -1;
            int into = freeCarrySlot();
            if (into == -1) {
                // Only so the stack on the cursor is not lost: any free slot, said
                into = freeInventoryIndex();
                if (into != -1) warn("No free carry slot for the stack on the cursor: it goes into inventory slot {} so it isn't lost.", into + 1);
            }
            if (into == -1) return -1;
            if (submit(new ClickItem(gridWindowId(), gridWindowSlot(into), ClickItemAction.LEFT_CLICK)) == RequestFuture.rejected) return -1;
            stashHeld.put(into, held.id);
            CraftRun r = craft;
            if (r != null) r.owned.add(into);
            wait = CONFIG.delayTicks;
            return into;
        }

        @Override
        public int take(String item, int count) {
            return fromTable(CraftGrid.RESULT, count);
        }

        @Override
        public int retrieve(int gridSlot) {
            Item it = gridOpen() ? Item.of(gridStack(gridSlot)) : null;
            return it == null ? -1 : fromTable(gridSlot, it.count);
        }

        /** Moves the stack in this table slot into a carry slot it fits in whole: two left clicks, never a shift-click. */
        private int fromTable(int tableSlot, int count) {
            if (!gridOpen() || holdingCursor()) return -1;
            Item what = Item.of(gridStack(tableSlot));
            if (what == null) return -1;
            int into = fitSlot(what, count);
            if (into == -1) return -1;
            int windowId = gridWindowId();
            RequestFuture f = submit(new ClickItem(windowId, tableSlot, ClickItemAction.LEFT_CLICK), new ClickItem(windowId, gridWindowSlot(into), ClickItemAction.LEFT_CLICK));
            if (f == RequestFuture.rejected) return -1;
            stashHeld.put(into, what.id);
            CraftRun r = craft;
            if (r != null) {
                r.owned.add(into);
                if (tableSlot == CraftGrid.RESULT && what.id.equals("shulker_box")) r.resultSlots.add(into);
                if (tableSlot == CraftGrid.RESULT && r.oc != null && what.id.equals(r.oc.id())) r.oc.resultSlots.add(into);
                if (tableSlot == CraftGrid.RESULT && r.forTable && what.id.equals("crafting_table")) r.tableSlot = into;
            }
            actions++;
            wait = CONFIG.delayTicks;
            return into;
        }

        @Override
        public boolean close() {
            if (openContainerId() == 0 || !ownsWindow()) return true;
            closeSafely();
            return false;
        }
    };

    /** Whether the table's grid holds anything now (a window of ours being let go of must be emptied first). */
    private boolean craftGridHolds() {
        Container c = tableWindow();
        if (c == null) return false;
        for (int s = 1; s <= 9; s++) if (Item.of(c.getItemStack(s)) != null) return true;
        return false;
    }

    private boolean craftStuckSaid;

    /** A craft is gathering its inputs. */
    private boolean craftGathering() {
        CraftRun r = craft;
        return r != null && r.phase == CraftPhase.Gather;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Finding an anvil (1.8.1): with none set by hand (or the one set gone), an anvil standing in the stash area is looked for
    // and remembered as found; with none standing, one is taken from the stash, put down by the PlaceSearch rules and
    // remembered as placed. With neither, the shulker goes unnamed with AnvilFinder.NO_ANVILS and the order goes on.

    private enum AnvilPhase { Gather, Place, PutBack }

    /** What the run puts down: an anvil (naming), or a crafting table (1.10.0, for crafting). */
    private enum PlaceKind { Anvil, Table }

    private enum AnvilStep { Walk, Hotbar, Place, Wait }

    private static final class AnvilRun {
        final String task;
        final Bin bin;
        AnvilPhase phase = AnvilPhase.Gather;
        int jobs, spots;
        // The slot the anvil was moved to for placing (a hotbar slot need not be a carry slot), or -1
        int heldSlot = -1;
        // The swap made to get it into the hotbar, undone when the run ends (as the shulker flow does): -1 when none
        int swapFrom = -1, swapTo = -1;
        @Nullable String failure;
        boolean done;
        PlaceKind kind = PlaceKind.Anvil;
        /** The table is for a craft order: its placement counts against the caps. */
        boolean forOrderCraft;
        // A table: crafted by the bot (not taken from the stash), and when the run began (it gives up after 90 s)
        boolean crafted;
        long startTick;

        AnvilRun(String task, List<Order.Lot> lots) {
            this.task = task;
            this.bin = new Bin(new Unit(Unit.Kind.Bin, "", new ArrayList<>(lots)));
        }
    }

    private @Nullable AnvilRun anvilRun;
    // Anvils (standing or spots to put one) given up on this run; the order that has none at all
    private final Set<BlockPos> anvilSkip = new HashSet<>();
    private @Nullable String anvilNoneFor;
    // The last search for one standing: when, and what it found
    private long anvilScanAt = Long.MIN_VALUE / 4;
    // Putting one down
    private PlaceSearch.@Nullable Found anvilSpot;
    private @Nullable AnvilStep anvilStep;
    private int anvilStepTicks;
    private static final int ANVIL_SCAN_TICKS = 20 * 10, ANVIL_PLACE_TICKS = 20 * 10, MAX_ANVIL_SPOTS = 3;
    private static final int TABLE_PLACE_TICKS = 20 * 90;
    // (1.8.1) The search spread over ticks replaced the single scan; anvilScanAt is kept for planning() to reset it

    private final AnvilFinder.Blocks anvilBlocks = new AnvilFinder.Blocks() {
        @Override
        public boolean loaded(int x, int z) {
            return World.isChunkLoadedBlockPos(x, z);
        }

        @Override
        public @Nullable String name(int x, int y, int z) {
            try {
                return World.getBlock(x, y, z).name();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return placeView.standable(x, y, z);
        }
    };

    // The search for an anvil standing, spread over ticks (at most AnvilFinder.LOOKUPS_PER_TICK block lookups a tick): the one
    // under way, what the last finished one found, and when it finished
    private AnvilFinder.@Nullable Scan anvilScanNow;
    private List<BlockPos> anvilScanFound = List.of();
    private long anvilScanDoneAt = Long.MIN_VALUE / 4;
    // An anvil found or put down while the one set with `stash anvil` is gone: used for naming without changing the setting
    private AnvilSpot.@Nullable Spot anvilOverride;

    /** The anvil naming uses now: one found or placed in place of a hand-set one that is gone, else the setting. */
    private AnvilSpot.@Nullable Spot namingSpot() {
        AnvilSpot.Spot o = anvilOverride;
        return o != null ? o : AnvilSpot.get();
    }

    /** The search is going: a tick's worth more of it. */
    private void advanceAnvilScan() {
        AnvilFinder.Scan sc = anvilScanNow;
        if (sc == null) return;
        sc.step(anvilBlocks, AnvilFinder.LOOKUPS_PER_TICK);
        if (!sc.done()) return;
        anvilScanFound = new ArrayList<>(sc.found());
        anvilScanDoneAt = tickCount;
        anvilScanNow = null;
        final long looked = sc.lookups();
        final int n = anvilScanFound.size();
        debug(() -> "Anvil search: " + looked + " block lookups, " + n + " anvils standing.");
    }

    private boolean anvilScanFresh() {
        return tickCount - anvilScanDoneAt < ANVIL_SCAN_TICKS;
    }

    /** Starts a search (when none is going) and makes the first tick of it. */
    private void startAnvilScan() {
        Stash s = stash;
        if (anvilScanNow != null || s == null) return;
        List<BlockPos> at = new ArrayList<>();
        for (Box b : s.boxes) if (!b.delivery) at.add(b.pos);
        anvilScanNow = new AnvilFinder.Scan(AnvilFinder.region(s.bounds(), at, playerFeet(), CONFIG.radius, CONFIG.height));
        advanceAnvilScan();
    }

    /** Forget what the last search found: the next look searches again (an anvil was given up on, say). */
    private void anvilScanStale() {
        anvilScanDoneAt = Long.MIN_VALUE / 4;
    }

    /** The nearest anvil the last search found that still stands, the bot can stand next to, and isn't given up on. */
    private @Nullable BlockPos nearestScannedAnvil() {
        Stash s = stash;
        if (s == null) return null;
        List<BlockPos> standing = new ArrayList<>();
        for (BlockPos p : anvilScanFound) if (AnvilFinder.isAnvil(anvilBlocks.name(p.x(), p.y(), p.z()))) standing.add(p);
        return AnvilFinder.nearest(playerFeet(), standing, anvilBlocks, p -> s.inArea(p, 0), anvilSkip);
    }

    /** One anvil (as an item) in the stash that no line of the order needs, as a load to gather; empty when there is none. */
    private List<Order.Lot> anvilLots() {
        Stash s = orders.st();
        if (s == null) return List.of();
        CraftPool.Stock stock = SourceRules.craftSurvey(s.boxes, new HashSet<>(AnvilFinder.ITEMS), orders.reservedShulkerKeys(), CONFIG.useNamedShulkers, Set.of(), sourceLive);
        List<Unit> others = orders.flow.holdsOrder() ? remainingUnits() : List.of();
        Map<String, Long> left = CraftPool.residual(stock, others);
        for (String id : AnvilFinder.ITEMS) if (left.getOrDefault(id, 0L) > 0) return CraftPool.lots(stock, Map.of(id, 1L), others);
        return List.of();
    }

    /**
     * Makes an anvil known for naming if it can: the one set or remembered when it is still there, else one standing in the
     * stash area (remembered as found). @return USE when one is known now, PLACE when one can be taken from the stash, NONE
     */
    private AnvilFinder.Way anvilWay() {
        AnvilSpot.Spot spot = namingSpot();
        boolean handSet = anvilOverride == null && spot != null && AnvilSpot.SET.equals(AnvilSpot.source());
        if (spot != null && !AnvilSpot.here(spot)) {
            // A hand-set one elsewhere is said by namingProblem; a found or placed one elsewhere is looked past, and one is
            // searched for here
            if (handSet) return AnvilFinder.Way.USE;
            spot = null;
        }
        boolean gone = spot != null && (anvilSkip.contains(spot.pos()) || (Containers.isLoaded(spot.pos()) && !AnvilSpot.isAnvil(spot.pos())));
        if (spot != null && !gone) return AnvilFinder.Way.USE;
        if (spot != null) {
            String was = anvilOverride != null ? "anvil used instead of the one set" : AnvilSpot.SET.equals(AnvilSpot.source()) ? "anvil set with `stash anvil`" : AnvilSpot.source() + " anvil";
            if (anvilSkip.add(spot.pos())) step("naming: the " + was + " is gone or given up on; looking for another" + (handSet ? " (the setting is kept)." : "."));
            // A hand-set anvil stays set; a remembered one is forgotten
            if (anvilOverride != null) anvilOverride = null;
            else if (!handSet) AnvilSpot.clear();
            anvilScanStale();
        }
        if (!anvilScanFresh()) {
            startAnvilScan();
            if (!anvilScanFresh()) return AnvilFinder.Way.WAIT;
        }
        BlockPos standing = nearestScannedAnvil();
        boolean inStock = anvilRun != null || anvilItemSlot() >= 0 || !anvilLots().isEmpty();
        AnvilFinder.Way way = AnvilFinder.decide(false, standing != null, inStock);
        if (way == AnvilFinder.Way.FOUND && standing != null) {
            String dimension = Stash.currentDimension();
            if (dimension == null) return AnvilFinder.Way.NONE;
            useAnvil(dimension, standing, AnvilSpot.FOUND);
            step("naming: found an anvil standing " + String.format("%.0f", Math.sqrt(distSq(playerFeet(), standing))) + " blocks away; remembered it for next time.");
            return AnvilFinder.Way.USE;
        }
        return way;
    }

    /** Uses this anvil from now on: remembered in the settings, unless a hand-set one is there (then only for now). */
    private void useAnvil(String dimension, BlockPos at, String source) {
        if (AnvilSpot.isSet() && AnvilSpot.SET.equals(AnvilSpot.source())) anvilOverride = new AnvilSpot.Spot(dimension, at.x(), at.y(), at.z());
        else AnvilSpot.remember(dimension, at.x(), at.y(), at.z(), source);
    }

    private static boolean placeItem(PlaceKind kind, @Nullable Item it) {
        if (it == null) return false;
        return kind == PlaceKind.Table ? it.id.equals("crafting_table") : AnvilFinder.ITEMS.contains("minecraft:" + it.id);
    }

    private static boolean tableItem(@Nullable Item it) {
        return placeItem(PlaceKind.Table, it);
    }

    private static String placeLead(AnvilRun r) {
        return r.kind == PlaceKind.Table ? "crafting table: " : "naming: ";
    }

    private static String placeWhat(AnvilRun r) {
        return r.kind == PlaceKind.Table ? "a crafting table" : "an anvil";
    }

    /** An anvil item is carried by the run now: the slot, or -1. */
    private int carriedAnvilSlot(AnvilRun r) {
        if (r.heldSlot >= 0) {
            if (placeItem(r.kind, inventoryItem(r.heldSlot))) return r.heldSlot;
        }
        for (BinTracker.Held h : carriedHeld(r.bin)) return h.slot();
        return -1;
    }

    /** Starts taking an anvil from the stash and putting it down, for naming this order's shulkers. */
    private boolean startAnvilRun() {
        if (anvilRun != null) return continueAnvilRun();
        if (compact != null || craft != null) return false;
        // An anvil already in the inventory (one that couldn't be put down before, say) is used before one is fetched
        int have = anvilItemSlot();
        if (have >= 0) {
            AnvilRun r = new AnvilRun(String.valueOf(orders.flow.orderId()), List.of());
            r.heldSlot = have;
            r.phase = AnvilPhase.Place;
            anvilRun = r;
            step("naming: no anvil standing in the stash area; putting down the one in the inventory; next: name the shulker there.");
            return continueAnvilRun();
        }
        List<Order.Lot> lots = anvilLots();
        if (lots.isEmpty()) return false;
        anvilRun = new AnvilRun(String.valueOf(orders.flow.orderId()), lots);
        step("naming: no anvil standing in the stash area; taking " + Recipes.pretty(baseIdOf(lots.get(0).key())) + " from the stash to put down; next: name the shulker there.");
        return continueAnvilRun();
    }

    /** The anvil run gave up: the order's shulkers go unnamed with NO_ANVILS, the anvil (if carried) goes back. */
    private boolean anvilGaveUp(AnvilRun r, String why) {
        r.failure = why;
        r.phase = AnvilPhase.PutBack;
        if (r.kind == PlaceKind.Table) {
            // Whatever was gathered goes back (the table too, it is no longer kept from being put away)
            craftKeep.keySet().removeIf(slot -> tableItem(inventoryItem(slot)));
            warn("Couldn't place a crafting table: {}.", why);
            tableFail(r.task, CraftingTableFinder.NONE + ": couldn't place one (" + why + ")");
            return continueAnvilRun();
        }
        anvilNoneFor = r.task;
        step("naming: couldn't put an anvil down: " + why + "; the shulkers go unnamed (" + AnvilFinder.NO_ANVILS + ").");
        return continueAnvilRun();
    }

    private boolean continueAnvilRun() {
        AnvilRun r = anvilRun;
        if (r == null) return false;
        if (job != null) return true;
        if (!orders.gate()) return false;
        if (r.kind == PlaceKind.Table) {
            // A table for a craft: the order ending does not strand it (it is put down, it is useful anyway); a respawn or a
            // change of dimension does end it, reported as failed to the one that asked
            if (r.phase != AnvilPhase.PutBack && tableRunBroken()) return anvilGaveUp(r, "the bot respawned or changed dimension");
        }
        else if (r.phase != AnvilPhase.PutBack && (!orders.flow.running() || orders.flow.cancelRequested() || orders.flow.parkRequested())) {
            r.failure = "the order ended";
            r.phase = AnvilPhase.PutBack;
        }
        switch (r.phase) {
            case Gather -> {
                if (++r.jobs > 12) return anvilGaveUp(r, "it took too many trips");
                BinTracker.Need need = needOf(r.bin);
                if (need == null) {
                    r.phase = AnvilPhase.Place;
                    return continueAnvilRun();
                }
                if (freeCarryCount() < 2) return anvilGaveUp(r, "no carry room");
                Box source = binSourceFor(r.bin);
                if (source != null) {
                    gatherLeft = 1;
                    step(placeLead(r) + "taking " + placeWhat(r) + " from the stash; next: put it down.");
                    return start(Job.Gather, source);
                }
                if (shulkerPause() != null && findUnpackSource(r.bin) != null) return anvilGaveUp(r, "shulker handling is paused (" + shulkerPause() + ")");
                if (startBinUnpack(r.bin, placeLead(r))) return true;
                return anvilGaveUp(r, r.kind == PlaceKind.Table ? "the crafting table in the stash couldn't be taken" : "the anvil in the stash couldn't be taken");
            }
            case Place -> {
                if (carriedAnvilSlot(r) < 0) return anvilGaveUp(r, r.kind == PlaceKind.Table ? "the crafting table is no longer carried" : "the anvil is no longer carried");
                if (r.spots >= MAX_ANVIL_SPOTS) return anvilGaveUp(r, "it couldn't be put down at " + MAX_ANVIL_SPOTS + " spots");
                if (r.kind == PlaceKind.Table && tickCount - r.startTick > TABLE_PLACE_TICKS) return anvilGaveUp(r, "it took more than 90 seconds");
                PlaceSearch.Result found = PlaceSearch.search(playerFeet(), placeView, anvilSkip);
                if (found.found() == null) return anvilGaveUp(r, "no spot within " + PlaceSearch.RADIUS + " blocks: " + found.why());
                r.spots++;
                anvilSpot = found.found();
                anvilStep = AnvilStep.Walk;
                anvilStepTicks = 0;
                markJobStart();
                job = Job.Anvil;
                target = null;
                actions = 0;
                stepTicks = 0;
                jobRunTicks = 0;
                step = Step.Walking;
                resetJobState();
                jobStartedInside = false;
                step(r.kind == PlaceKind.Table ? "crafting table: putting it down " + found.found().steps() + " blocks away; next: craft there."
                    : "naming: putting the anvil down " + found.found().steps() + " blocks away; next: name the shulker there.");
                return true;
            }
            case PutBack -> {
                // The hotbar swap is undone first: what it displaced goes back, an anvil not put down comes back to a carry slot
                if (anvilSwapBack(r)) return true;
                if (r.kind == PlaceKind.Table && !r.done) craftKeep.keySet().removeIf(slot -> tableItem(inventoryItem(slot)));
                List<Integer> left = new ArrayList<>(looseCarried());
                left.removeIf(slot -> craftKeep.containsKey(slot) || pendingSourceShulker(slot));
                if (!r.done && r.jobs++ < 20 && handle == null && returnSpentShulker()) return true;
                if (!left.isEmpty() && r.spots++ < 6 && putBack(left, "naming: putting " + left.size() + " stacks back")) return true;
                anvilRun = null;
                return false;
            }
        }
        return false;
    }

    /** Putting the anvil down: walk beside the spot, the anvil into an allowed hotbar slot, place it, see it standing. */
    private void tickAnvilPlace() {
        AnvilRun r = anvilRun;
        PlaceSearch.Found f = anvilSpot;
        if (r == null || f == null || anvilStep == null) {
            endAnvilPlace(false);
            return;
        }
        anvilStepTicks++;
        if (r.kind == PlaceKind.Table ? tableRunBroken() : (!orders.flow.running() || orders.flow.cancelRequested() || orders.flow.parkRequested())) {
            endAnvilPlace(false);
            return;
        }
        if (busy()) return;
        int slot = carriedAnvilSlot(r);
        switch (anvilStep) {
            case Walk -> {
                if (distSq(playerFeet(), f.stand()) <= 2 && !activity.ownPathing()) {
                    activity.releaseOurPathing();
                    restoreRules();
                    anvilStep = AnvilStep.Hotbar;
                    anvilStepTicks = 0;
                    return;
                }
                if (anvilStepTicks > WALK_TIMEOUT || (anvilStepTicks > 40 && !activity.ownPathing())) {
                    activity.releaseOurPathing();
                    restoreRules();
                    anvilSkip.add(f.spot());
                    endAnvilPlace(false);
                    return;
                }
                if (!activity.ownPathing() && activity.mayRequest()) {
                    rules(false, false);
                    PathingRequestFuture future = BARITONE.pathTo(new GoalBlock(f.stand()));
                    activity.notePath(future, BARITONE.getCustomGoalProcess(), null, tickCount);
                    traceRequest("walk to a spot to put an anvil down", future);
                    wait = 10;
                }
            }
            case Hotbar -> {
                if (slot < 0) {
                    endAnvilPlace(false);
                    return;
                }
                // A hotbar slot the pathfinder places this anvil from: an empty one, else a carry slot, and only last one holding
                // the bot's own things; the swap is undone when the run ends
                int hotbar = AnvilFinder.hotbarFor(slot, keepSlots(), new HashSet<>(carry), i -> inventoryItem(i) != null);
                if (hotbar == -1) {
                    anvilGaveUpLater("no hotbar slot may be used to place it");
                    return;
                }
                if (hotbar != slot) {
                    if (submit(new MoveToHotbarSlot(invSlot(slot), MoveToHotbarAction.from(hotbar))) == RequestFuture.rejected) return;
                    swapHeld(slot, hotbar);
                    if (r.swapFrom < 0) {
                        r.swapFrom = slot;
                        r.swapTo = hotbar;
                    }
                    r.heldSlot = hotbar;
                    wait = CONFIG.delayTicks;
                    return;
                }
                anvilStep = AnvilStep.Place;
                anvilStepTicks = 0;
            }
            case Place -> {
                if (slot < 0) {
                    endAnvilPlace(false);
                    return;
                }
                BlockPos at = f.spot();
                if (!placeView.open(at.x(), at.y(), at.z()) || unsafeBecause(at) != null || placeGuarded(at)) {
                    anvilSkip.add(at);
                    endAnvilPlace(false);
                    return;
                }
                if (!activity.mayRequest()) return;
                Item it = inventoryItem(slot);
                ItemData data = it == null ? null : ItemRegistry.REGISTRY.get(it.id);
                if (data == null) {
                    endAnvilPlace(false);
                    return;
                }
                requestPlace(at, data);
                anvilStep = AnvilStep.Wait;
                anvilStepTicks = 0;
            }
            case Wait -> {
                BlockPos at = f.spot();
                if (Containers.isLoaded(at) && (r.kind == PlaceKind.Table ? CraftingTableSpot.isTable(at) : AnvilSpot.isAnvil(at))) {
                    activity.releaseOurPathing();
                    restoreRules();
                    String dimension = Stash.currentDimension();
                    if (r.kind == PlaceKind.Table) {
                        if (dimension != null) CraftingTableSpot.remember(dimension, at.x(), at.y(), at.z(), r.crafted ? CraftingTableSpot.CRAFTED : CraftingTableSpot.PLACED);
                        if (r.forOrderCraft) recordTablePlacement(r.task);
                        tableChanged();
                        r.done = true;
                        step("crafting table: put one down " + String.format("%.0f", Math.sqrt(distSq(playerFeet(), at))) + " blocks away and remembered it; next: craft there.");
                    }
                    else {
                        if (dimension != null) useAnvil(dimension, at, AnvilSpot.PLACED);
                        r.done = true;
                        step("naming: put an anvil down " + String.format("%.0f", Math.sqrt(distSq(playerFeet(), at))) + " blocks away and remembered it; next: name the shulker there.");
                    }
                    endAnvilPlace(true);
                    return;
                }
                if (anvilStepTicks > ANVIL_PLACE_TICKS || (activity.ownFailureSettled() && anvilStepTicks > 2)) {
                    activity.releaseOurPathing();
                    restoreRules();
                    anvilSkip.add(at);
                    endAnvilPlace(false);
                }
            }
        }
    }

    private void anvilGaveUpLater(String why) {
        AnvilRun r = anvilRun;
        endAnvilPlace(false);
        if (r != null) {
            r.spots = MAX_ANVIL_SPOTS;
            r.failure = why;
        }
    }

    /** The place job is over: placed (the run ends, what is left goes back), or tried again at another spot. */
    private void endAnvilPlace(boolean placed) {
        AnvilRun r = anvilRun;
        anvilSpot = null;
        anvilStep = null;
        activity.releaseOurPathing();
        restoreRules();
        job = null;
        target = null;
        step = Step.Walking;
        wait = CONFIG.delayTicks;
        if (r == null) return;
        if (placed) r.phase = AnvilPhase.PutBack;
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Finding a crafting table (1.10.0): with none set by hand (or the one set gone), the order of preference is the addon's: a table
    // standing in the stash area (remembered as found), one taken from the stash and put down (placed), one crafted in the inventory's
    // 2x2 grid from planks or logs from the stash and put down (crafted). Looked for only when a craft is about to start with no usable
    // table, after the craft itself was found possible. Placing is the anvil's run (AnvilRun, kind Table), crafting is a CraftRun for the
    // table (forTable) made in the inventory's grid. With none of it the craft fails with CraftingTableFinder.NONE.

    private enum TableWay { Ready, Wait, Started, Failed }

    // Tables put down for craft orders are counted (one for an order, three in an hour), in the settings file
    private @Nullable String tableForOrder;
    private long autoRetryAt;
    private int autoStartFails, autoSkips;
    private @Nullable String autoSkipFor;

    private CraftTableCap.State tableCapState() {
        List<Long> times = CONFIG.craftTablePlacedAt == null ? List.of() : new ArrayList<>(CONFIG.craftTablePlacedAt);
        return new CraftTableCap.State(times, CONFIG.craftTableOrder == null || CONFIG.craftTableOrder.isEmpty() ? null : CONFIG.craftTableOrder, CONFIG.craftTableOrderCount);
    }

    /** What `stash craftorders` shows about tables put down for craft orders. */
    public String craftTablePlacementsLine() {
        CraftTableCap.State st = tableCapState();
        String id = orders.flow.orderId();
        return CraftTableCap.lastHour(st, System.currentTimeMillis()) + " of " + CraftTableCap.PER_HOUR + " this hour, "
            + (id == null ? 0 : CraftTableCap.forOrder(st, id)) + " of " + CraftTableCap.PER_ORDER + " for the order in hand";
    }

    /** A craft order is about to put a table down (taken from the stash or crafted): refused past the caps. Counted only when one is really put down. */
    private boolean tablePlacementRefused(String[] why) {
        String order = tableForOrder;
        if (order == null) return false;
        if (!CraftTableCap.allowed(tableCapState(), order, System.currentTimeMillis())) {
            why[0] = "no more crafting tables to put down (" + craftTablePlacementsLine() + ")";
            return true;
        }
        return false;
    }

    /** A table for a craft order was put down: counted (one for the order, three in the hour) and saved. */
    private void recordTablePlacement(String order) {
        long now = System.currentTimeMillis();
        CraftTableCap.State next = CraftTableCap.record(tableCapState(), order, now);
        CONFIG.craftTablePlacedAt = new ArrayList<>(next.times());
        CONFIG.craftTableOrder = next.orderId() == null ? "" : next.orderId();
        CONFIG.craftTableOrderCount = next.orderCount();
        try {
            com.aquarius.Globals.saveConfigAsync();
        } catch (RuntimeException | LinkageError e) {
            // Kept in memory at least
        }
    }

    private AnvilFinder.@Nullable Scan tableScanNow;
    private List<BlockPos> tableScanFound = List.of();
    private long tableScanDoneAt = Long.MIN_VALUE / 4;
    // Tables given up on (could not be reached or opened) this session: not found again
    private final Set<BlockPos> tableSkip = new HashSet<>();
    // The table decision for the pre-check, worked out at most every few seconds; never scans
    private long tableObtainAt = Long.MIN_VALUE / 4;
    private boolean tableObtain;
    // A table could not be had for this task: told to whoever asks for it next, once
    private @Nullable String tableFailedTask, tableFailedWhy;
    private long tableFailedAt;
    // Where the bot was when the table session began (a change of dimension ends it)
    private @Nullable String tableRunDim;

    private static final int TABLE_DECISION_TICKS = 20 * 5, TABLE_FAIL_TTL_TICKS = 20 * 60 * 2;

    private void tableResetState() {
        tableScanNow = null;
        tableScanFound = List.of();
        tableScanDoneAt = Long.MIN_VALUE / 4;
        tableSkip.clear();
        tableObtainAt = Long.MIN_VALUE / 4;
        tableFailedTask = null;
        tableFailedWhy = null;
        tableRunDim = null;
    }

    /** The table was set, cleared, forgotten or put down: what was seen about tables is forgotten. */
    private void tableChanged() {
        tableScanDoneAt = Long.MIN_VALUE / 4;
        tableObtainAt = Long.MIN_VALUE / 4;
    }

    private void tableFail(String task, String why) {
        tableFailedTask = task;
        tableFailedWhy = why;
        tableFailedAt = tickCount;
        tableObtainAt = Long.MIN_VALUE / 4;
    }

    /** The bot died, or is in another dimension than the one the table session began in. */
    private boolean tableRunBroken() {
        if (!CACHE.getPlayerCache().isAlive()) return true;
        String now = Stash.currentDimension();
        return tableRunDim != null && now != null && !com.porter.qwertestash.stash.Dimensions.same(tableRunDim, now);
    }

    /** A found, placed or crafted table that can't be reached or opened is forgotten (a hand-set one never is). */
    private void forgetTable() {
        CraftingTableSpot.Spot spot = CraftingTableSpot.get();
        if (spot == null || CraftingTableSpot.websiteActive() || CraftingTableSpot.SET.equals(CraftingTableSpot.source())) return;
        tableSkip.add(spot.pos());
        CraftingTableSpot.clear();
        tableChanged();
        info("The crafting table I knew is gone, looking for another.");
    }

    private boolean tableScanFresh() {
        return tickCount - tableScanDoneAt < ANVIL_SCAN_TICKS;
    }

    private void startTableScan() {
        Stash s = stash;
        if (tableScanNow != null || s == null) return;
        List<BlockPos> at = new ArrayList<>();
        for (Box b : s.boxes) if (!b.delivery) at.add(b.pos);
        tableScanNow = new AnvilFinder.Scan(AnvilFinder.region(s.bounds(), at, playerFeet(), CONFIG.radius, CONFIG.height), CraftingTableFinder::isTable);
        advanceTableScan();
    }

    private void advanceTableScan() {
        AnvilFinder.Scan sc = tableScanNow;
        if (sc == null) return;
        sc.step(anvilBlocks, AnvilFinder.LOOKUPS_PER_TICK);
        if (!sc.done()) return;
        tableScanFound = new ArrayList<>(sc.found());
        tableScanDoneAt = tickCount;
        tableScanNow = null;
        final long looked = sc.lookups();
        final int n = tableScanFound.size();
        debug(() -> "Crafting table search: " + looked + " block lookups, " + n + " tables standing.");
    }

    /** The nearest table the last search found that still stands, in the stash area, with a spot beside it to stand on. */
    private @Nullable BlockPos nearestScannedTable() {
        Stash s = stash;
        if (s == null) return null;
        List<BlockPos> standing = new ArrayList<>();
        for (BlockPos p : tableScanFound) if (CraftingTableFinder.isTable(anvilBlocks.name(p.x(), p.y(), p.z()))) standing.add(p);
        CraftBounds.StashArea area = stashArea(s);
        return CraftingTableFinder.nearest(standing, playerFeet(), p -> !tableSkip.contains(p) && CraftBounds.contains(area, p, 0) && CraftingTableFinder.standBeside(p, anvilBlocks));
    }

    /**
     * What crafting may take for a table (found, placed or crafted for itself): the pool less what the other units take and less what is left of
     * the order's craft budget, so a table never eats ingredients a craft line of the order is counting on.
     */
    private Map<String, Long> residualNet(CraftPool.Stock stock, List<Unit> others) {
        Map<String, Long> out = new HashMap<>(CraftPool.residual(stock, others));
        CraftLedger ledger = orders.flow.craftLedger();
        if (ledger != null && orders.flow.running()) {
            for (var e : ledger.budget().entrySet()) out.computeIfPresent(e.getKey(), (k, v) -> Math.max(0, v - e.getValue()));
        }
        return out;
    }

    /** The stash's crafting table items no line of the order needs, as loads to gather; empty when there is none. */
    private List<Order.Lot> tableLots() {
        Stash s = orders.st();
        if (s == null) return List.of();
        CraftPool.Stock stock = SourceRules.craftSurvey(s.boxes, new HashSet<>(List.of(CraftingTableFinder.TABLE_ID)), orders.reservedShulkerKeys(), CONFIG.useNamedShulkers, neverUse(), sourceLive).plainOnly();
        List<Unit> others = orders.flow.holdsOrder() ? remainingUnits() : List.of();
        if (residualNet(stock, others).getOrDefault(CraftingTableFinder.TABLE_ID, 0L) <= 0) return List.of();
        return CraftPool.lots(stock, Map.of(CraftingTableFinder.TABLE_ID, 1L), others);
    }

    /** What a table could be crafted from out of the stash's planks or logs (not what the bot carries). */
    private CraftingTableFinder.Material tableMaterial() {
        return CraftingTableFinder.material(recipesOrNull(), residualNet(craftStock().plainOnly(), orders.flow.holdsOrder() ? remainingUnits() : List.of()), neverUse());
    }

    /**
     * A table could be had if a craft needed one: standing in the area (only known from a search made in the last 10 s; the pre-check
     * never makes one), in the stash, or craftable. Not known to be impossible counts as possible. Kept for a few seconds.
     */
    private boolean tableObtainable() {
        if (tickCount - tableObtainAt < TABLE_DECISION_TICKS && tickCount >= tableObtainAt) return tableObtain;
        boolean yes;
        try {
            yes = !tableLots().isEmpty() || tableMaterial() != CraftingTableFinder.Material.NONE || !tableScanFresh() || nearestScannedTable() != null;
        } catch (RuntimeException e) {
            yes = true;
        }
        tableObtain = yes;
        tableObtainAt = tickCount;
        return yes;
    }

    /**
     * A craft is about to start (and was found possible): makes sure a crafting table is there. Ready: one is set or remembered and
     * there. Wait: the search is still going, ask again. Started: a run was begun to take one from the stash or craft one, and it puts
     * the table down; the craft is asked for again afterwards. Failed: none can be had, {@code why[0]} says why (once for this task).
     */
    private TableWay ensureTable(String task, String[] why) {
        if (stash == null || !CACHE.getPlayerCache().isAlive()) return TableWay.Ready;
        if (CraftingTableSpot.isSet()) tableFailedTask = null;
        if (task.equals(tableFailedTask) && tickCount - tableFailedAt < TABLE_FAIL_TTL_TICKS) {
            why[0] = tableFailedWhy == null ? CraftingTableFinder.NONE : tableFailedWhy;
            tableFailedTask = null;
            tableFailedWhy = null;
            return TableWay.Failed;
        }
        CraftingTableSpot.Spot spot = CraftingTableSpot.get();
        // Another dimension is said by tableProblem, as before
        if (spot != null && !CraftingTableSpot.here(spot)) return TableWay.Ready;
        boolean gone = spot != null && Containers.isLoaded(spot.pos()) && !CraftingTableSpot.isTable(spot.pos());
        if (spot != null && !gone) return TableWay.Ready;
        if (gone) {
            info("The crafting table I knew is gone, looking for another.");
            tableSkip.add(spot.pos());
            CraftingTableSpot.clear();
            tableChanged();
        }
        if (!tableScanFresh()) {
            startTableScan();
            if (!tableScanFresh()) return TableWay.Wait;
        }
        CraftingTableFinder.World world = new CraftingTableFinder.World() {
            @Override
            public boolean isTable(BlockPos p) {
                return !Containers.isLoaded(p) || CraftingTableSpot.isTable(p);
            }

            @Override
            public @Nullable BlockPos search() {
                return nearestScannedTable();
            }

            @Override
            public boolean stockHasTable() {
                return !tableLots().isEmpty();
            }

            @Override
            public CraftingTableFinder.Material craftMaterial() {
                return tableMaterial();
            }
        };
        CraftingTableFinder.Decision d = CraftingTableFinder.decide(null, world);
        String dimension = Stash.currentDimension();
        switch (d.step()) {
            case FOUND -> {
                if (dimension == null || d.pos() == null) {
                    why[0] = CraftingTableFinder.NONE + ": the dimension isn't known";
                    return TableWay.Failed;
                }
                CraftingTableSpot.remember(dimension, d.pos().x(), d.pos().y(), d.pos().z(), CraftingTableSpot.FOUND);
                tableChanged();
                info("Found a crafting table in the stash area, using it for crafting from now on.");
                return TableWay.Ready;
            }
            case PLACE_FROM_STOCK -> {
                if (tablePlacementRefused(why)) return TableWay.Failed;
                List<Order.Lot> lots = tableLots();
                if (lots.isEmpty()) {
                    why[0] = CraftingTableFinder.NONE;
                    return TableWay.Failed;
                }
                AnvilRun r = new AnvilRun(task, lots);
                r.kind = PlaceKind.Table;
                r.forOrderCraft = tableForOrder != null;
                r.startTick = tickCount;
                anvilRun = r;
                tableRunDim = dimension;
                info("No crafting table in the area, taking one out of the stash to place.");
                continueAnvilRun();
                return TableWay.Started;
            }
            case CRAFT -> {
                if (tablePlacementRefused(why)) return TableWay.Failed;
                Recipes recipes = recipesOrNull();
                CraftPool.Stock stock = craftStock().plainOnly();
                List<Unit> others = orders.flow.holdsOrder() ? remainingUnits() : List.of();
                CraftPlanner.Plan plan = recipes == null ? null : CraftPlanner.plan(recipes, CraftingTableFinder.TABLE_ID, 1, residualNet(stock, others), CraftingTableFinder.limits(neverUse()));
                if (plan == null || !plan.ok() || !CraftingTableFinder.fitsInventoryGrid(plan)) {
                    why[0] = CraftingTableFinder.NONE + (plan == null || plan.ok() ? "" : ": " + plan.refusal());
                    return TableWay.Failed;
                }
                CraftRun run = new CraftRun(null, task, plan, CraftPool.lots(stock, plan.fromStock(), others));
                run.forTable = true;
                run.forOrderCraft = tableForOrder != null;
                tableRunDim = dimension;
                info("No crafting table in the area or the stash, crafting one from {} in the inventory grid.", d.material() == CraftingTableFinder.Material.LOGS ? "logs" : "planks");
                beginCraft(run, "crafting table: none in the area or the stash");
                return TableWay.Started;
            }
            default -> {
                why[0] = d.failure() == null ? CraftingTableFinder.NONE : d.failure();
                return TableWay.Failed;
            }
        }
    }

    /** The table craft is over: made, it is put down; not made, whoever asked is told it can't be had. */
    private void finishTableCraft(CraftRun r) {
        if (r.made >= 1 && r.tableSlot >= 0 && tableItem(inventoryItem(r.tableSlot)) && !tableRunBroken()) {
            AnvilRun p = new AnvilRun(r.task, List.of());
            p.kind = PlaceKind.Table;
            p.forOrderCraft = r.forOrderCraft;
            p.crafted = true;
            p.heldSlot = r.tableSlot;
            p.phase = AnvilPhase.Place;
            p.startTick = tickCount;
            anvilRun = p;
            step("crafting table: crafted one; next: put it down.");
            return;
        }
        // Not made (or the session ended): what is left of it goes back with the rest; never counted as a crafted shulker
        craftKeep.keySet().removeIf(slot -> tableItem(inventoryItem(slot)));
        String why = r.failure == null ? "nothing was made" : r.failure;
        warn("Couldn't craft a crafting table: {}.", why);
        step("crafting table: couldn't craft one: " + why + ".");
        tableFail(r.task, CraftingTableFinder.NONE + ": couldn't craft one (" + why + ")");
    }

    // ------------------------------------------------------------------------------------------------------------------
    // Delivery storage (1.9.0): the delivery container and the double chests put up above it (DeliveryBook keeps which are full).
    // With "auto expand" on, when every chest is full another double chest is taken from the stash (or crafted, when crafting is on)
    // and put up one block above the top row: the first half by a sneaking click on the top face of the chest below, the second by
    // a sneaking click on the side face of the first (ChestStack has the rules and why). A chest a player emptied is found by its
    // lid (the block event with the viewer count) and looked at once; where the lid was out of sight, by looking again now and then
    // while an order waits for space. The chests are boxes of the stash flagged delivery (never sorted into, read, taken from,
    // indexed or searched), saved with the stash.

    private enum ChestPhase { Gather, Place, PutBack }

    private enum ChestStep { Walk, Hotbar, Aim, Click, Wait }

    private static final class ChestRun {
        final String task;
        @Nullable Bin bin;
        ChestPhase phase = ChestPhase.Gather;
        int jobs, tries, crafts;
        // The slot the chest was moved to for placing, the swap made to get it into the hotbar and the hand before: undone at the end
        int heldSlot = -1, swapFrom = -1, swapTo = -1, handBefore = -1;
        @Nullable String failure;
        boolean placed;
        // Stands given up on this run
        final Set<BlockPos> stands = new HashSet<>();

        ChestRun(String task) {
            this.task = task;
        }
    }

    private @Nullable ChestRun chestRun;
    private ChestStack.@Nullable Plan chestPlan;
    // Which half is being put up (0 the first, 1 the second), the stand it is put up from, and where it is in that
    private int chestHalf;
    private @Nullable BlockPos chestStandCell;
    private int chestStandFacingX, chestStandFacingZ;
    private @Nullable ChestStep chestStep;
    private int chestStepTicks, chestOpenedTries;
    private @Nullable InputRequestFuture chestFuture;
    private static final int CHEST_PLACE_TICKS = 20 * 8, CHEST_MAX_TRIES = 6;
    private static final long EXPAND_RETRY_MS = 60_000L, EXPAND_EVAL_MS = 5_000L;

    // Why another chest could not be put up the last time (not geometry: no chests to be had, placing failed), and when
    private volatile @Nullable String expandFailure;
    private volatile long expandFailureAt;
    // The last evaluation of why no chest can be put up (supply included), cached for a few seconds
    private volatile @Nullable String expandEval;
    private volatile long expandEvalAt;
    private @Nullable String chestCraftFailure;
    // The lid events: the delivery chest the bot had open last, and when (the bot's own lid is not a player's)
    private volatile @Nullable Box lidBox;
    private volatile long lidMs;

    private DeliveryBook deliveryBook() {
        Stash s = orders.st();
        return new DeliveryBook(s == null || s.deliveryStorage == null ? new ArrayList<>() : s.deliveryStorage);
    }

    private static DeliveryBook.Pos dpos(BlockPos p) {
        return new DeliveryBook.Pos(p.x(), p.y(), p.z());
    }

    private static BlockPos bpos(DeliveryBook.Pos p) {
        return new BlockPos(p.x(), p.y(), p.z());
    }

    private static boolean plainChest(@Nullable Item it) {
        return it != null && it.id.equals("chest") && !it.blockData && it.contents.isEmpty();
    }

    private static boolean chestBlockAt(BlockPos p) {
        return Containers.isLoaded(p) && World.getBlock(p.x(), p.y(), p.z()).name().equals("chest");
    }

    /** A lid event from the network thread: a chest's viewer count changed. */
    private void lidSeen(int x, int y, int z, int viewers) {
        Stash st = stash != null ? stash : Stashes.current();
        if (st == null || st.deliveryStorage == null || st.deliveryStorage.isEmpty()) return;
        Box b = lidBox;
        boolean bot = b != null && b.has(new BlockPos(x, y, z)) && System.currentTimeMillis() - lidMs < 4_000;
        new DeliveryBook(st.deliveryStorage).onViewers(new DeliveryBook.Pos(x, y, z), viewers, bot);
    }

    private final ChestStack.View stackView = new ChestStack.View() {
        @Override
        public boolean loaded(int x, int z) {
            return World.isChunkLoadedBlockPos(x, z);
        }

        @Override
        public boolean air(int x, int y, int z) {
            return World.getBlock(x, y, z).isAir() && !playerBoxIntersectsCell(x, y, z);
        }

        @Override
        public boolean conductor(int x, int y, int z) {
            var b = World.getBlock(x, y, z);
            return !b.isAir() && b.solidBlock();
        }

        @Override
        public @Nullable String name(int x, int y, int z) {
            try {
                return World.getBlock(x, y, z).name();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public boolean standable(int x, int y, int z) {
            return placeView.standable(x, y, z);
        }

        @Override
        public int minY() {
            return World.getCurrentDimension().minY();
        }

        @Override
        public int maxY() {
            var d = World.getCurrentDimension();
            return d.minY() + d.height();
        }
    };

    /** Where the next row goes, or why it can't. */
    private ChestStack.Result stackPlan() {
        DeliveryBook book = deliveryBook();
        List<DeliveryBook.Entry> row = book.topRow();
        if (row.isEmpty()) return new ChestStack.Result(null, "no delivery container is set");
        DeliveryBook.Entry top = row.get(0);
        List<DeliveryBook.Pos> parts = top.parts();
        BlockPos p0 = bpos(parts.get(0));
        if (!Containers.isLoaded(p0)) return new ChestStack.Result(null, "the delivery chest isn't loaded");
        String name = World.getBlock(p0.x(), p0.y(), p0.z()).name();
        int fx = 0, fz = 0;
        int state = World.getBlockStateId(p0.x(), p0.y(), p0.z());
        if (World.hasBlockStateProperty(state, BlockStateProperties.HORIZONTAL_FACING)) {
            Direction d = World.getBlockStateProperty(state, BlockStateProperties.HORIZONTAL_FACING);
            if (d != null) {
                fx = d.x();
                fz = d.z();
            }
        }
        return ChestStack.next(parts, fx, fz, !name.equals("barrel"), book.sorted().get(0).y(), dpos(playerFeet()), stackView);
    }

    /** Why no chest can be put up now, or null when one can: geometry, then the supply (chests in the stash or craftable). */
    private @Nullable String expandBlockerNow() {
        long now = System.currentTimeMillis();
        String failed = expandFailure;
        if (failed != null && now - expandFailureAt < EXPAND_RETRY_MS) return failed;
        if (now - expandEvalAt < EXPAND_EVAL_MS) return expandEval;
        String why = computeExpandBlocker();
        expandEval = why;
        expandEvalAt = now;
        return why;
    }

    private @Nullable String computeExpandBlocker() {
        ChestStack.Result res = stackPlan();
        if (res.plan() == null) return res.blocked();
        int need = res.plan().second() != null ? 2 : 1;
        int have = carriedChestCount();
        if (have >= need) return null;
        if (!chestLots(need - have).isEmpty()) return null;
        if (!craftingOn()) return "no chests left in the stash (crafting is off)";
        Recipes recipes = recipesOrNull();
        if (recipes == null) return "no chests left in the stash and no recipe table yet (the Stash Manager server sends it; crafting stays off until it has)";
        String table = tableProblem(CHEST_TASK);
        if (table != null) return "no chests left in the stash, and " + table;
        CraftPool.Stock stock = craftStock();
        List<Unit> others = orders.flow.holdsOrder() ? remainingUnits() : List.of();
        CraftPlanner.Limits limits = new CraftPlanner.Limits(tableForPlan(), Math.max(1, CONFIG.craftMaxOpsPerTask), Integer.MAX_VALUE, Integer.MAX_VALUE, neverUse());
        CraftPlanner.Plan plan = CraftPlanner.plan(recipes, CHEST, need - have, CraftPool.residual(stock, others), limits);
        return plan.ok() ? null : "no chests left in the stash, and " + plan.refusal();
    }

    /** The chests carried (taken from the stash or crafted). */
    private int carriedChestCount() {
        int n = 0;
        for (int slot : carriedSlots()) {
            Item it = inventoryItem(slot);
            if (plainChest(it)) n += it.count;
        }
        return n;
    }

    private int carriedChestSlot() {
        ChestRun r = chestRun;
        if (r != null && r.heldSlot >= 0 && plainChest(inventoryItem(r.heldSlot))) return r.heldSlot;
        for (int slot : carriedSlots()) if (plainChest(inventoryItem(slot))) return slot;
        return -1;
    }

    /** {@code n} empty chests in the stash that no line of the order needs, as loads to gather; empty when there are not enough. */
    private List<Order.Lot> chestLots(int n) {
        Stash s = orders.st();
        if (s == null || n <= 0) return List.of();
        CraftPool.Stock stock = SourceRules.craftSurvey(s.boxes, Set.of(CHEST), orders.reservedShulkerKeys(), CONFIG.useNamedShulkers, Set.of(), sourceLive);
        List<Unit> others = orders.flow.holdsOrder() ? remainingUnits() : List.of();
        Map<String, Long> left = CraftPool.residual(stock, others);
        if (left.getOrDefault(CHEST, 0L) < n) return List.of();
        return CraftPool.lots(stock, Map.of(CHEST, (long) n), others);
    }

    /** What the delivery storage can take now, as the order flow asks. */
    private DeliveryRoom deliveryRoomNow() {
        if (!DeliverySpot.isSet() || stash == null) return DeliveryRoom.ROOM;
        syncDeliveryBox();
        DeliveryBook book = deliveryBook();
        if (book.chests() == 0) return DeliveryRoom.ROOM;
        // Only worked out when every chest is full (the supply check reads the whole stash)
        String blocker = CONFIG.autoExpandDelivery && book.firstUsable() == null ? expandBlockerNow() : null;
        return book.room(CONFIG.autoExpandDelivery, blocker, System.currentTimeMillis(), DeliveryBook.RECHECK_MS);
    }

    /** The heartbeat's deliveryStorage (null when no delivery container is set): chests, full ones, whether another can go up. */
    public ManagerIndex.@Nullable DeliveryStorage deliveryStorage() {
        try {
            Stash s = stash != null ? stash : Stashes.current();
            if (s == null || !DeliverySpot.isSet() || s.deliveryStorage == null) return null;
            DeliveryBook book = new DeliveryBook(s.deliveryStorage);
            int chests = book.chests(), full = book.fullCount();
            boolean auto = CONFIG.autoExpandDelivery;
            String blocked = null;
            if (chests > 0) {
                if (!auto) blocked = full >= chests ? "auto expand is off" : null;
                else {
                    String failed = expandFailure;
                    if (failed != null && System.currentTimeMillis() - expandFailureAt < EXPAND_RETRY_MS) blocked = failed;
                    else if (book.firstUsable() == null && Alerts.online() && DeliverySpot.here(DeliverySpot.get())) {
                        // Only worked out when every chest is full, and at most every few seconds
                        blocked = storageBlocker();
                    }
                }
            }
            return new ManagerIndex.DeliveryStorage(auto, chests, full, auto && blocked == null && chests > 0, blocked);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private @Nullable String storageCached;
    private long storageAt;

    /** The geometry blocker, cached for a few seconds (the heartbeat and the commands ask often, from other threads). */
    private synchronized @Nullable String storageBlocker() {
        long now = System.currentTimeMillis();
        if (now - storageAt < EXPAND_EVAL_MS) return storageCached;
        storageAt = now;
        try {
            ChestStack.Result res = stackPlan();
            storageCached = res.plan() == null ? res.blocked() : expandEval;
        } catch (RuntimeException e) {
            storageCached = null;
        }
        return storageCached;
    }

    /** "3 chests, 1 full, can expand" or "... blocked: why", for the commands. */
    public String deliveryStorageLine() {
        ManagerIndex.DeliveryStorage d = deliveryStorage();
        if (d == null) return "no delivery container set";
        String tail = d.blocked() != null ? "blocked: " + d.blocked() : d.canExpand() ? "can expand" : d.autoExpand() ? "can expand" : "auto expand is off";
        return d.chests() + (d.chests() == 1 ? " chest, " : " chests, ") + d.full() + " full, " + tail;
    }

    // Looking at a full chest again

    private boolean startRecheck(OrderFlow.Recheck rc) {
        if (compact != null && compact.bin == null) return false;
        if (syncDeliveryBox() == null) return false;
        Box box = stash.boxAt(bpos(rc.at()));
        if (box == null) return false;
        orders.baseTaken = true;
        orders.recheck = true;
        boolean started = start(Job.Deliver, box);
        jobStartedInside = false;
        step("order: looking at a delivery chest that may have been emptied.");
        return started;
    }

    /** The open delivery chest was read (Stashes.read): it has room again, or it is still full. */
    private void recheckRead(Container c) {
        orders.recheck = false;
        Box t = target;
        if (t == null) return;
        DeliveryBook book = deliveryBook();
        DeliveryBook.Entry e = book.find(dpos(t.pos));
        if (e == null) return;
        boolean room = false;
        for (int i = 0; i < Stashes.slots(c); i++) if (Item.of(c.getItemStack(i)) == null) room = true;
        long now = System.currentTimeMillis();
        if (room) book.markRoom(e, now);
        else book.markFull(e, now);
        stash.markDirty();
        info("Order: a delivery chest was looked at again: {}.", room ? "it has room" : "still full");
    }

    /** The delivery chest being put into was full. */
    private void deliveryChestFull(@Nullable Box box) {
        if (box == null) return;
        DeliveryBook book = deliveryBook();
        DeliveryBook.Entry e = book.find(dpos(box.pos));
        if (e == null) return;
        book.markFull(e, System.currentTimeMillis());
        stash.markDirty();
    }

    // Putting a double chest up

    /** Starts putting another row of chests up (OrderFlow.Expand). @return true when a job was started */
    private boolean startExpand(OrderFlow.Expand x) {
        if (chestRun != null) return continueChestRun();
        if (compact != null || craft != null || anvilRun != null) return false;
        if (syncDeliveryBox() == null) return false;
        chestRun = new ChestRun(String.valueOf(orders.flow.orderId()));
        chestCraftFailure = null;
        orders.flow.expandStarted(x.unit());
        step("order: the delivery storage is full; putting another double chest up above it.");
        return continueChestRun();
    }

    /** Chests could not be had or put up: said, and remembered for a minute before it is tried again. */
    private boolean chestGaveUp(ChestRun r, String why) {
        chestFail(r, why);
        return continueChestRun();
    }

    private void chestFail(ChestRun r, String why) {
        r.failure = why;
        r.phase = ChestPhase.PutBack;
        expandFailure = why;
        expandFailureAt = System.currentTimeMillis();
        expandEvalAt = 0;
        step("delivery storage: couldn't put another chest up: " + why + "; the order waits for space.");
        warn("Delivery storage: couldn't put another chest up ({}).", why);
    }

    private boolean continueChestRun() {
        ChestRun r = chestRun;
        if (r == null) return false;
        if (job != null) return true;
        if (!orders.gate()) return false;
        if (r.phase != ChestPhase.PutBack && (!orders.flow.running() || orders.flow.cancelRequested() || orders.flow.parkRequested())) {
            r.failure = "the order ended";
            r.phase = ChestPhase.PutBack;
        }
        switch (r.phase) {
            case Gather -> {
                if (++r.jobs > 14) return chestGaveUp(r, "it took too many trips");
                ChestStack.Result res = stackPlan();
                if (res.plan() == null) return chestGaveUp(r, res.blocked());
                ChestStack.Plan plan = res.plan();
                // Chests already standing in the new row (a try cut short before) are taken as they are
                int toPlace = (chestBlockAt(bpos(plan.first())) ? 0 : 1) + (plan.second() == null || chestBlockAt(bpos(plan.second())) ? 0 : 1);
                if (toPlace == 0) {
                    chestPlaced(plan);
                    r.placed = true;
                    r.phase = ChestPhase.PutBack;
                    return continueChestRun();
                }
                int have = carriedChestCount();
                if (have >= toPlace) {
                    r.phase = ChestPhase.Place;
                    return continueChestRun();
                }
                // What a craft made or gathering took is in the inventory: what is still missing is gathered or crafted
                Bin gathering = r.bin;
                if (gathering != null && needOf(gathering) == null) gathering = r.bin = null;
                if (gathering == null) {
                    if (chestCraftFailure != null) {
                        String why = chestCraftFailure;
                        chestCraftFailure = null;
                        return chestGaveUp(r, "no chests in the stash, and crafting failed: " + why);
                    }
                    List<Order.Lot> lots = chestLots(toPlace - have);
                    if (lots.isEmpty()) {
                        if (!craftingOn()) return chestGaveUp(r, "no chests left in the stash (crafting is off)");
                        if (r.crafts + 1 > 2) return chestGaveUp(r, "crafting chests didn't work");
                        String[] why = new String[1];
                        CraftRun cr = planChestCraft(toPlace - have, why);
                        if (cr == null) return chestGaveUp(r, "no chests left in the stash, and " + why[0]);
                        // The chests can be crafted (the preview): only now is a crafting table found, put down or crafted
                        boolean hadTable = CraftingTableSpot.isSet();
                        switch (ensureTable(CHEST_TASK, why)) {
                            case Wait -> {
                                return false;
                            }
                            case Started -> {
                                return job != null;
                            }
                            case Failed -> {
                                return chestGaveUp(r, "no chests left in the stash, and " + why[0]);
                            }
                            case Ready -> {
                                if (!hadTable) {
                                    cr = planChestCraft(toPlace - have, why);
                                    if (cr == null) return chestGaveUp(r, "no chests left in the stash, and " + why[0]);
                                }
                            }
                        }
                        r.crafts++;
                        craft = cr;
                        return beginCraft(cr, "delivery storage: no chests in the stash");
                    }
                    gathering = r.bin = new Bin(new Unit(Unit.Kind.Bin, "", new ArrayList<>(lots)));
                }
                if (freeCarryCount() < 2) return chestGaveUp(r, "no carry room");
                Box source = binSourceFor(gathering);
                if (source != null) {
                    gatherLeft = 1;
                    step("delivery storage: taking a chest from the stash; next: put it up.");
                    return start(Job.Gather, source);
                }
                if (shulkerPause() != null && findUnpackSource(gathering) != null) return chestGaveUp(r, "shulker handling is paused (" + shulkerPause() + ")");
                if (startBinUnpack(gathering, "delivery storage: ")) return true;
                return chestGaveUp(r, "the chests in the stash couldn't be taken");
            }
            case Place -> {
                if (carriedChestSlot() < 0) {
                    r.phase = ChestPhase.Gather;
                    return continueChestRun();
                }
                if (++r.tries > CHEST_MAX_TRIES) return chestGaveUp(r, "it couldn't be put up in " + CHEST_MAX_TRIES + " tries");
                ChestStack.Result res = stackPlan();
                if (res.plan() == null) return chestGaveUp(r, res.blocked());
                ChestStack.Plan plan = res.plan();
                chestPlan = plan;
                chestHalf = chestBlockAt(bpos(plan.first())) ? 1 : 0;
                if (chestHalf == 1 && (plan.second() == null || chestBlockAt(bpos(plan.second())))) {
                    chestPlaced(plan);
                    r.placed = true;
                    r.phase = ChestPhase.PutBack;
                    return continueChestRun();
                }
                if (!pickChestStand(r, plan)) return chestGaveUp(r, "nowhere to stand that can reach it (" + r.stands.size() + " stands tried)");
                chestStep = ChestStep.Walk;
                chestStepTicks = 0;
                chestOpenedTries = 0;
                chestFuture = null;
                markJobStart();
                job = Job.Chest;
                target = null;
                actions = 0;
                stepTicks = 0;
                jobRunTicks = 0;
                step = Step.Walking;
                resetJobState();
                jobStartedInside = false;
                step("delivery storage: putting a chest up above the delivery chest" + (plan.second() != null ? " (a double chest)" : "") + "; next: carry on with the order.");
                return true;
            }
            case PutBack -> {
                if (chestSwapBack(r)) return true;
                for (int slot : new ArrayList<>(craftKeep.keySet())) if (plainChest(inventoryItem(slot))) craftKeep.remove(slot);
                List<Integer> left = new ArrayList<>(looseCarried());
                left.removeIf(slot -> craftKeep.containsKey(slot) || pendingSourceShulker(slot));
                if (!left.isEmpty() && r.jobs++ < 40 && handle == null && returnSpentShulker()) return true;
                if (!left.isEmpty() && r.tries++ < 40 && putBack(left, "delivery storage: putting " + left.size() + " stacks back")) return true;
                chestRun = null;
                chestPlan = null;
                return false;
            }
        }
        return false;
    }

    /** The stand to put the current half up from, nearest first, one not given up on. @return false when there is none */
    private boolean pickChestStand(ChestRun r, ChestStack.Plan plan) {
        chestStandCell = null;
        if (chestHalf == 0) {
            for (ChestStack.Stand s : plan.firstStands()) {
                BlockPos cell = bpos(s.cell());
                if (r.stands.contains(cell)) continue;
                chestStandCell = cell;
                chestStandFacingX = s.facingX();
                chestStandFacingZ = s.facingZ();
                return true;
            }
            return false;
        }
        for (DeliveryBook.Pos s : plan.secondStands()) {
            BlockPos cell = bpos(s);
            if (r.stands.contains(cell)) continue;
            chestStandCell = cell;
            return true;
        }
        return false;
    }

    /** The chest is put up (or was): the book and the stash know it, the delivery storage has one more. */
    private void chestPlaced(ChestStack.Plan plan) {
        Stash st = stash;
        if (st == null) return;
        DeliveryBook book = deliveryBook();
        // What the game made of them: a double chest, or two singles when they did not join
        addPlacedChest(book, plan.first());
        if (plan.second() != null) addPlacedChest(book, plan.second());
        expandFailure = null;
        expandEvalAt = 0;
        syncDeliveryBox();
        st.markDirty();
        st.save();
        Stashes.version++;
        info("Delivery storage: {} chests now.", book.chests());
        com.porter.qwertestash.manager.ActivityFeed.INSTANCE.event("Stash Sorter", "info", "Another chest was put up for the delivery storage (" + book.chests() + " chests).");
    }

    /** A chest standing at this block joins the storage (as the double chest it is a half of, merged with the entry it already is in). */
    private void addPlacedChest(DeliveryBook book, DeliveryBook.Pos at) {
        if (!chestBlockAt(bpos(at))) return;
        Box one = Containers.makeBox(bpos(at));
        List<DeliveryBook.Pos> parts = new ArrayList<>();
        for (BlockPos p : one.parts) parts.add(dpos(p));
        book.addOrMerge(parts);
        syncedTick = -1;
    }

    /** Undoes the hotbar swap and the hand. @return true when a request was sent (or one is going out) */
    private boolean chestSwapBack(ChestRun r) {
        if (r.swapFrom < 0 && r.handBefore < 0) return false;
        if (busy()) return true;
        List<InventoryAction> back = new ArrayList<>();
        if (r.swapFrom >= 0 && (inventoryItem(r.swapFrom) != null || inventoryItem(r.swapTo) != null)) back.add(new MoveToHotbarSlot(invSlot(r.swapFrom), MoveToHotbarAction.from(r.swapTo)));
        if (r.handBefore >= 0 && CACHE.getPlayerCache().getHeldItemSlot() != r.handBefore) back.add(new SetHeldItem(r.handBefore));
        if (back.isEmpty()) {
            r.swapFrom = r.swapTo = r.handBefore = -1;
            return false;
        }
        // Not now when the request is refused: asked again next tick
        if (submit(back.toArray(new InventoryAction[0])) == RequestFuture.rejected) return true;
        if (r.swapFrom >= 0) swapHeld(r.swapFrom, r.swapTo);
        r.swapFrom = r.swapTo = r.handBefore = -1;
        wait = CONFIG.delayTicks;
        return true;
    }

    /** What the chest run holds now: the chests carried to be put up. */
    private Set<Integer> chestReserved() {
        ChestRun r = chestRun;
        if (r == null || r.phase == ChestPhase.PutBack) return Set.of();
        Set<Integer> out = new HashSet<>();
        Bin b = r.bin;
        if (b != null) for (BinTracker.Held h : carriedHeld(b)) out.add(h.slot());
        for (int slot : carriedSlots()) if (plainChest(inventoryItem(slot))) out.add(slot);
        if (r.heldSlot >= 0 && inventoryItem(r.heldSlot) != null) out.add(r.heldSlot);
        return out;
    }

    /** A click on a chest of the storage may open it: that lid is the bot's own, not a player's. */
    private void noteOwnLid(BlockPos at) {
        Stash st = stash;
        Box lb = st == null ? null : st.boxAt(at);
        if (lb != null && lb.delivery) {
            lidBox = lb;
            lidMs = System.currentTimeMillis();
        }
    }

    private void chestAimAt(double tx, double ty, double tz) {
        double px = CACHE.getPlayerCache().getX(), py = CACHE.getPlayerCache().getY(), pz = CACHE.getPlayerCache().getZ();
        aimYaw = ChestStack.yawTo(px, pz, tx, tz);
        aimPitch = ChestStack.pitchTo(px, py + ChestStack.SNEAK_EYE, pz, tx, ty, tz);
    }

    /** The point the click aims at for the half being put up. */
    private double[] chestAimPoint(ChestStack.Plan plan) {
        if (chestHalf == 0) {
            // The top face of the chest below the first: its (0.875 high) top, in the middle
            BlockPos below = bpos(plan.first()).add(0, -1, 0);
            String name = World.getBlock(below.x(), below.y(), below.z()).name();
            return new double[] {below.x() + 0.5, below.y() + (name.equals("barrel") ? 1.0 : ChestStack.CHEST_TOP), below.z() + 0.5};
        }
        // The side of the first chest that faces the second
        BlockPos a = bpos(plan.first()), b = bpos(plan.second());
        int dx = Integer.signum(b.x() - a.x()), dz = Integer.signum(b.z() - a.z());
        return new double[] {a.x() + 0.5 + dx * ChestStack.CHEST_SIDE, a.y() + 0.5, a.z() + 0.5 + dz * ChestStack.CHEST_SIDE};
    }

    /** One tick of putting the chest up: walk to the stand, hold the chest, sneak and aim, click, see it standing. */
    private void tickChestPlace() {
        ChestRun r = chestRun;
        ChestStack.Plan plan = chestPlan;
        ChestStep stepNow = chestStep;
        if (r == null || plan == null || stepNow == null || chestStandCell == null) {
            endChestPlace();
            return;
        }
        chestStepTicks++;
        if (!orders.flow.running() || orders.flow.cancelRequested() || orders.flow.parkRequested()) {
            endChestPlace();
            return;
        }
        if (busy()) return;
        BlockPos stand = chestStandCell;
        switch (stepNow) {
            case Walk -> {
                if (playerFeet().equals(stand)) {
                    activity.releaseOurPathing();
                    restoreRules();
                    chestStep = ChestStep.Hotbar;
                    chestStepTicks = 0;
                    return;
                }
                if (chestStepTicks > WALK_TIMEOUT || (chestStepTicks > 40 && !activity.ownPathing() && distSq(playerFeet(), stand) > 2)) {
                    activity.releaseOurPathing();
                    restoreRules();
                    r.stands.add(stand);
                    nextChestStand(r, "couldn't get to the stand");
                    return;
                }
                if (!activity.ownPathing() && activity.mayRequest()) {
                    rules(false, false);
                    PathingRequestFuture future = BARITONE.pathTo(new GoalBlock(stand));
                    activity.notePath(future, BARITONE.getCustomGoalProcess(), null, tickCount);
                    traceRequest("walk to a stand to put a chest up", future);
                    wait = 10;
                }
            }
            case Hotbar -> {
                int slot = carriedChestSlot();
                if (slot < 0) {
                    endChestPlace();
                    return;
                }
                // The chest in an allowed hotbar slot and in the hand (swapped back, with the hand, when the run ends)
                int hotbar = AnvilFinder.hotbarFor(slot, keepSlots(), new HashSet<>(carry), i -> inventoryItem(i) != null);
                if (hotbar == -1) {
                    r.tries = CHEST_MAX_TRIES;
                    endChestPlace();
                    return;
                }
                List<InventoryAction> request = new ArrayList<>();
                if (hotbar != slot) request.add(new MoveToHotbarSlot(invSlot(slot), MoveToHotbarAction.from(hotbar)));
                int held = CACHE.getPlayerCache().getHeldItemSlot();
                if (held != hotbar) request.add(new SetHeldItem(hotbar));
                if (!request.isEmpty()) {
                    if (submit(request.toArray(new InventoryAction[0])) == RequestFuture.rejected) return;
                    if (hotbar != slot) {
                        swapHeld(slot, hotbar);
                        if (r.swapFrom < 0) {
                            r.swapFrom = slot;
                            r.swapTo = hotbar;
                        }
                    }
                    if (held != hotbar && r.handBefore < 0) r.handBefore = held;
                    r.heldSlot = hotbar;
                    wait = CONFIG.delayTicks;
                    return;
                }
                r.heldSlot = hotbar;
                chestStep = ChestStep.Aim;
                chestStepTicks = 0;
            }
            case Aim -> {
                // The first half must be put up while the way the bot looks is the way the chest should face, and the cells must still be free
                double[] at = chestAimPoint(plan);
                BlockPos cell = bpos(chestHalf == 0 ? plan.first() : plan.second());
                if (!World.getBlock(cell.x(), cell.y(), cell.z()).isAir()) {
                    r.stands.add(stand);
                    nextChestStand(r, "the cell is not free");
                    return;
                }
                chestAimAt(at[0], at[1], at[2]);
                if (chestHalf == 0) {
                    double px = CACHE.getPlayerCache().getX(), pz = CACHE.getPlayerCache().getZ();
                    double lx = at[0] - px, lz = at[2] - pz;
                    int fx = 0, fz = 0;
                    if (Math.abs(lx) > Math.abs(lz)) fx = lx > 0 ? -1 : 1;
                    else fz = lz > 0 ? -1 : 1;
                    // Arriving off the middle of the cell turns the look a little: the other way along the same axis is as good
                    if (Math.abs(fx) != Math.abs(chestStandFacingX) || Math.abs(fz) != Math.abs(chestStandFacingZ)) {
                        // The look is across the planned axis: another stand
                        r.stands.add(stand);
                        nextChestStand(r, "the look would turn the chest the wrong way");
                        return;
                    }
                }
                if (!activity.mayRequest()) return;
                InputRequestFuture future = INPUTS.submit(InputRequest.builder().owner(this)
                    .input(Input.builder().sneaking(true).build()).yaw(aimYaw).pitch(aimPitch).priority(LABEL_INPUT_PRIORITY).build());
                activity.noteInput(future, tickCount);
                if (future == InputRequestFuture.rejected) return;
                chestStep = ChestStep.Click;
                chestStepTicks = 0;
            }
            case Click -> {
                // One tick of sneaking has gone out; the click on the chest below (the first half) or on the first chest's side
                BlockPos clicked = chestHalf == 0 ? bpos(plan.first()).add(0, -1, 0) : bpos(plan.first());
                noteOwnLid(clicked);
                if (!activity.mayRequest()) return;
                // Should it open the chest after all, it's ours to close
                lastOpenClickPos = clicked;
                openRequestedAt = lastOpenClickAt = System.currentTimeMillis();
                foreignSinceClick = false;
                InputRequestFuture future = INPUTS.submit(InputRequest.builder().owner(this)
                    .input(Input.builder().sneaking(true).rightClick(true)
                        .clickTarget(new ClickTarget.BlockPosition(clicked.x(), clicked.y(), clicked.z())).clickRequiresRotation(true).build())
                    .yaw(aimYaw).pitch(aimPitch).priority(LABEL_INPUT_PRIORITY).build());
                activity.noteInput(future, tickCount);
                if (future == InputRequestFuture.rejected) return;
                chestFuture = future;
                chestStep = ChestStep.Wait;
                chestStepTicks = 0;
            }
            case Wait -> {
                noteOwnLid(chestHalf == 0 ? bpos(plan.first()).add(0, -1, 0) : bpos(plan.first()));
                BlockPos want = chestHalf == 0 ? bpos(plan.first()) : bpos(plan.second());
                if (openContainerId() != 0) {
                    // Sneaking didn't take and it opened the chest: closed, and another go
                    if (closeSafely() == Close.Stuck) return;
                    if (++chestOpenedTries >= 3) {
                        r.stands.add(stand);
                        nextChestStand(r, "the click opened the chest instead of placing against it");
                    }
                    else {
                        chestStep = ChestStep.Aim;
                        chestStepTicks = 0;
                    }
                    return;
                }
                if (chestBlockAt(want)) {
                    activity.releaseOurPathing();
                    restoreRules();
                    if (chestHalf == 0 && plan.second() != null) {
                        // The first chest is the storage's from now (nothing else may take it for a chest of the stash)
                        addPlacedChest(deliveryBook(), plan.first());
                        syncDeliveryBox();
                        // The second half next: from a stand that sees the side of the first
                        chestHalf = 1;
                        chestFuture = null;
                        if (!pickChestStand(r, plan)) {
                            // No stand for the second: the first stands as a single chest
                            warn("Delivery storage: the second half of the chest couldn't be put up (no stand), the first stands alone.");
                            chestPlaced(plan);
                            r.placed = true;
                            r.phase = ChestPhase.PutBack;
                            endChestPlace();
                            return;
                        }
                        // Already standing where it can be clicked from
                        chestStep = playerFeet().equals(chestStandCell) ? ChestStep.Aim : ChestStep.Walk;
                        chestStepTicks = 0;
                        chestOpenedTries = 0;
                        return;
                    }
                    chestPlaced(plan);
                    r.placed = true;
                    r.phase = ChestPhase.PutBack;
                    endChestPlace();
                    return;
                }
                if (chestStepTicks > CHEST_PLACE_TICKS || (chestFuture != null && chestFuture.isDone() && chestStepTicks > 6)) {
                    r.stands.add(stand);
                    nextChestStand(r, "the chest didn't appear");
                }
            }
        }
    }

    /** This stand did not work: the next one for the half, or the run is given up on. */
    private void nextChestStand(ChestRun r, String why) {
        ChestStack.Plan plan = chestPlan;
        debug("Delivery storage: stand {} given up on ({}).", chestStandCell, why);
        chestFuture = null;
        if (plan == null || ++r.tries > CHEST_MAX_TRIES || !pickChestStand(r, plan)) {
            if (plan != null && chestHalf == 1) {
                // The first half stands: it is kept as a single chest of the storage, and the other chest goes back
                warn("Delivery storage: the second half of the chest couldn't be put up ({}), the first stands alone.", why);
                chestPlaced(plan);
                r.placed = true;
                r.phase = ChestPhase.PutBack;
                endChestPlace();
                return;
            }
            r.failure = why;
            r.tries = CHEST_MAX_TRIES;
            endChestPlace();
            return;
        }
        chestStep = ChestStep.Walk;
        chestStepTicks = 0;
        chestOpenedTries = 0;
    }

    /** The place job is over: the run goes on (puts back what is left, or tries again from the planner). */
    private void endChestPlace() {
        ChestRun r = chestRun;
        chestStep = null;
        chestFuture = null;
        activity.releaseOurPathing();
        restoreRules();
        job = null;
        target = null;
        step = Step.Walking;
        wait = CONFIG.delayTicks;
        if (r == null) return;
        if (r.phase == ChestPhase.PutBack) return;
        // Not placed: back to the planner, which tries again (or gives up with the tries spent)
        r.phase = ChestPhase.Place;
        if (r.tries >= CHEST_MAX_TRIES) chestFail(r, r.failure != null ? r.failure : "it couldn't be put up");
    }

    /** The stash bookkeeping of two inventory slots whose stacks swapped places: what is the stash's, and where it came from. */
    private void swapHeld(int a, int b) {
        String ha = stashHeld.remove(a), hb = stashHeld.remove(b);
        if (ha != null) stashHeld.put(b, ha);
        if (hb != null) stashHeld.put(a, hb);
        OrderSource oa = orderSources.remove(a), ob = orderSources.remove(b);
        if (oa != null) orderSources.put(b, oa);
        if (ob != null) orderSources.put(a, ob);
    }

    /** Undoes the run's hotbar swap. @return true when a request was sent (or one is going out) */
    private boolean anvilSwapBack(AnvilRun r) {
        if (r.swapFrom < 0) return false;
        if (busy()) return true;
        if (inventoryItem(r.swapFrom) == null && inventoryItem(r.swapTo) == null) {
            r.swapFrom = r.swapTo = -1;
            return false;
        }
        if (submit(new MoveToHotbarSlot(invSlot(r.swapFrom), MoveToHotbarAction.from(r.swapTo))) == RequestFuture.rejected) return true;
        swapHeld(r.swapFrom, r.swapTo);
        if (r.heldSlot == r.swapTo) r.heldSlot = r.swapFrom;
        r.swapFrom = r.swapTo = -1;
        wait = CONFIG.delayTicks;
        return true;
    }

    /** The chest run is forgotten (the module stops, the bot goes offline): its hotbar swap and hand are undone if nothing is open. */
    private void resetChestRun() {
        try {
            ChestRun r = chestRun;
            if (r != null && (r.swapFrom >= 0 || r.handBefore >= 0) && openContainerId() == 0 && CACHE.getPlayerCache().isAlive()) {
                List<InventoryAction> back = new ArrayList<>();
                if (r.swapFrom >= 0 && (inventoryItem(r.swapFrom) != null || inventoryItem(r.swapTo) != null)) back.add(new MoveToHotbarSlot(invSlot(r.swapFrom), MoveToHotbarAction.from(r.swapTo)));
                if (r.handBefore >= 0) back.add(new SetHeldItem(r.handBefore));
                if (!back.isEmpty()) INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(back).priority(SORTER_PRIORITY).build());
            }
        } catch (RuntimeException e) {
            // Best effort
        }
        chestRun = null;
        chestPlan = null;
        chestStep = null;
        chestFuture = null;
        lidBox = null;
        expandFailure = null;
        expandEval = null;
        expandEvalAt = 0;
        syncedTick = -1;
    }

    /** The run's hotbar swap as a request to undo it now (the module stops or starts over), or null. */
    private @Nullable InventoryAction anvilSwapBackAction() {
        AnvilRun r = anvilRun;
        if (r == null || r.swapFrom < 0) return null;
        if (inventoryItem(r.swapFrom) == null && inventoryItem(r.swapTo) == null) return null;
        swapHeld(r.swapFrom, r.swapTo);
        InventoryAction back = new MoveToHotbarSlot(invSlot(r.swapFrom), MoveToHotbarAction.from(r.swapTo));
        r.swapFrom = r.swapTo = -1;
        return back;
    }

    /** The run is forgotten (the module starts over or stops): its hotbar swap is undone if nothing is open. */
    private void undoAnvilSwapNow() {
        try {
            if (anvilRun == null || openContainerId() != 0 || !CACHE.getPlayerCache().isAlive()) return;
            InventoryAction back = anvilSwapBackAction();
            if (back != null) INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(back).priority(SORTER_PRIORITY).build());
        } catch (RuntimeException e) {
            // Best effort
        }
    }

    /** An anvil item anywhere in the inventory (not a kept slot), or -1. */
    private int anvilItemSlot() {
        Set<Integer> keep = keepSlots();
        for (int i = 0; i < 36; i++) {
            if (keep.contains(i)) continue;
            Item it = inventoryItem(i);
            if (it != null && AnvilFinder.ITEMS.contains("minecraft:" + it.id) && it.contents.isEmpty()) return i;
        }
        return -1;
    }

    /** What the anvil run holds now: the anvil carried to be put down. */
    private Set<Integer> anvilReserved() {
        AnvilRun r = anvilRun;
        if (r == null || r.phase == AnvilPhase.PutBack) return Set.of();
        Set<Integer> out = new HashSet<>();
        for (BinTracker.Held h : carriedHeld(r.bin)) out.add(h.slot());
        int held = carriedAnvilSlot(r);
        if (held >= 0) out.add(held);
        return out;
    }

    /** The bin being gathered outside an order's own shulker: a craft's inputs, or an anvil to put down. */
    private @Nullable Bin gatheringBin() {
        CraftRun c = craft;
        if (c != null && c.phase == CraftPhase.Gather) return c.bin;
        AnvilRun a = anvilRun;
        if (a != null && a.phase == AnvilPhase.Gather) return a.bin;
        ChestRun h = chestRun;
        if (h != null && h.phase == ChestPhase.Gather) return h.bin;
        PassRun ps = pass;
        if (ps != null && ps.phase == PassPhase.Items) return ps.bin;
        EnchantRun e = enchantRun;
        if (e != null && e.phase == EnchantPhase.Gather) return e.bin;
        return null;
    }

    /** Takes the next load of this bin out of a shulker: one already carried, else one fetched from the stash. */
    private boolean startBinUnpack(Bin bin, String lead) {
        BinTracker.Need need = needOf(bin);
        if (need == null) return false;
        String key = need.lot().key();
        for (var e : sourceShulkers.entrySet()) {
            Item held = inventoryItem(e.getKey());
            if (!e.getValue().opened && held != null && holds(held, key)) {
                startHandling(Job.Clean, e.getKey(), null);
                unpackingNow = e.getValue();
                step(lead + "taking items out of a shulker; next: put it back where it came from.");
                return true;
            }
        }
        Found f = findUnpackSource(bin);
        if (f == null) return false;
        unpackFetchOrigin = f.box().pos;
        wantSlot = f.slot();
        fetchWants = it -> it.isShulker() && holds(it, key);
        step(lead + "fetching a shulker that holds it; next: take it out.");
        return start(Job.Fetch, f.box());
    }

    private boolean startBin(Unit u) {
        if (compact != null) return false;
        if (u.replanned && !u.trimmed) {
            u.trimmed = true;
            // Planned again from the sources the run will really use now
            clearOrderBlocks();
            if (!orders.trim(u)) {
                orders.flow.skip("nothing of it is left in the stash");
                return false;
            }
        }
        if (!orders.flow.noteBuildStart(u)) {
            orders.flow.skip("couldn't start building a shulker");
            return false;
        }
        Bin bin = new Bin(u);
        Item reference = new Item();
        reference.id = "shulker_box";
        reference.name = "an order shulker";
        reference.count = 1;
        reference.maxStack = 1;
        CompactPlan plan = new CompactPlan(ORDER_KEY + orders.flow.orderId() + ":" + orders.flow.index(), reference, false, 0, u.lots.size());
        plan.bin = bin;
        compact = plan;
        activeBin = bin;
        compactBlocker = "";
        step("order: building shulker " + (orders.flow.index() + 1) + "/" + orders.flow.units().size() + " (" + u.lots.size() + " stacks); next: take them from the stash and pack them.");
        return continueCompaction();
    }

    private boolean startFetch(Unit u) {
        if (compact != null) return false;
        Found found = orders.locate(u);
        if (found == null) return false;
        wantSlot = found.slot();
        fetchWants = it -> orders.matches(u, it);
        step("order: fetching shulker " + (orders.flow.index() + 1) + "/" + orders.flow.units().size() + " from the stash.");
        return start(Job.Fetch, found.box());
    }

    private boolean startDeliver(OrderFlow.Deliver d) {
        if (compact != null && compact.bin == null) return false;
        Box box = syncDeliveryBox();
        if (box == null) return false;
        // The lowest chest of the delivery storage with room (1.9.0); a look to see the shulker arrived is in the chest it went to
        Box use = box;
        if (d.verify()) {
            Box last = orders.lastDeliveryBox;
            OrderFlow.Saved saved = orders.saved(String.valueOf(orders.flow.orderId()));
            if (last == null && saved != null && saved.deliveringAt != null && saved.deliveringAt.length == 3) {
                last = stash.boxAt(new BlockPos(saved.deliveringAt[0], saved.deliveringAt[1], saved.deliveringAt[2]));
            }
            if (last != null && last.delivery) use = last;
        }
        else {
            DeliveryBook.Entry room = deliveryBook().firstUsable();
            Box b = room == null ? null : stash.boxAt(bpos(room.first()));
            if (b != null && b.delivery) use = b;
        }
        orders.baseTaken = false;
        orders.recheck = false;
        boolean started = start(Job.Deliver, use);
        // The delivery container may be well outside the stash area
        jobStartedInside = false;
        step("order: " + (d.verify() ? "checking" : "delivering") + " shulker " + (orders.flow.index() + 1) + "/" + orders.flow.units().size() + ".");
        return started;
    }

    /** What an order took and did not deliver goes back, to the container it came from if that has room. Only when the sorter is not sorting (it files them itself then). */
    private boolean returnLeftovers() {
        if (sorts()) return false;
        orderSources.entrySet().removeIf(e -> {
            Item held = inventoryItem(e.getKey());
            return held == null || !held.id.equals(e.getValue().itemId());
        });
        // Whatever is carried from the stash, however the order ended (also rebuilt after a restart from what was saved as held)
        List<Integer> mine = new ArrayList<>(activeCarried());
        if (mine.isEmpty() || orderLeftoverTried) return false;

        OrderSource first = orderSources.get(mine.get(0));
        BlockPos src = first == null ? null : first.pos();
        Box from = src == null ? null : stash.boxAt(src);
        Box home = from != null && from.sortable() && !from.dropOff && !from.delivery && from.free() > 0 && !blocked(from, Job.Dump) && originAllowed(from, mine) ? from : null;
        if (home == null) {
            home = nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && !b.delivery && b.free() > 0 && !blocked(b, Job.Dump) && !signPending(b) && !kitReserved(b)).toList());
        }
        if (home == null) {
            orderLeftoverTried = true;
            alert("No container with room for the {} stacks an order left over, they stay in the bot's inventory.", mine.size());
            return false;
        }
        step("order: putting " + mine.size() + " leftover stacks back.");
        putBackTrip = true;
        return start(Job.Dump, home);
    }

    private boolean orderLeftovers() {
        return !sorts() && !orderLeftoverTried && !activeCarried().isEmpty();
    }

    /** The order machine's view of the sorter and the stash. */
    private final class OrderGlue implements OrderFlow.Host {
        final OrderFlow flow = new OrderFlow(this);
        boolean baseTaken;
        // The Deliver job is a look at a full chest (Recheck), not a delivery; the chest the last shulker went into
        boolean recheck;
        @Nullable Box lastDeliveryBox;

        @Override
        public DeliveryRoom deliveryRoom() {
            return deliveryRoomNow();
        }

        @Override
        public int deliveryChests() {
            return Math.max(1, deliveryBook().chests());
        }

        @Nullable Stash st() {
            return stash != null ? stash : Stashes.current();
        }

        boolean active() {
            return flow.holdsOrder();
        }

        /** Something for the order step to do: an order in hand, a bin being built, or leftovers to put back. */
        boolean engaged() {
            return flow.holdsOrder() || activeBin != null || orderLeftovers() || !kitEmptyQueue.isEmpty();
        }

        @Override
        public void kitEmptyBack(Unit unit) {
            if (!kitEmptyQueue.contains(unit)) kitEmptyQueue.add(unit);
        }

        @Override
        public boolean gate() {
            return pauseReason() == null;
        }

        @Override
        public String pauseReason() {
            if (!Alerts.online()) return "the bot is not in game";
            if (st() == null) return "no stash is loaded";
            try {
                com.aquarius.Proxy proxy = com.aquarius.Proxy.getInstance();
                if (proxy.isInQueue()) return "in the queue";
                if (proxy.hasActivePlayer()) return "a player is controlling the bot";
            } catch (RuntimeException | LinkageError e) {
                // Not known: not a reason
            }
            // Shulker handling being paused holds only what puts a shulker down (building a bin, see buildPauseReason):
            // finished shulkers are still named, delivered and fetched, and leftovers put back
            return null;
        }

        /**
         * Like the addon's shulkersOk(): a bin started while shulkers may not be put down would fail at once, so the Build
         * waits (as long as PAUSE_TIMEOUT_MS allows). New orders are held by orderBusy() and the blocker.
         */
        @Override
        public @Nullable String buildPauseReason() {
            return shulkerPause();
        }

        @Override
        public boolean rereading() {
            if (stash == null || !isEnabled()) return true;
            return staleCount() > 0;
        }

        @Override
        public int staleCount() {
            Stash s = stash;
            if (s == null) return 0;
            int n = 0;
            for (Box b : s.boxes) {
                if (b.excluded || b.delivery || b.unreachable || failedSurvey.contains(b)) continue;
                if (!b.known() || b.stale) n++;
            }
            return n;
        }

        @Override
        public boolean deliverySet() {
            return DeliverySpot.isSet();
        }

        // Why delivering failed, and for which delivery container: set by a failed Deliver job only
        private @Nullable String deliveryFailure;
        private DeliverySpot.@Nullable Spot failedFor;

        void failDelivery(String why) {
            deliveryFailure = why;
            failedFor = DeliverySpot.get();
        }

        void clearDeliveryFailure() {
            deliveryFailure = null;
            failedFor = null;
        }

        @Override
        public @Nullable String deliveryProblem() {
            DeliverySpot.Spot spot = DeliverySpot.get();
            if (spot == null) return null;
            if (!DeliverySpot.here(spot)) return "the delivery container is in another dimension (" + spot.dimension() + ")";
            return deliveryFailure != null && spot.equals(failedFor) ? deliveryFailure : null;
        }

        @Override
        public boolean machineRunning() {
            return stash != null && isEnabled();
        }

        /**
         * Whether this item is (a finished copy of) what the unit delivers. A shulker the order renamed at the anvil is still
         * the unit's: its name is the one the order asked for, and it is known by the key it had before (or the one seen once
         * the anvil gave it back), so a custom name never makes it look missing or someone else's.
         */
        boolean matches(Unit u, @Nullable Item it) {
            return com.porter.qwertestash.orders.ShulkerMatch.matches(u, it, CONFIG.useNamedShulkers);
        }

        // Anvil trouble (unreachable, won't open, no result, result never taken, closed twice in a row) for this order and
        // anvil: later shulkers of that order go unnamed at once instead of making the trip again
        final com.porter.qwertestash.orders.AnvilHealth anvilHealth = new com.porter.qwertestash.orders.AnvilHealth();

        @Override
        public @Nullable String namingProblem(Unit u) {
            // The anvil set with `stash anvil` first, then one remembered, then one standing in the stash area, then one taken
            // from the stash and put down (1.8.1); with none at all the shulker goes unnamed with NO_ANVILS
            String oid = flow.orderId();
            AnvilSpot.Spot spot = namingSpot();
            boolean handSet = anvilOverride == null && spot != null && AnvilSpot.SET.equals(AnvilSpot.source());
            if (spot != null && handSet && !AnvilSpot.here(spot)) return "the anvil is in another dimension";
            if (oid != null && oid.equals(anvilNoneFor)) return AnvilFinder.NO_ANVILS;
            AnvilFinder.Way way = anvilWay();
            spot = namingSpot();
            if (way == AnvilFinder.Way.USE && spot != null) {
                String trouble = anvilHealth.problem(oid, spot);
                if (trouble != null) {
                    if (anvilOverride == null && AnvilSpot.SET.equals(AnvilSpot.source())) return trouble;
                    // A found or placed anvil that gave trouble is given up on, and another looked for
                    anvilSkip.add(spot.pos());
                    if (anvilOverride != null) anvilOverride = null;
                    else AnvilSpot.clear();
                    anvilScanStale();
                    way = anvilWay();
                }
            }
            // Still looking (the search is spread over a few ticks): naming goes ahead, and waits for it (startName)
            if (way == AnvilFinder.Way.WAIT) return null;
            if (way == AnvilFinder.Way.NONE) {
                anvilNoneFor = oid;
                return AnvilFinder.NO_ANVILS;
            }
            int level = xpLevel();
            if (level >= 0 && level < 1) return AnvilNamer.TOO_FEW_LEVELS;
            return null;
        }

        @Override
        public @Nullable String nameOn(Unit u, int slot) {
            Item it = inventoryItem(slot);
            return it != null && it.customName ? it.customText : null;
        }

        @Override
        public int finishedSlot(Unit u) {
            // A shulker just back from the anvil is looked for in the whole inventory too, in case the server put it elsewhere
            boolean anywhere = u.have && (u.nameDone || u.nameTries > 0);
            return com.porter.qwertestash.orders.ShulkerMatch.findSlot(u, carriedSlots(), anywhere, StashSorter.this::inventoryItem, CONFIG.useNamedShulkers);
        }

        @Override
        public Map<String, Integer> contentOf(Unit u, int slot) {
            Map<String, Integer> out = new HashMap<>();
            Item it = inventoryItem(slot);
            if (u.kitTemplate != null) {
                // A kit counts as one of its template, however it is filled. A shulker of another colour than the template asks for is noted
                if (it != null && !u.kitReady && u.kitColor != null && !it.id.equals(colourShulkerId(u.kitColor)) && flow.kitResult() != null) {
                    flow.kitResult().note(com.porter.qwertestash.orders.KitResult.COLOUR_SUBSTITUTED);
                }
                out.put(u.kitTemplate, 1);
                return out;
            }
            if (u.kind != Unit.Kind.Bin || it == null) {
                out.put(u.key, 1);
                return out;
            }
            for (Item in : it.contents) if (in != null) out.merge(in.indexKey(), in.count, Integer::sum);
            return out;
        }

        @Override
        public boolean canFetch(Unit u) {
            return locate(u) != null;
        }

        @Nullable Found locate(Unit u) {
            Stash s = st();
            if (s == null) return null;
            Found best = null;
            double bestDistance = Double.MAX_VALUE;
            for (Box box : s.boxes) {
                if (!box.sortable() || box.stale || box.dropOff || box.delivery || blocked(box, Job.Fetch) || signPending(box)) continue;
                for (int i = 0; i < box.contents.length; i++) {
                    Item it = box.contents[i];
                    if (it == null || !matches(u, it)) continue;
                    double d = distSq(playerFeet(), nearestPart(box));
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = new Found(box, i);
                    }
                    break;
                }
            }
            return best;
        }

        String nameOf(String key) {
            int hash = key.indexOf('#');
            String id = hash < 0 ? key : key.substring(0, hash);
            if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
            return Item.prettyName(id);
        }

        /** Boxes an order may take from, or null and why not (for the notes). */
        private @Nullable String skipReason(Box box) {
            if (box.delivery) return "in the delivery container";
            if (box.excluded) return "in an excluded container";
            if (box.dropOff) return "in a drop-off";
            if (box.unreachable) return "in a chest I couldn't open";
            if (!box.known()) return "in a container not read yet";
            if (!CONFIG.useNamedShulkers && box.labels.stream().anyMatch(Categories::isKeepWhole)) return "in a kit chest";
            return null;
        }

        /**
         * What the stash can give the order now, by the rules the run takes from ({@link SourceRules}): the plan, the replan
         * and the run never disagree about a source.
         */
        StockPlanner.Survey survey(Stash s, Set<String> wanted) {
            return SourceRules.survey(s.boxes, wanted, reservedShulkerKeys(), CONFIG.useNamedShulkers, sourceLive);
        }

        @Override
        public void planning() {
            clearOrderBlocks();
            // A new order (or a resumed one) looks for an anvil afresh, anvils given up on included
            anvilNoneFor = null;
            anvilScanAt = Long.MIN_VALUE / 4;
            anvilScanStale();
            anvilSkip.clear();
            anvilOverride = null;
            // And put-back trips that came to nothing count from scratch
            putBackNoOps.clear();
            putBackGiveUps = 0;
            orderLeftoverTried = false;
        }

        /** The group keys of the packed shulkers this order asks for whole: not to be unpacked for anything else. */
        Set<String> reservedShulkerKeys() {
            Set<String> keys = new HashSet<>();
            for (Unit u : flow.units()) if (u.kind == Unit.Kind.Shulker) keys.add(u.key);
            // Routed kits (incomplete copies included) are never unpacked as sources (KIT-CONTRACT v2, kit orders)
            Stash s = st();
            if (s != null && CONFIG.kitSorting) {
                for (Box box : s.boxes) {
                    if (box.contents == null || !kitReserved(box)) continue;
                    for (Item it : box.contents) {
                        String k = it == null ? null : ManagerIndex.shulkerKey(it);
                        if (k != null) keys.add(k);
                    }
                }
            }
            return keys;
        }

        /** The empty unnamed shulkers an order may use, by item key. */
        Map<String, Integer> empties(Stash s) {
            Map<String, Integer> out = new HashMap<>();
            for (Box box : s.boxes) {
                if (box.contents == null || box.stale || skipReason(box) != null) continue;
                for (Item it : box.contents) if (emptyShulker(it)) out.merge(it.indexKey(), 1, Integer::sum);
            }
            return out;
        }

        @Override
        public @Nullable Recipes craftTable() {
            return craftOrdersBase() ? Recipes.current() : null;
        }

        @Override
        public int craftMaxFills() {
            return Math.max(1, CONFIG.craftOrderMaxFills);
        }

        @Override
        public int maxStack(String id) {
            return stackOf(id);
        }

        @Override
        public Map<String, Long> craftCarried() {
            return orderSurplusCounts();
        }

        @Override
        public void craftBasesReleased(List<Order.Lot> bases) {
            // Bases the enchant items did not use (ENCHANT-CONTRACT draft 3.4): their stacks stop being a bin's loads and are surplus of the craft, so they are put
            // back with the order's leftovers and counted in result.craft.returned (matched slot by slot, like CraftResume)
            // Only fresh plain stacks are claimed (a stale load whose base was used up finds no stack and is ignored)
            Map<String, Integer> released = new HashMap<>();
            for (Order.Lot lot : bases) released.merge(Recipes.ns(lot.key()), 1, Integer::sum);
            List<BaseSlots.Stack> crafted = new ArrayList<>();
            for (var e : new ArrayList<>(orderCraftLots.entrySet())) {
                Item it = inventoryItem(e.getKey());
                if (it != null && it.id.equals(e.getValue()) && !it.isShulker()) crafted.add(new BaseSlots.Stack(e.getKey(), "minecraft:" + it.id, freshPlain(it)));
            }
            for (int slot : BaseSlots.claim(released, crafted)) {
                Item it = inventoryItem(slot);
                if (it == null) continue;
                orderCraftLots.remove(slot);
                orderCraftSurplus.put(slot, it.id);
                orderReleasedBases.add(slot);
            }
            CraftLedger saving = orders.flow.craftLedger();
            if (saving != null) {
                saving.setSurplus(orderSurplusCounts());
                orders.flow.craftLedgerChanged();
            }
        }

        @Override
        public void craftBaseUsed() {
            // A base was used up by a finished item: the slot it vacated may now hold the enchanted result, so claims on stale slots are dropped (orderCraftHeld prunes
            // every crafted slot that is not a plain stack any more)
            orderCraftHeld();
        }

        // Enchant orders (1.19.0, ENCHANT-CONTRACT v1.0)

        @Override
        public @Nullable String enchantProblem(Order order) {
            return enchantProblemNow(order.enchant);
        }

        @Override
        public void enchantResumed(List<Unit> units) {
            // After a restart the finished enchant items that wait for their bins are the order's again (never the sorter's): their slots are registered as held
            for (Unit u : units) {
                if (u.built || u.have) continue;
                for (Order.Lot lot : u.lots) {
                    if (!lot.crafted() || lot.key().indexOf('#') < 0) continue;
                    for (int slot : carry) {
                        Item it = inventoryItem(slot);
                        if (it != null && it.indexKey().equals(lot.key()) && !stashHeld.containsKey(slot)) {
                            stashHeld.put(slot, it.id);
                            break;
                        }
                    }
                }
            }
        }

        @Override
        public Map<String, Long> enchantCarried() {
            return enchantCarriedCounts();
        }

        @Override
        public void enchantWatch(Map<String, Long> carried) {
            // The put-back is the leftover trip's; nothing to set up
        }

        @Override
        public boolean enchantReturning() {
            return !orderLeftoverTried && !enchantCarriedCounts().isEmpty();
        }

        @Override
        public Map<String, Long> enchantStillCarried() {
            return enchantCarriedCounts();
        }

        @Override
        public Map<String, Long> craftResumed(List<Unit> units, Map<String, Long> surplus) {
            // After a restart what the order carries is set aside again: the crafted loads waiting for their bin, and the surplus the ledger
            // saved, matched to carried stacks slot by slot (CraftResume). Surplus that is not carried any more is refunded to the budget
            List<CraftResume.Load> loads = new ArrayList<>();
            List<Order.Lot> lots = new ArrayList<>();
            for (Unit u : units) {
                if (u.built || u.have) continue;
                for (Order.Lot lot : u.lots) {
                    if (!lot.crafted()) continue;
                    loads.add(new CraftResume.Load(lot.key(), lot.count()));
                    lots.add(lot);
                }
            }
            List<CraftResume.Stack> carried = new ArrayList<>();
            for (int slot : carriedSlots()) {
                Item it = inventoryItem(slot);
                if (it != null && it.indexKey().equals("minecraft:" + it.id)) carried.add(new CraftResume.Stack(slot, "minecraft:" + it.id, it.count));
            }
            CraftResume.Result result = CraftResume.remark(loads, surplus, carried);
            for (int i = 0; i < result.lotSlots().size(); i++) {
                int slot = result.lotSlots().get(i);
                if (slot >= 0) orderCraftLots.put(slot, shortId(Recipes.ns(lots.get(i).key())));
            }
            for (var e : result.surplusSlots().entrySet()) orderCraftSurplus.put(e.getKey(), shortId(e.getValue()));
            return result.refund();
        }

        @Override
        public void craftWatch(Map<String, Long> ignored) {
            // The stacks the order set aside leave the order's hands now; they are watched until they are put back
            orderReturnWatch.clear();
            orderReturnWatch.putAll(orderCraftLots);
            orderReturnWatch.putAll(orderCraftSurplus);
        }

        @Override
        public boolean craftReturning() {
            return !craftStillCarried().isEmpty();
        }

        @Override
        public Map<String, Long> craftStillCarried() {
            Map<String, Long> out = new HashMap<>();
            for (var e : new ArrayList<>(orderReturnWatch.entrySet())) {
                Item it = inventoryItem(e.getKey());
                if (it == null || !it.id.equals(e.getValue())) orderReturnWatch.remove(e.getKey());
                else out.merge("minecraft:" + it.id, (long) it.count, Long::sum);
            }
            return out;
        }

        /**
         * The units for the order's kit lines from what the stash holds now (tick thread, like every plan): complete copies to fetch from pickable
         * containers, kits to build from an incomplete copy in the template chest or an empty shulker, their loads gathered by match key within take.
         */
        @Override
        public com.porter.qwertestash.orders.KitPlanner.@Nullable Plan planKit(Order order, int emptiesTaken) {
            Stash s = st();
            com.porter.qwertestash.orders.KitOrder.Snapshot snap = order.kit;
            if (s == null || snap == null || !CONFIG.kitSorting || !CONFIG.kitOrders) return null;
            KitSettings settings = KitStore.shared().get(ManagerIndex.clamp(s.server, 256), ManagerIndex.clamp(s.name, 256));
            java.util.function.Function<com.porter.qwertestash.stash.KitTemplate, @Nullable String> target = t -> settings == null ? null : KitRouting.templateTarget(t, settings);
            java.util.function.Predicate<Box> pickable = b -> KitRouting.pickable(b) && !b.stale && !blocked(b, Job.Fetch);
            com.porter.qwertestash.orders.KitPlanner.Scan scan = com.porter.qwertestash.orders.KitPlanner.scan(snap, s.boxes, pickable, target);
            // A restock job that builds a new copy never starts from an incomplete one
            if (order.restock && !order.restockTopUp) scan = scan.withoutBases();
            StockPlanner.Survey survey = survey(s, scan.wanted());
            int empties = -Math.max(0, emptiesTaken);
            for (int n : empties(s).values()) empties += n;
            return com.porter.qwertestash.orders.KitPlanner.plan(snap, scan, survey, StashSorter::stackOf, Math.max(0, empties), Set.of(), order.restock);
        }

        /** One scan of the stash and one survey, made once for every template a restock decision looks at (the plans only read them). */
        record RestockStock(com.porter.qwertestash.orders.KitPlanner.Scan scan, StockPlanner.Survey survey) {
        }

        @Nullable RestockStock restockStock(com.porter.qwertestash.orders.KitOrder.Snapshot union) {
            Stash s = st();
            if (s == null) return null;
            KitSettings settings = KitStore.shared().get(ManagerIndex.clamp(s.server, 256), ManagerIndex.clamp(s.name, 256));
            java.util.function.Function<com.porter.qwertestash.stash.KitTemplate, @Nullable String> target = t -> settings == null ? null : KitRouting.templateTarget(t, settings);
            java.util.function.Predicate<Box> pickable = b -> KitRouting.pickable(b) && !b.stale && !blocked(b, Job.Fetch);
            com.porter.qwertestash.orders.KitPlanner.Scan scan = com.porter.qwertestash.orders.KitPlanner.scan(union, s.boxes, pickable, target);
            return new RestockStock(scan, survey(s, scan.wanted()));
        }

        com.porter.qwertestash.orders.KitPlanner.Plan planWith(RestockStock rs, com.porter.qwertestash.orders.KitOrder.Snapshot snap, int emptiesAvailable, boolean noBases) {
            return com.porter.qwertestash.orders.KitPlanner.plan(snap, noBases ? rs.scan().withoutBases() : rs.scan(), rs.survey(), StashSorter::stackOf, Math.max(0, emptiesAvailable), Set.of(), true);
        }

        /** Kit plan for a snapshot against the stash now (tick thread). {@code noBases}: incomplete copies are not used as a base. */
        com.porter.qwertestash.orders.KitPlanner.@Nullable Plan planFor(com.porter.qwertestash.orders.KitOrder.Snapshot snap, int emptiesAvailable, boolean noBases) {
            Stash s = st();
            if (s == null) return null;
            KitSettings settings = KitStore.shared().get(ManagerIndex.clamp(s.server, 256), ManagerIndex.clamp(s.name, 256));
            java.util.function.Function<com.porter.qwertestash.stash.KitTemplate, @Nullable String> target = t -> settings == null ? null : KitRouting.templateTarget(t, settings);
            java.util.function.Predicate<Box> pickable = b -> KitRouting.pickable(b) && !b.stale && !blocked(b, Job.Fetch);
            com.porter.qwertestash.orders.KitPlanner.Scan scan = com.porter.qwertestash.orders.KitPlanner.scan(snap, s.boxes, pickable, target);
            if (noBases) scan = new com.porter.qwertestash.orders.KitPlanner.Scan(scan.wanted(), scan.matchOf(), scan.damage(), scan.ready(), Map.of());
            StockPlanner.Survey survey = survey(s, scan.wanted());
            return com.porter.qwertestash.orders.KitPlanner.plan(snap, scan, survey, StashSorter::stackOf, Math.max(0, emptiesAvailable), Set.of());
        }

        /** The loose experience bottles the stash holds. */
        long bottlesInStash() {
            Stash s = st();
            if (s == null) return 0;
            StockPlanner.Survey sv = survey(s, Set.of(com.porter.qwertestash.orders.KitRestock.BOTTLE));
            return sv.available().getOrDefault(com.porter.qwertestash.orders.KitRestock.BOTTLE, 0L);
        }

        int emptiesTotal() {
            Stash s = st();
            int n = 0;
            if (s != null) for (int c : empties(s).values()) n += c;
            return n;
        }

        @Override
        public StockPlanner.Plan plan(Order order) {
            return plan(order, List.of());
        }

        @Override
        public StockPlanner.Plan plan(Order order, List<Order.Lot> craftLots) {
            Stash s = st();
            if (s == null) return new StockPlanner.Plan(List.of(), List.of(), "no stash is loaded");
            Set<String> wanted = new HashSet<>();
            for (Order.Line l : order.lines) if (l.type().equals(Order.ITEM) && !StockPlanner.isShulkerKey(l.key())) wanted.add(l.key());
            StockPlanner.Survey survey = survey(s, wanted);
            Map<String, Integer> empties = empties(s);
            StockPlanner.Plan plan = StockPlanner.plan(order, survey, empties, this::nameOf, craftLots);
            if (!craftingOn()) return plan;
            // Crafting is on: empty plain shulker boxes the stash lacks for the lines are counted as craftable, after what the
            // bins will need (craftPool: the same pool the check and the run use)
            int asked = 0, given = 0, bins = 0;
            for (Order.Line l : order.lines) if (l.type().equals(Order.ITEM) && OrderFlow.PLAIN_SHULKER.equals(l.key())) asked += l.count();
            List<Unit> binUnits = new ArrayList<>();
            for (Unit u : plan.units()) {
                if (u.kind == Unit.Kind.Empty && OrderFlow.PLAIN_SHULKER.equals(u.key)) given++;
                if (u.kind == Unit.Kind.Bin) binUnits.add(u);
            }
            bins = binUnits.size();
            int shortLines = asked - given;
            if (shortLines <= 0) return plan;
            int have = carriedEmpties(false).size();
            for (int n : empties.values()) have += n;
            int emptyUnits = 0;
            for (Unit u : plan.units()) if (u.kind == Unit.Kind.Empty) emptyUnits++;
            int binShort = Math.max(0, bins - (have - emptyUnits));
            int craftable = craftable(binUnits, shortLines + binShort);
            int forLines = Math.min(shortLines, Math.max(0, craftable - binShort));
            if (forLines <= 0) return plan;
            Map<String, Integer> more = new HashMap<>(empties);
            more.merge(OrderFlow.PLAIN_SHULKER, forLines, Integer::sum);
            StockPlanner.Plan again = StockPlanner.plan(order, survey, more, this::nameOf, craftLots);
            List<String> notes = new ArrayList<>(again.notes());
            notes.add("Shulker Box: " + forLines + " to be crafted");
            return new StockPlanner.Plan(again.units(), notes, again.failure());
        }

        // Crafting (1.8.0)

        @Override
        public boolean craftingOn() {
            return StashSorter.craftingOn();
        }

        /**
         * The pool crafting may take from for these units of the order: what the stash can give crafting, less what every one
         * of them (the unit crafted for too, when it is a bin) is planned to take. The plan, the check of the empties and the
         * run all count craftable shulker boxes from this.
         */
        Map<String, Long> craftPool(List<Unit> units) {
            return CraftPool.residual(craftStock(), units);
        }

        /** How many plain shulker boxes could be crafted (up to {@code upTo}) without taking what these units need. */
        int craftable(List<Unit> units, int upTo) {
            if (!craftingOn() || upTo <= 0) return 0;
            Recipes r = recipesOrNull();
            if (r == null || tableProblem(String.valueOf(flow.orderId())) != null) return 0;
            return CraftPlanner.maxCraftable(r, OrderFlow.PLAIN_SHULKER, upTo, craftPool(units), craftLimits(true));
        }

        @Override
        public boolean emptyAvailable(Unit u) {
            for (int slot : carriedEmpties(false)) if (!sourceShulkers.containsKey(slot)) return true;
            return stash != null && findEmptyShulker() != null;
        }

        @Override
        public @Nullable String craftProblem(Unit u) {
            Recipes r = recipesOrNull();
            if (r == null) return "no recipe table yet (the Stash Manager server sends it; crafting stays off until it has)";
            String table = tableProblem(String.valueOf(flow.orderId()));
            if (table != null) return table;
            CraftPlanner.Plan plan = CraftPlanner.plan(r, OrderFlow.PLAIN_SHULKER, 1, craftPool(remainingUnits()), craftLimits(true));
            return plan.ok() ? null : plan.refusal();
        }

        @Override
        public int craftCount(Unit u) {
            int needs = 0;
            for (Unit x : remainingUnits()) {
                if (x.have || x.built) continue;
                if (x.kind == Unit.Kind.Bin || (x.kind == Unit.Kind.Empty && OrderFlow.PLAIN_SHULKER.equals(x.key))) needs++;
            }
            int have = 0;
            for (int slot : carriedEmpties(false)) if (!sourceShulkers.containsKey(slot)) have++;
            Stash s = st();
            if (s != null) for (int n : empties(s).values()) have += n;
            int want = Math.max(1, needs - have);
            // Each one takes a carry slot until it is used
            want = Math.min(want, Math.max(1, freeCarryCount() - 4));
            return Math.max(1, craftable(remainingUnits(), want));
        }

        @Override
        public @Nullable String checkEmpties(List<Unit> remaining) {
            int bins = 0;
            Map<String, Integer> need = new HashMap<>();
            for (Unit u : remaining) {
                if (u.kind == Unit.Kind.Bin) bins++;
                else if (u.kind == Unit.Kind.Empty) need.merge(u.key, 1, Integer::sum);
            }
            if (bins == 0 && need.isEmpty()) return null;
            Stash s = st();
            if (s == null) return "no stash is loaded";

            Map<String, Integer> have = empties(s);
            int total = 0;
            for (int n : have.values()) total += n;
            for (int slot = 0; slot < 36; slot++) {
                Item it = inventoryItem(slot);
                if (emptyShulker(it)) {
                    have.merge(it.indexKey(), 1, Integer::sum);
                    total++;
                }
            }
            // Crafting is on: what can be crafted counts, from the same pool the plan and the run use
            if (craftingOn()) {
                int craftable = craftable(remaining, bins + need.values().stream().mapToInt(Integer::intValue).sum());
                if (craftable > 0) {
                    have.merge(OrderFlow.PLAIN_SHULKER, craftable, Integer::sum);
                    total += craftable;
                }
            }
            int asked = 0;
            for (var e : need.entrySet()) {
                asked += e.getValue();
                if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return "need " + e.getValue() + " empty shulkers of one kind, have " + have.getOrDefault(e.getKey(), 0);
            }
            if (bins > total - asked) return "need " + (bins + asked) + " empty shulkers, have " + total;
            return null;
        }

        /** The loads of a unit planned once more from what the stash holds now: what is not there any more is dropped. @return false if nothing is left */
        boolean trim(Unit u) {
            Stash s = st();
            if (s == null) return false;
            Map<String, Long> totals = new java.util.LinkedHashMap<>();
            for (Order.Lot lot : u.lots) totals.merge(lot.key(), (long) lot.count(), Long::sum);
            // Loose stacks and what lies inside shulkers, as the first plan counted them
            List<Order.Lot> out = StockPlanner.trim(u.lots, survey(s, totals.keySet()));
            u.lots.clear();
            u.lots.addAll(out);
            return !out.isEmpty();
        }

        /** The unit of a saved bin key ("order:id:index"), from the plan saved with the stash. */
        @Nullable Unit unitFromKey(String key) {
            Stash s = st();
            OrderFlow.Saved plan = s == null ? null : s.activeOrder;
            String[] parts = key.split(":");
            if (plan == null || parts.length != 3 || !parts[1].equals(plan.id)) return null;
            try {
                int index = Integer.parseInt(parts[2]);
                List<Unit> mine = flow.units();
                if (index >= 0 && index < mine.size() && mine.get(index).kind == Unit.Kind.Bin) return mine.get(index);
                OrderFlow.Saved.UnitSave p = plan.units.get(index);
                List<Order.Lot> lots = new ArrayList<>();
                for (int i = 0; i < p.lotKeys.size(); i++) {
                    String v = i < p.lotVariants.size() ? p.lotVariants.get(i) : "";
                    lots.add(new Order.Lot(p.lotKeys.get(i), v == null || v.isEmpty() ? null : v, p.lotCounts.get(i)));
                }
                return new Unit(Unit.Kind.Bin, "", lots);
            } catch (RuntimeException e) {
                return null;
            }
        }

        // Persistence: the plan lives with the stash

        @Override
        public void save(OrderFlow.Saved saved) {
            Stash s = st();
            // A restock job is not kept over a restart: its items are put back, nothing resumes
            if (s == null || saved.id.startsWith(RESTOCK_ID)) return;
            s.activeOrder = saved;
            s.markDirty();
            s.save();
        }

        @Override
        public void clearSaved(String id) {
            Stash s = st();
            if (s == null || s.activeOrder == null || !s.activeOrder.id.equals(id)) return;
            s.activeOrder = null;
            s.markDirty();
            s.save();
        }

        @Override
        public OrderFlow.@Nullable Saved saved(String id) {
            Stash s = st();
            return s != null && s.activeOrder != null && s.activeOrder.id.equals(id) ? s.activeOrder : null;
        }

        @Override
        public @Nullable String savedId() {
            Stash s = st();
            return s == null || s.activeOrder == null ? null : s.activeOrder.id;
        }

        @Override
        public void say(String text) {
            info("Order: {}", text);
        }
    }
}
