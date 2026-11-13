package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.EnchantRules;
import com.porter.qwerte.core.enchant.McData;
import com.porter.qwerte.core.enchant.Plan;
import com.porter.qwerte.core.enchant.PlanRequest;
import com.porter.qwerte.core.enchant.Planner;
import com.porter.qwerte.core.enchant.Stack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Test data for the enchant orders: a plan made with the real library, the order block around it, and the stacks a bot would hold for it. */
final class EnchantKit {
    private EnchantKit() {
    }

    static final String MC = "1.21.4", SWORD = "minecraft:diamond_sword", BOOK = "minecraft:enchanted_book", ANVIL = "minecraft:anvil";

    static Map<String, Integer> ench(Object... pairs) {
        Map<String, Integer> m = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    /** One book in stock: its enchantments and stored repair cost. */
    record Bk(Map<String, Integer> ench, int rc) {
    }

    /** Everything a test needs for one item: the order block, the server's plan, and the stacks that make the plan up. */
    record Case(EnchantOrder.Block block, EnchantOrder.ItemRef ref, List<EnchantItems.Held> held, Plan core) {
    }

    /**
     * A one-line, one-item order for {@code item} with the wanted enchantments, planned with the library against these books. The base is a fresh
     * {@code diamond_sword}-like item (key {@code item}). Take: the base, every book (one key each, #bk0, #bk1...), the plan's bottles, and {@code anvils} anvils.
     */
    static Case order(String item, Map<String, Integer> wanted, List<Bk> books, int anvils) {
        McData d = McData.load(MC);
        List<PlanRequest.Wanted> w = new ArrayList<>();
        for (var e : wanted.entrySet()) w.add(new PlanRequest.Wanted(e.getKey(), e.getValue()));
        // Identical books are one stock entry with a count (as the index groups them), so they share one key
        List<PlanRequest.Stock> stock = new ArrayList<>();
        Map<Bk, Integer> grouped = new LinkedHashMap<>();
        for (Bk b : books) grouped.merge(b, 1, Integer::sum);
        for (var e : grouped.entrySet()) stock.add(new PlanRequest.Stock(Stack.book(e.getKey().ench(), e.getKey().rc()), e.getValue()));
        Plan p = Planner.plan(d, new PlanRequest(McData.norm(item), w, List.of(new PlanRequest.Stock(Stack.fresh(), 1)), stock));
        if (!p.ok()) throw new IllegalStateException("no plan: " + p.code() + " " + p.message());
        List<EnchantOrder.Mat> mats = new ArrayList<>();
        List<EnchantItems.Held> held = new ArrayList<>();
        int slot = 9;
        for (Plan.Material m : p.materials()) {
            boolean book = !"base".equals(m.kind());
            // The key of a book is its index in the request's books (one entry each), so identical books would share a key; tests use distinct books
            String key = book ? BOOK + "#bk" + m.stockIndex() : item;
            mats.add(new EnchantOrder.Mat(book ? "book" : "base", key, m.enchants(), m.repairCost()));
            held.add(new EnchantItems.Held(slot++, key, book ? "enchanted_book" : McData.norm(item), 1, book, m.enchants(), m.repairCost(), false));
        }
        List<EnchantOrder.Step> steps = new ArrayList<>();
        for (Plan.Step s : p.steps()) steps.add(new EnchantOrder.Step(s.left(), s.right(), s.cost(), s.repairCost()));
        EnchantOrder.ItemPlan plan = new EnchantOrder.ItemPlan(mats, steps, p.levels(), p.extras(), new ArrayList<>(p.cursed()), p.points(), p.bottles());
        List<EnchantOrder.Take> take = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EnchantOrder.Mat m : mats) counts.merge(m.key(), 1, Integer::sum);
        for (var e : counts.entrySet()) take.add(new EnchantOrder.Take(e.getKey(), e.getValue()));
        take.add(new EnchantOrder.Take(EnchantOrder.BOTTLE_KEY, p.bottles()));
        if (anvils > 0) take.add(new EnchantOrder.Take(ANVIL, anvils));
        EnchantOrder.Line line = new EnchantOrder.Line(item, 1, new TreeMap<>(wanted), List.of(plan));
        EnchantOrder.Block block = new EnchantOrder.Block(1, MC, List.of(line), take, List.of(), false);
        return new Case(block, block.items().get(0), held, p);
    }

    /** The sword golden case: Sharpness V from two Sharpness IV books (two merge steps and the application). */
    static Case sharpnessV() {
        return order(SWORD, ench("sharpness", 5), List.of(new Bk(ench("sharpness", 4), 0), new Bk(ench("sharpness", 4), 0)), 2);
    }

    /** A sword with Sharpness V and Unbreaking III from three books, for a longer run. */
    static Case sharpnessAndUnbreaking() {
        return order(SWORD, ench("sharpness", 5, "unbreaking", 3),
            List.of(new Bk(ench("sharpness", 4), 0), new Bk(ench("sharpness", 4), 0), new Bk(ench("unbreaking", 3), 0)), 2);
    }

    /** The level the points amount to. */
    static int levelOf(double points) {
        int l = 0;
        while (EnchantRules.points(l + 1) <= points + 1e-9) l++;
        return l;
    }
}
