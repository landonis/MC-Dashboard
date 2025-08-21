package net.landonis.dashboardmod.anticheat;

import net.minecraft.util.math.MathHelper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Box;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.util.shape.VoxelShape;

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
    private static final double MAX_VERTICAL_SPEED = 0.8;
    private static final double TELEPORT_THRESHOLD = 8.0;
    private static final int POSITION_HISTORY_SIZE = 10;
    private static final long VIOLATION_RESET_TIME = 300_000;
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 20;
    private static final double LAG_COMPENSATION_MULTIPLIER = 1.3;

    private final Map<UUID, PlayerMovementData> playerData = new ConcurrentHashMap<>();

    private static class PlayerMovementData {
        private final Queue<MovementSnapshot> positionHistory = new LinkedList<>();
        private Vec3d lastValidPosition;
        private Vec3d lastVelocity = Vec3d.ZERO;
        private int airTime = 0;
        private int violationCount = 0;
        private long lastViolation = 0;
        private boolean wasOnGround = true;
        private BlockContext lastBlockContext;
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
     * Comprehensive block context around player
     */
    private static class BlockContext {
        final Map<BlockPos, Block> nearbyBlocks = new HashMap<>();
        final boolean onSolidGround;
        final boolean inWater;
        final boolean inLava;
        final boolean hasClimbable;
        final double maxStepHeight;
        final boolean hasObstructions;
        final Set<Block> supportingBlocks = new HashSet<>();
        final Vec3d position;

        BlockContext(ServerPlayerEntity player, Vec3d pos) {
            this.position = pos;
            ServerWorld world = player.getWorld();
            Box playerBox = player.getBoundingBox().offset(pos.subtract(player.getPos()));
            
            // Check blocks in 3x3x3 area around player
            BlockPos center = new BlockPos(pos);
            boolean solidGround = false;
            boolean water = false;
            boolean lava = false;
            boolean climbable = false;
            boolean obstructed = false;
            double stepHeight = 0.0;

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        BlockPos checkPos = center.add(x, y, z);
                        BlockState state = world.getBlockState(checkPos);
                        Block block = state.getBlock();
                        nearbyBlocks.put(checkPos, block);

                        // Check for ground support (blocks below player)
                        if (y == -1 && isSolid(block)) {
                            solidGround = true;
                            supportingBlocks.add(block);
                            stepHeight = Math.max(stepHeight, getBlockStepHeight(block, state));
                        }

                        // Check for liquids
                        if (block == Blocks.WATER) water = true;
                        if (block == Blocks.LAVA) lava = true;

                        // Check for climbable blocks
                        if (isClimbable(block)) climbable = true;

                        // Check for obstructions in movement path
                        if (y >= 0 && y <= 1 && isSolid(block)) {
                            Box blockBox = state.getCollisionShape(world, checkPos).getBoundingBox().offset(checkPos);
                            if (blockBox.intersects(playerBox)) {
                                obstructed = true;
                            }
                        }
                    }
                }
            }

            this.onSolidGround = solidGround;
            this.inWater = water;
            this.inLava = lava;
            this.hasClimbable = climbable;
            this.maxStepHeight = stepHeight;
            this.hasObstructions = obstructed;
        }

        private boolean isSolid(Block block) {
            return block != Blocks.AIR && 
                   block != Blocks.WATER && 
                   block != Blocks.LAVA &&
                   !isClimbable(block);
        }

        private boolean isClimbable(Block block) {
            return block == Blocks.LADDER || 
                   block == Blocks.VINE ||
                   block instanceof net.minecraft.block.ScaffoldingBlock;
        }

        private double getBlockStepHeight(Block block, BlockState state) {
            if (block instanceof SlabBlock) {
                return state.get(SlabBlock.TYPE) == net.minecraft.block.enums.SlabType.DOUBLE ? 1.0 : 0.5;
            }
            if (block instanceof StairsBlock) return 0.75;
            if (block instanceof FenceBlock || block instanceof WallBlock) return 1.5;
            if (block == Blocks.AIR) return 0.0;
            return 1.0;
        }

        public boolean canSupportPlayer() {
            return onSolidGround || inWater || hasClimbable;
        }

        public boolean isValidGroundState(boolean playerOnGround) {
            if (playerOnGround && !canSupportPlayer()) return false;
            if (!playerOnGround && onSolidGround && !inWater) return false;
            return true;
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
            data.lastBlockContext = new BlockContext(player, fromPos);
            return true;
        }

        // Get block context for both positions
        BlockContext fromContext = data.lastBlockContext != null ? data.lastBlockContext : new BlockContext(player, fromPos);
        BlockContext toContext = new BlockContext(player, toPos);

        // Basic movement calculations
        Vec3d movement = toPos.subtract(fromPos);
        double distance = movement.length();
        double horizontalDistance = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
        double verticalDistance = movement.y;

        addPositionSnapshot(data, toPos, currentTime, player.isOnGround(), player.getYaw());

        // Check for teleporting first
        if (distance > TELEPORT_THRESHOLD) {
            if (isLegitTeleport(player, fromPos, toPos)) {
                data.lastValidPosition = toPos;
                data.lastBlockContext = toContext;
                return true;
            } else {
                recordViolation(data, player, "Illegal teleport: " + String.format("%.2f", distance) + " blocks");
                return false;
            }
        }

        // Enhanced validation with block context
        if (checkSpeedViolation(player, data, horizontalDistance, verticalDistance, fromContext, toContext)) return false;
        if (checkFlyViolation(player, data, verticalDistance, fromContext, toContext)) return false;
        if (checkPhaseViolation(player, fromPos, toPos, fromContext, toContext)) return false;
        if (checkGroundStateViolation(player, data, toContext)) return false;
        if (checkJesusViolation(player, toPos, toContext)) return false;
        if (checkObstructionViolation(player, data, movement, fromContext, toContext)) return false;

        data.lastValidPosition = toPos;
        data.lastVelocity = movement;
        data.lastBlockContext = toContext;
        updateAirTime(data, player.isOnGround());

        return true;
    }

    private boolean checkSpeedViolation(ServerPlayerEntity player, PlayerMovementData data,
                                       double horizontalDistance, double verticalDistance,
                                       BlockContext fromContext, BlockContext toContext) {
        double maxSpeed = getMaxAllowedSpeed(player, toContext) * LAG_COMPENSATION_MULTIPLIER;

        // Reduce speed limit if moving through obstructions
        if (toContext.hasObstructions && !player.getAbilities().allowFlying) {
            maxSpeed *= 0.7; // Reduce speed when obstructed
        }

        // Allow higher speed when falling or on ice
        if (verticalDistance < -0.1) {
            maxSpeed *= 1.2; // Allow some extra horizontal speed when falling
        }
        
        if (isOnIce(toContext)) {
            maxSpeed *= 1.4; // Ice allows faster movement
        }

        if (horizontalDistance > maxSpeed) {
            recordViolation(data, player, String.format("Speed hack: %.3f > %.3f (context: %s)", 
                horizontalDistance, maxSpeed, getContextDescription(toContext)));
            return true;
        }

        // Enhanced consistency checking with block context
        if (data.positionHistory.size() >= 3) {
            double avgSpeed = calculateAverageSpeed(data, 3);
            if (avgSpeed > maxSpeed * 0.85 && horizontalDistance > maxSpeed * 0.85) {
                // Only flag if not in a legitimate high-speed context
                if (!toContext.inWater && !isOnIce(toContext) && !toContext.hasClimbable) {
                    recordViolation(data, player, String.format("Consistent high speed: %.3f", avgSpeed));
                    return true;
                }
            }
        }

        return false;
    }

    private boolean checkFlyViolation(ServerPlayerEntity player, PlayerMovementData data,
                                      double verticalDistance, BlockContext fromContext, BlockContext toContext) {
        if (player.getAbilities().allowFlying || player.isGliding()) return false;
        if (toContext.inWater || toContext.inLava || toContext.hasClimbable) return false;

        double maxVertical = MAX_VERTICAL_SPEED;
        if (player.hasStatusEffect(StatusEffects.JUMP_BOOST)) {
            int amplifier = player.getStatusEffect(StatusEffects.JUMP_BOOST).getAmplifier() + 1;
            maxVertical += 0.1 * amplifier;
        }

        // Allow extra vertical movement when stepping up
        if (toContext.maxStepHeight > 0 && verticalDistance <= toContext.maxStepHeight + 0.3) {
            return false; // Valid step up
        }

        if (verticalDistance > maxVertical * LAG_COMPENSATION_MULTIPLIER) {
            recordViolation(data, player, String.format("Fly hack: upward %.3f (max: %.3f)", 
                verticalDistance, maxVertical));
            return true;
        }

        // Enhanced hovering detection
        if (data.airTime > 30 && Math.abs(verticalDistance) < 0.02 && !player.isOnGround()) {
            if (!toContext.canSupportPlayer() && !hasLevitation(player)) {
                recordViolation(data, player, "Hovering in air without support");
                return true;
            }
        }

        // Anti-gravity detection with context
        if (data.airTime > 60 && verticalDistance > -0.05) {
            if (!toContext.canSupportPlayer() && !hasSlowFalling(player) && !hasLevitation(player)) {
                recordViolation(data, player, "Anti-gravity detected");
                return true;
            }
        }

        return false;
    }

    private boolean checkPhaseViolation(ServerPlayerEntity player, Vec3d fromPos, Vec3d toPos,
                                       BlockContext fromContext, BlockContext toContext) {
        if (player.isSpectator()) return false;

        // Quick check: if destination has obstructions, likely phasing
        if (toContext.hasObstructions && !player.getAbilities().allowFlying) {
            recordViolation(getPlayerData(player.getUuid()), player, 
                "Phase through solid blocks at destination");
            return true;
        }

        // Detailed path checking for longer movements
        Vec3d direction = toPos.subtract(fromPos);
        double distance = direction.length();

        if (distance > 0.3) {
            ServerWorld world = player.getWorld();
            Vec3d normalized = direction.normalize();
            int steps = Math.max(3, (int) (distance * 8));
            Box playerBox = player.getBoundingBox();

            for (int i = 1; i < steps; i++) {
                double progress = (double) i / steps;
                Vec3d checkPos = fromPos.add(direction.multiply(progress));
                Box checkBox = playerBox.offset(checkPos.subtract(fromPos));

                // Check if player box intersects solid blocks
                BlockPos blockPos = BlockPos.ofFloored(checkPos);
                for (int x = -1; x <= 1; x++) {
                    for (int y = 0; y <= 2; y++) {
                        for (int z = -1; z <= 1; z++) {
                            BlockPos pos = blockPos.add(x, y, z);
                            BlockState state = world.getBlockState(pos);
                            if (!state.isAir() && state.getBlock() != Blocks.WATER) {
                                VoxelShape shape = state.getCollisionShape(world, pos);
                                if (!shape.isEmpty()) {
                                    Box blockBox = shape.getBoundingBox().offset(pos);
                                    if (checkBox.intersects(blockBox)) {
                                        recordViolation(getPlayerData(player.getUuid()), player,
                                            "Phase/NoClip through " + state.getBlock() + " at " + pos);
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return false;
    }

    private boolean checkGroundStateViolation(ServerPlayerEntity player, PlayerMovementData data,
                                             BlockContext toContext) {
        boolean playerOnGround = player.isOnGround();
        
        if (!toContext.isValidGroundState(playerOnGround)) {
            recordViolation(data, player, String.format(
                "Invalid ground state: player=%s, context=%s", 
                playerOnGround, getContextDescription(toContext)));
            return true;
        }

        return false;
    }

    private boolean checkJesusViolation(ServerPlayerEntity player, Vec3d position, BlockContext context) {
        if (player.getAbilities().allowFlying || player.isGliding()) return false;
        if (player.hasStatusEffect(StatusEffects.WATER_BREATHING)) return false;

        // Enhanced Jesus detection
        if (context.inWater && player.isOnGround() && !player.isSwimming()) {
            // Check if actually on water surface
            BlockPos pos = BlockPos.ofFloored(position);
            ServerWorld world = player.getWorld();
            
            if (world.getBlockState(pos.down()).getBlock() == Blocks.WATER &&
                world.getBlockState(pos).getBlock() == Blocks.AIR) {
                recordViolation(getPlayerData(player.getUuid()), player, "Jesus/Water walking");
                return true;
            }
        }

        return false;
    }

    private boolean checkObstructionViolation(ServerPlayerEntity player, PlayerMovementData data,
                                             Vec3d movement, BlockContext fromContext, BlockContext toContext) {
        // Check for impossible movements through blocks
        if (movement.length() > 0.1) {
            // If moving horizontally through what should be solid obstructions
            double horizontalMovement = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
            if (horizontalMovement > 0.1 && toContext.hasObstructions && !toContext.inWater) {
                // Allow if player can legitimately pass through (e.g., doors, gaps)
                if (!canPassThrough(player, fromContext, toContext)) {
                    recordViolation(data, player, "Movement through solid obstructions");
                    return true;
                }
            }
        }

        return false;
    }

    private boolean canPassThrough(ServerPlayerEntity player, BlockContext from, BlockContext to) {
        // Check if movement is through legitimate openings
        for (Block block : to.supportingBlocks) {
            if (block instanceof net.minecraft.block.DoorBlock ||
                block instanceof net.minecraft.block.FenceGateBlock ||
                block instanceof net.minecraft.block.TrapDoorBlock) {
                return true; // Can pass through doors/gates
            }
        }
        return false;
    }

    private boolean isOnIce(BlockContext context) {
        return context.supportingBlocks.contains(Blocks.ICE) ||
               context.supportingBlocks.contains(Blocks.PACKED_ICE) ||
               context.supportingBlocks.contains(Blocks.BLUE_ICE);
    }

    private String getContextDescription(BlockContext context) {
        StringBuilder desc = new StringBuilder();
        if (context.onSolidGround) desc.append("ground,");
        if (context.inWater) desc.append("water,");
        if (context.hasClimbable) desc.append("climb,");
        if (context.hasObstructions) desc.append("blocked,");
        return desc.length() > 0 ? desc.substring(0, desc.length() - 1) : "air";
    }

    private double getMaxAllowedSpeed(ServerPlayerEntity player, BlockContext context) {
        double baseSpeed = player.isSprinting() ? MAX_SPRINT_SPEED : MAX_WALK_SPEED;

        // Context-based speed modifications
        if (context.inWater && !player.hasStatusEffect(StatusEffects.DOLPHINS_GRACE)) {
            baseSpeed *= 0.4; // Slower in water
        } else if (context.inLava) {
            baseSpeed *= 0.2; // Much slower in lava
        } else if (isOnIce(context)) {
            baseSpeed *= 1.3; // Faster on ice
        }

        // Status effect modifications
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

    private boolean hasLevitation(ServerPlayerEntity player) {
        return player.hasStatusEffect(StatusEffects.LEVITATION);
    }

    private boolean hasSlowFalling(ServerPlayerEntity player) {
        return player.hasStatusEffect(StatusEffects.SLOW_FALLING);
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
