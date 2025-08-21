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
import net.minecraft.util.Hand;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced Action Rate Limiter for Minecraft Fabric Anticheat
 * Context-aware detection that differentiates between legitimate and suspicious actions
 */
public class ActionRateLimiter {
    
    // Base cooldowns (realistic for legitimate gameplay)
    private static final long BLOCK_BREAK_COOLDOWN = 50; // 50ms = 20 blocks/second max
    private static final long BLOCK_PLACE_COOLDOWN = 75; // 75ms = 13 blocks/second max  
    private static final long ITEM_USE_COOLDOWN = 100; // 100ms = 10 items/second max
    private static final long ATTACK_COOLDOWN = 75; // Much more lenient - 75ms = 13 attacks/second max
    private static final int MAX_ACTIONS_PER_SECOND = 25; // More conservative overall limit
    
    // Context-specific allowances (account for 0-4ms natural timing)
    private static final long DOOR_INTERACTION_COOLDOWN = 50; // Allow reasonable door speed
    private static final long ANIMAL_FEEDING_COOLDOWN = 100; // 100ms between feeds is very fast but fair
    private static final int MAX_SEQUENTIAL_FEEDS = 20; // Allow feeding more animals
    private static final long FEED_SEQUENCE_TIMEOUT = 8000; // 8 seconds for feeding session
    
    // Violation thresholds (more forgiving)
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 25; // Higher threshold
    private static final long VIOLATION_DECAY_TIME = 20000; // 20 seconds faster decay
    private static final long CLEANUP_INTERVAL = 300000; // 5 minutes
    
    // Suspicious pattern detection (target actual cheats, not fast legitimate play)
    private static final int SUSPICIOUS_RAPID_ACTIONS = 60; // 60 actions in 3 seconds = clearly impossible
    private static final long PATTERN_DETECTION_WINDOW = 3000; // 3 seconds
    
    // Player tracking data
    private final Map<UUID, PlayerActionData> playerData = new ConcurrentHashMap<>();
    private final Set<Item> ANIMAL_FOOD = Set.of(
        Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT,
        Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS,
        Items.PUMPKIN_SEEDS, Items.SWEET_BERRIES, Items.HAY_BLOCK,
        Items.APPLE, Items.GOLDEN_APPLE, Items.GOLDEN_CARROT
    );
    private long lastCleanup = System.currentTimeMillis();
    
    /**
     * Internal class to track player action history
     */
    private static class PlayerActionData {
        // Basic action tracking (keep original structure for compatibility)
        private long lastBlockBreak = 0;
        private long lastBlockPlace = 0;
        private long lastItemUse = 0;
        private long lastAttack = 0;
        
        // Enhanced context-aware tracking
        private long lastDoorInteraction = 0;
        private long lastAnimalFeed = 0;
        private int sequentialFeeds = 0;
        private BlockPos lastDoorPos = null;
        
        // Efficient action tracking (replacing the old Queue)
        private final long[] recentActionTimes = new long[50];
        private int actionIndex = 0;
        private int actionCount = 0;
        
        // Violation tracking (keep original structure)
        private int violationCount = 0;
        private long lastViolation = 0;
        private int suspiciousPatternCount = 0;
        
        void addAction(long timestamp) {
            recentActionTimes[actionIndex] = timestamp;
            actionIndex = (actionIndex + 1) % recentActionTimes.length;
            if (actionCount < recentActionTimes.length) {
                actionCount++;
            }
        }
        
        int countRecentActions(long currentTime, long windowMs) {
            long cutoff = currentTime - windowMs;
            int count = 0;
            int index = (actionIndex - 1 + recentActionTimes.length) % recentActionTimes.length;
            
            for (int i = 0; i < actionCount; i++) {
                if (recentActionTimes[index] >= cutoff) {
                    count++;
                } else {
                    break;
                }
                index = (index - 1 + recentActionTimes.length) % recentActionTimes.length;
            }
            return count;
        }
    }
    
    /**
     * Check if a block break action is allowed - Enhanced version
     */
    public boolean canBreakBlock(ServerPlayerEntity player, BlockPos pos) {
        return canBreakBlock(player, pos, null);
    }
    
    /**
     * Check if a block break action is allowed with block context
     */
    public boolean canBreakBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Check for suspicious rapid breaking first
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid block breaking pattern detected");
            return false;
        }
        
        long cooldown = BLOCK_BREAK_COOLDOWN;
        
        // Allow faster breaking for certain blocks (like crops)
        if (block != null && isHarvestableBlock(block)) {
            cooldown = 25; // Fixed 25ms for crops regardless of base cooldown
        }
        
        // Check cooldown
        if (currentTime - data.lastBlockBreak < cooldown) {
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
        data.addAction(currentTime);
        
        return true;
    }
    
    /**
     * Check if a block place action is allowed
     */
    public boolean canPlaceBlock(ServerPlayerEntity player, BlockPos pos) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid block placing pattern detected");
            return false;
        }
        
        if (currentTime - data.lastBlockPlace < BLOCK_PLACE_COOLDOWN) {
            recordViolation(data, player, "Block place too fast: " + (currentTime - data.lastBlockPlace) + "ms");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second: " + countRecentActions(data, currentTime));
            return false;
        }
        
        data.lastBlockPlace = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    /**
     * Check if an item use action is allowed - Original method for compatibility
     */
    public boolean canUseItem(ServerPlayerEntity player) {
        return canUseItem(player, null, Hand.MAIN_HAND, null);
    }
    
    /**
     * Check if an item use action is allowed with context
     */
    public boolean canUseItem(ServerPlayerEntity player, Item item, Hand hand, Entity targetEntity) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Context-aware checking for animal feeding
        if (targetEntity instanceof AnimalEntity && item != null && ANIMAL_FOOD.contains(item)) {
            return handleAnimalFeeding(data, player, currentTime);
        }
        
        // Check for suspicious patterns
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid item use pattern detected");
            return false;
        }
        
        if (currentTime - data.lastItemUse < ITEM_USE_COOLDOWN) {
            recordViolation(data, player, "Item use too fast: " + (currentTime - data.lastItemUse) + "ms");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second: " + countRecentActions(data, currentTime));
            return false;
        }
        
        data.lastItemUse = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    /**
     * Check if a block interaction is allowed (doors, etc.)
     */
    public boolean canInteractWithBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Special handling for doors, trapdoors, and fence gates
        if (isDoorLikeBlock(block)) {
            return handleDoorInteraction(data, player, pos, currentTime);
        }
        
        // Default to item use logic for other blocks
        return canUseItem(player);
    }
    
    /**
     * Check if an attack action is allowed - Original method for compatibility
     */
    public boolean canAttack(ServerPlayerEntity player) {
        return canAttack(player, null);
    }
    
    /**
     * Check if an attack action is allowed with target context
     */
    public boolean canAttack(ServerPlayerEntity player, Entity target) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // More lenient for PvE, slightly stricter for PvP
        long cooldown = (target instanceof ServerPlayerEntity) ? 100 : 50; // 100ms PvP, 50ms PvE
        
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid attack pattern detected");
            return false;
        }
        
        if (currentTime - data.lastAttack < cooldown) {
            recordViolation(data, player, "Attack too fast: " + (currentTime - data.lastAttack) + "ms");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second: " + countRecentActions(data, currentTime));
            return false;
        }
        
        data.lastAttack = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    private boolean handleAnimalFeeding(PlayerActionData data, ServerPlayerEntity player, long currentTime) {
        // Reset sequence if it's been too long since last feed
        if (currentTime - data.lastAnimalFeed > FEED_SEQUENCE_TIMEOUT) {
            data.sequentialFeeds = 0;
        }
        
        // Allow rapid feeding up to a reasonable limit
        if (data.sequentialFeeds >= MAX_SEQUENTIAL_FEEDS) {
            recordViolation(data, player, "Excessive animal feeding: " + data.sequentialFeeds + " animals");
            return false;
        }
        
        if (currentTime - data.lastAnimalFeed < ANIMAL_FEEDING_COOLDOWN) {
            recordViolation(data, player, "Animal feeding too fast");
            return false;
        }
        
        data.lastAnimalFeed = currentTime;
        data.sequentialFeeds++;
        data.addAction(currentTime);
        
        return true;
    }
    
    private boolean handleDoorInteraction(PlayerActionData data, ServerPlayerEntity player, BlockPos pos, long currentTime) {
        // Allow very rapid door interactions, but track position to prevent abuse
        boolean sameDoor = pos.equals(data.lastDoorPos);
        
        if (!sameDoor) {
            data.lastDoorPos = pos;
            data.lastDoorInteraction = currentTime;
            data.addAction(currentTime);
            return true;
        }
        
        // Same door - allow reasonable interaction speed but warn if too fast
        if (currentTime - data.lastDoorInteraction < DOOR_INTERACTION_COOLDOWN) {
            // Only warn for excessive door spam, but still allow the action
            if (currentTime - data.lastDoorInteraction < 25) { // Only trigger if under 25ms
                recordViolation(data, player, "Very rapid door interaction: " + (currentTime - data.lastDoorInteraction) + "ms");
            }
            return true; // Still allow the action
        }
        
        data.lastDoorInteraction = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    private boolean detectSuspiciousPattern(PlayerActionData data, long currentTime) {
        int recentActions = data.countRecentActions(currentTime, PATTERN_DETECTION_WINDOW);
        
        if (recentActions > SUSPICIOUS_RAPID_ACTIONS) {
            data.suspiciousPatternCount++;
            return data.suspiciousPatternCount > 2; // Allow some false positives
        }
        
        return false;
    }
    
    private boolean isDoorLikeBlock(Block block) {
        return block instanceof DoorBlock || block instanceof TrapdoorBlock || block instanceof FenceGateBlock;
    }
    
    private boolean isHarvestableBlock(Block block) {
        String blockName = block.getTranslationKey().toLowerCase();
        return blockName.contains("crop") || blockName.contains("wheat") || 
               blockName.contains("carrot") || blockName.contains("potato") ||
               blockName.contains("beetroot") || blockName.contains("berry");
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
        return data.countRecentActions(currentTime, 1000);
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
                player.sendMessage(net.minecraft.text.Text.of("§6[AntiCheat] §eSlowing down actions to prevent violations"), false);
            } else if (data.violationCount == 8) {
                player.sendMessage(net.minecraft.text.Text.of("§c[AntiCheat] §cToo many rapid actions detected"), false);
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
            data.suspiciousPatternCount = 0;
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
                    data.suspiciousPatternCount = Math.max(0, data.suspiciousPatternCount - 1);
                    data.lastViolation = currentTime; // Reset timer
                }
            });
            
            // Remove data for players who haven't been active recently
            long cutoffTime = currentTime - CLEANUP_INTERVAL;
            playerData.entrySet().removeIf(entry -> {
                PlayerActionData data = entry.getValue();
                if (data.actionCount == 0) return true;
                
                long lastActivity = data.recentActionTimes[(data.actionIndex - 1 + data.recentActionTimes.length) % data.recentActionTimes.length];
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
    
    /**
     * Debug method for admins to get player stats
     */
    public String getPlayerStats(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        if (data == null) return "No data";
        
        long currentTime = System.currentTimeMillis();
        return String.format("Violations: %d, Recent actions: %d/s, Sequential feeds: %d, Suspicious patterns: %d",
            data.violationCount,
            data.countRecentActions(currentTime, 1000),
            data.sequentialFeeds,
            data.suspiciousPatternCount);
    }
}
