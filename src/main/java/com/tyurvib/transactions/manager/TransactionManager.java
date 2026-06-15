package com.tyurvib.transactions.manager;

import com.tcoded.folialib.FoliaLib;
import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.model.FilterData;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;


public class TransactionManager {

    private final Transactions plugin;
    private final FoliaLib foliaLib;


    private final ConcurrentMap<UUID, List<Transaction>> transactionCache = new ConcurrentHashMap<>();

    /** UUID тех игроков, чей кеш был полностью загружен из БД. */
    private final Set<UUID> fullyLoaded = ConcurrentHashMap.newKeySet();

    private final ConcurrentMap<UUID, CompletableFuture<List<Transaction>>> loadingFutures = new ConcurrentHashMap<>();

    public final Set<UUID> dirtySettings = ConcurrentHashMap.newKeySet();

    public final Map<UUID, Integer>    playerGmtOffset = new ConcurrentHashMap<>();
    public final Map<UUID, FilterData> playerFilters   = new ConcurrentHashMap<>();
    public final Map<UUID, Boolean>    showBalance     = new ConcurrentHashMap<>();


    public final Map<UUID, Integer>     currentPage        = new ConcurrentHashMap<>();
    public final Map<UUID, String>      searchTargetPlayer = new ConcurrentHashMap<>();
    public final Map<UUID, Transaction> pendingRollback    = new ConcurrentHashMap<>();
    public final Map<UUID, UUID>        rollbackTargetUUID = new ConcurrentHashMap<>();
    public final Map<UUID, String>      rollbackTargetName = new ConcurrentHashMap<>();
    public final Set<UUID>              playerInSearchMode = ConcurrentHashMap.newKeySet();


    public final Set<UUID> payInProgress      = ConcurrentHashMap.newKeySet();
    public final Set<UUID> shopInProgress     = ConcurrentHashMap.newKeySet();
    public final Set<UUID> ecoInProgress      = ConcurrentHashMap.newKeySet();
    public final Set<UUID> externalInProgress = ConcurrentHashMap.newKeySet();

    public TransactionManager(Transactions plugin) {
        this.plugin   = plugin;
        this.foliaLib = plugin.getFoliaLib();
        // loadPlayerSettings уже ждёт завершения внутри DatabaseManager (join)
        plugin.getDatabaseManager().loadPlayerSettings(playerGmtOffset, playerFilters, showBalance);
        startSaveTask();
    }

    public CompletableFuture<List<Transaction>> getTransactionsAsync(UUID uuid) {
        // Быстрый путь: данные уже полностью загружены из БД
        if (fullyLoaded.contains(uuid)) {
            List<Transaction> cached = transactionCache.get(uuid);
            if (cached != null) return CompletableFuture.completedFuture(cached);
        }

        // Дедупликация: если уже грузим — возвращаем тот же Future
        return loadingFutures.computeIfAbsent(uuid, key ->
                plugin.getDatabaseManager()
                        .loadTransactionsAsync(uuid)           // выполняется в DB-потоке
                        .whenComplete((result, throwable) -> {
                            loadingFutures.remove(uuid);
                            if (throwable != null) {
                                plugin.getLogger().warning(
                                        "Ошибка загрузки транзакций для " + uuid + ": " + throwable.getMessage());
                                return;
                            }
                            if (result != null) {
                                List<Transaction> fresh = Collections.synchronizedList(result);
                                // Если addTransaction() добавил записи до загрузки — переносим их в начало
                                List<Transaction> existing = transactionCache.get(uuid);
                                if (existing != null) {
                                    synchronized (existing) {
                                        for (int i = existing.size() - 1; i >= 0; i--) {
                                            fresh.add(0, existing.get(i));
                                        }
                                        if (fresh.size() > 1000) {
                                            fresh.subList(1000, fresh.size()).clear();
                                        }
                                    }
                                }
                                transactionCache.put(uuid, fresh);
                                fullyLoaded.add(uuid);
                            }
                        })
        );
    }

    public List<Transaction> getTransactions(UUID uuid) {
        try {
            return getTransactionsAsync(uuid).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Таймаут получения транзакций для " + uuid);
            return transactionCache.getOrDefault(uuid, Collections.emptyList());
        }
    }

    // ─── Добавление транзакции ───────────────────────────────────────────────

    public void addTransaction(UUID uuid, Transaction t) {
        if (t.amount < 0 || (t.amount == 0 && t.type != Type.YELLOW)) return;

        // Обновляем кеш только если он уже был загружен из БД,
        // чтобы не создавать неполный список из 1 записи вместо 1000+
        if (fullyLoaded.contains(uuid)) {
            List<Transaction> list = transactionCache.get(uuid);
            if (list != null) {
                synchronized (list) {
                    list.add(0, t); // вставляем в начало (список хранится DESC по времени)
                    if (list.size() > 1000) list.remove(list.size() - 1);
                }
            }
        }

        plugin.getDatabaseManager().queueSaveTransaction(uuid, t);
    }


    public boolean isDirty(UUID uuid) { return dirtySettings.contains(uuid); }
    public void markDirty(UUID uuid)  { dirtySettings.add(uuid); }

    private void startSaveTask() {
        foliaLib.getScheduler().runTimerAsync(task -> saveDirtyPlayers(), 15, 15, TimeUnit.SECONDS);
    }

    /**
     * Async-сохранение грязных настроек.
     * UUID удаляются из dirtySettings ПОСЛЕ подтверждения записи (в DatabaseManager).
     */
    public void saveDirtyPlayers() {
        if (dirtySettings.isEmpty()) return;
        plugin.getDatabaseManager()
                .savePlayerSettings(dirtySettings, playerGmtOffset, playerFilters, showBalance);
    }

    /**
     * Sync-сохранение — вызывается из onDisable, блокирует до завершения.
     */
    public void saveDirtyPlayersSync() {
        if (dirtySettings.isEmpty()) return;
        plugin.getDatabaseManager()
                .savePlayerSettingsSync(dirtySettings, playerGmtOffset, playerFilters, showBalance);
    }

    // ─── Очистка при выходе игрока ───────────────────────────────────────────

    public void onPlayerQuit(UUID uuid) {
        transactionCache.remove(uuid);
        fullyLoaded.remove(uuid);
        loadingFutures.remove(uuid);
        currentPage.remove(uuid);
        searchTargetPlayer.remove(uuid);
        pendingRollback.remove(uuid);
        rollbackTargetUUID.remove(uuid);
        rollbackTargetName.remove(uuid);
        playerInSearchMode.remove(uuid);
    }

    public void clearCache() {
        transactionCache.clear();
        fullyLoaded.clear();
    }

    // ─── Экспорт в TXT ──────────────────────────────────────────────────────

    /**
     * Загружает транзакции игрока и сохраняет в файл.
     * Файловый I/O выполняется в отдельном потоке — ни main, ни DB не блокируются.
     */
    public void downloadTransactionsToTxt(UUID adminUUID, UUID targetUUID, String targetName) {
        Player admin = Bukkit.getPlayer(adminUUID);
        if (admin == null) return;

        getTransactionsAsync(targetUUID).thenAcceptAsync(list -> {
            if (list.isEmpty()) {
                foliaLib.getScheduler().runNextTick(t -> admin.sendMessage("§cNo transactions found."));
                return;
            }

            File folder = new File(plugin.getDataFolder(), "downloads");
            folder.mkdirs();

            String safeName = targetName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            File file = new File(folder, safeName + "_transactions.txt");

            int offset = playerGmtOffset.getOrDefault(adminUUID, plugin.getConfigManager().defaultGmtOffset);
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
            sdf.setTimeZone(TimeZone.getTimeZone("GMT" + (offset >= 0 ? "+" : "") + offset));


            List<Transaction> ordered = new ArrayList<>(list);
            Collections.reverse(ordered);

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                writer.write("Transactions for: " + targetName);
                writer.newLine();
                writer.newLine();
                for (Transaction t : ordered) {
                    String desc = ChatColor.stripColor(
                            plugin.getConfigManager().getTranslatedDescription(t));
                    writer.write(String.format("[%s] %s", sdf.format(new Date(t.timestamp)), desc));
                    writer.newLine();
                }
                foliaLib.getScheduler().runNextTick(t ->
                        admin.sendMessage("§aSaved to " + file.getAbsolutePath()));
            } catch (IOException e) {
                plugin.getLogger().warning("Ошибка записи файла транзакций: " + e.getMessage());
                foliaLib.getScheduler().runNextTick(t ->
                        admin.sendMessage("§cFailed to save transactions to file."));
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Ошибка при скачивании транзакций для " + targetUUID + ": " + ex.getMessage());
            foliaLib.getScheduler().runNextTick(t ->
                    admin.sendMessage("§cAn error occurred while downloading transactions."));
            return null;
        });
    }


    public double parseAmount(String s) {
        s = s.toUpperCase().replace(",", "").trim();
        double multiplier = 1;
        if      (s.endsWith("K")) { multiplier = 1_000L;              s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("M")) { multiplier = 1_000_000L;          s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("B")) { multiplier = 1_000_000_000L;      s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("T")) { multiplier = 1_000_000_000_000L;  s = s.substring(0, s.length() - 1); }
        try {
            return Double.parseDouble(s) * multiplier;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}