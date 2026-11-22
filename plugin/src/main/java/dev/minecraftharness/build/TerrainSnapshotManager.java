package dev.minecraftharness.build;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Snapshots original terrain blocks and spawned display/helper entities per build session
 * so /mh undo and /mh reset restore the world cleanly.
 */
public final class TerrainSnapshotManager {

    public record BlockKey(String worldName, int x, int y, int z) {}

    public record SavedBlock(BlockData blockData, String command) {}

    public static final class SessionSnapshot {
        public final String id;
        public final String label;
        public final Map<BlockKey, SavedBlock> originalBlocks = new LinkedHashMap<>();
        public final List<UUID> spawnedEntities = new ArrayList<>();
        public int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        public int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        public SessionSnapshot(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public void expandBounds(int x, int y, int z) {
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        public Location centerLocation(World fallback) {
            if (minX == Integer.MAX_VALUE) return fallback.getSpawnLocation();
            return new Location(
                fallback,
                (minX + maxX) * 0.5 + 0.5,
                (minY + maxY) * 0.5 + 0.5,
                (minZ + maxZ) * 0.5 + 0.5
            );
        }
    }

    private final Deque<SessionSnapshot> sessions = new ArrayDeque<>();
    private SessionSnapshot activeSession;

    public synchronized SessionSnapshot beginOrGetSession(String label) {
        if (activeSession == null) {
            activeSession = new SessionSnapshot(
                "build-" + System.currentTimeMillis(),
                label != null ? label : "AI Build"
            );
            sessions.push(activeSession);
            while (sessions.size() > 16) {
                sessions.removeLast();
            }
        }
        return activeSession;
    }

    public synchronized SessionSnapshot closeActiveSession() {
        SessionSnapshot closed = activeSession;
        activeSession = null;
        return closed;
    }

    public synchronized SessionSnapshot getActiveSession() {
        return activeSession;
    }

    public void recordBeforeWrite(World world, int x, int y, int z) {
        SessionSnapshot session = beginOrGetSession("AI Build");
        BlockKey key = new BlockKey(world.getName(), x, y, z);
        if (!session.originalBlocks.containsKey(key)) {
            Block block = world.getBlockAt(x, y, z);
            BlockData cloned = block.getBlockData().clone();
            Material mat = cloned.getMaterial();
            String cmd = null;
            if (mat == Material.COMMAND_BLOCK
                || mat == Material.REPEATING_COMMAND_BLOCK
                || mat == Material.CHAIN_COMMAND_BLOCK) {
                if (block.getState() instanceof CommandBlock cb) {
                    cmd = cb.getCommand();
                }
            }
            session.originalBlocks.put(key, new SavedBlock(cloned, cmd));
        }
        session.expandBounds(x, y, z);
    }

    public void recordSpawnedEntity(UUID uuid) {
        SessionSnapshot session = beginOrGetSession("AI Build");
        session.spawnedEntities.add(uuid);
    }

    public record RestoreSummary(int restoredBlocks, int removedEntities, Location center) {}

    public RestoreSummary undoLast() {
        SessionSnapshot session;
        synchronized (this) {
            if (sessions.isEmpty()) {
                return new RestoreSummary(0, 0, null);
            }
            session = sessions.pop();
            if (session == activeSession) {
                activeSession = null;
            }
        }
        return restoreSession(session);
    }

    public RestoreSummary resetAll() {
        List<SessionSnapshot> all = new ArrayList<>();
        synchronized (this) {
            while (!sessions.isEmpty()) {
                all.add(sessions.pop());
            }
            activeSession = null;
        }
        int totalBlocks = 0;
        int totalEntities = 0;
        Location lastCenter = null;
        for (SessionSnapshot s : all) {
            RestoreSummary sum = restoreSession(s);
            totalBlocks += sum.restoredBlocks();
            totalEntities += sum.removedEntities();
            if (sum.center() != null) lastCenter = sum.center();
        }
        return new RestoreSummary(totalBlocks, totalEntities, lastCenter);
    }

    private RestoreSummary restoreSession(SessionSnapshot session) {
        int restored = 0;
        World lastWorld = Bukkit.getWorlds().get(0);

        List<Map.Entry<BlockKey, SavedBlock>> entries = new ArrayList<>(session.originalBlocks.entrySet());
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<BlockKey, SavedBlock> entry = entries.get(i);
            BlockKey key = entry.getKey();
            SavedBlock saved = entry.getValue();
            World w = Bukkit.getWorld(key.worldName());
            if (w == null) continue;
            lastWorld = w;
            Block block = w.getBlockAt(key.x(), key.y(), key.z());
            block.setBlockData(saved.blockData(), false);
            if (saved.command() != null && block.getState() instanceof CommandBlock cb) {
                cb.setCommand(saved.command());
                cb.update(true, false);
            }
            restored++;
        }

        int removedEntities = 0;
        for (UUID uuid : session.spawnedEntities) {
            Entity e = Bukkit.getEntity(uuid);
            if (e != null && !e.isDead()) {
                e.remove();
                removedEntities++;
            }
        }

        // Also sweep any stray air-floating items dropped during undo
        Location center = session.centerLocation(lastWorld);
        for (Entity nearby : lastWorld.getNearbyEntities(center, 48, 48, 48)) {
            if (nearby instanceof org.bukkit.entity.Item) {
                nearby.remove();
            }
        }

        return new RestoreSummary(restored, removedEntities, center);
    }
}
