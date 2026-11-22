package dev.minecraftharness.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.io.File;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentTests {
    static JsonArray tools() {
        return JsonParser.parseString("[{\"name\":\"t1\",\"description\":\"d\",\"input_schema\":{\"type\":\"object\",\"properties\":{}},\"eager_input_streaming\":true}]").getAsJsonArray();
    }

    // ---- SseParser / AnthropicStream ----
    @Test void sseParsesEventsAndStream() {
        AnthropicStream st = new AnthropicStream(new AnthropicStream.Listener() {});
        SseParser p = new SseParser(st::accept);
        String[] lines = {
            "event: message_start", "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5}}}", "",
            "event: content_block_start", "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}", "",
            "event: content_block_delta", "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}", "",
            "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":0}", "",
            "event: content_block_start", "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"tu1\",\"name\":\"t1\",\"input\":{}}}", "",
            "event: content_block_delta", "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"a\\\":\"}}", "",
            "event: content_block_delta", "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"1}\"}}", "",
            "event: content_block_stop", "data: {\"type\":\"content_block_stop\",\"index\":1}", "",
            "event: message_delta", "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":9}}", "",
            "event: message_stop", "data: {\"type\":\"message_stop\"}", ""
        };
        for (String l : lines) p.feedLine(l);
        p.finish();
        assertEquals("Hi", st.text());
        assertEquals("tool_use", st.stopReason());
        assertEquals(1, st.toolUses().size());
        assertEquals("tu1", st.toolUses().get(0).id());
        assertEquals(1, st.toolUses().get(0).input().get("a").getAsInt());
        assertTrue(st.toolUses().get(0).inputValid());
        assertFalse(st.hasError());
    }

    @Test void streamReportsApiError() {
        AnthropicStream st = new AnthropicStream(new AnthropicStream.Listener() {});
        st.accept("error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}");
        assertTrue(st.hasError());
        assertEquals("overloaded_error", st.errorType());
    }

    // ---- ModelCatalog ----
    @Test void modelCatalogResolves() {
        assertEquals("cli/opus", ModelCatalog.DEFAULT_REF);
        assertEquals(ModelCatalog.Backend.CLI, ModelCatalog.resolve("cli/opus").orElseThrow().backend());
        var api = ModelCatalog.resolve("api/claude-opus-5-5").orElseThrow();
        assertEquals(ModelCatalog.Backend.API, api.backend());
        assertEquals("claude-opus-5-5", api.id());
        assertEquals("custom-x", ModelCatalog.resolve("api/custom-x").orElseThrow().id());
        assertTrue(ModelCatalog.resolve("gpt-4").isEmpty());
        assertTrue(ModelCatalog.refs().contains("cli/sonnet"));
    }

    @Test void effortMapping() {
        assertEquals("max", ModelCatalog.effortFor("MAX"));
        assertEquals("low", ModelCatalog.effortFor("off"));
        assertEquals("low", ModelCatalog.effortFor(null));
        assertEquals(List.of("low", "medium", "high", "xhigh", "max"), ModelCatalog.effortLevels());
    }

    // ---- AnthropicHistory ----
    @Test void historyToolResultConsistency() {
        AnthropicHistory h = new AnthropicHistory();
        h.addUserText("hello");
        JsonArray content = JsonParser.parseString("[{\"type\":\"tool_use\",\"id\":\"a\",\"name\":\"t1\",\"input\":{}}]").getAsJsonArray();
        h.addAssistant(content);
        assertNotNull(h.validate());
        h.closePendingToolUses("cancelled");
        assertNull(h.validate());
        h.addUserText("again");
        assertNull(h.validate());
        JsonArray req = h.toRequestMessages();
        JsonArray last = req.get(req.size() - 1).getAsJsonObject().getAsJsonArray("content");
        assertTrue(last.get(last.size() - 1).getAsJsonObject().has("cache_control"));
    }

    @Test void historyPrunesKeepingValid() {
        AnthropicHistory h = new AnthropicHistory();
        for (int i = 0; i < 40; i++) { h.addUserText("u" + i); h.addAssistantText("a" + i); }
        h.prune();
        assertTrue(h.size() <= 26);
        assertNull(h.validate());
    }

    // ---- AnthropicBackend pure helpers ----
    @Test void buildRequestShape() {
        JsonObject r = AnthropicBackend.buildRequest("claude-opus-5-5", "sys", tools(), new JsonArray(), "high", true, 1000);
        assertTrue(r.get("stream").getAsBoolean());
        assertEquals("adaptive", r.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("high", r.getAsJsonObject("output_config").get("effort").getAsString());
        assertTrue(r.getAsJsonArray("system").get(0).getAsJsonObject().has("cache_control"));
        assertTrue(r.getAsJsonArray("tools").get(0).getAsJsonObject().has("cache_control"));
        JsonObject plain = AnthropicBackend.buildRequest("m", "sys", tools(), new JsonArray(), "high", false, 1000);
        assertFalse(plain.has("thinking"));
        assertFalse(plain.has("output_config"));
    }

    @Test void errorClassification() {
        assertTrue(AnthropicBackend.isThinkingRejection(400, "{\"error\":{\"message\":\"effort is not supported\"}}"));
        assertFalse(AnthropicBackend.isThinkingRejection(401, "thinking"));
        assertFalse(AnthropicBackend.isThinkingRejection(400, "bad model"));
        assertTrue(AnthropicBackend.isRetryable(529));
        assertTrue(AnthropicBackend.isRetryable(429));
        assertFalse(AnthropicBackend.isRetryable(400));
        assertTrue(AnthropicBackend.describeError(401, "{\"error\":{\"message\":\"invalid x-api-key\"}}").contains("APIキー"));
    }

    // ---- ClaudeCliBackend pure helpers ----
    @Test void cliCommand() {
        var s = new ClaudeCliBackend.Settings("claude", new File("."), "http://127.0.0.1:1/mcp/t", "opus", 300);
        List<String> c = ClaudeCliBackend.buildCommand(s, "SYS", "uuid-1", false, "high", true);
        assertTrue(c.containsAll(List.of("-p", "--strict-mcp-config", "--session-id", "uuid-1", "--effort", "high")));
        assertEquals("", c.get(c.indexOf("--tools") + 1));
        assertFalse(c.contains("--resume"));
        List<String> r = ClaudeCliBackend.buildCommand(s, "SYS", "uuid-1", true, "high", false);
        assertTrue(r.contains("--resume"));
        assertFalse(r.contains("--effort"));
        JsonObject cfg = JsonParser.parseString(c.get(c.indexOf("--mcp-config") + 1)).getAsJsonObject();
        assertEquals("http://127.0.0.1:1/mcp/t", cfg.getAsJsonObject("mcpServers").getAsJsonObject("minecraft").get("url").getAsString());
    }

    @Test void cliInitCheck() {
        JsonObject ok = JsonParser.parseString("{\"tools\":[\"mcp__minecraft__a\"],\"mcp_servers\":[{\"name\":\"minecraft\",\"status\":\"connected\"}]}").getAsJsonObject();
        assertNull(ClaudeCliBackend.checkInit(ok));
        JsonObject down = JsonParser.parseString("{\"tools\":[],\"mcp_servers\":[{\"name\":\"minecraft\",\"status\":\"failed\"}]}").getAsJsonObject();
        assertNotNull(ClaudeCliBackend.checkInit(down));
        JsonObject extra = JsonParser.parseString("{\"tools\":[\"Bash\",\"mcp__minecraft__a\"],\"mcp_servers\":[{\"name\":\"minecraft\",\"status\":\"connected\"}]}").getAsJsonObject();
        assertNotNull(ClaudeCliBackend.checkInit(extra));
    }

    @Test void cliAssistantText() {
        JsonObject m = JsonParser.parseString("{\"message\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"x\"},{\"type\":\"text\",\"text\":\"A\"},{\"type\":\"text\",\"text\":\"B\"}]}}").getAsJsonObject();
        assertEquals("A\nB", ClaudeCliBackend.assistantText(m));
    }

    // ---- McpProtocol / McpServer ----
    @Test void mcpProtocolRoundTrip() {
        McpProtocol p = new McpProtocol("s", "1", tools(), (n, a) -> McpProtocol.textResult("ok:" + n, false));
        String init = p.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").body();
        assertTrue(init.contains("protocolVersion"));
        assertEquals(202, p.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").status());
        String list = p.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").body();
        assertTrue(list.contains("inputSchema"));
        assertFalse(list.contains("input_schema"));
        assertFalse(list.contains("eager_input_streaming"));
        String call = p.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"t1\",\"arguments\":{}}}").body();
        assertTrue(call.contains("ok:t1"));
        assertTrue(p.handle("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"nope\"}").body().contains("-32601"));
        assertTrue(p.handle("not json").body().contains("error"));
    }

    @Test void mcpServerOverHttp() throws Exception {
        McpProtocol p = new McpProtocol("s", "1", tools(), (n, a) -> McpProtocol.textResult("hi", false));
        McpServer srv = new McpServer(p, "secret");
        srv.start(0);
        try {
            HttpClient hc = HttpClient.newHttpClient();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            var ok = hc.send(HttpRequest.newBuilder(URI.create(srv.url())).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ok.statusCode());
            assertTrue(ok.body().contains("t1"));
            String bad = srv.url().replace("secret", "wrong");
            var denied = hc.send(HttpRequest.newBuilder(URI.create(bad)).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(denied.statusCode() >= 400);
            var get = hc.send(HttpRequest.newBuilder(URI.create(srv.url())).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, get.statusCode());
            assertTrue(srv.url().startsWith("http://127.0.0.1:"));
        } finally {
            srv.stop();
        }
    }
}
