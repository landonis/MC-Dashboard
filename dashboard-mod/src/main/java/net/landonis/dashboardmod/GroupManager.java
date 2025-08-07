package net.landonis.dashboardmod;

import java.util.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

public class GroupManager {
    private static final Map<String, Group> groups = new HashMap<>();
    private static final Gson gson = new Gson();
    private static Path dataFile;
    private static final Map<UUID, List<String>> invites = new HashMap<>();

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT);
    }

    public static void load(MinecraftServer server) {
        dataFile = server.getSavePath(WorldSavePath.ROOT).resolve("groups.json");
        if (Files.exists(dataFile)) {
            try (Reader reader = Files.newBufferedReader(dataFile)) {
                Map<String, Group> loaded = gson.fromJson(reader, new TypeToken<Map<String, Group>>(){}.getType());
                if (loaded != null) {
                    // normalize keys on load
                    for (Map.Entry<String, Group> e : loaded.entrySet()) {
                        String key = norm(e.getKey());
                        Group g = e.getValue();
                        if (g != null) {
                            groups.put(key, g);
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[GroupManager] Failed to load groups: " + e.getMessage());
            }
        }
    }

    public static void save() {
        saveGroups();
    }

    public static void saveGroups() {
        if (dataFile == null) return;
        try (Writer writer = Files.newBufferedWriter(dataFile)) {
            gson.toJson(groups, writer);
        } catch (IOException e) {
            System.err.println("[GroupManager] Failed to save groups: " + e.getMessage());
        }
    }

    // -------- Core lookups (case-insensitive) --------
    public static Group get(String name) {
        return getGroup(name);
    }

    public static boolean exists(String name) {
        return groupExists(name);
    }

    public static boolean groupExists(String name) {
        return groups.containsKey(norm(name));
    }

    public static Group getGroup(String name) {
        return groups.get(norm(name));
    }

    public static Collection<Group> getAllGroups() {
        return groups.values();
    }

    // -------- Mutations (persist automatically) --------
    public static void createGroup(String name, UUID owner) {
        String key = norm(name);
        // Keep display name as provided; only the map key is lowercased
        groups.put(key, new Group(name, owner));
        save();
    }

    public static void deleteGroup(String name) {
        groups.remove(norm(name));
        save();
    }

    // -------- Membership helpers (encapsulation) --------
    public static boolean isMember(String groupName, UUID playerId) {
        Group g = getGroup(groupName);
        return g != null && g.isMember(playerId);
    }

    public static Map<UUID, String> getMembers(String groupName) {
        Group g = getGroup(groupName);
        if (g == null) return Collections.emptyMap();
        // expose read-only view so handlers can’t mutate internals accidentally
        return Collections.unmodifiableMap(g.members);
    }

    public static int getMemberCount(String groupName) {
        return getMembers(groupName).size();
    }

    public static boolean addMember(String groupName, UUID playerId, String role) {
        Group g = getGroup(groupName);
        if (g == null) return false;
        g.addMember(playerId, role);
        save();
        return true;
    }

    public static boolean removeMember(String groupName, UUID playerId) {
        Group g = getGroup(groupName);
        if (g == null) return false;
        if (!g.isMember(playerId)) return false;
        g.removeMember(playerId);
        save();
        return true;
    }

    // -------- Invites (store normalized names) --------
    public static void invite(String groupName, UUID playerId) {
        invites.computeIfAbsent(playerId, k -> new ArrayList<>()).add(norm(groupName));
    }

    public static boolean accept(String groupName, UUID playerId) {
        String key = norm(groupName);
        List<String> userInvites = invites.getOrDefault(playerId, new ArrayList<>());
        if (!userInvites.contains(key)) return false;

        Group group = groups.get(key);
        if (group == null) return false;

        group.addMember(playerId, "member");
        userInvites.remove(key);
        save();
        return true;
    }

    public static boolean decline(String groupName, UUID playerId) {
        String key = norm(groupName);
        List<String> userInvites = invites.get(playerId);
        if (userInvites == null) return false;
        return userInvites.remove(key);
    }

    public static List<String> getInvites(UUID playerId) {
        return invites.getOrDefault(playerId, Collections.emptyList());
    }
}
