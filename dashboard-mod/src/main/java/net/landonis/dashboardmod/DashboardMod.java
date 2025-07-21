package net.landonis.dashboardmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

public class DashboardMod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[DashboardMod] Initializing...");

        // Connect to WebSocket
        

        // When the server has started, pass it to the WebSocket client
        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            DashboardWebSocketClient.connect(server);
            DashboardWebSocketClient.setServerInstance(server);
            DashboardWebSocketClient.sendServerStatus();
        });
    }
}
