package com.porter.qwertestash.stash;

import com.aquarius.cache.data.inventory.Container;
import com.aquarius.mc.block.BlockRegistry;
import com.aquarius.mc.enchantment.EnchantmentData;
import com.aquarius.mc.enchantment.EnchantmentRegistry;
import com.aquarius.mc.item.ItemData;
import com.aquarius.mc.item.ItemRegistry;
import com.aquarius.mc.item.ItemTags;
import com.aquarius.util.ComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What the sorter knows about one stack: enough to sort and search it, and to save it with the stash. Read from the
 * proxy's item data. The live stack is kept too, while there is one, for telling identical stacks apart.
 */
public final class Item {
    public String id;
    public int count;
    /** The name the registry id gives it. Never the anvil name: that is only kept in {@link #customText}, for display. */
    public String name;
    public boolean customName;
    public @Nullable String customText;
    public int maxStack;
    public boolean food;
    public boolean music;
    public boolean block;
    public boolean fullBlock;
    public boolean damaged;
    public List<String> tags = new ArrayList<>();
    public List<String> enchants = new ArrayList<>();
    /** The same enchantments with their levels, as "name:level". */
    public List<String> enchantLevels = new ArrayList<>();
    public @Nullable String potion;
    /**
     * For items whose id doesn't say what they are (potions, tipped arrows, enchanted books, suspicious stew, rockets,
     * goat horns, ominous bottles): what they are. Null for everything else, and for stacks saved before this existed.
     */
    public @Nullable String typeData;
    /** What's inside, for shulkers. Empty for everything else, and for empty shulkers. */
    public List<Item> contents = new ArrayList<>();

    /** The stack this was read from, while it's still in front of us. Not saved. */
    public transient @Nullable ItemStack raw;
    /** How many stacks a bundle holds (0 for anything else). */
    public int bundleStacks;
    /** Has the bundles tag. Not saved: a saved stash is told by the id (see {@link #isBundle()}). */
    public transient boolean bundle;

    /**
     * The item for a stack, or null for an empty slot.
     */
    public static @Nullable Item of(@Nullable ItemStack stack) {
        if (stack == Container.EMPTY_STACK || stack == null || stack.getAmount() <= 0) return null;
        ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        if (data == null || stack.getId() == ItemRegistry.AIR.id()) return null;

        Item item = new Item();
        item.raw = stack;
        item.id = data.name();
        item.count = stack.getAmount();

        var components = stack.getDataComponentsOrEmpty();
        var customName = components.get(DataComponentTypes.CUSTOM_NAME);
        item.customName = customName != null;
        item.name = prettyName(data.name());
        if (customName != null) item.customText = ComponentSerializer.serializePlain(customName);

        Integer max = components.get(DataComponentTypes.MAX_STACK_SIZE);
        item.maxStack = max != null ? max : data.stackSize();
        item.food = data.components().get(DataComponentTypes.FOOD) != null;
        item.music = data.components().get(DataComponentTypes.JUKEBOX_PLAYABLE) != null;
        var block = BlockRegistry.REGISTRY.get(data.name());
        item.block = block != null;
        item.fullBlock = block != null && block.solidBlock();

        Integer damage = components.get(DataComponentTypes.DAMAGE);
        item.damaged = damage != null && damage > 0;

        if (data.itemTags() != null) for (ItemTags tag : data.itemTags()) item.tags.add(tag.name());
        item.bundle = data.itemTags() != null && data.itemTags().contains(ItemTags.BUNDLES);

        addEnchants(item, components.get(DataComponentTypes.ENCHANTMENTS));
        addEnchants(item, components.get(DataComponentTypes.STORED_ENCHANTMENTS));

        var potion = components.get(DataComponentTypes.POTION_CONTENTS);
        if (potion != null && potion.getPotionId() >= 0 && potion.getPotionId() < POTIONS.length) item.potion = POTIONS[potion.getPotionId()];

        item.typeData = typeDataOf(data.name(), components);

        if (item.bundle) {
            List<ItemStack> packed = components.get(DataComponentTypes.BUNDLE_CONTENTS);
            item.bundleStacks = packed == null ? 0 : packed.size();
        }

        List<ItemStack> inside = components.get(DataComponentTypes.CONTAINER);
        if (inside != null) {
            for (ItemStack s : inside) {
                Item i = of(s);
                if (i != null) {
                    i.raw = null;
                    item.contents.add(i);
                }
            }
        }

        return item;
    }

    private static final java.util.Set<String> TYPE_DEFINED = java.util.Set.of(
        "potion", "splash_potion", "lingering_potion", "tipped_arrow", "enchanted_book", "suspicious_stew",
        "firework_rocket", "goat_horn", "ominous_bottle", "filled_map", "player_head", "written_book", "firework_star",
        "shield");

    private static boolean typeDefined(String id) {
        return TYPE_DEFINED.contains(id) || id.endsWith("_banner");
    }

    private static @Nullable String typeDataOf(String id, org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents components) {
        if (!typeDefined(id)) return null;
        if (id.endsWith("_banner")) return String.valueOf(components.get(DataComponentTypes.BANNER_PATTERNS));
        switch (id) {
            case "potion", "splash_potion", "lingering_potion", "tipped_arrow" -> {
                return String.valueOf(components.get(DataComponentTypes.POTION_CONTENTS));
            }
            case "enchanted_book" -> {
                ItemEnchantments stored = components.get(DataComponentTypes.STORED_ENCHANTMENTS);
                if (stored == null) return "none";
                List<String> parts = new ArrayList<>();
                for (var entry : stored.getEnchantments().int2IntEntrySet()) {
                    EnchantmentData data = EnchantmentRegistry.REGISTRY.get(entry.getIntKey());
                    parts.add((data != null ? data.name() : "#" + entry.getIntKey()) + ":" + entry.getIntValue());
                }
                java.util.Collections.sort(parts);
                return String.join(",", parts);
            }
            case "suspicious_stew" -> {
                return String.valueOf(components.get(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS));
            }
            case "firework_rocket" -> {
                var fireworks = components.get(DataComponentTypes.FIREWORKS);
                return fireworks == null ? "none" : fireworks.getFlightDuration() + "|" + fireworks.getExplosions();
            }
            case "goat_horn" -> {
                return String.valueOf(components.get(DataComponentTypes.INSTRUMENT));
            }
            case "filled_map" -> {
                return String.valueOf(components.get(DataComponentTypes.MAP_ID));
            }
            case "player_head" -> {
                return String.valueOf(components.get(DataComponentTypes.PROFILE));
            }
            case "written_book" -> {
                return String.valueOf(components.get(DataComponentTypes.WRITTEN_BOOK_CONTENT));
            }
            case "firework_star" -> {
                return String.valueOf(components.get(DataComponentTypes.FIREWORK_EXPLOSION));
            }
            case "shield" -> {
                return components.get(DataComponentTypes.BASE_COLOR) + "|" + components.get(DataComponentTypes.BANNER_PATTERNS);
            }
            default -> {
                return String.valueOf(components.get(DataComponentTypes.OMINOUS_BOTTLE_AMPLIFIER));
            }
        }
    }

    /**
     * What stacks have to share to be packed into one shulker: the item id, and for items whose id doesn't say what
     * they are, what they are. Never enchantments, damage or a name on gear. Null for shulkers, anything with contents,
     * and a stack of such an item saved before its type was kept (it gets one when its container is read again).
     */
    public @Nullable String bulkKey() {
        if (isShulker() || !contents.isEmpty() || bundleStacks > 0) return null;
        if (typeDefined(id)) return typeData == null ? null : id + "|" + typeData;
        return id;
    }

    public boolean isFullStack() {
        return count >= maxStack;
    }

    private static void addEnchants(Item item, @Nullable ItemEnchantments enchants) {
        if (enchants == null) return;
        for (int id : enchants.getEnchantments().keySet()) {
            EnchantmentData data = EnchantmentRegistry.REGISTRY.get(id);
            if (data != null) {
                item.enchants.add(data.name());
                item.enchantLevels.add(data.name() + ":" + enchants.getEnchantments().get(id));
            }
        }
    }

    /**
     * A bundle. The proxy's ClickItem cannot put one down on an empty slot (see StashSorter.bundleHazard).
     */
    public boolean isBundle() {
        return bundle || id.equals("bundle") || id.endsWith("_bundle");
    }

    /**
     * A bundle with something in it: it is emptied (see StashSorter), never put away as it is.
     */
    public boolean isFilledBundle() {
        return isBundle() && bundleStacks > 0;
    }

    public boolean isShulker() {
        return id.endsWith("shulker_box");
    }

    /**
     * The same item with the same data, so the two would stack. Falls back to comparing what was saved when either
     * isn't a live stack.
     */
    public boolean sameAs(Item other) {
        if (!id.equals(other.id)) return false;
        if (raw != null && other.raw != null) return Objects.equals(raw.getDataComponents(), other.raw.getDataComponents());
        return Objects.equals(customText, other.customText) && enchants.equals(other.enchants) && Objects.equals(potion, other.potion) && contents.size() == other.contents.size();
    }

    /**
     * For messages: the item's own name, and the player's name for it if it has one. Not used for matching.
     */
    public String label() {
        if (!customName) return name;
        return customText == null || customText.isBlank() ? name + " (named)" : name + " (named " + customText + ")";
    }

    /**
     * Stashes saved before names were kept apart have the anvil name in {@code name}: back to the registry's name.
     */
    void normalizeName() {
        if (customName && customText == null) customText = name;
        name = prettyName(id);
        if (contents != null) for (Item inside : contents) if (inside != null) inside.normalizeName();
    }

    /**
     * "totem_of_undying" as "Totem of Undying".
     */
    public static String prettyName(String id) {
        StringBuilder out = new StringBuilder();
        for (String word : id.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            boolean small = !out.isEmpty() && (word.equals("of") || word.equals("the") || word.equals("on") || word.equals("a") || word.equals("and"));
            out.append(small ? word : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1));
        }
        return out.toString();
    }

    /** The potion names, for telling what words a sign can use. */
    static List<String> potionNames() {
        return List.of(POTIONS);
    }

    /** Potions in registry order, for Minecraft 1.21.4. */
    private static final String[] POTIONS = {
        "water", "mundane", "thick", "awkward", "night_vision", "long_night_vision", "invisibility", "long_invisibility",
        "leaping", "long_leaping", "strong_leaping", "fire_resistance", "long_fire_resistance", "swiftness", "long_swiftness",
        "strong_swiftness", "slowness", "long_slowness", "strong_slowness", "turtle_master", "long_turtle_master",
        "strong_turtle_master", "water_breathing", "long_water_breathing", "healing", "strong_healing", "harming",
        "strong_harming", "poison", "long_poison", "strong_poison", "regeneration", "long_regeneration", "strong_regeneration",
        "strength", "long_strength", "strong_strength", "weakness", "long_weakness", "luck", "slow_falling", "long_slow_falling",
        "wind_charged", "weaving", "oozing", "infested"
    };
}
