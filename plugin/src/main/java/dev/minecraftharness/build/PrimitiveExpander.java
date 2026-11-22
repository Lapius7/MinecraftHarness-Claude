package dev.minecraftharness.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minecraftharness.context.SpatialContextProvider;
import dev.minecraftharness.context.SpatialContextProvider.BuildAnchor;
import dev.minecraftharness.context.SpatialContextProvider.StructureRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands architectural DSL primitives into exact sorted Minecraft block placements.
 * Supports common modifiers (repeat, mirrorX), 3D region move/shift/copy,
 * fuzzy material resolution, BlockData caching, and physical auto-fixers.
 */
public final class PrimitiveExpander {

    public record BlockPlacement(
        World world,
        int x,
        int y,
        int z,
        BlockData data,
        boolean applyPhysics,
        int seqIndex
    ) {}

    public record ExpandResult(
        List<BlockPlacement> placements,
        List<String> warnings
    ) {}

    private record PaletteEntry(String rawSpec, int weight) {}

    private record CapturedBlock(int x, int y, int z, BlockData data) {}

    private static final int MAX_MOVE_VOLUME = 100_000;
    private static final int MAX_BLOCKDATA_CACHE = 4096;

    private static final Pattern FACING_TOKEN = Pattern.compile(
        "(facing=)(front|back|left|right|up|down|away|toward|camera)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Map<String, String> MATERIAL_ALIASES = buildMaterialAliases();

    private final SpatialContextProvider contextProvider;
    private final Map<String, BlockData> blockDataCache = new ConcurrentHashMap<>();

    public PrimitiveExpander() {
        this(null);
    }

    public PrimitiveExpander(SpatialContextProvider contextProvider) {
        this.contextProvider = contextProvider;
    }

    public ExpandResult expand(JsonObject batchReq, BuildAnchor anchor) {
        List<String> warnings = new ArrayList<>();
        World world = anchor.world();

        String coordMode = optString(batchReq, "coordMode", "local").toLowerCase(Locale.ROOT);
        boolean isWorld = "world".equals(coordMode);
        String sortMode = optString(batchReq, "sortMode", "bottom_up").toLowerCase(Locale.ROOT);

        JsonArray ops = batchReq.has("ops") && batchReq.get("ops").isJsonArray()
            ? batchReq.getAsJsonArray("ops")
            : new JsonArray();

        // Deduplicate by coordinate within the same batch (later op wins)
        Map<Long, BlockPlacement> dedup = new LinkedHashMap<>();
        int seq = 0;

        for (int idx = 0; idx < ops.size(); idx++) {
            JsonElement el = ops.get(idx);
            if (!el.isJsonObject()) continue;
            JsonObject op = el.getAsJsonObject();
            String type = optString(op, "type", "box").toLowerCase(Locale.ROOT);

            boolean hasRepeat = op.has("repeat") && op.get("repeat").isJsonObject();
            boolean hasMirror = optBool(op, "mirrorX", false) || optBool(op, "mirror_x", false);
            Map<Long, BlockPlacement> targetOut = (hasRepeat || hasMirror) ? new LinkedHashMap<>() : dedup;

            switch (type) {
                case "box" -> seq = expandBox(op, anchor, world, isWorld, false, targetOut, warnings, seq);
                case "hollow_box" -> seq = expandBox(op, anchor, world, isWorld, true, targetOut, warnings, seq);
                case "clear" -> seq = expandClear(op, anchor, world, isWorld, targetOut, seq);
                case "pillar" -> seq = expandPillar(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "cylinder" -> seq = expandCylinder(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "dome" -> seq = expandDome(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "arch" -> seq = expandArch(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "stairs_run" -> seq = expandStairsRun(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "bridge" -> seq = expandBridge(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "spiral" -> seq = expandSpiral(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "ring" -> seq = expandRing(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "scatter" -> seq = expandScatter(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "pattern" -> seq = expandPattern(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "blocks" -> seq = expandBlocks(op, anchor, world, isWorld, targetOut, warnings, seq);
                case "move", "shift", "copy" -> seq = expandMove(op, type, anchor, world, isWorld, dedup, targetOut, warnings, seq);
                default -> warnings.add("Unknown op type '" + type + "' at ops[" + idx + "]");
            }

            if (hasRepeat || hasMirror) {
                seq = applyModifiersAndMerge(op, anchor, world, isWorld, targetOut, dedup, seq);
            }
        }

        seq = applyPhysicalAutoFixers(dedup, seq);

        List<BlockPlacement> list = new ArrayList<>(dedup.values());
        sortPlacements(list, sortMode, anchor);
        return new ExpandResult(list, warnings);
    }

    // -------------------------------------------------------------------------
    // Common Modifiers: repeat & mirrorX
    // -------------------------------------------------------------------------

    private int applyModifiersAndMerge(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> opOut, Map<Long, BlockPlacement> dedup, int seq
    ) {
        List<BlockPlacement> working = new ArrayList<>(opOut.values());

        if (op.has("repeat") && op.get("repeat").isJsonObject()) {
            JsonObject rep = op.getAsJsonObject("repeat");
            int count = Math.max(1, Math.min(64, optInt(rep, "count", 1)));
            int dr = 0, du = 0, df = 0;
            if (rep.has("offset") && rep.get("offset").isJsonArray()) {
                int[] off = parseArr3(rep.getAsJsonArray("offset"));
                dr = off[0]; du = off[1]; df = off[2];
            } else {
                String axis = optString(rep, "axis", "r").toLowerCase(Locale.ROOT);
                int step = optInt(rep, "step", 1);
                switch (axis) {
                    case "u", "y" -> du = step;
                    case "f", "z" -> df = step;
                    default -> dr = step;
                }
            }
            int dwx = isWorld ? dr : (dr * anchor.cardinal().rightX + df * anchor.cardinal().forwardX);
            int dwy = du;
            int dwz = isWorld ? df : (dr * anchor.cardinal().rightZ + df * anchor.cardinal().forwardZ);

            List<BlockPlacement> expanded = new ArrayList<>(working.size() * count);
            for (int i = 0; i < count; i++) {
                int ox = i * dwx;
                int oy = i * dwy;
                int oz = i * dwz;
                for (BlockPlacement bp : working) {
                    int ny = bp.y() + oy;
                    if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) continue;
                    expanded.add(new BlockPlacement(
                        bp.world(), bp.x() + ox, ny, bp.z() + oz,
                        bp.data(), bp.applyPhysics(), seq++
                    ));
                }
            }
            working = expanded;
        }

        boolean mirror = optBool(op, "mirrorX", false) || optBool(op, "mirror_x", false);
        int mirrorCenter = optInt(op, "mirrorCenter", 0);

        for (BlockPlacement bp : working) {
            seq = putBlock(bp.world(), bp.x(), bp.y(), bp.z(), bp.data(), bp.applyPhysics(), dedup, seq);
            if (mirror) {
                int mx, mz;
                if (isWorld) {
                    mx = 2 * mirrorCenter - bp.x();
                    mz = bp.z();
                } else {
                    int r = anchor.localR(bp.x(), bp.z());
                    int f = anchor.localF(bp.x(), bp.z());
                    int mr = 2 * mirrorCenter - r;
                    mx = anchor.worldX(mr, f);
                    mz = anchor.worldZ(mr, f);
                }
                if (mx != bp.x() || mz != bp.z()) {
                    BlockData mirroredData = mirrorBlockData(bp.data(), anchor, isWorld);
                    seq = putBlock(bp.world(), mx, bp.y(), mz, mirroredData, bp.applyPhysics(), dedup, seq);
                }
            }
        }
        return seq;
    }

    private BlockData mirrorBlockData(BlockData data, BuildAnchor anchor, boolean isWorld) {
        BlockFace rightFace = isWorld ? BlockFace.EAST : anchor.cardinal().rotateRight();
        BlockFace leftFace = isWorld ? BlockFace.WEST : anchor.cardinal().rotateLeft();

        boolean flipDir = (data instanceof Directional dir)
            && (dir.getFacing() == rightFace || dir.getFacing() == leftFace);
        boolean flipDoor = (data instanceof Door);

        if (!flipDir && !flipDoor) {
            return data;
        }
        BlockData cloned = data.clone();
        if (flipDir && cloned instanceof Directional dir) {
            dir.setFacing(dir.getFacing() == rightFace ? leftFace : rightFace);
        }
        if (flipDoor && cloned instanceof Door door) {
            door.setHinge(door.getHinge() == Door.Hinge.LEFT ? Door.Hinge.RIGHT : Door.Hinge.LEFT);
        }
        return cloned;
    }

    // -------------------------------------------------------------------------
    // Physical Auto-Fixers (Doors, Beds, 2-Block Flora)
    // -------------------------------------------------------------------------

    private int applyPhysicalAutoFixers(Map<Long, BlockPlacement> dedup, int seq) {
        List<BlockPlacement> snapshot = new ArrayList<>(dedup.values());
        Map<Long, BlockPlacement> extra = new LinkedHashMap<>();

        // 1. Detect adjacent same-facing lower doors and pair double-door hinges
        Map<Long, BlockPlacement> lowerDoors = new LinkedHashMap<>();
        for (BlockPlacement bp : snapshot) {
            if (bp.data() instanceof Door door && door.getHalf() == Bisected.Half.BOTTOM) {
                lowerDoors.put(packKey(bp.x(), bp.y(), bp.z()), bp);
            }
        }

        Set<Long> pairedDoors = new HashSet<>();
        for (Map.Entry<Long, BlockPlacement> entry : lowerDoors.entrySet()) {
            long key = entry.getKey();
            if (pairedDoors.contains(key)) continue;
            BlockPlacement bp = entry.getValue();
            Door door = (Door) bp.data();
            BlockFace facing = door.getFacing();
            BlockFace rightSide = rotateClockwise(facing);
            long rightKey = packKey(bp.x() + rightSide.getModX(), bp.y(), bp.z() + rightSide.getModZ());
            BlockPlacement rightBp = lowerDoors.get(rightKey);
            if (rightBp != null && rightBp.data() instanceof Door rightDoor
                && rightDoor.getFacing() == facing && rightDoor.getMaterial() == door.getMaterial()) {
                Door leftClone = (Door) door.clone();
                leftClone.setHinge(Door.Hinge.LEFT);
                BlockPlacement updatedLeft = new BlockPlacement(bp.world(), bp.x(), bp.y(), bp.z(), leftClone, false, bp.seqIndex());
                dedup.put(key, updatedLeft);
                lowerDoors.put(key, updatedLeft);

                Door rightClone = (Door) rightDoor.clone();
                rightClone.setHinge(Door.Hinge.RIGHT);
                BlockPlacement updatedRight = new BlockPlacement(rightBp.world(), rightBp.x(), rightBp.y(), rightBp.z(), rightClone, false, rightBp.seqIndex());
                dedup.put(rightKey, updatedRight);
                lowerDoors.put(rightKey, updatedRight);

                pairedDoors.add(key);
                pairedDoors.add(rightKey);
            }
        }

        // 2. Emit upper halves for doors, head parts for beds, and upper halves for 2-block tall flora
        for (BlockPlacement bp : dedup.values()) {
            BlockData data = bp.data();
            World world = bp.world();

            if (data instanceof Door door && door.getHalf() == Bisected.Half.BOTTOM) {
                int uy = bp.y() + 1;
                if (uy < world.getMaxHeight()) {
                    long upKey = packKey(bp.x(), uy, bp.z());
                    BlockPlacement existing = dedup.get(upKey);
                    if (existing == null || !(existing.data() instanceof Door exDoor)
                        || exDoor.getHalf() != Bisected.Half.TOP
                        || exDoor.getMaterial() != door.getMaterial()
                        || exDoor.getHinge() != door.getHinge()) {
                        Door upper = (Door) door.clone();
                        upper.setHalf(Bisected.Half.TOP);
                        extra.put(upKey, new BlockPlacement(world, bp.x(), uy, bp.z(), upper, false, seq++));
                    }
                }
            } else if (data instanceof Bed bed && bed.getPart() == Bed.Part.FOOT) {
                BlockFace f = bed.getFacing();
                int hx = bp.x() + f.getModX();
                int hy = bp.y();
                int hz = bp.z() + f.getModZ();
                long headKey = packKey(hx, hy, hz);
                BlockPlacement existing = dedup.get(headKey);
                if (existing == null || !(existing.data() instanceof Bed exBed)
                    || exBed.getPart() != Bed.Part.HEAD
                    || exBed.getMaterial() != bed.getMaterial()) {
                    Bed head = (Bed) bed.clone();
                    head.setPart(Bed.Part.HEAD);
                    extra.put(headKey, new BlockPlacement(world, hx, hy, hz, head, false, seq++));
                }
            } else if (isTwoBlockTallFlora(data.getMaterial())
                && data instanceof Bisected bis && bis.getHalf() == Bisected.Half.BOTTOM) {
                int uy = bp.y() + 1;
                if (uy < world.getMaxHeight()) {
                    long upKey = packKey(bp.x(), uy, bp.z());
                    BlockPlacement existing = dedup.get(upKey);
                    if (existing == null || !(existing.data() instanceof Bisected exBis)
                        || exBis.getHalf() != Bisected.Half.TOP
                        || exBis.getMaterial() != bis.getMaterial()) {
                        Bisected upper = (Bisected) bis.clone();
                        upper.setHalf(Bisected.Half.TOP);
                        extra.put(upKey, new BlockPlacement(world, bp.x(), uy, bp.z(), upper, false, seq++));
                    }
                }
            }
        }

        dedup.putAll(extra);
        return seq;
    }

    private static boolean isTwoBlockTallFlora(Material m) {
        return switch (m) {
            case SUNFLOWER, ROSE_BUSH, LILAC, PEONY, TALL_GRASS, LARGE_FERN, PITCHER_PLANT -> true;
            default -> false;
        };
    }

    private static BlockFace rotateClockwise(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    // -------------------------------------------------------------------------
    // New 3D Region Op: type="move" / "shift" / "copy"
    // -------------------------------------------------------------------------

    private int expandMove(
        JsonObject op, String type, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> batchDedup, Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        boolean isCopy = optBool(op, "copy", "copy".equals(type));
        boolean includeAir = optBool(op, "includeAir", false);
        String clearFillSpec = optString(op, "clearFill", "air");

        int x0, y0, z0, x1, y1, z1;
        World srcWorld = world;
        boolean hasExplicitBounds = op.has("from") && op.get("from").isJsonArray()
            && op.has("to") && op.get("to").isJsonArray();

        if (op.has("structureId") && op.get("structureId").isJsonPrimitive()) {
            String sid = op.get("structureId").getAsString().trim();
            StructureRecord rec = contextProvider != null ? contextProvider.findStructure(sid) : null;
            if (rec == null) {
                warnings.add("move/shift: structureId '" + sid + "' not found");
                return seq;
            }
            srcWorld = rec.anchor().world();
            x0 = rec.minX(); y0 = rec.minY(); z0 = rec.minZ();
            x1 = rec.maxX(); y1 = rec.maxY(); z1 = rec.maxZ();
        } else if (hasExplicitBounds) {
            int[] a = vec3(op, "from", new int[]{0, 0, 0});
            int[] b = vec3(op, "to", a);
            int wx1 = isWorld ? a[0] : anchor.worldX(a[0], a[2]);
            int wy1 = isWorld ? a[1] : anchor.worldY(a[1]);
            int wz1 = isWorld ? a[2] : anchor.worldZ(a[0], a[2]);
            int wx2 = isWorld ? b[0] : anchor.worldX(b[0], b[2]);
            int wy2 = isWorld ? b[1] : anchor.worldY(b[1]);
            int wz2 = isWorld ? b[2] : anchor.worldZ(b[0], b[2]);
            x0 = Math.min(wx1, wx2); x1 = Math.max(wx1, wx2);
            y0 = Math.min(wy1, wy2); y1 = Math.max(wy1, wy2);
            z0 = Math.min(wz1, wz2); z1 = Math.max(wz1, wz2);
        } else {
            StructureRecord rec = contextProvider != null ? contextProvider.findStructure("last") : null;
            if (rec == null) {
                warnings.add("move/shift: no from/to bounds and no recent structure available");
                return seq;
            }
            srcWorld = rec.anchor().world();
            x0 = rec.minX(); y0 = rec.minY(); z0 = rec.minZ();
            x1 = rec.maxX(); y1 = rec.maxY(); z1 = rec.maxZ();
        }

        // Clamp Y to world bounds
        y0 = Math.max(srcWorld.getMinHeight(), y0);
        y1 = Math.min(srcWorld.getMaxHeight() - 1, y1);
        if (y1 < y0) return seq;

        long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (volume > MAX_MOVE_VOLUME) {
            warnings.add("move/shift volume " + volume + " exceeds safety cap (" + MAX_MOVE_VOLUME + "); skipped");
            return seq;
        }

        // Parse shift vector: by / offset / shorthand dy, du, dr, df
        int dr = 0, du = 0, df = 0;
        if (op.has("by") && op.get("by").isJsonArray()) {
            int[] v = parseArr3(op.getAsJsonArray("by"));
            dr = v[0]; du = v[1]; df = v[2];
        } else if (op.has("offset") && op.get("offset").isJsonArray()) {
            int[] v = parseArr3(op.getAsJsonArray("offset"));
            dr = v[0]; du = v[1]; df = v[2];
        } else {
            dr = optInt(op, "dr", optInt(op, "dx", 0));
            du = optInt(op, "du", optInt(op, "dy", 0));
            df = optInt(op, "df", optInt(op, "dz", 0));
        }

        int dwx = isWorld ? dr : (dr * anchor.cardinal().rightX + df * anchor.cardinal().forwardX);
        int dwy = du;
        int dwz = isWorld ? df : (dr * anchor.cardinal().rightZ + df * anchor.cardinal().forwardZ);
        if (dwx == 0 && dwy == 0 && dwz == 0) return seq;

        // Phase 1: Snapshot every block in the source box FIRST (prevents overlap corruption!)
        List<CapturedBlock> captured = new ArrayList<>((int) Math.min(volume, 4096));
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    long k = packKey(x, y, z);
                    BlockPlacement earlier = batchDedup.get(k);
                    BlockData data = (earlier != null)
                        ? earlier.data().clone()
                        : srcWorld.getBlockAt(x, y, z).getBlockData().clone();
                    captured.add(new CapturedBlock(x, y, z, data));
                }
            }
        }

        // Phase 2: Compute set of destination keys that will be written
        Set<Long> destWritten = new HashSet<>();
        for (CapturedBlock cb : captured) {
            if (cb.data().getMaterial().isAir() && !includeAir) continue;
            int ny = cb.y() + dwy;
            if (ny < srcWorld.getMinHeight() || ny >= srcWorld.getMaxHeight()) continue;
            destWritten.add(packKey(cb.x() + dwx, ny, cb.z() + dwz));
        }

        // Phase 3: Clear vacated source cells (when copy == false)
        if (!isCopy) {
            BlockData fillData = parseBlockData(clearFillSpec, anchor, warnings);
            if (fillData == null) fillData = Material.AIR.createBlockData();
            boolean fillPhysics = shouldApplyPhysics(fillData);
            for (CapturedBlock cb : captured) {
                if (cb.data().getMaterial().isAir()) continue;
                long srcKey = packKey(cb.x(), cb.y(), cb.z());
                if (!destWritten.contains(srcKey)) {
                    seq = putBlock(srcWorld, cb.x(), cb.y(), cb.z(), fillData, fillPhysics, out, seq);
                }
            }
        }

        // Phase 4: Emit destination blocks
        for (CapturedBlock cb : captured) {
            if (cb.data().getMaterial().isAir() && !includeAir) continue;
            int nx = cb.x() + dwx;
            int ny = cb.y() + dwy;
            int nz = cb.z() + dwz;
            if (ny < srcWorld.getMinHeight() || ny >= srcWorld.getMaxHeight()) continue;
            seq = putBlock(srcWorld, nx, ny, nz, cb.data(), shouldApplyPhysics(cb.data()), out, seq);
        }

        return seq;
    }

    // -------------------------------------------------------------------------
    // Existing 14 Primitives (100% Backwards-Compatible)
    // -------------------------------------------------------------------------

    private int expandBox(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld, boolean hollow,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] a = vec3(op, "from", new int[]{0, 0, 0});
        int[] b = vec3(op, "to", a);
        int r0 = Math.min(a[0], b[0]), r1 = Math.max(a[0], b[0]);
        int u0 = Math.min(a[1], b[1]), u1 = Math.max(a[1], b[1]);
        int f0 = Math.min(a[2], b[2]), f1 = Math.max(a[2], b[2]);

        int thick = Math.max(1, optInt(op, "thickness", 1));
        List<PaletteEntry> mainPal = parsePalette(op, "material", "palette", "stone_bricks", warnings);
        List<PaletteEntry> wallPal = op.has("wallMaterial") ? parseSinglePalette(op.get("wallMaterial"), warnings) : mainPal;
        List<PaletteEntry> floorPal = op.has("floorMaterial") ? parseSinglePalette(op.get("floorMaterial"), warnings) : mainPal;
        List<PaletteEntry> roofPal = op.has("roofMaterial") ? parseSinglePalette(op.get("roofMaterial"), warnings) : mainPal;

        for (int u = u0; u <= u1; u++) {
            for (int r = r0; r <= r1; r++) {
                for (int f = f0; f <= f1; f++) {
                    boolean onFloor = u < u0 + thick;
                    boolean onRoof = u > u1 - thick;
                    boolean onWall = r < r0 + thick || r > r1 - thick || f < f0 + thick || f > f1 - thick;
                    if (hollow && !onFloor && !onRoof && !onWall) {
                        continue;
                    }
                    List<PaletteEntry> pal = onFloor ? floorPal : (onRoof ? roofPal : wallPal);
                    seq = putCoord(world, anchor, isWorld, r, u, f, pal, out, warnings, seq);
                }
            }
        }
        return seq;
    }

    private int expandClear(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, int seq
    ) {
        int[] a = vec3(op, "from", new int[]{0, 0, 0});
        int[] b = vec3(op, "to", a);
        int r0 = Math.min(a[0], b[0]), r1 = Math.max(a[0], b[0]);
        int u0 = Math.min(a[1], b[1]), u1 = Math.max(a[1], b[1]);
        int f0 = Math.min(a[2], b[2]), f1 = Math.max(a[2], b[2]);
        BlockData air = Material.AIR.createBlockData();

        for (int u = u0; u <= u1; u++) {
            for (int r = r0; r <= r1; r++) {
                for (int f = f0; f <= f1; f++) {
                    int wx = isWorld ? r : anchor.worldX(r, f);
                    int wy = isWorld ? u : anchor.worldY(u);
                    int wz = isWorld ? f : anchor.worldZ(r, f);
                    if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) continue;
                    seq = putBlock(world, wx, wy, wz, air, true, out, seq);
                }
            }
        }
        return seq;
    }

    private int expandPillar(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] at = vec3(op, op.has("at") ? "at" : "from", new int[]{0, 0, 0});
        int height = Math.max(1, optInt(op, "height", 6));
        int radius = Math.max(0, optInt(op, "radius", 0));
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "quartz_pillar", warnings);
        List<PaletteEntry> capPal = op.has("capMaterial") ? parseSinglePalette(op.get("capMaterial"), warnings) : pal;

        for (int du = 0; du < height; du++) {
            boolean isCap = (du == 0 || du == height - 1);
            List<PaletteEntry> usePal = isCap ? capPal : pal;
            for (int dr = -radius; dr <= radius; dr++) {
                for (int df = -radius; df <= radius; df++) {
                    if (radius >= 2 && (dr * dr + df * df > radius * radius + 0.5)) continue;
                    seq = putCoord(world, anchor, isWorld, at[0] + dr, at[1] + du, at[2] + df, usePal, out, warnings, seq);
                }
            }
        }
        return seq;
    }

    private int expandCylinder(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] c = vec3(op, "center", new int[]{0, 0, 0});
        double radius = Math.max(1.0, optDouble(op, "radius", 4.0));
        int height = Math.max(1, optInt(op, "height", 4));
        boolean hollow = optBool(op, "hollow", false);
        String axis = optString(op, "axis", "y").toLowerCase(Locale.ROOT);
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "stone_bricks", warnings);

        int ir = (int) Math.ceil(radius);
        double rSqMax = (radius + 0.35) * (radius + 0.35);
        double rSqMin = Math.max(0.0, (radius - 0.9) * (radius - 0.9));

        for (int h = 0; h < height; h++) {
            for (int a = -ir; a <= ir; a++) {
                for (int b = -ir; b <= ir; b++) {
                    double dSq = a * a + b * b;
                    if (dSq > rSqMax) continue;
                    if (hollow && dSq < rSqMin) continue;
                    int r = c[0], u = c[1], f = c[2];
                    if ("r".equals(axis)) {
                        r += h; u += a; f += b;
                    } else if ("f".equals(axis)) {
                        r += a; u += b; f += h;
                    } else {
                        r += a; u += h; f += b;
                    }
                    seq = putCoord(world, anchor, isWorld, r, u, f, pal, out, warnings, seq);
                }
            }
        }
        return seq;
    }

    private int expandDome(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] c = vec3(op, "center", new int[]{0, 0, 0});
        double radius = Math.max(2.0, optDouble(op, "radius", 6.0));
        boolean hollow = optBool(op, "hollow", true);
        String half = optString(op, "half", "upper").toLowerCase(Locale.ROOT);
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "cyan_stained_glass", warnings);

        int ir = (int) Math.ceil(radius);
        int uMin = "upper".equals(half) ? 0 : -ir;
        int uMax = "lower".equals(half) ? 0 : ir;
        double rSqMax = (radius + 0.4) * (radius + 0.4);
        double rSqMin = Math.max(0.0, (radius - 1.05) * (radius - 1.05));

        for (int du = uMin; du <= uMax; du++) {
            for (int dr = -ir; dr <= ir; dr++) {
                for (int df = -ir; df <= ir; df++) {
                    double dSq = dr * dr + du * du + df * df;
                    if (dSq > rSqMax) continue;
                    if (hollow && dSq < rSqMin) continue;
                    seq = putCoord(world, anchor, isWorld, c[0] + dr, c[1] + du, c[2] + df, pal, out, warnings, seq);
                }
            }
        }
        return seq;
    }

    private int expandArch(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] a = vec3(op, "from", new int[]{-4, 0, 0});
        int[] b = vec3(op, "to", new int[]{4, 0, 0});
        int archHeight = Math.max(2, optInt(op, "height", 5));
        int thickness = Math.max(1, optInt(op, "thickness", 1));
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "quartz_bricks", warnings);

        double span = Math.max(1.0, Math.hypot(b[0] - a[0], b[2] - a[2]));
        int steps = (int) Math.ceil(span * 2.5);
        int baseU = Math.min(a[1], b[1]);

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double r = a[0] + (b[0] - a[0]) * t;
            double f = a[2] + (b[2] - a[2]) * t;
            double arcU = baseU + Math.sin(t * Math.PI) * archHeight;
            for (int tu = 0; tu < thickness; tu++) {
                seq = putCoord(
                    world, anchor, isWorld,
                    (int) Math.round(r), (int) Math.round(arcU) + tu, (int) Math.round(f),
                    pal, out, warnings, seq
                );
            }
        }
        return seq;
    }

    private int expandStairsRun(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] a = vec3(op, "from", new int[]{0, 0, 0});
        int[] b = vec3(op, "to", new int[]{0, 4, 4});
        int width = Math.max(1, optInt(op, "width", 3));
        boolean autoHeadroom = optBool(op, "autoHeadroom", true);
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "quartz_stairs", warnings);
        List<PaletteEntry> railPal = op.has("railMaterial") ? parseSinglePalette(op.get("railMaterial"), warnings) : null;

        int dr = b[0] - a[0];
        int du = b[1] - a[1];
        int df = b[2] - a[2];
        int steps = Math.max(1, Math.max(Math.abs(dr), Math.max(Math.abs(du), Math.abs(df))));

        int perpR = Math.abs(df) >= Math.abs(dr) ? 1 : 0;
        int perpF = perpR == 1 ? 0 : 1;
        int halfW = (width - 1) / 2;

        int uphillR = du < 0 ? -dr : dr;
        int uphillF = du < 0 ? -df : df;
        BlockFace autoFacing;
        if (isWorld) {
            autoFacing = Math.abs(uphillF) >= Math.abs(uphillR)
                ? (uphillF >= 0 ? BlockFace.SOUTH : BlockFace.NORTH)
                : (uphillR >= 0 ? BlockFace.EAST : BlockFace.WEST);
        } else {
            autoFacing = anchor.resolveFacing(resolveStepFacing(uphillR, uphillF));
        }

        // Pass 1: Clear 3 blocks of headroom above every step (if autoHeadroom is true)
        if (autoHeadroom) {
            BlockData air = Material.AIR.createBlockData();
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                int cr = (int) Math.round(a[0] + dr * t);
                int cu = (int) Math.round(a[1] + du * t);
                int cf = (int) Math.round(a[2] + df * t);
                for (int w = -halfW; w < -halfW + width; w++) {
                    int rr = cr + perpR * w;
                    int ff = cf + perpF * w;
                    for (int h = 1; h <= 3; h++) {
                        seq = putExactCoord(world, anchor, isWorld, rr, cu + h, ff, air, false, out, seq);
                    }
                }
            }
        }

        // Pass 2: Place stairs & rails (stairs win over headroom air)
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int cr = (int) Math.round(a[0] + dr * t);
            int cu = (int) Math.round(a[1] + du * t);
            int cf = (int) Math.round(a[2] + df * t);

            for (int w = -halfW; w < -halfW + width; w++) {
                int rr = cr + perpR * w;
                int ff = cf + perpF * w;
                seq = putCoordWithAutoStairFacing(world, anchor, isWorld, rr, cu, ff, pal, autoFacing, out, warnings, seq);
            }
            if (railPal != null) {
                seq = putCoord(world, anchor, isWorld, cr + perpR * (-halfW - 1), cu + 1, cf + perpF * (-halfW - 1), railPal, out, warnings, seq);
                seq = putCoord(world, anchor, isWorld, cr + perpR * (-halfW + width), cu + 1, cf + perpF * (-halfW + width), railPal, out, warnings, seq);
            }
        }
        return seq;
    }

    private int expandBridge(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] a = vec3(op, "from", new int[]{0, 0, 0});
        int[] b = vec3(op, "to", new int[]{0, 0, 10});
        int width = Math.max(1, optInt(op, "width", 3));
        double sag = optDouble(op, "sag", 0.0);
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "spruce_planks", warnings);
        List<PaletteEntry> railPal = op.has("railMaterial") ? parseSinglePalette(op.get("railMaterial"), warnings) : null;

        int dr = b[0] - a[0];
        int du = b[1] - a[1];
        int df = b[2] - a[2];
        int steps = Math.max(1, Math.max(Math.abs(dr), Math.abs(df)) * 2);

        int perpR = Math.abs(df) >= Math.abs(dr) ? 1 : 0;
        int perpF = perpR == 1 ? 0 : 1;
        int halfW = (width - 1) / 2;

        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int cr = (int) Math.round(a[0] + dr * t);
            int cf = (int) Math.round(a[2] + df * t);
            double curve = Math.sin(t * Math.PI) * sag;
            int cu = (int) Math.round(a[1] + du * t - curve);

            for (int w = -halfW; w < -halfW + width; w++) {
                seq = putCoord(world, anchor, isWorld, cr + perpR * w, cu, cf + perpF * w, pal, out, warnings, seq);
            }
            if (railPal != null) {
                seq = putCoord(world, anchor, isWorld, cr + perpR * (-halfW - 1), cu + 1, cf + perpF * (-halfW - 1), railPal, out, warnings, seq);
                seq = putCoord(world, anchor, isWorld, cr + perpR * (-halfW + width), cu + 1, cf + perpF * (-halfW + width), railPal, out, warnings, seq);
            }
        }
        return seq;
    }

    private int expandSpiral(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] c = vec3(op, "center", new int[]{0, 0, 0});
        double radius = Math.max(2.0, optDouble(op, "radius", 4.0));
        int height = Math.max(2, optInt(op, "height", 12));
        double turns = optDouble(op, "turns", 1.5);
        int width = Math.max(1, optInt(op, "width", 2));
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "polished_deepslate_slab", warnings);

        int steps = Math.max(16, (int) Math.round(height * 4.5));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double angle = t * turns * Math.PI * 2.0;
            int u = c[1] + (int) Math.round(t * height);
            for (int w = 0; w < width; w++) {
                double rad = Math.max(1.0, radius - w * 0.85);
                int r = c[0] + (int) Math.round(Math.cos(angle) * rad);
                int f = c[2] + (int) Math.round(Math.sin(angle) * rad);
                seq = putCoord(world, anchor, isWorld, r, u, f, pal, out, warnings, seq);
            }
        }
        return seq;
    }

    private int expandRing(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] c = vec3(op, "center", new int[]{0, 0, 0});
        double radius = Math.max(1.0, optDouble(op, "radius", 5.0));
        double inner = optDouble(op, "innerRadius", Math.max(0.0, radius - 1.1));
        int thickness = Math.max(1, optInt(op, "thickness", 1));
        String axis = optString(op, "axis", "y").toLowerCase(Locale.ROOT);
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "sea_lantern", warnings);

        int ir = (int) Math.ceil(radius);
        double rMaxSq = (radius + 0.4) * (radius + 0.4);
        double rMinSq = inner * inner;

        for (int h = 0; h < thickness; h++) {
            for (int a = -ir; a <= ir; a++) {
                for (int b = -ir; b <= ir; b++) {
                    double dSq = a * a + b * b;
                    if (dSq > rMaxSq || dSq < rMinSq) continue;
                    int r = c[0], u = c[1], f = c[2];
                    if ("r".equals(axis)) {
                        r += h; u += a; f += b;
                    } else if ("f".equals(axis)) {
                        r += a; u += b; f += h;
                    } else {
                        r += a; u += h; f += b;
                    }
                    seq = putCoord(world, anchor, isWorld, r, u, f, pal, out, warnings, seq);
                }
            }
        }
        return seq;
    }

    private int expandScatter(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int[] a = vec3(op, "from", new int[]{0, 0, 0});
        int[] b = vec3(op, "to", a);
        double density = Math.max(0.01, Math.min(1.0, optDouble(op, "density", 0.25)));
        boolean onlyAir = optBool(op, "onlyAir", true);
        List<PaletteEntry> pal = parsePalette(op, "material", "palette", "lantern", warnings);

        int r0 = Math.min(a[0], b[0]), r1 = Math.max(a[0], b[0]);
        int u0 = Math.min(a[1], b[1]), u1 = Math.max(a[1], b[1]);
        int f0 = Math.min(a[2], b[2]), f1 = Math.max(a[2], b[2]);

        for (int u = u0; u <= u1; u++) {
            for (int r = r0; r <= r1; r++) {
                for (int f = f0; f <= f1; f++) {
                    int wx = isWorld ? r : anchor.worldX(r, f);
                    int wy = isWorld ? u : anchor.worldY(u);
                    int wz = isWorld ? f : anchor.worldZ(r, f);
                    double hash = ((mixHash(wx, wy, wz) & 0xFFFF) / 65535.0);
                    if (hash > density) continue;
                    if (onlyAir && (!world.getBlockAt(wx, wy, wz).getType().isAir() || out.containsKey(packKey(wx, wy, wz)))) continue;
                    seq = putCoord(world, anchor, isWorld, r, u, f, pal, out, warnings, seq);
                }
            }
        }
        return seq;
    }

    private int expandPattern(JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
                              Map<Long, BlockPlacement> out, List<String> warnings, int seq) {
        String plane = optString(op, "plane", "floor").toLowerCase(Locale.ROOT);
        if (!List.of("floor", "front", "side").contains(plane)) {
            warnings.add("Unknown pattern plane '" + plane + "', defaulting to floor");
            plane = "floor";
        }
        if (!op.has("rows") || !op.get("rows").isJsonArray() || !op.has("key") || !op.get("key").isJsonObject()) {
            warnings.add("pattern op missing rows or key");
            return seq;
        }
        int[] at = vec3(op, "at", new int[]{0, 0, 0});
        JsonArray rows = op.getAsJsonArray("rows");
        Map<Character, List<PaletteEntry>> key = new HashMap<>();
        for (var e : op.getAsJsonObject("key").entrySet()) {
            if (e.getKey().length() != 1) continue;
            key.put(e.getKey().charAt(0), parseSinglePalette(e.getValue(), warnings));
        }
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row).getAsString();
            for (int col = 0; col < line.length(); col++) {
                char symbol = line.charAt(col);
                if (symbol == ' ') continue;
                var palette = key.get(symbol);
                if (palette == null) {
                    // Treat '.' and '_' as skip markers when not explicitly mapped in key
                    if (symbol == '.' || symbol == '_') continue;
                    if (warnings.size() < 12) warnings.add("Skipped unmapped pattern symbol: " + symbol);
                    continue;
                }
                int r = at[0] + (plane.equals("side") ? 0 : col);
                int u = at[1] + (plane.equals("floor") ? 0 : rows.size() - 1 - row);
                int f = at[2] + (plane.equals("floor") ? row : plane.equals("side") ? col : 0);
                seq = putCoord(world, anchor, isWorld, r, u, f, palette, out, warnings, seq);
            }
        }
        return seq;
    }

    private int expandBlocks(
        JsonObject op, BuildAnchor anchor, World world, boolean isWorld,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        List<PaletteEntry> defaultPal = parsePalette(op, "material", "palette", "stone", warnings);
        if (op.has("positions") && op.get("positions").isJsonArray()) {
            for (JsonElement pEl : op.getAsJsonArray("positions")) {
                if (!pEl.isJsonArray()) continue;
                int[] p = parseArr3(pEl.getAsJsonArray());
                seq = putCoord(world, anchor, isWorld, p[0], p[1], p[2], defaultPal, out, warnings, seq);
            }
        }
        if (op.has("items") && op.get("items").isJsonArray()) {
            for (JsonElement iEl : op.getAsJsonArray("items")) {
                if (!iEl.isJsonObject()) continue;
                JsonObject item = iEl.getAsJsonObject();
                int[] p = vec3(item, "pos", new int[]{0, 0, 0});
                List<PaletteEntry> pal = item.has("material")
                    ? parseSinglePalette(item.get("material"), warnings)
                    : defaultPal;
                seq = putCoord(world, anchor, isWorld, p[0], p[1], p[2], pal, out, warnings, seq);
            }
        }
        return seq;
    }

    // -------------------------------------------------------------------------
    // Placement Helpers, BlockData Cache, Fuzzy Material Resolver
    // -------------------------------------------------------------------------

    private String resolveStepFacing(int dr, int df) {
        if (Math.abs(df) >= Math.abs(dr)) {
            return df >= 0 ? "front" : "back";
        }
        return dr >= 0 ? "right" : "left";
    }

    private int putExactCoord(
        World world, BuildAnchor anchor, boolean isWorld, int r, int u, int f,
        BlockData data, boolean applyPhysics, Map<Long, BlockPlacement> out, int seq
    ) {
        int wx = isWorld ? r : anchor.worldX(r, f);
        int wy = isWorld ? u : anchor.worldY(u);
        int wz = isWorld ? f : anchor.worldZ(r, f);
        if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) return seq;
        return putBlock(world, wx, wy, wz, data, applyPhysics, out, seq);
    }

    private int putCoordWithAutoStairFacing(
        World world, BuildAnchor anchor, boolean isWorld, int r, int u, int f,
        List<PaletteEntry> palette, BlockFace autoFacing,
        Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int wx = isWorld ? r : anchor.worldX(r, f);
        int wy = isWorld ? u : anchor.worldY(u);
        int wz = isWorld ? f : anchor.worldZ(r, f);
        if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) return seq;

        String spec = pickWeighted(palette, wx, wy, wz);
        BlockData data = parseBlockData(spec, anchor, warnings);
        if (data == null) return seq;
        if (data instanceof Stairs && !spec.toLowerCase(Locale.ROOT).contains("facing=")) {
            Stairs cloned = (Stairs) data.clone();
            cloned.setFacing(autoFacing);
            data = cloned;
        }
        return putBlock(world, wx, wy, wz, data, shouldApplyPhysics(data), out, seq);
    }

    private int putCoord(
        World world, BuildAnchor anchor, boolean isWorld, int r, int u, int f,
        List<PaletteEntry> palette, Map<Long, BlockPlacement> out, List<String> warnings, int seq
    ) {
        int wx = isWorld ? r : anchor.worldX(r, f);
        int wy = isWorld ? u : anchor.worldY(u);
        int wz = isWorld ? f : anchor.worldZ(r, f);
        if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) return seq;

        String spec = pickWeighted(palette, wx, wy, wz);
        BlockData data = parseBlockData(spec, anchor, warnings);
        if (data == null) return seq;
        return putBlock(world, wx, wy, wz, data, shouldApplyPhysics(data), out, seq);
    }

    private static boolean shouldApplyPhysics(BlockData data) {
        if (isFluid(data)) return false;
        if (data instanceof Door || data instanceof Bed) return false;
        if (isTwoBlockTallFlora(data.getMaterial())) return false;
        return true;
    }

    private static long packKey(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << 38) | (((long) (z & 0x3FFFFFF)) << 12) | (y & 0xFFFL);
    }

    private int putBlock(
        World world, int x, int y, int z, BlockData data, boolean applyPhysics,
        Map<Long, BlockPlacement> out, int seq
    ) {
        long key = packKey(x, y, z);
        out.put(key, new BlockPlacement(world, x, y, z, data, applyPhysics, seq));
        return seq + 1;
    }

    private BlockData parseBlockData(String rawSpec, BuildAnchor anchor, List<String> warnings) {
        if (rawSpec == null || rawSpec.isBlank()) return null;
        String spec = rawSpec.trim().toLowerCase(Locale.ROOT);
        if (spec.startsWith("minecraft:")) {
            spec = spec.substring("minecraft:".length());
        }
        String cacheKey = anchor.cardinal().name() + ":" + spec;
        BlockData cached = blockDataCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Fuzzy material resolver (normalizes common AI mistakes cleanly without warnings)
        spec = resolveFuzzyMaterial(spec);

        // Replace relative facing tokens (facing=front|back|left|right|up|down)
        Matcher m = FACING_TOKEN.matcher(spec);
        if (m.find()) {
            StringBuilder sb = new StringBuilder();
            do {
                BlockFace resolved = anchor.resolveFacing(m.group(2));
                m.appendReplacement(sb, m.group(1) + resolved.name().toLowerCase(Locale.ROOT));
            } while (m.find());
            m.appendTail(sb);
            spec = sb.toString();
        }
        try {
            BlockData created = Bukkit.createBlockData("minecraft:" + spec);
            if (blockDataCache.size() >= MAX_BLOCKDATA_CACHE) {
                blockDataCache.clear();
            }
            blockDataCache.put(cacheKey, created);
            return created;
        } catch (IllegalArgumentException ex) {
            if (warnings.size() < 12) {
                warnings.add("Invalid block spec '" + rawSpec + "': " + ex.getMessage());
            }
            return null;
        }
    }

    private static String resolveFuzzyMaterial(String spec) {
        int bracket = spec.indexOf('[');
        String base = bracket >= 0 ? spec.substring(0, bracket) : spec;
        String suffix = bracket >= 0 ? spec.substring(bracket) : "";

        if ("water_cauldron".equals(base) && !suffix.contains("level=")) {
            return "water_cauldron[level=3]";
        }
        String mapped = MATERIAL_ALIASES.get(base);
        if (mapped != null) {
            if (mapped.contains("[")) {
                return mapped;
            }
            return mapped + suffix;
        }
        return spec;
    }

    private static Map<String, String> buildMaterialAliases() {
        Map<String, String> m = new HashMap<>();
        m.put("amethyst_lamp", "pearlescent_froglight");
        m.put("crystal_lamp", "pearlescent_froglight");
        m.put("glow_lamp", "pearlescent_froglight");
        m.put("fairy_light", "pearlescent_froglight");
        m.put("magic_lamp", "pearlescent_froglight");
        m.put("purple_lamp", "pearlescent_froglight");
        m.put("pink_lamp", "pearlescent_froglight");
        m.put("warm_lamp", "ochre_froglight");
        m.put("gold_lamp", "ochre_froglight");
        m.put("sun_lamp", "ochre_froglight");
        m.put("green_lamp", "verdant_froglight");
        m.put("emerald_lamp", "verdant_froglight");
        m.put("jade_lamp", "verdant_froglight");
        m.put("quartz_wall", "diorite_wall");
        m.put("smooth_quartz_wall", "diorite_wall");
        m.put("white_wall", "diorite_wall");
        m.put("smooth_stone_wall", "andesite_wall");
        m.put("stone_wall", "cobblestone_wall");
        m.put("bricks_wall", "brick_wall");
        m.put("gold_stairs", "cut_copper_stairs");
        m.put("gold_slab", "cut_copper_slab");
        m.put("wood_planks", "oak_planks");
        m.put("wooden_planks", "oak_planks");
        m.put("wood_stairs", "oak_stairs");
        m.put("wooden_stairs", "oak_stairs");
        m.put("wood_slab", "oak_slab");
        m.put("wooden_slab", "oak_slab");
        m.put("wood_fence", "oak_fence");
        m.put("wooden_fence", "oak_fence");
        m.put("wood_door", "oak_door");
        m.put("wooden_door", "oak_door");
        m.put("wood_trapdoor", "oak_trapdoor");
        m.put("wooden_trapdoor", "oak_trapdoor");
        m.put("wood_log", "oak_log");
        m.put("log", "oak_log");
        m.put("oak_wood_planks", "oak_planks");
        m.put("spruce_wood_planks", "spruce_planks");
        m.put("birch_wood_planks", "birch_planks");
        m.put("dark_oak_wood_planks", "dark_oak_planks");
        m.put("cherry_wood_planks", "cherry_planks");
        m.put("stained_glass", "white_stained_glass");
        m.put("stained_glass_pane", "white_stained_glass_pane");
        m.put("glass_wall", "glass_pane");
        m.put("concrete", "white_concrete");
        m.put("wool", "white_wool");
        m.put("carpet", "white_carpet");
        m.put("terracotta", "white_terracotta");
        m.put("bed", "red_bed");
        m.put("marble", "smooth_quartz");
        m.put("marble_block", "smooth_quartz");
        m.put("marble_pillar", "quartz_pillar");
        m.put("marble_stairs", "smooth_quartz_stairs");
        m.put("marble_slab", "smooth_quartz_slab");
        m.put("roof_tile", "deepslate_tile_stairs");
        m.put("roof_tiles", "deepslate_tile_stairs");
        m.put("brick", "bricks");
        m.put("stone_brick", "stone_bricks");
        m.put("deepslate_brick", "deepslate_bricks");
        m.put("mud_brick", "mud_bricks");
        m.put("quartz_brick", "quartz_bricks");
        m.put("nether_brick", "nether_bricks");
        m.put("prismarine_brick", "prismarine_bricks");
        m.put("grass", "short_grass");
        m.put("lit_campfire", "campfire[lit=true]");
        m.put("lit_lantern", "lantern");
        return m;
    }

    private List<PaletteEntry> parsePalette(
        JsonObject op, String singleKey, String paletteKey, String fallback, List<String> warnings
    ) {
        if (op.has(paletteKey)) {
            return parseSinglePalette(op.get(paletteKey), warnings);
        }
        if (op.has(singleKey)) {
            return parseSinglePalette(op.get(singleKey), warnings);
        }
        return List.of(new PaletteEntry(fallback, 100));
    }

    private List<PaletteEntry> parseSinglePalette(JsonElement el, List<String> warnings) {
        List<PaletteEntry> list = new ArrayList<>();
        if (el == null || el.isJsonNull()) {
            list.add(new PaletteEntry("stone", 100));
            return list;
        }
        if (el.isJsonPrimitive()) {
            String s = el.getAsString().trim();
            if (!s.contains("[") && s.contains(",")) {
                for (String part : s.split(",")) {
                    addPaletteItem(part, list);
                }
            } else {
                addPaletteItem(s, list);
            }
        } else if (el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    addPaletteItem(item.getAsString(), list);
                } else if (item.isJsonObject()) {
                    JsonObject o = item.getAsJsonObject();
                    String mat = optString(o, "material", "stone");
                    int w = Math.max(1, optInt(o, "weight", 10));
                    list.add(new PaletteEntry(mat, w));
                }
            }
        }
        if (list.isEmpty()) {
            warnings.add("Empty palette fallback to stone");
            list.add(new PaletteEntry("stone", 100));
        }
        return list;
    }

    private void addPaletteItem(String token, List<PaletteEntry> list) {
        String t = token.trim();
        if (t.isEmpty()) return;
        int colon = t.lastIndexOf(':');
        if (colon > 0 && !t.substring(colon + 1).contains("]")) {
            String suffix = t.substring(colon + 1).trim();
            String prefix = t.substring(0, colon).trim();
            if (!"minecraft".equalsIgnoreCase(prefix)) {
                try {
                    int w = Math.max(1, Integer.parseInt(suffix));
                    list.add(new PaletteEntry(prefix, w));
                    return;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        list.add(new PaletteEntry(t, 10));
    }

    private String pickWeighted(List<PaletteEntry> palette, int x, int y, int z) {
        if (palette.size() == 1) return palette.get(0).rawSpec();
        int total = 0;
        for (PaletteEntry e : palette) total += e.weight();
        int h = Math.abs(mixHash(x, y, z)) % Math.max(1, total);
        int acc = 0;
        for (PaletteEntry e : palette) {
            acc += e.weight();
            if (h < acc) return e.rawSpec();
        }
        return palette.get(0).rawSpec();
    }

    private static int mixHash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 2147483647;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    private void sortPlacements(List<BlockPlacement> list, String sortMode, BuildAnchor anchor) {
        if (list.size() <= 1) return;

        Comparator<BlockPlacement> tierOrder = Comparator.comparingInt(bp -> placementTier(bp.data()));
        if ("none".equals(sortMode)) {
            list.sort(tierOrder);
            return;
        }

        double sumX = 0, sumY = 0, sumZ = 0;
        for (BlockPlacement bp : list) {
            sumX += bp.x();
            sumY += bp.y();
            sumZ += bp.z();
        }
        final double cx = sumX / list.size();
        final double cy = sumY / list.size();
        final double cz = sumZ / list.size();

        switch (sortMode) {
            case "center_out" -> list.sort(tierOrder.thenComparingDouble(
                bp -> sq(bp.x() - cx) + sq(bp.y() - cy) + sq(bp.z() - cz)
            ));
            case "spiral" -> list.sort(tierOrder
                .thenComparingInt(BlockPlacement::y)
                .thenComparingDouble(bp -> Math.atan2(bp.z() - cz, bp.x() - cx))
                .thenComparingDouble(bp -> sq(bp.x() - cx) + sq(bp.z() - cz))
            );
            case "front_to_back" -> {
                final int fx = anchor.cardinal().forwardX;
                final int fz = anchor.cardinal().forwardZ;
                list.sort(tierOrder
                    .thenComparingInt((BlockPlacement bp) -> bp.x() * fx + bp.z() * fz)
                    .thenComparingInt(BlockPlacement::y)
                );
            }
            default -> // bottom_up
                list.sort(tierOrder
                    .thenComparingInt(BlockPlacement::y)
                    .thenComparingDouble(bp -> sq(bp.x() - cx) + sq(bp.z() - cz))
                );
        }
    }

    private static int placementTier(BlockData data) {
        if (isFluid(data)) return 2;
        if (isFragileAttachment(data.getMaterial())) return 1;
        return 0;
    }

    private static boolean isFragileAttachment(Material m) {
        String n = m.name();
        if ((n.endsWith("_DOOR") && !n.endsWith("_TRAPDOOR"))
            || n.endsWith("_BUTTON") || n.endsWith("_PRESSURE_PLATE")
            || n.endsWith("_SIGN") || n.endsWith("_BANNER") || n.endsWith("_CARPET")
            || n.endsWith("_TORCH") || n.endsWith("_BED")
            || n.endsWith("_CANDLE") || n.endsWith("_RAIL") || n.endsWith("_SAPLING")
            || n.endsWith("_AMETHYST_BUD")) {
            return true;
        }
        return switch (m) {
            case TORCH, WALL_TORCH, SOUL_TORCH, SOUL_WALL_TORCH,
                 REDSTONE_TORCH, REDSTONE_WALL_TORCH, REDSTONE_WIRE,
                 REPEATER, COMPARATOR, LEVER, LANTERN, SOUL_LANTERN,
                 TRIPWIRE, TRIPWIRE_HOOK, LADDER, VINE, BELL,
                 SCAFFOLDING, SNOW, AMETHYST_CLUSTER, FLOWER_POT,
                 DANDELION, POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET,
                 RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP,
                 OXEYE_DAISY, CORNFLOWER, LILY_OF_THE_VALLEY, TORCHFLOWER,
                 ROSE_BUSH, LILAC, PEONY, SUNFLOWER, PITCHER_PLANT, BAMBOO,
                 FERN, LARGE_FERN, SHORT_GRASS, TALL_GRASS, SWEET_BERRY_BUSH,
                 SUGAR_CANE, DEAD_BUSH, GLOW_LICHEN, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    private static boolean isFluid(BlockData data) {
        Material m = data.getMaterial();
        if (m == Material.WATER || m == Material.LAVA) return true;
        return data instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }

    private static String anchorScopeKey(BuildAnchor anchor) {
        return anchor.worldName() + ":" + anchor.originX() + "," + anchor.originY() + "," + anchor.originZ() + ":" + anchor.cardinal().name();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static int[] vec3(JsonObject obj, String key, int[] fallback) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) return fallback;
        return parseArr3(obj.getAsJsonArray(key));
    }

    private static int[] parseArr3(JsonArray arr) {
        int x = arr.size() > 0 ? arr.get(0).getAsInt() : 0;
        int y = arr.size() > 1 ? arr.get(1).getAsInt() : 0;
        int z = arr.size() > 2 ? arr.get(2).getAsInt() : 0;
        return new int[]{x, y, z};
    }

    private static String optString(JsonObject o, String k, String def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : def;
    }

    private static int optInt(JsonObject o, String k, int def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : def;
    }

    private static double optDouble(JsonObject o, String k, double def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : def;
    }

    private static boolean optBool(JsonObject o, String k, boolean def) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsBoolean() : def;
    }
}
