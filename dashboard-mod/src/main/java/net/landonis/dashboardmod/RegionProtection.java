package net.landonis.dashboardmod;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

public class RegionProtection {
    
    public static boolean canPlayerModifyBlock(PlayerEntity player, BlockPos blockPos) {
        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return true; // Allow client-side operations
        }
        
        // Allow creative mode players (assuming they're admins)
        if (serverPlayer.isCreative()) {
            return true;
        }
        
        ChunkPos chunkPos = new ChunkPos(blockPos);
        String playerName = serverPlayer.getName().getString();
        
        // If chunk is not claimed, allow modification
        if (!RegionManager.isClaimed(chunkPos)) {
            return true;
        }
        
        // If player owns the chunk, allow modification
        if (RegionManager.canEdit(playerName, chunkPos)) {
            return true;
        }
        
        // Chunk is claimed by someone else, deny modification
        String owner = RegionManager.getChunkOwner(chunkPos);
        serverPlayer.sendMessage(
            Text.literal("§cThis area is protected by " + owner + "!"), 
            true // Show as action bar message
        );
        
        return false;
    }
    
    public static boolean isChunkProtected(ChunkPos chunkPos) {
        return RegionManager.isClaimed(chunkPos);
    }
    
    public static String getChunkOwner(ChunkPos chunkPos) {
        return RegionManager.getChunkOwner(chunkPos);
    }
}
