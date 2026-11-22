package dev.minecraftharness.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the user's own `claude` CLI as a child process (subscription login). The plugin never
 * reads or forwards an OAuth token: the CLI uses its own login. The CLI reaches the Minecraft
 * tools through the plugin's loopback MCP server; its built-in tools are turned off.
 */
public final class ClaudeCliBackend {

    public static final String MCP_SERVER_NAME = "minecraft";
    public static final String TOOL_PREFIX = "mcp__" + MCP_SERVER_NAME + "__";

    public record Settings(String binary, File workDir, String mcpUrl, String model, int idleTimeoutSeconds) {}

    /** What a finished run looked like; used for retry decisions. */
    record Outcome(boolean ok, boolean authFailed, boolean sessionMissing, boolean flagRejected, String text, String error) {}

    private final ToolHost host;
    private final String systemPrompt;
    private final AtomicInteger callCounter;
    private final AtomicReference<Process> active = new AtomicReference<>();
    private volatile StreamDebugLog debug = new StreamDebugLog(null);

    /** Null disables the raw stream log. */
    public void setDebugFile(File f) {
        debug = new StreamDebugLog(f);
    }

    public ClaudeCliBackend(ToolHost host, String systemPrompt, AtomicInteger callCounter) {
        this.host = host;
        this.systemPrompt = systemPrompt;
        this.callCounter = callCounter;
    }

    // ---- pure helpers (unit-tested) -------------------------------------------------------

    public static String mcpConfigJson(String url) {
        JsonObject server = new JsonObject();
        server.addProperty("type", "http");
        server.addProperty("url", url);
        JsonObject servers = new JsonObject();
        servers.add(MCP_SERVER_NAME, server);
        JsonObject root = new JsonObject();
        root.add("mcpServers", servers);
        return root.toString();
    }

    public static String defaultBinary() {
        String env = System.getenv("MH_CLAUDE_BIN");
        if (env != null && !env.isBlank()) return env.trim();
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "claude.exe" : "claude";
    }

    public static List<String> buildCommand(Settings s, String systemPrompt, String sessionId, boolean resume, String effort, boolean withEffort) {
        List<String> c = new ArrayList<>();
        c.add(s.binary());
        c.add("-p");
        c.add("--output-format");
        c.add("stream-json");
        c.add("--include-partial-messages");
        c.add("--verbose");
        c.add("--model");
        c.add(s.model());
        if (withEffort && effort != null) {
            c.add("--effort");
            c.add(effort);
        }
        c.add("--tools");
        c.add("");
        c.add("--strict-mcp-config");
        c.add("--mcp-config");
        c.add(mcpConfigJson(s.mcpUrl()));
        c.add("--allowedTools");
        c.add(TOOL_PREFIX + "*");
        c.add("--permission-mode");
        c.add("dontAsk");
        c.add("--setting-sources");
        c.add("");
        c.add("--disable-slash-commands");
        c.add("--system-prompt");
        c.add(systemPrompt);
        c.add(resume ? "--resume" : "--session-id");
        c.add(sessionId);
        return c;
    }

    /** Pulls the visible text out of an "assistant" stream-json message. */
    public static String assistantText(JsonObject msg) {
        StringBuilder sb = new StringBuilder();
        if (msg.has("message") && msg.get("message").isJsonObject()) {
            JsonObject m = msg.getAsJsonObject("message");
            if (m.has("content") && m.get("content").isJsonArray()) {
                for (JsonElement el : m.getAsJsonArray("content")) {
                    if (el.isJsonObject() && "text".equals(str(el.getAsJsonObject(), "type"))) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(str(el.getAsJsonObject(), "text"));
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Checks the init message: only our MCP tools, and the MCP server must be connected. */
    public static String checkInit(JsonObject init) {
        boolean connected = false;
        if (init.has("mcp_servers") && init.get("mcp_servers").isJsonArray()) {
            for (JsonElement el : init.getAsJsonArray("mcp_servers")) {
                if (el.isJsonObject() && MCP_SERVER_NAME.equals(str(el.getAsJsonObject(), "name"))) {
                    connected = "connected".equals(str(el.getAsJsonObject(), "status"));
                }
            }
        }
        if (!connected) return "claude CLI がプラグインの MCP サーバーに接続できませんでした。";
        if (init.has("tools") && init.get("tools").isJsonArray()) {
            for (JsonElement el : init.getAsJsonArray("tools")) {
                String t = el.isJsonPrimitive() ? el.getAsString() : "";
                if (!t.startsWith(TOOL_PREFIX)) return "claude CLI の標準ツール(" + t + ")が有効のままです。";
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    // ---- run ------------------------------------------------------------------------------

    public void cancel() {
        Process p = active.get();
        if (p != null) killTree(p);
    }

    static void killTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
            // best effort
        }
        p.destroyForcibly();
    }

    public void runTurn(Settings cfg, TurnInput in) {
        HarnessAgent.Session session = in.session();
        String effort = ModelCatalog.effortFor(in.reasoningLevel());
        boolean withEffort = true;
        boolean resume = session.cliStarted;
        int callNo = callCounter.incrementAndGet();
        String player = in.playerName();

        for (int attempt = 0; attempt < 3; attempt++) {
            Outcome o = runOnce(cfg, in, callNo, effort, withEffort, resume);
            if (in.cancelled().getAsBoolean()) {
                host.notifyChat(player, "ビルドを中断しました。", in.filmMode());
                host.progress().cancelRunning(player, "/mh stop で停止");
                return;
            }
            if (o.ok) {
                session.cliStarted = true;
                return;
            }
            if (o.sessionMissing && resume) {
                resume = false;
                session.renewCliId();
                continue;
            }
            if (o.flagRejected && withEffort) {
                withEffort = false;
                continue;
            }
            if (o.authFailed) {
                host.notifyChat(player, "claude CLI にログインしていません。サーバー上で `claude` を起動して `/login` を実行してください。", false);
                host.progress().fail(player, "claude CLI 未ログイン");
            } else {
                host.notifyChat(player, "claude CLI でエラーが出ました: " + ToolHost.truncate(o.error == null ? "" : o.error, 300), false);
                host.progress().fail(player, "claude CLI エラー");
            }
            return;
        }
    }

    private Outcome runOnce(Settings cfg, TurnInput in, int callNo, String effort, boolean withEffort, boolean resume) {
        String player = in.playerName();
        String level = in.reasoningLevel();
        long t0 = System.currentTimeMillis();

        List<String> cmd = buildCommand(cfg, systemPrompt, in.session().cliId(), resume, effort, withEffort);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cfg.workDir());
        pb.environment().remove("ANTHROPIC_API_KEY");
        pb.environment().remove("ANTHROPIC_AUTH_TOKEN");
        pb.redirectErrorStream(false);

        Process proc;
        try {
            cfg.workDir().mkdirs();
            proc = pb.start();
        } catch (IOException ex) {
            return new Outcome(false, false, false, false, "",
                "`" + cfg.binary() + "` を起動できません(" + ex.getMessage() + ")。claude CLI をインストールするか MH_CLAUDE_BIN で場所を指定してください。");
        }
        active.set(proc);
        final StreamDebugLog dbg = debug;
        dbg.line("start", "pid=" + proc.pid() + " player=" + player + " resume=" + resume + " effort=" + (withEffort ? effort : "-"));

        long[] lastActivity = {System.currentTimeMillis()};
        boolean[] timedOut = {false};
        Thread watchdog = new Thread(() -> {
            try {
                while (proc.isAlive()) {
                    Thread.sleep(1000);
                    if (in.cancelled().getAsBoolean()) {
                        killTree(proc);
                        return;
                    }
                    if (System.currentTimeMillis() - lastActivity[0] > cfg.idleTimeoutSeconds() * 1000L) {
                        timedOut[0] = true;
                        killTree(proc);
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "MinecraftHarness-claude-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        StringBuilder stderr = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    dbg.line("stderr", l);
                    if (stderr.length() < 4000) stderr.append(l).append('\n');
                }
            } catch (IOException ignored) {
                // process gone
            }
        }, "MinecraftHarness-claude-stderr");
        errReader.setDaemon(true);
        errReader.start();

        try (OutputStream os = proc.getOutputStream()) {
            os.write(in.userMessage().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // the reader below reports the failure
        }

        boolean authFailed = false;
        boolean isError = false;
        String resultText = "";
        String errorText = "";
        String initProblem = null;
        long[] firstToken = {-1};
        StringBuilder reasoning = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        int chars = 0;
        long lastHud = 0;
        String phase = "QUEUED";
        String activeTool = "";

        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                lastActivity[0] = System.currentTimeMillis();
                if (line.isBlank()) continue;
                dbg.line("out", line.length() > 4000 ? line.substring(0, 4000) + "…" : line);
                    host.progress().activity(player);
                JsonObject j;
                try {
                    JsonElement el = JsonParser.parseString(line);
                    if (!el.isJsonObject()) continue;
                    j = el.getAsJsonObject();
                } catch (Exception ex) {
                    continue;
                }
                String type = str(j, "type");
                switch (type) {
                    case "system" -> {
                        if ("init".equals(str(j, "subtype"))) {
                            in.turn().stats.setModel(str(j, "model"), level,
                                "none".equals(str(j, "apiKeySource")) ? "サブスクリプション (claude CLI)"
                                    : str(j, "apiKeySource").isEmpty() ? "claude CLI" : "claude CLI (APIキー)");
                            initProblem = checkInit(j);
                            if (initProblem != null) killTree(proc);
                        }
                    }
                    case "stream_event" -> {
                        JsonObject ev = j.has("event") && j.get("event").isJsonObject() ? j.getAsJsonObject("event") : null;
                        if (ev == null) break;
                        String et = str(ev, "type");
                        if ("content_block_start".equals(et) && ev.has("content_block") && ev.get("content_block").isJsonObject()) {
                            JsonObject cb = ev.getAsJsonObject("content_block");
                            if ("tool_use".equals(str(cb, "type"))) {
                                phase = "TOOL_CALLING";
                                activeTool = str(cb, "name").replace(TOOL_PREFIX, "");
                                host.progress().toolStreaming(player, activeTool);
                            }
                        } else if ("content_block_delta".equals(et) && ev.has("delta") && ev.get("delta").isJsonObject()) {
                            JsonObject d = ev.getAsJsonObject("delta");
                            String dt = str(d, "type");
                            if ("thinking_delta".equals(dt)) {
                                String t = str(d, "thinking");
                                reasoning.append(t);
                                chars += t.length();
                                phase = "REASONING";
                                host.progress().thinking(player);
                            } else if ("text_delta".equals(dt)) {
                                String t = str(d, "text");
                                answer.append(t);
                                chars += t.length();
                                phase = "RESPONDING";
                                host.progress().responding(player, (int) Math.round(chars / 3.6));
                            } else if ("input_json_delta".equals(dt)) {
                                chars += str(d, "partial_json").length();
                                phase = "TOOL_CALLING";
                            }
                        }
                        long now = System.currentTimeMillis();
                        if (firstToken[0] < 0 && chars > 0) firstToken[0] = now;
                        if (firstToken[0] >= 0 && now - lastHud >= 150) {
                            lastHud = now;
                            int est = (int) Math.round(chars / 3.6);
                            double tps = Math.round((est / Math.max(0.05, (now - firstToken[0]) / 1000.0)) * 10.0) / 10.0;
                            host.emitHud(new ToolHost.HudData(callNo, phase, level, now - t0, firstToken[0] - t0, tps,
                                null, null, null, est, null, ToolHost.tailSnippet(reasoning.toString(), 96),
                                "RESPONDING".equals(phase) ? ToolHost.tailSnippet(answer.toString(), 80) : "",
                                "TOOL_CALLING".equals(phase) ? activeTool : "", player, false));
                        }
                    }
                    case "assistant" -> {
                        if ("authentication_failed".equals(str(j, "error"))) authFailed = true;
                        String text = assistantText(j);
                        if (!text.isBlank() && !authFailed) {
                            in.turn().recordRaw(text);
                            host.sendReplyOnce(player, text, in.filmMode(), in.turn().sentReplies);
                        }
                        answer.setLength(0);
                    }
                    case "result" -> {
                        isError = j.has("is_error") && j.get("is_error").isJsonPrimitive() && j.get("is_error").getAsBoolean();
                        resultText = str(j, "result");
                        readUsage(j, in.turn().stats);
                        if (isError) errorText = resultText;
                        if (str(j, "subtype").startsWith("error")) {
                            isError = true;
                            if (errorText.isEmpty()) errorText = str(j, "subtype");
                        }
                    }
                    default -> { }
                }
            }
        } catch (IOException ex) {
            errorText = ex.getMessage() == null ? "stream read failed" : ex.getMessage();
            isError = true;
        }

        int exit = -1;
        try {
            exit = proc.waitFor();
            dbg.line("exit", "code=" + exit + " timedOut=" + timedOut[0] + " cancelled=" + in.cancelled().getAsBoolean());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            killTree(proc);
        }
        active.compareAndSet(proc, null);
        watchdog.interrupt();
        try {
            errReader.join(500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        long done = System.currentTimeMillis();
        host.emitHud(callNo, isError || exit != 0 ? "ERROR" : "DONE", level, done - t0, "", player, true);

        if (in.cancelled().getAsBoolean()) return new Outcome(false, false, false, false, "", "cancelled");
        if (initProblem != null) return new Outcome(false, false, false, false, "", initProblem);
        if (timedOut[0]) return new Outcome(false, false, false, false, "", "応答が " + cfg.idleTimeoutSeconds() + " 秒止まったので中断しました。");

        String all = (errorText + "\n" + stderr).toLowerCase(Locale.ROOT);
        if (authFailed || all.contains("authentication") || all.contains("/login") || all.contains("not logged in") || all.contains("invalid api key")) {
            return new Outcome(false, true, false, false, resultText, errorText);
        }
        if (all.contains("no conversation found")) return new Outcome(false, false, true, false, resultText, errorText);
        if (isError || exit != 0) {
            boolean flag = all.contains("effort") || all.contains("unknown option");
            String err = errorText.isEmpty() ? stderr.toString().trim() : errorText;
            return new Outcome(false, false, false, flag, resultText, err.isEmpty() ? "exit " + exit : err);
        }
        return new Outcome(true, false, false, false, resultText, "");
    }

    /** Reads the result event's usage block; fields the CLI did not send stay unknown. */
    static void readUsage(JsonObject result, dev.minecraftharness.summary.TurnStats stats) {
        if (!result.has("usage") || !result.get("usage").isJsonObject()) return;
        JsonObject u = result.getAsJsonObject("usage");
        long thinking = -1;
        if (u.has("output_tokens_details") && u.get("output_tokens_details").isJsonObject()) {
            thinking = num(u.getAsJsonObject("output_tokens_details"), "thinking_tokens", -1);
        }
        stats.addUsage(num(u, "input_tokens", 0), num(u, "output_tokens", 0),
            num(u, "cache_read_input_tokens", 0), num(u, "cache_creation_input_tokens", 0), thinking);
    }

    private static long num(JsonObject o, String key, long fallback) {
        if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return fallback;
        try {
            return o.get(key).getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
