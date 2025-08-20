package net.landonis.dashboardmod.anticheat;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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

    private static final double MAX_WALK_SPEED = 0.32;
    private static final double MAX_SPRINT_SPEED = 0.43;
    private static final double MAX_FLY_SPEED = 0.15;
    private static final double MAX_VERTICAL_SPEED = 0.48;
    private static final double TELEPORT_THRESHOLD = 8.0;
    private static final int POSITION_HISTORY_SIZE = 10;
    private static final long VIOLATION_RESET_TIME = 300_000;
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 20;
    private static final double LAG_COMPENSATION_MULTIPLIER = 1.2;

    private final Map<UUID, PlayerMovementData> playerData = new ConcurrentHashMap<>();

    private static class PlayerMovementData {
        private final Queue<MovementSnapshot> positionHistory = new LinkedList<>();
        private Vec3d lastValidPosition;
        private Vec3d lastVelocity = Vec3d.ZERO;
        private int airTime = 0;
        private int violationCount = 0;
        private long lastViolation = 0;
        private boolean wasOnGround = true;
    }

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

        if (data.lastValidPosition == null) {
            data.lastValidPosition = fromPos;
            return true;
        }

        Vec3d movement = toPos.subtract(fromPos);
        double distance = movement.length();
        double horizontalDistance = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        double verticalDistance = movement.y;

        addPositionSnapshot(data, toPos, currentTime, player.isOnGround(), player.getYaw());

        if (distance > TELEPORT_THRESHOLD) {
            if (isLegitTeleport(player, fromPos, toPos)) {
                data.lastValidPosition = toPos;
                return true;
            } else {
                recordViolation(data, player, "Illegal teleport: " + String.format("%.2f", distance) + " blocks");
                return false;
            }
        }

        // Step-aware vertical movement
        double stepHeight = getStepHeight(player);
        if (!player.getAbilities().allowFlying && verticalDistance <= stepHeight + 0.15) {
            // Allow normal step/jump movement without flag
            data.lastValidPosition = toPos;
            data.lastVelocity = movement;
            updateAirTime(data, player.isOnGround());
            return true;
        }

        if (checkSpeedViolation(player, data, horizontalDistance, verticalDistance)) return false;
        if (checkFlyViolation(player, data, verticalDistance, stepHeight)) return false;
        if (checkPhaseViolation(player, fromPos, toPos)) return false;
        if (checkJesusViolation(player, toPos)) return false;

        data.lastValidPosition = toPos;
        data.lastVelocity = movement;
        updateAirTime(data, player.isOnGround());

        return true;
    }

    /**
     * Maximum step height in front of the player
     */
    private double getStepHeight(ServerPlayerEntity player) {
        ServerWorld world = player.getWorld();
        BlockPos pos = player.getBlockPos();
        // Check the block in front
        BlockPos front = pos.offset(player.getHorizontalFacing());
        Block block = world.getBlockState(front).getBlock();

        if (block == Blocks.AIR) return 0.0;
        if (block == Blocks.SLAB || block == Blocks.STONE_SLAB) return 0.5;
        if (block == Blocks.STAIRS || block == Blocks.OAK_STAIRS) return 0.5;
        return 1.0; // full block step
    }

    private boolean checkSpeedViolation(ServerPlayerEntity player, PlayerMovementData data,
                                       double horizontalDistance, double verticalDistance) {
        double maxSpeed = getMaxAllowedSpeed(player) * LAG_COMPENSATION_MULTIPLIER;

        if (horizontalDistance > maxSpeed) {
            recordViolation(data, player, String.format("Speed hack: %.3f > %.3f", horizontalDistance, maxSpeed));
            return true;
        }

        if (data.positionHistory.size() >= 3) {
            double avgSpeed = calculateAverageSpeed(data, 3);
            if (avgSpeed > maxSpeed * 0.9 && horizontalDistance > maxSpeed * 0.9) {
                long recentViolations = data.positionHistory.stream()
                        .mapToLong(s -> s.timestamp)
                        .filter(t -> System.currentTimeMillis() - t < 5000)
                        .count();

                if (recentViolations < 2) {
                    recordViolation(data, player, String.format("Consistent high speed: %.3f", avgSpeed));
                    return true;
                }
            }
        }

        return false;
    }

    private boolean checkFlyViolation(ServerPlayerEntity player, PlayerMovementData data,
                                      double verticalDistance, double stepHeight) {
        if (player.getAbilities().allowFlying || player.isGliding() || isInWater(player)) return false;

        double maxVertical = MAX_VERTICAL_SPEED;
        if (player.hasStatusEffect(StatusEffects.JUMP_BOOST)) {
            int amplifier = player.getStatusEffect(StatusEffects.JUMP_BOOST).getAmplifier() + 1;
            maxVertical += 0.1 * amplifier;
        }

        if (verticalDistance > maxVertical * LAG_COMPENSATION_MULTIPLIER) {
            recordViolation(data, player, String.format("Fly hack: upward %.3f", verticalDistance));
            return true;
        }

        if (data.airTime > 30 && Math.abs(verticalDistance) < 0.02 && !player.isOnGround()) {
            if (!isInWater(player) && !hasLevitation(player) && !player.isClimbing()) {
                recordViolation(data, player, "Hovering in air");
                return true;
            }
        }

        if (data.airTime > 60 && verticalDistance > -0.05) {
            if (!isInWater(player) && !hasSlowFalling(player) && !hasLevitation(player)) {
                recordViolation(data, player, "Anti-gravity");
                return true;
            }
        }

        return false;
    }

    private boolean checkPhaseViolation(ServerPlayerEntity player, Vec3d fromPos, Vec3d toPos) {
        ServerWorld world = player.getWorld();
        Vec3d direction = toPos.subtract(fromPos);
        double distance = direction.length();

        if (distance > 0.1) {
            Vec3d normalized = direction.normalize();
            int steps = (int) (distance * 4);

            for (int i = 1; i < steps; i++) {
                Vec3d checkPos = fromPos.add(normalized.multiply(i * 0.25));
                BlockPos blockPos = BlockPos.ofFloored(checkPos);
                Block block = world.getBlockState(blockPos).getBlock();

                if (block != Blocks.AIR && block != Blocks.WATER) {
                    if (wouldCollide(player, checkPos)) {
                        recordViolation(getPlayerData(player.getUuid()), player,
                                "Phase/NoClip through " + block);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean checkJesusViolation(ServerPlayerEntity player, Vec3d position) {
        ServerWorld world = player.getWorld();
        BlockPos pos = BlockPos.ofFloored(position);
        BlockPos below = pos.down();

        if (world.getBlockState(below).getBlock() == Blocks.WATER &&
                player.isOnGround() && !player.getAbilities().allowFlying) {
            recordViolation(getPlayerData(player.getUuid()), player, "Jesus/Water walking");
            return true;
        }

        return false;
    }

    private double getMaxAllowedSpeed(ServerPlayerEntity player) {
        double baseSpeed = player.isSprinting() ? MAX_SPRINT_SPEED : MAX_WALK_SPEED;

        if (player.hasStatusEffect(StatusEffects.SPEED)) {
            int amplifier = player.getStatusEffect(StatusEffects.SPEED).getAmplifier() + 1;
            baseSpeed *= (1.0 + 0.2 * amplifier);
        }
        if (player.hasStatusEffect(StatusEffects.SLOWNESS)) {
            int amplifier = player.getStatusEffect(StatusEffects.SLOWNESS).getAmplifier() + 1;
            baseSpeed *= (1.0 - 0.15 * amplifier);
        }
        if (player.isCreative() || player.isSpectator()) {
            baseSpeed = player.getAbilities().getFlySpeed() * 20;
        }
        return Math.max(baseSpeed, 0.01);
    }

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

    private boolean isLegitTeleport(ServerPlayerEntity player, Vec3d from, Vec3d to) {
        return player.isCreative() || player.hasPermissionLevel(2);
    }

    private void addPositionSnapshot(PlayerMovementData data, Vec3d pos, long time, boolean onGround, double yaw) {
        data.positionHistory.offer(new MovementSnapshot(pos, time, onGround, yaw));
        while (data.positionHistory.size() > POSITION_HISTORY_SIZE) {
            data.positionHistory.poll();
        }
    }

    private void updateAirTime(PlayerMovementData data, boolean onGround) {
        if (onGround) {
            data.airTime = 0;
            data.wasOnGround = true;
        } else {
            data.airTime++;
            data.wasOnGround = false;
        }
    }

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
        return !player.getWorld().isSpaceEmpty(player, player.getBoundingBox().offset(position.subtract(player.getPos())));
    }

    private void recordViolation(PlayerMovementData data, ServerPlayerEntity player, String reason) {
        data.violationCount++;
        data.lastViolation = System.currentTimeMillis();

        System.out.println("[AntiCheat] Movement violation by " + player.getName().getString() +
                ": " + reason + " (Total: " + data.violationCount + ")");

        remediate(player, data, reason);
    }

    private void remediate(ServerPlayerEntity player, PlayerMovementData data, String reason) {
        if (data.violationCount > 3 && data.lastValidPosition != null) {
            player.requestTeleport(data.lastValidPosition.x, data.lastValidPosition.y, data.lastValidPosition.z);
        }

        if (data.violationCount == 5) {
            player.sendMessage(net.minecraft.text.Text.of("§6[AntiCheat] §eMovement irregularities detected"));
        }

        if (data.violationCount == 10) {
            player.sendMessage(net.minecraft.text.Text.of("§c[AntiCheat] §cSuspicious movement patterns detected"));
        }

        if (data.violationCount > MAX_VIOLATIONS_BEFORE_KICK) {
            System.out.println("[AntiCheat] Player " + player.getName().getString() + " should be kicked for violations");
        }
    }

    private PlayerMovementData getPlayerData(UUID playerId) {
        return playerData.computeIfAbsent(playerId, k -> new PlayerMovementData());
    }

    public int getViolationCount(ServerPlayerEntity player) {
        PlayerMovementData data = playerData.get(player.getUuid());
        return data != null ? data.violationCount : 0;
    }

    public void resetViolations(ServerPlayerEntity player) {
        PlayerMovementData data = playerData.get(player.getUuid());
        if (data != null) {
            data.violationCount = 0;
            data.lastViolation = 0;
        }
    }

    public void performMaintenance() {
        long currentTime = System.currentTimeMillis();
        playerData.values().forEach(data -> {
            if (currentTime - data.lastViolation > VIOLATION_RESET_TIME) {
                data.violationCount = Math.max(0, data.violationCount - 1);
            }
        });
        playerData.entrySet().removeIf(entry ->
                currentTime - entry.getValue().lastViolation > VIOLATION_RESET_TIME * 2 &&
                        entry.getValue().violationCount == 0
        );
    }

    public void removePlayer(ServerPlayerEntity player) {
        playerData.remove(player.getUuid());
    }
}
