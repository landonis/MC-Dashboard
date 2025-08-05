package net.landonis.dashboardmod;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RegionManager {
    private static final Map<ChunkPos, ClaimedChunk> claimedChunks = new ConcurrentHashMap<>();
    private static final File CLAIM_FILE = new File("config/dashboardmod/claims.json");

    private static final Map<UUID, String> nameCache = new HashMap<>();
    private static MinecraftServer serverReference;

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            serverReference = server;
            loadClaims();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> saveClaims());
    }

    public static void setServer(MinecraftServer server) {
        serverReference = server;
    }

    // ===== Claiming =====

    public static boolean claimChunk(UUID owner, ChunkPos pos) {
        if (claimedChunks.containsKey(pos)) return false;
        claimedChunks.put(pos, ClaimedChunk.playerClaim(owner));
        return true;
    }

    public static boolean claimChunk(String groupName, ChunkPos pos) {
        if (claimedChunks.containsKey(pos)) return false;
        if (!GroupManager.groupExists(groupName)) return false;
        claimedChunks.put(pos, ClaimedChunk.groupClaim(groupName));
        return true;
    }

    public static boolean unclaimChunk(UUID owner, ChunkPos pos) {
        ClaimedChunk existing = claimedChunks.get(pos);
        if (existing != null && existing.isPlayerClaim() && owner.equals(existing.owner)) {
            claimedChunks.remove(pos);
            return true;
        }
        return false;
    }

    public static boolean unclaimChunk(String groupName, UUID actor, ChunkPos pos) {
        ClaimedChunk claim = claimedChunks.get(pos);
        if (claim != null && claim.isGroupClaim() && groupName.equals(claim.group)) {
            Group group = GroupManager.getGroup(groupName);
            if (group != null && group.hasPermission(actor, "claim")) {
                claimedChunks.remove(pos);
                return true;
            }
        }
        return false;
    }

    public static ClaimedChunk getClaim(ChunkPos pos) {
        return claimedChunks.get(pos);
    }

    public static boolean isClaimed(ChunkPos pos) {
        return claimedChunks.containsKey(pos);
    }

    // ===== Save / Load =====

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

                if (chunk.isPlayerClaim()) {
                    obj.addProperty("owner", chunk.owner.toString());
                } else if (chunk.isGroupClaim()) {
                    obj.addProperty("group", chunk.group);
                }

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
                ClaimedChunk claim;

                if (obj.has("owner")) {
                    UUID owner = UUID.fromString(obj.get("owner").getAsString());
                    claim = ClaimedChunk.playerClaim(owner);
                } else if (obj.has("group")) {
                    String groupName = obj.get("group").getAsString();
                    claim = ClaimedChunk.groupClaim(groupName);
                } else {
                    continue; // skip malformed entry
                }

                if (obj.has("trusted")) {
                    for (JsonElement uuidEl : obj.getAsJsonArray("trusted")) {
                        claim.addTrustedPlayer(UUID.fromString(uuidEl.getAsString()));
                    }
                }

                claimedChunks.put(new ChunkPos(x, z), claim);
            }
        } catch (Exception e) {
            System.err.println("[DashboardMod] Failed to load claims: " + e.getMessage());
        }
    }

    // ===== Permissions / Utilities =====

    public static boolean canEdit(UUID actor, ChunkPos pos) {
        ClaimedChunk claim = claimedChunks.get(pos);
        if (claim == null) return true;

        if (claim.isPlayerClaim()) {
            return claim.owner.equals(actor) || claim.isTrusted(actor);
        } else if (claim.isGroupClaim()) {
            Group group = GroupManager.getGroup(claim.group);
            return group != null && group.hasPermission(actor, "build");
        }

        return false;
    }

    public static String getChunkOwner(ChunkPos pos) {
        ClaimedChunk claim = claimedChunks.get(pos);
        if (claim == null) return null;

        if (claim.isPlayerClaim()) {
            return resolvePlayerName(claim.owner);
        } else if (claim.isGroupClaim()) {
            return claim.group;
        }

        return null;
    }

    public static Set<ChunkPos> getPlayerClaims(UUID uuid) {
        Set<ChunkPos> result = new HashSet<>();
        for (Map.Entry<ChunkPos, ClaimedChunk> entry : claimedChunks.entrySet()) {
            ClaimedChunk claim = entry.getValue();
            if (claim.isPlayerClaim() && claim.owner.equals(uuid)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public static Set<ChunkPos> getGroupClaims(String groupName) {
        Set<ChunkPos> result = new HashSet<>();
        for (Map.Entry<ChunkPos, ClaimedChunk> entry : claimedChunks.entrySet()) {
            ClaimedChunk claim = entry.getValue();
            if (claim.isGroupClaim() && claim.group.equals(groupName)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public static String resolvePlayerName(UUID uuid) {
        return nameCache.computeIfAbsent(uuid, id -> {
            Optional<GameProfile> profile = serverReference.getUserCache().getByUuid(id);
            return profile.map(GameProfile::getName).orElse(id.toString());
        });
    }

    // ===== ClaimedChunk inner class =====

    public static class ClaimedChunk {
        private UUID owner;   // Null if group claim
        private String group; // Null if player claim
        private final Set<UUID> trustedPlayers = new HashSet<>();

        public static ClaimedChunk playerClaim(UUID owner) {
            ClaimedChunk c = new ClaimedChunk();
            c.owner = owner;
            return c;
        }

        public static ClaimedChunk groupClaim(String groupName) {
            ClaimedChunk c = new ClaimedChunk();
            c.group = groupName;
            return c;
        }

        public boolean isPlayerClaim() {
            return owner != null;
        }

        public boolean isGroupClaim() {
            return group != null;
        }

        public UUID getOwner() {
            return owner;
        }

        public String getGroupName() {
            return group;
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
}
