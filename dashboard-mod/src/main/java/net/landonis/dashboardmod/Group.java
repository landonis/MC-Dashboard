package net.landonis.dashboardmod;

import java.util.*;

public class Group {
    public String name;
    public UUID owner;
    public Map<UUID, String> members = new HashMap<>(); // UUID → role
    private Map<UUID, String> roles = new HashMap<>();


    public Group(String name, UUID owner) {
        this.name = name;
        this.owner = owner;
        this.members.put(owner, "owner");
    }

    public boolean isAdmin(UUID uuid) {
        return "admin".equals(roles.get(uuid)) || isOwner(uuid);
    }
    public boolean isOwner(UUID uuid) {
        return "owner".equals(roles.get(uuid));
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
        String actorRole = roles.get(actor);
        String targetRole = roles.get(target);
        return isOwner(actor) || ("admin".equals(actorRole) && !"owner".equals(targetRole));
    }
    
    public void promote(UUID target) {
        String current = roles.getOrDefault(target, "member");
        if ("member".equals(current)) {
            roles.put(target, "builder");
        } else if ("builder".equals(current)) {
            roles.put(target, "admin");
        }
    }
    
    public boolean canDemote(UUID actor, UUID target) {
        String actorRole = roles.get(actor);
        String targetRole = roles.get(target);
        return isOwner(actor) || ("admin".equals(actorRole) && "builder".equals(targetRole));
    }
    
    public void demote(UUID target) {
        String current = roles.getOrDefault(target, "member");
        if ("admin".equals(current)) {
            roles.put(target, "builder");
        } else if ("builder".equals(current)) {
            roles.put(target, "member");
        }
    }

}
