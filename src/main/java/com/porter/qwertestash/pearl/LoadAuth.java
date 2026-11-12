package com.porter.qwertestash.pearl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Who a loader may report or act for (PEARL-CONTRACT v1.0 rev 4 and 5, "Loaders must verify the sender"). The list comes only from the
 * server's reply to the loader's own signed report ({@code allow}): the names the owner approved, each with the uuid the <b>owner pinned</b>
 * to that name (never one a bot sent). A traveller is permitted only when its name is on the list <b>and</b> its uuid, taken from a verified
 * source (PearlPlus keys every stored pearl by the uuid the game server gave the whispering player), equals the pinned one. A missing uuid,
 * an unpinned (null) uuid, another uuid and a name that is not on the list authorise nobody. Pure; any thread.
 */
public final class LoadAuth {
    private LoadAuth() {
    }

    /** A name (lower case) the owner approved and the uuid pinned to it (null: not pinned yet, which authorises nobody). */
    public record Allowed(String name, @Nullable UUID uuid) {
    }

    private static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final int MAX_ALLOWED = 64;

    /** The server's {@code allow} array, read strictly: an entry that does not fit is dropped; at most 64 are kept. */
    public static List<Allowed> parse(@Nullable JsonElement e) {
        List<Allowed> out = new ArrayList<>();
        if (!(e instanceof JsonArray a)) return out;
        for (JsonElement x : a) {
            if (out.size() >= MAX_ALLOWED) break;
            if (!(x instanceof JsonObject o) || !(o.get("name") instanceof JsonPrimitive n) || !n.isString() || !PLAYER.matcher(n.getAsString()).matches()) continue;
            UUID uuid = null;
            if (o.get("uuid") instanceof JsonPrimitive u && u.isString()) {
                try {
                    uuid = UUID.fromString(u.getAsString());
                } catch (IllegalArgumentException ex) {
                    continue;
                }
            }
            out.add(new Allowed(n.getAsString().toLowerCase(Locale.ROOT), uuid));
        }
        return out;
    }

    /** The name is on the list (whatever its uuid). */
    public static boolean listed(List<Allowed> allow, @Nullable String name) {
        return find(allow, name) != null;
    }

    private static @Nullable Allowed find(List<Allowed> allow, @Nullable String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (Allowed a : allow) if (a.name().equals(n)) return a;
        return null;
    }

    /** The traveller may be reported for and loaded: listed, pinned, and {@code uuid} (a verified one) is the pinned one. */
    public static boolean permits(List<Allowed> allow, @Nullable String name, @Nullable UUID uuid) {
        Allowed a = find(allow, name);
        return a != null && a.uuid() != null && uuid != null && a.uuid().equals(uuid);
    }

    /** The traveller is listed but has no pin yet: its chambers may be shown as "owner unknown", never as its own. */
    public static boolean unpinned(List<Allowed> allow, @Nullable String name) {
        Allowed a = find(allow, name);
        return a != null && a.uuid() == null;
    }

    /** Whether the uuid belongs to a listed traveller (used to ignore the owner's own travellers when looking for "someone near"). */
    public static boolean isListedUuid(List<Allowed> allow, @Nullable UUID uuid) {
        if (uuid == null) return false;
        for (Allowed a : allow) if (uuid.equals(a.uuid())) return true;
        return false;
    }
}
