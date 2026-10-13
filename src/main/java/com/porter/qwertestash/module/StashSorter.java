package com.porter.qwertestash.module;

import com.aquarius.cache.data.inventory.Container;
import com.aquarius.event.client.ClientBotTick;
import com.aquarius.feature.inventory.InventoryActionRequest;
import com.aquarius.feature.inventory.actions.ClickItem;
import com.aquarius.feature.pathfinder.goals.GoalNear;
import com.aquarius.mc.block.BlockPos;
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

    private enum Job { Survey, Take, Deposit, Dump }

    private enum Step { Walking, Opening, Working }

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

        if (!CACHE.getPlayerCache().isAlive() || CACHE.getPlayerCache().getPlayerInventory().size() < 45) {
            // Not in game yet: ClientBotTick.Starting calls this again once we are
            return;
        }

        if (!pickCarrySlots()) {
            stop();
            return;
        }

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
            error("Found {} container(s) to sort within {} blocks, need at least 2.", sortable, CONFIG.radius);
            stop();
            return;
        }

        if (CONFIG.mode == Mode.Signs && stash.boxes.stream().allMatch(b -> b.labels.isEmpty())) {
            error("None of the containers have signs on them. Put signs on them, or use tiers mode.");
            stop();
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
            error(designated ? "The carry slots are all full. Empty at least one of them." : "Empty at least one inventory slot to carry items in.");
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

        Box source = nearest(stash.boxes.stream().filter(b -> b.sortable() && !stalled.contains(b) && takeableSlot(b) != -1).toList());
        if (source != null) return start(Job.Take, source);

        finishSorting();
        return false;
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
            case Survey -> false;
            case Take -> takeOne(c);
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
        com.aquarius.Globals.INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(actions).priority(ACTION_PRIORITY).build());
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
        } + " (" + moved + " moved)";
    }
}
