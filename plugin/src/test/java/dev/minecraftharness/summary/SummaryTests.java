package dev.minecraftharness.summary;

import static org.junit.jupiter.api.Assertions.*;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class SummaryTests {
    static String plain(net.kyori.adventure.text.Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }

    TurnStats stats() {
        TurnStats s = new TurnStats();
        s.setModel("claude-sonnet-5-5", "high", "API");
        s.addUsage(1_000_000, 100_000, 200_000, 50_000, -1);
        s.addUsage(0, 0, 0, 0, -1);
        s.addToolCall(); s.addToolCall();
        s.addBlocks(120);
        s.addAnchor("s1");
        s.setTiming(new TurnStats.Timing(1000, 2000, 3000, 4000, 5000));
        return s;
    }

    @Test void aggregatesAndTimingTotal() {
        TurnStats s = stats();
        assertEquals(2, s.toolCalls());
        assertEquals(120, s.blocks());
        assertEquals(15_000, s.timing().totalMs());
        assertEquals(1_000_000, s.tokens().input());
        assertEquals(-1, s.tokens().thinking());
    }

    @Test void priceUsesPerMillionAndOmitsWhenUnknown() {
        TurnStats s = stats();
        var t = new PriceTable().put("claude-sonnet-5-5", new PriceTable.Price(3, 15, 0.3, 3.75));
        // 1M*3 + 0.1M*15 + 0.2M*0.3 + 0.05M*3.75 = 3 + 1.5 + 0.06 + 0.1875
        assertEquals(4.7475, t.cost("claude-sonnet-5-5", s.tokens()), 1e-9);
        assertNull(t.cost("other-model", s.tokens()));
        assertNull(new PriceTable().put("m", new PriceTable.Price(0, 0, 0, 0)).cost("m", s.tokens()));
    }

    @Test void renderLevels() {
        TurnStats s = stats();
        assertNull(SummaryRenderer.render(DetailLevel.OFF, s, null));
        String shortText = plain(SummaryRenderer.render(DetailLevel.SHORT, s, null));
        assertFalse(shortText.contains("\n"));
        assertFalse(shortText.contains("$"));
        String full = plain(SummaryRenderer.render(DetailLevel.FULL, s, 4.7475));
        assertTrue(full.contains("s1"));
        assertTrue(full.contains("$"));
        assertTrue(full.contains("-"), "unknown thinking tokens shown as -");
    }

    @Test void budgetIgnoresCacheReadsAndBuiltIsIndependentOfBlockCount() {
        TurnStats s = stats();
        assertEquals(1_000_000 + 100_000 + 50_000, s.budgetTokens());
        TurnStats t = new TurnStats();
        assertFalse(t.built());
        t.markBuilt();
        assertTrue(t.built());
        assertEquals(-1, t.blocks());
    }

    @Test void detailParse() {
        assertEquals(DetailLevel.FULL, DetailLevel.parse("FULL", DetailLevel.SHORT));
        assertEquals(DetailLevel.SHORT, DetailLevel.parse("x", DetailLevel.SHORT));
    }

    @Test void reviewRoundsAndVerdictShownInSummary() {
        TurnStats s = stats();
        s.addReview(3, 1, "窓を足した");
        s.addReview(2, 3, "");
        s.markLastRoundUndone();
        s.setReviewStop(dev.minecraftharness.review.ReviewLoop.Stop.WORSE.label());
        s.setReviewVerdict(dev.minecraftharness.review.ReviewLoop.Stop.WORSE.verdict());
        TurnStats.Review r = s.review();
        assertEquals(2, r.roundList().size());
        assertEquals(2, r.roundList().get(0).fixed());
        assertTrue(r.roundList().get(1).undone());
        String full = plain(SummaryRenderer.render(DetailLevel.FULL, s, null));
        assertTrue(full.contains("2 回点検"));
        assertTrue(full.contains("判定: 悪化で undo"));
        assertTrue(full.contains("1 回目") && full.contains("見つけた 3 件 / 直した 2 件 / 残り 1 件"));
        assertTrue(full.contains("悪化したので元に戻した"));
        assertTrue(plain(SummaryRenderer.render(DetailLevel.SHORT, s, null)).contains("レビュー 2 回 悪化で undo"));
    }

    @Test void verdictForEveryStop() {
        var S = dev.minecraftharness.review.ReviewLoop.Stop.class;
        assertEquals("通過", dev.minecraftharness.review.ReviewLoop.Stop.CLEAN.verdict());
        for (var st : new dev.minecraftharness.review.ReviewLoop.Stop[] {
            dev.minecraftharness.review.ReviewLoop.Stop.ROUNDS, dev.minecraftharness.review.ReviewLoop.Stop.TIME,
            dev.minecraftharness.review.ReviewLoop.Stop.TOKENS}) assertEquals("上限到達", st.verdict());
        assertNotNull(S);
    }
}
