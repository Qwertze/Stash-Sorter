package com.porter.qwertestash.pearl;

import com.aquarius.cache.data.entity.EntityPlayer;
import com.aquarius.module.impl.AutoLoadModule;
import com.aquarius.module.impl.PearlManager;
import com.aquarius.util.config.Config;
import com.porter.qwertestash.manager.ManagerIndex;
import com.porter.qwertestash.module.Friends;
import com.porter.qwertestash.stash.Stash;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.aquarius.Globals.BARITONE;
import static com.aquarius.Globals.CACHE;
import static com.aquarius.Globals.MODULE;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;

/**
 * Reads PearlPlus (the proxy's pearl loader) for the loader report: the stored pearls (the {@code players} map of its config, keyed by the uuid the
 * game server gave the player), whether each pearl's entity is seen and whether the bot is in range of it (both through the loader module's own
 * {@code PearlManager}, reached by reflection like {@code ProxySources} does, so PearlPlus's own range rule is the one used), who is in the tab list, and
 * the load in progress (the pathfinder's interact process aimed at a stored pearl). Each read is guarded: anything that is missing in this proxy build
 * leaves that piece out ({@code unknown}). No coordinate leaves this class: positions are only compared.
 * <p>
 * Thread: the dashboard link's state thread (the proxy's caches are read the way the activity feed already reads them).
 */
public final class PearlSource implements PearlReporter.Source {
    private static final Pattern INTERACT_BLOCK = Pattern.compile("InteractWithBlock[(]x=(-?[0-9]+), y=(-?[0-9]+), z=(-?[0-9]+), leftClick=false");
    private static final double NEAR_CHAMBER = 16.0, PLAYER_NEAR = 16.0;

    private final Map<UUID, Long> onlineSince = new HashMap<>();
    /** Where each chamber was last seen armed (a dimension): "not seen" in another one says nothing. */
    private final Map<String, String> armedIn = new HashMap<>();
    /** When each chamber was first not seen (in range): "not seen" must last before it counts as empty. */
    private final Map<String, Long> unseenSince = new HashMap<>();
    private boolean wasConnected;
    private long inGameSince;
    private Field managerField, triesField;
    private Method rangeMethod;

    @Override
    public boolean enabled() {
        try {
            return CONFIG.pearlLoader && com.aquarius.Globals.CONFIG.client.extra.pearlPlus.autoLoad.enabled;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String player() {
        try {
            var profile = CACHE.getProfileCache().getProfile();
            String name = profile == null ? null : profile.getName();
            return name == null ? "" : name;
        } catch (RuntimeException e) {
            return "";
        }
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    private static boolean online() {
        try {
            var client = com.aquarius.Proxy.getInstance().getClient();
            return client != null && client.isConnected() && CACHE.getPlayerCache().isAlive();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean inQueue() {
        try {
            return com.aquarius.Proxy.getInstance().isInQueue();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The stored pearls as (owner uuid, entry) pairs: a copy that a concurrent change of PearlPlus's map can only empty, never break. */
    private static List<Map.Entry<UUID, Config.Client.Extra.PearlPlus.PlayerPearls>> players(Config.Client.Extra.PearlPlus plus) {
        try {
            return new ArrayList<>(plus.players.entrySet());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<Config.Client.Extra.PearlPlus.StoredPearl> pearlsOf(Config.Client.Extra.PearlPlus.PlayerPearls pp) {
        try {
            return new ArrayList<>(pp.pearls.values());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    @Override
    public PearlReporter.Reading read(List<LoadAuth.Allowed> allow, LoadTracker.@Nullable Target watching, long graceMs) {
        long now = now();
        boolean queue = inQueue();
        boolean connected = online() && !queue;
        if (connected && !wasConnected) inGameSince = now;
        if (!connected) {
            inGameSince = 0;
            onlineSince.clear();
        }
        wasConnected = connected;

        Config.Client.Extra.PearlPlus plus = null;
        try {
            plus = com.aquarius.Globals.CONFIG.client.extra.pearlPlus;
        } catch (RuntimeException | LinkageError e) {
            // No PearlPlus in this build: nothing to say
        }
        boolean drop = false, temporary = false;
        List<PearlReport.Pearl> pearls = new ArrayList<>();
        LoadTracker.Target active = null;
        boolean watchPresent = false, watchInRange = false, watchNear = false;
        int tries = 0;
        double px = px(), pz = pz();
        if (plus != null && connected) {
            try {
                drop = plus.autoLoad.dropPearlAfterLoad;
                temporary = plus.autoDetect.temporaryMode;
            } catch (RuntimeException | LinkageError e) {
                // Left at false
            }
            String dim = dimension();
            double py = 0;
            try {
                py = CACHE.getPlayerCache().getY();
            } catch (RuntimeException e) {
                connected = false;
            }
            double range = 32;
            try {
                if (plus.autoDetect.temporaryRemovalRange > 0) range = plus.autoDetect.temporaryRemovalRange;
            } catch (RuntimeException | LinkageError e) {
                // The default
            }
            // The load in progress first: the pearl it is for is the one to watch when no load was seen before
            List<Map.Entry<UUID, Config.Client.Extra.PearlPlus.PlayerPearls>> list = players(plus);
            try {
                var process = BARITONE.getInteractWithProcess();
                if (process.isActive()) {
                    Matcher m = INTERACT_BLOCK.matcher(String.valueOf(process.displayName0()));
                    if (m.find()) {
                        int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
                        for (Map.Entry<UUID, Config.Client.Extra.PearlPlus.PlayerPearls> entry : list) {
                            if (entry.getValue() == null || entry.getValue().pearls == null || entry.getValue().playerName == null) continue;
                            for (Config.Client.Extra.PearlPlus.StoredPearl sp : pearlsOf(entry.getValue())) {
                                if (sp != null && sp.x == x && sp.y == y && sp.z == z && sp.pearlId != null) {
                                    active = new LoadTracker.Target(entry.getKey(), entry.getValue().playerName, sp.pearlId);
                                    tries = interactTries(process);
                                }
                            }
                        }
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                active = null;
            }
            LoadTracker.Target focus = active != null ? active : watching;

            Map<UUID, Boolean> seenOnline = new HashMap<>();
            for (Map.Entry<UUID, Config.Client.Extra.PearlPlus.PlayerPearls> entry : list) {
                UUID owner = entry.getKey();
                Config.Client.Extra.PearlPlus.PlayerPearls pp = entry.getValue();
                if (owner == null || pp == null || pp.pearls == null) continue;
                boolean ownerOnline = tabListHas(owner);
                seenOnline.put(owner, ownerOnline);
                if (ownerOnline) onlineSince.putIfAbsent(owner, now);
                else onlineSince.remove(owner);
                for (Config.Client.Extra.PearlPlus.StoredPearl sp : pearlsOf(pp)) {
                    if (sp == null || sp.pearlId == null) continue;
                    boolean inRange = inRangeOf(sp, px, py, pz, range);
                    Boolean seen = connected && inRange ? presentOf(sp) : null;
                    boolean present = Boolean.TRUE.equals(seen);
                    String key = owner + "/" + sp.pearlId;
                    if (present && dim != null) armedIn.put(key, dim);
                    if (present || !inRange || seen == null) unseenSince.remove(key);
                    else unseenSince.putIfAbsent(key, now);
                    long unseenFor = present ? 0 : unseenSince.containsKey(key) ? now - unseenSince.get(key) : 0;
                    boolean dimKnown = dim != null && dim.equals(armedIn.get(key));
                    pearls.add(new PearlReport.Pearl(owner, pp.playerName, sp.pearlId, present, inRange && seen != null, dimKnown, ownerOnline,
                        onlineSince.getOrDefault(owner, 0L), unseenFor));
                    if (focus != null && focus.owner().equals(owner) && focus.pearlId().equals(sp.pearlId)) {
                        watchPresent = present;
                        watchInRange = inRange && seen != null;
                        watchNear = ownerNear(owner, sp);
                    }
                }
            }
            onlineSince.keySet().removeIf(u -> !Boolean.TRUE.equals(seenOnline.get(u)));
        }
        boolean near = connected && playerNear(allow, px, pz);
        PearlReport.Snapshot snapshot = new PearlReport.Snapshot(player(), now, drop, temporary, connected, queue, inGameSince, graceMs, false, near, pearls);
        return new PearlReporter.Reading(snapshot, active, watchPresent, watchInRange, watchNear, tries);
    }

    private static double px() {
        try {
            return CACHE.getPlayerCache().getX();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double pz() {
        try {
            return CACHE.getPlayerCache().getZ();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private static @Nullable String dimension() {
        try {
            String d = Stash.currentDimension();
            return d == null ? null : ManagerIndex.namespaced(d);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean tabListHas(UUID uuid) {
        try {
            return CACHE.getTabListCache().get(uuid).isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private @Nullable PearlManager manager() {
        try {
            var module = MODULE.get(AutoLoadModule.class);
            if (managerField == null) {
                managerField = module.getClass().getDeclaredField("pearlManager");
                managerField.setAccessible(true);
            }
            return (PearlManager) managerField.get(module);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether the stored pearl's entity is seen, through the loader module's own PearlManager (null: it can't be asked). */
    private @Nullable Boolean presentOf(Config.Client.Extra.PearlPlus.StoredPearl stored) {
        try {
            PearlManager m = manager();
            return m == null ? null : m.isPearlPresent(stored);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** PearlPlus's own presence-range rule (its private {@code isWithinPresenceRange}); the same distance test when that can't be reached. */
    private boolean inRangeOf(Config.Client.Extra.PearlPlus.StoredPearl sp, double px, double py, double pz, double range) {
        try {
            PearlManager m = manager();
            if (m != null) {
                if (rangeMethod == null) {
                    rangeMethod = PearlManager.class.getDeclaredMethod("isWithinPresenceRange", Config.Client.Extra.PearlPlus.StoredPearl.class);
                    rangeMethod.setAccessible(true);
                }
                return (Boolean) rangeMethod.invoke(m, sp);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // The same test below
        }
        return Math.sqrt(sq(px - (sp.x + 0.5)) + sq(py - sp.y) + sq(pz - (sp.z + 0.5))) <= range;
    }

    private int interactTries(Object process) {
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

    /** The owner's player entity is within 16 blocks of the chamber. */
    private static boolean ownerNear(UUID owner, Config.Client.Extra.PearlPlus.StoredPearl sp) {
        try {
            for (EntityPlayer p : CACHE.getEntityCache().getPlayers().values()) {
                if (p == null || p.isRemoved() || !owner.equals(p.getUuid())) continue;
                return Math.sqrt(sq(p.getX() - (sp.x + 0.5)) + sq(p.getY() - sp.y) + sq(p.getZ() - (sp.z + 0.5))) <= NEAR_CHAMBER;
            }
        } catch (RuntimeException e) {
            // Not seen
        }
        return false;
    }

    /** A player that is not the bot, a friend or one of the owner's approved travellers is within 16 blocks (flat). */
    private static boolean playerNear(List<LoadAuth.Allowed> allow, double x, double z) {
        try {
            int self = CACHE.getPlayerCache().getEntityId();
            for (EntityPlayer p : CACHE.getEntityCache().getPlayers().values()) {
                if (p == null || p.getEntityId() == self || p.isSelfPlayer() || p.isRemoved()) continue;
                if (Math.hypot(p.getX() - x, p.getZ() - z) > PLAYER_NEAR) continue;
                String name = CACHE.getTabListCache().get(p.getUuid()).map(PlayerListEntry::getName).orElse(null);
                if (Friends.matches(CONFIG.friends, p.getUuid(), name) || LoadAuth.isListedUuid(allow, p.getUuid())) continue;
                return true;
            }
        } catch (RuntimeException e) {
            // Not known
        }
        return false;
    }
}
