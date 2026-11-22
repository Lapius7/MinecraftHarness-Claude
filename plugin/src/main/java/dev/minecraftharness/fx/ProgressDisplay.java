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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Shows each player's {@link ProgressState} as a bossbar or action bar. Main thread only: call
 * {@link #tick} once a second. Bars are removed on completion, on stop, and when the player leaves.
 */
public final class ProgressDisplay {

    private final ProgressTracker tracker;
    private final Map<UUID, BossBar> bars = new HashMap<>();
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

    /** Draws every running prompt once and drops what is finished and shown long enough. */
    public void tick(boolean filmMode) {
        if (filmMode || mode == ProgressMode.OFF) {
            hideAllBars();
            if (filmMode) tracker.purgeExpired();
            return;
        }
        for (ProgressState st : tracker.all()) {
            Player p = Bukkit.getPlayerExact(st.playerName());
            if (p == null || !p.isOnline()) {
                tracker.remove(st.playerName());
                continue;
            }
            ProgressState.View v = st.view();
            Component text = Component.text("⚡ " + v.text(), textColor(v.tone()));
            if (mode == ProgressMode.ACTIONBAR) {
                p.sendActionBar(text);
            } else {
                BossBar bar = bars.computeIfAbsent(p.getUniqueId(), k -> {
                    BossBar b = BossBar.bossBar(text, v.fraction(), color(v.tone()), BossBar.Overlay.PROGRESS);
                    p.showBossBar(b);
                    return b;
                });
                bar.name(text);
                bar.color(color(v.tone()));
                bar.progress(Math.max(0f, Math.min(1f, v.fraction())));
            }
        }
        tracker.purgeExpired();
        // Bars whose prompt is gone (expired, stopped, player left).
        Iterator<Map.Entry<UUID, BossBar>> it = bars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BossBar> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            boolean live = p != null && p.isOnline() && tracker.get(p.getName()) != null;
            if (!live) {
                if (p != null) p.hideBossBar(e.getValue());
                it.remove();
            }
        }
    }

    /** Removes one player's bar and state at once (quit, /mh stop). */
    public void clear(Player p) {
        tracker.remove(p.getName());
        BossBar b = bars.remove(p.getUniqueId());
        if (b != null) p.hideBossBar(b);
        p.sendActionBar(Component.empty());
    }

    /** Removes every bar and state at once (/mh stop). */
    public void clearAll() {
        hideAllBars();
        for (Player p : Bukkit.getOnlinePlayers()) p.sendActionBar(Component.empty());
        tracker.clear();
    }

    private void hideAllBars() {
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
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
