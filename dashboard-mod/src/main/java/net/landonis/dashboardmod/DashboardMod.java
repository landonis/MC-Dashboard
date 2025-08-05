package net.landonis.dashboardmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;

public class DashboardMod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[DashboardMod] Initializing with Region Protection...");

        // Initialize region protection
        RegionManager.loadClaims();
        RegionCommandHandler.registerCommands();

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            ChunkPos chunkPos = new ChunkPos(pos);
            ClaimedChunk claim = RegionManager.getClaim(chunkPos);
            if (claim != null && !claim.getOwner().equals(player.getUuid()) && !claim.isTrusted(player.getUuid())) {
                player.sendMessage(Text.literal("You can't break blocks in this claimed area.").formatted(Formatting.RED), false);
                return false;
            }
            return true;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            ChunkPos chunkPos = new ChunkPos(hitResult.getBlockPos());
            ClaimedChunk claim = RegionManager.getClaim(chunkPos);
            if (claim != null && !claim.getOwner().equals(player.getUuid()) && !claim.isTrusted(player.getUuid())) {
                player.sendMessage(Text.literal("You can't interact with blocks here.").formatted(Formatting.RED), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
        
        // Register block interaction events for region protection
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            
            if (!RegionProtection.canPlayerModifyBlock(player, hitResult.getBlockPos())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Register block breaking events
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClient) return true; // or false, depending on your logic
            
            return RegionProtection.canPlayerModifyBlock(player, pos);
        });


        // Register block attack events (left-click)
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient) return ActionResult.PASS;
            
            if (!RegionProtection.canPlayerModifyBlock(player, pos)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Register player join events to send welcome message
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            player.sendMessage(net.minecraft.text.Text.literal("§a[Region Protection] Welcome! Use /claim to protect your builds."), false);
        });

        // When the server has started, connect to dashboard and save claims on shutdown
        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            DashboardWebSocketClient.connect(server);
            DashboardWebSocketClient.setServerInstance(server);
            DashboardWebSocketClient.sendServerStatus();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RegionManager.saveClaims();
        });
    }
}
