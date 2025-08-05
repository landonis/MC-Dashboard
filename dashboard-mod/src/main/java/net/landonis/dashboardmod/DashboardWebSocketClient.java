package net.landonis.dashboardmod;

import java.util.concurrent.CompletableFuture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.util.concurrent.CompletionStage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.text.Text;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.Formatting;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.util.UUID;
import java.util.Optional;

import com.mojang.authlib.GameProfile;

public class DashboardWebSocketClient {
    private static WebSocket webSocket;
    public static MinecraftServer serverInstance;
    private static boolean isConnected = false;

    public static void connect(MinecraftServer server) {
        serverInstance = server;
        HttpClient client = HttpClient.newHttpClient();

        try {
            webSocket = client.newWebSocketBuilder()
                    .buildAsync(URI.create("ws://localhost:3020/ws/minecraft"), new WebSocketListener())
                    .join();
            isConnected = true;
            System.out.println("[DashboardMod] Successfully connected to WebSocket");
        } catch (Exception e) {
            System.err.println("[DashboardMod] Failed to connect to WebSocket (this is normal if dashboard backend is not running): " + e.getMessage());
            isConnected = false;
        }
    }

    public static void setServerInstance(MinecraftServer server) {
        serverInstance = server;
    }

    public static void sendServerStatus() {
        if (isConnected && webSocket != null && serverInstance != null) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "server_status");
            message.addProperty("message", "Server started with Region Protection");
            webSocket.sendText(message.toString(), true);
        }
    }

    public static void sendMessage(String content) {
        if (isConnected && webSocket != null && serverInstance != null) {
            serverInstance.getPlayerManager().broadcast(Text.literal("[Dashboard] " + content).formatted(Formatting.AQUA), false);
    
            JsonObject message = new JsonObject();
            message.addProperty("type", "message_sent");
            message.addProperty("content", content);
            webSocket.sendText(message.toString(), true);
        }
    }

    public static void listPlayers() {
        if (isConnected && webSocket != null && serverInstance != null) {
            List<String> playerNames = serverInstance.getPlayerManager()
                .getPlayerList()
                .stream()
                .map(player -> player.getName().getString())
                .collect(Collectors.toList());
    
            JsonObject response = new JsonObject();
            response.addProperty("type", "players");
    
            JsonArray playersArray = new JsonArray();
            for (String name : playerNames) {
                playersArray.add(name);
            }
            response.add("players", playersArray);
            webSocket.sendText(response.toString(), true);
        }
    }

    public static void sendClaimUpdate(String player, ChunkPos pos, String action) {
        if (isConnected && webSocket != null) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "claim_update");
            message.addProperty("player", player);
            message.addProperty("chunkX", pos.x);
            message.addProperty("chunkZ", pos.z);
            message.addProperty("action", action);
            webSocket.sendText(message.toString(), true);
        }
    }

    public static void sendClaimsData() {
        if (isConnected && webSocket != null) {
            JsonObject response = new JsonObject();
            response.addProperty("type", "claims_data");
            
            JsonObject claimsObj = new JsonObject();
            Map<String, Set<ChunkPos>> allClaims = RegionManager.getAllClaims();
            
            for (Map.Entry<String, Set<ChunkPos>> entry : allClaims.entrySet()) {
                JsonArray chunks = new JsonArray();
                for (ChunkPos pos : entry.getValue()) {
                    JsonObject chunk = new JsonObject();
                    chunk.addProperty("x", pos.x);
                    chunk.addProperty("z", pos.z);
                    chunks.add(chunk);
                }
                claimsObj.add(entry.getKey(), chunks);
            }
            
            response.add("claims", claimsObj);
            webSocket.sendText(response.toString(), true);
        }
    }
    
    public static boolean isConnected() {
        return isConnected;
    }
    
    private static class WebSocketListener implements Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.println("[DashboardMod] WebSocket connected with Region Protection features.");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("[DashboardMod] Received: " + data);
            webSocket.request(1);
        
            try {
                JsonObject message;
                try {
                    message = JsonParser.parseString(data.toString()).getAsJsonObject();
                } catch (JsonSyntaxException e) {
                    System.err.println("[DashboardMod] Invalid JSON received: " + data);
                    return CompletableFuture.completedFuture(null);
                }
                
                String type = message.get("type").getAsString();
        
                switch (type) {
                    case "sendMessage":
                        if (message.has("content")) {
                            String content = message.get("content").getAsString();
                            sendMessage(content);
                        }
                        break;
                    case "setDay":
                        if (serverInstance != null) {
                            serverInstance.getOverworld().setTimeOfDay(1000);
                        }
                        break;
                    case "setNight":
                        if (serverInstance != null) {
                            serverInstance.getOverworld().setTimeOfDay(13000);
                        }
                        break;
                    case "listPlayers":
                        listPlayers();
                        break;
                    case "getClaims":
                        sendClaimsData();
                        break;
                    case "adminUnclaim":
                        if (message.has("chunkX") && message.has("chunkZ")) {
                            int x = message.get("chunkX").getAsInt();
                            int z = message.get("chunkZ").getAsInt();
                            ChunkPos pos = new ChunkPos(x, z);
                            String owner = RegionManager.getChunkOwner(pos);
                            GameProfile profile = DashboardWebSocketClient.serverInstance.getUserCache().getByName(owner);
                            UUID uuid = (profile != null) ? profile.getId() : null;

                            if (uuid != null && RegionManager.unclaimChunk(uuid, pos)) {
                                sendClaimUpdate("ADMIN", pos, "admin_unclaimed");
                            }
                        }
                        break;
                    default:
                        System.out.println("[DashboardMod] Unknown message type: " + type);
                        break;
                }
            } catch (Exception e) {
                System.err.println("[DashboardMod] Error parsing message: " + e.getMessage());
            }
        
            return CompletableFuture.completedFuture(null);
        }
        
        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("[DashboardMod] WebSocket error: " + error.getMessage());
            isConnected = false;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[DashboardMod] WebSocket closed: " + reason);
            isConnected = false;
            return CompletableFuture.completedFuture(null);
        }
    }
}
