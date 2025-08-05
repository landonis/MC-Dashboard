package net.landonis.dashboardmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

import net.landonis.dashboardmod.RegionManager.ClaimedChunk;

public class DashboardMod implements ModInitializer {

@Override
public void onInitialize() {
    System.out.println("[DashboardMod] Initializing with Region Protection...");

    // Load and register systems
    RegionManager.loadClaims();
    RegionCommandHandler.registerCommands();
    ChunkTracker.register(); // Handles /claiminfo
    GroupCommandHandler.register(); // Handles /group commands

    // Block break protection
    PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
        ChunkPos chunkPos = new ChunkPos(pos);
        RegionManager.ClaimedChunk claim = RegionManager.getClaim(chunkPos);
        if (claim != null && !RegionProtection.canPlayerBuild(player.getUuid(), claim)) {
            player.sendMessage(Text.literal("You can't break blocks in this claimed area.").formatted(Formatting.RED), false);
            return false;
        }
        return true;
    });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            ChunkPos chunkPos = new ChunkPos(hitResult.getBlockPos());
            ClaimedChunk claim = RegionManager.getClaim(chunkPos);
            if (claim != null && !canPlayerBuild(player.getUuid(), claim)) {
                player.sendMessage(Text.literal("You can't interact with blocks here.").formatted(Formatting.RED), false);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient) return ActionResult.PASS;
            if (!RegionProtection.canPlayerBuild(player.getUuid(), pos)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            player.sendMessage(Text.literal("§a[Region Protection] Welcome! Use /claim to protect your builds."), false);
        });

        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            GroupManager.load(server);
            RegionManager.setServer(server);
            DashboardWebSocketClient.connect(server);
            DashboardWebSocketClient.setServerInstance(server);
            DashboardWebSocketClient.sendServerStatus();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RegionManager.saveClaims();
            GroupManager.saveGroups();
        });
    }

    private boolean canPlayerBuild(UUID playerUuid, ClaimedChunk claim) {
        if (claim.isPlayerClaim()) {
            return claim.getOwner().equals(playerUuid) || claim.isTrusted(playerUuid);
        } else if (claim.isGroupClaim()) {
            String groupName = claim.getGroupName();
            return GroupManager.getGroup(groupName).hasPermission(playerUuid, "build");
        }
        return false;
    }
}
