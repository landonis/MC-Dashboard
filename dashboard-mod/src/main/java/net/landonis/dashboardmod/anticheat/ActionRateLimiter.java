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
    
    // Much more lenient configuration constants
    private static final long BLOCK_BREAK_COOLDOWN = 25; // ms between block breaks (was 50)
    private static final long BLOCK_PLACE_COOLDOWN = 50; // ms between block placements (was 100)
    private static final long ITEM_USE_COOLDOWN = 100; // ms between item usage (was 250)
    private static final long ATTACK_COOLDOWN = 200; // ms between attacks (was 500)
    private static final int MAX_ACTIONS_PER_SECOND = 35; // Increased from 20
    private static final long CLEANUP_INTERVAL = 300000; // 5 minutes
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 15; // Increased from 10
    private static final long VIOLATION_DECAY_TIME = 30000; // 30 seconds (was 1 minute)
    
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
            recordViolation(data, player, "Block break too fast: " + (currentTime - data.lastBlockBreak) + "ms");
            return false;
        }
        
        // Check general action rate
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second: " + countRecentActions(data, currentTime));
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
            recordViolation(data, player, "Block place too fast: " + (currentTime - data.lastBlockPlace) + "ms");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second: " + countRecentActions(data, currentTime));
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
            recordViolation(data, player, "Item use too fast: " + (currentTime - data.lastItemUse) + "ms");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second: " + countRecentActions(data, currentTime));
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
            recordViolation(data, player, "Attack too fast: " + (currentTime - data.lastAttack) + "ms");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second: " + countRecentActions(data, currentTime));
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
        return countRecentActions(data, currentTime) < MAX_ACTIONS_PER_SECOND;
    }
    
    /**
     * Count recent actions in the last second
     */
    private int countRecentActions(PlayerActionData data, long currentTime) {
        long oneSecondAgo = currentTime - 1000;
        int recentActionCount = 0;
        for (Long actionTime : data.recentActions) {
            if (actionTime > oneSecondAgo) {
                recentActionCount++;
            }
        }
        return recentActionCount;
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
    private void recordViolation(PlayerActionData data, ServerPlayerEntity player, String reason) {
        data.violationCount++;
        data.lastViolation = System.currentTimeMillis();
        
        try {
            System.out.println("[AntiCheat] Rate limit violation by " + player.getName().getString() + 
                              ": " + reason + " (Total violations: " + data.violationCount + ")");
        } catch (Exception e) {
            System.out.println("[AntiCheat] Rate limit violation: " + reason + 
                              " (Total violations: " + data.violationCount + ")");
        }
        
        // Send feedback to player
        try {
            if (data.violationCount == 3) {
                player.sendMessage(net.minecraft.text.Text.of("§6[AntiCheat] §eSlowing down actions to prevent violations"));
            } else if (data.violationCount == 8) {
                player.sendMessage(net.minecraft.text.Text.of("§c[AntiCheat] §cToo many rapid actions detected"));
            }
        } catch (Exception e) {
            // Ignore message sending errors
        }
        
        // Implement escalating punishments based on violation count
        if (data.violationCount > MAX_VIOLATIONS_BEFORE_KICK) {
            try {
                System.out.println("[AntiCheat] Player " + player.getName().getString() + 
                                 " exceeded rate limit violation threshold");
            } catch (Exception e) {
                System.out.println("[AntiCheat] Player exceeded rate limit violation threshold");
            }
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
