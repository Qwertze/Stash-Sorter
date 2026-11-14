package com.porter.qwertestash.farm;

import com.porter.qwertestash.QwerteStashPlugin;

import java.util.List;

/**
 * The FarmerGuard checklist (FARM P5a, D7, "Farmer CT setup"). Two halves:
 * <ul>
 *   <li>The feed gates: while the local farmer role is on, every feed that could carry a position is off, enforced in code and
 *       not by a setting the dashboard could flip. Each gate below is asked by the code that would start the feed.</li>
 *   <li>{@link #preflight}: the setup table, checked at every session start. The first row that is wrong is the code.</li>
 * </ul>
 * Pure: the proxy is seen through {@link View}. Any thread.
 */
public final class FarmerGuard {
    private FarmerGuard() {
    }

    /** True when the local farmer role is on (the config switch). */
    public static boolean active() {
        return QwerteStashPlugin.CONFIG != null && QwerteStashPlugin.CONFIG.farmerEnabled;
    }

    // ---- feed gates (P5a): each is false while the farmer role is on -------------------------------------------------

    /** The dashboard link is not started at all. */
    public static boolean allowDashboardLink() {
        return !active();
    }

    public static boolean allowMapTiles() {
        return !active();
    }

    public static boolean allowDeliveryContents() {
        return !active();
    }

    public static boolean allowKitReport() {
        return !active();
    }

    /** The pearlLoader and pearlTravel heartbeat fields. */
    public static boolean allowPearlHeartbeatFields() {
        return !active();
    }

    /** The index upload (index positions and the delivery x, y, z fields) and the stash fields of the heartbeat. */
    public static boolean allowIndexAndHeartbeat() {
        return !active();
    }

    public static boolean allowViewerFeed() {
        return !active();
    }

    /** All gates, by name, for the self test and the tests. */
    public static java.util.Map<String, Boolean> gates() {
        java.util.Map<String, Boolean> m = new java.util.LinkedHashMap<>();
        m.put("dashboardLink", allowDashboardLink());
        m.put("mapTiles", allowMapTiles());
        m.put("deliveryContents", allowDeliveryContents());
        m.put("kitReport", allowKitReport());
        m.put("pearlHeartbeatFields", allowPearlHeartbeatFields());
        m.put("indexAndHeartbeat", allowIndexAndHeartbeat());
        m.put("viewerFeed", allowViewerFeed());
        return m;
    }

    // ---- the setup table ---------------------------------------------------------------------------------------------

    /** What the proxy's config and modules say. The real one reads the proxy; a test gives its own. */
    public interface View {
        /** A closed code ({@code key_missing}, {@code key_invalid}, {@code key_perms}, {@code key_mismatch}) or null. */
        String keyCode();

        boolean bridgeEnabled();

        boolean stripRegisterChannel();

        int invokeAllowListSize();

        /** The patched Bridge (2.6p items 1 to 4, build id present). */
        boolean bridgeHardened();

        /** The patched Discord embeds (2.6p item 6, build id present). */
        boolean discordStripped();

        boolean reportCoords();

        boolean debugLogs();

        boolean terminalDebugLogs();

        boolean packetLog();

        boolean replayMod();

        /** The log filter is on all three appenders. */
        boolean logFilterInstalled();

        /** The viewer API (enabled or control) is on, or its setting cannot be read: it serves positions and runs commands as the terminal. */
        boolean viewerOn();

        /** The plugin's whisper search is on (it answers whispers with positions). */
        boolean whisperSearch();

        /** server.verifyUsers is off while farmOwners names are used: a connection's name could be forged. */
        boolean ownersUnverified();

        boolean rotateBeforeInteract();

        boolean antiAfk();

        boolean visualRange();

        boolean autoTotem();

        boolean autoEat();

        boolean autoDisconnect();

        /** Names of the modules in the D6 conflict list (and the sorter) that are on. */
        List<String> conflictingModules();
    }

    /**
     * The first wrong row of the table, as a code, or null when the CT is set up. Order: key, bridge, discord, logging, rotation,
     * module conflicts, safety modules.
     */
    public static String preflight(View v) {
        String key = v.keyCode();
        if (key != null) return key;
        if (v.viewerOn()) return "unsafe_viewer";
        // Bridge on is not checked here: with it off the addon sends nothing (C1), and the proxy answers no hello
        if (!v.stripRegisterChannel() || v.invokeAllowListSize() != 0 || !v.bridgeHardened() || v.ownersUnverified()) return "unsafe_bridge";
        if (v.reportCoords() || !v.discordStripped()) return "unsafe_discord";
        if (v.debugLogs() || v.terminalDebugLogs() || v.packetLog() || v.replayMod() || v.visualRange() || !v.logFilterInstalled()) return "unsafe_logging";
        if (!v.rotateBeforeInteract()) return "unsafe_rotation";
        if (v.antiAfk() || v.whisperSearch() || !v.conflictingModules().isEmpty()) return "module_conflict";
        if (!v.autoTotem() || !v.autoEat() || !v.autoDisconnect()) return "guard_off";
        return null;
    }
}
