package net.landonis.dashboardmod;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;

import java.util.*;

public class RegionCommandHandler {
    private static final Set<UUID> claimInfoTrackingPlayers = new HashSet<>();
    private static final Map<UUID, ChunkPos> lastKnownChunks = new HashMap<>();
    
    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerClaimCommands(dispatcher);
        });
    }

    public static boolean isTrackingClaimInfo(UUID uuid) {
        return claimInfoTrackingPlayers.contains(uuid);
    }
    
    public static void updateLastKnownChunk(UUID uuid, ChunkPos newPos) {
        lastKnownChunks.put(uuid, newPos);
    }


    private static void registerClaimCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("claim")
            .requires(source -> source.isExecutedByPlayer())
            .executes(RegionCommandHandler::executeClaim));

        dispatcher.register(CommandManager.literal("unclaim")
            .requires(source -> source.isExecutedByPlayer())
            .executes(RegionCommandHandler::executeUnclaim));

        dispatcher.register(CommandManager.literal("claims")
            .requires(source -> source.isExecutedByPlayer())
            .executes(RegionCommandHandler::executeListClaims));

        dispatcher.register(CommandManager.literal("claiminfo")
            .requires(source -> source.isExecutedByPlayer())
            .executes(RegionCommandHandler::executeClaimInfo)
            .then(CommandManager.literal("start")
                .executes(RegionCommandHandler::executeClaimInfoStart))
            .then(CommandManager.literal("stop")
                .executes(RegionCommandHandler::executeClaimInfoStop)));


        dispatcher.register(CommandManager.literal("claimhelp")
            .executes(RegionCommandHandler::executeHelp));

        dispatcher.register(CommandManager.literal("trustlist")
            .requires(source -> source.isExecutedByPlayer())
            .executes(RegionCommandHandler::executeTrustList));

        dispatcher.register(CommandManager.literal("trust")
            .requires(source -> source.isExecutedByPlayer())
            .then(CommandManager.argument("player", net.minecraft.command.argument.GameProfileArgumentType.gameProfile())
                .executes(RegionCommandHandler::executeTrust)));

        dispatcher.register(CommandManager.literal("untrust")
            .requires(source -> source.isExecutedByPlayer())
            .then(CommandManager.argument("player", net.minecraft.command.argument.GameProfileArgumentType.gameProfile())
                .executes(RegionCommandHandler::executeUntrust)));
    }

    private static int executeClaim(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        UUID uuid = player.getUuid();

        if (RegionManager.isClaimed(pos)) {
            String owner = RegionManager.getChunkOwner(pos);
            if (owner != null && owner.equals(uuid.toString())) {
                player.sendMessage(Text.literal("You already own this chunk.").formatted(Formatting.YELLOW), false);
            } else {
                player.sendMessage(Text.literal("This chunk is already claimed by " + owner + ".").formatted(Formatting.RED), false);
            }
        } else if (RegionManager.claimChunk(uuid, pos)) {
            player.sendMessage(Text.literal("Chunk claimed successfully! (" + pos.x + ", " + pos.z + ")").formatted(Formatting.GREEN), false);
            DashboardWebSocketClient.sendClaimUpdate(player.getName().getString(), pos, "claimed");
        } else {
            player.sendMessage(Text.literal("Failed to claim chunk.").formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int executeUnclaim(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        UUID uuid = player.getUuid();

        if (RegionManager.unclaimChunk(uuid, pos)) {
            player.sendMessage(Text.literal("Chunk unclaimed successfully. (" + pos.x + ", " + pos.z + ")").formatted(Formatting.YELLOW), false);
            DashboardWebSocketClient.sendClaimUpdate(player.getName().getString(), pos, "unclaimed");
        } else {
            player.sendMessage(Text.literal("You don't own this chunk or it's not claimed.").formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int executeListClaims(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        UUID uuid = player.getUuid();
        Set<ChunkPos> claims = RegionManager.getPlayerClaims(uuid);

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
        source.sendFeedback(() -> Text.literal("/claimhelp - Show this help").formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("/trust <player> - Allow player to build in your claim").formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("/untrust <player> - Remove trust from a player").formatted(Formatting.GREEN), false);
        source.sendFeedback(() -> Text.literal("/trustlist - Show trusted players in current chunk").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int executeTrustList(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        RegionManager.ClaimedChunk claim = RegionManager.getClaim(pos);

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
                    Optional<GameProfile> profileOpt = player.getServer().getUserCache().getByUuid(uuid);
                    String name = profileOpt.map(GameProfile::getName).orElse(uuid.toString());
                    player.sendMessage(Text.literal("- " + name).formatted(Formatting.GRAY), false);
                }
            }
        }
        return 1;
    }

    private static int executeClaimInfoStart(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        UUID uuid = player.getUuid();
    
        if (claimInfoTrackingPlayers.contains(uuid)) {
            player.sendMessage(Text.literal("Claim info tracking is already active.").formatted(Formatting.YELLOW), false);
        } else {
            claimInfoTrackingPlayers.add(uuid);
            lastKnownChunks.put(uuid, player.getChunkPos());
            player.sendMessage(Text.literal("Claim info tracking started. Walk around to see who owns each chunk.").formatted(Formatting.GREEN), false);
        }
        return 1;
    }
    
    private static int executeClaimInfoStop(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        UUID uuid = player.getUuid();
    
        if (claimInfoTrackingPlayers.remove(uuid)) {
            lastKnownChunks.remove(uuid);
            player.sendMessage(Text.literal("Claim info tracking stopped.").formatted(Formatting.YELLOW), false);
        } else {
            player.sendMessage(Text.literal("Claim info tracking wasn't active.").formatted(Formatting.RED), false);
        }
        return 1;
    }


    private static int executeTrust(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity sender = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = sender.getChunkPos();
        RegionManager.ClaimedChunk claim = RegionManager.getClaim(pos);

        if (claim == null) {
            sender.sendMessage(Text.literal("This chunk is not claimed.").formatted(Formatting.RED), false);
            return 1;
        }

        if (!claim.getOwner().equals(sender.getUuid())) {
            sender.sendMessage(Text.literal("You do not own this chunk.").formatted(Formatting.RED), false);
            return 1;
        }

        Collection<GameProfile> targets = net.minecraft.command.argument.GameProfileArgumentType.getProfileArgument(ctx, "player");
        for (GameProfile target : targets) {
            UUID targetUUID = target.getId();
            if (targetUUID.equals(sender.getUuid())) {
                sender.sendMessage(Text.literal("You can't trust yourself.").formatted(Formatting.RED), false);
                continue;
            }
            if (claim.getTrustedPlayers().contains(targetUUID)) {
                sender.sendMessage(Text.literal(target.getName() + " is already trusted.").formatted(Formatting.YELLOW), false);
            } else {
                claim.addTrustedPlayer(targetUUID);
                sender.sendMessage(Text.literal("Trusted " + target.getName() + " for this chunk.").formatted(Formatting.GREEN), false);
            }
        }

        return 1;
    }

    private static int executeUntrust(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity sender = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = sender.getChunkPos();
        RegionManager.ClaimedChunk claim = RegionManager.getClaim(pos);

        if (claim == null) {
            sender.sendMessage(Text.literal("This chunk is not claimed.").formatted(Formatting.RED), false);
            return 1;
        }

        if (!claim.getOwner().equals(sender.getUuid())) {
            sender.sendMessage(Text.literal("You do not own this chunk.").formatted(Formatting.RED), false);
            return 1;
        }

        Collection<GameProfile> targets = net.minecraft.command.argument.GameProfileArgumentType.getProfileArgument(ctx, "player");
        for (GameProfile target : targets) {
            UUID targetUUID = target.getId();
            if (claim.getTrustedPlayers().contains(targetUUID)) {
                claim.removeTrustedPlayer(targetUUID);
                sender.sendMessage(Text.literal("Removed trust from " + target.getName() + ".").formatted(Formatting.YELLOW), false);
            } else {
                sender.sendMessage(Text.literal(target.getName() + " is not trusted.").formatted(Formatting.RED), false);
            }
        }

        return 1;
    }
}
