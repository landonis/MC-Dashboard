package net.landonis.dashboardmod;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.Formatting;

import java.util.UUID;

public class RegionProtection {

    public static boolean canPlayerBuild(UUID playerUuid, RegionManager.ClaimedChunk claim) {
        if (claim == null) return true;

        if (claim.isPlayerClaim()) {
            return claim.getOwner().equals(playerUuid) || claim.isTrusted(playerUuid);
        } else if (claim.isGroupClaim()) {
            String groupName = claim.getGroupName();
            return GroupManager.getGroup(groupName).hasPermission(playerUuid, "build");
        }

        return false;
    }

    public static boolean canPlayerModifyBlock(PlayerEntity player, BlockPos blockPos) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return true; // client-side fallback
        }

        if (serverPlayer.isCreative() || serverPlayer.hasPermissionLevel(2)) {
            return true; // Admins can always build
        }

        ChunkPos chunkPos = new ChunkPos(blockPos);
        RegionManager.ClaimedChunk claim = RegionManager.getClaim(chunkPos);

        if (claim == null) {
            return true; // unclaimed chunks are editable
        }

        UUID uuid = serverPlayer.getUuid();
        if (canPlayerBuild(uuid, claim)) {
            return true;
        }

        // Send denial message
        String owner = claim.isPlayerClaim()
                ? serverPlayer.getServer().getUserCache().getByUuid(claim.getOwner())
                    .map(profile -> profile.getName()).orElse("Unknown Player")
                : "Group: " + claim.getGroupName();

        sendProtectionMessage(serverPlayer, owner);
        return false;
    }

    public static void sendProtectionMessage(ServerPlayerEntity player, String owner) {
        player.sendMessage(
            Text.literal("⚠ Protected by ").formatted(Formatting.YELLOW)
                .append(Text.literal(owner).formatted(Formatting.RED)),
            true // Action bar
        );
    }

    public static boolean isChunkProtected(ChunkPos chunkPos) {
        return RegionManager.isClaimed(chunkPos);
    }

    public static String getChunkOwner(ChunkPos chunkPos) {
        return RegionManager.getChunkOwner(chunkPos);
    }
}
