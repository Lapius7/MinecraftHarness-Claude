package dev.minecraftharness.progress;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProgressTests {
    final AtomicLong now = new AtomicLong(1_000_000);

    ProgressState state() {
        return new ProgressState("Steve", now::get);
    }

    @Test void startsWaitingThenThinkingWithElapsedSeconds() {
        ProgressState s = state();
        assertEquals(ProgressState.Phase.WAITING, s.phase());
        assertTrue(s.view().text().startsWith("受付: 待機中"));
        now.addAndGet(3_000);
        s.thinking();
        ProgressState.View v = s.view();
        assertEquals(ProgressState.Phase.THINKING, v.phase());
        assertEquals("思考中(3秒)", v.text());
        now.addAndGet(2_000);
        assertEquals("思考中(5秒)", s.view().text());
    }

    @Test void respondingShowsRoughTokensAndNeverShrinks() {
        ProgressState s = state();
        s.responding(120);
        s.responding(80);
        assertTrue(s.view().text().contains("約120トークン"));
    }

    @Test void toolNameAndCallNumber() {
        ProgressState s = state();
        s.toolStreaming("mc_inspect_world");
        assertTrue(s.view().text().contains("mc_inspect_world"));
        s.toolStart("mc_inspect_world");
        assertEquals(ProgressState.Phase.TOOL, s.phase());
        assertTrue(s.view().text().contains("1件目"));
        s.toolEnd();
        s.thinking();
        s.toolStart("mc_build_batch");
        assertEquals(ProgressState.Phase.BUILDING, s.phase());
        assertTrue(s.view().text().startsWith("建築中: mc_build_batch 2件目"));
        // text deltas arriving during a running tool must not hide it
        s.responding(10);
        assertEquals(ProgressState.Phase.BUILDING, s.phase());
        s.toolEnd();
        assertEquals(ProgressState.Phase.THINKING, s.phase());
    }

    @Test void stallNoticeAfterThirtySecondsOfSilence() {
        ProgressState s = state();
        s.thinking();
        now.addAndGet(29_000);
        assertFalse(s.view().stalled());
        now.addAndGet(2_000);
        ProgressState.View v = s.view();
        assertTrue(v.stalled());
        assertTrue(v.text().startsWith("応答待ち(31秒)"));
        s.activity();
        assertFalse(s.view().stalled());
    }

    @Test void runningToolIsNotReportedAsStall() {
        ProgressState s = state();
        s.toolStart("mc_build_batch");
        now.addAndGet(120_000);
        assertFalse(s.view().stalled());
    }

    @Test void doneShowsThenExpires() {
        ProgressState s = state();
        now.addAndGet(7_000);
        s.finish();
        assertEquals("完了(7秒)", s.view().text());
        assertTrue(s.isFinished());
        assertFalse(s.isExpired());
        now.addAndGet(ProgressState.DONE_SHOW_MS);
        assertTrue(s.isExpired());
    }

    @Test void failureKeepsReasonAndLateEventsAreIgnored() {
        ProgressState s = state();
        s.fail("API エラー 529\nbusy");
        assertEquals(ProgressState.Phase.FAILED, s.phase());
        assertEquals("失敗: API エラー 529 busy", s.view().text());
        s.thinking();
        s.finish();
        assertEquals(ProgressState.Phase.FAILED, s.phase());
        assertFalse(s.isExpired());
        now.addAndGet(ProgressState.FAIL_SHOW_MS);
        assertTrue(s.isExpired());
    }

    @Test void cancelShowsReason() {
        ProgressState s = state();
        s.cancel("/mh stop で停止");
        assertEquals(ProgressState.Phase.CANCELLED, s.phase());
        assertTrue(s.view().text().startsWith("中断: "));
    }

    @Test void longReasonIsTruncatedAndFormattingCodesDropped() {
        ProgressState s = state();
        s.fail("§c" + "x".repeat(200));
        String t = s.view().text();
        assertFalse(t.contains("§"));
        assertTrue(t.length() < 80);
        assertTrue(t.endsWith("…"));
    }

    @Test void secondPromptQueuesBehindFirst() {
        ProgressTracker t = new ProgressTracker(now::get);
        ProgressState a = t.begin("Steve");
        ProgressState b = t.begin("steve");
        assertSame(a, b);
        assertEquals(1, a.queued());
        assertTrue(a.view().text().contains("+待ち1"));
        now.addAndGet(5_000);
        t.finish("Steve"); // first turn done: the second one starts from zero
        assertFalse(a.isFinished());
        assertEquals(ProgressState.Phase.WAITING, a.phase());
        assertEquals(0, a.queued());
        t.finish("Steve");
        assertEquals(ProgressState.Phase.DONE, a.phase());
    }

    @Test void failureOfFirstStillLetsQueuedOneTakeOver() {
        ProgressTracker t = new ProgressTracker(now::get);
        ProgressState a = t.begin("Steve");
        t.begin("Steve");
        t.fail("Steve", "x");
        assertFalse(a.isFinished());
        t.finish("Steve");
        assertEquals(ProgressState.Phase.WAITING, a.phase());
    }

    @Test void trackerDropsEventsWithoutPromptAndPurges() {
        ProgressTracker t = new ProgressTracker(now::get);
        t.thinking("Nobody");
        t.toolStart("Nobody", "mc_x");
        assertNull(t.get("Nobody"));
        t.begin("Alex");
        t.finish("Alex");
        t.purgeExpired();
        assertNotNull(t.get("alex"));
        now.addAndGet(ProgressState.DONE_SHOW_MS);
        t.purgeExpired();
        assertNull(t.get("Alex"));
    }

    @Test void newPromptReplacesFinishedState() {
        ProgressTracker t = new ProgressTracker(now::get);
        ProgressState a = t.begin("Steve");
        t.finish("Steve");
        ProgressState b = t.begin("Steve");
        assertNotSame(a, b);
        assertEquals(ProgressState.Phase.WAITING, b.phase());
    }

    @Test void cancelAllAndRemove() {
        ProgressTracker t = new ProgressTracker(now::get);
        t.begin("A");
        t.begin("B");
        t.cancelAll("stop");
        assertEquals(ProgressState.Phase.CANCELLED, t.get("A").phase());
        t.remove("A");
        assertNull(t.get("A"));
        t.clear();
        assertTrue(t.all().isEmpty());
    }

    @Test void modeParsing() {
        assertEquals(ProgressMode.BOSSBAR, ProgressMode.parseOrDefault(null));
        assertEquals(ProgressMode.BOSSBAR, ProgressMode.parseOrDefault("nonsense"));
        assertEquals(ProgressMode.ACTIONBAR, ProgressMode.parseOrDefault(" ActionBar "));
        assertEquals(ProgressMode.OFF, ProgressMode.parse("off").orElseThrow());
        assertTrue(ProgressMode.parse("x").isEmpty());
        assertEquals(java.util.List.of("bossbar", "actionbar", "off"), ProgressMode.ids());
    }

    @Test void cancelRunningKeepsDisplayWhenPromptsWait() {
        ProgressTracker t = new ProgressTracker(now::get);
        ProgressState a = t.begin("Steve");
        t.begin("Steve");
        t.cancelRunning("Steve", "stop");
        assertFalse(a.isFinished());
        t.finish("Steve"); // the stopped turn returns, the waiting one takes over
        assertEquals(ProgressState.Phase.WAITING, a.phase());
        t.cancelRunning("Steve", "stop");
        assertEquals(ProgressState.Phase.CANCELLED, a.phase());
    }

    @Test void dropQueuedCountsDownThenEnds() {
        ProgressTracker t = new ProgressTracker(now::get);
        ProgressState a = t.begin("Steve");
        t.begin("Steve");
        t.dropQueued("Steve", "x");
        assertEquals(0, a.queued());
        assertFalse(a.isFinished());
        t.dropQueued("Steve", "x");
        assertEquals(ProgressState.Phase.CANCELLED, a.phase());
    }
}
