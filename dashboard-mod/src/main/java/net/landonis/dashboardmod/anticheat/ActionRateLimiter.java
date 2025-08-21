package net.landonis.dashboardmod.anticheat;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.Block;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapDoorBlock;
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
public class EnhancedActionRateLimiter {
    
    // Base cooldowns (more lenient for context-aware detection)
    private static final long BLOCK_BREAK_COOLDOWN = 20;
    private static final long BLOCK_PLACE_COOLDOWN = 30;
    private static final long ITEM_USE_COOLDOWN = 50;
    private static final long ATTACK_COOLDOWN = 150;
    private static final int MAX_ACTIONS_PER_SECOND = 40;
    
    // Context-specific allowances
    private static final long DOOR_INTERACTION_COOLDOWN = 10; // Very fast door opening allowed
    private static final long ANIMAL_FEEDING_COOLDOWN = 25; // Fast animal feeding allowed
    private static final int MAX_SEQUENTIAL_FEEDS = 20; // Max animals fed in sequence
    private static final long FEED_SEQUENCE_TIMEOUT = 5000; // 5 seconds
    
    // Violation thresholds
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 20;
    private static final long VIOLATION_DECAY_TIME = 45000; // 45 seconds
    private static final long CLEANUP_INTERVAL = 300000; // 5 minutes
    
    // Suspicious pattern detection
    private static final int SUSPICIOUS_RAPID_ACTIONS = 50; // Actions per second that's clearly suspicious
    private static final long PATTERN_DETECTION_WINDOW = 3000; // 3 seconds
    
    private final Map<UUID, PlayerActionData> playerData = new ConcurrentHashMap<>();
    private final Set<Item> ANIMAL_FOOD = Set.of(
        Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT,
        Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS,
        Items.PUMPKIN_SEEDS, Items.SWEET_BERRIES, Items.HAY_BLOCK,
        Items.APPLE, Items.GOLDEN_APPLE, Items.GOLDEN_CARROT
    );
    private long lastCleanup = System.currentTimeMillis();
    
    private static class PlayerActionData {
        // Basic action tracking
        private long lastBlockBreak = 0;
        private long lastBlockPlace = 0;
        private long lastItemUse = 0;
        private long lastAttack = 0;
        
        // Context-aware tracking
        private long lastDoorInteraction = 0;
        private long lastAnimalFeed = 0;
        private int sequentialFeeds = 0;
        private long feedSequenceStart = 0;
        private BlockPos lastDoorPos = null;
        
        // Efficient action tracking (circular buffer instead of queue)
        private final long[] recentActionTimes = new long[60]; // Store last 60 actions
        private int actionIndex = 0;
        private int actionCount = 0;
        
        // Violation tracking
        private int violationCount = 0;
        private long lastViolation = 0;
        private int suspiciousPatternCount = 0;
        
        // Performance optimization
        private long lastMaintenanceCheck = 0;
        
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
                    break; // Actions are in chronological order
                }
                index = (index - 1 + recentActionTimes.length) % recentActionTimes.length;
            }
            return count;
        }
    }
    
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
        if (isHarvestableBlock(block)) {
            cooldown = BLOCK_BREAK_COOLDOWN / 2;
        }
        
        if (currentTime - data.lastBlockBreak < cooldown) {
            recordViolation(data, player, "Block break too fast");
            return false;
        }
        
        if (!checkGeneralActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second");
            return false;
        }
        
        data.lastBlockBreak = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    public boolean canPlaceBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid block placing pattern detected");
            return false;
        }
        
        if (currentTime - data.lastBlockPlace < BLOCK_PLACE_COOLDOWN) {
            recordViolation(data, player, "Block place too fast");
            return false;
        }
        
        if (!checkGeneralActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second");
            return false;
        }
        
        data.lastBlockPlace = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    public boolean canUseItem(ServerPlayerEntity player, Item item, Hand hand, Entity targetEntity) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Context-aware checking for animal feeding
        if (targetEntity instanceof AnimalEntity && ANIMAL_FOOD.contains(item)) {
            return handleAnimalFeeding(data, player, currentTime);
        }
        
        // Check for suspicious patterns
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid item use pattern detected");
            return false;
        }
        
        if (currentTime - data.lastItemUse < ITEM_USE_COOLDOWN) {
            recordViolation(data, player, "Item use too fast");
            return false;
        }
        
        if (!checkGeneralActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second");
            return false;
        }
        
        data.lastItemUse = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    public boolean canInteractWithBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Special handling for doors, trapdoors, and fence gates
        if (isDoorLikeBlock(block)) {
            return handleDoorInteraction(data, player, pos, currentTime);
        }
        
        // Default item use logic for other blocks
        return canUseItem(player, player.getMainHandStack().getItem(), Hand.MAIN_HAND, null);
    }
    
    public boolean canAttack(ServerPlayerEntity player, Entity target) {
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // More lenient for PvE, stricter for PvP
        long cooldown = (target instanceof ServerPlayerEntity) ? ATTACK_COOLDOWN : ATTACK_COOLDOWN / 2;
        
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid attack pattern detected");
            return false;
        }
        
        if (currentTime - data.lastAttack < cooldown) {
            recordViolation(data, player, "Attack too fast");
            return false;
        }
        
        if (!checkGeneralActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second");
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
            data.feedSequenceStart = currentTime;
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
        
        // Same door - allow reasonable interaction speed
        if (currentTime - data.lastDoorInteraction < DOOR_INTERACTION_COOLDOWN) {
            // Only warn, don't block door interactions completely
            if (data.violationCount < 3) {
                recordViolation(data, player, "Very rapid door interaction");
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
    
    private boolean checkGeneralActionRate(PlayerActionData data, long currentTime) {
        return data.countRecentActions(currentTime, 1000) < MAX_ACTIONS_PER_SECOND;
    }
    
    private boolean isDoorLikeBlock(Block block) {
        return block instanceof DoorBlock || block instanceof TrapDoorBlock || block instanceof FenceGateBlock;
    }
    
    private boolean isHarvestableBlock(Block block) {
        String blockName = block.getTranslationKey().toLowerCase();
        return blockName.contains("crop") || blockName.contains("wheat") || 
               blockName.contains("carrot") || blockName.contains("potato") ||
               blockName.contains("beetroot") || blockName.contains("berry");
    }
    
    private PlayerActionData getPlayerData(UUID playerId) {
        return playerData.computeIfAbsent(playerId, k -> new PlayerActionData());
    }
    
    private void recordViolation(PlayerActionData data, ServerPlayerEntity player, String reason) {
        data.violationCount++;
        data.lastViolation = System.currentTimeMillis();
        
        try {
            System.out.println("[AntiCheat] Rate limit violation by " + player.getName().getString() + 
                              ": " + reason + " (Total: " + data.violationCount + ")");
        } catch (Exception e) {
            System.out.println("[AntiCheat] Rate limit violation: " + reason);
        }
        
        // Progressive warnings
        try {
            if (data.violationCount == 5) {
                player.sendMessage(net.minecraft.text.Text.of("§6[AntiCheat] §eSlowing down - detected rapid actions"));
            } else if (data.violationCount == 12) {
                player.sendMessage(net.minecraft.text.Text.of("§c[AntiCheat] §cWarning: Suspicious activity detected"));
            }
        } catch (Exception e) {
            // Ignore message errors
        }
        
        if (data.violationCount > MAX_VIOLATIONS_BEFORE_KICK) {
            try {
                System.out.println("[AntiCheat] Player " + player.getName().getString() + 
                                 " exceeded violation threshold - consider review");
            } catch (Exception e) {
                System.out.println("[AntiCheat] Player exceeded violation threshold");
            }
        }
    }
    
    public int getViolationCount(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        return data != null ? data.violationCount : 0;
    }
    
    public void resetViolations(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        if (data != null) {
            data.violationCount = 0;
            data.lastViolation = 0;
            data.suspiciousPatternCount = 0;
        }
    }
    
    // Optimized maintenance - only run when needed
    public void performMaintenance() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            // Efficient violation decay
            playerData.values().parallelStream().forEach(data -> {
                if (currentTime - data.lastViolation > VIOLATION_DECAY_TIME) {
                    data.violationCount = Math.max(0, data.violationCount - 2); // Faster decay
                    data.suspiciousPatternCount = Math.max(0, data.suspiciousPatternCount - 1);
                }
            });
            
            // Remove inactive players
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
    
    public void removePlayer(ServerPlayerEntity player) {
        playerData.remove(player.getUuid());
    }
    
    // Debug methods for admins
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
