package dev.minecraftharness.review;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReviewTests {
    ReviewLoop loop(ReviewLevel l) { return new ReviewLoop(l, ReviewLimits.defaults(l)); }

    @Test void offNeverRuns() {
        assertEquals(ReviewLoop.Stop.OFF, loop(ReviewLevel.OFF).beforeRound(0, 0, false));
    }

    @Test void roundLimitPerLevel() {
        int[] max = {1, 3, 5};
        ReviewLevel[] lv = {ReviewLevel.LIGHT, ReviewLevel.NORMAL, ReviewLevel.STRICT};
        for (int i = 0; i < lv.length; i++) {
            ReviewLoop l = loop(lv[i]);
            for (int r = 0; r < max[i]; r++) {
                assertEquals(ReviewLoop.Stop.NONE, l.beforeRound(0, 0, false), lv[i] + " round " + r);
                l.afterRound(new ReviewLoop.RoundResult(true, 5 - r, 4 - r, 1, false));
            }
            assertEquals(ReviewLoop.Stop.ROUNDS, l.beforeRound(0, 0, false), lv[i].id());
        }
    }

    @Test void maxIsBoundedByTimeAndTokens() {
        ReviewLoop l = loop(ReviewLevel.MAX);
        assertEquals(ReviewLoop.Stop.NONE, l.beforeRound(0, 0, false));
        assertEquals(ReviewLoop.Stop.TIME, l.beforeRound(3_600_000L, 0, false));
        assertEquals(ReviewLoop.Stop.TOKENS, l.beforeRound(0, 3_000_000L, false));
        assertEquals("-", l.maxLabel());
    }

    @Test void cancelStops() {
        assertEquals(ReviewLoop.Stop.STOPPED, loop(ReviewLevel.NORMAL).beforeRound(0, 0, true));
    }

    @Test void cleanWorseStuckFailed() {
        assertEquals(ReviewLoop.Stop.CLEAN, loop(ReviewLevel.NORMAL).afterRound(new ReviewLoop.RoundResult(true, 2, 0, 2, false)));
        assertEquals(ReviewLoop.Stop.WORSE, loop(ReviewLevel.NORMAL).afterRound(new ReviewLoop.RoundResult(true, 2, 1, 1, true)));
        assertEquals(ReviewLoop.Stop.FAILED, loop(ReviewLevel.NORMAL).afterRound(new ReviewLoop.RoundResult(false, 0, 0, 0, false)));
        ReviewLoop l = loop(ReviewLevel.STRICT);
        assertEquals(ReviewLoop.Stop.NONE, l.afterRound(new ReviewLoop.RoundResult(true, 3, 3, 0, false)));
        assertEquals(ReviewLoop.Stop.NONE, l.afterRound(new ReviewLoop.RoundResult(true, 3, 3, 0, false)));
        assertEquals(ReviewLoop.Stop.STUCK, l.afterRound(new ReviewLoop.RoundResult(true, 3, 3, 0, false)));
    }

    @Test void remainingGrowingIsWorse() {
        ReviewLoop l = loop(ReviewLevel.STRICT);
        l.afterRound(new ReviewLoop.RoundResult(true, 3, 2, 1, false));
        assertEquals(ReviewLoop.Stop.WORSE, l.afterRound(new ReviewLoop.RoundResult(true, 4, 4, 0, false)));
    }

    @Test void cancelDuringRoundIsStoppedNotFailedOrWorse() {
        ReviewLoop l = loop(ReviewLevel.NORMAL);
        var noReport = new ReviewLoop.RoundResult(false, 0, 0, 0, true);
        assertEquals(ReviewLoop.Stop.STOPPED, l.afterRound(noReport, true));
        assertEquals(1, l.rounds());
        assertEquals(ReviewLoop.Stop.FAILED, loop(ReviewLevel.NORMAL).afterRound(noReport, false));
    }

    @Test void worseWorld() {
        assertFalse(ReviewLoop.worseWorld(-1, 0, 100, 50));
        assertTrue(ReviewLoop.worseWorld(1000, 0, 1000, 20));
        assertFalse(ReviewLoop.worseWorld(1000, 0, 1000, 3));
        assertTrue(ReviewLoop.worseWorld(1000, 0, 600, 0));
        assertFalse(ReviewLoop.worseWorld(30, 0, 5, 0));
    }

    @Test void levelParseAndFlag() {
        assertEquals(ReviewLevel.STRICT, ReviewLevel.parse("STRICT", ReviewLevel.OFF));
        assertNull(ReviewLevel.parse("zz", null));
        var r = ReviewFlag.extract("城を建てて --review strict");
        assertEquals("城を建てて", r.prompt());
        assertEquals(ReviewLevel.STRICT, r.level());
        assertEquals("zz", ReviewFlag.extract("建てて --review zz").badValue());
        assertNull(ReviewFlag.extract("普通の依頼").level());
    }

    @Test void reportParseAndStrip() {
        var p = ReviewReport.parse("見ました\nREVIEW_FOUND: 3\nREVIEW_FIXED：窓を追加\nREVIEW_ISSUES: 1");
        assertTrue(p.reported());
        assertEquals(3, p.found());
        assertEquals(1, p.remaining());
        assertEquals("窓を追加", p.fixed());
        assertFalse(ReviewReport.parse("報告なし").reported());
        assertFalse(ReviewReport.strip("a\nREVIEW_FOUND: 1\nb").contains("REVIEW_"));
    }
}
