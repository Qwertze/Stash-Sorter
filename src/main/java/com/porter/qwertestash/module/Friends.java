package com.porter.qwertestash.module;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The players that do not count as "someone else near" (the owner's own alts): matched by UUID when the entry is one and the
 * tab list gives one, otherwise by name, ignoring case. Not the whisper whitelist, which only says who may whisper the bot.
 * Pure; the list is {@code CONFIG.friends}. Any thread.
 */
public final class Friends {
    private Friends() {
    }

    public static boolean matches(List<String> friends, @Nullable UUID uuid, @Nullable String name) {
        if (friends == null || friends.isEmpty()) return false;
        for (String entry : friends) {
            if (entry == null || entry.isBlank()) continue;
            String e = entry.trim();
            if (name != null && e.equalsIgnoreCase(name)) return true;
            if (uuid != null && e.equalsIgnoreCase(uuid.toString())) return true;
        }
        return false;
    }
}
