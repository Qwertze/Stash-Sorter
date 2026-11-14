package com.porter.qwertestash.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.aquarius.command.api.CommandSources;
import com.porter.qwertestash.QwerteStashPlugin;
import com.porter.qwertestash.StashConfig;
import com.porter.qwertestash.manager.ConfigSchema;
import com.porter.qwertestash.manager.DashboardCommandSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** FarmerGuard (P5a, D7), FarmLogGuard (P4, D8), the accept switch, the rate limit and the terminal-only sources. */
class FarmGuardsTest {
    private StashConfig saved;

    @BeforeEach
    void config() {
        saved = QwerteStashPlugin.CONFIG;
        QwerteStashPlugin.CONFIG = new StashConfig();
        FarmCommandSupport.viewerControl = () -> false;
    }

    @AfterEach
    void restore() {
        QwerteStashPlugin.CONFIG = saved;
        FarmCommandSupport.viewerControl = ProxyFarmView::viewerControlNow;
    }

    // ---- FarmerGuard: the feed gates ---------------------------------------------------------------------------------

    @Test
    void everyFeedGateIsOffWhileTheFarmerRoleIsOnAndOnWhileItIsOff() {
        QwerteStashPlugin.CONFIG.farmerEnabled = false;
        assertTrue(FarmerGuard.gates().values().stream().allMatch(b -> b));
        QwerteStashPlugin.CONFIG.farmerEnabled = true;
        assertEquals(7, FarmerGuard.gates().size());
        for (var e : FarmerGuard.gates().entrySet()) assertFalse(e.getValue(), e.getKey() + " must be off for the farmer");
        assertFalse(FarmerGuard.allowDashboardLink());
        assertFalse(FarmerGuard.allowMapTiles());
        assertFalse(FarmerGuard.allowDeliveryContents());
        assertFalse(FarmerGuard.allowKitReport());
        assertFalse(FarmerGuard.allowPearlHeartbeatFields());
        assertFalse(FarmerGuard.allowIndexAndHeartbeat());
        assertFalse(FarmerGuard.allowViewerFeed());
    }

    @Test
    void noFarmSettingIsShownOrChangedFromTheDashboard() {
        var built = ConfigSchema.build(new StashConfig(), ConfigSchema.PLUGIN);
        String all = built.schema().toString() + built.values().toString();
        assertFalse(all.contains("farm"), "the plugin scope must not expose farm values");
        assertNull(ConfigSchema.find(new StashConfig(), "farmerEnabled"));
        assertNull(ConfigSchema.find(new StashConfig(), "farmOwners"));
    }

    /** A CT that is set up right; each test flips one row. */
    static class View implements FarmerGuard.View {
        String key;
        boolean viewer, whisper, unverified;
        boolean strip = true, hardened = true, stripped = true, report, debug, tdebug, plog, replay, filter = true, rotate = true, afk, vr, totem = true, eat = true, disc = true;
        int allow;
        List<String> conflicts = List.of();

        public String keyCode() {
            return key;
        }

        public boolean bridgeEnabled() {
            return true;
        }

        public boolean stripRegisterChannel() {
            return strip;
        }

        public int invokeAllowListSize() {
            return allow;
        }

        public boolean bridgeHardened() {
            return hardened;
        }

        public boolean discordStripped() {
            return stripped;
        }

        public boolean reportCoords() {
            return report;
        }

        public boolean debugLogs() {
            return debug;
        }

        public boolean terminalDebugLogs() {
            return tdebug;
        }

        public boolean packetLog() {
            return plog;
        }

        public boolean replayMod() {
            return replay;
        }

        public boolean logFilterInstalled() {
            return filter;
        }

        public boolean viewerOn() {
            return viewer;
        }

        public boolean whisperSearch() {
            return whisper;
        }

        public boolean ownersUnverified() {
            return unverified;
        }

        public boolean rotateBeforeInteract() {
            return rotate;
        }

        public boolean antiAfk() {
            return afk;
        }

        public boolean visualRange() {
            return vr;
        }

        public boolean autoTotem() {
            return totem;
        }

        public boolean autoEat() {
            return eat;
        }

        public boolean autoDisconnect() {
            return disc;
        }

        public List<String> conflictingModules() {
            return conflicts;
        }
    }

    @Test
    void theSetupTableGivesTheContractsCodeForEveryRow() {
        assertNull(FarmerGuard.preflight(new View()));
        View v = new View();
        v.key = "key_perms";
        assertEquals("key_perms", FarmerGuard.preflight(v));
        v = new View();
        v.strip = false;
        assertEquals("unsafe_bridge", FarmerGuard.preflight(v));
        v = new View();
        v.allow = 4;
        assertEquals("unsafe_bridge", FarmerGuard.preflight(v), "the invoke allow list must be empty");
        v = new View();
        v.hardened = false;
        assertEquals("unsafe_bridge", FarmerGuard.preflight(v), "the 2.6p Bridge fixes are missing");
        v = new View();
        v.report = true;
        assertEquals("unsafe_discord", FarmerGuard.preflight(v));
        v = new View();
        v.stripped = false;
        assertEquals("unsafe_discord", FarmerGuard.preflight(v), "an unpatched Discord build");
        for (int i = 0; i < 6; i++) {
            v = new View();
            switch (i) {
                case 0 -> v.debug = true;
                case 1 -> v.tdebug = true;
                case 2 -> v.plog = true;
                case 3 -> v.replay = true;
                case 4 -> v.vr = true;
                default -> v.filter = false;
            }
            assertEquals("unsafe_logging", FarmerGuard.preflight(v), "row " + i);
        }
        v = new View();
        v.viewer = true;
        assertEquals("unsafe_viewer", FarmerGuard.preflight(v), "the viewer API serves positions and runs commands as the terminal");
        v = new View();
        v.whisper = true;
        assertEquals("module_conflict", FarmerGuard.preflight(v), "whisper search answers with positions");
        v = new View();
        v.unverified = true;
        assertEquals("unsafe_bridge", FarmerGuard.preflight(v), "owner names are not verified");
        v = new View();
        v.rotate = false;
        assertEquals("unsafe_rotation", FarmerGuard.preflight(v));
        v = new View();
        v.afk = true;
        assertEquals("module_conflict", FarmerGuard.preflight(v));
        v = new View();
        v.conflicts = List.of("KillAura");
        assertEquals("module_conflict", FarmerGuard.preflight(v));
        for (int i = 0; i < 3; i++) {
            v = new View();
            if (i == 0) v.totem = false;
            else if (i == 1) v.eat = false;
            else v.disc = false;
            assertEquals("guard_off", FarmerGuard.preflight(v), "safety module " + i);
        }
    }

    @Test
    void theBuildIdsAreReadByReflectionAndAnUnpatchedProxyIsTold() {
        assertNull(BuildIds.read("no.such.Class", "X"));
        assertNull(BuildIds.read(BuildIds.BRIDGE_CLASS, "NO_SUCH_FIELD"));
        assertFalse(BuildIds.bridgeOk(null));
        assertFalse(BuildIds.bridgeOk("2.5"));
        assertTrue(BuildIds.bridgeOk("2.6p-2"));
        assertFalse(BuildIds.bridgeOk("2.6p-1"), "2.6p-1 has no proxy hello on login (patch 8)");
        assertFalse(BuildIds.bridgeOk("2.6p"), "a full-string match: not just a prefix");
        assertFalse(BuildIds.bridgeOk("2.6p-3"));
        assertFalse(BuildIds.bridgeOk("2.6p-2 "));
        assertFalse(BuildIds.discordOk(null));
        assertTrue(BuildIds.discordOk("2.6p-1"));
        assertFalse(BuildIds.discordOk("2.6p-2"));
        assertFalse(BuildIds.discordOk("2.6p"));
        // the jar these tests run against is the unpatched one: the marker is missing
        assertFalse(BuildIds.bridgeHardened());
        assertFalse(BuildIds.discordStripped());
    }

    // ---- FarmLogGuard ------------------------------------------------------------------------------------------------

    private static final String[][] POSITION_LINES = {
        {"Client", "Server teleport 42 to: 1.5, 64.0, -3.2d"},
        {"Client", "Server teleport 7 to: 123456.5, 64.0, -987654.25d"},
        {"Pathfinder", "All done. At GoalBlock{x=12, y=64, z=-30}"},
        {"Pathfinder", "All done. At GoalXZ{x=12, z=-30}"},
        {"Module", "[VisualRange] Notch entered visual range [12.5, 64.0, -30.25]"},
        {"Module", "[VisualRange] Notch left visual range [12, 64, -30]"},
        {"Module", "[VisualRange] Notch logged out in visual range [1.5, 64.0, 3.5]"},
        {"Module", "[CoordObfuscation] Offset for Steve: 12345, -6789"},
        {"Module", "[CoordObfuscation] Generated random pos: 12345 -6789"},
        {"Module", "[Bot] Click attacking entity: ZOMBIE [1.5, 64.0, -3.2]"},
        {"Module", "[Bot] Right click MAIN_HAND block at: [12, 64, -30]"},
        {"Module", "[Bot] [1699999999999] [12, 64, -30] StartDestroyBlock START: Instant break"},
        {"Module", "[Bot] [1699999999999] [12, 64, -30] ContinueDestroyBlock FINISH"},
        {"Module", "[Bot] [1699999999999] [1.5, 64.0, -3.2] Attack Entity"},
        {"Server", "Sending player position: (x=1.50, y=64.00, z=-3.20, yaw=0.00, pitch=0.00)"},
        {"Client", "Received ServerUpdateTileEntityPacket for chunk column that does not exist: [12, 64, -30], data: {}"},
        {"Other", "somebody said 100 64 -200 in chat"},
        {"Other", "at 12.5, 64.0, -30.5 now"},
        {"Other", "x=12 y=64 z=-30"}
    };

    private static final String[][] FINE_LINES = {
        {"Client", "Connecting to the server"},
        {"Module", "[Bridge] ProxyBridge detected on connection Someone"},
        {"Pathfinder", "All done."},
        {"Plugin", "Farm: session started: mode=custom hash=1a2b3c4d"},
        {"Plugin", "Farm: session ended: code=time_up loops=12 recoveries=0"},
        {"Plugin", "Farm upload stored: mode=custom ticks=2400 loop=2120 anchors=4 unsupported=0 hash=1a2b3c4d code=stored"}
    };

    @Test
    void everyPositionBearingLineTheSpikeFoundIsDeniedAndTheRestPasses() {
        for (String[] l : POSITION_LINES) assertTrue(FarmLogGuard.denies(l[0], l[1]), "must be denied: " + l[1]);
        for (String[] l : FINE_LINES) assertFalse(FarmLogGuard.denies(l[0], l[1]), "must pass: " + l[1]);
    }

    @Test
    void noPositionReachesTheTerminalTheFileOrTheDebugFileAppender() {
        LoggerContext ctx = new LoggerContext();
        ctx.start();
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.TRACE);
        List<ListAppender<ILoggingEvent>> apps = new ArrayList<>();
        for (String name : FarmLogGuard.APPENDERS) {
            ListAppender<ILoggingEvent> a = new ListAppender<>();
            a.setContext(ctx);
            a.setName(name);
            a.start();
            root.addAppender(a);
            apps.add(a);
        }
        assertEquals(List.of(), FarmLogGuard.installOn(ctx, () -> true));
        for (String[] l : POSITION_LINES) ctx.getLogger(l[0]).info(l[1]);
        for (String[] l : POSITION_LINES) ctx.getLogger(l[0]).debug(l[1]);
        for (String[] l : POSITION_LINES) ctx.getLogger(l[0]).warn(l[1]);
        for (String[] l : FINE_LINES) ctx.getLogger(l[0]).info(l[1]);
        for (ListAppender<ILoggingEvent> a : apps) {
            assertEquals(FINE_LINES.length, a.list.size(), a.getName() + " must hold only the fine lines");
            for (ILoggingEvent e : a.list) assertFalse(FarmLogGuard.denies(e.getLoggerName(), e.getFormattedMessage()));
        }
    }

    @Test
    void withTheFarmerRoleOffTheFilterChangesNothing() {
        LoggerContext ctx = new LoggerContext();
        ctx.start();
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> a = new ListAppender<>();
        a.setContext(ctx);
        a.setName("ASYNC_TERMINAL");
        a.start();
        root.addAppender(a);
        List<String> missing = FarmLogGuard.installOn(ctx, () -> false);
        assertEquals(List.of("ASYNC_FILE", "ASYNC_DEBUG_FILE"), missing, "a missing appender is reported (the farmer then refuses unsafe_logging)");
        ctx.getLogger("Client").info("Server teleport 1 to: 1.5, 64.0, -3.2d");
        assertEquals(1, a.list.size());
    }

    // ---- accept switch, rate, sources --------------------------------------------------------------------------------

    @Test
    void theSwitchIsOffByDefaultTerminalOnlyBoundedAndExpires() {
        AcceptSwitch s = new AcceptSwitch();
        assertFalse(s.on(0));
        for (AcceptSwitch.Source src : new AcceptSwitch.Source[] {AcceptSwitch.Source.DASHBOARD, AcceptSwitch.Source.DISCORD,
            AcceptSwitch.Source.CONSOLE_ROUTE, AcceptSwitch.Source.BRIDGE, AcceptSwitch.Source.OTHER}) {
            assertEquals("source_refused", s.enable(src, 5, 0));
            assertFalse(s.on(0));
        }
        assertEquals("bad_minutes", s.enable(AcceptSwitch.Source.TERMINAL, 0, 0));
        assertEquals("bad_minutes", s.enable(AcceptSwitch.Source.TERMINAL, 11, 0));
        assertNull(s.enable(AcceptSwitch.Source.TERMINAL, 10, 1000));
        assertTrue(s.on(1000 + 10 * 60_000L - 1));
        assertFalse(s.on(1000 + 10 * 60_000L), "it turns itself off when the minutes run out");
        assertNull(s.enable(AcceptSwitch.Source.TERMINAL, 3, 0));
        s.uploaded();
        assertFalse(s.on(1), "and after one successful upload");
    }

    @Test
    void theRateLimitIsFourBeginsInTenMinutes() {
        UploadRate r = new UploadRate();
        for (int i = 0; i < 4; i++) assertTrue(r.tryAcquire(i));
        assertFalse(r.tryAcquire(5));
        assertFalse(r.tryAcquire(UploadRate.WINDOW_MS - 1));
        assertTrue(r.tryAcquire(UploadRate.WINDOW_MS + 1));
    }

    @Test
    void onlyTheTerminalMayAcceptAndRunStashFarm() {
        assertEquals(AcceptSwitch.Source.TERMINAL, FarmCommandSupport.sourceOf(CommandSources.TERMINAL));
        assertEquals(AcceptSwitch.Source.DISCORD, FarmCommandSupport.sourceOf(CommandSources.DISCORD));
        assertEquals(AcceptSwitch.Source.DASHBOARD, FarmCommandSupport.sourceOf(new DashboardCommandSource(o -> {
        })));
        assertEquals(AcceptSwitch.Source.OTHER, FarmCommandSupport.sourceOf(CommandSources.PLAYER));
        assertEquals(AcceptSwitch.Source.OTHER, FarmCommandSupport.sourceOf("cmd/invoke"));
        assertTrue(FarmCommandSupport.farmAllowed(CommandSources.TERMINAL));
        assertFalse(FarmCommandSupport.farmAllowed(CommandSources.DISCORD));
        assertFalse(FarmCommandSupport.farmAllowed(new DashboardCommandSource(o -> {
        })));
        // STOP from anywhere but the dashboard; resume from the terminal and Discord only
        assertTrue(FarmCommandSupport.stopAllowed(CommandSources.DISCORD));
        assertFalse(FarmCommandSupport.stopAllowed(new DashboardCommandSource(o -> {
        })));
        assertTrue(FarmCommandSupport.resumeAllowed(CommandSources.TERMINAL));
        assertTrue(FarmCommandSupport.resumeAllowed(CommandSources.DISCORD));
        assertFalse(FarmCommandSupport.resumeAllowed(CommandSources.PLAYER));
        assertFalse(FarmCommandSupport.resumeAllowed(new DashboardCommandSource(o -> {
        })));
    }

    // ---- safety -----------------------------------------------------------------------------------------------------

    private static FarmSafety.Sample sample(double health, int food, boolean hasFood, boolean totem, int free, Double... dist) {
        return new FarmSafety.Sample(health, food, hasFood, totem, free, List.of(dist));
    }

    @Test
    void aPlayerWithinEightBlocksPausesInputsAndOneWithinTheRadiusForTheSecondsEndsPlayerNear() {
        FarmSafety s = new FarmSafety(FarmSafety.Thresholds.DEFAULT);
        assertFalse(s.evaluate(sample(20, 20, true, true, 10, 30.0), 0).pauseInputs());
        assertTrue(s.evaluate(sample(20, 20, true, true, 10, 7.0), 1000).pauseInputs());
        assertNull(s.evaluate(sample(20, 20, true, true, 10, 12.0), 5000).endCode());
        assertNull(s.evaluate(sample(20, 20, true, true, 10, 12.0), 10_999).endCode());
        assertEquals("player_near", s.evaluate(sample(20, 20, true, true, 10, 12.0), 11_000).endCode());
        FarmSafety t = new FarmSafety(FarmSafety.Thresholds.DEFAULT);
        t.evaluate(sample(20, 20, true, true, 10, 12.0), 0);
        t.evaluate(sample(20, 20, true, true, 10, 40.0), 9_000); // left: the timer starts over
        assertNull(t.evaluate(sample(20, 20, true, true, 10, 12.0), 12_000).endCode());
    }

    @Test
    void lowHealthNeedsToBeFallingAndFoodNeedsNothingToEat() {
        FarmSafety s = new FarmSafety(FarmSafety.Thresholds.DEFAULT);
        assertNull(s.evaluate(sample(8, 20, true, true, 10), 0).endCode(), "low but not known to be falling");
        assertEquals("low_health", s.evaluate(sample(7, 20, true, true, 10), 50).endCode());
        FarmSafety up = new FarmSafety(FarmSafety.Thresholds.DEFAULT);
        up.evaluate(sample(5, 20, true, true, 10), 0);
        assertNull(up.evaluate(sample(6, 20, true, true, 10), 50).endCode(), "rising again");
        FarmSafety f = new FarmSafety(FarmSafety.Thresholds.DEFAULT);
        assertNull(f.evaluate(sample(20, 5, true, true, 10), 0).endCode(), "AutoEat has food");
        assertEquals("no_food", f.evaluate(sample(20, 5, false, true, 10), 50).endCode());
    }

    @Test
    void noTotemForTwoSecondsAndNoFreeSlotForThirty() {
        FarmSafety s = new FarmSafety(FarmSafety.Thresholds.DEFAULT);
        assertNull(s.evaluate(sample(20, 20, true, false, 10), 0).endCode());
        assertNull(s.evaluate(sample(20, 20, true, false, 10), 1_999).endCode());
        assertEquals("no_totem", s.evaluate(sample(20, 20, true, false, 10), 2_000).endCode());
        FarmSafety n = new FarmSafety(new FarmSafety.Thresholds(10, 6, 0, 16, 10, 90));
        assertNull(n.evaluate(sample(20, 20, true, false, 10), 0).endCode(), "no totem required");
        FarmSafety i = new FarmSafety(FarmSafety.Thresholds.DEFAULT);
        i.evaluate(sample(20, 20, true, true, 0), 0);
        assertNull(i.evaluate(sample(20, 20, true, true, 0), 29_999).endCode());
        assertEquals("inventory_full", i.evaluate(sample(20, 20, true, true, 0), 30_000).endCode());
    }

    // ---- journal ----------------------------------------------------------------------------------------------------

    @Test
    void theJournalKeepsTheStopFlagAcrossARestartAndHoldsNoPosition(@TempDir Path dir) throws Exception {
        SessionJournal j = new SessionJournal(dir.resolve("farm").resolve("session.json"));
        j.save(new SessionJournal.State("f-1a2b3c4d", "t-0123456789ab", 2, 7, "a1", 140, 1, false, false).withStop(true, true));
        SessionJournal again = new SessionJournal(dir.resolve("farm").resolve("session.json"));
        SessionJournal.State s = again.load();
        assertTrue(s.stopped());
        assertTrue(s.localStop());
        assertEquals(7, s.loops());
        String text = java.nio.file.Files.readString(dir.resolve("farm").resolve("session.json"));
        assertFalse(text.contains("origin") || text.contains("\"x\"") || text.contains("pos"));
        assertFalse(FarmLogGuard.denies("Plugin", text), "the journal holds nothing a position filter would stop");
    }

    // ---- review fixes --------------------------------------------------------------------------------------------

    @Test
    void whileTheViewerCanRunCommandsNoFarmOrResumeCommandRunsButStopStill() {
        FarmCommandSupport.viewerControl = () -> true;
        assertFalse(FarmCommandSupport.farmAllowed(CommandSources.TERMINAL));
        assertFalse(FarmCommandSupport.resumeAllowed(CommandSources.TERMINAL));
        assertFalse(FarmCommandSupport.resumeAllowed(CommandSources.DISCORD));
        assertTrue(FarmCommandSupport.stopAllowed(CommandSources.TERMINAL), "a STOP is a safety command and stays available");
    }

    private static LoggerContext contextWith(String... appenderNames) {
        LoggerContext ctx = new LoggerContext();
        ctx.start();
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        for (String n : appenderNames) {
            ListAppender<ILoggingEvent> a = new ListAppender<>();
            a.setContext(ctx);
            a.setName(n);
            a.start();
            root.addAppender(a);
        }
        return ctx;
    }

    @Test
    void aMissingAppenderIsReportedOnEveryInstallNotJustTheFirst() {
        LoggerContext ctx = contextWith("ASYNC_TERMINAL", "ASYNC_FILE"); // no debug file appender
        assertEquals(List.of("ASYNC_DEBUG_FILE"), FarmLogGuard.installOn(ctx, () -> true));
        assertEquals(List.of("ASYNC_DEBUG_FILE"), FarmLogGuard.installOn(ctx, () -> true), "a second call still says it");
        assertFalse(FarmLogGuard.attachedEverywhere(ctx));
        // installing twice never stacks a second filter
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
        assertEquals(1, root.getAppender("ASYNC_TERMINAL").getCopyOfAttachedFiltersList().size());
        // the appender appears later (a reconfigure): the next install fixes it and the check passes
        ListAppender<ILoggingEvent> late = new ListAppender<>();
        late.setContext(ctx);
        late.setName("ASYNC_DEBUG_FILE");
        late.start();
        root.addAppender(late);
        assertEquals(List.of(), FarmLogGuard.installOn(ctx, () -> true));
        assertTrue(FarmLogGuard.attachedEverywhere(ctx));
    }

    @Test
    void aLogbackReconfigureThatDropsTheFiltersIsSeenByTheCheck() {
        LoggerContext ctx = contextWith(FarmLogGuard.APPENDERS.toArray(new String[0]));
        FarmLogGuard.installOn(ctx, () -> true);
        assertTrue(FarmLogGuard.attachedEverywhere(ctx));
        ctx.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC_FILE").clearAllFilters();
        assertFalse(FarmLogGuard.attachedEverywhere(ctx), "a dropped filter must stop the next session (unsafe_logging)");
        FarmLogGuard.installOn(ctx, () -> true);
        assertTrue(FarmLogGuard.attachedEverywhere(ctx));
    }

    @Test
    void aPositionInTheCauseChainOrInENotationIsDenied() {
        LoggerContext ctx = contextWith(FarmLogGuard.APPENDERS.toArray(new String[0]));
        FarmLogGuard.installOn(ctx, () -> true);
        Exception inner = new IllegalStateException("bad block at 12.5, 64.0, -30.25");
        ctx.getLogger("Plugin").warn("the outer line is harmless", new RuntimeException("wrapped", inner));
        ctx.getLogger("Plugin").info("harmless");
        ListAppender<ILoggingEvent> a = (ListAppender<ILoggingEvent>) ctx.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC_TERMINAL");
        assertEquals(1, a.list.size(), "the line whose cause carries a position is dropped");
        assertTrue(FarmLogGuard.denies("Client", "at 1.5E3, 6.4e+1, -3.2E-2"));
        assertTrue(FarmLogGuard.denies("Client", "Server teleport 3 to: 1.0E2, 64.0, -3.0E1d"));
        assertTrue(FarmLogGuard.denies("Other", "pos 12.5; 64; -30.5"));
        assertFalse(FarmLogGuard.denies("Plugin", "versions 1.21.4, 5.13.0 and 2.6p-1"));
    }

    @Test
    void theJournalFailsClosedWhenItIsUnreadableAndKeepsAFailedWrite(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("farm").resolve("session.json");
        java.nio.file.Files.createDirectories(f.getParent());
        java.nio.file.Files.writeString(f, "{ this is not json");
        SessionJournal j = new SessionJournal(f);
        SessionJournal.State s = j.load();
        assertTrue(s.stopped() && s.localStop(), "unreadable means stopped, and only a local resume clears it");
        assertEquals("journal_unreadable", j.loadError());
        // a write into a place that cannot be written is kept, logged once and retried
        SessionJournal bad = new SessionJournal(dir.resolve("a-file").resolve("x").resolve("session.json"));
        java.nio.file.Files.writeString(dir.resolve("a-file"), "i am a file, not a folder");
        assertFalse(bad.save(SessionJournal.State.EMPTY.withStop(true, true)));
        assertTrue(bad.unsaved());
        assertTrue(bad.takeWriteFailureToLog());
        assertFalse(bad.takeWriteFailureToLog(), "logged once per failure series");
        java.nio.file.Files.delete(dir.resolve("a-file"));
        assertTrue(bad.retry());
        assertFalse(bad.unsaved());
    }

    @Test
    void journalUpdateIsAtomicSoTwoThreadsLoseNoUpdate(@TempDir Path dir) throws Exception {
        SessionJournal j = new SessionJournal(dir.resolve("farm").resolve("session.json"));
        j.save(SessionJournal.State.EMPTY);
        final int n = 100;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        // one thread counts loops (the bot tick's journal save), the other counts recoveries and flips the STOP at the end (the sync worker)
        Thread a = new Thread(() -> {
            await(go);
            for (int i = 0; i < n; i++) j.update(o -> new SessionJournal.State(o.farmId(), o.taskId(), o.epoch(), o.loops() + 1, o.anchor(), o.tick(), o.recoveries(), o.stopped(), o.localStop()));
        });
        Thread b = new Thread(() -> {
            await(go);
            for (int i = 0; i < n; i++) j.update(o -> new SessionJournal.State(o.farmId(), o.taskId(), o.epoch(), o.loops(), o.anchor(), o.tick(), o.recoveries() + 1, o.stopped(), o.localStop()));
            j.update(o -> o.withStop(true, true));
        });
        a.start();
        b.start();
        go.countDown();
        a.join();
        b.join();
        SessionJournal.State s = j.get();
        assertEquals(n, s.loops(), "no loop update was lost");
        assertEquals(n, s.recoveries(), "no recovery update was lost");
        assertTrue(s.stopped() && s.localStop(), "the STOP was not overwritten by a counter save");
        SessionJournal.State disk = new SessionJournal(dir.resolve("farm").resolve("session.json")).load();
        assertEquals(n, disk.loops());
        assertTrue(disk.stopped());
    }

    private static void await(java.util.concurrent.CountDownLatch l) {
        try {
            l.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
