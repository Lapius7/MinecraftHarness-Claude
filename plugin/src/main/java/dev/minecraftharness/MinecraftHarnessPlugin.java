package dev.minecraftharness;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minecraftharness.build.BuildPacer;
import dev.minecraftharness.build.PrimitiveExpander;
import dev.minecraftharness.build.TerrainSnapshotManager;
import dev.minecraftharness.build.TerrainSnapshotManager.RestoreSummary;
import dev.minecraftharness.command.CommandWirer;
import dev.minecraftharness.command.FunctionPackInstaller;
import dev.minecraftharness.context.SpatialContextProvider;
import dev.minecraftharness.context.SpatialContextProvider.BuildAnchor;
import dev.minecraftharness.context.SpatialContextProvider.StructureRecord;
import dev.minecraftharness.fx.CameraRig;
import dev.minecraftharness.fx.TelemetryHud;
import dev.minecraftharness.fx.VfxEngine;
import dev.minecraftharness.ws.HarnessWebSocketServer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CommandBlock;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MinecraftHarnessPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private SpatialContextProvider contextProvider;
    private TerrainSnapshotManager snapshotManager;
    private PrimitiveExpander expander;
    private VfxEngine vfxEngine;
    private BuildPacer buildPacer;
    private CommandWirer commandWirer;
    private CameraRig cameraRig;
    private TelemetryHud telemetryHud;
    private HarnessWebSocketServer wsServer;

    private String reasoningMode = "low";

    @Override
    public void onEnable() {
        reasoningMode = getConfig().getString("reasoning", "low");
        this.contextProvider = new SpatialContextProvider();
        this.snapshotManager = new TerrainSnapshotManager();
        this.expander = new PrimitiveExpander(contextProvider);
        this.vfxEngine = new VfxEngine();
        this.buildPacer = new BuildPacer(contextProvider, snapshotManager, expander, vfxEngine);
        this.commandWirer = new CommandWirer(contextProvider, snapshotManager, vfxEngine);
        this.cameraRig = new CameraRig();
        this.telemetryHud = new TelemetryHud();

        getLogger().info("Brigadier validator status: " + commandWirer.getBrigadierStatus());

        this.buildPacer.setOnBuildCompleteCallback(summary -> {
            if (wsServer != null) {
                wsServer.broadcastEvent("build_complete", summary);
            }
            if (!cameraRig.isFilmMode()) {
                int placed = summary.get("placed").getAsInt();
                long ms = summary.get("durationMs").getAsLong();
                broadcastStatus("§b⚡ Complete! §f" + placed + " blocks in " + String.format(Locale.US, "%.1fs", ms / 1000.0));
            }
        });

        int wsPort = Integer.parseInt(System.getProperty("mh.ws.port", "25580"));
        this.wsServer = new HarnessWebSocketServer(this, "127.0.0.1", wsPort, this::handleSyncWsAction);
        this.wsServer.start();

        var cmd = getCommand("mh");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);

        // 1-tick heartbeat driving Adaptive Pacing, VFX lasers/drones/pop-ins, CameraRig, and TelemetryHud
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            buildPacer.tick();
            vfxEngine.tick();
            cameraRig.tick();
            telemetryHud.tick(cameraRig.isFilmMode());
        }, 1L, 1L);

        getLogger().info("MinecraftHarness enabled cleanly on ws://127.0.0.1:" + wsPort);
    }

    @Override
    public void onDisable() {
        if (buildPacer != null) buildPacer.stopNow();
        if (vfxEngine != null) vfxEngine.cleanupAll();
        if (cameraRig != null) cameraRig.stopAll();
        if (telemetryHud != null) telemetryHud.cleanup();
        if (wsServer != null) {
            try {
                wsServer.stop(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (!text.startsWith("!") || text.length() <= 1) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("minecraftharness.use")) return;

        if (cameraRig.isFilmMode()) {
            event.setCancelled(true);
        }

        String prompt = text.substring(1).trim();
        Bukkit.getScheduler().runTask(this, () -> dispatchPrompt(player, prompt));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidSpread(BlockFromToEvent event) {
        if (buildPacer != null && buildPacer.shouldPreventFluidSpread(event.getBlock(), event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        if (buildPacer != null) {
            buildPacer.unguardFluidAt(event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        if (buildPacer != null) {
            buildPacer.unguardFluidAt(event.getBlock());
        }
    }

    private void dispatchPrompt(Player player, String prompt) {
        if (wsServer.getConnectedClientCount() == 0) {
            player.sendMessage(Component.text("OpenClaw Bridge is not connected on ws://127.0.0.1:25580", NamedTextColor.RED));
            return;
        }

        BuildAnchor anchor = contextProvider.captureAnchor(player.getName());

        if (cameraRig.isFilmMode()) {
            player.sendActionBar(Component.text("⚡ " + prompt, NamedTextColor.AQUA));
        } else {
            player.sendMessage(Component.text("⚡ OpenClaw (" + reasoningMode + "): ", NamedTextColor.AQUA)
                .append(Component.text(prompt, NamedTextColor.WHITE)));
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("playerName", player.getName());
        payload.addProperty("prompt", prompt);
        payload.addProperty("filmMode", cameraRig.isFilmMode());
        payload.addProperty("reasoning", reasoningMode);
        payload.add("promptAnchor", new Gson().toJsonTree(anchor));
        wsServer.broadcastEvent("player_prompt", payload);
    }

    private void setReasoningMode(String level) {
        if (!List.of("off", "minimal", "low", "medium", "high").contains(level)) throw new IllegalArgumentException("Unknown effort level");
        getConfig().set("reasoning", level);
        saveConfig();
        reasoningMode = level;
    }

    private JsonObject handleSyncWsAction(String action, JsonObject params) {
        String playerName = params.has("playerName") && params.get("playerName").isJsonPrimitive()
            ? params.get("playerName").getAsString()
            : null;

        return switch (action) {
            case "get_reasoning", "set_reasoning" -> {
                if (action.equals("set_reasoning")) setReasoningMode(params.get("level").getAsString());
                JsonObject result = new JsonObject();
                result.addProperty("level", reasoningMode);
                result.addProperty("ok", true);
                yield result;
            }
            case "begin_prompt" -> {
                BuildAnchor chosen = null;
                if (params.has("preserveAnchorId") && params.get("preserveAnchorId").isJsonPrimitive()) {
                    String pid = params.get("preserveAnchorId").getAsString().trim();
                    if (!pid.isEmpty()) {
                        StructureRecord rec = contextProvider.findStructure(pid);
                        if (rec != null && Bukkit.getWorld(rec.anchor().worldName()) != null) {
                            chosen = rec.anchor();
                        }
                    }
                }
                if (chosen == null) {
                    BuildAnchor anchor = new Gson().fromJson(params.get("anchor"), BuildAnchor.class);
                    if (Bukkit.getWorld(anchor.worldName()) == null) throw new IllegalStateException("Request world is unavailable");
                    chosen = anchor;
                }
                contextProvider.setActiveAnchor(chosen);
                commandWirer.resetCoreSlots();
                yield contextProvider.buildFullContextJson(playerName, false);
            }
            case "get_context" -> {
                boolean lock = params.has("lockAnchor") && params.get("lockAnchor").getAsBoolean();
                yield contextProvider.buildFullContextJson(playerName, lock);
            }
            case "lock_on" -> {
                BuildAnchor anchor = contextProvider.resolveAnchor(params, playerName);
                Player p = contextProvider.resolvePlayer(playerName);
                vfxEngine.startLockOn(anchor.toLocation(0, 0, 0), p);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                yield r;
            }
            case "build_batch" -> handleBuildBatch(params, playerName);
            case "function_pack" -> {
                try {
                    yield FunctionPackInstaller.apply(params, contextProvider.resolveAnchor(params, playerName), commandWirer);
                } catch (java.io.IOException | IllegalArgumentException ex) {
                    JsonObject error = new JsonObject();
                    error.addProperty("ok", false);
                    error.addProperty("error", ex.getMessage());
                    yield error;
                }
            }
            case "place_command_chain" -> commandWirer.placeCommandChain(params, playerName);
            case "exec_commands" -> commandWirer.execCommands(params, playerName);
            case "inspect_world" -> inspectWorld(params, playerName);
            case "fx" -> handleFxAction(params, playerName);
            case "finish_build" -> {
                String label = params.has("label") ? params.get("label").getAsString() : null;
                yield buildPacer.finishBuild(label);
            }
            case "undo" -> performUndo();
            case "reset" -> performReset();
            case "notify_status" -> {
                String msg = params.has("message") ? params.get("message").getAsString() : "";
                if (!cameraRig.isFilmMode() && !msg.isBlank()) {
                    if (playerName == null) {
                        broadcastStatus("§7[OpenClaw] §f" + msg);
                    } else {
                        Player recipient = Bukkit.getPlayerExact(playerName);
                        if (recipient != null) recipient.sendMessage(Component.text("[OpenClaw] ", NamedTextColor.GRAY)
                            .append(Component.text(msg, NamedTextColor.WHITE)));
                    }
                }
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                yield r;
            }
            case "film_mode" -> {
                boolean on = params.has("enabled") && params.get("enabled").getAsBoolean();
                cameraRig.setFilmMode(on);
                telemetryHud.tick(on);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("filmMode", on);
                yield r;
            }
            case "telemetry" -> {
                telemetryHud.update(params, cameraRig.isFilmMode());
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                yield r;
            }
            case "camera_snap" -> handleCameraSnap(params, playerName);
            case "camera_restore" -> {
                Player p = contextProvider.resolvePlayer(playerName);
                boolean restored = cameraRig.restoreAfterPhoto(p);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                r.addProperty("restored", restored);
                yield r;
            }
            case "voxel_snapshot" -> captureVoxelSnapshot(params, playerName);
            case "shutdown_server" -> {
                getLogger().info("Received local shutdown_server request — saving and stopping PaperMC...");
                Bukkit.savePlayers();
                for (World w : Bukkit.getWorlds()) {
                    w.save();
                }
                Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 2L);
                JsonObject r = new JsonObject();
                r.addProperty("ok", true);
                yield r;
            }
            default -> {
                JsonObject err = new JsonObject();
                err.addProperty("ok", false);
                err.addProperty("error", "Unknown WS action: " + action);
                yield err;
            }
        };
    }

    private JsonObject handleBuildBatch(JsonObject params, String playerName) {
        JsonObject resp = buildPacer.enqueueBatch(params, playerName);
        if (params.has("commands") && params.get("commands").isJsonArray() && !params.getAsJsonArray("commands").isEmpty()) {
            JsonObject cmdRes = commandWirer.execCommands(params, playerName);
            resp.add("commandResults", cmdRes);
            if (cmdRes.has("ok") && !cmdRes.get("ok").getAsBoolean()) {
                resp.addProperty("commandsOk", false);
            }
        }
        boolean shouldFinish = !params.has("finish")
            || !params.get("finish").isJsonPrimitive()
            || params.get("finish").getAsBoolean();
        if (shouldFinish) {
            String label = params.has("label") && params.get("label").isJsonPrimitive()
                ? params.get("label").getAsString()
                : null;
            buildPacer.finishBuild(label);
            resp.addProperty("finished", true);
        } else {
            resp.addProperty("finished", false);
        }
        return resp;
    }

    private JsonObject inspectWorld(JsonObject params, String playerName) {
        BuildAnchor anchor = contextProvider.resolveAnchor(params, playerName);
        World world = anchor.world();

        int[] from = vec3(params, "from", new int[]{-8, -2, 0});
        int[] to = vec3(params, "to", new int[]{8, 12, 16});
        int r0 = Math.min(from[0], to[0]), r1 = Math.max(from[0], to[0]);
        int u0 = Math.min(from[1], to[1]), u1 = Math.max(from[1], to[1]);
        int f0 = Math.min(from[2], to[2]), f1 = Math.max(from[2], to[2]);

        long vol = (long) (r1 - r0 + 1) * (u1 - u0 + 1) * (f1 - f0 + 1);
        if (vol > 20000) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "Inspect volume too large (" + vol + " blocks > 20000 max)");
            return err;
        }

        String view = params.has("view") ? params.get("view").getAsString() : "";
        boolean top = view.equals("top"), side = view.equals("side");
        int width = side ? f1 - f0 + 1 : r1 - r0 + 1;
        int height = top ? f1 - f0 + 1 : u1 - u0 + 1;
        char[][] projection = null;
        Map<String, Character> symbols = new java.util.LinkedHashMap<>();
        if (!view.isEmpty()) {
            if (!List.of("front", "side", "top").contains(view) || width > 96 || height > 96) {
                throw new IllegalArgumentException("Choose front, side or top and a view at most 96 x 96 cells");
            }
            projection = new char[height][width];
            for (char[] row : projection) java.util.Arrays.fill(row, '.');
        }
        Map<String, Integer> counts = new HashMap<>();
        JsonArray commandBlocks = new JsonArray();
        int nonAir = 0;

        for (int u = u0; u <= u1; u++) {
            for (int r = r0; r <= r1; r++) {
                for (int f = f0; f <= f1; f++) {
                    int wx = anchor.worldX(r, f);
                    int wy = anchor.worldY(u);
                    int wz = anchor.worldZ(r, f);
                    Block b = world.getBlockAt(wx, wy, wz);
                    if (b.getType().isAir()) continue;
                    nonAir++;
                    String key = b.getType().name().toLowerCase(Locale.ROOT);
                    if (projection != null) {
                        int row = top ? f - f0 : u1 - u, col = side ? f - f0 : r - r0;
                        // Scan order retains the nearest front face, highest top, or rightmost side.
                        if (top || side || projection[row][col] == '.') {
                            char symbol = symbols.computeIfAbsent(key, ignored -> {
                                String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
                                return symbols.size() < alphabet.length() ? alphabet.charAt(symbols.size()) : '?';
                            });
                            projection[row][col] = symbol;
                        }
                    }
                    counts.merge(key, 1, Integer::sum);

                    if (b.getState() instanceof CommandBlock cb) {
                        JsonObject cbo = new JsonObject();
                        cbo.addProperty("local", r + "," + u + "," + f);
                        cbo.addProperty("world", wx + "," + wy + "," + wz);
                        cbo.addProperty("type", key);
                        cbo.addProperty("command", cb.getCommand());
                        commandBlocks.add(cbo);
                    }
                }
            }
        }

        JsonObject hist = new JsonObject();
        counts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(20)
            .forEach(e -> hist.addProperty(e.getKey(), e.getValue()));

        Location center = anchor.toLocation((r0 + r1) / 2, (u0 + u1) / 2, (f0 + f1) / 2);
        JsonArray entities = new JsonArray();
        for (Entity e : world.getNearbyEntities(center, 24, 16, 24)) {
            if (entities.size() >= 25) break;
            JsonObject eo = new JsonObject();
            eo.addProperty("type", e.getType().name().toLowerCase(Locale.ROOT));
            eo.addProperty("pos", Math.round(e.getLocation().getX()) + "," + Math.round(e.getLocation().getY()) + "," + Math.round(e.getLocation().getZ()));
            if (e instanceof Player pl) eo.addProperty("name", pl.getName());
            entities.add(eo);
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("pendingPlacements", buildPacer.getRemainingCount());
        if (projection != null) {
            JsonArray rows = new JsonArray();
            for (char[] row : projection) rows.add(new String(row));
            JsonObject legend = new JsonObject();
            symbols.forEach((material, symbol) -> { if (symbol != '?') legend.addProperty(symbol.toString(), material); });
            resp.add("rows", rows);
            resp.add("legend", legend);
            resp.addProperty("view", view);
        }
        resp.addProperty("volumeScanned", vol);
        resp.addProperty("nonAirBlocks", nonAir);
        resp.add("topMaterials", hist);
        resp.add("commandBlocks", commandBlocks);
        resp.add("nearbyEntities", entities);
        if (params.has("sampleCoords") && params.get("sampleCoords").isJsonArray()) {
            JsonObject sampleBlocks = new JsonObject();
            for (var el : params.getAsJsonArray("sampleCoords")) {
                if (!el.isJsonArray()) continue;
                JsonArray arr = el.getAsJsonArray();
                if (arr.size() < 3) continue;
                int sr = arr.get(0).getAsInt();
                int su = arr.get(1).getAsInt();
                int sf = arr.get(2).getAsInt();
                Block sb = world.getBlockAt(anchor.worldX(sr, sf), anchor.worldY(su), anchor.worldZ(sr, sf));
                sampleBlocks.addProperty(sr + "," + su + "," + sf, sb.getBlockData().getAsString());
            }
            resp.add("sampleBlocks", sampleBlocks);
        }
        return resp;
    }

    private JsonObject handleFxAction(JsonObject params, String playerName) {
        String effect = params.has("effect") ? params.get("effect").getAsString().toLowerCase(Locale.ROOT) : "finish";
        BuildAnchor anchor = contextProvider.resolveAnchor(params, playerName);
        World world = anchor.world();

        int[] at = vec3(params, "at", new int[]{0, 2, 8});
        Location loc = anchor.toLocation(at[0], at[1], at[2]);

        switch (effect) {
            case "finish" -> {
                String label = params.has("label") ? params.get("label").getAsString() : null;
                return buildPacer.finishBuild(label);
            }
            case "illumination" -> {
                String preset = params.has("preset") && params.get("preset").isJsonPrimitive()
                    ? params.get("preset").getAsString()
                    : "sparkle";
                int minX, minY, minZ, maxX, maxY, maxZ;
                if (params.has("from") && params.get("from").isJsonArray()
                    && params.has("to") && params.get("to").isJsonArray()) {
                    int[] f = vec3(params, "from", new int[]{-10, 0, 0});
                    int[] t = vec3(params, "to", new int[]{10, 12, 20});
                    int wx1 = anchor.worldX(f[0], f[2]), wy1 = anchor.worldY(f[1]), wz1 = anchor.worldZ(f[0], f[2]);
                    int wx2 = anchor.worldX(t[0], t[2]), wy2 = anchor.worldY(t[1]), wz2 = anchor.worldZ(t[0], t[2]);
                    minX = Math.min(wx1, wx2); maxX = Math.max(wx1, wx2);
                    minY = Math.min(wy1, wy2); maxY = Math.max(wy1, wy2);
                    minZ = Math.min(wz1, wz2); maxZ = Math.max(wz1, wz2);
                } else {
                    StructureRecord rec = null;
                    if (params.has("anchorId") && params.get("anchorId").isJsonPrimitive()) {
                        rec = contextProvider.findStructure(params.get("anchorId").getAsString());
                    }
                    if (rec == null) {
                        rec = contextProvider.findStructure("last");
                    }
                    if (rec != null) {
                        minX = rec.minX(); minY = rec.minY(); minZ = rec.minZ();
                        maxX = rec.maxX(); maxY = rec.maxY(); maxZ = rec.maxZ();
                        world = rec.anchor().world();
                    } else {
                        int wx1 = anchor.worldX(-10, 0), wy1 = anchor.worldY(0), wz1 = anchor.worldZ(-10, 0);
                        int wx2 = anchor.worldX(10, 20), wy2 = anchor.worldY(12), wz2 = anchor.worldZ(10, 20);
                        minX = Math.min(wx1, wx2); maxX = Math.max(wx1, wx2);
                        minY = Math.min(wy1, wy2); maxY = Math.max(wy1, wy2);
                        minZ = Math.min(wz1, wz2); maxZ = Math.max(wz1, wz2);
                    }
                }
                vfxEngine.addIlluminationZone(world, preset, minX, minY, minZ, maxX, maxY, maxZ);
            }
            case "clear_illumination" -> {
                vfxEngine.clearIlluminations();
            }
            case "shockwave" -> {
                vfxEngine.playFinishShockwave(loc);
            }
            case "firework", "fireworks" -> {
                int count = params.has("count") ? Math.max(1, Math.min(8, params.get("count").getAsInt())) : 3;
                for (int i = 0; i < count; i++) {
                    Location fl = loc.clone().add((i - count / 2.0) * 2.5, 0.5, 0);
                    world.spawn(fl, Firework.class, fw -> {
                        FireworkMeta meta = fw.getFireworkMeta();
                        meta.setPower(1);
                        meta.addEffect(FireworkEffect.builder()
                            .with(FireworkEffect.Type.BURST)
                            .withColor(Color.AQUA, Color.YELLOW, Color.FUCHSIA)
                            .withFade(Color.WHITE)
                            .trail(true)
                            .flicker(true)
                            .build());
                        fw.setFireworkMeta(meta);
                    });
                }
            }
            case "title" -> {
                String title = params.has("title") ? params.get("title").getAsString() : "§bREADY!";
                String sub = params.has("subtitle") ? params.get("subtitle").getAsString() : "";
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(net.kyori.adventure.title.Title.title(
                        Component.text(title),
                        Component.text(sub)
                    ));
                }
            }
            case "time_night" -> world.setTime(14500L);
            case "time_sunset" -> world.setTime(12600L);
            case "time_day" -> world.setTime(6000L);
            case "lightning" -> world.strikeLightningEffect(loc);
            default -> {
                world.spawnParticle(Particle.END_ROD, loc, 24, 1.5, 1.5, 1.5, 0.05);
                world.playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.4f);
            }
        }

        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("effect", effect);
        return r;
    }

    private JsonObject performUndo() {
        buildPacer.stopNow();
        vfxEngine.clearIlluminations();
        RestoreSummary sum = snapshotManager.undoLast();
        buildPacer.pruneGuardedFluids();
        if (sum.center() != null) {
            vfxEngine.playUndoDissolve(sum.center());
        }
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("restoredBlocks", sum.restoredBlocks());
        r.addProperty("removedEntities", sum.removedEntities());
        return r;
    }

    private JsonObject performReset() {
        buildPacer.stopNow();
        vfxEngine.clearIlluminations();
        commandWirer.resetCoreSlots();
        contextProvider.clearStructures();
        RestoreSummary sum = snapshotManager.resetAll();
        buildPacer.clearGuardedFluids();
        if (sum.center() != null) {
            vfxEngine.playUndoDissolve(sum.center());
        }
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("restoredBlocks", sum.restoredBlocks());
        r.addProperty("removedEntities", sum.removedEntities());
        return r;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§bMinecraftHarness §7| Use §f!<prompt>§7 or §f/mh <prompt|undo|reset|film|cam|fx|reasoning|stop|status>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "undo" -> {
                JsonObject r = performUndo();
                sender.sendMessage("§aUndid last build (§f" + r.get("restoredBlocks").getAsInt() + " blocks restored§a).");
            }
            case "reset" -> {
                JsonObject r = performReset();
                sender.sendMessage("§aReset all session builds (§f" + r.get("restoredBlocks").getAsInt() + " blocks restored§a).");
            }
            case "stop" -> {
                buildPacer.stopNow();
                sender.sendMessage("§eStopped active build queue and VFX.");
            }
            case "film" -> {
                boolean next = args.length >= 2 ? "on".equalsIgnoreCase(args[1]) : !cameraRig.isFilmMode();
                cameraRig.setFilmMode(next);
                telemetryHud.tick(next);
                sender.sendMessage("§bFilm Mode: " + (next ? "§aON (clean recording HUD)" : "§7OFF"));
            }
            case "hud" -> {
                if (args.length >= 2 && "reasoning".equalsIgnoreCase(args[1])) {
                    boolean next = args.length >= 3 ? "on".equalsIgnoreCase(args[2]) : !telemetryHud.isReasoningEnabled();
                    telemetryHud.setReasoningEnabled(next);
                    sender.sendMessage("§bIn-Game Reasoning Ticker: " + (next ? "§aON" : "§7OFF"));
                } else {
                    boolean next = args.length >= 2 ? "on".equalsIgnoreCase(args[1]) : !telemetryHud.isHudEnabled();
                    telemetryHud.setHudEnabled(next);
                    sender.sendMessage("§bIn-Game Telemetry HUD: " + (next ? "§aON" : "§7OFF"));
                }
            }
            case "fx" -> {
                if (args.length >= 3 && "sound".equalsIgnoreCase(args[1])) {
                    boolean on = "on".equalsIgnoreCase(args[2]);
                    vfxEngine.setSoundEnabled(on);
                    sender.sendMessage("§bHarness SFX: " + (on ? "§aON" : "§7OFF"));
                } else {
                    boolean on = args.length >= 2 ? "on".equalsIgnoreCase(args[1]) : !vfxEngine.isFxEnabled();
                    vfxEngine.setFxEnabled(on);
                    sender.sendMessage("§bHarness VFX: " + (on ? "§aON" : "§7OFF"));
                }
            }
            case "cam" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Player only.");
                    return true;
                }
                String camMode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "orbit";
                if ("off".equals(camMode)) {
                    cameraRig.stop(p, true);
                    p.sendMessage("§bCamera rig OFF.");
                } else {
                    BuildAnchor a = contextProvider.getOrCaptureActiveAnchor(p.getName());
                    Location center = a.toLocation(0, 3, 8);
                    double radius = args.length >= 3 ? parseDouble(args[2], 18.0) : 18.0;
                    double height = args.length >= 4 ? parseDouble(args[3], 8.0) : 8.0;
                    double speed = args.length >= 5 ? parseDouble(args[4], 16.0) : 16.0;
                    if ("dolly".equals(camMode)) {
                        cameraRig.startDolly(p, center, radius, height);
                    } else {
                        cameraRig.startOrbit(p, center, radius, height, speed);
                    }
                    p.sendMessage("§bCamera rig (" + camMode + ") active. Sneak or run §f/mh cam off§b to exit.");
                }
            }
            case "reasoning", "effort" -> {
                if (args.length < 2) {
                    sender.sendMessage("§bCurrent reasoning_effort: §f" + reasoningMode);
                    return true;
                }
                String lvl = args[1].toLowerCase(Locale.ROOT);
                if (!List.of("off", "minimal", "low", "medium", "high").contains(lvl)) {
                    sender.sendMessage("§cValid levels: off, minimal, low, medium, high");
                    return true;
                }
                setReasoningMode(lvl);
                JsonObject ev = new JsonObject();
                ev.addProperty("command", "reasoning");
                ev.addProperty("value", lvl);
                wsServer.broadcastEvent("player_command", ev);
                sender.sendMessage("§bOpenClaw thinking level set to: §f" + lvl);
            }
            case "status" -> {
                sender.sendMessage("§b=== MinecraftHarness Status ===");
                sender.sendMessage("§7Bridge WS clients: §f" + wsServer.getConnectedClientCount());
                sender.sendMessage("§7Brigadier: §f" + commandWirer.getBrigadierStatus());
                sender.sendMessage("§7Thinking level: §f" + reasoningMode + " §7| Build speed: §f" + buildPacer.getSpeedMode());
                sender.sendMessage("§bFilm Mode: §f" + cameraRig.isFilmMode() + " §7| HUD: §f" + telemetryHud.isHudEnabled() + " §7| VFX: §f" + vfxEngine.isFxEnabled() + " §7| SFX: §f" + vfxEngine.isSoundEnabled());
                sender.sendMessage("§7Telemetry Web UI: §bhttp://127.0.0.1:25581/");
                sender.sendMessage("§7Active queue: §f" + buildPacer.getRemainingCount() + " blocks remaining");
            }
            case "speed" -> {
                if (args.length < 2) {
                    sender.sendMessage("§bCurrent build speed: §f" + buildPacer.getSpeedMode() + " §7(turbo | fast | cinematic)");
                    return true;
                }
                String sp = args[1].toLowerCase(Locale.ROOT);
                if (!List.of("turbo", "fast", "cinematic").contains(sp)) {
                    sender.sendMessage("§cValid speed modes: turbo, fast, cinematic");
                    return true;
                }
                buildPacer.setSpeedMode(sp);
                sender.sendMessage("§bBuild speed mode set to: §f" + sp);
            }
            case "shift" -> {
                if (args.length < 2) {
                    sender.sendMessage("§bUsage: §f/mh shift <du> §7or §f/mh shift <dr> <du> <df>");
                    return true;
                }
                StructureRecord rec = contextProvider.findStructure("last");
                if (rec == null) {
                    sender.sendMessage("§cNo recent structure found to shift.");
                    return true;
                }
                int dr = 0, du = 0, df = 0;
                try {
                    if (args.length == 2) {
                        du = Integer.parseInt(args[1]);
                    } else if (args.length >= 4) {
                        dr = Integer.parseInt(args[1]);
                        du = Integer.parseInt(args[2]);
                        df = Integer.parseInt(args[3]);
                    } else {
                        sender.sendMessage("§bUsage: §f/mh shift <du> §7or §f/mh shift <dr> <du> <df>");
                        return true;
                    }
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§cShift values must be integers (e.g. /mh shift -3).");
                    return true;
                }
                JsonObject moveOp = new JsonObject();
                moveOp.addProperty("type", "move");
                moveOp.addProperty("structureId", rec.id());
                JsonArray byArr = new JsonArray();
                byArr.add(dr);
                byArr.add(du);
                byArr.add(df);
                moveOp.add("by", byArr);
                JsonArray opsArr = new JsonArray();
                opsArr.add(moveOp);
                JsonObject batchReq = new JsonObject();
                batchReq.addProperty("anchorId", rec.id());
                batchReq.addProperty("label", rec.label() + " (shifted)");
                batchReq.addProperty("finish", true);
                batchReq.add("ops", opsArr);
                String pName = sender instanceof Player pl ? pl.getName() : null;
                JsonObject res = handleBuildBatch(batchReq, pName);
                int queued = res.has("queued") ? res.get("queued").getAsInt() : 0;
                sender.sendMessage("§aShifting §f" + rec.id() + " (" + rec.label() + ")§a by [" + dr + ", " + du + ", " + df + "] (§f" + queued + " blocks§a).");
            }
            default -> {
                if (sender instanceof Player p) {
                    String prompt = String.join(" ", args);
                    dispatchPrompt(p, prompt);
                } else {
                    sender.sendMessage("Only players can run prompt commands.");
                }
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("undo", "reset", "shift", "film", "hud", "cam", "fx", "speed", "reasoning", "effort", "session", "history", "new", "stop", "status");
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "film", "fx" -> List.of("on", "off", "sound");
                case "hud" -> List.of("on", "off", "reasoning");
                case "cam" -> List.of("orbit", "dolly", "off");
                case "history" -> List.of("reset");
                case "session" -> List.of("list", "use");
                case "speed" -> List.of("turbo", "fast", "cinematic");
                case "reasoning", "effort" -> List.of("off", "minimal", "low", "medium", "high");
                default -> List.of();
            };
        }
        if (args.length == 3 && "hud".equalsIgnoreCase(args[0]) && "reasoning".equalsIgnoreCase(args[1])) {
            return List.of("on", "off");
        }
        return List.of();
    }

    private JsonObject handleCameraSnap(JsonObject params, String playerName) {
        Player p = contextProvider.resolvePlayer(playerName);
        if (p == null) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("moved", false);
            r.addProperty("reason", "no_online_player");
            return r;
        }
        String angle = params.has("angle") && params.get("angle").isJsonPrimitive()
            ? params.get("angle").getAsString().toLowerCase(Locale.ROOT).trim()
            : "current";
        if ("current".equals(angle)) {
            JsonObject r = new JsonObject();
            r.addProperty("ok", true);
            r.addProperty("moved", false);
            r.addProperty("playerName", p.getName());
            return r;
        }

        BuildAnchor anchor = contextProvider.resolveAnchor(params, playerName);
        int dist = params.has("distance") && params.get("distance").isJsonPrimitive() ? params.get("distance").getAsInt() : 16;
        int height = params.has("height") && params.get("height").isJsonPrimitive() ? params.get("height").getAsInt() : 6;
        Location look = params.has("lookAt") && params.get("lookAt").isJsonArray()
            ? anchorFromVec3(anchor, vec3(params, "lookAt", new int[]{0, 2, 8}))
            : anchor.toLocation(0, 2, 8);

        Location cam = switch (angle) {
            case "front" -> anchor.toLocation(0, height, -Math.max(4, dist - 8));
            case "orbit_left" -> anchor.toLocation(-dist, height, 6);
            case "orbit_right" -> anchor.toLocation(dist, height, 6);
            case "top_overview" -> anchor.toLocation(0, Math.max(14, height + 10), 0);
            case "custom" -> anchorFromVec3(anchor, vec3(params, "cameraAt", new int[]{0, height, -8}));
            default -> anchor.toLocation(0, height, -6);
        };

        cameraRig.snapForPhoto(p, cam, look);
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("moved", true);
        r.addProperty("playerName", p.getName());
        r.addProperty("angle", angle);
        r.addProperty("cameraPos", Math.round(cam.getX()) + "," + Math.round(cam.getY()) + "," + Math.round(cam.getZ()));
        r.addProperty("lookAt", Math.round(look.getX()) + "," + Math.round(look.getY()) + "," + Math.round(look.getZ()));
        return r;
    }

    private static Location anchorFromVec3(BuildAnchor anchor, int[] c) {
        return anchor.toLocation(c[0], c[1], c[2]);
    }

    private JsonObject captureVoxelSnapshot(JsonObject params, String playerName) {
        BuildAnchor anchor = contextProvider.resolveAnchor(params, playerName);
        World world = anchor.world();
        int width = 48;
        int depth = 48;
        int rStart = -24;
        int fStart = -12;
        int maxScanY = Math.min(world.getMaxHeight() - 1, anchor.originY() + 32);
        int minScanY = Math.max(world.getMinHeight(), anchor.originY() - 12);

        JsonArray rows = new JsonArray();
        for (int df = 0; df < depth; df++) {
            int f = fStart + df;
            JsonArray row = new JsonArray();
            for (int dr = 0; dr < width; dr++) {
                int r = rStart + dr;
                int wx = anchor.worldX(r, f);
                int wz = anchor.worldZ(r, f);
                String matName = "air";
                int relY = -12;
                for (int y = maxScanY; y >= minScanY; y--) {
                    Block b = world.getBlockAt(wx, y, wz);
                    if (!b.getType().isAir()) {
                        matName = b.getType().name().toLowerCase(Locale.ROOT);
                        relY = y - anchor.originY();
                        break;
                    }
                }
                JsonArray cell = new JsonArray();
                cell.add(matName);
                cell.add(relY);
                row.add(cell);
            }
            rows.add(row);
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("width", width);
        resp.addProperty("height", depth);
        resp.addProperty("anchorOrigin", anchor.originX() + "," + anchor.originY() + "," + anchor.originZ());
        resp.add("grid", rows);
        return resp;
    }

    private void broadcastStatus(String legacyText) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(legacyText);
        }
    }

    private static int[] vec3(JsonObject obj, String key, int[] fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return fallback;
        JsonArray arr = obj.getAsJsonArray(key);
        int x = arr.size() > 0 ? arr.get(0).getAsInt() : fallback[0];
        int y = arr.size() > 1 ? arr.get(1).getAsInt() : fallback[1];
        int z = arr.size() > 2 ? arr.get(2).getAsInt() : fallback[2];
        return new int[]{x, y, z};
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
