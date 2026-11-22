package dev.minecraftharness.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Captures player gaze, nearest-cardinal camera basis, terrain heightmap,
 * and recent built structure anchors so OpenClaw places creations in frame.
 */
public final class SpatialContextProvider {

    public enum Cardinal {
        NORTH(0, 0, -1, 1, 0, 0, BlockFace.NORTH),
        EAST(1, 0, 0, 0, 0, 1, BlockFace.EAST),
        SOUTH(0, 0, 1, -1, 0, 0, BlockFace.SOUTH),
        WEST(-1, 0, 0, 0, 0, -1, BlockFace.WEST);

        public final int forwardX;
        public final int forwardY;
        public final int forwardZ;
        public final int rightX;
        public final int rightY;
        public final int rightZ;
        public final BlockFace face;

        Cardinal(int fx, int fy, int fz, int rx, int ry, int rz, BlockFace face) {
            this.forwardX = fx;
            this.forwardY = fy;
            this.forwardZ = fz;
            this.rightX = rx;
            this.rightY = ry;
            this.rightZ = rz;
            this.face = face;
        }

        public static Cardinal fromDirection(Vector dir) {
            double dx = dir.getX();
            double dz = dir.getZ();
            if (Math.abs(dx) >= Math.abs(dz)) {
                return dx >= 0 ? EAST : WEST;
            }
            return dz >= 0 ? SOUTH : NORTH;
        }

        public BlockFace rotateRight() {
            return switch (this) {
                case NORTH -> BlockFace.EAST;
                case EAST -> BlockFace.SOUTH;
                case SOUTH -> BlockFace.WEST;
                case WEST -> BlockFace.NORTH;
            };
        }

        public BlockFace rotateLeft() {
            return switch (this) {
                case NORTH -> BlockFace.WEST;
                case EAST -> BlockFace.NORTH;
                case SOUTH -> BlockFace.EAST;
                case WEST -> BlockFace.SOUTH;
            };
        }
    }

    public record BuildAnchor(
        String worldName,
        int originX,
        int originY,
        int originZ,
        Cardinal cardinal,
        double playerX,
        double playerY,
        double playerZ,
        float yaw,
        float pitch
    ) {
        public World world() {
            World w = Bukkit.getWorld(worldName);
            return w != null ? w : Bukkit.getWorlds().get(0);
        }

        public int worldX(int r, int f) {
            return originX + r * cardinal.rightX + f * cardinal.forwardX;
        }

        public int worldY(int u) {
            return originY + u;
        }

        public int worldZ(int r, int f) {
            return originZ + r * cardinal.rightZ + f * cardinal.forwardZ;
        }

        public int localR(int wx, int wz) {
            return (wx - originX) * cardinal.rightX + (wz - originZ) * cardinal.rightZ;
        }

        public int localU(int wy) {
            return wy - originY;
        }

        public int localF(int wx, int wz) {
            return (wx - originX) * cardinal.forwardX + (wz - originZ) * cardinal.forwardZ;
        }

        public Location toLocation(int r, int u, int f) {
            return new Location(world(), worldX(r, f) + 0.5, worldY(u) + 0.5, worldZ(r, f) + 0.5);
        }

        public BlockFace resolveFacing(String token) {
            if (token == null) return cardinal.face;
            return switch (token.toLowerCase(Locale.ROOT).trim()) {
                case "front", "away" -> cardinal.face;
                case "back", "toward", "camera" -> cardinal.face.getOppositeFace();
                case "right" -> cardinal.rotateRight();
                case "left" -> cardinal.rotateLeft();
                case "up" -> BlockFace.UP;
                case "down" -> BlockFace.DOWN;
                case "north" -> BlockFace.NORTH;
                case "east" -> BlockFace.EAST;
                case "south" -> BlockFace.SOUTH;
                case "west" -> BlockFace.WEST;
                default -> cardinal.face;
            };
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("world", worldName);
            obj.add("origin", intArr(originX, originY, originZ));
            obj.addProperty("cardinal", cardinal.name());
            obj.add("forwardVec", intArr(cardinal.forwardX, 0, cardinal.forwardZ));
            obj.add("rightVec", intArr(cardinal.rightX, 0, cardinal.rightZ));
            obj.add("playerPos", dblArr(playerX, playerY, playerZ));
            obj.addProperty("yaw", Math.round(yaw * 10.0) / 10.0);
            obj.addProperty("pitch", Math.round(pitch * 10.0) / 10.0);
            return obj;
        }
    }

    public record StructureRecord(
        String id,
        String label,
        BuildAnchor anchor,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ,
        int localMinR,
        int localMinU,
        int localMinF,
        int localMaxR,
        int localMaxU,
        int localMaxF,
        String commandCoreCoords
    ) {
        public static StructureRecord fromWorldBounds(
            String id,
            String label,
            BuildAnchor anchor,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            String commandCoreCoords
        ) {
            int rA = anchor.localR(minX, minZ);
            int rB = anchor.localR(maxX, maxZ);
            int fA = anchor.localF(minX, minZ);
            int fB = anchor.localF(maxX, maxZ);
            int lMinR = Math.min(rA, rB);
            int lMaxR = Math.max(rA, rB);
            int lMinU = anchor.localU(minY);
            int lMaxU = anchor.localU(maxY);
            int lMinF = Math.min(fA, fB);
            int lMaxF = Math.max(fA, fB);
            return new StructureRecord(
                id, label, anchor,
                minX, minY, minZ, maxX, maxY, maxZ,
                lMinR, lMinU, lMinF, lMaxR, lMaxU, lMaxF,
                commandCoreCoords
            );
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("label", label);
            o.add("origin", intArr(anchor.originX(), anchor.originY(), anchor.originZ()));
            o.addProperty("cardinal", anchor.cardinal().name());
            o.add("boundsMin", intArr(minX, minY, minZ));
            o.add("boundsMax", intArr(maxX, maxY, maxZ));
            o.add("localMin", intArr(localMinR, localMinU, localMinF));
            o.add("localMax", intArr(localMaxR, localMaxU, localMaxF));
            if (commandCoreCoords != null) {
                o.addProperty("commandCore", commandCoreCoords);
            }
            return o;
        }
    }

    private final List<StructureRecord> recentStructures = new ArrayList<>();
    private BuildAnchor activeAnchor;
    private int nextStructureSeq = 1;

    public synchronized String nextStructureId() {
        return "s" + (nextStructureSeq++);
    }

    public synchronized StructureRecord findStructure(String anchorId) {
        if (anchorId == null || anchorId.isBlank()) return null;
        String id = anchorId.trim();
        if ("last".equalsIgnoreCase(id)) {
            return recentStructures.isEmpty() ? null : recentStructures.get(0);
        }
        for (StructureRecord rec : recentStructures) {
            if (rec.id().equalsIgnoreCase(id)) {
                return rec;
            }
        }
        return null;
    }

    public synchronized BuildAnchor resolveAnchor(JsonObject params, String playerName) {
        if (params != null && params.has("anchorId") && params.get("anchorId").isJsonPrimitive()) {
            String aid = params.get("anchorId").getAsString().trim();
            if (!aid.isEmpty()) {
                StructureRecord rec = findStructure(aid);
                if (rec != null) {
                    return rec.anchor();
                }
            }
        }
        return getOrCaptureActiveAnchor(playerName);
    }

    public synchronized BuildAnchor getOrCaptureActiveAnchor(String playerName) {
        if (activeAnchor == null) {
            activeAnchor = captureAnchor(playerName);
        }
        return activeAnchor;
    }

    public synchronized BuildAnchor lockNewAnchor(String playerName) {
        activeAnchor = captureAnchor(playerName);
        return activeAnchor;
    }

    public synchronized void setActiveAnchor(BuildAnchor anchor) {
        activeAnchor = anchor;
    }

    public synchronized BuildAnchor getActiveAnchor() {
        return activeAnchor;
    }

    public synchronized void clearActiveAnchor() {
        activeAnchor = null;
    }

    public synchronized void recordStructure(StructureRecord record) {
        recentStructures.add(0, record);
        while (recentStructures.size() > 6) {
            recentStructures.remove(recentStructures.size() - 1);
        }
    }

    public synchronized List<StructureRecord> getRecentStructures() {
        return Collections.unmodifiableList(new ArrayList<>(recentStructures));
    }

    public synchronized void clearStructures() {
        recentStructures.clear();
        nextStructureSeq = 1;
    }

    public Player resolvePlayer(String playerName) {
        if (playerName != null && !playerName.isBlank()) {
            Player exact = Bukkit.getPlayerExact(playerName);
            if (exact != null && exact.isOnline()) return exact;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            return p;
        }
        return null;
    }

    public BuildAnchor captureAnchor(String playerName) {
        Player player = resolvePlayer(playerName);
        if (player == null) {
            World world = Bukkit.getWorlds().get(0);
            Location spawn = world.getSpawnLocation();
            int groundY = findSurfaceY(world, spawn.getBlockX(), spawn.getBlockZ(), spawn.getBlockY());
            return new BuildAnchor(
                world.getName(),
                spawn.getBlockX(),
                groundY,
                spawn.getBlockZ(),
                Cardinal.NORTH,
                spawn.getX(),
                spawn.getY(),
                spawn.getZ(),
                180.0f,
                0.0f
            );
        }

        World world = player.getWorld();
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        Cardinal cardinal = Cardinal.fromDirection(dir);

        // Raycast up to 64 blocks to find what the player is looking at
        RayTraceResult hit = world.rayTraceBlocks(eye, dir, 64.0, FluidCollisionMode.NEVER, true);
        int targetX;
        int targetY;
        int targetZ;

        if (hit != null && hit.getHitBlock() != null) {
            Block hitBlock = hit.getHitBlock();
            BlockFace face = hit.getHitBlockFace();
            Block placement = hitBlock.getRelative(face != null ? face : BlockFace.UP);
            targetX = placement.getX();
            targetY = placement.getY();
            targetZ = placement.getZ();
        } else {
            // Looking at sky: project 14 blocks forward and snap to nearby ground if close
            Location ahead = eye.clone().add(dir.clone().multiply(14.0));
            targetX = ahead.getBlockX();
            targetZ = ahead.getBlockZ();
            int surfaceY = findSurfaceY(world, targetX, targetZ, player.getLocation().getBlockY());
            if (Math.abs(ahead.getBlockY() - surfaceY) <= 10) {
                targetY = surfaceY;
            } else {
                targetY = Math.max(world.getMinHeight() + 4, ahead.getBlockY());
            }
        }

        return new BuildAnchor(
            world.getName(),
            targetX,
            targetY,
            targetZ,
            cardinal,
            player.getLocation().getX(),
            player.getLocation().getY(),
            player.getLocation().getZ(),
            eye.getYaw(),
            eye.getPitch()
        );
    }

    public JsonObject buildFullContextJson(String playerName, boolean lockAnchor) {
        BuildAnchor anchor = lockAnchor ? lockNewAnchor(playerName) : getOrCaptureActiveAnchor(playerName);
        Player player = resolvePlayer(playerName);
        World world = anchor.world();

        JsonObject root = new JsonObject();
        root.addProperty("hasPlayer", player != null);
        if (player != null) {
            root.addProperty("playerName", player.getName());
            root.addProperty("gameMode", player.getGameMode().name());
            Vector dir = player.getEyeLocation().getDirection();
            root.add("lookVector", dblArr(dir.getX(), dir.getY(), dir.getZ()));
        }

        root.add("anchor", anchor.toJson());
        root.addProperty("worldTime", world.getTime());
        root.addProperty("biome", world.getBiome(anchor.originX(), anchor.originY(), anchor.originZ()).getKey().getKey());

        // Scan 5x5 local terrain heightmap relative to anchor (spacing 4 blocks: -8..+8)
        JsonArray heightRows = new JsonArray();
        Material dominant = Material.GRASS_BLOCK;
        int minH = Integer.MAX_VALUE;
        int maxH = Integer.MIN_VALUE;

        for (int f = -8; f <= 8; f += 4) {
            JsonArray row = new JsonArray();
            for (int r = -8; r <= 8; r += 4) {
                int wx = anchor.worldX(r, f);
                int wz = anchor.worldZ(r, f);
                int surfY = findSurfaceY(world, wx, wz, anchor.originY());
                int relY = surfY - anchor.originY();
                row.add(relY);
                if (relY < minH) minH = relY;
                if (relY > maxH) maxH = relY;
                if (r == 0 && f == 0) {
                    Material below = world.getBlockAt(wx, surfY - 1, wz).getType();
                    if (!below.isAir()) dominant = below;
                }
            }
            heightRows.add(row);
        }

        JsonObject terrain = new JsonObject();
        terrain.addProperty("gridSpacing", 4);
        terrain.addProperty("axes", "rows=forward(-8..+8), cols=right(-8..+8), values=relativeUp");
        terrain.addProperty("dominantSurfaceBlock", dominant.name().toLowerCase(Locale.ROOT));
        terrain.addProperty("minRelY", minH);
        terrain.addProperty("maxRelY", maxH);
        terrain.add("heightmap", heightRows);
        root.add("terrain", terrain);

        JsonArray recentArr = new JsonArray();
        synchronized (this) {
            for (StructureRecord rec : recentStructures) {
                recentArr.add(rec.toJson());
            }
        }
        root.add("recentStructures", recentArr);
        root.add("nearby", scanSurroundingSummary(world, anchor));

        return root;
    }

    private JsonObject scanSurroundingSummary(World world, BuildAnchor anchor) {
        JsonArray mapRows = new JsonArray();
        Map<String, Integer> matCounts = new HashMap<>();
        int aboveGroundBlocks = 0;
        int minR = Integer.MAX_VALUE, minU = Integer.MAX_VALUE, minF = Integer.MAX_VALUE;
        int maxR = Integer.MIN_VALUE, maxU = Integer.MIN_VALUE, maxF = Integer.MIN_VALUE;

        int maxScanU = Math.min(14, world.getMaxHeight() - 1 - anchor.originY());
        int minScanU = Math.max(-2, world.getMinHeight() - anchor.originY());

        for (int f = 0; f <= 20; f += 2) {
            StringBuilder row = new StringBuilder(11);
            for (int r = -10; r <= 10; r += 2) {
                int wx = anchor.worldX(r, f);
                int wz = anchor.worldZ(r, f);
                Material topMat = null;
                int topU = minScanU;

                for (int u = maxScanU; u >= minScanU; u--) {
                    Material m = world.getBlockAt(wx, anchor.worldY(u), wz).getType();
                    if (m.isAir() || isSurfaceFlora(m)) continue;

                    if (topMat == null) {
                        topMat = m;
                        topU = u;
                    }
                    if (u >= 0) {
                        aboveGroundBlocks++;
                        matCounts.merge(m.name().toLowerCase(Locale.ROOT), 1, Integer::sum);
                        if (r < minR) minR = r;
                        if (u < minU) minU = u;
                        if (f < minF) minF = f;
                        if (r > maxR) maxR = r;
                        if (u > maxU) maxU = u;
                        if (f > maxF) maxF = f;
                    } else {
                        break;
                    }
                }
                row.append(classifyColumnSymbol(topMat, topU));
            }
            mapRows.add(String.format(Locale.ROOT, "f%02d: %s", f, row.toString()));
        }

        JsonObject topMats = new JsonObject();
        matCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(8)
            .forEach(e -> topMats.addProperty(e.getKey(), e.getValue()));

        Map<String, Integer> entityCounts = new HashMap<>();
        Location scanCenter = anchor.toLocation(0, 2, 10);
        for (Entity e : world.getNearbyEntities(scanCenter, 20, 12, 20)) {
            if (e instanceof Player || e instanceof Display) continue;
            String name = e.getType().name().toLowerCase(Locale.ROOT);
            entityCounts.merge(name, 1, Integer::sum);
        }
        JsonArray entitiesArr = new JsonArray();
        entityCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(8)
            .forEach(e -> entitiesArr.add(e.getKey() + "(" + e.getValue() + ")"));

        JsonObject nearby = new JsonObject();
        nearby.addProperty("legend", ".=ground ~=water T=tree #=build ^=hill _=low (2-block step, rows f=0..20, cols r=-10..+10)");
        nearby.add("map", mapRows);
        nearby.addProperty("aboveGroundBlocks", aboveGroundBlocks);
        nearby.add("topMaterials", topMats);
        if (aboveGroundBlocks > 0) {
            JsonObject bounds = new JsonObject();
            bounds.add("min", intArr(minR, minU, minF));
            bounds.add("max", intArr(maxR, maxU, maxF));
            nearby.add("occupiedBoundsLocal", bounds);
        }
        nearby.add("nearbyEntities", entitiesArr);
        return nearby;
    }

    private static char classifyColumnSymbol(Material mat, int topU) {
        if (mat == null) return '_';
        if (mat == Material.WATER || mat == Material.LAVA || mat == Material.ICE
            || mat == Material.PACKED_ICE || mat == Material.BLUE_ICE) {
            return '~';
        }
        String n = mat.name();
        if (n.endsWith("_LOG") || n.endsWith("_LEAVES") || n.endsWith("_WOOD")
            || mat == Material.VINE || mat == Material.BAMBOO
            || mat == Material.AZALEA || mat == Material.FLOWERING_AZALEA
            || mat == Material.MANGROVE_ROOTS || mat == Material.MUDDY_MANGROVE_ROOTS) {
            return 'T';
        }
        if (isManMadeMaterial(n)) {
            return '#';
        }
        if (topU >= 2) return '^';
        if (topU <= -2) return '_';
        return '.';
    }

    private static boolean isSurfaceFlora(Material m) {
        return m == Material.SHORT_GRASS || m == Material.TALL_GRASS
            || m == Material.FERN || m == Material.LARGE_FERN
            || m == Material.DEAD_BUSH || m == Material.SNOW;
    }

    private static boolean isManMadeMaterial(String n) {
        return n.endsWith("_PLANKS") || n.endsWith("_BRICKS") || n.endsWith("_STAIRS")
            || n.endsWith("_SLAB") || n.endsWith("_FENCE") || n.endsWith("_FENCE_GATE")
            || n.endsWith("_WALL") || n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR")
            || n.endsWith("_GLASS") || n.endsWith("_GLASS_PANE") || n.endsWith("_CONCRETE")
            || n.endsWith("_CONCRETE_POWDER") || n.endsWith("_TERRACOTTA") || n.endsWith("_WOOL")
            || n.endsWith("_CARPET") || n.endsWith("_LANTERN") || n.endsWith("_TORCH")
            || n.endsWith("_LAMP") || n.endsWith("_SIGN") || n.endsWith("_BED")
            || n.endsWith("_BANNER") || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE")
            || n.contains("GLASS") || n.contains("BRICK") || n.contains("QUARTZ")
            || n.contains("COPPER") || n.contains("PRISMARINE") || n.contains("PURPUR")
            || n.contains("COMMAND_BLOCK") || n.contains("FROGLIGHT") || n.contains("POLISHED_")
            || n.contains("CHISELED_") || n.contains("SMOOTH_") || n.contains("STRIPPED_")
            || n.equals("COBBLESTONE") || n.equals("MOSSY_COBBLESTONE") || n.equals("DEEPSLATE_TILES")
            || n.equals("SEA_LANTERN") || n.equals("GLOWSTONE") || n.equals("SHROOMLIGHT")
            || n.equals("END_ROD") || n.equals("IRON_BARS") || n.equals("CHAIN")
            || n.equals("CHEST") || n.equals("BARREL") || n.equals("BOOKSHELF")
            || n.equals("AMETHYST_BLOCK") || n.equals("TORCH") || n.equals("LANTERN")
            || n.equals("SOUL_LANTERN") || n.equals("BEACON");
    }

    public int findSurfaceY(World world, int x, int z, int hintY) {
        int top = Math.min(world.getMaxHeight() - 2, hintY + 24);
        int bottom = Math.max(world.getMinHeight() + 1, hintY - 24);
        for (int y = top; y >= bottom; y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (!m.isAir() && m != Material.SHORT_GRASS && m != Material.TALL_GRASS && m != Material.SNOW) {
                return y + 1;
            }
        }
        return world.getHighestBlockYAt(x, z) + 1;
    }

    private static JsonArray intArr(int a, int b, int c) {
        JsonArray arr = new JsonArray();
        arr.add(a);
        arr.add(b);
        arr.add(c);
        return arr;
    }

    private static JsonArray dblArr(double a, double b, double c) {
        JsonArray arr = new JsonArray();
        arr.add(Math.round(a * 100.0) / 100.0);
        arr.add(Math.round(b * 100.0) / 100.0);
        arr.add(Math.round(c * 100.0) / 100.0);
        return arr;
    }
}
