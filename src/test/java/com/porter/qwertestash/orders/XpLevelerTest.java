package com.porter.qwertestash.orders;

import com.porter.qwerte.core.enchant.EnchantRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** XpLeveler: single bottles near the target, batches far from it, and the reasons it stops. */
class XpLevelerTest {
    /** A bot with an experience bar: each thrown bottle gives these points. */
    private static final class Bot implements XpLeveler.Port {
        double points;
        int stock, hand;
        int[] gives = {7};
        int thrown, near, orbs, busy, refused;
        boolean abort, playerNear;
        /** Throws that give nothing (the orbs go elsewhere). */
        boolean dry;
        int maxBatch;
        int batch;
        final java.util.List<Integer> batches = new java.util.ArrayList<>();

        @Override
        public boolean busy() {
            return busy-- > 0;
        }

        @Override
        public double points() {
            return points;
        }

        @Override
        public int bottles() {
            return stock + hand;
        }

        @Override
        public boolean bottleReady() {
            return hand > 0;
        }

        @Override
        public boolean refill() {
            if (hand > 0) return true;
            int n = Math.min(64, stock);
            stock -= n;
            hand += n;
            return hand > 0;
        }

        @Override
        public boolean throwBottle() {
            if (refused-- > 0) return false;
            if (hand <= 0) return false;
            hand--;
            if (!dry) points += gives[thrown % gives.length];
            thrown++;
            return true;
        }

        @Override
        public boolean playerNear() {
            return playerNear;
        }

        @Override
        public int orbsNear() {
            return orbs > 0 ? orbs-- : 0;
        }

        @Override
        public boolean abort() {
            return abort;
        }
    }

    private static XpLeveler.Result run(XpLeveler l, int limit) {
        for (int i = 0; i < limit && !l.done(); i++) l.tick();
        return l.result();
    }

    @Test
    void reachesTheLevelWithSingleBottlesNearTheTarget() {
        Bot bot = new Bot();
        bot.stock = 200;
        bot.points = EnchantRules.points(14);
        XpLeveler l = new XpLeveler(bot, 16);
        assertEquals(XpLeveler.Result.Reached, run(l, 5000));
        assertTrue(XpLeveler.reached(bot.points, 16));
        // 7 points a bottle: the target needs (256+96) - (196+84) = 72 points, 11 bottles; never a handful more
        assertTrue(bot.thrown <= 12, "thrown " + bot.thrown);
        assertEquals(bot.thrown, l.thrown());
    }

    @Test
    void doesNotThrowWhenTheLevelIsAlreadyThere() {
        Bot bot = new Bot();
        bot.stock = 10;
        bot.points = EnchantRules.points(20);
        XpLeveler l = new XpLeveler(bot, 20);
        assertEquals(XpLeveler.Result.Reached, run(l, 10));
        assertEquals(0, bot.thrown);
    }

    @Test
    void aBatchIsNeverAllOfWhatIsNeeded() {
        // far from the target: at most 70 percent of the estimated bottles, at most 8
        assertEquals(8, XpLeveler.batchSize(0, 30));
        assertEquals(1, XpLeveler.batchSize(EnchantRules.points(30) - 7 * 3, 30));
        assertEquals(1, XpLeveler.batchSize(EnchantRules.points(30) - 1, 30));
        int b = XpLeveler.batchSize(EnchantRules.points(10), 12);
        assertTrue(b >= 1 && b <= XpLeveler.bottlesLeft(EnchantRules.points(10), 12), "batch " + b);
        assertTrue(XpLeveler.bottlesLeft(0, 5) > 0);
        assertEquals(0, XpLeveler.bottlesLeft(EnchantRules.points(5), 5));
    }

    @Test
    void anOvershootingBottleStopsTheThrowing() {
        Bot bot = new Bot();
        bot.stock = 200;
        bot.gives = new int[] {11};
        bot.points = EnchantRules.points(9);
        XpLeveler l = new XpLeveler(bot, 10);
        assertEquals(XpLeveler.Result.Reached, run(l, 5000));
        // the level at the end is at least the target and the bot did not keep throwing
        assertTrue(bot.points >= EnchantRules.points(10));
        assertTrue(bot.points < EnchantRules.points(10) + 22);
    }

    @Test
    void noBottlesLeftEndsWithNoBottles() {
        Bot bot = new Bot();
        bot.stock = 5;
        XpLeveler l = new XpLeveler(bot, 30);
        assertEquals(XpLeveler.Result.NoBottles, run(l, 5000));
        assertEquals(5, bot.thrown);
        assertEquals(0, bot.bottles());
    }

    @Test
    void thrownBottlesOrbsAreLookedAtBeforeNoBottles() {
        // the last bottles reach the target once their orbs are counted: Reached, not NoBottles
        Bot bot = new Bot();
        bot.stock = 3;
        bot.points = EnchantRules.points(5) - 15;
        XpLeveler l = new XpLeveler(bot, 5);
        assertEquals(XpLeveler.Result.Reached, run(l, 5000));
    }

    @Test
    void aPlayerNearStopsItBetweenThrows() {
        Bot bot = new Bot();
        bot.stock = 400;
        XpLeveler l = new XpLeveler(bot, 30);
        for (int i = 0; i < 30; i++) l.tick();
        bot.playerNear = true;
        assertEquals(XpLeveler.Result.Player, run(l, 200));
        int thrown = bot.thrown;
        assertFalse(thrown == 0);
        // a new leveler carries on when the player is gone
        bot.playerNear = false;
        XpLeveler again = new XpLeveler(bot, 30);
        assertEquals(XpLeveler.Result.Reached, run(again, 20000));
    }

    @Test
    void bottlesThatGiveNothingEndDry() {
        Bot bot = new Bot();
        bot.stock = 200;
        bot.dry = true;
        XpLeveler l = new XpLeveler(bot, 12);
        assertEquals(XpLeveler.Result.Dry, run(l, 20000));
        assertTrue(bot.thrown >= 1 && bot.thrown <= 3 * XpLeveler.MAX_BATCH, "thrown " + bot.thrown);
    }

    @Test
    void aRefusedThrowIsAskedAgain() {
        Bot bot = new Bot();
        bot.stock = 100;
        bot.refused = 7;
        XpLeveler l = new XpLeveler(bot, 6);
        assertEquals(XpLeveler.Result.Reached, run(l, 5000));
    }

    @Test
    void anAbortStopsAtOnce() {
        Bot bot = new Bot();
        bot.stock = 100;
        XpLeveler l = new XpLeveler(bot, 30);
        l.tick();
        bot.abort = true;
        assertEquals(XpLeveler.Result.Aborted, l.tick());
    }

    @Test
    void theHandIsRefilledFromTheInventory() {
        Bot bot = new Bot();
        bot.stock = 150;
        bot.hand = 0;
        XpLeveler l = new XpLeveler(bot, 20);
        assertEquals(XpLeveler.Result.Reached, run(l, 20000));
        assertTrue(bot.thrown > 64, "more than one stack was used: " + bot.thrown);
    }

    @Test
    void aBusyPortHoldsItBack() {
        Bot bot = new Bot();
        bot.stock = 10;
        bot.busy = 50;
        XpLeveler l = new XpLeveler(bot, 3);
        for (int i = 0; i < 40; i++) l.tick();
        assertEquals(0, bot.thrown);
        assertEquals(XpLeveler.Result.Reached, run(l, 5000));
    }
}
