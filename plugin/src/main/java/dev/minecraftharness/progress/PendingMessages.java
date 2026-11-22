package dev.minecraftharness.progress;

import net.kyori.adventure.text.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Messages for players who are offline when a result arrives (their prompt kept running after they
 * left). Delivered when they join again. Bounded, so a long absence cannot grow without limit.
 */
public final class PendingMessages {

    public static final int MAX_PER_PLAYER = 20;

    private final Map<String, Deque<Component>> byPlayer = new HashMap<>();

    public synchronized void add(String player, Component message) {
        if (player == null || player.isBlank() || message == null) return;
        Deque<Component> q = byPlayer.computeIfAbsent(key(player), k -> new ArrayDeque<>());
        q.addLast(message);
        while (q.size() > MAX_PER_PLAYER) q.removeFirst();
    }

    /** Takes all waiting messages for the player, oldest first. */
    public synchronized List<Component> drain(String player) {
        Deque<Component> q = byPlayer.remove(key(player));
        return q == null ? List.of() : new ArrayList<>(q);
    }

    public synchronized int count(String player) {
        Deque<Component> q = byPlayer.get(key(player));
        return q == null ? 0 : q.size();
    }

    private static String key(String player) {
        return player.toLowerCase(Locale.ROOT);
    }
}
