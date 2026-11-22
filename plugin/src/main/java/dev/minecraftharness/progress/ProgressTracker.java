package dev.minecraftharness.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Progress of the prompt each player is waiting for. Thread-safe, so the agent thread, the MCP
 * server threads and the main thread can all report into it. Events for a player with no running
 * prompt are dropped, so a late event cannot bring a cleared bar back.
 */
public final class ProgressTracker {

    private final ConcurrentHashMap<String, ProgressState> states = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public ProgressTracker() {
        this(System::currentTimeMillis);
    }

    public ProgressTracker(LongSupplier clock) {
        this.clock = clock;
    }

    private static String key(String player) {
        return player == null ? "" : player.toLowerCase(Locale.ROOT);
    }

    /** Starts (or restarts) tracking right when the prompt is accepted. */
    public ProgressState begin(String player) {
        return states.compute(key(player), (k, old) -> {
            if (old != null && !old.isFinished()) {
                old.enqueue();
                return old;
            }
            return new ProgressState(player, clock);
        });
    }

    public ProgressState get(String player) {
        return states.get(key(player));
    }

    public List<ProgressState> all() {
        return new ArrayList<>(states.values());
    }

    public void remove(String player) {
        states.remove(key(player));
    }

    public void clear() {
        states.clear();
    }

    /** Drops finished states that have been shown long enough. */
    public void purgeExpired() {
        states.values().removeIf(ProgressState::isExpired);
    }

    public void activity(String player) {
        ProgressState s = get(player);
        if (s != null) s.activity();
    }

    public void thinking(String player) {
        ProgressState s = get(player);
        if (s != null) s.thinking();
    }

    public void responding(String player, int tokens) {
        ProgressState s = get(player);
        if (s != null) s.responding(tokens);
    }

    public void toolStreaming(String player, String name) {
        ProgressState s = get(player);
        if (s != null) s.toolStreaming(name);
    }

    public void toolStart(String player, String name) {
        ProgressState s = get(player);
        if (s != null) s.toolStart(name);
    }

    public void toolEnd(String player) {
        ProgressState s = get(player);
        if (s != null) s.toolEnd();
    }

    public void finish(String player) {
        ProgressState s = get(player);
        if (s != null) s.finish();
    }

    public void fail(String player, String reason) {
        ProgressState s = get(player);
        if (s != null) s.fail(reason);
    }

    public void cancel(String player, String reason) {
        ProgressState s = get(player);
        if (s != null) s.cancel(reason);
    }

    /** Marks every running prompt cancelled (used by /mh stop). */
    public void cancelAll(String reason) {
        for (ProgressState s : states.values()) s.cancel(reason);
    }
}
