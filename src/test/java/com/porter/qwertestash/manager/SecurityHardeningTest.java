package com.porter.qwertestash.manager;

import com.porter.qwertestash.module.StashManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityHardeningTest {
    @Test
    void plaintextRemoteManagerUrlsAreRejected() {
        assertNull(StashManager.parse("http://example.com:8765"));
        assertNull(StashManager.parse("http://192.168.1.2:8765"));
        assertNull(StashManager.parse("http://127.0.0.2:8765"));
        assertNotNull(StashManager.parse("http://127.0.0.1:8765"));
        assertNotNull(StashManager.parse("http://localhost:8765"));
        assertNotNull(StashManager.parse("http://[::1]:8765"));
        assertNotNull(StashManager.parse("https://example.com:8765"));
        assertNull(StashManager.parse("https://user:password@example.com/"));
    }

    @Test
    void privateChatNeverReachesViewerBuffer() {
        ViewerFeed.ChatRing ring = new ViewerFeed.ChatRing(() -> 123L);
        ring.add("whisper", "Alice", "Bob", "secret coords");
        ring.add("public", "Alice", null, "hello");
        assertEquals(1, ring.last(50).size());
        assertEquals("hello", ring.last(50).get(0).m());
    }

    @Test
    void arbitraryProxyStringsAreNotExported() {
        assertTrue(ProxyPolicy.PROXY.hideStrings());
        assertTrue(ProxyPolicy.OTHER_PLUGIN.hideStrings());
    }
}
