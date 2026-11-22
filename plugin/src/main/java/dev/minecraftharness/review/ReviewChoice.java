package dev.minecraftharness.review;

/**
 * Which review level a prompt ends up with. A level the player set on purpose (the {@code --review}
 * flag or {@code /mh review}) always wins; otherwise the level the model chose from what the player
 * said; otherwise the config default.
 */
public final class ReviewChoice {

    private ReviewChoice() {}

    public static ReviewLevel resolve(ReviewLevel explicit, ReviewLevel fromModel, ReviewLevel configDefault) {
        if (explicit != null) return explicit;
        if (fromModel != null) return fromModel;
        return configDefault == null ? ReviewLevel.OFF : configDefault;
    }

    /** Whether the model's request is applied, given a level the player set on purpose. */
    public static boolean modelMayChoose(ReviewLevel explicit) {
        return explicit == null;
    }

    /** A review runs after a build, or when the player asked to check a build that already exists. */
    public static boolean shouldRun(ReviewLevel level, boolean builtThisTurn, boolean reviewRequested, boolean ok) {
        return level != ReviewLevel.OFF && ok && (builtThisTurn || reviewRequested);
    }
}
