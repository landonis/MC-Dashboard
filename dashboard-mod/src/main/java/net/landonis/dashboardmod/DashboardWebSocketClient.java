package net.landonis.dashboardmod;

import okhttp3.*;
import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DashboardWebSocketClient {
    private static WebSocket webSocket;
    private static MinecraftServer server;
    private static boolean wasPreviouslyConnected = false;

    public static void connect() {
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        Request request = new Request.Builder()
                .url("ws://localhost:3020/ws/minecraft")
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                System.out.println("[DashboardMod] WebSocket connected.");

                // If this isn't the first connection, notify backend of reconnect
                if (wasPreviouslyConnected) {
                    JsonObject msg = new JsonObject();
                    msg.addProperty("event", "reconnected");
                    send(msg.toString());
                } else {
                    wasPreviouslyConnected = true;
                }

                startUpdateLoop();
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                    String type = json.has("type") ? json.get("type").getAsString() : "";

                    if (server == null) return;

                    switch (type) {
                        case "sendMessage":
                            String msg = json.get("content").getAsString();
                            server.getPlayerManager().broadcast(Text.of(msg), false);
                            break;

                        case "setDay":
                            server.getOverworld().setTimeOfDay(1000);
                            break;

                        case "listPlayers":
                            List<String> players = server.getPlayerManager()
                                    .getPlayerList()
                                    .stream()
                                    .map(p -> p.getEntityName())
                                    .collect(Collectors.toList());
                            JsonObject reply = new JsonObject();
                            reply.addProperty("event", "playerList");
                            reply.add("players", new Gson().toJsonTree(players));
                            send(reply.toString());
                            break;

                        default:
                            System.out.println("[DashboardMod] Unknown message type: " + type);
                    }
                } catch (Exception e) {
                    System.err.println("[DashboardMod] Error handling message: " + e.getMessage());
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                System.out.println("[DashboardMod] WebSocket closed: " + reason);
                retryConnection();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                System.err.println("[DashboardMod] WebSocket error: " + t.getMessage());
                retryConnection();
            }
        });
    }

    private static void retryConnection() {
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                System.out.println("[DashboardMod] Attempting reconnect...");
                connect();
            } catch (InterruptedException ignored) {}
        }).start();
    }

    public static void send(String json) {
        if (webSocket != null) {
            webSocket.send(json);
        }
    }

    public static void sendServerStatus() {
        if (server == null) return;

        JsonObject obj = new JsonObject();
        obj.addProperty("event", "serverStats");
        obj.addProperty("players", server.getCurrentPlayerCount());
        obj.addProperty("time", server.getOverworld().getTimeOfDay());
        obj.addProperty("tps", 20.0); // Static placeholder
        send(obj.toString());
    }

    public static void setServerInstance(MinecraftServer srv) {
        server = srv;
    }

    private static void startUpdateLoop() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    sendServerStatus();
                } catch (InterruptedException ignored) {}
            }
        }).start();
    }
}
