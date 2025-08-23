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
import net.minecraft.entity.Entity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Movement AntiCheat System for Minecraft Fabric
 * Detects and prevents movement-based exploits
 */
public class MovementAntiCheat {

    private static final double MAX_WALK_SPEED = 1.5;
    private static final double MAX_SPRINT_SPEED = 1.8;
    private static final double MAX_FLY_SPEED = 0.15;
    private static final double MAX_VERTICAL_SPEED = 0.8;
    private static final double TELEPORT_THRESHOLD = 8.0;
    private static final int POSITION_HISTORY_SIZE = 10;
    private static final long VIOLATION_RESET_TIME = 300_000;
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 20;
    private static final double LAG_COMPENSATION_MULTIPLIER = 1.3;
    private static final double MOUNT_SPEED_MULTIPLIER = 5.0;
    private static final long MOUNT_TRANSITION_GRACE = 2500; // 1 second grace period
    
    private final Map<UUID, PlayerMovementData> playerData = new ConcurrentHashMap<>();

    private static class PlayerMovementData {
        private final Queue<MovementSnapshot> positionHistory = new LinkedList<>();
        private Vec3d lastValidPosition;
        private Vec3d lastVelocity = Vec3d.ZERO;
        private int airTime = 0;
        private int violationCount = 0;
        private long lastViolation = 0;
        private boolean wasOnGround = true;
        private boolean wasLastMounted = false;
        private long lastMountStateChange = 0;
        
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
            BlockPos center = BlockPos.ofFloored(pos.x, pos.y, pos.z);
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

                        // Check for obstructions in movement path - be more lenient
                        if (y >= 0 && y <= 1 && isSolid(block)) {
                            VoxelShape shape = state.getCollisionShape(world, checkPos);
                            if (!shape.isEmpty()) {
                                Box blockBox = shape.getBoundingBox().offset(checkPos);
                                if (blockBox.intersects(playerBox)) {
                                    obstructed = true;
                                }
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
                try {
                    return state.get(SlabBlock.TYPE) == net.minecraft.block.enums.SlabType.DOUBLE ? 1.0 : 0.5;
                } catch (IllegalArgumentException e) {
                    return 0.5; // Default slab height if property doesn't exist
                }
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
            // Be more lenient with ground state validation
            if (playerOnGround && !canSupportPlayer()) {
                // Allow some tolerance for client-server sync issues
                return !(onSolidGround == false && !inWater && !hasClimbable);
            }
            // Don't be too strict about air-ground mismatches
            return true;
        }
    }

    private boolean isPlayerMounted(ServerPlayerEntity player) {
        try {
            boolean hasVehicle = player.hasVehicle();
            
            // Additional debug checks
            Entity vehicle = null;
            String vehicleType = "none";
            
            try {
                vehicle = player.getVehicle();
                if (vehicle != null) {
                    vehicleType = vehicle.getClass().getSimpleName();
                }
            } catch (Exception e) {
                // Ignore secondary checks if they fail
            }
            
   
            return hasVehicle;
        } catch (Exception e) {
            System.out.println("[AntiCheat DEBUG] Mount check failed for " + player.getName().getString() + 
                ": " + e.getMessage());
            return false;
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

        // Enhanced validation with block context - simplified approach
        if (distance > 0.15) { // Only validate movements worth checking
            if (checkSpeedViolation(player, data, horizontalDistance, verticalDistance, fromContext, toContext)) return false;
            if (checkVerticalViolation(player, data, verticalDistance, fromContext, toContext)) return false;
            if (distance > 0.8 && checkPhaseViolation(player, fromPos, toPos, fromContext, toContext)) return false;
        }
        
        // Simple checks for basic exploits
        if (distance > 0.3) {
            if (checkJesusViolation(player, toPos, toContext)) return false;
        }

        data.lastValidPosition = toPos;
        data.lastVelocity = movement;
        data.lastBlockContext = toContext;
        updateAirTime(data, player.isOnGround());

        return true;
    }
//TODO move logic for mount transition into the check speed method
    private boolean checkSpeedViolation(ServerPlayerEntity player, PlayerMovementData data,
                                       double horizontalDistance, double verticalDistance,
                                       BlockContext fromContext, BlockContext toContext) {
        // Check if player is mounted or was recently mounting/dismounting
        boolean currentlyMounted = isPlayerMounted(player);
        long currentTime = System.currentTimeMillis();
    
        // Track mount state changes with debug logging
        if (currentlyMounted != data.wasLastMounted) {
            System.out.println("[AntiCheat DEBUG] Mount state change for " + player.getName().getString() + 
                ": " + data.wasLastMounted + " -> " + currentlyMounted);
            data.wasLastMounted = currentlyMounted;
            data.lastMountStateChange = currentTime;
        }
    
        boolean inMountTransition = (currentTime - data.lastMountStateChange) < MOUNT_TRANSITION_GRACE;
    
        // Simple speed checking - just max speed + generous wiggle room
        double baseMaxSpeed = getMaxAllowedSpeed(player); // Very generous for stepping/slabs
        double maxSpeed = baseMaxSpeed;
    
        // Apply mount speed adjustments with debug logging
        if (inMountTransition) {
            maxSpeed *= MOUNT_SPEED_MULTIPLIER; // Be lenient during mounting/dismounting
        } else if (currentlyMounted) {
            maxSpeed *= MOUNT_SPEED_MULTIPLIER; // Allow much faster speeds on mounts
        }
        
        if (horizontalDistance > maxSpeed) {
                    // Debug speed check
            System.out.println("[AntiCheat DEBUG] Speed check for " + player.getName().getString() + 
                ": distance=" + String.format("%.3f", horizontalDistance) + 
                ", maxSpeed=" + String.format("%.3f", maxSpeed) + 
                ", mounted=" + currentlyMounted + 
                ", inTransition=" + inMountTransition);
            recordViolation(data, player, String.format("Speed hack: %.3f > %.3f", 
                horizontalDistance, maxSpeed));
            return true;
        }
    
        // Only check consistency for obviously fast movement
        if (data.positionHistory.size() >= 5 && horizontalDistance > maxSpeed * 0.6) {
            double avgSpeed = calculateAverageSpeed(data, 5);
            if (avgSpeed > maxSpeed * 0.7) {
                    // Debug speed check
                System.out.println("[AntiCheat DEBUG] Speed check for " + player.getName().getString() + 
                    ": distance=" + String.format("%.3f", horizontalDistance) + 
                    ", maxSpeed=" + String.format("%.3f", maxSpeed) + 
                    ",avgSpeed=" + String.format("%.3f", avgSpeed) +
                    ", mounted=" + currentlyMounted + 
                    ", inTransition=" + inMountTransition);                
                recordViolation(data, player, String.format("Consistent high speed: %.3f", avgSpeed));
                return true;
            }
        }
    
        return false;
    }
    
    private double getMaxAllowedSpeed(ServerPlayerEntity player) {
        double baseSpeed = player.isSprinting() ? MAX_SPRINT_SPEED : MAX_WALK_SPEED;
        double originalBaseSpeed = baseSpeed;
    
        // Status effect modifications with safety checks
        try {
            if (player.hasStatusEffect(StatusEffects.SPEED)) {
                int amplifier = player.getStatusEffect(StatusEffects.SPEED).getAmplifier() + 1;
                baseSpeed *= (1.0 + 0.2 * Math.min(amplifier, 10)); // Cap amplifier
            }
            if (player.hasStatusEffect(StatusEffects.SLOWNESS)) {
                int amplifier = player.getStatusEffect(StatusEffects.SLOWNESS).getAmplifier() + 1;
                baseSpeed *= (1.0 - 0.15 * Math.min(amplifier, 10)); // Cap amplifier
            }
        } catch (Exception e) {
            // Fallback if status effect queries fail
        }
    
        if (player.isCreative() || player.isSpectator()) {
            try {
                baseSpeed = player.getAbilities().getFlySpeed() * 20;
            } catch (Exception e) {
                baseSpeed = MAX_FLY_SPEED * 20; // Fallback
            }
        }
    
        double finalSpeed = Math.max(baseSpeed, 0.01);
        
        return finalSpeed;
    }
private boolean checkVerticalViolation(ServerPlayerEntity player, PlayerMovementData data,
                                       double verticalDistance, BlockContext fromContext, BlockContext toContext) {
    if (player.getAbilities().allowFlying || player.isGliding()) return false;
    if (toContext.inWater || toContext.inLava || toContext.hasClimbable) return false;

    // Add mount checks - skip most vertical validation when mounted
    boolean currentlyMounted = isPlayerMounted(player);
    long currentTime = System.currentTimeMillis();
    boolean inMountTransition = (currentTime - data.lastMountStateChange) < MOUNT_TRANSITION_GRACE;
    
    if (currentlyMounted || inMountTransition) {
        // Allow much more freedom for mounted movement
        // Only check for extreme impossible vertical speeds
        if (Math.abs(verticalDistance) > 5.0) { // 5 blocks per tick is clearly impossible even for mounts
            recordViolation(data, player, String.format("Extreme mounted vertical speed: %.3f", verticalDistance));
            return true;
        }
        return false; // Skip all other vertical checks when mounted
    }

    // Track jumping for height validation (only for non-mounted players)
    if (!data.wasOnGround && player.isOnGround()) {
        // Just landed - reset tracking
        data.airTime = 0;
        return false;
    }
    
    if (data.wasOnGround && !player.isOnGround() && verticalDistance > 0) {
        // Just started jumping - track the starting height
        if (data.lastValidPosition != null) {
            // We'll check max jump height based on barriers, not step-by-step movement
            double maxJumpHeight = getMaxJumpHeight(player, data.lastValidPosition);
            
            // Only check if they've been in air for a while and gained significant height
            if (data.airTime > 5) {
                double totalHeightGain = toContext.position.y - data.lastValidPosition.y;
                if (totalHeightGain > maxJumpHeight) {
                    recordViolation(data, player, String.format("Jump too high: %.2f > %.2f blocks", 
                        totalHeightGain, maxJumpHeight));
                    return true;
                }
            }
        }
    }

    // Simple fly check - only for extreme vertical speeds
    double maxVerticalSpeed = 1.5; // Very generous
    if (player.hasStatusEffect(StatusEffects.JUMP_BOOST)) {
        int amplifier = player.getStatusEffect(StatusEffects.JUMP_BOOST).getAmplifier() + 1;
        maxVerticalSpeed += 0.3 * amplifier;
    }

    if (verticalDistance > maxVerticalSpeed) {
        recordViolation(data, player, String.format("Extreme vertical speed: %.3f", verticalDistance));
        return true;
    }

    // Simple hovering check - only for extreme cases (and not when mounted!)
    if (data.airTime > 100 && Math.abs(verticalDistance) < 0.005) {
        recordViolation(data, player, "Hovering detected");
        return true;
    }

    return false;
}

    private double getMaxJumpHeight(ServerPlayerEntity player, Vec3d startPos) {
        ServerWorld world = player.getWorld();
        BlockPos startBlock = BlockPos.ofFloored(startPos.x, startPos.y, startPos.z);
        
        // Base jump height - normal player can jump ~1.25 blocks
        double maxHeight = 1.3;
        
        if (player.hasStatusEffect(StatusEffects.JUMP_BOOST)) {
            int amplifier = player.getStatusEffect(StatusEffects.JUMP_BOOST).getAmplifier() + 1;
            maxHeight += 0.5 * amplifier; // Each level adds ~0.5 blocks
        }
        
        // Check for barriers around starting position that should limit jump height
        boolean hasBarrier = false;
        double barrierHeight = 0;
        
        // Check 3x3 area around starting position
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos checkPos = startBlock.add(x, 0, z);
                Block block = world.getBlockState(checkPos).getBlock();
                
                // Fences and walls - should limit jump to ~1.5 blocks
                if (block instanceof FenceBlock || block instanceof WallBlock) {
                    hasBarrier = true;
                    barrierHeight = Math.max(barrierHeight, 1.5);
                }
                
                // Check for trapdoors on top of blocks
                BlockPos abovePos = checkPos.up();
                Block aboveBlock = world.getBlockState(abovePos).getBlock();
                if (aboveBlock instanceof net.minecraft.block.TrapdoorBlock) {
                    BlockState trapdoorState = world.getBlockState(abovePos);
                    try {
                        boolean isOpen = trapdoorState.get(net.minecraft.block.TrapdoorBlock.OPEN);
                        boolean isTop = trapdoorState.get(net.minecraft.block.TrapdoorBlock.HALF) == net.minecraft.block.enums.BlockHalf.TOP;
                        
                        if (!isOpen && isTop) {
                            hasBarrier = true;
                            barrierHeight = Math.max(barrierHeight, 1.9); // Block + trapdoor
                        }
                    } catch (Exception e) {
                        // Ignore property errors
                    }
                }
            }
        }
        
        // If there's a barrier, limit jump height to slightly above it
        if (hasBarrier) {
            maxHeight = Math.min(maxHeight, barrierHeight + 0.2);
        }
        
        return maxHeight;
    }

    private boolean checkPhaseViolation(ServerPlayerEntity player, Vec3d fromPos, Vec3d toPos,
                                       BlockContext fromContext, BlockContext toContext) {
        if (player.isSpectator()) return false;

        // Allow limited phasing during mount transitions - only within interaction distance
        PlayerMovementData data = getPlayerData(player.getUuid());
        long currentTime = System.currentTimeMillis();
        boolean inMountTransition = (currentTime - data.lastMountStateChange) < MOUNT_TRANSITION_GRACE;
            
        // Calculate distance for this method
        double distance = fromPos.distanceTo(toPos);

        // If in mount transition, only allow phasing for short distances (mounting range)
        if (inMountTransition && distance > 6.0) { // MAX_INTERACTION_DISTANCE from your action limiter
            recordViolation(data, player, "Long-distance phase during mount transition: " + String.format("%.2f", distance));
            return true;
        }
        
        // For mount transitions within interaction range, allow some phasing but still check for extreme cases
        if (inMountTransition) {
            // Only block if trying to phase through many solid blocks (obvious exploit)
            int solidBlockCount = countSolidBlocksInPath(player, fromPos, toPos);
            if (solidBlockCount > 3) { // Allow phasing through a few blocks, but not a wall
                recordViolation(data, player, "Excessive phasing during mount transition through " + solidBlockCount + " blocks");
                return true;
            }
            return false; // Allow limited phasing during mount transitions
        }
        
        // Only check for significant movements that could be phasing
        if (distance < 0.8) return false;

        // Simple check: moving through solid blocks
        ServerWorld world = player.getWorld();
        Vec3d direction = toPos.subtract(fromPos);
        double pathDistance = direction.length();
        Vec3d normalized = direction.normalize();
        int steps = Math.max(5, (int) (pathDistance * 10));
        Box playerBox = player.getBoundingBox();

        // Check path for solid blocks
        for (int i = 1; i < steps; i++) {
            double progress = (double) i / steps;
            Vec3d checkPos = fromPos.add(direction.multiply(progress));
            Box checkBox = playerBox.offset(checkPos.subtract(fromPos));

            BlockPos blockPos = BlockPos.ofFloored(checkPos.x, checkPos.y, checkPos.z);
            BlockState state = world.getBlockState(blockPos);
            
            if (!state.isAir() && state.getBlock() != Blocks.WATER) {
                VoxelShape shape = state.getCollisionShape(world, blockPos);
                if (!shape.isEmpty()) {
                    Box blockBox = shape.getBoundingBox().offset(blockPos);
                    if (checkBox.intersects(blockBox)) {
                        recordViolation(getPlayerData(player.getUuid()), player,
                            "Phase/NoClip through " + state.getBlock());
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private int countSolidBlocksInPath(ServerPlayerEntity player, Vec3d fromPos, Vec3d toPos) {
        ServerWorld world = player.getWorld();
        Vec3d direction = toPos.subtract(fromPos);
        double pathDistance = direction.length();
        Vec3d normalized = direction.normalize();
        int steps = Math.max(5, (int) (pathDistance * 10));
        Box playerBox = player.getBoundingBox();
        
        int solidBlockCount = 0;
        Set<BlockPos> checkedPositions = new HashSet<>(); // Avoid double-counting same block
        
        // Check path for solid blocks
        for (int i = 1; i < steps; i++) {
            double progress = (double) i / steps;
            Vec3d checkPos = fromPos.add(direction.multiply(progress));
            Box checkBox = playerBox.offset(checkPos.subtract(fromPos));
    
            BlockPos blockPos = BlockPos.ofFloored(checkPos.x, checkPos.y, checkPos.z);
            
            // Skip if we already checked this block position
            if (checkedPositions.contains(blockPos)) continue;
            checkedPositions.add(blockPos);
            
            BlockState state = world.getBlockState(blockPos);
            
            if (!state.isAir() && state.getBlock() != Blocks.WATER) {
                VoxelShape shape = state.getCollisionShape(world, blockPos);
                if (!shape.isEmpty()) {
                    Box blockBox = shape.getBoundingBox().offset(blockPos);
                    if (checkBox.intersects(blockBox)) {
                        solidBlockCount++;
                    }
                }
            }
        }
        
        return solidBlockCount;
    }

    private boolean checkJesusViolation(ServerPlayerEntity player, Vec3d position, BlockContext context) {
        if (player.getAbilities().allowFlying || player.isGliding()) return false;
        if (player.hasStatusEffect(StatusEffects.WATER_BREATHING)) return false;

        // Enhanced Jesus detection
        if (context.inWater && player.isOnGround() && !player.isSwimming()) {
            // Check if actually on water surface
            BlockPos playerPos = BlockPos.ofFloored(position.x, position.y, position.z);
            ServerWorld world = player.getWorld();
            
            if (world.getBlockState(playerPos.down()).getBlock() == Blocks.WATER &&
                world.getBlockState(playerPos).getBlock() == Blocks.AIR) {
                recordViolation(getPlayerData(player.getUuid()), player, "Jesus/Water walking");
                return true;
            }
        }

        return false;
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
        try {
            return player.hasStatusEffect(StatusEffects.LEVITATION);
        } catch (Exception e) {
            return false; // Fallback if status effect check fails
        }
    }

    private boolean hasSlowFalling(ServerPlayerEntity player) {
        try {
            return player.hasStatusEffect(StatusEffects.SLOW_FALLING);
        } catch (Exception e) {
            return false; // Fallback if status effect check fails
        }
    }

    private void recordViolation(PlayerMovementData data, ServerPlayerEntity player, String reason) {
        data.violationCount++;
        data.lastViolation = System.currentTimeMillis();

        // Add debug logging with safety checks
        try {
            System.out.println("[AntiCheat] Movement violation by " + player.getName().getString() +
                    ": " + reason + " (Total: " + data.violationCount + ")");
        } catch (Exception e) {
            System.out.println("[AntiCheat] Movement violation by unknown player: " + reason + 
                    " (Total: " + data.violationCount + ")");
        }

        remediate(player, data, reason);
    }

    private void remediate(ServerPlayerEntity player, PlayerMovementData data, String reason) {
        // Be much more lenient with teleporting back
        if (data.violationCount > 8 && data.lastValidPosition != null) {
            try {
                player.requestTeleport(data.lastValidPosition.x, data.lastValidPosition.y, data.lastValidPosition.z);
            } catch (Exception e) {
                System.out.println("[AntiCheat] Failed to teleport player back: " + e.getMessage());
            }
        }

        try {
            if (data.violationCount == 10) {
                player.sendMessage(net.minecraft.text.Text.of("§6[AntiCheat] §eMovement irregularities detected"));
            }

            if (data.violationCount == 15) {
                player.sendMessage(net.minecraft.text.Text.of("§c[AntiCheat] §cSuspicious movement patterns detected"));
            }
        } catch (Exception e) {
            System.out.println("[AntiCheat] Failed to send message to player: " + e.getMessage());
        }

        if (data.violationCount > MAX_VIOLATIONS_BEFORE_KICK) {
            try {
                System.out.println("[AntiCheat] Player " + player.getName().getString() + " should be kicked for violations");
                removePlayer(player);
            } catch (Exception e) {
                System.out.println("[AntiCheat] Player should be kicked for violations (name unavailable)");
            }
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
