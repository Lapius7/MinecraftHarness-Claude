package dev.minecraftharness.fx;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Renders live AI inference telemetry (phase, TTFT, TPS, token breakdown, reasoning ticker)
 * in-game via an Adventure BossBar + ActionBar, auto-hidden during /mh film on.
 */
public final class TelemetryHud {

    private final BossBar bossBar;
    private boolean hudEnabled = true;
    private boolean reasoningEnabled = true;
    private boolean visible = false;
    private long hideAfterMs = -1L;
    private int tickCount = 0;

    public TelemetryHud() {
        this.bossBar = BossBar.bossBar(
            Component.text("MinecraftHarness AI Telemetry", NamedTextColor.AQUA),
            0.0f,
            BossBar.Color.BLUE,
            BossBar.Overlay.PROGRESS
        );
    }

    public boolean isHudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean enabled) {
        this.hudEnabled = enabled;
        if (!enabled) {
            hideFromAll();
        }
    }

    public boolean isReasoningEnabled() {
        return reasoningEnabled;
    }

    public void setReasoningEnabled(boolean enabled) {
        this.reasoningEnabled = enabled;
    }

    public void update(JsonObject p, boolean filmMode) {
        if (!hudEnabled || filmMode) {
            hideFromAll();
            return;
        }

        String phase = optStr(p, "phase", "IDLE").toUpperCase(Locale.ROOT);
        if ("IDLE".equals(phase)) {
            hideFromAll();
            return;
        }

        int callNo = optInt(p, "callNumber", 1);
        String effort = optStr(p, "reasoningEffort", "low");
        long elapsedMs = optLong(p, "elapsedMs", 0L);
        Long ttftMs = optNullableLong(p, "ttftWallMs");
        Double serverTps = optNullableDouble(p, "serverTps");
        Double liveTps = optNullableDouble(p, "liveTps");
        Long rTok = optNullableLong(p, "reasoningTokens");
        Long oTok = optNullableLong(p, "outputTokens");
        Double cachePct = optNullableDouble(p, "cacheHitPct");
        String snippet = optStr(p, "reasoningSnippet", "");
        String activeTool = optStr(p, "activeTool", "");
        boolean done = optBool(p, "done", false);

        String ttftTxt = ttftMs != null ? fmtMs(ttftMs) : fmtMs(elapsedMs);
        String tpsTxt;
        if (serverTps != null) {
            tpsTxt = String.format(Locale.US, "%.0f tok/s", serverTps);
        } else if (liveTps != null) {
            tpsTxt = String.format(Locale.US, "~%.0f tok/s", liveTps);
        } else {
            tpsTxt = "-";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(callNo == 0 ? "⚡ " : "⚡ #" + callNo + " ").append(phase).append(" (").append(effort).append(")");
        sb.append(ttftMs == null ? " | Elapsed " : " | TTFT ").append(ttftTxt);
        sb.append(" | ").append(tpsTxt);
        int retry = optInt(p, "retryCount", 0);
        if ("QUEUED".equals(phase) && retry > 0) sb.append(" | 再試行 ").append(retry);
        if (cachePct != null) {
            sb.append(" | Cache ").append(String.format(Locale.US, "%.0f%%", cachePct));
        }
        if (rTok != null || oTok != null) {
            sb.append(" | R:").append(rTok != null ? rTok : 0).append(" O:").append(oTok != null ? oTok : 0);
        }

        bossBar.name(Component.text(sb.toString(), colorForText(phase)));
        bossBar.color(colorForBar(phase));
        bossBar.progress(progressForPhase(phase));
        showToAll();

        if (!done) {
            hideAfterMs = -1L;
            String answer = optStr(p, "answerSnippet", "");
            if (!answer.isBlank()) {
                Player recipient = Bukkit.getPlayerExact(optStr(p, "playerName", ""));
                if (recipient != null) recipient.sendActionBar(Component.text(answer, NamedTextColor.WHITE));
            } else if (!activeTool.isBlank()) {
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    pl.sendActionBar(
                        Component.text("🔧 " + activeTool + " ", NamedTextColor.GOLD)
                            .append(Component.text(snippet, NamedTextColor.GRAY))
                    );
                }
            } else if (reasoningEnabled && !snippet.isBlank()) {
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    pl.sendActionBar(
                        Component.text("🧠 ", NamedTextColor.AQUA)
                            .append(Component.text(snippet, NamedTextColor.GRAY))
                    );
                }
            }
        } else {
            hideAfterMs = System.currentTimeMillis() + 12000L;
        }
    }

    public void tick(boolean filmMode) {
        tickCount++;
        if (filmMode || !hudEnabled) {
            if (visible) hideFromAll();
            return;
        }
        if (visible && hideAfterMs > 0 && System.currentTimeMillis() >= hideAfterMs) {
            hideFromAll();
            hideAfterMs = -1L;
            return;
        }
        if (visible && tickCount % 20 == 0) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                pl.showBossBar(bossBar);
            }
        }
    }

    public void cleanup() {
        hideFromAll();
    }

    private void showToAll() {
        visible = true;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.showBossBar(bossBar);
        }
    }

    private void hideFromAll() {
        visible = false;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            pl.hideBossBar(bossBar);
        }
    }

    private static BossBar.Color colorForBar(String phase) {
        return switch (phase) {
            case "QUEUED" -> BossBar.Color.PURPLE;
            case "REASONING" -> BossBar.Color.BLUE;
            case "TOOL_CALLING" -> BossBar.Color.YELLOW;
            case "RESPONDING", "DONE" -> BossBar.Color.GREEN;
            case "ERROR" -> BossBar.Color.RED;
            default -> BossBar.Color.WHITE;
        };
    }

    private static NamedTextColor colorForText(String phase) {
        return switch (phase) {
            case "QUEUED" -> NamedTextColor.LIGHT_PURPLE;
            case "REASONING" -> NamedTextColor.AQUA;
            case "TOOL_CALLING" -> NamedTextColor.GOLD;
            case "RESPONDING", "DONE" -> NamedTextColor.GREEN;
            case "ERROR" -> NamedTextColor.RED;
            default -> NamedTextColor.WHITE;
        };
    }

    private static float progressForPhase(String phase) {
        return switch (phase) {
            case "QUEUED" -> 0.20f;
            case "REASONING" -> 0.55f;
            case "TOOL_CALLING" -> 0.80f;
            case "RESPONDING" -> 0.90f;
            default -> 1.0f;
        };
    }

    private static String fmtMs(long ms) {
        if (ms >= 1000L) {
            return String.format(Locale.US, "%.1fs", ms / 1000.0);
        }
        return ms + "ms";
    }

    private static String optStr(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static int optInt(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
    }

    private static long optLong(JsonObject o, String k, long def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : def;
    }

    private static boolean optBool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }

    private static Long optNullableLong(JsonObject o, String k) {
        if (!o.has(k)) return null;
        JsonElement el = o.get(k);
        return el.isJsonPrimitive() ? el.getAsLong() : null;
    }

    private static Double optNullableDouble(JsonObject o, String k) {
        if (!o.has(k)) return null;
        JsonElement el = o.get(k);
        return el.isJsonPrimitive() ? el.getAsDouble() : null;
    }
}
