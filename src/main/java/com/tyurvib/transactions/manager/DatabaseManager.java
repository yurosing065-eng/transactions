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
import java.util.concurrent.*;

/**
 * DatabaseManager — версия 2.0
 *
 * Ключевые исправления:
 *  1. Один выделенный однопоточный ExecutorService для ВСЕХ операций с БД —
 *     Connection больше не используется из нескольких потоков одновременно.
 *  2. Настоящий батч INSERT через addBatch()/executeBatch() вместо
 *     последовательных executeUpdate() внутри одной транзакции.
 *  3. FoliaLib берётся из плагина (не создаётся второй экземпляр).
 *  4. PreparedStatement для INSERT кешируется на время батч-сессии.
 *  5. Race condition в saveDirtyPlayers устранён — UUID удаляем поштучно
 *     уже ПОСЛЕ успешного сохранения.
 */
public class DatabaseManager {

    // ─── Внутренний тип задачи для очереди ──────────────────────────────────

    private record WriteTask(UUID uuid, Transaction transaction) {}

    // ─── Поля ────────────────────────────────────────────────────────────────

    private final Transactions plugin;
    private final FoliaLib foliaLib;

    /** Единственный поток, который когда-либо трогает `db`. */
    private final ExecutorService dbThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transactions-db");
        t.setDaemon(true);
        return t;
    });

    private Connection db;

    /** Очередь транзакций, ожидающих INSERT. */
    private final ConcurrentLinkedQueue<WriteTask> writeQueue = new ConcurrentLinkedQueue<>();

    private volatile long lastCleanTime = 0;

    // ─── Конструктор ─────────────────────────────────────────────────────────

    public DatabaseManager(Transactions plugin) {
        this.plugin = plugin;
        // FIX: берём FoliaLib из плагина, не создаём второй экземпляр
        this.foliaLib = plugin.getFoliaLib();

        // Инициализация БД строго в DB-потоке
        submitToDb(() -> {
            initDatabase();
            return null;
        }).join(); // блокируем onEnable до готовности БД

        startTasks();
    }

    // ─── Утилита: отправить задачу в DB-поток ────────────────────────────────

    /** Отправляет задачу в DB-поток и возвращает Future. */
    private <T> CompletableFuture<T> submitToDb(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, dbThread);
    }

    /** Вариант без возвращаемого значения. */
    private CompletableFuture<Void> runOnDb(ThrowingRunnable task) {
        return CompletableFuture.runAsync(() -> {
            try {
                task.run();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, dbThread);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ─── Инициализация ───────────────────────────────────────────────────────

    /** Вызывается ТОЛЬКО из DB-потока. */
    private void initDatabase() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) dataFolder.mkdirs();

            File dbFile = new File(dataFolder, "transactions.db");
            db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getPath());

            try (Statement pragma = db.createStatement()) {
                pragma.execute("PRAGMA journal_mode=WAL;");
                pragma.execute("PRAGMA synchronous=NORMAL;");
                pragma.execute("PRAGMA cache_size=10000;");
                pragma.execute("PRAGMA temp_store=MEMORY;");
                pragma.execute("PRAGMA mmap_size=30000000;");
            }

            createTables();
            updateTableStructure();
            plugin.getLogger().info("SQLite база данных подключена и оптимизирована.");
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
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_player_history ON transactions (player_uuid, timestamp DESC);");
        }
    }

    private void updateTableStructure() {
        try (Statement stmt = db.createStatement()) {
            for (String sql : new String[]{
                    "ALTER TABLE transactions ADD COLUMN balance_before REAL DEFAULT 0.0;",
                    "ALTER TABLE transactions ADD COLUMN balance_after REAL DEFAULT 0.0;",
                    "ALTER TABLE transactions ADD COLUMN source TEXT;"
            }) {
                try { stmt.execute(sql); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Ошибка при обновлении колонок: " + e.getMessage());
        }
    }

    // ─── Периодические задачи ────────────────────────────────────────────────

    private void startTasks() {
        // Батч-запись каждые 5 секунд
        foliaLib.getScheduler().runTimerAsync(task -> flushWriteQueue(), 5, 5, TimeUnit.SECONDS);

        // Автоочистка старых транзакций
        lastCleanTime = System.currentTimeMillis();
        long intervalSec = Math.max(plugin.getConfigManager().cleanTransactionsPeriodMs / 1000L, 60L);
        foliaLib.getScheduler().runTimerAsync(task -> {
            long period = plugin.getConfigManager().cleanTransactionsPeriodMs;
            if (period > 0 && System.currentTimeMillis() - lastCleanTime >= period) {
                clearAllTransactionsAsync();
            }
        }, intervalSec, intervalSec, TimeUnit.SECONDS);
    }

    /**
     * FIX: настоящий батч через addBatch()/executeBatch().
     * Один PreparedStatement — много строк — один коммит.
     * Выполняется в DB-потоке.
     */
    // Вызывается НАПРЯМУЮ внутри DB-потока — без runOnDb!
    private void flushWriteQueueInternal() throws SQLException {
        if (writeQueue.isEmpty()) return;

        String sql = """
        INSERT INTO transactions
          (player_uuid, type, key, amount, balance_before, balance_after,
           timestamp, rolled_back, param1, param2, param3, source)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        db.setAutoCommit(false);
        try (PreparedStatement pstmt = db.prepareStatement(sql)) {
            int count = 0;
            WriteTask task;
            while ((task = writeQueue.poll()) != null && count < 1000) {
                Transaction t = task.transaction();
                pstmt.setString(1, task.uuid().toString());
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
                pstmt.setString(12, t.source);
                pstmt.addBatch();
                count++;
            }
            pstmt.executeBatch();
            db.commit();
        } catch (SQLException e) {
            try { db.rollback(); } catch (SQLException ignored) {}
            plugin.getLogger().severe("Ошибка пакетной записи в БД: " + e.getMessage());
            throw e;
        } finally {
            try { db.setAutoCommit(true); } catch (SQLException ignored) {}
        }
    }
    // Обёртка для периодической задачи — отправляет в DB-поток
    private void flushWriteQueue() {
        if (writeQueue.isEmpty()) return;
        runOnDb(this::flushWriteQueueInternal)
                .exceptionally(ex -> {
                    plugin.getLogger().severe("Ошибка flush очереди: " + ex.getMessage());
                    return null;
                });
    }

    // ─── Публичное API ───────────────────────────────────────────────────────

    public void queueSaveTransaction(UUID uuid, Transaction t) {
        writeQueue.offer(new WriteTask(uuid, t));
    }

    public void updateRollbackStatus(UUID uuid, long timestamp, String key, boolean rolledBack) {
        runOnDb(() -> {
            String sql = "UPDATE transactions SET rolled_back = ? WHERE player_uuid = ? AND timestamp = ? AND key = ?";
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                ps.setBoolean(1, rolledBack);
                ps.setString(2, uuid.toString());
                ps.setLong(3, timestamp);
                ps.setString(4, key);
                ps.executeUpdate();
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to update rollback status: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Загрузить транзакции конкретного игрока асинхронно.
     * Возвращает Future — вызывающий код сам решает, что делать с результатом.
     */
    public CompletableFuture<List<Transaction>> loadTransactionsAsync(UUID uuid) {
        return submitToDb(() -> {
            List<Transaction> list = new ArrayList<>(300);
            String sql = "SELECT * FROM transactions WHERE player_uuid = ? ORDER BY timestamp DESC LIMIT 1000";
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapTransaction(rs));
                    }
                }
            }
            return list;
        });
    }

    /**
     * Загрузить настройки всех игроков — вызывается при старте плагина
     * из основного потока, поэтому синхронно ждём завершения.
     */
    public void loadPlayerSettings(Map<UUID, Integer> offsets,
                                   Map<UUID, FilterData> filters,
                                   Map<UUID, Boolean> showBalance) {
        submitToDb(() -> {
            try (Statement stmt = db.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM player_settings")) {
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
            }
            return null;
        }).join(); // блокируем до загрузки — настройки нужны сразу
    }

    /**
     * FIX: UUID удаляем из dirtySet поштучно ПОСЛЕ успешного сохранения,
     * чтобы не потерять markDirty(), пришедший во время сохранения.
     */
    public void savePlayerSettings(Set<UUID> dirtyPlayers,
                                   Map<UUID, Integer> offsets,
                                   Map<UUID, FilterData> filters,
                                   Map<UUID, Boolean> showBalance) {
        if (dirtyPlayers.isEmpty()) return;
        // Снапшот — только список UUID для итерации
        Set<UUID> snapshot = new HashSet<>(dirtyPlayers);
        runOnDb(() -> executeSettingsBatch(snapshot, offsets, filters, showBalance))
                .thenRun(() -> dirtyPlayers.removeAll(snapshot))
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Ошибка сохранения настроек: " + ex.getMessage());
                    return null;
                });
    }

    public void savePlayerSettingsSync(Set<UUID> dirtyPlayers,
                                       Map<UUID, Integer> offsets,
                                       Map<UUID, FilterData> filters,
                                       Map<UUID, Boolean> showBalance) {
        if (dirtyPlayers.isEmpty()) return;
        Set<UUID> snapshot = new HashSet<>(dirtyPlayers);
        submitToDb(() -> {
            executeSettingsBatch(snapshot, offsets, filters, showBalance);
            return null;
        }).join();
        dirtyPlayers.removeAll(snapshot);
    }

    private void executeSettingsBatch(Set<UUID> players,
                                      Map<UUID, Integer> offsets,
                                      Map<UUID, FilterData> filters,
                                      Map<UUID, Boolean> showBalance) throws SQLException {
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
            for (UUID uuid : players) {
                pstmt.setString(1, uuid.toString());
                pstmt.setInt(2, offsets.getOrDefault(uuid, plugin.getConfigManager().defaultGmtOffset));
                FilterData f = filters.getOrDefault(uuid, new FilterData());
                pstmt.setString(3, f.filterType.name());
                pstmt.setString(4, f.timePeriod.name());
                pstmt.setBoolean(5, showBalance.getOrDefault(uuid, true));
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    public void clearAllTransactionsAsync() {
        runOnDb(() -> {
            try (Statement stmt = db.createStatement()) {
                stmt.executeUpdate("DELETE FROM transactions");
                stmt.execute("VACUUM;");
            }
            lastCleanTime = System.currentTimeMillis();
            // Очистка кеша — в следующем тике главного потока
            foliaLib.getScheduler().runNextTick(t -> {
                plugin.getTransactionManager().clearCache();
                plugin.getLogger().info("База данных очищена и сжата.");
            });
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Ошибка очистки БД: " + ex.getMessage());
            return null;
        });
    }

    /**
     * Вызывается из onDisable — сначала сбрасываем очередь, потом закрываем Connection.
     */
    public void close() {
        submitToDb(() -> {
            if (!writeQueue.isEmpty()) {
                plugin.getLogger().info("Сохранение оставшихся транзакций перед выключением...");
                flushWriteQueueInternal();
            }
            return null;
        }).join();

        dbThread.shutdown();
        try {
            if (!dbThread.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("DB-поток не завершился за 10 секунд.");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (db != null) {
            try {
                if (!db.isClosed()) db.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Ошибка при закрытии БД: " + e.getMessage());
            }
        }
    }

    // ─── Внутренние утилиты ──────────────────────────────────────────────────

    private Transaction mapTransaction(ResultSet rs) throws SQLException {
        String[] params = new String[3];
        for (int i = 0; i < 3; i++) {
            params[i] = rs.getString("param" + (i + 1));
        }
        Transaction t = new Transaction(
                Type.valueOf(rs.getString("type")),
                rs.getString("key"),
                rs.getDouble("amount"),
                rs.getDouble("balance_before"),
                rs.getDouble("balance_after"),
                params,
                rs.getLong("timestamp")
        );
        String src = rs.getString("source");
        t.source = (src != null && !src.isEmpty()) ? src : null;
        t.rolledBack = rs.getBoolean("rolled_back");
        return t;
    }
}