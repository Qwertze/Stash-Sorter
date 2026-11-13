package com.porter.qwerte.core.enchant;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enchantment table of one Minecraft version: ids, max levels, anvil multipliers, supported items and exclusive
 * (conflict) sets. Loaded from the bundled enchant-&lt;mc&gt;.json (game facts generated from the vanilla data pack,
 * see tools/gen_enchant_data.py).
 */
public final class McData {
    /** Minecraft versions with a bundled table. */
    public static final List<String> SUPPORTED = List.of("1.21.4", "1.21.11");

    /** One enchantment. {@code bookMult} applies when the right item is an enchanted book, {@code itemMult} otherwise. */
    public record Enchantment(String id, int maxLevel, int itemMult, int bookMult, String supportsTag, String exclusiveSet) {
    }

    private static final Map<String, McData> CACHE = new ConcurrentHashMap<>();

    private final String mc;
    private final String source;
    private final Map<String, Enchantment> enchants;
    private final Map<String, Set<String>> itemTags;
    private final Map<String, Set<String>> exclusiveSets;
    private final Set<String> curses;

    private McData(String mc, String source, Map<String, Enchantment> enchants, Map<String, Set<String>> itemTags,
                   Map<String, Set<String>> exclusiveSets, Set<String> curses) {
        this.mc = mc;
        this.source = source;
        this.enchants = enchants;
        this.itemTags = itemTags;
        this.exclusiveSets = exclusiveSets;
        this.curses = curses;
    }

    /** Loads (and caches) the table for a supported Minecraft version. */
    public static McData load(String mc) {
        if (mc == null || !SUPPORTED.contains(mc)) {
            throw new IllegalArgumentException("no enchantment table for Minecraft " + mc + " (have " + SUPPORTED + ")");
        }
        return CACHE.computeIfAbsent(mc, McData::read);
    }

    @SuppressWarnings("unchecked")
    private static McData read(String mc) {
        String name = "enchant-" + mc + ".json";
        try (InputStream in = McData.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            Map<String, Object> doc = (Map<String, Object>) MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Enchantment> ench = new LinkedHashMap<>();
            for (Object o : (List<Object>) doc.get("enchantments")) {
                Map<String, Object> e = (Map<String, Object>) o;
                String id = (String) e.get("id");
                if (((Long) e.get("max")) > 7) {
                    throw new IllegalStateException("level above 7 does not fit the packed search: " + id);
                }
                ench.put(id, new Enchantment(id, ((Long) e.get("max")).intValue(), ((Long) e.get("itemMult")).intValue(),
                        ((Long) e.get("bookMult")).intValue(), (String) e.get("supports"), (String) e.get("exclusive")));
            }
            return new McData((String) doc.get("mc"), (String) doc.get("source"), Collections.unmodifiableMap(ench),
                    sets((Map<String, Object>) doc.get("itemTags")), sets((Map<String, Object>) doc.get("exclusiveSets")),
                    curseSet((List<Object>) doc.get("curses")));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + name, e);
        }
    }

    private static Set<String> curseSet(List<Object> raw) {
        Set<String> s = new TreeSet<>();
        if (raw != null) {
            for (Object o : raw) {
                s.add((String) o);
            }
        }
        return Collections.unmodifiableSet(s);
    }

    /** True for the curses (Curse of Binding, Curse of Vanishing): enchantments nobody wants by accident. */
    public boolean isCurse(String id) {
        return curses.contains(norm(id));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> sets(Map<String, Object> raw) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            Set<String> s = new TreeSet<>();
            for (Object o : (List<Object>) e.getValue()) {
                s.add((String) o);
            }
            out.put(e.getKey(), Collections.unmodifiableSet(s));
        }
        return Collections.unmodifiableMap(out);
    }

    public String mc() {
        return mc;
    }

    /** Where the numbers came from. */
    public String source() {
        return source;
    }

    /** Strips a {@code minecraft:} prefix and lower-cases an id. */
    public static String norm(String id) {
        if (id == null) {
            return "";
        }
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
    }

    public Map<String, Enchantment> enchantments() {
        return enchants;
    }

    /** The enchantment with this id, or null. */
    public Enchantment get(String id) {
        return enchants.get(norm(id));
    }

    /** True when the item (an id like diamond_sword) takes this enchantment in an anvil. */
    public boolean supports(String enchantId, String itemKey) {
        Enchantment e = get(enchantId);
        if (e == null) {
            return false;
        }
        Set<String> items = itemTags.get(e.supportsTag());
        return items != null && items.contains(norm(itemKey));
    }

    /** All enchantment ids the item takes, in table order. */
    public List<String> enchantsFor(String itemKey) {
        List<String> out = new ArrayList<>();
        for (Enchantment e : enchants.values()) {
            if (supports(e.id(), itemKey)) {
                out.add(e.id());
            }
        }
        return out;
    }

    /** Items that take this enchantment, sorted. */
    public Set<String> itemsFor(String enchantId) {
        Enchantment e = get(enchantId);
        return e == null ? Set.of() : itemTags.getOrDefault(e.supportsTag(), Set.of());
    }

    /** True when the two different enchantments cannot share an item (a shared exclusive set). */
    public boolean conflicts(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        if (x.equals(y)) {
            return false;
        }
        return inExclusiveSetOf(x, y) || inExclusiveSetOf(y, x);
    }

    private boolean inExclusiveSetOf(String owner, String other) {
        Enchantment e = enchants.get(owner);
        if (e == null || e.exclusiveSet() == null) {
            return false;
        }
        Set<String> s = exclusiveSets.get(e.exclusiveSet());
        return s != null && s.contains(other);
    }

    /** Exclusive sets by name (members sorted). */
    public Map<String, Set<String>> exclusiveSets() {
        return exclusiveSets;
    }
}
