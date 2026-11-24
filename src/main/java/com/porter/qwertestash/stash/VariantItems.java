package com.porter.qwertestash.stash;

import com.porter.qwertestash.orders.VariantSpecs;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.PotionContents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.SuspiciousStewEffect;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the stash keeps of the stacks that craft orders treat as variants (CRAFT-CONTRACT-v1.2.md 3 and 15), read from a stack's components into
 * {@link Item#fw}: a rocket's or star's explosions ({@link FireworkItems}, B3), and for B4
 * <ul>
 *     <li>{@code T|<potion>}: a tipped arrow or lingering potion whose patch is a potion and nothing else (S17: {@code tipped:minecraft:long_poison});</li>
 *     <li>{@code W|<effect>|<ticks>}: a suspicious stew with exactly one effect and no other component (S19);</li>
 *     <li>{@code G<n>}: a written book of generation {@code n} (a copy is made only from generation 0);</li>
 *     <li>{@code C}: a banner with patterns, a filled map, or a shield with a banner's patterns (copied or decorated by key, never an index spec).</li>
 * </ul>
 * Null for everything else, and for a stack outside the grammar (a custom colour, custom effects, a custom name, lore, enchantments, ...). A
 * {@code custom_data} component is left out of the judgement, as {@code ItemKeys} leaves it out of the key. Pure; any thread.
 */
public final class VariantItems {
    private VariantItems() {
    }

    /**
     * @param id       the item id without a namespace
     * @param patch    the stack's component patch
     * @param defaults the item's default components (may lack the component)
     */
    public static @Nullable String dataOf(String id, @Nullable DataComponents patch, @Nullable DataComponents defaults) {
        switch (id) {
            case "firework_rocket", "firework_star" -> {
                return FireworkItems.dataOf(id, patch, defaults);
            }
            case "tipped_arrow", "lingering_potion" -> {
                return tipped(patch);
            }
            case "suspicious_stew" -> {
                return stew(patch);
            }
            case "written_book" -> {
                WrittenBookContent b = patch == null ? null : patch.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
                return b == null ? null : "G" + Math.max(0, b.getGeneration());
            }
            case "filled_map" -> {
                return patch != null && patch.get(DataComponentTypes.MAP_ID) != null ? "C" : null;
            }
            case "shield" -> {
                return hasPatterns(patch) ? "C" : null;
            }
            default -> {
                return id.endsWith("_banner") && hasPatterns(patch) ? "C" : null;
            }
        }
    }

    /**
     * The generation of the written book of this index key among these stacks and what they hold (a shulker's contents), as the stash saved it: 0 when any
     * stack of the key is an original, else the generation of the copies found (1 or more) when no stack of the key has an unknown one, else -1 (no book of the
     * key, or a stack whose generation was not kept: a stash saved before B4). A copy is made only from an original: a key with copies only is refused before
     * anything is taken (CRAFT-CONTRACT-v1.2.md, B4 clarifications), a key with an original is not.
     */
    public static int generationOfKey(Iterable<? extends @Nullable Item> stacks, String key) {
        int[] found = {0, 0, -1}; // originals, unknown, the lowest copy generation
        for (Item it : stacks) if (it != null) scan(it, key, found);
        if (found[0] > 0) return 0;
        if (found[1] > 0) return -1;
        return found[2];
    }

    /** As {@link #generationOfKey} for one stack and what it holds. */
    public static int generationIn(Item it, String key) {
        return generationOfKey(List.of(it), key);
    }

    private static void scan(Item it, String key, int[] found) {
        if (it.id.equals("written_book") && it.indexKey().equals(key)) {
            int g = com.porter.qwertestash.orders.Variants.generationOf(it.fw);
            if (g == 0) found[0]++;
            else if (g < 0) found[1]++;
            else found[2] = found[2] < 0 ? g : Math.min(found[2], g);
        }
        for (Item in : it.contents) if (in != null) scan(in, key, found);
    }

    private static boolean hasPatterns(@Nullable DataComponents patch) {
        var layers = patch == null ? null : patch.get(DataComponentTypes.BANNER_PATTERNS);
        return layers != null && !layers.isEmpty();
    }

    /** Whether the patch holds anything but {@code own} (and custom data): then the stack is outside the grammar. */
    private static boolean other(@Nullable DataComponents patch, DataComponentType<?> own) {
        Map<DataComponentType<?>, DataComponent<?, ?>> all = patch == null ? null : patch.getDataComponents();
        if (all == null) return false;
        for (DataComponent<?, ?> c : all.values()) {
            if (c == null || c.getType() == DataComponentTypes.CUSTOM_DATA) continue;
            if (c.getType() != own || c.getValue() == null) return true;
        }
        return false;
    }

    private static @Nullable String tipped(@Nullable DataComponents patch) {
        if (patch == null || other(patch, DataComponentTypes.POTION_CONTENTS)) return null;
        PotionContents pc = patch.get(DataComponentTypes.POTION_CONTENTS);
        if (pc == null || pc.getCustomColor() >= 0 || pc.getCustomName() != null) return null;
        if (pc.getCustomEffects() != null && !pc.getCustomEffects().isEmpty()) return null;
        String name = Item.potionName(pc.getPotionId());
        return name == null || VariantSpecs.tippedSpec(name) == null ? null : "T|" + name;
    }

    private static @Nullable String stew(@Nullable DataComponents patch) {
        if (patch == null || other(patch, DataComponentTypes.SUSPICIOUS_STEW_EFFECTS)) return null;
        List<SuspiciousStewEffect> effects = patch.get(DataComponentTypes.SUSPICIOUS_STEW_EFFECTS);
        if (effects == null || effects.size() != 1) return null;
        SuspiciousStewEffect e = effects.get(0);
        if (e == null || e.getMobEffectId() < 0 || e.getMobEffectId() >= Effect.VALUES.length) return null;
        String effect = "minecraft:" + Effect.VALUES[e.getMobEffectId()].name().toLowerCase(Locale.ROOT);
        return VariantSpecs.stewSpec(effect, e.getDuration()) == null ? null : "W|" + effect + "|" + e.getDuration();
    }
}
