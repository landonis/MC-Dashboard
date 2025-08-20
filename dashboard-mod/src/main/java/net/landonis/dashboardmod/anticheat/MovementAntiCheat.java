package net.landonis.dashboardmod.anticheat;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffects;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Movement AntiCheat System for Minecraft Fabric
 * Detects and prevents movement-based exploits
 */
public class MovementAntiCheat {
    
    // Physics constants - adjusted for production use
    private static final double MAX_WALK_SPEED = 0.32; // More lenient for lag
    private static final double MAX_SPRINT_SPEED = 0.43; // More lenient for lag
    private static final double MAX_FLY_SPEED = 0.15;
    private static final double MAX_VERTICAL_SPEED = 0.48; // Account for jump boost
    private static final double GRAVITY = -0.08; // blocks per tick squared
    private static final double FRICTION = 0.91;
    private static final double MAX_ELYTRA_SPEED = 2.0;
    private static final double TELEPORT_THRESHOLD = 8.0; // blocks
    private static final int POSITION_HISTORY_SIZE = 10;
    private static final long VIOLATION_RESET_TIME = 300000; // 5 minutes
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 20; // More lenient
    private static final double LAG_COMPENSATION_MULTIPLIER = 1.2; // 20% tolerance
    
    // Player tracking data
    private final Map<UUID, PlayerMovementData> playerData = new ConcurrentHashMap<>();
    private long lastCleanup = System.currentTimeMillis();
    
    /**
     * Internal class to track player movement history
     */
    private static class PlayerMovementData {
        private final Queue<MovementSnapshot> positionHistory = new LinkedList<>();
        private Vec3d lastValidPosition;
        private Vec3d lastVelocity = Vec3d.ZERO;
        private long lastGroundCheck = 0;
        private int airTime = 0;
        private int violationCount = 0;
        private long lastViolation = 0;
        private boolean wasOnGround = true;
        private double totalDistance = 0;
        private long lastDistanceReset = System.currentTimeMillis();
    }
    
    /**
     * Snapshot of player movement at a point in time
     */
    private static class MovementSnapshot {
        final Vec3d position;
        final long timestamp;
        final boolean onGround;
        final double yaw;
        
        MovementSnapshot(Vec3d pos, long time, boolean ground, double yaw) {
            this.position = pos;
            this.timestamp = time;
            this.onGround = ground;
            this.yaw = yaw;
        }
    }
    
    /**
     * Main movement validation method
     */
    public boolean validateMovement(ServerPlayerEntity player, Vec3d fromPos, Vec3d toPos) {
        UUID playerId = player.getUuid();
        PlayerMovementData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Initialize last valid position if needed
        if (data.lastValidPosition == null) {
            data.lastValidPosition = fromPos;
            return true;
        }
        
        // Calculate movement vector
        Vec3d movement = toPos.subtract(fromPos);
        double distance = movement.length();
        double horizontalDistance = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        double verticalDistance = movement.y;
        
        // Add to position history
        addPositionSnapshot(data, toPos, currentTime, player.isOnGround(), player.getYaw());
        
        // Skip validation for teleports (legitimate ones)
        if (distance > TELEPORT_THRESHOLD) {
            if (isLegitTeleport(player, fromPos, toPos)) {
                data.lastValidPosition = toPos;
                return true;
            } else {
                recordViolation(data, player, "Illegal teleport: " + String.format("%.2f", distance) + " blocks");
                return false;
            }
        }
        
        // Check various movement violations
        if (checkSpeedViolation(player, data, horizontalDistance, verticalDistance)) return false;
        if (checkFlyViolation(player, data, verticalDistance)) return false;
        if (checkPhaseViolation(player, fromPos, toPos)) return false;
        if (checkJesusViolation(player, toPos)) return false;
        
        // Update tracking data
        data.lastValidPosition = toPos;
        data.lastVelocity = movement;
        updateAirTime(data, player.isOnGround());
        
        return true;
    }
    
    /**
     * Check for speed hacks
     */
    private boolean checkSpeedViolation(ServerPlayerEntity player, PlayerMovementData data, 
                                      double horizontalDistance, double verticalDistance) {
        double maxSpeed = getMaxAllowedSpeed(player) * LAG_COMPENSATION_MULTIPLIER;
        
        // Check horizontal speed
        if (horizontalDistance > maxSpeed) {
            recordViolation(data, player, String.format("Speed hack: %.3f > %.3f", horizontalDistance, maxSpeed));
            return true;
        }
        
        // Check for consistent high speed (indicates velocity modification)
        if (data.positionHistory.size() >= 3) {
            double avgSpeed = calculateAverageSpeed(data, 3);
            if (avgSpeed > maxSpeed * 0.9 && horizontalDistance > maxSpeed * 0.9) {
                // Only flag if consistently fast, not just burst speed
                long recentViolations = data.positionHistory.stream()
                    .mapToLong(s -> s.timestamp)
                    .filter(t -> System.currentTimeMillis() - t < 5000)
                    .count();
                    
                if (recentViolations < 2) { // Avoid spam violations
                    recordViolation(data, player, String.format("Consistent high speed: %.3f", avgSpeed));
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * Check for fly hacks
     */
    private boolean checkFlyViolation(ServerPlayerEntity player, PlayerMovementData data, double verticalDistance) {
        if (player.getAbilities().allowFlying || player.isGliding() || isInWater(player)) {
            return false; // Creative mode, elytra, or water
        }
        
        // Check for impossible upward movement
        double maxVertical = MAX_VERTICAL_SPEED;
        if (player.hasStatusEffect(StatusEffects.JUMP_BOOST)) {
            int amplifier = player.getStatusEffect(StatusEffects.JUMP_BOOST).getAmplifier() + 1;
            maxVertical += 0.1 * amplifier; // Jump boost increases jump height
        }
        
        if (verticalDistance > maxVertical * LAG_COMPENSATION_MULTIPLIER && !player.isOnGround()) {
            recordViolation(data, player, String.format("Fly hack: upward %.3f", verticalDistance));
            return true;
        }
        
        // Check for hovering (staying at same Y level while not on ground)
        if (data.airTime > 30 && Math.abs(verticalDistance) < 0.02 && !player.isOnGround()) {
            if (!isInWater(player) && !hasLevitation(player) && !player.isClimbing()) {
                recordViolation(data, player, "Hovering in air");
                return true;
            }
        }
        
        // Check for impossible air movement patterns - more lenient
        if (data.airTime > 60 && verticalDistance > -0.05) { // Should be falling faster after 3 seconds
            if (!isInWater(player) && !hasSlowFalling(player) && !hasLevitation(player)) {
                recordViolation(data, player, "Anti-gravity");
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check for phase/noclip violations
     */
    private boolean checkPhaseViolation(ServerPlayerEntity player, Vec3d fromPos, Vec3d toPos) {
        ServerWorld world = player.getWorld();
        
        // Simple ray-cast check for solid blocks between positions
        Vec3d direction = toPos.subtract(fromPos);
        double distance = direction.length();
        
        if (distance > 0.1) {
            Vec3d normalized = direction.normalize();
            int steps = (int)(distance * 4); // Check every 0.25 blocks
            
            for (int i = 1; i < steps; i++) {
                Vec3d checkPos = fromPos.add(normalized.multiply(i * 0.25));
                BlockPos blockPos = BlockPos.ofFloored(checkPos);
                
                if (!world.getBlockState(blockPos).isAir() && 
                    !world.getBlockState(blockPos).getBlock().equals(Blocks.WATER)) {
                    // Check if player actually collides with this block
                    if (wouldCollide(player, checkPos)) {
                        recordViolation(getPlayerData(player.getUuid()), player, 
                                      "Phase/NoClip through " + world.getBlockState(blockPos).getBlock());
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    /**
     * Check for Jesus/water walking
     */
    private boolean checkJesusViolation(ServerPlayerEntity player, Vec3d position) {
        ServerWorld world = player.getWorld();
        BlockPos pos = BlockPos.ofFloored(position);
        BlockPos below = pos.down();
        
        // Check if player is walking on water
        if (world.getBlockState(below).getBlock().equals(Blocks.WATER) && 
            player.isOnGround() && !player.getAbilities().allowFlying) {
            recordViolation(getPlayerData(player.getUuid()), player, "Jesus/Water walking");
            return true;
        }
        
        return false;
    }
    
    /**
     * Calculate maximum allowed speed for player
     */
    private double getMaxAllowedSpeed(ServerPlayerEntity player) {
        double baseSpeed = player.isSprinting() ? MAX_SPRINT_SPEED : MAX_WALK_SPEED;
        
        // Adjust for effects
        if (player.hasStatusEffect(StatusEffects.SPEED)) {
            int amplifier = player.getStatusEffect(StatusEffects.SPEED).getAmplifier() + 1;
            baseSpeed *= (1.0 + 0.2 * amplifier);
        }
        
        if (player.hasStatusEffect(StatusEffects.SLOWNESS)) {
            int amplifier = player.getStatusEffect(StatusEffects.SLOWNESS).getAmplifier() + 1;
            baseSpeed *= (1.0 - 0.15 * amplifier);
        }
        
        // Creative/spectator mode
        if (player.isCreative() || player.isSpectator()) {
            baseSpeed = player.getAbilities().getFlySpeed() * 20; // Convert to blocks/tick
        }
        
        return Math.max(baseSpeed, 0.01); // Minimum speed
    }
    
    /**
     * Calculate average speed over last N movements - avoid streams for performance
     */
    private double calculateAverageSpeed(PlayerMovementData data, int samples) {
        if (data.positionHistory.size() < samples) return 0;
        
        MovementSnapshot[] history = data.positionHistory.toArray(new MovementSnapshot[0]);
        double totalDistance = 0;
        int actualSamples = 0;
        
        for (int i = Math.max(0, history.length - samples); i < history.length - 1; i++) {
            totalDistance += history[i].position.distanceTo(history[i + 1].position);
            actualSamples++;
        }
        
        return actualSamples > 0 ? totalDistance / actualSamples : 0;
    }
    
    /**
     * Check if teleport is legitimate (chorus fruit, ender pearl, etc.)
     */
    private boolean isLegitTeleport(ServerPlayerEntity player, Vec3d from, Vec3d to) {
        // This would need to be integrated with your mod's teleport tracking
        // For now, assume admin/creative teleports are legitimate
        return player.isCreative() || player.hasPermissionLevel(2);
    }
    
    /**
     * Add position to history and maintain size limit
     */
    private void addPositionSnapshot(PlayerMovementData data, Vec3d pos, long time, boolean onGround, double yaw) {
        data.positionHistory.offer(new MovementSnapshot(pos, time, onGround, yaw));
        while (data.positionHistory.size() > POSITION_HISTORY_SIZE) {
            data.positionHistory.poll();
        }
    }
    
    /**
     * Update air time tracking
     */
    private void updateAirTime(PlayerMovementData data, boolean onGround) {
        if (onGround) {
            data.airTime = 0;
            data.wasOnGround = true;
        } else {
            data.airTime++;
            data.wasOnGround = false;
        }
    }
    
    /**
     * Helper methods for environmental checks
     */
    private boolean isInWater(ServerPlayerEntity player) {
        return player.isSubmergedInWater() || player.isInSwimmingPose();
    }
    
    private boolean hasLevitation(ServerPlayerEntity player) {
        return player.hasStatusEffect(StatusEffects.LEVITATION);
    }
    
    private boolean hasSlowFalling(ServerPlayerEntity player) {
        return player.hasStatusEffect(StatusEffects.SLOW_FALLING);
    }
    
    private boolean wouldCollide(ServerPlayerEntity player, Vec3d position) {
        // Simplified collision check - you might want to use Minecraft's collision system
        return !player.getWorld().isSpaceEmpty(player, player.getBoundingBox().offset(position.subtract(player.getPos())));
    }
    
    /**
     * Record a movement violation
     */
    private void recordViolation(PlayerMovementData data, ServerPlayerEntity player, String reason) {
        data.violationCount++;
        data.lastViolation = System.currentTimeMillis();
        
        System.out.println("[AntiCheat] Movement violation by " + player.getName().getString() + 
                          ": " + reason + " (Total: " + data.violationCount + ")");
        
        // Implement remediation
        remediate(player, data, reason);
    }
    
    /**
     * Apply remediation measures
     */
    private void remediate(ServerPlayerEntity player, PlayerMovementData data, String reason) {
        // For production, implement more sophisticated remediation
        
        // Only teleport back on severe violations to avoid disrupting gameplay
        if (data.violationCount > 3 && data.lastValidPosition != null) {
            // Use Minecraft's proper teleport method
            player.requestTeleport(data.lastValidPosition.x, data.lastValidPosition.y, data.lastValidPosition.z);
        }
        
        // Escalating punishments based on violation count
        if (data.violationCount == 5) {
            // Send warning message
            player.sendMessage(net.minecraft.text.Text.of("§6[AntiCheat] §eMovement irregularities detected"));
        }
        
        if (data.violationCount == 10) {
            // More serious warning
            player.sendMessage(net.minecraft.text.Text.of("§c[AntiCheat] §cSuspicious movement patterns detected"));
        }
        
        if (data.violationCount > MAX_VIOLATIONS_BEFORE_KICK) {
            // This should be handled by calling code with proper server access
            // player.networkHandler.disconnect(net.minecraft.text.Text.of("Movement violations"));
            System.out.println("[AntiCheat] Player " + player.getName().getString() + " should be kicked for violations");
        }
    }
    
    /**
     * Get or create player data
     */
    private PlayerMovementData getPlayerData(UUID playerId) {
        return playerData.computeIfAbsent(playerId, k -> new PlayerMovementData());
    }
    
    /**
     * Get violation count for a player
     */
    public int getViolationCount(ServerPlayerEntity player) {
        PlayerMovementData data = playerData.get(player.getUuid());
        return data != null ? data.violationCount : 0;
    }
    
    /**
     * Reset violations for a player
     */
    public void resetViolations(ServerPlayerEntity player) {
        PlayerMovementData data = playerData.get(player.getUuid());
        if (data != null) {
            data.violationCount = 0;
            data.lastViolation = 0;
        }
    }
    
    /**
     * Clean up old player data
     */
    public void performMaintenance() {
        long currentTime = System.currentTimeMillis();
        
        // Reset old violations
        playerData.values().forEach(data -> {
            if (currentTime - data.lastViolation > VIOLATION_RESET_TIME) {
                data.violationCount = Math.max(0, data.violationCount - 1);
            }
        });
        
        // Remove inactive players
        playerData.entrySet().removeIf(entry -> 
            currentTime - entry.getValue().lastViolation > VIOLATION_RESET_TIME * 2 &&
            entry.getValue().violationCount == 0
        );
    }
    
    /**
     * Remove player data when they disconnect
     */
    public void removePlayer(ServerPlayerEntity player) {
        playerData.remove(player.getUuid());
    }
}
