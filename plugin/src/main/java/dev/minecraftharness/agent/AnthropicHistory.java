package dev.minecraftharness.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Conversation history for the Messages API. Stores raw content blocks and guarantees the
 * invariant the API enforces: every tool_use in an assistant message is answered by a
 * tool_result in the very next user message.
 */
public final class AnthropicHistory {

    private static final int MAX_MESSAGES = 24;
    private static final int KEEP_RECENT = 6;

    private final List<JsonObject> messages = new ArrayList<>();

    public int size() {
        return messages.size();
    }

    public void addUserText(String text) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        JsonArray content = new JsonArray();
        content.add(textBlock(text));
        m.add("content", content);
        messages.add(m);
    }

    public void addAssistant(JsonArray content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "assistant");
        m.add("content", content.deepCopy());
        messages.add(m);
    }

    public void addAssistantText(String text) {
        JsonArray content = new JsonArray();
        content.add(textBlock(text));
        addAssistant(content);
    }

    /** One tool_result for a tool_use id; {@code base64Png} may be null. */
    public static JsonObject toolResult(String toolUseId, String text, boolean isError, String base64Png) {
        JsonObject r = new JsonObject();
        r.addProperty("type", "tool_result");
        r.addProperty("tool_use_id", toolUseId);
        JsonArray content = new JsonArray();
        content.add(textBlock(text));
        if (base64Png != null && !base64Png.isEmpty()) {
            JsonObject img = new JsonObject();
            img.addProperty("type", "image");
            JsonObject src = new JsonObject();
            src.addProperty("type", "base64");
            src.addProperty("media_type", "image/png");
            src.addProperty("data", base64Png);
            img.add("source", src);
            content.add(img);
        }
        r.add("content", content);
        if (isError) r.addProperty("is_error", true);
        return r;
    }

    public void addToolResults(List<JsonObject> results) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        JsonArray content = new JsonArray();
        results.forEach(content::add);
        m.add("content", content);
        messages.add(m);
    }

    /**
     * Makes the history valid after an aborted or failed turn: a trailing assistant message
     * whose tool_use blocks never got results receives synthetic error results.
     */
    public void closePendingToolUses(String reason) {
        if (messages.isEmpty()) return;
        JsonObject last = messages.get(messages.size() - 1);
        if (!"assistant".equals(role(last))) return;
        List<JsonObject> results = new ArrayList<>();
        for (String id : toolUseIds(last)) {
            results.add(toolResult(id, reason, true, null));
        }
        if (!results.isEmpty()) addToolResults(results);
    }

    /** Removes everything added since {@code size} (used to roll back a failed first call). */
    public void truncateTo(int size) {
        while (messages.size() > size) messages.remove(messages.size() - 1);
    }

    /** @return null when valid, otherwise a description of the first problem. */
    public String validate() {
        for (int i = 0; i < messages.size(); i++) {
            JsonObject m = messages.get(i);
            List<String> ids = "assistant".equals(role(m)) ? toolUseIds(m) : List.of();
            if (!ids.isEmpty()) {
                if (i + 1 >= messages.size()) return "assistant message " + i + " has unanswered tool_use";
                Set<String> answered = resultIds(messages.get(i + 1));
                for (String id : ids) {
                    if (!answered.contains(id)) return "tool_use " + id + " has no tool_result";
                }
            }
            if ("user".equals(role(m))) {
                Set<String> answered = resultIds(m);
                if (!answered.isEmpty()) {
                    if (i == 0 || !"assistant".equals(role(messages.get(i - 1)))) return "tool_result without preceding tool_use";
                    List<String> prev = toolUseIds(messages.get(i - 1));
                    for (String id : answered) {
                        if (!prev.contains(id)) return "tool_result for unknown id " + id;
                    }
                }
            }
        }
        return null;
    }

    /** Trims old turns on a user-text boundary and shrinks stale tool output and screenshots. */
    public void prune() {
        if (messages.size() > MAX_MESSAGES) {
            int cut = messages.size() - MAX_MESSAGES;
            while (cut < messages.size() && !isPlainUserTurn(messages.get(cut))) cut++;
            if (cut > 0 && cut < messages.size()) messages.subList(0, cut).clear();
        }
        int limit = Math.max(0, messages.size() - KEEP_RECENT);
        for (int i = 0; i < limit; i++) {
            JsonObject m = messages.get(i);
            if (!"user".equals(role(m))) continue;
            for (JsonElement el : m.getAsJsonArray("content")) {
                if (!el.isJsonObject()) continue;
                JsonObject block = el.getAsJsonObject();
                if (!"tool_result".equals(str(block, "type")) || !block.has("content") || !block.get("content").isJsonArray()) continue;
                JsonArray inner = block.getAsJsonArray("content");
                for (int k = 0; k < inner.size(); k++) {
                    JsonObject part = inner.get(k).getAsJsonObject();
                    String t = str(part, "type");
                    if ("image".equals(t)) {
                        inner.set(k, textBlock("[Previous screenshot omitted]"));
                    } else if ("text".equals(t)) {
                        String s = str(part, "text");
                        if (s.length() > 1600) part.addProperty("text", s.substring(0, 1400) + "...(truncated)");
                    }
                }
            }
        }
    }

    /**
     * Messages for the next request, with a cache breakpoint on the last block so the whole
     * conversation so far is read from the prompt cache on the following call.
     */
    public JsonArray toRequestMessages() {
        JsonArray out = new JsonArray();
        for (JsonObject m : messages) out.add(m.deepCopy());
        if (!out.isEmpty()) {
            JsonArray content = out.get(out.size() - 1).getAsJsonObject().getAsJsonArray("content");
            if (!content.isEmpty()) {
                JsonObject last = content.get(content.size() - 1).getAsJsonObject();
                String t = str(last, "type");
                if (!"thinking".equals(t) && !"redacted_thinking".equals(t)) {
                    JsonObject cc = new JsonObject();
                    cc.addProperty("type", "ephemeral");
                    last.add("cache_control", cc);
                }
            }
        }
        return out;
    }

    // ---- helpers --------------------------------------------------------------------------

    private static boolean isPlainUserTurn(JsonObject m) {
        return "user".equals(role(m)) && resultIds(m).isEmpty();
    }

    private static List<String> toolUseIds(JsonObject m) {
        List<String> ids = new ArrayList<>();
        for (JsonElement el : m.getAsJsonArray("content")) {
            if (el.isJsonObject() && "tool_use".equals(str(el.getAsJsonObject(), "type"))) {
                ids.add(str(el.getAsJsonObject(), "id"));
            }
        }
        return ids;
    }

    private static Set<String> resultIds(JsonObject m) {
        Set<String> ids = new HashSet<>();
        if (!"user".equals(role(m))) return ids;
        for (JsonElement el : m.getAsJsonArray("content")) {
            if (el.isJsonObject() && "tool_result".equals(str(el.getAsJsonObject(), "type"))) {
                ids.add(str(el.getAsJsonObject(), "tool_use_id"));
            }
        }
        return ids;
    }

    private static JsonObject textBlock(String text) {
        JsonObject b = new JsonObject();
        b.addProperty("type", "text");
        b.addProperty("text", text);
        return b;
    }

    private static String role(JsonObject m) {
        return str(m, "role");
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
