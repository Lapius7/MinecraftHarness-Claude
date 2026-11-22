package dev.minecraftharness.review;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads a per-request {@code --review <level>} from the end (or anywhere) in a prompt. */
public final class ReviewFlag {

    private static final Pattern FLAG = Pattern.compile("(?i)(?:^|\\s)--review(?:\\s+|=)([a-z]+)(?=\\s|$)");

    /** @param level null when the prompt had no valid flag; {@code prompt} has the flag removed. */
    public record Result(String prompt, ReviewLevel level, String badValue) {}

    private ReviewFlag() {}

    public static Result extract(String prompt) {
        if (prompt == null) return new Result("", null, null);
        Matcher m = FLAG.matcher(prompt);
        if (!m.find()) return new Result(prompt, null, null);
        ReviewLevel level = ReviewLevel.parse(m.group(1), null);
        String rest = (prompt.substring(0, m.start()) + " " + prompt.substring(m.end())).trim().replaceAll("\\s{2,}", " ");
        if (level == null) return new Result(prompt, null, m.group(1));
        return new Result(rest, level, null);
    }
}
