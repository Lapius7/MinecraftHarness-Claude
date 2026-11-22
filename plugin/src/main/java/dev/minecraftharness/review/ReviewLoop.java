package dev.minecraftharness.review;

/** Decides when the review loop stops. Pure, so every limit can be tested without a server. */
public final class ReviewLoop {

    public enum Stop {
        /** Keep going. */
        NONE("続行"),
        CLEAN("問題なし"),
        OFF("レビューなし"),
        ROUNDS("回数の上限"),
        TIME("時間の上限"),
        TOKENS("トークンの上限"),
        WORSE("悪化したので戻した"),
        STOPPED("/mh stop で停止"),
        STUCK("同じ問題が残った"),
        FAILED("点検に失敗");

        private final String label;

        Stop(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** The one-word result shown in the summary: passed, hit a limit, undone, or stopped early. */
        public String verdict() {
            return switch (this) {
                case CLEAN -> "通過";
                case ROUNDS, TIME, TOKENS -> "上限到達";
                case WORSE -> "悪化で undo";
                case STUCK -> "同じ問題が残り中断";
                case STOPPED -> "停止";
                case FAILED -> "点検失敗";
                default -> "点検なし";
            };
        }
    }

    private final ReviewLevel level;
    private final ReviewLimits limits;
    private int rounds;
    private int lastRemaining = -1;
    private int unchanged;

    public ReviewLoop(ReviewLevel level, ReviewLimits limits) {
        this.level = level;
        this.limits = limits;
    }

    public ReviewLevel level() {
        return level;
    }

    public int rounds() {
        return rounds;
    }

    /** Rounds shown as "2/3"; {@code -} for max, which has no round cap. */
    public String maxLabel() {
        return limits.maxRounds() == Integer.MAX_VALUE ? "-" : String.valueOf(limits.maxRounds());
    }

    public int maxRounds() {
        return limits.maxRounds() == Integer.MAX_VALUE ? 0 : limits.maxRounds();
    }

    /** Checked before each round starts. */
    public Stop beforeRound(long elapsedMs, long tokensUsed, boolean cancelled) {
        if (level == ReviewLevel.OFF) return Stop.OFF;
        if (cancelled) return Stop.STOPPED;
        if (rounds >= limits.maxRounds()) return Stop.ROUNDS;
        if (elapsedMs >= limits.maxMillis()) return Stop.TIME;
        if (limits.maxTokens() > 0 && tokensUsed >= limits.maxTokens()) return Stop.TOKENS;
        return Stop.NONE;
    }

    /** Like {@link #afterRound}, but a cancel during the round wins: it is reported as stopped, never as failed or worse. */
    public Stop afterRound(RoundResult r, boolean cancelled) {
        if (cancelled) {
            rounds++;
            return Stop.STOPPED;
        }
        return afterRound(r);
    }

    /** Called once a round has ended, then {@link #beforeRound} decides whether another one starts. */
    public Stop afterRound(RoundResult r) {
        rounds++;
        if (!r.reported()) return Stop.FAILED;
        if (r.worse() || (lastRemaining >= 0 && r.remaining() > lastRemaining)) return Stop.WORSE;
        if (r.remaining() == 0) return Stop.CLEAN;
        if (lastRemaining >= 0 && r.remaining() >= lastRemaining && r.found() > 0 && r.fixed() == 0) {
            unchanged++;
        } else {
            unchanged = 0;
        }
        lastRemaining = r.remaining();
        return unchanged >= 2 ? Stop.STUCK : Stop.NONE;
    }

    /**
     * What one round reported. {@code found} problems were seen, {@code remaining} are left after the
     * fixes, {@code worse} is true when the world or the report got worse than before the round.
     */
    public record RoundResult(boolean reported, int found, int remaining, int fixed, boolean worse) {}

    /** Compares the world before and after a round: more floating blocks, or many blocks gone. */
    public static boolean worseWorld(int blocksBefore, int floatingBefore, int blocksAfter, int floatingAfter) {
        if (blocksBefore < 0 || blocksAfter < 0) return false;
        if (floatingAfter > floatingBefore + Math.max(3, blocksBefore / 200)) return true;
        return blocksBefore >= 50 && blocksAfter < blocksBefore * 0.7;
    }
}
