package net.landonis.dashboardmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

import java.util.Set;

public class RegionCommandHandler {
    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerClaimCommands(dispatcher);
        });
    }

    private static void registerClaimCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        // /claim command
        dispatcher.register(CommandManager.literal("claim")
            .executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                ChunkPos pos = player.getChunkPos();
                String playerName = player.getName().getString();
                
                if (RegionManager.isClaimed(pos)) {
                    String owner = RegionManager.getChunkOwner(pos);
                    if (owner.equals(playerName)) {
                        player.sendMessage(Text.literal("§eYou already own this chunk."), false);
                    } else {
                        player.sendMessage(Text.literal("§cThis chunk is already claimed by " + owner + "."), false);
                    }
                } else if (RegionManager.claimChunk(playerName, pos)) {
                    player.sendMessage(Text.literal("§aChunk claimed successfully!"), false);
                    
                    // Notify dashboard if connected
                    DashboardWebSocketClient.sendClaimUpdate(playerName, pos, "claimed");
                } else {
                    player.sendMessage(Text.literal("§cFailed to claim chunk."), false);
                }
                return 1;
            }));

        // /unclaim command
        dispatcher.register(CommandManager.literal("unclaim")
            .executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                ChunkPos pos = player.getChunkPos();
                String playerName = player.getName().getString();
                
                if (RegionManager.unclaimChunk(playerName, pos)) {
                    player.sendMessage(Text.literal("§eChunk unclaimed successfully."), false);
                    
                    // Notify dashboard if connected
                    DashboardWebSocketClient.sendClaimUpdate(playerName, pos, "unclaimed");
                } else {
                    player.sendMessage(Text.literal("§cYou don't own this chunk or it's not claimed."), false);
                }
                return 1;
            }));

        // /claims command - list player's claims
        dispatcher.register(CommandManager.literal("claims")
            .executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                String playerName = player.getName().getString();
                Set<ChunkPos> claims = RegionManager.getPlayerClaims(playerName);
                
                if (claims.isEmpty()) {
                    player.sendMessage(Text.literal("§eYou don't have any claimed chunks."), false);
                } else {
                    player.sendMessage(Text.literal("§aYour claimed chunks (" + claims.size() + "):"), false);
                    for (ChunkPos pos : claims) {
                        player.sendMessage(Text.literal("§7- Chunk " + pos.x + ", " + pos.z), false);
                    }
                }
                return 1;
            }));

        // /claiminfo command - info about current chunk
        dispatcher.register(CommandManager.literal("claiminfo")
            .executes(ctx -> {
                ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
                ChunkPos pos = player.getChunkPos();
                
                if (RegionManager.isClaimed(pos)) {
                    String owner = RegionManager.getChunkOwner(pos);
                    player.sendMessage(Text.literal("§eThis chunk is claimed by: §a" + owner), false);
                } else {
                    player.sendMessage(Text.literal("§7This chunk is not claimed by anyone."), false);
                }
                return 1;
            }));
    }
}
