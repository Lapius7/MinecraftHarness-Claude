package dev.minecraftharness.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Incrementally extracts top-level metadata and each completed element of `ops: [...]`
 * from a streamed `mc_build_batch` JSON arguments string for zero-latency speculative placement.
 */
public final class StreamingOpExtractor {

    public record BatchMeta(String coordMode, String anchorId, String label, String sortMode) {}

    public record OpEvent(BatchMeta meta, int opIndex, JsonObject op, String fingerprint) {}

    private enum State {
        SEEK_ROOT,
        TOP_EXPECT_KEY,
        TOP_EXPECT_COLON,
        TOP_EXPECT_VALUE,
        IN_OPS_ARRAY,
        DONE
    }

    private static final Set<String> META_KEYS = Set.of("coordMode", "anchorId", "label", "sortMode");

    private final StringBuilder buf = new StringBuilder();
    private int pos = 0;
    private State state = State.SEEK_ROOT;
    private String currentKey = "";
    private int opIndex = 0;

    private String coordMode;
    private String anchorId;
    private String label;
    private String sortMode;

    public static String canonicalJson(JsonElement el) {
        if (el == null || el.isJsonNull()) return "null";
        if (el.isJsonPrimitive()) return el.toString();
        if (el.isJsonArray()) {
            StringBuilder sb = new StringBuilder("[");
            JsonArray arr = el.getAsJsonArray();
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonicalJson(arr.get(i)));
            }
            return sb.append(']').toString();
        }
        JsonObject obj = el.getAsJsonObject();
        TreeSet<String> keys = new TreeSet<>(obj.keySet());
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJsonString(k)).append("\":").append(canonicalJson(obj.get(k)));
        }
        return sb.append('}').toString();
    }

    private static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    public List<OpEvent> feed(String chunk) {
        List<OpEvent> events = new ArrayList<>();
        if (chunk == null || chunk.isEmpty() || state == State.DONE) return events;
        buf.append(chunk);

        while (pos < buf.length() && state != State.DONE) {
            skipWs();
            if (pos >= buf.length()) break;

            if (state == State.SEEK_ROOT) {
                if (buf.charAt(pos) == '{') {
                    pos++;
                    state = State.TOP_EXPECT_KEY;
                } else {
                    pos++;
                }
                continue;
            }

            if (state == State.TOP_EXPECT_KEY) {
                char ch = buf.charAt(pos);
                if (ch == ',') {
                    pos++;
                    continue;
                }
                if (ch == '}') {
                    pos++;
                    state = State.DONE;
                    break;
                }
                if (ch != '"') {
                    state = State.DONE;
                    break;
                }
                int strEnd = findStringEnd(pos);
                if (strEnd < 0) break;
                try {
                    currentKey = JsonParser.parseString(buf.substring(pos, strEnd + 1)).getAsString();
                } catch (Exception ex) {
                    state = State.DONE;
                    break;
                }
                pos = strEnd + 1;
                state = State.TOP_EXPECT_COLON;
                continue;
            }

            if (state == State.TOP_EXPECT_COLON) {
                if (buf.charAt(pos) == ':') {
                    pos++;
                    state = State.TOP_EXPECT_VALUE;
                } else {
                    state = State.DONE;
                }
                continue;
            }

            if (state == State.TOP_EXPECT_VALUE) {
                char ch = buf.charAt(pos);
                if ("ops".equals(currentKey) && ch == '[') {
                    pos++;
                    state = State.IN_OPS_ARRAY;
                    continue;
                }
                int valEnd = findValueEnd(pos);
                if (valEnd < 0) break;

                if (META_KEYS.contains(currentKey)) {
                    try {
                        JsonElement parsed = JsonParser.parseString(buf.substring(pos, valEnd));
                        if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()) {
                            String v = parsed.getAsString();
                            switch (currentKey) {
                                case "coordMode" -> coordMode = v;
                                case "anchorId" -> anchorId = v;
                                case "label" -> label = v;
                                case "sortMode" -> sortMode = v;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                pos = valEnd;
                state = State.TOP_EXPECT_KEY;
                continue;
            }

            if (state == State.IN_OPS_ARRAY) {
                char ch = buf.charAt(pos);
                if (ch == ',') {
                    pos++;
                    continue;
                }
                if (ch == ']') {
                    pos++;
                    state = State.TOP_EXPECT_KEY;
                    continue;
                }
                int valEnd = findValueEnd(pos);
                if (valEnd < 0) break;
                try {
                    JsonElement parsed = JsonParser.parseString(buf.substring(pos, valEnd));
                    if (parsed.isJsonObject()) {
                        JsonObject opObj = parsed.getAsJsonObject();
                        events.add(new OpEvent(
                            new BatchMeta(coordMode, anchorId, label, sortMode),
                            opIndex++,
                            opObj,
                            canonicalJson(opObj)
                        ));
                    }
                } catch (Exception ignored) {
                }
                pos = valEnd;
            }
        }
        return events;
    }

    private void skipWs() {
        while (pos < buf.length()) {
            char c = buf.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
            else break;
        }
    }

    private int findStringEnd(int start) {
        boolean escaped = false;
        for (int i = start + 1; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private int findValueEnd(int start) {
        if (start >= buf.length()) return -1;
        char first = buf.charAt(start);
        if (first == '"') {
            int end = findStringEnd(start);
            return end < 0 ? -1 : end + 1;
        }
        if (first == '{' || first == '[') {
            int depth = 0;
            boolean inStr = false;
            boolean escaped = false;
            for (int i = start; i < buf.length(); i++) {
                char c = buf.charAt(i);
                if (inStr) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (c == '"') inStr = true;
                else if (c == '{' || c == '[') depth++;
                else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
            return -1;
        }
        for (int i = start; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (c == ',' || c == '}' || c == ']' || c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                return i;
            }
        }
        return -1;
    }
}
