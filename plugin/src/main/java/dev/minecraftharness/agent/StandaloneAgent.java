package dev.minecraftharness.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minecraftharness.context.SpatialContextProvider.BuildAnchor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Self-contained Java 21 LLM agent embedded inside the PaperMC plugin JAR.
 * Automatically activates when no external Node.js / OpenClaw Bridge is connected,
 * allowing drop-in single-JAR deployment on any OS or Minecraft hosting provider.
 */
public final class StandaloneAgent {

    public record StandaloneModel(
        String ref,
        String provider,
        String modelId,
        String name,
        String apiKeyEnv,
        boolean hasApiKey,
        boolean supportsImage
    ) {}

    public record SelectResult(boolean ok, String active, StandaloneModel selected, String warning, String error) {}

    private record ProviderResolved(
        String providerId,
        String modelId,
        String baseUrl,
        String apiKey,
        String apiKeyEnv,
        boolean supportsImage
    ) {}

    private record ToolCall(String id, String name, String arguments) {}

    private record StreamResult(
        boolean ok,
        int status,
        String content,
        String reasoningContent,
        List<ToolCall> toolCalls,
        String errorText,
        long ttftMs,
        Integer promptTokens,
        Integer cachedTokens,
        Integer reasoningTokens,
        Integer outputTokens,
        Integer completionTokens
    ) {}

    private record StreamedOpRecord(String coordMode, String anchorId, String fingerprint, int queued) {}

    private static final class ChatMsg {
        String role;
        JsonElement content;
        List<ToolCall> toolCalls;
        String toolCallId;
        String reasoningContent;

        static ChatMsg text(String role, String text) {
            ChatMsg m = new ChatMsg();
            m.role = role;
            m.content = text == null ? null : new com.google.gson.JsonPrimitive(text);
            return m;
        }
    }

    private static final class PlayerSessionState {
        String activeName = "main";
        final Map<String, String> sessionIds = new LinkedHashMap<>();

        PlayerSessionState() {
            sessionIds.put("main", "mh-standalone-" + UUID.randomUUID());
        }

        String currentId() {
            return sessionIds.computeIfAbsent(activeName, k -> "mh-standalone-" + UUID.randomUUID());
        }
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern THINK_TAG = Pattern.compile("(?i)</?think>");
    private static final Set<String> OBSERVATION_TOOLS = Set.of("mc_inspect_world", "mc_take_photo", "mc_get_context");

    private final Plugin plugin;
    private final BiFunction<String, JsonObject, JsonObject> mainThreadAction;
    private final HttpClient http;
    private final ExecutorService executor;
    private final String systemPrompt;
    private final JsonArray openaiTools;

    private final Map<String, PlayerSessionState> playerSessions = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMsg>> sessionHistories = new ConcurrentHashMap<>();
    private final Set<String> providersRequiringReasoningContent = ConcurrentHashMap.newKeySet();
    private final AtomicInteger callCounter = new AtomicInteger(0);
    private final AtomicInteger queuedPrompts = new AtomicInteger(0);
    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);

    public StandaloneAgent(Plugin plugin, BiFunction<String, JsonObject, JsonObject> mainThreadAction) {
        this.plugin = plugin;
        this.mainThreadAction = mainThreadAction;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MinecraftHarness-StandaloneAgent");
            t.setDaemon(true);
            return t;
        });
        this.systemPrompt = loadResourceText("/agent-prompt.md",
            "You are MinecraftHarness, an expert real-time Minecraft builder and minigame designer.");
        this.openaiTools = loadResourceJsonArray("/openai-tools.json");
    }

    public void shutdown() {
        cancelRequested.set(true);
        executor.shutdownNow();
    }

    public void cancelActive() {
        cancelRequested.set(true);
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("standalone.enabled", true);
    }

    public String getActiveModelRef() {
        return plugin.getConfig().getString("standalone.activeModel", "cerebras/moonshotai-kimi-k3.1-pro-trial");
    }

    public List<StandaloneModel> listModels() {
        List<StandaloneModel> out = new ArrayList<>();
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection providers = cfg.getConfigurationSection("standalone.providers");
        if (providers == null) return out;
        Map<String, String> dotEnv = loadDotEnvMap();

        for (String providerId : providers.getKeys(false)) {
            ConfigurationSection pSec = providers.getConfigurationSection(providerId);
            if (pSec == null) continue;
            String apiKeyEnv = pSec.getString("apiKeyEnv", "");
            String resolvedKey = resolveProviderApiKey(pSec, providerId, dotEnv);
            boolean hasKey = !resolvedKey.isBlank();

            List<Map<?, ?>> modelList = pSec.getMapList("models");
            for (Map<?, ?> entry : modelList) {
                Object idObj = entry.get("id");
                if (idObj == null) continue;
                String id = String.valueOf(idObj).trim();
                if (id.isEmpty()) continue;
                Object nameObj = entry.get("name");
                String name = nameObj != null && !String.valueOf(nameObj).isBlank()
                    ? String.valueOf(nameObj)
                    : id + " (" + providerId + ")";
                boolean supportsImage = entry.get("supportsImage") == null || Boolean.parseBoolean(String.valueOf(entry.get("supportsImage")));
                out.add(new StandaloneModel(providerId + "/" + id, providerId, id, name, apiKeyEnv, hasKey, supportsImage));
            }
        }
        return out;
    }

    public SelectResult selectModel(String targetRaw) {
        String trimmed = targetRaw == null ? "" : targetRaw.trim();
        String query = trimmed.toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return new SelectResult(false, null, null, null, "Model target is empty");
        }
        List<StandaloneModel> models = listModels();
        StandaloneModel match = models.stream().filter(m -> m.ref().equalsIgnoreCase(query)).findFirst()
            .or(() -> models.stream().filter(m -> m.provider().equalsIgnoreCase(query)).findFirst())
            .or(() -> models.stream().filter(m -> m.modelId().equalsIgnoreCase(query)).findFirst())
            .orElse(null);

        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection providers = cfg.getConfigurationSection("standalone.providers");

        // Dynamic `<existing-provider>/<any-model-id>` registration
        if (match == null && trimmed.contains("/") && providers != null) {
            int slash = trimmed.indexOf('/');
            String provPrefix = trimmed.substring(0, slash).toLowerCase(Locale.ROOT);
            String dynModelId = trimmed.substring(slash + 1).trim();
            String matchedProv = providers.getKeys(false).stream()
                .filter(k -> k.equalsIgnoreCase(provPrefix))
                .findFirst().orElse(null);
            if (matchedProv != null && !dynModelId.isEmpty() && !dynModelId.matches(".*\\s.*")) {
                ConfigurationSection pSec = providers.getConfigurationSection(matchedProv);
                if (pSec != null) {
                    List<Map<String, Object>> updatedList = new ArrayList<>();
                    boolean exists = false;
                    for (Map<?, ?> existing : pSec.getMapList("models")) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        existing.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        if (dynModelId.equals(String.valueOf(copy.get("id")))) exists = true;
                        updatedList.add(copy);
                    }
                    if (!exists) {
                        Map<String, Object> added = new LinkedHashMap<>();
                        added.put("id", dynModelId);
                        added.put("name", dynModelId + " (" + matchedProv + ")");
                        added.put("supportsImage", true);
                        updatedList.add(added);
                        pSec.set("models", updatedList);
                    }
                    String apiKeyEnv = pSec.getString("apiKeyEnv", "");
                    boolean hasKey = !resolveProviderApiKey(pSec, matchedProv, loadDotEnvMap()).isBlank();
                    match = new StandaloneModel(matchedProv + "/" + dynModelId, matchedProv, dynModelId,
                        dynModelId + " (" + matchedProv + ")", apiKeyEnv, hasKey, true);
                }
            }
        }

        if (match == null) {
            match = models.stream()
                .filter(m -> m.ref().toLowerCase(Locale.ROOT).contains(query) || m.name().toLowerCase(Locale.ROOT).contains(query))
                .findFirst().orElse(null);
        }
        if (match == null) {
            return new SelectResult(false, null, null, null,
                "Unknown model '" + targetRaw + "'. Use /mh model or /mh model <provider>/<model-id>");
        }

        cfg.set("standalone.activeModel", match.ref());
        plugin.saveConfig();

        String warning = !match.hasApiKey()
            ? "APIキーが未設定です。`/mh key " + match.provider() + " <APIキー>` または config.yml（環境変数 " + match.apiKeyEnv() + "）を設定してください。"
            : null;
        return new SelectResult(true, match.ref(), match, warning, null);
    }

    public String setProviderApiKey(String providerRaw, String apiKey) {
        String prov = providerRaw == null ? "" : providerRaw.trim().toLowerCase(Locale.ROOT);
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection providers = cfg.getConfigurationSection("standalone.providers");
        if (providers == null) return null;
        String matched = providers.getKeys(false).stream().filter(k -> k.equalsIgnoreCase(prov)).findFirst().orElse(null);
        if (matched == null) return null;
        cfg.set("standalone.providers." + matched + ".apiKey", apiKey.trim());
        plugin.saveConfig();
        return matched;
    }

    public static String maskKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    public String handleConversationCommand(String playerName, String prompt) {
        String[] parts = prompt.trim().split("\\s+");
        if (parts.length == 0) return null;
        String verb = parts[0].toLowerCase(Locale.ROOT);
        boolean reset = ((verb.equals("new") || verb.equals("reset-chat") || verb.equals("reset_chat")) && parts.length == 1)
            || (verb.equals("history") && parts.length == 2 && parts[1].equalsIgnoreCase("reset"));
        if (!reset && !verb.equals("session")) return null;

        PlayerSessionState st = playerSessions.computeIfAbsent(playerName.toLowerCase(Locale.ROOT), k -> new PlayerSessionState());
        if (reset) {
            String oldId = st.currentId();
            sessionHistories.remove(oldId);
            st.sessionIds.put(st.activeName, "mh-standalone-" + UUID.randomUUID());
            return "「" + st.activeName + "」の会話を新しくしました。";
        }
        if (parts.length == 1 || (parts.length == 2 && parts[1].equalsIgnoreCase("list"))) {
            return "現在: " + st.activeName + " ｜ セッション: " + String.join("、", st.sessionIds.keySet());
        }
        if (parts.length != 3 || !parts[1].equalsIgnoreCase("use")) {
            return "使い方: /mh session list または /mh session use <名前>";
        }
        String target = parts[2].trim();
        if (!target.matches("[\\p{L}\\p{N}_-]{1,32}")) {
            return "名前は文字・数字・ハイフン・下線で1〜32文字にしてください。";
        }
        st.activeName = target;
        st.currentId();
        return "現在のセッション: " + target;
    }

    public void enqueuePrompt(
        String playerName,
        String prompt,
        boolean filmMode,
        String thinkingLevel,
        BuildAnchor promptAnchor
    ) {
        String cmdReply = handleConversationCommand(playerName, prompt);
        if (cmdReply != null) {
            notifyChat(playerName, cmdReply, filmMode);
            return;
        }
        int ahead = queuedPrompts.getAndIncrement();
        if (ahead > 0 && !filmMode) {
            String preview = prompt.length() > 30 ? prompt.substring(0, 30) : prompt;
            notifyChat(playerName, "「" + preview + "」を受け付けました。先に処理する依頼は" + ahead + "件です。", false);
        }
        executor.submit(() -> {
            cancelRequested.set(false);
            try {
                runPromptTurn(playerName, prompt.trim(), filmMode, thinkingLevel, promptAnchor);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Standalone agent turn failed", t);
                notifyChat(playerName, "AI実行エラー: " + t.getMessage(), filmMode);
            } finally {
                queuedPrompts.decrementAndGet();
            }
        });
    }

    private void runPromptTurn(
        String playerName,
        String prompt,
        boolean filmMode,
        String thinkingLevel,
        BuildAnchor promptAnchor
    ) {
        ProviderResolved cfg;
        try {
            cfg = resolveActiveProvider();
        } catch (IllegalStateException ex) {
            notifyChat(playerName, ex.getMessage(), filmMode);
            return;
        }
        if (cfg.apiKey().isBlank()) {
            notifyChat(playerName,
                "APIキーが未設定です（" + cfg.providerId() + "）。コンソールまたはチャットで `/mh key "
                    + cfg.providerId() + " <APIキー>` を実行するか、plugins/MinecraftHarness/config.yml に設定してください。",
                false);
            return;
        }

        // Lock prompt anchor & gather world context on the main thread
        JsonObject beginParams = new JsonObject();
        beginParams.addProperty("playerName", playerName);
        beginParams.add("anchor", GSON.toJsonTree(promptAnchor));
        JsonObject ctx = callMainThread("begin_prompt", beginParams);

        String userMessage = formatSurroundingSummary(ctx) + "\n\nPlayer \"" + playerName + "\" says: " + prompt;
        PlayerSessionState pState = playerSessions.computeIfAbsent(playerName.toLowerCase(Locale.ROOT), k -> new PlayerSessionState());
        String sessionId = pState.currentId();
        List<ChatMsg> history = sessionHistories.computeIfAbsent(sessionId, k -> new ArrayList<>());
        pruneHistory(history);

        int turnStartIdx = history.size();
        history.add(ChatMsg.text("user", userMessage));

        Set<String> sentChatReplies = new LinkedHashSet<>();
        boolean batchNeedsFinish = false;
        int maxSteps = Math.max(1, plugin.getConfig().getInt("standalone.maxSteps", 12));

        try {
            for (int step = 0; step < maxSteps; step++) {
                if (cancelRequested.get()) {
                    notifyChat(playerName, "ビルドを中断しました。", filmMode);
                    return;
                }

                List<StreamedOpRecord> streamedOps = new ArrayList<>();
                AtomicBoolean streamedAny = new AtomicBoolean(false);
                int callNo = callCounter.incrementAndGet();

                StreamResult res = executeLlmStepWithRetries(
                    cfg, history, thinkingLevel, sessionId, callNo, playerName, filmMode, streamedOps, streamedAny
                );
                if (streamedAny.get()) batchNeedsFinish = true;

                if (!res.ok()) {
                    if (history.size() == turnStartIdx + 1) history.remove(history.size() - 1);
                    String errMsg = res.status() == 429
                        ? "AIが混雑していて回答を完了できませんでした。少し待ってから続けてください。"
                        : "AI HTTP " + res.status() + ": " + truncate(res.errorText(), 280);
                    notifyChat(playerName, errMsg, filmMode);
                    return;
                }

                ChatMsg assistantMsg = ChatMsg.text("assistant", res.content().isEmpty() ? null : res.content());
                if (!res.toolCalls().isEmpty()) {
                    assistantMsg.toolCalls = res.toolCalls();
                    assistantMsg.reasoningContent = res.reasoningContent();
                }
                history.add(assistantMsg);

                // Always write post-inference reply to chat (even when tool calls continue the turn)
                sendReplyOnce(playerName, res.content(), filmMode, sentChatReplies, false);

                if (res.toolCalls().isEmpty()) {
                    return;
                }

                boolean allSucceeded = true;
                boolean hasObservation = false;
                boolean hasPendingMultiBatch = false;
                String fastFinishReply = null;
                String builtFallback = null;
                String actionFallback = null;
                List<String> pendingImagesBase64 = new ArrayList<>();

                for (ToolCall tc : res.toolCalls()) {
                    if (cancelRequested.get()) break;
                    JsonObject args;
                    try {
                        JsonElement parsed = tc.arguments() == null || tc.arguments().isBlank()
                            ? new JsonObject()
                            : JsonParser.parseString(tc.arguments());
                        args = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
                    } catch (Exception ex) {
                        JsonObject err = new JsonObject();
                        err.addProperty("ok", false);
                        err.addProperty("error", "Invalid JSON arguments for " + tc.name() + ": " + ex.getMessage());
                        history.add(toolResultMsg(tc.id(), err));
                        allSucceeded = false;
                        continue;
                    }

                    emitHud(callNo, "TOOL_CALLING", thinkingLevel, 0, null, null, null, null, null, null, null, "", "", tc.name(), playerName, false);
                    JsonObject result = dispatchTool(tc.name(), args, playerName, streamedOps, filmMode, sentChatReplies);

                    if (OBSERVATION_TOOLS.contains(tc.name())
                        || ("mc_exec_commands".equals(tc.name()) && args.has("observe") && args.get("observe").isJsonObject())) {
                        hasObservation = true;
                    }
                    boolean ok = !result.has("ok") || !result.get("ok").isJsonPrimitive() || result.get("ok").getAsBoolean();
                    if (!ok) allSucceeded = false;

                    if ("mc_build_batch".equals(tc.name())) {
                        boolean isFinishFalse = args.has("finish") && args.get("finish").isJsonPrimitive() && !args.get("finish").getAsBoolean();
                        batchNeedsFinish = isFinishFalse;
                        if (isFinishFalse) {
                            hasPendingMultiBatch = true;
                        } else if (ok) {
                            if (args.has("reply") && args.get("reply").isJsonPrimitive() && !args.get("reply").getAsString().isBlank()) {
                                fastFinishReply = args.get("reply").getAsString().trim();
                            } else {
                                String lbl = args.has("label") && args.get("label").isJsonPrimitive() && !args.get("label").getAsString().isBlank()
                                    ? "「" + args.get("label").getAsString().trim() + "」" : "建築";
                                int q = result.has("queued") ? result.get("queued").getAsInt() : 0;
                                builtFallback = q > 0 ? lbl + "を完了しました！（" + q + "ブロック）" : lbl + "を完了しました！";
                            }
                        }
                    } else if ("mc_fx".equals(tc.name()) && ok
                        && args.has("effect") && "finish".equalsIgnoreCase(args.get("effect").getAsString())) {
                        batchNeedsFinish = false;
                    } else if (ok && ("mc_fx".equals(tc.name()) || "mc_exec_commands".equals(tc.name())
                        || "mc_place_command_chain".equals(tc.name()) || "mc_function_pack".equals(tc.name()))) {
                        actionFallback = "処理を完了しました！";
                    }

                    if (result.has("_imageBase64") && result.get("_imageBase64").isJsonPrimitive()) {
                        String b64 = result.get("_imageBase64").getAsString();
                        result.remove("_imageBase64");
                        if (cfg.supportsImage() && !b64.isEmpty()) {
                            pendingImagesBase64.add(b64);
                        }
                    }
                    history.add(toolResultMsg(tc.id(), result));
                }

                String finalFastReply = fastFinishReply != null
                    ? fastFinishReply
                    : (!res.content().trim().isEmpty() ? res.content().trim()
                        : (builtFallback != null ? builtFallback : actionFallback));
                if (allSucceeded && !hasObservation && !hasPendingMultiBatch && finalFastReply != null) {
                    history.add(ChatMsg.text("assistant", finalFastReply));
                    sendReplyOnce(playerName, finalFastReply, filmMode, sentChatReplies, false);
                    return;
                }

                if (!pendingImagesBase64.isEmpty()) {
                    JsonArray parts = new JsonArray();
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("type", "text");
                    textPart.addProperty("text", "Captured top-down voxel overview from tool call:");
                    parts.add(textPart);
                    for (String b64 : pendingImagesBase64) {
                        JsonObject imgPart = new JsonObject();
                        imgPart.addProperty("type", "image_url");
                        JsonObject imgUrl = new JsonObject();
                        imgUrl.addProperty("url", "data:image/png;base64," + b64);
                        imgPart.add("image_url", imgUrl);
                        parts.add(imgPart);
                    }
                    ChatMsg imgMsg = new ChatMsg();
                    imgMsg.role = "user";
                    imgMsg.content = parts;
                    history.add(imgMsg);
                }
            }
        } finally {
            if (batchNeedsFinish) {
                callMainThread("finish_build", new JsonObject());
            }
        }
    }

    private StreamResult executeLlmStepWithRetries(
        ProviderResolved cfg,
        List<ChatMsg> history,
        String thinkingLevel,
        String sessionId,
        int callNo,
        String playerName,
        boolean filmMode,
        List<StreamedOpRecord> streamedOps,
        AtomicBoolean streamedAny
    ) {
        boolean omitReasoningEffort = false;
        int[] retryDelays = { 400, 900, 1800 };
        int attempt = 0;

        while (true) {
            StreamResult res = sendStreamingCompletion(
                cfg, history, thinkingLevel, omitReasoningEffort, sessionId, callNo, playerName, streamedOps, streamedAny
            );
            if (res.ok()) return res;

            String err = res.errorText() == null ? "" : res.errorText();
            // Auto-strip reasoning_effort on 400/422 rejection
            if (!omitReasoningEffort && (res.status() == 400 || res.status() == 422)
                && err.toLowerCase(Locale.ROOT).contains("reasoning_effort")
                && err.toLowerCase(Locale.ROOT).matches(".*(unsupported|invalid|unknown|not supported|not allowed|does not support|unrecognized|extra).*")) {
                omitReasoningEffort = true;
                continue;
            }
            // Auto-attach reasoning_content if upstream requires it on assistant tool-call history
            if (res.status() == 400 && !providersRequiringReasoningContent.contains(cfg.providerId())
                && err.toLowerCase(Locale.ROOT).contains("reasoning_content")
                && err.toLowerCase(Locale.ROOT).matches(".*(missing|required).*")) {
                providersRequiringReasoningContent.add(cfg.providerId());
                continue;
            }
            // Transparent backoff on 429
            if (res.status() == 429 && attempt < retryDelays.length && !cancelRequested.get()) {
                try {
                    Thread.sleep(retryDelays[attempt++]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return res;
                }
                continue;
            }
            return res;
        }
    }

    private StreamResult sendStreamingCompletion(
        ProviderResolved cfg,
        List<ChatMsg> history,
        String thinkingLevel,
        boolean omitReasoningEffort,
        String sessionId,
        int callNo,
        String playerName,
        List<StreamedOpRecord> streamedOps,
        AtomicBoolean streamedAny
    ) {
        long t0 = System.currentTimeMillis();
        boolean includeReasoning = providersRequiringReasoningContent.contains(cfg.providerId());

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.modelId());
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt);
        messages.add(sys);

        for (ChatMsg m : history) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role);
            if (m.content == null) o.add("content", com.google.gson.JsonNull.INSTANCE);
            else o.add("content", m.content);
            if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                JsonArray tcs = new JsonArray();
                for (ToolCall tc : m.toolCalls) {
                    JsonObject tco = new JsonObject();
                    tco.addProperty("id", tc.id());
                    tco.addProperty("type", "function");
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", tc.name());
                    fn.addProperty("arguments", tc.arguments());
                    tco.add("function", fn);
                    tcs.add(tco);
                }
                o.add("tool_calls", tcs);
                if (includeReasoning && m.reasoningContent != null && !m.reasoningContent.isEmpty()) {
                    o.addProperty("reasoning_content", m.reasoningContent);
                }
            }
            if (m.toolCallId != null) o.addProperty("tool_call_id", m.toolCallId);
            messages.add(o);
        }
        body.add("messages", messages);
        body.add("tools", openaiTools);
        body.addProperty("tool_choice", "auto");
        body.addProperty("parallel_tool_calls", true);
        body.addProperty("stream", true);
        JsonObject streamOpts = new JsonObject();
        streamOpts.addProperty("include_usage", true);
        body.add("stream_options", streamOpts);
        body.addProperty("max_completion_tokens", 16384);

        String lvl = thinkingLevel == null ? "low" : thinkingLevel.toLowerCase(Locale.ROOT).trim();
        if (!omitReasoningEffort) {
            if (lvl.equals("low") || lvl.equals("medium") || lvl.equals("high")) {
                body.addProperty("reasoning_effort", lvl);
            } else if (lvl.equals("minimal")) {
                body.addProperty("reasoning_effort", "low");
            }
        }

        String endpoint = cfg.baseUrl().replaceAll("/+$", "") + "/chat/completions";
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(300))
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer " + cfg.apiKey())
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));

        if ("opencode-go".equalsIgnoreCase(cfg.providerId())) {
            reqBuilder.header("x-opencode-session", sessionId);
        } else if ("openrouter".equalsIgnoreCase(cfg.providerId())) {
            reqBuilder.header("HTTP-Referer", "https://github.com/ykundesu/MinecraftHarness");
            reqBuilder.header("X-Title", "MinecraftHarness");
        }

        emitHud(callNo, "QUEUED", lvl, 0, null, null, null, null, null, null, null, "", "", "", playerName, false);

        try {
            HttpResponse<InputStream> response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 400) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                emitHud(callNo, "ERROR", lvl, System.currentTimeMillis() - t0, null, null, null, null, null, null, null, "", "", "", playerName, true);
                return new StreamResult(false, status, "", "", List.of(), errBody, -1, null, null, null, null, null);
            }

            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            StringBuilder reasoningFallback = new StringBuilder();
            TreeMap<Integer, String[]> tcMap = new TreeMap<>(); // [id, name, args]
            Map<Integer, StreamingOpExtractor> extractors = new HashMap<>();
            Map<Integer, StringBuilder> pendingArgs = new HashMap<>();

            long firstTokenAt = -1L;
            long lastHudAt = 0L;
            int chunkCount = 0;
            int streamedChars = 0;
            Integer promptTok = null, cachedTok = null, reasonTok = null, outTok = null, compTok = null;
            String phase = "QUEUED";

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelRequested.get()) break;
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) continue;
                    String payload = trimmed.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                    JsonObject j;
                    try {
                        j = JsonParser.parseString(payload).getAsJsonObject();
                    } catch (Exception ignored) {
                        continue;
                    }

                    if (j.has("usage") && j.get("usage").isJsonObject()) {
                        JsonObject u = j.getAsJsonObject("usage");
                        if (u.has("prompt_tokens") && u.get("prompt_tokens").isJsonPrimitive()) promptTok = u.get("prompt_tokens").getAsInt();
                        if (u.has("completion_tokens") && u.get("completion_tokens").isJsonPrimitive()) compTok = u.get("completion_tokens").getAsInt();
                        if (u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject()) {
                            JsonObject pd = u.getAsJsonObject("prompt_tokens_details");
                            if (pd.has("cached_tokens") && pd.get("cached_tokens").isJsonPrimitive()) cachedTok = pd.get("cached_tokens").getAsInt();
                        }
                        if (u.has("completion_tokens_details") && u.get("completion_tokens_details").isJsonObject()) {
                            JsonObject cd = u.getAsJsonObject("completion_tokens_details");
                            if (cd.has("reasoning_tokens") && cd.get("reasoning_tokens").isJsonPrimitive()) reasonTok = cd.get("reasoning_tokens").getAsInt();
                        }
                        if (compTok != null) {
                            outTok = Math.max(0, compTok - (reasonTok != null ? reasonTok : 0));
                        }
                    }

                    JsonArray choices = j.has("choices") && j.get("choices").isJsonArray() ? j.getAsJsonArray("choices") : null;
                    if (choices == null || choices.isEmpty()) continue;
                    JsonObject c0 = choices.get(0).getAsJsonObject();
                    JsonObject delta = c0.has("delta") && c0.get("delta").isJsonObject() ? c0.getAsJsonObject("delta") : null;
                    if (delta == null) continue;

                    long now = System.currentTimeMillis();
                    boolean gotAny = false;

                    if (delta.has("reasoning_content") && delta.get("reasoning_content").isJsonPrimitive()) {
                        String rc = delta.get("reasoning_content").getAsString();
                        if (!rc.isEmpty()) {
                            reasoning.append(rc);
                            streamedChars += rc.length();
                            phase = "REASONING";
                            gotAny = true;
                        }
                    } else if (delta.has("reasoning") && delta.get("reasoning").isJsonPrimitive()) {
                        String rf = delta.get("reasoning").getAsString();
                        if (!rf.isEmpty()) {
                            reasoningFallback.append(rf);
                            streamedChars += rf.length();
                            phase = "REASONING";
                            gotAny = true;
                        }
                    }

                    if (delta.has("content") && delta.get("content").isJsonPrimitive()) {
                        String cc = delta.get("content").getAsString();
                        if (!cc.isEmpty()) {
                            content.append(cc);
                            streamedChars += cc.length();
                            if (tcMap.isEmpty()) phase = "RESPONDING";
                            gotAny = true;
                        }
                    }

                    if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                        for (JsonElement el : delta.getAsJsonArray("tool_calls")) {
                            if (!el.isJsonObject()) continue;
                            JsonObject tc = el.getAsJsonObject();
                            int idx = tc.has("index") && tc.get("index").isJsonPrimitive() ? tc.get("index").getAsInt() : 0;
                            String[] entry = tcMap.computeIfAbsent(idx, k -> new String[]{ "call_" + k + "_" + now, "", "" });
                            if (tc.has("id") && tc.get("id").isJsonPrimitive() && !tc.get("id").getAsString().isEmpty()) {
                                entry[0] = tc.get("id").getAsString();
                            }
                            if (tc.has("function") && tc.get("function").isJsonObject()) {
                                JsonObject fn = tc.getAsJsonObject("function");
                                if (fn.has("name") && fn.get("name").isJsonPrimitive()) {
                                    entry[1] += fn.get("name").getAsString();
                                    streamedChars += fn.get("name").getAsString().length();
                                }
                                if (fn.has("arguments") && fn.get("arguments").isJsonPrimitive()) {
                                    String argChunk = fn.get("arguments").getAsString();
                                    entry[2] += argChunk;
                                    streamedChars += argChunk.length();
                                    feedSpeculativeBuildOp(idx, entry[1], argChunk, extractors, pendingArgs, streamedOps, streamedAny, playerName);
                                }
                            }
                            phase = "TOOL_CALLING";
                            gotAny = true;
                        }
                    }

                    if (gotAny) {
                        chunkCount++;
                        if (firstTokenAt < 0) firstTokenAt = now;
                        if (now - lastHudAt >= 150) {
                            lastHudAt = now;
                            double streamSec = Math.max(0.05, (now - firstTokenAt) / 1000.0);
                            int estTokens = (int) Math.max(chunkCount, Math.round(streamedChars / 3.6));
                            double liveTps = Math.round((estTokens / streamSec) * 10.0) / 10.0;
                            String rAll = reasoning.length() > 0 ? reasoning.toString() : reasoningFallback.toString();
                            String rTail = tailSnippet(rAll, 96);
                            String aTail = "RESPONDING".equals(phase) ? tailSnippet(content.toString(), 80) : "";
                            emitHud(callNo, phase, lvl, now - t0, firstTokenAt - t0, liveTps, null, null, null, estTokens, null, rTail, aTail, "", playerName, false);
                        }
                    }
                }
            }

            long doneNow = System.currentTimeMillis();
            long ttftMs = firstTokenAt >= 0 ? (firstTokenAt - t0) : (doneNow - t0);
            int finalEstTokens = (int) Math.max(chunkCount, Math.round(streamedChars / 3.6));
            Double tps = null;
            if (compTok != null && firstTokenAt >= 0) {
                double sec = Math.max(0.05, (doneNow - firstTokenAt) / 1000.0);
                tps = Math.round((compTok / sec) * 10.0) / 10.0;
            }
            Double cachePct = (promptTok != null && promptTok > 0 && cachedTok != null)
                ? Math.round((cachedTok * 1000.0) / promptTok) / 10.0 : null;
            emitHud(callNo, "DONE", lvl, doneNow - t0, ttftMs, tps, reasonTok, outTok, compTok, compTok == null ? finalEstTokens : null, cachePct, "", "", "", playerName, true);

            List<ToolCall> toolCalls = new ArrayList<>();
            for (String[] entry : tcMap.values()) {
                toolCalls.add(new ToolCall(entry[0], entry[1], entry[2]));
            }
            return new StreamResult(
                true, status, content.toString(),
                reasoning.length() > 0 ? reasoning.toString() : reasoningFallback.toString(),
                toolCalls, null, ttftMs, promptTok, cachedTok, reasonTok, outTok, compTok
            );
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            emitHud(callNo, "ERROR", lvl, System.currentTimeMillis() - t0, null, null, null, null, null, null, null, "", "", "", playerName, true);
            return new StreamResult(false, 0, "", "", List.of(), ex.getMessage(), -1, null, null, null, null, null);
        }
    }

    private void feedSpeculativeBuildOp(
        int tcIdx,
        String toolName,
        String argChunk,
        Map<Integer, StreamingOpExtractor> extractors,
        Map<Integer, StringBuilder> pendingArgs,
        List<StreamedOpRecord> streamedOps,
        AtomicBoolean streamedAny,
        String playerName
    ) {
        if ("mc_build_batch".equals(toolName)) {
            StreamingOpExtractor ex = extractors.computeIfAbsent(tcIdx, k -> new StreamingOpExtractor());
            StringBuilder pend = pendingArgs.remove(tcIdx);
            String toFeed = pend != null ? pend.toString() + argChunk : argChunk;
            for (StreamingOpExtractor.OpEvent ev : ex.feed(toFeed)) {
                String coordMode = ev.meta().coordMode() != null ? ev.meta().coordMode() : "local";
                String anchorId = ev.meta().anchorId() != null && !ev.meta().anchorId().isEmpty() ? ev.meta().anchorId() : null;
                JsonObject payload = new JsonObject();
                payload.addProperty("playerName", playerName);
                payload.addProperty("coordMode", coordMode);
                if (anchorId != null) payload.addProperty("anchorId", anchorId);
                if (ev.meta().label() != null) payload.addProperty("label", ev.meta().label());
                if (ev.meta().sortMode() != null) payload.addProperty("sortMode", ev.meta().sortMode());
                JsonArray ops = new JsonArray();
                ops.add(ev.op());
                payload.add("ops", ops);
                payload.addProperty("finish", false);
                payload.addProperty("streamed", true);

                JsonObject res = callMainThread("build_batch", payload);
                if (!res.has("ok") || res.get("ok").getAsBoolean()) {
                    int q = res.has("queued") ? res.get("queued").getAsInt() : 0;
                    streamedOps.add(new StreamedOpRecord(coordMode, anchorId, ev.fingerprint(), q));
                    streamedAny.set(true);
                }
            }
        } else if (toolName.isEmpty() && !argChunk.isEmpty()) {
            pendingArgs.computeIfAbsent(tcIdx, k -> new StringBuilder()).append(argChunk);
        }
    }

    private JsonObject dispatchTool(
        String toolName,
        JsonObject args,
        String playerName,
        List<StreamedOpRecord> streamedOps,
        boolean filmMode,
        Set<String> sentChatReplies
    ) {
        if ("mc_take_photo".equals(toolName)) {
            JsonObject snapReq = args.deepCopy();
            snapReq.addProperty("playerName", playerName);
            JsonObject snap = callMainThread("voxel_snapshot", snapReq);
            if (!snap.has("ok") || !snap.get("ok").getAsBoolean()) return snap;
            String b64 = renderVoxelSnapshotPngBase64(snap);
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            out.addProperty("mode", "voxel_topdown");
            out.addProperty("width", 384);
            out.addProperty("height", 384);
            out.addProperty("note", "Embedded JAR top-down voxel overview rendered directly on server.");
            out.addProperty("_imageBase64", b64);
            return out;
        }

        if ("mc_exec_commands".equals(toolName) && args.has("observe") && args.get("observe").isJsonObject()) {
            JsonObject obs = args.getAsJsonObject("observe");
            JsonObject execReq = new JsonObject();
            execReq.addProperty("playerName", playerName);
            if (args.has("commands")) execReq.add("commands", args.get("commands"));
            if (args.has("anchorId")) execReq.add("anchorId", args.get("anchorId"));
            JsonObject res = callMainThread("exec_commands", execReq);
            if (res.has("ok") && !res.get("ok").getAsBoolean()) return res;

            if (obs.has("at") && obs.get("at").isJsonArray() && obs.has("afterMs")) {
                JsonArray at = obs.getAsJsonArray("at");
                int afterMs = Math.max(1, Math.min(5000, obs.get("afterMs").getAsInt()));
                long start = System.currentTimeMillis();
                JsonArray observations = new JsonArray();
                for (int offset : new int[]{ 0, afterMs }) {
                    long rem = start + offset - System.currentTimeMillis();
                    if (rem > 0) {
                        try { Thread.sleep(rem); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                    JsonObject inspReq = new JsonObject();
                    inspReq.addProperty("playerName", playerName);
                    inspReq.add("from", at);
                    inspReq.add("to", at);
                    JsonArray sc = new JsonArray();
                    sc.add(at);
                    inspReq.add("sampleCoords", sc);
                    if (args.has("anchorId")) inspReq.add("anchorId", args.get("anchorId"));
                    JsonObject insp = callMainThread("inspect_world", inspReq);
                    JsonObject sample = new JsonObject();
                    sample.addProperty("elapsedMs", System.currentTimeMillis() - start);
                    String key = at.get(0).getAsInt() + "," + at.get(1).getAsInt() + "," + at.get(2).getAsInt();
                    if (insp.has("sampleBlocks") && insp.get("sampleBlocks").isJsonObject()) {
                        sample.add("state", insp.getAsJsonObject("sampleBlocks").get(key));
                    }
                    observations.add(sample);
                }
                JsonObject obsOut = new JsonObject();
                obsOut.add("at", at);
                obsOut.add("observations", observations);
                res.add("observe", obsOut);
            }
            return res;
        }

        String action = toolName.startsWith("mc_") ? toolName.substring(3) : toolName;
        JsonObject req = args.deepCopy();
        req.addProperty("playerName", playerName);

        int preStreamedCount = 0;
        int preStreamedQueued = 0;
        boolean skipCall = false;

        if ("mc_build_batch".equals(toolName) && !streamedOps.isEmpty() && req.has("ops") && req.get("ops").isJsonArray()) {
            JsonArray reqOps = req.getAsJsonArray("ops");
            String reqCoordMode = req.has("coordMode") && req.get("coordMode").isJsonPrimitive() ? req.get("coordMode").getAsString() : "local";
            String reqAnchorId = req.has("anchorId") && req.get("anchorId").isJsonPrimitive() && !req.get("anchorId").getAsString().isEmpty()
                ? req.get("anchorId").getAsString() : null;
            int matched = 0;
            while (matched < streamedOps.size() && matched < reqOps.size()) {
                StreamedOpRecord rec = streamedOps.get(matched);
                boolean anchorMatch = (rec.anchorId() == null ? reqAnchorId == null : rec.anchorId().equals(reqAnchorId));
                if (rec.coordMode().equals(reqCoordMode) && anchorMatch
                    && rec.fingerprint().equals(StreamingOpExtractor.canonicalJson(reqOps.get(matched)))) {
                    preStreamedQueued += rec.queued();
                    matched++;
                } else {
                    break;
                }
            }
            if (matched > 0) {
                streamedOps.subList(0, matched).clear();
                preStreamedCount = matched;
                JsonArray remaining = new JsonArray();
                for (int i = matched; i < reqOps.size(); i++) remaining.add(reqOps.get(i));
                boolean hasCmds = req.has("commands") && req.get("commands").isJsonArray() && !req.getAsJsonArray("commands").isEmpty();
                boolean needsFinish = !req.has("finish") || req.get("finish").getAsBoolean();
                if (remaining.isEmpty() && !hasCmds && !needsFinish) {
                    skipCall = true;
                } else {
                    req.add("ops", remaining);
                }
            } else {
                streamedOps.clear();
            }
        }

        JsonObject result;
        if (skipCall) {
            result = new JsonObject();
            result.addProperty("ok", true);
            result.addProperty("queued", preStreamedQueued);
            result.addProperty("totalQueued", preStreamedQueued);
            result.addProperty("preStreamed", preStreamedCount);
            result.addProperty("finished", false);
        } else {
            result = callMainThread(action, req);
            if (preStreamedCount > 0 && (!result.has("ok") || result.get("ok").getAsBoolean())) {
                int q = result.has("queued") ? result.get("queued").getAsInt() : 0;
                result.addProperty("queued", q + preStreamedQueued);
                result.addProperty("preStreamed", preStreamedCount);
            }
        }

        if ("mc_build_batch".equals(toolName) && (!result.has("ok") || result.get("ok").getAsBoolean())
            && args.has("reply") && args.get("reply").isJsonPrimitive() && !args.get("reply").getAsString().isBlank()) {
            sendReplyOnce(playerName, args.get("reply").getAsString(), filmMode, sentChatReplies, false);
        }
        return result;
    }

    private JsonObject callMainThread(String action, JsonObject params) {
        if (Bukkit.isPrimaryThread()) {
            return mainThreadAction.apply(action, params);
        }
        CompletableFuture<JsonObject> fut = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                JsonObject r = mainThreadAction.apply(action, params);
                fut.complete(r != null ? r : new JsonObject());
            } catch (Throwable t) {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", t.getClass().getSimpleName() + ": " + t.getMessage());
                fut.complete(err);
            }
        });
        try {
            return fut.get(20, TimeUnit.SECONDS);
        } catch (Exception ex) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Main-thread action timeout/failure: " + ex.getMessage());
            return err;
        }
    }

    private void emitHud(
        int callNo, String phase, String effort, long elapsedMs, Long ttftMs, Double tps,
        Integer rTok, Integer oTok, Integer compTok, Integer liveTok, Double cachePct,
        String rSnippet, String aSnippet, String activeTool, String playerName, boolean done
    ) {
        JsonObject hud = new JsonObject();
        hud.addProperty("callNumber", callNo);
        hud.addProperty("phase", phase);
        hud.addProperty("reasoningEffort", effort);
        hud.addProperty("elapsedMs", elapsedMs);
        if (ttftMs != null) hud.addProperty("ttftWallMs", ttftMs);
        if (tps != null) {
            if (done) hud.addProperty("serverTps", tps);
            else hud.addProperty("liveTps", tps);
        }
        if (rTok != null) hud.addProperty("reasoningTokens", rTok);
        if (oTok != null) hud.addProperty("outputTokens", oTok);
        if (compTok != null) hud.addProperty("completionTokens", compTok);
        if (liveTok != null) hud.addProperty("liveTokens", liveTok);
        if (cachePct != null) hud.addProperty("cacheHitPct", cachePct);
        if (rSnippet != null && !rSnippet.isEmpty()) hud.addProperty("reasoningSnippet", rSnippet);
        if (aSnippet != null && !aSnippet.isEmpty()) hud.addProperty("answerSnippet", aSnippet);
        if (activeTool != null && !activeTool.isEmpty()) hud.addProperty("activeTool", activeTool);
        hud.addProperty("playerName", playerName);
        hud.addProperty("done", done);
        Bukkit.getScheduler().runTask(plugin, () -> mainThreadAction.apply("telemetry", hud));
    }

    private void sendReplyOnce(String playerName, String raw, boolean filmMode, Set<String> sentReplies, boolean force) {
        String clean = sanitizeReply(raw);
        if (clean.isEmpty() || filmMode) return;
        if (!force) {
            if (sentReplies.contains(clean)) return;
            if (!sentReplies.isEmpty()) {
                List<String> prevList = new ArrayList<>(sentReplies);
                if (String.join("\n\n", prevList).equals(clean) || String.join("\n", prevList).equals(clean)) return;
                String remainder = clean;
                for (String prev : prevList) {
                    if (remainder.equals(prev)) { remainder = ""; break; }
                    if (remainder.startsWith(prev + "\n") || remainder.startsWith(prev + "\r\n")) {
                        remainder = remainder.substring(prev.length()).trim();
                    }
                }
                if (remainder.isEmpty() || sentReplies.contains(remainder)) return;
                clean = remainder;
            }
        }
        sentReplies.add(clean);
        notifyChat(playerName, clean, false);
    }

    private void notifyChat(String playerName, String message, boolean filmMode) {
        if (filmMode || message == null || message.isBlank()) return;
        String text = message.trim();
        for (int start = 0; start < text.length(); start += 2048) {
            String chunk = text.substring(start, Math.min(text.length(), start + 2048));
            JsonObject p = new JsonObject();
            p.addProperty("playerName", playerName);
            p.addProperty("message", chunk);
            Bukkit.getScheduler().runTask(plugin, () -> mainThreadAction.apply("notify_status", p));
        }
    }

    private ProviderResolved resolveActiveProvider() {
        String active = getActiveModelRef();
        int slash = active.indexOf('/');
        if (slash <= 0) {
            throw new IllegalStateException("Invalid standalone.activeModel in config.yml: " + active);
        }
        String providerId = active.substring(0, slash);
        String modelId = active.substring(slash + 1);
        ConfigurationSection pSec = plugin.getConfig().getConfigurationSection("standalone.providers." + providerId);
        if (pSec == null) {
            throw new IllegalStateException("Provider '" + providerId + "' not found in config.yml");
        }
        Map<String, String> dotEnv = loadDotEnvMap();
        String baseUrl = pSec.getString("baseUrl", "");
        if ("opendesign".equalsIgnoreCase(providerId)) {
            String ov = lookupEnv("OPENDESIGN_BASE_URL", dotEnv);
            if (!ov.isBlank()) baseUrl = ov;
        } else if ("opencode-go".equalsIgnoreCase(providerId)) {
            String ov = lookupEnv("OPENCODE_GO_BASE_URL", dotEnv);
            if (!ov.isBlank()) baseUrl = ov;
        } else if ("openrouter".equalsIgnoreCase(providerId)) {
            String ov = lookupEnv("OPENROUTER_BASE_URL", dotEnv);
            if (!ov.isBlank()) baseUrl = ov;
        }
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("Provider '" + providerId + "' has no baseUrl in config.yml");
        }
        String apiKeyEnv = pSec.getString("apiKeyEnv", "");
        String apiKey = resolveProviderApiKey(pSec, providerId, dotEnv);
        boolean supportsImage = true;
        for (Map<?, ?> entry : pSec.getMapList("models")) {
            if (modelId.equals(String.valueOf(entry.get("id")))) {
                if (entry.get("supportsImage") != null) {
                    supportsImage = Boolean.parseBoolean(String.valueOf(entry.get("supportsImage")));
                }
                break;
            }
        }
        return new ProviderResolved(providerId, modelId, baseUrl, apiKey, apiKeyEnv, supportsImage);
    }

    private String resolveProviderApiKey(ConfigurationSection pSec, String providerId, Map<String, String> dotEnv) {
        String raw = pSec.getString("apiKey", "").trim();
        if (!raw.isEmpty() && !(raw.startsWith("${") && raw.endsWith("}"))) {
            return raw;
        }
        String envName = pSec.getString("apiKeyEnv", "").trim();
        if (raw.startsWith("${") && raw.endsWith("}")) {
            envName = raw.substring(2, raw.length() - 1).trim();
        }
        if (!envName.isEmpty()) {
            String val = lookupEnv(envName, dotEnv);
            if (!val.isBlank()) return val;
        }
        if ("opencode-go".equalsIgnoreCase(providerId)) {
            return lookupEnv("OPENCODE_API_KEY", dotEnv);
        }
        return "";
    }

    private String lookupEnv(String key, Map<String, String> dotEnv) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        return dotEnv.getOrDefault(key, "").trim();
    }

    private Map<String, String> loadDotEnvMap() {
        Map<String, String> out = new HashMap<>();
        Path dataDir = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path serverDir = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        List<Path> candidates = new ArrayList<>();
        candidates.add(dataDir.resolve(".env"));
        candidates.add(serverDir.resolve(".env"));
        if (serverDir.getParent() != null) {
            candidates.add(serverDir.getParent().resolve(".env"));
        }
        for (Path p : candidates) {
            if (!Files.isRegularFile(p)) continue;
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                    int eq = trimmed.indexOf('=');
                    if (eq > 0) {
                        String k = trimmed.substring(0, eq).trim();
                        String v = trimmed.substring(eq + 1).trim();
                        if (!k.isEmpty() && !v.isEmpty()) out.putIfAbsent(k, v);
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static ChatMsg toolResultMsg(String toolCallId, JsonObject payload) {
        ChatMsg m = ChatMsg.text("tool", GSON.toJson(payload));
        m.toolCallId = toolCallId;
        return m;
    }

    private static void pruneHistory(List<ChatMsg> history) {
        int max = 24;
        if (history.size() > max) {
            int cut = history.size() - max;
            while (cut < history.size() && !"user".equals(history.get(cut).role)) cut++;
            if (cut > 0 && cut < history.size()) {
                history.subList(0, cut).clear();
            }
        }
        int compactLimit = Math.max(0, history.size() - 6);
        for (int i = 0; i < compactLimit; i++) {
            ChatMsg m = history.get(i);
            if ("assistant".equals(m.role)) {
                m.reasoningContent = null;
            } else if ("tool".equals(m.role) && m.content != null && m.content.isJsonPrimitive()) {
                String s = m.content.getAsString();
                if (s.length() > 1600) m.content = new com.google.gson.JsonPrimitive(s.substring(0, 1400) + "...(truncated)");
            } else if ("user".equals(m.role) && m.content != null && m.content.isJsonArray()) {
                m.content = new com.google.gson.JsonPrimitive("[Previous screenshot omitted]");
            }
        }
    }

    private static String sanitizeReply(String text) {
        if (text == null) return "";
        String s = THINK_BLOCK.matcher(text).replaceAll("");
        s = THINK_TAG.matcher(s).replaceAll("");
        return s.trim();
    }

    private static String tailSnippet(String text, int maxLen) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLen) return clean;
        return "..." + clean.substring(clean.length() - (maxLen - 3));
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String formatSurroundingSummary(JsonObject ctx) {
        if (ctx == null || !ctx.has("anchor")) return "[World snapshot unavailable]";
        JsonObject a = ctx.getAsJsonObject("anchor");
        StringBuilder sb = new StringBuilder();
        sb.append("[World snapshot — Anchor locked at player gaze]\n");
        sb.append("- Origin (world): ").append(a.get("origin")).append(" in ")
            .append(a.has("world") ? a.get("world").getAsString() : "world")
            .append(", facing ").append(a.has("cardinal") ? a.get("cardinal").getAsString() : "NORTH")
            .append(" (MC ").append(ctx.has("minecraftVersion") ? ctx.get("minecraftVersion").getAsString() : "1.21").append(")\n");
        sb.append("- Local basis: forwardVec=").append(a.get("forwardVec")).append(", rightVec=").append(a.get("rightVec")).append("\n");
        sb.append("- Biome: ").append(ctx.has("biome") ? ctx.get("biome").getAsString() : "plains")
            .append(", worldTime: ").append(ctx.has("worldTime") ? ctx.get("worldTime").getAsLong() : 0).append("\n");
        if (ctx.has("terrain") && ctx.get("terrain").isJsonObject()) {
            JsonObject t = ctx.getAsJsonObject("terrain");
            sb.append("- Ground surface: ").append(t.has("dominantSurfaceBlock") ? t.get("dominantSurfaceBlock").getAsString() : "grass_block")
                .append(", minRelY=").append(t.has("minRelY") ? t.get("minRelY").getAsInt() : 0)
                .append(", maxRelY=").append(t.has("maxRelY") ? t.get("maxRelY").getAsInt() : 0).append("\n");
        }
        if (ctx.has("recentStructures") && ctx.get("recentStructures").isJsonArray() && !ctx.getAsJsonArray("recentStructures").isEmpty()) {
            sb.append("- Recent structures (pass anchorId to edit in their own local coords): ")
                .append(GSON.toJson(ctx.get("recentStructures"))).append("\n");
        }
        if (ctx.has("nearby") && ctx.get("nearby").isJsonObject()) {
            sb.append("- Nearby summary: ").append(GSON.toJson(ctx.get("nearby"))).append("\n");
        }
        return sb.toString().trim();
    }

    private static String renderVoxelSnapshotPngBase64(JsonObject snap) {
        int scale = 8;
        int w = snap.has("width") ? snap.get("width").getAsInt() : 48;
        int h = snap.has("height") ? snap.get("height").getAsInt() : 48;
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(20, 24, 34));
        g.fillRect(0, 0, w * scale, h * scale);

        JsonArray grid = snap.has("grid") && snap.get("grid").isJsonArray() ? snap.getAsJsonArray("grid") : new JsonArray();
        for (int row = 0; row < grid.size() && row < h; row++) {
            JsonArray rArr = grid.get(row).getAsJsonArray();
            for (int col = 0; col < rArr.size() && col < w; col++) {
                JsonArray cell = rArr.get(col).getAsJsonArray();
                String mat = cell.get(0).getAsString();
                int relY = cell.get(1).getAsInt();
                g.setColor(colorForMaterial(mat, relY));
                g.fillRect(col * scale, (h - 1 - row) * scale, scale, scale);
            }
        }
        g.dispose();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException ex) {
            return "";
        }
    }

    private static Color colorForMaterial(String mat, int relY) {
        Color base;
        if (mat.contains("grass") || mat.contains("leaves") || mat.contains("moss")) base = new Color(92, 165, 78);
        else if (mat.contains("water")) base = new Color(54, 116, 210);
        else if (mat.contains("lava") || mat.contains("magma")) base = new Color(235, 102, 34);
        else if (mat.contains("wood") || mat.contains("planks") || mat.contains("log") || mat.contains("oak") || mat.contains("spruce")) base = new Color(160, 120, 74);
        else if (mat.contains("glass") || mat.contains("ice") || mat.contains("diamond")) base = new Color(115, 220, 232);
        else if (mat.contains("quartz") || mat.contains("white") || mat.contains("snow")) base = new Color(232, 230, 224);
        else if (mat.contains("gold") || mat.contains("glow") || mat.contains("lantern") || mat.contains("torch")) base = new Color(242, 206, 86);
        else if (mat.contains("stone") || mat.contains("brick") || mat.contains("deepslate")) base = new Color(136, 140, 146);
        else if (mat.equals("air")) base = new Color(26, 32, 46);
        else base = new Color(172, 152, 134);

        int shade = Math.max(-45, Math.min(45, relY * 3));
        return new Color(
            Math.max(0, Math.min(255, base.getRed() + shade)),
            Math.max(0, Math.min(255, base.getGreen() + shade)),
            Math.max(0, Math.min(255, base.getBlue() + shade))
        );
    }

    private String loadResourceText(String path, String fallback) {
        try (InputStream in = StandaloneAgent.class.getResourceAsStream(path)) {
            if (in == null) return fallback;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return fallback;
        }
    }

    private JsonArray loadResourceJsonArray(String path) {
        try (InputStream in = StandaloneAgent.class.getResourceAsStream(path)) {
            if (in == null) return new JsonArray();
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (Exception ex) {
            return new JsonArray();
        }
    }
}
