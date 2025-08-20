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

    private static final Map<UUID, Vec3d> previousPositions = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        System.out.println("[DashboardMod] Initializing with Region Protection and AntiCheat...");

        // Initialize AntiCheat core
        AntiCheatHelper.initialize();

        // Load region claims & commands
        RegionManager.loadClaims();
        RegionCommandHandler.registerCommands();
        ChunkTracker.register();
        GroupCommandHandler.register();

        // Register anticheat commands — fixed stub to prevent compile errors
        AntiCheatCommands.initialize();

        // Block break protection
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return false;

            // AntiCheat fast-break detection
            if (!AntiCheatHelper.canBreakBlock(serverPlayer, pos)) {
                return false;
            }

            // Region protection
            ChunkPos chunkPos = new ChunkPos(pos);
            ClaimedChunk claim = RegionManager.getClaim(chunkPos);
            if (claim != null && !RegionProtection.canPlayerBuild(serverPlayer.getUuid(), claim)) {
                serverPlayer.sendMessage(Text.literal("You can't break blocks in this claimed area.").formatted(Formatting.RED), false);
                return false;
            }
            return true;
        });

        // Block placement / use protection
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;

            // AntiCheat fast-place detection
            if (!AntiCheatHelper.canPlaceBlock(serverPlayer, hitResult.getBlockPos())) {
                return ActionResult.FAIL;
            }

            // Region protection
            ChunkPos chunkPos = new ChunkPos(hitResult.getBlockPos());
            ClaimedChunk claim = RegionManager.getClaim(chunkPos);
            if (claim != null && !RegionProtection.canPlayerBuild(serverPlayer.getUuid(), claim)) {
                serverPlayer.sendMessage(Text.literal("You can't interact with blocks here.").formatted(Formatting.RED), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Attack block protection
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;

            if (!RegionProtection.canPlayerModifyBlock(serverPlayer, pos)) {
                serverPlayer.sendMessage(Text.literal("You can't attack blocks here.").formatted(Formatting.RED), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Attack entity protection
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;

            if (!AntiCheatHelper.canAttack(serverPlayer)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Use item protection
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!(player instanceof ServerPlayerEntity serverPlayer)) return ActionResult.PASS;

            if (!AntiCheatHelper.canUseItem(serverPlayer)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Player joins
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            player.sendMessage(Text.literal("§a[Region Protection] Welcome! Use /claim to protect your builds."), false);
            previousPositions.put(player.getUuid(), player.getPos());
        });

        // Player disconnects
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            AntiCheatHelper.onPlayerDisconnect(player);
            previousPositions.remove(player.getUuid());
        });

        // Server start/stop lifecycle
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

        // Tick events for movement anti-cheat
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 == 0) {
                AntiCheatHelper.performMaintenance();
            }
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                checkPlayerMovement(player);
            }
        });
    }

    private void checkPlayerMovement(ServerPlayerEntity player) {
        Vec3d currentPos = player.getPos();
        Vec3d previousPos = previousPositions.get(player.getUuid());

        if (previousPos != null) {
            if (!AntiCheatHelper.validateMovement(player, previousPos, currentPos)) {
                handleMovementViolation(player);
            }
        }
        previousPositions.put(player.getUuid(), currentPos);
    }

    private void handleMovementViolation(ServerPlayerEntity player) {
        int violations = AntiCheatHelper.getMovementViolations(player);
        if (violations == 5) {
            for (ServerPlayerEntity admin : player.getServer().getPlayerManager().getPlayerList()) {
                if (admin.hasPermissionLevel(2)) {
                    admin.sendMessage(Text.literal("§c[AntiCheat] " + player.getName().getString() +
                            " triggered movement violation (" + violations + " total)"), false);
                }
            }
        }
        if (violations > 20) {
            System.out.println("[DashboardMod] Player " + player.getName().getString() +
                    " kicked for movement violations");
        }
    }

    public static String getPlayerViolationSummary(ServerPlayerEntity player) {
        int rateViolations = AntiCheatHelper.getRateViolations(player);
        int movementViolations = AntiCheatHelper.getMovementViolations(player);
        return String.format("§e%s: Rate violations: %d, Movement violations: %d",
                player.getName().getString(), rateViolations, movementViolations);
    }

    public static void resetPlayerViolations(ServerPlayerEntity player) {
        AntiCheatHelper.resetViolations(player);
        player.sendMessage(Text.literal("§a[AntiCheat] Your violations have been reset by an administrator"), false);
    }
}
