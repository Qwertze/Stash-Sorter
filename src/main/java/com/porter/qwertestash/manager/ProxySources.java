package com.porter.qwertestash.manager;

import com.aquarius.Proxy;
import com.aquarius.module.api.Module;
import com.aquarius.module.impl.AquariusMiner;
import com.aquarius.module.impl.AutoEat;
import com.aquarius.module.impl.AutoReconnect;
import com.aquarius.module.impl.ElytraPilot;
import com.aquarius.module.impl.HighwayBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.CONFIG;
import static com.aquarius.Globals.MODULE;
import static com.aquarius.Globals.PLUGIN_MANAGER;

/**
 * Reads what the proxy is doing: the connection, the pathfinder's active process, the task-style modules that say what
 * they are doing, the module list and the loaded plugins. Each piece is read inside its own try/catch (LinkageError
 * included), so a part that is missing or has changed in this proxy version is left out and nothing else is affected.
 */
public final class ProxySources {
    private ProxySources() {
    }

    public record ModuleInfo(String name, String plugin, boolean enabled, @Nullable String status) {
    }

    private interface Reader {
        void read() throws Exception;
    }

    private static void guard(Reader r) {
        try {
            r.read();
        } catch (Exception | LinkageError e) {
            // This piece is left out
        }
    }

    // Connection

    static JsonObject connection() {
        JsonObject o = new JsonObject();
        boolean[] online = {false};
        guard(() -> {
            Proxy p = Proxy.getInstance();
            boolean connected = p.isConnected();
            boolean queue = connected && p.isInQueue();
            online[0] = connected && !queue;
            o.addProperty("state", connected ? (queue ? "queue" : "online") : p.getLoggingIn().get() ? "connecting" : "disconnected");
            if (queue) {
                int position = p.getQueuePosition();
                o.addProperty("queuePosition", position);
                guard(() -> o.addProperty("queueEta", com.aquarius.feature.queue.Queue.getQueueEta(position)));
            }
            if (connected) {
                guard(() -> o.addProperty("uptimeSeconds", p.getOnlineTimeSeconds()));
                guard(() -> {
                    var client = p.getClient();
                    if (client != null) o.addProperty("ping", client.getPing());
                });
            }
            guard(() -> o.addProperty("spectators", p.getSpectatorConnections().size()));
            guard(() -> {
                var current = p.getCurrentPlayer().get();
                if (current != null) o.addProperty("controllingPlayer", current.getName());
                else o.add("controllingPlayer", com.google.gson.JsonNull.INSTANCE);
            });
        });
        if (!o.has("state")) o.addProperty("state", "offline");
        guard(() -> o.addProperty("server", ActivityFeed.cap(CONFIG.client.server.address, 200)));
        if (online[0]) {
            guard(() -> {
                String dim = com.porter.qwertestash.stash.Stash.currentDimension();
                if (dim != null) o.addProperty("dimension", ManagerIndex.namespaced(dim));
            });
            guard(() -> {
                var player = CACHE.getPlayerCache();
                o.addProperty("x", (int) Math.floor(player.getX()));
                o.addProperty("y", (int) Math.floor(player.getY()));
                o.addProperty("z", (int) Math.floor(player.getZ()));
                var self = player.getThePlayer();
                if (self != null) {
                    if (self.getHealth() != null) o.addProperty("health", Math.round(self.getHealth() * 10) / 10.0);
                    o.addProperty("food", self.getFood());
                }
            });
        }
        return o;
    }

    // What is working now

    static List<ActivityFeed.Item> processes() {
        List<ActivityFeed.Item> items = new ArrayList<>();
        guard(() -> {
            var control = BARITONE.getPathingControlManager();
            if (!control.isActive()) return;
            var process = control.mostRecentInControl();
            String name = process.map(com.aquarius.feature.pathfinder.process.IBaritoneProcess::displayName).orElse("Pathfinder");
            String goal = control.mostRecentCommand().map(c -> c.goal == null ? null : c.goal.toString()).orElse(null);
            items.add(new ActivityFeed.Item("Pathfinder", "process", name, goal == null ? null : "goal: " + goal, null, "info"));
        });
        guard(() -> {
            var hb = MODULE.get(HighwayBuilder.class);
            if (hb != null && hb.isEnabled() && hb.phase() != HighwayBuilder.Phase.IDLE) {
                items.add(new ActivityFeed.Item("Highway Builder", "module", "Highway: " + hb.phase(), hb.built() + " blocks built", null, "info"));
            }
        });
        guard(() -> {
            var ep = MODULE.get(ElytraPilot.class);
            String phase = ep == null || !ep.isEnabled() ? null : ep.viewerPhase();
            if (phase != null && !phase.isBlank()) items.add(new ActivityFeed.Item("Elytra Pilot", "module", "Elytra: " + phase, null, null, "info"));
        });
        guard(() -> {
            var ar = MODULE.get(AutoReconnect.class);
            if (ar != null && ar.isEnabled() && ar.autoReconnectIsInProgress()) items.add(new ActivityFeed.Item("Auto Reconnect", "module", "Waiting to reconnect", null, null, "info"));
        });
        guard(() -> {
            var eat = MODULE.get(AutoEat.class);
            if (eat != null && eat.isEnabled() && eat.isEating()) items.add(new ActivityFeed.Item("Auto Eat", "module", "Eating", null, null, "info"));
        });
        guard(() -> {
            var miner = MODULE.get(AquariusMiner.class);
            if (miner != null && miner.isEnabled()) {
                String state = miner.isComplete() ? "done" : miner.isPaused() ? "paused" : miner.isStoring() ? "storing" : "mining";
                items.add(new ActivityFeed.Item("Aquarius Miner", "module", "Miner: " + state, null, null, "info"));
            }
        });
        guard(() -> pearlLoader(items));
        // Any other enabled module that can say what it is doing (stashscanner, kitmaker, regear, enchanter, orderfiller, ...)
        guard(() -> {
            for (Module m : MODULE.getModules()) {
                if (!m.isEnabled() || ours(m)) continue;
                String line = statusOf(m);
                if (line != null) items.add(new ActivityFeed.Item(displayName(m), "module", line, null, null, "info"));
            }
        });
        return items;
    }

    private static boolean ours(Module m) {
        return m.getClass().getName().startsWith("com.porter.qwertestash.");
    }

    // Modules and plugins

    static List<ModuleInfo> modules() {
        List<ModuleInfo> out = new ArrayList<>();
        guard(() -> {
            Map<ClassLoader, String> owners = new HashMap<>();
            guard(() -> PLUGIN_MANAGER.getPluginInstances().forEach(i -> owners.put(i.getClassLoader(), i.getId())));
            for (Module m : MODULE.getModules()) {
                guard(() -> {
                    String plugin = m.getClass().getName().startsWith("com.aquarius.") ? "aquarius" : owners.getOrDefault(m.getClass().getClassLoader(), "plugin");
                    out.add(new ModuleInfo(displayName(m), plugin, m.isEnabled(), m.isEnabled() ? (pearlStatus(m) != null ? pearlStatus(m) : statusOf(m)) : null));
                });
            }
        });
        return out;
    }

    static JsonArray plugins() {
        JsonArray out = new JsonArray();
        guard(() -> {
            for (var info : PLUGIN_MANAGER.getPluginInfos()) {
                guard(() -> {
                    JsonObject j = new JsonObject();
                    j.addProperty("id", info.id());
                    j.addProperty("version", String.valueOf(info.version()));
                    j.addProperty("name", info.id());
                    out.add(j);
                });
            }
        });
        return out;
    }

    // Pearl loader (PearlPlus). Loading a pearl is BARITONE.rightClickBlock(x, y, z) on its stored position, so it shows up as
    // the interact-with process aiming at a block that is one of the stored pearls. There is no queue: a new request replaces
    // the one before. The proxy sends no event, so requested / loaded / not loaded are worked out from what the snapshots see.

    private record PearlTarget(String pearlId, String owner, int x, int y, int z, Object stored) {
    }

    private static final class PearlSeen {
        final PearlTarget target;
        final long since;
        boolean near;
        int tries;

        PearlSeen(PearlTarget target, long since) {
            this.target = target;
            this.since = since;
        }
    }

    private static final java.util.regex.Pattern INTERACT_BLOCK = java.util.regex.Pattern.compile("InteractWithBlock[(]x=(-?[0-9]+), y=(-?[0-9]+), z=(-?[0-9]+), leftClick=false");
    private static PearlSeen pearlSeen;
    private static java.lang.reflect.Field triesField;

    private static void pearlLoader(List<ActivityFeed.Item> items) {
        PearlTarget now = null;
        int tries = 0;
        double distance = Double.NaN;
        try {
            var process = BARITONE.getInteractWithProcess();
            if (process.isActive()) {
                var m = INTERACT_BLOCK.matcher(String.valueOf(process.displayName0()));
                if (m.find()) {
                    now = storedPearlAt(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                    if (now != null) {
                        tries = interactTries(process);
                        var player = CACHE.getPlayerCache();
                        distance = Math.sqrt(Math.pow(player.getX() - (now.x() + 0.5), 2) + Math.pow(player.getY() - now.y(), 2) + Math.pow(player.getZ() - (now.z() + 0.5), 2));
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            now = null;
        }

        long time = System.currentTimeMillis();
        PearlSeen was = pearlSeen;
        if (was != null && (now == null || !same(was.target, now))) {
            // That request is over: the pearl is gone from its place when it was loaded
            Boolean present = pearlStillThere(was.target.stored());
            String what = "pearl \"" + was.target.pearlId() + "\" of " + was.target.owner();
            if (present == null) ActivityFeed.INSTANCE.event("Pearl Loader", "info", "Stopped loading " + what);
            else if (!present) ActivityFeed.INSTANCE.event("Pearl Loader", "info", "Loaded " + what);
            else ActivityFeed.INSTANCE.event("Pearl Loader", "warn", "Could not load " + what + " (the pearl is still in place after " + was.tries + " tries)");
            pearlSeen = was = null;
        }
        if (now != null) {
            if (was == null) {
                was = new PearlSeen(now, time);
                pearlSeen = was;
                ActivityFeed.INSTANCE.event("Pearl Loader", "info", "Loading pearl \"" + now.pearlId() + "\" for " + now.owner());
            }
            was.tries = Math.max(was.tries, tries);
            boolean near = distance <= 5.5;
            was.near = near;
            String phase = near ? "interacting with the pearl" : "walking there (" + Math.round(distance) + " blocks away)";
            if (tries > 0) phase += ", retrying (path try " + tries + ")";
            items.add(new ActivityFeed.Item("Pearl Loader", "process", "Loading pearl \"" + now.pearlId() + "\" for " + now.owner(),
                phase + "; at " + now.x() + " " + now.y() + " " + now.z() + "; no other request is waiting", null, tries >= 3 ? "warn" : "info"));
        }
    }

    private static boolean same(PearlTarget a, PearlTarget b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z() && a.pearlId().equals(b.pearlId());
    }

    private static @Nullable PearlTarget storedPearlAt(int x, int y, int z) {
        var plus = CONFIG.client.extra.pearlPlus;
        for (var player : new ArrayList<>(plus.players.values())) {
            for (var pearl : new ArrayList<>(player.pearls.values())) {
                if (pearl.x == x && pearl.y == y && pearl.z == z) return new PearlTarget(pearl.pearlId, player.playerName, x, y, z, pearl);
            }
        }
        return null;
    }

    private static int interactTries(Object process) {
        try {
            if (triesField == null) {
                triesField = process.getClass().getDeclaredField("tries");
                triesField.setAccessible(true);
            }
            return triesField.getInt(process);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    /** Whether the pearl is still where it was stored, through the loader module's own PearlManager; null if that can't be asked. */
    private static @Nullable Boolean pearlStillThere(Object stored) {
        try {
            var module = MODULE.get(com.aquarius.module.impl.AutoLoadModule.class);
            var field = module.getClass().getDeclaredField("pearlManager");
            field.setAccessible(true);
            var manager = (com.aquarius.module.impl.PearlManager) field.get(module);
            return manager.isPearlPresent((com.aquarius.util.config.Config.Client.Extra.PearlPlus.StoredPearl) stored);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** "5 pearls of 3 players" for the PearlPlus modules, or null. */
    private static @Nullable String pearlStatus(Module m) {
        String name = m.getClass().getSimpleName();
        if (!name.equals("AutoLoadModule") && !name.equals("AutoDetectModule")) return null;
        try {
            int players = 0, pearls = 0;
            for (var p : new ArrayList<>(CONFIG.client.extra.pearlPlus.players.values())) {
                players++;
                pearls += p.pearls.size();
            }
            return pearls + " pearls registered for " + players + " players";
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // Helpers

    private static final Method NONE;

    static {
        Method none = null;
        try {
            none = Object.class.getMethod("toString");
        } catch (NoSuchMethodException ignored) {
            // Object always has toString
        }
        NONE = none;
    }

    private static final Map<Class<?>, Method> STATUS = new HashMap<>();

    /** A short status line a module can give: a public statusLine() without arguments, looked up once per class. */
    static @Nullable String statusOf(Object module) {
        Method m;
        synchronized (STATUS) {
            m = STATUS.computeIfAbsent(module.getClass(), c -> {
                try {
                    Method found = c.getMethod("statusLine");
                    return found.getReturnType() == String.class ? found : NONE;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return NONE;
                }
            });
        }
        if (m == NONE) return null;
        try {
            Object v = m.invoke(module);
            return v instanceof String s && !s.isBlank() ? s.strip() : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** "AntiAFK" for a module class. */
    static String displayName(Object module) {
        return module.getClass().getSimpleName();
    }
}
