package com.porter.qwertestash.module;

import com.aquarius.cache.data.entity.EntityPlayer;
import com.aquarius.event.client.ClientBotTick;
import com.aquarius.event.client.ClientConnectEvent;
import com.aquarius.event.client.ClientDisconnectEvent;
import com.aquarius.event.player.PlayerConnectedEvent;
import com.aquarius.event.player.PlayerDisconnectedEvent;
import com.aquarius.feature.bridge.BridgeProtocol;
import com.aquarius.module.api.Module;
import com.aquarius.module.impl.Bridge;
import com.aquarius.network.codec.PacketHandler;
import com.aquarius.network.codec.PacketHandlerCodec;
import com.aquarius.network.codec.PacketHandlerStateCodec;
import com.aquarius.network.server.ServerSession;
import com.github.rfresh2.EventConsumer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.porter.qwerte.core.farm.FarmException;
import com.porter.qwerte.core.farm.FarmRecording;
import com.porter.qwerte.core.farm.ProgramChecker;
import com.porter.qwertestash.BuildConstants;
import com.porter.qwertestash.farm.AcceptSwitch;
import com.porter.qwertestash.farm.BuildIds;
import com.porter.qwertestash.farm.FarmCodes;
import com.porter.qwertestash.farm.FarmLogGuard;
import com.porter.qwertestash.farm.FarmPlayer;
import com.porter.qwertestash.farm.FarmProgram;
import com.porter.qwertestash.farm.FarmSafety;
import com.porter.qwertestash.farm.FarmStore;
import com.porter.qwertestash.farm.FarmerGuard;
import com.porter.qwertestash.farm.ProxyFarmPort;
import com.porter.qwertestash.farm.ProxyFarmView;
import com.porter.qwertestash.farm.SessionJournal;
import com.porter.qwertestash.farm.UploadController;
import com.porter.qwertestash.manager.ManagerHttp;
import com.porter.qwertestash.tasks.TaskSync;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.MODULE;
import static com.github.rfresh2.EventConsumer.of;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;
import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * The farmer role (FARM-CONTRACT v1.0, plugin 1.20.0). On while {@code CONFIG.farmerEnabled}. It owns:
 * <ul>
 *   <li>the Bridge handlers of {@code qwerte/farm/*} (netty thread: they copy bytes and answer; the heavy work is on {@code store worker}),</li>
 *   <li>the sealed stores, the sync client ({@code sync worker}) and the session, which runs on the {@code ClientBotTick} thread only.</li>
 * </ul>
 * No position is logged, reported or sent: only codes, counters and ids. The thread of each path is noted where it starts.
 */
public class Farmer extends Module {
    private static final String PREFIX = "Farm: ";
    private static final Path DIR = Path.of("plugins", "qwerte-stash", "farm");
    private static final Key BRIDGE_KEY = Key.key("proxybridge", "main");
    private static final int MAX_BRIDGE_BYTES = 32_768;
    private static final long REPORT_EVERY_MS = 30_000;
    private static final long PREFLIGHT_EVERY_MS = 30_000;

    private final AcceptSwitch accept = new AcceptSwitch();
    private FarmStore store;
    private SessionJournal journal;
    private volatile TaskSync sync;
    private UploadController uploads;
    private ScheduledExecutorService syncWorker;
    private ExecutorService storeWorker;
    private Bridge registeredOn;
    private boolean warnedBridge;
    private final Map<String, TaskSync.Farm> registry = new ConcurrentHashMap<>();
    private volatile boolean controllerConnected;
    private volatile boolean clientConnected = true;

    /** The open session (bot tick thread only). */
    private static final class Session {
        String taskId; // null for a local session
        int epoch;
        String farmId;
        FarmPlayer player;
        FarmSafety safety;
        long maxMs;
        long runtimeMs;
        long lastTickAt;
        long lastReportAt;
        String lastPhase = "";
        boolean parked;
        String parkCode;
        long parkedAt;
        long retryAt;
        String parkReported;
        long lastPreflightAt;
    }

    private Session session;
    /** Work the terminal asks for, run on the next bot tick (the session belongs to that thread). */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> inbox = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean sessionOpen;
    private volatile String sessionFarm;
    private volatile JsonObject statusSnap = new JsonObject();
    private long statusAt;
    private String summary = "idle";

    @Override
    public boolean enabledSetting() {
        return CONFIG.farmerEnabled;
    }

    public AcceptSwitch accept() {
        return accept;
    }

    public synchronized FarmStore store() {
        if (store == null) store = new FarmStore(DIR, System.getenv("QWERTE_FARM_KEY"), System::currentTimeMillis);
        return store;
    }

    private synchronized SessionJournal journal() {
        if (journal == null) journal = new SessionJournal(DIR.resolve("session.json"));
        return journal;
    }

    public ProxyFarmView view() {
        // the log filter is looked at for real every time (a logback reconfigure drops it), not remembered
        return new ProxyFarmView(() -> store().keyCode(), FarmLogGuard::attachedEverywhere);
    }

    // What the terminal and the sync worker may read about the session; written by the bot tick only (the session itself is tick-thread-only)
    private volatile String snapTask;
    private volatile int snapEpoch;
    private volatile int snapLoops;
    private volatile int snapRecoveries;
    private volatile String snapPhase = "none";
    private volatile String snapPark;
    private volatile boolean snapParked;
    /** The farms that have a program, refreshed on the store worker: {@code ready} and the upload gate never touch the disk or the key on a network thread. */
    private volatile java.util.Set<String> haveProgram = java.util.Set.of();

    // ---- life -------------------------------------------------------------------------------------------------------

    @Override
    public void onEnable() {
        List<String> missing = FarmLogGuard.install(FarmerGuard::active);
        if (!missing.isEmpty()) LOG.warn("{}the log filter could not be put on {} appender(s); sessions will refuse with unsafe_logging", PREFIX, missing.size());
        SessionJournal.State js = journal().load();
        if (journal().loadError() != null) LOG.warn("{}code={}: the journal could not be read, so a STOP is assumed until `stash tasks resume`", PREFIX, journal().loadError());
        store().cleanTmp();
        loadRegistry();
        storeWorker = Executors.newSingleThreadExecutor(r -> daemon(r, "qwerte-farm-store"));
        syncWorker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "qwerte-farm-sync"));
        uploads = new UploadController(new UploadEnv(), storeWorker);
        sync = new TaskSync(this::post, new SyncHost());
        sync.setGraceMs(Math.max(30, CONFIG.farmOfflineGraceSeconds) * 1000L);
        sync.setFarms(registryRev);
        sync.seedStopped(js.stopped());
        storeWorker.execute(this::refreshProgramCache);
        ensureBridge(); // registered now, not on the first tick: a controller may be connected and then there are no ticks
        syncWorker.scheduleWithFixedDelay(() -> {
            try {
                long now = System.currentTimeMillis();
                ensureBridge();
                UploadController u = uploads;
                if (u != null) u.tick(now); // the 60 s upload timeout runs here, so it runs while a controller is connected too
                SessionJournal j = journal();
                if (j.unsaved()) {
                    j.retry();
                    if (j.takeWriteFailureToLog()) LOG.warn("{}code=journal_write_failed: the journal could not be written, trying again", PREFIX);
                }
                TaskSync s = sync;
                if (s != null) s.step(now);
            } catch (Throwable t) {
                // the worker never dies; a failed step is tried again in a second
            }
        }, 1, 1, TimeUnit.SECONDS);
        LOG.info("{}farmer role on (version {}); dashboard link, index, viewer and map feeds are off while it is on", PREFIX, BuildConstants.VERSION);
    }

    /** Which farms have a program, read on the store worker (disk and key) and kept in memory. */
    private void refreshProgramCache() {
        try {
            haveProgram = java.util.Set.copyOf(store().entries().keySet());
        } catch (RuntimeException e) {
            haveProgram = java.util.Set.of();
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * {@code stash farm off}, step one (command thread): the session is ended by the bot tick (it owns the session) and this waits briefly
     * for that. With no tick running (a controller is connected) the fallback in {@link #onDisable} reports the end from the snapshot.
     */
    public void stopForDisable() {
        if (!sessionOpen) return;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        inbox.add(() -> {
            try {
                Session s = session;
                if (s != null) finish(s, "ops_stop", System.currentTimeMillis());
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onDisable() {
        TaskSync s = sync;
        // A session the tick did not end (no ticks are running): its end is reported from the snapshot, then nothing is left open
        if (sessionOpen) {
            String task = snapTask;
            if (s != null && task != null) {
                s.queueReport(new TaskSync.Report("cancelled", "parked", snapLoops, 0, "parked", snapLoops, null, 0, snapRecoveries, 0, "ops_stop"));
            }
            session = null;
            sessionOpen = false;
            sessionFarm = null;
        }
        // nothing of a session is left behind for a later enable
        deferred = null;
        startPending = false;
        snapTask = null;
        snapParked = false;
        snapPark = null;
        // Reports first, then the workers: the terminal report is sent synchronously with a short timeout, never dropped by shutdownNow
        if (syncWorker != null) {
            syncWorker.shutdown();
            try {
                syncWorker.awaitTermination(1500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (s != null) {
            long end = System.currentTimeMillis() + 3000;
            while (s.pendingReports() > 0 && System.currentTimeMillis() < end) {
                s.step(System.currentTimeMillis());
                if (s.pendingReports() > 0) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (syncWorker != null) syncWorker.shutdownNow();
        if (storeWorker != null) storeWorker.shutdown();
        sync = null;
        uploads = null;
        registeredOn = null;
        accept.disable();
    }

    // ---- events -----------------------------------------------------------------------------------------------------

    @Override
    public List<EventConsumer<?>> registerEvents() {
        List<EventConsumer<?>> l = new ArrayList<>();
        l.add(of(ClientBotTick.class, this::onTick));
        l.add(of(ClientBotTick.Stopped.class, e -> onStopped()));
        l.add(of(ClientBotTick.Starting.class, e -> onStarting()));
        l.add(of(com.aquarius.event.player.PlayerConnectionRemovedEvent.class, e -> {
            UploadController u = uploads;
            var c = e.serverConnection();
            if (u != null && c != null) u.peerGone(String.valueOf(c.getUUID())); // its upload is dropped
        }));
        l.add(of(PlayerConnectedEvent.class, e -> controllerConnected = true));
        l.add(of(PlayerDisconnectedEvent.class, e -> controllerConnected = false));
        l.add(of(ClientDisconnectEvent.class, e -> clientConnected = false));
        l.add(of(ClientConnectEvent.class, e -> clientConnected = true));
        return l;
    }

    /** Observer on the Bridge channel: a message over 32 KiB never reaches Bridge or a handler (C6.1, a second layer next to 2.6p item 4). */
    @Override
    public PacketHandlerCodec registerServerPacketHandlerCodec() {
        return PacketHandlerCodec.serverBuilder().setId("qwerte-farm-size-cap").setPriority(2000)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.serverBuilder()
                .inbound(ServerboundCustomPayloadPacket.class, (PacketHandler<ServerboundCustomPayloadPacket, ServerSession>) (p, s) -> {
                    try {
                        Key c = p.getChannel();
                        if (BridgeProtocol.CHANNEL_NAMESPACE.equals(c.namespace()) && BridgeProtocol.CHANNEL_PATH.equals(c.value())
                            && p.getData().length > MAX_BRIDGE_BYTES) {
                            return null;
                        }
                    } catch (RuntimeException e) {
                        return null;
                    }
                    return p;
                }).build()).build();
    }

    /**
     * ClientBotTick.Stopped (event thread): a player took the account or the connection dropped. The session is the bot tick's, so the park
     * is queued for it; the server is told at once from the snapshot (there are no ticks while stopped, and the website should not show
     * "running" for a parked bot).
     */
    private void onStopped() {
        final String why = controllerConnected ? "controller_connected" : "disconnected";
        final boolean wasParked = snapParked;
        if (sessionOpen) {
            snapParked = true; // the terminal's status is right at once; the park itself is the tick's
            snapPark = why;
        }
        inbox.add(() -> {
            Session s = session;
            if (s != null && !s.parked) park(s, why, System.currentTimeMillis());
        });
        TaskSync t = sync;
        String task = snapTask;
        int epoch = snapEpoch;
        if (t != null && task != null && sessionOpen && !wasParked) {
            // one atomic step in TaskSync, only while that very task and epoch are still the running one: a session that ended or began
            // meanwhile is not overwritten from this thread (the bot tick owns the running state)
            t.queueReportIfRunning(task, epoch, new TaskSync.Report("paused", "parked", snapLoops, 0, "parked", snapLoops, null, 0, snapRecoveries, 0, why),
                new TaskSync.Running(task, epoch, "paused", "parked", snapLoops, 0));
        }
    }

    /** ClientBotTick.Starting (event thread): queued for the tick; the resume re-verifies the position against the anchors (D3b). */
    private void onStarting() {
        inbox.add(() -> {
            Session s = session;
            if (s == null || !s.parked) return;
            long now = System.currentTimeMillis();
            String why = s.parkCode;
            if ("controller_connected".equals(why) && !CONFIG.farmResumeAfterController) return;
            if ("disconnected".equals(why) || "controller_connected".equals(why)) resume(s, now, "disconnected".equals(why));
        });
    }

    // ---- bridge -----------------------------------------------------------------------------------------------------

    private static UploadController.Peer peer(ServerSession s) {
        return new UploadController.Peer() {
            @Override
            public String id() {
                return String.valueOf(s.getUUID());
            }

            @Override
            public String name() {
                return s.getName();
            }

            @Override
            public boolean spectator() {
                return s.isSpectator();
            }

            @Override
            public void send(byte[] payload) {
                s.sendAsync(new ClientboundCustomPayloadPacket(BRIDGE_KEY, payload));
            }
        };
    }

    /** Registers the six topics on the Bridge module once it exists (bot tick). Only on the patched proxy (2.6p): else no handler, no answer. */
    private synchronized void ensureBridge() {
        Bridge b = MODULE.get(Bridge.class);
        if (b == null || b == registeredOn || uploads == null) return;
        if (!BuildIds.bridgeHardened()) {
            if (!warnedBridge) {
                warnedBridge = true;
                LOG.warn("{}this proxy is not the patched build (2.6p): farm uploads stay off (unsafe_bridge)", PREFIX);
            }
            return;
        }
        UploadController u = uploads;
        b.registerInboundHandler(UploadController.T_HELLO, (s, r) -> u.onHello(peer(s), r));
        b.registerInboundHandler(UploadController.T_BEGIN, (s, r) -> u.onBegin(peer(s), r));
        b.registerInboundHandler(UploadController.T_CHUNK, (s, r) -> u.onChunk(peer(s), r));
        b.registerInboundHandler(UploadController.T_ORIGIN, (s, r) -> u.onOrigin(peer(s), r));
        b.registerInboundHandler(UploadController.T_END, (s, r) -> u.onEnd(peer(s), r));
        b.registerInboundHandler(UploadController.T_ABORT, (s, r) -> u.onAbort(peer(s), r));
        registeredOn = b;
    }

    private final class UploadEnv implements UploadController.Env {
        @Override
        public boolean farmerOn() {
            return CONFIG.farmerEnabled;
        }

        @Override
        public boolean acceptOn(long nowMs) {
            // the viewer API runs commands as the terminal and serves positions: no upload while it is on (review item 1)
            return accept.on(nowMs) && !ProxyFarmView.viewerOnNow();
        }

        @Override
        public boolean sessionOpen() {
            return sessionOpen || startPending;
        }

        @Override
        public boolean sessionOpenFor(String farmId) {
            return sessionOpen && farmId.equals(sessionFarm);
        }

        @Override
        public boolean farmKnown(String farmId) {
            return registry.containsKey(farmId);
        }

        @Override
        public List<UploadController.FarmInfo> farms() {
            // from memory only: this runs on a network thread (no disk, no key, no store lock)
            List<UploadController.FarmInfo> out = new ArrayList<>();
            java.util.Set<String> have = haveProgram;
            for (TaskSync.Farm f : registry.values()) out.add(new UploadController.FarmInfo(f.id(), f.name(), f.mode(), have.contains(f.id())));
            return out;
        }

        @Override
        public boolean isOwner(UploadController.Peer peer) {
            String n = peer.name();
            if (n == null) return false;
            // a name is only trusted when the proxy verifies the users' accounts: with verifyUsers off it could be forged
            if (!ProxyFarmView.verifyUsersNow()) return false;
            for (String o : CONFIG.farmOwners) if (o != null && o.equalsIgnoreCase(n)) return true;
            return false;
        }

        @Override
        public UploadController.Own own() {
            try {
                var p = CACHE.getPlayerCache();
                String dim = dimension();
                if (dim == null) return null;
                return new UploadController.Own(dim, (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public void log(String line) {
            LOG.info("{}{}", PREFIX, line);
        }

        @Override
        public void stored(String farmId) {
            ExecutorService w = storeWorker;
            if (w != null) w.execute(Farmer.this::refreshProgramCache);
            TaskSync s = sync;
            if (s != null) s.requestSoon();
        }

        @Override
        public String pluginVersion() {
            return BuildConstants.VERSION;
        }

        @Override
        public FarmStore store() {
            return Farmer.this.store();
        }

        @Override
        public AcceptSwitch accept() {
            return accept;
        }
    }

    private static @Nullable String dimension() {
        try {
            String d = com.aquarius.feature.player.World.getCurrentDimension().name();
            if (d == null || d.isEmpty()) return null;
            return d.contains(":") ? d : "minecraft:" + d;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- sync glue --------------------------------------------------------------------------------------------------

    private ManagerHttp.Response post(String path, String json) {
        URI base = StashManager.parse(CONFIG.managerUrl);
        String key = CONFIG.managerKey == null ? "" : CONFIG.managerKey.trim();
        if (base == null) return new ManagerHttp.Response(-1, "", "bad url");
        if (key.isEmpty()) return new ManagerHttp.Response(-1, "", "no key");
        // the path is appended to the base as the manager link does (resolve would drop a path prefix such as /stash)
        return ManagerHttp.call("POST", URI.create(base + path), json.getBytes(StandardCharsets.UTF_8), key, botId(), 3000, 5000, 8000, null);
    }

    private synchronized String botId() {
        String id = CONFIG.managerBotId;
        if (id == null || id.isBlank()) {
            CONFIG.managerBotId = java.util.UUID.randomUUID().toString();
            try {
                com.aquarius.Globals.saveConfigAsync();
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("{}couldn't save the bot id", PREFIX);
            }
            id = CONFIG.managerBotId;
        }
        return id;
    }

    private final class SyncHost implements TaskSync.Host {
        @Override
        public boolean farmerEnabled() {
            return CONFIG.farmerEnabled;
        }

        @Override
        public JsonObject status() {
            return statusSnap;
        }

        @Override
        public JsonObject farmer() {
            JsonObject f = new JsonObject();
            f.addProperty("enabled", CONFIG.farmerEnabled);
            f.addProperty("acceptUploads", accept.on(System.currentTimeMillis()));
            f.addProperty("version", BuildConstants.VERSION);
            JsonArray modes = new JsonArray();
            for (String m : FarmCodes.MODES) modes.add(m);
            f.add("modes", modes);
            return f;
        }

        @Override
        public JsonArray programs() {
            JsonArray a = new JsonArray();
            try {
                for (var e : store().entries().entrySet()) {
                    if (e.getKey().equals(FarmStore.DRAFT)) continue;
                    FarmStore.Entry x = e.getValue();
                    JsonObject o = new JsonObject();
                    o.addProperty("farm", e.getKey());
                    o.addProperty("hash", x.programHash());
                    o.addProperty("mode", x.mode());
                    o.addProperty("ticks", x.ticks());
                    o.addProperty("loop", x.loop());
                    o.addProperty("anchors", x.anchors());
                    o.addProperty("unsupported", x.unsupported());
                    a.add(o);
                    if (a.size() >= 32) break;
                }
            } catch (RuntimeException e) {
                // an unreadable store reports none
            }
            return a;
        }

        @Override
        public JsonObject draft() {
            try {
                FarmStore.Entry x = store().entry(FarmStore.DRAFT);
                if (x == null) return null;
                JsonObject o = new JsonObject();
                o.addProperty("hash", x.programHash());
                o.addProperty("mode", x.mode());
                o.addProperty("ticks", x.ticks());
                o.addProperty("loop", x.loop());
                o.addProperty("anchors", x.anchors());
                return o;
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public String botId() {
            return Farmer.this.botId();
        }

        @Override
        public String player() {
            try {
                return CACHE.getProfileCache().getProfile() == null ? null : CACHE.getProfileCache().getProfile().getName();
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public String server() {
            try {
                return com.aquarius.Globals.CONFIG.client.server.address;
            } catch (RuntimeException e) {
                return "";
            }
        }

        @Override
        public boolean localStopped() {
            return journal().get().stopped() && journal().get().localStop();
        }

        @Override
        public JsonObject helloBody() {
            JsonObject o = new JsonObject();
            o.addProperty("botId", Farmer.this.botId());
            String p = player();
            if (p != null) o.addProperty("player", p);
            JsonObject plugin = new JsonObject();
            plugin.addProperty("id", BuildConstants.PLUGIN_ID);
            plugin.addProperty("version", BuildConstants.VERSION);
            o.add("plugin", plugin);
            JsonObject proxy = new JsonObject();
            proxy.addProperty("mc", BuildConstants.MC_VERSION);
            o.add("proxy", proxy);
            JsonArray caps = new JsonArray();
            caps.add("farm");
            o.add("caps", caps);
            return o;
        }

        @Override
        public boolean busy() {
            return sessionOpen;
        }
    }

    // ---- registry ---------------------------------------------------------------------------------------------------

    private int registryRev;

    private void loadRegistry() {
        try {
            Path f = DIR.resolve("registry.json");
            if (!java.nio.file.Files.exists(f)) return;
            JsonObject o = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(f)).getAsJsonObject();
            registryRev = o.get("rev").getAsInt();
            for (var e : o.getAsJsonArray("farms")) {
                JsonObject x = e.getAsJsonObject();
                registry.put(x.get("id").getAsString(), new TaskSync.Farm(x.get("id").getAsString(), x.get("name").getAsString(), x.get("mode").getAsString(),
                    x.get("enabled").getAsBoolean(), x.get("minMs").getAsLong(), x.get("maxMs").getAsLong(), x.get("healthStop").getAsInt(),
                    x.get("food").getAsInt(), x.get("totems").getAsInt(), x.get("playerRadius").getAsInt(), x.get("playerSeconds").getAsInt(),
                    x.get("invFullPct").getAsInt()));
            }
        } catch (java.io.IOException | RuntimeException e) {
            registry.clear();
            registryRev = 0;
        }
    }

    private void saveRegistry() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("rev", registryRev);
            JsonArray a = new JsonArray();
            for (TaskSync.Farm f : registry.values()) {
                JsonObject x = new JsonObject();
                x.addProperty("id", f.id());
                x.addProperty("name", f.name());
                x.addProperty("mode", f.mode());
                x.addProperty("enabled", f.enabled());
                x.addProperty("minMs", f.minMs());
                x.addProperty("maxMs", f.maxMs());
                x.addProperty("healthStop", f.healthStop());
                x.addProperty("food", f.food());
                x.addProperty("totems", f.totems());
                x.addProperty("playerRadius", f.playerRadius());
                x.addProperty("playerSeconds", f.playerSeconds());
                x.addProperty("invFullPct", f.invFullPct());
                a.add(x);
            }
            o.add("farms", a);
            java.nio.file.Files.createDirectories(DIR);
            java.nio.file.Files.writeString(DIR.resolve("registry.json"), o.toString());
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("{}couldn't save the farm list", PREFIX);
        }
    }

    public List<TaskSync.Farm> farms() {
        return new ArrayList<>(registry.values());
    }

    // ---- the tick (bot tick thread) ------------------------------------------------------------------------------------

    private void onTick(ClientBotTick e) {
        long now = System.currentTimeMillis();
        try {
            ensureBridge();
            UploadController u = uploads;
            if (u != null) u.tick(now);
            refreshStatus(now);
            Runnable job;
            while ((job = inbox.poll()) != null) job.run();
            TaskSync s = sync;
            if (s == null) return;
            TaskSync.Event ev;
            while ((ev = s.events().poll()) != null) handle(ev, now);
            tickDeferred(now);
            Session ses = session;
            if (ses == null) return;
            tickSession(ses, now);
        } catch (RuntimeException ex) {
            LOG.warn("{}tick failed: {}", PREFIX, ex.getClass().getSimpleName());
            Session ses = session;
            if (ses != null) finish(ses, "error", now);
        }
    }

    private void refreshStatus(long now) {
        if (now - statusAt < 1000) return;
        statusAt = now;
        JsonObject o = new JsonObject();
        boolean in = Alerts.online();
        o.addProperty("inGame", in);
        if (in) {
            String dim = dimension();
            if (dim != null && dim.length() <= 64 && dim.matches("^[a-z0-9_.-]+:[a-z0-9_./-]+$")) o.addProperty("dim", dim);
            try {
                var me = CACHE.getPlayerCache().getThePlayer();
                Float h = me.getHealth();
                o.addProperty("health", Math.max(0, Math.min(20, h == null ? 20 : Math.round(h))));
                o.addProperty("food", Math.max(0, Math.min(20, me.getFood())));
                o.addProperty("freeSlots", Math.max(0, Math.min(41, freeSlots())));
                o.addProperty("totems", Math.max(0, Math.min(64, totems())));
            } catch (RuntimeException ex) {
                // a status without the numbers is still a status
            }
        }
        statusSnap = o;
    }

    private static int freeSlots() {
        var inv = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        int free = 0;
        for (int i = 9; i <= 45 && i < inv.getSize(); i++) if (empty(inv.getItemStack(i))) free++;
        return free;
    }

    private static boolean empty(ItemStack s) {
        return s == null || s.getAmount() <= 0 || s == com.aquarius.cache.data.inventory.Container.EMPTY_STACK;
    }

    private static boolean isTotem(ItemStack s) {
        if (empty(s)) return false;
        var d = com.aquarius.mc.item.ItemRegistry.REGISTRY.get(s.getId());
        return d != null && d.name().equals("totem_of_undying");
    }

    private static int totems() {
        var inv = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        int n = 0;
        for (int i = 9; i <= 45 && i < inv.getSize(); i++) if (isTotem(inv.getItemStack(i))) n += inv.getItemStack(i).getAmount();
        return n;
    }

    private static boolean offhandTotem() {
        var inv = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        return inv.getSize() > 45 && isTotem(inv.getItemStack(45));
    }

    private static boolean hasFood() {
        var inv = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        for (int i = 9; i <= 45 && i < inv.getSize(); i++) {
            ItemStack s = inv.getItemStack(i);
            if (empty(s)) continue;
            var d = com.aquarius.mc.item.ItemRegistry.REGISTRY.get(s.getId());
            if (d != null && d.components().get(org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes.FOOD) != null) return true;
        }
        return false;
    }

    private static List<Double> nonFriendDistances() {
        List<Double> out = new ArrayList<>();
        var me = CACHE.getPlayerCache();
        for (EntityPlayer p : CACHE.getEntityCache().getPlayers().values()) {
            if (p == null || p.isSelfPlayer() || p.isRemoved()) continue;
            String name = CACHE.getTabListCache().get(p.getUuid()).map(PlayerListEntry::getName).orElse(null);
            if (com.porter.qwertestash.module.Friends.matches(CONFIG.friends, p.getUuid(), name) || proxyFriend(p.getUuid(), name)) continue;
            out.add(Math.sqrt(sq(p.getX() - me.getX()) + sq(p.getY() - me.getY()) + sq(p.getZ() - me.getZ())));
        }
        return out;
    }

    private static boolean proxyFriend(java.util.UUID uuid, String name) {
        try {
            for (var f : com.aquarius.Globals.CONFIG.client.extra.friendsList) {
                if (f.getUuid() != null && f.getUuid().equals(uuid)) return true;
                if (name != null && f.getUsername() != null && f.getUsername().equalsIgnoreCase(name)) return true;
            }
        } catch (RuntimeException e) {
            // unknown: not a friend
        }
        return false;
    }

    private static double sq(double d) {
        return d * d;
    }

    // ---- events from the server --------------------------------------------------------------------------------------

    private void handle(TaskSync.Event ev, long now) {
        if (ev instanceof TaskSync.Claimed c) {
            claimed(c, now);
        } else if (ev instanceof TaskSync.Cancel c) {
            Session s = session;
            if (s != null && c.taskId().equals(s.taskId)) finish(s, sync.stopped() ? "ops_stop" : "stopped", now);
        } else if (ev instanceof TaskSync.LeaseLost l) {
            Session s = session;
            if (s != null && l.taskId().equals(s.taskId)) {
                s.taskId = null; // nothing more is reported for a lost lease
                finish(s, "lease_lost", now);
            }
        } else if (ev instanceof TaskSync.Dropped d) {
            Session s = session;
            if (s != null && d.taskId().equals(s.taskId)) {
                s.taskId = null; // the report for it was refused: nothing more is reported, and it is not claimed as running
                finish(s, "error", now);
            }
        } else if (ev instanceof TaskSync.StopChanged st) {
            stopChanged(st, now);
        } else if (ev instanceof TaskSync.Farms f) {
            registry.clear();
            for (TaskSync.Farm x : f.farms()) registry.put(x.id(), x);
            registryRev = f.rev();
            saveRegistry();
        } else if (ev instanceof TaskSync.Forget f) {
            List<String> ids = f.ids();
            storeWorker.execute(() -> {
                for (String id : ids) {
                    try {
                        if (id.matches("^f-[0-9a-f]{8}$")) store().forget(id);
                    } catch (RuntimeException e) {
                        // left where it is
                    }
                }
                TaskSync s = sync;
                if (s != null) s.forgot(ids);
            });
            for (String id : ids) registry.remove(id);
            saveRegistry();
        } else if (ev instanceof TaskSync.Attach a) {
            attach(a.farm(), a.draftHash());
        } else if (ev instanceof TaskSync.Unreachable) {
            Session s = session;
            if (s != null && !s.parked) park(s, "server_unreachable", now);
        } else if (ev instanceof TaskSync.Reachable) {
            Session s = session;
            if (s != null && s.parked && "server_unreachable".equals(s.parkCode)) resume(s, now, false);
        } else if (ev instanceof TaskSync.KeyProblem) {
            LOG.warn("{}the Stash Manager server keeps refusing the signature: check `stash manager key`", PREFIX);
        } else if (ev instanceof TaskSync.ServerOld) {
            LOG.info("{}the Stash Manager server is too old for farms (no tasks/sync); the farmer stays idle", PREFIX);
        }
    }

    private void stopChanged(TaskSync.StopChanged st, long now) {
        SessionJournal j = journal();
        Session s = session;
        if (st.stopped()) {
            j.update(o -> o.withStop(true, o.localStop()));
            if (s != null) finish(s, "ops_stop", now);
            sync.ackStop(st.rev());
        } else {
            j.update(o -> o.localStop() ? o : o.withStop(false, false)); // the server resumed; a local STOP stays
            sync.ackStop(st.rev());
        }
    }

    /** {@code stash tasks stop}: terminal. Works with the server down. */
    public void localStop() {
        long now = System.currentTimeMillis();
        SessionJournal j = journal();
        j.update(o -> o.withStop(true, true));
        inbox.add(() -> {
            Session s = session;
            if (s != null) finish(s, "ops_stop", System.currentTimeMillis());
        });
    }

    /** {@code stash tasks resume}: clears the local STOP; a STOP the server still holds is lifted on the website. */
    public String localResume() {
        SessionJournal j = journal();
        j.update(o -> o.withStop(false, false));
        TaskSync s = sync;
        return s != null && s.stopped() ? "server_stop" : null;
    }

    public boolean stopped() {
        return journal().get().stopped();
    }

    private void attach(String farm, String hash) {
        if (storeWorker == null) return;
        storeWorker.execute(() -> {
            try {
                store().attach(farm, hash);
                LOG.info("{}draft attached to a farm", PREFIX);
            } catch (FarmException e) {
                LOG.warn("{}attach failed: code={}", PREFIX, e.code());
            } catch (RuntimeException e) {
                LOG.warn("{}attach failed", PREFIX);
            }
            TaskSync s = sync;
            if (s != null) s.requestSoon();
        });
    }

    /** {@code stash farm attach <id>} (terminal). */
    public void attachLocal(String farm) {
        attach(farm, null);
    }

    public void forgetLocal(String id) {
        if (storeWorker == null) return;
        storeWorker.execute(() -> {
            try {
                store().forget(id);
                TaskSync s = sync;
                if (s != null) s.forgot(List.of(id));
            } catch (RuntimeException e) {
                LOG.warn("{}forget failed", PREFIX);
            }
        });
    }

    // ---- sessions ---------------------------------------------------------------------------------------------------

    private volatile boolean startPending;
    private volatile TaskSync.Claimed deferred;
    private long deferredAt;

    private void claimed(TaskSync.Claimed c, long now) {
        if (session != null || deferred != null) {
            // no offer is made while a task runs, so this cannot happen; the running task is left alone (never overwritten) and the
            // extra claim simply lapses on the server
            LOG.warn("{}a second claim arrived while a session is open; ignored", PREFIX);
            return;
        }
        sync.setRunning(new TaskSync.Running(c.taskId(), c.epoch(), "running", "setup", 0, 0));
        if (uploads != null && uploads.uploading()) {
            // an upload is in flight: the session waits for it (at most 90 s), it does not start over it
            deferred = c;
            deferredAt = now;
            startPending = true;
            return;
        }
        startClaimed(c, now);
    }

    private void startClaimed(TaskSync.Claimed c, long now) {
        Object[] r = begin(c.farmId(), c.programHash(), c.maxMs(), c.taskId(), c.epoch(), now);
        if (r[0] != null) report(c.taskId(), "failed", "setup", (String) r[0], 0, 0, null, 0, 0, 0);
    }

    /** A claim waiting for an upload to finish (bot tick). */
    private void tickDeferred(long now) {
        TaskSync.Claimed c = deferred;
        if (c == null) return;
        if (uploads == null || !uploads.uploading()) {
            deferred = null;
            startPending = false;
            startClaimed(c, now);
        } else if (now - deferredAt > 90_000) {
            deferred = null;
            startPending = false;
            report(c.taskId(), "failed", "setup", "error", 0, 0, null, 0, 0, 0);
        }
    }

    /**
     * {@code stash farm start <id>}: a local session without a task (spike and CT tests); no report goes to the server. The start checks
     * run here, so a refusal is the answer to the command (a closed code); only the start itself is queued for the bot tick.
     */
    public String startLocal(String farmId) {
        if (!CONFIG.farmerEnabled) return "guard_off";
        if (sessionOpen || startPending) return "busy";
        if (stopped()) return "stopped";
        TaskSync.Farm f = registry.get(farmId);
        if (f != null && !f.enabled()) return "disabled"; // the owner switched this farm off
        String why = startChecks(farmId, null);
        if (why != null) return why;
        inbox.add(() -> {
            if (session != null) return;
            TaskSync.Farm g = registry.get(farmId);
            Object[] r = begin(farmId, null, g == null ? 7_200_000L : g.maxMs(), null, 0, System.currentTimeMillis());
            if (r[0] != null) LOG.info("{}local start refused: code={}", PREFIX, r[0]);
        });
        return null;
    }

    /** Starts a session; returns {code or null}. Bot tick thread (or the terminal command thread for a local start, which only queues). */
    private Object[] begin(String farmId, String hash, long maxMs, String taskId, int epoch, long now) {
        String code = startChecks(farmId, hash);
        // the session never runs longer than the farm's own limit, whatever the task says
        TaskSync.Farm farm = registry.get(farmId);
        long limit = farm == null ? 7_200_000L : farm.maxMs();
        maxMs = Math.max(60_000L, Math.min(maxMs, Math.min(limit, 43_200_000L)));
        Object[] out = new Object[1];
        if (code != null) {
            out[0] = code;
            LOG.info("{}session refused: code={}", PREFIX, code);
            return out;
        }
        try {
            // the origin and the program from the same commit of the store (an upload cannot swap one under the other)
            FarmStore.Opened opened = store().openWithEntry(farmId);
            FarmStore.Entry e = opened.entry();
            FarmRecording rec = ProgramChecker.checkPlain(opened.plain());
            FarmProgram prog = FarmProgram.of(rec);
            Session s = new Session();
            s.taskId = taskId;
            s.epoch = epoch;
            s.farmId = farmId;
            s.maxMs = maxMs;
            s.safety = new FarmSafety(thresholds(farmId));
            ProxyFarmPort port = new ProxyFarmPort(this, (hit, aimed) -> {
                Session cur = session;
                if (cur != null && aimed) cur.player.clickResult(hit, System.currentTimeMillis());
            });
            s.player = new FarmPlayer(prog, e.ox(), e.oy(), e.oz(), port);
            s.player.expectDimension(e.dim());
            s.lastTickAt = now;
            s.lastReportAt = now;
            s.lastPreflightAt = now;
            session = s;
            sessionOpen = true;
            sessionFarm = farmId;
            snapTask = taskId;
            snapEpoch = epoch;
            snapLoops = 0;
            snapRecoveries = 0;
            snapParked = false;
            snapPark = null;
            snapPhase = "setup";
            s.player.start(now);
            summary = "running";
            LOG.info("{}session started: mode={} hash={}", PREFIX, rec.header().mode(), e.programHash().substring(0, 8));
            saveJournal(s);
            report(taskId, "running", "setup", null, 0, 0, null, 0, 0, 0);
            return out;
        } catch (FarmException ex) {
            out[0] = ex.code().equals("seal_failed") ? "program_missing" : (FarmCodes.known(ex.code()) ? ex.code() : "program_missing");
        } catch (RuntimeException ex) {
            out[0] = "error";
        }
        session = null;
        sessionOpen = false;
        sessionFarm = null;
        return out;
    }

    private FarmSafety.Thresholds thresholds(String farmId) {
        TaskSync.Farm f = registry.get(farmId);
        return f == null ? FarmSafety.Thresholds.DEFAULT
            : new FarmSafety.Thresholds(f.healthStop(), f.food(), f.totems(), f.playerRadius(), f.playerSeconds(), f.invFullPct());
    }

    /** The start refusals of D7 in the contract's order; a closed code or null. */
    private String startChecks(String farmId, String hash) {
        if (!CONFIG.farmerEnabled) return "guard_off";
        if (!Alerts.online()) return "error";
        FarmLogGuard.install(FarmerGuard::active); // idempotent: puts the filter back if a logback reconfigure dropped it
        String pre = FarmerGuard.preflight(view());
        if (pre != null) return pre;
        try {
            FarmStore.Entry e = store().entry(farmId);
            if (e == null || !store().hasFile(farmId)) return "program_missing";
            if (hash != null && !hash.equals(e.programHash())) return "program_mismatch";
            TaskSync.Farm f = registry.get(farmId);
            if (f != null && !f.mode().equals(e.mode())) return "program_mismatch";
            if (!FarmCodes.MODES.contains(e.mode())) return "mode_not_ready";
        } catch (FarmException ex) {
            return FarmCodes.known(ex.code()) ? ex.code() : "program_missing";
        }
        return null;
    }

    private void tickSession(Session s, long now) {
        if (!s.parked) s.runtimeMs += Math.max(0, Math.min(5_000, now - s.lastTickAt));
        s.lastTickAt = now;
        snapLoops = s.player.loops();
        snapRecoveries = s.player.recoveries();
        snapPhase = phaseName(s.player.phase());
        // discord.reportCoords can be flipped at runtime (the displayCoords command): checked every tick, parked or not (a plain config read)
        if (ProxyFarmView.reportCoordsNow()) {
            finish(s, "unsafe_discord", now);
            return;
        }
        if (s.parked) {
            // A park that could clear by itself is tried again, bounded (review item 17)
            if ("pathfinder_busy".equals(s.parkCode)) {
                if (now - s.parkedAt > 300_000) {
                    finish(s, "error", now); // another pathfinder process held the bot for 5 minutes
                } else if (now >= s.retryAt) {
                    s.retryAt = now + 5_000;
                    resume(s, now, false);
                }
            } else if ("controller_connected".equals(s.parkCode) && now - s.parkedAt > 70 * 60_000L) {
                finish(s, "stopped", now); // the server cancels a parked task after 60 minutes (pause_timeout); this is the bot's own bound
            }
            return;
        }
        if (s.runtimeMs >= s.maxMs) {
            finish(s, "time_up", now);
            return;
        }
        // the setup table again every 30 s (contract draft 2.5): a module or setting changed during the session ends it with that code
        if (now - s.lastPreflightAt >= PREFLIGHT_EVERY_MS) {
            s.lastPreflightAt = now;
            FarmLogGuard.install(FarmerGuard::active);
            String pre = CONFIG.farmerEnabled ? FarmerGuard.preflight(view()) : "guard_off";
            if (pre != null) {
                finish(s, pre, now);
                return;
            }
        }
        // safety (D7)
        try {
            var me = CACHE.getPlayerCache().getThePlayer();
            Float h = me.getHealth();
            FarmSafety.Verdict v = s.safety.evaluate(new FarmSafety.Sample(h == null ? 20 : h, me.getFood(), hasFood(), offhandTotem(), freeSlots(),
                nonFriendDistances()), now);
            if (v.endCode() != null) {
                finish(s, v.endCode(), now);
                return;
            }
            if (v.pauseInputs()) s.player.pauseInputs(now);
            else s.player.resumeInputs(now);
        } catch (RuntimeException ex) {
            finish(s, "error", now);
            return;
        }
        s.player.tick(now);
        if (s.player.endedCode() != null) {
            finish(s, s.player.endedCode(), now);
            return;
        }
        if (s.player.phase() == FarmPlayer.Phase.PARKED) {
            park(s, s.player.parkedCode(), now);
            return;
        }
        String phase = phaseName(s.player.phase());
        if (s.player.phase() == FarmPlayer.Phase.LOOP) s.parkReported = null;
        if (!phase.equals(s.lastPhase) || now - s.lastReportAt >= REPORT_EVERY_MS) {
            s.lastPhase = phase;
            s.lastReportAt = now;
            report(s.taskId, "running", phase, null, s.player.loops(), 0, s.player, 0, 0, 0);
            saveJournal(s);
            TaskSync t = sync;
            if (t != null && s.taskId != null) t.setRunning(new TaskSync.Running(s.taskId, s.epoch, "running", phase, s.player.loops(), 0));
        }
    }

    private static String phaseName(FarmPlayer.Phase p) {
        return switch (p) {
            case SETUP -> "setup";
            case LOOP -> "loop";
            case RECOVER -> "recover";
            case STOP_STEPS -> "stop_steps";
            default -> "parked";
        };
    }

    /** The journal: where the session was, as counters and ids only (D3b: a hint for which anchor, never trusted for a position). */
    private void saveJournal(Session s) {
        try {
            final int loops = s.player.loops(), tick = s.player.trackTick(), recoveries = s.player.recoveries();
            final String anchor = s.player.anchorId();
            journal().update(old -> new SessionJournal.State(s.farmId, s.taskId, s.epoch, loops, anchor, tick, recoveries, old.stopped(), old.localStop()));
        } catch (RuntimeException e) {
            // a journal that cannot be written is a hint lost
        }
    }

    private void park(Session s, String code, long now) {
        boolean again = code.equals(s.parkReported); // a retry that parks again for the same reason is not reported again
        s.parked = true;
        s.parkCode = code;
        s.parkedAt = again ? s.parkedAt : now;
        s.retryAt = now + 5_000;
        s.player.park(code, now);
        snapParked = true;
        snapPark = code;
        if (!again) {
            LOG.info("{}session parked: code={}", PREFIX, code);
            saveJournal(s);
            report(s.taskId, "paused", "parked", code, s.player.loops(), 0, s.player, 0, 0, 0);
            TaskSync t = sync;
            if (t != null && s.taskId != null) t.setRunning(new TaskSync.Running(s.taskId, s.epoch, "paused", "parked", s.player.loops(), 0));
        }
        s.parkReported = code;
        s.lastTickAt = now;
    }

    private void resume(Session s, long now, boolean nearest) {
        // The CT may have changed while parked (a setting, a module, a logback reconfigure): the start checks run again (review item 8)
        FarmLogGuard.install(FarmerGuard::active);
        String pre = CONFIG.farmerEnabled ? FarmerGuard.preflight(view()) : "guard_off";
        if (pre != null) {
            finish(s, pre, now);
            return;
        }
        boolean retry = "pathfinder_busy".equals(s.parkCode);
        s.parked = false;
        s.parkCode = null;
        snapParked = false;
        snapPark = null;
        s.lastTickAt = now;
        s.player.resume(now, nearest);
        s.lastPhase = "";
        if (!retry) LOG.info("{}session resumed", PREFIX);
    }

    private void finish(Session s, String code, long now) {
        if (session != s) return;
        String ph = phaseName(s.player.phase());
        s.player.end(code);
        session = null;
        sessionOpen = false;
        sessionFarm = null;
        snapTask = null;
        snapParked = false;
        snapPark = null;
        snapPhase = "none";
        try {
            journal().update(old -> new SessionJournal.State(null, null, 0, 0, null, 0, 0, old.stopped(), old.localStop()));
        } catch (RuntimeException e) {
            // nothing to clear
        }
        summary = "idle";
        LOG.info("{}session ended: code={} loops={} recoveries={}", PREFIX, code, s.player.loops(), s.player.recoveries());
        String state = FarmCodes.stateOf(code);
        report(s.taskId, state, ph, code, s.player.loops(), s.runtimeMs, s.player, 0, 0, 0);
    }

    /** Queues a report for the sync worker. Local sessions (no task id) report nothing. */
    private void report(String taskId, String state, String phase, String code, int loops, long runtimeMs, FarmPlayer p, int a, int b, int c) {
        TaskSync t = sync;
        if (t == null || taskId == null) return;
        String cpStep = p != null && FarmCodes.CHECKPOINT_STEPS.contains(phase) ? phase : null;
        t.queueReport(new TaskSync.Report(state, phase, loops, 0, cpStep, loops, p == null ? null : p.anchorId(), p == null ? 0 : p.trackTick(),
            p == null ? 0 : p.recoveries(), runtimeMs, code));
    }

    // ---- status (terminal) --------------------------------------------------------------------------------------------

    /** One summary per line for {@code stash farm status}; no position, no name. */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        out.add("Farmer role: " + (CONFIG.farmerEnabled ? "on" : "off"));
        TaskSync t = sync;
        out.add("Server: " + (t == null ? "not running" : t.serverOld() ? "too old for farms" : t.keyProblem() ? "signature refused" : t.everOk() ? "synced" : "no sync yet"));
        out.add("Roles from the server: " + (t == null ? "-" : String.join(",", t.roles())));
        out.add("STOP: " + (stopped() ? (journal().get().localStop() ? "on (local)" : "on") : "off"));
        out.add("Accepting uploads: " + (accept.on(System.currentTimeMillis()) ? accept.secondsLeft(System.currentTimeMillis()) + " s left" : "off"));
        // from the snapshot: the session belongs to the bot tick
        out.add("Session: " + (!sessionOpen ? "none" : (snapParked ? "parked (" + snapPark + ")" : "running") + ", phase " + snapPhase
            + ", loops " + snapLoops + ", recoveries " + snapRecoveries));
        out.add("Farms known: " + registry.size());
        return out;
    }

    /**
     * {@code stash farm selftest}: the setup table row by row, the key (source and 4-byte id only, never the key), a seal round trip in
     * memory and the feed gates. Terminal only. Never prints a key, a position or a name.
     */
    public List<String> selftestLines() {
        List<String> out = new ArrayList<>();
        FarmStore st = store();
        String kc = st.keyCode();
        out.add("Key: " + (kc == null ? "ok, source " + st.keySource() + ", id " + st.keyIdHex() : "NOT USABLE (" + kc + ")"));
        if ("key_invalid".equals(kc)) {
            out.add("  A farm.key of the wrong length is never deleted or replaced: remove or fix it by hand, or set QWERTE_FARM_KEY (32 bytes, base64).");
        }
        if ("key_missing".equals(kc)) out.add("  Sealed files exist but no key does: a new key is never made over them. Put the old farm.key back or record the farms again.");
        if (kc == null) {
            try {
                byte[] probe = "selftest".getBytes(StandardCharsets.UTF_8);
                var key = com.porter.qwerte.core.farm.FarmKey.load(DIR, System.getenv("QWERTE_FARM_KEY"));
                var sealed = com.porter.qwerte.core.farm.Seal.sealLocations(key.key(), probe);
                var opened = sealed.ok() ? com.porter.qwerte.core.farm.Seal.openLocations(key.key(), sealed.data()) : sealed;
                out.add("Seal round trip: " + (opened.ok() && java.util.Arrays.equals(opened.data(), probe) ? "ok" : "FAILED (" + opened.code() + ")"));
            } catch (RuntimeException e) {
                out.add("Seal round trip: FAILED");
            }
        }
        try {
            out.add("Stored programs: " + st.entries().size());
        } catch (FarmException e) {
            out.add("Stored programs: unreadable (" + e.code() + "); files are left where they are");
        }
        ProxyFarmView v = view();
        out.add("Proxy build: bridge fixes " + (v.bridgeHardened() ? "present" : "MISSING") + ", discord patch " + (v.discordStripped() ? "present" : "MISSING"));
        String pre = FarmerGuard.preflight(v);
        out.add("Session start check: " + (pre == null ? "ok" : "would refuse with " + pre));
        if (!v.conflictingModules().isEmpty()) out.add("Modules on that conflict: " + String.join(", ", v.conflictingModules()));
        out.add("Log filter: " + (FarmLogGuard.attachedEverywhere() ? "on all three appenders" : "NOT attached everywhere (missing: " + FarmLogGuard.missing() + ")"));
        String viewerLine;
        try {
            var vw = com.aquarius.Globals.CONFIG.server.viewer;
            viewerLine = "viewer.enabled=" + (vw.enabled ? "ON" : "off") + ", viewer.control=" + (vw.control ? "ON" : "off");
        } catch (RuntimeException e) {
            viewerLine = "viewer settings unreadable (counts as on)";
        }
        out.add("Proxy viewer API: " + viewerLine + (ProxyFarmView.viewerOnNow() ? "  -> sessions and uploads refuse with unsafe_viewer" : ""));
        out.add("server.verifyUsers: " + (ProxyFarmView.verifyUsersNow() ? "on" : "OFF") + (!ProxyFarmView.verifyUsersNow() && !CONFIG.farmOwners.isEmpty()
            ? "  -> farm owner names could be forged: sessions refuse with unsafe_bridge" : ""));
        out.add("Whisper search: " + (CONFIG.whisperSearch ? "ON  -> sessions refuse with module_conflict" : "off"));
        StringBuilder g = new StringBuilder();
        for (var e : FarmerGuard.gates().entrySet()) g.append(e.getKey()).append(e.getValue() ? "=on " : "=off ");
        out.add("Feeds (off while the farmer role is on): " + g.toString().trim());
        out.add("Bridge handlers: " + (registeredOn != null ? "registered" : "not registered"));
        return out;
    }

    /** Stops a local or any session from the terminal (ends {@code stopped}). */
    public boolean stopSession() {
        if (!sessionOpen) return false;
        inbox.add(() -> {
            Session s = session;
            if (s != null) finish(s, "stopped", System.currentTimeMillis());
        });
        return true;
    }

    public boolean hasSession() {
        return sessionOpen;
    }

    public TaskSync syncOrNull() {
        return sync;
    }
}
