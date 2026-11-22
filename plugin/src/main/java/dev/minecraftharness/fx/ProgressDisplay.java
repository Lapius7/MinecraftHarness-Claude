package dev.minecraftharness.fx;

import dev.minecraftharness.progress.ProgressMode;
import dev.minecraftharness.progress.ProgressState;
import dev.minecraftharness.progress.ProgressTracker;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import dev.minecraftharness.progress.BarPlanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Iterator;
import java.util.Map;

/**
 * Shows each player's {@link ProgressState} as a bossbar or action bar. Main thread only: call
 * {@link #tick} once a second. Bars are removed on completion, on stop, and when the player leaves.
 */
public final class ProgressDisplay {

    private final ProgressTracker tracker;
    private final Map<String, BossBar> bars = new HashMap<>();
    private volatile ProgressMode mode;

    public ProgressDisplay(ProgressTracker tracker, ProgressMode mode) {
        this.tracker = tracker;
        this.mode = mode;
    }

    public ProgressMode mode() {
        return mode;
    }

    public void setMode(ProgressMode next) {
        if (next == mode) return;
        mode = next;
        hideAllBars();
    }

    static BossBar.Color color(ProgressState.Tone t) {
        return switch (t) {
            case QUEUED -> BossBar.Color.PURPLE;
            case THINK -> BossBar.Color.BLUE;
            case WRITE, OK -> BossBar.Color.GREEN;
            case TOOL -> BossBar.Color.YELLOW;
            case WARN -> BossBar.Color.PINK;
            case ERROR -> BossBar.Color.RED;
        };
    }

    static TextColor textColor(ProgressState.Tone t) {
        return switch (t) {
            case QUEUED -> NamedTextColor.LIGHT_PURPLE;
            case THINK -> NamedTextColor.AQUA;
            case WRITE, OK -> NamedTextColor.GREEN;
            case TOOL -> NamedTextColor.YELLOW;
            case WARN -> NamedTextColor.GOLD;
            case ERROR -> NamedTextColor.RED;
        };
    }

    /** Who besides the requester sees a build in progress (config progress.observers). */
    private volatile BarPlanner.Observers observers = BarPlanner.Observers.ALL;

    public void setObservers(BarPlanner.Observers o) {
        if (o == observers) return;
        observers = o;
        hideAllBars();
    }

    /**
     * Draws every running prompt once and drops what is finished and shown long enough. A requester
     * who is offline keeps their state: it is drawn again as soon as they are back.
     */
    public void tick(boolean filmMode) {
        if (filmMode || mode == ProgressMode.OFF) {
            hideAllBars();
            if (filmMode) tracker.purgeExpired();
            return;
        }
        List<BarPlanner.Work> work = new ArrayList<>();
        Map<String, ProgressState> byOwner = new HashMap<>();
        for (ProgressState st : tracker.all()) {
            ProgressState.View v = st.view();
            boolean running = !st.isFinished() && st.phase() != ProgressState.Phase.WAITING;
            work.add(new BarPlanner.Work(st.playerName(), running, true));
            byOwner.put(st.playerName().toLowerCase(Locale.ROOT), st);
        }
        List<BarPlanner.Viewer> online = new ArrayList<>();
        Map<String, Player> players = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(new BarPlanner.Viewer(p.getName(), false));
            players.put(p.getName().toLowerCase(Locale.ROOT), p);
        }
        Set<String> wanted = new HashSet<>();
        for (BarPlanner.Slot slot : BarPlanner.plan(work, online, observers)) {
            Player p = players.get(slot.viewer().toLowerCase(Locale.ROOT));
            ProgressState st = byOwner.get(slot.owner().toLowerCase(Locale.ROOT));
            if (p == null || st == null) continue;
            ProgressState.View v = st.view();
            Component text = slot.own()
                ? Component.text("⚡ " + v.text(), textColor(v.tone()))
                : Component.text(st.playerName() + " さんが建築中: " + v.text(), NamedTextColor.GRAY);
            wanted.add(slot.key());
            if (slot.own() && mode == ProgressMode.ACTIONBAR) {
                p.sendActionBar(text);
                continue;
            }
            if (!slot.own() && mode == ProgressMode.ACTIONBAR) continue;
            BossBar bar = bars.computeIfAbsent(slot.key(), k -> {
                BossBar b = BossBar.bossBar(text, v.fraction(), slot.own() ? color(v.tone()) : BossBar.Color.WHITE,
                    slot.own() ? BossBar.Overlay.PROGRESS : BossBar.Overlay.NOTCHED_6);
                p.showBossBar(b);
                return b;
            });
            bar.name(text);
            if (slot.own()) {
                bar.color(color(v.tone()));
                bar.progress(Math.max(0f, Math.min(1f, v.fraction())));
            }
        }
        tracker.purgeExpired();
        // Bars no longer planned: finished, expired, owner stopped, viewer left or changed mode.
        Iterator<Map.Entry<String, BossBar>> it = bars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BossBar> e = it.next();
            if (wanted.contains(e.getKey()) && tracker.get(owner(e.getKey())) != null) continue;
            Player p = players.get(viewer(e.getKey()));
            if (p != null) p.hideBossBar(e.getValue());
            it.remove();
        }
    }

    private static String viewer(String key) {
        return key.substring(0, key.indexOf('|'));
    }

    private static String owner(String key) {
        return key.substring(key.indexOf('|') + 1);
    }

    /**
     * Takes every bar off one viewer and forgets it, without touching any prompt state. The next tick
     * draws what they should see again. Used when they leave, change world, die or respawn.
     */
    public void refresh(Player p) {
        String prefix = p.getName().toLowerCase(Locale.ROOT) + "|";
        Iterator<Map.Entry<String, BossBar>> it = bars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BossBar> e = it.next();
            if (!e.getKey().startsWith(prefix)) continue;
            p.hideBossBar(e.getValue());
            it.remove();
        }
        p.sendActionBar(Component.empty());
    }

    /** Removes one player's bars and their prompt state at once (/mh stop, or leaving with onQuit: stop). */
    public void clear(Player p) {
        tracker.remove(p.getName());
        refresh(p);
    }

    /** Removes every bar and state at once (/mh stop). */
    public void clearAll() {
        hideAllBars();
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(Component.empty());
        tracker.clear();
    }

    private void hideAllBars() {
        for (Map.Entry<String, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayerExact(viewer(e.getKey()));
            if (p != null) p.hideBossBar(e.getValue());
        }
        bars.clear();
    }

    /** Plugin shutdown: nothing may stay on screen. */
    public void cleanup() {
        hideAllBars();
        tracker.clear();
    }
}
