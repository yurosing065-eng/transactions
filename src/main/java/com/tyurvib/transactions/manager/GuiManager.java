package com.tyurvib.transactions.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tcoded.folialib.FoliaLib;
import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.model.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class GuiManager {
    private final Transactions plugin;
    private final Map<String, ItemStack> headCache = new ConcurrentHashMap<>();
    private final ItemStack INCOME_ITEM = getHead(getTransactionHeadBase64(Type.INCOME));
    private final ItemStack EXPENSE_ITEM = getHead(getTransactionHeadBase64(Type.EXPENSE));
    private final ItemStack PAY_ITEM = getHead(getTransactionHeadBase64(Type.YELLOW));
    public static final String SELECTED_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTIxOTI4ZWE2N2QzYThiOTdkMjEyNzU4ZjE1Y2NjYWMxMDI0Mjk1YjE4NWIzMTkyNjQ4NDRmNGM1ZTFlNjFlIn19fQ==";
    public static final String UNSELECTED_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjVlZjY4ZGNiZDU4MjM0YmE3YWVlMmFkOTFjYTZmYTdjZTIzZjlhMzIzNDViNDhkNmU1ZjViODZhNjhiNWIifX19";
    public static final String SEARCH_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjc0OGYyMTM1ODhkYmY0NDE1Y2UyNGZlNjZkZTM1MjY4MTZiZjM1ZGY4ZTM5OGY5OGVmZWMyZmIwODk1NmEzIn19fQ==";
    private final FoliaLib foliaLib;
    public GuiManager(Transactions plugin) {
        this.plugin = plugin;
        this.foliaLib = plugin.getFoliaLib();
    }

    public void openGUI(Player player, int page, UUID targetUUID, String targetName) {
        TransactionManager tm = plugin.getTransactionManager();
        ConfigManager cm = plugin.getConfigManager();

        tm.getTransactionsAsync(targetUUID).thenAccept(list -> {
            FilterData filter = tm.playerFilters.computeIfAbsent(player.getUniqueId(), k -> new FilterData());
            List<Transaction> filteredList = new ArrayList<>();
            long now = System.currentTimeMillis();
            long cutoffTimestamp = (filter.timePeriod == TimePeriod.LAST_7_DAYS) ? now - TimeUnit.DAYS.toMillis(7) :
                    (filter.timePeriod == TimePeriod.LAST_30_DAYS) ? now - TimeUnit.DAYS.toMillis(30) : 0;

            for (Transaction t : list) {
                if (filter.timePeriod == TimePeriod.ALL_TIME || t.timestamp >= cutoffTimestamp) {
                    if (filter.filterType == FilterType.ALL ||
                            (filter.filterType == FilterType.INCOME && t.type == Type.INCOME) ||
                            (filter.filterType == FilterType.EXPENSE && t.type == Type.EXPENSE) ||
                            (filter.filterType == FilterType.PAY && t.key.contains("pay")) ||
                            (filter.filterType == FilterType.OTHER && !t.key.contains("pay"))) {
                        filteredList.add(t);
                    }
                }
            }
            filteredList.sort((t1, t2) -> Long.compare(t2.timestamp, t1.timestamp));

            double currentBalance = plugin.getEconomy().getBalance(Bukkit.getOfflinePlayer(targetUUID));
            List<Double> balanceLog = calculateBalanceLog(filteredList, currentBalance);
            foliaLib.getScheduler().runAtEntity(player, task -> {
                if (!player.isOnline()) return;

                Inventory inv = Bukkit.createInventory(null, 54, cm.getTranslation("gui-title") + targetName + " (#" + (page + 1) + ")");

                int start = page * 45;
                int end = Math.min(filteredList.size(), start + 45);

                int gmtOffset = tm.playerGmtOffset.getOrDefault(player.getUniqueId(), cm.defaultGmtOffset);
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                sdf.setTimeZone(TimeZone.getTimeZone("GMT" + (gmtOffset >= 0 ? "+" : "") + gmtOffset));

                for (int i = start; i < end; i++) {
                    Transaction t = filteredList.get(i);
                    ItemStack item = (t.key.contains("pay") && t.params.length > 0)
                            ? getPlayerHead(t.params[0])
                            : getHead(getTransactionHeadBase64(t.type));

                    ItemMeta meta = item.getItemMeta();
                    String colorCode = cm.getTransactionColor(t.type);
                    meta.setDisplayName("§r" + colorCode + cm.getTranslatedDescription(t));

                    List<String> lore = new ArrayList<>();
                    if (tm.showBalance.getOrDefault(player.getUniqueId(), true) && t.type != Type.YELLOW) {
                        double after = balanceLog.get(i);
                        double before = after + (t.type == Type.INCOME ? -t.amount : t.amount);
                        lore.add("§r§f" + cm.getTranslation("balance-change",
                                cm.getAmountFormatter().format(Math.abs(before)),
                                cm.getAmountFormatter().format(Math.abs(after))));
                    }
                    lore.add("§r§7" + cm.getTranslation("time") + sdf.format(new Date(t.timestamp)));
                    if (player.hasPermission("transactions.source") && t.source != null && !t.source.isEmpty()) {
                        lore.add("§r§8" + cm.getTranslation("source-plugin") + "§7" + t.source);
                    }
                    if (player.hasPermission("transactions.rollback") && t.key.equals("transaction-pay-received") && !t.rolledBack) {
                        lore.add("§r§e" + cm.getTranslation("rollback-hint"));
                    }
                    meta.setLore(lore);
                    item.setItemMeta(meta);
                    inv.setItem(i - start, item);
                }

                setupNavigationButtons(inv, page, end, filteredList.size(), cm, player, targetUUID, targetName, filter, list);

                tm.currentPage.put(player.getUniqueId(), page);
                tm.searchTargetPlayer.put(player.getUniqueId(), targetName);
                tm.rollbackTargetUUID.put(player.getUniqueId(), targetUUID);

                player.openInventory(inv);
            });
        });
    }

    private void setupNavigationButtons(Inventory inv, int page, int end, int totalSize, ConfigManager cm, Player player, UUID targetUUID, String targetName, FilterData filter, List<Transaction> allTransactions) {
        if (page > 0) inv.setItem(45, createButton(Material.ARROW, "§r" + cm.getTranslation("prev-button")));
        if (end < totalSize) inv.setItem(53, createButton(Material.ARROW, "§r" + cm.getTranslation("next-button")));

        if (player.isOp()) {
            inv.setItem(46, createHeadButton("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODgyZmFmOWE1ODRjNGQ2NzZkNzMwYjIzZjg5NDJiYjk5N2ZhM2RhZDQ2ZDRmNjVlMjg4YzM5ZWI0NzFjZTcifX19", "§r§6" + cm.getTranslation("download-button")));
        }

        ItemStack filterBtn = createHeadButton("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGNlZTNhZWFkZDY4YjI0N2ZkZmUzZWE3YmMwMDhkMTJmZDk3YWMxNWVkMTViZjc0Njg1NDNhMDY2ODAifX19", "§r" + cm.getTranslation("filter-button"));
        ItemMeta fm = filterBtn.getItemMeta();
        fm.setLore(Collections.singletonList("§r§7" + cm.getTranslation("filter-current-type") + cm.getTranslation("filter-type-" + filter.filterType.name().toLowerCase())));
        filterBtn.setItemMeta(fm);
        inv.setItem(48, filterBtn);

        if (cm.showStatsButton) {
            inv.setItem(49, createStatsButton(allTransactions, cm));
        }

        ItemStack searchBtn = (player.getUniqueId().equals(targetUUID) || player.hasPermission("transactions.view.others"))
                ? createHeadButton(SEARCH_HEAD, "§r§9" + cm.getTranslation("search-button"))
                : createButton(Material.BARRIER, "§r§c" + cm.getTranslation("search-disabled"));
        inv.setItem(50, searchBtn);
    }
    public void openFilterGUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9, plugin.getConfigManager().getTranslation("filter-gui-title"));
        FilterData fd = plugin.getTransactionManager().playerFilters.computeIfAbsent(player.getUniqueId(), k -> new FilterData());

        inv.setItem(1, createHeadButton(fd.filterType == FilterType.ALL ? SELECTED_HEAD : UNSELECTED_HEAD, plugin.getConfigManager().getTranslation("filter-type-all")));
        inv.setItem(2, createHeadButton(fd.filterType == FilterType.INCOME ? SELECTED_HEAD : UNSELECTED_HEAD, plugin.getConfigManager().getTranslation("filter-type-income")));
        inv.setItem(3, createHeadButton(fd.filterType == FilterType.EXPENSE ? SELECTED_HEAD : UNSELECTED_HEAD, plugin.getConfigManager().getTranslation("filter-type-expense")));
        inv.setItem(5, createHeadButton(fd.filterType == FilterType.PAY ? SELECTED_HEAD : UNSELECTED_HEAD, plugin.getConfigManager().getTranslation("filter-type-pay")));
        inv.setItem(6, createHeadButton(fd.filterType == FilterType.OTHER ? SELECTED_HEAD : UNSELECTED_HEAD, plugin.getConfigManager().getTranslation("filter-type-other")));
        inv.setItem(8, createButton(Material.ARROW, plugin.getConfigManager().getTranslation("back-button")));
        player.openInventory(inv);
    }

    public void openRollbackGUI(Player player, Transaction t, UUID targetUUID, String targetName) {
        Inventory inv = Bukkit.createInventory(null, 27, plugin.getConfigManager().getTranslation("rollback-confirm-title"));

        String confirmBase64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTIxOTI4ZWE2N2QzYThiOTdkMjEyNzU4ZjE1Y2NjYWMxMDI0Mjk1YjE4NWIzMTkyNjQ4NDRmNGM1ZTFlNjFlIn19fQ==";
        String cancelBase64 = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjVlZjY4ZGNiZDU4MjM0YmE3YWVlMmFkOTFjYTZmYTdjZTIzZjlhMzIzNDViNDhkNmU1ZjViODZhNjhiNWIifX19";

        ItemStack confirm = createHeadButton(confirmBase64, plugin.getConfigManager().getTranslation("confirm-rollback"));
        ItemStack cancel = createHeadButton(cancelBase64, plugin.getConfigManager().getTranslation("cancel-rollback"));

        ItemStack info = createHeadButton("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDY3ZGNlNDY0NTM0OWU0MWE3ZjM1Nzk3ZTJiOTI3OWUzNWE2NWY1ZTgxYTM0NDk2ODg1ZDI3MjY4ZjM2OTEzOSJ9fX0=", "§eInfo");
        ItemMeta meta = info.getItemMeta();
        meta.setDisplayName("§e" + plugin.getConfigManager().getTranslatedDescription(t));
        List<String> lore = new ArrayList<>();
        lore.add("§7" + plugin.getConfigManager().getTranslation("rollback-amount", plugin.getConfigManager().getAmountFormatter().format(t.amount)));
        lore.add("§7" + plugin.getConfigManager().getTranslation("rollback-receiver", targetName));
        lore.add("§7" + plugin.getConfigManager().getTranslation("rollback-sender", t.params[0]));
        meta.setLore(lore);
        info.setItemMeta(meta);

        plugin.getTransactionManager().pendingRollback.put(player.getUniqueId(), t);
        plugin.getTransactionManager().rollbackTargetUUID.put(player.getUniqueId(), targetUUID);
        plugin.getTransactionManager().rollbackTargetName.put(player.getUniqueId(), targetName);

        inv.setItem(11, confirm);
        inv.setItem(13, info);
        inv.setItem(15, cancel);
        player.openInventory(inv);
    }
    private ItemStack createStatsButton(List<Transaction> allTransactions, ConfigManager cm) {
        ItemStack statsButton = getHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTc5NjczMjY0ZWY5NTVmM2Q5NjBlNjk3MzM0MWMxZDhkNjRjZTVjM2YyMzNjYTM0YjI3OTMzNTA4YzM2ODdmMSJ9fX0=");
        ItemMeta statsMeta = statsButton.getItemMeta();
        statsMeta.setDisplayName("§r§6" + cm.getTranslation("stats-1-week-button"));

        List<String> statsLore = new ArrayList<>();
        double income = 0, expense = 0;

        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
        for (Transaction trans : allTransactions) {
            if (trans.timestamp >= cutoff) {
                if (trans.type == Type.INCOME) income += trans.amount;
                else if (trans.type == Type.EXPENSE) expense += trans.amount;
            }
        }

        statsLore.add("§r§7" + cm.getTranslation("stats-income") + "§a" + cm.getAmountFormatter().format(income) + " " + cm.prefix);
        statsLore.add("§r§7" + cm.getTranslation("stats-expense") + "§c" + cm.getAmountFormatter().format(expense) + " " + cm.prefix);

        statsMeta.setLore(statsLore);
        statsButton.setItemMeta(statsMeta);
        return statsButton;
    }

    private List<Double> calculateBalanceLog(List<Transaction> transactions, double currentBalance) {
        List<Double> balances = new ArrayList<>();
        double temp = currentBalance;
        for (Transaction t : transactions) {
            balances.add(temp);
            if (t.type == Type.INCOME) temp -= t.amount;
            else if (t.type == Type.EXPENSE) temp += t.amount;
        }
        return balances;
    }

    public ItemStack createButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack createHeadButton(String base64, String name) {
        ItemStack item = getHead(base64);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    public ItemStack getPlayerHead(String playerName) {
        return headCache.computeIfAbsent(playerName, name -> {
            ItemStack item = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) item.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(name));
            item.setItemMeta(meta);
            return item;
        });
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
            textures.setSkin(new URL(url));
            profile.setTextures(textures);
            skullMeta.setOwnerProfile(profile);
        } catch (Exception e) { return item; }
        item.setItemMeta(skullMeta);
        return item;
    }

    private String getTransactionHeadBase64(Type type) {
        switch (type) {
            case INCOME: return "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDY3ZGNlNDY0NTM0OWU0MWE3ZjM1Nzk3ZTJiOTI3OWUzNWE2NWY1ZTgxYTM0NDk2ODg1ZDI3MjY4ZjM2OTEzOSJ9fX0=";
            case EXPENSE: return "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGQxMTEwOWY0YWIwM2FhNmM1Yjc2Y2FkMTI5MTc2ZmZiMWZjZThjMTc0ZTY5YzllOGJhMDZiOWY4MDYxZTVhZCJ9fX0=";
            case YELLOW: return "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZTQ2N2E3YjlkNzZiYTZkMGZlZDc0MzYwMjUzM2ZjOThjODdhZjBjNjBmODBmMzhkYTc3NGY3YTAxYTIwOTNmYSJ9fX0=";
            default: return "";
        }
    }
    public void openTransactions(Player player, int page, UUID targetUUID, String targetName) {
        plugin.getLogger().info("useDialogs=" + plugin.getConfigManager().useDialogs
                + " isDialogSupported=" + isDialogSupported(player));

        if (plugin.getConfigManager().useDialogs && isDialogSupported(player)) {
            openDialog(player, page, targetUUID, targetName);
        } else {
            openGUI(player, page, targetUUID, targetName);
        }
    }

    private boolean isDialogSupported(Player player) {
        try {
            // Проверяем что класс Dialog вообще есть на сервере
            Class.forName("io.papermc.paper.dialog.Dialog");
        } catch (ClassNotFoundException e) {
            return false;
        }

        try {
            // Проверяем версию протокола клиента через ViaVersion
            // 1.21.6 = протокол 766, 1.21.7 = протокол 771
            int protocol = com.viaversion.viaversion.api.Via.getAPI()
                    .getPlayerVersion(player.getUniqueId());
            return protocol >= 771;
        } catch (Exception e) {
            // ViaVersion недоступен — значит клиент той же версии что сервер (1.21.8)
            // значит диалоги поддерживаются
            return true;
        }
    }
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        plugin.getTransactionManager().onPlayerQuit(e.getPlayer().getUniqueId());
    }

    private static net.kyori.adventure.text.Component legacy(String s) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().deserialize(s);
    }

    public void openDialog(Player player, int page, UUID targetUUID, String targetName) {
        openDialog(player, page, targetUUID, targetName, null, null);
    }

    public void openDialog(Player player, int page, UUID targetUUID, String targetName, FilterType filterOverride, String nickFilter) {
        TransactionManager tm = plugin.getTransactionManager();
        ConfigManager cm = plugin.getConfigManager();

        tm.getTransactionsAsync(targetUUID).thenAccept(list -> {
            FilterData filter = tm.playerFilters.computeIfAbsent(player.getUniqueId(), k -> new FilterData());
            if (filterOverride != null) filter.filterType = filterOverride;

            List<Transaction> filteredList = new ArrayList<>();
            long now = System.currentTimeMillis();
            long cutoffTimestamp = (filter.timePeriod == TimePeriod.LAST_7_DAYS) ? now - TimeUnit.DAYS.toMillis(7) :
                    (filter.timePeriod == TimePeriod.LAST_30_DAYS) ? now - TimeUnit.DAYS.toMillis(30) : 0;

            for (Transaction t : list) {
                if (filter.timePeriod == TimePeriod.ALL_TIME || t.timestamp >= cutoffTimestamp) {
                    boolean typeMatch = filter.filterType == FilterType.ALL ||
                            (filter.filterType == FilterType.INCOME && t.type == Type.INCOME) ||
                            (filter.filterType == FilterType.EXPENSE && t.type == Type.EXPENSE) ||
                            (filter.filterType == FilterType.PAY && t.key.contains("pay")) ||
                            (filter.filterType == FilterType.OTHER && !t.key.contains("pay"));
                    if (!typeMatch) continue;

                    if (nickFilter != null && !nickFilter.isEmpty()) {
                        boolean nickMatch = java.util.Arrays.stream(t.params)
                                .anyMatch(p -> p.equalsIgnoreCase(nickFilter));
                        if (!nickMatch) continue;
                    }
                    filteredList.add(t);
                }
            }
            filteredList.sort((t1, t2) -> Long.compare(t2.timestamp, t1.timestamp));

            double currentBalance = plugin.getEconomy().getBalance(Bukkit.getOfflinePlayer(targetUUID));
            List<Double> balanceLog = calculateBalanceLog(list, currentBalance);

            Map<Long, Integer> timestampToIndex = new HashMap<>();
            for (int i = 0; i < list.size(); i++) {
                timestampToIndex.put(list.get(i).timestamp, i);
            }

            foliaLib.getScheduler().runAtEntity(player,task -> {
                if (!player.isOnline()) return;

                final int perPage = 8;
                int start = page * perPage;
                int end = Math.min(filteredList.size(), start + perPage);
                int totalPages = filteredList.isEmpty() ? 1 : (int) Math.ceil(filteredList.size() / (double) perPage);

                int gmtOffset = tm.playerGmtOffset.getOrDefault(player.getUniqueId(), cm.defaultGmtOffset);
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                sdf.setTimeZone(TimeZone.getTimeZone("GMT" + (gmtOffset >= 0 ? "+" : "") + gmtOffset));

                double weekIncome = 0, weekExpense = 0;
                long weekCutoff = now - TimeUnit.DAYS.toMillis(7);
                for (Transaction t : filteredList) {
                    if (t.timestamp >= weekCutoff) {
                        if (t.type == Type.INCOME) weekIncome += t.amount;
                        else if (t.type == Type.EXPENSE) weekExpense += t.amount;
                    }
                }

                List<io.papermc.paper.registry.data.dialog.body.DialogBody> bodies = new ArrayList<>();

                String filterName = cm.getTranslation("filter-type-" + filter.filterType.name().toLowerCase());
                String nickInfo = (nickFilter != null && !nickFilter.isEmpty()) ? "  §d@" + nickFilter : "";
                bodies.add(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(legacy(
                        "§7" + cm.getTranslation("filter-button") + ": §e" + filterName +
                                nickInfo +
                                "  §8|  §7" + cm.getTranslation("page") + " §f" + (page + 1) + "§7/§f" + totalPages
                )));

                if (cm.showStatsButton) {
                    bodies.add(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(legacy(
                            "§a▲ " + cm.getTranslation("stats-income") +
                                    cm.getAmountFormatter().format(weekIncome) + " " + cm.prefix +
                                    "  §c▼ " + cm.getTranslation("stats-expense") +
                                    cm.getAmountFormatter().format(weekExpense) + " " + cm.prefix
                    )));
                }

                bodies.add(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(
                        legacy("§8§m                                        ")
                ));

                if (filteredList.isEmpty()) {
                    bodies.add(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(
                            legacy("§7  " + cm.getTranslation("no-transactions"))
                    ));
                } else {
                    for (int i = start; i < end; i++) {
                        Transaction t = filteredList.get(i);
                        String time = sdf.format(new Date(t.timestamp));

                        String icon, amountColor, msgColor;
                        switch (t.type) {
                            case INCOME  -> { icon = "§a▲"; amountColor = "§a"; msgColor = cm.incomeMessageColor; }
                            case EXPENSE -> { icon = "§c▼"; amountColor = "§c"; msgColor = cm.expenseMessageColor; }
                            default      -> { icon = "§e●"; amountColor = "§e"; msgColor = cm.yellowMessageColor; }
                        }

                        String rawDesc = ChatColor.stripColor(cm.getTranslatedDescription(t));
                        String cleanDesc = rawDesc.replaceAll("\\(.*?\\)", "").trim();
                        String sign = t.type == Type.INCOME ? "+" : t.type == Type.EXPENSE ? "-" : "";

                        StringBuilder line = new StringBuilder();
                        line.append(icon).append(" ")
                                .append(msgColor).append(cleanDesc)
                                .append("  ").append(amountColor)
                                .append(sign).append(cm.getAmountFormatter().format(t.amount))
                                .append(" ").append(cm.prefix);

                        if (tm.showBalance.getOrDefault(player.getUniqueId(), true) && t.type != Type.YELLOW) {
                            Integer idx = timestampToIndex.get(t.timestamp);
                            if (idx != null && idx < balanceLog.size()) {
                                double after = balanceLog.get(idx);
                                double before = after + (t.type == Type.INCOME ? -t.amount : t.amount);
                                line.append("\n   §8")
                                        .append(cm.getAmountFormatter().format(Math.abs(before)))
                                        .append(" → ")
                                        .append(cm.getAmountFormatter().format(Math.abs(after)))
                                        .append("  §7").append(time);
                            } else {
                                line.append("  §7").append(time);
                            }
                        } else {
                            line.append("  §7").append(time);
                        }

                        if (player.hasPermission("transactions.source") && t.source != null && !t.source.isEmpty()) {
                            line.append("\n   §8").append(cm.getTranslation("source-plugin")).append(t.source);
                        }

                        bodies.add(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(
                                legacy(line.toString())
                        ));
                    }
                }

                var opts = net.kyori.adventure.text.event.ClickCallback.Options.builder()
                        .uses(Integer.MAX_VALUE)
                        .lifetime(java.time.Duration.ofMinutes(10))
                        .build();

                List<io.papermc.paper.registry.data.dialog.ActionButton> buttons = new ArrayList<>();

                if (page > 0) {
                    final int prevPage = page - 1;
                    buttons.add(io.papermc.paper.registry.data.dialog.ActionButton.builder(
                                    legacy("§e◀ " + cm.getTranslation("prev-button")))
                            .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                    (view, audience) -> { if (audience instanceof Player p) openDialog(p, prevPage, targetUUID, targetName, null, nickFilter); },
                                    opts
                            )).build()
                    );
                }

                if (end < filteredList.size()) {
                    final int nextPage = page + 1;
                    buttons.add(io.papermc.paper.registry.data.dialog.ActionButton.builder(
                                    legacy("§e" + cm.getTranslation("next-button") + " ▶"))
                            .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                    (view, audience) -> { if (audience instanceof Player p) openDialog(p, nextPage, targetUUID, targetName, null, nickFilter); },
                                    opts
                            )).build()
                    );
                }

                // Фильтр цикличный
                FilterType nextFilter = switch (filter.filterType) {
                    case ALL -> FilterType.INCOME;
                    case INCOME -> FilterType.EXPENSE;
                    case EXPENSE -> FilterType.PAY;
                    case PAY -> FilterType.OTHER;
                    case OTHER -> FilterType.ALL;
                };
                buttons.add(io.papermc.paper.registry.data.dialog.ActionButton.builder(
                                legacy("§b⚙ " + cm.getTranslation("filter-type-" + nextFilter.name().toLowerCase())))
                        .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                (view, audience) -> { if (audience instanceof Player p) openDialog(p, 0, targetUUID, targetName, nextFilter, nickFilter); },
                                opts
                        )).build()
                );

                // Поиск по нику
                if (player.getUniqueId().equals(targetUUID) || player.hasPermission("transactions.view.others")) {
                    String searchLabel = (nickFilter != null && !nickFilter.isEmpty())
                            ? "§d✕ " + nickFilter
                            : "§9⌕ " + cm.getTranslation("search-button");
                    buttons.add(io.papermc.paper.registry.data.dialog.ActionButton.builder(legacy(searchLabel))
                            .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                                    (view, audience) -> {
                                        if (audience instanceof Player p) {
                                            if (nickFilter != null && !nickFilter.isEmpty()) {
                                                openDialog(p, 0, targetUUID, targetName, null, null);
                                            } else {
                                                openSearchDialog(p, targetUUID, targetName);
                                            }
                                        }
                                    },
                                    opts
                            )).build()
                    );
                }

                // Закрыть
                buttons.add(io.papermc.paper.registry.data.dialog.ActionButton.builder(
                                legacy("§c✕ " + cm.getTranslation("close-button")))
                        .action(null)
                        .build()
                );

                io.papermc.paper.dialog.Dialog dialog = io.papermc.paper.dialog.Dialog.create(builder -> builder.empty()
                        .base(io.papermc.paper.registry.data.dialog.DialogBase.builder(
                                        legacy("§6§l" + cm.getTranslation("gui-title") + "§r§7" + targetName))
                                .body(bodies)
                                .build()
                        )
                        .type(io.papermc.paper.registry.data.dialog.type.DialogType.multiAction(buttons).build())
                );

                player.showDialog(dialog);

                tm.currentPage.put(player.getUniqueId(), page);
                tm.searchTargetPlayer.put(player.getUniqueId(), targetName);
                tm.rollbackTargetUUID.put(player.getUniqueId(), targetUUID);
            });
        });
    }

    public void openSearchDialog(Player player, UUID targetUUID, String targetName) {
        ConfigManager cm = plugin.getConfigManager();

        var opts = net.kyori.adventure.text.event.ClickCallback.Options.builder()
                .uses(Integer.MAX_VALUE)
                .lifetime(java.time.Duration.ofMinutes(5))
                .build();

        var nickInput = io.papermc.paper.registry.data.dialog.input.DialogInput.text(
                "nick",
                legacy("§fНик игрока")
        ).build();

        var confirmBtn = io.papermc.paper.registry.data.dialog.ActionButton.builder(
                        legacy("§a" + cm.getTranslation("search-confirm")))
                .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                        (view, audience) -> {
                            if (audience instanceof Player p) {
                                String nick = view.getText("nick");
                                if (nick != null && !nick.isBlank()) {
                                    openDialog(p, 0, targetUUID, targetName, null, nick.trim());
                                } else {
                                    openDialog(p, 0, targetUUID, targetName, null, null);
                                }
                            }
                        },
                        opts
                ))
                .build();

        var backBtn = io.papermc.paper.registry.data.dialog.ActionButton.builder(
                        legacy("§7← " + cm.getTranslation("back-button")))
                .action(io.papermc.paper.registry.data.dialog.action.DialogAction.customClick(
                        (view, audience) -> {
                            if (audience instanceof Player p)
                                openDialog(p, 0, targetUUID, targetName, null, null);
                        },
                        opts
                ))
                .build();

        io.papermc.paper.dialog.Dialog dialog = io.papermc.paper.dialog.Dialog.create(builder -> builder.empty()
                .base(io.papermc.paper.registry.data.dialog.DialogBase.builder(
                                legacy("§9⌕ " + cm.getTranslation("search-button")))
                        .body(List.of(
                                io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(
                                        legacy("§7" + cm.getTranslation("enter-player-name"))
                                )
                        ))
                        .inputs(List.of(nickInput))
                        .build()
                )
                .type(io.papermc.paper.registry.data.dialog.type.DialogType.multiAction(
                        List.of(confirmBtn, backBtn)
                ).build())
        );

        player.showDialog(dialog);
    }


}