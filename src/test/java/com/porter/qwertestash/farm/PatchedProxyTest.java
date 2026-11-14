package com.porter.qwertestash.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * The 2.6p patches against a patched proxy jar (class level, no running proxy): the build ids, the Reader.readString bound and the
 * Bridge size cap constant. Runs only with {@code -PpatchedProxyJar=<path>} (the standard build has the unpatched jar and skips it).
 */
class PatchedProxyTest {
    private static URLClassLoader loader() throws Exception {
        String p = System.getProperty("patchedProxyJar");
        assumeTrue(p != null && new File(p).isFile(), "no patched proxy jar given");
        // child-first is not needed: the patched classes are loaded from the jar by a loader with no parent on the classpath
        return new URLClassLoader(new URL[] {new File(p).toURI().toURL()}, ClassLoader.getPlatformClassLoader());
    }

    @Test
    void theBuildIdsArePresentAndAccepted() throws Exception {
        try (URLClassLoader l = loader()) {
            String bridge = (String) Class.forName(BuildIds.BRIDGE_CLASS, true, l).getField(BuildIds.BRIDGE_FIELD).get(null);
            String discord = (String) Class.forName(BuildIds.DISCORD_CLASS, false, l).getField(BuildIds.DISCORD_FIELD).get(null);
            assertTrue(BuildIds.bridgeOk(bridge), bridge);
            assertTrue(BuildIds.discordOk(discord), discord);
        }
    }

    @Test
    void readStringRefusesALengthThatOverflowsPosPlusLen() throws Exception {
        try (URLClassLoader l = loader()) {
            Class<?> reader = Class.forName("com.aquarius.feature.bridge.BridgeProtocol$Reader", true, l);
            Constructor<?> c = reader.getConstructor(byte[].class);
            Method readString = reader.getMethod("readString");
            // VarInt 2^31-1 (FF FF FF FF 07) then nothing: pos + len overflows to a negative number in the unpatched code
            Object r = c.newInstance((Object) new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07});
            InvocationTargetException e = assertThrows(InvocationTargetException.class, () -> readString.invoke(r));
            assertEquals(IndexOutOfBoundsException.class, e.getCause().getClass());
            assertEquals("String past end of buffer", e.getCause().getMessage(), "the patched bound check, not a later StringIndexOutOfBounds");
        }
    }

    @Test
    void theBridgeSizeCapIs32KiBAndTheHelloCapIs64() throws Exception {
        try (URLClassLoader l = loader()) {
            assertEquals(32_768, Class.forName("com.aquarius.feature.bridge.ServerboundBridgePayloadHandler", false, l).getField("MAX_INBOUND_BYTES").get(null));
            assertEquals(64, Class.forName("com.aquarius.module.impl.Bridge", false, l).getField("MAX_HELLO_FEATURES").get(null));
        }
    }
}
