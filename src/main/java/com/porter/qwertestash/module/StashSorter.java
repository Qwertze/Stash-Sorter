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
import com.aquarius.mc.item.ItemData;
import com.aquarius.mc.item.ItemRegistry;
import com.aquarius.module.impl.AbstractFieldModule;
import com.aquarius.util.RequestFuture;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.StashConfig.Mode;
import com.porter.qwertestash.stash.Categories;
import com.porter.qwertestash.stash.Containers;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.StashIndex;
import com.porter.qwertestash.stash.Stashes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
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

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.INPUTS;
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

    private enum Job { Survey, Take, Deposit, Dump, Fetch, Clean, Fill, Compact, FetchSigns, Label, Gather, Ground, Explore, Unpack }

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
    private int shulkersBefore;
    private final Set<Integer> shulkerSlotsBefore = new HashSet<>();
    private boolean repackFailed;
    private final Set<String> packTried = new HashSet<>();
    // What the shulker being fetched has to be, checked against the live window before it is taken
    private Predicate<Item> fetchWants = it -> it.isShulker() && !it.customName;
    // How many more stacks the gathering trip in hand may take
    private int gatherLeft;
    private @Nullable ItemData placedItem;
    // Carry slots whose items we put there ourselves from the stash. Anything the bot owns that looks like gear, food,
    // totems or pearls and wasn't put there by us is the bot's own, never carried, packed or put away
    private final Map<Integer, String> stashHeld = new HashMap<>();

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

    @Override
    public boolean enabledSetting() {
        return CONFIG.sorting;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            // After the other modules (default 0, Baritone included) have had their tick, so what they started this tick
            // is visible to us, and before the inventory queue (-5000) sends our clicks
            of(ClientBotTick.class, -1000, this::onTick),
            of(ClientBotTick.Starting.class, e -> restart()),
            of(ClientDisconnectEvent.class, e -> restart())
        );
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
        // A shulker of ours standing in the world is said loudly before anything is forgotten
        if (compact != null || handle != null) reportPlacedShulker("Lost track of");
        // This also runs every tick while the bot isn't in game: only the first time has anything to let go of
        if (stash != null || job != null || gate != Gate.Running || waitStart >= 0) resetGiveWay();
        job = null;
        target = null;
        moved = 0;
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
        lastSummary = null;
        lastCompactWhy = "";
        failedSurvey.clear();
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
        compactCooldown.clear();
        gatherLeft = 0;
        failedSpots.clear();
        dropLost = false;
        resetJobState();
        cursorReported = false;
        unpackBlocked.clear();
        unpackFuture = null;
        routePending = false;
        groundId = -1;
        groundFinishing = false;
        groundSkipped.clear();
        nextGroundCheck = 0;
        surveyed = 0;

        if (!CACHE.getPlayerCache().isAlive() || CACHE.getPlayerCache().getPlayerInventory().size() < 45) {
            // Not in game yet: ClientBotTick.Starting calls this again once we are
            return;
        }

        if (!pickCarrySlots()) return;

        boolean existed = Stashes.current() != null;
        stash = Stashes.currentOrNew();
        exploreSeen = Stashes.exploreVersion;
        resetExplore();

        int added = addNearbyBoxes();
        for (Box box : stash.boxes) {
            box.unreachable = false;
            Containers.readSigns(box);
        }
        orderBoxes();
        // Players may have changed things while the bot was away: every container is read again before anything is
        // sorted or packed. What was saved stays in use for searching until each one is read
        for (Box box : stash.boxes) if (!box.excluded) box.stale = true;
        tiesReported.clear();
        strayReported.clear();
        unrecognisedReported.clear();
        if (CONFIG.mode == Mode.Signs) unrecognisedSigns(true);

        // With a perimeter the containers are found by exploring it first, and checked then
        if (stash.hasArea()) {
            info("Stash \"{}\": exploring the stash area {} first, then reading every container again{}.", stash.name,
                Stash.describe(stash.bounds()), CONFIG.sortItems ? " before sorting" : " (scan only)");
            if (added > 0 || !existed) stash.save();
            return;
        }
        if (!checkEnough()) return;

        int unknown = stash.boxes.size() - stash.knownCount();
        if (!existed) info("New stash \"{}\": scanning {} containers{}.", stash.name, stash.boxes.size(), CONFIG.sortItems ? ", then sorting" : " (scan only)");
        else if (unknown > 0) info("Stash \"{}\": {} containers, scanning {} new ones first and reading the rest again{}.", stash.name, stash.boxes.size(), unknown, CONFIG.sortItems ? "" : " (scan only)");
        else if (!stash.boxes.isEmpty()) info("Stash \"{}\": {} containers, reading all of them again{}, as the bot was away.", stash.name, stash.boxes.size(), CONFIG.sortItems ? " before sorting" : " (scan only)");
        else info("Stash \"{}\": {} containers, {}.", stash.name, stash.boxes.size(), CONFIG.sortItems ? "sorting by " + (CONFIG.mode == Mode.Tiers ? "tier" : "sign") : "scan only");

        if (added > 0 || !existed) stash.save();
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
        if (job == Job.Label && labelFace != null && labelCritical()) warn("Stopped while putting up a sign, there may be a blank one at {}.", labelFace.sign());
        MODULE.get(StashTracker.class).stopAwaitingEditor();
        // The hand and the hotbar as they were, if the bot can still do it: best effort, nothing ticks the module now
        List<InventoryAction> back = new ArrayList<>();
        if (heldFrom != -1) back.add(new MoveToHotbarSlot(invSlot(heldFrom), MoveToHotbarAction.from(heldTo)));
        if (heldBefore != -1) back.add(new SetHeldItem(heldBefore));
        if (!back.isEmpty() && openContainerId() == 0) {
            INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(back).priority(SORTER_PRIORITY).build());
        }
        heldFrom = heldTo = heldBefore = -1;
        labelStep = null;
        reservedSigns.clear();
        if (handle != null && placed != null && placed(placed)) warn("Stopped while repacking, there's a shulker left at {}.", placed);
        CompactPlan inFlight = compact;
        reportPlacedShulker("Stopped with");
        if (inFlight != null && inFlight.placedPos == null && findPlanShulker() >= 0) warn("Stopped while compacting, a part-filled shulker of {} is in the bot's inventory.", inFlight.reference.name);
        compact = null;
        handle = null;
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
            if (box == null) continue;
            box.contents = null;
            stash.markDirty();
        }

        if (stash != null && stash.isDirty()) stash.save();
        if (!carriedSlots().isEmpty()) warn("Stopped while carrying {} stacks from the stash, they're in the bot's inventory.", carriedSlots().size());

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
        problem = reason;
        error(reason);
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
        if (carry.isEmpty() && CONFIG.sortItems) {
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
            tick();
        } catch (RuntimeException e) {
            // Never leave Baritone's settings overridden for everyone else
            restoreRules();
            throw e;
        }
    }

    private void tick() {
        if (notReady()) return;
        tickCount++;
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
            if (sortSeen != CONFIG.sortItems && !sortChanged()) return;
            if (exploreSeen != Stashes.exploreVersion || !Objects.equals(exploreDimension, Stash.currentDimension())) exploreChanged();
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

        if (job == Job.Label) tickLabel();
        else if (job == Job.Ground) tickGround();
        else if (job == Job.Explore) tickExplore();
        else if (job == Job.Unpack) tickUnpack();
        else if (handle != null) tickHandling();
        else {
            stepTicks++;

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
        sortSeen = CONFIG.sortItems;
        idle = false;
        clearStalled();
        futile.clear();
        stuckCarried.clear();
        stuckWarned = false;
        if (!CONFIG.sortItems) {
            info("Scan only: containers are read and kept up to date, nothing is moved.");
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
        exploreSeen = Stashes.exploreVersion;
        for (Box box : stash.boxes) box.unreachable = false;
        failedSurvey.clear();
        boxFailures.clear();
        clearStalled();
        futile.clear();
        idle = false;
        boolean hadArea = areaWasSet;

        if (stash.hasArea()) {
            int dropped = stash.dropOutsideArea();
            if (dropped > 0) debug("Dropped {} containers outside the stash area.", dropped);
            resetExplore();
            orderBoxes();
        }
        else {
            resetExplore();
            // The perimeter is gone: containers around the bot again
            if (hadArea) {
                addNearbyBoxes();
                for (Box box : stash.boxes) if (!box.known()) Containers.readSigns(box);
                orderBoxes();
            }
        }
        stash.markDirty();
        stash.save();
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
        if (b == null || exploreSeen != Stashes.exploreVersion || !Objects.equals(exploreDimension, Stash.currentDimension())) {
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
        if (stash == null || !stash.hasArea() || !exploreDone) return;
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
        long sortable = stash.boxes.stream().filter(b -> !b.excluded && (!b.dropOff || !CONFIG.sortItems)).count();
        int needed = CONFIG.sortItems ? 2 : 1;
        if (sortable < needed) {
            fail(String.format("Found %d container(s) to %s %s, need at least %d.", sortable, CONFIG.sortItems ? "sort" : "scan",
                stash.hasArea() ? "in the stash area" : "within " + CONFIG.radius + " blocks of the bot", needed));
            return false;
        }

        // Scan only reads containers: signs are not needed for that
        if (CONFIG.sortItems && CONFIG.mode == Mode.Signs && stash.boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            fail("None of the containers have signs on them. Put signs on them, or use tiers mode.");
            return false;
        }
        return true;
    }

    /**
     * Said once for a container, which is then left alone until the next full read or a new perimeter.
     */
    private void reportUnreachable(Box box) {
        warn("Can't reach the {} at {} without breaking or placing blocks{}, skipping it.", blockName(box), fmt(box.pos),
            CONFIG.safeWalk ? ", jumping or falling" : "");
    }

    private void reportCantOpen(Box box) {
        warn("Couldn't open the {} at {}, skipping it.", blockName(box), fmt(box.pos));
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
        if (!CONFIG.sortItems) return "off (scan only)";
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
        if (!CONFIG.collectGround || !CONFIG.sortItems) return false;
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
                if (groundSkipped.containsKey(entity.getEntityId()) || !inArea(area, entity.getX(), entity.getY(), entity.getZ(), 0)) continue;
                ItemStack stack = GroundItems.stackOf(entity);
                if (stack == null || stack.getAmount() <= 0 || hazardAt(cell(entity))) continue;
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
        return true;
    }

    private static BlockPos cell(Entity entity) {
        return new BlockPos((int) Math.floor(entity.getX()), (int) Math.floor(entity.getY()), (int) Math.floor(entity.getZ()));
    }

    private void tickGround() {
        groundTicks++;
        GroundItems ground = groundItems();
        if (!CONFIG.collectGround || !CONFIG.sortItems) {
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

        box.contents = null;
        current.markDirty();
        current.save();
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

            box.contents = null;
            stash.markDirty();
            clearStalled();
            compacted.remove(box);
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
                    warn("No signs to label new categories (\"{}\" and others), so those items go to the catch-all container. Put signs in the bot's inventory or in any container of the stash.", plan.text());
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
        for (Item item : known.values()) current.put(item.id, signedScore(item));
        Map<String, String> chosen = new HashMap<>();

        List<Item> sources = new ArrayList<>();
        for (int slot : activeCarried()) {
            Item item = inventoryItem(slot);
            if (item != null) sources.add(item);
        }
        for (Box box : stash.boxes) {
            if (!box.known() || box.unreachable || box.excluded) continue;
            if (!(box.dropOff || box.labels.isEmpty() || isMisc(box))) continue;
            for (Item item : box.contents) if (item != null) sources.add(item);
        }

        Map<String, Group> groups = new HashMap<>();
        for (Item item : sources) {
            if (Containers.isSignItem(item.id)) continue;
            Item subject = labelSubject(item);
            if (mixedShulker(item) && !keepWholeBoxes().isEmpty()) continue;

            List<Box> dests = destinationsFor(item);
            boolean homeless = dests.isEmpty() || dests.stream().allMatch(this::isMisc);
            String text;
            boolean same = false;
            if (homeless) {
                if (!chosen.containsKey(subject.id)) chosen.put(subject.id, chooseLabel(subject, known, current));
                text = chosen.get(subject.id);
            }
            else {
                // A container for it exists, but every one is full: another chest with the same sign
                if (dests.stream().anyMatch(b -> b.free() > 0 || misplacedCount(b) > 0)) continue;
                text = signTextOf(dests);
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
            if (!box.sortable() || box.dropOff || box.labelFailed || !box.labels.isEmpty() || box.autoLabel != null) continue;
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
     * Every kind of stack in the stash and being carried, one of each.
     */
    private Map<String, Item> knownItems() {
        Map<String, Item> known = new HashMap<>();
        for (Box box : stash.boxes) {
            if (!box.known()) continue;
            for (Item item : box.contents) {
                if (item == null) continue;
                known.putIfAbsent(item.id, item);
                Item inside = item.isShulker() ? Categories.dominantContent(item) : null;
                if (inside != null) known.putIfAbsent(inside.id, inside);
            }
        }
        for (int slot : carriedSlots()) {
            Item item = inventoryItem(slot);
            if (item != null) known.putIfAbsent(item.id, item);
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
            for (String label : box.labels) best = Math.max(best, Categories.signScore(label, described));
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

        for (String text : Categories.labelCandidates(item)) {
            if (text.length() > SIGN_LINE_LENGTH) continue;
            boolean byName = text.equals(name);
            List<String> labels = Containers.labelsOf(List.of(text, signDate()));

            int got = 0;
            for (String label : labels) got = Math.max(got, Categories.signScore(label, described));
            if (got < (byName ? 100 : 40)) continue;

            boolean steals = false;
            for (Item other : known.values()) {
                int home = current.getOrDefault(other.id, 0);
                // Only a real home is protected: a weak match or the catch-all would just as well be somewhere else
                if (home < 40 || other.id.equals(item.id)) continue;
                Categories.Described d = Categories.describe(other);
                int now = 0;
                for (String label : labels) now = Math.max(now, Categories.signScore(label, d));
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
     */
    private @Nullable String signTextOf(List<Box> boxes) {
        for (Box box : boxes) {
            for (String label : box.labels) {
                if (label.length() <= SIGN_LINE_LENGTH && !label.matches(".*\\d+[-/.]\\d+[-/.]\\d+.*") && Categories.recognises(label)) return label;
            }
        }
        return null;
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
        labelLines = new String[] {plan.text(), signDate(), "", ""};
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
        if (read != null && read.labels().contains(labelText)) {
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
        return CONFIG.watchSigns && (CONFIG.mode == Mode.Signs || !CONFIG.sortItems);
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
        if (box.autoLabel != null && !now.contains(box.autoLabel)) {
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
            if (CONFIG.sortItems) info("Sign at {} changed: \"{}\" -> \"{}\", re-sorting.", box.pos, was, is);
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
        if (reason == Activity.Reason.Pathing && waitStart < 0) info("Giving way to {}.", activity.foreignWho != null ? activity.foreignWho : "another module's pathing");
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

    private void tickYielding() {
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
        }
        if (tickCount >= reminderAt) {
            reminderAt = tickCount + REMINDER_TICKS;
            info("Still waiting for another module ({}), {} minutes so far.", waitLabel(lastReason), (tickCount - waitStart) / (20 * 60));
        }
    }

    private void endWaiting() {
        if (waitStart >= 0 && tickCount - waitStart > quietTicks()) info("Resuming.");
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
            case Place -> inWorld && countInInv(this::isShulkerBox) < shulkersBefore ? Handle.Open : Handle.Hold;
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


    /**
     * Stay-on mode, waiting for something to sort: a container that changed, or a drop-off due for a check.
     */
    private boolean wakeUp() {
        idleTicks++;

        // Scan only moves nothing into the drop-offs' reach: their lids and signs are watched, which covers changes
        if (CONFIG.sortItems && idleTicks >= CONFIG.recheckSeconds * 20) {
            idleTicks = 0;
            boolean any = false;
            for (Box box : stash.boxes) {
                if (box.dropOff && !box.unreachable) {
                    box.contents = null;
                    any = true;
                }
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
            failedSurvey.clear();
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
        try {
            return pickJob();
        } finally {
            destCache = null;
            misplacedCache = null;
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

    private boolean pickJob() {
        // The perimeter was set in another dimension: nothing here is the stash's
        if (stash.areaSet() && !stash.hasArea()) {
            if (!elsewhereSaid) {
                elsewhereSaid = true;
                info("The stash area is in another dimension, waiting until the bot is back there.");
            }
            return false;
        }
        elsewhereSaid = false;
        // A perimeter is explored before anything is read or sorted
        if (stash.hasArea() && !exploreDone && startExplore()) return true;

        Box unread = nearest(stash.boxes.stream().filter(b -> (!b.known() || b.stale) && !b.unreachable && !failedSurvey.contains(b) && !signPending(b)).toList());
        if (unread != null) return start(Job.Survey, unread);

        // Scan only: everything is read and kept up to date, nothing is moved
        if (!CONFIG.sortItems) {
            finishSorting();
            return false;
        }

        plan();
        destCache = new IdentityHashMap<>();
        misplacedCache = new HashMap<>();

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
        if (passJobs > 6 * stash.boxes.size() + 60) {
            warn("Sorting went in circles ({} jobs without getting closer to done), stopping this pass. {} stacks out of place, carrying {}.",
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
            if (place != null) return start(Job.Deposit, place);

            // Nowhere it belongs has room: only a container signed misc, other or overflow takes it. Never one with a
            // sign for something else, and never an unsigned one: a signed container only holds what its sign says
            Box spare = spareBox();
            if (spare != null) return start(Job.Dump, spare);

            // Nothing takes it for now. It stays in the carry slots, out of the plans, while the rest carries on, and is
            // looked at again when something in the stash changes. The carry slots never include a kept slot, or the
            // pearl slot
            for (int slot : carried) stuckCarried.put(slot, inventoryItem(slot).id);
            if (!stuckWarned) {
                stuckWarned = true;
                warn("Found nowhere with room for {} stacks I'm carrying (no container they belong in has space, and none signed misc/other/overflow does" + (CONFIG.unsignedOverflow && CONFIG.mode == Mode.Signs ? ", and no unsigned container whose signs could be read does" : "") + "), keeping them in the bot's inventory and carrying on with the rest.", carried.size());
            }
        }

        // Mixed shulkers sitting in the stash: fetch one to clean out
        if (repackOn() && freeCarryCount() >= 2) {
            Box best = null;
            int bestSlot = -1;
            for (Box box : stash.boxes) {
                if (!box.sortable() || blocked(box, Job.Fetch) || signPending(box)) continue;
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
        Box source = freeCarrySlot() == -1 ? null : nearest(stash.boxes.stream().filter(b -> b.sortable() && !blocked(b, Job.Take) && !signPending(b) && takeableSlot(b) != -1).toList());
        if (source != null) return start(Job.Take, source);

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

        // Everything is where it belongs: full shulkers' worth of anything are packed, which frees chest slots
        if (startCompaction() && continueCompaction()) return true;

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
        int jobs, acquireTries, slotHint = -1;
        boolean aborting;

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

    private @Nullable CompactPlan compact;
    private final Map<String, Long> compactCooldown = new HashMap<>();
    private volatile int compactedShulkers, slotsFreed;
    private int runPacked, runFreed;
    private volatile String compactBlocker = "";
    private long lastPlaceTick = Long.MIN_VALUE / 4;
    // A shulker could not be taken back: no more are put down
    private boolean dropLost;

    /**
     * A shulker of ours standing in the world (compacting between rounds, or repacking) when sorting stops or starts
     * over: said loudly with what is in it and where, and kept in the stash file and the status until it is picked up.
     */
    private void reportPlacedShulker(String lead) {
        CompactPlan plan = compact;
        BlockPos at = null;
        String what = "stacks";
        int stacks = 0;
        if (plan != null && plan.placedPos != null) {
            at = plan.placedPos;
            what = plan.reference.name;
            stacks = plan.placedSize;
        }
        else if (handle != null && placed != null && placed(placed)) {
            at = placed;
            what = fillId != null ? fillId : "items";
            stacks = -1;
        }
        if (at == null) return;

        error("{} a shulker holding {} of {} at {}. Pick it up by hand.", lead, stacks >= 0 ? stacks + " stacks" : "some stacks", what, at);
        inGameAlertActivePlayer("<red>A shulker with " + what + " is standing at " + at + ".");
        recordOrphan(at, what, Math.max(stacks, 0));
    }

    private void recordOrphan(BlockPos at, String item, int stacks) {
        Stash current = stash != null ? stash : Stashes.current();
        if (current == null) return;
        current.orphans.removeIf(o -> o.pos.equals(at));
        current.orphans.add(new Stash.Orphan(at, item, stacks, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(java.time.LocalDateTime.now())));
        current.markDirty();
        current.save();
    }

    private void clearOrphan(@Nullable BlockPos at) {
        Stash current = stash != null ? stash : Stashes.current();
        if (current == null || at == null) return;
        if (current.orphans.removeIf(o -> o.pos.equals(at))) {
            current.markDirty();
            current.save();
        }
    }

    private boolean compactOn() {
        return CONFIG.compact && CONFIG.mode == Mode.Signs && !repackFailed && !dropLost && tickCount >= repackPausedUntil;
    }

    /**
     * For the command: what compacting has done and what is holding it back.
     */
    public String compactStatus() {
        String blocker = compactBlocker;
        CompactPlan plan = compact;
        return compactedShulkers + " shulkers packed, " + slotsFreed + " slots freed"
            + (plan != null ? ", packing " + plan.reference.name : blocker.isEmpty() ? "" : ", last blocker: " + blocker)
            + (dropLost ? ", stopped: a shulker could not be picked up" : "");
    }

    private boolean compactExcluded(Item item) {
        return CONFIG.compactExclude.contains(item.id);
    }

    /**
     * A container its stacks and shulkers may be taken from: not a drop-off, a kit chest, one marked compactSkip, or
     * one whose sign is changing.
     */
    private boolean compactSource(Box b) {
        return b.sortable() && !b.dropOff && !b.noCompact && !signPending(b) && b.labels.stream().noneMatch(Categories::isKeepWhole);
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
        if (s == null || !s.isShulker() || s.customName) return false;
        if (s.contents.isEmpty()) return !plan.topUp;
        for (Item inside : s.contents) if (!plan.key.equals(inside.bulkKey())) return false;
        return true;
    }

    private boolean matchesPlan(@Nullable Item item) {
        CompactPlan plan = compact;
        return plan != null && item != null && item.isFullStack() && plan.key.equals(item.bulkKey());
    }

    /**
     * The carry slot holding the plan's shulker, by what is in it and not where it is: slots change when a shulker is
     * moved to the hotbar or picked up again. -1 if there is none.
     */
    private int findPlanShulker() {
        CompactPlan plan = compact;
        if (plan == null) return -1;
        if (plan.slotHint >= 0 && carry.contains(plan.slotHint) && isPlanShulker(inventoryItem(plan.slotHint), plan)) return plan.slotHint;

        int found = -1;
        for (int slot : carriedSlots()) {
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
        if (compact == null) return Set.of();
        Set<Integer> reserved = new HashSet<>();
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

    private @Nullable Box compactSourceBox(CompactPlan plan) {
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
            compactWhy(dropLost ? "stopped, a shulker could not be picked up" : "paused for a few minutes after a shulker problem");
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
            else continue;
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
    private @Nullable Found findEmptyShulker() {
        String offhand = offhandId();
        Found best = null;
        boolean bestSigned = false;
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.noCompact || signPending(box) || blocked(box, Job.Fetch)) continue;
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
        if (!compactOn()) return "compacting is off or paused";
        if (plan.jobs > 12 + 4 * plan.target) return "it took too many trips";
        if (!hasHome(plan.reference)) return "its category is gone";
        return null;
    }

    /**
     * Gives the plan up. A shulker standing in the world is still fetched back first.
     */
    private void abortCompaction(String why) {
        CompactPlan plan = compact;
        if (plan == null) return;
        compactCooldown.put(plan.key, tickCount + COMPACT_COOLDOWN);
        compactBlocker = why;
        debug("Compacting {} given up on: {}.", plan.reference.name, why);
        plan.aborting = true;
    }

    /**
     * The next job of the plan in hand, or false when it is over (done or given up on) and normal sorting carries on.
     */
    private boolean continueCompaction() {
        CompactPlan plan = compact;
        if (plan == null) return false;

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

        int remaining = plan.target - have;
        if (carriedMatch >= remaining || (carriedMatch > 0 && free <= 1)) return startPackJob(plan);

        Box source = compactSourceBox(plan);
        if (source == null) {
            if (carriedMatch > 0) return startPackJob(plan);
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
        else {
            Found found = findEmptyShulker();
            if (found == null) {
                abortCompaction("no empty shulker");
                return continueCompaction();
            }
            box = found.box();
            slot = found.slot();
            String offhand = offhandId();
            want = it -> emptyShulker(it) && !it.id.equals(offhand);
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
        plan.jobs++;
        if (plan.placedPos != null) {
            startHandling(Job.Fill, -1, plan.key);
            placed = plan.placedPos;
            handle = Handle.Return;
            returnTo = Handle.Open;
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
        startHandling(Job.Fill, -1, plan.key);
        placed = at;
        handle = Handle.Collect;
        shulkersBefore = countInInv(this::isShulkerBox) + 1;
        shulkerSlotsBefore.clear();
        for (int i = 0; i < 36; i++) {
            Item item = inventoryItem(i);
            if (item != null && item.isShulker()) shulkerSlotsBefore.add(i);
        }
        return true;
    }

    private void completeCompaction(CompactPlan plan, int size) {
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
        CompactPlan plan = compact;
        if (plan == null || gatherLeft <= 0 || freeCarryCount() <= 1) return false;

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

        boolean more = !plan.aborting && size < plan.target && compactSourceBox(plan) != null && freeCarryCount() >= 2;
        handle = more ? Handle.Leave : Handle.Break;
        handleTicks = 0;
        wait = CONFIG.delayTicks;
        if (more) plan.placedPos = placed;
    }

    private void handleLeave() {
        if (openContainerId() != 0) {
            closeSafely();
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
        return CONFIG.repack && CONFIG.mode == Mode.Signs && !repackFailed && tickCount >= repackPausedUntil;
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
                mainId = Categories.dominantContent(shulker).id;
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
                    if (!box.sortable() || box.dropOff || blocked(box, Job.Fetch) || signPending(box)) continue;
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

        // A chest signed for kits takes mixed shulkers whole instead
        if (!keepWholeBoxes().isEmpty()) return List.of();

        List<Item> out = new ArrayList<>();
        for (Item inside : shulker.contents) {
            if (inside.isFilledBundle() || (!inside.id.equals(main.id) && !bestSigned(inside).isEmpty())) out.add(inside);
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
    private static final int MAX_BREAK_RETRIES = 3;

    private void handleReturn() {
        // A chunk that isn't loaded says nothing about the shulker: walk to it first
        boolean loaded = placed != null && Containers.isLoaded(placed);
        if (placed == null || (loaded && (!placed(placed) || arrivedAt(new GoalNear(placed, REACH_RANGE_SQ))))) {
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
        // On the way back to where one went down before
        if (standRetries > 0 && activity.ownPathing()) return;
        if (!activity.mayRequest()) return;
        placed = safeSpot(null);
        if (placed == null) {
            // Walk to where one went down before and look again; failing that, pause a while instead of giving up
            if (activity.ownPathing()) {
                placed = null;
                return;
            }
            BlockPos stand = lastGoodStand;
            if (stand != null && !playerFeet().equals(stand) && standRetries < 2 && activity.mayRequest()) {
                standRetries++;
                debug("No room beside the bot at {}, walking back to {} where a shulker went down before.", playerFeet(), stand);
                requestWalk(stand);
                return;
            }
            standRetries = 0;
            warn("No room to put a shulker down beside the bot, trying again in a few minutes.");
            repackPausedUntil = tickCount + 20 * 60 * 3;
            endHandling();
            return;
        }
        standRetries = 0;

        placeFailures = 0;
        if (compact != null) compact.placedSize = shulker.contents.size();
        shulkersBefore = countInInv(this::isShulkerBox);
        lastPlaceTick = tickCount;
        placedItem = ItemRegistry.REGISTRY.get(shulker.id);
        requestPlace(placed, placedItem);
        handle = Handle.Place;
        handleTicks = 0;
    }

    private void handlePlace() {
        if (placed(placed) && countInInv(this::isShulkerBox) < shulkersBefore) {
            activity.releaseOurPathing();
            restoreRules();
            if (compact != null) compact.placedPos = placed;
            lastGoodStand = playerFeet();
            failedSpots.clear();
            handle = Handle.Open;
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
            if (++placeFailures >= 3) {
                warn("Couldn't put the shulker down ({} spots failed), not repacking shulkers for a few minutes.", placeFailures);
                repackPausedUntil = tickCount + 20 * 60 * 3;
                endHandling();
                return;
            }
            BlockPos next = safeSpot(failed);
            if (next == null || next.equals(failed)) {
                warn("No other spot to put the shulker down, not repacking shulkers for a few minutes.");
                repackPausedUntil = tickCount + 20 * 60 * 3;
                endHandling();
                return;
            }
            placed = next;
            lastPlaceTick = tickCount;
            requestPlace(placed, placedItem);
            handleTicks = 0;
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT) {
            activity.releaseOurPathing();
            restoreRules();
            warn("Couldn't put the shulker down, not repacking shulkers for a few minutes.");
            repackPausedUntil = tickCount + 20 * 60 * 3;
            endHandling();
        }
    }

    private int placeFailures;
    private long repackPausedUntil;

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
            shulkerSlotsBefore.clear();
            for (int i = 0; i < 36; i++) {
                Item item = inventoryItem(i);
                if (item != null && item.isShulker()) shulkerSlotsBefore.add(i);
            }
            handle = Handle.Work;
            handleTicks = 0;
            wait = CONFIG.delayTicks;
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
            if (!placed(placed)) {
                endHandling();
                return;
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
            handle = Handle.Break;
            handleTicks = 0;
            return;
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
        boolean busy = job == Job.Clean ? takeStray(c) : plan != null ? packPlanOne(c, plan) : packOne(c);
        // A click that was refused (see bundleHazard) did nothing: the shulker is closed up instead of asking again
        if (clickRefused) {
            clickRefused = false;
            busy = false;
            if (job == Job.Clean) repackFailed = true;
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
        if (job == Job.Clean && actions == 0) repackFailed = true;

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
            if (item == null || item.id.equals(mainId) || (!item.isFilledBundle() && bestSigned(item).isEmpty())) continue;
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
            if (brokenSince < 0) brokenSince = tickCount;
            if (tickCount - brokenSince < BREAK_SETTLE_TICKS) return;
            activity.releaseOurPathing();
            restoreRules();
            brokenSince = -1;
            handle = Handle.Collect;
            handleTicks = 0;
            return;
        }
        brokenSince = -1;

        if (handleTicks > HANDLE_TIMEOUT * 2) {
            activity.releaseOurPathing();
            restoreRules();
            abandonShulker("Couldn't break the shulker at " + placed + ", it's still there.");
            return;
        }

        // Breaking it needs a free slot to pick it up into: otherwise it would end up on the ground
        if (activity.mayRequest()) {
            if (!freeInventorySlot()) {
                if (handleTicks % 200 == 1) warn("No free inventory slot to pick the shulker at {} up into, leaving it where it is until there is one.", placed);
                if (handleTicks > 20 * 60 * 5) abandonShulker("No free inventory slot to pick the shulker at " + placed + " up into.");
                return;
            }
            // The pathfinder logs a start for every request: once is enough while it works on it
            if (!placed.equals(breakAsked) || tickCount - breakAskedAt > 20 * 4) {
                breakAsked = placed;
                breakAskedAt = tickCount;
                requestBreak(placed);
            }
        }
    }

    private void handleCollect() {
        if (countInInv(this::isShulkerBox) >= shulkersBefore) {
            activity.releaseOurPathing();

            // Picked up: make sure it's in a carry slot, so it gets put away
            for (int i = 0; i < 36; i++) {
                Item item = inventoryItem(i);
                if (shulkerSlotsBefore.contains(i) || item == null || !item.isShulker()) continue;
                CompactPlan plan = compact;
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
                    }
                    else warn("The repacked shulker ended up outside the carry slots, in slot {}.", i + 1);
                }
                break;
            }
            endHandling();
            return;
        }

        // The break didn't go through after all: the server put the shulker back where it stood. It is broken again rather
        // than looked for on the ground, where it never was
        if (stillStanding()) {
            activity.releaseOurPathing();
            restoreRules();
            if (++breakRetries > MAX_BREAK_RETRIES) {
                abandonShulker("Couldn't break the shulker at " + fmt(placed) + ", the server keeps putting it back. It's still standing there.");
                return;
            }
            warn("The shulker at {} is still standing, the server didn't let it break. Breaking it again ({}/{}).", fmt(placed), breakRetries, MAX_BREAK_RETRIES);
            breakAsked = null;
            brokenSince = -1;
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }

        if (handleTicks > COLLECT_TIMEOUT) {
            activity.releaseOurPathing();
            debug("Couldn't pick the shulker back up: {}.", pickupDiagnosis());
            abandonShulker("Couldn't pick the shulker back up, it's on the ground near " + fmt(placed) + ".");
            return;
        }

        collectPickup();
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

        Entity item = shulkerItemNearby();
        if (item != null && pickupAsks % 2 == 0) {
            rules(false, false);
            GoalBlock walkTo = new GoalBlock(cell(item));
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
            abandonShulker("Skipping the shulker box at " + fmt(placed) + ": the way there leaves the stash area.");
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
        handle = null;
        placed = null;
        breakRetries = 0;
        brokenSince = -1;
        if (stash.isDirty()) stash.save();
        job = null;
        wait = CONFIG.delayTicks;
    }

    /**
     * A shulker that could not be broken or picked up: said loudly, left where it is, and no more are put down until
     * the bot is started again. Sorting carries on.
     */
    private void abandonShulker(String why) {
        CompactPlan plan = compact;
        String what = plan != null ? plan.reference.name : fillId != null ? fillId : "items";
        int stacks = plan != null ? plan.placedSize : 0;
        error("{} It holds {} of {}. No more shulkers are put down until sorting is restarted.", why, stacks > 0 ? stacks + " stacks" : "some stacks", what);
        inGameAlertActivePlayer("<red>" + why);
        if (placed != null) recordOrphan(placed, what, stacks);
        dropLost = true;
        if (compact != null) compact.aborting = true;
        compact = null;
        endHandling();
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
        if (CONFIG.sortItems) reportStrays();
        unrecognisedSigns(true);

        if (!CONFIG.sortItems) {
            long unopened = stash.boxes.stream().filter(b -> b.unreachable).count();
            String note = unopened > 0 ? String.format(" Couldn't open %d containers.", unopened) : "";
            if (CONFIG.stayOn) {
                if (surveyed > 0 || !note.equals(lastSummary)) info("Scanned {} containers (scan only).{} Waiting for changes.", surveyed, note);
                lastSummary = note;
                surveyed = 0;
                idle = true;
                idleTicks = 0;
                seenVersion = Stashes.version;
                return;
            }
            info("Done, scanned {} containers (scan only).{}", surveyed, note);
            inGameAlertActivePlayer("<green>Stash scanned: " + stash.knownCount() + " containers read.");
            surveyed = 0;
            stop();
            return;
        }

        long unreachable = stash.boxes.stream().filter(b -> b.unreachable).count();
        String extra = unreachable > 0 ? String.format(" Couldn't open %d containers, they were left alone.", unreachable) : "";
        if (runPacked > 0) extra += String.format(" Packed %d shulkers, freed %d slots.", runPacked, runFreed);
        runPacked = runFreed = 0;
        if (!stuckCarried.isEmpty()) extra += String.format(" %d stacks I am carrying have nowhere to go.", stuckCarried.size());
        long leftInDropOffs = stash.boxes.stream().filter(b -> b.dropOff && b.known() && !b.unreachable).mapToLong(b -> b.contents.length - b.free()).sum();
        if (leftInDropOffs > 0) extra += String.format(" %d stacks are still in drop-offs, there's no room left in the stash.", leftInDropOffs);

        if (CONFIG.stayOn) {
            // Waking up for a drop-off check that finds nothing says nothing; a changed summary does
            if (moved > 0 || !extra.equals(lastSummary)) info("Sorted, moved {} stacks.{} Waiting for more to sort.", moved, extra);
            lastSummary = extra;
            moved = 0;
            idle = true;
            idleTicks = 0;
            seenVersion = Stashes.version;
            return;
        }

        info("Done, moved {} stacks.{}", moved, extra);
        inGameAlertActivePlayer("<green>Stash sorted: moved " + moved + " stacks.");
        stop();
    }

    private boolean start(Job next, Box box) {
        // Compacting has its own limits: its trips are not what the circle watchdog counts
        if (next != Job.Gather && !(next == Job.Fetch && compact != null)) {
            passJobs++;
            boxVisits.merge(box, 1, Integer::sum);
        }
        signAbort = false;
        if (next == Job.Survey) surveyed++;
        markJobStart();
        job = next;
        target = box;
        actions = 0;
        tries = 0;
        stepTicks = 0;
        goal = new GoalNear(box.pos, REACH_RANGE_SQ);
        step = Step.Walking;
        jobInterruptions = 0;
        resetJobState();
        return true;
    }

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

        // Opened it and got nothing done: don't keep coming back while nothing else changes
        // Clicks that changed neither the carry slots nor the container count for nothing: they never went through
        // Clicks were made but the window went before the result could be read: an unconfirmed attempt, which is neither
        // progress nor proof of none. A box is stalled after two of them in a row
        boolean confirmed = endSigSet && startSigSet;
        boolean unconfirmed = actions > 0 && !confirmed;
        boolean progressed = actions > 0 && confirmed && startSig != endSig;
        if (actions == 0 && !signAbort && job != null && job != Job.Survey && target != null && !target.unreachable) {
            futile.put(futileKey(target, job), futileSig(target));
        }
        if (actions > 0 && target != null) signAbortRuns.remove(target);
        int visits = target == null ? 0 : boxVisits.getOrDefault(target, 0);
        if (visits >= 3 && job != Job.Survey && loopReported.add(target)) {
            debug("Container at {} has been the target of {} jobs without the backlog shrinking: last job {}, {} actions, {}, {}, {}, carrying {} stacks.",
                target.pos, visits, job, actions, confirmed ? "confirmed" : "unconfirmed", progressed ? "changed something" : "changed nothing",
                signAbort ? "ended by a pending sign" : "not a sign abort", carriedSlots().size());
        }
        // Going back and forth between containers while the backlog doesn't shrink: given up on, however each job looks
        boolean circle = visits >= 4 && job != Job.Survey && target != null && !signAbort;
        if (circle) {
            circular.add(target);
            stalled.add(target);
        }
        else if (job == Job.Survey && target != null && (!target.known() || target.stale) && !signAbort) failedSurvey.add(target);
        else if (job != null && job != Job.Survey && target != null && !target.unreachable) {
            if (unconfirmed && !signAbort) {
                if (unconfirmedRuns.merge(target, 1, Integer::sum) >= 2) stalled.add(target);
            }
            else if (!progressed && !signAbort) stalled.add(target);
            else if (progressed) {
                unconfirmedRuns.remove(target);
                clearStalled();
                stalled.addAll(circular);
                stuckCarried.clear();
            }
        }

        startSigSet = endSigSet = false;

        if (stash.isDirty()) stash.save();
        signAbort = false;
        job = null;
        target = null;
        wait = CONFIG.delayTicks;
    }

    private void giveUp(boolean opening) {
        activity.releaseOurPathing();
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
            giveUp(false);
            return;
        }

        // Anything else on Baritone would have had us give way by now, so this is only ever our own path
        if (activity.mayRequest()) {
            goal = requestWalk(nearestPart(target));
            wait = 10;
        }
    }

    private void tickOpening() {
        int id = openContainerId();
        Container c = id != 0 ? openContainer() : null;

        if (c != null) {
            String why = openRejection(c);
            if (why == null) {
                step = Step.Working;
                boxFailures.remove(target);
                stepTicks = 0;
                targetOpen = true;
                Stashes.read(stash, target, c);
                wait = CONFIG.delayTicks;
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
            if (!ownsWindow()) {
                beginYield(Activity.Reason.Container);
                return;
            }
            if (closeSafely() == Close.Stuck) return;
            wait = CONFIG.delayTicks;
            if (stepTicks > OPEN_TIMEOUT) giveUp(true);
            return;
        }

        if (tries >= OPEN_TRIES || stepTicks > OPEN_TIMEOUT) {
            giveUp(true);
            return;
        }

        // The last click is still on its way to the container: asking again would start it over. Nor over anyone else's
        if (activity.ownPathing() || !activity.mayRequest()) return;

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

        // Both ends of the comparison are read through the open window, before anything is clicked and once the last
        // click is through, so they come from the same source
        if (actions == 0 && !startSigSet) {
            startSig = stateSig();
            startSigSet = true;
        }

        clickRefused = false;
        boolean busy = switch (job) {
            case Survey, Clean, Fill, Label, Ground, Explore, Unpack -> false;
            case FetchSigns -> fetchSignsOne(c);
            case Take -> takeOne(c);
            case Fetch -> fetchOne(c);
            case Compact -> compactOne(c);
            case Deposit -> depositOne(c);
            case Dump -> dumpOne(c);
            case Gather -> gatherOne(c);
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
            finishJob();
        }
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
        // What each destination can still take, so we don't pick up more than we can put away
        Map<Box, Integer> room = new HashMap<>();
        for (Box box : stash.boxes) {
            // Only where a deposit could go: not a container being skipped or whose sign hasn't settled
            if (box.sortable() && !box.dropOff && box != source && !stalled.contains(box) && !signPending(box)) room.put(box, box.free() + misplacedCount(box));
        }
        for (int carried : looseCarried()) {
            for (Box box : destinationsFor(inventoryItem(carried))) {
                if (room.containsKey(box)) {
                    room.merge(box, -1, Integer::sum);
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
            if (destinationsFor(item).stream().anyMatch(b -> room.getOrDefault(b, 0) > 0)) return i;
            if (spare > 0) return i;
        }

        return -1;
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
            if (!canFit(inventoryItem(slot))) continue;
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
        rules(false, false);
        GoalNear near = new GoalNear(pos, REACH_RANGE_SQ);
        PathingRequestFuture future = BARITONE.pathTo(near);
        activity.notePath(future, BARITONE.getCustomGoalProcess(), near, tickCount);
        return near;
    }

    private void requestOpen(BlockPos pos) {
        lastOpenClickPos = pos;
        rules(false, false);
        PathingRequestFuture future = BARITONE.rightClickBlock(pos.x(), pos.y(), pos.z());
        activity.notePath(future, BARITONE.getInteractWithProcess(), null, tickCount);
    }

    private void requestPlace(BlockPos pos, ItemData item) {
        rules(false, true);
        PathingRequestFuture future = BARITONE.placeBlock(pos.x(), pos.y(), pos.z(), item);
        activity.notePath(future, BARITONE.getInteractWithProcess(), null, tickCount);
    }

    private void requestBreak(BlockPos pos) {
        rules(true, false);
        PathingRequestFuture future = BARITONE.breakBlock(pos.x(), pos.y(), pos.z(), true);
        activity.notePath(future, BARITONE.getInteractWithProcess(), null, tickCount);
    }

    private void requestPickup() {
        rules(false, false);
        // Just the shulker, not whatever else lies around
        PathingRequestFuture future = placedItem != null ? BARITONE.pickup(placedItem) : BARITONE.pickup();
        activity.notePath(future, BARITONE.getFollowProcess(), null, tickCount);
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
    private RequestFuture submitClose(int id, int slot) {
        List<InventoryAction> request = new ArrayList<>();
        if (slot != -1) request.add(new ClickItem(id, slot, ClickItemAction.LEFT_CLICK));
        request.add(new CloseContainer());
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
        if (openedAt >= openRequestedAt) ours = handle != null ? isShulkerWindow(c) : target != null && openRejection(c) == null;
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
        if (CONFIG.mode != Mode.Tiers) return;

        List<Box> ordered = stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff).sorted(Comparator.comparingInt(b -> b.order)).toList();

        // How many stacks of each tier there are, including drop-offs and what we're carrying
        int[] counts = new int[5];
        for (Box box : stash.boxes) {
            if (!box.sortable()) continue;
            for (Item item : box.contents) if (item != null) counts[tier(item)]++;
        }
        for (int slot : carriedSlots()) counts[tier(inventoryItem(slot))]++;

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
        int best = 0;
        List<Box> result = new ArrayList<>();
        Categories.Described described = Categories.describe(item);
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff) continue;

            int score = 0;
            for (String label : box.labels) score = Math.max(score, Categories.signScore(label, described));

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
            if (!box.sortable() || box.dropOff || box.labels.isEmpty()) continue;
            if (box.labels.stream().anyMatch(Categories::recognises)) continue;
            count++;
            if (say && unrecognisedReported.add(box)) {
                warn("I don't recognise the sign on the container at {} (\"{}\"), so nothing is sorted into it.", box.pos, String.join(" | ", box.labels));
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
            if (!box.sortable() || box.dropOff || box.labels.isEmpty() || isMisc(box)) continue;
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
        if (!b.sortable() || b.dropOff || b.free() <= 0 || blocked(b, Job.Dump) || signPending(b)) return false;
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
        return stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && b.labels.stream().anyMatch(Categories::isKeepWhole)).toList();
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

    public String statusLine() {
        if (!isEnabled()) return "off";
        if (stash == null) return "waiting for the bot to be in game";
        if (gate != Gate.Running) {
            int quiet = quietTicks();
            long left = foreignNow ? quiet : Math.max(0, quiet - (tickCount - lastForeignTick));
            return "waiting: " + waitLabel(foreignNow || !quietElapsed() ? lastReason : null) + ", " + (left + 19) / 20 + "s quiet left";
        }
        return sortStatus() + scanNote() + ", ground items: " + groundState();
    }

    private String scanNote() {
        return CONFIG.sortItems ? "" : " (scan only)";
    }

    private String sortStatus() {
        if (idle) return (CONFIG.sortItems ? "sorted, waiting for more (stay-on)" : "scanned, waiting for changes (stay-on)") + unrecognisedNote();
        if (job == null) return (CONFIG.sortItems ? "planning" : "scanning") + unrecognisedNote();
        return switch (job) {
            case Survey -> "reading " + target.pos;
            case Take -> "taking from " + target.pos;
            case Deposit -> "putting away into " + target.pos;
            case Dump -> "putting down extras in " + target.pos;
            case Fetch -> "fetching a shulker from " + target.pos;
            case Clean -> "cleaning out a mixed shulker";
            case Fill -> compact != null ? "packing " + compact.reference.name + " into a shulker" : "packing " + fillId + " into a bulk shulker";
            case Compact -> "merging stacks in " + target.pos;
            case FetchSigns -> "fetching signs from " + target.pos;
            case Label -> "labelling the chest at " + target.pos + " as \"" + labelText + "\"";
            case Gather -> "gathering stacks to compact from " + target.pos;
            case Unpack -> "emptying a bundle";
            case Explore -> "exploring the stash area (" + exploredChunks.size() + " chunks seen, " + unreachableChunks.size() + " out of reach)";
            case Ground -> "picking up " + groundLabel + " from the ground at " + (groundCell == null ? "?" : fmt(groundCell));
        } + " (" + moved + " moved)" + unrecognisedNote();
    }
}
