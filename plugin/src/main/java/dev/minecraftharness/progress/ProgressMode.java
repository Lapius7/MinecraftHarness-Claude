package dev.minecraftharness.progress;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Where a player's progress for a running prompt is shown. */
public enum ProgressMode {
    BOSSBAR, ACTIONBAR, OFF;

    public static final ProgressMode DEFAULT = BOSSBAR;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<ProgressMode> parse(String s) {
        if (s == null) return Optional.empty();
        String t = s.trim().toLowerCase(Locale.ROOT);
        for (ProgressMode m : values()) {
            if (m.id().equals(t)) return Optional.of(m);
        }
        return Optional.empty();
    }

    /** Parses a config value; anything unknown falls back to the default. */
    public static ProgressMode parseOrDefault(String s) {
        return parse(s).orElse(DEFAULT);
    }

    public static List<String> ids() {
        return List.of(BOSSBAR.id(), ACTIONBAR.id(), OFF.id());
    }
}
