package com.porter.qwertestash.manager;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the dashboard must never see or change: values are never put in the JSON, the schema only says {@code secret:true}
 * and whether it is set. Layers, as in DASHBOARD-DESIGN.md: the plugin's own list, the proxy's list (for the proxy scope),
 * a regex on every name (wide on purpose: hiding too much is fine), and a check of string values for a URL with a login in
 * it.
 */
public final class Secrets {
    private Secrets() {
    }

    /** Any path segment (or the whole path) matching this is a secret, whatever scope it is in. */
    public static final Pattern NAME = Pattern.compile(
        "(?i)token|passw|pass|pwd|secret|api_?key|key|auth|bearer|jwt|session|cookie|credential|cred|private|licen[sc]e|dsn|salt|signature|webhook|topic");

    /** A string with a login in it: scheme://user:password@host. */
    public static final Pattern URL_USER_INFO = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://[^/@\\s]+@");

    /** The plugin's own settings. */
    private static final Set<String> PLUGIN = Set.of("managerKey");

    /** AquariusProxy's config paths (scope {@code proxy}). Prefixes end in a dot and cover the whole subtree. */
    private static final Set<String> PROXY = Set.of(
        "authentication.email", "authentication.password", "discord.token", "client.connectionProxy.user", "client.connectionProxy.password",
        "database.username", "database.password", "database.lock.redisUsername", "database.lock.redisPassword", "database.lock.redisAddress",
        "notifications.ntfy.topic", "notifications.ntfy.server");
    private static final Set<String> PROXY_PREFIXES = Set.of("server.permissions.users.");

    public static boolean matchesName(String path) {
        if (NAME.matcher(path).find()) return true;
        int dot = path.lastIndexOf('.');
        return dot >= 0 && NAME.matcher(path.substring(dot + 1)).find();
    }

    /** A string value that carries a login (URL user info). */
    public static boolean hasUserInfo(String value) {
        return URL_USER_INFO.matcher(value).find();
    }

    /** A setting of this plugin's config. */
    public static boolean plugin(String path) {
        return PLUGIN.contains(path) || matchesName(path);
    }

    /** A setting of the proxy's config (path relative to the config root). */
    public static boolean proxy(String path) {
        if (PROXY.contains(path) || path.equals("server.permissions.users") || matchesName(path)) return true;
        for (String prefix : PROXY_PREFIXES) if (path.startsWith(prefix)) return true;
        return false;
    }

    /** A setting of another plugin's config: nothing is known about it, so only the name check applies (and see ProxyPolicy). */
    public static boolean otherPlugin(String path) {
        return matchesName(path);
    }
}
