package com.porter.qwertestash.manager;

import com.aquarius.command.CommandManager;
import com.aquarius.command.api.Command;
import com.aquarius.command.api.CommandContext;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.context.CommandContextBuilder;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a console command from the dashboard may not do, whatever the website or a server pretending to be it asks for:
 * the commands that run other commands, change how the proxy starts, updates, logs in, who may use it or where it sends
 * data, and the plugin's own settings that decide who can reach this proxy. The command is parsed with the proxy's own
 * dispatcher and the path of literals it resolves to is checked, so an alias ({@code x} for multi) or other letter case
 * can not get round it. Anything not listed runs as the terminal would run it.
 */
public final class CommandGate {
    private CommandGate() {
    }

    /** Whole commands, by lower-case name. */
    private static final Set<String> BLOCKED = Set.of(
        // Run other commands, scripts, files
        "multi", "tasks", "terminal", "commandconfig", "litematica", "map", "debug", "bridge",
        // Proxy lifecycle, updates, JVM, other plugins
        "jvmargs", "update", "autoupdate", "channel", "releasechannel", "shutdown", "restart", "plugins",
        // Credentials and logins
        "auth", "database", "discord", "discordmanage", "members", "chatrelay",
        // Who may log in, use the bot or reach the proxy
        "perms", "whitelist", "serverconnection", "ratelimiter", "whispercontrol", "unsupported", "kick", "transfer",
        "spectator", "spectatorentity", "swap",
        // Where the proxy connects to or sends players
        "server", "clientconnection", "switch", "connectiontest",
        // Speaking as the bot, and money
        "sendmessage", "spammer", "order", "orderfiller");

    private static final Set<String> STASH_MANAGER_BLOCKED = Set.of("url", "key", "dashboard");

    /**
     * @return why the command is refused, or null if it may run
     */
    public static @Nullable String check(String command, CommandManager manager) {
        String text = command.trim();
        if (text.startsWith("/")) text = text.substring(1);
        String[] words = text.split("\s+");
        String rawFirst = words.length == 0 ? "" : words[0].toLowerCase(Locale.ROOT);

        List<String> literals = new ArrayList<>();
        try {
            CommandContext ctx = CommandContext.create(text, new DashboardCommandSource(o -> {
            }));
            ParseResults<CommandContext> parse = manager.parse(ctx);
            CommandContextBuilder<CommandContext> b = parse.getContext();
            while (b != null) {
                for (ParsedCommandNode<CommandContext> n : b.getNodes()) {
                    if (n.getNode() instanceof LiteralCommandNode<CommandContext> lit) literals.add(lit.getLiteral().toLowerCase(Locale.ROOT));
                }
                b = b.getChild();
            }
        } catch (RuntimeException e) {
            // Parsed no further: the raw words below still decide
        }

        String first = literals.isEmpty() ? rawFirst : literals.get(0);
        Command known = manager.getCommandByLiteral().get(first);
        if (known == null) known = manager.getCommandByLiteral().get(rawFirst);
        String canonical = known == null ? first : known.commandUsage().getName().toLowerCase(Locale.ROOT);

        if (BLOCKED.contains(canonical) || BLOCKED.contains(first) || BLOCKED.contains(rawFirst)) {
            return "`" + canonical + "` can't be run from the dashboard";
        }
        List<String> rest = literals.size() > 1 ? literals.subList(1, literals.size()) : List.of();
        if (canonical.equals("replay") && (rest.stream().anyMatch(s -> s.endsWith("upload")) || java.util.Arrays.stream(words).skip(1).anyMatch(w -> w.toLowerCase(Locale.ROOT).endsWith("upload")))) {
            return "replay uploads can't be started from the dashboard";
        }
        // `stash delivery ...` and `stash orders ...` need no special case: only the manager's address, key and dashboard
        // settings are held back from the dashboard console (orders and the delivery container are meant to be set there)
        boolean stash = canonical.equals("stash");
        if (stash) {
            boolean parsedBlocked = rest.size() >= 2 && rest.get(0).equals("manager") && STASH_MANAGER_BLOCKED.contains(rest.get(1));
            boolean rawBlocked = words.length >= 3 && words[1].equalsIgnoreCase("manager") && STASH_MANAGER_BLOCKED.contains(words[2].toLowerCase(Locale.ROOT));
            if (parsedBlocked || rawBlocked) return "the Stash Manager's address, key and dashboard settings can only be changed in the proxy terminal";
        }
        return null;
    }
}
