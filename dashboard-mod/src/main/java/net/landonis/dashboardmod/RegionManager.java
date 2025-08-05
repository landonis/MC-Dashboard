package net.landonis.dashboardmod;

import com.google.gson.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.math.ChunkPos;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RegionManager {
    private static final Map<ChunkPos, ClaimedChunk> claimedChunks = new ConcurrentHashMap<>();
    private static final File CLAIM_FILE = new File("config/dashboardmod/claims.json");

    public static void init() {
        loadClaims();
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveClaims());
    }

    public static boolean claimChunk(UUID owner, ChunkPos pos) {
        if (claimedChunks.containsKey(pos)) return false;
        claimedChunks.put(pos, new ClaimedChunk(owner));
        return true;
    }

    public static boolean unclaimChunk(UUID owner, ChunkPos pos) {
        ClaimedChunk existing = claimedChunks.get(pos);
        if (existing != null && existing.getOwner().equals(owner)) {
            claimedChunks.remove(pos);
            return true;
        }
        return false;
    }

    public static ClaimedChunk getClaim(ChunkPos pos) {
        return claimedChunks.get(pos);
    }

    public static void saveClaims() {
        try {
            CLAIM_FILE.getParentFile().mkdirs();
            JsonArray data = new JsonArray();
            for (Map.Entry<ChunkPos, ClaimedChunk> entry : claimedChunks.entrySet()) {
                ChunkPos pos = entry.getKey();
                ClaimedChunk chunk = entry.getValue();

                JsonObject obj = new JsonObject();
                obj.addProperty("x", pos.x);
                obj.addProperty("z", pos.z);
                obj.addProperty("owner", chunk.getOwner().toString());

                JsonArray trusted = new JsonArray();
                for (UUID uuid : chunk.getTrustedPlayers()) {
                    trusted.add(uuid.toString());
                }
                obj.add("trusted", trusted);

                data.add(obj);
            }

            try (Writer writer = new FileWriter(CLAIM_FILE)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(data, writer);
            }

        } catch (IOException e) {
            System.err.println("[DashboardMod] Failed to save claims: " + e.getMessage());
        }
    }

    public static void loadClaims() {
        if (!CLAIM_FILE.exists()) return;
        try (Reader reader = new FileReader(CLAIM_FILE)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                int x = obj.get("x").getAsInt();
                int z = obj.get("z").getAsInt();
                UUID owner = UUID.fromString(obj.get("owner").getAsString());

                ClaimedChunk chunk = new ClaimedChunk(owner);
                if (obj.has("trusted")) {
                    for (JsonElement uuidEl : obj.getAsJsonArray("trusted")) {
                        chunk.addTrustedPlayer(UUID.fromString(uuidEl.getAsString()));
                    }
                }

                claimedChunks.put(new ChunkPos(x, z), chunk);
            }
        } catch (Exception e) {
            System.err.println("[DashboardMod] Failed to load claims: " + e.getMessage());
        }
    }

    public static class ClaimedChunk {
        private final UUID owner;
        private final Set<UUID> trustedPlayers = new HashSet<>();

        public ClaimedChunk(UUID owner) {
            this.owner = owner;
        }

        public UUID getOwner() {
            return owner;
        }

        public void addTrustedPlayer(UUID uuid) {
            trustedPlayers.add(uuid);
        }

        public void removeTrustedPlayer(UUID uuid) {
            trustedPlayers.remove(uuid);
        }

        public boolean isTrusted(UUID uuid) {
            return trustedPlayers.contains(uuid);
        }

        public Set<UUID> getTrustedPlayers() {
            return trustedPlayers;
        }
    }

    public static boolean isClaimed(ChunkPos pos) {
        return claimedChunks.containsKey(pos);
    }

    public static String getChunkOwner(ChunkPos pos) {
        ClaimedChunk claim = claimedChunks.get(pos);
        return (claim != null) ? claim.getOwner().toString() : null;
    }

    public static boolean canEdit(String playerName, ChunkPos pos) {
        ClaimedChunk claim = claimedChunks.get(pos);
        if (claim == null) return true;
        UUID player = UUID.fromString(playerName);
        return claim.getOwner().equals(player) || claim.isTrusted(player);
    }

    public static Set<ChunkPos> getPlayerClaims(String playerName) {
        UUID uuid = UUID.fromString(playerName);
        Set<ChunkPos> result = new HashSet<>();
        for (Map.Entry<ChunkPos, ClaimedChunk> entry : claimedChunks.entrySet()) {
            if (entry.getValue().getOwner().equals(uuid)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public static boolean claimChunk(String playerName, ChunkPos pos) {
        return claimChunk(UUID.fromString(playerName), pos);
    }

    public static boolean unclaimChunk(String playerName, ChunkPos pos) {
        return unclaimChunk(UUID.fromString(playerName), pos);
    }

}
