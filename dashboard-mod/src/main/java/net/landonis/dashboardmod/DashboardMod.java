package net.landonis.dashboardmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.landonis.dashboardmod.RegionManager.ClaimedChunk;
import net.landonis.dashboardmod.anticheat.AntiCheatHelper;
import net.landonis.dashboardmod.anticheat.AntiCheatCommands;

public class DashboardMod implements ModInitializer {
    
    // Position tracking for movement anticheat
    private static final Map<UUID, Vec3d> previousPositions = new ConcurrentHashMap<>();
    
    @Override
    public void onInitialize() {
        System.out.println("[DashboardMod] Initializing with Region Protection and AntiCheat...");
        
        // Initialize anticheat system
        AntiCheatHelper.initialize();
        
        // Load and register existing systems
        RegionManager.loadClaims();
        RegionCommandHandler.registerCommands();
        ChunkTracker.register(); // Handles /claiminfo
        GroupCommandHandler.register(); // Handles /group commands
        
        // Register anticheat commands - FIXED: Use initialize() instead of register()
        AntiCheatCommands.initialize();
        
        // Block break protection (enhanced with anticheat)
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            // ANTICHEAT CHECK FIRST - prevents rapid breaking exploits
            if (!AntiCheatHelper.canBreakBlock(player, pos)) {
                return false; // Block the action due to rate limiting
            }
            
            // EXISTING REGION PROTECTION LOGIC
            ChunkPos chunkPos = new ChunkPos(pos);
            RegionManager.ClaimedChunk claim = RegionManager.getClaim(chunkPos);
            if (claim != null && !RegionProtection.canPlayerBuild(player.getUuid(), claim)) {
                player.sendMessage(Text.literal("You can't break blocks in this claimed area.").formatted(Formatting.RED), false);
                return false;
            }
            return true;
        });

        // Block use/placement protection (enhanced with anticheat)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            
            // FIXED: Safe cast to ServerPlayerEntity
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS; // Skip for client-side players
            }
            
            // ANTICHEAT CHECK FIRST - prevents rapid placement exploits
            if (!AntiCheatHelper.canPlaceBlock(serverPlayer, hitResult.getBlockPos())) {
                return ActionResult.FAIL; // Block the action due to rate limiting
            }
            
            // EXISTING REGION PROTECTION LOGIC
            ChunkPos chunkPos = new ChunkPos(hitResult.getBlockPos());
            ClaimedChunk claim = RegionManager.getClaim(chunkPos);
            if (claim != null && !RegionProtection.canPlayerBuild(serverPlayer.getUuid(), claim)) {
                serverPlayer.sendMessage(Text.literal("You can't interact with blocks here.").formatted(Formatting.RED), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Block attack protection (existing logic)
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient) return ActionResult.PASS;
            
            // FIXED: Safe cast to ServerPlayerEntity
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS; // Skip for client-side players
            }
            
            if (!RegionProtection.canPlayerModifyBlock(serverPlayer, pos)) {
                serverPlayer.sendMessage(Text.literal("You can't attack blocks here.").formatted(Formatting.RED), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        
        // NEW: Entity attack protection with anticheat
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            // FIXED: Safe cast to ServerPlayerEntity
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS; // Skip for client-side players
            }
            
            // ANTICHEAT CHECK - prevents rapid attack exploits
            if (!AntiCheatHelper.canAttack(serverPlayer)) {
                return ActionResult.FAIL; // Block the action due to rate limiting
            }
            
            // You can add region protection for entity attacks here if needed
            // For example, protect pets in claimed areas
            return ActionResult.PASS;
        });
        
        // NEW: Item use protection with anticheat
        UseItemCallback.EVENT.register((player, world, hand) -> {
            // FIXED: Safe cast to ServerPlayerEntity
            if (!(player instanceof ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS; // Skip for client-side players
            }
            
            // ANTICHEAT CHECK - prevents rapid item use exploits
            if (!AntiCheatHelper.canUseItem(serverPlayer)) {
                return ActionResult.FAIL; // Block the action due to rate limiting
            }
            
            // You can add region protection for item usage here if needed
            return ActionResult.PASS;
        });

        // Player join events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            player.sendMessage(Text.literal("§a[Region Protection] Welcome! Use /claim to protect your builds."), false);
            
            // Initialize player position tracking for movement anticheat
            previousPositions.put(player.getUuid(), player.getPos());
        });
        
        // Player disconnect events (enhanced with anticheat cleanup)
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            
            // Clean up anticheat data
            AntiCheatHelper.onPlayerDisconnect(player);
            
            // Clean up position tracking
            previousPositions.remove(player.getUuid());
        });

        // Server lifecycle events
        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            GroupManager.load(server);
            RegionManager.setServer(server);
            DashboardWebSocketClient.connect(server);
            DashboardWebSocketClient.setServerInstance(server);
            DashboardWebSocketClient.sendServerStatus();
            
            System.out.println("[DashboardMod] Server started with AntiCheat protection active");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RegionManager.saveClaims();
            GroupManager.saveGroups();
            
            System.out.println("[DashboardMod] Server stopping - saved region data and anticheat cleanup complete");
        });
        
        // NEW: Server tick events for anticheat maintenance and movement checking
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Perform anticheat maintenance every second (20 ticks)
            if (server.getTicks() % 20 == 0) {
                AntiCheatHelper.performMaintenance();
            }
            
            // Check player movements every tick
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                checkPlayerMovement(player);
            }
        });
    }
    
    /**
     * Check individual player movement for anticheat violations
     */
    private void checkPlayerMovement(ServerPlayerEntity player) {
        Vec3d currentPos = player.getPos();
        Vec3d previousPos = previousPositions.get(player.getUuid());
        
        if (previousPos != null) {
            // Validate movement with anticheat system
            if (!AntiCheatHelper.validateMovement(player, previousPos, currentPos)) {
                // Movement violation detected - MovementAntiCheat handles remediation
                handleMovementViolation(player);
            }
        }
        
        // Update position for next check
        previousPositions.put(player.getUuid(), currentPos);
    }
    
    /**
     * Handle movement violations (optional additional actions)
     */
    private void handleMovementViolation(ServerPlayerEntity player) {
        int violations = AntiCheatHelper.getMovementViolations(player);
        
        // You can add custom actions here, like:
        if (violations == 5) {
            // Send message to all admins
            for (ServerPlayerEntity admin : player.getServer().getPlayerManager().getPlayerList()) {
                if (admin.hasPermissionLevel(2)) {
                    admin.sendMessage(Text.literal("§c[AntiCheat] " + player.getName().getString() + 
                                                 " triggered movement violation (" + violations + " total)"), false);
                }
            }
        }
        
        if (violations > 20) {
            // Log to your dashboard system if you have logging
            System.out.println("[DashboardMod] Player " + player.getName().getString() + 
                             " kicked for movement violations");
        }
    }
    
    /**
     * Get anticheat violation summary for a player (for admin commands)
     */
    public static String getPlayerViolationSummary(ServerPlayerEntity player) {
        int rateViolations = AntiCheatHelper.getRateViolations(player);
        int movementViolations = AntiCheatHelper.getMovementViolations(player);
        
        return String.format("§e%s: Rate violations: %d, Movement violations: %d", 
                           player.getName().getString(), rateViolations, movementViolations);
    }
    
    /**
     * Reset violations for a player (for admin commands)
     */
    public static void resetPlayerViolations(ServerPlayerEntity player) {
        AntiCheatHelper.resetViolations(player);
        player.sendMessage(Text.literal("§a[AntiCheat] Your violations have been reset by an administrator"), false);
    }
}
