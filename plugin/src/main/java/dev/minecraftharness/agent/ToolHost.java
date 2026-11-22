package dev.minecraftharness.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Everything both backends share: the Minecraft tool definitions, the hop onto the server
 * main thread, tool dispatch (including ops already built while the model was still streaming),
 * chat replies and the telemetry HUD.
 */
public final class ToolHost {

    /** One build op that was placed while the model was still streaming its tool input. */
    public record StreamedOp(String coordMode, String anchorId, String fingerprint, int queued) {}

    /** Per-turn state the tool layer needs. */
    public static final class Turn {
        public final String playerName;
        public final boolean filmMode;
        public final String thinkingLevel;
        public final Set<String> sentReplies = new java.util.LinkedHashSet<>();
        public final List<StreamedOp> streamedOps = new ArrayList<>();
        /** Set when a build was started with finish=false and never closed. */
        public volatile boolean batchNeedsFinish;
        public volatile boolean sawObservation;
        public volatile boolean allToolsOk = true;
        public volatile String fastReply;

        public Turn(String playerName, boolean filmMode, String thinkingLevel) {
            this.playerName = playerName;
            this.filmMode = filmMode;
            this.thinkingLevel = thinkingLevel;
        }
    }

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final Set<String> OBSERVATION_TOOLS = Set.of("mc_inspect_world", "mc_take_photo", "mc_get_context");

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern THINK_TAG = Pattern.compile("(?i)</?think>");

    private final Plugin plugin;
    private final BiFunction<String, JsonObject, JsonObject> mainThreadAction;
    private final JsonArray tools;

    public ToolHost(Plugin plugin, BiFunction<String, JsonObject, JsonObject> mainThreadAction) {
        this.plugin = plugin;
        this.mainThreadAction = mainThreadAction;
        this.tools = loadTools();
    }

    /** Tool list as {name, description, input_schema}: the shape the Messages API and MCP both accept. */
    public JsonArray tools() {
        return tools;
    }

    public static String loadResourceText(String path, String fallback) {
        try (InputStream in = ToolHost.class.getResourceAsStream(path)) {
            if (in == null) return fallback;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return fallback;
        }
    }

    private static JsonArray loadTools() {
        try {
            return JsonParser.parseString(loadResourceText("/tools.json", "[]")).getAsJsonArray();
        } catch (Exception ex) {
            return new JsonArray();
        }
    }

    // ---- main thread ----------------------------------------------------------------------

    public JsonObject callMainThread(String action, JsonObject params) {
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

    // ---- chat / HUD -----------------------------------------------------------------------

    public void notifyChat(String playerName, String message, boolean filmMode) {
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

    /** Sends a reply once; later replies that merely extend an earlier one only send the new part. */
    public void sendReplyOnce(String playerName, String raw, boolean filmMode, Set<String> sentReplies) {
        String clean = sanitizeReply(raw);
        if (clean.isEmpty() || filmMode) return;
        synchronized (sentReplies) {
            if (sentReplies.contains(clean)) return;
            if (!sentReplies.isEmpty()) {
                List<String> prevList = new ArrayList<>(sentReplies);
                if (String.join("\n\n", prevList).equals(clean) || String.join("\n", prevList).equals(clean)) return;
                String remainder = clean;
                for (String prev : prevList) {
                    if (remainder.equals(prev)) {
                        remainder = "";
                        break;
                    }
                    if (remainder.startsWith(prev + "\n") || remainder.startsWith(prev + "\r\n")) {
                        remainder = remainder.substring(prev.length()).trim();
                    }
                }
                if (remainder.isEmpty() || sentReplies.contains(remainder)) return;
                clean = remainder;
            }
            sentReplies.add(clean);
        }
        notifyChat(playerName, clean, false);
    }

    public record HudData(
        int callNo, String phase, String effort, long elapsedMs, Long ttftMs, Double tps,
        Integer reasoningTokens, Integer outputTokens, Integer completionTokens, Integer liveTokens,
        Double cachePct, String reasoningSnippet, String answerSnippet, String activeTool,
        String playerName, boolean done
    ) {}

    public void emitHud(HudData d) {
        JsonObject hud = new JsonObject();
        hud.addProperty("callNumber", d.callNo());
        hud.addProperty("phase", d.phase());
        hud.addProperty("reasoningEffort", d.effort());
        hud.addProperty("elapsedMs", d.elapsedMs());
        if (d.ttftMs() != null) hud.addProperty("ttftWallMs", d.ttftMs());
        if (d.tps() != null) hud.addProperty(d.done() ? "serverTps" : "liveTps", d.tps());
        if (d.reasoningTokens() != null) hud.addProperty("reasoningTokens", d.reasoningTokens());
        if (d.outputTokens() != null) hud.addProperty("outputTokens", d.outputTokens());
        if (d.completionTokens() != null) hud.addProperty("completionTokens", d.completionTokens());
        if (d.liveTokens() != null) hud.addProperty("liveTokens", d.liveTokens());
        if (d.cachePct() != null) hud.addProperty("cacheHitPct", d.cachePct());
        if (d.reasoningSnippet() != null && !d.reasoningSnippet().isEmpty()) hud.addProperty("reasoningSnippet", d.reasoningSnippet());
        if (d.answerSnippet() != null && !d.answerSnippet().isEmpty()) hud.addProperty("answerSnippet", d.answerSnippet());
        if (d.activeTool() != null && !d.activeTool().isEmpty()) hud.addProperty("activeTool", d.activeTool());
        hud.addProperty("playerName", d.playerName());
        hud.addProperty("done", d.done());
        Bukkit.getScheduler().runTask(plugin, () -> mainThreadAction.apply("telemetry", hud));
    }

    public void emitHud(int callNo, String phase, String effort, long elapsedMs, String activeTool, String playerName, boolean done) {
        emitHud(new HudData(callNo, phase, effort, elapsedMs, null, null, null, null, null, null, null, "", "", activeTool, playerName, done));
    }

    // ---- tool dispatch --------------------------------------------------------------------

    /**
     * Runs one tool call. The result may carry {@code _imageBase64} (a PNG); callers must
     * take it out with {@link #takeImage(JsonObject)} before serialising the result.
     */
    public JsonObject dispatch(String toolName, JsonObject args, Turn turn) {
        String playerName = turn.playerName;
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
            out.addProperty("note", "Top-down voxel overview rendered by the plugin.");
            out.addProperty("_imageBase64", b64);
            return out;
        }

        if ("mc_exec_commands".equals(toolName) && args.has("observe") && args.get("observe").isJsonObject()) {
            return execWithObservation(args, playerName);
        }

        String action = toolName.startsWith("mc_") ? toolName.substring(3) : toolName;
        JsonObject req = args.deepCopy();
        req.addProperty("playerName", playerName);

        int preStreamedCount = 0;
        int preStreamedQueued = 0;
        boolean skipCall = false;
        List<StreamedOp> streamedOps = turn.streamedOps;

        if ("mc_build_batch".equals(toolName) && !streamedOps.isEmpty() && req.has("ops") && req.get("ops").isJsonArray()) {
            JsonArray reqOps = req.getAsJsonArray("ops");
            String reqCoordMode = req.has("coordMode") && req.get("coordMode").isJsonPrimitive() ? req.get("coordMode").getAsString() : "local";
            String reqAnchorId = req.has("anchorId") && req.get("anchorId").isJsonPrimitive() && !req.get("anchorId").getAsString().isEmpty()
                ? req.get("anchorId").getAsString() : null;
            int matched = 0;
            while (matched < streamedOps.size() && matched < reqOps.size()) {
                StreamedOp rec = streamedOps.get(matched);
                boolean anchorMatch = rec.anchorId() == null ? reqAnchorId == null : rec.anchorId().equals(reqAnchorId);
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

        if ("mc_build_batch".equals(toolName) && isOk(result)
            && args.has("reply") && args.get("reply").isJsonPrimitive() && !args.get("reply").getAsString().isBlank()) {
            sendReplyOnce(playerName, args.get("reply").getAsString(), turn.filmMode, turn.sentReplies);
        }
        return result;
    }

    /** Records bookkeeping the turn loop needs after a tool ran; shared by both backends. */
    public void afterTool(String toolName, JsonObject args, JsonObject result, Turn turn) {
        if (OBSERVATION_TOOLS.contains(toolName)
            || ("mc_exec_commands".equals(toolName) && args.has("observe") && args.get("observe").isJsonObject())) {
            turn.sawObservation = true;
        }
        boolean ok = isOk(result);
        if (!ok) turn.allToolsOk = false;
        if ("mc_build_batch".equals(toolName)) {
            boolean finishFalse = args.has("finish") && args.get("finish").isJsonPrimitive() && !args.get("finish").getAsBoolean();
            if (ok) turn.batchNeedsFinish = finishFalse;
        } else if ("mc_fx".equals(toolName) && ok
            && args.has("effect") && "finish".equalsIgnoreCase(args.get("effect").getAsString())) {
            turn.batchNeedsFinish = false;
        }
    }

    public static boolean isOk(JsonObject result) {
        return !result.has("ok") || !result.get("ok").isJsonPrimitive() || result.get("ok").getAsBoolean();
    }

    /** Removes and returns the base64 PNG attached to a tool result, or null. */
    public static String takeImage(JsonObject result) {
        if (result.has("_imageBase64") && result.get("_imageBase64").isJsonPrimitive()) {
            String b64 = result.get("_imageBase64").getAsString();
            result.remove("_imageBase64");
            return b64.isEmpty() ? null : b64;
        }
        return null;
    }

    private JsonObject execWithObservation(JsonObject args, String playerName) {
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
            for (int offset : new int[]{0, afterMs}) {
                long rem = start + offset - System.currentTimeMillis();
                if (rem > 0) {
                    try {
                        Thread.sleep(rem);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
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

    /** Places one op that arrived mid-stream; returns true if it was queued. */
    public boolean placeStreamedOp(StreamingOpExtractor.OpEvent ev, String playerName, List<StreamedOp> streamedOps) {
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
            streamedOps.add(new StreamedOp(coordMode, anchorId, ev.fingerprint(), q));
            return true;
        }
        return false;
    }

    // ---- text helpers ---------------------------------------------------------------------

    public static String sanitizeReply(String text) {
        if (text == null) return "";
        String s = THINK_BLOCK.matcher(text).replaceAll("");
        s = THINK_TAG.matcher(s).replaceAll("");
        return s.trim();
    }

    public static String tailSnippet(String text, int maxLen) {
        String clean = text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLen) return clean;
        return "..." + clean.substring(clean.length() - (maxLen - 3));
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    public static String formatSurroundingSummary(JsonObject ctx) {
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

    // ---- voxel photo ----------------------------------------------------------------------

    static String renderVoxelSnapshotPngBase64(JsonObject snap) {
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

    static Color colorForMaterial(String mat, int relY) {
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
}
