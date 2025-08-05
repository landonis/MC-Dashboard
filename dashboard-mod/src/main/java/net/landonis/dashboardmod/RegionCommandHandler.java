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

import static net.minecraft.command.argument.StringArgumentType.getString;
import static net.minecraft.command.argument.StringArgumentType.word;

public class RegionCommandHandler {
    private static final Set<UUID> trackingPlayers = new HashSet<>();

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerClaimCommands(dispatcher);
        });
    }

    public static boolean isTrackingClaimInfo(UUID uuid) {
        return trackingPlayers.contains(uuid);
    }

    public static void startTracking(UUID uuid) {
        trackingPlayers.add(uuid);
    }

    public static void stopTracking(UUID uuid) {
        trackingPlayers.remove(uuid);
    }

    private static void registerClaimCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("claim")
            .requires(source -> source.isExecutedByPlayer())
            .executes(RegionCommandHandler::executeClaim)
            .then(CommandManager.argument("group", word())
                .executes(RegionCommandHandler::executeGroupClaim)));

        dispatcher.register(CommandManager.literal("unclaim")
            .requires(source -> source.isExecutedByPlayer())
            .executes(RegionCommandHandler::executeUnclaim));

        // Other commands unchanged
    }

    private static int executeClaim(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        UUID uuid = player.getUuid();

        if (RegionManager.isClaimed(pos)) {
            String owner = RegionManager.getChunkOwner(pos);
            player.sendMessage(Text.literal("This chunk is already claimed by " + owner + ".").formatted(Formatting.RED), false);
        } else if (RegionManager.claimChunk(uuid, pos)) {
            player.sendMessage(Text.literal("Chunk claimed successfully! (" + pos.x + ", " + pos.z + ")").formatted(Formatting.GREEN), false);
            DashboardWebSocketClient.sendClaimUpdate(player.getName().getString(), pos, "claimed");
        } else {
            player.sendMessage(Text.literal("Failed to claim chunk.").formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int executeGroupClaim(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        String group = getString(ctx, "group");

        if (!GroupManager.groupExists(group)) {
            player.sendMessage(Text.literal("Group does not exist.").formatted(Formatting.RED), false);
            return 1;
        }

        if (!GroupManager.getGroup(group).hasPermission(player.getUuid(), "claim")) {
            player.sendMessage(Text.literal("You don't have permission to claim for this group.").formatted(Formatting.RED), false);
            return 1;
        }

        if (RegionManager.isClaimed(pos)) {
            String owner = RegionManager.getChunkOwner(pos);
            player.sendMessage(Text.literal("This chunk is already claimed by " + owner + ".").formatted(Formatting.RED), false);
        } else if (RegionManager.claimChunk(group, pos)) {
            player.sendMessage(Text.literal("Chunk claimed for group '" + group + "'! (" + pos.x + ", " + pos.z + ")").formatted(Formatting.AQUA), false);
            DashboardWebSocketClient.sendClaimUpdate(group, pos, "claimed");
        } else {
            player.sendMessage(Text.literal("Failed to claim chunk.").formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int executeUnclaim(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity player = ctx.getSource().getPlayerOrThrow();
        ChunkPos pos = player.getChunkPos();
        UUID uuid = player.getUuid();
        RegionManager.ClaimedChunk claim = RegionManager.getClaim(pos);

        if (claim == null) {
            player.sendMessage(Text.literal("This chunk is not claimed.").formatted(Formatting.RED), false);
            return 1;
        }

        if (claim.isPlayerClaim()) {
            if (!claim.getOwner().equals(uuid)) {
                player.sendMessage(Text.literal("You do not own this chunk.").formatted(Formatting.RED), false);
                return 1;
            }
            RegionManager.unclaimChunk(uuid, pos);
            player.sendMessage(Text.literal("Unclaimed your chunk. (" + pos.x + ", " + pos.z + ")").formatted(Formatting.YELLOW), false);
            DashboardWebSocketClient.sendClaimUpdate(player.getName().getString(), pos, "unclaimed");
        } else if (claim.isGroupClaim()) {
            String group = claim.getGroupName();
            boolean success = RegionManager.unclaimChunk(group, uuid, pos);
            if (success) {
                player.sendMessage(Text.literal("Unclaimed group chunk for '" + group + "'. (" + pos.x + ", " + pos.z + ")").formatted(Formatting.YELLOW), false);
                DashboardWebSocketClient.sendClaimUpdate(group, pos, "unclaimed");
            } else {
                player.sendMessage(Text.literal("You don't have permission to unclaim this group chunk.").formatted(Formatting.RED), false);
            }
        }
        return 1;
    }
}
