package dev.minecraftharness.summary;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the result summary shown at the end of a prompt. Lines whose value is unknown are left out
 * (short) or shown as "-" (full), so nothing is made up.
 */
public final class SummaryRenderer {

    private SummaryRenderer() {}

    /** @return null when the level is OFF. */
    public static Component render(DetailLevel level, TurnStats s, Double costUsd) {
        if (level == DetailLevel.OFF) return null;
        Component shortLine = shortLine(s, costUsd);
        if (level == DetailLevel.SHORT) {
            return shortLine.hoverEvent(HoverEvent.showText(fullBlock(s, costUsd)));
        }
        return fullBlock(s, costUsd);
    }

    public static String seconds(long ms) {
        if (ms < 0) return "-";
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1f秒", ms / 1000.0);
        return (ms / 60_000) + "分" + String.format(Locale.ROOT, "%02d", (ms % 60_000) / 1000) + "秒";
    }

    public static String count(long n) {
        return n < 0 ? "-" : String.format(Locale.ROOT, "%,d", n);
    }

    static Component shortLine(TurnStats s, Double costUsd) {
        List<String> parts = new ArrayList<>();
        TurnStats.Timing t = s.timing();
        if (t != null) parts.add(seconds(t.totalMs()));
        if (s.tokens().known()) {
            parts.add("入力 " + count(s.tokens().input() + s.tokens().cacheRead() + s.tokens().cacheWrite())
                + " / 出力 " + count(s.tokens().output()) + " トークン");
        }
        if (s.blocks() >= 0) parts.add(count(s.blocks()) + " ブロック");
        if (!s.anchors().isEmpty()) parts.add(String.join(",", s.anchors()));
        TurnStats.Review r = s.review();
        if (r != null && r.rounds() > 0) parts.add("レビュー " + r.rounds() + " 回"
            + (r.verdict() == null || r.verdict().isBlank() ? "" : " " + r.verdict()));
        if (costUsd != null) parts.add(money(costUsd));
        if (parts.isEmpty()) parts.add("数値は取得できませんでした");
        return Component.text("📊 ", NamedTextColor.AQUA)
            .append(Component.text(String.join(" · ", parts), NamedTextColor.GRAY))
            .append(Component.text("  (ホバーで詳細)", NamedTextColor.DARK_GRAY));
    }

    static String money(double usd) {
        return String.format(Locale.ROOT, "約 $%.4f", usd);
    }

    static Component fullBlock(TurnStats s, Double costUsd) {
        Component out = Component.text("📊 結果の詳細", NamedTextColor.AQUA);
        TurnStats.Timing t = s.timing();
        out = line(out, "総所要時間", t == null ? "-" : seconds(t.totalMs()));
        if (t != null) {
            out = line(out, "  内訳", "待ち " + seconds(t.waitMs()) + " / 思考 " + seconds(t.thinkMs())
                + " / ツール " + seconds(t.toolMs()) + " / 建築 " + seconds(t.buildMs()) + " / レビュー " + seconds(t.reviewMs()));
        }
        TurnStats.Tokens k = s.tokens();
        if (k.known()) {
            out = line(out, "トークン", "入力 " + count(k.input()) + " / 出力 " + count(k.output())
                + " / キャッシュ読み " + count(k.cacheRead()) + " / キャッシュ書き " + count(k.cacheWrite())
                + " / 思考 " + (k.thinking() >= 0 ? count(k.thinking()) : "-(出力に含まれ、内訳は取得できません)"));
        } else {
            out = line(out, "トークン", "-(取得できませんでした)");
        }
        out = line(out, "モデル", blankDash(s.model()) + " (effort " + blankDash(s.effort()) + ")");
        out = line(out, "認証", blankDash(s.auth()));
        out = line(out, "ツール呼び出し", s.toolCalls() + " 回");
        out = line(out, "置いたブロック", s.blocks() >= 0 ? count(s.blocks()) + "(キューに入れた数)" : "-");
        out = line(out, "建築 ID", s.anchors().isEmpty() ? "-" : String.join(", ", s.anchors()));
        TurnStats.Review r = s.review();
        if (r != null) {
            out = line(out, "レビュー", reviewLine(r));
            for (TurnStats.Round rd : r.roundList()) out = line(out, "  " + rd.number() + " 回目", roundLine(rd));
            for (String f : r.fixes()) out = line(out, "  直した内容", f);
        }
        if (costUsd != null) out = line(out, "概算料金", money(costUsd));
        return out;
    }

    /** "2 回点検 / 判定: 通過(問題なし) / 見つけた問題 3 / 残り 0". */
    static String reviewLine(TurnStats.Review r) {
        String verdict = r.verdict() == null || r.verdict().isBlank() ? "-" : r.verdict();
        String reason = r.stop() == null || r.stop().isBlank() ? "" : "(" + r.stop() + ")";
        return r.rounds() + " 回点検 / 判定: " + verdict + reason
            + " / 見つけた問題 " + r.found() + " / 残り " + r.remaining();
    }

    static String roundLine(TurnStats.Round rd) {
        return "見つけた " + rd.found() + " 件 / 直した " + rd.fixed() + " 件 / 残り " + rd.remaining() + " 件"
            + (rd.undone() ? "(悪化したので元に戻した)" : "");
    }

    private static String blankDash(String v) {
        return v == null || v.isBlank() ? "-" : v;
    }

    private static Component line(Component acc, String label, String value) {
        return acc.append(Component.newline())
            .append(Component.text(label + ": ", NamedTextColor.GRAY))
            .append(Component.text(value, NamedTextColor.WHITE));
    }
}
