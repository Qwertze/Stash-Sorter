package com.porter.qwertestash.stash;

import com.porter.qwertestash.manager.ManagerIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The queue of areas, the edit that waits for the server, and what a heartbeat reply makes of an area (AREA-CONTRACT.md). */
class AreaSyncTest {
    private static final String OW = "minecraft:overworld";
    private static final int[] A = {100, 60, -40, 163, 90, 23}, B = {0, 60, 0, 10, 70, 10};

    @BeforeEach
    @AfterEach
    void clean() {
        AreaPending.reset();
    }

    private static ManagerIndex.AreaReply reply(String hash, int[] box) {
        String area = box == null ? "null" : "{\"box\":[" + box[0] + "," + box[1] + "," + box[2] + "," + box[3] + "," + box[4] + "," + box[5] + "],\"dimension\":\"minecraft:overworld\"}";
        return ManagerIndex.areaReply("{\"areaHash\":\"" + hash + "\",\"area\":" + area + "}");
    }

    @Test
    void theQueueHoldsOneRequestAndANewOneStartsAsBusy() {
        assertNull(AreaPending.get());
        AreaPending.queue(new AreaPending.Request(A, OW, true));
        AreaPending.setWhy("far");
        assertEquals("far", AreaPending.why());
        AreaPending.queue(new AreaPending.Request(null, OW, false));
        assertEquals("busy", AreaPending.why());
        assertNull(AreaPending.get().box());
        assertEquals(AreaRules.hashOf(null, null), AreaPending.get().hash());
    }

    @Test
    void clearingTheAppliedRequestKeepsOneQueuedMeanwhile() {
        AreaPending.Request applied = new AreaPending.Request(A, OW, true);
        AreaPending.queue(applied);
        // A command queues another while the tick is applying the first
        AreaPending.Request newer = new AreaPending.Request(B, OW, false);
        AreaPending.queue(newer);
        assertFalse(AreaPending.clear(applied), "it is not the one waiting any more");
        assertSame(newer, AreaPending.get());
        assertTrue(AreaPending.clear(newer));
        assertNull(AreaPending.get());
        assertFalse(AreaPending.clear(newer), "already gone");
    }

    @Test
    void onlyTheAckOfTheEditedHashEndsAnEditAndAnOlderOneDoesNot() {
        String first = AreaRules.hashOf(A, OW), second = AreaRules.hashOf(B, OW);
        AreaPending.markEdited(first);
        assertTrue(AreaPending.edited());
        // A second edit happens between sending and the reply; the reply of the first heartbeat arrives
        AreaPending.markEdited(second);
        assertFalse(AreaPending.acknowledge(first), "the old ack");
        assertTrue(AreaPending.edited(), "still edited");
        assertEquals(second, AreaPending.editedHash());
        assertFalse(AreaPending.acknowledge(null));
        assertFalse(AreaPending.acknowledge("0".repeat(40)));
        assertTrue(AreaPending.acknowledge(second));
        assertFalse(AreaPending.edited());
    }

    @Test
    void anOldReplyDoesNotRevertALocalEditThatIsNotAcknowledgedYet() {
        // The bot edited to B; the reply of an older heartbeat still carries the website's A and the old hash
        String edited = AreaRules.hashOf(B, OW);
        AreaPending.markEdited(edited);
        ManagerIndex.AreaReply stale = reply(AreaRules.hashOf(A, OW), A);
        assertFalse(AreaPending.acknowledge(stale.hash()));
        AreaSync.Action a = AreaSync.react(stale, edited, null, AreaPending.edited(), OW);
        assertNull(a.queue(), "the website area is ignored while the edit is in flight");
        assertNull(a.warn());
        assertTrue(AreaPending.edited());
        // Once the server has the edit, the website's area is looked at again
        assertTrue(AreaPending.acknowledge(edited));
        assertNull(AreaSync.react(reply(edited, B), edited, null, AreaPending.edited(), OW).queue(), "the same area as in use");
        assertNotNull(AreaSync.react(reply(edited, A), edited, null, AreaPending.edited(), OW).queue(), "a different one is queued");
    }

    @Test
    void aLocalRequestWaitingIgnoresTheServersArea() {
        AreaPending.Request local = new AreaPending.Request(B, OW, false);
        AreaSync.Action a = AreaSync.react(reply(AreaRules.hashOf(A, OW), A), AreaRules.hashOf(null, null), local, false, OW);
        assertNull(a.queue());
        assertNull(a.warn());
        // A website request waiting is replaced by a newer website area, but not queued twice
        AreaPending.Request site = new AreaPending.Request(A, OW, true);
        assertNull(AreaSync.react(reply(AreaRules.hashOf(A, OW), A), AreaRules.hashOf(null, null), site, false, OW).queue());
        assertNotNull(AreaSync.react(reply(AreaRules.hashOf(B, OW), B), AreaRules.hashOf(null, null), site, false, OW).queue());
    }

    @Test
    void theServersAreaIsCheckedBeforeItIsQueued() {
        String inUse = AreaRules.hashOf(null, null);
        // Wrong dimension, no known dimension, over the caps: refused with the reason
        assertNotNull(AreaSync.react(reply("a".repeat(40), A), inUse, null, false, "minecraft:the_nether").warn());
        assertNotNull(AreaSync.react(reply("a".repeat(40), A), inUse, null, false, null).warn());
        assertNotNull(AreaSync.react(reply("a".repeat(40), new int[] {0, 0, 0, 999, 10, 10}), inUse, null, false, OW).warn());
        // An invalid object is reported
        assertNotNull(AreaSync.react(ManagerIndex.areaReply("{\"area\":{\"box\":[1]}}"), inUse, null, false, OW).warn());
        // A cleared area on the website clears ours
        String set = AreaRules.hashOf(A, OW);
        AreaSync.Action clear = AreaSync.react(reply(AreaRules.hashOf(null, null), null), set, null, false, OW);
        assertNotNull(clear.queue());
        assertNull(clear.queue().box());
        assertTrue(clear.queue().website());
        // No area key: nothing to do
        assertNull(AreaSync.react(ManagerIndex.areaReply("{\"areaHash\":\"" + "a".repeat(40) + "\"}"), inUse, null, false, OW).queue());
    }

    @Test
    void aCommandsDimensionIsResolvedWhenItIsApplied() {
        assertEquals(OW, AreaSync.resolveDimension(OW, "minecraft:the_nether"), "the one it was queued with");
        assertEquals("minecraft:the_nether", AreaSync.resolveDimension(null, "minecraft:the_nether"));
        assertNull(AreaSync.resolveDimension(null, null), "not known yet: it waits");
    }
}
