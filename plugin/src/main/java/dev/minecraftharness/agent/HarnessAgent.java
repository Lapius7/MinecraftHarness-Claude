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
        final dev.minecraftharness.review.ReviewLevel review;
        final long enqueuedAt = System.currentTimeMillis();
        volatile long startedAt;
        final dev.minecraftharness.summary.TurnStats stats = new dev.minecraftharness.summary.TurnStats();
        volatile boolean cancelled;

        Job(String player, String prompt, boolean film, String level, BuildAnchor anchor,
            dev.minecraftharness.review.ReviewLevel review) {
            this.review = review;
            this.player = player;
            this.prompt = prompt;
            this.film = film;
            this.level = level;
            this.anchor = anchor;
        }
    }

    /** Set by the plugin: a player's own review / detail setting, falling back to config.yml. */
    public volatile java.util.function.Function<String, dev.minecraftharness.review.ReviewLevel> reviewLevelFor =
        p -> dev.minecraftharness.review.ReviewLevel.OFF;
    public volatile java.util.function.Function<String, dev.minecraftharness.summary.DetailLevel> detailLevelFor =
        p -> dev.minecraftharness.summary.DetailLevel.SHORT;

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
        return enqueuePrompt(playerName, prompt, filmMode, reasoningLevel, anchor, urgent, null);
    }

    /** {@code review} null uses the player's own setting (or the config default). */
    public Enqueued enqueuePrompt(String playerName, String prompt, boolean filmMode, String reasoningLevel,
                                  BuildAnchor anchor, boolean urgent, dev.minecraftharness.review.ReviewLevel review) {
        if (shutdown) return new Enqueued(EnqueueStatus.STOPPED, 0, queueLimit());
        int limit = queueLimit();
        dev.minecraftharness.review.ReviewLevel rl = review != null ? review : reviewLevelFor.apply(playerName);
        Job job = new Job(playerName, prompt, filmMode, reasoningLevel, anchor, rl);
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

    /** Stops this player's running prompt (if it is theirs) and drops their waits; others are left alone. */
    public void stopPlayer(String playerName) {
        boolean mine;
        synchronized (queueLock) {
            mine = running != null && same(running.player, playerName);
            if (mine) running.cancelled = true;
        }
        clearQueue(playerName);
        if (mine) {
            host.progress().cancel(playerName, "退出のため停止");
            cli.cancel();
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
        job.startedAt = System.currentTimeMillis();

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
        dev.minecraftharness.summary.TurnStats stats = job.stats;
        stats.setModel(model.id(), level, model.backend() == ModelCatalog.Backend.API ? "Anthropic API (APIキー)" : "claude CLI");
        ToolHost.Turn turn = new ToolHost.Turn(playerName, filmMode, level, stats);
        boolean ran = runBackend(playerName, userMessage, filmMode, level, session, turn, job, model);
        if (!ran) return;

        long mainTokens = stats.budgetTokens();
        dev.minecraftharness.review.ReviewLevel rl = job.review == null ? dev.minecraftharness.review.ReviewLevel.OFF : job.review;
        boolean built = stats.built() && turn.allToolsOk;
        if (rl != dev.minecraftharness.review.ReviewLevel.OFF && built && !job.cancelled && !shutdown) {
            reviewRounds(job, rl, session, model, prompt, mainTokens);
        }
        sendSummary(job, model);
    }

    /** Runs one backend turn. @return false when it could not start (the reason is already reported). */
    private boolean runBackend(String playerName, String userMessage, boolean filmMode, String level, Session session,
        ToolHost.Turn turn, Job job, ModelCatalog.Model model) {
        FileConfiguration c = cfg();
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
                return false;
            }
            String bin = c.getString("claude.binary", "");
            File work = new File(plugin.getDataFolder(), "claude-cli");
            cli.setDebugFile(c.getBoolean("claude.debugLog", false) ? new File(plugin.getDataFolder(), "logs/cli-stream.log") : null);
            cli.runTurn(new ClaudeCliBackend.Settings(
                bin == null || bin.isBlank() ? ClaudeCliBackend.defaultBinary() : bin.trim(),
                work, url, model.id(), Math.max(30, c.getInt("claude.idleTimeoutSeconds", 300))), in);
        }
        return true;
    }

    /** Limits for a level from config.yml {@code review.levels.<id>}, falling back to built-in defaults. */
    public dev.minecraftharness.review.ReviewLimits reviewLimits(dev.minecraftharness.review.ReviewLevel level) {
        FileConfiguration c = cfg();
        String base = "review.levels." + level.id() + ".";
        int rounds = c.getInt(base + "rounds", level.defaultRounds() == Integer.MAX_VALUE ? 0 : level.defaultRounds());
        int seconds = c.getInt(base + "seconds", level.defaultSeconds());
        int tokens = c.getInt(base + "tokens", level.defaultTokens());
        return new dev.minecraftharness.review.ReviewLimits(rounds <= 0 ? Integer.MAX_VALUE : rounds,
            Math.max(1, seconds) * 1000L, Math.max(0, tokens));
    }

    private JsonObject reviewState(String anchorId) {
        JsonObject req = new JsonObject();
        if (anchorId != null) req.addProperty("anchorId", anchorId);
        return host.callMainThread("review_state", req);
    }

    private static long longOf(JsonObject o, String key, long fallback) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsLong() : fallback;
    }

    /** Waits (up to a minute) until the placement queue is empty, so the next measurement sees the finished build. */
    private void awaitBuildIdle(Job job) {
        long end = System.currentTimeMillis() + 60_000L;
        while (System.currentTimeMillis() < end && !shutdown && !job.cancelled) {
            JsonObject r = host.callMainThread("build_status", new JsonObject());
            if (r == null || !r.has("active") || !r.get("active").getAsBoolean()) return;
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void reviewRounds(Job job, dev.minecraftharness.review.ReviewLevel rl, Session session,
        ModelCatalog.Model model, String request, long tokensBefore) {
        String player = job.player;
        dev.minecraftharness.summary.TurnStats stats = job.stats;
        dev.minecraftharness.review.ReviewLoop loop =
            new dev.minecraftharness.review.ReviewLoop(rl, reviewLimits(rl));
        java.util.List<String> anchors = stats.anchors();
        String anchorId = anchors.isEmpty() ? null : anchors.get(anchors.size() - 1);
        long t0 = System.currentTimeMillis();
        dev.minecraftharness.review.ReviewLoop.Stop stop;
        awaitBuildIdle(job);
        while (true) {
            stop = loop.beforeRound(System.currentTimeMillis() - t0, stats.budgetTokens() - tokensBefore,
                job.cancelled || shutdown);
            if (stop != dev.minecraftharness.review.ReviewLoop.Stop.NONE) break;
            int round = loop.rounds() + 1;
            host.progress().review(player, round, loop.maxRounds(), "点検中・修正中");
            JsonObject pre = reviewState(anchorId);
            long snapsBefore = longOf(pre, "snapshots", -1);
            ToolHost.Turn rt = new ToolHost.Turn(player, job.film, job.level, stats);
            String msg = dev.minecraftharness.review.ReviewPrompt.build(rl, round, loop.maxLabel(), request,
                anchorId == null ? "" : anchorId);
            runBackend(player, msg, job.film, job.level, session, rt, job, model);
            if (job.cancelled || shutdown) {
                stop = loop.afterRound(null, true);
                break;
            }
            awaitBuildIdle(job);
            JsonObject post = reviewState(anchorId);
            dev.minecraftharness.review.ReviewReport.Parsed rep = dev.minecraftharness.review.ReviewReport.parse(rt.raw());
            boolean worseWorld = dev.minecraftharness.review.ReviewLoop.worseWorld(
                (int) longOf(pre, "blocks", -1), (int) longOf(pre, "floating", -1),
                (int) longOf(post, "blocks", -1), (int) longOf(post, "floating", -1));
            stop = loop.afterRound(new dev.minecraftharness.review.ReviewLoop.RoundResult(
                rep.reported(), rep.found(), rep.remaining(), Math.max(0, rep.found() - rep.remaining()), worseWorld));
            stats.addReview(rep.found(), rep.remaining(), rep.fixed());
            if (stop == dev.minecraftharness.review.ReviewLoop.Stop.WORSE) {
                long added = longOf(post, "snapshots", snapsBefore) - snapsBefore;
                if (snapsBefore >= 0 && added > 0) {
                    JsonObject u = new JsonObject();
                    u.addProperty("count", (int) Math.min(added, 16));
                    host.callMainThread("undo_many", u);
                }
            }
            if (stop != dev.minecraftharness.review.ReviewLoop.Stop.NONE) break;
        }
        stats.setReviewStop(stop.label());
        host.progress().reviewEnd(player);
        dev.minecraftharness.summary.TurnStats.Review r = stats.review();
        String note = "レビュー終了(" + stop.label() + "): " + (r == null ? 0 : r.rounds()) + " 回点検"
            + (r == null || r.rounds() == 0 ? "" : "、見つけた問題 " + r.found() + " 件、残り " + r.remaining() + " 件");
        host.notifyChat(player, note, job.film);
    }

    private void sendSummary(Job job, ModelCatalog.Model model) {
        dev.minecraftharness.summary.DetailLevel detail = detailLevelFor.apply(job.player);
        if (detail == dev.minecraftharness.summary.DetailLevel.OFF || job.film) return;
        dev.minecraftharness.progress.ProgressState.Timings t = host.progress().timings(job.player);
        long wait = Math.max(0, job.startedAt - job.enqueuedAt);
        if (t != null) {
            job.stats.setTiming(new dev.minecraftharness.summary.TurnStats.Timing(
                wait, t.thinkMs(), t.toolMs(), t.buildMs(), t.reviewMs()));
        }
        Double cost = null;
        if (model.backend() == ModelCatalog.Backend.API) {
            cost = dev.minecraftharness.summary.PriceTable.fromConfig(cfg().getConfigurationSection("pricing"))
                .cost(model.id(), job.stats.tokens());
        }
        host.sendComponent(job.player, dev.minecraftharness.summary.SummaryRenderer.render(detail, job.stats, cost));
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
