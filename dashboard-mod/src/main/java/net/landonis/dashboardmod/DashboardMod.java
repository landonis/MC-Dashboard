
package net.landonis.dashboardmod;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class DashboardMod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[DashboardMod] Initializing...");

        DashboardWebSocketClient.connect();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DashboardWebSocketClient.sendServerStatus(server);
        });
    }
}
