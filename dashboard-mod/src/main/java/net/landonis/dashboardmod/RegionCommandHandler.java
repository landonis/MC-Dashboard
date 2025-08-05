package net.landonis.dashboardmod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.Formatting;

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
            .requires(source -> source.isExecutedByPlayer())
            .executes(ctx -> {
                return executeClaim(ctx);
            }));

        // /unclaim command
        dispatcher.register(CommandManager.literal("unclaim")
            .requires(source -> source.isExecutedByPlayer())
            .executes(ctx -> {
                return executeUnclaim(ctx);
            }));

        // /claims command - list player's claims
        dispatcher.register(CommandManager.literal("claims")
            .requires(source -> source.isExecutedByPlayer())
            .executes(ctx -> {
                return executeListClaims(ctx);
            }));

        // /claiminfo command - info about current chunk
        dispatcher.register(CommandManager.literal("claiminfo")
            .requires(source -> source.isExecutedByPlayer())
            .executes(ctx -> {
                return executeClaimInfo(ctx);
            }));

        
        // /trustlist - list trusted players in current chunk
        dispatcher.register(CommandManager.literal("trustlist")
            .requires(source -> source.isExecutedByPlayer())
            .executes(ctx -> {
                return executeTrustList(ctx);
            }));

        // /claimhelp command - show help
        dispatcher.register(CommandManager.literal("claimhelp")
            .executes(ctx -> {
                return executeHelp(ctx);
            }));
    }

    private static int executeClaim(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        String playerName = player.getName().getString();
        
        if (RegionManager.isClaimed(pos)) {
            String owner = RegionManager.getChunkOwner(pos);
            if (owner.equals(playerName)) {
                player.sendMessage(Text.literal("You already own this chunk.").formatted(Formatting.YELLOW), false);
            } else {
                player.sendMessage(Text.literal("This chunk is already claimed by " + owner + ".").formatted(Formatting.RED), false);
            }
        } else if (RegionManager.claimChunk(playerName, pos)) {
            player.sendMessage(Text.literal("Chunk claimed successfully! (" + pos.x + ", " + pos.z + ")").formatted(Formatting.GREEN), false);
            
            // Notify dashboard if connected
            DashboardWebSocketClient.sendClaimUpdate(playerName, pos, "claimed");
        } else {
            player.sendMessage(Text.literal("Failed to claim chunk.").formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int executeUnclaim(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        String playerName = player.getName().getString();
        
        if (RegionManager.unclaimChunk(playerName, pos)) {
            player.sendMessage(Text.literal("Chunk unclaimed successfully. (" + pos.x + ", " + pos.z + ")").formatted(Formatting.YELLOW), false);
            
            // Notify dashboard if connected
            DashboardWebSocketClient.sendClaimUpdate(playerName, pos, "unclaimed");
        } else {
            player.sendMessage(Text.literal("You don't own this chunk or it's not claimed.").formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int executeListClaims(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        String playerName = player.getName().getString();
        Set<ChunkPos> claims = RegionManager.getPlayerClaims(playerName);
        
        if (claims.isEmpty()) {
            player.sendMessage(Text.literal("You don't have any claimed chunks.").formatted(Formatting.YELLOW), false);
        } else {
            player.sendMessage(Text.literal("Your claimed chunks (" + claims.size() + "):").formatted(Formatting.GREEN), false);
            for (ChunkPos pos : claims) {
                player.sendMessage(Text.literal("- Chunk " + pos.x + ", " + pos.z).formatted(Formatting.GRAY), false);
            }
        }
        return 1;
    }

    private static int executeClaimInfo(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        
        if (RegionManager.isClaimed(pos)) {
            String owner = RegionManager.getChunkOwner(pos);
            player.sendMessage(Text.literal("This chunk is claimed by: ").formatted(Formatting.YELLOW)
                .append(Text.literal(owner).formatted(Formatting.GREEN)), false);
        } else {
            player.sendMessage(Text.literal("This chunk is not claimed by anyone.").formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    private static int executeHelp(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        source.sendFeedback(() -> Text.literal("=== Region Protection Commands ===").formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("/claim - Claim the current chunk").formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("/unclaim - Unclaim the current chunk").formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("/claims - List your claimed chunks").formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("/claiminfo - Get info about current chunk").formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("/claimhelp - Show this help").formatted(Formatting.GREEN), false", false);
        source.sendFeedback(() -> Text.literal("/trust <player> - Allow player to build in your claim").formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("/untrust <player> - Remove trust from a player").formatted(Formatting.GREEN), false);
        return 1;
    }
}

    private static int executeTrustList(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        ClaimedChunk claim = RegionManager.getClaim(pos);

        if (claim == null) {
            player.sendMessage(Text.literal("This chunk is not claimed.").formatted(Formatting.RED), false);
        } else if (!claim.getOwner().equals(player.getUuid())) {
            player.sendMessage(Text.literal("You do not own this chunk.").formatted(Formatting.RED), false);
        } else {
            Set<UUID> trusted = claim.getTrustedPlayers();
            if (trusted.isEmpty()) {
                player.sendMessage(Text.literal("No players are trusted in this chunk.").formatted(Formatting.YELLOW), false);
            } else {
                player.sendMessage(Text.literal("Trusted players in this chunk:").formatted(Formatting.GREEN), false);
                for (UUID uuid : trusted) {
                    player.sendMessage(Text.literal("- " + uuid).formatted(Formatting.GRAY), false);
                }
            }
        }
        return 1;
    }
