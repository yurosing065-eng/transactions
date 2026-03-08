package com.tyurvib.transactions.listener;

import com.djrapitops.vaultevents.events.economy.PlayerDepositEvent;
import com.djrapitops.vaultevents.events.economy.PlayerWithdrawEvent;
import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.manager.TransactionManager;
import com.tyurvib.transactions.model.FilterData;
import com.tyurvib.transactions.model.FilterType;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.List;
import java.util.UUID;

public class TransactionListener implements Listener {

    private final Transactions plugin;

    public TransactionListener(Transactions plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (e.getPlayer().isOp() && plugin.getUpdater() != null && plugin.getUpdater().isUpdateAvailable()) {
            e.getPlayer().sendMessage(plugin.getConfigManager().getTranslation("update-available",
                    e.getPlayer().getName(),
                    plugin.getUpdater().getLatestVersion(),
                    "https://modrinth.com/plugin/transactions"));
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (e.getCurrentItem() == null) return;

        String title = e.getView().getTitle();
        String mainTitle = ChatColor.stripColor(plugin.getConfigManager().getTranslation("gui-title"));
        String filterTitle = ChatColor.stripColor(plugin.getConfigManager().getTranslation("filter-gui-title"));
        String rollbackTitle = ChatColor.stripColor(plugin.getConfigManager().getTranslation("rollback-confirm-title"));
        String strippedTitle = ChatColor.stripColor(title);

        boolean isOurGui = strippedTitle.contains(mainTitle) ||
                strippedTitle.contains(filterTitle) ||
                strippedTitle.contains(rollbackTitle);

        if (!isOurGui) return;
        e.setCancelled(true);

        TransactionManager tm = plugin.getTransactionManager();
        UUID targetUUID = tm.rollbackTargetUUID.getOrDefault(player.getUniqueId(), player.getUniqueId());
        String targetName = tm.searchTargetPlayer.getOrDefault(player.getUniqueId(), player.getName());
        int page = tm.currentPage.getOrDefault(player.getUniqueId(), 0);
        int slot = e.getSlot();


        if (strippedTitle.contains(filterTitle)) {
            FilterData fd = tm.playerFilters.computeIfAbsent(player.getUniqueId(), k -> new FilterData());
            if (slot == 1) fd.filterType = FilterType.ALL;
            else if (slot == 2) fd.filterType = FilterType.INCOME;
            else if (slot == 3) fd.filterType = FilterType.EXPENSE;
            else if (slot == 5) fd.filterType = FilterType.PAY;
            else if (slot == 6) fd.filterType = FilterType.OTHER;

            tm.markDirty(player.getUniqueId());
            plugin.getGuiManager().openGUI(player, 0, targetUUID, targetName);
            return;
        }


        if (strippedTitle.contains(rollbackTitle)) {
            if (slot == 11) {
                Transaction t = tm.pendingRollback.get(player.getUniqueId());
                if (t != null) {
                    performRollback(player, t, targetUUID, tm.rollbackTargetName.getOrDefault(player.getUniqueId(), targetName));
                    tm.pendingRollback.remove(player.getUniqueId());
                }
            } else if (slot == 15) {
                plugin.getGuiManager().openGUI(player, page, targetUUID, targetName);
            }
            return;
        }



        if (e.getCurrentItem().getType() == Material.ARROW) {
            String name = ChatColor.stripColor(e.getCurrentItem().getItemMeta().getDisplayName());
            String prevName = ChatColor.stripColor(plugin.getConfigManager().getTranslation("prev-button"));
            String nextName = ChatColor.stripColor(plugin.getConfigManager().getTranslation("next-button"));

            if (name.contains(prevName)) {
                if (page > 0) plugin.getGuiManager().openGUI(player, page - 1, targetUUID, targetName);
            } else if (name.contains(nextName)) {
                plugin.getGuiManager().openGUI(player, page + 1, targetUUID, targetName);
            }
            return;
        }


        if (e.getCurrentItem().getType() == Material.BARRIER && slot == 50) {
            plugin.getGuiManager().openGUI(player, page, targetUUID, targetName);
            return;
        }


        if (slot == 48) {
            plugin.getGuiManager().openFilterGUI(player);
            return;
        }


        if (slot == 46 && player.isOp()) {
            tm.downloadTransactionsToTxt(player.getUniqueId(), targetUUID, targetName);
            return;
        }


        if (slot == 50) {
            if (player.getUniqueId().equals(targetUUID) || player.hasPermission("transactions.view.others")) {
                tm.playerInSearchMode.add(player.getUniqueId());
                player.closeInventory();
                player.sendMessage("§e" + plugin.getConfigManager().getTranslation("enter-player-name"));
            }
            return;
        }

        if (e.getClick().isRightClick() && player.hasPermission("transactions.rollback")) {
            List<Transaction> list = tm.getTransactions(targetUUID);

            int index = slot + (page * 45);

            if (index >= 0 && index < list.size()) {
                Transaction t = list.get(index);
                if (t.key.equals("transaction-pay-received") && !t.rolledBack) {
                    plugin.getGuiManager().openRollbackGUI(player, t, targetUUID, targetName);
                }
            }
        }
    }


    private void performRollback(Player admin, Transaction t, UUID targetUUID, String targetName) {
        if (t.rolledBack) {
            admin.sendMessage("§c" + plugin.getConfigManager().getTranslation("rollback-already-done"));
            return;
        }

        String senderName = t.params[0];
        OfflinePlayer sender = Bukkit.getOfflinePlayer(senderName);
        OfflinePlayer receiver = Bukkit.getOfflinePlayer(targetName);
        double amount = t.amount;

        if (!plugin.getEconomy().has(receiver, amount)) {
            admin.sendMessage("§c" + plugin.getConfigManager().getTranslation("rollback-insufficient-funds", targetName));
            return;
        }

        double receiverBalBefore = plugin.getEconomy().getBalance(receiver);
        double senderBalBefore = plugin.getEconomy().getBalance(sender);


        EconomyResponse w = plugin.getEconomy().withdrawPlayer(receiver, amount);
        if (!w.transactionSuccess()) {
            admin.sendMessage("§cError withdrawing: " + w.errorMessage);
            return;
        }

        EconomyResponse d = plugin.getEconomy().depositPlayer(sender, amount);
        if (!d.transactionSuccess()) {

            plugin.getEconomy().depositPlayer(receiver, amount);
            admin.sendMessage("§cError depositing to sender.");
            return;
        }

        t.rolledBack = true;
        plugin.getDatabaseManager().updateRollbackStatus(targetUUID, t.timestamp, t.key, true);

        plugin.getTransactionManager().addTransaction(receiver.getUniqueId(), new Transaction(
                Type.EXPENSE,
                "transaction-rollback",
                amount,
                receiverBalBefore,
                receiverBalBefore - amount,
                senderName
        ));

        plugin.getTransactionManager().addTransaction(sender.getUniqueId(), new Transaction(
                Type.INCOME,
                "transaction-rollback-received",
                amount,
                senderBalBefore,
                senderBalBefore + amount,
                targetName
        ));

        admin.sendMessage("§aRollback successful.");
        plugin.getGuiManager().openGUI(admin, 0, targetUUID, targetName);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        if (e.isCancelled()) return;
        String[] args = e.getMessage().substring(1).split(" ");
        handleEcoCommand(e.getPlayer(), args);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onServerCommand(ServerCommandEvent e) {
        handleEcoCommand(null, e.getCommand().split(" "));
    }

    private void handleEcoCommand(Player sender, String[] args) {
        if (args.length < 3) return;
        String cmd = args[0].toLowerCase();
        if (!cmd.equals("eco") && !cmd.equals("economy")) return;

        String sub = args[1].toLowerCase();
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) return;

        UUID tId = target.getUniqueId();
        String adminName = (sender != null) ? sender.getName() : "Console";
        TransactionManager tm = plugin.getTransactionManager();

        double balanceBefore = plugin.getEconomy().getBalance(target);
        tm.ecoInProgress.add(tId);
        Bukkit.getGlobalRegionScheduler().runDelayed(plugin, (task) -> {
            double balanceAfter = plugin.getEconomy().getBalance(target);
            double delta = Math.abs(balanceAfter - balanceBefore);

            if (sub.equals("set") || sub.equals("reset")) {
                tm.addTransaction(tId, new Transaction(
                        Type.YELLOW, "transaction-balance-set", balanceAfter, balanceBefore, balanceAfter, adminName
                ));
            } else if (delta > 0.001) {
                Type type = (balanceAfter > balanceBefore) ? Type.INCOME : Type.EXPENSE;
                String key = (type == Type.INCOME) ? "transaction-admin-give" : "transaction-admin-take";

                tm.addTransaction(tId, new Transaction(
                        type, key, delta, balanceBefore, balanceAfter, adminName
                ));
            }

            tm.ecoInProgress.remove(tId);
        }, 2L);
    }
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getTransactionManager().playerInSearchMode.remove(p.getUniqueId())) return;

        e.setCancelled(true);
        String searchName = e.getMessage().trim();

        if (searchName.isEmpty()) {
            p.sendMessage("§c" + plugin.getConfigManager().getTranslation("search-cancelled"));
            return;
        }

        UUID targetUUID = plugin.getTransactionManager().rollbackTargetUUID.getOrDefault(p.getUniqueId(), p.getUniqueId());
        String targetName = plugin.getTransactionManager().searchTargetPlayer.getOrDefault(p.getUniqueId(), p.getName());

        List<Transaction> all = plugin.getTransactionManager().getTransactions(targetUUID);


        List<Transaction> found = all.stream()
                .filter(t -> java.util.Arrays.stream(t.params).anyMatch(param -> param.equalsIgnoreCase(searchName)))
                .collect(java.util.stream.Collectors.toList());

        if (found.isEmpty()) {
            p.sendMessage("§c" + plugin.getConfigManager().getTranslation("no-transactions-found", searchName));
            Bukkit.getRegionScheduler().run(plugin, p.getLocation(), task -> {
                plugin.getGuiManager().openGUI(p, 0, targetUUID, targetName);
            });
            return;
        }

        Bukkit.getRegionScheduler().run(plugin, p.getLocation(), task -> {
            plugin.getTransactionManager().searchTargetPlayer.put(p.getUniqueId(), searchName);
            plugin.getGuiManager().openGUI(p, 0, targetUUID, targetName);
            p.sendMessage("§a" + plugin.getConfigManager().getTranslation("search-success", String.valueOf(found.size())));
        });
    }

    // ПОЛНЫЙ МЕТОД: onDeposit
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDeposit(PlayerDepositEvent e) {
        if (!plugin.getConfigManager().logExternalTransactions) return;
        UUID uuid = e.getOfflinePlayer().getUniqueId();
        if (plugin.getTransactionManager().payInProgress.contains(uuid) ||
                plugin.getTransactionManager().ecoInProgress.contains(uuid)) return;

        double amount = e.getAmount();
        double balanceBefore = plugin.getEconomy().getBalance(e.getOfflinePlayer());

        // Для внешних событий мы фиксируем баланс ДО и рассчитываем ПОСЛЕ
        plugin.getTransactionManager().addTransaction(uuid,
                new Transaction(Type.INCOME, "transaction-external-deposit", amount, balanceBefore, balanceBefore + amount));
    }

    // ПОЛНЫЙ МЕТОД: onWithdraw
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onWithdraw(PlayerWithdrawEvent e) {
        if (!plugin.getConfigManager().logExternalTransactions) return;
        UUID uuid = e.getOfflinePlayer().getUniqueId();
        if (plugin.getTransactionManager().payInProgress.contains(uuid) ||
                plugin.getTransactionManager().ecoInProgress.contains(uuid)) return;

        double amount = e.getAmount();
        double balanceBefore = plugin.getEconomy().getBalance(e.getOfflinePlayer());

        plugin.getTransactionManager().addTransaction(uuid,
                new Transaction(Type.EXPENSE, "transaction-external-withdraw", amount, balanceBefore, balanceBefore - amount));
    }}