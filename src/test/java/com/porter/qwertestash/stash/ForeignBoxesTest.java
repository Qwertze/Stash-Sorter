package com.porter.qwertestash.stash;

import com.aquarius.mc.block.BlockPos;
import com.porter.qwertestash.stash.Stash.Box;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.12.1: a never-read container millions of blocks from the stash made every scan stall; a real one must never be lost. */
class ForeignBoxesTest {
    private static Box at(int x, int y, int z, boolean read) {
        BlockPos p = new BlockPos(x, y, z);
        Box box = new Box(p, new ArrayList<>(List.of(p)));
        if (read) {
            box.contents = new Item[27];
            box.readAt = 1;
        }
        return box;
    }

    private static Stash farm(int read) {
        Stash stash = new Stash("farm", "2b2t.org");
        for (int i = 0; i < read; i++) stash.boxes.add(at(703025 + i % 15, -40, -696463 - i / 15, true));
        return stash;
    }

    @Test
    void theCt113CaseIsCaught() {
        Stash stash = farm(47);
        Box foreign = at(2150763, 231, -2167116, false);
        stash.boxes.add(foreign);
        assertEquals(List.of(foreign), stash.foreignBoxes());
        assertTrue(stash.implausible(foreign));
        assertEquals(48, stash.boxes.size(), "foreignBoxes only says, the caller removes");
    }

    @Test
    void aReadBoxIsNeverDropped() {
        Stash stash = farm(47);
        stash.boxes.add(at(2150763, 231, -2167116, true));
        assertTrue(stash.foreignBoxes().isEmpty());
    }

    @Test
    void threeWideBoxesAreNotJudged() {
        Stash stash = new Stash("farm", "2b2t.org");
        stash.boxes.add(at(0, 64, 0, true));
        stash.boxes.add(at(5000, 64, 0, false));
        stash.boxes.add(at(2_000_000, 64, 0, false));
        assertTrue(stash.foreignBoxes().isEmpty(), "fewer than five read boxes say nothing");
    }

    @Test
    void twoClustersKeepBoth() {
        Stash stash = farm(10);
        for (int i = 0; i < 10; i++) stash.boxes.add(at(703025 + 15000 + i, -40, -696463, true));
        stash.boxes.add(at(703025 + 7500, -40, -696463, false));
        assertTrue(stash.foreignBoxes().isEmpty());
    }

    @Test
    void deliveryBoxesAreNeitherReferenceNorCandidate() {
        Stash stash = farm(4);
        for (int i = 0; i < 3; i++) {
            Box d = at(703025 + i, -41, -696463, true);
            d.delivery = true;
            stash.boxes.add(d);
        }
        Box farDelivery = at(2150763, 231, -2167116, false);
        farDelivery.delivery = true;
        stash.boxes.add(farDelivery);
        stash.boxes.add(at(2150000, 231, -2167116, false));
        assertTrue(stash.foreignBoxes().isEmpty(), "only 4 read non-delivery boxes: not enough to judge");
        stash.boxes.add(at(703040, -40, -696470, true));
        List<Box> gone = stash.foreignBoxes();
        assertEquals(1, gone.size());
        assertFalse(gone.contains(farDelivery));
    }

    @Test
    void aFarButLoadedLookingBoxIsNotImplausibleWithinTheLimit() {
        Stash stash = farm(10);
        assertFalse(stash.implausible(at(703025 + 500, -40, -696463, false)), "far in the stash, walked to");
    }

    private static Stash.Area small() {
        return new Stash.Area(new int[] {703000, -64, -696500}, new int[] {703100, 64, -696400}, null);
    }

    @Test
    void aSmallerPerimeterKeepsAndSkipsAReadBoxOutsideIt() {
        Stash stash = farm(6);
        Box readOutside = at(703500, -40, -696463, true);
        Box unreadOutside = at(703501, -40, -696463, false);
        Box deliveryOutside = at(703502, -40, -696463, true);
        deliveryOutside.delivery = true;
        stash.boxes.add(readOutside);
        stash.boxes.add(unreadOutside);
        stash.boxes.add(deliveryOutside);
        stash.area = small();
        assertEquals(1, stash.dropOutsideArea(), "only the never-read one goes");
        assertTrue(stash.boxes.contains(readOutside), "a read box stays in the file");
        assertFalse(stash.boxes.contains(unreadOutside));
        assertTrue(readOutside.excluded && readOutside.outsideSkip, "and is skipped like an excluded one");
        assertFalse(deliveryOutside.excluded, "the delivery container is left alone");
        stash.area = null;
        stash.skipOutsideArea();
        assertFalse(readOutside.excluded, "no perimeter: let back in");
        assertFalse(readOutside.outsideSkip);
    }

    @Test
    void aBoxTheUserExcludedStaysExcluded() {
        Stash stash = farm(6);
        Box mine = at(703500, -40, -696463, true);
        mine.excluded = true;
        stash.boxes.add(mine);
        stash.area = small();
        stash.skipOutsideArea();
        assertFalse(mine.outsideSkip);
        stash.area = null;
        stash.skipOutsideArea();
        assertTrue(mine.excluded, "not undone: the user's exclusion");
    }

    @Test
    void aSkippedBoxIsOutOfScopeEverywhere() {
        Stash stash = farm(6);
        Box outside = at(703500, -40, -696463, true);
        stash.boxes.add(outside);
        assertTrue(stash.inScope(outside));
        stash.area = small();
        stash.dropOutsideArea();
        assertFalse(stash.inScope(outside), "the index, the website and the status do not count it");
        assertTrue(stash.boxes.stream().filter(b -> b != outside).allMatch(stash::inScope));
    }

    @Test
    void aRescanDoesNotMakeASkippedBoxSurveyable() {
        Stash stash = farm(6);
        Box outside = at(703500, -40, -696463, true);
        stash.boxes.add(outside);
        stash.area = small();
        stash.dropOutsideArea();
        stash.markAllStale();
        assertFalse(outside.stale);
        assertFalse(outside.wantsRead());
        assertTrue(stash.boxes.stream().filter(b -> b != outside).allMatch(b -> b.stale && b.wantsRead()));
    }

    @Test
    void excludeAndDropoffOnASkippedBoxAreBlocked() {
        Stash stash = farm(6);
        Box outside = at(703500, -40, -696463, true);
        stash.boxes.add(outside);
        stash.area = small();
        stash.dropOutsideArea();
        assertTrue(outside.marksBlocked());
        assertFalse(stash.boxes.get(0).marksBlocked());
    }

    @Test
    void aUserExcludedNeverReadBoxOutsideTheAreaIsKept() {
        Stash stash = farm(6);
        Box mine = at(703500, -40, -696463, false);
        mine.excluded = true;
        stash.boxes.add(mine);
        stash.area = small();
        assertEquals(0, stash.dropOutsideArea());
        assertTrue(stash.boxes.contains(mine));
        assertTrue(stash.foreignBoxes().isEmpty());
        // And with no perimeter, far from every read one
        stash.area = null;
        Box far = at(2150763, 231, -2167116, false);
        far.excluded = true;
        stash.boxes.add(far);
        assertFalse(stash.foreignBoxes().contains(far));
    }
}
