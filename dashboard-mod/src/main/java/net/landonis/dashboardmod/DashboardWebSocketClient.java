package net.landonis.dashboardmod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.MinecraftServer;

public class DashboardWebSocketClient {
    private static WebSocket webSocket;

    public static void connect(MinecraftServer server) {
        HttpClient client = HttpClient.newHttpClient();

        webSocket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:3020/ws/minecraft"), new WebSocketListener())
                .join();
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

