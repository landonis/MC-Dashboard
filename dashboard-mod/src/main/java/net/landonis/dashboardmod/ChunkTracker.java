package net.landonis.dashboardmod;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

import java.util.*;

public class ChunkTracker {
    private static final Map<UUID, ChunkPos> lastKnownChunks = new HashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(ChunkTracker::onServerTick);
    }

    private static void onServerTick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();

            if (!RegionCommandHandler.isTrackingClaimInfo(uuid)) continue;

            ChunkPos currentChunk = player.getChunkPos();
            ChunkPos lastChunk = lastKnownChunks.get(uuid);

            if (!currentChunk.equals(lastChunk)) {
                lastKnownChunks.put(uuid, currentChunk);

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
    }
}
