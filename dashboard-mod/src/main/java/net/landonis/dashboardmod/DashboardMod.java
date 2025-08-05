package net.landonis.dashboardmod;
import net.fabricmc.fabric.api.event.player.PlayerMoveCallback;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import net.landonis.dashboardmod.RegionManager.ClaimedChunk;

public class DashboardMod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[DashboardMod] Initializing with Region Protection...");

        // Initialize region protection
        RegionManager.loadClaims();
        RegionCommandHandler.registerCommands();

        // Register block break protection
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            ChunkPos chunkPos = new ChunkPos(pos);
            ClaimedChunk claim = RegionManager.getClaim(chunkPos);
            if (claim != null && !claim.getOwner().equals(player.getUuid()) && !claim.isTrusted(player.getUuid())) {
                player.sendMessage(Text.literal("You can't break blocks in this claimed area.").formatted(Formatting.RED), false);
                return false;
            }
            return true;
        });

        // Register block use (right-click) protection
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

        // Register block attack (left-click) protection
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
            player.sendMessage(Text.literal("§a[Region Protection] Welcome! Use /claim to protect your builds."), false);
        });

        PlayerMoveCallback.EVENT.register((player, from, to) -> {
            if (!RegionCommandHandler.isTrackingClaimInfo(player.getUuid())) return;
        
            ChunkPos fromChunk = new ChunkPos(from);
            ChunkPos toChunk = new ChunkPos(to);
        
            if (!fromChunk.equals(toChunk)) {
                RegionCommandHandler.updateLastKnownChunk(player.getUuid(), toChunk);
                String ownerName = RegionManager.getChunkOwner(toChunk);
                if (ownerName == null) {
                    player.sendMessage(Text.literal("Chunk unclaimed.").formatted(Formatting.GRAY), false);
                } else {
                    player.sendMessage(Text.literal("This chunk is claimed by ").formatted(Formatting.YELLOW)
                        .append(Text.literal(ownerName).formatted(Formatting.GREEN)), false);
                }
            }
        });


        // When the server starts, connect the mod to dashboard
        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            RegionManager.setServer(server);
            DashboardWebSocketClient.connect(server);
            DashboardWebSocketClient.setServerInstance(server);
            DashboardWebSocketClient.sendServerStatus();
        });

        // Save claims on server shutdown
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RegionManager.saveClaims();
        });
    }
}
