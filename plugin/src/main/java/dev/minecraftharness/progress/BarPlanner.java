package dev.minecraftharness.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides which player sees which progress bar. Pure, so the quit, rejoin, mid-join and film-mode
 * cases can be tested without a server. The caller draws what comes back and removes any bar that
 * is no longer in the plan.
 */
public final class BarPlanner {

    /** Who besides the requester may see a build in progress. */
    public enum Observers {
        ALL, OWNER, OFF;

        public static Observers parse(String text, Observers fallback) {
            if (text == null) return fallback;
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "all" -> ALL;
                case "owner" -> OWNER;
                case "off" -> OFF;
                default -> fallback;
            };
        }
    }

    /** A prompt that has a state: {@code showing} is false once nothing is worth drawing. */
    public record Work(String owner, boolean running, boolean showing) {}

    public record Viewer(String name, boolean film) {}

    /** {@code own} bars are the requester's full bar; the others are the subdued "さんが建築中" bars. */
    public record Slot(String viewer, String owner, boolean own) {
        public String key() {
            return viewer.toLowerCase(Locale.ROOT) + "|" + owner.toLowerCase(Locale.ROOT);
        }
    }

    public static final int MAX_OBSERVED = 3;

    private BarPlanner() {}

    public static List<Slot> plan(List<Work> work, List<Viewer> online, Observers observers) {
        List<Slot> out = new ArrayList<>();
        for (Viewer v : online) {
            if (v.film()) continue;
            int seen = 0;
            for (Work w : work) {
                if (!w.showing()) continue;
                boolean mine = w.owner().equalsIgnoreCase(v.name());
                if (mine) {
                    out.add(new Slot(v.name(), w.owner(), true));
                } else if (observers == Observers.ALL && w.running() && seen < MAX_OBSERVED) {
                    out.add(new Slot(v.name(), w.owner(), false));
                    seen++;
                }
            }
        }
        return out;
    }
}
