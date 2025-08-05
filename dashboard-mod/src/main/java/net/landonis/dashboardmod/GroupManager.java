package net.landonis.dashboardmod;

import java.util.*;
import java.io.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;

public class GroupManager {
    private static final Map<String, Group> groups = new HashMap<>();
    private static final Gson gson = new Gson();
    private static Path dataFile;

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

    public static void saveGroups() {
        if (dataFile == null) return;
        try (Writer writer = Files.newBufferedWriter(dataFile)) {
            gson.toJson(groups, writer);
        } catch (IOException e) {
            System.err.println("[GroupManager] Failed to save groups: " + e.getMessage());
        }
    }

    public static boolean groupExists(String name) {
        return groups.containsKey(name);
    }

    public static Group getGroup(String name) {
        return groups.get(name);
    }

    public static void createGroup(String name, UUID owner) {
        groups.put(name, new Group(name, owner));
    }

    public static Collection<Group> getAllGroups() {
        return groups.values();
    }
}
