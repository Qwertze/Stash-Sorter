package com.porter.qwertestash.orders;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A fake world for the order machine: a stash of loose stacks, packed shulkers and empty shulkers, the bot's carried
 * finished shulkers, and a delivery container. It plays the sorter's part (building bins, fetching shulkers, delivering)
 * so the flow and the protocol driver can be run end to end without the game.
 */
final class OrderSim implements OrderFlow.Host {
    record Carried(Unit.Kind kind, String key, Map<String, Integer> content, @Nullable String name) {
        Carried(Unit.Kind kind, String key, Map<String, Integer> content) {
            this(kind, key, content, null);
        }

        Carried named(String n) {
            return new Carried(kind, key, content, n);
        }
    }

    /** The anvil: whether one is set, the bot's XP level, and how a trip to it goes ("ok", "timeout", "closed", "lowxp"). */
    boolean anvil = true;
    /** With no anvil: one stands in the stash area, one is in the stash to put down; how one came to be; why there is none. */
    boolean anvilStanding, anvilInStock;
    String anvilHow = "set";
    String noAnvilReason = "no anvil set";
    int xp = 30;
    String anvilTrip = "ok";
    /** The names the anvil gave, in order. */
    final List<String> named = new ArrayList<>();
    int anvilTrips;

    // Delivery storage (1.9.0): with a book the sim has chests of chestCap shulkers each, put up above one another
    DeliveryBook book;
    boolean autoExpand;
    @Nullable String expandBlocker;
    int chestCap = 2, expansions, rechecks;
    /** The host can't start an Expand or a Recheck (it does nothing for them). */
    boolean refuseStarts;
    final Map<DeliveryBook.Pos, Integer> inChest = new HashMap<>();

    /** The sim's storage is one chest at (0, 64, 0) with this many shulkers of room. */
    OrderSim storage(int cap) {
        book = new DeliveryBook();
        chestCap = cap;
        book.syncPrimary(List.of(new DeliveryBook.Pos(0, 64, 0)));
        return this;
    }

    /** A player takes everything out of the chest (the lid is seen open and shut). */
    void claim(DeliveryBook.Pos p) {
        DeliveryBook.Entry e = book.find(p);
        for (DeliveryBook.Pos q : e.parts()) inChest.put(q, 0);
        book.onViewers(p, 1, false);
        book.onViewers(p, 0, false);
    }

    @Override
    public DeliveryRoom deliveryRoom() {
        return book == null ? DeliveryRoom.ROOM : book.room(autoExpand, expandBlocker, now, DeliveryBook.RECHECK_MS);
    }

    @Override
    public int deliveryChests() {
        return book == null ? 1 : Math.max(1, book.chests());
    }

    long now = 1_000_000;
    boolean gate = true, deliverySet = true, deliveryUnreachable;
    boolean rereading;
    int staleCount;
    /** Shulkers that leave the inventory but never show up in the delivery container. */
    boolean loseDeliveries;
    int deliveryCapacity = 54;
    boolean failBuilds;
    /** Keys the plan counts but the run refuses to take (1.7.1's planner/runner disagreement): the shulker comes out short. */
    final Set<String> runnerRefuses = new java.util.HashSet<>();
    /** Times the host was told an order is being planned. */
    int plannings;

    /** Kit units the flow asked to have emptied back. */
    final List<Unit> emptied = new ArrayList<>();

    @Override
    public void kitEmptyBack(Unit unit) {
        emptied.add(unit);
    }

    /** The kit plan the sim hands the flow for an order with kit lines (kit orders and restock tests). */
    KitPlanner.@Nullable Plan kitPlan;

    @Override
    public KitPlanner.@Nullable Plan planKit(Order order, int emptiesTaken) {
        return kitPlan;
    }

    @Override
    public void planning() {
        plannings++;
    }

    // Crafting (1.8.0): what crafting may take, by item id; how a trip goes ("ok", "closed", "fail"); empties crafted for bins
    boolean craftOn, tableSet = true;
    final Map<String, Long> craftStock = new HashMap<>();
    String craftTrip = "ok";
    int spareEmpties, craftTrips, craftedTotal;
    /** Stacks the sim put back after a craft (what was gathered and not used). */
    int putBack;

    OrderSim craftStock(String id, long n) {
        craftStock.merge(id, n, Long::sum);
        return this;
    }

    @Override
    public boolean craftingOn() {
        return craftOn;
    }

    @Override
    public boolean emptyAvailable(Unit unit) {
        if (!craftOn) return true;
        return spareEmpties > 0 || empties.values().stream().mapToInt(Integer::intValue).sum() > 0;
    }

    /** The pool for a craft: what crafting may take, less what the remaining units take (CraftPool.residual). */
    Map<String, Long> craftPool() {
        Map<String, Long> loose = new HashMap<>(craftStock);
        CraftPool.Stock stock = new CraftPool.Stock(loose, Map.of(), Map.of(), Map.of());
        List<Unit> rest = new ArrayList<>(flow.units().subList(Math.min(flow.index(), flow.units().size()), flow.units().size()));
        return CraftPool.residual(stock, rest);
    }

    CraftPlanner.Limits limits() {
        return new CraftPlanner.Limits(tableSet, 128, 27 - flow.craftedCount(), 54, Set.of());
    }

    int craftable(List<Unit> units, int upTo) {
        if (!craftOn) return 0;
        CraftPool.Stock stock = new CraftPool.Stock(new HashMap<>(craftStock), Map.of(), Map.of(), Map.of());
        return CraftPlanner.maxCraftable(TestRecipes.table(), OrderFlow.PLAIN_SHULKER, upTo, CraftPool.residual(stock, units), limits());
    }

    @Override
    public @Nullable String craftProblem(Unit unit) {
        CraftPlanner.Plan p = CraftPlanner.plan(TestRecipes.table(), OrderFlow.PLAIN_SHULKER, 1, craftPool(), limits());
        return p.ok() ? null : p.refusal();
    }

    /** key to (variant to count) */
    final Map<String, Map<String, Integer>> loose = new TreeMap<>();
    final Map<String, Integer> maxStack = new HashMap<>();
    final Map<String, Integer> packed = new HashMap<>();
    /** Loose stacks inside shulkers in the stash: key to (variant to count). A second source, after the loose stacks. */
    final Map<String, Map<String, Integer>> shulkerStock = new TreeMap<>();
    /** Source shulkers opened for an order (each is put back where it came from). */
    int sourcesOpened;
    final Map<String, Integer> empties = new HashMap<>();
    final List<Carried> carried = new ArrayList<>();
    final List<Carried> delivery = new ArrayList<>();
    final List<String> said = new ArrayList<>();
    final List<String> phases = new ArrayList<>();

    OrderFlow.Saved saved;
    final OrderFlow flow = new OrderFlow(this, () -> now);

    OrderSim stock(String key, String variant, int count, int max) {
        loose.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(variant, count, Integer::sum);
        maxStack.put(key, max);
        return this;
    }

    OrderSim inShulker(String key, String variant, int count, int max) {
        shulkerStock.computeIfAbsent(key, k -> new LinkedHashMap<>()).merge(variant, count, Integer::sum);
        maxStack.put(key, max);
        return this;
    }

    // Host

    @Override
    public boolean gate() {
        return gate;
    }

    @Override
    public boolean rereading() {
        return rereading;
    }

    @Override
    public int staleCount() {
        return staleCount;
    }

    @Override
    public boolean deliverySet() {
        return deliverySet;
    }

    /** Set when the delivery container is in another dimension now. */
    boolean otherDimension;
    /** Whether anything runs to notice a park flag. */
    boolean active = true;
    /** The container is looked at, but the confirming look never happens (the bot goes offline right after the click). */
    boolean deferConfirm;
    String pauseText = "test pause";

    @Override
    public String deliveryProblem() {
        if (otherDimension) return "the delivery container is in another dimension (the_nether)";
        return deliveryUnreachable ? "couldn't reach the delivery container" : null;
    }

    @Override
    public String pauseReason() {
        return pauseText;
    }

    @Override
    public boolean machineRunning() {
        return active;
    }

    boolean matches(Unit u, Carried c) {
        return switch (u.kind) {
            case Bin -> c.kind == Unit.Kind.Bin && c.content.equals(BinTracker.lotTotals(u.lots));
            default -> c.kind == u.kind && c.key.equals(u.key);
        };
    }

    @Override
    public int finishedSlot(Unit unit) {
        for (int i = 0; i < carried.size(); i++) if (matches(unit, carried.get(i))) return i;
        return -1;
    }

    @Override
    public Map<String, Integer> contentOf(Unit unit, int slot) {
        Carried c = carried.get(slot);
        return c.kind == Unit.Kind.Bin ? new HashMap<>(c.content) : Map.of(c.key, 1);
    }

    @Override
    public boolean canFetch(Unit unit) {
        return unit.kind == Unit.Kind.Shulker ? packed.getOrDefault(unit.key, 0) > 0 : empties.getOrDefault(unit.key, 0) > 0;
    }

    // Crafting for orders (1.15.0): the recipe table the bot has (null: none), the cap, the plain stock craft orders may take, what is carried of
    // crafted items (the loads for bins to come and the surplus), and the free carry slots a chunk may use
    @Nullable Recipes craftTable;
    int craftMaxFills = 2048, freeSlots = 27, craftChunks;
    final Map<String, Long> craftStash = new HashMap<>(), craftCarry = new HashMap<>();
    /** How a chunk goes: "ok", or a failure reason the sim reports with whatever was made ("short" makes half). */
    String chunkTrip = "ok";
    /** Per item: the stack size (64 if not listed). */
    final Map<String, Integer> craftStack = new HashMap<>();

    OrderSim craftOrders(Recipes table) {
        this.craftTable = table;
        return this;
    }

    @Override
    public @Nullable Recipes craftTable() {
        return craftTable;
    }

    @Override
    public int craftMaxFills() {
        return craftMaxFills;
    }

    @Override
    public int maxStack(String id) {
        return craftStack.getOrDefault(id, 64);
    }

    /** What is carried of crafted items beyond the loads still waiting for their bin. */
    Map<String, Long> craftSurplus() {
        Map<String, Long> out = new HashMap<>(craftCarry);
        for (Unit u : flow.units()) {
            if (u.built || u.have) continue;
            for (Order.Lot lot : u.lots) if (lot.crafted()) out.merge(lot.key(), (long) -lot.count(), Long::sum);
        }
        out.values().removeIf(v -> v <= 0);
        return out;
    }

    @Override
    public Map<String, Long> craftCarried() {
        return craftSurplus();
    }

    /** What of the ended order's crafted leftovers is still carried (the put-back is not over while it is not empty). */
    final Map<String, Long> stillCarried = new HashMap<>();

    @Override
    public boolean craftReturning() {
        return !stillCarried.isEmpty();
    }

    @Override
    public Map<String, Long> craftStillCarried() {
        return stillCarried;
    }

    /** One chunk of crafting the way the sorter does it: the same chooser, then the stash and the carry change as the plan says. */
    private void craftChunk(Unit u) {
        last = "craftloads";
        int idx = flow.nextCraftLot(u);
        CraftLedger ledger = flow.craftLedger();
        Order.Lot lot = u.lots.get(idx);
        String key = Recipes.ns(lot.key());
        flow.craftLoadsStarted(u);
        ledger.setSurplus(craftSurplus());
        CraftBatch.Choice c = CraftBatch.choose(ledger, key, lot.count(), freeSlots, ledger.maxFills() - ledger.fills(), this::stackOfId, need -> need.size());
        craftChunks++;
        if (!c.ok()) {
            flow.craftLotShort(u, idx, c.reason());
            return;
        }
        for (var e : c.plan().fromStock().entrySet()) {
            if (craftStash.getOrDefault(e.getKey(), 0L) < e.getValue()) {
                flow.craftLotShort(u, idx, CraftOrder.INGREDIENTS_SHORT);
                return;
            }
        }
        if (!chunkTrip.equals("ok")) {
            if (chunkTrip.equals("table")) {
                flow.craftAbandon(CraftOrder.TABLE_BLOCKED);
                return;
            }
            flow.craftChunk(u, idx, 0, 0, Map.of(), chunkTrip);
            return;
        }
        Map<String, Long> have = new HashMap<>(craftCarry);
        for (var e : c.plan().fromStock().entrySet()) {
            craftStash.merge(e.getKey(), -e.getValue(), Long::sum);
            have.merge(e.getKey(), e.getValue(), Long::sum);
        }
        for (CraftPlanner.Step s : c.plan().steps()) {
            for (var use : s.uses().entrySet()) have.merge(use.getKey(), -use.getValue(), Long::sum);
            have.merge(s.recipe().result(), s.made(), Long::sum);
        }
        have.values().removeIf(v -> v <= 0);
        craftCarry.clear();
        craftCarry.putAll(have);
        flow.craftChunk(u, idx, c.count(), c.plan().fills(), c.plan().fromStock(), null);
    }

    private int stackOfId(String id) {
        return craftStack.getOrDefault(id, 64);
    }

    @Override
    public StockPlanner.Plan plan(Order order) {
        return plan(order, List.of());
    }

    @Override
    public StockPlanner.Plan plan(Order order, List<Order.Lot> craftLots) {
        Map<String, Long> available = new HashMap<>();
        Map<String, List<StockPlanner.Var>> variants = new HashMap<>();
        for (var e : loose.entrySet()) {
            List<StockPlanner.Var> list = new ArrayList<>();
            long total = 0;
            for (var v : e.getValue().entrySet()) {
                list.add(new StockPlanner.Var(v.getKey(), v.getValue()));
                total += v.getValue();
            }
            list.sort((a, b) -> Long.compare(b.count, a.count));
            available.put(e.getKey(), total);
            variants.put(e.getKey(), list);
        }
        Map<String, Long> shulkerAvailable = new HashMap<>();
        Map<String, List<StockPlanner.Var>> shulkerVariants = new HashMap<>();
        for (var e : shulkerStock.entrySet()) {
            List<StockPlanner.Var> list = new ArrayList<>();
            long total = 0;
            for (var v : e.getValue().entrySet()) {
                list.add(new StockPlanner.Var(v.getKey(), v.getValue()));
                total += v.getValue();
            }
            list.sort((a, b) -> Long.compare(b.count, a.count));
            shulkerAvailable.put(e.getKey(), total);
            shulkerVariants.put(e.getKey(), list);
        }
        StockPlanner.Survey survey = new StockPlanner.Survey(available, maxStack, Set.of(), Set.of(), variants, Map.of(), shulkerAvailable, shulkerVariants);
        StockPlanner.Plan first = StockPlanner.plan(order, survey, empties, k -> k, craftLots);
        if (!craftOn) return first;
        // As the sorter does: plain empty lines the stash can't give are counted as craftable, after the bins' own needs
        List<Unit> bins = first.units().stream().filter(u -> u.kind == Unit.Kind.Bin).toList();
        int asked = 0;
        for (Order.Line l : order.lines) if (OrderFlow.PLAIN_SHULKER.equals(l.key())) asked += l.count();
        int given = (int) first.units().stream().filter(u -> u.kind == Unit.Kind.Empty && OrderFlow.PLAIN_SHULKER.equals(u.key)).count();
        int total = empties.values().stream().mapToInt(Integer::intValue).sum();
        int emptyUnits = (int) first.units().stream().filter(u -> u.kind == Unit.Kind.Empty).count();
        int binShort = Math.max(0, bins.size() - (total - emptyUnits));
        int c = craftable(bins, asked - given + binShort);
        int forLines = Math.min(asked - given, Math.max(0, c - binShort));
        if (forLines <= 0) return first;
        Map<String, Integer> more = new HashMap<>(empties);
        more.merge(OrderFlow.PLAIN_SHULKER, forLines, Integer::sum);
        planCrafted = forLines;
        return StockPlanner.plan(order, survey, more, k -> k, craftLots);
    }

    /** Plain empty shulkers the last plan counted as craftable. */
    int planCrafted;

    @Override
    public @Nullable String checkEmpties(List<Unit> remaining) {
        int bins = 0;
        for (Unit u : remaining) if (u.kind == Unit.Kind.Bin) bins++;
        int total = empties.values().stream().mapToInt(Integer::intValue).sum() + spareEmpties;
        total += craftable(remaining, bins + (int) remaining.stream().filter(u -> u.kind == Unit.Kind.Empty).count());
        int asked = (int) remaining.stream().filter(u -> u.kind == Unit.Kind.Empty).count();
        return bins > total - asked ? "need " + (bins + asked) + " empty shulkers, have " + total : null;
    }

    @Override
    public void save(OrderFlow.Saved s) {
        saved = s;
    }

    @Override
    public void clearSaved(String id) {
        if (saved != null && saved.id.equals(id)) saved = null;
    }

    @Override
    public OrderFlow.@Nullable Saved saved(String id) {
        return saved != null && saved.id.equals(id) ? saved : null;
    }

    @Override
    public @Nullable String savedId() {
        return saved == null ? null : saved.id;
    }

    @Override
    public void say(String text) {
        said.add(text);
    }

    @Override
    public @Nullable String namingProblem(Unit unit) {
        if (!anvil) {
            // 1.8.1: with none set, one standing in the stash area is found, else one from the stash put down
            switch (com.porter.qwertestash.stash.AnvilFinder.decide(false, anvilStanding, anvilInStock)) {
                case FOUND -> anvilHow = "found";
                case PLACE -> anvilHow = "placed";
                default -> {
                    return noAnvilReason;
                }
            }
            anvil = true;
        }
        if (xp < 1) return AnvilNamer.TOO_FEW_LEVELS;
        return null;
    }

    /** Shulker handling is paused (no shulker may be put down): why, or null. */
    String buildPause;

    @Override
    public @Nullable String buildPauseReason() {
        return buildPause;
    }

    @Override
    public @Nullable String nameOn(Unit unit, int slot) {
        return slot >= 0 && slot < carried.size() ? carried.get(slot).name() : null;
    }

    // The sorter's part

    /** What the host did for an intent, for the tests. */
    String last = "";

    /** Runs one intent the way the sorter would. @return false when the flow had nothing to do */
    boolean step() {
        OrderFlow.Intent intent = flow.next();
        phases.add(flow.phase());
        if (intent instanceof OrderFlow.Idle) return false;
        if (intent instanceof OrderFlow.Build b) {
            Unit u = b.unit();
            last = "build";
            flow.noteBuildStart(u);
            if (!runnerRefuses.isEmpty() && !failBuilds) {
                // The plan counted these keys, the run won't take them: what it can take is packed, the shulker given up on
                List<Order.Lot> can = new ArrayList<>(), cannot = new ArrayList<>();
                for (Order.Lot lot : u.lots) (runnerRefuses.contains(lot.key()) ? cannot : can).add(lot);
                if (!cannot.isEmpty()) {
                    if (!can.isEmpty() && consume(can)) {
                        carried.add(new Carried(Unit.Kind.Bin, "", BinTracker.lotTotals(can)));
                        flow.builtShort(u, can, "not enough left to pack");
                    }
                    else flow.buildFailed(u, "not enough left to pack", false);
                    return true;
                }
            }
            if (failBuilds || !consume(u.lots)) {
                flow.buildFailed(u, "not enough left to pack", false);
                return true;
            }
            // With crafting on the sim keeps count of the empty shulkers bins use
            if (craftOn) {
                if (spareEmpties > 0) spareEmpties--;
                else {
                    String any = empties.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).findFirst().orElse(null);
                    if (any == null) {
                        flow.buildFailed(u, "no empty shulker", false);
                        return true;
                    }
                    empties.merge(any, -1, Integer::sum);
                }
            }
            carried.add(new Carried(Unit.Kind.Bin, "", BinTracker.lotTotals(u.lots)));
            flow.built(u);
            return true;
        }
        if (intent instanceof OrderFlow.CraftLoads cl) {
            craftChunk(cl.unit());
            return true;
        }
        if (intent instanceof OrderFlow.Craft c) {
            Unit u = c.unit();
            last = "craft";
            craftTrips++;
            flow.craftStarted(u);
            CraftPlanner.Plan p = CraftPlanner.plan(TestRecipes.table(), OrderFlow.PLAIN_SHULKER, 1, craftPool(), limits());
            if (!p.ok()) {
                flow.craftFailed(u, p.refusal(), false);
                return true;
            }
            switch (craftTrip) {
                case "ok" -> {
                    p.fromStock().forEach((k, v) -> craftStock.merge(k, -v, Long::sum));
                    if (u.kind == Unit.Kind.Empty) carried.add(new Carried(Unit.Kind.Empty, OrderFlow.PLAIN_SHULKER, Map.of()));
                    else spareEmpties++;
                    craftedTotal++;
                    flow.crafted(u, 1);
                }
                case "closed" -> {
                    putBack++;
                    flow.craftFailed(u, CraftTrip.CLOSED, true);
                }
                default -> {
                    putBack++;
                    flow.craftFailed(u, CraftTrip.NO_RESULT, false);
                }
            }
            return true;
        }
        if (intent instanceof OrderFlow.Fetch f) {
            Unit u = f.unit();
            last = "fetch";
            Map<String, Integer> from = u.kind == Unit.Kind.Shulker ? packed : empties;
            from.merge(u.key, -1, Integer::sum);
            carried.add(new Carried(u.kind, u.key, Map.of()));
            return true;
        }
        if (intent instanceof OrderFlow.Name n) {
            last = "name";
            anvilTrips++;
            flow.namingStarted(n.unit());
            switch (anvilTrip) {
                case "ok" -> {
                    carried.set(n.slot(), carried.get(n.slot()).named(n.name()));
                    named.add(n.name());
                    xp--;
                    if (n.unit().kind != Unit.Kind.Bin) n.unit().namedKey = n.unit().key + "~named";
                    flow.named(n.unit());
                }
                case "timeout" -> flow.nameFailed(n.unit(), AnvilNamer.NO_RESULT, false);
                case "closed" -> flow.nameFailed(n.unit(), AnvilNamer.CLOSED, true);
                default -> flow.nameFailed(n.unit(), AnvilNamer.TOO_FEW_LEVELS, false);
            }
            return true;
        }
        if (intent instanceof OrderFlow.Expand x) {
            last = "expand";
            if (refuseStarts) return true;
            flow.expandStarted(x.unit());
            expansions++;
            DeliveryBook.Entry top = book.topRow().get(0);
            List<DeliveryBook.Pos> up = new ArrayList<>();
            for (DeliveryBook.Pos p : top.parts()) up.add(new DeliveryBook.Pos(p.x(), p.y() + 1, p.z()));
            book.add(up);
            return true;
        }
        if (intent instanceof OrderFlow.Recheck r) {
            last = "recheck";
            if (refuseStarts) return true;
            rechecks++;
            DeliveryBook.Entry e = book.find(r.at());
            int held = 0;
            for (DeliveryBook.Pos q : e.parts()) held = Math.max(held, inChest.getOrDefault(q, 0));
            if (held < chestCap) book.markRoom(e, now);
            else book.markFull(e, now);
            return true;
        }
        if (intent instanceof OrderFlow.Deliver d) {
            Unit u = d.unit();
            last = d.verify() ? "verify" : "deliver";
            if (d.verify()) {
                // A fresh look at the container
                long count = delivery.stream().filter(c -> matches(u, c)).count();
                if (count > u.base) flow.confirmed();
                return true;
            }
            u.base = (int) delivery.stream().filter(c -> matches(u, c)).count();
            flow.deliveryStarted(u);
            if (book != null) {
                DeliveryBook.Entry chest = book.firstUsable();
                DeliveryBook.Pos at = chest.first();
                if (inChest.getOrDefault(at, 0) >= chestCap) {
                    book.markFull(chest, now);
                    flow.deliveryFull();
                    return true;
                }
                inChest.merge(at, 1, Integer::sum);
            }
            else if (delivery.size() >= deliveryCapacity) {
                flow.deliveryFull();
                return true;
            }
            Carried c = carried.remove(d.slot());
            if (!loseDeliveries) delivery.add(c);
            // The real host sees the container hold more than before
            if (!loseDeliveries && !deferConfirm) flow.confirmed();
            return true;
        }
        return false;
    }

    private boolean consume(List<Order.Lot> lots) {
        // Crafted loads are stacks in hand: they leave the carry, not the stash
        Map<String, Long> carry = new HashMap<>(craftCarry);
        List<Order.Lot> fromStash = new ArrayList<>();
        for (Order.Lot lot : lots) {
            if (!lot.crafted()) {
                fromStash.add(lot);
                continue;
            }
            if (carry.getOrDefault(lot.key(), 0L) < lot.count()) return false;
            carry.merge(lot.key(), (long) -lot.count(), Long::sum);
        }
        lots = fromStash;
        craftCarry.clear();
        carry.values().removeIf(v -> v <= 0);
        craftCarry.putAll(carry);
        return consumeStash(lots);
    }

    private boolean consumeStash(List<Order.Lot> lots) {
        Map<String, Map<String, Integer>> copy = new HashMap<>();
        for (var e : loose.entrySet()) copy.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        Map<String, Map<String, Integer>> inside = new HashMap<>();
        for (var e : shulkerStock.entrySet()) inside.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
        int opened = 0;
        for (Order.Lot lot : lots) {
            Map<String, Integer> variants = copy.get(lot.key());
            String variant = lot.variant();
            if (variant == null) return false;
            // Loose stacks first, as many as there are; the rest of the load out of a shulker
            int fromLoose = variants == null ? 0 : Math.min(lot.count(), variants.getOrDefault(variant, 0));
            if (fromLoose > 0) variants.merge(variant, -fromLoose, Integer::sum);
            int rest = lot.count() - fromLoose;
            if (rest == 0) continue;
            Map<String, Integer> sh = inside.get(lot.key());
            if (sh == null || sh.getOrDefault(variant, 0) < rest) return false;
            sh.merge(variant, -rest, Integer::sum);
            opened++;
        }
        shulkerStock.clear();
        shulkerStock.putAll(inside);
        sourcesOpened += opened > 0 ? 1 : 0;
        loose.clear();
        loose.putAll(copy);
        return true;
    }

    /** Runs until the order ends (or the step budget does). @return the steps taken */
    int run(int budget) {
        int n = 0;
        while (n < budget && flow.running()) {
            step();
            n++;
            now += 50;
        }
        return n;
    }

    Map<String, Integer> deliveredTotals() {
        Map<String, Integer> out = new HashMap<>();
        for (Carried c : delivery) {
            if (c.kind == Unit.Kind.Bin) c.content.forEach((k, v) -> out.merge(k, v, Integer::sum));
            else out.merge(c.key, 1, Integer::sum);
        }
        return out;
    }
}
