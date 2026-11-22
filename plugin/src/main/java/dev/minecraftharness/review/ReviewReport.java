package dev.minecraftharness.review;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The machine-readable lines Claude ends a review round with. They are read here and hidden from chat. */
public final class ReviewReport {

    private static final Pattern LINE = Pattern.compile("(?im)^\\s*REVIEW_(FOUND|ISSUES|FIXED)\\s*[:：]\\s*(.*)$");

    public record Parsed(boolean reported, int found, int remaining, String fixed) {}

    private ReviewReport() {}

    public static Parsed parse(String text) {
        if (text == null) return new Parsed(false, 0, 0, "");
        int found = -1;
        int remaining = -1;
        String fixed = "";
        Matcher m = LINE.matcher(text);
        while (m.find()) {
            String key = m.group(1).toUpperCase(java.util.Locale.ROOT);
            String val = m.group(2).trim();
            switch (key) {
                case "FOUND" -> found = number(val, found);
                case "ISSUES" -> remaining = number(val, remaining);
                default -> fixed = val;
            }
        }
        if (remaining < 0 && found < 0) return new Parsed(false, 0, 0, fixed);
        if (found < 0) found = remaining;
        if (remaining < 0) remaining = found;
        return new Parsed(true, found, remaining, fixed);
    }

    /** Removes the report lines so players do not see them. */
    public static String strip(String text) {
        if (text == null) return "";
        return LINE.matcher(text).replaceAll("").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static int number(String v, int fallback) {
        Matcher n = Pattern.compile("\\d+").matcher(v);
        if (n.find()) {
            try {
                return Integer.parseInt(n.group());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
