package net.landonis.dashboardmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ActionResult;

public class DashboardMod implements ModInitializer {

    @Override
    public void onInitialize() {
        System.out.println("[DashboardMod] Initializing with Region Protection...");

        // Initialize region protection
        RegionManager.loadClaims();
        RegionCommandHandler.registerCommands();
        
        // Register region protection events
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClient) return ActionResult.PASS;
            
            if (!RegionProtection.canPlayerModifyBlock(player, hitResult.getBlockPos())) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (world.isClient) return ActionResult.PASS;
            
            if (!RegionProtection.canPlayerModifyBlock(player, pos)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // When the server has started, connect to dashboard and save claims on shutdown
        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            DashboardWebSocketClient.connect(server);
            DashboardWebSocketClient.setServerInstance(server);
            DashboardWebSocketClient.sendServerStatus();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            RegionManager.saveClaims();
        });
    }
}
