package dev.minecraftharness.progress;

import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Progress of one prompt, from the moment it is accepted until the reply ends. Pure state with an
 * injected clock, so it can be fed from any thread and tested without a server. Once the prompt has
 * finished, failed or been cancelled, later events from a still-running backend are ignored.
 */
public final class ProgressState {

    public enum Phase { WAITING, THINKING, RESPONDING, TOOL, BUILDING, DONE, FAILED, CANCELLED }

    /** Colour hint for the renderer; keeps this class free of any UI library. */
    public enum Tone { QUEUED, THINK, WRITE, TOOL, OK, WARN, ERROR }

    /** What the player should see right now. */
    public record View(Phase phase, Tone tone, String text, float fraction, boolean stalled) {}

    /** No event for this long while waiting on the model counts as "応答待ち". */
    public static final long STALL_MS = 30_000L;
    public static final long DONE_SHOW_MS = 3_000L;
    public static final long FAIL_SHOW_MS = 6_000L;
    static final int REASON_MAX = 60;

    private static final Set<String> BUILD_TOOLS = Set.of(
        "mc_build_batch", "mc_function_pack", "mc_place_command_chain", "mc_exec_commands");

    private final LongSupplier clock;
    private final String playerName;
    private long startedAt;
    private Phase phase = Phase.WAITING;
    private long lastEventAt;
    private long finishedAt;
    private int tokens;
    private int toolCalls;
    private boolean inTool;
    private String toolName = "";
    private String reason = "";
    private int queued;

    public ProgressState(String playerName, LongSupplier clock) {
        this.playerName = playerName;
        this.clock = clock;
        this.startedAt = clock.getAsLong();
        this.lastEventAt = startedAt;
    }

    public String playerName() {
        return playerName;
    }

    // ---- events ---------------------------------------------------------------------------

    /** Any sign of life from the backend (a stream line, a heartbeat) without a change of state. */
    public synchronized void activity() {
        touch();
    }

    /** The model started reasoning (CLI thinking_delta / API thinking_delta, or the turn began). */
    public synchronized void thinking() {
        if (!touch()) return;
        if (!inTool) phase = Phase.THINKING;
    }

    /** The model is writing its answer; {@code tokens} is a rough running total for this turn. */
    public synchronized void responding(int tokens) {
        if (!touch()) return;
        this.tokens = Math.max(this.tokens, tokens);
        if (!inTool) phase = Phase.RESPONDING;
    }

    /** The model started writing a tool call. Not counted yet: counting happens when it runs. */
    public synchronized void toolStreaming(String name) {
        if (!touch()) return;
        if (inTool) return;
        toolName = clean(name);
        phase = Phase.TOOL;
    }

    /** A tool call is actually running (ToolHost.dispatch). */
    public synchronized void toolStart(String name) {
        if (!touch()) return;
        toolCalls++;
        inTool = true;
        toolName = clean(name);
        phase = BUILD_TOOLS.contains(toolName) ? Phase.BUILDING : Phase.TOOL;
    }

    /** The running tool call returned; the model gets the result next. */
    public synchronized void toolEnd() {
        if (!touch()) return;
        inTool = false;
        phase = Phase.THINKING;
    }

    /** Another prompt from the same player was accepted while this one is still running. */
    public synchronized void enqueue() {
        if (!isFinishedLocked()) queued++;
    }

    public synchronized int queued() {
        return queued;
    }

    /** The turn ended normally. With prompts waiting behind it, the next one starts counting instead. */
    public synchronized void finish() {
        if (advance()) return;
        end(Phase.DONE, "");
    }

    /**
     * The turn failed (the reason also goes to chat). With prompts waiting behind it the bar stays up
     * until {@link #finish()} hands over to the next one; the backend returns right after failing.
     */
    public synchronized void fail(String why) {
        if (isFinishedLocked() || queued > 0) return;
        end(Phase.FAILED, why);
    }

    private boolean advance() {
        if (isFinishedLocked() || queued <= 0) return false;
        queued--;
        startedAt = clock.getAsLong();
        lastEventAt = startedAt;
        phase = Phase.WAITING;
        tokens = 0;
        toolCalls = 0;
        inTool = false;
        toolName = "";
        return true;
    }

    public synchronized void cancel(String why) {
        end(Phase.CANCELLED, why);
    }

    /** Stops the current prompt only; with prompts waiting behind it the next one takes over via {@link #finish()}. */
    public synchronized void cancelRunning(String why) {
        if (isFinishedLocked() || queued > 0) return;
        end(Phase.CANCELLED, why);
    }

    /** A waiting prompt was removed. The last one removed ends the display. */
    public synchronized void dropQueued(String why) {
        if (isFinishedLocked()) return;
        if (queued > 0) queued--;
        else end(Phase.CANCELLED, why);
    }

    private boolean touch() {
        if (isFinishedLocked()) return false;
        lastEventAt = clock.getAsLong();
        return true;
    }

    private void end(Phase target, String why) {
        if (isFinishedLocked()) return;
        phase = target;
        reason = clean(why);
        finishedAt = clock.getAsLong();
        inTool = false;
    }

    // ---- queries --------------------------------------------------------------------------

    public synchronized boolean isFinished() {
        return isFinishedLocked();
    }

    private boolean isFinishedLocked() {
        return phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.CANCELLED;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized int toolCalls() {
        return toolCalls;
    }

    /** True once a finished state has been on screen long enough to be removed. */
    public synchronized boolean isExpired() {
        if (!isFinishedLocked()) return false;
        long show = phase == Phase.DONE ? DONE_SHOW_MS : FAIL_SHOW_MS;
        return clock.getAsLong() - finishedAt >= show;
    }

    public synchronized View view() {
        long now = clock.getAsLong();
        if (isFinishedLocked()) {
            long sec = Math.max(0, (finishedAt - startedAt) / 1000);
            return switch (phase) {
                case DONE -> new View(phase, Tone.OK, "完了(" + sec + "秒)", 1f, false);
                case FAILED -> new View(phase, Tone.ERROR, withReason("失敗", reason), 1f, false);
                default -> new View(phase, Tone.WARN, withReason("中断", reason), 1f, false);
            };
        }
        long elapsed = Math.max(0, (now - startedAt) / 1000);
        long quiet = Math.max(0, now - lastEventAt);
        float pulse = ((elapsed % 10) + 1) / 10f;
        boolean stalled = !inTool && quiet >= STALL_MS;
        if (stalled) {
            return new View(phase, Tone.WARN, "応答待ち(" + (quiet / 1000) + "秒) 経過" + elapsed + "秒", pulse, true);
        }
        String wait = queued > 0 ? " +待ち" + queued : "";
        String nth = toolCalls > 0 ? toolCalls + "件目" : "";
        return switch (phase) {
            case WAITING -> new View(phase, Tone.QUEUED, "受付: 待機中(" + elapsed + "秒)" + wait, pulse, false);
            case THINKING -> new View(phase, Tone.THINK, "思考中(" + elapsed + "秒)" + wait, pulse, false);
            case RESPONDING -> new View(phase, Tone.WRITE,
                "応答中" + (tokens > 0 ? " 約" + tokens + "トークン" : "") + "(" + elapsed + "秒)" + wait, pulse, false);
            case TOOL -> new View(phase, Tone.TOOL,
                "ツール実行中: " + toolName + (nth.isEmpty() ? "" : " " + nth) + "(" + elapsed + "秒)" + wait, pulse, false);
            default -> new View(phase, Tone.TOOL,
                "建築中: " + toolName + (nth.isEmpty() ? "" : " " + nth) + "(" + elapsed + "秒)" + wait, pulse, false);
        };
    }

    private static String withReason(String head, String why) {
        return why.isEmpty() ? head : head + ": " + why;
    }

    /** One line, short enough for a bar: no newlines, no formatting codes, bounded length. */
    static String clean(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n\\t]+", " ").replace('§', ' ').trim();
        return t.length() <= REASON_MAX ? t : t.substring(0, REASON_MAX - 1) + "…";
    }
}
