package dev.minecraftharness.fx;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders CommonMark / GFM-style Markdown into PaperMC Adventure Components
 * for clean, readable in-game chat output (zero external dependencies).
 */
public final class MarkdownChatRenderer {

    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.+)$");
    private static final Pattern UNORDERED_LIST = Pattern.compile("^(\\s*)[-*+]\\s+(.+)$");
    private static final Pattern ORDERED_LIST = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.+)$");
    private static final Pattern HRULE = Pattern.compile("^\\s*([-*_])(?:\\s*\\1){2,}\\s*$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?(?:\\s*:?-+:?\\s*\\|)+\\s*:?-+:?\\s*\\|?\\s*$");

    private MarkdownChatRenderer() {}

    public static Component render(String prefixTag, String markdown) {
        TextComponent.Builder out = Component.text();
        if (prefixTag != null && !prefixTag.isEmpty()) {
            out.append(Component.text(prefixTag, NamedTextColor.GRAY));
        }
        if (markdown == null || markdown.isEmpty()) {
            return out.build();
        }

        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inCodeBlock = false;
        boolean firstLine = true;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();

            if (!firstLine) {
                out.append(Component.newline());
            }
            firstLine = false;

            if (trimmed.startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    String lang = trimmed.substring(3).trim();
                    out.append(Component.text(lang.isEmpty() ? "┌──────" : "┌ " + lang, NamedTextColor.DARK_GRAY));
                } else {
                    inCodeBlock = false;
                    out.append(Component.text("└──────", NamedTextColor.DARK_GRAY));
                }
                continue;
            }

            if (inCodeBlock) {
                out.append(Component.text("│ ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(line, NamedTextColor.GRAY));
                continue;
            }

            if (trimmed.isEmpty()) {
                continue;
            }

            if (HRULE.matcher(line).matches()) {
                out.append(Component.text("────────────", NamedTextColor.DARK_GRAY));
                continue;
            }

            if (TABLE_SEP.matcher(line).matches()) {
                out.append(Component.text("────────────", NamedTextColor.DARK_GRAY));
                continue;
            }

            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() >= 3) {
                String inner = trimmed.substring(1, trimmed.length() - 1);
                String[] cells = inner.split("\\|");
                for (int c = 0; c < cells.length; c++) {
                    if (c > 0) out.append(Component.text(" │ ", NamedTextColor.DARK_GRAY));
                    out.append(parseInline(cells[c].trim(), Style.style(NamedTextColor.WHITE)));
                }
                continue;
            }

            Matcher hm = HEADING.matcher(trimmed);
            if (hm.matches()) {
                int level = hm.group(1).length();
                TextColor color = switch (level) {
                    case 1 -> NamedTextColor.GOLD;
                    case 2 -> NamedTextColor.AQUA;
                    default -> NamedTextColor.YELLOW;
                };
                out.append(parseInline(hm.group(2).trim(), Style.style(color, TextDecoration.BOLD)));
                continue;
            }

            if (trimmed.startsWith(">")) {
                String quoteText = trimmed.substring(1).trim();
                out.append(Component.text("│ ", NamedTextColor.DARK_GRAY))
                    .append(parseInline(quoteText, Style.style(NamedTextColor.GRAY, TextDecoration.ITALIC)));
                continue;
            }

            Matcher um = UNORDERED_LIST.matcher(line);
            if (um.matches()) {
                String indent = " ".repeat(Math.min(6, um.group(1).length()));
                out.append(Component.text(indent + "• ", NamedTextColor.AQUA))
                    .append(parseInline(um.group(2), Style.style(NamedTextColor.WHITE)));
                continue;
            }

            Matcher om = ORDERED_LIST.matcher(line);
            if (om.matches()) {
                String indent = " ".repeat(Math.min(6, om.group(1).length()));
                out.append(Component.text(indent + om.group(2) + ". ", NamedTextColor.AQUA))
                    .append(parseInline(om.group(3), Style.style(NamedTextColor.WHITE)));
                continue;
            }

            out.append(parseInline(line, Style.style(NamedTextColor.WHITE)));
        }

        return out.build();
    }

    private static Component parseInline(String text, Style baseStyle) {
        TextComponent.Builder out = Component.text();
        StringBuilder plain = new StringBuilder();
        int len = text.length();
        int i = 0;

        while (i < len) {
            char ch = text.charAt(i);

            // Backslash escape for Markdown syntax chars
            if (ch == '\\' && i + 1 < len && "\\`*_{}[]()#+-.!~|".indexOf(text.charAt(i + 1)) >= 0) {
                plain.append(text.charAt(i + 1));
                i += 2;
                continue;
            }

            // Inline code: `code`
            if (ch == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i + 1) {
                    flushPlain(out, plain, baseStyle);
                    String code = text.substring(i + 1, end);
                    out.append(Component.text(code, NamedTextColor.YELLOW));
                    i = end + 1;
                    continue;
                }
            }

            // Link: [label](https://...)
            if (ch == '[') {
                int closeBracket = text.indexOf(']', i + 1);
                if (closeBracket > i + 1 && closeBracket + 1 < len && text.charAt(closeBracket + 1) == '(') {
                    int closeParen = text.indexOf(')', closeBracket + 2);
                    if (closeParen > closeBracket + 2) {
                        String label = text.substring(i + 1, closeBracket);
                        String url = text.substring(closeBracket + 2, closeParen).trim();
                        if (url.startsWith("https://") || url.startsWith("http://")) {
                            flushPlain(out, plain, baseStyle);
                            Component linkComp = Component.text(label, NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(url))
                                .hoverEvent(HoverEvent.showText(Component.text(url, NamedTextColor.GRAY)));
                            out.append(linkComp);
                            i = closeParen + 1;
                            continue;
                        }
                    }
                }
            }

            // Bold + Italic: ***text***
            if (i + 2 < len && ch == '*' && text.charAt(i + 1) == '*' && text.charAt(i + 2) == '*') {
                int end = text.indexOf("***", i + 3);
                if (end > i + 3) {
                    flushPlain(out, plain, baseStyle);
                    Style next = baseStyle.decorate(TextDecoration.BOLD).decorate(TextDecoration.ITALIC);
                    out.append(parseInline(text.substring(i + 3, end), next));
                    i = end + 3;
                    continue;
                }
            }

            // Bold: **text** or __text__
            if (i + 1 < len && ((ch == '*' && text.charAt(i + 1) == '*') || (ch == '_' && text.charAt(i + 1) == '_'))) {
                String delim = ch == '*' ? "**" : "__";
                int end = text.indexOf(delim, i + 2);
                if (end > i + 2) {
                    flushPlain(out, plain, baseStyle);
                    Style next = baseStyle.decorate(TextDecoration.BOLD);
                    out.append(parseInline(text.substring(i + 2, end), next));
                    i = end + 2;
                    continue;
                }
            }

            // Strikethrough: ~~text~~
            if (i + 1 < len && ch == '~' && text.charAt(i + 1) == '~') {
                int end = text.indexOf("~~", i + 2);
                if (end > i + 2) {
                    flushPlain(out, plain, baseStyle);
                    Style next = baseStyle.color(NamedTextColor.GRAY).decorate(TextDecoration.STRIKETHROUGH);
                    out.append(parseInline(text.substring(i + 2, end), next));
                    i = end + 2;
                    continue;
                }
            }

            // Italic: *text* (avoid matching standalone math like "2 * 3")
            if (ch == '*' && i + 1 < len && !Character.isWhitespace(text.charAt(i + 1))) {
                int end = findSingleStarEnd(text, i + 1);
                if (end > i + 1) {
                    flushPlain(out, plain, baseStyle);
                    Style next = baseStyle.decorate(TextDecoration.ITALIC);
                    out.append(parseInline(text.substring(i + 1, end), next));
                    i = end + 1;
                    continue;
                }
            }

            plain.append(ch);
            i++;
        }

        flushPlain(out, plain, baseStyle);
        return out.build();
    }

    private static int findSingleStarEnd(String text, int from) {
        for (int idx = from; idx < text.length(); idx++) {
            if (text.charAt(idx) == '*') {
                if (idx > from && !Character.isWhitespace(text.charAt(idx - 1))) {
                    // Don't stop on the first char of "**"
                    if (idx + 1 < text.length() && text.charAt(idx + 1) == '*') {
                        idx++;
                        continue;
                    }
                    return idx;
                }
            }
        }
        return -1;
    }

    private static void flushPlain(TextComponent.Builder out, StringBuilder plain, Style style) {
        if (!plain.isEmpty()) {
            out.append(Component.text(plain.toString(), style));
            plain.setLength(0);
        }
    }
}
