package com.tyurvib.transactions;

import com.google.gson.*;
import com.tcoded.folialib.FoliaLib;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Objects;

public class Updater {

    private final Transactions plugin;
    private final String modrinthProjectId;
    private String latestVersion;
    private boolean updateAvailable;
    private final FoliaLib foliaLib;

    public Updater(Transactions plugin, String modrinthProjectId) {
        this.plugin = plugin;
        this.modrinthProjectId = modrinthProjectId;
        this.updateAvailable = false;
        this.latestVersion = plugin.getDescription().getVersion();
        this.foliaLib = new FoliaLib(plugin);
    }

    public void checkForUpdates() {
        foliaLib.getScheduler().runAsync((scheduledTask) -> {
            try {
                URL url = new URL("https://api.modrinth.com/v2/project/" + modrinthProjectId + "/version");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", "Transactions-Plugin/" + plugin.getDescription().getVersion());
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                if (connection.getResponseCode() != 200) {
                    plugin.getLogger().warning("Failed to check for updates. Modrinth API returned: " + connection.getResponseCode());
                    return;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String jsonResponse = reader.lines().collect(java.util.stream.Collectors.joining());
                    JsonArray versions = JsonParser.parseString(jsonResponse).getAsJsonArray();

                    if (versions.size() > 0) {
                        for (JsonElement versionElement : versions) {
                            JsonObject version = versionElement.getAsJsonObject();
                            JsonArray loaders = version.getAsJsonArray("loaders");
                            boolean supportsPaper = false;
                            for (JsonElement loader : loaders) {
                                String loaderName = loader.getAsString();
                                if ("paper".equalsIgnoreCase(loaderName) || "folia".equalsIgnoreCase(loaderName) || "bukkit".equalsIgnoreCase(loaderName)) {
                                    supportsPaper = true;
                                    break;
                                }
                            }
                            if (supportsPaper) {
                                latestVersion = version.get("version_number").getAsString();
                                break;
                            }
                        }

                        String currentVersion = plugin.getDescription().getVersion();
                        if (isVersionNewer(latestVersion, currentVersion)) {
                            this.updateAvailable = true;
                            foliaLib.getScheduler().runNextTick((t) ->
                                    Bukkit.getConsoleSender().sendMessage(ChatColor.YELLOW + "[Transactions] " + ChatColor.RED +
                                            "A new version (" + ChatColor.YELLOW + latestVersion + ChatColor.RED + ") is available! " +
                                            "Your current version is " + ChatColor.YELLOW + currentVersion + ChatColor.RED + ".")
                            );
                        } else {
                            plugin.getLogger().info("You are running the latest version of the plugin.");
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not check for updates: " + e.getMessage());
            }
        });
    }

    private boolean isVersionNewer(String latest, String current) {
        if (latest == null || current == null) return false;
        if (latest.equals(current)) return false;

        String[] latestParts = latest.replaceAll("[^0-9.]", "").split("\\.");
        String[] currentParts = current.replaceAll("[^0-9.]", "").split("\\.");

        for (int i = 0; i < Math.max(latestParts.length, currentParts.length); i++) {
            int l = i < latestParts.length ? Integer.parseInt(latestParts[i]) : 0;
            int c = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            if (l > c) return true;
            if (l < c) return false;
        }
        return latestParts.length > currentParts.length;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }
}