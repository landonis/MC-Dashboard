package net.landonis.dashboardmod;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.util.*;

public class GroupManager {
    public static class Group {
        public String name;
        public UUID owner;
        public Map<UUID, String> members = new HashMap<>();
        public Set<UUID> invites = new HashSet<>();

        public Group(String name, UUID owner) {
            this.name = name;
            this.owner = owner;
            this.members.put(owner, "owner");
        }

        public boolean hasPermission(UUID uuid, String permission) {
            String role = members.get(uuid);
            if (role == null) return false;
            return switch (role) {
                case "owner", "admin" -> true;
                case "builder" -> permission.equals("build") || permission.equals("interact");
                case "member" -> permission.equals("interact");
                case "viewer" -> false;
                default -> false;
            };
        }

        public boolean isAdmin(UUID uuid) {
            String role = members.get(uuid);
            return "admin".equals(role) || "owner".equals(role);
        }

        public void promote(UUID uuid) {
            String current = members.get(uuid);
            switch (current) {
                case "viewer" -> members.put(uuid, "member");
                case "member" -> members.put(uuid, "builder");
                case "builder" -> members.put(uuid, "admin");
            }
        }

        public void demote(UUID uuid) {
            String current = members.get(uuid);
            switch (current) {
                case "admin" -> members.put(uuid, "builder");
                case "builder" -> members.put(uuid, "member");
                case "member" -> members.put(uuid, "viewer");
            }
        }

        public boolean canPromote(UUID actor, UUID target) {
            String actorRole = members.get(actor);
            String targetRole = members.get(target);
            return actorRole != null && targetRole != null
                    && isAdmin(actor)
                    && !"owner".equals(targetRole)
                    && (!"admin".equals(targetRole) || !"admin".equals(actorRole));
        }

        public boolean canDemote(UUID actor, UUID target) {
            return canPromote(actor, target); // same conditions
        }
    }

    private static final Map<String, Group> groups = new HashMap<>();
    private static final File FILE = new File("config/dashboardmod/groups.json");

    public static void createGroup(String name, UUID owner) {
        groups.put(name.toLowerCase(), new Group(name, owner));
        save();
    }

    public static boolean exists(String name) {
        return groups.containsKey(name.toLowerCase());
    }

    public static Group get(String name) {
        return groups.get(name.toLowerCase());
    }

    public static void invite(String groupName, UUID player) {
        Group group = get(groupName);
        if (group != null) {
            group.invites.add(player);
            save();
        }
    }

    public static boolean accept(String groupName, UUID player) {
        Group group = get(groupName);
        if (group != null && group.invites.remove(player)) {
            group.members.put(player, "viewer");
            save();
            return true;
        }
        return false;
    }

    public static boolean decline(String groupName, UUID player) {
        Group group = get(groupName);
        if (group != null) {
            boolean removed = group.invites.remove(player);
            save();
            return removed;
        }
        return false;
    }

    public static List<String> getInvites(UUID uuid) {
        List<String> result = new ArrayList<>();
        for (Group g : groups.values()) {
            if (g.invites.contains(uuid)) {
                result.add(g.name);
            }
        }
        return result;
    }

    public static void save() {
        try {
            FILE.getParentFile().mkdirs();
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Group> entry : groups.entrySet()) {
                Group group = entry.getValue();
                JsonObject obj = new JsonObject();
                obj.addProperty("owner", group.owner.toString());

                JsonObject membersJson = new JsonObject();
                for (Map.Entry<UUID, String> mem : group.members.entrySet()) {
                    membersJson.addProperty(mem.getKey().toString(), mem.getValue());
                }
                obj.add("members", membersJson);

                JsonArray invitesJson = new JsonArray();
                for (UUID uuid : group.invites) {
                    invitesJson.add(uuid.toString());
                }
                obj.add("invites", invitesJson);

                root.add(group.name.toLowerCase(), obj);
            }

            try (Writer writer = new FileWriter(FILE)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
        } catch (IOException e) {
            System.err.println("[DashboardMod] Failed to save groups: " + e.getMessage());
        }
    }

    public static void load(MinecraftServer server) {
        if (!FILE.exists()) return;
        try (Reader reader = new FileReader(FILE)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                String name = entry.getKey();
                JsonObject obj = entry.getValue().getAsJsonObject();
                UUID owner = UUID.fromString(obj.get("owner").getAsString());
                Group group = new Group(name, owner);

                if (obj.has("members")) {
                    JsonObject members = obj.getAsJsonObject("members");
                    for (Map.Entry<String, JsonElement> m : members.entrySet()) {
                        UUID id = UUID.fromString(m.getKey());
                        String role = m.getValue().getAsString();
                        group.members.put(id, role);
                    }
                }

                if (obj.has("invites")) {
                    for (JsonElement invite : obj.getAsJsonArray("invites")) {
                        group.invites.add(UUID.fromString(invite.getAsString()));
                    }
                }

                groups.put(name.toLowerCase(), group);
            }
        } catch (IOException | JsonParseException e) {
            System.err.println("[DashboardMod] Failed to load groups: " + e.getMessage());
        }
    }
}
