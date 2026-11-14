package com.porter.qwertestash.farm;

import java.lang.reflect.Field;

/**
 * The 2.6p build id (patch 7): two public static String fields the patched proxy carries, read by reflection so an unpatched proxy
 * (the field is missing) is told apart without a link error. {@code BridgeProtocol.FARM_HARDENING} ({@code 2.6p-2}) stands for the Bridge fixes
 * (onHello cap, Throwable catch, readString bound, total-size cap) and the proxy's own hello on login (patch 8); {@code NotificationEventListener.FARM_NO_COORDS} for the
 * Discord embeds without coordinates. A value must start with {@code 2.6p}. Any thread.
 */
public final class BuildIds {
    private BuildIds() {
    }

    public static final String BRIDGE_CLASS = "com.aquarius.feature.bridge.BridgeProtocol";
    public static final String BRIDGE_FIELD = "FARM_HARDENING";
    public static final String DISCORD_CLASS = "com.aquarius.discord.NotificationEventListener";
    public static final String DISCORD_FIELD = "FARM_NO_COORDS";

    /** The field's value, or null when the class or the field is missing. */
    public static String read(String className, String field) {
        try {
            Field f = Class.forName(className).getField(field);
            Object v = f.get(null);
            return v instanceof String s ? s : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** The one Bridge build id this plugin accepts (patches 1 to 4 and 8; a full-string match: a later or different id is not trusted). */
    public static final String BRIDGE_EXPECTED = "2.6p-2";
    /** The one Discord build id this plugin accepts (patch 6). */
    public static final String DISCORD_EXPECTED = "2.6p-1";

    public static boolean bridgeOk(String value) {
        return BRIDGE_EXPECTED.equals(value);
    }

    public static boolean discordOk(String value) {
        return DISCORD_EXPECTED.equals(value);
    }

    public static boolean bridgeHardened() {
        return bridgeOk(read(BRIDGE_CLASS, BRIDGE_FIELD));
    }

    public static boolean discordStripped() {
        return discordOk(read(DISCORD_CLASS, DISCORD_FIELD));
    }
}
