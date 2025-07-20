
package net.landonis.dashboardmod;

import okhttp3.*;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.TimeUnit;

public class DashboardWebSocketClient {
    private static WebSocket webSocket;

    public static void connect() {
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build();
        Request request = new Request.Builder().url("ws://localhost:3020/ws/minecraft").build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                System.out.println("[DashboardMod] WebSocket connected.");
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("[DashboardMod] WebSocket error: " + t.getMessage());
            }
        });
    }

    public static void send(String json) {
        if (webSocket != null) {
            webSocket.send(json);
        }
    }

    public static void sendServerStatus(MinecraftServer server) {
        JsonObject obj = new JsonObject();
        obj.addProperty("event", "serverStats");
        obj.addProperty("players", server.getCurrentPlayerCount());
        obj.addProperty("time", server.getOverworld().getTimeOfDay());
        obj.addProperty("tps", 20.0); // Placeholder
        send(obj.toString());
    }
}
