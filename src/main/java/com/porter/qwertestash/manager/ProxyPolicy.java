package com.porter.qwertestash.manager;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rules for the proxy's own config (scope {@code proxy}) and for other plugins' configs (scope {@code plugin:<id>}):
 * the secret lists from {@link Secrets}, the settings that are read-only because changing them is dangerous or needs a
 * restart (each with a reason), and the group a setting is shown in. Every setting that is neither is editable, checked
 * only by its type.
 */
public final class ProxyPolicy implements ConfigSchema.Policy {
    /** Path or path prefix (matches the setting itself and everything under it) to the reason it can't be changed here. */
    private static final Map<String, String> LOCKED = new LinkedHashMap<>();

    private static void lock(String reason, String... paths) {
        for (String p : paths) LOCKED.put(p, reason);
    }

    static {
        lock("needs a restart or could lock you out: change it with a command or in the config file",
            "server.bind", "server.enabled", "server.permissions", "server.viewer", "client.bindAddress", "client.server");
        lock("the account login is changed with the auth command", "authentication");
        lock("changing the Discord bot's connection needs a restart", "discord.enable", "discord.channelId", "discord.accountOwnerRoleId");
        lock("the database connection is changed with a command and needs a restart", "database");
        lock("plugin loading is changed with a command and needs a restart", "plugins");
        lock("the terminal is set up at start", "interactiveTerminal");
        lock("updates are changed with a command", "autoUpdater");
        lock("not a setting", "warning");
        lock("who may log in or use commands is changed with a command, not remotely", "server.verifyUsers", "server.extra.whitelist.enable", "server.spectator.whitelistEnabled",
            "server.spectator.fullCommandsEnabled", "server.spectator.fullCommandsRequireRegularWhitelist", "debug.allowWhitelistedToUseAccountOwnerCommands");
        lock("transfers and login limits protect the proxy: change them with a command", "server.acceptTransfers", "server.onlyAquariusTransfers", "server.loginRateLimiter.enabled");
        lock("connection and notification targets can send data elsewhere: change them with a command", "client.connectionProxy", "notifications.ntfy");
        lock("Discord channels and roles are changed with Discord commands", "discord.membersChannel", "discord.chatRelay", "discord.notificationMentionRoleId");
    }

    private final boolean proxy;

    private ProxyPolicy(boolean proxy) {
        this.proxy = proxy;
    }

    /** AquariusProxy's config. */
    public static final ProxyPolicy PROXY = new ProxyPolicy(true);
    /** Another plugin's config: nothing is known about it, so only the secret name check applies. */
    public static final ProxyPolicy OTHER_PLUGIN = new ProxyPolicy(false);

    @Override
    public SettingsSpec.@Nullable Spec spec(String path) {
        return null;
    }

    @Override
    public boolean secret(String path) {
        return proxy ? Secrets.proxy(path) : Secrets.otherPlugin(path);
    }

    @Override
    public @Nullable String lock(String path) {
        if (!proxy) return null;
        for (Map.Entry<String, String> e : LOCKED.entrySet()) {
            String p = e.getKey();
            if (path.equals(p) || path.startsWith(p + ".")) return e.getValue();
        }
        return null;
    }

    /** The first part of the path, and for the modules under client.extra their name, so the page can group by module. */
    @Override
    public @Nullable String section(String path) {
        String[] parts = path.split("\\.");
        if (proxy && parts.length >= 3 && parts[0].equals("client") && parts[1].equals("extra")) return parts[2];
        return parts[0];
    }

    @Override
    public boolean tree() {
        return true;
    }

    @Override
    public boolean generic() {
        return true;
    }

    /** Another plugin's text settings could hold anything (addresses, logins): they are shown as set-only. */
    @Override
    public boolean hideStrings() {
        return !proxy;
    }
}
