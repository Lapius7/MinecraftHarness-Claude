package dev.minecraftharness.progress;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class BarPlannerTests {
    static BarPlanner.Work w(String o, boolean running) { return new BarPlanner.Work(o, running, true); }
    static BarPlanner.Viewer v(String n, boolean film) { return new BarPlanner.Viewer(n, film); }

    @Test void ownerOnlineGetsOwnBar() {
        var p = BarPlanner.plan(List.of(w("Steve", true)), List.of(v("Steve", false)), BarPlanner.Observers.ALL);
        assertEquals(1, p.size());
        assertTrue(p.get(0).own());
    }

    @Test void offlineOwnerHasNoBarButStateReattachesOnRejoin() {
        var work = List.of(w("Steve", true));
        assertTrue(BarPlanner.plan(work, List.of(v("Alex", false)), BarPlanner.Observers.OFF).isEmpty());
        var back = BarPlanner.plan(work, List.of(v("steve", false)), BarPlanner.Observers.OFF);
        assertEquals(1, back.size());
        assertTrue(back.get(0).own());
    }

    @Test void onlyWaitsRemainStillReattaches() {
        var p = BarPlanner.plan(List.of(w("Steve", false)), List.of(v("Steve", false)), BarPlanner.Observers.ALL);
        assertEquals(1, p.size());
    }

    @Test void observersAllShowsRunningBuildToOthers() {
        var p = BarPlanner.plan(List.of(w("Steve", true)), List.of(v("Steve", false), v("Alex", false)), BarPlanner.Observers.ALL);
        assertEquals(2, p.size());
        assertFalse(p.stream().filter(s -> s.viewer().equals("Alex")).findFirst().orElseThrow().own());
    }

    @Test void observersOwnerOrOffHidesFromOthers() {
        for (var o : List.of(BarPlanner.Observers.OWNER, BarPlanner.Observers.OFF)) {
            var p = BarPlanner.plan(List.of(w("Steve", true)), List.of(v("Steve", false), v("Alex", false)), o);
            assertEquals(1, p.size());
        }
    }

    @Test void waitingOnlyIsNotShownToOthers() {
        var p = BarPlanner.plan(List.of(w("Steve", false)), List.of(v("Alex", false)), BarPlanner.Observers.ALL);
        assertTrue(p.isEmpty());
    }

    @Test void filmModeViewerSeesNothing() {
        var p = BarPlanner.plan(List.of(w("Steve", true)), List.of(v("Steve", true), v("Alex", true)), BarPlanner.Observers.ALL);
        assertTrue(p.isEmpty());
    }

    @Test void observersParse() {
        assertEquals(BarPlanner.Observers.OWNER, BarPlanner.Observers.parse("Owner", BarPlanner.Observers.ALL));
        assertEquals(BarPlanner.Observers.ALL, BarPlanner.Observers.parse("zzz", BarPlanner.Observers.ALL));
    }

    @Test void pendingMessagesAreKeptPerPlayerAndCapped() {
        var m = new PendingMessages();
        for (int i = 0; i < 30; i++) m.add("Steve", net.kyori.adventure.text.Component.text("x" + i));
        assertEquals(PendingMessages.MAX_PER_PLAYER, m.count("steve"));
        assertEquals(PendingMessages.MAX_PER_PLAYER, m.drain("STEVE").size());
        assertEquals(0, m.count("Steve"));
    }

    @Test void reviewLabelAndTimingBuckets() {
        long[] t = {0};
        var s = new ProgressState("Steve", () -> t[0]);
        t[0] = 2000; s.thinking();
        t[0] = 5000; s.toolStart("mc_build_batch");
        t[0] = 9000; s.toolEnd();
        s.review(2, 3, "点検中・修正中");
        t[0] = 12000;
        assertTrue(s.view().text().startsWith("レビュー 2/3: 点検中・修正中 "));
        var tm = s.timings();
        assertEquals(2000, tm.waitMs());
        assertEquals(3000, tm.thinkMs());
        assertEquals(4000, tm.buildMs());
        assertEquals(3000, tm.reviewMs());
    }
}
