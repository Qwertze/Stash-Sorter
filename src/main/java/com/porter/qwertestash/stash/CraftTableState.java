package com.porter.qwertestash.stash;

import com.porter.qwertestash.manager.ManagerIndex;
import org.jspecify.annotations.Nullable;

/**
 * What the heartbeat says about the crafting table of craft orders (1.15.1): the {@code table} state and the {@code tableWhy} reason.
 * With a website table the state is its own; with none the bot finds, places or crafts one ("auto") when it can, else the table is "unset" and
 * the reason says what is missing. Pure.
 */
public final class CraftTableState {
    private CraftTableState() {
    }

    public record State(String status, @Nullable String why) {
    }

    /** The state of a website table from the reason it can't be used (null: it can). */
    public static State website(@Nullable String why) {
        if (why == null) return new State(ManagerIndex.TABLE_OK, null);
        return new State(switch (why) {
            case CraftBounds.WRONG_DIMENSION -> ManagerIndex.TABLE_OTHER_DIMENSION;
            case CraftBounds.NOT_LOADED -> ManagerIndex.TABLE_UNLOADED;
            case CraftBounds.NOT_TABLE -> ManagerIndex.TABLE_NOT_TABLE;
            default -> ManagerIndex.TABLE_UNREACHABLE;
        }, why);
    }

    /**
     * The state with no website table.
     * @param stashDimension the stash's dimension, or null; {@code bounds} its area (or the box of its containers), or null
     * @param ownUsable      the bot's own table (found, placed, crafted or set by hand) stands and is within the limits
     * @param found          a crafting table stands in the stash area within the limits
     * @param stockTable     the stash holds a crafting table
     * @param material       the stash holds planks, or logs to make planks of
     * @return "auto" when the bot can get a table, else "unset" with the reason
     */
    public static State auto(@Nullable String stashDimension, int @Nullable [] bounds, boolean ownUsable, boolean found, boolean stockTable, boolean material) {
        return auto(stashDimension, bounds != null, ownUsable, found, stockTable, material);
    }

    /** As above; {@code hasArea}: the stash has a perimeter or at least one container. */
    public static State auto(@Nullable String stashDimension, boolean hasArea, boolean ownUsable, boolean found, boolean stockTable, boolean material) {
        if (stashDimension == null) return new State(ManagerIndex.TABLE_UNSET, CraftBounds.NO_DIMENSION);
        if (!hasArea) return new State(ManagerIndex.TABLE_UNSET, CraftBounds.NO_AREA);
        if (ownUsable || found || stockTable || material) return new State(ManagerIndex.TABLE_AUTO, null);
        // No table known or found, none in the stash, and no planks or logs to make one of
        return new State(ManagerIndex.TABLE_UNSET, CraftBounds.NO_MATERIAL);
    }

    /**
     * Whether the heartbeat carries a craftOrders object (with the table state and the reason, even when no table route is available, so the website
     * shows why): when crafting and craft orders are on and a recipe table is held. Otherwise it is an explicit null.
     */
    public static boolean sendOrders(boolean craftingOn, boolean craftOrdersOn, boolean haveRecipes) {
        return craftingOn && craftOrdersOn && haveRecipes;
    }

    /**
     * Whether the claim carries {@code craftOrders: 1}: when the bot can craft, and always while it holds a craft order, whatever the table state
     * is for the moment, so the server never fails a held order as "downgraded".
     */
    public static boolean claimFlag(boolean capable, boolean holdsCraftOrder) {
        return capable || holdsCraftOrder;
    }

    /** The bot takes craft orders: the settings and a recipe table, and a table the website set that works or the auto path. */
    public static boolean capable(boolean base, boolean websiteSet, State state) {
        if (!base) return false;
        if (!websiteSet) return ManagerIndex.TABLE_AUTO.equals(state.status());
        return ManagerIndex.TABLE_OK.equals(state.status()) || ManagerIndex.TABLE_UNLOADED.equals(state.status());
    }
}
