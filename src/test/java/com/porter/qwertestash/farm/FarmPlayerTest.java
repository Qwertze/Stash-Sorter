package com.porter.qwertestash.farm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.porter.qwerte.core.farm.ClickCompiler.ClickTarget;
import com.porter.qwerte.core.farm.FarmRecording;
import com.porter.qwerte.core.farm.ProgramCompiler;
import com.porter.qwerte.core.farm.ProgramCompiler.RawAnchor;
import com.porter.qwerte.core.farm.ProgramCompiler.RawRecording;
import com.porter.qwerte.core.farm.ProgramCompiler.RawTick;
import com.porter.qwerte.core.farm.FarmRecording.Vec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Playback and drift in the sim (FARM D3, D3b, D5, D7): the player against a tiny world, with wall-clock timing. */
class FarmPlayerTest {
    private static final long T0 = 1_000_000L;

    private static FarmPlayer started(FarmRecording rec, Sim sim) {
        FarmPlayer p = Sim.player(rec, sim);
        p.start(T0);
        return p;
    }

    private static long toLoop(FarmPlayer p, Sim sim, long now) {
        return Sim.run(p, sim, now, 50, 40, () -> p.phase() == FarmPlayer.Phase.LOOP || p.endedCode() != null);
    }

    @Test
    void verifyRefusesTheWrongDimensionTheWrongPlaceAndUnloadedChunks() {
        Sim s = new Sim();
        s.dim = "minecraft:the_nether";
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        p.tick(T0);
        assertEquals("wrong_dim", p.endedCode());

        Sim far = new Sim();
        far.x += 200;
        FarmPlayer q = started(Sim.walkLoop("custom", Sim.loops(0), null), far);
        q.tick(T0);
        assertEquals("not_at_farm", q.endedCode());

        Sim cold = new Sim();
        cold.chunks = false;
        FarmPlayer r = started(Sim.walkLoop("custom", Sim.loops(0), null), cold);
        r.tick(T0);
        r.tick(T0 + 59_000);
        assertNull(r.endedCode());
        r.tick(T0 + 61_000);
        assertEquals("chunk_not_loaded", r.endedCode());
    }

    @Test
    void anAnchorWithTheWrongFingerprintEndsFarmChanged() {
        Sim s = new Sim();
        s.below = "minecraft:dirt";
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        Sim.run(p, s, T0, 50, 40, () -> p.endedCode() != null);
        assertEquals("farm_changed", p.endedCode());
    }

    @Test
    void aLoopRunsWithoutDriftWhenTheBotTicksAtTwentyHertz() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = toLoop(p, s, T0);
        // three full passes: 3 x 80 ticks
        now = Sim.run(p, s, now, 50, 3 * 80 + 10, () -> p.endedCode() != null);
        assertNull(p.endedCode());
        assertTrue(p.loops() >= 3, "loops " + p.loops());
        assertEquals(0, p.recoveries());
        // still on the loop: z between the ends
        assertTrue(s.z >= Sim.OZ + 0.4 && s.z <= Sim.OZ + 5.0);
    }

    @Test
    void maxLoopsEndsTimeUpAfterTheStopSteps() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(2), null), s);
        long now = toLoop(p, s, T0);
        Sim.run(p, s, now, 50, 2 * 80 + 40, () -> p.endedCode() != null);
        assertEquals("time_up", p.endedCode());
        assertEquals(2, p.loops());
    }

    @Test
    void aBotPushedOffThePathRecoversAtTheNextAnchorAndKeepsLooping() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = toLoop(p, s, T0);
        now = Sim.run(p, s, now, 50, 10, () -> false); // somewhere in the first walk
        s.x += 3.0; // pushed 3 blocks sideways
        now = Sim.run(p, s, now, 50, 120, () -> p.recoveries() >= 1 || p.endedCode() != null);
        assertNull(p.endedCode());
        assertEquals(1, p.recoveries());
        // it is back on the loop and goes on without a further recovery in the next pass
        now = Sim.run(p, s, now, 50, 200, () -> p.phase() == FarmPlayer.Phase.LOOP && s.pathsStarted > 0 && Math.abs(s.x - (Sim.OX + 0.5)) < 0.35);
        assertTrue(Math.abs(s.x - (Sim.OX + 0.5)) < 0.35, "back on the line, x off by " + (s.x - Sim.OX - 0.5));
        assertEquals(FarmPlayer.Phase.LOOP, p.phase());
    }

    @Test
    void threeFailedRecoveriesEndDrifted() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = toLoop(p, s, T0);
        Sim.run(p, s, now, 50, 5, () -> false);
        s.pathFails = true;
        s.x += 5.0;
        Sim.run(p, s, T0 + 10_000, 50, 400, () -> p.endedCode() != null);
        assertEquals("drifted", p.endedCode());
        assertEquals(3, p.failedRecoveries());
    }

    @Test
    void moreThanSixRecoveriesInAnHourEndDrifted() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = toLoop(p, s, T0);
        for (int i = 0; i < 12 && p.endedCode() == null; i++) {
            now = Sim.run(p, s, now, 50, 6, () -> false);
            s.x += 3.0;
            now = Sim.run(p, s, now, 50, 600, () -> p.endedCode() != null || p.recoveries() > 0 && p.phase() == FarmPlayer.Phase.LOOP && Math.abs(s.x - Sim.OX - 0.5) < 0.2);
        }
        assertEquals("drifted", p.endedCode());
        assertTrue(p.recoveries() >= 6);
    }

    @Test
    void aSlowBotTickDoesNotLagTheRecordingBecauseMovementAdvancesPerBotTick() {
        // 62 ms bot ticks (spike 2.1a): movement advances one track tick per bot tick, so the distance covered matches the recording
        // and the session stays in the loop over several passes with no recovery and no drifted end (review item 5)
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = Sim.run(p, s, T0, 62, 60, () -> p.phase() == FarmPlayer.Phase.LOOP || p.endedCode() != null);
        Sim.run(p, s, now, 62, 5 * 80 + 20, () -> p.endedCode() != null);
        assertNull(p.endedCode());
        assertEquals(FarmPlayer.Phase.LOOP, p.phase());
        assertEquals(0, p.recoveries());
        assertTrue(p.loops() >= 4, "loops " + p.loops());
    }

    @Test
    void everyOneTickTapIsAppliedWhateverTheTickRate() {
        // a single forward tap at tick 10 (and one jump at tick 30): both reach the bot as exactly one submitted tick of that key
        FarmRecording rec = Sim.walkLoop("custom", Sim.loops(0), i -> {
            if (i == 15) return new RawTick(FarmRecording.KEY_JUMP, 0, 0, 0, 0, false, 0, "minecraft:bone_meal", ClickTarget.MISS, new Vec(0.5, 0.0, 0.5 + Sim.WALK), List.of(), false);
            if (i < 20) return new RawTick(i == 10 ? FarmRecording.KEY_FORWARD : 0, 0, 0, 0, 0, false, 0, "minecraft:bone_meal", ClickTarget.MISS, new Vec(0.5, 0.0, 0.5), List.of(), false);
            if (i >= 20 && i < 80) return new RawTick(0, 0, 0, 0, 0, false, 0, "minecraft:bone_meal", ClickTarget.MISS, new Vec(0.5, 0.0, 0.5 + Sim.WALK), List.of(), false);
            return null;
        });
        for (long dt : new long[] {50, 62, 90}) {
            Sim s = new Sim();
            FarmPlayer p = started(rec, s);
            long now = toLoop(p, s, T0);
            int from = s.sent.size();
            Sim.run(p, s, now, dt, 19, () -> p.endedCode() != null);
            int forward = 0, jump = 0;
            for (var c : s.sent.subList(from, s.sent.size())) {
                if ((c.keys() & FarmRecording.KEY_FORWARD) != 0) forward++;
                if ((c.keys() & FarmRecording.KEY_JUMP) != 0) jump++;
            }
            assertEquals(1, forward, "the one-tick forward tap, dt=" + dt);
            assertEquals(1, jump, "the one-tick jump, dt=" + dt);
        }
    }

    @Test
    void parkStopsInputsAtOnceAndResumeVerifiesThenWalksBackToTheLastAnchorFirst() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = toLoop(p, s, T0);
        now = Sim.run(p, s, now, 50, 30, () -> false); // past a1
        p.park("controller_connected", now);
        int before = s.sent.size();
        p.tick(now);
        assertEquals(before, s.sent.size(), "a parked player submits nothing");
        s.z += 2.5; // the world moved on while parked
        now += 600_000;
        p.resume(now, false);
        assertEquals(FarmPlayer.Phase.SETUP, p.phase(), "a verify state first, as setup has");
        Sim.run(p, s, now, 50, 300, () -> p.phase() == FarmPlayer.Phase.LOOP);
        assertEquals(FarmPlayer.Phase.LOOP, p.phase());
        assertEquals("a1", p.anchorId());
        assertEquals(0, p.recoveries(), "a resume is not a drift recovery");
    }

    @Test
    void resumeEndsOnAWrongDimensionOrADistanceOnlyWhenItPersistsAndNotOnABlankOne() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = toLoop(p, s, T0);
        p.park("disconnected", now);
        s.dim = ""; // right after a reconnect the dimension can read blank
        p.resume(now + 1000, true);
        for (int i = 0; i < 100; i++) {
            p.tick(now + 1000 + i * 50L);
        }
        assertNull(p.endedCode(), "a blank dimension is not a verdict");
        s.dim = "minecraft:the_end";
        long t = now + 7000;
        p.tick(t);
        p.tick(t + FarmPlayer.VERIFY_GRACE_MS - 1);
        assertNull(p.endedCode());
        p.tick(t + FarmPlayer.VERIFY_GRACE_MS + 1);
        assertEquals("wrong_dim", p.endedCode());

        Sim far = new Sim();
        FarmPlayer q = started(Sim.walkLoop("custom", Sim.loops(0), null), far);
        long n2 = toLoop(q, far, T0);
        q.park("disconnected", n2);
        far.x += 500;
        q.resume(n2 + 1000, true);
        q.tick(n2 + 1000);
        assertNull(q.endedCode(), "a stale or transient position is given the grace period");
        q.tick(n2 + 1000 + FarmPlayer.VERIFY_GRACE_MS + 1);
        assertEquals("not_at_farm", q.endedCode());
        // a transient wrong position that clears does not end it
        Sim ok = new Sim();
        FarmPlayer r = started(Sim.walkLoop("custom", Sim.loops(0), null), ok);
        long n3 = toLoop(r, ok, T0);
        r.park("disconnected", n3);
        ok.x += 500;
        r.resume(n3 + 1000, true);
        r.tick(n3 + 1000);
        ok.x -= 500;
        Sim.run(r, ok, n3 + 2000, 50, 200, () -> r.phase() == FarmPlayer.Phase.LOOP);
        assertEquals(FarmPlayer.Phase.LOOP, r.phase());
    }

    @Test
    void aSessionParkedInTheStopStepsResumesInTheStopSteps() {
        Map<String, Object> look = Map.of("op", "look", "yaw", 10.0, "pitch", 0.0);
        List<RawAnchor> anchors = List.of(new RawAnchor("a0", new Vec(0.5, 0.0, 0.5), 0.0, 0.0, 0.3, null, null, 0));
        List<RawTick> t = new ArrayList<>();
        for (int i = 0; i < 20; i++) t.add(new RawTick(0, 0, 0, 0, 0, false, 0, null, ClickTarget.MISS, new Vec(0.5, 0.0, 0.5), List.of(), false));
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("repeat", true);
        params.put("maxLoops", 1L);
        FarmRecording rec = ProgramCompiler.compile(new RawRecording("1.21.11", "1.21.4", "custom", "minecraft:overworld", params, 0, 20, true, anchors,
            List.of(), List.of(), List.of(), List.of(Map.of("op", "wait", "ticks", 200L), look), t), false).program();
        Sim s = new Sim();
        FarmPlayer p = started(rec, s);
        long now = Sim.run(p, s, T0, 50, 200, () -> p.phase() == FarmPlayer.Phase.STOP_STEPS);
        assertEquals(FarmPlayer.Phase.STOP_STEPS, p.phase());
        p.park("controller_connected", now);
        p.resume(now + 1000, false);
        Sim.run(p, s, now + 1000, 50, 50, () -> p.phase() == FarmPlayer.Phase.STOP_STEPS && false);
        assertTrue(p.phase() == FarmPlayer.Phase.STOP_STEPS || p.endedCode() != null, "it goes back to the stop steps, not to the loop: " + p.phase());
        assertTrue(p.phase() != FarmPlayer.Phase.LOOP);
    }

    @Test
    void aPlayerNearPausesARecoveryToo() {
        Sim s = new Sim();
        s.x += 6; // off the spot: the initial recovery has to walk
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = Sim.run(p, s, T0, 50, 3, () -> false);
        now = Sim.run(p, s, now, 50, 6, () -> p.phase() == FarmPlayer.Phase.RECOVER);
        assertEquals(FarmPlayer.Phase.RECOVER, p.phase());
        p.pauseInputs(now);
        assertTrue(p.inputsPaused(), "a recovery pauses when somebody is within 8 blocks");
        int paths = s.pathsStarted;
        double x = s.x;
        now = Sim.run(p, s, now, 50, 20, () -> false);
        assertEquals(x, s.x, 0.6, "the walk stands still while paused");
        p.resumeInputs(now);
        Sim.run(p, s, now, 50, 300, () -> p.phase() == FarmPlayer.Phase.LOOP);
        assertEquals(FarmPlayer.Phase.LOOP, p.phase());
        assertTrue(s.pathsStarted >= paths, "and goes on afterwards");
    }

    @Test
    void aClickWaitsForTheHeldSlotAndAMissingItemEndsItemMissingAfterTheLimit() {
        FarmRecording rec = Sim.walkLoop("custom", Sim.loops(0), i -> {
            if (i == 5) return new RawTick(0, 0, 0, 1, 0, false, 1, "minecraft:diamond_sword", ClickTarget.entity("minecraft:zombie"), new Vec(0.5, 0.0, 0.5), List.of(), false);
            if (i == 6) return new RawTick(0, 0, 0, 0, 0, false, 1, "minecraft:diamond_sword", ClickTarget.MISS, new Vec(0.5, 0.0, 0.5), List.of(), false);
            return null;
        });
        Sim s = new Sim();
        s.holdOk = false;
        FarmPlayer p = started(rec, s);
        Sim.run(p, s, T0, 50, 200, () -> p.endedCode() != null);
        assertEquals("item_missing", p.endedCode(), "the slot never became the chosen one");
        assertEquals(0, Sim.counts(s.sent).getOrDefault("left", 0), "no click went out before the slot was confirmed");
        // the slot confirms late: the click goes out then, once
        Sim s2 = new Sim();
        s2.holdOk = false;
        FarmPlayer q = started(rec, s2);
        long now = toLoop(q, s2, T0);
        now = Sim.run(q, s2, now, 50, 15, () -> false);
        s2.holdOk = true;
        Sim.run(q, s2, now, 50, 10, () -> false);
        assertEquals(1, Sim.counts(s2.sent).getOrDefault("left", 0));
    }

    @Test
    void clicksCarryTheirOwnTickAndAreSpacedByWallClock() {
        // an attack press at tick 10 and at tick 11 (entity target: one left click each press); 20 Hz bot ticks keep the recorded 50 ms gap
        FarmRecording rec = Sim.walkLoop("custom", Sim.loops(0), i -> {
            if (i == 10 || i == 11) return new RawTick(0, 33.0, 5.0, 1, 0, false, 0, "minecraft:bone_meal", ClickTarget.entity("minecraft:zombie"), new Vec(0.5, 0.0, 0.5), List.of(), false);
            if (i == 12) return new RawTick(0, 33.0, 5.0, 0, 0, false, 0, "minecraft:bone_meal", ClickTarget.MISS, new Vec(0.5, 0.0, 0.5), List.of(), false);
            return null;
        });
        Sim s = new Sim();
        FarmPlayer p = started(rec, s);
        long now = toLoop(p, s, T0);
        int from = s.sent.size();
        Sim.run(p, s, now, 50, 15, () -> false);
        int lefts = 0;
        for (var c : s.sent.subList(from, s.sent.size())) {
            if (c.left()) {
                lefts++;
                assertEquals(33.0f, c.yaw(), 1e-3, "the click carries its own tick's yaw");
                assertEquals(2, c.aim().kind());
                assertEquals("minecraft:zombie", c.aim().entity());
            }
        }
        assertEquals(2, lefts, "both clicks are issued, on different bot ticks, never merged");
        // faster bot ticks (10 ms): the gap between the two clicks is still at least 50 ms of wall clock (the bot holds the second one)
        Sim f = new Sim();
        FarmPlayer q = started(rec, f);
        long n2 = Sim.run(q, f, T0, 10, 200, () -> q.phase() == FarmPlayer.Phase.LOOP);
        long[] at = new long[2];
        int seen = 0;
        for (int i = 0; i < 400 && seen < 2; i++) {
            int before = f.sent.size();
            q.tick(n2);
            f.physics();
            for (var c : f.sent.subList(before, f.sent.size())) if (c.left() && seen < 2) at[seen++] = n2;
            n2 += 10;
        }
        assertEquals(2, seen);
        assertTrue(at[1] - at[0] >= 50, "gap " + (at[1] - at[0]));
    }

    @Test
    void pausingInputsHoldsTheTrack() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = toLoop(p, s, T0);
        now = Sim.run(p, s, now, 50, 5, () -> false);
        int tickBefore = p.trackTick();
        p.pauseInputs(now);
        now = Sim.run(p, s, now, 50, 100, () -> false); // 5 seconds
        assertEquals(tickBefore, p.trackTick(), "the track stands still while inputs are paused");
        assertTrue(p.inputsPaused());
        p.resumeInputs(now);
        Sim.run(p, s, now, 50, 3, () -> false);
        assertTrue(p.trackTick() <= tickBefore + 3, "and does not jump ahead afterwards: " + p.trackTick() + " vs " + tickBefore);
    }

    @Test
    void afkStillStaysAndRecoversWhenPushed() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.still(), s);
        long now = toLoop(p, s, T0);
        now = Sim.run(p, s, now, 50, 100, () -> false);
        assertEquals(FarmPlayer.Phase.LOOP, p.phase());
        assertEquals(0, p.recoveries());
        s.x += 4;
        Sim.run(p, s, now, 50, 200, () -> p.recoveries() >= 1);
        assertEquals(1, p.recoveries());
        Sim.run(p, s, now + 20_000, 50, 100, () -> p.phase() == FarmPlayer.Phase.LOOP);
        assertTrue(s.distanceToStand(0.5, 0, 0.5) < 0.6);
    }

    @Test
    void anotherPathfinderProcessParksTheSessionPathfinderBusy() {
        Sim s = new Sim();
        s.x += 4;
        s.busy = true;
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        Sim.run(p, s, T0, 50, 40, () -> p.phase() == FarmPlayer.Phase.PARKED || p.endedCode() != null);
        assertEquals(FarmPlayer.Phase.PARKED, p.phase());
        assertEquals("pathfinder_busy", p.parkedCode());
    }

    @Test
    void threeClickMissesInARowStartARecovery() {
        Sim s = new Sim();
        FarmPlayer p = started(Sim.walkLoop("custom", Sim.loops(0), null), s);
        long now = toLoop(p, s, T0);
        now = Sim.run(p, s, now, 50, 3, () -> false);
        p.clickResult(false, now);
        p.clickResult(false, now);
        assertEquals(FarmPlayer.Phase.LOOP, p.phase());
        p.clickResult(false, now);
        assertEquals(FarmPlayer.Phase.RECOVER, p.phase());
        // a hit resets the count
        Sim s2 = new Sim();
        FarmPlayer q = started(Sim.walkLoop("custom", Sim.loops(0), null), s2);
        long n2 = toLoop(q, s2, T0);
        q.clickResult(false, n2);
        q.clickResult(false, n2);
        q.clickResult(true, n2);
        q.clickResult(false, n2);
        assertEquals(FarmPlayer.Phase.LOOP, q.phase());
    }

    @Test
    void aMissingHotbarItemEndsItemMissing() {
        Sim s = new Sim();
        s.hasItems = false;
        FarmRecording rec = Sim.walkLoop("custom", Sim.loops(0), i -> i == 5
            ? new RawTick(0, 0, 0, 0, 0, false, 1, "minecraft:diamond_hoe", ClickTarget.MISS, new Vec(0.5, 0.0, 0.5), List.of(), false) : null);
        FarmPlayer p = started(rec, s);
        Sim.run(p, s, T0, 50, 60, () -> p.endedCode() != null);
        assertEquals("item_missing", p.endedCode());
    }

    @Test
    void programsWithContainerStepsAreNotReadyInM2() {
        Map<String, Object> take = Map.of("op", "wait-until", "what", "inventory_full");
        List<RawAnchor> anchors = List.of(new RawAnchor("a0", new Vec(0.5, 0.0, 0.5), 0.0, 0.0, 0.3, "minecraft:stone", "minecraft:air", 0));
        List<RawTick> t = new ArrayList<>();
        for (int i = 0; i < 20; i++) t.add(new RawTick(0, 0, 0, 0, 0, false, 0, null, ClickTarget.MISS, new Vec(0.5, 0.0, 0.5), List.of(), false));
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("repeat", true);
        params.put("maxLoops", 0L);
        FarmRecording rec = ProgramCompiler.compile(new RawRecording("1.21.11", "1.21.4", "custom", "minecraft:overworld", params, 0, 20, true, anchors,
            List.of(), List.of(), List.of(take), List.of(), t), false).program();
        Sim s = new Sim();
        FarmPlayer p = started(rec, s);
        assertEquals("mode_not_ready", p.endedCode());
    }

    // ---- review round: click spacing after a wrap or a recovery (N1), parking from a recovery (N4) ----------------------------

    private static FarmRecording clickAtFive() {
        // in the still stretch (ticks 20 to 39), so the click's own yaw moves nobody
        return Sim.walkLoop("custom", Sim.loops(0), i -> {
            if (i == 25) return new RawTick(0, 0.0, 5.0, 1, 0, false, 0, "minecraft:bone_meal", ClickTarget.entity("minecraft:zombie"), new Vec(0.5, 0.0, 4.5), List.of(), false);
            if (i == 26) return new RawTick(0, 0.0, 5.0, 0, 0, false, 0, "minecraft:bone_meal", ClickTarget.MISS, new Vec(0.5, 0.0, 4.5), List.of(), false);
            return null;
        });
    }

    private static List<Integer> leftClickIndexes(Sim s) {
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < s.sent.size(); i++) if (s.sent.get(i).left()) at.add(i);
        return at;
    }

    @Test
    void theFirstClickOfASecondPassIsNotHeldBackByTheClickOfThePassBefore() {
        Sim s = new Sim();
        FarmPlayer p = started(clickAtFive(), s);
        long now = toLoop(p, s, T0);
        Sim.run(p, s, now, 50, 200, () -> p.endedCode() != null);
        List<Integer> at = leftClickIndexes(s);
        assertTrue(at.size() >= 2, "clicks " + at);
        assertEquals(80, at.get(1) - at.get(0), "exactly one pass (80 bot ticks) apart: no stall of the wrap, clicks at " + at + ", recoveries " + p.recoveries());
    }

    @Test
    void theFirstClickAfterARecoveryIsNotHeldBack() {
        Sim s = new Sim();
        FarmPlayer p = started(clickAtFive(), s);
        long now = toLoop(p, s, T0);
        now = Sim.run(p, s, now, 50, 35, () -> false); // the click at tick 25 went out
        assertEquals(1, leftClickIndexes(s).size());
        p.park("controller_connected", now);
        now += 5_000;
        p.resume(now, false);
        now = Sim.run(p, s, now, 50, 300, () -> p.phase() == FarmPlayer.Phase.LOOP);
        assertEquals(FarmPlayer.Phase.LOOP, p.phase());
        int mark = s.sent.size();
        Sim.run(p, s, now, 50, 30, () -> false);
        int next = -1;
        for (int i : leftClickIndexes(s)) if (i >= mark) {
            next = i;
            break;
        }
        assertTrue(next >= 0 && next - mark <= 12, "the click at tick 25 follows the recovery within a few bot ticks, not after a stall: " + (next - mark));
    }

    private static FarmRecording withSteps(List<Map<String, Object>> start, List<Map<String, Object>> stop, long maxLoops) {
        List<RawAnchor> anchors = List.of(
            new RawAnchor("a0", new Vec(0.5, 0.0, 0.5), 0.0, 0.0, 0.3, null, null, 0),
            new RawAnchor("a1", new Vec(0.5, 0.0, 4.5), 0.0, 0.0, 0.3, null, null, 10));
        List<RawTick> t = new ArrayList<>();
        for (int i = 0; i < 20; i++) t.add(new RawTick(0, 0, 0, 0, 0, false, 0, null, ClickTarget.MISS, new Vec(0.5, 0.0, i < 10 ? 0.5 : 4.5), List.of(), false));
        Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("repeat", true);
        params.put("maxLoops", maxLoops);
        return ProgramCompiler.compile(new RawRecording("1.21.11", "1.21.4", "custom", "minecraft:overworld", params, 0, 20, true, anchors,
            List.of(), List.of(), start, stop, t), false).program();
    }

    @Test
    void aParkDuringAStopStepRecoveryResumesInTheStopStepsAndAParkAgainThereDoesToo() {
        Map<String, Object> walk = Map.of("op", "walk-to-anchor", "anchor", "a0");
        Map<String, Object> pause = Map.of("op", "wait", "ticks", 400L);
        FarmRecording rec = withSteps(List.of(), List.of(Map.of("op", "wait", "ticks", 2L), walk, pause), 1);
        Sim s = new Sim();
        s.below = "minecraft:stone";
        FarmPlayer p = started(rec, s);
        long now = Sim.run(p, s, T0, 50, 400, () -> p.phase() == FarmPlayer.Phase.STOP_STEPS);
        assertEquals(FarmPlayer.Phase.STOP_STEPS, p.phase());
        s.x += 5; // away from the anchor: the walk step needs a path
        now = Sim.run(p, s, now, 50, 100, () -> p.phase() == FarmPlayer.Phase.RECOVER);
        assertEquals(FarmPlayer.Phase.RECOVER, p.phase());
        p.park("pathfinder_busy", now);
        assertEquals(FarmPlayer.Phase.PARKED, p.phase());
        p.resume(now + 1000, false);
        now = Sim.run(p, s, now + 1000, 50, 300, () -> p.phase() == FarmPlayer.Phase.STOP_STEPS || p.phase() == FarmPlayer.Phase.LOOP);
        assertEquals(FarmPlayer.Phase.STOP_STEPS, p.phase(), "back in the stop steps, not the loop");
        assertNull(p.endedCode());
        int loops = p.loops();
        p.park("controller_connected", now);
        p.resume(now + 1000, false);
        now = Sim.run(p, s, now + 1000, 50, 300, () -> p.phase() == FarmPlayer.Phase.STOP_STEPS || p.phase() == FarmPlayer.Phase.LOOP);
        assertEquals(FarmPlayer.Phase.STOP_STEPS, p.phase());
        assertNull(p.endedCode());
        assertEquals(loops, p.loops(), "the loop did not start again");
        Sim.run(p, s, now, 50, 20, () -> false);
        assertEquals(FarmPlayer.Phase.STOP_STEPS, p.phase());
    }

    @Test
    void aParkDuringTheSetupWalkToAnchorStartsTheSetupOverAndThenApproachesTheStartAnchor() {
        FarmRecording rec = withSteps(List.of(Map.of("op", "walk-to-anchor", "anchor", "a1")), List.of(), 0);
        Sim s = new Sim();
        FarmPlayer p = started(rec, s);
        long now = Sim.run(p, s, T0, 50, 100, () -> p.phase() == FarmPlayer.Phase.RECOVER);
        assertEquals(FarmPlayer.Phase.RECOVER, p.phase());
        now = Sim.run(p, s, now, 50, 2, () -> false);
        p.park("pathfinder_busy", now);
        int paths = s.goals.size();
        assertEquals(1, paths);
        p.resume(now + 1000, false);
        assertEquals(FarmPlayer.Phase.SETUP, p.phase());
        Sim.run(p, s, now + 1000, 50, 600, () -> p.phase() == FarmPlayer.Phase.LOOP);
        assertEquals(FarmPlayer.Phase.LOOP, p.phase());
        assertNull(p.endedCode());
        assertEquals(OZ_STAND_A1, s.goals.get(paths)[2], "the setup ran again: the walk to a1 first");
        assertEquals(Sim.OZ, s.goals.get(paths + 1)[2], "then the approach to the start anchor a0");
    }

    private static final int OZ_STAND_A1 = Sim.OZ + 4;
}
