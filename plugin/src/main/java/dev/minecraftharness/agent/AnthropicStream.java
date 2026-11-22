package dev.minecraftharness.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Rebuilds one assistant message from Messages API stream events. Keeps the raw content blocks
 * (text, thinking with its signature, redacted_thinking, tool_use) so they can be sent back
 * unchanged in the next request.
 */
public final class AnthropicStream {

    /** Receives live deltas; all methods default to no-ops. */
    public interface Listener {
        default void onTextDelta(String text) {}
        default void onThinkingDelta(String text) {}
        /** First sight of a tool_use block. */
        default void onToolStart(int index, String id, String name) {}
        /** A raw chunk of tool input JSON. */
        default void onToolInputDelta(int index, String name, String chunk) {}
    }

    public record ToolUse(String id, String name, JsonObject input, boolean inputValid) {}

    public record Usage(int inputTokens, int cacheReadTokens, int cacheCreationTokens, int outputTokens) {
        public int promptTotal() {
            return inputTokens + cacheReadTokens + cacheCreationTokens;
        }
    }

    private static final class Block {
        String type;
        JsonObject raw;
        final StringBuilder text = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final StringBuilder signature = new StringBuilder();
        final StringBuilder json = new StringBuilder();
        boolean inputValid = true;
        boolean stopped;
    }

    private final Listener listener;
    private final TreeMap<Integer, Block> blocks = new TreeMap<>();
    private String stopReason;
    private String errorType;
    private String errorMessage;
    private int inputTokens;
    private int cacheRead;
    private int cacheCreation;
    private int outputTokens;
    private boolean messageStopped;

    public AnthropicStream(Listener listener) {
        this.listener = listener == null ? new Listener() {} : listener;
    }

    public void accept(String eventName, String data) {
        JsonObject e;
        try {
            JsonElement parsed = JsonParser.parseString(data);
            if (!parsed.isJsonObject()) return;
            e = parsed.getAsJsonObject();
        } catch (Exception ex) {
            return;
        }
        String type = str(e, "type");
        if (type.isEmpty()) type = eventName == null ? "" : eventName;
        switch (type) {
            case "message_start" -> {
                JsonObject msg = e.has("message") && e.get("message").isJsonObject() ? e.getAsJsonObject("message") : null;
                if (msg != null && msg.has("usage") && msg.get("usage").isJsonObject()) mergeUsage(msg.getAsJsonObject("usage"));
            }
            case "content_block_start" -> {
                int idx = e.has("index") ? e.get("index").getAsInt() : blocks.size();
                JsonObject cb = e.has("content_block") && e.get("content_block").isJsonObject()
                    ? e.getAsJsonObject("content_block").deepCopy() : new JsonObject();
                Block b = new Block();
                b.type = str(cb, "type");
                b.raw = cb;
                if ("text".equals(b.type)) b.text.append(str(cb, "text"));
                if ("thinking".equals(b.type)) {
                    b.thinking.append(str(cb, "thinking"));
                    b.signature.append(str(cb, "signature"));
                }
                blocks.put(idx, b);
                if ("tool_use".equals(b.type)) listener.onToolStart(idx, str(cb, "id"), str(cb, "name"));
            }
            case "content_block_delta" -> {
                int idx = e.has("index") ? e.get("index").getAsInt() : -1;
                Block b = blocks.get(idx);
                JsonObject d = e.has("delta") && e.get("delta").isJsonObject() ? e.getAsJsonObject("delta") : null;
                if (b == null || d == null) return;
                switch (str(d, "type")) {
                    case "text_delta" -> {
                        String t = str(d, "text");
                        b.text.append(t);
                        if (!t.isEmpty()) listener.onTextDelta(t);
                    }
                    case "thinking_delta" -> {
                        String t = str(d, "thinking");
                        b.thinking.append(t);
                        if (!t.isEmpty()) listener.onThinkingDelta(t);
                    }
                    case "signature_delta" -> b.signature.append(str(d, "signature"));
                    case "input_json_delta" -> {
                        String chunk = str(d, "partial_json");
                        b.json.append(chunk);
                        if (!chunk.isEmpty()) listener.onToolInputDelta(idx, str(b.raw, "name"), chunk);
                    }
                    default -> { }
                }
            }
            case "content_block_stop" -> {
                Block b = blocks.get(e.has("index") ? e.get("index").getAsInt() : -1);
                if (b != null) finishBlock(b);
            }
            case "message_delta" -> {
                if (e.has("delta") && e.get("delta").isJsonObject()) {
                    String sr = str(e.getAsJsonObject("delta"), "stop_reason");
                    if (!sr.isEmpty()) stopReason = sr;
                }
                if (e.has("usage") && e.get("usage").isJsonObject()) mergeUsage(e.getAsJsonObject("usage"));
            }
            case "message_stop" -> messageStopped = true;
            case "error" -> {
                JsonObject err = e.has("error") && e.get("error").isJsonObject() ? e.getAsJsonObject("error") : e;
                errorType = str(err, "type");
                errorMessage = str(err, "message");
                if (errorType.isEmpty()) errorType = "error";
            }
            default -> { }
        }
    }

    private void finishBlock(Block b) {
        if (b.stopped) return;
        b.stopped = true;
        if ("tool_use".equals(b.type)) {
            String raw = b.json.toString().trim();
            if (raw.isEmpty()) {
                b.raw.add("input", new JsonObject());
            } else {
                try {
                    JsonElement el = JsonParser.parseString(raw);
                    if (el.isJsonObject()) {
                        b.raw.add("input", el);
                    } else {
                        b.raw.add("input", new JsonObject());
                        b.inputValid = false;
                    }
                } catch (Exception ex) {
                    b.raw.add("input", new JsonObject());
                    b.inputValid = false;
                }
            }
        } else if ("text".equals(b.type)) {
            b.raw.addProperty("text", b.text.toString());
        } else if ("thinking".equals(b.type)) {
            b.raw.addProperty("thinking", b.thinking.toString());
            b.raw.addProperty("signature", b.signature.toString());
        }
    }

    private void mergeUsage(JsonObject u) {
        if (u.has("input_tokens")) inputTokens = u.get("input_tokens").getAsInt();
        if (u.has("cache_read_input_tokens") && !u.get("cache_read_input_tokens").isJsonNull()) cacheRead = u.get("cache_read_input_tokens").getAsInt();
        if (u.has("cache_creation_input_tokens") && !u.get("cache_creation_input_tokens").isJsonNull()) cacheCreation = u.get("cache_creation_input_tokens").getAsInt();
        if (u.has("output_tokens")) outputTokens = u.get("output_tokens").getAsInt();
    }

    // ---- results --------------------------------------------------------------------------

    public boolean hasError() {
        return errorType != null;
    }

    public String errorType() {
        return errorType;
    }

    public String errorMessage() {
        return errorMessage;
    }

    /** True once message_stop arrived; false means the connection ended early. */
    public boolean complete() {
        if (!messageStopped || errorType != null) return false;
        for (Block b : blocks.values()) {
            if (!b.stopped) return false;
        }
        return true;
    }

    public String stopReason() {
        return stopReason;
    }

    public Usage usage() {
        return new Usage(inputTokens, cacheRead, cacheCreation, outputTokens);
    }

    /** Raw content blocks, ready to send back as the assistant message. */
    public JsonArray content() {
        JsonArray out = new JsonArray();
        for (Block b : blocks.values()) {
            finishBlock(b);
            // An empty text block is rejected by the API when replayed.
            if ("text".equals(b.type) && b.text.length() == 0) continue;
            out.add(b.raw);
        }
        return out;
    }

    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks.values()) {
            if ("text".equals(b.type)) sb.append(b.text);
        }
        return sb.toString();
    }

    public List<ToolUse> toolUses() {
        List<ToolUse> out = new ArrayList<>();
        for (Block b : blocks.values()) {
            if (!"tool_use".equals(b.type)) continue;
            finishBlock(b);
            out.add(new ToolUse(str(b.raw, "id"), str(b.raw, "name"), b.raw.getAsJsonObject("input"), b.inputValid));
        }
        return out;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
