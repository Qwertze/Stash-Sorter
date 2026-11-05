package com.porter.qwertestash.module;

import com.aquarius.event.client.ClientBotTick;
import com.aquarius.event.client.ClientDisconnectEvent;
import com.aquarius.module.api.Module;
import com.github.rfresh2.EventConsumer;
import com.porter.qwertestash.manager.ActivityFeed;
import com.porter.qwertestash.manager.ConfigApplier;
import com.porter.qwertestash.manager.ConfigSchema;
import com.porter.qwertestash.manager.ProxyEnv;
import com.porter.qwertestash.manager.ProxyPolicy;
import com.porter.qwertestash.manager.ConsoleRunner;
import com.porter.qwertestash.manager.DashboardLink;
import com.porter.qwertestash.manager.ManagerHttp;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.orders.OrderBlocker;
import com.porter.qwertestash.orders.OrderDriver;
import com.porter.qwertestash.orders.OrderFlow;
import com.porter.qwertestash.stash.DeliverySpot;
import com.porter.qwertestash.stash.Stash;
import com.porter.qwertestash.stash.Stash.Box;
import com.porter.qwertestash.stash.Stashes;
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
    private boolean orderDriven;
    private long lastOrderDrive;
    private boolean heartbeatSeen;

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
        hook(list, () -> list.add(of(com.aquarius.event.client.ClientConnectEvent.class, e -> ActivityFeed.INSTANCE.event("Proxy", "info", "Connecting to the server"))));
        hook(list, () -> list.add(of(com.aquarius.event.client.ClientOnlineEvent.class, e -> ActivityFeed.INSTANCE.event("Proxy", "info", "Online"
            + e.queueWait().map(d -> " after " + d.toMinutes() + " min in the queue").orElse("")))));
        hook(list, () -> list.add(of(com.aquarius.event.client.ClientDeathEvent.class, e -> ActivityFeed.INSTANCE.event("Proxy", "error", "The bot died"))));
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
        return list;
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
    private DashboardLink link;

    /**
     * Starts or stops the dashboard link to match the settings. Safe from any thread. A link stopped from one of its own
     * jobs (the website turning the dashboard off) ends by itself.
     */
    public synchronized void updateDashboard() {
        try {
            boolean want = isEnabled() && CONFIG.manager && CONFIG.dashboard;
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
    }

    private void disconnected() {
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

    /** "not set", or the key as asterisks and its last four characters. The key itself is never shown or logged. */
    public static String maskKey(@Nullable String key) {
        if (key == null || key.isBlank()) return "not set";
        String k = key.trim();
        return k.length() < 12 ? "****" : "****" + k.substring(k.length() - 4);
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
        if (!Alerts.online()) return;
        Alerts.pump(this);

        Stash stash = Stashes.current();
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

        // Another bot owns this stash name: one heartbeat a minute finds out when that changes
        if (conflictOwner != null) {
            if (now < conflictRetryAt) return;
            beat(base, key, built, now);
            return;
        }

        String id = built.server() + "|" + built.stash() + "|" + built.hash() + "|" + built.known() + "/" + built.total() + "/" + built.stale();
        if (needIndex || !id.equals(lastPushedId)) {
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
        else if (now - lastHeartbeat >= HEARTBEAT_MS || !sig().equals(sentSig)) beat(base, key, built, now);
        else orderStep(base, key, built, now);
    }

    private String sig() {
        return rescanDone + "|" + rescanBlocked + "|" + blockerSig;
    }

    private void beat(URI base, String key, ManagerIndex.Built built, long now) {
        lastHeartbeat = now;
        String sig = sig();
        long done = rescanDone;
        DeliverySpot.Spot spot = DeliverySpot.get();
        ManagerIndex.Delivery delivery = spot == null ? null : new ManagerIndex.Delivery(spot.x(), spot.y(), spot.z(), spot.dimension());
        send(base, "/api/v1/heartbeat", ManagerIndex.heartbeat(built, done, rescanBlocked, blocker, delivery), key, (code, body) -> {
            if (code != 200) return false;
            heartbeatSeen = true;
            driver().onHeartbeat(body);
            sentSig = sig;
            if (done >= 0 && rescanDone == done) {
                rescanDone = -1;
                sentSig = sig();
            }
            needIndex = ManagerIndex.needsIndex(body);
            long id = ManagerIndex.rescanId(body);
            if (id >= 0) startRescan(id);
            return true;
        });
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
            blockerSig = (next == null ? "" : next) + "|" + (spot == null ? "-" : String.valueOf(spot.hashCode()));
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
            inGame ? s.orderBusy() : null, inGame ? s.freeOrderSlots() : 1, inGame ? s.shulkersKnown() : true, inGame && s.stashReadOnce());
    }

    /**
     * The order's next request, if there is one: the claim of a pending order (or the one held from before a restart), or the
     * status of the order in hand. Nothing but the sorter moves the bot; this only talks to the server.
     * @return true when a request was sent
     */
    private boolean orderStep(URI base, String key, ManagerIndex.Built built, long now) {
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
        for (Box box : stash.boxes) if (box.stale && (include || !box.excluded)) stale++;
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
        rescanBlocked = null;
        for (Box box : stash.boxes) if (!box.excluded) box.stale = true;
        Stashes.version++;
        Stashes.exploreVersion++;
        rescanning = true;
        LOG.info("{}rescan asked for from the website: reading {} containers again.", PREFIX, stash.boxes.stream().filter(b -> !b.excluded).count());
    }

    /**
     * Starts the sorter's read-only loop for a rescan when sorting is off, and notices when it is done. With sorting on the
     * sorter reads by itself (it saw the exploreVersion change), with nothing for us to drive.
     */
    private void driveSorter(@Nullable Stash stash) {
        StashSorter sorter = sorter();
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
                    handle(finalCode, finalText, finalError, reply);
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
    private void handle(int code, String body, @Nullable String error, Reply reply) {
        // 409 with the ownership error: this stash name belongs to another player. Told apart from every other 409 by the
        // error field, so an order status 409 later ("already finished") is not taken for it
        if (code == 409) {
            String owner = ManagerIndex.ownerConflict(body);
            if (owner != null) {
                ownerRefused(owner);
                return;
            }
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
        backoff = 0;
        nextAttemptAt = 0;
        lastPushedId = null;
        needIndex = false;
        lastError = "stash name '" + CONFIG.stashName + "' is used by " + owner;
        setStatus("stash name taken by " + owner);
        String text = "stash name '" + CONFIG.stashName + "' is used by " + owner + ": pick another stash name (each bot needs its own, `stash name <name>`).";
        if (!text.equals(conflictAlerted)) {
            conflictAlerted = text;
            LOG.warn("{}{}", PREFIX, text);
            Alerts.raise(this, PREFIX + text);
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
