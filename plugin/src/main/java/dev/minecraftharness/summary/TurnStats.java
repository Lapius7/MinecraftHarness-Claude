package dev.minecraftharness.summary;

import java.util.ArrayList;
import java.util.List;

/**
 * Numbers collected while one prompt (and its review rounds) runs. Written from the agent thread and
 * the tool threads, so every method is synchronized. Values that no source reported stay unknown
 * instead of being filled in.
 */
public final class TurnStats {

    /** Token counts; {@code thinking} is -1 when the source does not report it. */
    public record Tokens(long input, long output, long cacheRead, long cacheWrite, long thinking, boolean known) {}

    public record Timing(long waitMs, long thinkMs, long toolMs, long buildMs, long reviewMs) {
        public long totalMs() {
            return waitMs + thinkMs + toolMs + buildMs + reviewMs;
        }
    }

    /** One review round: problems seen, left after the fixes, fixed, and whether its edits were undone. */
    public record Round(int number, int found, int remaining, int fixed, boolean undone) {}

    public record Review(int rounds, String stop, List<String> fixes, int found, int remaining,
                         List<Round> roundList, String verdict) {}

    private long input;
    private long output;
    private long cacheRead;
    private long cacheWrite;
    private long thinking;
    private boolean thinkingKnown;
    private boolean tokensKnown;
    private int toolCalls;
    private long blocks;
    private boolean blocksKnown;
    private final List<String> anchors = new ArrayList<>();
    private String model = "";
    private String effort = "";
    private String auth = "";
    private Timing timing;
    private int reviewRounds;
    private String reviewStop = "";
    private final List<String> fixes = new ArrayList<>();
    private int found;
    private int remaining;
    private boolean reviewed;
    private final List<Round> roundList = new ArrayList<>();
    private String reviewVerdict = "";

    public synchronized void setModel(String model, String effort, String auth) {
        this.model = model == null ? "" : model;
        this.effort = effort == null ? "" : effort;
        this.auth = auth == null ? "" : auth;
    }

    /** Adds one API response's usage, or one CLI result's usage. {@code thinkingTokens} -1 means not reported. */
    public synchronized void addUsage(long in, long out, long cacheRead, long cacheWrite, long thinkingTokens) {
        input += Math.max(0, in);
        output += Math.max(0, out);
        this.cacheRead += Math.max(0, cacheRead);
        this.cacheWrite += Math.max(0, cacheWrite);
        if (thinkingTokens >= 0) {
            thinking += thinkingTokens;
            thinkingKnown = true;
        }
        tokensKnown = true;
    }

    public synchronized void addToolCall() {
        toolCalls++;
    }

    public synchronized void addBlocks(long n) {
        if (n < 0) return;
        blocks += n;
        blocksKnown = true;
    }

    public synchronized void addAnchor(String id) {
        if (id != null && !id.isBlank() && !anchors.contains(id)) anchors.add(id);
    }

    public synchronized void setTiming(Timing t) {
        this.timing = t;
    }

    public synchronized void addReview(int found, int remaining, String fix) {
        reviewed = true;
        reviewRounds++;
        this.found += Math.max(0, found);
        this.remaining = Math.max(0, remaining);
        if (fix != null && !fix.isBlank()) fixes.add(fix.trim());
        int f = Math.max(0, found);
        int rem = Math.max(0, remaining);
        roundList.add(new Round(reviewRounds, f, rem, Math.max(0, f - rem), false));
    }

    /** The last round made things worse and was rolled back. */
    public synchronized void markLastRoundUndone() {
        if (roundList.isEmpty()) return;
        Round r = roundList.remove(roundList.size() - 1);
        roundList.add(new Round(r.number(), r.found(), r.remaining(), r.fixed(), true));
    }

    public synchronized void setReviewVerdict(String verdict) {
        this.reviewVerdict = verdict == null ? "" : verdict;
    }

    public synchronized void setReviewStop(String stop) {
        this.reviewStop = stop == null ? "" : stop;
        reviewed = true;
    }

    // ---- reads ----------------------------------------------------------------------------

    public synchronized Tokens tokens() {
        return new Tokens(input, output, cacheRead, cacheWrite, thinkingKnown ? thinking : -1, tokensKnown);
    }

    public synchronized long tokensTotal() {
        return input + output + cacheRead + cacheWrite;
    }

    /** Tokens that count against the review budget: cache reads are left out, they repeat on every call. */
    public synchronized long budgetTokens() {
        return input + output + cacheWrite;
    }

    private boolean built;

    public synchronized void markBuilt() {
        built = true;
    }

    /** True once a build tool has run and succeeded, whether or not it reported a block count. */
    public synchronized boolean built() {
        return built;
    }

    public synchronized int toolCalls() {
        return toolCalls;
    }

    /** @return placed block count, or -1 when no build tool reported one. */
    public synchronized long blocks() {
        return blocksKnown ? blocks : -1;
    }

    public synchronized List<String> anchors() {
        return List.copyOf(anchors);
    }

    public synchronized String model() {
        return model;
    }

    public synchronized String effort() {
        return effort;
    }

    public synchronized String auth() {
        return auth;
    }

    public synchronized Timing timing() {
        return timing;
    }

    public synchronized Review review() {
        return reviewed ? new Review(reviewRounds, reviewStop, List.copyOf(fixes), found, remaining, List.copyOf(roundList), reviewVerdict) : null;
    }
}
