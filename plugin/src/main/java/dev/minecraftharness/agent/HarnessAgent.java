package dev.minecraftharness.agent;

import com.google.gson.JsonObject;
import dev.minecraftharness.context.SpatialContextProvider.BuildAnchor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/**
 * Runs player prompts one at a time on a worker thread and hands each to the active backend:
 * the claude CLI (subscription) or the Anthropic API (key). Owns per-player sessions and
 * the model/key settings stored in config.yml.
 */
public final class HarnessAgent {

    /** One conversation: an API message history and a CLI session id. */
    public static final class Session {
        public final AnthropicHistory history = new AnthropicHistory();
        public volatile boolean cliStarted;
        private volatile String cliId = UUID.randomUUID().toString();

        public String cliId() {
            return cliId;
        }

        /** Gives the CLI a fresh session id (used by clear and when the old one vanished). */
        public void renewCliId() {
            cliId = UUID.randomUUID().toString();
            cliStarted = false;
        }
    }

    private static final class PlayerState {
        String activeName = "main";
        final Map<String, Session> sessions = new LinkedHashMap<>();

        Session current() {
            return sessions.computeIfAbsent(activeName, k -> new Session());
        }
    }

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private final Plugin plugin;
    private final ToolHost host;
    private final Thread worker;
    private final Object queueLock = new Object();
    private final java.util.LinkedList<Job> pending = new java.util.LinkedList<>();
    private Job running;
    private final AtomicInteger callCounter = new AtomicInteger();
    private final Map<String, PlayerState> players = new ConcurrentHashMap<>();
    private final AnthropicBackend api;
    private final ClaudeCliBackend cli;
    private final String mcpToken;
    private volatile ToolHost.Turn currentTurn;
    private volatile boolean shutdown;
    private McpServer mcp;

    public HarnessAgent(Plugin plugin, BiFunction<String, JsonObject, JsonObject> mainThreadAction) {
        this.plugin = plugin;
        this.host = new ToolHost(plugin, mainThreadAction);
        String prompt = ToolHost.loadResourceText("/agent-prompt.md",
            "You are MinecraftHarness, an expert real-time Minecraft builder.");
        prompt = prompt + TonePrompt.addendum(plugin.getConfig().getString("agent.tone", TonePrompt.DEFAULT));
        this.api = new AnthropicBackend(host, prompt, callCounter);
        this.cli = new ClaudeCliBackend(host, prompt, callCounter);
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        this.mcpToken = HexFormat.of().formatHex(rnd);
        this.worker = new Thread(this::workLoop, "MinecraftHarness-Agent");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void shutdown() {
        shutdown = true;
        cli.cancel();
        worker.interrupt();
        synchronized (this) {
            if (mcp != null) mcp.stop();
            mcp = null;
        }
    }

    /** Stops the running turn and drops every waiting one. */
    public void cancelActive() {
        stop(true);
    }

    // ---- settings -------------------------------------------------------------------------

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public ModelCatalog.Model activeModel() {
        return ModelCatalog.resolve(cfg().getString("activeModel", ModelCatalog.DEFAULT_REF))
            .orElseGet(() -> ModelCatalog.resolve(ModelCatalog.DEFAULT_REF).orElseThrow());
    }

    /** @return the model that is now active, or empty if the reference is unknown. */
    public java.util.Optional<ModelCatalog.Model> setActiveModel(String ref) {
        java.util.Optional<ModelCatalog.Model> m = ModelCatalog.resolve(ref);
        m.ifPresent(model -> {
            cfg().set("activeModel", model.ref());
            plugin.saveConfig();
        });
        return m;
    }

    public void setApiKey(String key) {
        cfg().set("anthropic.apiKey", key == null ? "" : key.trim());
        plugin.saveConfig();
    }

    public boolean hasApiKey() {
        return !resolveApiKey().isBlank();
    }

    public String maskedApiKey() {
        return maskKey(resolveApiKey());
    }

    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "(未設定)";
        if (key.length() <= 8) return "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    /** config.yml, then the ANTHROPIC_API_KEY environment variable, then a .env file. */
    String resolveApiKey() {
        String k = cfg().getString("anthropic.apiKey", "");
        if (k != null && !k.isBlank()) return k.trim();
        String env = System.getenv("ANTHROPIC_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        String dot = loadDotEnv().get("ANTHROPIC_API_KEY");
        return dot == null ? "" : dot.trim();
    }

    private Map<String, String> loadDotEnv() {
        Map<String, String> out = new HashMap<>();
        File world = plugin.getServer().getWorldContainer();
        File[] candidates = {
            new File(plugin.getDataFolder(), ".env"),
            new File(world, ".env"),
            world.getAbsoluteFile().getParentFile() == null ? null : new File(world.getAbsoluteFile().getParentFile(), ".env")
        };
        for (File f : candidates) {
            if (f == null || !f.isFile()) continue;
            try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue;
                    int eq = t.indexOf('=');
                    String k = t.substring(0, eq).trim();
                    String v = t.substring(eq + 1).trim();
                    if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    out.putIfAbsent(k, v);
                }
            } catch (IOException ignored) {
                // unreadable .env is simply skipped
            }
        }
        return out;
    }

    // ---- sessions -------------------------------------------------------------------------

    private PlayerState state(String player) {
        return players.computeIfAbsent(player.toLowerCase(Locale.ROOT), k -> new PlayerState());
    }

    /** Drops the active conversation for a player (both backends). */
    public void clearSession(String player) {
        PlayerState st = state(player);
        synchronized (st) {
            st.sessions.remove(st.activeName);
        }
    }

    /** @return a reply for conversation commands (new, session ...), or null if not one. */
    public String handleConversationCommand(String playerName, String prompt) {
        String[] parts = prompt.trim().split("\\s+");
        if (parts.length == 0) return null;
        String verb = parts[0].toLowerCase(Locale.ROOT);
        boolean reset = ((verb.equals("new") || verb.equals("reset-chat") || verb.equals("reset_chat")) && parts.length == 1)
            || (verb.equals("history") && parts.length == 2 && parts[1].equalsIgnoreCase("reset"));
        if (!reset && !verb.equals("session")) return null;

        PlayerState st = state(playerName);
        synchronized (st) {
            if (reset) {
                st.sessions.remove(st.activeName);
                return "「" + st.activeName + "」の会話を新しくしました。";
            }
            if (parts.length == 1 || (parts.length == 2 && parts[1].equalsIgnoreCase("list"))) {
                st.current();
                return "現在: " + st.activeName + " ｜ セッション: " + String.join("、", st.sessions.keySet());
            }
            if (parts.length != 3 || !parts[1].equalsIgnoreCase("use")) {
                return "使い方: /mh session list または /mh session use <名前>";
            }
            String target = parts[2].trim();
            if (!target.matches("[\\p{L}\\p{N}_-]{1,32}")) {
                return "名前は文字・数字・ハイフン・下線で1〜32文字にしてください。";
            }
            st.activeName = target;
            st.current();
            return "現在のセッション: " + target;
        }
    }

    // ---- prompts --------------------------------------------------------------------------

    // ---- prompt queue -----------------------------------------------------------------------

    /** One accepted prompt. {@code cancelled} stops it whether it is waiting or running. */
    private static final class Job {
        final String player, prompt, level;
        final boolean film;
        final BuildAnchor anchor;
        volatile boolean cancelled;

        Job(String player, String prompt, boolean film, String level, BuildAnchor anchor) {
            this.player = player;
            this.prompt = prompt;
            this.film = film;
            this.level = level;
            this.anchor = anchor;
        }
    }

    public enum EnqueueStatus { ACCEPTED, DUPLICATE, FULL, STOPPED }

    /** {@code position} is 0 when the prompt starts right away, else its place among waiting prompts (1 = next). */
    public record Enqueued(EnqueueStatus status, int position, int limit) {}

    public static final int DEFAULT_QUEUE_LIMIT = 5;

    private int queueLimit() {
        return Math.max(1, cfg().getInt("queue.maxPerPlayer", DEFAULT_QUEUE_LIMIT));
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /**
     * Queues a prompt behind the running one. With {@code urgent} it goes first, and the player's own
     * running prompt is stopped (someone else's is left alone).
     */
    public Enqueued enqueuePrompt(String playerName, String prompt, boolean filmMode, String reasoningLevel,
                                  BuildAnchor anchor, boolean urgent) {
        if (shutdown) return new Enqueued(EnqueueStatus.STOPPED, 0, queueLimit());
        int limit = queueLimit();
        Job job = new Job(playerName, prompt, filmMode, reasoningLevel, anchor);
        boolean interrupt = false;
        int position;
        synchronized (queueLock) {
            Job last = null;
            int mine = 0;
            for (Job j : pending) {
                if (same(j.player, playerName)) { last = j; mine++; }
            }
            if (last == null && running != null && same(running.player, playerName)) last = running;
            if (running != null && same(running.player, playerName)) mine++;
            if (last != null && last.prompt.equals(prompt)) return new Enqueued(EnqueueStatus.DUPLICATE, 0, limit);
            if (mine >= limit) return new Enqueued(EnqueueStatus.FULL, 0, limit);
            host.progress().begin(playerName);
            if (urgent) {
                pending.addFirst(job);
                interrupt = running != null && same(running.player, playerName);
                if (interrupt) running.cancelled = true;
            } else {
                pending.addLast(job);
            }
            position = pending.indexOf(job) + 1;
            if (running == null && position == 1) position = 0;
            queueLock.notifyAll();
        }
        if (interrupt) cli.cancel();
        return new Enqueued(EnqueueStatus.ACCEPTED, position, limit);
    }

    /** Stops the running prompt; with {@code clearQueue} the waiting ones are dropped too. */
    public void stop(boolean clearQueue) {
        java.util.List<Job> dropped = new java.util.ArrayList<>();
        boolean hadRunning;
        synchronized (queueLock) {
            if (clearQueue) {
                dropped.addAll(pending);
                pending.clear();
            }
            hadRunning = running != null;
            if (running != null) running.cancelled = true;
        }
        for (Job j : dropped) j.cancelled = true;
        if (clearQueue) {
            for (Job j : dropped) host.progress().cancel(j.player, "/mh stop all で停止");
            if (hadRunning) host.progress().cancelAll("/mh stop all で停止");
        }
        cli.cancel();
    }

    public int pendingCount() {
        synchronized (queueLock) {
            return pending.size();
        }
    }

    /** Removes the player's waiting prompts. @return how many were removed. */
    public int clearQueue(String playerName) {
        java.util.List<Job> removed = new java.util.ArrayList<>();
        synchronized (queueLock) {
            pending.removeIf(j -> {
                if (same(j.player, playerName)) { removed.add(j); return true; }
                return false;
            });
        }
        for (Job j : removed) {
            j.cancelled = true;
            host.progress().dropQueued(playerName, "待ちを消しました");
        }
        return removed.size();
    }

    /** Lines for /mh queue: the running prompt first, then waiting ones with their owner. */
    public java.util.List<String> queueLines() {
        java.util.List<String> out = new java.util.ArrayList<>();
        synchronized (queueLock) {
            if (running != null) out.add("実行中: " + running.player + " - " + ToolHost.truncate(running.prompt, 40));
            int i = 1;
            for (Job j : pending) out.add("待ち " + (i++) + ": " + j.player + " - " + ToolHost.truncate(j.prompt, 40));
        }
        return out;
    }

    private void workLoop() {
        while (!shutdown) {
            Job job;
            synchronized (queueLock) {
                while (pending.isEmpty() && !shutdown) {
                    try {
                        queueLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (shutdown) return;
                job = pending.removeFirst();
                running = job;
            }
            try {
                if (job.cancelled) {
                    host.progress().cancelRunning(job.player, "/mh stop で停止");
                } else {
                    runPrompt(job.player, job.prompt, job.film, job.level, job.anchor, job);
                }
                host.progress().finish(job.player);
            } catch (Throwable t) {
                plugin.getLogger().warning("Prompt failed: " + t);
                host.notifyChat(job.player, "内部エラー: " + ToolHost.truncate(String.valueOf(t.getMessage()), 200), false);
                host.progress().fail(job.player, "内部エラー");
                host.progress().finish(job.player);
            } finally {
                currentTurn = null;
                synchronized (queueLock) {
                    running = null;
                }
            }
        }
    }

    private void runPrompt(String playerName, String prompt, boolean filmMode, String level, BuildAnchor anchor, Job job) {
        ModelCatalog.Model model = activeModel();
        FileConfiguration c = cfg();

        if (model.backend() == ModelCatalog.Backend.API && resolveApiKey().isBlank()) {
            host.notifyChat(playerName,
                "APIキーが未設定です。コンソールで `mh key anthropic <キー>` を実行するか、config.yml の anthropic.apiKey に設定してください。"
                    + "サブスクリプションで使うなら `/mh model cli/opus` に切り替えてください。", false);
            host.progress().fail(playerName, "APIキー未設定");
            return;
        }

        JsonObject begin = new JsonObject();
        begin.addProperty("playerName", playerName);
        begin.add("anchor", ToolHost.GSON.toJsonTree(anchor));
        JsonObject ctx = host.callMainThread("begin_prompt", begin);
        host.progress().thinking(playerName);
        String userMessage = ToolHost.formatSurroundingSummary(ctx) + "\n\nPlayer \"" + playerName + "\" says: " + prompt;

        PlayerState st = state(playerName);
        Session session;
        synchronized (st) {
            session = st.current();
        }
        ToolHost.Turn turn = new ToolHost.Turn(playerName, filmMode, level);
        currentTurn = turn;
        TurnInput in = new TurnInput(playerName, userMessage, filmMode, level, session, turn,
            () -> shutdown || job.cancelled);

        int maxSteps = Math.max(1, c.getInt("maxSteps", 12));
        if (model.backend() == ModelCatalog.Backend.API) {
            api.runTurn(new AnthropicBackend.Settings(resolveApiKey(),
                c.getString("anthropic.baseUrl", DEFAULT_BASE_URL), model.id(), maxSteps,
                Math.max(1024, c.getInt("anthropic.maxTokens", 32000))), in);
        } else {
            String url;
            try {
                url = ensureMcp(c.getInt("mcp.port", 0));
            } catch (IOException ex) {
                host.notifyChat(playerName, "MCP サーバーを起動できませんでした: " + ex.getMessage()
                    + "（config.yml の mcp.port を変えてみてください）", false);
                host.progress().fail(playerName, "MCP を起動できません");
                return;
            }
            String bin = c.getString("claude.binary", "");
            File work = new File(plugin.getDataFolder(), "claude-cli");
            cli.setDebugFile(c.getBoolean("claude.debugLog", false) ? new File(plugin.getDataFolder(), "logs/cli-stream.log") : null);
            cli.runTurn(new ClaudeCliBackend.Settings(
                bin == null || bin.isBlank() ? ClaudeCliBackend.defaultBinary() : bin.trim(),
                work, url, model.id(), Math.max(30, c.getInt("claude.idleTimeoutSeconds", 300))), in);
        }
    }

    private synchronized String ensureMcp(int port) throws IOException {
        if (mcp == null) {
            McpProtocol protocol = new McpProtocol("minecraft-harness", plugin.getDescription().getVersion(),
                host.tools(), McpServer.toolCaller(host, () -> currentTurn));
            McpServer s = new McpServer(protocol, mcpToken);
            s.start(port);
            mcp = s;
            plugin.getLogger().info("MCP server listening on 127.0.0.1:" + s.port());
        }
        return mcp.url();
    }

    /** One line for /mh status. */
    public String statusLine() {
        ModelCatalog.Model m = activeModel();
        String auth = m.backend() == ModelCatalog.Backend.API
            ? "APIキー: " + maskedApiKey()
            : "claude CLI (サブスクリプション)";
        return m.ref() + " / " + auth;
    }

    /** Short description of the player's current conversation for the help screen. */
    public String sessionSummary(String playerName) {
        PlayerState st = state(playerName);
        Session cur = st.sessions.get(st.activeName);
        boolean active = cur != null && (cur.cliStarted || cur.history.size() > 0);
        return st.activeName + (active ? "（会話あり）" : "（まだ空）");
    }

    public ToolHost host() {
        return host;
    }
}
