package com.porter.qwertestash.module;

import com.aquarius.cache.data.inventory.Container;
import com.aquarius.event.client.ClientBotTick;
import com.aquarius.feature.inventory.InventoryActionRequest;
import com.aquarius.feature.inventory.actions.ClickItem;
import com.aquarius.feature.inventory.actions.MoveToHotbarSlot;
import com.aquarius.feature.pathfinder.goals.GoalNear;
import com.aquarius.mc.block.BlockPos;
import com.aquarius.mc.item.ItemRegistry;
import com.aquarius.module.impl.AbstractFieldModule;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.StashConfig.Mode;
import com.porter.qwertestash.stash.Categories;
import com.porter.qwertestash.stash.Containers;
import com.porter.qwertestash.stash.Item;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.Stashes;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.INVENTORY;
import static com.aquarius.Globals.MODULE;
import static com.github.rfresh2.EventConsumer.of;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * Sorts a saved stash of chests and barrels, either into tiers (shulkers first, then stackables, then singles) or into
 * whichever container's signs best describe each item. The stash is scanned once and saved, then kept up to date by
 * {@link StashTracker}. Only runs while nobody is controlling the bot: it pauses while you're connected.
 */
public class StashSorter extends AbstractFieldModule {
    // Generous limits, 2b2t can be laggy
    private static final int OPEN_TRIES = 6;
    private static final int WALK_TIMEOUT = 20 * 60;
    private static final int CLICK_TIMEOUT = 20 * 5;

    private enum Job { Survey, Take, Deposit, Dump, Fetch, Clean, Fill }

    private enum Step { Walking, Opening, Working }

    // Repacking a shulker in the world: put it down, open it, move items, break it, pick it back up
    private enum Handle { Hold, Place, Open, Work, Break, Collect }

    private static final int HANDLE_TIMEOUT = 20 * 10;
    private static final int COLLECT_TIMEOUT = 20 * 15;

    private int wantSlot = -1;
    private int handledSlot;
    private @Nullable String fillId;
    private @Nullable String mainId;
    private @Nullable Handle handle;
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

    private @Nullable Job job;
    private @Nullable Box target;
    private Step step = Step.Walking;
    private int stepTicks;
    private int tries;
    private int wait;
    private int clickTicks;
    private int moved;
    private @Nullable GoalNear goal;

    // Containers where the last job got nothing done, skipped until something moves somewhere else
    private final Set<Box> stalled = new HashSet<>();
    private int actions;

    // Stay-on mode
    private boolean idle;
    private int idleTicks;
    private int seenVersion;

    @Override
    public boolean enabledSetting() {
        return CONFIG.sorting;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, e -> restart())
        );
    }

    /**
     * Opening and reading containers, so the tracker leaves them to us.
     */
    public boolean isWorking() {
        return isEnabled() && job != null;
    }

    // Turning on and off

    @Override
    public void onEnable() {
        restart();
    }

    /**
     * Starts over from the current state: when turned on, and when the bot (re)connects.
     */
    private void restart() {
        job = null;
        target = null;
        moved = 0;
        wait = 0;
        idle = false;
        stalled.clear();
        tierBoxes.clear();
        stash = null;
        handle = null;
        repackFailed = false;
        packTried.clear();

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

        long sortable = stash.boxes.stream().filter(b -> !b.excluded && !b.dropOff).count();
        if (sortable < 2) {
            fail(String.format("Found %d container(s) to sort within %d blocks of the bot, need at least 2.", sortable, CONFIG.radius));
            return;
        }

        if (CONFIG.mode == Mode.Signs && stash.boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            fail("None of the containers have signs on them. Put signs on them, or use tiers mode.");
            return;
        }

        // Never dig or build our way between containers
        setBreakingAllowed(false);
        setPlacingAllowed(false);

        int unknown = stash.boxes.size() - stash.knownCount();
        if (!existed) info("New stash \"{}\": scanning {} containers, then sorting.", stash.name, stash.boxes.size());
        else if (unknown > 0) info("Stash \"{}\": {} containers, scanning {} new ones first.", stash.name, stash.boxes.size(), unknown);
        else info("Stash \"{}\": {} containers, sorting by {}.", stash.name, stash.boxes.size(), CONFIG.mode == Mode.Tiers ? "tier" : "sign");

        if (added > 0 || !existed) stash.save();
    }

    @Override
    public void onDisable() {
        if (openContainerId() != 0) closeContainer();
        if (BARITONE.isActive()) BARITONE.stop();
        restoreBreaking();
        restorePlacing();
        if (handle != null && placed != null && placed(placed)) warn("Stopped while repacking, there's a shulker left at {}.", placed);
        handle = null;

        if (stash != null && stash.isDirty()) stash.save();
        if (!carriedSlots().isEmpty()) warn("Stopped while carrying {} stacks from the stash, they're in the bot's inventory.", carriedSlots().size());

        job = null;
        target = null;
        stash = null;
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
        Set<Integer> keep = parseSlots(CONFIG.keepSlots);
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
        if (notReady()) return;
        if (stash == null) {
            restart();
            return;
        }

        if (wait > 0) {
            wait--;
            return;
        }

        if (job == null) {
            if (idle && !wakeUp()) return;
            if (openContainerId() != 0) {
                closeContainer();
                wait = CONFIG.delayTicks;
                return;
            }
            if (!nextJob()) return;
        }

        if (handle != null) {
            tickHandling();
            return;
        }
        stepTicks++;

        switch (step) {
            case Walking -> tickWalking();
            case Opening -> tickOpening();
            case Working -> tickWorking();
        }
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
            stalled.clear();
            packTried.clear();
            for (Box box : stash.boxes) Containers.readSigns(box);
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
        Box unread = nearest(stash.boxes.stream().filter(b -> !b.known() && !b.unreachable).toList());
        if (unread != null) return start(Job.Survey, unread);

        plan();

        if (repackOn() && nextRepackJob()) return true;

        List<Integer> carried = carriedSlots();
        if (!carried.isEmpty()) {
            List<Box> places = new ArrayList<>();
            for (int slot : carried) {
                for (Box box : destinationsFor(inventoryItem(slot))) {
                    if (!stalled.contains(box) && (box.free() > 0 || misplacedCount(box) > 0)) places.add(box);
                }
            }

            Box place = nearest(places);
            if (place != null) return start(Job.Deposit, place);

            // Nowhere it belongs has room: put it down anywhere with space rather than walk around holding it
            Box spare = nearest(stash.boxes.stream().filter(b -> b.sortable() && !b.dropOff && b.free() > 0 && !stalled.contains(b)).toList());
            if (spare != null) return start(Job.Dump, spare);

            warn("Found nowhere with room for what I'm carrying, stopping. {} stacks from the stash are in the bot's inventory.", carried.size());
            stop();
            return false;
        }

        // Mixed shulkers sitting in the stash: fetch one to clean out
        if (repackOn() && freeCarryCount() >= 2) {
            Box best = null;
            int bestSlot = -1;
            for (Box box : stash.boxes) {
                if (!box.sortable() || stalled.contains(box)) continue;
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

        Box source = nearest(stash.boxes.stream().filter(b -> b.sortable() && !stalled.contains(b) && takeableSlot(b) != -1).toList());
        if (source != null) return start(Job.Take, source);

        finishSorting();
        return false;
    }

    // Repacking shulkers

    private boolean repackOn() {
        return CONFIG.repack && CONFIG.mode == Mode.Signs && !repackFailed;
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
                    if (!box.sortable() || box.dropOff || stalled.contains(box)) continue;
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

        // A sign asks for shulkers as they are, like "kits" or "full shulkers"
        if (!bestSigned(shulker).isEmpty()) return List.of();

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
        job = next;
        target = null;
        handledSlot = slot;
        fillId = fill;
        handle = Handle.Hold;
        handleTicks = 0;
        placed = null;
        actions = 0;
        return true;
    }

    /**
     * One step of repacking a shulker beside the bot: place, open, move items, break, pick back up.
     */
    private void tickHandling() {
        handleTicks++;
        if (inventoryBusy()) return;

        switch (handle) {
            case Hold -> handleHold();
            case Place -> handlePlace();
            case Open -> handleOpen();
            case Work -> handleWork();
            case Break -> handleBreak();
            case Collect -> handleCollect();
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
        Set<Integer> keep = parseSlots(CONFIG.keepSlots);
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
            if (inventoryItem(hotbar) != null) {
                swapBackFrom = handledSlot;
                swapBackTo = hotbar;
            }
            INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                .actions(new MoveToHotbarSlot(invSlot(handledSlot), MoveToHotbarAction.from(hotbar)))
                .priority(ACTION_PRIORITY).build());
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

        shulkersBefore = countInInv(this::isShulkerBox);
        setPlacingAllowed(true);
        place(placed, ItemRegistry.REGISTRY.get(shulker.id));
        handle = Handle.Place;
        handleTicks = 0;
    }

    private void handlePlace() {
        if (placed(placed) && countInInv(this::isShulkerBox) < shulkersBefore) {
            if (BARITONE.isActive()) BARITONE.stop();
            setPlacingAllowed(false);
            handle = Handle.Open;
            handleTicks = 0;
            wait = CONFIG.delayTicks;
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT) {
            if (BARITONE.isActive()) BARITONE.stop();
            setPlacingAllowed(false);
            warn("Couldn't put the shulker down, so I'm not repacking shulkers this run.");
            repackFailed = true;
            endHandling();
        }
    }

    private void handleOpen() {
        // Put back whatever was swapped out of the hotbar to place it
        if (swapBackFrom != -1) {
            if (inventoryItem(swapBackTo) == null) {
                INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                    .actions(new MoveToHotbarSlot(invSlot(swapBackFrom), MoveToHotbarAction.from(swapBackTo)))
                    .priority(ACTION_PRIORITY).build());
            }
            swapBackFrom = swapBackTo = -1;
            wait = CONFIG.delayTicks;
            return;
        }

        Container c = openContainer();
        if (openContainerId() != 0 && c != null && Stashes.slots(c) == 27) {
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

        if (handleTicks == 1 || handleTicks % 20 == 0) {
            if (!placed(placed)) {
                endHandling();
                return;
            }
            open(placed);
        }
    }

    private void handleWork() {
        Container c = openContainer();
        if (openContainerId() == 0 || c == null || Stashes.slots(c) != 27) {
            handle = Handle.Break;
            handleTicks = 0;
            return;
        }
        if (!cursorEmpty()) {
            stashCursor(c);
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

        closeContainer();
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
            closeContainer();
            return;
        }

        if (!placed(placed)) {
            setBreakingAllowed(false);
            handle = Handle.Collect;
            handleTicks = 0;
            return;
        }

        if (handleTicks > HANDLE_TIMEOUT * 2) {
            setBreakingAllowed(false);
            failHandling("Couldn't break the shulker at " + placed + ", it's still there.");
            return;
        }

        setBreakingAllowed(true);
        breakAt(placed, true);
    }

    private void handleCollect() {
        if (countInInv(this::isShulkerBox) >= shulkersBefore) {
            if (BARITONE.isActive()) BARITONE.stop();

            // Picked up: make sure it's in a carry slot, so it gets put away
            for (int i = 0; i < 36; i++) {
                Item item = inventoryItem(i);
                if (shulkerSlotsBefore.contains(i) || item == null || !item.isShulker()) continue;
                if (!carry.contains(i)) {
                    int free = freeCarrySlot();
                    if (free != -1) {
                        INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                            .actions(new ClickItem(0, invSlot(i), ClickItemAction.LEFT_CLICK), new ClickItem(0, invSlot(free), ClickItemAction.LEFT_CLICK))
                            .priority(ACTION_PRIORITY).build());
                    }
                    else warn("The repacked shulker ended up outside the carry slots, in slot {}.", i + 1);
                }
                break;
            }
            endHandling();
            return;
        }

        if (handleTicks > COLLECT_TIMEOUT) {
            if (BARITONE.isActive()) BARITONE.stop();
            failHandling("Couldn't pick the shulker back up, it's on the ground near " + placed + ".");
            return;
        }

        if (!BARITONE.isActive()) BARITONE.pickup();
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

    private void finishSorting() {
        if (stash.isDirty()) stash.save();

        long unreachable = stash.boxes.stream().filter(b -> b.unreachable).count();
        String extra = unreachable > 0 ? String.format(" Couldn't open %d containers, they were left alone.", unreachable) : "";

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
        job = next;
        target = box;
        actions = 0;
        tries = 0;
        stepTicks = 0;
        goal = new GoalNear(box.pos, REACH_RANGE_SQ);
        step = Step.Walking;
        return true;
    }

    private void finishJob() {
        if (openContainerId() != 0) closeContainer();

        // Opened it and got nothing done: don't keep coming back while nothing else changes
        if (job != null && job != Job.Survey && target != null && !target.unreachable) {
            if (actions == 0) stalled.add(target);
            else stalled.clear();
        }

        if (stash.isDirty()) stash.save();
        job = null;
        target = null;
        wait = CONFIG.delayTicks;
    }

    private void giveUp() {
        if (BARITONE.isActive()) BARITONE.stop();
        target.unreachable = true;
        warn("Couldn't reach or open the container at {}, skipping it.", target.pos);
        finishJob();
    }

    // Steps

    private void tickWalking() {
        if (arrivedAt(goal)) {
            if (BARITONE.isActive()) BARITONE.stop();
            step = Step.Opening;
            stepTicks = 0;
            return;
        }

        if (stepTicks > WALK_TIMEOUT) {
            giveUp();
            return;
        }

        if (!BARITONE.isActive()) {
            goal = pathToNear(nearestPart(target));
            wait = 10;
        }
    }

    private void tickOpening() {
        Container c = openContainer();
        if (openContainerId() != 0 && c != null && Stashes.slots(c) > 0) {
            step = Step.Working;
            stepTicks = 0;
            Stashes.read(stash, target, c);
            wait = CONFIG.delayTicks;
            return;
        }

        if (tries >= OPEN_TRIES) {
            giveUp();
            return;
        }

        tries++;
        BlockPos pos = nearestPart(target);
        MODULE.get(StashTracker.class).clicked(pos);
        open(pos);
        wait = CONFIG.settleTicks;
    }

    private void tickWorking() {
        Container c = openContainer();
        if (openContainerId() == 0 || c == null) {
            // Closed on us, by the server
            finishJob();
            return;
        }

        // Wait for the last clicks to go through before deciding what's next
        if (inventoryBusy() || !cursorEmpty()) {
            if (++clickTicks > CLICK_TIMEOUT) {
                warn("Inventory clicks aren't going through, stopping.");
                stop();
            }
            if (!inventoryBusy() && !cursorEmpty()) stashCursor(c);
            return;
        }
        clickTicks = 0;

        Stashes.read(stash, target, c);

        boolean busy = switch (job) {
            case Survey, Clean, Fill -> false;
            case Take -> takeOne(c);
            case Fetch -> fetchOne(c);
            case Deposit -> depositOne(c);
            case Dump -> dumpOne(c);
        };

        if (busy) {
            actions++;
            wait = CONFIG.delayTicks;
        }
        else finishJob();
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
            if (box.sortable() && !box.dropOff && box != source) room.put(box, box.free() + misplacedCount(box));
        }
        for (int carried : carriedSlots()) {
            for (Box box : destinationsFor(inventoryItem(carried))) {
                if (room.containsKey(box)) {
                    room.merge(box, -1, Integer::sum);
                    break;
                }
            }
        }

        for (int i = 0; i < source.contents.length; i++) {
            Item item = source.contents[i];
            if (item == null || !misplaced(item, source)) continue;
            if (destinationsFor(item).stream().anyMatch(b -> room.getOrDefault(b, 0) > 0)) return i;
        }

        return -1;
    }

    /**
     * Puts one carried stack that belongs here into this container. When it's full, swaps it with something in here
     * that belongs elsewhere instead, so full containers can still be sorted.
     * @return false when nothing more can be put here
     */
    private boolean depositOne(Container c) {
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

                // Carried stack onto the cursor, swap it into the slot, then what was there into the carry slot
                int carrySlot = toWindow(slot, c);
                clicks(c, carrySlot, i, carrySlot);
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
     * Left-clicks these window slots in order, as one request so nothing gets in between.
     */
    private void clicks(Container c, int... slots) {
        ClickItem[] actions = new ClickItem[slots.length];
        for (int i = 0; i < slots.length; i++) actions[i] = new ClickItem(c.getContainerId(), slots[i], ClickItemAction.LEFT_CLICK);
        INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(actions).priority(ACTION_PRIORITY).build());
    }

    /**
     * Puts down whatever is left on the cursor, in an empty carry slot if there is one.
     */
    private void stashCursor(Container c) {
        for (int slot : carry) {
            if (inventoryItem(slot) == null) {
                leftClick(c, toWindow(slot, c));
                return;
            }
        }
        int empty = findEmptyPlayerWindowSlot(c);
        if (empty != -1) leftClick(c, empty);
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

        if (CONFIG.mode == Mode.Tiers) return tierBoxes.getOrDefault(tier(item), List.of());

        // A shulker goes where what it's mostly full of goes: a shulker of totems belongs with the totems. Only when
        // no sign describes its contents does it fall back to signs about shulkers themselves, like "shulkers" or "kits".
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
        for (Box box : stash.boxes) {
            if (!box.sortable() || box.dropOff) continue;

            int score = 0;
            for (String label : box.labels) score = Math.max(score, Categories.signScore(label, item));

            if (score > best) {
                best = score;
                result.clear();
            }
            if (score == best && score > 0) result.add(box);
        }
        return result;
    }

    /**
     * In the wrong place: everything in a drop-off that has somewhere to go, or anything in a container it doesn't
     * belong in.
     */
    private boolean misplaced(Item item, Box box) {
        List<Box> places = destinationsFor(item);
        return !places.isEmpty() && !places.contains(box);
    }

    private int misplacedCount(Box box) {
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

    public String statusLine() {
        if (!isEnabled()) return "off";
        if (stash == null) return "waiting for the bot to be in game";
        if (idle) return "sorted, waiting for more (stay-on)";
        if (job == null) return "planning";
        return switch (job) {
            case Survey -> "reading " + target.pos;
            case Take -> "taking from " + target.pos;
            case Deposit -> "putting away into " + target.pos;
            case Dump -> "putting down extras in " + target.pos;
            case Fetch -> "fetching a shulker from " + target.pos;
            case Clean -> "cleaning out a mixed shulker";
            case Fill -> "packing " + fillId + " into a bulk shulker";
        } + " (" + moved + " moved)";
    }
}
