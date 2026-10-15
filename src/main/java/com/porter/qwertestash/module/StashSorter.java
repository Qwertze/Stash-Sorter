package com.porter.qwertestash.module;

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
import com.porter.qwertestash.stash.Stashes;
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

    private enum Job { Survey, Take, Deposit, Dump, Fetch, Clean, Fill, Compact, FetchSigns, Label }

    private enum Step { Walking, Opening, Working }

    // Repacking a shulker in the world: put it down, open it, move items, break it, pick it back up. Return is the
    // walk back to it after giving way
    private enum Handle { Hold, Place, Open, Work, Break, Collect, Return }

    // Running: sorting. Yielding: someone else started something, we're letting go of our window, path and clicks.
    // Waiting: letting go is done, waiting for them to finish and be quiet for a while
    private enum Gate { Running, Yielding, Waiting }

    private static final int HANDLE_TIMEOUT = 20 * 10;
    private static final int COLLECT_TIMEOUT = 20 * 15;

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
        // This also runs every tick while the bot isn't in game: only the first time has anything to let go of
        if (stash != null || job != null || gate != Gate.Running || waitStart >= 0) resetGiveWay();
        job = null;
        target = null;
        moved = 0;
        passJobs = 0;
        passMin = Integer.MAX_VALUE;
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
        repackFailed = false;
        packTried.clear();
        resetJobState();
        cursorReported = false;

        if (!CACHE.getPlayerCache().isAlive() || CACHE.getPlayerCache().getPlayerInventory().size() < 45) {
            // Not in game yet: ClientBotTick.Starting calls this again once we are
            return;
        }

        if (!pickCarrySlots()) return;

        boolean existed = Stashes.current() != null;
        stash = Stashes.currentOrNew();

        int added = addNearbyBoxes();
        for (Box box : stash.boxes) {
            box.unreachable = false;
            Containers.readSigns(box);
        }
        orderBoxes();
        tiesReported.clear();
        strayReported.clear();
        unrecognisedReported.clear();
        if (CONFIG.mode == Mode.Signs) unrecognisedSigns(true);

        long sortable = stash.boxes.stream().filter(b -> !b.excluded && !b.dropOff).count();
        if (sortable < 2) {
            fail(String.format("Found %d container(s) to sort within %d blocks of the bot, need at least 2.", sortable, CONFIG.radius));
            return;
        }

        if (CONFIG.mode == Mode.Signs && stash.boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            fail("None of the containers have signs on them. Put signs on them, or use tiers mode.");
            return;
        }

        int unknown = stash.boxes.size() - stash.knownCount();
        if (!existed) info("New stash \"{}\": scanning {} containers, then sorting.", stash.name, stash.boxes.size());
        else if (unknown > 0) info("Stash \"{}\": {} containers, scanning {} new ones first.", stash.name, stash.boxes.size(), unknown);
        else info("Stash \"{}\": {} containers, sorting by {}.", stash.name, stash.boxes.size(), CONFIG.mode == Mode.Tiers ? "tier" : "sign");

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
        handle = null;

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
            if (inventoryItem(i) == null) carry.add(i);
        }

        if (designated && carry.size() < chosen.size()) {
            warn("{} of the carry slots have items in them, they'll be left alone.", chosen.size() - carry.size());
        }

        if (carry.isEmpty()) {
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

        if (wait > 0) {
            wait--;
            return;
        }

        if (finishing) {
            finishJob();
            return;
        }

        if (job == null) {
            restoreHand();
            if (!deferredAccess.isEmpty()) releaseDeferred();
            if (!staleBoxes.isEmpty()) applyStale();
            applySignChanges();
            if (idle && !wakeUp()) return;

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

        if (job == Job.Label) tickLabel();
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
        job = Job.Label;
        target = plan.box();
        step = Step.Walking;
        handle = null;
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
        if (!activity.ownPathing() && !BARITONE.isActive()) {
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
            tiesReported.clear();
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
    private void watchSignSpots() {
        StashTracker tracker = MODULE.get(StashTracker.class);
        if (!CONFIG.watchSigns || CONFIG.mode != Mode.Signs) {
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
        if (!CONFIG.watchSigns || CONFIG.mode != Mode.Signs) return;

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
        tiesReported.clear();
        idle = false;

        Long mine = ownSignUntil.get(box);
        if (mine == null || tickCount > mine) {
            info("Sign at {} changed: \"{}\" -> \"{}\", re-sorting.", box.pos, before.isEmpty() ? "no sign" : before.getFirst(), now.isEmpty() ? "no sign" : now.getFirst());
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
            warn("Couldn't reach or open the container at {}, skipping it.", target.pos);
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
    }

    private void restoreRules() {
        restoreBreaking();
        restorePlacing();
    }


    /**
     * Stay-on mode, waiting for something to sort: a container that changed, or a drop-off due for a check.
     */
    private boolean wakeUp() {
        idleTicks++;

        if (idleTicks >= CONFIG.recheckSeconds * 20) {
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
        Box unread = nearest(stash.boxes.stream().filter(b -> !b.known() && !b.unreachable && !failedSurvey.contains(b) && !signPending(b)).toList());
        if (unread != null) return start(Job.Survey, unread);

        plan();
        destCache = new IdentityHashMap<>();
        misplacedCache = new HashMap<>();

        // Jobs started since the backlog (stacks out of place, and carried) last reached a new low: a pass that keeps
        // starting jobs without getting anywhere is going in circles, so say so and stop rather than reopen forever
        int backlog = carriedSlots().size();
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
                passJobs, backlog - carriedSlots().size(), carriedSlots().size());
            finishSorting();
            return false;
        }

        // A homeless item gets a new category before it is put in the catch-all: this comes before putting things away
        if (nextLabelJob()) return true;

        if (repackOn() && nextRepackJob()) return true;

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

        finishSorting();
        return false;
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
            if (loose == null || loose.isShulker() || packTried.contains(loose.id)) continue;
            String id = loose.id;
            int stacks = carriedStacksOf(id);
            boolean startNew = CONFIG.newBulkAt > 0 && stacks >= CONFIG.newBulkAt;

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
        return start(Job.Fetch, box);
    }

    /**
     * Takes the one shulker this trip came for into a free carry slot, to repack it.
     * @return false once it's taken, or if it's gone
     */
    private boolean fetchOne(Container c) {
        if (wantSlot < 0 || actions > 0) return false;

        int into = freeCarrySlot();
        Item there = wantSlot < target.contents.length ? target.contents[wantSlot] : null;
        if (into == -1 || there == null || !there.isShulker()) return false;

        clicks(c, wantSlot, toWindow(into, c));
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
            if (!inside.id.equals(main.id) && !bestSigned(inside).isEmpty()) out.add(inside);
        }
        return out;
    }

    /**
     * A shulker holding only {@code id}, with room for more.
     */
    private static boolean bulkWithRoom(@Nullable Item shulker, String id) {
        if (shulker == null || !shulker.isShulker() || shulker.customName || shulker.contents.isEmpty()) return false;

        boolean room = shulker.contents.size() < 27;
        for (Item inside : shulker.contents) {
            if (!inside.id.equals(id)) return false;
            if (inside.count < inside.maxStack) room = true;
        }
        return room;
    }

    private static boolean emptyShulker(@Nullable Item shulker) {
        return shulker != null && shulker.isShulker() && !shulker.customName && shulker.contents.isEmpty();
    }

    private int carriedStacksOf(String id) {
        int count = 0;
        for (int slot : carriedSlots()) {
            Item item = inventoryItem(slot);
            if (item != null && item.id.equals(id)) count++;
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
        passJobs++;
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
        }
    }

    /**
     * Walks back to the shulker we put down, after giving way took us away from it.
     */
    private void handleReturn() {
        if (placed == null || !placed(placed) || arrivedAt(new GoalNear(placed, REACH_RANGE_SQ))) {
            activity.releaseOurPathing();
            enterHandle(returnTo != null ? returnTo : Handle.Open);
            return;
        }

        if (handleTicks > WALK_TIMEOUT) {
            activity.releaseOurPathing();
            failHandling("Couldn't get back to the shulker at " + placed + ".");
            return;
        }

        if (!activity.ownPathing() && !BARITONE.isActive()) {
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

        placed = selectSpotBeside(null);
        if (placed != null && stash.boxAt(placed) != null) placed = null;
        if (placed == null) {
            warn("No room to put a shulker down beside the bot, so I'm not repacking shulkers this run.");
            repackFailed = true;
            endHandling();
            return;
        }

        placeFailures = 0;
        shulkersBefore = countInInv(this::isShulkerBox);
        requestPlace(placed, ItemRegistry.REGISTRY.get(shulker.id));
        handle = Handle.Place;
        handleTicks = 0;
    }

    private void handlePlace() {
        if (placed(placed) && countInInv(this::isShulkerBox) < shulkersBefore) {
            activity.releaseOurPathing();
            restoreRules();
            handle = Handle.Open;
            openRequestedAt = Long.MAX_VALUE;
            openIdBefore = openContainerId();
            handleTicks = 0;
            wait = CONFIG.delayTicks;
            return;
        }

        // The place request failed by itself (an entity in the way, nothing to place against): let go of it, so it is
        // not taken for another module, and try the next spot; after three, repacking waits a while
        if (activity.superseded() && handleTicks > 5) {
            BlockPos failed = placed;
            activity.releaseOurPathing();
            restoreRules();
            if (++placeFailures >= 3) {
                warn("Couldn't put the shulker down ({} spots failed), not repacking shulkers for a few minutes.", placeFailures);
                repackPausedUntil = tickCount + 20 * 60 * 3;
                endHandling();
                return;
            }
            BlockPos next = selectSpotBeside(failed);
            if (next == null || next.equals(failed) || stash.boxAt(next) != null) {
                warn("No other spot to put the shulker down, not repacking shulkers for a few minutes.");
                repackPausedUntil = tickCount + 20 * 60 * 3;
                endHandling();
                return;
            }
            placed = next;
            requestPlace(placed, ItemRegistry.REGISTRY.get(inventoryItem(handledSlot).id));
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

        if ((handleTicks == 1 || handleTicks % 20 == 0) && !activity.ownPathing()) {
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

        boolean busy = job == Job.Clean ? takeStray(c) : packOne(c);
        if (busy) {
            actions++;
            wait = CONFIG.delayTicks;
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
        if (into == -1) return false;

        for (int i = 0; i < 27; i++) {
            Item item = Item.of(c.getItemStack(i));
            if (item == null || item.id.equals(mainId) || bestSigned(item).isEmpty()) continue;
            clicks(c, i, toWindow(into, c));
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
            if (item == null || !item.id.equals(fillId)) continue;

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

        if (!placed(placed)) {
            activity.releaseOurPathing();
            restoreRules();
            handle = Handle.Collect;
            handleTicks = 0;
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT * 2) {
            activity.releaseOurPathing();
            restoreRules();
            failHandling("Couldn't break the shulker at " + placed + ", it's still there.");
            return;
        }

        // Asking again would start the break over: only when the last request is over
        if (!activity.ownPathing() && !BARITONE.isActive()) requestBreak(placed);
    }

    private void handleCollect() {
        if (countInInv(this::isShulkerBox) >= shulkersBefore) {
            activity.releaseOurPathing();

            // Picked up: make sure it's in a carry slot, so it gets put away
            for (int i = 0; i < 36; i++) {
                Item item = inventoryItem(i);
                if (shulkerSlotsBefore.contains(i) || item == null || !item.isShulker()) continue;
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

        if (handleTicks > COLLECT_TIMEOUT) {
            activity.releaseOurPathing();
            failHandling("Couldn't pick the shulker back up, it's on the ground near " + placed + ".");
            return;
        }

        if (!BARITONE.isActive()) requestPickup();
    }

    private void endHandling() {
        handle = null;
        placed = null;
        if (stash.isDirty()) stash.save();
        job = null;
        wait = CONFIG.delayTicks;
    }

    private void failHandling(String why) {
        warn("{} Stopping.", why);
        handle = null;
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
        restoreRules();
        reportStrays();
        unrecognisedSigns(true);

        long unreachable = stash.boxes.stream().filter(b -> b.unreachable).count();
        String extra = unreachable > 0 ? String.format(" Couldn't open %d containers, they were left alone.", unreachable) : "";
        if (!stuckCarried.isEmpty()) extra += String.format(" %d stacks I am carrying have nowhere to go.", stuckCarried.size());
        long leftInDropOffs = stash.boxes.stream().filter(b -> b.dropOff && b.known() && !b.unreachable).mapToLong(b -> b.contents.length - b.free()).sum();
        if (leftInDropOffs > 0) extra += String.format(" %d stacks are still in drop-offs, there's no room left in the stash.", leftInDropOffs);

        if (CONFIG.stayOn) {
            if (moved > 0 || !idle) info("Sorted, moved {} stacks.{} Waiting for more to sort.", moved, extra);
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
        passJobs++;
        boxVisits.merge(box, 1, Integer::sum);
        signAbort = false;
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
        else if (job == Job.Survey && target != null && !target.known() && !signAbort) failedSurvey.add(target);
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

    private void giveUp() {
        activity.releaseOurPathing();
        target.unreachable = true;
        warn("Couldn't reach or open the container at {}, skipping it.", target.pos);
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
            giveUp();
            return;
        }

        // Anything else on Baritone would have had us give way by now, so this is only ever our own path
        if (!activity.ownPathing() && !BARITONE.isActive()) {
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
            if (stepTicks > OPEN_TIMEOUT) giveUp();
            return;
        }

        if (tries >= OPEN_TRIES || stepTicks > OPEN_TIMEOUT) {
            giveUp();
            return;
        }

        // The last click is still on its way to the container: asking again would start it over
        if (activity.ownPathing()) return;

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
            if (ourBusy() && ++clickTicks > CLICK_TIMEOUT) {
                warn("Inventory clicks are timing out, the inventory actions never finished. Stopping.");
                stop();
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

        boolean busy = switch (job) {
            case Survey, Clean, Fill, Label -> false;
            case FetchSigns -> fetchSignsOne(c);
            case Take -> takeOne(c);
            case Fetch -> fetchOne(c);
            case Compact -> compactOne(c);
            case Deposit -> depositOne(c);
            case Dump -> dumpOne(c);
        };

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

        // Straight into a carry slot, so it can never land in or top up one of the bot's own stacks
        clicks(c, slot, toWindow(into, c));
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
        for (int carried : carriedSlots()) {
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

        for (int slot : carriedSlots()) {
            Item item = inventoryItem(slot);
            if (!destinationsFor(item).contains(target)) continue;

            if (canFit(item)) {
                shiftClick(c, toWindow(slot, c));
                moved++;
                return true;
            }

            for (int i = 0; i < target.contents.length; i++) {
                Item here = target.contents[i];
                if (here == null || !misplaced(here, target) || here.sameAs(item)) continue;

                // Carried stack onto the cursor, swap it into the slot, then what was there into the carry slot, one click
                // at a time with the cursor checked in between
                swapCarry = toWindow(slot, c);
                swapSlot = i;
                swapStack = snapshot(item);
                swapDisplaced = snapshot(here);
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
        for (int slot : carriedSlots()) {
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
        RequestFuture future = INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(actions).priority(SORTER_PRIORITY).build());
        activity.noteSubmit(future, false, tickCount);
        return future;
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
        PathingRequestFuture future = BARITONE.pickup();
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
        if (held == null) return -1;

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
        if (different && tiesReported.add(item.id)) {
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
        return free - carriedSlots().size();
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
        for (int slot : carriedSlots()) {
            Item item = inventoryItem(slot);
            // Signs kept for labelling are not put away
            if (item != null && reservedSigns.contains(slot) && Containers.isSignItem(item.id)) continue;
            if (item != null && !item.id.equals(stuckCarried.get(slot))) slots.add(slot);
        }
        return slots;
    }

    /**
     * Inventory slots holding stash items we're carrying.
     */
    private List<Integer> carriedSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot : carry) if (inventoryItem(slot) != null) slots.add(slot);
        return slots;
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
        if (idle) return "sorted, waiting for more (stay-on)" + unrecognisedNote();
        if (job == null) return "planning" + unrecognisedNote();
        return switch (job) {
            case Survey -> "reading " + target.pos;
            case Take -> "taking from " + target.pos;
            case Deposit -> "putting away into " + target.pos;
            case Dump -> "putting down extras in " + target.pos;
            case Fetch -> "fetching a shulker from " + target.pos;
            case Clean -> "cleaning out a mixed shulker";
            case Fill -> "packing " + fillId + " into a bulk shulker";
            case Compact -> "merging stacks in " + target.pos;
            case FetchSigns -> "fetching signs from " + target.pos;
            case Label -> "labelling the chest at " + target.pos + " as \"" + labelText + "\"";
        } + " (" + moved + " moved)" + unrecognisedNote();
    }
}
