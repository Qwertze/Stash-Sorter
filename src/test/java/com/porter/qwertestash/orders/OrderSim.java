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
    record Carried(Unit.Kind kind, String key, Map<String, Integer> content) {
    }

    long now = 1_000_000;
    boolean gate = true, deliverySet = true, deliveryUnreachable;
    boolean rereading;
    int staleCount;
    /** Shulkers that leave the inventory but never show up in the delivery container. */
    boolean loseDeliveries;
    int deliveryCapacity = 54;
    boolean failBuilds;

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

    @Override
    public StockPlanner.Plan plan(Order order) {
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
        return StockPlanner.plan(order, new StockPlanner.Survey(available, maxStack, Set.of(), Set.of(), variants, Map.of(), shulkerAvailable, shulkerVariants), empties, k -> k);
    }

    @Override
    public @Nullable String checkEmpties(List<Unit> remaining) {
        int bins = 0;
        for (Unit u : remaining) if (u.kind == Unit.Kind.Bin) bins++;
        int total = empties.values().stream().mapToInt(Integer::intValue).sum();
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
            if (failBuilds || !consume(u.lots)) {
                flow.buildFailed(u, "not enough left to pack", false);
                return true;
            }
            carried.add(new Carried(Unit.Kind.Bin, "", BinTracker.lotTotals(u.lots)));
            flow.built(u);
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
            if (delivery.size() >= deliveryCapacity) {
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
