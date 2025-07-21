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
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("[DashboardMod] WebSocket error: " + error.getMessage());
        }
    }
}
