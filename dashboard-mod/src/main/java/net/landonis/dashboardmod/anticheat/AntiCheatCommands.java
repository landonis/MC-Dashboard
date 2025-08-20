package net.landonis.dashboardmod.anticheat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.landonis.dashboardmod.DashboardMod;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Command handler for AntiCheat administration
 */
public class AntiCheatCommands {

    /**
     * Initializes the AntiCheat commands and registers them.
     * Called from DashboardMod.onInitialize().
     */
    public static void initialize() {
        CommandRegistrationCallback.EVENT.register(AntiCheatCommands::registerCommands);
    }

    /**
     * Register AntiCheat commands.
     */
    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher,
                                        CommandRegistryAccess registryAccess,
                                        CommandManager.RegistrationEnvironment environment) {

        dispatcher.register(CommandManager.literal("anticheat")
            .requires(source -> source.hasPermissionLevel(2)) // Require OP level 2+
            .then(CommandManager.literal("reset")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(AntiCheatCommands::resetPlayerViolations)))
            .then(CommandManager.literal("check")
                .then(CommandManager.argument("player", EntityArgumentType.player())
                    .executes(AntiCheatCommands::checkPlayerViolations)))
            .then(CommandManager.literal("status")
                .executes(AntiCheatCommands::showStatus))
        );
    }

    /**
     * Reset violations for a specific player.
     */
    private static int resetPlayerViolations(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");

            // Use DashboardMod's reset method instead of missing AntiCheatEventHandler
            DashboardMod.resetPlayerViolations(targetPlayer);

            context.getSource().sendFeedback(
                () -> Text.literal("§a[AntiCheat] Reset violations for " + targetPlayer.getName().getString()),
                true
            );

            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("§c[AntiCheat] Failed to reset violations: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Check violations for a specific player.
     */
    private static int checkPlayerViolations(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");

            // Use DashboardMod's built-in summary method instead of missing AntiCheatEventHandler
            String summary = DashboardMod.getPlayerViolationSummary(targetPlayer);

            context.getSource().sendFeedback(
                () -> Text.literal("§e[AntiCheat] " + summary),
                false
            );

            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("§c[AntiCheat] Failed to check violations: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Show general AntiCheat status.
     */
    private static int showStatus(CommandContext<ServerCommandSource> context) {
        try {
            MinecraftServer server = context.getSource().getServer();
            int playerCount = server.getPlayerManager().getPlayerList().size();

            context.getSource().sendFeedback(
                () -> Text.literal("§a[AntiCheat] Status: Active | Monitoring " + playerCount + " players"),
                false
            );

            context.getSource().sendFeedback(
                () -> Text.literal("§7[AntiCheat] Use '/anticheat check <player>' to check specific violations"),
                false
            );

            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("§c[AntiCheat] Failed to show status: " + e.getMessage()));
            return 0;
        }
    }
}
