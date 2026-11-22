package dev.minecraftharness.summary;

import java.util.List;
import java.util.Locale;

/** How much of the result summary a player sees after a prompt. */
public enum DetailLevel {
    OFF, SHORT, FULL;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static List<String> ids() {
        return List.of("off", "short", "full");
    }

    public static DetailLevel parse(String text, DetailLevel fallback) {
        if (text == null) return fallback;
        for (DetailLevel d : values()) {
            if (d.id().equals(text.trim().toLowerCase(Locale.ROOT))) return d;
        }
        return fallback;
    }
}
