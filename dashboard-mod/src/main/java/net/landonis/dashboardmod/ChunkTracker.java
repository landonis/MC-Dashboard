package net.landonis.dashboardmod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

public class ChunkTracker {
    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                UUID uuid = player.getUuid();

                if (!RegionCommandHandler.isTrackingClaimInfo(uuid)) continue;

                ChunkPos currentChunk = player.getChunkPos();
                ChunkPos lastChunk = RegionCommandHandler.getLastKnownChunk(uuid);

                if (!currentChunk.equals(lastChunk)) {
                    RegionCommandHandler.updateLastKnownChunk(uuid, currentChunk);

                    if (RegionManager.isClaimed(currentChunk)) {
                        String owner = RegionManager.getChunkOwner(currentChunk);
                        player.sendMessage(
                            Text.literal("This chunk is claimed by ")
                                .append(Text.literal(owner).formatted(Formatting.GREEN))
                                .formatted(Formatting.YELLOW),
                            false
                        );
                    } else {
                        player.sendMessage(Text.literal("Chunk unclaimed.").formatted(Formatting.GRAY), false);
                    }
                }
            }
        });
    }
    public static ChunkPos getLastKnownChunk(UUID uuid) {
        return lastChunks.get(uuid);
    }
    
    public static void updateLastKnownChunk(UUID uuid, ChunkPos newChunk) {
        lastChunks.put(uuid, newChunk);
    }

}
