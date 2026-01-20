package com.tyurvib.transactions;

import com.google.gson.JsonObject;

import com.google.gson.JsonParser;
import com.djrapitops.vaultevents.events.economy.PlayerDepositEvent;
import com.djrapitops.vaultevents.events.economy.PlayerWithdrawEvent;
import net.bestemor.villagermarket.event.interact.BuyShopItemsEvent;
import net.bestemor.villagermarket.event.interact.SellShopItemsEvent;
import net.bestemor.villagermarket.shop.ShopItem;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.*;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Transactions extends JavaPlugin implements Listener, TabExecutor {

    enum Type {INCOME, EXPENSE, YELLOW}

    enum FilterType {ALL, INCOME, EXPENSE, PAY, OTHER}
    enum TimePeriod {ALL_TIME, LAST_7_DAYS, LAST_30_DAYS}

    class FilterData {
        FilterType filterType = FilterType.ALL;
        TimePeriod timePeriod = TimePeriod.ALL_TIME;
    }

    class Transaction {
        Type type;
        String key;
        String[] params;
        double amount;
        long timestamp;
        boolean rolledBack = false;

        Transaction(Type type, String key, double amount, String[] params, long timestamp) {
            this.type = type;
            this.key = key;
            this.params = params;
            this.amount = amount;
            this.timestamp = timestamp;
        }

        Transaction(Type type, String key, double amount, String... params) {
            this(type, key, amount, params, System.currentTimeMillis());
        }
    }

    private static final String SELECTED_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTIxOTI4ZWE2N2QzYThiOTdkMjEyNzU4ZjE1Y2NjYWMxMDI0Mjk1YjE4NWIzMTkyNjQ4NDRmNGM1ZTFlNjFlIn19fQ==";
    private static final String UNSELECTED_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjVlZjY4ZGNiZDU4MjM0YmE3YWVlMmFkOTFjYTZmYTdjZTIzZjlhMzIzNDViNDhkNmU1ZjViODZhNjhiNWIifX19";
    private static final String SEARCH_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjc0OGYyMTM1ODhkYmY0NDE1Y2UyNGZlNjZkZTM1MjY4MTZiZjM1ZGY4ZTM5OGY5OGVmZWMyZmIwODk1NmEzIn19fQ==";
    private long saveIntervalTicks;
    private static final String PLAYER_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDY3ZGNlNDY0NTM0OWU0MWE3ZjM1Nzk3ZTJiOTI3OWUzNWE2NWY1ZTgxYTM0NDk2ODg1ZDI3MjY4ZjM2OTEzOSJ9fX0=";
    private long lastCleanTime = 0;
    private final Map<UUID, Long> lastExternalDepositTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastExternalWithdrawTime = new ConcurrentHashMap<>();


    private static final long THROTTLE_INTERVAL_MS = 500;
    private static final double THROTTLE_MIN_AMOUNT = 50.0;

    private boolean logExternalTransactions = true;
    private Connection db;
    private final ConcurrentMap<UUID, List<Transaction>> transactionCache = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> payInProgress = ConcurrentHashMap.newKeySet(); // ← только для /pay!
    private final Set<UUID> playerInSearchMode = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Runnable> dbWriteQueue = new ConcurrentLinkedQueue<>();
    private int dbTaskId = -1;
    private int maxDisplayTransactionRange = 90; // ← ЭТОЙ СТРОЧКИ НЕ БЫЛО!
    Map<UUID, Integer> currentPage = new HashMap<>();
    Map<UUID, Integer> playerGmtOffset = new HashMap<>();
    Map<UUID, FilterData> playerFilters = new HashMap<>();
    Map<UUID, Boolean> showBalance = new HashMap<>();
    File dataFolder;
    private Economy economy;
    private final Set<UUID> ecoInProgress = ConcurrentHashMap.newKeySet();
    private int defaultGmtOffset;
    private String prefix;
    private int taskId;
    private String incomeMessageColor;
    private String incomeAmountColor;
    private String expenseMessageColor;
    private String expenseAmountColor;
    private String yellowMessageColor;
    private String yellowAmountColor;
    private boolean showStatsButton;
    private boolean allowBalanceToggle;
    private long cleanTransactionsPeriodMs;
    private Map<String, String> translations;
    private Updater updater;
    private String modrinthProjectId = "GbElTVjA";
    private final Map<UUID, String> searchTargetPlayer = new HashMap<>();
    private final Map<UUID, Transaction> pendingRollback = new HashMap<>();
    private final Map<UUID, UUID> rollbackTargetUUID = new HashMap<>();
    private final Map<UUID, String> rollbackTargetName = new HashMap<>();
    private final Set<UUID> pendingClean = new HashSet<>();
    private DecimalFormat amountFormatter = createDefaultFormatter();
    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("lang/ru.yml", false);
        saveResource("lang/en.yml", false);
        saveResource("lang/de.yml", false);
        saveResource("lang/zh.yml", false);
        saveResource("lang/tr.yml", false);
        saveResource("lang/sv.yml", false);
        saveResource("lang/pt.yml", false);
        saveResource("lang/pl.yml", false);
        saveResource("lang/ko.yml", false);
        saveResource("lang/ja.yml", false);
        saveResource("lang/it.yml", false);
        saveResource("lang/fr.yml", false);
        saveResource("lang/es.yml", false);
        saveResource("lang/zh-hant.yml", false);

        updater = new Updater(this, modrinthProjectId);
        updater.checkForUpdates();
        loadConfigValues();
        lastCleanTime = System.currentTimeMillis();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("transactions").setExecutor(this);
        getCommand("transactions").setTabCompleter(this);

        if (!setupEconomy()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        initDatabase();
        loadAllFromDatabase();
        getServer().getAsyncScheduler().runAtFixedRate(this, (task) -> {
            Runnable dbTask;
            while ((dbTask = dbWriteQueue.poll()) != null) {
                try { dbTask.run(); } catch (Exception e) {
                    getLogger().severe("Ошибка в очереди БД: " + e.getMessage());
                    e.printStackTrace();
                }
            }
            if (System.currentTimeMillis() % 15000 < 1000) {
                saveDirtyPlayers();
            }
        }, 5, 5, TimeUnit.SECONDS);
        long cleanIntervalSec = Math.max(cleanTransactionsPeriodMs / 1000, 20);
        getServer().getAsyncScheduler().runAtFixedRate(this, (task) -> {
            if (System.currentTimeMillis() - lastCleanTime >= cleanTransactionsPeriodMs) {
                clearAllTransactionsAsync();
            }
        }, cleanIntervalSec, cleanIntervalSec, TimeUnit.SECONDS);
    }

    void clearAllTransactions() {
        try (Statement stmt = db.createStatement()) {
            stmt.executeUpdate("DELETE FROM transactions");
            transactionCache.clear();
            getLogger().info("Транзакции очищены. Настройки игроков сохранены.");
        } catch (SQLException e) {
            getLogger().warning("Ошибка очистки: " + e.getMessage());
        }
    }
    private DecimalFormat createDefaultFormatter() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator('.');
        DecimalFormat df = new DecimalFormat("#,###.##", symbols);
        df.setGroupingUsed(true);
        df.setMinimumFractionDigits(0);
        df.setMaximumFractionDigits(2);
        return df;
    }
    @Override
    public void onDisable() {
        Runnable task;
        while ((task = dbWriteQueue.poll()) != null) {
            try { task.run(); } catch (Exception ignored) {}
        }
        saveDirtyPlayers();

        if (db != null) {
            try {
                db.close();
                getLogger().info("SQLite соединение закрыто.");
            } catch (SQLException e) {
                getLogger().warning("Ошибка при закрытии БД: " + e.getMessage());
            }
        }
    }
    private void clearAllTransactionsAsync() {
        try (Statement stmt = db.createStatement()) {
            stmt.executeUpdate("DELETE FROM transactions");
            Bukkit.getScheduler().runTask(this, () -> {
                transactionCache.clear();
                getLogger().info("Транзакции очищены (асинхронно).");
                lastCleanTime = System.currentTimeMillis();
            });
        } catch (SQLException e) {
            getLogger().warning("Ошибка очистки: " + e.getMessage());
        }
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.isOp() && updater.isUpdateAvailable()) {
            getServer().getGlobalRegionScheduler().runDelayed(this, (s) -> {
                player.sendMessage(getTranslation("update-available", player.getName(), updater.getLatestVersion(),
                        "https://modrinth.com/plugin/" + modrinthProjectId + "/versions"));
            }, 60);
        }
    }
    private void initDatabase() {
        try {
            File dbFile = new File(getDataFolder(), "transactions.db");
            db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getPath());
            createTables();
            getLogger().info("SQLite база данных подключена: " + dbFile.getAbsolutePath());
        } catch (SQLException e) {
            getLogger().severe("Не удалось подключиться к SQLite: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void createTables() throws SQLException {
        String sqlTransactions = """
        CREATE TABLE IF NOT EXISTS transactions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            player_uuid TEXT NOT NULL,
            type TEXT NOT NULL,
            key TEXT NOT NULL,
            amount REAL NOT NULL,
            timestamp INTEGER NOT NULL,
            rolled_back BOOLEAN NOT NULL DEFAULT 0,
            param1 TEXT,
            param2 TEXT,
            param3 TEXT
        );
        """;

        String sqlSettings = """
        CREATE TABLE IF NOT EXISTS player_settings (
            player_uuid TEXT PRIMARY KEY,
            gmt_offset INTEGER DEFAULT 0,
            filter_type TEXT DEFAULT 'ALL',
            time_period TEXT DEFAULT 'ALL_TIME',
            show_balance BOOLEAN DEFAULT 1
        );
        """;

        String[] indexes = {
                "CREATE INDEX IF NOT EXISTS idx_player ON transactions (player_uuid);",
                "CREATE INDEX IF NOT EXISTS idx_timestamp ON transactions (timestamp DESC);",
                "CREATE INDEX IF NOT EXISTS idx_type ON transactions (type);",
                "CREATE INDEX IF NOT EXISTS idx_key ON transactions (key);"
        };

        try (Statement stmt = db.createStatement()) {
            stmt.execute(sqlTransactions);
            stmt.execute(sqlSettings);
            for (String index : indexes) {
                stmt.execute(index);
            }
        }
    }

    private void saveTransactionToDatabase(UUID uuid, Transaction t) {
        String sql = """
        INSERT INTO transactions 
        (player_uuid, type, key, amount, timestamp, rolled_back, param1, param2, param3)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement pstmt = db.prepareStatement(sql)) {
            pstmt.setString(1, uuid.toString());
            pstmt.setString(2, t.type.name());
            pstmt.setString(3, t.key);
            pstmt.setDouble(4, t.amount);
            pstmt.setLong(5, t.timestamp);
            pstmt.setBoolean(6, t.rolledBack);

            for (int i = 0; i < 3; i++) {
                if (i < t.params.length && t.params[i] != null) {
                    pstmt.setString(7 + i, t.params[i]);
                } else {
                    pstmt.setNull(7 + i, java.sql.Types.VARCHAR);
                }
            }

            pstmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().warning("Ошибка сохранения транзакции в БД: " + e.getMessage());
        }
    }

    void saveDirtyPlayers() {
        if (dirtyPlayers.isEmpty()) return;

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
                pstmt.setInt(2, playerGmtOffset.getOrDefault(uuid, defaultGmtOffset));

                FilterData filter = playerFilters.getOrDefault(uuid, new FilterData());
                pstmt.setString(3, filter.filterType.name());
                pstmt.setString(4, filter.timePeriod.name());

                pstmt.setBoolean(5, showBalance.getOrDefault(uuid, true));
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        } catch (SQLException e) {
            getLogger().warning("Ошибка сохранения настроек игроков: " + e.getMessage());
        } finally {
            dirtyPlayers.clear();
        }
    }

    void loadAllFromDatabase() {
        String sql = "SELECT * FROM transactions ORDER BY timestamp DESC";
        String settingsSql = "SELECT * FROM player_settings WHERE player_uuid = ?";

        try (Statement stmt = db.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            Map<UUID, List<Transaction>> tempMap = new HashMap<>();

            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("player_uuid"));
                String[] params = getParamsFromResultSet(rs);

                Transaction t = new Transaction(
                        Type.valueOf(rs.getString("type")),
                        rs.getString("key"),
                        rs.getDouble("amount"),
                        params,
                        rs.getLong("timestamp")
                );
                t.rolledBack = rs.getBoolean("rolled_back");
                tempMap.computeIfAbsent(uuid, k -> new ArrayList<>()).add(t);
            }

            transactionCache.putAll(tempMap);

            for (UUID uuid : tempMap.keySet()) {
                try (PreparedStatement pstmt = db.prepareStatement(settingsSql)) {
                    pstmt.setString(1, uuid.toString());
                    try (ResultSet rs2 = pstmt.executeQuery()) {
                        if (rs2.next()) {
                            playerGmtOffset.put(uuid, rs2.getInt("gmt_offset"));

                            try {
                                FilterData filter = new FilterData();
                                filter.filterType = FilterType.valueOf(rs2.getString("filter_type"));
                                filter.timePeriod = TimePeriod.valueOf(rs2.getString("time_period"));
                                playerFilters.put(uuid, filter);
                            } catch (Exception ignored) {
                                playerFilters.put(uuid, new FilterData());
                            }

                            showBalance.put(uuid, rs2.getBoolean("show_balance"));
                        }
                    }
                }
            }

            getLogger().info("Загружено транзакций: " + transactionCache.values().stream().mapToLong(List::size).sum());
        } catch (SQLException e) {
            getLogger().severe("Ошибка загрузки данных из SQLite: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String[] getParamsFromResultSet(ResultSet rs) throws SQLException {
        List<String> params = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String param = rs.getString("param" + i);
            if (param != null && !param.isEmpty()) {
                params.add(param);
            }
        }
        return params.toArray(new String[0]);
    }
    private long parseTimePeriod(String period) {
        try {
            Pattern pattern = Pattern.compile("^(\\d+)([smhdw]|mo|y)$");
            Matcher matcher = pattern.matcher(period.toLowerCase());
            if (!matcher.matches()) {
                return TimeUnit.DAYS.toMillis(14);
            }
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            switch (unit) {
                case "s": return TimeUnit.SECONDS.toMillis(value);
                case "m": return TimeUnit.MINUTES.toMillis(value);
                case "h": return TimeUnit.HOURS.toMillis(value);
                case "d": return TimeUnit.DAYS.toMillis(value);
                case "w": return TimeUnit.DAYS.toMillis(value * 7);
                case "mo": return TimeUnit.DAYS.toMillis(value * 30);
                case "y": return TimeUnit.DAYS.toMillis(value * 365);
                default: return TimeUnit.DAYS.toMillis(14);
            }
        } catch (NumberFormatException e) {
            return TimeUnit.DAYS.toMillis(14);
        }
    }

    private void loadConfigValues() {
        reloadConfig();
        prefix = getConfig().getString("prefix", "💰");
        showStatsButton = getConfig().getBoolean("show-stats-button", true);
        allowBalanceToggle = getConfig().getBoolean("allow-balance-toggle", true);
        incomeMessageColor = getConfig().getString("income-message-color", "§f");
        incomeAmountColor = getConfig().getString("income-amount-color", "§a");
        expenseMessageColor = getConfig().getString("expense-message-color", "§f");
        expenseAmountColor = getConfig().getString("expense-amount-color", "§c");
        yellowMessageColor = getConfig().getString("yellow-message-color", "§f");
        yellowAmountColor = getConfig().getString("yellow-amount-color", "§e");
        cleanTransactionsPeriodMs = parseTimePeriod(getConfig().getString("clean-transactions-period", "14d"));
        defaultGmtOffset = getConfig().getInt("default-gmt-offset", 0); // Новый параметр конфига
        if (!allowBalanceToggle) {
            for (UUID uuid : showBalance.keySet()) {
                showBalance.put(uuid, false);
            }
        }
        long saveIntervalSeconds = getConfig().getLong("save-interval-seconds", 60);
        logExternalTransactions = getConfig().getBoolean("log-external-transactions", true);
        saveIntervalTicks = saveIntervalSeconds * 20;
        String language = getConfig().getString("language", "en");
        File langFile = new File(getDataFolder() + "/lang", language + ".yml");
        if (!langFile.exists()) {
            langFile = new File(getDataFolder() + "/lang", "en.yml");
        }
        YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        translations = new HashMap<>();
        for (String key : langConfig.getKeys(false)) {
            translations.put(key, langConfig.getString(key));
        }
    }

    private String getTranslation(String key, String... placeholders) {
        String translated = translations.getOrDefault(key, key);
        for (int i = 0; i < placeholders.length; i++) {
            translated = translated.replace("%p" + (i + 1) + "%", placeholders[i]);
        }
        return translated;
    }

    private String stripColor(String text) {
        return ChatColor.stripColor(text);
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    void addTransaction(UUID uuid, Transaction t) {
        if (t.amount <= 0) return;

        transactionCache.computeIfAbsent(uuid, k -> new ArrayList<>()).add(0, t);
        dirtyPlayers.add(uuid);

        dbWriteQueue.offer(() -> saveTransactionToDatabase(uuid, t));
    }
    List<Transaction> getTransactions(UUID uuid) {
        return transactionCache.getOrDefault(uuid, Collections.emptyList());
    }

    public static ItemStack getHead(String base64) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) Objects.requireNonNull(item.getItemMeta());
        try {
            String skinJson = new String(Base64.getDecoder().decode(base64));
            JsonObject skinObject = JsonParser.parseString(skinJson).getAsJsonObject();
            String url = skinObject.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "");
            PlayerTextures textures = profile.getTextures();
            URL urlObject = new URL(url);
            textures.setSkin(urlObject);
            profile.setTextures(textures);
            skullMeta.setOwnerProfile(profile);
        } catch (IllegalArgumentException | MalformedURLException e) {
            return item;
        }
        item.setItemMeta(skullMeta);
        return item;
    }

    private String getTranslatedDescription(Transaction t) {
        String translated = translations.getOrDefault(t.key, t.key);
        for (int i = 0; i < t.params.length; i++) {
            translated = translated.replace("%p" + (i + 1) + "%", t.params[i]);
        }
        String messageColor, amountColor;
        switch (t.type) {
            case INCOME:
                messageColor = incomeMessageColor;
                amountColor = incomeAmountColor;
                break;
            case EXPENSE:
                messageColor = expenseMessageColor;
                amountColor = expenseAmountColor;
                break;
            case YELLOW:
                messageColor = yellowMessageColor;
                amountColor = yellowAmountColor;
                break;
            default:
                messageColor = "§f";
                amountColor = "§a";
        }
        String formattedAmount = "(" + (t.type == Type.INCOME ? "+" : t.type == Type.EXPENSE ? "-" : "") + amountFormatter.format(t.amount) + " " + prefix + ")";
        return messageColor + translated + " " + amountColor + formattedAmount;
    }

    void openGUI(Player player, int page, UUID targetUUID, String targetName) {
        player.getScheduler().run(this, (scheduledTask) -> {
            FilterData filter = playerFilters.computeIfAbsent(player.getUniqueId(), k -> new FilterData());
            List<Transaction> list = getTransactions(targetUUID);
            List<Transaction> filteredList = new ArrayList<>();
            long cutoffTimestamp = 0;

            if (filter.timePeriod == TimePeriod.LAST_7_DAYS) {
                cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
            } else if (filter.timePeriod == TimePeriod.LAST_30_DAYS) {
                cutoffTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30);
            }

            for (Transaction t : list) {
                if (filter.timePeriod == TimePeriod.ALL_TIME || t.timestamp >= cutoffTimestamp) {
                    if (filter.filterType == FilterType.ALL ||
                            (filter.filterType == FilterType.INCOME && t.type == Type.INCOME) ||
                            (filter.filterType == FilterType.EXPENSE && t.type == Type.EXPENSE) ||
                            (filter.filterType == FilterType.PAY && (t.key.equals("transaction-pay-sent") || t.key.equals("transaction-pay-received"))) ||
                            (filter.filterType == FilterType.OTHER && !(t.key.equals("transaction-pay-sent") || t.key.equals("transaction-pay-received")))) {
                        filteredList.add(t);
                    }
                }
            }

            filteredList.sort((t1, t2) -> Long.compare(t2.timestamp, t1.timestamp));

            Inventory inv = Bukkit.createInventory(null, 54, getTranslation("gui-title") + targetName + " (#" + (page + 1) + ")");
            int transactionsPerPage = 45;
            int start = page * transactionsPerPage;
            int end = Math.min(filteredList.size(), start + transactionsPerPage);

            OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetUUID);
            double currentBalance = economy.getBalance(targetPlayer);

            List<Double> balanceAfterEach = new ArrayList<>(filteredList.size());
            double tempBalance = currentBalance;
            for (Transaction t : filteredList) {
                balanceAfterEach.add(tempBalance);
                if (t.type == Type.INCOME) tempBalance -= t.amount;
                else if (t.type == Type.EXPENSE) tempBalance += t.amount;
            }

            int gmtOffset = playerGmtOffset.getOrDefault(player.getUniqueId(), defaultGmtOffset);
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
            sdf.setTimeZone(TimeZone.getTimeZone("GMT" + (gmtOffset >= 0 ? "+" : "") + gmtOffset));

            for (int i = start; i < end; i++) {
                Transaction t = filteredList.get(i);
                double balanceAfter = balanceAfterEach.get(i);
                double balanceBefore = balanceAfter;
                if (t.type == Type.INCOME) balanceBefore -= t.amount;
                else if (t.type == Type.EXPENSE) balanceBefore += t.amount;

                ItemStack item;
                if (t.key.equals("transaction-pay-sent") || t.key.equals("transaction-pay-received")) {
                    String playerName = t.params[0];
                    OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
                    item = new ItemStack(Material.PLAYER_HEAD);
                    SkullMeta skullMeta = (SkullMeta) item.getItemMeta();
                    skullMeta.setOwningPlayer(target);
                    item.setItemMeta(skullMeta);
                } else {
                    item = getHead(getTransactionHeadBase64(t.type));
                }

                ItemMeta meta = item.getItemMeta();
                String colorCode = getTransactionColor(t.type);
                meta.setDisplayName(colorCode + getTranslatedDescription(t));

                List<String> lore = new ArrayList<>();
                if (showBalance.getOrDefault(player.getUniqueId(), true) && t.type != Type.YELLOW) {
                    lore.add(colorCode + getTranslation("balance-change",
                            amountFormatter.format(Math.abs(balanceBefore)),
                            amountFormatter.format(Math.abs(balanceAfter))));
                }
                lore.add(getTranslation("time") + sdf.format(new Date(t.timestamp)));

                if (player.hasPermission("transactions.rollback") && t.key.equals("transaction-pay-received") && !t.rolledBack) {
                    lore.add("§e" + getTranslation("rollback-hint"));
                }

                meta.setLore(lore);
                item.setItemMeta(meta);
                inv.setItem(i - start, item);
            }


            if (page > 0) inv.setItem(45, createButton(Material.ARROW, getTranslation("prev-button")));
            if (end < filteredList.size()) inv.setItem(53, createButton(Material.ARROW, getTranslation("next-button")));

            if (player.isOp()) {
                ItemStack downloadButton = getHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODgyZmFmOWE1ODRjNGQ2NzZkNzMwYjIzZjg5NDJiYjk5N2ZhM2RhZDQ2ZDRmNjVlMjg4YzM5ZWI0NzFjZTcifX19");
                ItemMeta downloadMeta = downloadButton.getItemMeta();
                downloadMeta.setDisplayName("§6" + getTranslation("download-button"));
                downloadButton.setItemMeta(downloadMeta);
                inv.setItem(46, downloadButton);
            }

            ItemStack filterButton = getHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGNlZTNhZWFkZDY4YjI0N2ZkZmUzZWE3YmMwMDhkMTJmZDk3YWMxNWVkMTViZjc0Njg1NDNhMDY2ODAifX19");
            ItemMeta filterMeta = filterButton.getItemMeta();
            filterMeta.setDisplayName(getTranslation("filter-button"));
            filterMeta.setLore(Collections.singletonList(getTranslation("filter-current-type") + getTranslation("filter-type-" + filter.filterType.name().toLowerCase())));
            filterButton.setItemMeta(filterMeta);
            inv.setItem(48, filterButton);

            if (showStatsButton) {
                ItemStack statsButton = getHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTc5NjczMjY0ZWY5NTVmM2Q5NjBlNjk3MzM0MWMxZDhkNjRjZTVjM2YyMzNjYTM0YjI3OTMzNTA4YzM2ODdmMSJ9fX0=");
                ItemMeta statsMeta = statsButton.getItemMeta();
                statsMeta.setDisplayName("§r" + getTranslation("stats-1-week-button"));
                List<String> statsLore = new ArrayList<>();
                double income = 0, expense = 0;
                long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
                for (Transaction t : list) {
                    if (t.timestamp >= cutoff) {
                        if (t.type == Type.INCOME) income += t.amount;
                        else if (t.type == Type.EXPENSE) expense += t.amount;
                    }
                }
                if (income >= expense) {
                    statsLore.add(income > 0 ? getTranslation("stats-income") + amountFormatter.format(income) + " " + prefix : getTranslation("stats-no-income"));
                    statsLore.add(expense > 0 ? getTranslation("stats-expense") + amountFormatter.format(expense) + " " + prefix : getTranslation("stats-no-expense"));
                } else {
                    statsLore.add(expense > 0 ? getTranslation("stats-expense") + amountFormatter.format(expense) + " " + prefix : getTranslation("stats-no-expense"));
                    statsLore.add(income > 0 ? getTranslation("stats-income") + amountFormatter.format(income) + " " + prefix : getTranslation("stats-no-income"));
                }
                statsMeta.setLore(statsLore);
                statsButton.setItemMeta(statsMeta);
                inv.setItem(49, statsButton);
            }

            ItemStack searchButton = player.getUniqueId().equals(targetUUID) || player.hasPermission("transactions.view.others")
                    ? createHeadButton(SEARCH_HEAD, ChatColor.BLUE + "" + ChatColor.RESET + getTranslation("search-button"))
                    : createButton(Material.BARRIER, ChatColor.RED + getTranslation("search-disabled"));
            inv.setItem(50, searchButton);

            currentPage.put(player.getUniqueId(), page);
            searchTargetPlayer.put(player.getUniqueId(), targetName);
            rollbackTargetUUID.put(player.getUniqueId(), targetUUID);
            player.openInventory(inv);
        }, null);
    }
    void openGUI(Player player, int page) {
        openGUI(player, page, player.getUniqueId(), player.getName());
    }

    void openGUI(Player player, int page, FilterData filter) {
        playerFilters.put(player.getUniqueId(), filter);
        openGUI(player, page, player.getUniqueId(), player.getName());
    }


    void openFilterGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9, getTranslation("filter-gui-title"));
        FilterData currentFilter = playerFilters.computeIfAbsent(player.getUniqueId(), k -> new FilterData());
        String filterAllHead = currentFilter.filterType == FilterType.ALL ? SELECTED_HEAD : UNSELECTED_HEAD;
        String filterIncomeHead = currentFilter.filterType == FilterType.INCOME ? SELECTED_HEAD : UNSELECTED_HEAD;
        String filterExpenseHead = currentFilter.filterType == FilterType.EXPENSE ? SELECTED_HEAD : UNSELECTED_HEAD;
        String filterPayHead = currentFilter.filterType == FilterType.PAY ? SELECTED_HEAD : UNSELECTED_HEAD;
        String filterOtherHead = currentFilter.filterType == FilterType.OTHER ? SELECTED_HEAD : UNSELECTED_HEAD;

        inv.setItem(1, createHeadButton(filterAllHead, getTranslation("filter-type-all")));
        inv.setItem(2, createHeadButton(filterIncomeHead, getTranslation("filter-type-income")));
        inv.setItem(3, createHeadButton(filterExpenseHead, getTranslation("filter-type-expense")));
        inv.setItem(5, createHeadButton(filterPayHead, "§r§7" + getTranslation("filter-type-pay")));
        inv.setItem(6, createHeadButton(filterOtherHead, "§r§7" + getTranslation("filter-type-other")));
        inv.setItem(8, createButton(Material.ARROW, getTranslation("back-button")));
        player.openInventory(inv);
    }

    ItemStack createButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack createHeadButton(String base64, String name) {
        ItemStack item = getHead(base64);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    void openSearchResultsGUI(Player player, List<Transaction> searchResults, String searchName, int page) {
        Inventory inv = Bukkit.createInventory(null, 54, getTranslation("search-results-gui-title") + " " + searchName);
        int transactionsPerPage = 45;
        int start = page * transactionsPerPage;
        int end = Math.min(searchResults.size(), start + transactionsPerPage);
        int gmtOffset = playerGmtOffset.getOrDefault(player.getUniqueId(), defaultGmtOffset);
        UUID targetUUID = rollbackTargetUUID.getOrDefault(player.getUniqueId(), player.getUniqueId()); // Используем targetUUID
        double currentBalance = economy.getBalance(Bukkit.getOfflinePlayer(targetUUID));
        List<Transaction> sortedSearchResults = searchResults.stream()
                .sorted((t1, t2) -> Long.compare(t2.timestamp, t1.timestamp))
                .collect(Collectors.toList());
        for (int i = start; i < end; i++) {
            Transaction t = sortedSearchResults.get(i);
            String base64;
            switch (t.type) {
                case INCOME:
                    base64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDY3ZGNlNDY0NTM0OWU0MWE3ZjM1Nzk3ZTJiOTI3OWUzNWE2NWY1ZTgxYTM0NDk2ODg1ZDI3MjY4ZjM2OTEzOSJ9fX0=";
                    break;
                case EXPENSE:
                    base64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGQxMTEwOWY0YWIwM2FhNmM1Yjc2Y2FkMTI5MTc2ZmZiMWZjZThjMTc0ZTY5YzllOGJhMDZiOWY4MDYxZTVhZCJ9fX0=";
                    break;
                case YELLOW:
                    base64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTQ2N2E3YjlkNzZiYTZkMGZlZDc0MzYwMjUzM2ZjOThjODdhZjBjNjBmODBmMzhkYTc3NGY3YTAxYTIwOTNmYSJ9fX0=";
                    break;
                default:
                    base64 = "";
            }
            ItemStack item = getHead(base64);
            ItemMeta meta = item.getItemMeta();
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
            String gmtString = "GMT" + (gmtOffset >= 0 ? "+" : "") + gmtOffset;
            sdf.setTimeZone(TimeZone.getTimeZone(gmtString));
            String colorCode = t.type == Type.INCOME ? incomeMessageColor : t.type == Type.EXPENSE ? expenseMessageColor : yellowMessageColor;
            meta.setDisplayName(colorCode + getTranslatedDescription(t));
            List<String> lore = new ArrayList<>();
            if (showBalance.getOrDefault(player.getUniqueId(), true) && t.type != Type.YELLOW) {
                double balanceAtTransaction = currentBalance;
                for (int j = 0; j < i; j++) {
                    Transaction newer = sortedSearchResults.get(j);
                    if (newer.type == Type.INCOME) {
                        balanceAtTransaction -= newer.amount;
                    } else if (newer.type == Type.EXPENSE) {
                        balanceAtTransaction += newer.amount;
                    }
                }
                double previousBalance = balanceAtTransaction + (t.type == Type.INCOME ? -t.amount : t.amount);
                String balanceChange = getTranslation("balance-change", amountFormatter.format(Math.abs(previousBalance)), amountFormatter.format(Math.abs(balanceAtTransaction)));
                lore.add(colorCode + balanceChange);
            }
            lore.add(getTranslation("time") + sdf.format(new Date(t.timestamp)));
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(i - start, item);
        }
        for (int i = end - start; i < transactionsPerPage; i++) {
            ItemStack emptySlotFiller = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = emptySlotFiller.getItemMeta();
            meta.setDisplayName(" ");
            emptySlotFiller.setItemMeta(meta);
            inv.setItem(i, emptySlotFiller);
        }
        if (page > 0) inv.setItem(45, createButton(Material.ARROW, getTranslation("prev-button")));
        if (end < sortedSearchResults.size()) inv.setItem(53, createButton(Material.ARROW, getTranslation("next-button")));
        inv.setItem(49, createButton(Material.BARRIER, getTranslation("back-button")));
        currentPage.put(player.getUniqueId(), page);
        player.openInventory(inv);
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!playerInSearchMode.remove(p.getUniqueId())) return;

        e.setCancelled(true);
        String searchName = e.getMessage().trim();
        if (searchName.isEmpty()) {
            p.sendMessage("§c" + getTranslation("search-cancelled"));
            openGUI(p, 0);
            return;
        }

        String targetName = searchTargetPlayer.getOrDefault(p.getUniqueId(), p.getName());
        UUID targetUUID = rollbackTargetUUID.getOrDefault(p.getUniqueId(), p.getUniqueId());

        if (searchName.equalsIgnoreCase(targetName)) {
            p.sendMessage("§c" + getTranslation("cannot-search-self"));
            openGUI(p, 0, targetUUID, targetName);
            return;
        }

        List<Transaction> all = getTransactions(targetUUID);
        List<Transaction> found = all.stream()
                .filter(t -> Arrays.stream(t.params).anyMatch(param -> param.equalsIgnoreCase(searchName)))
                .collect(Collectors.toList());

        if (found.isEmpty()) {
            p.sendMessage("§c" + getTranslation("no-transactions-found", searchName));
            openGUI(p, 0, targetUUID, targetName);
            return;
        }
        p.getScheduler().run(this, scheduledTask -> {
            searchTargetPlayer.put(p.getUniqueId(), searchName);
            currentPage.put(p.getUniqueId(), 0);
            openSearchResultsGUI(p, found, searchName, 0);
        }, null);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getCurrentItem() == null) return;

        String title = LegacyComponentSerializer.legacySection().serialize(e.getView().title());

        String mainPrefix = stripColor(getTranslation("gui-title")).trim();
        String filterTitle = stripColor(getTranslation("filter-gui-title"));
        String rollbackTitle = stripColor(getTranslation("rollback-confirm-title"));
        String searchTitle = stripColor(getTranslation("search-results-gui-title"));

        boolean isOurGui = title.contains(mainPrefix) ||
                title.contains(filterTitle) ||
                title.contains(rollbackTitle) ||
                title.contains(searchTitle);

        if (!isOurGui) return;

        e.setCancelled(true);

        player.getScheduler().run(this, (task) -> {
            ItemStack clicked = e.getCurrentItem();
            int slot = e.getSlot();
            int page = currentPage.getOrDefault(player.getUniqueId(), 0);
            UUID targetUUID = rollbackTargetUUID.getOrDefault(player.getUniqueId(), player.getUniqueId());
            String targetName = searchTargetPlayer.getOrDefault(player.getUniqueId(), player.getName());

            if (clicked.getType() == Material.ARROW) {
                String name = clicked.getItemMeta().getDisplayName();
                if (name.contains(getTranslation("prev-button"))) {
                    if (page > 0) openGUI(player, page - 1, targetUUID, targetName);
                    return;
                }
                if (name.contains(getTranslation("next-button"))) {
                    openGUI(player, page + 1, targetUUID, targetName);
                    return;
                }
            }

            if (clicked.getType() == Material.BARRIER) {
                openGUI(player, page, targetUUID, targetName);
                return;
            }

            if (title.contains(filterTitle)) {
                FilterData f = playerFilters.computeIfAbsent(player.getUniqueId(), k -> new FilterData());
                switch (slot) {
                    case 1 -> f.filterType = FilterType.ALL;
                    case 2 -> f.filterType = FilterType.INCOME;
                    case 3 -> f.filterType = FilterType.EXPENSE;
                    case 5 -> f.filterType = FilterType.PAY;
                    case 6 -> f.filterType = FilterType.OTHER;
                }
                dirtyPlayers.add(player.getUniqueId());
                openGUI(player, 0, targetUUID, targetName);
                return;
            }

            if (slot == 48) {
                openFilterGUI(player);
                return;
            }

            if (slot == 50 && (player.getUniqueId().equals(targetUUID) || player.hasPermission("transactions.view.others"))) {
                playerInSearchMode.add(player.getUniqueId());
                player.closeInventory();
                player.sendMessage("§e" + getTranslation("enter-player-name"));
                return;
            }

            if (slot == 46 && player.isOp()) {
                downloadTransactionsToTxt(player, targetUUID, targetName);
                return;
            }

            if (e.getClick().isRightClick() && player.hasPermission("transactions.rollback")) {
                List<Transaction> list = getTransactions(targetUUID);
                int index = slot + page * 45;
                if (index < list.size()) {
                    Transaction t = list.get(index);
                    if (t.key.equals("transaction-pay-received") && !t.rolledBack) {
                        openRollbackConfirmationGUI(player, t, targetUUID, targetName);
                    }
                }
                return;
            }

            if (title.contains(rollbackTitle)) {
                if (slot == 11) {
                    Transaction t = pendingRollback.get(player.getUniqueId());
                    if (t != null) {
                        rollbackTransaction(player, t, targetUUID, rollbackTargetName.getOrDefault(player.getUniqueId(), targetName));
                        pendingRollback.remove(player.getUniqueId());
                    }
                } else if (slot == 15) {
                    player.closeInventory();
                    openGUI(player, page, targetUUID, targetName);
                }
            }
        }, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        String title = LegacyComponentSerializer.legacySection().serialize(e.getView().title());

        String mainPrefix = stripColor(getTranslation("gui-title")).trim();
        String filterTitle = stripColor(getTranslation("filter-gui-title"));
        String rollbackTitle = stripColor(getTranslation("rollback-confirm-title"));
        String searchTitle = stripColor(getTranslation("search-results-gui-title"));

        boolean isOurGui = title.contains(mainPrefix) ||
                title.contains(filterTitle) ||
                title.contains(rollbackTitle) ||
                title.contains(searchTitle);

        if (isOurGui) {
            e.setCancelled(true);
        }
    }
    private void downloadTransactionsToTxt(Player player, UUID targetUUID, String targetName) {
        if (!player.isOp()) {
            player.sendMessage("§c" + getTranslation("no-permissions"));
            return;
        }
        List<Transaction> transactionsList = getTransactions(targetUUID);
        if (transactionsList.isEmpty()) {
            player.sendMessage("§c" + getTranslation("no-transactions-found", targetName));
            return;
        }
        File downloadFolder = new File(getDataFolder(), "downloads");
        if (!downloadFolder.exists()) {
            downloadFolder.mkdirs();
        }
        File file = new File(downloadFolder, targetName + "_transactions.txt");
        int gmtOffset = playerGmtOffset.getOrDefault(player.getUniqueId(), defaultGmtOffset);
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT" + (gmtOffset >= 0 ? "+" : "") + gmtOffset));
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write("Транзакции игрока: " + targetName);
            writer.newLine();
            writer.newLine();
            for (Transaction t : transactionsList) {
                String description = stripColor(getTranslatedDescription(t));
                String date = sdf.format(new Date(t.timestamp));
                writer.write(String.format("[%s] %s", date, description));
                writer.newLine();
            }
            player.sendMessage("§a" + getTranslation("download-success", file.getAbsolutePath()));
        } catch (IOException e) {
            player.sendMessage("§c" + getTranslation("download-failed"));
            getLogger().warning("Ошибка при сохранении файла транзакций для " + targetName + ": " + e.getMessage());
        }
    }
    private void openRollbackConfirmationGUI(Player player, Transaction transaction, UUID targetUUID, String targetName) {
        Inventory inv = Bukkit.createInventory(null, 27, getTranslation("rollback-confirm-title"));
        String confirmBase64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTIxOTI4ZWE2N2QzYThiOTdkMjEyNzU4ZjE1Y2NjYWMxMDI0Mjk1YjE4NWIzMTkyNjQ4NDRmNGM1ZTFlNjFlIn19fQ==";
        String cancelBase64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjVlZjY4ZGNiZDU4MjM0YmE3YWVlMmFkOTFjYTZmYTdjZTIzZjlhMzIzNDViNDhkNmU1ZjViODZhNjhiNWIifX19";
        ItemStack confirmButton = getHead(confirmBase64);
        ItemMeta confirmMeta = confirmButton.getItemMeta();
        confirmMeta.setDisplayName(getTranslation("confirm-rollback"));
        confirmButton.setItemMeta(confirmMeta);

        ItemStack cancelButton = getHead(cancelBase64);
        ItemMeta cancelMeta = cancelButton.getItemMeta();
        cancelMeta.setDisplayName(getTranslation("cancel-rollback"));
        cancelButton.setItemMeta(cancelMeta);

        ItemStack infoItem = getHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDY3ZGNlNDY0NTM0OWU0MWE3ZjM1Nzk3ZTJiOTI3OWUzNWE2NWY1ZTgxYTM0NDk2ODg1ZDI3MjY4ZjM2OTEzOSJ9fX0=");
        ItemMeta infoMeta = infoItem.getItemMeta();
        infoMeta.setDisplayName("§e" + getTranslatedDescription(transaction));
        List<String> lore = new ArrayList<>();
        lore.add("§7" + getTranslation("rollback-amount", amountFormatter.format(transaction.amount)));
        lore.add("§7" + getTranslation("rollback-receiver", targetName));
        lore.add("§7" + getTranslation("rollback-sender", transaction.params[0]));
        infoMeta.setLore(lore);
        infoItem.setItemMeta(infoMeta);

        inv.setItem(11, confirmButton);
        inv.setItem(13, infoItem);
        inv.setItem(15, cancelButton);

        pendingRollback.put(player.getUniqueId(), transaction);
        rollbackTargetUUID.put(player.getUniqueId(), targetUUID);
        rollbackTargetName.put(player.getUniqueId(), targetName);
        player.openInventory(inv);
    }
    private String getTransactionHeadBase64(Type type) {
        switch (type) {
            case INCOME:
                return "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDY3ZGNlNDY0NTM0OWU0MWE3ZjM1Nzk3ZTJiOTI3OWUzNWE2NWY1ZTgxYTM0NDk2ODg1ZDI3MjY4ZjM2OTEzOSJ9fX0=";
            case EXPENSE:
                return "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGQxMTEwOWY0YWIwM2FhNmM1Yjc2Y2FkMTI5MTc2ZmZiMWZjZThjMTc0ZTY5YzllOGJhMDZiOWY4MDYxZTVhZCJ9fX0=";
            case YELLOW:
                return "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTQ2N2E3YjlkNzZiYTZkMGZlZDc0MzYwMjUzM2ZjOThjODdhZjBjNjBmODBmMzhkYTc3NGY3YTAxYTIwOTNmYSJ9fX0=";
            default:
                return "";
        }
    }
    private String getTransactionColor(Type type) {
        switch (type) {
            case INCOME:
                return incomeMessageColor;
            case EXPENSE:
                return expenseMessageColor;
            case YELLOW:
                return yellowMessageColor;
            default:
                return "§f";
        }
    }



    private void rollbackTransaction(Player admin, Transaction transaction, UUID targetUUID, String targetName) {
        if (!transaction.key.equals("transaction-pay-received")) {
            admin.sendMessage("§c" + getTranslation("rollback-invalid-transaction"));
            return;
        }

        if (transaction.rolledBack) {
            admin.sendMessage("§c" + getTranslation("rollback-already-done"));
            return;
        }

        String senderName = transaction.params[0];
        OfflinePlayer sender = Bukkit.getOfflinePlayer(senderName);
        OfflinePlayer receiver = Bukkit.getOfflinePlayer(targetName);
        double amount = transaction.amount;

        if (!economy.has(receiver, amount)) {
            admin.sendMessage("§c" + getTranslation("rollback-insufficient-funds", targetName));
            return;
        }


        EconomyResponse withdraw = economy.withdrawPlayer(receiver, amount);
        if (!withdraw.transactionSuccess()) {
            admin.sendMessage("§c" + getTranslation("rollback-failed-withdraw", withdraw.errorMessage));
            getLogger().warning("Rollback failed (withdraw): " + withdraw.errorMessage + " | Player: " + targetName);
            return;
        }


        EconomyResponse deposit = economy.depositPlayer(sender, amount);
        if (!deposit.transactionSuccess()) {

            economy.depositPlayer(receiver, amount);
            admin.sendMessage("§c" + getTranslation("rollback-failed-deposit", senderName));
            getLogger().severe("CRITICAL: Rollback failed to return money to " + senderName + "! Money refunded to " + targetName);
            return;
        }

        Transaction rollbackExpense = new Transaction(Type.EXPENSE, "transaction-rollback", amount, senderName);
        Transaction rollbackIncome = new Transaction(Type.INCOME, "transaction-rollback-received", amount, targetName);


        transaction.rolledBack = true;
        dbWriteQueue.offer(() -> {
            try (PreparedStatement ps = db.prepareStatement(
                    "UPDATE transactions SET rolled_back = 1 WHERE id = (SELECT id FROM transactions WHERE player_uuid = ? AND timestamp = ? AND key = ? LIMIT 1)")) {
                ps.setString(1, targetUUID.toString());
                ps.setLong(2, transaction.timestamp);
                ps.setString(3, transaction.key);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().warning("Failed to mark transaction as rolled back: " + e.getMessage());
            }
        });

        addTransaction(receiver.getUniqueId(), rollbackExpense);
        addTransaction(sender.getUniqueId(), rollbackIncome);


        admin.sendMessage("§a" + getTranslation("rollback-success", targetName, amountFormatter.format(amount), senderName));

        Player receiverPlayer = receiver.getPlayer();
        if (receiverPlayer != null) {
            receiverPlayer.sendMessage("§c" + getTranslation("rollback-notify-receiver", amountFormatter.format(amount), senderName));
        }

        Player senderPlayer = sender.getPlayer();
        if (senderPlayer != null) {
            senderPlayer.sendMessage("§a" + getTranslation("rollback-notify-sender", amountFormatter.format(amount), targetName));
        }
        int page = currentPage.getOrDefault(admin.getUniqueId(), 0);
        openGUI(admin, page, targetUUID, targetName);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (e.isCancelled()) return;
        String[] args = e.getMessage().substring(1).split(" ");
        handleEconomyCommands(e.getPlayer(), args);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsoleCommand(ServerCommandEvent e) {
        String[] args = e.getCommand().split(" ");
        handleEconomyCommands(null, args);
    }

    private void handleEconomyCommands(Player senderPlayer, String[] args) {
        if (args.length == 0) return;
        String cmd = args[0].toLowerCase();


        if (cmd.equals("pay") && senderPlayer != null && args.length >= 3) {
            double amount = parseAmount(args[2]);
            if (amount <= 0) return;

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.hasPlayedBefore() || target.getUniqueId().equals(senderPlayer.getUniqueId())) return;

            if (economy.getBalance(senderPlayer) < amount) {
                return;
            }

            UUID senderUUID = senderPlayer.getUniqueId();
            UUID targetUUID = target.getUniqueId();

            payInProgress.add(senderUUID);
            payInProgress.add(targetUUID);

            addTransaction(senderUUID,
                    new Transaction(Type.EXPENSE, "transaction-pay-sent", amount, target.getName()));
            addTransaction(targetUUID,
                    new Transaction(Type.INCOME, "transaction-pay-received", amount, senderPlayer.getName()));

            getServer().getGlobalRegionScheduler().runDelayed(this, s -> {
                payInProgress.remove(senderUUID);
                payInProgress.remove(targetUUID);
            }, 3L);

            return;
        }

        if ((cmd.equals("eco") || cmd.equals("economy")) && args.length >= 3) {
            String sub = args[1].toLowerCase();
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            if (!target.hasPlayedBefore()) return;

            String adminName = senderPlayer != null ? senderPlayer.getName() : "Console";
            UUID targetUUID = target.getUniqueId();

            ecoInProgress.add(targetUUID);

            if (sub.equals("reset")) {
                addTransaction(targetUUID, new Transaction(Type.YELLOW, "transaction-balance-reset", 0, adminName));
            } else if (args.length >= 4) {
                double amount = parseAmount(args[3]);
                if (amount <= 0) {
                    getServer().getGlobalRegionScheduler().runDelayed(this, t -> ecoInProgress.remove(targetUUID), 2L);
                    return;
                }

                switch (sub) {
                    case "give" -> addTransaction(targetUUID,
                            new Transaction(Type.INCOME, "transaction-admin-give", amount, adminName));

                    case "take" -> {
                        if (economy.has(target, amount)) {
                            addTransaction(targetUUID,
                                    new Transaction(Type.EXPENSE, "transaction-admin-take", amount, adminName));
                        }
                    }

                    case "set" -> addTransaction(targetUUID,
                            new Transaction(Type.YELLOW, "transaction-balance-set", amount, adminName));
                }
            }


            getServer().getGlobalRegionScheduler().runDelayed(this, scheduledTask ->
                    ecoInProgress.remove(targetUUID), 2L);
        }
    }
    private double parseAmount(String s) {
        s = s.toUpperCase().replace(",", "").trim();
        double multiplier = 1;

        if (s.endsWith("K")) {
            multiplier = 1_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("M")) {
            multiplier = 1_000_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("B")) {
            multiplier = 1_000_000_000;
            s = s.substring(0, s.length() - 1);
        } else if (s.endsWith("T")) {
            multiplier = 1_000_000_000_000L;
            s = s.substring(0, s.length() - 1);
        }

        try {
            return Double.parseDouble(s) * multiplier;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }



    private void updateFiles() {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.options().copyDefaults(true);
        try (InputStream defaultsStream = this.getResource("config.yml")) {
            if (defaultsStream != null) {
                YamlConfiguration defaultsConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultsStream));
                config.setDefaults(defaultsConfig);
                config.save(configFile);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        File langFolder = new File(getDataFolder(), "lang");
        if (langFolder.exists()) {
            File[] langFiles = langFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (langFiles != null) {
                for (File langFile : langFiles) {
                    YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
                    String langName = langFile.getName();
                    try (InputStream defaultsStream = this.getResource("lang/" + langName)) {
                        if (defaultsStream != null) {
                            YamlConfiguration defaultsLangConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultsStream));
                            langConfig.setDefaults(defaultsLangConfig);
                            langConfig.options().copyDefaults(true);
                            langConfig.save(langFile);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(getTranslation("in-game-only"));
            return true;
        }

        if (args.length == 0) {
            openGUI(p, 0);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload") || sub.equals("update")) {
            if (!p.isOp()) {
                p.sendMessage(getTranslation("no-permissions"));
                return true;
            }

            if (sub.equals("update")) {
                updateFiles();
            }

            reloadConfig();
            loadConfigValues();


            lastCleanTime = System.currentTimeMillis();

            p.sendMessage(ChatColor.GREEN + getTranslation(sub.equals("update") ? "update-success" : "config-reloaded"));
            saveDirtyPlayers();
            return true;
        }

        if (sub.equals("clean")) {
            if (!p.isOp()) {
                p.sendMessage(getTranslation("no-permissions"));
                return true;
            }
            clearAllTransactions();
            p.sendMessage(getTranslation("cleanup-executed"));
            return true;
        }
        if (sub.equals("range")) {
            if (!sender.isOp()) {
                sender.sendMessage(getTranslation("no-permissions"));
                return true;
            }

            if (args.length != 2) {
                sender.sendMessage(getTranslation("range-usage"));
                return true;
            }

            int days;
            try {
                days = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage(getTranslation("range-not-number"));
                return true;
            }

            if (days < 1 || days > 365) {
                sender.sendMessage(getTranslation("range-invalid", "1", "365"));
                return true;
            }

            getConfig().set("max-display-transaction-range", days);
            saveConfig();
            maxDisplayTransactionRange = days;

            sender.sendMessage("");
            sender.sendMessage(getTranslation("range-success-title"));
            sender.sendMessage(getTranslation("range-success-days", String.valueOf(days)));
            sender.sendMessage(getTranslation("range-success-limit", String.valueOf(days)));
            sender.sendMessage("");

            getLogger().info(sender.getName() + " установил max-display-transaction-range = " + days + " дней");
            return true;
        }
        if (sub.equals("gmt")) {
            if (args.length != 2) {
                p.sendMessage(getTranslation("gmt-usage"));
                p.sendMessage(getTranslation("gmt-range"));
                return true;
            }
            try {
                int gmt = Integer.parseInt(args[1]);
                if (gmt < -12 || gmt > 14) {
                    p.sendMessage(getTranslation("gmt-range"));
                    return true;
                }
                playerGmtOffset.put(p.getUniqueId(), gmt);
                dirtyPlayers.add(p.getUniqueId());
                p.sendMessage(getTranslation("gmt-set", args[1]));
            } catch (NumberFormatException e) {
                p.sendMessage(getTranslation("invalid-gmt"));
            }
            return true;
        }

        if (sub.equals("balance") && allowBalanceToggle) {
            if (args.length != 2) {
                p.sendMessage(getTranslation("balance-usage"));
                return true;
            }
            boolean state = args[1].equalsIgnoreCase("on");
            if (state || args[1].equalsIgnoreCase("off")) {
                showBalance.put(p.getUniqueId(), state);
                dirtyPlayers.add(p.getUniqueId());
                p.sendMessage(state ? "§a" + getTranslation("balance-enabled") : "§c" + getTranslation("balance-disabled"));
                saveDirtyPlayers();
            } else {
                p.sendMessage(getTranslation("balance-usage"));
            }
            return true;
        }

        if (sub.equals("player") && p.hasPermission("transactions.view.others")) {
            if (args.length < 2) {
                p.sendMessage("§c" + getTranslation("player-usage"));
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.hasPlayedBefore() || target.getName() == null) {
                p.sendMessage("§c" + getTranslation("player-not-found", args[1]));
                return true;
            }
            if (target.getUniqueId().equals(p.getUniqueId())) {
                p.sendMessage("§c" + getTranslation("cannot-view-self"));
                return true;
            }
            if (args.length == 3 && args[2].equalsIgnoreCase("txt") && p.isOp()) {
                downloadTransactionsToTxt(p, target.getUniqueId(), target.getName());
                return true;
            }
            openGUI(p, 0, target.getUniqueId(), target.getName());
            return true;
        }

        p.sendMessage(getTranslation("invalid-command"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("transactions")) return null;

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();

            if ("gmt".startsWith(input)) completions.add("gmt");
            if (allowBalanceToggle && "balance".startsWith(input)) completions.add("balance");
            if (sender.hasPermission("transactions.view.others") && "player".startsWith(input)) completions.add("player");

            if (sender.isOp()) {
                if ("reload".startsWith(input)) completions.add("reload");
                if ("update".startsWith(input)) completions.add("update");
                if ("clean".startsWith(input)) completions.add("clean");
                if ("range".startsWith(input)) completions.add("range"); // ← ДОБАВИЛ!
            }

            return completions;
        }

        // Подсказка для /tr range <число>
        if (args.length == 2 && args[0].equalsIgnoreCase("range") && sender.isOp()) {
            return Arrays.asList("30", "60", "90", "180", "365");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("balance") && allowBalanceToggle) {
            return Arrays.asList("on", "off");
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("player") && sender.hasPermission("transactions.view.others")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return null;
    }


    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVaultDeposit(PlayerDepositEvent event) {
        if (!logExternalTransactions) return;

        OfflinePlayer p = event.getOfflinePlayer();
        if (p == null || p.getUniqueId() == null || event.getAmount() <= 0.01) return;

        UUID uuid = p.getUniqueId();


        if (payInProgress.contains(uuid) || ecoInProgress.contains(uuid)) {
            return;
        }

        double amount = event.getAmount();

        if (amount < THROTTLE_MIN_AMOUNT) {
            long now = System.currentTimeMillis();
            Long lastTime = lastExternalDepositTime.get(uuid);
            if (lastTime != null && now - lastTime < THROTTLE_INTERVAL_MS) {
                return;
            }
            lastExternalDepositTime.put(uuid, now);
        }

        addTransaction(uuid, new Transaction(
                Type.INCOME,
                "transaction-external-deposit",
                amount
        ));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVaultWithdraw(PlayerWithdrawEvent event) {
        if (!logExternalTransactions) return;

        OfflinePlayer p = event.getOfflinePlayer();
        if (p == null || p.getUniqueId() == null || event.getAmount() <= 0.01) return;

        UUID uuid = p.getUniqueId();
        if (payInProgress.contains(uuid) || ecoInProgress.contains(uuid)) {
            return;
        }

        double amount = event.getAmount();
        double balanceBefore = economy.getBalance(p);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            double balanceAfter = economy.getBalance(p);
            double expectedBalance = balanceBefore - amount;

            if (Math.abs(balanceAfter - expectedBalance) < 0.01) {
                return;
            }


            if (amount < THROTTLE_MIN_AMOUNT) {
                long now = System.currentTimeMillis();
                Long lastTime = lastExternalWithdrawTime.get(uuid);
                if (lastTime != null && now - lastTime < THROTTLE_INTERVAL_MS) {
                    return;
                }
                lastExternalWithdrawTime.put(uuid, now);
            }

            addTransaction(uuid, new Transaction(
                    Type.EXPENSE,
                    "transaction-external-withdraw",
                    amount
            ));
        }, 1L);
    }}