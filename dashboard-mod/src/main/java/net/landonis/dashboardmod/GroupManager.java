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


    public static void load(MinecraftServer server) {
        dataFile = server.getSavePath(WorldSavePath.ROOT).resolve("groups.json");
        if (Files.exists(dataFile)) {
            try (Reader reader = Files.newBufferedReader(dataFile)) {
                Map<String, Group> loaded = gson.fromJson(reader, new TypeToken<Map<String, Group>>(){}.getType());
                if (loaded != null) groups.putAll(loaded);
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
    public static Group get(String name) {
        return getGroup(name);
    }
    public static boolean exists(String name) {
        return groupExists(name);
    }
    public static boolean groupExists(String name) {
        return groups.containsKey(name.toLowerCase());
    }

    public static Group getGroup(String name) {
        return groups.get(name.toLowerCase());
    }

    public static void createGroup(String name, UUID owner) {
        groups.put(name, new Group(name.toLowerCase(), owner));
        save();
    }

    public static void deleteGroup(String name) {
        groups.remove(name.toLowerCase()); 
        save();
    }

    public static Collection<Group> getAllGroups() {
        return groups.values();
    }
    public static void invite(String groupName, UUID playerId) {
        invites.computeIfAbsent(playerId, k -> new ArrayList<>()).add(groupName.toLowerCase());
    }

    public static boolean accept(String groupName, UUID playerId) {
        List<String> userInvites = invites.getOrDefault(playerId, new ArrayList<>());
        if (!userInvites.contains(groupName.toLowerCase())) return false;

        Group group = groups.get(groupName);
        if (group == null) return false;

        group.addMember(playerId, "member");
        userInvites.remove(groupName.toLowerCase());
        return true;
    }

    public static boolean decline(String groupName, UUID playerId) {
        List<String> userInvites = invites.get(playerId);
        if (userInvites == null) return false;
        return userInvites.remove(groupName.toLowerCase());
    }

    public static List<String> getInvites(UUID playerId) {
        return invites.getOrDefault(playerId, Collections.emptyList());
    }
}
