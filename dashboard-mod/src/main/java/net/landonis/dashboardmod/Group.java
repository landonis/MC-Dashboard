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
        String role = members.get(uuid);
        return "admin".equals(role) || isOwner(uuid);
    }
    public boolean isOwner(UUID uuid) {
            return this.owner.equals(uuid);
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
    public boolean canPromote(UUID actor, UUID target) {
        String actorRole = members.get(actor);
        String targetRole = members.get(target);
        return isOwner(actor) || ("admin".equals(actorRole) && !"owner".equals(targetRole));
    }
    
    public void promote(UUID target) {
        String current = members.getOrDefault(target, "member");
        if ("member".equals(current)) {
            members.put(target, "builder");
        } else if ("builder".equals(current)) {
            members.put(target, "admin");
        }
    }
    
    public boolean canDemote(UUID actor, UUID target) {
        String actorRole = members.get(actor);
        String targetRole = members.get(target);
        return isOwner(actor) || ("admin".equals(actorRole) && "builder".equals(targetRole));
    }
    
    public void demote(UUID target) {
        String current = members.getOrDefault(target, "member");
        if ("admin".equals(current)) {
            members.put(target, "builder");
        } else if ("builder".equals(current)) {
            members.put(target, "member");
        }
    }

    public boolean canInvite(UUID uuid) {
        return isOwner(uuid) || isAdmin(uuid);
    }

}
