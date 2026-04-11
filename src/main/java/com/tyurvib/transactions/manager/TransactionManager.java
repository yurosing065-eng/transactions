package com.tyurvib.transactions.manager;

import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.model.FilterData;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class TransactionManager {

    private final Transactions plugin;
    private final ConcurrentMap<UUID, List<Transaction>> transactionCache = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();

    public final Map<UUID, Integer> playerGmtOffset = new HashMap<>();
    public final Map<UUID, FilterData> playerFilters = new HashMap<>();
    public final Map<UUID, Boolean> showBalance = new HashMap<>();

    public final Map<UUID, Integer> currentPage = new HashMap<>();
    public final Map<UUID, String> searchTargetPlayer = new HashMap<>();
    public final Set<UUID> playerInSearchMode = ConcurrentHashMap.newKeySet();
    public final Map<UUID, Transaction> pendingRollback = new HashMap<>();
    public final Map<UUID, UUID> rollbackTargetUUID = new HashMap<>();
    public final Map<UUID, String> rollbackTargetName = new HashMap<>();
    public final Set<UUID> payInProgress = ConcurrentHashMap.newKeySet();
    public final Set<UUID> ecoInProgress = ConcurrentHashMap.newKeySet();

    public TransactionManager(Transactions plugin) {
        this.plugin = plugin;
        startSaveTask();
    }

    public CompletableFuture<List<Transaction>> getTransactionsAsync(UUID uuid) {
        if (transactionCache.containsKey(uuid)) {
            return CompletableFuture.completedFuture(transactionCache.get(uuid));
        }


        return CompletableFuture.supplyAsync(() -> {
            List<Transaction> list = new ArrayList<>();
            String sql = "SELECT * FROM transactions WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 1000";

            try (PreparedStatement ps = plugin.getDatabaseManager().db.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        List<String> params = new ArrayList<>();
                        for (int i = 1; i <= 3; i++) {
                            String p = rs.getString("param" + i);
                            if (p != null && !p.isEmpty()) params.add(p);
                        }

                        Transaction t = new Transaction(
                                Type.valueOf(rs.getString("type")),
                                rs.getString("key"),
                                rs.getDouble("amount"),
                                rs.getDouble("balance_before"),
                                rs.getDouble("balance_after"),
                                params.toArray(new String[0]),
                                rs.getLong("timestamp")
                        );

                        t.rolledBack = rs.getBoolean("rolled_back");
                        list.add(t);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Ошибка загрузки транзакций для " + uuid + ": " + e.getMessage());
            }

            transactionCache.put(uuid, list);
            return list;

        }, runnable -> plugin.getServer().getAsyncScheduler().runNow(plugin, task -> runnable.run()));
    }
    private void startSaveTask() {
        plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, (task) -> {
            saveDirtyPlayers();
        }, 15, 15, TimeUnit.SECONDS);
    }


    public void addTransaction(UUID uuid, Transaction t) {
        if (t.amount < 0 || (t.amount == 0 && t.type != Type.YELLOW)) return;

        transactionCache.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>())).add(0, t);

        dirtyPlayers.add(uuid);
        plugin.getDatabaseManager().queueSaveTransaction(uuid, t);
    }
    public List<Transaction> getTransactions(UUID uuid) {
        try {
            return getTransactionsAsync(uuid).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении транзакций для " + uuid);
            return transactionCache.getOrDefault(uuid, new ArrayList<>());
        }
    }

    public void clearCache() {
        transactionCache.clear();
    }

    public void saveDirtyPlayers() {
        if (dirtyPlayers.isEmpty()) return;
        plugin.getDatabaseManager().savePlayerSettings(dirtyPlayers, playerGmtOffset, playerFilters, showBalance);
        dirtyPlayers.clear();
    }

    public void markDirty(UUID uuid) {
        dirtyPlayers.add(uuid);
    }

    public void downloadTransactionsToTxt(UUID adminUUID, UUID targetUUID, String targetName) {
        List<Transaction> list = getTransactions(targetUUID);
        if (list.isEmpty()) {
            Bukkit.getPlayer(adminUUID).sendMessage("§cNo transactions found.");
            return;
        }
        File folder = new File(plugin.getDataFolder(), "downloads");
        folder.mkdirs();
        File file = new File(folder, targetName + "_transactions.txt");

        int offset = playerGmtOffset.getOrDefault(adminUUID, plugin.getConfigManager().defaultGmtOffset);
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT" + (offset >= 0 ? "+" : "") + offset));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write("Transactions for: " + targetName);
            writer.newLine(); writer.newLine();
            for (Transaction t : list) {
                String desc = ChatColor.stripColor(plugin.getConfigManager().getTranslatedDescription(t));
                writer.write(String.format("[%s] %s", sdf.format(new Date(t.timestamp)), desc));
                writer.newLine();
            }
            Bukkit.getPlayer(adminUUID).sendMessage("§aSaved to " + file.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public double parseAmount(String s) {
        s = s.toUpperCase().replace(",", "").trim();
        double multiplier = 1;
        if (s.endsWith("K")) { multiplier = 1_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("M")) { multiplier = 1_000_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("B")) { multiplier = 1_000_000_000; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("T")) { multiplier = 1_000_000_000_000L; s = s.substring(0, s.length() - 1); }
        try { return Double.parseDouble(s) * multiplier; } catch (NumberFormatException ex) { return -1; }
    }
}