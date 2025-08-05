package net.landonis.dashboardmod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChunkTracker {

    private static final Map<UUID, ChunkPos> lastChunks = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                UUID uuid = player.getUuid();

                if (!RegionCommandHandler.isTrackingClaimInfo(uuid)) continue;

                ChunkPos currentChunk = player.getChunkPos();
                ChunkPos lastChunk = lastChunks.get(uuid);

                if (!currentChunk.equals(lastChunk)) {
                    lastChunks.put(uuid, currentChunk);

                    String owner = RegionManager.getChunkOwner(currentChunk);
                    if (owner == null) {
                        player.sendMessage(Text.literal("Chunk unclaimed.").formatted(Formatting.GRAY), false);
                    } else {
                        player.sendMessage(Text.literal("This chunk is claimed by ")
                            .formatted(Formatting.YELLOW)
                            .append(Text.literal(owner).formatted(Formatting.GREEN)), false);
                    }
                }
            }
        });
    }

    // Optional utility methods if needed later
    public static ChunkPos getLastKnownChunk(UUID uuid) {
        return lastChunks.get(uuid);
    }

    public static void updateLastKnownChunk(UUID uuid, ChunkPos newChunk) {
        lastChunks.put(uuid, newChunk);
    }
}
