package com.tyurvib.transactions.manager;

import com.tcoded.folialib.FoliaLib;
import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.model.FilterData;
import com.tyurvib.transactions.model.FilterType;
import com.tyurvib.transactions.model.TimePeriod;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class DatabaseManager {

    private final Transactions plugin;
    private final FoliaLib foliaLib;
    public Connection db;
    private final ConcurrentLinkedQueue<Runnable> dbWriteQueue = new ConcurrentLinkedQueue<>();
    private long lastCleanTime = 0;

    public DatabaseManager(Transactions plugin) {
        this.plugin = plugin;
        // Инициализируем FoliaLib
        this.foliaLib = new FoliaLib(plugin);
        initDatabase();
        startTasks();
    }

    private void initDatabase() {
        try {
            File dbFile = new File(plugin.getDataFolder(), "transactions.db");
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

            db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getPath());
            createTables();
            updateTableStructure();
            plugin.getLogger().info("SQLite база данных подключена.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Не удалось подключиться к SQLite: " + e.getMessage());
            plugin.getServer().getPluginManager().disablePlugin(plugin);
        }
    }

    private void createTables() throws SQLException {
        try (Statement stmt = db.createStatement()) {
            stmt.execute("""
            CREATE TABLE IF NOT EXISTS transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                type TEXT NOT NULL,
                key TEXT NOT NULL,
                amount REAL NOT NULL,
                balance_before REAL DEFAULT 0.0,
                balance_after REAL DEFAULT 0.0,
                timestamp INTEGER NOT NULL,
                rolled_back BOOLEAN NOT NULL DEFAULT 0,
                param1 TEXT, param2 TEXT, param3 TEXT,
                source TEXT
            );
            """);
            stmt.execute("""
            CREATE TABLE IF NOT EXISTS player_settings (
                player_uuid TEXT PRIMARY KEY,
                gmt_offset INTEGER DEFAULT 0,
                filter_type TEXT DEFAULT 'ALL',
                time_period TEXT DEFAULT 'ALL_TIME',
                show_balance BOOLEAN DEFAULT 1
            );
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player ON transactions (player_uuid);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON transactions (timestamp DESC);");
        }
    }

    private void updateTableStructure() {
        try (Statement stmt = db.createStatement()) {
            String[] columns = {
                    "ALTER TABLE transactions ADD COLUMN balance_before REAL DEFAULT 0.0;",
                    "ALTER TABLE transactions ADD COLUMN balance_after REAL DEFAULT 0.0;",
                    "ALTER TABLE transactions ADD COLUMN source TEXT;"
            };
            for (String sql : columns) {
                try { stmt.execute(sql); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка при обновлении колонок базы: " + e.getMessage());
        }
    }

    private void startTasks() {
        // Очередь записи (Асинхронно)
        foliaLib.getScheduler().runTimerAsync((task) -> {
            Runnable dbTask;
            while ((dbTask = dbWriteQueue.poll()) != null) {
                try {
                    dbTask.run();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, 5, 5, TimeUnit.SECONDS);

        lastCleanTime = System.currentTimeMillis();
        long cleanIntervalSec = Math.max(plugin.getConfigManager().cleanTransactionsPeriodMs / 1000, 20);

        // Очистка старых транзакций (Асинхронно)
        foliaLib.getScheduler().runTimerAsync((task) -> {
            if (System.currentTimeMillis() - lastCleanTime >= plugin.getConfigManager().cleanTransactionsPeriodMs) {
                clearAllTransactionsAsync();
            }
        }, cleanIntervalSec, cleanIntervalSec, TimeUnit.SECONDS);
    }

    public void queueSaveTransaction(UUID uuid, Transaction t) {
        dbWriteQueue.offer(() -> {
            String sql = "INSERT INTO transactions (player_uuid, type, key, amount, balance_before, balance_after, timestamp, rolled_back, param1, param2, param3, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement pstmt = db.prepareStatement(sql)) {
                pstmt.setString(1, uuid.toString());
                pstmt.setString(2, t.type.name());
                pstmt.setString(3, t.key);
                pstmt.setDouble(4, t.amount);
                pstmt.setDouble(5, t.balanceBefore);
                pstmt.setDouble(6, t.balanceAfter);
                pstmt.setLong(7, t.timestamp);
                pstmt.setBoolean(8, t.rolledBack);
                for (int i = 0; i < 3; i++) {
                    if (i < t.params.length && t.params[i] != null) pstmt.setString(9 + i, t.params[i]);
                    else pstmt.setNull(9 + i, Types.VARCHAR);
                }
                pstmt.setString(12, t.source != null ? t.source : null);
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Error saving transaction: " + e.getMessage());
            }
        });
    }

    public void updateRollbackStatus(UUID uuid, long timestamp, String key, boolean rolledBack) {
        dbWriteQueue.offer(() -> {
            try (PreparedStatement ps = db.prepareStatement(
                    "UPDATE transactions SET rolled_back = ? WHERE id = (SELECT id FROM transactions WHERE player_uuid = ? AND timestamp = ? AND key = ? LIMIT 1)")) {
                ps.setBoolean(1, rolledBack);
                ps.setString(2, uuid.toString());
                ps.setLong(3, timestamp);
                ps.setString(4, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to update rollback status: " + e.getMessage());
            }
        });
    }

    public void savePlayerSettings(Set<UUID> dirtyPlayers, Map<UUID, Integer> offsets, Map<UUID, FilterData> filters, Map<UUID, Boolean> showBalance) {
        if (dirtyPlayers.isEmpty()) return;
        // Запускаем асинхронно через FoliaLib, чтобы не блокировать поток вызова
        foliaLib.getScheduler().runAsync((task) -> {
            String sql = """
            INSERT INTO player_settings (player_uuid, gmt_offset, filter_type, time_period, show_balance)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(player_uuid) DO UPDATE SET
                gmt_offset = excluded.gmt_offset,
                filter_type = excluded.filter_type,
                time_period = excluded.time_period,
                show_balance = excluded.show_balance
            """;
            try (PreparedStatement pstmt = db.prepareStatement(sql)) {
                for (UUID uuid : dirtyPlayers) {
                    pstmt.setString(1, uuid.toString());
                    pstmt.setInt(2, offsets.getOrDefault(uuid, plugin.getConfigManager().defaultGmtOffset));
                    FilterData f = filters.getOrDefault(uuid, new FilterData());
                    pstmt.setString(3, f.filterType.name());
                    pstmt.setString(4, f.timePeriod.name());
                    pstmt.setBoolean(5, showBalance.getOrDefault(uuid, true));
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            } catch (SQLException e) {
                plugin.getLogger().warning("Error saving settings: " + e.getMessage());
            }
        });
    }

    public Map<UUID, List<Transaction>> loadAllTransactions() {
        Map<UUID, List<Transaction>> map = new HashMap<>();
        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM transactions ORDER BY timestamp DESC")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                List<String> paramsList = new ArrayList<>();
                for (int i = 1; i <= 3; i++) {
                    String p = rs.getString("param" + i);
                    if (p != null) paramsList.add(p);
                }

                Transaction t = new Transaction(
                        Type.valueOf(rs.getString("type")),
                        rs.getString("key"),
                        rs.getDouble("amount"),
                        rs.getDouble("balance_before"),
                        rs.getDouble("balance_after"),
                        paramsList.toArray(new String[0]),
                        rs.getLong("timestamp")
                );
                t.rolledBack = rs.getBoolean("rolled_back");
                t.source = rs.getString("source");
                map.computeIfAbsent(uuid, k -> new ArrayList<>()).add(t);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return map;
    }

    public void loadPlayerSettings(Map<UUID, Integer> offsets, Map<UUID, FilterData> filters, Map<UUID, Boolean> showBalance) {
        try (Statement stmt = db.createStatement(); ResultSet rs = stmt.executeQuery("SELECT * FROM player_settings")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                offsets.put(uuid, rs.getInt("gmt_offset"));
                try {
                    FilterData f = new FilterData();
                    f.filterType = FilterType.valueOf(rs.getString("filter_type"));
                    f.timePeriod = TimePeriod.valueOf(rs.getString("time_period"));
                    filters.put(uuid, f);
                } catch (Exception ignored) {
                    filters.put(uuid, new FilterData());
                }
                showBalance.put(uuid, rs.getBoolean("show_balance"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void clearAllTransactionsAsync() {
        foliaLib.getScheduler().runAsync((task) -> {
            try (Statement stmt = db.createStatement()) {
                stmt.executeUpdate("DELETE FROM transactions");

                // Возвращаемся в основной поток (или ближайший тикающий регион),
                // если clearCache требует синхронизации
                foliaLib.getScheduler().runNextTick((t) -> {
                    plugin.getTransactionManager().clearCache();
                    plugin.getLogger().info("Транзакции очищены.");
                    lastCleanTime = System.currentTimeMillis();
                });
            } catch (SQLException e) {
                plugin.getLogger().warning("Ошибка очистки: " + e.getMessage());
            }
        });
    }

    public void close() {
        // Отрабатываем оставшиеся задачи перед закрытием
        Runnable task;
        while ((task = dbWriteQueue.poll()) != null) {
            try { task.run(); } catch (Exception ignored) {}
        }
        if (db != null) {
            try { db.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}