package dev.minecraftharness.fx;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Renders 20-second viral-ready construction VFX/SFX:
 * - T=0.0s target lock-on scan perimeter
 * - Glowing constructor drones firing particle lasers at active block placements
 * - FPS-capped BlockDisplay pop-in animations (0.25 -> 1.0 scale over 4 ticks)
 * - Pentatonic velocity-mapped ASMR chimes
 * - Finish shockwave and undo dissolve effects
 */
public final class VfxEngine {

    private record PopInEntry(UUID entityId, long removeAtTick) {}

    private record Drone(UUID entityId, Location pos, double phaseOffset) {}

    private record IlluminationZone(
        UUID worldId,
        String preset,
        int minX,
        int minY,
        int minZ,
        int maxX,
        int maxY,
        int maxZ
    ) {}

    private static final float[] PENTATONIC_PITCHES = {
        0.707f, 0.794f, 0.891f, 1.059f, 1.189f,
        1.414f, 1.587f, 1.782f, 2.0f
    };

    private static final int MAX_CONCURRENT_POPINS = 48;
    private static final int MAX_ILLUMINATION_ZONES = 8;

    private final List<Drone> drones = new ArrayList<>();
    private final Deque<PopInEntry> activePopIns = new ArrayDeque<>();
    private final List<IlluminationZone> illuminationZones = new ArrayList<>();

    private Location lockOnCenter;
    private long lockOnUntilTick = -1;
    private long activeUntilTick = -1;
    private Location frontierTarget;
    private long tickCounter = 0;
    private int noteStep = 0;
    private long lastSoundTick = -10;
    private boolean fxEnabled = true;
    private boolean soundEnabled = true;

    public void setFxEnabled(boolean enabled) {
        this.fxEnabled = enabled;
        if (!enabled) {
            clearDrones();
            clearPopIns();
        }
    }

    public boolean isFxEnabled() {
        return fxEnabled;
    }

    public void setSoundEnabled(boolean enabled) {
        this.soundEnabled = enabled;
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public void startLockOn(Location center, Player triggerPlayer) {
        if (center == null || center.getWorld() == null) return;
        this.lockOnCenter = center.clone();
        this.frontierTarget = center.clone().add(0, 2.5, 0);
        this.lockOnUntilTick = tickCounter + 3600; // Keep scan ring pulsing smoothly until Wave 1 arrives (cleared on notifyPlacement/finish/undo)
        this.activeUntilTick = tickCounter + 3600;

        if (fxEnabled) {
            ensureDrones(center.getWorld(), center);
            World w = center.getWorld();
            w.spawnParticle(
                Particle.DUST,
                center.clone().add(0, 0.4, 0),
                24, 2.2, 0.2, 2.2, 0,
                new Particle.DustOptions(Color.fromRGB(0, 235, 255), 1.4f)
            );
        }
        if (soundEnabled && triggerPlayer != null) {
            triggerPlayer.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.45f, 1.6f);
        }
    }

    public void notifyPlacement(Location blockLoc, BlockData data, boolean sampleForAnim) {
        if (!fxEnabled || blockLoc == null || blockLoc.getWorld() == null) return;
        this.frontierTarget = blockLoc.clone().add(0.5, 0.5, 0.5);
        this.activeUntilTick = tickCounter + 12;
        this.lockOnUntilTick = -1; // Once building starts, drones switch from lock-on to laser build mode

        ensureDrones(blockLoc.getWorld(), this.frontierTarget);

        if (sampleForAnim && data.getMaterial().isSolid() && activePopIns.size() < MAX_CONCURRENT_POPINS) {
            spawnPopIn(blockLoc, data);
        }

        // Fire laser from the nearest drone on sampled placements
        if (sampleForAnim && !drones.isEmpty()) {
            Drone d = drones.get((int) (Math.abs(blockLoc.getBlockX() + blockLoc.getBlockZ() + tickCounter) % drones.size()));
            drawLaser(d.pos, this.frontierTarget, Color.fromRGB(0, 235, 255), 5);
        }
    }

    public void playBuildStepSound(Location at, int placedCount) {
        if (!soundEnabled || at == null || at.getWorld() == null || placedCount <= 0) return;
        if (tickCounter - lastSoundTick < 2) return;
        lastSoundTick = tickCounter;

        float pitch = PENTATONIC_PITCHES[noteStep % PENTATONIC_PITCHES.length];
        noteStep++;
        at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, pitch);
        if (placedCount >= 18) {
            at.getWorld().playSound(at, Sound.BLOCK_NOTE_BLOCK_PLING, 0.22f, Math.min(2.0f, pitch * 1.0f));
        }
    }

    public void playCommandWireFx(Location start, Location end) {
        if (start == null || start.getWorld() == null) return;
        World w = start.getWorld();
        if (fxEnabled) {
            if (end != null && end.getWorld() == w) {
                drawLaser(start.clone().add(0.5, 0.6, 0.5), end.clone().add(0.5, 0.6, 0.5), Color.fromRGB(255, 200, 40), 10);
            }
            w.spawnParticle(Particle.ELECTRIC_SPARK, start.clone().add(0.5, 0.7, 0.5), 18, 0.6, 0.5, 0.6, 0.05);
        }
        if (soundEnabled) {
            w.playSound(start, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.65f, 1.4f);
        }
    }

    public void playFinishShockwave(Location center) {
        lockOnUntilTick = -1;
        activeUntilTick = -1;
        clearDrones();
        if (center == null || center.getWorld() == null) return;
        World w = center.getWorld();

        if (fxEnabled) {
            for (int i = 0; i < 40; i++) {
                double ang = (Math.PI * 2.0 * i) / 40.0;
                Location p = center.clone().add(Math.cos(ang) * 4.5, 0.6, Math.sin(ang) * 4.5);
                w.spawnParticle(Particle.END_ROD, p, 1, 0.1, 0.1, 0.1, 0.05);
                w.spawnParticle(Particle.DUST, p, 2, 0.2, 0.2, 0.2, 0,
                    new Particle.DustOptions(Color.fromRGB(255, 210, 60), 1.5f));
            }
        }
        if (soundEnabled) {
            w.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.35f);
            w.playSound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.8f, 1.6f);
        }
    }

    public void playUndoDissolve(Location center) {
        lockOnUntilTick = -1;
        activeUntilTick = -1;
        clearDrones();
        clearPopIns();
        clearIlluminations();
        if (center == null || center.getWorld() == null) return;
        World w = center.getWorld();
        if (fxEnabled) {
            w.spawnParticle(Particle.CLOUD, center, 28, 3.0, 1.5, 3.0, 0.04);
        }
        if (soundEnabled) {
            w.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.2f);
        }
    }

    public void tick() {
        tickCounter++;

        // Expire finished BlockDisplay pop-in animations
        while (!activePopIns.isEmpty() && activePopIns.peek().removeAtTick <= tickCounter) {
            PopInEntry entry = activePopIns.poll();
            Entity e = BukkitEntityLookup.get(entry.entityId);
            if (e != null && !e.isDead()) {
                e.remove();
            }
        }

        // Render lock-on rotating ring before Wave 1 arrives
        if (fxEnabled && lockOnCenter != null && tickCounter <= lockOnUntilTick) {
            World w = lockOnCenter.getWorld();
            if (w != null && tickCounter % 2 == 0) {
                double radius = 4.2 + Math.sin(tickCounter * 0.25) * 0.5;
                double baseAngle = tickCounter * 0.16;
                for (int i = 0; i < 12; i++) {
                    double a = baseAngle + (Math.PI * 2.0 * i) / 12.0;
                    Location ringPt = lockOnCenter.clone().add(Math.cos(a) * radius, 0.35, Math.sin(a) * radius);
                    w.spawnParticle(
                        Particle.DUST, ringPt, 1, 0, 0, 0, 0,
                        new Particle.DustOptions(i % 3 == 0 ? Color.fromRGB(255, 210, 60) : Color.fromRGB(0, 235, 255), 1.2f)
                    );
                }
            }
        }

        // Update hovering constructor drones
        if (!drones.isEmpty()) {
            if (!fxEnabled || tickCounter > activeUntilTick || frontierTarget == null) {
                clearDrones();
            } else {
                for (Drone d : drones) {
                    double angle = tickCounter * 0.14 + d.phaseOffset;
                    double desiredX = frontierTarget.getX() + Math.cos(angle) * 5.2;
                    double desiredY = frontierTarget.getY() + 4.5 + Math.sin(tickCounter * 0.2 + d.phaseOffset) * 0.8;
                    double desiredZ = frontierTarget.getZ() + Math.sin(angle) * 5.2;

                    d.pos.setX(d.pos.getX() + (desiredX - d.pos.getX()) * 0.22);
                    d.pos.setY(d.pos.getY() + (desiredY - d.pos.getY()) * 0.22);
                    d.pos.setZ(d.pos.getZ() + (desiredZ - d.pos.getZ()) * 0.22);

                    Entity e = BukkitEntityLookup.get(d.entityId);
                    if (e != null && !e.isDead()) {
                        Location teleportLoc = d.pos.clone();
                        teleportLoc.setYaw((float) ((tickCounter * 12 + d.phaseOffset * 60) % 360));
                        e.teleport(teleportLoc);
                    }
                }
            }
        }

        // Tick persistent ambient illumination zones (every 4 ticks)
        if (fxEnabled && !illuminationZones.isEmpty() && (tickCounter % 4 == 0)) {
            tickIlluminations();
        }
    }

    private void tickIlluminations() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (IlluminationZone z : illuminationZones) {
            World w = org.bukkit.Bukkit.getWorld(z.worldId());
            if (w == null) continue;
            int spanX = Math.max(1, z.maxX() - z.minX());
            int spanY = Math.max(1, z.maxY() - z.minY());
            int spanZ = Math.max(1, z.maxZ() - z.minZ());

            // Emit 4 gentle particles around eaves & perimeter per tick cycle
            for (int i = 0; i < 4; i++) {
                double px, py, pz;
                int edge = rng.nextInt(4);
                boolean eave = (i < 2);
                if (edge == 0) {
                    px = z.minX() + rng.nextDouble() * spanX;
                    pz = z.minZ() - 0.3;
                } else if (edge == 1) {
                    px = z.minX() + rng.nextDouble() * spanX;
                    pz = z.maxZ() + 1.3;
                } else if (edge == 2) {
                    px = z.minX() - 0.3;
                    pz = z.minZ() + rng.nextDouble() * spanZ;
                } else {
                    px = z.maxX() + 1.3;
                    pz = z.minZ() + rng.nextDouble() * spanZ;
                }
                py = eave
                    ? z.maxY() - rng.nextDouble() * Math.min(2.5, spanY * 0.35) + 0.5
                    : z.minY() + 1.0 + rng.nextDouble() * spanY;
                Location pt = new Location(w, px, py, pz);

                switch (z.preset()) {
                    case "firefly" -> {
                        if (i % 2 == 0) {
                            w.spawnParticle(Particle.GLOW, pt, 1, 0.25, 0.25, 0.25, 0.01);
                        } else {
                            w.spawnParticle(Particle.DUST, pt, 1, 0.15, 0.15, 0.15, 0,
                                new Particle.DustOptions(Color.fromRGB(215, 255, 110), 1.1f));
                        }
                    }
                    case "magic" -> {
                        if (i == 0) {
                            w.spawnParticle(Particle.ELECTRIC_SPARK, pt, 1, 0.2, 0.2, 0.2, 0.02);
                        } else if (i == 1) {
                            w.spawnParticle(Particle.END_ROD, pt, 1, 0.15, 0.15, 0.15, 0.01);
                        } else {
                            Color c = (i == 2) ? Color.fromRGB(185, 90, 255) : Color.fromRGB(0, 235, 255);
                            w.spawnParticle(Particle.DUST, pt, 1, 0.15, 0.15, 0.15, 0,
                                new Particle.DustOptions(c, 1.2f));
                        }
                    }
                    case "aurora" -> {
                        if (i == 0) {
                            w.spawnParticle(Particle.END_ROD, pt, 1, 0.2, 0.15, 0.2, 0.015);
                        } else if (i == 1 && rng.nextInt(3) == 0) {
                            w.spawnParticle(Particle.CHERRY_LEAVES, pt, 1, 0.3, 0.2, 0.3, 0.01);
                        } else {
                            int phase = (int) ((tickCounter / 8 + i) % 3);
                            Color c = switch (phase) {
                                case 0 -> Color.fromRGB(60, 245, 220);
                                case 1 -> Color.fromRGB(120, 255, 140);
                                default -> Color.fromRGB(215, 120, 255);
                            };
                            w.spawnParticle(Particle.DUST, pt, 1, 0.2, 0.2, 0.2, 0,
                                new Particle.DustOptions(c, 1.3f));
                        }
                    }
                    default -> { // sparkle
                        if (i % 2 == 0) {
                            w.spawnParticle(Particle.END_ROD, pt, 1, 0.15, 0.15, 0.15, 0.01);
                        } else {
                            Color c = (i == 1) ? Color.fromRGB(255, 225, 130) : Color.fromRGB(255, 255, 240);
                            w.spawnParticle(Particle.DUST, pt, 1, 0.1, 0.1, 0.1, 0,
                                new Particle.DustOptions(c, 1.0f));
                        }
                    }
                }
            }
        }
    }

    public void addIlluminationZone(World world, String preset, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (world == null) return;
        String normPreset = preset == null ? "sparkle" : preset.trim().toLowerCase(Locale.ROOT);
        if (!normPreset.equals("sparkle") && !normPreset.equals("firefly")
            && !normPreset.equals("magic") && !normPreset.equals("aurora")) {
            normPreset = "sparkle";
        }
        IlluminationZone zone = new IlluminationZone(
            world.getUID(),
            normPreset,
            Math.min(x0, x1),
            Math.min(y0, y1),
            Math.min(z0, z1),
            Math.max(x0, x1),
            Math.max(y0, y1),
            Math.max(z0, z1)
        );
        illuminationZones.add(zone);
        while (illuminationZones.size() > MAX_ILLUMINATION_ZONES) {
            illuminationZones.remove(0);
        }
    }

    public void clearIlluminations() {
        illuminationZones.clear();
    }

    public void cleanupAll() {
        clearDrones();
        clearPopIns();
        clearIlluminations();
    }

    private void ensureDrones(World world, Location near) {
        if (!drones.isEmpty()) {
            // Check if existing drones are alive and in the same world
            boolean valid = true;
            for (Drone d : drones) {
                Entity e = BukkitEntityLookup.get(d.entityId);
                if (e == null || e.isDead() || e.getWorld() != world) {
                    valid = false;
                    break;
                }
            }
            if (valid) return;
            clearDrones();
        }

        for (int i = 0; i < 3; i++) {
            final int droneIdx = i;
            double phase = (Math.PI * 2.0 * i) / 3.0;
            Location spawnLoc = near.clone().add(Math.cos(phase) * 5.0, 5.0, Math.sin(phase) * 5.0);
            ItemDisplay droneEntity = world.spawn(spawnLoc, ItemDisplay.class, display -> {
                display.setItemStack(new ItemStack(droneIdx == 0 ? Material.SEA_LANTERN : Material.AMETHYST_CLUSTER));
                display.setGlowing(true);
                display.setBillboard(Display.Billboard.FIXED);
                display.setTeleportDuration(2);
                display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 1, 0),
                    new Vector3f(0.48f, 0.48f, 0.48f),
                    new AxisAngle4f(0, 0, 1, 0)
                ));
            });
            drones.add(new Drone(droneEntity.getUniqueId(), spawnLoc, phase));
        }
    }

    private void spawnPopIn(Location blockLoc, BlockData data) {
        World w = blockLoc.getWorld();
        if (w == null) return;
        Location spawnLoc = new Location(w, blockLoc.getBlockX(), blockLoc.getBlockY(), blockLoc.getBlockZ());
        BlockDisplay bd = w.spawn(spawnLoc, BlockDisplay.class, display -> {
            display.setBlock(data);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setTransformation(new Transformation(
                new Vector3f(0.375f, 0.375f, 0.375f),
                new AxisAngle4f(0, 0, 1, 0),
                new Vector3f(0.25f, 0.25f, 0.25f),
                new AxisAngle4f(0, 0, 1, 0)
            ));
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(4);
        });
        // Trigger smooth scale-up to full 1x1 block on next frame
        bd.setInterpolationDelay(0);
        bd.setInterpolationDuration(4);
        bd.setTransformation(new Transformation(
            new Vector3f(0.0f, 0.0f, 0.0f),
            new AxisAngle4f(0, 0, 1, 0),
            new Vector3f(1.01f, 1.01f, 1.01f),
            new AxisAngle4f(0, 0, 1, 0)
        ));
        activePopIns.add(new PopInEntry(bd.getUniqueId(), tickCounter + 5));
    }

    private void drawLaser(Location from, Location to, Color color, int steps) {
        World w = from.getWorld();
        if (w == null || w != to.getWorld()) return;
        double dx = (to.getX() - from.getX()) / steps;
        double dy = (to.getY() - from.getY()) / steps;
        double dz = (to.getZ() - from.getZ()) / steps;
        Particle.DustOptions opts = new Particle.DustOptions(color, 0.9f);
        for (int i = 0; i <= steps; i++) {
            Location pt = new Location(w, from.getX() + dx * i, from.getY() + dy * i, from.getZ() + dz * i);
            w.spawnParticle(Particle.DUST, pt, 1, 0, 0, 0, 0, opts);
        }
        w.spawnParticle(Particle.END_ROD, to, 1, 0.05, 0.05, 0.05, 0.01);
    }

    private void clearDrones() {
        Iterator<Drone> it = drones.iterator();
        while (it.hasNext()) {
            Drone d = it.next();
            Entity e = BukkitEntityLookup.get(d.entityId);
            if (e != null && !e.isDead()) {
                e.remove();
            }
            it.remove();
        }
    }

    private void clearPopIns() {
        while (!activePopIns.isEmpty()) {
            PopInEntry p = activePopIns.poll();
            Entity e = BukkitEntityLookup.get(p.entityId);
            if (e != null && !e.isDead()) {
                e.remove();
            }
        }
    }

    private static final class BukkitEntityLookup {
        static Entity get(UUID uuid) {
            return org.bukkit.Bukkit.getEntity(uuid);
        }
    }
}
