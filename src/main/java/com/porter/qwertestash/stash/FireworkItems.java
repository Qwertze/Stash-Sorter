package com.porter.qwertestash.stash;

import com.porter.qwertestash.orders.FireworkSpecs;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Fireworks;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a firework rocket's or star's components (CRAFT-CONTRACT-v1.2.md 3, "How the bot derives a spec from an item"): the item's <em>effective</em> value,
 * the default component merged with the stack's patch. The result is the compact text {@link FireworkSpecs#encodeRocket} and
 * {@link FireworkSpecs#encodeStar} define, which {@link Item#fw} keeps with the stack so its spec can be derived whenever the extras are known. Null when the
 * stack has no spec whatever the tables say: its patch holds any other component (a custom name, lore, enchantments, ...), the item's own component is removed, or a star has
 * no explosion. A {@code custom_data} component is left out of the judgement, as {@code ItemKeys} leaves it out of the key (some servers' ViaVersion adds it to most items).
 */
public final class FireworkItems {
    private FireworkItems() {
    }

    public static final String ROCKET = "firework_rocket", STAR = "firework_star";

    /**
     * @param id       the item id without a namespace
     * @param patch    the stack's component patch
     * @param defaults the item's default components (may lack the firework component)
     */
    public static @Nullable String dataOf(String id, DataComponents patch, @Nullable DataComponents defaults) {
        boolean rocket = id.equals(ROCKET);
        if (!rocket && !id.equals(STAR)) return null;
        DataComponentType<?> own = rocket ? DataComponentTypes.FIREWORKS : DataComponentTypes.FIREWORK_EXPLOSION;
        Map<DataComponentType<?>, DataComponent<?, ?>> all = patch == null ? null : patch.getDataComponents();
        if (all != null) {
            for (DataComponent<?, ?> c : all.values()) {
                if (c == null || c.getType() == DataComponentTypes.CUSTOM_DATA) continue;
                // Only the item's own component, and set: a removed one or any other is outside the grammar
                if (c.getType() != own || c.getValue() == null) return null;
            }
        }
        if (rocket) {
            Fireworks f = patch == null ? null : patch.get(DataComponentTypes.FIREWORKS);
            if (f == null && defaults != null) f = defaults.get(DataComponentTypes.FIREWORKS);
            // [UNVERIFIED, B3] a rocket crafted with 1 gunpowder and no stars has no patch: its default is flight 1 with no explosions (S1)
            if (f == null) return FireworkSpecs.encodeRocket(1, List.of());
            if (f.getFlightDuration() < 1 || f.getFlightDuration() > 3 || (f.getExplosions() != null && f.getExplosions().size() > 8)) return null;
            List<FireworkSpecs.Explosion> ex = new ArrayList<>();
            if (f.getExplosions() != null) for (Fireworks.FireworkExplosion e : f.getExplosions()) {
                if (tooLong(e)) return null;
                ex.add(explosion(e));
            }
            return FireworkSpecs.encodeRocket(f.getFlightDuration(), ex);
        }
        Fireworks.FireworkExplosion e = patch == null ? null : patch.get(DataComponentTypes.FIREWORK_EXPLOSION);
        if (e == null && defaults != null) e = defaults.get(DataComponentTypes.FIREWORK_EXPLOSION);
        return e == null || tooLong(e) ? null : FireworkSpecs.encodeStar(explosion(e));
    }

    /** A colour or fade list longer than the grammar's 8 (the data is bounded before it is kept with the stack). */
    private static boolean tooLong(Fireworks.FireworkExplosion e) {
        return (e.getColors() != null && e.getColors().length > FireworkSpecs.MAX_COLORS) || (e.getFadeColors() != null && e.getFadeColors().length > FireworkSpecs.MAX_COLORS);
    }

    /** What the sub-plan calls this stack: the pseudo id of its spec when it has one, else its namespaced item id. */
    public static String refOf(Item it, FireworkSpecs.@Nullable Tables tables) {
        return com.porter.qwertestash.orders.Pseudo.of("minecraft:" + it.id, FireworkSpecs.specOf(it.fw, tables));
    }

    private static FireworkSpecs.Explosion explosion(Fireworks.FireworkExplosion e) {
        return FireworkSpecs.Explosion.of(e.getShapeId(), e.getColors(), e.getFadeColors(), e.isHasTrail(), e.isHasTwinkle());
    }
}
