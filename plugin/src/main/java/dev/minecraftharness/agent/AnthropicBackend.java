package dev.minecraftharness.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Talks to the Messages API directly: streaming, tool_use, adaptive thinking and prompt caching.
 * Uses java.net.http on purpose: the plugin ships as one JAR without a shaded SDK.
 */
public final class AnthropicBackend {

    public static final String API_VERSION = "2023-06-01";
    private static final int[] RETRY_DELAYS_MS = {500, 1500, 4000};

    private final ToolHost host;
    private final String systemPrompt;
    private final HttpClient http;
    private final AtomicInteger callCounter;

    /** Settings read fresh for every turn so config edits and /mh commands apply immediately. */
    public record Settings(String apiKey, String baseUrl, String modelId, int maxSteps, int maxTokens) {}

    public AnthropicBackend(ToolHost host, String systemPrompt, AtomicInteger callCounter) {
        this.host = host;
        this.systemPrompt = systemPrompt;
        this.callCounter = callCounter;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    // ---- request building (pure, unit-tested) ---------------------------------------------

    public static JsonObject buildRequest(
        String modelId, String systemPrompt, JsonArray tools, JsonArray messages,
        String effort, boolean withThinking, int maxTokens
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("model", modelId);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", true);

        JsonArray system = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("type", "text");
        sys.addProperty("text", systemPrompt);
        sys.add("cache_control", ephemeral());
        system.add(sys);
        body.add("system", system);

        JsonArray t = new JsonArray();
        for (int i = 0; i < tools.size(); i++) {
            JsonObject tool = tools.get(i).getAsJsonObject().deepCopy();
            tool.addProperty("eager_input_streaming", true);
            if (i == tools.size() - 1) tool.add("cache_control", ephemeral());
            t.add(tool);
        }
        body.add("tools", t);
        body.add("messages", messages);

        if (withThinking) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "adaptive");
            thinking.addProperty("display", "summarized");
            body.add("thinking", thinking);
            JsonObject out = new JsonObject();
            out.addProperty("effort", effort);
            body.add("output_config", out);
        }
        return body;
    }

    private static JsonObject ephemeral() {
        JsonObject cc = new JsonObject();
        cc.addProperty("type", "ephemeral");
        return cc;
    }

    /** A 400 that blames the thinking / effort parameters: worth one retry without them. */
    public static boolean isThinkingRejection(int status, String body) {
        if (status != 400 || body == null) return false;
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("thinking") || b.contains("effort") || b.contains("output_config") || b.contains("display");
    }

    public static boolean isRetryable(int status) {
        return status == 408 || status == 409 || status == 429 || status == 500 || status == 502
            || status == 503 || status == 504 || status == 529;
    }

    /** Short player-facing description of an API failure. */
    public static String describeError(int status, String body) {
        String detail = extractErrorMessage(body);
        String base = switch (status) {
            case 401 -> "APIキーが無効です。`/mh key anthropic <キー>` で設定し直してください。";
            case 403 -> "このAPIキーには権限がありません。";
            case 404 -> "モデルが見つかりません。`/mh model` で選び直してください。";
            case 429 -> "リクエストが多すぎます。少し待ってから再実行してください。";
            case 529 -> "Anthropic API が混み合っています。少し待ってから再実行してください。";
            default -> "Anthropic API エラー (HTTP " + status + ")";
        };
        return detail.isEmpty() ? base : base + " " + ToolHost.truncate(detail, 200);
    }

    static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error") && o.get("error").isJsonObject()) {
                JsonObject e = o.getAsJsonObject("error");
                if (e.has("message")) return e.get("message").getAsString();
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return ToolHost.truncate(body.trim(), 200);
    }

    // ---- turn loop ------------------------------------------------------------------------

    public void runTurn(Settings cfg, TurnInput in) {
        AnthropicHistory history = in.session().history;
        String player = in.playerName();
        ToolHost.Turn turn = in.turn();
        String effort = ModelCatalog.effortFor(in.reasoningLevel());
        boolean withThinking = true;

        history.prune();
        int rollbackTo = history.size();
        history.addUserText(in.userMessage());
        boolean anyStepDone = false;

        try {
            for (int step = 0; step < cfg.maxSteps(); step++) {
                if (in.cancelled().getAsBoolean()) {
                    host.notifyChat(player, "ビルドを中断しました。", in.filmMode());
                    host.progress().cancelRunning(player, "/mh stop で停止");
                    if (!anyStepDone) history.truncateTo(rollbackTo);
                    return;
                }
                int callNo = callCounter.incrementAndGet();
                StepResult res = null;
                int attempt = 0;
                while (true) {
                    res = streamOnce(cfg, in, callNo, effort, withThinking);
                    if (res.ok) break;
                    if (in.cancelled().getAsBoolean()) {
                        host.progress().cancelRunning(player, "/mh stop で停止");
                        if (!anyStepDone) history.truncateTo(rollbackTo);
                        return;
                    }
                    if (withThinking && isThinkingRejection(res.status, res.errorBody)) {
                        withThinking = false;
                        continue;
                    }
                    if ((isRetryable(res.status) || res.status == 0) && attempt < RETRY_DELAYS_MS.length && !res.partial) {
                        sleep(RETRY_DELAYS_MS[attempt++]);
                        continue;
                    }
                    break;
                }
                if (!res.ok) {
                    host.notifyChat(player, res.status == 0
                        ? "Anthropic API に接続できませんでした: " + ToolHost.truncate(String.valueOf(res.errorBody), 200)
                        : describeError(res.status, res.errorBody), false);
                    host.progress().fail(player, res.status == 0 ? "API に接続できません" : "API エラー " + res.status);
                    if (!anyStepDone) history.truncateTo(rollbackTo);
                    return;
                }
                anyStepDone = true;

                AnthropicStream s = res.stream;
                history.addAssistant(s.content());
                String text = s.text();
                if (!text.isBlank()) host.sendReplyOnce(player, text, in.filmMode(), turn.sentReplies);

                List<AnthropicStream.ToolUse> uses = s.toolUses();
                if (uses.isEmpty() || !"tool_use".equals(s.stopReason())) {
                    if ("max_tokens".equals(s.stopReason())) {
                        host.notifyChat(player, "出力が長すぎて途中で切れました。依頼を小分けにしてください。", in.filmMode());
                        host.progress().fail(player, "出力が長すぎて途中で切れました");
                    } else if ("refusal".equals(s.stopReason())) {
                        host.notifyChat(player, "モデルがこの依頼を断りました。", in.filmMode());
                        host.progress().fail(player, "モデルが断りました");
                    }
                    // Any tool_use in a truncated message still needs an answer to keep history valid.
                    history.closePendingToolUses("Turn ended before this tool ran.");
                    return;
                }

                List<com.google.gson.JsonObject> results = new java.util.ArrayList<>();
                String fastReply = null;
                for (AnthropicStream.ToolUse tu : uses) {
                    if (in.cancelled().getAsBoolean()) {
                        results.add(AnthropicHistory.toolResult(tu.id(), "Cancelled by the player.", true, null));
                        continue;
                    }
                    if (!tu.inputValid()) {
                        turn.allToolsOk = false;
                        results.add(AnthropicHistory.toolResult(tu.id(), "Invalid JSON arguments for " + tu.name() + ".", true, null));
                        continue;
                    }
                    host.emitHud(callNo, "TOOL_CALLING", in.reasoningLevel(), 0, tu.name(), player, false);
                    JsonObject result = host.dispatch(tu.name(), tu.input(), turn);
                    host.afterTool(tu.name(), tu.input(), result, turn);
                    String image = ToolHost.takeImage(result);
                    results.add(AnthropicHistory.toolResult(tu.id(), ToolHost.GSON.toJson(result), !ToolHost.isOk(result), image));
                }
                history.addToolResults(results);

                if (in.cancelled().getAsBoolean()) {
                    host.notifyChat(player, "ビルドを中断しました。", in.filmMode());
                    host.progress().cancelRunning(player, "/mh stop で停止");
                    return;
                }
                // Everything worked, nothing needs a look at the result, and the player already got
                // the reply with the build: another model round trip would only repeat it.
                if (turn.allToolsOk && !turn.sawObservation && !turn.batchNeedsFinish && !turn.sentReplies.isEmpty()) {
                    history.addAssistantText(String.join("\n\n", turn.sentReplies));
                    return;
                }
            }
            host.notifyChat(player, "ステップ上限(" + cfg.maxSteps() + ")に達したので止めました。続きは「続けて」と送ってください。", in.filmMode());
            host.progress().fail(player, "ステップ上限");
            history.closePendingToolUses("Step limit reached.");
        } finally {
            history.closePendingToolUses("Turn ended before this tool ran.");
            if (turn.batchNeedsFinish) {
                host.callMainThread("finish_build", new JsonObject());
            }
        }
    }

    // ---- one streaming call ---------------------------------------------------------------

    private static final class StepResult {
        boolean ok;
        boolean partial;
        int status;
        String errorBody;
        AnthropicStream stream;
    }

    private StepResult streamOnce(Settings cfg, TurnInput in, int callNo, String effort, boolean withThinking) {
        StepResult out = new StepResult();
        long t0 = System.currentTimeMillis();
        String player = in.playerName();
        String level = in.reasoningLevel();

        JsonObject body = buildRequest(cfg.modelId(), systemPrompt, host.tools(),
            in.session().history.toRequestMessages(), effort, withThinking, cfg.maxTokens());

        HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(cfg.baseUrl()) + "/v1/messages"))
            .timeout(Duration.ofMinutes(10))
            .header("content-type", "application/json")
            .header("x-api-key", cfg.apiKey())
            .header("anthropic-version", API_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(ToolHost.GSON.toJson(body), StandardCharsets.UTF_8))
            .build();

        Map<Integer, StreamingOpExtractor> extractors = new HashMap<>();
        long[] firstTokenAt = {-1};
        long[] lastHudAt = {0};
        int[] chars = {0};
        StringBuilder reasoning = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        String[] phase = {"QUEUED"};

        AnthropicStream.Listener listener = new AnthropicStream.Listener() {
            private void tick() {
                long now = System.currentTimeMillis();
                if (firstTokenAt[0] < 0) firstTokenAt[0] = now;
                if (now - lastHudAt[0] < 150) return;
                lastHudAt[0] = now;
                double sec = Math.max(0.05, (now - firstTokenAt[0]) / 1000.0);
                int est = (int) Math.round(chars[0] / 3.6);
                double tps = Math.round((est / sec) * 10.0) / 10.0;
                String r = ToolHost.tailSnippet(reasoning.toString(), 96);
                String a = "RESPONDING".equals(phase[0]) ? ToolHost.tailSnippet(answer.toString(), 80) : "";
                host.emitHud(new ToolHost.HudData(callNo, phase[0], level, now - t0, firstTokenAt[0] - t0, tps,
                    null, null, null, est, null, r, a, "", player, false));
            }

            @Override public void onThinkingDelta(String t) {
                host.progress().thinking(player);
                reasoning.append(t);
                chars[0] += t.length();
                phase[0] = "REASONING";
                tick();
            }

            @Override public void onTextDelta(String t) {
                host.progress().responding(player, (int) Math.round((chars[0] + t.length()) / 3.6));
                answer.append(t);
                chars[0] += t.length();
                phase[0] = "RESPONDING";
                tick();
            }

            @Override public void onToolStart(int index, String id, String name) {
                phase[0] = "TOOL_CALLING";
                host.progress().toolStreaming(player, name);
                if ("mc_build_batch".equals(name)) extractors.put(index, new StreamingOpExtractor());
                tick();
            }

            @Override public void onToolInputDelta(int index, String name, String chunk) {
                chars[0] += chunk.length();
                phase[0] = "TOOL_CALLING";
                StreamingOpExtractor ex = extractors.get(index);
                if (ex != null) {
                    for (StreamingOpExtractor.OpEvent ev : ex.feed(chunk)) {
                        host.placeStreamedOp(ev, player, in.turn().streamedOps);
                    }
                }
                tick();
            }
        };
        AnthropicStream stream = new AnthropicStream(listener);

        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            out.status = resp.statusCode();
            if (out.status >= 400) {
                try (InputStream eb = resp.body()) {
                        out.errorBody = new String(eb.readNBytes(16384), StandardCharsets.UTF_8);
                    }
                host.emitHud(callNo, "ERROR", level, System.currentTimeMillis() - t0, "", player, true);
                return out;
            }
            SseParser parser = new SseParser(stream::accept);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (in.cancelled().getAsBoolean()) {
                        out.status = 0;
                        out.errorBody = "cancelled";
                        out.stream = stream;
                        return out;
                    }
                    parser.feedLine(line);
                }
                parser.finish();
            }
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            host.emitHud(callNo, "ERROR", level, System.currentTimeMillis() - t0, "", player, true);
            out.status = 0;
            out.errorBody = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            out.partial = firstTokenAt[0] >= 0;
            return out;
        }

        if (stream.hasError()) {
            out.status = "overloaded_error".equals(stream.errorType()) ? 529 : 500;
            out.errorBody = stream.errorMessage();
            out.partial = firstTokenAt[0] >= 0;
            host.emitHud(callNo, "ERROR", level, System.currentTimeMillis() - t0, "", player, true);
            return out;
        }
        if (!stream.complete()) {
            out.status = 0;
            out.errorBody = "stream ended early";
            out.partial = firstTokenAt[0] >= 0;
            host.emitHud(callNo, "ERROR", level, System.currentTimeMillis() - t0, "", player, true);
            return out;
        }

        long done = System.currentTimeMillis();
        AnthropicStream.Usage u = stream.usage();
        Double tps = null;
        if (u.outputTokens() > 0 && firstTokenAt[0] >= 0) {
            double sec = Math.max(0.05, (done - firstTokenAt[0]) / 1000.0);
            tps = Math.round((u.outputTokens() / sec) * 10.0) / 10.0;
        }
        Double cachePct = u.promptTotal() > 0 ? Math.round((u.cacheReadTokens() * 1000.0) / u.promptTotal()) / 10.0 : null;
        long ttft = firstTokenAt[0] >= 0 ? firstTokenAt[0] - t0 : done - t0;
        host.emitHud(new ToolHost.HudData(callNo, "DONE", level, done - t0, ttft, tps,
            null, null, u.outputTokens(), null, cachePct, "", "", "", player, true));

        out.ok = true;
        out.status = 200;
        out.stream = stream;
        return out;
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
