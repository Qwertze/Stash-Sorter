package com.porter.qwertestash.module;

import com.aquarius.event.client.ClientBotTick;
import com.aquarius.event.client.ClientDisconnectEvent;
import com.aquarius.module.api.Module;
import com.aquarius.network.codec.PacketHandlerCodec;
import com.aquarius.network.codec.PacketHandlerStateCodec;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.manager.ActivityFeed;
import com.porter.qwertestash.manager.ConfigApplier;
import com.porter.qwertestash.manager.ConfigSchema;
import com.porter.qwertestash.manager.ProxyEnv;
import com.porter.qwertestash.manager.ProxyPolicy;
import com.porter.qwertestash.manager.ConsoleRunner;
import com.porter.qwertestash.manager.DeliveryContents;
import com.porter.qwertestash.manager.KitReport;
import com.porter.qwertestash.manager.DashboardLink;
import com.porter.qwertestash.manager.ManagerHttp;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.manager.ViewerFeed;
import com.porter.qwertestash.manager.ViewerGame;
import com.porter.qwertestash.orders.OrderBlocker;
import com.porter.qwertestash.pearl.AwayGuard;
import com.porter.qwertestash.pearl.PearlTripDriver;
import com.porter.qwertestash.orders.OrderDriver;
import com.porter.qwertestash.orders.OrderFlow;
import com.porter.qwertestash.stash.AnvilSpot;
import com.porter.qwertestash.stash.DeliverySpot;
import com.porter.qwertestash.manager.RecipeStore;
import com.porter.qwertestash.manager.RecipeSync;
import com.porter.qwertestash.orders.Recipes;
import com.porter.qwertestash.stash.CraftSettings;
import com.porter.qwertestash.stash.CraftStore;
import com.porter.qwertestash.stash.KitItems;
import com.porter.qwertestash.stash.KitScore;
import com.porter.qwertestash.stash.KitSettings;
import com.porter.qwertestash.stash.KitStore;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.Stashes;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aquarius.Globals.CACHE;
import static com.github.rfresh2.EventConsumer.of;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;
import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * Pushes an index of the saved stash (the one the sorter and the tracker use) to the Stash Manager web server on the
 * local network, sends a heartbeat every 15 seconds, and re-reads the containers when the website asks for a rescan. It
 * works whether or not sorting is on: a rescan with sorting off drives the sorter in its read-only mode (see
 * {@link StashSorter#drive}).
 * <p>
 * Threads: the tick (ClientBotTick) does all the deciding and touches all the state below. The index is built and the
 * requests are made on two small daemon workers, from plain data only; whatever they find goes back through
 * {@link #results} and is handled on the next tick. A generation number, bumped whenever the module is turned off or the
 * bot disconnects, makes late replies from before that be dropped.
 */
public class StashManager extends Module {
    private static final long HEARTBEAT_MS = 15_000;
    /** The heartbeat while a pearl trip is on: the server needs T's steps and T needs the server's go. */
    private static final long PEARL_BEAT_MS = 3_000;
    private static final long REBUILD_MS = 60_000;
    private static final long MIN_PUSH_GAP_MS = 5_000;
    private static final long BACKOFF_MIN_MS = 5_000, BACKOFF_MAX_MS = 60_000;
    private static final long CONFLICT_RETRY_MS = 60_000;
    private static final int MAX_REPLY = 1 << 20;
    private static final String PREFIX = "Stash Manager: ";

    private static ThreadPoolExecutor worker(String name) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
        // The threads go away when idle, so nothing of this plugin is kept alive after it is unloaded
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private final ThreadPoolExecutor builder = worker("qwerte-stash-manager-build");
    private final ThreadPoolExecutor io = worker("qwerte-stash-manager-http");
    private final ConcurrentLinkedQueue<Runnable> results = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean building = new AtomicBoolean();
    private volatile int generation;

    // Tick thread only
    private ManagerIndex.@Nullable Built current;
    private long fingerprint = Long.MIN_VALUE, lastBuild, lastPushAt;
    private int scanTicks;
    private boolean inFlight;
    private long lastHeartbeat, nextAttemptAt, backoff;
    private @Nullable String lastPushedId;
    private boolean needIndex;
    private String lastLogged = "";
    private String sentSig = "";
    private boolean warnedKey, warnedUrl;
    // The stash name is taken by another player: nothing is pushed until the retry time, then one heartbeat tries again
    private @Nullable String conflictOwner;
    private long conflictRetryAt;
    private String conflictAlerted = "";
    // Website rescan: the id handled and not yet acknowledged to the server (or -1), the last one acted on (kept over a
    // reset so one that was not acknowledged yet is not done twice), whether the read is under way, and whether we started
    // the sorter's read-only loop for it
    private long rescanDone = -1;
    private long lastRescanId = -1, lastRescanAt;
    private boolean rescanning, engaged;
    private @Nullable String rescanBlocked;

    // Stash orders (tick thread): the protocol side of the order in hand, the reason orders can't be taken (worked out once a
    // second), and whether the sorter is being driven for an order
    private @Nullable OrderDriver driver;
    private @Nullable String blocker = "starting";
    private long blockerAt;
    private String blockerSig = "";
    // What the heartbeat says about naming order shulkers, worked out on the tick once a second
    private volatile ManagerIndex.Naming naming = new ManagerIndex.Naming(false, -1);
    // And about crafting: the setting, and whether a crafting table is set
    private volatile ManagerIndex.Crafting crafting = new ManagerIndex.Crafting(false, false);
    // And about the delivery storage (1.9.0): chests, full ones, whether another can be put up (null: no delivery container set)
    private volatile ManagerIndex.@Nullable DeliveryStorage storage;
    // And what is in those chests (1.11.0): the report as last built (once a second), and when it is sent
    private DeliveryContents.@Nullable Built deliveryReport;
    private long deliveryBuiltAt, deliveryCheckedAt;
    private List<DeliveryContents.Chest> deliveryChests = List.of();
    private String deliveryWho = "";
    private final DeliveryContents.Schedule deliverySchedule = new DeliveryContents.Schedule();
    // Kit sorting (1.14.0, KIT-CONTRACT.md): the report as last built (every few seconds), what the server last said about it, and what was sent
    private KitReport.@Nullable Built kitReport;
    private long kitCheckedAt, kitAttemptAt;
    private String kitWho = "";
    private boolean kitReportKnown, kitAsked;
    private @Nullable String kitSentHash, kitRejectedHash, kitInvalidSaid;
    // Repair passes (1.17.0): the ids acknowledged, whether the server knows the repair report route, and the report's progress
    private volatile long repairRunDone, repairStopDone;
    private boolean repairReportKnown, repairAsked;
    private @Nullable String repairSentHash, repairRejectedHash, repairInvalidSaid;
    private long repairAttemptAt, repairBlockedUntil;
    // After a 409 owner conflict the report is not tried again for this long
    private long kitBlockedUntil;
    // What the report was last built from, and when
    private String kitSig = "";
    private long kitBuiltAt;
    private static final long KIT_CONFLICT_WAIT_MS = 5 * 60_000L;
    private boolean orderDriven;
    private long lastOrderDrive;
    private boolean heartbeatSeen;

    // Pearl travel (1.18.0, PEARL-CONTRACT v1.0): the traveller's trip driver. Tick thread (commands only leave requests). The journal is read now so the away mark
    // protects the stash from the first tick after a restart.
    private final PearlTripDriver pearlDriver = new PearlTripDriver(new PearlWorld(), java.nio.file.Path.of("plugins", "qwerte-stash", "pearl-trip.json"),
        () -> new PearlTripDriver.Config(CONFIG.pearlTravel, CONFIG.pearlGraceSeconds));
    private long joinedAt;
    private boolean rescanAfterReturn;
    private volatile int deaths;

    /** The traveller's trip driver (for `stash pearl`). */
    public PearlTripDriver pearl() {
        return pearlDriver;
    }

    // Crafting from the stash (1.15.0, CRAFT-CONTRACT.md): the recipe table is fetched from the server, not shipped; the website's crafting
    // table arrives in the heartbeat reply. Tick thread, except the fetch itself (a worker).
    private final RecipeSync recipeSync = new RecipeSync();
    private final ThreadPoolExecutor recipeIo = worker("qwerte-stash-manager-recipes");
    private boolean recipeCacheTried, recipeSaid;
    private String recipeWho = "";
    private @Nullable String craftInvalidSaid;
    // An older server (the 1.15.0 one) answers 400 to the newer craftOrders fields: remembered for the session and for that server
    private boolean craftLegacy, craftSentOrders;
    private long craftLegacyAt;
    private static final long CRAFT_LEGACY_RETRY_MS = 10 * 60_000L;
    private @Nullable String craftLegacyFor;
    private static final long CRAFT_SEEN_MS = 15_000;

    /** What `stash orders` shows. Updated on the tick, read from the command thread. */
    public record OrdersStatus(@Nullable String current, int pending, @Nullable String blocker) {
    }

    private volatile OrdersStatus ordersStatus = new OrdersStatus(null, -1, "stash manager not running");

    // Read by the status command from another thread
    private volatile String status = "idle";
    private volatile long lastPushedAtMs;
    private volatile @Nullable String lastError;
    private volatile int itemTypes = -1;
    private volatile String playerShown = "";

    @Override
    public boolean enabledSetting() {
        return CONFIG.manager;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        List<EventConsumer<?>> list = new ArrayList<>();
        list.add(of(ClientBotTick.class, this::onTick));
        list.add(of(ClientDisconnectEvent.class, e -> {
            ActivityFeed.INSTANCE.event("Proxy", "warn", "Disconnected from the server");
            disconnected();
        }));
        // Events for the dashboard's Activity tab. Each is hooked on its own: one the proxy doesn't have just isn't listened to
        hook(list, () -> list.add(of(com.aquarius.event.client.ClientConnectEvent.class, e -> {
            // A new login (also a server switch, which may not pass through a disconnect): the trip driver forgets where the bot was
            pearlDriver.onDisconnect();
            joinedAt = 0;
            ActivityFeed.INSTANCE.event("Proxy", "info", "Connecting to the server");
        })));
        hook(list, () -> list.add(of(com.aquarius.event.client.ClientOnlineEvent.class, e -> ActivityFeed.INSTANCE.event("Proxy", "info", "Online"
            + e.queueWait().map(d -> " after " + d.toMinutes() + " min in the queue").orElse("")))));
        hook(list, () -> list.add(of(com.aquarius.event.client.ClientDeathEvent.class, e -> {
            deaths++;
            ActivityFeed.INSTANCE.event("Proxy", "error", "The bot died");
        })));
        hook(list, () -> list.add(of(com.aquarius.event.queue.QueueStartEvent.class, e -> ActivityFeed.INSTANCE.event("Queue", "info", "Entered the queue"))));
        hook(list, () -> list.add(of(com.aquarius.event.queue.QueueCompleteEvent.class, e -> ActivityFeed.INSTANCE.event("Queue", "info", "Queue finished after " + e.queueDuration().toMinutes() + " min"))));
        hook(list, () -> list.add(of(com.aquarius.event.queue.QueueSkipEvent.class, e -> ActivityFeed.INSTANCE.event("Queue", "info", "Queue skipped"))));
        hook(list, () -> list.add(of(com.aquarius.event.module.AutoReconnectEvent.class, e -> ActivityFeed.INSTANCE.event("Auto Reconnect", "info", "Reconnecting"))));
        hook(list, () -> list.add(of(com.aquarius.event.module.TotemPopEvent.class, e -> ActivityFeed.INSTANCE.event("Combat", "warn", "A totem popped"))));
        hook(list, () -> list.add(of(com.aquarius.event.module.HealthAutoDisconnectEvent.class, e -> ActivityFeed.INSTANCE.event("Auto Disconnect", "error", "Disconnected: health too low"))));
        hook(list, () -> list.add(of(com.aquarius.event.module.QueueWarningEvent.class, e -> ActivityFeed.INSTANCE.event("Queue", "info", "Queue warning"))));
        hook(list, () -> list.add(of(com.aquarius.event.module.SessionTimeLimitWarningEvent.class, e -> ActivityFeed.INSTANCE.event("Session", "warn", "Session time limit is near"))));
        hook(list, () -> list.add(of(com.aquarius.event.module.SpawnPatrolTargetKilledEvent.class, e -> ActivityFeed.INSTANCE.event("Spawn Patrol", "info", "Killed a target"))));
        hook(list, () -> list.add(of(com.aquarius.event.module.ReplayStartedEvent.class, e -> ActivityFeed.INSTANCE.event("Replay", "info", "Recording started"))));
        hook(list, () -> list.add(of(com.aquarius.event.module.ReplayStoppedEvent.class, e -> ActivityFeed.INSTANCE.event("Replay", "info", "Recording stopped"))));
        hook(list, () -> list.add(of(com.aquarius.event.player.PlayerConnectedEvent.class, e -> ActivityFeed.INSTANCE.event("Players", "info", "A player took control"))));
        hook(list, () -> list.add(of(com.aquarius.event.player.PlayerDisconnectedEvent.class, e -> ActivityFeed.INSTANCE.event("Players", "info", "The controlling player left"))));
        hook(list, () -> list.add(of(com.aquarius.event.player.SpectatorConnectedEvent.class, e -> ActivityFeed.INSTANCE.event("Players", "info", "A spectator joined"))));
        hook(list, () -> list.add(of(com.aquarius.event.player.SpectatorDisconnectedEvent.class, e -> ActivityFeed.INSTANCE.event("Players", "info", "A spectator left"))));
        hook(list, () -> list.add(of(com.aquarius.event.update.UpdateAvailableEvent.class, e -> ActivityFeed.INSTANCE.event("Proxy", "info", "An update is available"))));
        // The live viewer: the chat ring (kept always, 50 lines) and the hint that a chunk's columns changed
        hook(list, () -> list.add(of(com.aquarius.event.chat.PublicChatEvent.class, e -> chat("public", name(e.sender()), null, e.message()))));
        hook(list, () -> list.add(of(com.aquarius.event.chat.WhisperChatEvent.class, e -> chat("whisper", name(e.sender()), name(e.receiver()), e.message()))));
        hook(list, () -> list.add(of(com.aquarius.event.chat.SystemChatEvent.class, e -> chat("system", "", null, e.message()))));
        hook(list, () -> list.add(of(com.aquarius.event.chat.DeathMessageChatEvent.class, e -> chat("death", "", null, e.message()))));
        hook(list, () -> list.add(of(com.aquarius.event.client.ChunkDataEvent.class, e -> {
            ViewerFeed v = viewerFeed();
            if (v != null && v.isActive()) v.markDirty(e.x(), e.z());
        })));
        return list;
    }

    private static void chat(String kind, @Nullable String sender, @Nullable String to, @Nullable String message) {
        try {
            ViewerFeed.CHAT.add(kind, sender, to, message);
        } catch (RuntimeException e) {
            // The viewer's chat is best effort
        }
    }

    private static @Nullable String name(@Nullable PlayerListEntry entry) {
        return entry == null ? null : entry.getName();
    }

    private @Nullable ViewerFeed viewerFeed() {
        DashboardLink l = link;
        return l == null ? null : l.viewer();
    }

    /** Block changes mark the chunk for the live viewer. Seen only, never changed; costs one flag read when nobody watches. */
    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("qwerte-stash-viewer")
            .setPriority(-1000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundBlockUpdatePacket.class, (packet, session) -> {
                    try {
                        ViewerFeed v = viewerFeed();
                        if (v != null && v.isActive()) v.markDirty(packet.getEntry().getX() >> 4, packet.getEntry().getZ() >> 4);
                    } catch (RuntimeException e) {
                        // Only a hint
                    }
                    return packet;
                })
                .inbound(ClientboundForgetLevelChunkPacket.class, (packet, session) -> {
                    try {
                        ViewerFeed v = viewerFeed();
                        if (v != null && v.isActive()) v.markUnloaded(packet.getX(), packet.getZ());
                    } catch (RuntimeException e) {
                        // Only a hint
                    }
                    return packet;
                })
                .inbound(ClientboundSectionBlocksUpdatePacket.class, (packet, session) -> {
                    try {
                        ViewerFeed v = viewerFeed();
                        if (v != null && v.isActive()) v.markDirty(packet.getChunkX(), packet.getChunkZ());
                    } catch (RuntimeException e) {
                        // Only a hint
                    }
                    return packet;
                })
                .build())
            .build();
    }

    private static void hook(List<EventConsumer<?>> list, Runnable add) {
        try {
            add.run();
        } catch (RuntimeException | LinkageError e) {
            LOG.debug("{}an activity event is not available: {}", PREFIX, e.toString());
        }
    }

    /** What the plugin itself is doing, for the dashboard's Activity tab. */
    private List<ActivityFeed.Item> ownItems() {
        List<ActivityFeed.Item> out = new ArrayList<>();
        try {
            StashSorter s = sorter();
            if (s.isEnabled()) {
                String line = s.statusLineNoPositions();
                boolean problem = s.problem() != null || s.driveProblem() != null;
                out.add(new ActivityFeed.Item("Stash Sorter", "plugin", line, s.activityDetail(), s.readProgress(), problem ? "warn" : "info"));
                // A craft under way has a card of its own: what is made, from what (no positions)
                String craftTitle = s.craftCardTitle();
                if (craftTitle != null) out.add(new ActivityFeed.Item("Stash Crafting", "plugin", craftTitle, s.craftCardDetail(), null, "info"));
            }
        } catch (RuntimeException | LinkageError e) {
            // The sorter is left out
        }
        String st = status;
        boolean bad = st.startsWith("error") || st.startsWith("offline") || st.contains("signature") || st.contains("unsigned") || st.startsWith("no ") || st.startsWith("bad");
        String err = lastError;
        out.add(new ActivityFeed.Item("Stash Manager", "plugin", "Index link: " + st + "; dashboard: " + dashboardStatus(), err, null, bad ? "warn" : "info"));
        return out;
    }

    @Override
    public void onEnable() {
        reset();
        ActivityFeed.INSTANCE.setOwn(this::ownItems);
        ActivityFeed.INSTANCE.setOrders(this::orderItem);
        updateDashboard();
    }

    @Override
    public void onDisable() {
        // A trip that was about to leave does not hold the sorter once the manager is off
        AwayGuard.quiesce(false);
        ActivityFeed.INSTANCE.setOwn(null);
        ActivityFeed.INSTANCE.setOrders(null);
        // Nobody is left to see the read through: the sorter lets go of everything it holds
        stopDriving();
        forgetOrder();
        reset();
        status = "off";
        ordersStatus = new OrdersStatus(null, -1, "stash manager off");
        updateDashboard();
    }

    // The dashboard link: on while the manager is on and the dashboard setting is
    private volatile DashboardLink link;

    /**
     * Starts or stops the dashboard link to match the settings. Safe from any thread. A link stopped from one of its own
     * jobs (the website turning the dashboard off) ends by itself.
     */
    public synchronized void updateDashboard() {
        try {
            boolean want = isEnabled() && CONFIG.manager && CONFIG.dashboard && com.porter.qwertestash.farm.FarmerGuard.allowDashboardLink();
            if (want) {
                if (link != null && link.isRunning()) return;
                link = new DashboardLink(new LinkHost());
                link.start();
            }
            else if (link != null) {
                link.stop();
                link = null;
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("{}couldn't {} the dashboard link: {}", PREFIX, CONFIG.dashboard ? "start" : "stop", e.toString());
        }
    }

    public synchronized String dashboardStatus() {
        if (!CONFIG.dashboard) return "off";
        if (link == null || !link.isRunning()) return CONFIG.manager ? "not running" : "off (manager off)";
        String error = link.lastError();
        return link.status() + (link.watching() ? ", a browser has this bot open" : "") + (error == null ? "" : " (" + error + ")");
    }

    /** What the dashboard link gets from the plugin. */
    private final class LinkHost implements DashboardLink.Host {
        @Override
        public @Nullable URI base() {
            return parse(CONFIG.managerUrl);
        }

        @Override
        public String key() {
            return CONFIG.managerKey == null ? "" : CONFIG.managerKey.trim();
        }

        @Override
        public String botId() {
            String id = CONFIG.managerBotId;
            if (id == null || id.isBlank()) {
                synchronized (StashManager.this) {
                    if (CONFIG.managerBotId == null || CONFIG.managerBotId.isBlank()) {
                        CONFIG.managerBotId = java.util.UUID.randomUUID().toString();
                        try {
                            com.aquarius.Globals.saveConfigAsync();
                        } catch (RuntimeException | LinkageError e) {
                            LOG.warn("{}couldn't save the bot id: {}", PREFIX, e.toString());
                        }
                    }
                    id = CONFIG.managerBotId;
                }
            }
            return id;
        }

        @Override
        public String player() {
            return playerShown;
        }

        @Override
        public java.util.List<DashboardLink.Scope> scopes() {
            java.util.List<DashboardLink.Scope> out = new java.util.ArrayList<>();
            ConfigApplier.Env own = new ConfigApplier.Env() {
                @Override
                public ConsoleRunner.Outcome command(String line) {
                    return ConsoleRunner.run(line, late -> {
                    });
                }

                @Override
                public void changed(String path) {
                    try {
                        com.aquarius.Globals.saveConfigAsync();
                    } catch (RuntimeException | LinkageError e) {
                        LOG.warn("{}couldn't save the config: {}", PREFIX, e.toString());
                    }
                }
            };
            out.add(new DashboardLink.Scope(DashboardLink.PLUGIN_SCOPE, CONFIG, ConfigSchema.PLUGIN, own, "stash "));
            if (!CONFIG.dashboardProxy) return out;
            try {
                ProxyEnv env = new ProxyEnv(() -> new java.util.ArrayList<>(com.aquarius.Globals.MODULE.getModules()), com.aquarius.Globals::saveConfigAsync);
                out.add(new DashboardLink.Scope("proxy", com.aquarius.Globals.CONFIG, ProxyPolicy.PROXY, env, ""));
                // Sorted by file name so the same id always means the same config; ids are what the server accepts: 1-56 of [A-Za-z0-9_.-], unique, at most 50 scopes in all
                var configs = new java.util.ArrayList<>(com.aquarius.Globals.PLUGIN_MANAGER.getAllPluginConfigs());
                configs.sort(java.util.Comparator.comparing(c -> c.file().getName()));
                java.util.Set<String> used = new java.util.HashSet<>();
                for (var instance : configs) {
                    if (out.size() >= 50) break;
                    String file = instance.file().getName();
                    int dot = file.lastIndexOf('.');
                    String raw = dot > 0 ? file.substring(0, dot) : file;
                    if (raw.equals(com.porter.qwertestash.BuildConstants.PLUGIN_ID) || instance.instance() == null) continue;
                    String id = raw.replaceAll("[^A-Za-z0-9_.\\-]", "_");
                    if (id.length() > 56) id = id.substring(0, 56);
                    if (id.isEmpty()) id = "_";
                    String unique = id;
                    for (int n = 2; !used.add(unique); n++) {
                        String suffix = "-" + n;
                        unique = id.substring(0, Math.min(id.length(), 56 - suffix.length())) + suffix;
                    }
                    out.add(new DashboardLink.Scope("plugin:" + unique, instance.instance(), ProxyPolicy.OTHER_PLUGIN, env, ""));
                }
            } catch (RuntimeException | LinkageError e) {
                LOG.debug("{}couldn't list the proxy's settings: {}", PREFIX, e.toString());
            }
            return out;
        }

        @Override
        public String pluginId() {
            return com.porter.qwertestash.BuildConstants.PLUGIN_ID;
        }

        @Override
        public String pluginVersion() {
            return com.porter.qwertestash.BuildConstants.VERSION;
        }

        @Override
        public String proxyVersion() {
            try {
                String v = com.aquarius.Globals.VERSION;
                return v == null ? "" : v;
            } catch (RuntimeException | LinkageError e) {
                return "";
            }
        }

        @Override
        public String mcVersion() {
            return StashManager.mcVersion();
        }

        @Override
        public boolean consoleAllowed() {
            return CONFIG.dashboardConsole;
        }

        @Override
        public boolean proxyAllowed() {
            return CONFIG.dashboardProxy;
        }

        @Override
        public boolean wanted() {
            return CONFIG.manager && CONFIG.dashboard;
        }

        @Override
        public ViewerFeed.Game viewerGame() {
            return new ViewerGame();
        }
    }

    private void disconnected() {
        pearlDriver.onDisconnect();
        joinedAt = 0;
        ViewerFeed v = viewerFeed();
        if (v != null) v.resync();
        stopDriving();
        forgetOrder();
        reset();
    }

    /**
     * The order in hand is forgotten here, not given up: its plan is saved with the stash, the server still holds it, and a
     * claim after the next start carries on where it was.
     */
    private void forgetOrder() {
        try {
            sorter().orderFlow().reset();
            if (driver != null) driver.reset();
        } catch (RuntimeException e) {
            LOG.debug("{}couldn't park the order: {}", PREFIX, e.toString());
        }
        orderDriven = false;
        heartbeatSeen = false;
        blocker = "starting";
        blockerAt = 0;
    }

    private void stopDriving() {
        try {
            sorter().drive(false);
        } catch (RuntimeException e) {
            LOG.debug("Qwerte Stash: couldn't stop the manager's read", e);
        }
    }

    private static StashSorter sorter() {
        return com.aquarius.Globals.MODULE.get(StashSorter.class);
    }

    /** Drops every bit of session state; replies still on their way are ignored. */
    private void reset() {
        generation++;
        craftLegacy = false;
        craftLegacyFor = null;
        recipeSync.reset();
        results.clear();
        current = null;
        fingerprint = Long.MIN_VALUE;
        lastBuild = 0;
        scanTicks = 0;
        inFlight = false;
        building.set(false);
        lastHeartbeat = 0;
        nextAttemptAt = 0;
        backoff = 0;
        lastPushedId = null;
        needIndex = false;
        deliveryReport = null;
        deliveryBuiltAt = deliveryCheckedAt = 0;
        deliveryChests = List.of();
        deliveryWho = "";
        deliverySchedule.reset();
        sentSig = "";
        lastLogged = "";
        warnedKey = warnedUrl = false;
        conflictOwner = null;
        conflictRetryAt = 0;
        rescanDone = -1;
        rescanning = engaged = false;
        rescanBlocked = null;
        status = "idle";
    }

    // Status, for the command (any thread)

    public String status() {
        return status;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    public long lastPushedAt() {
        return lastPushedAtMs;
    }

    public int itemTypes() {
        return itemTypes;
    }

    public String player() {
        return playerShown;
    }

    /** "set" or "not set": no part of the key is ever shown or logged. */
    public static String maskKey(@Nullable String key) {
        return key == null || key.isBlank() ? "not set" : "set";
    }

    /** A usable http or https base address without a query, fragment, credentials or trailing slash, else null. */
    public static @Nullable URI parse(@Nullable String url) {
        if (url == null) return null;
        try {
            String s = url.trim();
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            URI uri = URI.create(s);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // The tick

    private void onTick(ClientBotTick event) {
        // Replies and builds from the workers
        Runnable job;
        while ((job = results.poll()) != null) {
            try {
                job.run();
            } catch (RuntimeException e) {
                LOG.warn("{}couldn't handle a reply: {}", PREFIX, e.toString());
            }
        }
        // The farmer role (FARM P5a): no index, no heartbeat, no pearl, delivery or kit report, no map tiles, no viewer: nothing of the stash leaves this bot
        if (!com.porter.qwertestash.farm.FarmerGuard.allowIndexAndHeartbeat()) {
            setStatus("off (farmer role)");
            return;
        }
        // Pearl travel runs before everything that needs the bot in the game: it is the part that notices a disconnect
        boolean inGame = Alerts.online() && !inQueueNow();
        if (inGame && joinedAt == 0) joinedAt = System.currentTimeMillis();
        else if (!inGame) joinedAt = 0;
        try {
            pearlDriver.tick();
        } catch (RuntimeException e) {
            LOG.warn("{}pearl travel: {}", PREFIX, e.toString());
        }
        if (!Alerts.online()) return;
        Alerts.pump(this);

        Stash stash = Stashes.current();
        if (rescanBlocked != null && rescanBlocked.equals(AwayGuard.REASON) && !AwayGuard.away()) {
            rescanBlocked = null;
            lastHeartbeat = 0;
        }
        if (rescanAfterReturn && !AwayGuard.away() && stash != null) {
            rescanAfterReturn = false;
            beginRescan(stash, "back at the stash");
        }
        driveSorter(stash);
        tickOrders(stash);

        String key = CONFIG.managerKey == null ? "" : CONFIG.managerKey.trim();
        if (key.isEmpty()) {
            setStatus("no key");
            if (!warnedKey) {
                warnedKey = true;
                LOG.warn("{}no API key set. Copy it from the Stash Manager server's console and give it to `stash manager key <key>`.", PREFIX);
            }
            return;
        }
        URI base = parse(CONFIG.managerUrl);
        if (base == null) {
            setStatus("bad url");
            if (!warnedUrl) {
                warnedUrl = true;
                LOG.warn("{}the server url must be a http or https address without a query, like http://127.0.0.1:8765 (`stash manager url <url>`).", PREFIX);
            }
            return;
        }
        warnedUrl = false;

        String name = playerName();
        playerShown = name == null ? "" : name;
        if (stash == null) {
            current = null;
            setStatus("no stash");
            return;
        }
        if (name == null) {
            setStatus("no player name");
            return;
        }

        if (scanTicks-- <= 0) {
            scanTicks = Math.max(2, CONFIG.managerScanInterval) * 20;
            scan(stash, name);
        }

        if (inFlight) return;
        long now = System.currentTimeMillis();
        if (now < nextAttemptAt) return;
        ManagerIndex.Built built = current;
        if (built == null || !built.server().equals(ManagerIndex.clamp(stash.server, 256)) || !built.stash().equals(ManagerIndex.clamp(stash.name, 256))
            || !built.player().equals(ManagerIndex.clamp(name, 256))) return;

        refreshDelivery(stash, name, now);
        refreshKits(stash, name, now);
        tickRecipes(base, key, now);

        // Another bot owns this stash name: one heartbeat a minute finds out when that changes
        if (conflictOwner != null) {
            if (now < conflictRetryAt) return;
            beat(base, key, built, now);
            return;
        }

        String id = built.server() + "|" + built.stash() + "|" + built.hash() + "|" + built.known() + "/" + built.total() + "/" + built.stale();
        // Away on a pearl trip the index is not pushed (the stash is not being kept up to date), only the heartbeat goes
        if (!AwayGuard.away() && (needIndex || !id.equals(lastPushedId))) {
            // Containers read during a rescan change the counts: those pushes are spaced out
            if (!needIndex && lastPushedId != null && now - lastPushAt < MIN_PUSH_GAP_MS) return;
            lastHeartbeat = now;
            lastPushAt = now;
            send(base, "/api/v1/index", built.json(), key, (code, body) -> {
                if (code != 200) return false;
                lastPushedId = id;
                needIndex = false;
                itemTypes = built.itemTypes();
                lastPushedAtMs = System.currentTimeMillis();
                return true;
            });
        }
        else if (now - lastHeartbeat >= (pearlDriver.active() ? PEARL_BEAT_MS : HEARTBEAT_MS) || !sig().equals(sentSig)) beat(base, key, built, now);
        else if (!orderStep(base, key, built, now) && !inFlight) {
            // Last in line: the index, the heartbeat and the order's requests all go first
            if (deliveryReport != null && deliverySchedule.due(scheduleKey(deliveryReport), now)) sendDelivery(base, key, deliveryReport, now);
            else if (kitReportDue(built, now)) sendKits(base, key, kitReport, now);
            else if (repairReportDue(now)) sendRepair(base, key, built, now);
        }
    }

    // Repair passes (1.17.0, REPAIR-CONTRACT v2): the heartbeat's repairHash and repairPass, the reply's settings, run and stop, and the report

    /** The repair keys of the heartbeat: none with repair passes off here; else the hash of the cached settings (null: none) and the pass state. Tick thread. */
    private ManagerIndex.@Nullable RepairHb repairBody(ManagerIndex.Built built) {
        if (!CONFIG.repairPass) return null;
        repairIdsSync(built);
        com.porter.qwertestash.stash.RepairSettings cached = com.porter.qwertestash.stash.RepairStore.shared().get(built.server(), built.stash());
        com.porter.qwertestash.orders.RepairPassReport r = sorter().repairReportNow();
        return new ManagerIndex.RepairHb(cached == null ? null : cached.hash(), sorter().repairBeat(repairRunDone, repairStopDone, r == null ? null : r.hash()));
    }

    private @Nullable String repairIdsFor;

    /** The acknowledged ids are kept per stash on disk: after a restart the bot sends its real values and is not asked to run an old request again. */
    private void repairIdsSync(ManagerIndex.Built built) {
        String who = built.server() + "|" + built.stash();
        if (who.equals(repairIdsFor)) return;
        repairIdsFor = who;
        long[] ids = com.porter.qwertestash.stash.RepairStore.shared().ids(built.server(), built.stash());
        repairRunDone = ids[0];
        repairStopDone = ids[1];
    }

    /** What a heartbeat reply says about repair: new settings are checked, cached and the sorter woken; a run or stop is queued and acknowledged. Tick thread. */
    private void repairReplied(ManagerIndex.Built built, String body) {
        if (!CONFIG.repairPass) return;
        repairIdsSync(built);
        ManagerIndex.RepairReply reply = ManagerIndex.repairReply(body);
        // Only a server that answers with a repairHash key knows passes (an older server: the cache is kept, nothing is asked)
        repairReportKnown = reply.known();
        if (!reply.known()) return;
        if (reply.invalid() != null) {
            if (!reply.invalid().equals(repairInvalidSaid)) LOG.warn("{}ignoring repair settings from the server: {}.", PREFIX, reply.invalid());
            repairInvalidSaid = reply.invalid();
        }
        else if (reply.settings() != null) {
            repairInvalidSaid = null;
            if (com.porter.qwertestash.stash.RepairStore.shared().put(built.server(), built.stash(), reply.settings())) {
                // The sorter plans everything again (routing, candidates)
                Stashes.version++;
                LOG.info("{}repair settings updated from the server.", PREFIX);
            }
        }
        // A run or stop is acknowledged once accepted (queued or acted on), even with repair switched off on the website
        if (reply.run() > repairRunDone) {
            sorter().repairRun();
            repairRunDone = reply.run();
            lastHeartbeat = 0;
            com.porter.qwertestash.stash.RepairStore.shared().putIds(built.server(), built.stash(), repairRunDone, repairStopDone);
        }
        if (reply.stop() > repairStopDone) {
            sorter().repairStop();
            repairStopDone = reply.stop();
            lastHeartbeat = 0;
            com.porter.qwertestash.stash.RepairStore.shared().putIds(built.server(), built.stash(), repairRunDone, repairStopDone);
        }
        repairAsked = reply.needReport();
    }

    /** Whether to send the pass report now: the server knows the route, the report changed (or it asked), at most every 10 s; a refused one is not sent again. */
    private boolean repairReportDue(long now) {
        if (!CONFIG.repairPass || !repairReportKnown || now < repairBlockedUntil || now - repairAttemptAt < 10_000L) return false;
        com.porter.qwertestash.orders.RepairPassReport r = sorter().repairReportNow();
        if (r == null) return false;
        String hash = r.hash();
        if (hash.equals(repairRejectedHash)) return false;
        return repairAsked || !hash.equals(repairSentHash);
    }

    /** POST /api/v1/repair/report, signed like the index. Soft: the answer never feeds the manager's backoff or status. */
    private void sendRepair(URI base, String key, ManagerIndex.Built built, long now) {
        com.porter.qwertestash.orders.RepairPassReport r = sorter().repairReportNow();
        String player = playerName();
        if (r == null || player == null) return;
        repairAttemptAt = now;
        com.google.gson.JsonArray containers = null;
        Stash stash = Stashes.current();
        if (!CONFIG.kitSorting && stash != null) {
            // With no kit report the server takes the pickable containers from here (for the needs-repair chest picker)
            try {
                KitReport.Built kb = KitReport.buildV2(stash, CONFIG.managerIncludeExcluded, player, List.of(), KitScore.Scoring.DEFAULT, KitItems.shared(), null);
                containers = com.google.gson.JsonParser.parseString(new String(kb.json(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("containers");
            } catch (RuntimeException e) {
                containers = null;
            }
        }
        String hash = r.hash();
        send(base, "/api/v1/repair/report", r.body(built.server(), built.stash(), player, containers), key, (code, body) -> {
            if (code == 200) {
                repairSentHash = hash;
                repairRejectedHash = null;
                repairAsked = false;
            }
            else if (code == 400 || code == 413) repairRejectedHash = hash;
            else if (code == 409) repairBlockedUntil = System.currentTimeMillis() + 5 * 60_000L;
            return true;
        }, true);
    }

    private String sig() {
        return rescanDone + "|" + rescanBlocked + "|" + blockerSig + "|" + pearlDriver.signature();
    }

    private void beat(URI base, String key, ManagerIndex.Built built, long now) {
        lastHeartbeat = now;
        String sig = sig();
        long done = rescanDone;
        DeliverySpot.Spot spot = DeliverySpot.get();
        ManagerIndex.Delivery delivery = spot == null ? null : new ManagerIndex.Delivery(spot.x(), spot.y(), spot.z(), spot.dimension());
        // A different server is asked afresh
        if (craftLegacyFor != null && !craftLegacyFor.equals(String.valueOf(base))) craftLegacy = false;
        // An older server may have been updated since: the newer shape is tried again every 10 minutes
        if (craftLegacy && now - craftLegacyAt >= CRAFT_LEGACY_RETRY_MS) craftLegacy = false;
        ManagerIndex.Craft craftNow = craftBody(built);
        ManagerIndex.AreaReport areaNow = areaBody();
        String sentAreaHash = areaNow == null ? null : areaNow.hash();
        craftSentOrders = craftNow.orders() != null;
        ManagerIndex.RepairHb repairNow = repairBody(built);
        PearlTripDriver.ManagerBody pearlBody = pearlDriver.body();
        ManagerIndex.PearlHb pearlNow = com.porter.qwertestash.farm.FarmerGuard.allowPearlHeartbeatFields() ? new ManagerIndex.PearlHb(pearlBody.object(), pearlBody.clear()) : new ManagerIndex.PearlHb(null, false);
        ManagerIndex.EnchantHb enchantNow = enchantBody();
        send(base, "/api/v1/heartbeat", ManagerIndex.heartbeat(built, done, rescanBlocked, blocker, delivery, naming, crafting, storage, deliveryReport == null ? null : deliveryReport.hash(), kitsBody(built), craftNow, areaNow, repairNow, pearlNow, enchantNow), key, (code, body) -> {
            if (ManagerIndex.craftShapeRefused(code, body, craftSentOrders, craftLegacy)) {
                // Not a failure of the server: it does not know the newer fields. Sent again at once in the 1.15.0 shape, and from now on
                craftLegacy = true;
                craftLegacyFor = String.valueOf(base);
                craftLegacyAt = System.currentTimeMillis();
                LOG.info("{}the server refused the newer craft fields (possibly an older Stash Manager server; update it): using the 1.15.0 shape, the newer one is tried again in 10 minutes.", PREFIX);
                lastHeartbeat = 0;
                return true;
            }
            if (code != 200) return false;
            heartbeatSeen = true;
            driver().onHeartbeat(body);
            pearlReplied(body);
            // The bottles open enchant orders reserve: repair passes keep their hands off them (ENCHANT-CONTRACT v1.0, TL4-B1)
            com.porter.qwertestash.orders.EnchantState.SHARED.onHeartbeat(body);
            sentSig = sig;
            if (done >= 0 && rescanDone == done) {
                rescanDone = -1;
                sentSig = sig();
            }
            needIndex = ManagerIndex.needsIndex(body);
            // Only a server that answers with a needDelivery key (true or false) knows the delivery report
            deliverySchedule.supported(DeliveryContents.supportsDelivery(body));
            if (deliveryReport != null && DeliveryContents.needsDelivery(body)) deliverySchedule.ask();
            kitsReplied(built, body);
            craftReplied(built, body);
            areaReplied(body, sentAreaHash);
            repairReplied(built, body);
            long id = ManagerIndex.rescanId(body);
            if (id >= 0) startRescan(id);
            return true;
        });
    }

    /**
     * Keeps the delivery contents report current. Once a second the chests are looked at (cheap: their read times, fullness and
     * the identity of the arrays the last read replaced); the report is only rebuilt when one of those changed, or every 10 s.
     */
    private void refreshDelivery(Stash stash, String name, long now) {
        if (now - deliveryCheckedAt < 1000) return;
        deliveryCheckedAt = now;
        try {
            if (!DeliverySpot.isSet()) {
                deliveryReport = null;
                deliveryChests = List.of();
                return;
            }
            List<DeliveryContents.Chest> chests = DeliveryContents.collect(stash);
            String who = stash.server + "|" + stash.name + "|" + name;
            if (deliveryReport != null && who.equals(deliveryWho) && chests.equals(deliveryChests) && now - deliveryBuiltAt < 10_000) return;
            deliveryChests = chests;
            deliveryWho = who;
            deliveryBuiltAt = now;
            deliveryReport = DeliveryContents.build(stash.server, stash.name, name, chests);
        } catch (RuntimeException e) {
            LOG.debug("{}couldn't build the delivery contents: {}", PREFIX, e.toString());
            deliveryReport = null;
        }
    }

    /** What was sent is remembered per stash and hash (a different stash with the same contents is still sent). */
    private static String scheduleKey(DeliveryContents.Built r) {
        return r.server() + "|" + r.stash() + "|" + r.hash();
    }

    /**
     * POST /api/v1/delivery, signed like the index. Never part of the index; no positions in it. It is soft: whatever the answer
     * (an old server's unsigned 404 included) it never feeds the manager's backoff or the status; it has a retry timer of its own.
     */
    private final DeliveryContents.SentLog deliveryLog = new DeliveryContents.SentLog();

    private void sendDelivery(URI base, String key, DeliveryContents.Built report, long now) {
        deliverySchedule.attempt(now);
        send(base, "/api/v1/delivery", report.json(), key, (code, body) -> {
            if (code == 200) {
                deliverySchedule.sent(scheduleKey(report));
                if (deliveryLog.sent(System.currentTimeMillis())) LOG.info("{}{}", PREFIX, DeliveryContents.SentLog.line(report.chests()));
            } else if (code == 400 || code == 413) {
                LOG.debug("{}the server refused the delivery contents ({}).", PREFIX, code);
                deliverySchedule.rejected(scheduleKey(report));
                deliveryLog.failed();
            } else {
                deliverySchedule.failed(System.currentTimeMillis());
                deliveryLog.failed();
            }
            return true;
        }, true);
    }

    // Crafting from the stash (1.15.0, CRAFT-CONTRACT.md)

    private String craftSig = "-";
    private long craftSeenAt;
    private List<String> craftSeen = List.of();

    /** Recipe table: loaded from the cache once, fetched at start when there is none and whenever the server's hash (heartbeat reply) differs. Tick thread. */
    private void tickRecipes(URI base, String key, long now) {
        String mc = mcVersion();
        if (!RecipeStore.validMc(mc)) return;
        loadRecipeCache();
        Recipes held = Recipes.current();
        if (!recipeSync.due(now, held == null ? null : held.hash())) return;
        recipeSync.started();
        String heldHash = held == null ? null : held.hash();
        String botId = new LinkHost().botId();
        int gen = generation;
        URI url;
        try {
            url = URI.create(base + "/api/v1/bot/recipes?mc=" + java.net.URLEncoder.encode(mc, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            recipeSync.failed(now, -1);
            return;
        }
        try {
            recipeIo.execute(() -> {
                ManagerHttp.Response response;
                try {
                    response = ManagerHttp.call("GET", url, null, key, botId, 5_000, 20_000, 30_000, null, heldHash == null ? null : RecipeStore.quote(heldHash));
                } catch (Throwable t) {
                    response = new ManagerHttp.Response(-1, "", t.getClass().getSimpleName());
                }
                ManagerHttp.Response done = response;
                results.add(() -> {
                    if (gen != generation) {
                        recipeSync.failed(System.currentTimeMillis(), -1);
                        return;
                    }
                    recipesFetched(mc, done);
                });
            });
        } catch (RuntimeException e) {
            recipeSync.failed(now, -1);
        }
    }

    /** The cached recipe table is loaded once, before anything needs to know whether the bot has one. Tick thread. */
    private void loadRecipeCache() {
        if (recipeCacheTried || Recipes.current() != null) return;
        String mc = mcVersion();
        if (!RecipeStore.validMc(mc)) return;
        recipeCacheTried = true;
        Recipes cached = RecipeStore.shared().load(mc);
        if (cached != null) {
            Recipes.setCurrent(cached);
            LOG.info("{}recipe table for {} loaded from the cache ({} recipes, hash {}).", PREFIX, mc, cached.size(), cached.hash().substring(0, 8));
        }
    }

    private void recipesFetched(String mc, ManagerHttp.Response r) {
        long now = System.currentTimeMillis();
        if (r.code() == 304) {
            recipeSync.succeeded(now);
            return;
        }
        if (r.code() == 200) {
            try {
                Recipes table = RecipeStore.shared().accept(mc, r.body(), r.etag());
                Recipes.setCurrent(table);
                recipeSync.succeeded(now);
                recipeSaid = false;
                LOG.info("{}recipe table for {} fetched ({} recipes, hash {}). Craft orders and shulker box crafting use it.", PREFIX, mc, table.size(), table.hash().substring(0, 8));
                return;
            } catch (RecipeStore.Invalid e) {
                LOG.warn("{}the recipe table from the server was refused: {}.", PREFIX, e.getMessage());
                recipeSync.failed(now, 400);
                return;
            }
        }
        recipeSync.failed(now, r.code());
        // Said once per run of failures (not every retry): without a table nothing is crafted
        if (!recipeSaid && Recipes.current() == null) {
            recipeSaid = true;
            LOG.warn("{}no recipe table: the server answered {} for the recipes of {}. Craft orders and shulker box crafting stay off until it sends one.", PREFIX,
                r.code() < 0 ? "nothing" : r.code(), mc);
        }
    }

    /** What the heartbeat says about craft orders changes: a heartbeat of its own is sent (the table recipe hash, the setting, the table state). */
    private String craftSignature() {
        Recipes table = Recipes.current();
        // The kit capability is part of the signature: a change of it sends a heartbeat of its own
        ManagerIndex.KitCaps caps = kitCaps();
        String kit = caps == null ? "" : "/k" + caps.enabled() + caps.restock() + caps.craft();
        if (table == null) return "-" + kit;
        StashSorter s = sorter();
        return (CONFIG.craftOrders && CONFIG.enableCrafting) + "/" + table.hash() + "/" + s.craftTableStatus() + "/" + s.craftTableWhy() + "/" + s.botTable() + "/" + CONFIG.craftOrderMaxFills + kit;
    }

    /**
     * The craft fields of the heartbeat. A bot with no recipe table sends craftOrders as an explicit null (it is never given a craft order);
     * one with a table says whether it takes them, the hash of its table, the state of the website's crafting table and the tables it has
     * seen, and asks for the settings with the hash of what it has cached. Tick thread.
     */
    private ManagerIndex.Craft craftBody(ManagerIndex.Built built) {
        StashSorter s = sorter();
        Recipes table = Recipes.current();
        if (!com.porter.qwertestash.stash.CraftTableState.sendOrders(CONFIG.enableCrafting, CONFIG.craftOrders, table != null)) return new ManagerIndex.Craft(null, null);
        boolean on = true;
        String status = s.craftTableStatus();
        long now = System.currentTimeMillis();
        if (now - craftSeenAt >= CRAFT_SEEN_MS) {
            craftSeenAt = now;
            craftSeen = s.seenTables();
        }
        CraftSettings cached = CraftStore.shared().get(built.server(), built.stash());
        ManagerIndex.CraftOrders orders = new ManagerIndex.CraftOrders(on, table.hash(), mcVersion(), status, Math.max(1, CONFIG.craftOrderMaxFills), craftSeen, s.craftTableWhy(), s.botTable());
        if (craftLegacy) orders = orders.asLegacy();
        return new ManagerIndex.Craft(orders, cached == null ? null : cached.hash());
    }

    /** What a heartbeat reply says about crafting: the server's recipe hash, and new craft settings (the website's table), checked and cached. Tick thread. */
    private void craftReplied(ManagerIndex.Built built, String body) {
        ManagerIndex.CraftReply reply = ManagerIndex.craftReply(body);
        recipeSync.serverHash(reply.recipesHash());
        if (reply.invalid() != null) {
            if (!reply.invalid().equals(craftInvalidSaid)) LOG.warn("{}ignoring craft settings from the server: {}.", PREFIX, reply.invalid());
            craftInvalidSaid = reply.invalid();
        }
        else if (reply.settings() != null) {
            craftInvalidSaid = null;
            if (CraftStore.shared().put(built.server(), built.stash(), reply.settings())) {
                // The position is not logged
                LOG.info("{}craft settings updated (revision {}, a crafting table is {}).", PREFIX, reply.settings().rev(), reply.settings().table() == null ? "not set" : "set");
            }
        }
    }

    // The stash area (1.15.2, AREA-CONTRACT.md)

    private @Nullable String areaIgnoredHash;

    /** The area fields of the heartbeat, or null with no stash (nothing to say) or an area whose dimension is not known. Tick thread. */
    private ManagerIndex.@Nullable AreaReport areaBody() {
        Stash s = Stashes.current();
        if (s == null) return null;
        StashSorter sorter = sorter();
        int[] box = null;
        String dimension = null;
        if (s.area != null) {
            box = Stash.boundsOf(s.area);
            dimension = s.area.dimension != null ? s.area.dimension : s.dimension;
            if (dimension == null) return null;
        }
        List<int[]> blocks = new java.util.ArrayList<>();
        int outside = 0;
        for (Box b : s.boxes) {
            if (b.delivery) continue;
            if (s.area != null && !s.boxInArea(b)) outside++;
            if (b.known() && s.inScope(b)) for (com.aquarius.mc.block.BlockPos p : b.parts) blocks.add(new int[] {p.x(), p.y(), p.z()});
        }
        com.porter.qwertestash.stash.AreaPending.Request pending = com.porter.qwertestash.stash.AreaPending.get();
        return new ManagerIndex.AreaReport(com.porter.qwertestash.stash.AreaRules.hashOf(box, dimension), box, dimension,
            s.area == null ? com.porter.qwertestash.stash.AreaRules.derived(blocks) : null, com.porter.qwertestash.stash.AreaPending.edited(),
            pending == null ? null : pending.hash(), pending == null ? null : com.porter.qwertestash.stash.AreaPending.why(), outside);
    }

    /**
     * What a heartbeat reply says about the area (AreaSync decides): an acknowledgement ends our edit only when it is for the hash we edited to; an
     * area for us is queued unless a change of ours is waiting or not acknowledged yet. Tick thread.
     */
    private void areaReplied(String body, @Nullable String sentHash) {
        ManagerIndex.AreaReply r = ManagerIndex.areaReply(body);
        com.porter.qwertestash.stash.AreaPending.acknowledge(r.hash());
        Stash s = Stashes.current();
        ManagerIndex.AreaReport now = areaBody();
        if (s == null || now == null) return;
        com.porter.qwertestash.stash.AreaSync.Action action = com.porter.qwertestash.stash.AreaSync.react(r, now.hash(), com.porter.qwertestash.stash.AreaPending.get(),
            com.porter.qwertestash.stash.AreaPending.edited(), s.dimension);
        if (action.warn() != null) {
            if (!action.warn().equals(areaIgnoredHash)) LOG.warn("{}ignoring the stash area from the server: {}.", PREFIX, action.warn());
            areaIgnoredHash = action.warn();
        }
        if (action.queue() != null) {
            com.porter.qwertestash.stash.AreaPending.queue(action.queue());
            LOG.info("{}a stash area from the server waits to be applied between tasks.", PREFIX);
        }
    }

    // Kit sorting (1.14.0, KIT-CONTRACT.md)

    /** The kit fields of the heartbeat, or null with kit sorting off (nothing about kits is said then). Tick thread. */
    private ManagerIndex.@Nullable Kits kitsBody(ManagerIndex.Built built) {
        if (!CONFIG.kitSorting) return null;
        KitSettings cached = KitStore.shared().get(built.server(), built.stash());
        KitReport.Built report = kitReport;
        return new ManagerIndex.Kits(cached == null ? null : cached.hash(), report == null ? null : report.hash(), cached == null || cached.types() == null ? null : cached.types().hash(), kitCaps());
    }

    /**
     * The kit order capability of this bot (KIT-CONTRACT v2): kit sorting on and the type table readable; {@code enabled} follows the kit orders setting.
     * {@code craft} only when the bot also sends craftOrders. Null when it cannot build kits at all. Tick thread.
     */
    private ManagerIndex.@Nullable KitCaps kitCaps() {
        if (!CONFIG.kitSorting || com.porter.qwertestash.stash.KitTypes.shared() == null) return null;
        boolean craft = com.porter.qwertestash.stash.CraftTableState.sendOrders(CONFIG.enableCrafting, CONFIG.craftOrders, Recipes.current() != null);
        return new ManagerIndex.KitCaps(CONFIG.kitOrders, CONFIG.kitRestock, craft);
    }

    /** What a heartbeat reply says about kits: new settings are checked, cached and the sorter woken; the report is asked for or not. Tick thread. */
    private void kitsReplied(ManagerIndex.Built built, String body) {
        if (!CONFIG.kitSorting) return;
        ManagerIndex.KitsReply reply = ManagerIndex.kitsReply(body);
        // Only a server that answers with a needKitsReport key (true or false) knows the report route
        kitReportKnown = reply.reportKnown();
        kitAsked = reply.reportKnown() && reply.needReport();
        if (reply.invalid() != null) {
            if (!reply.invalid().equals(kitInvalidSaid)) LOG.warn("{}ignoring kit settings from the server: {}.", PREFIX, reply.invalid());
            kitInvalidSaid = reply.invalid();
        }
        else if (reply.settings() != null) {
            kitInvalidSaid = null;
            if (KitStore.shared().put(built.server(), built.stash(), reply.settings())) {
                // The sorter plans everything again: kits stored elsewhere move once their chest has room
                Stashes.version++;
                kitCheckedAt = 0;
                LOG.info("{}kit settings updated (revision {}, {} rules{}).", PREFIX, reply.settings().rev(), reply.settings().rules().size(),
                    reply.settings().kitsChest() == null ? "" : ", a kits chest");
            }
        }
    }

    /**
     * Keeps the kit report current: rebuilt every few seconds from the stash as it is and what the bot carries. Cheap (containers and
     * shulker names only), and on the tick, where the inventory can be read.
     */
    private void refreshKits(Stash stash, String name, long now) {
        if (!CONFIG.kitSorting) {
            // Off: nothing is said about kits, and what was learned from the server is forgotten (asked again when it is turned on)
            kitReport = null;
            kitReportKnown = false;
            kitAsked = false;
            kitSentHash = null;
            kitRejectedHash = null;
            kitWho = "";
            return;
        }
        String who = stash.server + "|" + stash.name;
        if (!who.equals(kitWho)) {
            // Another server or stash: the reply that unlocked the report route was about the old one
            kitWho = who;
            kitReport = null;
            kitReportKnown = false;
            kitAsked = false;
            kitSentHash = null;
            kitRejectedHash = null;
            kitBlockedUntil = 0;
            kitCheckedAt = 0;
            kitSig = "";
        }
        if (now - kitCheckedAt < 5000) return;
        kitCheckedAt = now;
        try {
            // Looked at every 5 s, rebuilt (every shulker scored) only when something it is built from changed, and at least once a minute
            List<com.porter.qwertestash.stash.Item> carried = sorter().inventoryShulkers();
            com.porter.qwertestash.orders.KitRestock.Decision restock = sorter().restockState();
            String sig = KitReport.fingerprint(stash, CONFIG.managerIncludeExcluded) + "|" + Stashes.version + "|" + KitStore.shared().version() + "|"
                + KitReport.carriedSignature(carried) + "|" + name + "|" + restock.state() + restock.template() + restock.reason();
            if (kitReport != null && sig.equals(kitSig) && now - kitBuiltAt < 60_000) return;
            // Only boxes that are kits by this stash's scoring are reported (the defaults until settings have been cached)
            KitSettings cached = KitStore.shared().get(ManagerIndex.clamp(stash.server, 256), ManagerIndex.clamp(stash.name, 256));
            kitReport = KitReport.buildV2(stash, CONFIG.managerIncludeExcluded, name, carried,
                cached == null ? KitScore.Scoring.DEFAULT : cached.scoring(), KitItems.shared(), cached, restock);
            kitSig = sig;
            kitBuiltAt = now;
        } catch (RuntimeException e) {
            LOG.debug("{}couldn't build the kit report: {}", PREFIX, e.toString());
            kitReport = null;
        }
    }

    /**
     * Whether to send the report now: only after a heartbeat reply carried needKitsReport (an older server never sees the route), at
     * most every 10 s, and only when its hash changed or the server asked. A report the server refused is not sent again until it changes.
     */
    private boolean kitReportDue(ManagerIndex.Built built, long now) {
        KitReport.Built r = kitReport;
        if (r == null || now < kitBlockedUntil) return false;
        return KitReport.due(CONFIG.kitSorting, kitReportKnown, r.server().equals(built.server()) && r.stash().equals(built.stash()), now - kitAttemptAt,
            r.hash(), kitSentHash, kitRejectedHash, kitAsked);
    }

    /** POST /api/v1/kits/report, signed like the index. Soft: the answer never feeds the manager's backoff or status. */
    private void sendKits(URI base, String key, KitReport.Built report, long now) {
        kitAttemptAt = now;
        send(base, "/api/v1/kits/report", report.json(), key, (code, body) -> {
            if (code == 200) {
                kitSentHash = report.hash();
                kitRejectedHash = null;
                kitAsked = false;
            }
            else if (code == 400 || code == 413) {
                LOG.debug("{}the server refused the kit report ({}).", PREFIX, code);
                kitRejectedHash = report.hash();
            }
            else if (code == 409) {
                // Another bot owns the stash name: no point asking again for a while
                kitBlockedUntil = System.currentTimeMillis() + KIT_CONFLICT_WAIT_MS;
            }
            return true;
        }, true);
    }

    /** The bot account's name, from the profile the server gave it. */
    private static @Nullable String playerName() {
        try {
            var profile = CACHE.getProfileCache().getProfile();
            String name = profile == null ? null : profile.getName();
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // Stash orders

    private OrderDriver driver() {
        OrderDriver d = driver;
        if (d == null) {
            d = new OrderDriver(sorter().orderFlow(), System::currentTimeMillis, (level, text) -> ActivityFeed.INSTANCE.event("Stash Orders", level, text));
            // Whether the claim carries craftOrders is worked out when the claim is made, never earlier: the cached recipe table is loaded first,
            // so an order held over a restart is not claimed without the flag (the server would fail it as "downgraded")
            d.craftOrdersWhen(() -> {
                loadRecipeCache();
                StashSorter s = sorter();
                OrderFlow f = s.orderFlow();
                // A held craft order is always claimed with the flag, whatever the table is like for the moment
                return com.porter.qwertestash.stash.CraftTableState.claimFlag(s.craftOrdersCapable(), f.craftLedger() != null || f.resumeHasCraft());
            });
            // The claim carries kitOrders: 1 for a bot that can build kits; a kit order held over a restart is claimed with it as well
            d.kitOrdersWhen(() -> {
                ManagerIndex.KitCaps caps = kitCaps();
                return caps != null && (caps.enabled() || sorter().orderFlow().kitResult() != null);
            });
            // The claim carries enchantOrders: 1 for a bot that makes enchant items (option on, supported version, no conflicting proxy module);
            // an enchant order held over a restart is claimed with it as well, or the server fails it as "downgraded"
            d.enchantOrdersWhen(() -> {
                OrderFlow f = sorter().orderFlow();
                if (f.enchantLedger() != null || f.resumeHasEnchant()) return true;
                return CONFIG.enchantOrders && com.porter.qwerte.core.enchant.McData.SUPPORTED.contains(mcVersion())
                    && com.porter.qwertestash.orders.EnchantGuard.conflict(com.porter.qwertestash.manager.ProxySources.enabledModuleNames()) == null;
            });
            driver = d;
        }
        return d;
    }

    /** The order in hand as a card for the Now-doing list. Titles carry no coordinates (the website blurs only details). */
    private ActivityFeed.@Nullable Item orderItem() {
        return orderCard;
    }

    // Built on the tick from the order machine (which is for the tick thread alone); the feed reads this finished copy
    private volatile ActivityFeed.@Nullable Item orderCard;

    private void buildOrderCard(OrderFlow flow, StashSorter s) {
        String summary = flow.activityTitle();
        if (summary == null) {
            orderCard = null;
            return;
        }
        String detail = flow.running() ? s.activityDetail() : null;
        orderCard = new ActivityFeed.Item("Stash Orders", "plugin", summary, detail, new int[] {flow.index(), flow.units().size()}, flow.state() == OrderFlow.State.Failed ? "warn" : "info");
    }

    /** What `stash orders` shows. */
    public OrdersStatus ordersStatus() {
        return ordersStatus;
    }

    /** Works out why orders can't be taken (once a second) and keeps the sorter going while an order is in hand. */
    private void tickOrders(@Nullable Stash stash) {
        StashSorter s = sorter();
        OrderFlow flow = s.orderFlow();
        long now = System.currentTimeMillis();
        buildOrderCard(flow, s);

        if (now - blockerAt >= 1000) {
            blockerAt = now;
            String next = OrderBlocker.compute(snapshot(s, flow, stash));
            blocker = next;
            DeliverySpot.Spot spot = DeliverySpot.get();
            // A change of the blocker or of the delivery container is sent in a heartbeat of its own, not at the next 15 second beat
            ManagerIndex.Naming namingState = namingNow();
            naming = namingState;
            // So is a change of the anvil or of the bot's XP level
            ManagerIndex.Crafting craftingState = new ManagerIndex.Crafting(CONFIG.enableCrafting && Recipes.current() != null, com.porter.qwertestash.stash.CraftingTableSpot.usable(), com.porter.qwertestash.stash.CraftingTableSpot.source());
            crafting = craftingState;
            ManagerIndex.DeliveryStorage storageState = s.deliveryStorage();
            storage = storageState;
            craftSig = craftSignature();
            blockerSig = (next == null ? "" : next) + "|" + craftSig + "|" + (spot == null ? "-" : String.valueOf(spot.hashCode())) + "|" + namingState.anvil() + "/" + namingState.xpLevels() + "/" + namingState.anvilSource()
                + "|" + craftingState.enabled() + "/" + craftingState.table() + "/" + craftingState.tableSource()
                + "|" + (storageState == null ? "-" : storageState.autoExpand() + "/" + storageState.chests() + "/" + storageState.full() + "/" + storageState.canExpand() + "/" + storageState.blocked());
            driver();
            ordersStatus = new OrdersStatus(flow.summary(), heartbeatSeen ? driver().pending() : -1, next);
        }

        // The sorter is driven for an order the same way it is for a website rescan: it reads, then does the order's jobs. With
        // sorting on it is running by itself and picks the order up between its jobs
        boolean want = CONFIG.manager && CONFIG.acceptOrders && stash != null && flow.holdsOrder();
        if (CONFIG.sorting || !want) {
            orderDriven = false;
            return;
        }
        if (s.isDriven()) {
            orderDriven = true;
            return;
        }
        if (flow.running() && orderDriven) {
            // It ended by itself with the order still in hand: a problem fails the order, else it is started again (not too often)
            String why = s.driveProblem();
            if (why != null) {
                flow.finish(OrderFlow.State.Failed, "the stash sorter couldn't run: " + why);
                orderDriven = false;
                return;
            }
        }
        if (!flow.holdsOrder() || now - lastOrderDrive < 3_000) return;
        lastOrderDrive = now;
        s.drive(true);
        orderDriven = s.isDriven();
        if (!orderDriven && flow.running()) {
            String why = s.driveProblem();
            if (why != null) flow.finish(OrderFlow.State.Failed, "the stash sorter couldn't run: " + why);
        }
    }

    /** The heartbeat's naming field: whether the anvil can be used, and the bot's XP level (-1 when not in game). */
    private static ManagerIndex.Naming namingNow() {
        int level = -1;
        try {
            if (Alerts.online()) level = Math.max(0, CACHE.getPlayerCache().getThePlayer().getLevel());
        } catch (RuntimeException | LinkageError e) {
            // Not known
        }
        return new ManagerIndex.Naming(AnvilSpot.usable(), level, AnvilSpot.source());
    }

    private OrderBlocker.Snapshot snapshot(StashSorter s, OrderFlow flow, @Nullable Stash stash) {
        DeliverySpot.Spot spot = DeliverySpot.get();
        boolean inQueue = false, controlled = false;
        try {
            inQueue = com.aquarius.Proxy.getInstance().isInQueue();
            controlled = com.aquarius.Proxy.getInstance().hasActivePlayer();
        } catch (RuntimeException | LinkageError e) {
            // Not known: not a reason
        }
        boolean inGame = Alerts.online();
        double distance = -1;
        if (spot != null && inGame && DeliverySpot.here(spot)) {
            int[] b = stash == null ? null : stash.bounds();
            double cx = b != null ? (b[0] + b[3]) / 2.0 : CACHE.getPlayerCache().getX();
            double cz = b != null ? (b[2] + b[5]) / 2.0 : CACHE.getPlayerCache().getZ();
            distance = Math.hypot(cx - spot.x(), cz - spot.z());
        }
        return new OrderBlocker.Snapshot(CONFIG.acceptOrders, spot != null, inGame, inQueue, controlled && !inQueue, stash != null,
            spot == null ? null : spot.dimension(), inGame ? Stash.currentDimension() : null, distance,
            inGame ? s.orderBusy() : null, inGame ? s.freeOrderSlots() : 1, inGame ? s.shulkersKnown() : true, inGame && s.stashReadOnce(), AwayGuard.away() || AwayGuard.quiesced());
    }

    /**
     * The order's next request, if there is one: the claim of a pending order (or the one held from before a restart), or the
     * status of the order in hand. Nothing but the sorter moves the bot; this only talks to the server.
     * @return true when a request was sent
     */
    private boolean orderStep(URI base, String key, ManagerIndex.Built built, long now) {
        // Restock gives way to a claimable order, a player or a switch-off (tick thread, like the order flow)
        sorter().restockHint(heartbeatSeen && driver().pending() > 0, heartbeatSeen);
        sorter().restockYieldCheck();
        if (!CONFIG.acceptOrders && !sorter().orderFlow().holdsOrder() && sorter().orderFlow().resumeId() == null) return false;
        String player = playerName();
        OrderDriver.Request request = driver().next(CONFIG.acceptOrders, blocker, built.server(), built.stash(), player, true);
        if (request == null) return false;
        send(base, request.path(), request.body(), key, (code, body) -> request.reply().done(code, body));
        return true;
    }

    // Building the index

    private void scan(Stash stash, String player) {
        if (building.get()) return;
        long now = System.currentTimeMillis();
        boolean include = CONFIG.managerIncludeExcluded;
        long fp = include ? 1 : 0;
        fp = fp * 31 + stash.server.hashCode();
        fp = fp * 31 + stash.name.hashCode();
        fp = fp * 31 + Stashes.version;
        fp = fp * 31 + stash.boxes.size();
        int stale = 0;
        for (Box box : stash.boxes) if (box.stale && stash.inScope(box) && (include || !box.excluded)) stale++;
        fp = fp * 31 + stale;
        String dimension = Stash.currentDimension();
        fp = fp * 31 + (dimension == null ? 0 : dimension.hashCode());
        if (current != null && fp == fingerprint && now - lastBuild < REBUILD_MS) return;

        int gen = generation;
        String mcVersion = mcVersion();
        long fingerprintNow = fp;
        building.set(true);
        try {
            builder.execute(() -> {
                ManagerIndex.Built built = null;
                Throwable failure = null;
                try {
                    built = ManagerIndex.build(stash, include, player, mcVersion, dimension, id -> null);
                } catch (Throwable t) {
                    failure = t;
                }
                ManagerIndex.Built result = built;
                Throwable failed = failure;
                results.add(() -> {
                    if (gen != generation) return;
                    building.set(false);
                    if (failed != null) {
                        if (!status.equals("index error")) LOG.error("{}couldn't build the index", PREFIX, failed);
                        lastError = "couldn't build the index: " + failed;
                        current = null;
                        setStatus("index error");
                        return;
                    }
                    current = result;
                    fingerprint = fingerprintNow;
                    lastBuild = System.currentTimeMillis();
                    if (status.equals("no stash") || status.equals("index error") || status.equals("no player name")) status = "idle";
                });
            });
        } catch (RuntimeException e) {
            building.set(false);
        }
    }

    // Enchant orders (1.19.0, ENCHANT-CONTRACT v1.0): the heartbeat's capability block

    private boolean enchantAnnounced;

    /**
     * The {@code enchant} key of the heartbeat. With the option on and a supported Minecraft version it says whether this bot takes enchant orders now ({@code enabled}
     * is false while the proxy's Enchanter, KitMaker or OrderFiller is on) and how many carry slots are free once the gear is stowed. Turning the option off sends one
     * explicit null (the server forgets the capability); a bot that never had it on sends nothing. Tick thread.
     */
    private ManagerIndex.@Nullable EnchantHb enchantBody() {
        String mc = mcVersion();
        boolean supported = com.porter.qwerte.core.enchant.McData.SUPPORTED.contains(mc);
        if (!CONFIG.enchantOrders || !supported) {
            if (!enchantAnnounced) return null;
            enchantAnnounced = false;
            return new ManagerIndex.EnchantHb(false, null, -1, true);
        }
        enchantAnnounced = true;
        boolean free = com.porter.qwertestash.orders.EnchantGuard.conflict(com.porter.qwertestash.manager.ProxySources.enabledModuleNames()) == null;
        int carry;
        try {
            carry = sorter().enchantCarry();
        } catch (RuntimeException e) {
            carry = -1;
        }
        return new ManagerIndex.EnchantHb(free, mc, carry, false);
    }

    private static String mcVersion() {
        try {
            String v = com.aquarius.Globals.MC_VERSION;
            if (v != null && !v.isBlank()) return v;
        } catch (RuntimeException | LinkageError ignored) {
            // The build's own version below
        }
        return com.porter.qwertestash.BuildConstants.MC_VERSION;
    }

    // Website rescan

    /**
     * The website asked for a rescan: every container is marked stale and the area is explored afresh, exactly as
     * `stash rescan` does, and the server is told at once. The reading itself is the sorter's, or the read-only loop's.
     */
    private void startRescan(long id) {
        // The server sends the id until it has our rescanDone, so one handled and waiting to be acknowledged is not done twice
        if (id == rescanDone) return;
        long now = System.currentTimeMillis();
        boolean duplicate = id == lastRescanId && now - lastRescanAt < 120_000;
        rescanDone = id;
        lastHeartbeat = 0;
        if (duplicate) return;
        lastRescanId = id;
        lastRescanAt = now;

        Stash stash = Stashes.current();
        if (stash == null) {
            LOG.warn("{}rescan asked for from the website, but no stash is loaded.", PREFIX);
            rescanBlocked = "no stash";
            return;
        }
        if (AwayGuard.away()) {
            // Away on a pearl trip: the stash can't be read from here (the bot is not at it)
            rescanBlocked = AwayGuard.REASON;
            return;
        }
        beginRescan(stash, "asked for from the website");
    }

    /** Marks every container to be read again and lets the sorter's read loop take it up. Tick thread. */
    private void beginRescan(Stash stash, String why) {
        rescanBlocked = null;
        for (Box box : stash.boxes) if (!box.excluded) box.stale = true;
        Stashes.version++;
        Stashes.exploreVersion++;
        rescanning = true;
        LOG.info("{}rescan {}: reading {} containers again.", PREFIX, why, stash.boxes.stream().filter(b -> !b.excluded).count());
    }

    /**
     * Starts the sorter's read-only loop for a rescan when sorting is off, and notices when it is done. With sorting on the
     * sorter reads by itself (it saw the exploreVersion change), with nothing for us to drive.
     */
    private void driveSorter(@Nullable Stash stash) {
        StashSorter sorter = sorter();
        // A website rescan that has waited 2 minutes behind an area in another dimension says why (the heartbeat's rescan status)
        String waitWhy = sorter.areaWait(System.currentTimeMillis());
        if (waitWhy != null && lastRescanAt >= sorter.areaWaitSince() && rescanBlocked == null) {
            rescanBlocked = waitWhy;
            lastHeartbeat = 0;
            LOG.warn("{}the website rescan cannot go ahead: {}.", PREFIX, waitWhy);
        }
        else if (waitWhy == null && rescanBlocked != null && rescanBlocked.startsWith("the stash area is in another dimension")) {
            rescanBlocked = null;
            lastHeartbeat = 0;
        }
        if (!rescanning) {
            engaged = false;
            return;
        }
        if (CONFIG.sorting && !sorter.isDriven()) {
            rescanning = engaged = false;
            return;
        }
        if (sorter.isDriven()) {
            engaged = true;
            return;
        }
        if (engaged) {
            // It switched itself off: done, or it couldn't go on
            engaged = rescanning = false;
            String why = sorter.driveProblem();
            if (why != null) {
                lastError = "rescan stopped: " + why;
                LOG.warn("{}the rescan stopped: {}", PREFIX, why);
            }
            return;
        }
        if (stash == null) {
            rescanning = false;
            return;
        }
        sorter.drive(true);
        engaged = sorter.isDriven();
        if (!engaged) {
            rescanning = false;
            String why = sorter.driveProblem();
            if (why != null) lastError = "rescan stopped: " + why;
        }
    }

    // Requests

    /** What to do with the reply, on the tick. {@code code} is -1 when the server could not be reached. */
    private interface Reply {
        /** @return false when it failed and should be tried again later */
        boolean done(int code, String body);
    }

    private void send(URI base, String path, byte[] body, String key, Reply reply) {
        send(base, path, body, key, reply, false);
    }

    /** @param soft the answer never feeds the backoff or the status (only a 409 owner conflict is acted on) */
    private void send(URI base, String path, byte[] body, String key, Reply reply, boolean soft) {
        URI url;
        try {
            url = URI.create(base + path);
        } catch (IllegalArgumentException e) {
            setStatus("bad url");
            return;
        }
        if (key.chars().anyMatch(c -> c < 33 || c > 126)) {
            // A key with characters a header can't hold
            setStatus("bad key");
            return;
        }

        inFlight = true;
        int gen = generation;
        try {
            io.execute(() -> {
                // Signed, and the answer is only believed if the server signs it back (see ManagerHttp)
                ManagerHttp.Response response;
                try {
                    response = ManagerHttp.call("POST", url, body, key, null, 5_000, 15_000, 20_000, null);
                } catch (Throwable t) {
                    response = new ManagerHttp.Response(-1, "", t.getClass().getSimpleName());
                }
                int finalCode = response.code();
                String finalText = response.body();
                String finalError = response.error();
                results.add(() -> {
                    if (gen != generation) return;
                    inFlight = false;
                    handle(finalCode, finalText, finalError, reply, soft);
                });
            });
        } catch (RuntimeException e) {
            inFlight = false;
        }
    }

    private static String read(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < MAX_REPLY) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** A reply, on the tick. */
    private void handle(int code, String body, @Nullable String error, Reply reply, boolean soft) {
        // 409 with the ownership error: this stash name belongs to another player. Told apart from every other 409 by the
        // error field, so an order status 409 later ("already finished") is not taken for it
        if (code == 409) {
            String owner = ManagerIndex.ownerConflict(body);
            if (owner != null) {
                ownerRefused(owner);
                return;
            }
        }

        if (soft) {
            try {
                reply.done(code, body);
            } catch (RuntimeException e) {
                LOG.debug("{}couldn't handle a soft reply: {}", PREFIX, e.toString());
            }
            return;
        }

        boolean ok;
        try {
            ok = reply.done(code, body);
        } catch (RuntimeException e) {
            LOG.warn("{}couldn't handle the server's reply: {}", PREFIX, e.toString());
            ok = false;
        }
        if (ok) {
            backoff = 0;
            nextAttemptAt = 0;
            if (conflictOwner != null) {
                conflictOwner = null;
                conflictAlerted = "";
            }
            lastError = null;
            setStatus("online");
        }
        else {
            backoff = backoff == 0 ? BACKOFF_MIN_MS : Math.min(BACKOFF_MAX_MS, backoff * 2);
            nextAttemptAt = System.currentTimeMillis() + backoff;
            lastError = code == ManagerHttp.BAD_SIGNATURE ? "the answer was not signed by a server that knows the API key (a different server, or the wrong key)" : ManagerHttp.UNSIGNED.equals(error) ? "got an unsigned " + code + ": not believed (a wrong key, or something other than the server answered)" : code < 0 ? "server not reachable" + (error == null ? "" : " (" + error + ")") : "the server answered " + code;
            setStatus(code == ManagerHttp.BAD_SIGNATURE ? "server signature invalid" : code < 0 ? "offline" : code == 401 && ManagerHttp.UNSIGNED.equals(error) ? "401 (unsigned)" : "error " + code + (ManagerHttp.UNSIGNED.equals(error) ? " (unsigned)" : ""));
        }
    }

    private void ownerRefused(String owner) {
        // Another bot owns this stash name: an order in hand is let go of (saved, the server still holds it) instead of being
        // worked on for nobody while every report is refused
        if (sorter().orderFlow().holdsOrder()) {
            LOG.warn("{}stash name is used by {}: parking the order in hand.", PREFIX, owner);
            forgetOrder();
        }
        conflictOwner = owner;
        conflictRetryAt = System.currentTimeMillis() + CONFLICT_RETRY_MS;
        kitBlockedUntil = System.currentTimeMillis() + KIT_CONFLICT_WAIT_MS;
        backoff = 0;
        nextAttemptAt = 0;
        lastPushedId = null;
        needIndex = false;
        deliverySchedule.forget();
        lastError = "stash name '" + CONFIG.stashName + "' is used by " + owner;
        setStatus("stash name taken by " + owner);
        String text = "stash name '" + CONFIG.stashName + "' is used by " + owner + ": pick another stash name (each bot needs its own, `stash name <name>`).";
        if (!text.equals(conflictAlerted)) {
            conflictAlerted = text;
            LOG.warn("{}{}", PREFIX, text);
            Alerts.raise(this, PREFIX + text);
        }
    }

    // Pearl travel: the world as the trip driver sees it (1.18.0). All of it runs on the bot tick thread.

    private static boolean inQueueNow() {
        try {
            return com.aquarius.Proxy.getInstance().isInQueue();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** What a heartbeat reply says about pearl travel: the trip it offers or updates, an away clear. Tick thread. */
    private void pearlReplied(String body) {
        try {
            pearlDriver.onReply(body);
        } catch (RuntimeException e) {
            LOG.warn("{}pearl travel: couldn't handle the reply: {}", PREFIX, e.toString());
        }
    }

    private final class PearlWorld implements PearlTripDriver.World {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public boolean connected() {
            return Alerts.online();
        }

        @Override
        public boolean inQueue() {
            return inQueueNow();
        }

        @Override
        public boolean selfInTabList() {
            try {
                var profile = CACHE.getProfileCache().getProfile();
                return profile != null && CACHE.getTabListCache().get(profile.getId()).isPresent();
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public boolean whisperReady() {
            return Alerts.ready();
        }

        @Override
        public long joinedAt() {
            return joinedAt;
        }

        @Override
        public double x() {
            return CACHE.getPlayerCache().getX();
        }

        @Override
        public double y() {
            return CACHE.getPlayerCache().getY();
        }

        @Override
        public double z() {
            return CACHE.getPlayerCache().getZ();
        }

        @Override
        public @Nullable String dimension() {
            String d = Stash.currentDimension();
            String n = d == null ? "" : ManagerIndex.namespaced(d);
            return n.isEmpty() ? null : n;
        }

        @Override
        public int pearlsCarried() {
            int total = 0;
            try {
                var inv = CACHE.getPlayerCache().getPlayerInventory();
                // Slots 9 to 44 are the inventory and the hotbar, 45 the offhand
                for (int i = 9; i < Math.min(46, inv.size()); i++) {
                    com.porter.qwertestash.stash.Item item = com.porter.qwertestash.stash.Item.of(inv.get(i));
                    if (item != null && item.id.equals("ender_pearl")) total += item.count;
                }
            } catch (RuntimeException e) {
                return 0;
            }
            return total;
        }

        @Override
        public double health() {
            try {
                Float h = CACHE.getPlayerCache().getThePlayer().getHealth();
                return h == null ? 20 : h;
            } catch (RuntimeException e) {
                return 20;
            }
        }

        @Override
        public int food() {
            try {
                return CACHE.getPlayerCache().getThePlayer().getFood();
            } catch (RuntimeException e) {
                return 20;
            }
        }

        @Override
        public boolean nonFriendWithin(double radius) {
            try {
                return com.aquarius.Globals.MODULE.get(com.porter.qwertestash.module.GroundItems.class).playerWithin(x(), z(), radius) != null;
            } catch (RuntimeException e) {
                return true;
            }
        }

        @Override
        public @Nullable String sorterHold() {
            return sorter().travelHold();
        }

        @Override
        public boolean whisper(String player, String text) {
            try {
                sendClientPacketAsync(com.aquarius.util.ChatUtil.getWhisperChatPacket(player, text));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        private com.aquarius.module.impl.PearlDrop drop() {
            return com.aquarius.Globals.MODULE.get(com.aquarius.module.impl.PearlDrop.class);
        }

        @Override
        public boolean startRearm(boolean strict) {
            try {
                var pd = drop();
                int fx = (int) Math.floor(x()), fy = (int) Math.floor(y()), fz = (int) Math.floor(z());
                if (pd.beginDepositNear(fx, fy, fz, 1)) return true;
                // The home throw is strict: only PearlDrop's own tolerance, never a guess at another chamber
                if (strict) return false;
                // The bot landed more than PearlDrop's tolerance from the column: the nearest free chamber within 4 blocks is used
                if (!"NO_CHAMBER".equals(String.valueOf(pd.lastResult()))) return false;
                com.aquarius.module.impl.PearlDrop.StasisChamber best = null;
                double bestD = 4.0;
                for (var c : pd.scan(6)) {
                    double d = Math.hypot(c.columnX() + 0.5 - x(), c.columnZ() + 0.5 - z());
                    if (d <= bestD && !pd.isOccupied(c) && pd.hasRim(c)) {
                        bestD = d;
                        best = c;
                    }
                }
                return best != null && pd.beginDeposit(best, 1);
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        }

        @Override
        public boolean rearmRunning() {
            try {
                return !drop().isDepositDone();
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        }

        @Override
        public @Nullable String rearmResult() {
            try {
                return String.valueOf(drop().lastResult());
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        }

        @Override
        public void stopRearm() {
            try {
                drop().stopAll();
            } catch (RuntimeException | LinkageError e) {
                // Nothing to stop
            }
        }

        @Override
        public void requestRescan() {
            rescanAfterReturn = true;
        }

        @Override
        public void alert(String text) {
            Alerts.raise(StashManager.this, text);
        }

        @Override
        public void info(String text) {
            LOG.info("{}{}", PREFIX, text);
        }

        @Override
        public int deaths() {
            return deaths;
        }

        @Override
        public boolean tabListHas(String name) {
            try {
                for (PlayerListEntry e : CACHE.getTabListCache().getEntries()) {
                    if (e != null && e.getName() != null && e.getName().equalsIgnoreCase(name)) return true;
                }
                return false;
            } catch (RuntimeException e) {
                return false;
            }
        }

        @Override
        public @Nullable String uuid() {
            try {
                var profile = CACHE.getProfileCache().getProfile();
                return profile == null || profile.getId() == null ? null : profile.getId().toString();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Sets the status and, only when it changes, logs it. */
    private void setStatus(String next) {
        status = next;
        if (next.equals(lastLogged)) return;
        lastLogged = next;
        if (next.equals("error 401")) LOG.warn("{}the server refused the API key. Check it with `stash manager key <key>`.", PREFIX);
        else if (next.equals("online") || next.equals("idle")) LOG.info("{}{}", PREFIX, next);
        else LOG.warn("{}{}", PREFIX, next);
    }
}
