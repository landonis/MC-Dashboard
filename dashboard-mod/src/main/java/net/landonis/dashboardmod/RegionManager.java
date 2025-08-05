package net.landonis.dashboardmod;

import com.google.gson.*;
import net.minecraft.util.math.ChunkPos;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RegionManager {
    private static final Map<String, Set<ChunkPos>> claims = new ConcurrentHashMap<>();
    private static final File CLAIM_FILE = new File("regionprotector_claims.json");
    
    // Thread-safe operations
    private static final Object SAVE_LOCK = new Object();

    public static boolean claimChunk(String player, ChunkPos pos) {
        Set<ChunkPos> playerClaims = claims.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        boolean added = playerClaims.add(pos);
        if (added) {
            saveClaims(); // Auto-save on claim
        }
        return added;
    }

    public static boolean unclaimChunk(String player, ChunkPos pos) {
        Set<ChunkPos> owned = claims.get(player);
        if (owned != null && owned.remove(pos)) {
            if (owned.isEmpty()) {
                claims.remove(player); // Clean up empty claim sets
            }
            saveClaims(); // Auto-save on unclaim
            return true;
        }
        return false;
    }

    public static boolean isClaimed(ChunkPos pos) {
        return claims.values().stream().anyMatch(set -> set.contains(pos));
    }

    public static boolean canEdit(String player, ChunkPos pos) {
        return claims.getOrDefault(player, Collections.emptySet()).contains(pos);
    }

    public static String getChunkOwner(ChunkPos pos) {
        for (Map.Entry<String, Set<ChunkPos>> entry : claims.entrySet()) {
            if (entry.getValue().contains(pos)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static Set<ChunkPos> getPlayerClaims(String player) {
        return new HashSet<>(claims.getOrDefault(player, Collections.emptySet()));
    }

    public static Map<String, Set<ChunkPos>> getAllClaims() {
        return new HashMap<>(claims);
    }

    public static void loadClaims() {
        claims.clear(); // Clear existing claims
        if (!CLAIM_FILE.exists()) return;
        try (Reader reader = new FileReader(CLAIM_FILE)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                Set<ChunkPos> chunks = new HashSet<>();
                for (JsonElement el : entry.getValue().getAsJsonArray()) {
                    JsonArray arr = el.getAsJsonArray();
                    chunks.add(new ChunkPos(arr.get(0).getAsInt(), arr.get(1).getAsInt()));
                }
                claims.put(entry.getKey(), ConcurrentHashMap.newKeySet(chunks));
            }
            System.out.println("[RegionProtector] Loaded " + claims.size() + " player claims");
        } catch (IOException e) {
            System.err.println("[RegionProtector] Error loading claims: " + e.getMessage());
        }
    }

    public static void saveClaims() {
        synchronized (SAVE_LOCK) {
            saveClaimsInternal();
        }
    }
    
    private static void saveClaimsInternal() {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Set<ChunkPos>> entry : claims.entrySet()) {
            JsonArray array = new JsonArray();
            for (ChunkPos pos : entry.getValue()) {
                JsonArray coords = new JsonArray();
                coords.add(pos.x);
                coords.add(pos.z);
                array.add(coords);
            }
            out.add(entry.getKey(), array);
        }

        try (Writer writer = new FileWriter(CLAIM_FILE)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(out, writer);
            System.out.println("[RegionProtector] Saved claims for " + claims.size() + " players to " + CLAIM_FILE.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[RegionProtector] Error saving claims: " + e.getMessage());
        }
    }
    
    public static int getTotalClaimsCount() {
        return claims.values().stream().mapToInt(Set::size).sum();
    }
    
    public static int getPlayerClaimsCount(String player) {
        return claims.getOrDefault(player, Collections.emptySet()).size();
    }
}
