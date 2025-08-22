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
 * Enhanced Action Rate Limiter with Position Tracking and Creative Bypass
 * Context-aware detection with distance-based cheat detection
 */
public class ActionRateLimiter {
    
    // Base cooldowns (very lenient to account for natural timing variations)
    private static final long BLOCK_BREAK_COOLDOWN = 8;
    private static final long BLOCK_PLACE_COOLDOWN = 15;
    private static final long ITEM_USE_COOLDOWN = 25;
    private static final long ATTACK_COOLDOWN = 75;
    private static final int MAX_ACTIONS_PER_SECOND = 30;
    
    // Enhanced cooldowns for specific items
    private static final long BUCKET_USE_COOLDOWN = 8;
    private static final long CONSTRUCTION_ITEM_COOLDOWN = 10;
    private static final int MAX_SEQUENTIAL_BUCKET_USES = 50;
    private static final long BUCKET_SEQUENCE_TIMEOUT = 15000;
    
    // Context-specific allowances
    private static final long DOOR_INTERACTION_COOLDOWN = 5;
    private static final long ANIMAL_FEEDING_COOLDOWN = 15;
    private static final int MAX_SEQUENTIAL_FEEDS = 25;
    private static final long FEED_SEQUENCE_TIMEOUT = 10000;
    
    // Position-based cheat detection
    private static final double MAX_INTERACTION_DISTANCE = 6.0; // Vanilla reach is ~4.5, add buffer
    private static final double MAX_TELEPORT_DISTANCE = 8.0; // Detect impossible movement
    private static final long POSITION_CHECK_INTERVAL = 100; // Check every 100ms
    private static final int MAX_DISTANCE_VIOLATIONS = 5; // Before escalating punishment
    
    // Violation thresholds
    private static final int MAX_VIOLATIONS_BEFORE_KICK = 50;
    private static final long VIOLATION_DECAY_TIME = 15000;
    private static final long CLEANUP_INTERVAL = 300000;
    
    // Burst tolerance
    private static final int MAX_INSTANT_ACTIONS_PER_BURST = 8;
    private static final long BURST_WINDOW = 2000;
    private static final int VIOLATION_THRESHOLD_FOR_WARNING = 5;
    
    // Suspicious pattern detection
    private static final int SUSPICIOUS_RAPID_ACTIONS = 100;
    private static final long PATTERN_DETECTION_WINDOW = 2000;
    
    // Player tracking data
    private final Map<UUID, PlayerActionData> playerData = new ConcurrentHashMap<>();
    private final Set<Item> ANIMAL_FOOD = Set.of(
        Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT,
        Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS,
        Items.PUMPKIN_SEEDS, Items.SWEET_BERRIES, Items.HAY_BLOCK,
        Items.APPLE, Items.GOLDEN_APPLE, Items.GOLDEN_CARROT
    );
    
    private final Set<Item> CONSTRUCTION_ITEMS = Set.of(
        Items.LAVA_BUCKET, Items.WATER_BUCKET, Items.BUCKET,
        Items.POWDER_SNOW_BUCKET, Items.COD_BUCKET, Items.SALMON_BUCKET,
        Items.TROPICAL_FISH_BUCKET, Items.PUFFERFISH_BUCKET, Items.AXOLOTL_BUCKET,
        Items.TADPOLE_BUCKET
    );
    
    private long lastCleanup = System.currentTimeMillis();
    
    /**
     * Internal class to track player action history with position data
     */
    private static class PlayerActionData {
        // Basic action tracking
        private long lastBlockBreak = 0;
        private long lastBlockPlace = 0;
        private long lastItemUse = 0;
        private long lastAttack = 0;
        
        // Enhanced context-aware tracking
        private long lastDoorInteraction = 0;
        private long lastAnimalFeed = 0;
        private int sequentialFeeds = 0;
        private BlockPos lastDoorPos = null;
        
        // Bucket-specific tracking
        private long lastBucketUse = 0;
        private int sequentialBucketUses = 0;
        private BlockPos lastBucketPos = null;
        
        // Position tracking for cheat detection
        private BlockPos lastActionPos = null;
        private long lastPositionCheck = 0;
        private double lastPlayerX = 0, lastPlayerY = 0, lastPlayerZ = 0;
        private int distanceViolations = 0;
        
        // Efficient action tracking
        private final long[] recentActionTimes = new long[50];
        private int actionIndex = 0;
        private int actionCount = 0;
        
        // Violation tracking
        private int violationCount = 0;
        private long lastViolation = 0;
        private int suspiciousPatternCount = 0;
        
        // Burst tolerance tracking
        private final long[] instantActionTimes = new long[10];
        private int instantActionIndex = 0;
        private int instantActionCount = 0;
        
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
        
        void addInstantAction(long timestamp) {
            instantActionTimes[instantActionIndex] = timestamp;
            instantActionIndex = (instantActionIndex + 1) % instantActionTimes.length;
            if (instantActionCount < instantActionTimes.length) {
                instantActionCount++;
            }
        }
        
        int countInstantActionsInWindow(long currentTime, long windowMs) {
            long cutoff = currentTime - windowMs;
            int count = 0;
            for (int i = 0; i < instantActionCount; i++) {
                if (instantActionTimes[i] >= cutoff) {
                    count++;
                }
            }
            return count;
        }
    }
    
    /**
     * Creative mode bypass check - returns true if player should skip all rate limiting
     */
    private boolean shouldBypassRateLimit(ServerPlayerEntity player) {
        try {
            return player.interactionManager.getGameMode() == GameMode.CREATIVE;
        } catch (Exception e) {
            // Fallback check if interactionManager is not accessible
            return player.isCreative();
        }
    }
    
    /**
     * Enhanced position-based cheat detection
     */
    private boolean validatePosition(ServerPlayerEntity player, BlockPos targetPos, PlayerActionData data, String actionType) {
        long currentTime = System.currentTimeMillis();
        
        // Update player position tracking
        if (currentTime - data.lastPositionCheck > POSITION_CHECK_INTERVAL) {
            data.lastPlayerX = player.getX();
            data.lastPlayerY = player.getY();
            data.lastPlayerZ = player.getZ();
            data.lastPositionCheck = currentTime;
        }
        
        // Check interaction distance
        if (targetPos != null) {
            double distance = Math.sqrt(
                Math.pow(targetPos.getX() - data.lastPlayerX, 2) +
                Math.pow(targetPos.getY() - data.lastPlayerY, 2) +
                Math.pow(targetPos.getZ() - data.lastPlayerZ, 2)
            );
            
            if (distance > MAX_INTERACTION_DISTANCE) {
                data.distanceViolations++;
                recordViolation(data, player, actionType + " too far away: " + String.format("%.2f", distance) + " blocks");
                
                // Escalate punishment for repeated distance violations
                if (data.distanceViolations > MAX_DISTANCE_VIOLATIONS) {
                    recordViolation(data, player, "Repeated distance violations: " + data.distanceViolations);
                    return false;
                }
                return false;
            }
        }
        
        // Check for impossible teleportation
        if (data.lastActionPos != null && targetPos != null) {
            double teleportDistance = Math.sqrt(
                Math.pow(targetPos.getX() - data.lastActionPos.getX(), 2) +
                Math.pow(targetPos.getY() - data.lastActionPos.getY(), 2) +
                Math.pow(targetPos.getZ() - data.lastActionPos.getZ(), 2)
            );
            
            if (teleportDistance > MAX_TELEPORT_DISTANCE) {
                recordViolation(data, player, "Impossible " + actionType + " teleportation: " + 
                               String.format("%.2f", teleportDistance) + " blocks");
                return false;
            }
        }
        
        // Update last action position
        data.lastActionPos = targetPos;
        return true;
    }
    
    /**
     * Check if a block break action is allowed with position validation
     */
    public boolean canBreakBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        // Creative mode bypass
        if (shouldBypassRateLimit(player)) {
            return true;
        }
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Position-based validation first
        if (!validatePosition(player, pos, data, "block break")) {
            return false;
        }
        
        // Check for suspicious rapid breaking
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid block breaking pattern detected");
            return false;
        }
        
        long cooldown = BLOCK_BREAK_COOLDOWN;
        
        // Context-aware cooldowns
        if (block != null && isHarvestableBlock(block)) {
            cooldown = 3;
        } else if (block != null && isInstantBreakBlock(block)) {
            cooldown = 1;
        }
        
        if (currentTime - data.lastBlockBreak < cooldown) {
            recordViolation(data, player, "Block break too fast: " + (currentTime - data.lastBlockBreak) + "ms");
            return false;
        }
        
        if (!checkActionRate(data, currentTime)) {
            recordViolation(data, player, "Too many actions per second: " + countRecentActions(data, currentTime));
            return false;
        }
        
        data.lastBlockBreak = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    /**
     * Check if a block place action is allowed with position validation
     */
    public boolean canPlaceBlock(ServerPlayerEntity player, BlockPos pos) {
        // Creative mode bypass
        if (shouldBypassRateLimit(player)) {
            return true;
        }
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Position-based validation
        if (!validatePosition(player, pos, data, "block place")) {
            return false;
        }
        
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
     * Enhanced item use with bucket support and position validation
     */
    public boolean canUseItem(ServerPlayerEntity player, Item item, Hand hand, Entity targetEntity) {
        // Creative mode bypass
        if (shouldBypassRateLimit(player)) {
            return true;
        }
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Reset bucket sequence if using non-bucket items (like opening chests)
        if (item == null || !CONSTRUCTION_ITEMS.contains(item)) {
            resetBucketSequence(data, currentTime);
        }
        
        // Position validation for block-targeted item usage
        BlockPos targetPos = null;
        if (targetEntity == null) {
            // Try to get the block position the player is looking at
            try {
                var hitResult = player.raycast(MAX_INTERACTION_DISTANCE, 1.0f, false);
                if (hitResult.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
                    targetPos = ((net.minecraft.util.hit.BlockHitResult) hitResult).getBlockPos();
                }
            } catch (Exception e) {
                // Ignore raycast errors
            }
        }
        
        if (targetPos != null && !validatePosition(player, targetPos, data, "item use")) {
            return false;
        }
        
        // Special handling for buckets
        if (item != null && CONSTRUCTION_ITEMS.contains(item)) {
            return handleBucketUsage(data, player, item, targetPos, currentTime);
        }
        
        // Animal feeding context
        if (targetEntity instanceof AnimalEntity && item != null && ANIMAL_FOOD.contains(item)) {
            return handleAnimalFeeding(data, player, currentTime);
        }
        
        if (detectSuspiciousPattern(data, currentTime)) {
            recordViolation(data, player, "Suspicious rapid item use pattern detected");
            return false;
        }
        
        long cooldown = getItemUseCooldown(item);
        
        if (currentTime - data.lastItemUse < cooldown) {
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
     * Enhanced bucket usage with position tracking
     */
    private boolean handleBucketUsage(PlayerActionData data, ServerPlayerEntity player, Item item, BlockPos targetPos, long currentTime) {
        // Reset sequence if it's been too long or player moved significantly
        if (currentTime - data.lastBucketUse > BUCKET_SEQUENCE_TIMEOUT || 
            (data.lastBucketPos != null && targetPos != null && 
             data.lastBucketPos.getSquaredDistance(targetPos) > 64)) { // 8 block radius
            data.sequentialBucketUses = 0;
        }
        
        if (data.sequentialBucketUses >= MAX_SEQUENTIAL_BUCKET_USES) {
            recordViolation(data, player, "Excessive bucket usage: " + data.sequentialBucketUses + " uses");
            return false;
        }
        
        if (currentTime - data.lastBucketUse < BUCKET_USE_COOLDOWN) {
            if (currentTime - data.lastBucketUse < 5) {
                recordViolation(data, player, "Bucket use too fast: " + (currentTime - data.lastBucketUse) + "ms");
                return false;
            }
        }
        
        // Use separate action rate for buckets to avoid interfering with other actions
        int recentBucketActions = countRecentBucketActions(data, currentTime);
        if (recentBucketActions > MAX_ACTIONS_PER_SECOND) {
            recordViolation(data, player, "Too many bucket actions per second: " + recentBucketActions);
            return false;
        }
        
        data.lastBucketUse = currentTime;
        data.sequentialBucketUses++;
        data.lastBucketPos = targetPos;
        data.addAction(currentTime);
        
        return true;
    }
    
    /**
     * Helper method to reset bucket sequence when doing non-bucket actions
     */
    private void resetBucketSequence(PlayerActionData data, long currentTime) {
        // Only reset if it's been a reasonable time since last bucket use
        if (currentTime - data.lastBucketUse > 2000) { // 2 seconds
            data.sequentialBucketUses = 0;
            data.lastBucketPos = null;
        }
    }
    
    /**
     * Count recent bucket actions specifically (to separate from general actions)
     */
    private int countRecentBucketActions(PlayerActionData data, long currentTime) {
        if (data.sequentialBucketUses == 0) return 0;
        
        // Simple approximation: if we're in an active bucket sequence, estimate rate
        long timeSinceFirstBucket = currentTime - (data.lastBucketUse - (data.sequentialBucketUses * BUCKET_USE_COOLDOWN));
        if (timeSinceFirstBucket <= 1000) { // Within last second
            return data.sequentialBucketUses;
        }
        return 0;
    }
    
    /**
     * Enhanced block interaction with position validation
     */
    public boolean canInteractWithBlock(ServerPlayerEntity player, BlockPos pos, Block block) {
        // Creative mode bypass
        if (shouldBypassRateLimit(player)) {
            return true;
        }
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Reset bucket sequence when interacting with blocks (like chests, crafting tables)
        resetBucketSequence(data, currentTime);
        
        // Position validation
        if (!validatePosition(player, pos, data, "block interaction")) {
            return false;
        }
        
        if (isDoorLikeBlock(block)) {
            return handleDoorInteraction(data, player, pos, currentTime);
        }
        
        // Use a more lenient cooldown for block interactions (chests, etc.)
        if (currentTime - data.lastItemUse < 10) { // Very fast for block interactions
            recordViolation(data, player, "Block interaction too fast: " + (currentTime - data.lastItemUse) + "ms");
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
     * Enhanced attack with position validation for target
     */
    public boolean canAttack(ServerPlayerEntity player, Entity target) {
        // Creative mode bypass
        if (shouldBypassRateLimit(player)) {
            return true;
        }
        
        UUID playerId = player.getUuid();
        PlayerActionData data = getPlayerData(playerId);
        long currentTime = System.currentTimeMillis();
        
        // Position validation for target
        if (target != null) {
            BlockPos targetPos = target.getBlockPos();
            if (!validatePosition(player, targetPos, data, "attack")) {
                return false;
            }
        }
        
        long cooldown = (target instanceof ServerPlayerEntity) ? 100 : 50;
        
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
    
    // Keep all existing helper methods (handleAnimalFeeding, handleDoorInteraction, etc.)
    private boolean handleAnimalFeeding(PlayerActionData data, ServerPlayerEntity player, long currentTime) {
        if (currentTime - data.lastAnimalFeed > FEED_SEQUENCE_TIMEOUT) {
            data.sequentialFeeds = 0;
        }
        
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
        boolean sameDoor = pos.equals(data.lastDoorPos);
        
        if (!sameDoor) {
            data.lastDoorPos = pos;
            data.lastDoorInteraction = currentTime;
            data.addAction(currentTime);
            return true;
        }
        
        if (currentTime - data.lastDoorInteraction < DOOR_INTERACTION_COOLDOWN) {
            if (currentTime - data.lastDoorInteraction < 25) {
                recordViolation(data, player, "Very rapid door interaction: " + (currentTime - data.lastDoorInteraction) + "ms");
            }
            return true;
        }
        
        data.lastDoorInteraction = currentTime;
        data.addAction(currentTime);
        
        return true;
    }
    
    private long getItemUseCooldown(Item item) {
        if (item == null) return ITEM_USE_COOLDOWN;
        
        if (CONSTRUCTION_ITEMS.contains(item)) {
            return BUCKET_USE_COOLDOWN;
        }
        
        if (ANIMAL_FOOD.contains(item)) {
            return ANIMAL_FEEDING_COOLDOWN;
        }
        
        if (item == Items.FLINT_AND_STEEL || item == Items.SHEARS || 
            item == Items.BONE_MEAL || item == Items.INK_SAC) {
            return 15;
        }
        
        return ITEM_USE_COOLDOWN;
    }
    
    // Keep all existing utility methods unchanged
    private boolean detectSuspiciousPattern(PlayerActionData data, long currentTime) {
        int recentActions = data.countRecentActions(currentTime, PATTERN_DETECTION_WINDOW);
        if (recentActions > SUSPICIOUS_RAPID_ACTIONS) {
            data.suspiciousPatternCount++;
            return data.suspiciousPatternCount > 2;
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
    
    private boolean isInstantBreakBlock(Block block) {
        String blockName = block.getTranslationKey().toLowerCase();
        return blockName.contains("grass") || blockName.contains("flower") || 
               blockName.contains("sapling") || blockName.contains("mushroom") ||
               blockName.contains("torch") || blockName.contains("redstone_wire");
    }
    
    private PlayerActionData getPlayerData(UUID playerId) {
        return playerData.computeIfAbsent(playerId, k -> new PlayerActionData());
    }
    
    private boolean checkActionRate(PlayerActionData data, long currentTime) {
        return countRecentActions(data, currentTime) < MAX_ACTIONS_PER_SECOND;
    }
    
    private int countRecentActions(PlayerActionData data, long currentTime) {
        return data.countRecentActions(currentTime, 1000);
    }
    
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
        
        try {
            if (data.violationCount == 3) {
                player.sendMessage(net.minecraft.text.Text.of("§6[AntiCheat] §eSlowing down actions to prevent violations"), false);
            } else if (data.violationCount == 8) {
                player.sendMessage(net.minecraft.text.Text.of("§c[AntiCheat] §cToo many rapid actions detected"), false);
            }
        } catch (Exception e) {
            // Ignore message sending errors
        }
        
        if (data.violationCount > MAX_VIOLATIONS_BEFORE_KICK) {
            try {
                System.out.println("[AntiCheat] Player " + player.getName().getString() + 
                                 " exceeded rate limit violation threshold");
            } catch (Exception e) {
                System.out.println("[AntiCheat] Player exceeded rate limit violation threshold");
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
            data.distanceViolations = 0;
        }
    }
    
    public void performMaintenance() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            playerData.values().forEach(data -> {
                if (currentTime - data.lastViolation > VIOLATION_DECAY_TIME) {
                    data.violationCount = Math.max(0, data.violationCount - 1);
                    data.suspiciousPatternCount = Math.max(0, data.suspiciousPatternCount - 1);
                    data.distanceViolations = Math.max(0, data.distanceViolations - 1);
                    data.lastViolation = currentTime;
                }
            });
            
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
    
    public String getPlayerStats(ServerPlayerEntity player) {
        PlayerActionData data = playerData.get(player.getUuid());
        if (data == null) return "No data";
        
        long currentTime = System.currentTimeMillis();
        return String.format("Violations: %d, Recent actions: %d/s, Distance violations: %d, Sequential feeds: %d, Sequential buckets: %d, Suspicious patterns: %d",
            data.violationCount,
            data.countRecentActions(currentTime, 1000),
            data.distanceViolations,
            data.sequentialFeeds,
            data.sequentialBucketUses,
            data.suspiciousPatternCount);
    }
    
    // Convenience methods for backwards compatibility
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
