package net.landonis.dashboardmod.anticheat;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Action Rate Limiter for Minecraft Fabric Anticheat
 * Prevents various exploits by limiting player action rates
 */
public class ActionRateLimiter {
    
    // Configuration constants
    private static final long BLOCK_BREAK_COOLDOWN = 50; // ms between block breaks
    private static final long BLOCK_PLACE_COOLDOWN = 100; // ms between block placements
    private static final long ITEM_USE_COOLDOWN = 250; // ms between item usage
    private static final long ATTACK_COOLDOWN = 500; // ms between attacks
    private static final int MAX_ACTIONS_PER_SECOND = 20;
    private static final long CLEANUP_INTERVAL = 300000; // 5 minutes
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 10;
    private static final long VIOLATION_DECAY_TIME = 60000; // 1 minute
    
    // Player tracking data
    private final Map<UUID, PlayerActionData> playerData = new ConcurrentHashMap<>();
    private long lastCleanup = System.currentTimeMillis();
    
    /**
     * Internal class to track player action history
     */
    private static class PlayerActionData {
        private long lastBlockBreak = 0;
        private long lastBlockPlace = 0;
        private long lastItemUse = 0;
        private long lastAttack = 0;
        private final Queue<Long> recentActions = new LinkedList<>();
        private int violationCount = 0;
        private long lastViolation = 0;
    }
    
    /**
     * Check if a block break action is allowed
     */
    public boolean canBreakBlock(ServerPlayerEntity player, BlockPos pos) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Check cooldown
        if (currentTime - data.lastBlockBreak < BLOCK_BREAK_COOLDOWN) {
            recordViolation(data, "Block break too fast");
            return false;
        }
        
        // Check general action rate
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, "Too many actions per second");
            return false;
        }
        
        // Update last action time
        data.lastBlockBreak = currentTime;
        data.recentActions.offer(currentTime);
        cleanupOldActions(data, currentTime);
        
        return true;
    }
    
    /**
     * Check if a block place action is allowed
     */
    public boolean canPlaceBlock(ServerPlayerEntity player, BlockPos pos) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - data.lastBlockPlace < BLOCK_PLACE_COOLDOWN) {
            recordViolation(data, "Block place too fast");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, "Too many actions per second");
            return false;
        }
        
        data.lastBlockPlace = currentTime;
        data.recentActions.offer(currentTime);
        cleanupOldActions(data, currentTime);
        
        return true;
    }
    
    /**
     * Check if an item use action is allowed
     */
    public boolean canUseItem(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - data.lastItemUse < ITEM_USE_COOLDOWN) {
            recordViolation(data, "Item use too fast");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, "Too many actions per second");
            return false;
        }
        
        data.lastItemUse = currentTime;
        data.recentActions.offer(currentTime);
        cleanupOldActions(data, currentTime);
        
        return true;
    }
    
    /**
     * Check if an attack action is allowed
     */
    public boolean canAttack(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - data.lastAttack < ATTACK_COOLDOWN) {
            recordViolation(data, "Attack too fast");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, "Too many actions per second");
            return false;
        }
        
        data.lastAttack = currentTime;
        data.recentActions.offer(currentTime);
        cleanupOldActions(data, currentTime);
        
        return true;
    }
    
    /**
     * Get or create player data
     */
    private PlayerActionData getPlayerData(UUID playerId) {
        return playerData.computeIfAbsent(playerId, k -> new PlayerActionData());
    }
    
    /**
     * Check if the player is exceeding the general action rate limit
     */
    private boolean checkActionRate(PlayerActionData data, long currentTime) {
        // Count actions in the last second - avoid stream for performance
        long oneSecondAgo = currentTime - 1000;
        int recentActionCount = 0;
        for (Long actionTime : data.recentActions) {
            if (actionTime > oneSecondAgo) {
                recentActionCount++;
            }
        }
        
        return recentActionCount < MAX_ACTIONS_PER_SECOND;
    }
    
    /**
     * Clean up old actions from the queue
     */
    private void cleanupOldActions(PlayerActionData data, long currentTime) {
        long oneSecondAgo = currentTime - 1000;
        while (!data.recentActions.isEmpty() && data.recentActions.peek() < oneSecondAgo) {
            data.recentActions.poll();
        }
    }
    
    /**
     * Record a violation for the player
     */
    private void recordViolation(PlayerActionData data, String reason) {
        data.violationCount++;
        data.lastViolation = System.currentTimeMillis();
        
        // Use proper logger instead of System.out.println
        // Logger logger = LoggerFactory.getLogger(ActionRateLimiter.class);
        // logger.warn("[AntiCheat] Rate limit violation: {} (Total violations: {})", reason, data.violationCount);
        System.out.println("[AntiCheat] Rate limit violation: " + reason + 
                          " (Total violations: " + data.violationCount + ")");
        
        // Implement escalating punishments based on violation count
        if (data.violationCount > MAX_VIOLATIONS_BEFORE_KICK) {
            // This should be handled by the calling code with access to the player object
            System.out.println("[AntiCheat] Player exceeded violation threshold");
        }
    }
    
    /**
     * Get violation count for a player
     */
    public int getViolationCount(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        return data != null ? data.violationCount : 0;
    }
    
    /**
     * Reset violations for a player (useful for admin commands)
     */
    public void resetViolations(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        if (data != null) {
            data.violationCount = 0;
            data.lastViolation = 0;
        }
    }
    
    /**
     * Clean up old player data to prevent memory leaks
     */
    public void performMaintenance() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            // Apply violation decay first
            playerData.values().forEach(data -> {
                if (currentTime - data.lastViolation > VIOLATION_DECAY_TIME) {
                    data.violationCount = Math.max(0, data.violationCount - 1);
                    data.lastViolation = currentTime; // Reset timer
                }
            });
            
            // Remove data for players who haven't been active recently
            long cutoffTime = currentTime - CLEANUP_INTERVAL;
            playerData.entrySet().removeIf(entry -> {
                PlayerActionData data = entry.getValue();
                long lastActivity = Math.max(data.lastBlockBreak, 
                               Math.max(data.lastBlockPlace,
                               Math.max(data.lastItemUse, data.lastAttack)));
                return lastActivity < cutoffTime && data.violationCount == 0;
            });
            
            lastCleanup = currentTime;
        }
    }
    
    /**
     * Remove all data for a player (called when player leaves)
     */
    public void removePlayer(ServerPlayerEntity player) {
        playerData.remove(player.getUuid());
    }
}
