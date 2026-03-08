package com.tyurvib.transactions.manager;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.model.*;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class GuiManager {
    private final Transactions plugin;
    private final ItemStack INCOME_ITEM = getHead(getTransactionHeadBase64(Type.INCOME));
    private final ItemStack EXPENSE_ITEM = getHead(getTransactionHeadBase64(Type.EXPENSE));
    private final ItemStack PAY_ITEM = getHead(getTransactionHeadBase64(Type.YELLOW));
    public static final String SELECTED_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOTIxOTI4ZWE2N2QzYThiOTdkMjEyNzU4ZjE1Y2NjYWMxMDI0Mjk1YjE4NWIzMTkyNjQ4NDRmNGM1ZTFlNjFlIn19fQ==";
    public static final String UNSELECTED_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjVlZjY4ZGNiZDU4MjM0YmE3YWVlMmFkOTFjYTZmYTdjZTIzZjlhMzIzNDViNDhkNmU1ZjViODZhNjhiNWIifX19";
    public static final String SEARCH_HEAD = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZjc0OGYyMTM1ODhkYmY0NDE1Y2UyNGZlNjZkZTM1MjY4MTZiZjM1ZGY4ZTM5OGY5OGVmZWMyZmIwODk1NmEzIn19fQ==";

    public GuiManager(Transactions plugin) {
        this.plugin = plugin;
    }

    public void openGUI(Player player, int page, UUID targetUUID, String targetName) {
        TransactionManager tm = plugin.getTransactionManager();
        ConfigManager cm = plugin.getConfigManager();
        FilterData filter = tm.playerFilters.computeIfAbsent(player.getUniqueId(), k -> new FilterData());
        List<Transaction> list = tm.getTransactions(targetUUID);
        List<Transaction> filteredList = new ArrayList<>();
        long cutoffTimestamp = 0;

        if (filter.timePeriod == TimePeriod.LAST_7_DAYS)
            cutoffTimestamp = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(7);
        else if (filter.timePeriod == TimePeriod.LAST_30_DAYS)
            cutoffTimestamp = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(30);

        for (Transaction t : list) {
            if (filter.timePeriod == TimePeriod.ALL_TIME || t.timestamp >= cutoffTimestamp) {
                if (filter.filterType == FilterType.ALL ||
                        (filter.filterType == FilterType.INCOME && t.type == Type.INCOME) ||
                        (filter.filterType == FilterType.EXPENSE && t.type == Type.EXPENSE) ||
                        (filter.filterType == FilterType.PAY && (t.key.contains("pay"))) ||
                        (filter.filterType == FilterType.OTHER && !t.key.contains("pay"))) {
                    filteredList.add(t);
                }
            }
        }

        filteredList.sort((t1, t2) -> Long.compare(t2.timestamp, t1.timestamp));


        Inventory inv = Bukkit.createInventory(null, 54, cm.getTranslation("gui-title") + targetName + " (#" + (page + 1) + ")");
        int start = page * 45;
        int end = Math.min(filteredList.size(), start + 45);


        double currentBalance = plugin.getEconomy().getBalance(Bukkit.getOfflinePlayer(targetUUID));
        List<Double> balanceLog = calculateBalanceLog(filteredList, currentBalance);

        int gmtOffset = tm.playerGmtOffset.getOrDefault(player.getUniqueId(), cm.defaultGmtOffset);
        SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT" + (gmtOffset >= 0 ? "+" : "") + gmtOffset));


        for (int i = start; i < end; i++) {
            Transaction t = filteredList.get(i);
            ItemStack item;


            if (t.key.contains("pay") && t.params.length > 0) {
                item = getPlayerHead(t.params[0]);
            } else {
                item = getHead(getTransactionHeadBase64(t.type));
            }

            ItemMeta meta = item.getItemMeta();
            String colorCode = cm.getTransactionColor(t.type);


            meta.setDisplayName("§r" + colorCode + cm.getTranslatedDescription(t));

            List<String> lore = new ArrayList<>();


            if (tm.showBalance.getOrDefault(player.getUniqueId(), true) && t.type != Type.YELLOW) {
                double after = balanceLog.get(i);
                double before = after + (t.type == Type.INCOME ? -t.amount : t.amount);

                String balanceText = cm.getTranslation("balance-change",
                        cm.getAmountFormatter().format(Math.abs(before)),
                        cm.getAmountFormatter().format(Math.abs(after)));

                lore.add("§r§f" + balanceText);
            }


            lore.add("§r§7" + cm.getTranslation("time") + sdf.format(new Date(t.timestamp)));

            if (player.hasPermission("transactions.rollback") && t.key.equals("transaction-pay-received") && !t.rolledBack) {
                lore.add("§r§e" + cm.getTranslation("rollback-hint"));
            }

            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(i - start, item);
        }


        if (page > 0) inv.setItem(45, createButton(Material.ARROW, "§r" + cm.getTranslation("prev-button")));
        if (end < filteredList.size()) inv.setItem(53, createButton(Material.ARROW, "§r" + cm.getTranslation("next-button")));


        if (player.isOp()) {
            ItemStack dlBtn = getHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODgyZmFmOWE1ODRjNGQ2NzZkNzMwYjIzZjg5NDJiYjk5N2ZhM2RhZDQ2ZDRmNjVlMjg4YzM5ZWI0NzFjZTcifX19");
            ItemMeta dlMeta = dlBtn.getItemMeta();
            dlMeta.setDisplayName("§r§6" + cm.getTranslation("download-button"));
            dlBtn.setItemMeta(dlMeta);
            inv.setItem(46, dlBtn);
        }


        ItemStack filterBtn = getHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGNlZTNhZWFkZDY4YjI0N2ZkZmUzZWE3YmMwMDhkMTJmZDk3YWMxNWVkMTViZjc0Njg1NDNhMDY2ODAifX19");
        ItemMeta fm = filterBtn.getItemMeta();
        fm.setDisplayName("§r" + cm.getTranslation("filter-button"));
        fm.setLore(Collections.singletonList("§r§7" + cm.getTranslation("filter-current-type") + cm.getTranslation("filter-type-" + filter.filterType.name().toLowerCase())));
        filterBtn.setItemMeta(fm);
        inv.setItem(48, filterBtn);

        if (cm.showStatsButton) {
            ItemStack statsButton = getHead("eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTc5NjczMjY0ZWY5NTVmM2Q5NjBlNjk3MzM0MWMxZDhkNjRjZTVjM2YyMzNjYTM0YjI3OTMzNTA4YzM2ODdmMSJ9fX0=");
            ItemMeta statsMeta = statsButton.getItemMeta();
            statsMeta.setDisplayName("§r§6" + cm.getTranslation("stats-1-week-button"));

            List<String> statsLore = new ArrayList<>();
            double income = 0, expense = 0;

            long cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(7);
            for (Transaction trans : list) {
                if (trans.timestamp >= cutoff) {
                    if (trans.type == Type.INCOME) income += trans.amount;
                    else if (trans.type == Type.EXPENSE) expense += trans.amount;
                }
            }
            statsLore.add("§r§7" + cm.getTranslation("stats-income") + "§a" + cm.getAmountFormatter().format(income) + " " + cm.prefix);
            statsLore.add("§r§7" + cm.getTranslation("stats-expense") + "§c" + cm.getAmountFormatter().format(expense) + " " + cm.prefix);

            statsMeta.setLore(statsLore);
            statsButton.setItemMeta(statsMeta);
            inv.setItem(49, statsButton);
        }

      ItemStack searchBtn = player.getUniqueId().equals(targetUUID) || player.hasPermission("transactions.view.others")
                ? createHeadButton(SEARCH_HEAD, "§r§9" + cm.getTranslation("search-button"))
                : createButton(Material.BARRIER, "§r§c" + cm.getTranslation("search-disabled"));
        inv.setItem(50, searchBtn);


        tm.currentPage.put(player.getUniqueId(), page);
        tm.searchTargetPlayer.put(player.getUniqueId(), targetName);
        tm.rollbackTargetUUID.put(player.getUniqueId(), targetUUID);

        player.openInventory(inv);
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
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
        item.setItemMeta(meta);
        return item;
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
}