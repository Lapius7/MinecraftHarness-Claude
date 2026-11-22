package dev.minecraftharness.review;

import java.util.List;
import java.util.Locale;

/** How hard Claude checks a finished build. */
public enum ReviewLevel {
    OFF("off", 0, 0, 0),
    LIGHT("light", 1, 300, 150_000),
    NORMAL("normal", 3, 600, 400_000),
    STRICT("strict", 5, 1200, 900_000),
    MAX("max", Integer.MAX_VALUE, 3600, 3_000_000);

    private final String id;
    private final int defaultRounds;
    private final int defaultSeconds;
    private final int defaultTokens;

    ReviewLevel(String id, int rounds, int seconds, int tokens) {
        this.id = id;
        this.defaultRounds = rounds;
        this.defaultSeconds = seconds;
        this.defaultTokens = tokens;
    }

    public String id() {
        return id;
    }

    /** Default round limit used when config.yml has none; max is capped by time and tokens only. */
    public int defaultRounds() {
        return defaultRounds;
    }

    public int defaultSeconds() {
        return defaultSeconds;
    }

    public int defaultTokens() {
        return defaultTokens;
    }

    public boolean strictChecklist() {
        return this == STRICT || this == MAX;
    }

    public static List<String> ids() {
        return List.of("off", "light", "normal", "strict", "max");
    }

    /** @return the level, or {@code fallback} for blank or unknown text. */
    public static ReviewLevel parse(String text, ReviewLevel fallback) {
        if (text == null) return fallback;
        String t = text.trim().toLowerCase(Locale.ROOT);
        for (ReviewLevel l : values()) {
            if (l.id.equals(t)) return l;
        }
        return fallback;
    }

    public static boolean isLevel(String text) {
        return parse(text, null) != null;
    }
}
