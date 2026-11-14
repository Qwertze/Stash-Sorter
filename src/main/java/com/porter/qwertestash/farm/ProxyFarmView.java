package com.porter.qwertestash.farm;

import com.aquarius.module.api.Module;
import com.porter.qwertestash.module.StashSorter;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.aquarius.Globals.CONFIG;
import static com.aquarius.Globals.MODULE;

/**
 * The real {@link FarmerGuard.View}: reads the proxy's own config and modules. Every read is on its own, so a config path a
 * proxy version lacks fails closed (the row is reported as wrong), never open. Any thread.
 */
public final class ProxyFarmView implements FarmerGuard.View {
    /** The 2.6p marker values the plugin accepts (a build id starting with this). */
    public static final String PATCH = "2.6p";

    private final Supplier<String> keyCode;
    private final BooleanSupplier logFilter;

    /** The viewer API: enabled or control on, or unreadable (fail closed). Static so the commands can ask too. */
    public static boolean viewerOnNow() {
        try {
            var v = CONFIG.server.viewer;
            return v.enabled || v.control;
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** The viewer's control switch alone (it runs commands as the terminal); unreadable counts as on. */
    public static boolean viewerControlNow() {
        try {
            return CONFIG.server.viewer.control;
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    public static boolean verifyUsersNow() {
        try {
            return CONFIG.server.verifyUsers;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public ProxyFarmView(Supplier<String> keyCode, BooleanSupplier logFilter) {
        this.keyCode = keyCode;
        this.logFilter = logFilter;
    }

    private static boolean bad(BooleanSupplier s, boolean failValue) {
        try {
            return s.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return failValue;
        }
    }

    @Override
    public String keyCode() {
        return keyCode.get();
    }

    @Override
    public boolean bridgeEnabled() {
        return bad(() -> CONFIG.client.extra.bridge.enabled, false);
    }

    @Override
    public boolean stripRegisterChannel() {
        return bad(() -> CONFIG.client.extra.bridge.stripRegisterChannel, false);
    }

    @Override
    public int invokeAllowListSize() {
        try {
            return CONFIG.client.extra.bridge.invokeAllowList.size();
        } catch (RuntimeException | LinkageError e) {
            return 1;
        }
    }

    @Override
    public boolean bridgeHardened() {
        return BuildIds.bridgeHardened();
    }

    @Override
    public boolean discordStripped() {
        return BuildIds.discordStripped();
    }

    @Override
    public boolean reportCoords() {
        return reportCoordsNow();
    }

    /** {@code discord.reportCoords} as it is now (a runtime {@code displayCoords} command can flip it); unreadable counts as on. */
    public static boolean reportCoordsNow() {
        return bad(() -> CONFIG.discord.reportCoords, true);
    }

    @Override
    public boolean debugLogs() {
        return bad(() -> CONFIG.debug.debugLogs, true);
    }

    @Override
    public boolean terminalDebugLogs() {
        return bad(() -> CONFIG.debug.terminalDebugLogs, true);
    }

    @Override
    public boolean packetLog() {
        return bad(() -> CONFIG.debug.packetLog.enabled, true);
    }

    @Override
    public boolean replayMod() {
        return bad(() -> CONFIG.client.extra.replayMod.autoRecordMode != com.aquarius.util.config.Config.Client.Extra.ReplayMod.AutoRecordMode.NONE
            || enabled(com.aquarius.module.impl.ReplayMod.class), true);
    }

    @Override
    public boolean logFilterInstalled() {
        return logFilter.getAsBoolean();
    }

    @Override
    public boolean viewerOn() {
        return viewerOnNow();
    }

    @Override
    public boolean whisperSearch() {
        try {
            return com.porter.qwertestash.QwerteStashPlugin.CONFIG.whisperSearch;
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public boolean ownersUnverified() {
        try {
            return !verifyUsersNow() && !com.porter.qwertestash.QwerteStashPlugin.CONFIG.farmOwners.isEmpty();
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public boolean rotateBeforeInteract() {
        return bad(() -> CONFIG.debug.botRotateBeforeInteract, false);
    }

    @Override
    public boolean antiAfk() {
        return bad(() -> CONFIG.client.extra.antiafk.enabled || enabled(com.aquarius.module.impl.AntiAFK.class), true);
    }

    @Override
    public boolean visualRange() {
        return bad(() -> CONFIG.client.extra.visualRange.enabled || enabled(com.aquarius.module.impl.VisualRange.class), true);
    }

    @Override
    public boolean autoTotem() {
        return bad(() -> CONFIG.client.extra.autoTotem.enabled, false);
    }

    @Override
    public boolean autoEat() {
        return bad(() -> CONFIG.client.extra.autoEat.enabled, false);
    }

    @Override
    public boolean autoDisconnect() {
        return bad(() -> CONFIG.client.extra.utility.actions.autoDisconnect.enabled, false);
    }

    private static boolean enabled(Class<? extends Module> type) {
        Module m = MODULE.get(type);
        return m != null && m.isEnabled();
    }

    @Override
    public List<String> conflictingModules() {
        List<String> on = new ArrayList<>();
        // Click is on by default in the proxy and harmless while it holds nothing: it only conflicts when it holds a click, sneak or rotation
        try {
            var click = CONFIG.client.extra.click;
            if (enabled(com.aquarius.module.impl.Click.class) && (click.holdLeftClick || click.holdRightClick || click.holdSneak || click.hasRotation
                || click.additionalBlockReach != 0 || click.additionalEntityReach != 0)) on.add("Click");
        } catch (RuntimeException | LinkageError e) {
            on.add("Click");
        }
        List<Class<? extends Module>> types = List.of(com.aquarius.module.impl.KillAura.class,
            com.aquarius.module.impl.AutoFish.class, com.aquarius.module.impl.VillagerTrader.class, com.aquarius.module.impl.LitematicaBuilder.class,
            com.aquarius.module.impl.AquariusMiner.class, com.aquarius.module.impl.OrderFiller.class, com.aquarius.module.impl.KitMaker.class,
            com.aquarius.module.impl.Enchanter.class, StashSorter.class);
        for (Class<? extends Module> t : types) {
            try {
                if (enabled(t)) on.add(t.getSimpleName());
            } catch (RuntimeException | LinkageError e) {
                on.add(t.getSimpleName()); // unknown: counts as on
            }
        }
        return on;
    }
}
