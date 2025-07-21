package net.landonis.dashboardmod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;

public class DashboardWebSocketClient {
    private static WebSocket webSocket;
    private static MinecraftServer serverInstance;

    public static void connect(MinecraftServer server) {
        serverInstance = server;  // Save server instance
        HttpClient client = HttpClient.newHttpClient();

        webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:3020/ws/minecraft"), new WebSocketListener())
                .join();
    }

    public static void setServerInstance(MinecraftServer server) {
        serverInstance = server;
    }

    public static void sendServerStatus() {
        if (webSocket != null && serverInstance != null) {
            String message = "{\"type\": \"server_status\", \"message\": \"Server started\"}";
            webSocket.sendText(message, true);
        } else {
            System.err.println("[DashboardMod] Cannot send status – WebSocket or server not initialized.");
        }
    }

    private static class WebSocketListener implements Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.println("[DashboardMod] WebSocket connected.");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("[DashboardMod] Received: " + data);
            webSocket.request(1);
        
            try {
                JsonObject message = JsonParser.parseString(data.toString()).getAsJsonObject();
                String type = message.get("type").getAsString();
        
                switch (type) {
                    case "sendMessage":
                        if (message.has("content")) {
                            String content = message.get("content").getAsString();
                            DashboardWebSocketClient.sendMessage(content);
                        }
                        break;
                    case "setDay":
                        if (server != null) {
                            server.getOverworld().setTimeOfDay(1000);
                        }
                        break;
                    case "listPlayers":
                        DashboardWebSocketClient.listPlayers();
                        break;
                    // Add more command types here
                    default:
                        System.out.println("[DashboardMod] Unknown message type: " + type);
                        break;
                }
            } catch (Exception e) {
                System.err.println("[DashboardMod] Error parsing message: " + e.getMessage());
            }
        
            return null;
        }
        

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("[DashboardMod] WebSocket error: " + error.getMessage());
        }
    }
}

public static void sendMessage(String content) {
    if (webSocket != null && server != null) {
        // Broadcast to players
        server.getPlayerManager().broadcast(Text.of("[Dashboard] " + content), false);

        // Send confirmation via WebSocket
        JsonObject message = new JsonObject();
        message.addProperty("type", "message_sent");
        message.addProperty("content", content);

        webSocket.sendText(message.toString(), true);
    } else {
        System.err.println("[DashboardMod] Cannot send message: missing server or websocket.");
    }
}


public static void listPlayers() {
    if (webSocket != null && server != null) {
        List<String> playerNames = server.getPlayerManager().getPlayerList().stream()
                .map(player -> player.getEntityName())
                .collect(Collectors.toList());

        JsonObject response = new JsonObject();
        response.addProperty("type", "players");

        JsonArray playersArray = new JsonArray();
        for (String name : playerNames) {
            playersArray.add(name);
        }

        // Always return the players array, even if it's empty
        response.add("players", playersArray);
        webSocket.sendText(response.toString(), true);
    } else {
        System.err.println("[DashboardMod] Cannot list players: missing server or websocket.");
    }
}

