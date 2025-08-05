package net.landonis.dashboardmod;

import java.util.*;

public class Group {
    public String name;
    public UUID owner;
    public Map<UUID, String> members = new HashMap<>(); // UUID → role

    public Group(String name, UUID owner) {
        this.name = name;
        this.owner = owner;
        this.members.put(owner, "owner");
    }

    public boolean isAdmin(UUID uuid) {
        return "admin".equals(roles.get(uuid)) || isOwner(uuid);
    }
    public boolean hasPermission(UUID uuid, String permission) {
        String role = members.get(uuid);
        if (role == null) return false;
        switch (role) {
            case "owner": return true;
            case "admin":
                return !permission.equals("manage_owner"); // admins can't override owner
            case "builder":
                return permission.equals("build") || permission.equals("claim");
            case "member":
                return permission.equals("build");
            case "viewer":
            default:
                return false;
        }
    }

    public void addMember(UUID uuid, String role) {
        members.put(uuid, role);
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
    }

    public boolean isMember(UUID uuid) {
        return members.containsKey(uuid);
    }

    public String getRole(UUID uuid) {
        return members.get(uuid);
    }
}
