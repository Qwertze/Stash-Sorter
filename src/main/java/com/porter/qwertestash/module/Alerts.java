package com.porter.qwertestash.module;

import com.aquarius.Proxy;
import com.aquarius.module.api.ModuleUtils;
import com.aquarius.network.client.ClientSession;
import com.aquarius.util.ChatUtil;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static com.aquarius.Globals.CACHE;
import static com.porter.qwertestash.QwerteStashPlugin.CONFIG;
import static com.porter.qwertestash.QwerteStashPlugin.LOG;

/**
 * Alerts (things that need a person) whispered to the whitelisted players, whether or not whisper search is on. The same
 * text (digits ignored) goes out at most once every 10 minutes, at most one whisper every 5 seconds goes out overall,
 * at most 5 wait in the queue (the oldest is dropped), the whole command is at most 256 characters of plain text, it
 * goes out with the proxy's configured whisper command, and nothing is sent while the bot is not online.
 */
final class Alerts {
    static final long REPEAT_MS = 10 * 60_000L;
    static final long GAP_MS = 5_000L;
    static final int MAX_LENGTH = 256;
    static final int MAX_QUEUED = 5;

    private record Dm(String player, String text) {}

    private static final ArrayDeque<Dm> QUEUE = new ArrayDeque<>();
    private static final Map<String, Long> SENT = new HashMap<>();
    private static long lastSentAt;

    static final long JOIN_DELAY_MS = 15_000L;
    private static long onlineSince;

    /**
     * Online for at least 15 seconds and the server's command tree has arrived: a whisper sent before that makes the proxy
     * parse an empty command tree and log an error.
     */
    private static boolean ready() {
        if (!online()) {
            onlineSince = 0;
            return false;
        }
        long now = System.currentTimeMillis();
        if (onlineSince == 0) onlineSince = now;
        if (now - onlineSince < JOIN_DELAY_MS) return false;
        try {
            return CACHE.getChatCache().getCommandNodes().length > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Alerts() {
    }

    static boolean online() {
        try {
            ClientSession client = Proxy.getInstance().getClient();
            return client != null && client.isConnected() && CACHE.getPlayerCache().isAlive();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Plain ASCII, one line, at most 256 characters.
     */
    static String plain(String text) {
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') out.append(' ');
            else if (c >= 32 && c < 127) out.append(c);
            else if (c > 127 && c != '§') out.append('?');
        }
        String result = out.toString().trim().replaceAll(" {2,}", " ");
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) : result;
    }

    /**
     * Queues the alert for every whitelisted player, unless one like it was sent lately or the bot is not online.
     */
    static synchronized void raise(ModuleUtils sender, String text) {
        if (CONFIG.whitelist.isEmpty() || !online()) return;

        String message = plain(text);
        if (message.isEmpty()) return;
        long now = System.currentTimeMillis();
        SENT.values().removeIf(at -> now - at >= REPEAT_MS);
        String key = message.replaceAll("[0-9-]+", "");
        if (SENT.containsKey(key)) return;
        SENT.put(key, now);
        for (String name : CONFIG.whitelist) {
            if (!ChatUtil.isValidPlayerName(name)) continue;
            QUEUE.add(new Dm(name, message));
            while (QUEUE.size() > MAX_QUEUED) QUEUE.poll();
        }
        pump(sender);
    }

    /**
     * Sends the next queued whisper when 5 seconds have passed since the last one. Called from the module ticks.
     */
    static synchronized void pump(ModuleUtils sender) {
        // Called every tick, so the time online is counted from the first tick in game
        boolean ready = ready();
        if (QUEUE.isEmpty()) return;
        if (!online()) {
            QUEUE.clear();
            return;
        }
        if (!ready) return;
        long now = System.currentTimeMillis();
        if (now - lastSentAt < GAP_MS) return;

        Dm dm = QUEUE.poll();
        lastSentAt = now;
        // sanitizeChatMessage cuts the whole command to 256 characters
        sender.sendClientPacketAsync(ChatUtil.getWhisperChatPacket(dm.player(), dm.text()));
        LOG.debug("Qwerte Stash: whispered an alert to {}: {}", dm.player(), dm.text());
    }

    static synchronized void clear() {
        QUEUE.clear();
    }
}
