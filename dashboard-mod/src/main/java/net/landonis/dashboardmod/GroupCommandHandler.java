package net.landonis.dashboardmod;

import net.landonis.dashboardmod.Group;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.mojang.brigadier.arguments.StringArgumentType;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

public class GroupCommandHandler {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("group")
                .then(CommandManager.literal("create")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .executes(GroupCommandHandler::create)))
                .then(CommandManager.literal("invite")
                    .then(CommandManager.argument("player", GameProfileArgumentType.gameProfile())
                        .then(CommandManager.argument("group", StringArgumentType.word())
                            .executes(GroupCommandHandler::invite))))
                .then(CommandManager.literal("accept")
                    .then(CommandManager.argument("group", StringArgumentType.word())
                        .executes(GroupCommandHandler::accept)))
                .then(CommandManager.literal("decline")
                    .then(CommandManager.argument("group", StringArgumentType.word())
                        .executes(GroupCommandHandler::decline)))
                .then(CommandManager.literal("invites")
                    .executes(GroupCommandHandler::invites))
                .then(CommandManager.literal("promote")
                    .then(CommandManager.argument("player", GameProfileArgumentType.gameProfile())
                        .then(CommandManager.argument("group", StringArgumentType.word())
                            .executes(GroupCommandHandler::promote))))
                .then(CommandManager.literal("demote")
                    .then(CommandManager.argument("player", GameProfileArgumentType.gameProfile())
                        .then(CommandManager.argument("group", StringArgumentType.word())
                            .executes(GroupCommandHandler::demote))))
            );
        });
    }

    private static int create(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = getPlayer(ctx);
        String name = StringArgumentType.getString(ctx, "name");

        if (GroupManager.exists(name)) {
            player.sendMessage(Text.literal("Group already exists.").formatted(Formatting.RED), false);
            return 0;
        }

        GroupManager.createGroup(name, player.getUuid());
        player.sendMessage(Text.literal("Group '" + name + "' created!").formatted(Formatting.GREEN), false);
        return 1;
    }

    private static int invite(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity sender = getPlayer(ctx);
        String groupName = StringArgumentType.getString(ctx, "group");
        Collection<GameProfile> targets;
        try {
            targets = GameProfileArgumentType.getProfileArgument(ctx, "player");
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendError(Text.literal("Invalid player specified."));
            return 0;
        }


        Group group = GroupManager.get(groupName);
        
        if (group == null) {
            sender.sendMessage(Text.literal("Group not found.").formatted(Formatting.RED), false);
            return 0;
        }

        if (!group.isAdmin(sender.getUuid())) {
            sender.sendMessage(Text.literal("You don’t have permission to invite players.").formatted(Formatting.RED), false);
            return 0;
        }

        for (GameProfile target : targets) {
            if (group.members.containsKey(target.getId())) {
                sender.sendMessage(Text.literal(target.getName() + " is already in the group.").formatted(Formatting.YELLOW), false);
            } else {
                GroupManager.invite(groupName, target.getId());
                sender.sendMessage(Text.literal("Invited " + target.getName() + " to " + groupName + ".").formatted(Formatting.GREEN), false);
            }
        }

        return 1;
    }

    private static int accept(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = getPlayer(ctx);
        String groupName = StringArgumentType.getString(ctx, "group");

        boolean success = GroupManager.accept(groupName, player.getUuid());
        if (success) {
            player.sendMessage(Text.literal("Joined group '" + groupName + "'.").formatted(Formatting.GREEN), false);
        } else {
            player.sendMessage(Text.literal("No invite found for group '" + groupName + "'.").formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int decline(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = getPlayer(ctx);
        String groupName = StringArgumentType.getString(ctx, "group");

        boolean success = GroupManager.decline(groupName, player.getUuid());
        if (success) {
            player.sendMessage(Text.literal("Declined invite to group '" + groupName + "'.").formatted(Formatting.YELLOW), false);
        } else {
            player.sendMessage(Text.literal("No invite found for group '" + groupName + "'.").formatted(Formatting.RED), false);
        }
        return 1;
    }

    private static int invites(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = getPlayer(ctx);
        List<String> invites = GroupManager.getInvites(player.getUuid());

        if (invites.isEmpty()) {
            player.sendMessage(Text.literal("You have no group invites.").formatted(Formatting.GRAY), false);
        } else {
            player.sendMessage(Text.literal("Pending group invites:").formatted(Formatting.GREEN), false);
            for (String name : invites) {
                player.sendMessage(Text.literal("- " + name).formatted(Formatting.YELLOW), false);
            }
        }
        return 1;
    }

    private static int promote(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity sender = getPlayer(ctx);
        String groupName = StringArgumentType.getString(ctx, "group");
        Collection<GameProfile> targets;
        try {
            targets = GameProfileArgumentType.getProfileArgument(ctx, "player");
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendError(Text.literal("Invalid player specified."));
            return 0;
        }


        Group group = GroupManager.get(groupName);
        if (group == null) {
            sender.sendMessage(Text.literal("Group not found.").formatted(Formatting.RED), false);
            return 0;
        }

        for (GameProfile target : targets) {
            if (!group.members.containsKey(target.getId())) {
                sender.sendMessage(Text.literal(target.getName() + " is not a group member.").formatted(Formatting.RED), false);
                continue;
            }

            if (!group.canPromote(sender.getUuid(), target.getId())) {
                sender.sendMessage(Text.literal("You can’t promote " + target.getName() + ".").formatted(Formatting.RED), false);
                continue;
            }

            group.promote(target.getId());
            sender.sendMessage(Text.literal("Promoted " + target.getName() + ".").formatted(Formatting.GREEN), false);
        }

        GroupManager.save();
        return 1;
    }

    private static int demote(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity sender = getPlayer(ctx);
        String groupName = StringArgumentType.getString(ctx, "group");
        Collection<GameProfile> targets;
        try {
            targets = GameProfileArgumentType.getProfileArgument(ctx, "player");
        } catch (CommandSyntaxException e) {
            ctx.getSource().sendError(Text.literal("Invalid player specified."));
            return 0;
        }

        Group group = GroupManager.get(groupName);
        if (group == null) {
            sender.sendMessage(Text.literal("Group not found.").formatted(Formatting.RED), false);
            return 0;
        }

        for (GameProfile target : targets) {
            if (!group.members.containsKey(target.getId())) {
                sender.sendMessage(Text.literal(target.getName() + " is not a group member.").formatted(Formatting.RED), false);
                continue;
            }

            if (!group.canDemote(sender.getUuid(), target.getId())) {
                sender.sendMessage(Text.literal("You can’t demote " + target.getName() + ".").formatted(Formatting.RED), false);
                continue;
            }

            group.demote(target.getId());
            sender.sendMessage(Text.literal("Demoted " + target.getName() + ".").formatted(Formatting.YELLOW), false);
        }

        GroupManager.save();
        return 1;
    }

    private static ServerPlayerEntity getPlayer(CommandContext<ServerCommandSource> ctx) {
        try {
            return ctx.getSource().getPlayerOrThrow();
        } catch (Exception e) {
            throw new RuntimeException("This command must be executed by a player.");
        }
    }
}
