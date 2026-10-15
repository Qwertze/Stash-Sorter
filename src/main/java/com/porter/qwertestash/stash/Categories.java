package com.porter.qwertestash.stash;

import com.aquarius.mc.enchantment.EnchantmentData;
import com.aquarius.mc.enchantment.EnchantmentRegistry;
import com.aquarius.mc.item.ItemData;
import com.aquarius.mc.item.ItemRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * What players mean by the words they put on signs and type into searches. Maps everyday terms ("redstone stuff",
 * "pvp", "building blocks", "valuables") onto the items they describe, so signs and searches can be written the way
 * people actually talk. Ported from the Qwerte Meteor addon's stash-sorter.
 */
public final class Categories {
    private Categories() {
    }

    /**
     * How well a sign line describes an item. Higher wins: an exact name, then all the words of the line found in the
     * item's name, then a category the item belongs to, then a catch-all. 0 means the line doesn't describe it.
     */
    public static int signScore(String line, Item item) {
        return signScore(line, describe(item));
    }

    /**
     * Same as {@link #signScore(String, Item)}, for scoring one item against many lines: the item's normalised name and
     * words are worked out once, and each line's split and normalisation is remembered.
     */
    public static int signScore(String line, Described item) {
        int best = 0;
        for (Part part : prepared(line)) best = Math.max(best, partScore(part, item));
        return best;
    }

    public static Described describe(Item item) {
        return new Described(item);
    }

    /**
     * An item with the text derived from it, computed once and only when needed.
     */
    public static final class Described {
        private final Item item;
        private String name, id;
        private Set<String> words;

        private Described(Item item) {
            this.item = item;
        }

        private String name() {
            if (name == null) name = normalize(item.name);
            return name;
        }

        private String id() {
            if (id == null) id = normalize(item.id);
            return id;
        }

        private Set<String> words() {
            if (words == null) words = Categories.words(name() + " " + id() + " " + extraWords(item));
            return words;
        }
    }

    /**
     * A sign line part, normalised once. The full text as written ("ender chests") and the same with filler words like
     * "chest" or "of" taken out ("ender"), each with the keys it can be looked up by: both ways of undoing a plural
     * ("axes" is "axe" and "ax"), and with the spaces taken out ("e gaps" is "egap").
     */
    private record Part(String label, String full, List<String> labelKeys, List<String> fullKeys) {
        static Part of(String text) {
            String full = normalize(text);
            String label = stripFiller(full);
            return new Part(label, full, keys(label), label.equals(full) ? keys(label) : keys(full));
        }

        private static List<String> keys(String label) {
            if (label.isEmpty()) return List.of();
            Set<String> keys = new LinkedHashSet<>();
            keys.add(singularPhrase(label));
            keys.add(altPhrase(label));
            String compact = label.replace(" ", "");
            if (!compact.equals(label) && label.split(" ").length <= 3) {
                keys.add(singular(compact));
                keys.add(altWord(compact));
            }
            return List.copyOf(keys);
        }
    }

    /**
     * The first value any of the keys is registered under.
     */
    private static <T> T lookup(Map<String, T> map, List<String> keys) {
        for (String key : keys) {
            T found = map.get(key);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Whether the sign text as written means something on its own: a nickname, a category, a catch-all word or kit, or
     * words that are all found in the name of some item. Then it's read as written, and not with its filler words
     * dropped, which could cut "ender chests" down to "ender".
     */
    private static boolean wholeKnown(Part part) {
        if (part.full().isEmpty()) return false;
        Boolean known = WHOLE.get(part.full());
        if (known == null) {
            if (WHOLE.size() > 2048) WHOLE.clear();
            known = lookup(SHORTHANDS, part.fullKeys()) != null || lookup(CATEGORIES, part.fullKeys()) != null
                || CATCH_ALL.contains(part.full()) || KEEP_WHOLE.contains(part.fullKeys().getFirst()) || namesAnItem(part.full());
            WHOLE.put(part.full(), known);
        }
        return known;
    }

    private static final Map<String, Boolean> WHOLE = new ConcurrentHashMap<>();
    private static volatile List<Set<String>> itemWords;

    private static boolean namesAnItem(String label) {
        List<Set<String>> all = itemWords;
        if (all == null) {
            List<Set<String>> built = new ArrayList<>();
            for (ItemData item : ItemRegistry.REGISTRY.getKeyMap().values()) built.add(words(normalize(item.name())));
            all = itemWords = built;
        }
        for (Set<String> words : all) if (allIn(label, words)) return true;
        return false;
    }

    // Searches and commands can run on other threads than the one sorting
    private static final Map<String, List<Part>> PREPARED = new ConcurrentHashMap<>();

    private static List<Part> prepared(String line) {
        List<Part> parts = PREPARED.get(line);
        if (parts == null) {
            if (PREPARED.size() > 2048) PREPARED.clear();
            List<Part> built = new ArrayList<>();
            for (String part : splitList(line)) built.add(Part.of(part));
            parts = List.copyOf(built);
            PREPARED.put(line, parts);
        }
        return parts;
    }

    /**
     * How well a search query matches an item, 0 if it doesn't.
     */
    public static int searchScore(String query, Item item) {
        String q = normalize(query);
        if (q.isEmpty()) return 0;

        String name = normalize(item.name);
        String id = normalize(item.id);
        if (q.equals(name) || q.equals(id)) return 100;
        if (name.startsWith(q) || id.startsWith(q)) return 80;
        if (name.contains(q) || id.contains(q)) return 70;

        int score = partScore(q, item);
        return score == CATCH_ALL_SCORE ? 0 : score;
    }

    private static final int CATCH_ALL_SCORE = 1;

    private static final Set<String> KEEP_WHOLE = Set.of("kit", "pvp kit", "kit shulker", "mixed shulker", "whole shulker",
        "keep whole", "loot shulker", "unsorted shulker");

    /**
     * A sign line asking for mixed shulkers whole, like "kits", instead of having them repacked.
     */
    public static boolean isKeepWhole(String line) {
        for (String part : splitList(line)) {
            if (KEEP_WHOLE.contains(singularPhrase(stripFiller(normalize(part))))) return true;
        }
        return false;
    }

    /**
     * A sign line saying "anything goes here": misc, other, overflow and the like.
     */
    public static boolean isCatchAll(String line) {
        for (String part : splitList(line)) {
            if (CATCH_ALL.contains(stripFiller(normalize(part)))) return true;
        }
        return false;
    }

    private static int partScore(String part, Item item) {
        return partScore(Part.of(part), new Described(item));
    }

    /**
     * 100 an exact name, 60 the sign's nickname for it ("egaps", "obby"), 50 the sign's words all in its name, 40 a
     * category it belongs to, 1 a catch-all, 25 a loose category word of several. A sign that means something as it's
     * written is only ever read that way: a nickname or category that the item isn't in scores 0, and the loose words
     * are for signs that nothing else understood.
     */
    private static int partScore(Part part, Described described) {
        if (part.label().isEmpty()) return 0;

        // As written first ("ender chests"); only without its filler words when that means nothing
        boolean whole = wholeKnown(part);
        return whole ? score(part.full(), part.fullKeys(), described, false) : score(part.label(), part.labelKeys(), described, true);
    }

    private static int score(String label, List<String> keys, Described described, boolean loose) {
        Item item = described.item;
        if (label.equals(described.name()) || label.equals(described.id())) return 100;

        // Nicknames are the whole sign text, never part of a word
        Set<String> nicknamed = lookup(SHORTHANDS, keys);
        if (nicknamed != null && nicknamed.contains(item.id)) return 60;

        if (allIn(label, described.words())) return 50;

        Predicate<Item> category = lookup(CATEGORIES, keys);
        if (category != null) return category.test(item) ? 40 : 0;

        if (nicknamed != null) return 0;
        if (CATCH_ALL.contains(label)) return CATCH_ALL_SCORE;
        if (!loose) return 0;

        // A few loose words, like "pvp gear" or "farm crops": any category word that fits
        for (String word : label.split(" ")) {
            Predicate<Item> c = lookup(CATEGORIES, List.of(singular(word), altWord(word)));
            if (c != null && c.test(item)) return 25;
        }

        return 0;
    }

    // Text

    public static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ').replaceAll("[^a-z0-9*& ]", "").replaceAll("\\s+", " ").trim();
    }

    /**
     * "logs, planks & wood" is three things.
     */
    private static List<String> splitList(String line) {
        List<String> parts = new ArrayList<>();
        for (String part : line.toLowerCase(Locale.ROOT).split("[,&/+|;]| and | n ")) {
            if (!part.isBlank()) parts.add(part);
        }
        if (parts.isEmpty()) parts.add(line);
        return parts;
    }

    private static final Set<String> FILLER = Set.of("stuff", "things", "thing", "items", "item", "supplies", "supply",
        "storage", "the", "of", "for", "my", "all", "some", "assorted", "various", "general", "box", "chest", "barrel",
        "here", "only", "extra", "spare", "more");

    private static String stripFiller(String label) {
        StringBuilder out = new StringBuilder();
        for (String word : label.split(" ")) {
            if (word.isEmpty() || FILLER.contains(word)) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(word);
        }
        // "Storage" alone or "Items" alone still means something: keep the original if everything was filler
        return out.isEmpty() ? label : out.toString();
    }

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
        Map.entry("golden", "gold"), Map.entry("wooden", "wood"), Map.entry("stony", "stone"),
        Map.entry("leaf", "leave"), Map.entry("diamonds", "diamond"), Map.entry("netherrite", "netherite"),
        Map.entry("enderpearl", "pearl"), Map.entry("pearls", "pearl"), Map.entry("gapple", "golden apple"),
        Map.entry("xp", "experience"), Map.entry("exp", "experience")
    );

    private static Set<String> words(String text) {
        Set<String> set = new HashSet<>();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String w = singular(word);
            set.add(w);
            String syn = SYNONYMS.get(w);
            if (syn != null) set.add(syn);
        }
        return set;
    }

    /**
     * Words an item is known by beyond its name: its enchantments (stored ones too, for books) and its potion.
     */
    private static String extraWords(Item item) {
        StringBuilder out = new StringBuilder();
        for (String enchant : item.enchants) out.append(' ').append(enchant);
        if (item.potion != null) out.append(' ').append(item.potion);
        return normalize(out.toString());
    }

    /**
     * The item a shulker is mostly full of: whichever takes up the most slots, then the most items. Null for anything
     * that isn't a shulker with items in it.
     */
    public static Item dominantContent(Item shulker) {
        if (!shulker.isShulker()) return null;
        return mostCommon(shulker.contents);
    }

    /**
     * The item taking up the most slots, then the most items, or null if there's nothing.
     */
    public static Item mostCommon(List<Item> contents) {
        Map<String, int[]> tally = new HashMap<>();
        Map<String, Item> sample = new HashMap<>();
        for (Item item : contents) {
            if (item == null) continue;
            int[] t = tally.computeIfAbsent(item.id, i -> new int[2]);
            t[0]++;
            t[1] += item.count;
            sample.putIfAbsent(item.id, item);
        }

        String best = null;
        for (var entry : tally.entrySet()) {
            int[] t = entry.getValue();
            if (best == null || t[0] > tally.get(best)[0] || (t[0] == tally.get(best)[0] && t[1] > tally.get(best)[1])) best = entry.getKey();
        }
        return best == null ? null : sample.get(best);
    }

    private static boolean allIn(String label, Set<String> words) {
        for (String word : label.split(" ")) {
            if (word.isEmpty()) continue;
            if (!wordIn(singular(word), words) && !wordIn(altWord(word), words)) return false;
        }
        return true;
    }

    private static boolean wordIn(String w, Set<String> words) {
        String syn = SYNONYMS.get(w);
        return words.contains(w) || (syn != null && words.contains(syn));
    }

    /**
     * The other way of undoing a plural, only taking the "s" off: "axes" is "ax" by the usual rules, and "axe" this way.
     */
    private static String altWord(String word) {
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")) return word.substring(0, word.length() - 1);
        return word;
    }

    private static String altPhrase(String phrase) {
        StringBuilder out = new StringBuilder();
        for (String word : phrase.split(" ")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(altWord(word));
        }
        return out.toString();
    }

    public static String singular(String word) {
        if (word.endsWith("ies") && word.length() > 4) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("oes") && word.length() > 4) return word.substring(0, word.length() - 2);
        if (word.endsWith("ves") && word.length() > 4) return word.substring(0, word.length() - 3) + "f";
        if (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes")) return word.substring(0, word.length() - 2);
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us")) return word.substring(0, word.length() - 1);
        return word;
    }

    private static String singularPhrase(String phrase) {
        StringBuilder out = new StringBuilder();
        for (String word : phrase.split(" ")) {
            if (!out.isEmpty()) out.append(' ');
            out.append(singular(word));
        }
        return out.toString();
    }

    // Categories

    private static final Map<String, Set<String>> SHORTHANDS = new HashMap<>();

    /**
     * Short names players use for an item, mapped to the items they mean. Stored singular, so plurals work too.
     */
    private static void nick(String items, String... names) {
        Set<String> ids = Set.of(items.split(","));
        // A name given twice means both: "star" is a firework star and a nether star
        for (String name : names) {
            SHORTHANDS.merge(singularPhrase(normalize(name)), ids, (a, b) -> {
                Set<String> both = new HashSet<>(a);
                both.addAll(b);
                return Set.copyOf(both);
            });
        }
    }

    private static Set<String> nicknames(Part part) {
        Set<String> found = lookup(SHORTHANDS, part.labelKeys());
        return found != null ? found : lookup(SHORTHANDS, part.fullKeys());
    }

    static {
        nick("firework_rocket", "rocket", "firework", "fireworks", "rockets", "elytra rockets", "flight", "boost", "boosts");
        nick("firework_star", "firework star", "star");
        nick("end_crystal", "crystal", "end crystal", "crystals", "cry", "crys");
        nick("ender_pearl", "pearl", "ep", "epearl", "ender pearl");
        nick("ender_eye", "eye", "eye of ender", "eyes of ender", "ender eye");
        nick("obsidian,crying_obsidian", "obby", "obbie", "obsi", "obs");
        nick("golden_apple", "gap", "gapple", "golden apple", "gold apple", "gaps");
        nick("enchanted_golden_apple", "egap", "egapple", "god apple", "notch apple", "notch", "enchanted apple", "egaps");
        nick("totem_of_undying", "totem", "tot", "tots", "totems", "undying");
        nick("experience_bottle", "xp", "exp", "xp bottle", "exp bottle", "bottle", "experience", "bottle o enchanting", "xp bottles");
        nick("respawn_anchor", "anchor", "anchors");
        nick("glowstone", "glowstone", "anchor fuel");
        nick("cooked_beef", "steak", "steaks");
        nick("porkchop,cooked_porkchop", "pork", "porkchop", "chops");
        nick("cooked_beef,cooked_porkchop,cooked_chicken,cooked_mutton,cooked_rabbit,cooked_cod,cooked_salmon", "cooked", "cooked food", "cooked meat");
        nick("golden_carrot", "gold carrot", "golden carrot", "gcarrot", "gcarrots");
        nick("shulker_shell", "shell", "shulker shell");
        nick("cobblestone,cobbled_deepslate", "cobble", "cobbles");
        nick("netherite_scrap", "scrap", "netherite scrap");
        nick("ancient_debris", "debris", "ancient debris");
        nick("netherite_ingot", "netherite ingot", "neth", "neth ingot");
        nick("slime_ball,slime_block", "slime", "slimeball", "slimes");
        nick("honey_bottle,honey_block,honeycomb", "honey");
        nick("ender_chest", "echest", "e chest", "enderchest", "ender chest");
        nick("diamond", "dia", "dias", "diamonds", "diamond gem");
        nick("emerald", "em", "ems", "emmy", "emmies", "emerald");
        nick("lapis_lazuli", "lapis", "lapis lazuli");
        nick("redstone", "redstone dust", "dust");
        nick("gunpowder", "gp", "powder", "sulphur", "sulfur");
        nick("blaze_rod", "blaze rod", "blazerod");
        nick("tnt", "tnt", "dynamite");
        nick("cobweb", "web", "webs", "cobweb");
        nick("bone_meal", "bonemeal", "bm");
        nick("wind_charge", "wind charge", "charge");
        nick("trident", "trident", "trident");
        nick("elytra", "elytra", "elytras", "wing", "wings");
        nick("shield", "shield", "shields");
        nick("name_tag", "nametag", "name tag", "tag");
        nick("saddle", "saddle");
        nick("heart_of_the_sea", "heart", "hots");
        nick("nether_star", "star", "nether star");
        nick("dragon_egg", "egg", "dragon egg");
        nick("beacon", "beacon");
        // Not plain "book": that is the book, the written book and the enchanted book alike
        nick("enchanted_book", "ebook", "e book", "enchanted book", "ench book", "enchant book", "enchantment book", "enchanted books");
        nick("splash_potion", "splash", "splash potion");
        nick("lingering_potion", "lingering", "lingering potion");
        nick("chorus_fruit", "chorus", "chorus fruit");
        nick("sea_lantern", "sea lantern", "sealantern");
        nick("hay_block", "hay", "hay bale");
        nick("iron_ingot", "iron", "iron ingot");
        nick("gold_ingot", "gold", "gold ingot");
        nick("copper_ingot", "copper", "copper ingot");

        // More slang and shorthand. Each name is the whole text of a sign line (or of one part of "a, b & c"), plural or
        // not, and short ones like "ec" or "rs" are only ever that: nothing is matched inside a longer word
        nick("ender_chest", "ec", "ender chests", "echests");
        nick("golden_apple", "normal gapple", "regular gapple", "plain gapple", "gold gapple");
        nick("enchanted_golden_apple", "enchanted gapple", "enchanted gap", "enchanted golden apple", "enchanted gold apple",
            "ench gapple", "ench gap", "ench golden apple", "god gapple", "notch gapple", "ngap", "ngapple", "op gapple");
        nick("ender_pearl", "e pearl", "enderpearl", "ender pearls");
        nick("totem_of_undying", "totem of undying", "totem undying");
        nick("end_crystal", "end crystals", "ecrystal");
        nick("experience_bottle", "bottles o enchanting", "bottle o' enchanting", "exp bottles", "xpb");
        nick("respawn_anchor", "rsa", "respawn anchor", "respawn anchors");
        nick("obsidian", "obsid", "obi");
        nick("crying_obsidian", "cryobby", "cry obby", "crying obby", "cryobsidian", "crying obsidian");
        nick("elytra", "ely", "elys", "elytron");
        nick("firework_rocket", "fw", "fws", "firework rocket", "firework rockets");
        nick("diamond_block", "dblock", "dia block", "diamond block");
        nick("iron_block", "iblock", "iron block");
        nick("gold_block", "gblock", "gold block");
        nick("emerald_block", "eblock", "emerald block", "em block");
        nick("lapis_block", "lapis block", "lblock");
        nick("redstone", "rs", "redstone dust");
        nick("redstone_block", "rs block", "redstone block", "rblock");
        nick("coal_block", "coal block", "cblock");
        nick("netherite_ingot", "netherite", "netherite ingots");
        nick("netherite_block", "neth block", "netherite block", "nblock");
        nick("netherite_scrap", "neth scrap");
        nick("netherite_pickaxe", "neth pick", "nethpick", "neth pickaxe", "n pick", "npick", "netherite pick");
        nick("netherite_sword", "neth sword", "nsword", "n sword");
        nick("netherite_axe", "neth axe", "naxe", "n axe");
        nick("netherite_shovel", "neth shovel", "nshovel", "n shovel");
        nick("netherite_hoe", "neth hoe", "nhoe", "n hoe");
        nick("netherite_helmet", "neth helmet", "neth helm", "nhelm", "n helmet");
        nick("netherite_chestplate", "neth chestplate", "neth chest", "n chest", "nchest");
        nick("netherite_leggings", "neth leggings", "neth legs", "n legs", "nlegs");
        nick("netherite_boots", "neth boots", "n boots", "nboots");
        nick("golden_carrot", "golden carrots", "gold carrots", "gcarrots");
        nick("cooked_beef", "cooked beef", "cooked steak");
        nick("chorus_fruit", "chorus fruits", "choruses");
        nick("spawner", "spawners", "mob spawner");
        nick("tnt", "tnts", "tnt block");
        nick("bedrock", "bedrock");
        nick("beacon", "beacons");
        nick("honey_bottle,honey_block,honeycomb", "honey stuff");
    }

    private static final Set<String> CATCH_ALL = Set.of("misc", "miscellaneous", "other", "others", "*", "overflow",
        "everything", "anything", "random", "rest", "leftover", "leftovers", "unsorted", "mixed", "general");

    private static final Map<String, Predicate<Item>> CATEGORIES = new HashMap<>();
    private static final List<Label> LABELS = new ArrayList<>();

    /**
     * Registers a category under all its names. Names are stored singular, so "tools" and "tool" both work.
     */
    private static void add(Predicate<Item> test, String... names) {
        for (String name : names) CATEGORIES.put(singularPhrase(normalize(name)), test);
    }

    private static Predicate<Item> ids(String... parts) {
        return stack -> {
            String id = stack.id;
            for (String part : parts) if (id.contains(part)) return true;
            return false;
        };
    }

    private static Predicate<Item> exact(String... names) {
        Set<String> set = Set.of(names);
        return stack -> set.contains(stack.id);
    }

    private static Predicate<Item> suffix(String... suffixes) {
        return stack -> {
            String id = stack.id;
            for (String s : suffixes) if (id.endsWith(s)) return true;
            return false;
        };
    }

    private static boolean shulkerWithItems(Item item) {
        return item.isShulker() && !item.contents.isEmpty();
    }

    private static boolean fullBlock(Item item) {
        return item.fullBlock;
    }

    private static boolean enchanted(Item item) {
        return !item.enchants.isEmpty() && !item.id.equals("enchanted_book");
    }

    static {
        Predicate<Item> shulker = Item::isShulker;
        Predicate<Item> pickaxe = s -> s.tags.contains("PICKAXES");
        Predicate<Item> axe = s -> s.tags.contains("AXES");
        Predicate<Item> shovel = s -> s.tags.contains("SHOVELS");
        Predicate<Item> hoe = s -> s.tags.contains("HOES");
        Predicate<Item> sword = s -> s.tags.contains("SWORDS");
        Predicate<Item> tools = pickaxe.or(axe).or(shovel).or(hoe).or(exact("shears", "flint_and_steel", "fishing_rod", "brush"));
        Predicate<Item> armor = suffix("_helmet", "_chestplate", "_leggings", "_boots").or(exact("elytra", "turtle_helmet", "shield", "wolf_armor"));
        Predicate<Item> weapons = sword.or(axe).or(exact("bow", "crossbow", "trident", "mace")).or(suffix("arrow"));
        Predicate<Item> food = s -> s.food;
        Predicate<Item> ores = ids("_ore", "raw_", "ancient_debris");
        Predicate<Item> ingots = suffix("_ingot", "_nugget").or(exact("netherite_scrap", "copper_ingot"));
        Predicate<Item> gems = exact("diamond", "emerald", "lapis_lazuli", "amethyst_shard", "quartz", "prismarine_crystals", "echo_shard");
        Predicate<Item> storageBlocks = exact("iron_block", "gold_block", "diamond_block", "emerald_block", "lapis_block", "redstone_block",
            "netherite_block", "copper_block", "coal_block", "raw_iron_block", "raw_gold_block", "raw_copper_block", "amethyst_block", "quartz_block");
        Predicate<Item> valuables = ids("diamond", "netherite", "emerald").or(exact("totem_of_undying", "elytra", "nether_star", "beacon",
            "enchanted_golden_apple", "dragon_egg", "dragon_head", "trident", "heart_of_the_sea", "shulker_shell", "enchanted_book", "mace",
            "heavy_core", "conduit", "ancient_debris")).or(shulker);
        Predicate<Item> redstone = exact("redstone", "repeater", "comparator", "piston", "sticky_piston", "observer", "hopper", "dropper",
            "dispenser", "lever", "daylight_detector", "target", "tripwire_hook", "redstone_torch", "redstone_lamp", "note_block", "tnt",
            "slime_block", "honey_block", "crafter", "sculk_sensor", "calibrated_sculk_sensor", "lightning_rod", "trapped_chest", "redstone_block",
            "jukebox", "bell").or(suffix("_button", "_pressure_plate", "rail", "_door", "_trapdoor", "_fence_gate"));
        Predicate<Item> rails = suffix("rail");
        Predicate<Item> transport = suffix("_boat", "_raft").or(ids("minecart")).or(rails).or(exact("saddle", "elytra", "firework_rocket",
            "carrot_on_a_stick", "warped_fungus_on_a_stick", "lead")).or(suffix("_horse_armor"));
        Predicate<Item> light = ids("torch", "lantern", "froglight", "candle").or(exact("glowstone", "sea_lantern", "shroomlight",
            "redstone_lamp", "end_rod", "beacon", "jack_o_lantern", "glow_berries", "glow_lichen"));
        Predicate<Item> heads = suffix("_head", "_skull");
        Predicate<Item> decoration = exact("painting", "item_frame", "glow_item_frame", "flower_pot", "armor_stand", "decorated_pot",
            "chain", "bell", "lantern", "soul_lantern").or(suffix("_banner", "_carpet", "_candle", "candle", "_bed")).or(heads)
            .or(ids("pottery_sherd", "banner_pattern"));
        Predicate<Item> wool = suffix("_wool");
        Predicate<Item> dyes = suffix("_dye");
        Predicate<Item> glass = ids("glass").and(s -> !s.id.equals("glass_bottle"));
        Predicate<Item> concrete = ids("concrete");
        Predicate<Item> terracotta = ids("terracotta");
        Predicate<Item> colorful = wool.or(dyes).or(concrete).or(terracotta).or(suffix("_stained_glass", "_stained_glass_pane", "_carpet",
            "_bed", "_banner", "_candle", "_shulker_box"));
        Predicate<Item> logs = s -> s.tags.contains("LOGS");
        Predicate<Item> planks = s -> s.tags.contains("PLANKS");
        Predicate<Item> wood = logs.or(planks).or(ids("_wood", "_hyphae", "bamboo_block", "stripped_"))
            .or(s -> {
                String id = s.id;
                return (id.endsWith("_stairs") || id.endsWith("_slab") || id.endsWith("_fence")) && (id.startsWith("oak") || id.startsWith("spruce")
                    || id.startsWith("birch") || id.startsWith("jungle") || id.startsWith("acacia") || id.startsWith("dark_oak")
                    || id.startsWith("mangrove") || id.startsWith("cherry") || id.startsWith("pale_oak") || id.startsWith("bamboo")
                    || id.startsWith("crimson") || id.startsWith("warped"));
            });
        Predicate<Item> stone = s -> {
            String id = s.id;
            if (id.contains("redstone") || id.contains("glowstone") || id.contains("lodestone") || id.contains("grindstone") || id.contains("stonecutter")) return false;
            return s.block && (id.contains("stone") || id.contains("deepslate") || id.contains("andesite")
                || id.contains("diorite") || id.contains("granite") || id.contains("tuff") || id.contains("calcite") || id.contains("basalt")
                || id.contains("cobble"));
        };
        Predicate<Item> dirt = exact("dirt", "coarse_dirt", "rooted_dirt", "grass_block", "podzol", "mycelium", "mud", "muddy_mangrove_roots",
            "packed_mud", "moss_block", "pale_moss_block", "clay", "farmland", "dirt_path");
        Predicate<Item> sand = exact("sand", "red_sand", "gravel", "suspicious_sand", "suspicious_gravel").or(ids("sandstone"));
        Predicate<Item> saplings = ids("sapling", "propagule", "azalea");
        Predicate<Item> flowers = exact("dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "torchflower", "pitcher_plant", "pink_petals",
            "spore_blossom", "open_eyeblossom", "closed_eyeblossom", "wildflowers", "cactus_flower").or(suffix("_tulip"));
        Predicate<Item> seeds = suffix("_seeds").or(exact("pitcher_pod", "torchflower_seeds"));
        Predicate<Item> crops = exact("wheat", "carrot", "potato", "beetroot", "melon_slice", "melon", "pumpkin", "sugar_cane", "cocoa_beans",
            "nether_wart", "sweet_berries", "glow_berries", "cactus", "bamboo", "kelp", "hay_block", "chorus_fruit").or(seeds);
        Predicate<Item> plants = saplings.or(flowers).or(seeds).or(ids("leaves", "vine", "moss", "fern", "grass", "lily_pad", "mushroom",
            "fungus", "roots", "dripleaf", "sea_pickle", "seagrass", "coral", "bush", "cactus", "sugar_cane", "bamboo", "kelp", "hanging_roots"));
        Predicate<Item> mobDrops = exact("bone", "bone_meal", "string", "gunpowder", "spider_eye", "rotten_flesh", "ender_pearl", "slime_ball",
            "blaze_rod", "blaze_powder", "ghast_tear", "phantom_membrane", "feather", "leather", "rabbit_hide", "rabbit_foot", "ink_sac",
            "glow_ink_sac", "magma_cream", "shulker_shell", "prismarine_shard", "prismarine_crystals", "nautilus_shell", "turtle_scute",
            "scute", "armadillo_scute", "breeze_rod", "honeycomb", "egg", "wither_skeleton_skull", "nether_star", "dragon_breath");
        Predicate<Item> brewing = exact("potion", "splash_potion", "lingering_potion", "glass_bottle", "brewing_stand", "blaze_powder",
            "nether_wart", "fermented_spider_eye", "glistering_melon_slice", "magma_cream", "ghast_tear", "rabbit_foot", "dragon_breath",
            "phantom_membrane", "sugar", "glowstone_dust", "golden_carrot", "pufferfish", "turtle_helmet", "cauldron", "breeze_rod");
        Predicate<Item> potions = exact("potion", "splash_potion", "lingering_potion", "tipped_arrow");
        Predicate<Item> books = exact("book", "writable_book", "written_book", "enchanted_book", "bookshelf", "chiseled_bookshelf", "knowledge_book");
        Predicate<Item> ebooks = exact("enchanted_book");
        Predicate<Item> pvp = exact("end_crystal", "obsidian", "crying_obsidian", "respawn_anchor", "glowstone", "totem_of_undying",
            "golden_apple", "enchanted_golden_apple", "ender_pearl", "experience_bottle", "chorus_fruit", "cobweb", "bow", "crossbow", "mace",
            "wind_charge").or(sword).or(armor).or(potions).or(suffix("arrow"));
        Predicate<Item> gapples = exact("golden_apple", "enchanted_golden_apple");
        Predicate<Item> nether = ids("nether", "crimson", "warped", "blackstone", "basalt", "soul_", "quartz", "glowstone", "magma",
            "shroomlight", "ancient_debris", "blaze", "ghast", "wither").or(exact("netherrack", "gold_nugget"));
        Predicate<Item> end = ids("end_stone", "purpur", "chorus", "end_rod", "shulker_shell", "dragon", "ender_eye", "end_crystal").or(exact("elytra"));
        Predicate<Item> ocean = ids("prismarine", "coral", "kelp", "sponge", "sea_", "nautilus", "turtle", "trident", "conduit",
            "heart_of_the_sea", "_bucket", "fish", "cod", "salmon", "squid", "seagrass");
        Predicate<Item> fish = exact("cod", "salmon", "tropical_fish", "pufferfish", "cooked_cod", "cooked_salmon");
        Predicate<Item> meat = exact("beef", "cooked_beef", "porkchop", "cooked_porkchop", "chicken", "cooked_chicken", "mutton",
            "cooked_mutton", "rabbit", "cooked_rabbit", "rotten_flesh");
        Predicate<Item> music = s -> s.music || s.id.startsWith("disc_fragment");
        Predicate<Item> eggs = suffix("_spawn_egg");
        Predicate<Item> utility = exact("shears", "flint_and_steel", "fishing_rod", "lead", "name_tag", "compass", "recovery_compass",
            "clock", "spyglass", "bucket", "water_bucket", "lava_bucket", "brush", "map", "filled_map", "saddle", "bundle");
        Predicate<Item> buckets = suffix("bucket");
        Predicate<Item> junk = exact("rotten_flesh", "poisonous_potato", "dirt", "cobblestone", "cobbled_deepslate", "netherrack", "gravel",
            "andesite", "diorite", "granite", "tuff", "spider_eye", "bone", "string", "stick", "wheat_seeds", "flint", "sand", "kelp",
            "dead_bush", "leather_boots", "leather_helmet", "fishing_rod", "bowl", "lily_pad", "seagrass");
        Predicate<Item> workstations = exact("crafting_table", "furnace", "blast_furnace", "smoker", "anvil", "chipped_anvil",
            "damaged_anvil", "enchanting_table", "grindstone", "stonecutter", "smithing_table", "loom", "cartography_table",
            "fletching_table", "brewing_stand", "cauldron", "composter", "barrel", "lectern", "beehive", "bee_nest", "crafter");
        Predicate<Item> containers = exact("chest", "trapped_chest", "barrel", "ender_chest", "hopper", "dropper", "dispenser", "bundle").or(shulker);
        Predicate<Item> building = s -> fullBlock(s) || suffix("_stairs", "_slab", "_wall").test(s);
        Predicate<Item> templates = ids("smithing_template", "armor_trim");
        Predicate<Item> stackable64 = s -> s.maxStack == 64;
        Predicate<Item> stackable16 = s -> s.maxStack == 16;
        Predicate<Item> singles = s -> s.maxStack == 1;

        add(shulker, "shulker", "shulker box", "shulk", "shulkers box", "sb");
        add(Categories::shulkerWithItems, "full shulker", "filled shulker", "packed shulker", "kit", "loaded shulker", "stocked shulker");
        add(shulker.and(s -> !shulkerWithItems(s)), "empty shulker", "empty box", "spare shulker");
        add(food, "food", "eat", "eats", "snack", "grub", "ration", "meal", "edible", "consumable", "nom");
        add(meat, "meat", "raw meat", "cooked meat", "steak", "protein");
        add(fish, "fish", "seafood");
        // Plain gapples only: "egap", "god apple" and the like are enchanted golden apples alone (see the nicknames), so
        // a container signed for them never takes the ordinary kind
        add(gapples, "gapple", "golden apple", "gap");
        add(crops, "crop", "farming", "farm", "harvest", "produce", "veggie", "vegetable", "agriculture", "grain");
        add(seeds, "seed");
        add(saplings, "sapling", "tree", "propagule");
        add(flowers, "flower", "bouquet", "petal");
        add(plants, "plant", "nature", "greenery", "foliage", "garden", "gardening", "vegetation", "organic", "botany");
        add(tools, "tool", "toolkit", "equipment", "implement");
        add(pickaxe, "pickaxe", "pick", "picks", "pickaxes");
        add(axe, "axe", "hatchet");
        add(shovel, "shovel", "spade");
        add(hoe, "hoe");
        add(sword, "sword", "blade");
        add(weapons, "weapon", "combat", "fighting", "melee", "ranged", "arsenal");
        add(armor, "armor", "armour", "gear", "kit armor", "protection", "wearable", "suit");
        add(suffix("_helmet").or(exact("turtle_helmet")), "helmet", "helm", "cap", "hat");
        add(suffix("_chestplate").or(exact("elytra")), "chestplate", "chest plate", "chestpiece");
        add(suffix("_leggings"), "legging", "pant", "leg");
        add(suffix("_boots"), "boot", "shoe");
        add(exact("shield"), "shield");
        add(exact("elytra"), "elytra", "wing", "elytra wing");
        add(suffix("arrow"), "arrow", "ammo", "ammunition");
        add(exact("bow", "crossbow"), "bow", "crossbow", "archery");
        add(Categories::enchanted, "enchanted", "enchanted gear", "godset", "god set", "maxed", "max", "op gear", "enchants");
        add(ebooks, "enchanted book", "ebook", "e book", "enchantment book", "librarian");
        add(books, "book", "library", "reading", "lore");
        add(potions, "potion", "pot", "splash", "brew");
        add(brewing, "brewing", "alchemy", "brewing ingredient", "potion ingredient");
        add(exact("experience_bottle"), "experience", "experience bottle", "xp bottle", "exp bottle", "bottle o enchanting", "xp", "exp");
        add(pvp, "pvp", "crystal pvp", "cpvp", "crystalpvp", "pvp kit", "pvp gear", "fight", "war", "anarchy kit");
        add(exact("end_crystal"), "crystal", "end crystal", "crystals");
        add(exact("obsidian", "crying_obsidian"), "obsidian", "obby", "obsi");
        add(exact("totem_of_undying"), "totem", "totem of undying", "totems", "tots");
        add(exact("ender_pearl"), "pearl", "ender pearl", "epearl", "enderpearl");
        add(exact("respawn_anchor", "glowstone"), "anchor", "respawn anchor", "anchor pvp");
        add(ores, "ore", "raw", "raw ore", "mining", "mined", "mineral");
        add(ingots, "ingot", "metal", "bar", "nugget", "smelted");
        add(gems, "gem", "jewel", "crystal shard", "precious");
        add(storageBlocks, "storage block", "mineral block", "compressed", "metal block", "resource block");
        add(valuables, "valuable", "loot", "treasure", "rare", "precious", "expensive", "important", "dupe", "vault", "safe", "goodies", "best");
        add(ids("diamond"), "diamond", "dias", "diamond stuff");
        add(ids("netherite"), "netherite", "nethe", "netherite stuff");
        add(redstone, "redstone", "redstone component", "circuit", "circuitry", "tech", "technical", "mechanic", "mechanism", "contraption",
            "automation", "machine", "engineering", "logic", "wiring");
        add(rails, "rail", "railway", "track");
        add(transport, "transport", "transportation", "travel", "vehicle", "boat", "minecart", "cart", "ride", "mount", "horse");
        add(exact("firework_rocket", "firework_star"), "firework", "rocket", "fireworks", "elytra fuel", "boost");
        add(light, "light", "lighting", "torch", "lamp", "lantern", "illumination", "glow");
        add(decoration, "decoration", "decor", "deco", "decorative", "furniture", "interior", "aesthetic", "pretty");
        add(heads, "head", "skull", "trophy", "mob head");
        add(suffix("_banner"), "banner", "flag");
        add(suffix("_carpet"), "carpet", "rug");
        add(suffix("_bed"), "bed");
        add(suffix("candle"), "candle");
        add(suffix("_sign", "_hanging_sign"), "sign", "hanging sign");
        add(wool, "wool", "fabric", "cloth");
        add(dyes, "dye", "color", "colour", "paint", "pigment");
        add(colorful, "colored", "coloured", "colorful", "colourful", "rainbow", "dyed");
        add(glass, "glass", "pane", "window");
        add(concrete, "concrete", "concrete powder", "cement");
        add(terracotta, "terracotta", "terra cotta", "clay block", "glazed");
        add(logs, "log", "trunk", "timber");
        add(planks, "plank", "board");
        add(wood, "wood", "lumber", "woodwork", "wooden");
        add(stone, "stone", "rock", "cobble", "cobblestone", "masonry", "brick", "stonework", "deepslate");
        add(dirt, "dirt", "soil", "earth", "mud", "ground");
        add(sand, "sand", "gravel", "desert");
        add(building, "building", "building block", "build", "builder", "construction", "block", "material", "mat", "bulk");
        add(suffix("_stairs"), "stair", "step");
        add(suffix("_slab"), "slab", "half slab");
        add(suffix("_wall"), "wall");
        add(suffix("_fence", "_fence_gate"), "fence", "gate", "fencing");
        add(suffix("_door", "_trapdoor"), "door", "trapdoor", "hatch");
        add(mobDrops, "mob drop", "drop", "mob loot", "monster drop", "grinder", "farm drop", "spawner");
        add(exact("bone", "bone_meal", "bone_block"), "bone", "bonemeal", "bone meal", "fertilizer");
        add(exact("gunpowder", "tnt", "firework_star"), "gunpowder", "explosive", "boom", "sulphur");
        add(exact("string", "cobweb", "white_wool"), "string", "web", "cobweb");
        add(nether, "nether", "hell", "netherrack stuff");
        add(end, "end", "end stuff", "ender", "end city", "the end");
        add(ocean, "ocean", "aquatic", "water", "sea", "marine", "underwater", "monument");
        add(music, "music", "disc", "record", "music disc", "vinyl", "song");
        add(eggs, "spawn egg", "egg", "mob egg");
        add(utility, "utility", "util", "misc tool", "handy");
        add(buckets, "bucket", "pail");
        add(exact("map", "filled_map", "compass", "recovery_compass", "clock", "spyglass"), "map", "navigation", "exploration", "explorer");
        add(workstations, "workstation", "station", "crafting", "utility block", "job block", "furnace");
        add(containers, "container", "chest", "storage container");
        add(templates, "template", "smithing template", "trim", "armor trim", "upgrade");
        add(s -> s.customName, "named", "renamed", "custom", "named item");
        add(s -> s.damaged, "damaged", "broken", "used", "worn", "repair", "needs repair", "to repair");
        add(junk, "junk", "trash", "garbage", "rubbish", "useless", "scrap", "filler", "dump");
        add(stackable64, "stackable", "stack", "x64", "64", "64s", "full stack");
        add(stackable16, "x16", "16", "16s", "small stack", "half stackable");
        add(singles, "single", "unstackable", "non stackable", "nonstackable", "x1", "1s", "one");

        // More slang. Every colour of shulker box is "shulk" or "sb"; "boxes" on its own means them too
        add(shulker, "shulkerbox", "sbox", "boxes");
        add(Categories::enchanted, "ench", "enchant", "ench gear", "enchanted armor");
        add(redstone, "rs", "rs stuff");
        add(ids("netherite"), "neth", "nether ite");
        Predicate<Item> netheriteGear = ids("netherite").and(armor.or(tools).or(weapons));
        add(netheriteGear, "netherite gear", "neth gear", "netherite equipment");
        add(ids("netherite").and(armor), "netherite armor", "netherite armour", "neth armor", "neth armour");

        // Potions by what they do, with or without "pot" or "potion" after: "strength pots", "fres", "speed potion"
        potionKind(potions, "strength", "strength", "str");
        potionKind(potions, "swiftness", "speed", "swiftness", "swift", "spd");
        potionKind(potions, "fire_resistance", "fire resistance", "fire res", "fres", "fire resist");
        potionKind(potions, "turtle_master", "turtle master", "turtle");
        potionKind(potions, "healing", "healing", "heal", "health", "instant health");
        potionKind(potions, "harming", "harming", "harm", "instant damage");
        potionKind(potions, "regeneration", "regeneration", "regen");
        potionKind(potions, "slow_falling", "slow falling", "feather fall");
        potionKind(potions, "invisibility", "invisibility", "invis");
        potionKind(potions, "night_vision", "night vision", "nv");
        potionKind(potions, "water_breathing", "water breathing", "wb");
        potionKind(potions, "leaping", "leaping", "jump", "jump boost");
        potionKind(potions, "weakness", "weakness", "weak");
        potionKind(potions, "poison", "poison");
        potionKind(potions, "slowness", "slowness");
        potionKind(potions, "luck", "luck");

        // The names the sorter writes on a sign for a new category, narrowest first so a torch is "Light" and not
        // "Decoration". Each is also a word the sorter understands on a sign, as any other category name
        label("Light", light);
        label("Templates", templates);
        label("Gems", gems);
        label("Nether Blocks", nether);
        label("End Blocks", end.and(i -> i.block));
        label("Ores", ores);
        label("Ingots", ingots);
        label("Storage Blocks", storageBlocks);
        label("Redstone", redstone);
        label("Rails", rails);
        label("Transport", transport);
        label("Wool", wool);
        label("Dyes", dyes);
        label("Glass", glass);
        label("Concrete", concrete);
        label("Terracotta", terracotta);
        label("Logs", logs);
        label("Planks", planks);
        label("Wood", wood);
        label("Stone", stone);
        label("Dirt", dirt);
        label("Sand & Gravel", sand);
        label("Saplings", saplings);
        label("Flowers", flowers);
        label("Seeds", seeds);
        label("Crops", crops);
        label("Plants", plants);
        label("Meat", meat);
        label("Fish", fish);
        label("Food", food);
        label("Tools", tools);
        label("Weapons", weapons);
        label("Armor", armor);
        label("Arrows", suffix("arrow"));
        label("Potions", potions);
        label("XP Bottles", exact("experience_bottle"));
        label("Brewing", brewing);
        label("Enchanted Books", ebooks);
        label("Books", books);
        label("Mob Drops", mobDrops);
        label("Music Discs", music);
        label("Spawn Eggs", eggs);
        label("Buckets", buckets);
        label("Workstations", workstations);
        label("Shulkers", shulker);
        label("Heads", heads);
        label("Utility", utility);
        label("Signs", suffix("_sign", "_hanging_sign"));
        label("Decoration", decoration);
        label("Building Blocks", building);
    }

    /**
     * A name for a new category, with what belongs in it. Registered as a sign word like any other, unless the sorter
     * already knows it as one.
     */
    public record Label(String text, Predicate<Item> test) {
    }


    private static void label(String text, Predicate<Item> test) {
        LABELS.add(new Label(text, test));
        CATEGORIES.putIfAbsent(singularPhrase(normalize(text)), test);
    }

    /**
     * What a sign for this item could say, best first: the category names it fits, then its own name. Never a name
     * someone gave it.
     */
    public static List<String> labelCandidates(Item item) {
        List<String> out = new ArrayList<>();
        for (Label label : LABELS) if (label.test().test(item)) out.add(label.text());
        out.add(Item.prettyName(item.id));
        return out;
    }

    /**
     * A potion (drinkable, splash, lingering or tipped arrow) of one kind, in any strength, under all the names given.
     */
    private static void potionKind(Predicate<Item> potions, String kind, String... names) {
        Predicate<Item> test = s -> potions.test(s) && s.potion != null && s.potion.contains(kind);
        for (String name : names) add(test, name, name + " pot", name + " potion");
    }

    // Telling whether a sign says anything we understand

    /**
     * Whether a sign line is something the sorter understands: a nickname, a category, a catch-all word, a kit, or words
     * that are all found in some item's name, a potion or an enchantment. A date or a name alone is not.
     */
    public static boolean recognises(String line) {
        Boolean known = RECOGNISED.get(line);
        if (known == null) {
            if (RECOGNISED.size() > 2048) RECOGNISED.clear();
            known = false;
            for (Part part : prepared(line)) {
                if (partRecognised(part)) {
                    known = true;
                    break;
                }
            }
            RECOGNISED.put(line, known);
        }
        return known;
    }

    private static final Map<String, Boolean> RECOGNISED = new ConcurrentHashMap<>();
    private static volatile Set<String> vocabulary;

    private static boolean partRecognised(Part part) {
        String label = part.label();
        if (label.isEmpty()) return false;
        if (wholeKnown(part) || CATCH_ALL.contains(label) || KEEP_WHOLE.contains(part.labelKeys().getFirst())) return true;
        if (nicknames(part) != null || lookup(CATEGORIES, part.labelKeys()) != null) return true;
        for (String word : label.split(" ")) {
            if (lookup(CATEGORIES, List.of(singular(word), altWord(word))) != null) return true;
        }
        return allIn(label, vocabulary());
    }

    /**
     * Every word of every item name, potion and enchantment.
     */
    private static Set<String> vocabulary() {
        Set<String> words = vocabulary;
        if (words == null) {
            StringBuilder all = new StringBuilder();
            for (ItemData item : ItemRegistry.REGISTRY.getKeyMap().values()) all.append(' ').append(normalize(item.name()));
            for (EnchantmentData enchant : EnchantmentRegistry.REGISTRY.getDefaultRegistry().getKeyMap().values()) all.append(' ').append(normalize(enchant.name()));
            for (String potion : Item.potionNames()) all.append(' ').append(normalize(potion));
            words = Categories.words(all.toString());
            vocabulary = words;
        }
        return words;
    }
}
