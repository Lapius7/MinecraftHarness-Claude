package dev.minecraftharness.progress;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns tool calls into short chat lines. Calls to the same tool in a row are folded into one line
 * plus a count, so a long build does not flood the chat. One instance per prompt; not thread safe on
 * its own, callers lock it.
 */
public final class ToolChat {

    private String last = "";
    private int repeats;

    /** Lines to send for one tool call (empty when it only extends a run of the same tool). */
    public List<String> onTool(String tool, String detail) {
        List<String> out = new ArrayList<>();
        if (tool == null || tool.isBlank()) return out;
        if (tool.equals(last)) {
            repeats++;
            return out;
        }
        String tail = flush();
        if (tail != null) out.add(tail);
        last = tool;
        out.add("🔧 " + tool + (detail == null || detail.isBlank() ? "" : " " + detail));
        return out;
    }

    /** The pending "same tool again" line, or null. Call at the end of a prompt. */
    public String flush() {
        if (repeats == 0) return null;
        String s = "  └ 同じツールをさらに " + repeats + " 回";
        repeats = 0;
        return s;
    }

    /** One short phrase about what the call does, taken from its arguments. */
    public static String describe(JsonObject args) {
        if (args == null) return "";
        List<String> parts = new ArrayList<>();
        JsonElement ops = args.get("ops");
        if (ops != null && ops.isJsonArray()) parts.add(ops.getAsJsonArray().size() + " 操作");
        for (String key : new String[] {"effect", "action", "angle", "view", "level", "anchorId"}) {
            JsonElement e = args.get(key);
            if (e != null && e.isJsonPrimitive()) parts.add(clip(e.getAsString()));
            if (parts.size() >= 2) break;
        }
        return String.join(" / ", parts);
    }

    private static String clip(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 24 ? t : t.substring(0, 23) + "…";
    }
}
