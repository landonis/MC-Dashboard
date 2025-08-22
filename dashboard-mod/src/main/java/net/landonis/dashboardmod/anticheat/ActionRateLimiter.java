package net.landonis.dashboardmod.anticheat;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.util.Hand;
import net.minecraft.world.GameMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified Action Rate Limiter - Focus on actual exploits only
 * Much more permissive, only catches obvious cheating patterns
 */
public class ActionRateLimiter {
    
    // VERY lenient thresholds - only catch obvious exploits
    private static final int EXPLOIT_ACTIONS_PER_SECOND = 100; // 100+ actions = clearly impossible
    private static final long EXPLOIT_BREAK_SPEED = 1; // Only flag sub-1ms breaking (impossible)
    private static final long EXPLOIT_ATTACK_SPEED = 20; // Only flag sub-20ms attacks (impossible)
    private static final double MAX_INTERACTION_DISTANCE = 8.0; // Very generous reach check
    
    // Only track severe violations
    private static final int MAX_SEVERE_VIOLATIONS = 10; // Much higher threshold
    private static final long VIOLATION_DECAY_TIME = 30000; // 30 seconds
    private static final long CLEANUP_INTERVAL = 300000; // 5 minutes
    
    // Pattern detection for obvious exploits only
    private static final int NUKER_THRESHOLD = 50; // 50+ blocks broken in 2 seconds
    private static final int KILLAURA_THRESHOLD = 20; // 20+ attacks in 2 seconds
    private static final long PATTERN_WINDOW = 2000; // 2 seconds
    
    private final Map<UUID, PlayerActionData> playerData = new ConcurrentHashMap<>();
    private long lastCleanup = System.currentTimeMillis();
    
    /**
     * Minimal tracking - only severe exploit patterns
     */
    private static class PlayerActionData {
        // Only track extreme patterns
        private final List<Long> blockBreaks = new ArrayList<>();
        private final List<Long> attacks = new ArrayList<>();
        private final List<Long> allActions = new ArrayList<>();
        
        // Position tracking for reach hacks only
        private double lastPlayerX = 0, lastPlayerY = 0, lastPlayerZ = 0;
        private long lastPositionUpdate = 0;
        
        // Severe violation tracking only
        private int severeViolations = 0;
        private long lastViolation = 0;
        
        void addBlockBreak(long timestamp) {
            blockBreaks.add(timestamp);
            allActions.add(timestamp);
            cleanOldActions(timestamp);
        }
        
        void addAttack(long timestamp) {
            attacks.add(timestamp);
            allActions.add(timestamp);
            cleanOldActions(timestamp);
        }
        
        void addAction(long timestamp) {
            allActions.add(timestamp);
            cleanOldActions(timestamp);
        }
        
        private void cleanOldActions(long currentTime) {
            long cutoff = currentTime - PATTERN_WINDOW;
            blockBreaks.removeIf(time -> time < cutoff);
            attacks.removeIf(time -> time < cutoff);
            allActions.removeIf(time -> time < cutoff);
        }
        
        int getRecentBlockBreaks() {
            return blockBreaks.size();
        }
        
        int getRecentAttacks() {
            return attacks.size();
        }
        
        int getRecentActions() {
            return allActions.size();
        }
        
        long getLastBlockBreakInterval() {
            if (blockBreaks.size() < 2) return Long.MAX_VALUE;
            return blockBreaks.get(blockBreaks.size() - 1) - blockBreaks.get(blockBreaks.size() - 2);
        }
        
        long getLastAttackInterval() {
            if (attacks.size() < 2) return Long.MAX_VALUE;
            return attacks.get(attacks.size() - 1) - attacks.get(attacks.size() - 2);
        }
    }
    
    /**
     * Creative mode bypass check
     */
    private boolean shouldBypassRateLimit(ServerPlayerEntity player) {
        try {
            return player.interactionManager.getGameMode() == GameMode.CREATIVE;
        } catch (Exception e) {
            return player.isCreative();
        }
    }
    
    /**
     * Basic reach check - only flag obvious reach hacks
     */
    private boolean validateReach(ServerPlayerEntity player, BlockPos targetPos, PlayerActionData data, String actionType) {
        if (targetPos == null) return true; // Can't validate without position
        
        long currentTime = System.currentTimeMillis();
        
        // Update player position occasionally
        if (currentTime - data.lastPositionUpdate > 500) { // Every 500ms
            data.lastPlayerX = player.getX();
            data.lastPlayerY = player.getY();
            data.lastPlayerZ = player.getZ();
            data.lastPositionUpdate = currentTime;
        }
        
        // Very generous reach check - only catch obvious reach hacks
        double distance = Math.sqrt(
            Math.pow(targetPos.getX() - data.lastPlayerX, 2) +
            Math.pow(targetPos.getY() - data.lastPlayerY, 2) +
            Math.pow(targetPos.getZ() - data.lastPlayerZ, 2)
        );
        
        if (distance > MAX_INTERACTION_DISTANCE) {
            recordSevereViolation(data, player, "Obvious reach hack: " + String.format("%.1f", distance) + " blocks");
            return false;
        }
        
        return true;
    }
    
    /**
     * Block breaking - only catch obvious nuker/speed hacks
     */
    public boolean canBreakBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        // Creative bypass
        if (shouldBypassRateLimit(player)) return true;
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Basic reach check
        if (!validateReach(player, pos, data, "block break")) return false;
        
        // Check for obvious nuker pattern
        if (data.getRecentBlockBreaks() > NUKER_THRESHOLD) {
            recordSevereViolation(data, player, "Nuker detected: " + data.getRecentBlockBreaks() + " blocks in 2 seconds");
            return false;
        }
        
        // Check for impossible break speed (only sub-1ms)
        long interval = data.getLastBlockBreakInterval();
        if (interval < EXPLOIT_BREAK_SPEED) {
            recordSevereViolation(data, player, "Impossible break speed: " + interval + "ms");
            return false;
        }
        
        // Check for impossible action rate (100+ per second is clearly impossible)
        if (data.getRecentActions() > EXPLOIT_ACTIONS_PER_SECOND) {
            recordSevereViolation(data, player, "Impossible action rate: " + data.getRecentActions() + " actions in 2 seconds");
            return false;
        }
        
        data.addBlockBreak(currentTime);
        return true;
    }
    
    /**
     * Block placing - very lenient, only catch obvious exploits
     */
    public boolean canPlaceBlock(ServerPlayerEntity player, BlockPos pos) {
        // Creative bypass
        if (shouldBypassRateLimit(player)) return true;
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Basic reach check
        if (!validateReach(player, pos, data, "block place")) return false;
        
        // Only check for impossible action rates
        if (data.getRecentActions() > EXPLOIT_ACTIONS_PER_SECOND) {
            recordSevereViolation(data, player, "Impossible action rate during placement");
            return false;
        }
        
        data.addAction(currentTime);
        return true;
    }
    
    /**
     * Item usage - very permissive, no micromanagement
     */
    public boolean canUseItem(ServerPlayerEntity player, Item item, Hand hand, Entity targetEntity) {
        // Creative bypass
        if (shouldBypassRateLimit(player)) return true;
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Only check for absolutely impossible action rates
        if (data.getRecentActions() > EXPLOIT_ACTIONS_PER_SECOND * 2) { // Even more lenient for items
            recordSevereViolation(data, player, "Extreme item use exploit detected");
            return false;
        }
        
        data.addAction(currentTime);
        return true;
    }
    
    /**
     * Block interactions - no restrictions, just track for patterns
     */
    public boolean canInteractWithBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        // Creative bypass
        if (shouldBypassRateLimit(player)) return true;
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Basic reach check only
        if (!validateReach(player, pos, data, "block interaction")) return false;
        
        // No other restrictions - let players interact freely
        data.addAction(currentTime);
        return true;
    }
    
    /**
     * Attack checking - only catch obvious killaura
     */
    public boolean canAttack(ServerPlayerEntity player, Entity target) {
        // Creative bypass
        if (shouldBypassRateLimit(player)) return true;
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Reach check for attacks
        if (target != null) {
            BlockPos targetPos = target.getBlockPos();
            if (!validateReach(player, targetPos, data, "attack")) return false;
        }
        
        // Check for obvious killaura pattern
        if (data.getRecentAttacks() > KILLAURA_THRESHOLD) {
            recordSevereViolation(data, player, "Killaura detected: " + data.getRecentAttacks() + " attacks in 2 seconds");
            return false;
        }
        
        // Check for impossible attack speed (only sub-20ms)
        long interval = data.getLastAttackInterval();
        if (interval < EXPLOIT_ATTACK_SPEED) {
            recordSevereViolation(data, player, "Impossible attack speed: " + interval + "ms");
            return false;
        }
        
        data.addAttack(currentTime);
        return true;
    }
    
    /**
     * Record only severe violations
     */
    private void recordSevereViolation(PlayerActionData data, ServerPlayerEntity player, String reason) {
        data.severeViolations++;
        data.lastViolation = System.currentTimeMillis();
        
        try {
            System.out.println("[AntiCheat] SEVERE VIOLATION by " + player.getName().getString() + 
                              ": " + reason + " (Total: " + data.severeViolations + ")");
        } catch (Exception e) {
            System.out.println("[AntiCheat] SEVERE VIOLATION: " + reason + 
                              " (Total: " + data.severeViolations + ")");
        }
        
        // Only send messages for severe violations
        try {
            if (data.severeViolations == 1) {
                player.sendMessage(net.minecraft.text.Text.of("§c[AntiCheat] §cSevere cheat pattern detected!"), false);
            } else if (data.severeViolations >= 3) {
                player.sendMessage(net.minecraft.text.Text.of("§4[AntiCheat] §4Multiple severe violations - admin will be notified"), false);
            }
        } catch (Exception e) {
            // Ignore message sending errors
        }
        
        // Escalate only for multiple severe violations
        if (data.severeViolations > MAX_SEVERE_VIOLATIONS) {
            try {
                System.out.println("[AntiCheat] CRITICAL: Player " + player.getName().getString() + 
                                 " exceeded severe violation threshold - immediate attention required");
            } catch (Exception e) {
                System.out.println("[AntiCheat] CRITICAL: Player exceeded severe violation threshold");
            }
        }
    }
    
    private PlayerActionData getPlayerData(UUID playerId) {
        return playerData.computeIfAbsent(playerId, k -> new PlayerActionData());
    }
    
    public int getViolationCount(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        return data != null ? data.severeViolations : 0;
    }
    
    public void resetViolations(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        if (data != null) {
            data.severeViolations = 0;
            data.lastViolation = 0;
        }
    }
    
    public void performMaintenance() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            // Decay severe violations slowly
            playerData.values().forEach(data -> {
                if (currentTime - data.lastViolation > VIOLATION_DECAY_TIME) {
                    data.severeViolations = Math.max(0, data.severeViolations - 1);
                }
            });
            
            // Clean up inactive players
            long cutoffTime = currentTime - CLEANUP_INTERVAL;
            playerData.entrySet().removeIf(entry -> {
                PlayerActionData data = entry.getValue();
                return data.allActions.isEmpty() || 
                       (!data.allActions.isEmpty() && 
                        data.allActions.get(data.allActions.size() - 1) < cutoffTime);
            });
            
            lastCleanup = currentTime;
        }
    }
    
    public void removePlayer(ServerPlayerEntity player) {
        playerData.remove(player.getUuid());
    }
    
    public String getPlayerStats(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        if (data == null) return "No data";
        
        return String.format("Severe violations: %d, Recent blocks: %d, Recent attacks: %d, Recent actions: %d",
            data.severeViolations,
            data.getRecentBlockBreaks(),
            data.getRecentAttacks(),
            data.getRecentActions());
    }
    
    // Backwards compatibility methods
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
