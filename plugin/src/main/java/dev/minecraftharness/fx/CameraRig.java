package dev.minecraftharness.fx;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Provides smooth cinematic camera rigs (/mh cam orbit|dolly|off) and Film Mode (/mh film on|off)
 * for recording 20-second X demo clips cleanly.
 */
public final class CameraRig {

    public enum Mode { ORBIT, DOLLY }

    private static final class RigSession {
        final UUID playerId;
        final GameMode originalGameMode;
        final Location originalLocation;
        final Location focusCenter;
        final Mode mode;
        double radius;
        double heightOffset;
        double speedRadPerTick;
        double angle;
        int ticks;

        RigSession(
            UUID playerId, GameMode originalGameMode, Location originalLocation,
            Location focusCenter, Mode mode, double radius, double heightOffset, double speedRadPerTick
        ) {
            this.playerId = playerId;
            this.originalGameMode = originalGameMode;
            this.originalLocation = originalLocation;
            this.focusCenter = focusCenter;
            this.mode = mode;
            this.radius = radius;
            this.heightOffset = heightOffset;
            this.speedRadPerTick = speedRadPerTick;
            // Start angle from player's current relative offset to center so camera doesn't snap awkwardly
            double dx = originalLocation.getX() - focusCenter.getX();
            double dz = originalLocation.getZ() - focusCenter.getZ();
            this.angle = Math.atan2(dz, dx);
        }
    }

    private record SavedPhotoPose(GameMode gameMode, Location location) {}

    private final Map<UUID, RigSession> sessions = new HashMap<>();
    private final Map<UUID, SavedPhotoPose> savedPhotoPoses = new HashMap<>();
    private boolean filmMode = false;

    public boolean isFilmMode() {
        return filmMode;
    }

    public void setFilmMode(boolean enabled) {
        this.filmMode = enabled;
        for (World w : Bukkit.getWorlds()) {
            w.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, !enabled);
            w.setGameRule(org.bukkit.GameRule.COMMAND_BLOCK_OUTPUT, !enabled);
        }
    }

    public void startOrbit(Player player, Location center, double radius, double height, double speedDegPerSec) {
        stop(player, false);
        double radPerTick = Math.toRadians(speedDegPerSec) / 20.0;
        RigSession session = new RigSession(
            player.getUniqueId(),
            player.getGameMode(),
            player.getLocation().clone(),
            center.clone(),
            Mode.ORBIT,
            Math.max(4.0, Math.min(48.0, radius)),
            height,
            radPerTick
        );
        sessions.put(player.getUniqueId(), session);
        player.setGameMode(GameMode.SPECTATOR);
    }

    public void startDolly(Player player, Location center, double startDist, double height) {
        stop(player, false);
        RigSession session = new RigSession(
            player.getUniqueId(),
            player.getGameMode(),
            player.getLocation().clone(),
            center.clone(),
            Mode.DOLLY,
            Math.max(5.0, Math.min(48.0, startDist)),
            height,
            0.03
        );
        sessions.put(player.getUniqueId(), session);
        player.setGameMode(GameMode.SPECTATOR);
    }

    public boolean stop(Player player, boolean restorePos) {
        RigSession s = sessions.remove(player.getUniqueId());
        if (s == null) return false;
        player.setGameMode(s.originalGameMode);
        if (restorePos) {
            player.teleport(s.originalLocation);
        }
        return true;
    }

    public void stopAll() {
        for (RigSession s : sessions.values()) {
            Player p = Bukkit.getPlayer(s.playerId);
            if (p != null && p.isOnline()) {
                p.setGameMode(s.originalGameMode);
                p.teleport(s.originalLocation);
            }
        }
        sessions.clear();
    }

    public boolean isInCameraMode(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    public void snapForPhoto(Player player, Location camLoc, Location lookTarget) {
        if (player == null || !player.isOnline() || camLoc == null || lookTarget == null) return;
        savedPhotoPoses.putIfAbsent(
            player.getUniqueId(),
            new SavedPhotoPose(player.getGameMode(), player.getLocation().clone())
        );

        Location targetLoc = camLoc.clone();
        double dx = lookTarget.getX() - targetLoc.getX();
        double dy = lookTarget.getY() - targetLoc.getY();
        double dz = lookTarget.getZ() - targetLoc.getZ();
        double horiz = Math.hypot(dx, dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.001, horiz)));
        targetLoc.setYaw(yaw);
        targetLoc.setPitch(pitch);

        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.setGameMode(GameMode.SPECTATOR);
        }
        player.teleport(targetLoc);
    }

    public boolean restoreAfterPhoto(Player player) {
        if (player == null || !player.isOnline()) return false;
        SavedPhotoPose pose = savedPhotoPoses.remove(player.getUniqueId());
        if (pose == null) return false;
        player.setGameMode(pose.gameMode());
        player.teleport(pose.location());
        return true;
    }

    public void tick() {
        if (sessions.isEmpty()) return;
        Iterator<Map.Entry<UUID, RigSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            RigSession s = it.next().getValue();
            Player p = Bukkit.getPlayer(s.playerId);
            if (p == null || !p.isOnline()) {
                it.remove();
                continue;
            }
            if (p.isSneaking()) {
                p.setGameMode(s.originalGameMode);
                it.remove();
                continue;
            }

            s.ticks++;
            Location camLoc;
            if (s.mode == Mode.ORBIT) {
                s.angle += s.speedRadPerTick;
                double cx = s.focusCenter.getX() + Math.cos(s.angle) * s.radius;
                double cz = s.focusCenter.getZ() + Math.sin(s.angle) * s.radius;
                double cy = s.focusCenter.getY() + s.heightOffset + Math.sin(s.ticks * 0.04) * 0.8;
                camLoc = new Location(s.focusCenter.getWorld(), cx, cy, cz);
            } else {
                // Gentle push-in dolly shot
                double currentDist = Math.max(5.0, s.radius - s.ticks * 0.06);
                double cx = s.focusCenter.getX() + Math.cos(s.angle) * currentDist;
                double cz = s.focusCenter.getZ() + Math.sin(s.angle) * currentDist;
                double cy = s.focusCenter.getY() + s.heightOffset;
                camLoc = new Location(s.focusCenter.getWorld(), cx, cy, cz);
            }

            // Aim camera at focusCenter
            double dx = s.focusCenter.getX() - camLoc.getX();
            double dy = (s.focusCenter.getY() + 2.0) - camLoc.getY();
            double dz = s.focusCenter.getZ() - camLoc.getZ();
            double horiz = Math.hypot(dx, dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(0.001, horiz)));
            camLoc.setYaw(yaw);
            camLoc.setPitch(pitch);

            p.teleport(camLoc);
        }
    }
}
