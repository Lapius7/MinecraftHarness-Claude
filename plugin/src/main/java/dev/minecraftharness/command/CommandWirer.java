package dev.minecraftharness.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minecraftharness.build.TerrainSnapshotManager;
import dev.minecraftharness.context.SpatialContextProvider;
import dev.minecraftharness.context.SpatialContextProvider.BuildAnchor;
import dev.minecraftharness.fx.VfxEngine;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Switch;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Places auto-wired Minecraft 1.21.4 Impulse/Repeating/Chain command block chains,
 * executes direct server commands, substitutes {{REL r u f}} anchor macros,
 * and validates every command with Mojang's live Brigadier CommandDispatcher.
 */
public final class CommandWirer {

    private static final Pattern REL_MACRO = Pattern.compile("\\{\\{\\s*REL\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s*\\}\\}");
    private static final Pattern ORIGIN_MACRO = Pattern.compile("\\{\\{\\s*ORIGIN\\s*\\}\\}");

    private final SpatialContextProvider contextProvider;
    private final TerrainSnapshotManager snapshotManager;
    private final VfxEngine vfx;

    private Object brigadierDispatcher;
    private Object brigadierSourceStack;
    private Method parseMethod;
    private Method getExceptionsMethod;
    private Method getReaderMethod;
    private Method canReadMethod;
    private Method getContextMethod;
    private Method getNodesMethod;
    private boolean brigadierReady = false;
    private String brigadierStatus = "uninitialized";
    private int coreSlotCounter = 0;

    public CommandWirer(
        SpatialContextProvider contextProvider,
        TerrainSnapshotManager snapshotManager,
        VfxEngine vfx
    ) {
        this.contextProvider = contextProvider;
        this.snapshotManager = snapshotManager;
        this.vfx = vfx;
        initBrigadierValidator();
    }

    private void initBrigadierValidator() {
        try {
            Object craftServer = Bukkit.getServer();
            Method getServerMethod = craftServer.getClass().getMethod("getServer");
            Object mcServer = getServerMethod.invoke(craftServer);

            Method getCommandsMethod = mcServer.getClass().getMethod("getCommands");
            Object commandsObj = getCommandsMethod.invoke(mcServer);

            Method getDispatcherMethod = commandsObj.getClass().getMethod("getDispatcher");
            brigadierDispatcher = getDispatcherMethod.invoke(commandsObj);

            Method createSourceMethod = mcServer.getClass().getMethod("createCommandSourceStack");
            brigadierSourceStack = createSourceMethod.invoke(mcServer);

            Class<?> dispatcherCls = brigadierDispatcher.getClass();
            for (Method m : dispatcherCls.getMethods()) {
                if (m.getName().equals("parse") && m.getParameterCount() == 2
                    && m.getParameterTypes()[0] == String.class) {
                    parseMethod = m;
                    break;
                }
            }
            if (parseMethod == null) {
                throw new IllegalStateException("Could not find CommandDispatcher#parse(String, S)");
            }

            // Probe method handles using a trivial command
            Object probe = parseMethod.invoke(brigadierDispatcher, "say ok", brigadierSourceStack);
            Class<?> parseCls = probe.getClass();
            getExceptionsMethod = parseCls.getMethod("getExceptions");
            getReaderMethod = parseCls.getMethod("getReader");
            getContextMethod = parseCls.getMethod("getContext");
            Object readerObj = getReaderMethod.invoke(probe);
            canReadMethod = readerObj.getClass().getMethod("canRead");
            Object ctxObj = getContextMethod.invoke(probe);
            getNodesMethod = ctxObj.getClass().getMethod("getNodes");

            brigadierReady = true;
            brigadierStatus = "Brigadier " + Bukkit.getMinecraftVersion() + " live dispatcher initialized";
        } catch (Throwable t) {
            brigadierReady = false;
            brigadierStatus = "Fallback mode (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
    }

    public boolean isBrigadierReady() {
        return brigadierReady;
    }

    public String getBrigadierStatus() {
        return brigadierStatus;
    }

    public void resetCoreSlots() {
        coreSlotCounter = 0;
    }

    public String validateCommand(String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return "Command is empty";
        }
        String normalized = rawCommand.startsWith("/") ? rawCommand.substring(1).trim() : rawCommand.trim();
        if (normalized.isEmpty()) {
            return "Command is empty";
        }

        if (!brigadierReady) {
            String root = normalized.split("\\s+")[0];
            if (Bukkit.getCommandMap().getCommand(root) == null && Bukkit.getPluginCommand(root) == null) {
                return "Unknown root command: " + root;
            }
            return null;
        }

        try {
            Object parseResults = parseMethod.invoke(brigadierDispatcher, normalized, brigadierSourceStack);
            @SuppressWarnings("unchecked")
            Map<?, ?> exceptions = (Map<?, ?>) getExceptionsMethod.invoke(parseResults);
            Object reader = getReaderMethod.invoke(parseResults);
            boolean hasTrailing = (boolean) canReadMethod.invoke(reader);
            Object context = getContextMethod.invoke(parseResults);
            List<?> nodes = (List<?>) getNodesMethod.invoke(context);

            if (exceptions != null && !exceptions.isEmpty()) {
                Object firstErr = exceptions.values().iterator().next();
                return firstErr instanceof Throwable thr ? thr.getMessage() : String.valueOf(firstErr);
            }
            if (nodes == null || nodes.isEmpty()) {
                return "Unknown command or missing arguments near start of '" + normalized + "'";
            }
            if (hasTrailing) {
                Method getRemaining = reader.getClass().getMethod("getRemaining");
                String rem = (String) getRemaining.invoke(reader);
                return "Unexpected trailing syntax at: '" + rem + "'";
            }
            return null;
        } catch (Throwable t) {
            return "Validation exception: " + t.getMessage();
        }
    }

    public String expandMacros(String rawCmd, BuildAnchor anchor) {
        if (rawCmd == null) return "";
        String cmd = rawCmd.trim();
        if (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        if (anchor == null) return cmd;

        cmd = ORIGIN_MACRO.matcher(cmd).replaceAll(
            anchor.originX() + " " + anchor.originY() + " " + anchor.originZ()
        );

        Matcher m = REL_MACRO.matcher(cmd);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int r = Integer.parseInt(m.group(1));
            int u = Integer.parseInt(m.group(2));
            int f = Integer.parseInt(m.group(3));
            int wx = anchor.worldX(r, f);
            int wy = anchor.worldY(u);
            int wz = anchor.worldZ(r, f);
            m.appendReplacement(sb, Matcher.quoteReplacement(wx + " " + wy + " " + wz));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private record ChainStep(String command, boolean conditional) {}

    public JsonObject placeCommandChain(JsonObject req, String playerName) {
        BuildAnchor anchor = contextProvider.resolveAnchor(req, playerName);
        World world = anchor.world();
        snapshotManager.beginOrGetSession(optString(req, "label", "Command Core"));

        String label = optString(req, "label", "Logic Core");
        String mode = optString(req, "mode", "repeating").toLowerCase(Locale.ROOT);
        boolean isRepeating = "repeating".equals(mode) || "repeat".equals(mode);
        String placement = optString(req, "placement", "core").toLowerCase(Locale.ROOT);

        JsonArray rawCmds = req.has("commands") && req.get("commands").isJsonArray()
            ? req.getAsJsonArray("commands")
            : new JsonArray();

        if (rawCmds.isEmpty()) {
            JsonObject err = new JsonObject();
            err.addProperty("ok", false);
            err.addProperty("error", "commands array is empty");
            return err;
        }

        List<ChainStep> steps = new ArrayList<>();
        JsonArray validationErrors = new JsonArray();

        for (int i = 0; i < rawCmds.size(); i++) {
            JsonElement el = rawCmds.get(i);
            String raw;
            boolean conditional = false;
            if (el.isJsonPrimitive()) {
                raw = el.getAsString();
            } else if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                raw = optString(obj, "cmd", optString(obj, "command", ""));
                conditional = optBool(obj, "conditional", false);
            } else {
                continue;
            }
            String expanded = expandMacros(raw, anchor);
            String errMsg = validateCommand(expanded);
            if (errMsg != null) {
                JsonObject e = new JsonObject();
                e.addProperty("index", i);
                e.addProperty("command", expanded);
                e.addProperty("error", errMsg);
                validationErrors.add(e);
            } else {
                steps.add(new ChainStep(expanded, conditional));
            }
        }

        if (!validationErrors.isEmpty()) {
            JsonObject fail = new JsonObject();
            fail.addProperty("ok", false);
            fail.addProperty("message", "Brigadier syntax validation failed. Fix the commands and retry.");
            fail.add("errors", validationErrors);
            return fail;
        }

        // Determine world origin (baseX, baseY, baseZ) for the chain (chains advance along +X / EAST)
        int baseX;
        int baseY;
        int baseZ;
        if ("at".equals(placement) && req.has("at") && req.get("at").isJsonArray()) {
            int[] loc = vec3(req, "at", new int[]{0, 0, 0});
            baseX = anchor.worldX(loc[0], loc[2]);
            baseY = anchor.worldY(loc[1]);
            baseZ = anchor.worldZ(loc[0], loc[2]);
        } else {
            // Place in an aesthetic side bay relative to the anchor
            int slot = coreSlotCounter++;
            int localR = -10;
            int localU = 1 + (slot % 3) * 3;
            int localF = 4 + (slot / 3) * 4;
            baseX = anchor.worldX(localR, localF);
            baseY = anchor.worldY(localU);
            baseZ = anchor.worldZ(localR, localF);
        }

        boolean hasTrigger = req.has("trigger") && req.get("trigger").isJsonObject()
            && !"none".equalsIgnoreCase(optString(req.getAsJsonObject("trigger"), "type", "none"));
        boolean autoFirst = optBool(req, "auto", isRepeating || !hasTrigger);

        // If glass casing requested ("core"), build sleek glass pod around the chain
        boolean buildGlassCore = "core".equals(placement);
        if (buildGlassCore) {
            Material glassMat = isRepeating ? Material.CYAN_STAINED_GLASS : Material.ORANGE_STAINED_GLASS;
            for (int dx = -1; dx <= steps.size(); dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        // Leave interior row at dy=0, dz=0 for command blocks (and dx=0..steps.size()-1)
                        if (dy == 0 && dz == 0 && dx >= 0 && dx < steps.size()) continue;
                        int px = baseX + dx;
                        int py = baseY + dy;
                        int pz = baseZ + dz;
                        snapshotManager.recordBeforeWrite(world, px, py, pz);
                        if (dy == -1 && dz == 0) {
                            world.getBlockAt(px, py, pz).setType(Material.SEA_LANTERN, false);
                        } else if (dy == -1) {
                            world.getBlockAt(px, py, pz).setType(Material.POLISHED_BLACKSTONE, false);
                        } else {
                            world.getBlockAt(px, py, pz).setType(glassMat, false);
                        }
                    }
                }
            }
            // Spawn floating 3D label above Command Core
            Location labelLoc = new Location(world, baseX + (steps.size() - 1) * 0.5 + 0.5, baseY + 2.35, baseZ + 0.5);
            TextDisplay td = world.spawn(labelLoc, TextDisplay.class, display -> {
                display.setText((isRepeating ? "§b⚡ " : "§6▶ ") + label);
                display.setBillboard(Display.Billboard.CENTER);
                display.setSeeThrough(true);
                display.setBackgroundColor(Color.fromARGB(160, 12, 16, 28));
                display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 1, 0),
                    new Vector3f(1.1f, 1.1f, 1.1f),
                    new AxisAngle4f(0, 0, 1, 0)
                ));
            });
            snapshotManager.recordSpawnedEntity(td.getUniqueId());
        }

        // Place command blocks along +X (EAST)
        for (int i = 0; i < steps.size(); i++) {
            ChainStep step = steps.get(i);
            int cx = baseX + i;
            int cy = baseY;
            int cz = baseZ;
            snapshotManager.recordBeforeWrite(world, cx, cy, cz);

            Material blockMat;
            if (i == 0) {
                blockMat = isRepeating ? Material.REPEATING_COMMAND_BLOCK : Material.COMMAND_BLOCK;
            } else {
                blockMat = Material.CHAIN_COMMAND_BLOCK;
            }

            Block block = world.getBlockAt(cx, cy, cz);
            BlockData data = blockMat.createBlockData();
            if (data instanceof Directional dir) {
                dir.setFacing(BlockFace.EAST);
            }
            if (data instanceof org.bukkit.block.data.type.CommandBlock cbData) {
                cbData.setConditional(step.conditional());
            }
            block.setBlockData(data, false);

            if (block.getState() instanceof CommandBlock cb) {
                cb.setCommand(step.command());
                cb.update(true, false);
            }

            // Bukkit lacks CommandBlock#setAuto; apply Always Active & mute TrackOutput via /data merge block
            boolean shouldAuto = (i > 0) || autoFirst;
            String nbt = "{auto:" + (shouldAuto ? "1b" : "0b") + ",TrackOutput:0b}";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "data merge block " + cx + " " + cy + " " + cz + " " + nbt);
        }

        // Optional arena-side trigger (button / pressure_plate / lever)
        String triggerLocationText = null;
        if (hasTrigger) {
            JsonObject trig = req.getAsJsonObject("trigger");
            String trigType = optString(trig, "type", "button").toLowerCase(Locale.ROOT);
            int[] trigAt = vec3(trig, "at", new int[]{0, 0, 3});
            int tx = anchor.worldX(trigAt[0], trigAt[2]);
            int ty = anchor.worldY(trigAt[1]);
            int tz = anchor.worldZ(trigAt[0], trigAt[2]);
            triggerLocationText = tx + " " + ty + " " + tz;

            // Place trigger block + pedestal + hidden relay command block underneath at (tx, ty-2, tz)
            // Note: for Core placement, relay sets a redstone block at (baseX - 1, baseY, baseZ) and restores glass/air
            int relayTargetX = baseX - 1;
            int relayTargetY = baseY;
            int relayTargetZ = baseZ;
            snapshotManager.recordBeforeWrite(world, relayTargetX, relayTargetY, relayTargetZ);

            snapshotManager.recordBeforeWrite(world, tx, ty, tz);
            snapshotManager.recordBeforeWrite(world, tx, ty - 1, tz);
            snapshotManager.recordBeforeWrite(world, tx, ty - 2, tz);

            world.getBlockAt(tx, ty - 1, tz).setType(Material.POLISHED_BLACKSTONE, false);
            Block trigBlock = world.getBlockAt(tx, ty, tz);
            switch (trigType) {
                case "pressure_plate" -> trigBlock.setType(Material.STONE_PRESSURE_PLATE, true);
                case "lever" -> {
                    BlockData lever = Material.LEVER.createBlockData();
                    if (lever instanceof Switch sw) {
                        sw.setAttachedFace(Switch.AttachedFace.FLOOR);
                    }
                    trigBlock.setBlockData(lever, true);
                }
                default -> {
                    BlockData btn = Material.POLISHED_BLACKSTONE_BUTTON.createBlockData();
                    if (btn instanceof Switch sw) {
                        sw.setAttachedFace(Switch.AttachedFace.FLOOR);
                    }
                    trigBlock.setBlockData(btn, true);
                }
            }

            // Hidden relay impulse command block under pedestal fires the first block in the Command Core
            Block relay = world.getBlockAt(tx, ty - 2, tz);
            relay.setType(Material.COMMAND_BLOCK, false);
            if (relay.getState() instanceof CommandBlock rcb) {
                // Set redstone block beside block[0] to activate it cleanly
                rcb.setCommand("setblock " + relayTargetX + " " + relayTargetY + " " + relayTargetZ + " minecraft:redstone_block");
                rcb.update(true, false);
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "data merge block " + tx + " " + (ty - 2) + " " + tz + " {auto:0b,TrackOutput:0b}");

            // If impulse chain, add a cleanup tail chain block to clear the redstone block so it's reusable!
            if (!isRepeating) {
                int tailX = baseX + steps.size();
                snapshotManager.recordBeforeWrite(world, tailX, baseY, baseZ);
                Block tail = world.getBlockAt(tailX, baseY, baseZ);
                BlockData tailData = Material.CHAIN_COMMAND_BLOCK.createBlockData();
                if (tailData instanceof Directional d) d.setFacing(BlockFace.EAST);
                tail.setBlockData(tailData, false);
                if (tail.getState() instanceof CommandBlock tcb) {
                    tcb.setCommand("setblock " + relayTargetX + " " + relayTargetY + " " + relayTargetZ + " minecraft:air");
                    tcb.update(true, false);
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "data merge block " + tailX + " " + baseY + " " + baseZ + " {auto:1b,TrackOutput:0b}");
            }

            String trigLabel = optString(trig, "label", "§e▶ " + label);
            TextDisplay trigTd = world.spawn(new Location(world, tx + 0.5, ty + 1.4, tz + 0.5), TextDisplay.class, display -> {
                display.setText(trigLabel);
                display.setBillboard(Display.Billboard.CENTER);
                display.setBackgroundColor(Color.fromARGB(150, 15, 20, 35));
            });
            snapshotManager.recordSpawnedEntity(trigTd.getUniqueId());
        }

        vfx.playCommandWireFx(
            new Location(world, baseX, baseY, baseZ),
            new Location(world, baseX + steps.size() - 1, baseY, baseZ)
        );

        JsonObject ok = new JsonObject();
        ok.addProperty("ok", true);
        ok.addProperty("placedCount", steps.size());
        ok.addProperty("mode", isRepeating ? "repeating" : "impulse");
        ok.addProperty("auto", autoFirst);
        ok.addProperty("coreOrigin", baseX + "," + baseY + "," + baseZ);
        ok.addProperty("brigadierValidated", brigadierReady);
        if (triggerLocationText != null) {
            ok.addProperty("triggerAt", triggerLocationText);
        }
        return ok;
    }

    public JsonObject execCommands(JsonObject req, String playerName) {
        BuildAnchor anchor = contextProvider.resolveAnchor(req, playerName);
        JsonArray arr = req.has("commands") && req.get("commands").isJsonArray()
            ? req.getAsJsonArray("commands")
            : new JsonArray();

        JsonArray results = new JsonArray();
        JsonArray errors = new JsonArray();

        for (int i = 0; i < arr.size(); i++) {
            String raw = arr.get(i).getAsString();
            String expanded = expandMacros(raw, anchor);
            String valErr = validateCommand(expanded);
            if (valErr != null) {
                JsonObject e = new JsonObject();
                e.addProperty("index", i);
                e.addProperty("command", expanded);
                e.addProperty("error", valErr);
                errors.add(e);
                continue;
            }
            boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expanded);
            JsonObject item = new JsonObject();
            item.addProperty("index", i);
            item.addProperty("command", expanded);
            item.addProperty("dispatched", success);
            results.add(item);
        }

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", errors.isEmpty());
        resp.add("results", results);
        if (!errors.isEmpty()) {
            resp.add("errors", errors);
        }
        return resp;
    }

    private static int[] vec3(JsonObject obj, String key, int[] fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return fallback;
        JsonArray arr = obj.getAsJsonArray(key);
        int x = arr.size() > 0 ? arr.get(0).getAsInt() : fallback[0];
        int y = arr.size() > 1 ? arr.get(1).getAsInt() : fallback[1];
        int z = arr.size() > 2 ? arr.get(2).getAsInt() : fallback[2];
        return new int[]{x, y, z};
    }

    private static String optString(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static boolean optBool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }
}
