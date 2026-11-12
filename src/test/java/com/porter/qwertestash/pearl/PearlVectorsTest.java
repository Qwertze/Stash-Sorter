package com.porter.qwertestash.pearl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared vectors of PEARL-CONTRACT v1.0 (version 3, the contract's own file, byte for byte in test resources {@code pearl/}): every case of the
 * traveller state machine step by step, the pearl count rule, and the loader report bodies (what the server accepts, {@link PearlReport#check} accepts;
 * what it refuses with 400, it refuses). The chamber state, arrival and identity vectors are rules of the server and are run by its own tests.
 */
class PearlVectorsTest {
    private static JsonObject vectors() throws Exception {
        try (InputStream in = PearlVectorsTest.class.getResourceAsStream("/pearl/pearl-vectors.json")) {
            assertNotNull(in, "the vectors file is in the test resources");
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void theFileIsVersionThree() throws Exception {
        assertEquals(3, vectors().get("version").getAsInt());
    }

    private static boolean flag(JsonObject o, String f, boolean dflt) {
        return o.has(f) ? o.get(f).getAsBoolean() : dflt;
    }

    private static PearlReply replyOf(JsonObject ev, String action) {
        JsonObject o = new JsonObject();
        o.addProperty("id", "t-000000000001");
        o.addProperty("action", action);
        o.addProperty("state", ev.has("state") ? ev.get("state").getAsString() : "running");
        o.addProperty("station", "ps-00000001");
        for (String f : new String[] {"go", "armHome", "retry", "rearm", "hold", "cancel"}) if (ev.has(f)) o.addProperty(f, ev.get(f).getAsBoolean());
        for (String f : new String[] {"code", "loader", "pearlId"}) if (ev.has(f)) o.addProperty(f, ev.get(f).getAsString());
        if (ev.has("pullDeadlineAt")) o.addProperty("pullDeadlineAt", ev.get("pullDeadlineAt").getAsLong());
        PearlReply r = PearlReply.parse(o);
        assertNotNull(r, "the reply is valid: " + o);
        return r;
    }

    @Test
    void everyMachineCaseRunsStepByStep() throws Exception {
        JsonArray cases = vectors().getAsJsonObject("machine").getAsJsonArray("cases");
        assertTrue(cases.size() == 22, "all the contract's cases are there");
        for (JsonElement ce : cases) {
            JsonObject c = ce.getAsJsonObject();
            String name = c.get("name").getAsString();
            String action = c.get("action").getAsString();
            long[] clock = {1_767_225_600_000L};
            PearlTrip t = new PearlTrip(() -> clock[0]);
            int n = 0;
            for (JsonElement se : c.getAsJsonArray("steps")) {
                n++;
                JsonObject ev = se.getAsJsonObject().getAsJsonObject("event");
                JsonObject expect = se.getAsJsonObject().getAsJsonObject("expect");
                String where = name + " / step " + n + " / " + ev;
                clock[0] += 1000;
                List<PearlTrip.Action> got = switch (ev.get("e").getAsString()) {
                    case "offer" -> t.offer(ev.get("action").getAsString());
                    case "precheck" -> t.precheck(ev.get("ok").getAsBoolean(), ev.has("code") ? ev.get("code").getAsString() : null);
                    case "retryTick" -> t.retryTick();
                    case "reply" -> t.reply(replyOf(ev, action), flag(ev, "stillHere", true));
                    case "armHomeDone" -> t.armHomeDone();
                    case "teleport" -> t.teleport(flag(ev, "jump", false), flag(ev, "dimChange", false), "minecraft:the_nether");
                    case "pullDeadline" -> t.pullDeadline(flag(ev, "stillHere", true));
                    case "watchEnd" -> t.watchEnd();
                    case "pickupDone" -> t.pickupDone();
                    case "rearmResult" -> t.rearmResult(ev.get("result").getAsString());
                    case "disconnect" -> t.disconnect();
                    case "reconnect" -> t.reconnect(flag(ev, "teleported", false));
                    default -> throw new AssertionError("unknown event in " + where);
                };
                assertEquals(expect.get("step").getAsString(), t.step(), "step: " + where);
                assertEquals(expect.get("state").getAsString(), t.state(), "state: " + where);
                assertEquals(expect.get("whispers").getAsInt(), t.whispers(), "whispers: " + where);
                assertEquals(expect.get("away").getAsBoolean(), t.away(), "away: " + where);
                assertEquals(expect.get("watch").getAsBoolean(), t.watch(), "watch: " + where);
                List<String> actions = new ArrayList<>();
                for (JsonElement a : expect.getAsJsonArray("actions")) actions.add(a.getAsString());
                assertEquals(actions, got.stream().map(PearlTrip.Action::toString).toList(), "actions: " + where);
                if (expect.has("tries")) assertEquals(expect.get("tries").getAsInt(), t.tries(), "tries: " + where);
                if (expect.has("code")) assertEquals(expect.get("code").getAsString(), t.code(), "code: " + where);
                if (expect.has("warning")) assertEquals(expect.get("warning").getAsString(), t.warning(), "warning: " + where);
            }
        }
    }

    /** The contract's rule: exp = before - (armedHome ? 1 : 0) + (drop ? 1 : 0) - 1; no warning at exp, or at exp - 1 only when the loader drops a pearl. */
    static boolean pearlCountWarns(int before, boolean drop, boolean armedHome, int after) {
        int exp = before - (armedHome ? 1 : 0) + (drop ? 1 : 0) - 1;
        return !(after == exp || (drop && after == exp - 1));
    }

    @Test
    void thePearlCountVectors() throws Exception {
        JsonArray cases = vectors().getAsJsonObject("reports").getAsJsonObject("pearlCount").getAsJsonArray("cases");
        assertEquals(12, cases.size());
        for (JsonElement ce : cases) {
            JsonObject c = ce.getAsJsonObject();
            assertEquals(c.get("warning").getAsBoolean(), pearlCountWarns(c.get("before").getAsInt(), c.get("dropAfterLoad").getAsBoolean(), c.get("armedHome").getAsBoolean(),
                c.get("after").getAsInt()), c.toString());
        }
    }

    @Test
    void theLoaderReportBodiesAreAcceptedOrRefusedAsTheServerDoes() throws Exception {
        JsonArray cases = vectors().getAsJsonObject("reports").getAsJsonObject("bodies").getAsJsonArray("cases");
        assertEquals(13, cases.size());
        for (JsonElement ce : cases) {
            JsonObject c = ce.getAsJsonObject();
            String why = PearlReport.check(c.getAsJsonObject("body"));
            assertEquals(c.get("ok").getAsBoolean(), why == null, c.get("name").getAsString() + ": " + why);
        }
    }
}
