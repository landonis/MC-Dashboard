package com.example.anticheat;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Static helper class for integrating anticheat into existing event handlers
 */
public class AntiCheatHelper {
    
    private static ActionRateLimiter rateLimiter;
    private static MovementAntiCheat movementAntiCheat;
    private static boolean initialized = false;
    
    /**
     * Initialize the anticheat systems - call this once in your main mod initializer
     */
    public static void initialize() {
        if (!initialized) {
            rateLimiter = new ActionRateLimiter();
            movementAntiCheat = new MovementAntiCheat();
            initialized = true;
            System.out.println("[AntiCheat] Helper initialized");
        }
    }
    
    /**
     * Check if player can break a block - call from your UseBlockCallback
     */
    public static boolean canBreakBlock(ServerPlayerEntity player, BlockPos pos) {
        ensureInitialized();
        return rateLimiter.canBreakBlock(player, pos);
    }
    
    /**
     * Check if player can place a block - call from your UseBlockCallback
     */
    public static boolean canPlaceBlock(ServerPlayerEntity player, BlockPos pos) {
        ensureInitialized();
        return rateLimiter.canPlaceBlock(player, pos);
    }
    
    /**
     * Check if player can attack - call from your AttackEntityCallback
     */
    public static boolean canAttack(ServerPlayerEntity player) {
        ensureInitialized();
        return rateLimiter.canAttack(player);
    }
    
    /**
     * Check if player can use item - call from your UseItemCallback
     */
    public static boolean canUseItem(ServerPlayerEntity player) {
        ensureInitialized();
        return rateLimiter.canUseItem(player);
    }
    
    /**
     * Validate player movement - call from your server tick handler
     */
    public static boolean validateMovement(ServerPlayerEntity player, Vec3d fromPos, Vec3d toPos) {
        ensureInitialized();
        return movementAntiCheat.validateMovement(player, fromPos, toPos);
    }
    
    /**
     * Perform maintenance - call every second from your server tick handler
     */
    public static void performMaintenance() {
        if (initialized) {
            rateLimiter.performMaintenance();
            movementAntiCheat.performMaintenance();
        }
    }
    
    /**
     * Clean up player data when they disconnect
     */
    public static void onPlayerDisconnect(ServerPlayerEntity player) {
        if (initialized) {
            rateLimiter.removePlayer(player);
            movementAntiCheat.removePlayer(player);
        }
    }
    
    /**
     * Get violation counts for debugging/admin purposes
     */
    public static int getRateViolations(ServerPlayerEntity player) {
        ensureInitialized();
        return rateLimiter.getViolationCount(player);
    }
    
    public static int getMovementViolations(ServerPlayerEntity player) {
        ensureInitialized();
        return movementAntiCheat.getViolationCount(player);
    }
    
    /**
     * Reset violations for a player
     */
    public static void resetViolations(ServerPlayerEntity player) {
        if (initialized) {
            rateLimiter.resetViolations(player);
            movementAntiCheat.resetViolations(player);
        }
    }
    
    private static void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("AntiCheatHelper not initialized! Call AntiCheatHelper.initialize() first.");
        }
    }
}
