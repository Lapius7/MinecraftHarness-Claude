package dev.minecraftharness;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Builds the /mh help screen. Pure: takes plain values, returns Components. */
public final class HelpView {

    public static final String PERM_USE = "minecraftharness.use";
    public static final String PERM_ADMIN = "minecraftharness.admin";

    /** One row. {@code suggest} is what a click puts in the chat box. */
    public record Entry(String category, String usage, String suggest, String description,
                        String detail, String example, String permission) {}

    /** Values shown in the status block. */
    public record Status(String auth, String model, String effort, String session) {}

    public static final List<String> CATEGORIES = List.of("建築", "モデル", "セッション", "演出", "管理");
    /** Categories shown on each page. */
    private static final List<List<String>> PAGES = List.of(
        List.of("建築"), List.of("モデル", "セッション"), List.of("演出", "管理"));

    /** Rows should fit the 320px default chat width: 53 half-width columns, full-width counts as 2. */
    public static final int MAX_COLUMNS = 52;

    private static final List<Entry> ENTRIES = List.of(
        new Entry("建築", "!<依頼>", "!", "Claude に建築を頼む",
            "! か /mh に続けて依頼を書くと、視線の先に建てます。", "!屋根に煙突のある木の家を建てて", PERM_USE),
        new Entry("建築", "/mh undo", "/mh undo", "最後の建築を戻す",
            "直前の建築で置いたブロックを元に戻します。", "/mh undo", PERM_USE),
        new Entry("建築", "/mh shift <上下>", "/mh shift ", "建築物を動かす",
            "直前の建築を動かします。数値 1 つで上下、3 つで 右・上・前 の順です。", "/mh shift -3", PERM_USE),
        new Entry("建築", "/mh speed", "/mh speed ", "建築の速さ",
            "turbo / fast / cinematic から選びます。", "/mh speed fast", PERM_USE),
        new Entry("建築", "/mh stop", "/mh stop", "実行中の依頼を止める",
            "実行中の応答、建築、演出を止めます。待ちは残ります。all で全消し。", "/mh stop all", PERM_USE),
        new Entry("建築", "!now", "!now ", "割り込んで先に実行",
            "実行中の依頼を止めて、この指示を先に実行します。", "!now 屋根を丸くして", PERM_USE),
        new Entry("建築", "/mh queue", "/mh queue", "依頼の順番を見る",
            "実行中と待ちの一覧。clear で自分の待ちを消します。", "/mh queue clear", PERM_USE),

        new Entry("モデル", "/mh model", "/mh model", "モデルの確認と切替",
            "クリック式の一覧を開きます。切替は admin 権限が必要です。", "/mh model cli/opus", PERM_USE),
        new Entry("モデル", "/mh effort", "/mh effort", "推論の強さ",
            "low / medium / high / xhigh / max。変更は admin 権限が必要です。サブスク(CLI)と API の両方に効きます。",
            "/mh effort high", PERM_USE),
        new Entry("モデル", "/mh key anthropic", "/mh key anthropic ", "API キーの設定",
            "コンソールからの実行を勧めます。チャットで打つとログにキーが残ります。", "mh key anthropic sk-ant-...", PERM_ADMIN),

        new Entry("セッション", "!new", "!new", "新しい会話にする",
            "いまの会話を捨てて、次の依頼から新しく始めます。", "!new", PERM_USE),
        new Entry("セッション", "/mh claude clear", "/mh claude clear", "会話を破棄",
            "!new と同じです。claude CLI のセッションも作り直します。", "/mh claude clear", PERM_USE),
        new Entry("セッション", "/mh session", "/mh session ", "会話の一覧と切替",
            "list で一覧、use <名前> で切り替えます。", "/mh session use 家", PERM_USE),
        new Entry("セッション", "/mh history reset", "/mh history reset", "履歴を消す",
            "いまの会話の履歴を消します。", "/mh history reset", PERM_USE),

        new Entry("演出", "/mh film", "/mh film ", "録画向けの表示",
            "チャットや HUD を抑えます。on / off。", "/mh film on", PERM_USE),
        new Entry("演出", "/mh progress", "/mh progress ", "進捗の表示先",
            "bossbar / actionbar / off。送信後の状態と経過秒を出します。", "/mh progress actionbar", PERM_USE),
        new Entry("演出", "/mh hud", "/mh hud ", "詳細 HUD",
            "全員向けの詳細表示(既定は off)。on / off、reasoning で思考ティッカー。", "/mh hud on", PERM_USE),
        new Entry("演出", "/mh fx", "/mh fx ", "VFX と効果音",
            "on / off、sound on / off。", "/mh fx sound off", PERM_USE),
        new Entry("演出", "/mh cam", "/mh cam ", "カメラ演出",
            "orbit / dolly / off。スニークでも止まります。", "/mh cam orbit", PERM_USE),

        new Entry("管理", "/mh status", "/mh status", "状態を見る",
            "モデル、effort、建築速度、残りブロック数を表示します。", "/mh status", PERM_USE),
        new Entry("管理", "/mh reset", "/mh reset", "すべての建築を戻す",
            "このセッションの建築をすべて元に戻します。", "/mh reset", PERM_ADMIN)
    );

    private HelpView() {}

    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static int pageCount() {
        return PAGES.size();
    }

    /** Half-width columns of a string: full-width characters count as 2. */
    public static int columns(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (c < 0x80 || (c >= 0xFF61 && c <= 0xFF9F)) ? 1 : 2;
        }
        return n;
    }

    /** Resolves "2", "モデル" or "model" to a page number (1-based); 0 when it does not match. */
    public static int resolvePage(String arg) {
        if (arg == null || arg.isBlank()) return 1;
        String a = arg.trim().toLowerCase(Locale.ROOT);
        try {
            int n = Integer.parseInt(a);
            return n >= 1 && n <= PAGES.size() ? n : 0;
        } catch (NumberFormatException ignored) {
            // fall through to category names
        }
        for (int i = 0; i < PAGES.size(); i++) {
            for (String c : PAGES.get(i)) {
                if (c.equals(arg.trim()) || categoryAlias(c).equals(a)) return i + 1;
            }
        }
        return 0;
    }

    private static String categoryAlias(String c) {
        return switch (c) {
            case "建築" -> "build";
            case "モデル" -> "model";
            case "セッション" -> "session";
            case "演出" -> "fx";
            default -> "admin";
        };
    }

    public static List<String> pageCompletions() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= PAGES.size(); i++) out.add(String.valueOf(i));
        for (String c : CATEGORIES) out.add(categoryAlias(c));
        return out;
    }

    /** The whole screen for one page. {@code has} tells whether the viewer holds a permission. */
    public static List<Component> render(String version, Status status, int page, java.util.function.Predicate<String> has) {
        int p = Math.max(1, Math.min(PAGES.size(), page));
        List<Component> out = new ArrayList<>();
        out.add(banner(version));
        if (p == 1) out.addAll(statusBlock(status));
        for (String cat : PAGES.get(p - 1)) {
            out.add(Component.text("▍" + cat, NamedTextColor.DARK_AQUA, TextDecoration.BOLD));
            for (Entry e : ENTRIES) {
                if (e.category().equals(cat)) out.add(row(e, has.test(e.permission())));
            }
        }
        out.add(footer(p));
        return out;
    }

    static Component banner(String version) {
        return Component.text("━━ ", NamedTextColor.DARK_GRAY)
            .append(Component.text("MinecraftHarness", NamedTextColor.AQUA, TextDecoration.BOLD))
            .append(Component.text(" v" + version, NamedTextColor.GRAY))
            .append(Component.text(" ━━", NamedTextColor.DARK_GRAY));
    }

    static List<Component> statusBlock(Status s) {
        List<Component> out = new ArrayList<>();
        out.add(kv("認証", s.auth()));
        out.add(kv("モデル", s.model()));
        out.add(kv("effort", s.effort()));
        out.add(kv("会話", s.session()));
        return out;
    }

    private static Component kv(String key, String value) {
        return Component.text(" " + key + " ", NamedTextColor.GRAY)
            .append(Component.text(value, NamedTextColor.WHITE));
    }

    static Component row(Entry e, boolean allowed) {
        NamedTextColor cmdColor = allowed ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY;
        NamedTextColor descColor = allowed ? NamedTextColor.GRAY : NamedTextColor.DARK_GRAY;
        Component hover = Component.text(e.usage(), NamedTextColor.AQUA, TextDecoration.BOLD)
            .append(Component.newline()).append(Component.text(e.detail(), NamedTextColor.GRAY))
            .append(Component.newline()).append(Component.text("例: ", NamedTextColor.DARK_GRAY))
            .append(Component.text(e.example(), NamedTextColor.WHITE))
            .append(Component.newline()).append(Component.text("権限: " + e.permission(), allowed ? NamedTextColor.GREEN : NamedTextColor.RED))
            .append(Component.text(allowed ? "" : "（持っていません）", NamedTextColor.RED));
        Component line = Component.text(" " + e.usage(), cmdColor)
            .append(Component.text(" — " + e.description(), descColor));
        line = line.hoverEvent(HoverEvent.showText(hover));
        if (allowed) line = line.clickEvent(ClickEvent.suggestCommand(e.suggest()));
        return line;
    }

    static Component footer(int page) {
        Component prev = page > 1
            ? Component.text("[◀ 前]", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/mh help " + (page - 1)))
            : Component.text("[◀ 前]", NamedTextColor.DARK_GRAY);
        Component next = page < PAGES.size()
            ? Component.text("[次 ▶]", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/mh help " + (page + 1)))
            : Component.text("[次 ▶]", NamedTextColor.DARK_GRAY);
        return Component.text(" ", NamedTextColor.GRAY).append(prev)
            .append(Component.text(" " + page + "/" + PAGES.size() + " ", NamedTextColor.GRAY))
            .append(next)
            .append(Component.text("  行をクリックで入力欄に入ります", NamedTextColor.DARK_GRAY));
    }
}
