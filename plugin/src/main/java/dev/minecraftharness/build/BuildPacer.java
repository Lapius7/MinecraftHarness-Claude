package dev.minecraftharness.build;

import com.google.gson.JsonObject;
import dev.minecraftharness.build.PrimitiveExpander.BlockPlacement;
import dev.minecraftharness.build.PrimitiveExpander.ExpandResult;
import dev.minecraftharness.context.SpatialContextProvider;
import dev.minecraftharness.context.SpatialContextProvider.BuildAnchor;
import dev.minecraftharness.context.SpatialContextProvider.StructureRecord;
import dev.minecraftharness.fx.VfxEngine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockSupport;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Fence;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.GlassPane;
import org.bukkit.block.data.type.Wall;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Adaptive Inter-Batch Pacing Engine.
 * Smoothly paces incoming block batches across ~90% of the predicted time until the next
 * model batch arrives, eliminating pauses between LLM turns.
 */
public final class BuildPacer {

    private static final int MAX_BLOCKS_PER_TICK = 900; // ~18,000 blocks/sec safety ceiling
    private static final int TURBO_BLOCKS_PER_TICK = 1200;
    private static final int MAX_GUARDED_FLUIDS = 50_000;
    private static final int MAX_SESSION_TRACKED_BLOCKS = 30_000;
    private static final BlockFace[] CARDINALS = { BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST };

    private record FluidKey(UUID worldId, int x, int y, int z) {}

    private final SpatialContextProvider contextProvider;
    private final TerrainSnapshotManager snapshotManager;
    private final PrimitiveExpander expander;
    private final VfxEngine vfx;

    private final Deque<BlockPlacement> queue = new ArrayDeque<>();
    private final Deque<BlockPlacement> catchupQueue = new ArrayDeque<>();
    private final LinkedHashSet<FluidKey> guardedFluids = new LinkedHashSet<>();
    private final List<FluidKey> sessionPlacedBlocks = new ArrayList<>();

    private double blocksPerTick = 40.0;
    private double fractionalAcc = 0.0;
    private boolean fastFinishing = false;
    private BuildAnchor buildAnchor;
    private boolean buildActive = false;
    private String speedMode = "fast";

    private int totalEnqueued = 0;
    private int totalPlaced = 0;
    private long buildStartMs = 0;
    private long lastBatchArrivalMs = 0L;
    private int parallelWaveCount = 0;
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    private String activeLabel = "AI Structure";
    private Consumer<JsonObject> onBuildCompleteCallback;

    public BuildPacer(
        SpatialContextProvider contextProvider,
        TerrainSnapshotManager snapshotManager,
        PrimitiveExpander expander,
        VfxEngine vfx
    ) {
        this.contextProvider = contextProvider;
        this.snapshotManager = snapshotManager;
        this.expander = expander;
        this.vfx = vfx;
    }

    public void setOnBuildCompleteCallback(Consumer<JsonObject> cb) {
        this.onBuildCompleteCallback = cb;
    }

    public String getSpeedMode() {
        return speedMode;
    }

    public void setSpeedMode(String mode) {
        if (mode == null) return;
        String m = mode.toLowerCase(java.util.Locale.ROOT).trim();
        if (m.equals("turbo") || m.equals("fast") || m.equals("cinematic")) {
            this.speedMode = m;
        }
    }

    public JsonObject enqueueBatch(JsonObject batchReq, String playerName) {
        BuildAnchor anchor = contextProvider.resolveAnchor(batchReq, playerName);
        if (batchReq.has("label") && batchReq.get("label").isJsonPrimitive()) {
            activeLabel = batchReq.get("label").getAsString();
        }
        snapshotManager.beginOrGetSession(activeLabel);

        ExpandResult expanded = expander.expand(batchReq, anchor);
        List<BlockPlacement> placements = expanded.placements();

        long now = System.currentTimeMillis();
        boolean isStreamed = batchReq.has("streamed") && batchReq.get("streamed").isJsonPrimitive()
            && batchReq.get("streamed").getAsBoolean();
        boolean isParallelBurst = !isStreamed && buildActive && (now - lastBatchArrivalMs) < 300L;
        lastBatchArrivalMs = now;

        if (!buildActive) {
            buildAnchor = anchor;
            buildActive = true;
            buildStartMs = now;
            totalEnqueued = 0;
            totalPlaced = 0;
            parallelWaveCount = 1;
            resetBounds();
        } else if (isStreamed) {
            // Streamed ops flow smoothly into the active queue without dumping to catchupQueue
            parallelWaveCount = 1;
        } else if (isParallelBurst) {
            parallelWaveCount++;
        } else {
            parallelWaveCount = 1;
            // Compress any remaining blocks from the previous LLM turn into catchupQueue (drained in ~4 ticks)
            if (!queue.isEmpty()) {
                while (!queue.isEmpty()) {
                    catchupQueue.add(queue.poll());
                }
            }
        }

        for (BlockPlacement bp : placements) {
            snapshotManager.recordBeforeWrite(bp.world(), bp.x(), bp.y(), bp.z());
            queue.add(bp);
            expandBounds(bp.x(), bp.y(), bp.z());
        }
        totalEnqueued += placements.size();

        double targetTicks;
        if (isStreamed) {
            long windowMs = switch (speedMode) {
                case "turbo" -> 150L;
                case "cinematic" -> 700L;
                default -> 250L;
            };
            targetTicks = Math.max(2.0, Math.min(20.0, Math.round(windowMs / 50.0)));
            blocksPerTick = Math.max(12.0, queue.size() / targetTicks);
        } else if (isParallelBurst) {
            long windowMs = switch (speedMode) {
                case "turbo" -> Math.min(800L, Math.max(250L, parallelWaveCount * 250L));
                case "cinematic" -> Math.min(5500L, Math.max(1800L, parallelWaveCount * 1800L));
                default -> Math.min(2200L, Math.max(700L, parallelWaveCount * 750L));
            };
            targetTicks = Math.max(5.0, Math.min(110.0, Math.round(windowMs / 50.0)));
            blocksPerTick = Math.max(8.0, queue.size() / targetTicks);
        } else {
            long predictedNextMs = batchReq.has("predictedNextMs") && batchReq.get("predictedNextMs").isJsonPrimitive()
                ? batchReq.get("predictedNextMs").getAsLong()
                : 1200L;
            long windowMs = switch (speedMode) {
                case "turbo" -> Math.min(500L, Math.max(150L, Math.round(predictedNextMs * 0.35)));
                case "cinematic" -> Math.min(2800L, Math.max(350L, Math.round(predictedNextMs * 0.90)));
                default -> Math.min(1200L, Math.max(250L, Math.round(predictedNextMs * 0.65)));
            };
            targetTicks = Math.max(3.0, Math.min(56.0, Math.round(windowMs / 50.0)));
            blocksPerTick = Math.max(8.0, placements.size() / targetTicks);
        }
        fastFinishing = false;

        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("queued", placements.size());
        resp.addProperty("totalQueued", totalEnqueued);
        resp.addProperty("targetTicks", Math.round(targetTicks));
        resp.addProperty("anchorOrigin", anchor.originX() + "," + anchor.originY() + "," + anchor.originZ());
        if (!expanded.warnings().isEmpty()) {
            var warnArr = new com.google.gson.JsonArray();
            for (String w : expanded.warnings()) warnArr.add(w);
            resp.add("warnings", warnArr);
        }
        return resp;
    }

    public JsonObject finishBuild(String labelOverride) {
        if (labelOverride != null && !labelOverride.isBlank()) {
            activeLabel = labelOverride;
        }
        fastFinishing = true;
        if (!buildActive && queue.isEmpty() && catchupQueue.isEmpty()) {
            BuildAnchor anchor = contextProvider.getActiveAnchor();
            if (anchor != null) {
                vfx.playFinishShockwave(anchor.toLocation(0, 1, 6));
            }
            snapshotManager.closeActiveSession();
            JsonObject resp = new JsonObject();
            resp.addProperty("ok", true);
            resp.addProperty("placed", totalPlaced);
            return resp;
        }
        JsonObject resp = new JsonObject();
        resp.addProperty("ok", true);
        resp.addProperty("remaining", queue.size() + catchupQueue.size());
        resp.addProperty("totalPlaced", totalPlaced);
        return resp;
    }

    public void stopNow() {
        queue.clear();
        catchupQueue.clear();
        sessionPlacedBlocks.clear();
        fastFinishing = false;
        buildActive = false;
        lastBatchArrivalMs = 0L;
        parallelWaveCount = 0;
        vfx.cleanupAll();
        snapshotManager.closeActiveSession();
        contextProvider.clearActiveAnchor();
    }

    public boolean isActive() {
        return buildActive || !queue.isEmpty() || !catchupQueue.isEmpty();
    }

    public int getRemainingCount() {
        return queue.size() + catchupQueue.size();
    }

    public int getTotalPlaced() {
        return totalPlaced;
    }

    public void tick() {
        if (queue.isEmpty() && catchupQueue.isEmpty()) {
            if (buildActive && fastFinishing) {
                completeBuildSession();
            }
            return;
        }

        int budget = "turbo".equals(speedMode) ? TURBO_BLOCKS_PER_TICK : MAX_BLOCKS_PER_TICK;
        int placedThisTick = 0;
        Location lastPlacedLoc = null;

        // 1. Drain catch-up queue first (spread smoothly across ~4 ticks, or instant on finish)
        if (!catchupQueue.isEmpty()) {
            int catchupCount = fastFinishing
                ? Math.min(budget, catchupQueue.size())
                : Math.min(budget, Math.max(24, (int) Math.ceil(catchupQueue.size() / 3.0)));
            for (int i = 0; i < catchupCount && !catchupQueue.isEmpty(); i++) {
                BlockPlacement bp = catchupQueue.poll();
                lastPlacedLoc = applyPlacement(bp, placedThisTick == 0 || (placedThisTick % 12 == 0));
                placedThisTick++;
                budget--;
            }
        }

        // 2. Drain main paced queue
        if (!queue.isEmpty() && budget > 0) {
            int targetCount;
            if (fastFinishing) {
                double drainDiv = "turbo".equals(speedMode) ? 2.0 : ("cinematic".equals(speedMode) ? 8.0 : 3.0);
                targetCount = Math.min(budget, Math.max(36, (int) Math.ceil(queue.size() / drainDiv)));
            } else {
                fractionalAcc += blocksPerTick;
                targetCount = (int) Math.floor(fractionalAcc);
                fractionalAcc -= targetCount;
                targetCount = Math.min(budget, Math.max(1, targetCount));
            }

            for (int i = 0; i < targetCount && !queue.isEmpty(); i++) {
                BlockPlacement bp = queue.poll();
                boolean animSample = (placedThisTick % 8 == 0);
                lastPlacedLoc = applyPlacement(bp, animSample);
                placedThisTick++;
            }
        }

        if (placedThisTick > 0 && lastPlacedLoc != null) {
            vfx.playBuildStepSound(lastPlacedLoc, placedThisTick);
        }

        if (queue.isEmpty() && catchupQueue.isEmpty() && fastFinishing && buildActive) {
            completeBuildSession();
        }
    }

    private static boolean isFluidData(BlockData data) {
        Material m = data.getMaterial();
        if (m == Material.WATER || m == Material.LAVA) return true;
        return data instanceof Waterlogged wl && wl.isWaterlogged();
    }

    public boolean shouldPreventFluidSpread(Block from, Block to) {
        FluidKey key = new FluidKey(from.getWorld().getUID(), from.getX(), from.getY(), from.getZ());
        if (guardedFluids.contains(key)) {
            return true;
        }
        if (buildActive && minX != Integer.MAX_VALUE) {
            BuildAnchor anchor = buildAnchor;
            if (anchor != null && anchor.world().getUID().equals(from.getWorld().getUID())) {
                return from.getX() >= minX - 3 && from.getX() <= maxX + 3
                    && from.getY() >= minY - 3 && from.getY() <= maxY + 3
                    && from.getZ() >= minZ - 3 && from.getZ() <= maxZ + 3;
            }
        }
        return false;
    }

    public void unguardFluidAt(Block b) {
        if (b == null) return;
        guardedFluids.remove(new FluidKey(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()));
    }

    public void clearGuardedFluids() {
        guardedFluids.clear();
    }

    public void pruneGuardedFluids() {
        Iterator<FluidKey> it = guardedFluids.iterator();
        while (it.hasNext()) {
            FluidKey k = it.next();
            World w = Bukkit.getWorld(k.worldId());
            if (w == null || !isFluidData(w.getBlockAt(k.x(), k.y(), k.z()).getBlockData())) {
                it.remove();
            }
        }
    }

    private static boolean isConnectableMaterial(Material m) {
        if (m == Material.IRON_BARS || m == Material.GLASS_PANE) return true;
        String n = m.name();
        return n.endsWith("_GLASS_PANE") || n.endsWith("_FENCE") || n.endsWith("_WALL");
    }

    private static boolean isConnectable(BlockData d) {
        return d instanceof GlassPane || d instanceof Fence || d instanceof Wall || d.getMaterial() == Material.IRON_BARS;
    }

    private static boolean isNoConnectSolid(Material m) {
        return m.name().endsWith("_LEAVES") || m == Material.BARRIER || m == Material.MELON
            || m == Material.PUMPKIN || m == Material.CARVED_PUMPKIN || m == Material.JACK_O_LANTERN;
    }

    private static void refreshSingleConnectable(Block b) {
        BlockData data = b.getBlockData();
        if (data instanceof GlassPane || data.getMaterial() == Material.IRON_BARS) {
            MultipleFacing mf = (MultipleFacing) data;
            boolean changed = false;
            for (BlockFace face : CARDINALS) {
                Block nb = b.getRelative(face);
                BlockData nd = nb.getBlockData();
                Material nm = nd.getMaterial();
                boolean connect = nd instanceof GlassPane || nm == Material.IRON_BARS || nd instanceof Wall
                    || (nd.isFaceSturdy(face.getOppositeFace(), BlockSupport.FULL) && !isNoConnectSolid(nm));
                if (mf.hasFace(face) != connect) {
                    mf.setFace(face, connect);
                    changed = true;
                }
            }
            if (changed) {
                b.setBlockData(mf, true);
            }
            return;
        }
        if (data instanceof Fence fence) {
            boolean isNether = b.getType() == Material.NETHER_BRICK_FENCE;
            boolean changed = false;
            for (BlockFace face : CARDINALS) {
                Block nb = b.getRelative(face);
                BlockData nd = nb.getBlockData();
                Material nm = nd.getMaterial();
                boolean sameFence = nd instanceof Fence && nm != Material.IRON_BARS && ((nm == Material.NETHER_BRICK_FENCE) == isNether);
                boolean gateConn = nd instanceof Gate g && (g.getFacing().getModX() != 0
                    ? (face == BlockFace.NORTH || face == BlockFace.SOUTH)
                    : (face == BlockFace.EAST || face == BlockFace.WEST));
                boolean sturdy = nd.isFaceSturdy(face.getOppositeFace(), BlockSupport.FULL) && !isNoConnectSolid(nm);
                boolean connect = sameFence || gateConn || sturdy;
                if (fence.hasFace(face) != connect) {
                    fence.setFace(face, connect);
                    changed = true;
                }
            }
            if (changed) {
                b.setBlockData(fence, true);
            }
            return;
        }
        if (data instanceof Wall wall) {
            Block above = b.getRelative(BlockFace.UP);
            boolean aboveAir = above.getType().isAir();
            boolean aboveSturdyDown = !aboveAir && above.getBlockData().isFaceSturdy(BlockFace.DOWN, BlockSupport.FULL);
            boolean changed = false;
            boolean n = false, e = false, s = false, w = false;
            for (BlockFace face : CARDINALS) {
                Block nb = b.getRelative(face);
                BlockData nd = nb.getBlockData();
                Material nm = nd.getMaterial();
                boolean connect = nd instanceof Wall || nd instanceof GlassPane || nm == Material.IRON_BARS
                    || nd instanceof Fence || nd instanceof Gate
                    || (nd.isFaceSturdy(face.getOppositeFace(), BlockSupport.FULL) && !isNoConnectSolid(nm));
                switch (face) {
                    case NORTH -> n = connect;
                    case EAST -> e = connect;
                    case SOUTH -> s = connect;
                    case WEST -> w = connect;
                    default -> {}
                }
                Wall.Height h = !connect ? Wall.Height.NONE : (aboveSturdyDown ? Wall.Height.TALL : Wall.Height.LOW);
                if (wall.getHeight(face) != h) {
                    wall.setHeight(face, h);
                    changed = true;
                }
            }
            boolean straightNS = (n && s && !e && !w);
            boolean straightEW = (e && w && !n && !s);
            boolean shouldUp = !(straightNS || straightEW) || !aboveAir;
            if (wall.isUp() != shouldUp) {
                wall.setUp(shouldUp);
                changed = true;
            }
            if (changed) {
                b.setBlockData(wall, true);
            }
        }
    }

    private static void refreshBlockConnections(Block block, Material placedMat, boolean updateNeighbors) {
        if (isConnectableMaterial(placedMat)) {
            refreshSingleConnectable(block);
        }
        if (updateNeighbors) {
            for (BlockFace face : CARDINALS) {
                Block nb = block.getRelative(face);
                if (isConnectableMaterial(nb.getType())) {
                    refreshSingleConnectable(nb);
                }
            }
        }
    }

    private Location applyPlacement(BlockPlacement bp, boolean sampleForFx) {
        World world = bp.world();
        Block block = world.getBlockAt(bp.x(), bp.y(), bp.z());
        block.setBlockData(bp.data(), bp.applyPhysics());
        Material placedMat = bp.data().getMaterial();
        FluidKey fk = new FluidKey(world.getUID(), bp.x(), bp.y(), bp.z());
        if (isFluidData(bp.data())) {
            guardedFluids.add(fk);
            if (guardedFluids.size() > MAX_GUARDED_FLUIDS) {
                Iterator<FluidKey> it = guardedFluids.iterator();
                while (guardedFluids.size() > MAX_GUARDED_FLUIDS && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        } else {
            guardedFluids.remove(fk);
            if (isConnectableMaterial(placedMat) && sessionPlacedBlocks.size() < MAX_SESSION_TRACKED_BLOCKS) {
                sessionPlacedBlocks.add(fk);
            }
            refreshBlockConnections(block, placedMat, true);
        }
        totalPlaced++;

        Location loc = new Location(world, bp.x(), bp.y(), bp.z());
        if (!placedMat.isAir()) {
            vfx.notifyPlacement(loc, bp.data(), sampleForFx);
        }
        return loc;
    }

    private void completeBuildSession() {
        buildActive = false;
        fastFinishing = false;
        fractionalAcc = 0.0;
        lastBatchArrivalMs = 0L;
        parallelWaveCount = 0;

        for (FluidKey k : sessionPlacedBlocks) {
            World w = Bukkit.getWorld(k.worldId());
            if (w != null) {
                Block b = w.getBlockAt(k.x(), k.y(), k.z());
                Material m = b.getType();
                if (isConnectableMaterial(m)) {
                    refreshSingleConnectable(b);
                }
            }
        }
        sessionPlacedBlocks.clear();

        BuildAnchor anchor = buildAnchor;
        Location center;
        if (minX != Integer.MAX_VALUE && anchor != null) {
            center = new Location(
                anchor.world(),
                (minX + maxX) * 0.5 + 0.5,
                minY + 1.0,
                (minZ + maxZ) * 0.5 + 0.5
            );
            contextProvider.recordStructure(StructureRecord.fromWorldBounds(
                contextProvider.nextStructureId(),
                activeLabel,
                anchor,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                null
            ));
        } else if (anchor != null) {
            center = anchor.toLocation(0, 1, 6);
        } else {
            Player p = contextProvider.resolvePlayer(null);
            center = p != null ? p.getLocation() : null;
        }

        vfx.playFinishShockwave(center);
        snapshotManager.closeActiveSession();
        buildAnchor = null;

        if (onBuildCompleteCallback != null) {
            JsonObject summary = new JsonObject();
            summary.addProperty("label", activeLabel);
            summary.addProperty("placed", totalPlaced);
            summary.addProperty("durationMs", System.currentTimeMillis() - buildStartMs);
            onBuildCompleteCallback.accept(summary);
        }
    }

    private void resetBounds() {
        minX = Integer.MAX_VALUE; minY = Integer.MAX_VALUE; minZ = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE; maxY = Integer.MIN_VALUE; maxZ = Integer.MIN_VALUE;
    }

    private void expandBounds(int x, int y, int z) {
        if (x < minX) minX = x;
        if (y < minY) minY = y;
        if (z < minZ) minZ = z;
        if (x > maxX) maxX = x;
        if (y > maxY) maxY = y;
        if (z > maxZ) maxZ = z;
    }
}
