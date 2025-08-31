package net.landonis.dashboardmod.anticheat;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;
import net.minecraft.text.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ActionRateLimiter - Detects and prevents obvious exploit patterns
 * 
 * This class focuses on catching clear cheating behaviors while being
 * permissive to legitimate gameplay. It tracks player actions over time
 * and flags impossible patterns like superhuman clicking speeds.
 * 
 * @author Landonis Dashboard Mod
 * @version 2.0
 */
public class ActionRateLimiter {
    
    // ==================== CONFIGURATION CONSTANTS ====================
    
    /** Maximum reasonable actions per second before considering it an exploit */
    private static final int EXPLOIT_ACTIONS_PER_SECOND = 50;
    
    /** Minimum time between block breaks (ms) - below this is impossible */
    private static final long EXPLOIT_BREAK_SPEED_MS = 1;
    
    /** Minimum time between attacks (ms) - below this is impossible */
    private static final long EXPLOIT_ATTACK_SPEED_MS = 20;
    
    /** Maximum interaction distance in blocks - very generous to avoid false positives */
    private static final double MAX_INTERACTION_DISTANCE_BLOCKS = 8.0;
    
    /** Block breaks per second that indicates nuker usage - set to catch obvious exploits only */
    private static final int NUKER_DETECTION_THRESHOLD = 45;
    
    /** Attacks per second that indicates killaura usage */
    private static final int KILLAURA_DETECTION_THRESHOLD = 10;
    
    /** Time window for pattern detection (1 second) */
    private static final long PATTERN_DETECTION_WINDOW_MS = 1000;
    
    /** Maximum severe violations before escalation */
    private static final int MAX_SEVERE_VIOLATIONS = 10;
    
    /** Time after which violations start to decay */
    private static final long VIOLATION_DECAY_TIME_MS = 30_000; // 30 seconds
    
    /** How often to perform maintenance cleanup */
    private static final long CLEANUP_INTERVAL_MS = 300_000; // 5 minutes
    
    /** How often to update cached player positions */
    private static final long POSITION_UPDATE_INTERVAL_MS = 100; // 100ms
    
    /** Multiplier for item use rate limits (more lenient) */
    private static final int ITEM_USE_RATE_MULTIPLIER = 2;
    
    /** Offset to target block center for distance calculations */
    private static final double BLOCK_CENTER_OFFSET = 0.5;
    
    // ==================== INSTANCE VARIABLES ====================
    
    private final Map<UUID, PlayerActionData> playerActionData = new ConcurrentHashMap<>();
    private long lastMaintenanceTime = System.currentTimeMillis();
    
    // ==================== INNER CLASSES ====================
    
    /**
     * Tracks action patterns and violations for a single player
     */
    private static class PlayerActionData {
        // Action tracking lists
        private final List<Long> blockBreakTimestamps = new ArrayList<>();
        private final List<Long> attackTimestamps = new ArrayList<>();
        private final List<Long> allActionTimestamps = new ArrayList<>();
        
        // Cached player position for reach checks
        private double cachedPlayerX = 0.0;
        private double cachedPlayerY = 0.0;
        private double cachedPlayerZ = 0.0;
        private long lastPositionUpdateTime = 0;
        
        // Mining session tracking to distinguish sustained mining from exploits
        private long lastMiningSessionEnd = 0;
        private boolean inMiningSession = false;
        private int consecutiveMiningCycles = 0;
        // Violation tracking
        private int severeViolationCount = 0;
        private long lastViolationTime = 0;
        private long lastActionTime = 0;
        
        // ==================== ACTION RECORDING METHODS ====================
        
        void recordBlockBreak(long timestamp) {
            blockBreakTimestamps.add(timestamp);
            allActionTimestamps.add(timestamp);
            lastActionTime = timestamp;
            removeExpiredActions(timestamp);
        }
        
        void recordAttack(long timestamp) {
            attackTimestamps.add(timestamp);
            allActionTimestamps.add(timestamp);
            lastActionTime = timestamp;
            removeExpiredActions(timestamp);
        }
        
        void recordGenericAction(long timestamp) {
            allActionTimestamps.add(timestamp);
            lastActionTime = timestamp;
            removeExpiredActions(timestamp);
        }
        
        // ==================== DATA RETRIEVAL METHODS ====================
        
        int getRecentBlockBreaks(long currentTime) {
            removeExpiredActions(currentTime);
            return blockBreakTimestamps.size();
        }
        
        int getRecentAttacks(long currentTime) {
            removeExpiredActions(currentTime);
            return attackTimestamps.size();
        }
        
        int getRecentActions(long currentTime) {
            removeExpiredActions(currentTime);
            return allActionTimestamps.size();
        }
        
        long getTimeBetweenLastTwoBlockBreaks() {
            if (blockBreakTimestamps.size() < 2) {
                return Long.MAX_VALUE; // No interval available
            }
            int size = blockBreakTimestamps.size();
            return blockBreakTimestamps.get(size - 1) - blockBreakTimestamps.get(size - 2);
        }
        
        long getTimeBetweenLastTwoAttacks() {
            if (attackTimestamps.size() < 2) {
                return Long.MAX_VALUE; // No interval available
            }
            int size = attackTimestamps.size();
            return attackTimestamps.get(size - 1) - attackTimestamps.get(size - 2);
        }
        
        // ==================== POSITION CACHING METHODS ====================
        
        void updateCachedPosition(double x, double y, double z, long timestamp) {
            this.cachedPlayerX = x;
            this.cachedPlayerY = y;
            this.cachedPlayerZ = z;
            this.lastPositionUpdateTime = timestamp;
        }
        
        boolean shouldUpdatePosition(long currentTime) {
            return currentTime - lastPositionUpdateTime > POSITION_UPDATE_INTERVAL_MS;
        }
        
        double getDistanceToBlock(BlockPos blockPos) {
            return Math.sqrt(
                Math.pow(blockPos.getX() + BLOCK_CENTER_OFFSET - cachedPlayerX, 2) +
                Math.pow(blockPos.getY() + BLOCK_CENTER_OFFSET - cachedPlayerY, 2) +
                Math.pow(blockPos.getZ() + BLOCK_CENTER_OFFSET - cachedPlayerZ, 2)
            );
        }
        
        // ==================== VIOLATION TRACKING METHODS ====================
        
        void recordSevereViolation(long timestamp) {
            severeViolationCount++;
            lastViolationTime = timestamp;
        }
        
        boolean hasExceededViolationThreshold() {
            return severeViolationCount > MAX_SEVERE_VIOLATIONS;
        }
        
        boolean shouldDecayViolation(long currentTime) {
            return currentTime - lastViolationTime > VIOLATION_DECAY_TIME_MS;
        }
        
        void decayOneViolation() {
            severeViolationCount = Math.max(0, severeViolationCount - 1);
        }
        
        void resetViolations() {
            severeViolationCount = 0;
            lastViolationTime = 0;
        }
        
        // ==================== CLEANUP METHODS ====================
        
        private void removeExpiredActions(long currentTime) {
            long cutoffTime = currentTime - PATTERN_DETECTION_WINDOW_MS;
            blockBreakTimestamps.removeIf(timestamp -> timestamp < cutoffTime);
            attackTimestamps.removeIf(timestamp -> timestamp < cutoffTime);
            allActionTimestamps.removeIf(timestamp -> timestamp < cutoffTime);
        }
        
        boolean isPlayerInactive(long currentTime) {
            return currentTime - lastActionTime > CLEANUP_INTERVAL_MS;
        }
        
        // ==================== GETTERS ====================
        
        int getSevereViolationCount() { return severeViolationCount; }
        long getLastViolationTime() { return lastViolationTime; }
    }
    
    // ==================== PUBLIC API METHODS ====================
    
    /**
     * Checks if a player can break a block without triggering anti-cheat
     * 
     * @param player The player attempting to break the block
     * @param blockPos Position of the block being broken
     * @param block The block being broken (can be null)
     * @return true if the action should be allowed
     */
    public boolean canBreakBlock(ServerPlayerEntity player, BlockPos blockPos, Block block) {
        if (isPlayerInCreativeMode(player)) {
            return true;
        }
        
        PlayerActionData playerData = getOrCreatePlayerData(player.getUuid());
        long currentTime = System.currentTimeMillis();
        
        // Validate interaction distance
        if (!isWithinReachDistance(player, blockPos, playerData, "block break")) {
            return false;
        }
        
        // Check for nuker pattern
        if (detectsNukerPattern(playerData, currentTime)) {
            recordViolation(playerData, player, "Nuker detected: " + 
                playerData.getRecentBlockBreaks(currentTime) + " blocks in 1 second");
            return false;
        }
        
        // Check for impossible break speed
        if (detectsImpossibleBreakSpeed(playerData)) {
            recordViolation(playerData, player, "Impossible break speed: " + 
                playerData.getTimeBetweenLastTwoBlockBreaks() + "ms");
            return false;
        }
        
        // Check for impossible action rate
        if (detectsImpossibleActionRate(playerData, currentTime, 1)) {
            recordViolation(playerData, player, "Impossible action rate: " + 
                playerData.getRecentActions(currentTime) + " actions per second");
            return false;
        }
        
        playerData.recordBlockBreak(currentTime);
        return true;
    }
    
    /**
     * Checks if a player can place a block without triggering anti-cheat
     */
    public boolean canPlaceBlock(ServerPlayerEntity player, BlockPos blockPos) {
        if (isPlayerInCreativeMode(player)) {
            return true;
        }
        
        PlayerActionData playerData = getOrCreatePlayerData(player.getUuid());
        long currentTime = System.currentTimeMillis();
        
        if (!isWithinReachDistance(player, blockPos, playerData, "block place")) {
            return false;
        }
        
        if (detectsImpossibleActionRate(playerData, currentTime, 1)) {
            recordViolation(playerData, player, "Impossible action rate during placement: " + 
                playerData.getRecentActions(currentTime) + " per second");
            return false;
        }
        
        playerData.recordGenericAction(currentTime);
        return true;
    }
    
    /**
     * Checks if a player can use an item without triggering anti-cheat
     */
    public boolean canUseItem(ServerPlayerEntity player, Item item, Hand hand, Entity targetEntity) {
        if (isPlayerInCreativeMode(player)) {
            return true;
        }
        
        PlayerActionData playerData = getOrCreatePlayerData(player.getUuid());
        long currentTime = System.currentTimeMillis();
        
        // More lenient rate limit for item usage
        if (detectsImpossibleActionRate(playerData, currentTime, ITEM_USE_RATE_MULTIPLIER)) {
            recordViolation(playerData, player, "Extreme item use exploit detected: " + 
                playerData.getRecentActions(currentTime) + " per second");
            return false;
        }
        
        playerData.recordGenericAction(currentTime);
        return true;
    }
    
    /**
     * Checks if a player can interact with a block without triggering anti-cheat
     */
    public boolean canInteractWithBlock(ServerPlayerEntity player, BlockPos blockPos, Block block) {
        if (isPlayerInCreativeMode(player)) {
            return true;
        }
        
        PlayerActionData playerData = getOrCreatePlayerData(player.getUuid());
        long currentTime = System.currentTimeMillis();
        
        if (!isWithinReachDistance(player, blockPos, playerData, "block interaction")) {
            return false;
        }
        
        playerData.recordGenericAction(currentTime);
        return true;
    }
    
    /**
     * Checks if a player can attack an entity without triggering anti-cheat
     */
    public boolean canAttack(ServerPlayerEntity player, Entity target) {
        if (isPlayerInCreativeMode(player)) {
            return true;
        }
        
        PlayerActionData playerData = getOrCreatePlayerData(player.getUuid());
        long currentTime = System.currentTimeMillis();
        
        // Validate reach for attacks
        if (target != null && !isWithinReachDistance(player, target.getBlockPos(), playerData, "attack")) {
            return false;
        }
        
        // Check for killaura pattern
        if (detectsKillAuraPattern(playerData, currentTime)) {
            recordViolation(playerData, player, "Killaura detected: " + 
                playerData.getRecentAttacks(currentTime) + " attacks per second");
            return false;
        }
        
        // Check for impossible attack speed
        if (detectsImpossibleAttackSpeed(playerData)) {
            recordViolation(playerData, player, "Impossible attack speed: " + 
                playerData.getTimeBetweenLastTwoAttacks() + "ms");
            return false;
        }
        
        playerData.recordAttack(currentTime);
        return true;
    }
    
    // ==================== ADMIN/DEBUG METHODS ====================
    
    public int getViolationCount(ServerPlayerEntity player) {
        PlayerActionData data = playerActionData.get(player.getUuid());
        return data != null ? data.getSevereViolationCount() : 0;
    }
    
    public void resetViolations(ServerPlayerEntity player) {
        PlayerActionData data = playerActionData.get(player.getUuid());
        if (data != null) {
            data.resetViolations();
        }
    }
    
    public String getPlayerStats(ServerPlayerEntity player) {
        PlayerActionData data = playerActionData.get(player.getUuid());
        if (data == null) {
            return "No data available";
        }
        
        long currentTime = System.currentTimeMillis();
        return String.format("Severe violations: %d, Recent blocks: %d, Recent attacks: %d, Recent actions: %d",
            data.getSevereViolationCount(),
            data.getRecentBlockBreaks(currentTime),
            data.getRecentAttacks(currentTime),
            data.getRecentActions(currentTime));
    }
    
    public void removePlayer(ServerPlayerEntity player) {
        playerActionData.remove(player.getUuid());
    }
    
    /**
     * Performs routine maintenance - should be called periodically
     */
    public void performMaintenance() {
        long currentTime = System.currentTimeMillis();
        
        if (shouldPerformMaintenance(currentTime)) {
            cleanupExpiredData(currentTime);
            decayViolations(currentTime);
            removeInactivePlayers(currentTime);
            lastMaintenanceTime = currentTime;
        }
    }
    
    // ==================== DETECTION HELPER METHODS ====================
    
    private boolean detectsNukerPattern(PlayerActionData playerData, long currentTime) {
        return playerData.getRecentBlockBreaks(currentTime) > NUKER_DETECTION_THRESHOLD;
    }
    
    private boolean detectsKillAuraPattern(PlayerActionData playerData, long currentTime) {
        return playerData.getRecentAttacks(currentTime) > KILLAURA_DETECTION_THRESHOLD;
    }
    
    private boolean detectsImpossibleBreakSpeed(PlayerActionData playerData) {
        long interval = playerData.getTimeBetweenLastTwoBlockBreaks();
        return interval < EXPLOIT_BREAK_SPEED_MS && interval > 0;
    }
    
    private boolean detectsImpossibleAttackSpeed(PlayerActionData playerData) {
        long interval = playerData.getTimeBetweenLastTwoAttacks();
        return interval < EXPLOIT_ATTACK_SPEED_MS && interval > 0;
    }
    
    private boolean detectsImpossibleActionRate(PlayerActionData playerData, long currentTime, int multiplier) {
        return playerData.getRecentActions(currentTime) > (EXPLOIT_ACTIONS_PER_SECOND * multiplier);
    }
    
    // ==================== UTILITY HELPER METHODS ====================
    
    private boolean isPlayerInCreativeMode(ServerPlayerEntity player) {
        try {
            return player.interactionManager.getGameMode() == GameMode.CREATIVE;
        } catch (Exception e) {
            return player.isCreative();
        }
    }
    
    private boolean isWithinReachDistance(ServerPlayerEntity player, BlockPos targetPos, 
                                        PlayerActionData playerData, String actionType) {
        if (targetPos == null) {
            return true; // Cannot validate without position
        }
        
        long currentTime = System.currentTimeMillis();
        
        // Update cached position if needed
        if (playerData.shouldUpdatePosition(currentTime)) {
            playerData.updateCachedPosition(player.getX(), player.getY(), player.getZ(), currentTime);
        }
        
        double distance = playerData.getDistanceToBlock(targetPos);
        if (distance > MAX_INTERACTION_DISTANCE_BLOCKS) {
            recordViolation(playerData, player, String.format("Obvious reach hack: %.1f blocks (%s)", 
                distance, actionType));
            return false;
        }
        
        return true;
    }
    
    private PlayerActionData getOrCreatePlayerData(UUID playerId) {
        return playerActionData.computeIfAbsent(playerId, k -> new PlayerActionData());
    }
    
    private void recordViolation(PlayerActionData playerData, ServerPlayerEntity player, String reason) {
        long currentTime = System.currentTimeMillis();
        playerData.recordSevereViolation(currentTime);
        
        logViolation(player, reason, playerData.getSevereViolationCount());
        notifyPlayer(player, playerData.getSevereViolationCount());
        
        if (playerData.hasExceededViolationThreshold()) {
            escalateViolation(player);
        }
    }
    
    private void logViolation(ServerPlayerEntity player, String reason, int totalViolations) {
        try {
            System.out.println(String.format("[AntiCheat] SEVERE VIOLATION by %s: %s (Total: %d)",
                player.getName().getString(), reason, totalViolations));
        } catch (Exception e) {
            System.out.println(String.format("[AntiCheat] SEVERE VIOLATION: %s (Total: %d)", 
                reason, totalViolations));
        }
    }
    
    private void notifyPlayer(ServerPlayerEntity player, int violationCount) {
        try {
            if (violationCount == 1) {
                player.sendMessage(Text.of("§c[AntiCheat] §cSevere cheat pattern detected!"), false);
            } else if (violationCount >= 3) {
                player.sendMessage(Text.of("§4[AntiCheat] §4Multiple severe violations - admin will be notified"), false);
            }
        } catch (Exception e) {
            // Ignore message sending errors - not critical
        }
    }
    
    private void escalateViolation(ServerPlayerEntity player) {
        try {
            System.out.println("[AntiCheat] CRITICAL: Player " + player.getName().getString() + 
                             " exceeded severe violation threshold - immediate attention required");
        } catch (Exception e) {
            System.out.println("[AntiCheat] CRITICAL: Player exceeded severe violation threshold");
        }
    }
    
    // ==================== MAINTENANCE HELPER METHODS ====================
    
    private boolean shouldPerformMaintenance(long currentTime) {
        return currentTime - lastMaintenanceTime > CLEANUP_INTERVAL_MS;
    }
    
    private void cleanupExpiredData(long currentTime) {
        playerActionData.values().forEach(data -> data.removeExpiredActions(currentTime));
    }
    
    private void decayViolations(long currentTime) {
        playerActionData.values().forEach(data -> {
            if (data.shouldDecayViolation(currentTime)) {
                data.decayOneViolation();
            }
        });
    }
    
    private void removeInactivePlayers(long currentTime) {
        playerActionData.entrySet().removeIf(entry -> entry.getValue().isPlayerInactive(currentTime));
    }
    
    // ==================== BACKWARDS COMPATIBILITY METHODS ====================
    
    public boolean canBreakBlock(ServerPlayerEntity player, BlockPos pos) {
        return canBreakBlock(player, pos, null);
    }
    
    public boolean canUseItem(ServerPlayerEntity player) {
        return canUseItem(player, null, Hand.MAIN_HAND, null);
    }
    
    public boolean canAttack(ServerPlayerEntity player) {
        return canAttack(player, null);
    }
}
