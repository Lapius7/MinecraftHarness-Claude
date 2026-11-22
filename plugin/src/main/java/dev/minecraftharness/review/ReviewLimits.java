package dev.minecraftharness.review;

/** Caps for one review run. {@code maxRounds} of {@link Integer#MAX_VALUE} means "until time or tokens run out". */
public record ReviewLimits(int maxRounds, long maxMillis, long maxTokens) {

    public static ReviewLimits defaults(ReviewLevel level) {
        return new ReviewLimits(level.defaultRounds(), level.defaultSeconds() * 1000L, level.defaultTokens());
    }
}
