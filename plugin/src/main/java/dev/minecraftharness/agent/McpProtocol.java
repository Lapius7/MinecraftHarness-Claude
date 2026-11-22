package dev.minecraftharness.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.BiFunction;

/**
 * Stateless JSON-RPC handling for the loopback MCP server (Streamable HTTP, POST only).
 * {@code toolCaller} receives (toolName, arguments) and returns the MCP tool result object
 * ({@code content} array plus optional {@code isError}).
 */
public final class McpProtocol {

    public static final String PROTOCOL_VERSION = "2025-06-18";

    private final String serverName;
    private final String serverVersion;
    private final JsonArray tools;

    /** Anthropic tool definitions use input_schema; MCP wants inputSchema. */
    static JsonArray toMcpTools(JsonArray src) {
        JsonArray out = new JsonArray();
        for (JsonElement el : src) {
            JsonObject t = el.getAsJsonObject().deepCopy();
            if (t.has("input_schema") && !t.has("inputSchema")) {
                t.add("inputSchema", t.remove("input_schema"));
            }
            t.remove("eager_input_streaming");
            out.add(t);
        }
        return out;
    }
    private final BiFunction<String, JsonObject, JsonObject> toolCaller;

    public McpProtocol(String serverName, String serverVersion, JsonArray tools,
                       BiFunction<String, JsonObject, JsonObject> toolCaller) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.tools = toMcpTools(tools);
        this.toolCaller = toolCaller;
    }

    /** Result of handling one HTTP body: status and response body (null for 202 without body). */
    public record Reply(int status, String body) {}

    public Reply handle(String requestBody) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(requestBody);
        } catch (Exception ex) {
            return new Reply(400, error(null, -32700, "Parse error").toString());
        }
        if (parsed.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement el : parsed.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject r = handleOne(el.getAsJsonObject());
                if (r != null) out.add(r);
            }
            return out.isEmpty() ? new Reply(202, null) : new Reply(200, out.toString());
        }
        if (!parsed.isJsonObject()) {
            return new Reply(400, error(null, -32600, "Invalid Request").toString());
        }
        JsonObject r = handleOne(parsed.getAsJsonObject());
        return r == null ? new Reply(202, null) : new Reply(200, r.toString());
    }

    private JsonObject handleOne(JsonObject req) {
        boolean isNotification = !req.has("id");
        JsonElement id = req.get("id");
        String method = req.has("method") && req.get("method").isJsonPrimitive() ? req.get("method").getAsString() : "";
        if (method.isEmpty()) {
            // A response or junk from the client; nothing to answer.
            return isNotification ? null : error(id, -32600, "Invalid Request");
        }
        if (isNotification) return null;

        switch (method) {
            case "initialize" -> {
                JsonObject result = new JsonObject();
                String requested = null;
                if (req.has("params") && req.get("params").isJsonObject()) {
                    JsonObject p = req.getAsJsonObject("params");
                    if (p.has("protocolVersion") && p.get("protocolVersion").isJsonPrimitive()) {
                        requested = p.get("protocolVersion").getAsString();
                    }
                }
                result.addProperty("protocolVersion", requested != null && !requested.isBlank() ? requested : PROTOCOL_VERSION);
                JsonObject caps = new JsonObject();
                JsonObject toolsCap = new JsonObject();
                toolsCap.addProperty("listChanged", false);
                caps.add("tools", toolsCap);
                result.add("capabilities", caps);
                JsonObject info = new JsonObject();
                info.addProperty("name", serverName);
                info.addProperty("version", serverVersion);
                result.add("serverInfo", info);
                return ok(id, result);
            }
            case "ping" -> {
                return ok(id, new JsonObject());
            }
            case "tools/list" -> {
                JsonObject result = new JsonObject();
                result.add("tools", tools);
                return ok(id, result);
            }
            case "tools/call" -> {
                JsonObject params = req.has("params") && req.get("params").isJsonObject() ? req.getAsJsonObject("params") : new JsonObject();
                String name = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : "";
                if (name.isEmpty() || !hasTool(name)) {
                    return error(id, -32602, "Unknown tool: " + name);
                }
                JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                    ? params.getAsJsonObject("arguments") : new JsonObject();
                JsonObject result;
                try {
                    result = toolCaller.apply(name, args);
                } catch (Throwable t) {
                    result = textResult("Tool failed: " + t.getMessage(), true);
                }
                return ok(id, result);
            }
            default -> {
                return error(id, -32601, "Method not found: " + method);
            }
        }
    }

    private boolean hasTool(String name) {
        for (JsonElement t : tools) {
            if (t.isJsonObject() && t.getAsJsonObject().has("name")
                && name.equals(t.getAsJsonObject().get("name").getAsString())) return true;
        }
        return false;
    }

    public static JsonObject textResult(String text, boolean isError) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(block);
        JsonObject result = new JsonObject();
        result.add("content", content);
        if (isError) result.addProperty("isError", true);
        return result;
    }

    public static JsonObject imageBlock(String base64Png) {
        JsonObject b = new JsonObject();
        b.addProperty("type", "image");
        b.addProperty("data", base64Png);
        b.addProperty("mimeType", "image/png");
        return b;
    }

    private static JsonObject ok(JsonElement id, JsonObject result) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id);
        o.add("result", result);
        return o;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        o.add("error", e);
        return o;
    }
}
