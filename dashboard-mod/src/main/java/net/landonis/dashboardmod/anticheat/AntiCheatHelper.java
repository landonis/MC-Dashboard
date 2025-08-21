package net.landonis.dashboardmod.anticheat;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.util.Hand;

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
            System.out.println("[AntiCheat] Helper initialized with enhanced features");
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
     * Enhanced: Check if player can break a block with block context
     */
    public static boolean canBreakBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        ensureInitialized();
        return rateLimiter.canBreakBlock(player, pos, block);
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
     * Enhanced: Check if player can attack with target context
     */
    public static boolean canAttack(ServerPlayerEntity player, Entity target) {
        ensureInitialized();
        return rateLimiter.canAttack(player, target);
    }
    
    /**
     * Check if player can use item - call from your UseItemCallback
     */
    public static boolean canUseItem(ServerPlayerEntity player) {
        ensureInitialized();
        return rateLimiter.canUseItem(player);
    }
    
    /**
     * Enhanced: Check if player can use item with context (for animal feeding detection)
     */
    public static boolean canUseItem(ServerPlayerEntity player, Item item, Hand hand, Entity targetEntity) {
        ensureInitialized();
        return rateLimiter.canUseItem(player, item, hand, targetEntity);
    }
    
    /**
     * Enhanced: Check if player can interact with a specific block (doors, etc.)
     */
    public static boolean canInteractWithBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        ensureInitialized();
        return rateLimiter.canInteractWithBlock(player, pos, block);
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
     * Enhanced: Get detailed player stats from the rate limiter
     */
    public static String getPlayerStats(ServerPlayerEntity player) {
        ensureInitialized();
        return rateLimiter.getPlayerStats(player);
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
