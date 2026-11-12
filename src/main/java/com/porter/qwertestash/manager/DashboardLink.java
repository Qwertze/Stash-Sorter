package com.porter.qwertestash.manager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * The link to the Stash Manager website's dashboard (see stash-manager-server/DASHBOARD-DESIGN.md). Every connection is
 * opened from here. Not tied to the game tick, so it works while the bot is offline from the game server. Three kinds
 * of thread, all daemon:
 * <ul>
 *   <li>"dashboard-poll": says hello, then long-polls {@code bot/next} (the server holds it up to 25 s), hands the jobs it
 *       gets to the apply thread and notes what the server wants to know ({@code need}) and whether a browser has this bot
 *       open ({@code watching}). 429 means polling every 3 s; errors back off from 5 s to 60 s.</li>
 *   <li>"dashboard-state": pushes {@code bot/state} when a scope's schema or values changed, when asked, and re-checks the
 *       values every 3 s while watched and every 60 s otherwise (which also notices changes made from the terminal or
 *       Discord). The {@code plugin} scope is always pushed; the others (the proxy, other plugins) only once the server has
 *       asked for them, and then kept up to date.</li>
 *   <li>"dashboard-apply": one thread that runs the jobs one after another (settings changes and console commands) and
 *       posts the results.</li>
 * </ul>
 * Nothing here blocks the bot tick; every request has timeouts.
 */
public final class DashboardLink {
    private static final Gson GSON = new Gson();
    public static final String PLUGIN_SCOPE = "plugin";
    private static final long WATCHED_MS = 3_000, IDLE_MS = 60_000, RATE_LIMITED_MS = 3_000;
    private static final long BACKOFF_MIN_MS = 5_000, BACKOFF_MAX_MS = 60_000;
    private static final int MAX_COMMAND = 500;
    /** A scope whose schema and values together are bigger than this is logged (the server takes at most 8 MiB). */
    private static final int BIG_STATE = 2 * 1024 * 1024;

    /** A set of settings the dashboard can show: the config object, its rules and how a change to it is applied. */
    public record Scope(String name, Object root, ConfigSchema.Policy policy, ConfigApplier.Env env, String commandPrefix) {
    }

    /** What the link needs from the plugin. */
    public interface Host {
        /** The server's base address, or null while it isn't set right. */
        @Nullable URI base();

        String key();

        String botId();

        /** The bot account's name, or empty when not known (yet). */
        String player();

        /** The scopes that exist now, the plugin's own first. Asked again whenever it is needed (plugins can come and go). */
        List<Scope> scopes();

        String pluginId();

        String pluginVersion();

        String proxyVersion();

        String mcVersion();

        /** Console commands may run: switched on in the proxy terminal. */
        boolean consoleAllowed();

        /** Settings of the proxy and other plugins may change: switched on in the proxy terminal. */
        boolean proxyAllowed();

        /** The link keeps running only while this is true; checked between requests. */
        boolean wanted();

        /** What the live viewer reads from the game; null when there is none (then the viewer is never started). */
        default ViewerFeed.@Nullable Game viewerGame() {
            return null;
        }
    }

    private final Host host;
    private volatile boolean running;
    private volatile Thread poller, pusher;
    private final ThreadPoolExecutor apply = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "dashboard-apply");
        t.setDaemon(true);
        return t;
    });
    private volatile HttpURLConnection pollConn;
    /** The live viewer (its thread runs only while a browser watches). */
    private volatile @Nullable ViewerFeed viewer;
    /** What the server last asked of the viewer (null: nothing); the feed is started again from it when the server answers after a failure. */
    private volatile ViewerFeed.@Nullable Params viewerWanted;

    // Shared between the threads
    private volatile boolean helloOk, watching, pushSoon, watchingActivity;
    private final Set<String> needSchema = ConcurrentHashMap.newKeySet();
    private final Set<String> needValues = ConcurrentHashMap.newKeySet();
    /** Scopes the server has asked for (the plugin scope always counts). */
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    private volatile String helloPlayer = "";
    private volatile List<String> helloScopes = List.of();
    private volatile String helloCaps = "";
    private volatile String status = "starting";
    private volatile @Nullable String lastError;

    /**
     * The loader role (1.18.0): looks at PearlPlus and posts {@code bot/pearls} (PEARL-CONTRACT v1.0). Run by the state thread, so it is not held up by the
     * game tick, and only once the hello was accepted (the server needs the botId).
     */
    private final com.porter.qwertestash.pearl.PearlReporter pearls = new com.porter.qwertestash.pearl.PearlReporter(new com.porter.qwertestash.pearl.PearlSource(),
        new com.porter.qwertestash.pearl.PearlReporter.Transport() {
            @Override
            public com.porter.qwertestash.pearl.PearlReporter.@Nullable Result send(JsonObject body) {
                JsonObject o = body.deepCopy();
                o.addProperty("botId", host.botId());
                ManagerHttp.Response r = post("bot/pearls", o, 15_000);
                return r == null ? null : new com.porter.qwertestash.pearl.PearlReporter.Result(r.code(), r.body());
            }

            @Override
            public void refused(int code) {
                LOG.warn("Stash Manager dashboard: the server refused this bot's pearl loader report ({}): approve this bot as the loader of its station on the website's Pearl travel panel.", code);
            }
        });

    // State thread only
    private final Map<String, String[]> sent = new HashMap<>();
    private final Set<String> bigWarned = new HashSet<>();
    private long lastCheck;

    public DashboardLink(Host host) {
        this.host = host;
        apply.allowCoreThreadTimeOut(true);
        ViewerFeed.Game game = host.viewerGame();
        if (game != null) viewer = new ViewerFeed(game, this::postViewer, host.botId(), () -> viewerWanted = null);
    }

    /** The live viewer, or null when the host has none. */
    public @Nullable ViewerFeed viewer() {
        return viewer;
    }

    /** Posts one viewer frame (signed). Null when it couldn't be sent; a code other than 200 is passed on as it is. */
    private ViewerFeed.@Nullable Reply postViewer(JsonObject body) {
        // Stopped meanwhile (the link, or the feed): nothing more is sent
        ViewerFeed feed = viewer;
        if (!alive() || feed == null || !feed.isActive()) return null;
        ManagerHttp.Response r = post("bot/viewer", body, 4_000);
        if (r == null) return null;
        if (r.code() != 200) {
            long retry = 0;
            if (r.code() == 429) {
                try {
                    JsonElement e = SafeJson.parse(r.body());
                    if (e.isJsonObject() && e.getAsJsonObject().has("retryMs")) retry = e.getAsJsonObject().get("retryMs").getAsLong();
                } catch (RuntimeException ignored) {
                    // The default wait
                }
            }
            return new ViewerFeed.Reply(r.code(), true, false, retry);
        }
        boolean watching = true, resync = false;
        try {
            JsonElement e = SafeJson.parse(r.body());
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("watching") && o.get("watching").isJsonPrimitive()) watching = o.get("watching").getAsBoolean();
                if (o.has("resync") && o.get("resync").isJsonPrimitive()) resync = o.get("resync").getAsBoolean();
            }
        } catch (RuntimeException ignored) {
            // A 200 without a readable body: carry on
        }
        return new ViewerFeed.Reply(200, watching, resync, 0);
    }

    public String status() {
        return status;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    public boolean watching() {
        return watching;
    }

    /** The threads are still going: false once stopped, or once the link ended because it is no longer wanted. */
    public synchronized boolean isRunning() {
        return running && poller != null && poller.isAlive();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        helloOk = false;
        watching = false;
        needSchema.clear();
        needValues.clear();
        active.clear();
        active.add(PLUGIN_SCOPE);
        sent.clear();
        status = "starting";
        poller = new Thread(this::pollLoop, "dashboard-poll");
        pusher = new Thread(this::stateLoop, "dashboard-state");
        poller.setDaemon(true);
        pusher.setDaemon(true);
        poller.start();
        pusher.start();
    }

    /** Stops the threads: the poll in progress is cut off, jobs still waiting are dropped. Does not wait long. */
    public synchronized void stop() {
        if (!running) return;
        running = false;
        status = "stopped";
        ViewerFeed v = viewer;
        if (v != null) v.stop();
        HttpURLConnection c = pollConn;
        if (c != null) {
            try {
                c.disconnect();
            } catch (RuntimeException ignored) {
                // The thread ends on its own timeout then
            }
        }
        if (poller != null) poller.interrupt();
        if (pusher != null) pusher.interrupt();
        // Stopped by a job of its own (turning the dashboard or the manager off from the website): that thread finishes the job
        // and posts its result, so it is neither cut short nor waited for
        if (Thread.currentThread().getName().equals("dashboard-apply")) return;
        apply.shutdownNow();
        try {
            if (poller != null) poller.join(1000);
            if (pusher != null) pusher.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean alive() {
        return running && host.wanted();
    }

    private void setStatus(String next) {
        if (!next.equals(status)) {
            status = next;
            if (next.equals("linked")) LOG.info("Stash Manager dashboard: linked");
            else LOG.warn("Stash Manager dashboard: {}", next);
        }
    }

    // Requests

    private ManagerHttp.@Nullable Response post(String path, JsonObject body, int readMs) {
        URI base = host.base();
        String key = host.key();
        if (base == null || key.isBlank()) return null;
        return ManagerHttp.call("POST", URI.create(base + "/api/v1/" + path), GSON.toJson(body).getBytes(java.nio.charset.StandardCharsets.UTF_8), key, host.botId(), 5_000, readMs, readMs + 5_000, null);
    }

    private boolean sayHello() {
        String player = host.player();
        JsonObject o = new JsonObject();
        o.addProperty("botId", host.botId());
        if (!player.isBlank()) o.addProperty("player", player);
        JsonObject plugin = new JsonObject();
        plugin.addProperty("id", host.pluginId());
        plugin.addProperty("version", host.pluginVersion());
        o.add("plugin", plugin);
        JsonObject proxy = new JsonObject();
        proxy.addProperty("version", host.proxyVersion());
        proxy.addProperty("mc", host.mcVersion());
        o.add("proxy", proxy);
        JsonArray caps = new JsonArray();
        caps.add("settings");
        caps.add("activity");
        if (host.consoleAllowed()) caps.add("console");
        if (host.proxyAllowed()) caps.add("proxy");
        o.add("caps", caps);
        JsonArray scopeList = new JsonArray();
        List<String> names = scopeNames();
        for (String n : names) scopeList.add(n);
        o.add("scopes", scopeList);

        ManagerHttp.Response r = post("bot/hello", o, 15_000);
        if (r == null) {
            lastError = host.key().isBlank() ? "no API key" : "bad server address";
            setStatus(lastError);
            return false;
        }
        if (r.code() == 200) {
            helloOk = true;
            helloPlayer = player;
            helloScopes = names;
            helloCaps = capsKey();
            lastError = null;
            setStatus("linked");
            // The server hasn't seen this session: it gets everything it has asked for before, again
            needSchema.addAll(active);
            needValues.addAll(active);
            pushSoon = true;
            return true;
        }
        failed(r);
        return false;
    }

    private void failed(ManagerHttp.Response r) {
        if (r.code() == ManagerHttp.BAD_SIGNATURE) {
            lastError = "the answer was not signed by a server that knows the API key (a different server, or the wrong key)";
            setStatus("server signature invalid");
        }
        else if (r.code() < 0) {
            lastError = "server not reachable" + (r.error() == null ? "" : " (" + r.error() + ")");
            setStatus("offline");
        }
        else if (r.code() == 401 && ManagerHttp.UNSIGNED.equals(r.error())) {
            lastError = "got an unsigned 401: the key may be wrong, or what answered is not the Stash Manager server";
            setStatus("401 (unsigned)");
        }
        else if (r.code() == 401) {
            lastError = "the server refused the API key";
            setStatus("error 401");
        }
        else if (ManagerHttp.UNSIGNED.equals(r.error())) {
            lastError = "got an unsigned " + r.code() + ", which is not believed";
            setStatus("error " + r.code() + " (unsigned)");
        }
        else if (r.code() == 404 || r.code() == 405) {
            lastError = "the server has no dashboard support (update it)";
            setStatus("server too old");
        }
        else {
            lastError = "the server answered " + r.code();
            setStatus("error " + r.code());
        }
    }

    // Polling

    private void pollLoop() {
        long backoff = 0;
        while (alive()) {
            try {
                if (!helloOk || (!host.player().isBlank() && !Objects.equals(host.player(), helloPlayer)) || !scopeNames().equals(helloScopes) || !capsKey().equals(helloCaps)) {
                    if (!sayHello()) {
                        stopViewer();
                        backoff = next(backoff);
                        sleep(backoff);
                        continue;
                    }
                    backoff = 0;
                }

                URI base = host.base();
                String key = host.key();
                if (base == null || key.isBlank()) {
                    helloOk = false;
                    stopViewer();
                    sleep(5_000);
                    continue;
                }
                ManagerHttp.Response r = ManagerHttp.call("GET", URI.create(base + "/api/v1/bot/next?botId=" + java.net.URLEncoder.encode(host.botId(), java.nio.charset.StandardCharsets.UTF_8)),
                    null, key, host.botId(), 5_000, 35_000, 40_000, c -> pollConn = c);
                if (!alive()) break;

                if (r.code() == 200) {
                    backoff = 0;
                    setStatus("linked");
                    take(r.body());
                }
                else if (r.code() == 204) {
                    backoff = 0;
                    setStatus("linked");
                    // The server answers and nothing changed: a viewer that stopped on a failure while it is still wanted starts again
                    restartViewer();
                }
                else if (r.code() == 429) {
                    // Too many bots waiting at the server: poll gently
                    sleep(RATE_LIMITED_MS);
                }
                else {
                    failed(r);
                    stopViewer();
                    // The server may have forgotten this bot (409, 404): hello again
                    helloOk = false;
                    backoff = next(backoff);
                    sleep(r.code() == 404 || r.code() == 405 ? 60_000 : backoff);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                lastError = e.toString();
                stopViewer();
                try {
                    backoff = next(backoff);
                    sleep(backoff);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    /** The server can't be reached (or refused the poll): the viewer stops asking it for anything. */
    private void stopViewer() {
        ViewerFeed v = viewer;
        if (v != null) v.stop();
    }

    private void restartViewer() {
        ViewerFeed v = viewer;
        ViewerFeed.Params wanted = viewerWanted;
        if (v != null && wanted != null && !v.isActive()) v.update(true, wanted);
    }

    private static long next(long backoff) {
        return backoff == 0 ? BACKOFF_MIN_MS : Math.min(BACKOFF_MAX_MS, backoff * 2);
    }

    private void sleep(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (alive() && System.currentTimeMillis() < end) Thread.sleep(Math.min(250, Math.max(1, end - System.currentTimeMillis())));
    }

    /** A 200 reply from bot/next: what the server wants to know, whether a browser is watching, and the jobs. */
    private void take(String body) {
        JsonObject o;
        try {
            JsonElement e = SafeJson.parse(body);
            if (!e.isJsonObject()) return;
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        readNeed(o);
        if (o.has("watching") && o.get("watching").isJsonPrimitive()) watching = o.get("watching").getAsBoolean();
        if (o.has("watchingActivity") && o.get("watchingActivity").isJsonPrimitive()) watchingActivity = o.get("watchingActivity").getAsBoolean();
        ViewerFeed v = viewer;
        if (v != null && o.has("watchingViewer")) {
            ViewerFeed.Params params = ViewerFeed.parse(o);
            viewerWanted = params;
            v.update(params != null, params);
        }
        if (!o.has("jobs") || !o.get("jobs").isJsonArray()) return;
        for (JsonElement j : o.getAsJsonArray("jobs")) {
            if (!j.isJsonObject()) continue;
            JsonObject job = j.getAsJsonObject();
            try {
                apply.execute(() -> runJob(job));
            } catch (RuntimeException rejected) {
                // Stopping: the job is dropped, the server expires it
            }
        }
    }

    private void readNeed(JsonObject o) {
        if (!o.has("need") || !o.get("need").isJsonArray()) return;
        for (JsonElement n : o.getAsJsonArray("need")) {
            if (!n.isJsonObject()) continue;
            JsonObject need = n.getAsJsonObject();
            String scope = str(need, "scope");
            if (scope == null || scope.length() > 200 || findScope(scope) == null) continue;
            active.add(scope);
            String what = str(need, "what");
            if ("schema".equals(what)) needSchema.add(scope);
            else if ("values".equals(what)) needValues.add(scope);
            pushSoon = true;
        }
    }

    private String capsKey() {
        return (host.consoleAllowed() ? "c" : "-") + (host.proxyAllowed() ? "p" : "-");
    }

    private List<String> scopeNames() {
        List<String> names = new ArrayList<>();
        for (Scope s : host.scopes()) names.add(s.name());
        return names;
    }

    private @Nullable Scope findScope(String name) {
        for (Scope s : host.scopes()) if (s.name().equals(name)) return s;
        return null;
    }

    private static @Nullable String str(JsonObject o, String field) {
        JsonElement e = o.get(field);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    // Jobs, on the apply thread

    private void runJob(JsonObject job) {
        String id = str(job, "id");
        if (id == null) return;
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        try {
            String type = str(job, "type");
            if ("set".equals(type)) set(job, result);
            else if ("command".equals(type)) command(id, job, result);
            else {
                result.addProperty("ok", false);
                result.addProperty("error", "unknown job type");
            }
        } catch (Throwable e) {
            result.addProperty("ok", false);
            result.addProperty("error", "failed: " + e);
        }
        sendResults(List.of(result));
        // What the page shows is checked again at once
        pushSoon = true;
    }

    private void set(JsonObject job, JsonObject result) {
        String scopeName = str(job, "scope");
        String path = str(job, "path");
        if (scopeName != null && !PLUGIN_SCOPE.equals(scopeName) && !host.proxyAllowed()) {
            result.addProperty("ok", false);
            result.addProperty("error", "disabled on the proxy: run `stash manager dashboard proxy on` in the proxy terminal");
            return;
        }
        Scope scope = scopeName == null ? null : findScope(scopeName);
        if (scope == null || path == null) {
            result.addProperty("ok", false);
            result.addProperty("error", scope == null ? "unsupported scope" : "no path");
            return;
        }
        ConfigApplier applier = new ConfigApplier(scope.root(), scope.policy(), scope.env(), scope.commandPrefix());
        ConfigApplier.Result r = applier.apply(path, job.get("value"), job.has("expect") ? job.get("expect") : null);
        result.addProperty("ok", r.ok());
        if (r.value() != null) result.add("value", r.value());
        if (r.error() != null) result.addProperty("error", r.error());
    }

    private void command(String id, JsonObject job, JsonObject result) {
        String command = str(job, "command");
        if (!host.consoleAllowed()) {
            result.addProperty("ok", false);
            result.addProperty("error", "disabled on the proxy: run `stash manager dashboard console on` in the proxy terminal");
            return;
        }
        if (command == null || command.isBlank() || command.length() > MAX_COMMAND || command.chars().anyMatch(Character::isISOControl)) {
            result.addProperty("ok", false);
            result.addProperty("error", command == null || command.isBlank() ? "no command" : "the command is too long or has control characters");
            return;
        }
        ConsoleRunner.Outcome out = ConsoleRunner.run(command.trim(), late -> {
            // Output the command logs after it has returned goes on the same job
            JsonObject more = new JsonObject();
            more.addProperty("id", id);
            more.addProperty("ok", true);
            more.addProperty("more", true);
            more.add("output", late);
            sendResults(List.of(more));
        });
        result.addProperty("ok", out.ok());
        if (out.sensitive()) result.addProperty("sensitive", true);
        if (out.error() != null) result.addProperty("error", out.error());
        result.add("output", out.output());
    }

    private void sendResults(List<JsonObject> results) {
        JsonObject body = new JsonObject();
        body.addProperty("botId", host.botId());
        JsonArray array = new JsonArray();
        for (JsonObject r : results) array.add(r);
        body.add("results", array);
        // One more try if the server could not be reached: the page is waiting for it
        for (int attempt = 0; attempt < 2; attempt++) {
            ManagerHttp.Response r = post("bot/results", body, 15_000);
            if (r != null && r.code() > 0) return;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // Pushing state

    private void stateLoop() {
        while (alive()) {
            try {
                Thread.sleep(500);
                // The poll thread ended without being stopped (an Error got past it): a new one takes over
                Thread p = poller;
                if (p != null && !p.isAlive() && alive()) {
                    LOG.warn("Stash Manager dashboard: the poll thread died, restarting it");
                    poller = new Thread(this::pollLoop, "dashboard-poll");
                    poller.setDaemon(true);
                    poller.start();
                }
                if (!helloOk) continue;
                long now = System.currentTimeMillis();
                pushActivity(now);
                tickPearls();
                long every = watching ? WATCHED_MS : IDLE_MS;
                if (!pushSoon && needSchema.isEmpty() && needValues.isEmpty() && now - lastCheck < every) continue;
                lastCheck = now;
                pushSoon = false;
                for (Scope scope : host.scopes()) {
                    if (!active.contains(scope.name())) continue;
                    try {
                        pushState(scope);
                    } catch (Throwable e) {
                        lastError = scope.name() + ": " + e;
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                lastError = e.toString();
            }
        }
    }

    private void tickPearls() {
        try {
            var config = com.porter.qwertestash.QwerteStashPlugin.CONFIG;
            if (config != null) pearls.graceMs = Math.max(15, config.pearlGraceSeconds) * 1000L;
            pearls.tick();
            if (lastError != null && lastError.startsWith("pearl loader")) lastError = null;
        } catch (Throwable e) {
            // The loader role never takes the link's thread down
            lastError = "pearl loader: " + e.getClass().getSimpleName();
        }
    }

    // Activity: built every 2 s while a browser watches the tab (every 5 s otherwise, to notice changes), pushed every 2 s / 30 s
    // and at once when the connection state or an item changes
    private long lastActBuild, lastActPush;
    private String lastActSignature = "";

    private void pushActivity(long now) {
        boolean watched = watchingActivity;
        if (now - lastActBuild < (watched ? 2_000 : 5_000)) return;
        lastActBuild = now;
        ActivityFeed.Snap snap = ActivityFeed.INSTANCE.snapshot();
        boolean changed = !snap.signature().equals(lastActSignature);
        boolean due = now - lastActPush >= (watched ? 2_000 : 30_000);
        if (!changed && !due) return;
        JsonObject body = new JsonObject();
        body.addProperty("botId", host.botId());
        body.add("activity", snap.json());
        if (GSON.toJson(body).length() > 250 * 1024) {
            snap.json().add("events", new JsonArray());
            body.add("activity", snap.json());
        }
        ManagerHttp.Response r = post("bot/activity", body, 15_000);
        lastActPush = now;
        if (r != null && r.code() == 200) {
            lastActSignature = snap.signature();
            ActivityFeed.INSTANCE.ack(snap.lastEvent());
        }
    }

    private void pushState(Scope scope) {
        ConfigSchema.Built built = ConfigSchema.build(scope.root(), scope.policy());
        String[] before = sent.get(scope.name());
        boolean schemaChanged = before == null || !built.schemaHash().equals(before[0]);
        boolean valuesChanged = before == null || !built.valuesHash().equals(before[1]);
        boolean askSchema = needSchema.remove(scope.name());
        boolean askValues = needValues.remove(scope.name());
        boolean sendSchema = askSchema || schemaChanged;
        if (!sendSchema && !valuesChanged && !askValues) return;

        if (built.truncated() && bigWarned.add(scope.name() + "/truncated")) {
            LOG.warn("Stash Manager dashboard: the settings of scope {} were cut at {} nodes / {} levels", scope.name(), ConfigSchema.MAX_NODES, ConfigSchema.MAX_DEPTH);
        }
        JsonObject o = new JsonObject();
        o.addProperty("botId", host.botId());
        o.addProperty("scope", scope.name());
        o.addProperty("schemaHash", built.schemaHash());
        o.addProperty("valuesHash", built.valuesHash());
        if (sendSchema) o.add("schema", built.schema());
        o.add("values", built.values());
        if (!scope.name().equals(PLUGIN_SCOPE)) {
            int size = GSON.toJson(o).length();
            if (size > BIG_STATE && bigWarned.add(scope.name())) {
                LOG.warn("Stash Manager dashboard: the settings of scope {} are {} KiB as JSON (the server takes at most 8 MiB)", scope.name(), size / 1024);
            }
        }
        ManagerHttp.Response r = post("bot/state", o, 30_000);
        if (r != null && r.code() == 200) {
            sent.put(scope.name(), new String[] {built.schemaHash(), built.valuesHash()});
            try {
                JsonElement e = SafeJson.parse(r.body());
                if (e.isJsonObject()) readNeed(e.getAsJsonObject());
            } catch (RuntimeException ignored) {
                // A reply without need
            }
        }
        else {
            // Not delivered: tried again at the next check
            if (sendSchema) needSchema.add(scope.name());
            if (askValues) needValues.add(scope.name());
            if (r != null && r.code() > 0) lastError = "the server answered " + r.code() + " to the settings of " + scope.name();
        }
    }

    /** For tests: the paths of the settings (not groups) in a schema. */
    public static List<String> pathsOf(JsonArray schema) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : schema) {
            JsonObject n = e.getAsJsonObject();
            if (n.has("children")) out.addAll(pathsOf(n.getAsJsonArray("children")));
            else out.add(n.get("path").getAsString());
        }
        return out;
    }
}
