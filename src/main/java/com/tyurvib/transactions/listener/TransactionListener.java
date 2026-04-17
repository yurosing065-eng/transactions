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
import java.util.Map;
import java.util.Set;
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

        if (!strippedTitle.contains(mainTitle) && !strippedTitle.contains(filterTitle) && !strippedTitle.contains(rollbackTitle)) return;
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
            if (name.contains(ChatColor.stripColor(plugin.getConfigManager().getTranslation("prev-button")))) {
                if (page > 0) plugin.getGuiManager().openGUI(player, page - 1, targetUUID, targetName);
            } else if (name.contains(ChatColor.stripColor(plugin.getConfigManager().getTranslation("next-button")))) {
                plugin.getGuiManager().openGUI(player, page + 1, targetUUID, targetName);
            }
            return;
        }

        if (slot == 48) {
            plugin.getGuiManager().openFilterGUI(player);
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
            tm.getTransactionsAsync(targetUUID).thenAccept(list -> {
                int index = slot + (page * 45);
                if (index >= 0 && index < list.size()) {
                    Transaction t = list.get(index);
                    if (t.key.equals("transaction-pay-received") && !t.rolledBack) {
                        // ✅ было: getRegionScheduler().run(plugin, player.getLocation(), ...)
                        plugin.getFoliaLib().getImpl().runAtLocation(player.getLocation(), task -> {
                            plugin.getGuiManager().openRollbackGUI(player, t, targetUUID, targetName);
                        });
                    }
                }
            });
        }
    }

    private void performRollback(Player admin, Transaction t, UUID targetUUID, String targetName) {
        if (t.rolledBack) {
            admin.sendMessage("§c" + plugin.getConfigManager().getTranslation("rollback-already-done"));
            return;
        }

        // ✅ было: getGlobalRegionScheduler().run(plugin, ...)
        plugin.getFoliaLib().getImpl().runNextTick(task -> {
            String senderName = t.params[0];
            OfflinePlayer sender = Bukkit.getOfflinePlayer(senderName);
            OfflinePlayer receiver = Bukkit.getOfflinePlayer(targetName);
            double amount = t.amount;

            if (!plugin.getEconomy().has(receiver, amount)) {
                admin.sendMessage("§c" + plugin.getConfigManager().getTranslation("rollback-insufficient-funds", targetName));
                return;
            }

            double recBalBefore = plugin.getEconomy().getBalance(receiver);
            double senBalBefore = plugin.getEconomy().getBalance(sender);

            EconomyResponse w = plugin.getEconomy().withdrawPlayer(receiver, amount);
            if (w.transactionSuccess()) {
                plugin.getEconomy().depositPlayer(sender, amount);

                t.rolledBack = true;
                plugin.getDatabaseManager().updateRollbackStatus(targetUUID, t.timestamp, t.key, true);

                plugin.getTransactionManager().addTransaction(receiver.getUniqueId(), new Transaction(
                        Type.EXPENSE, "transaction-rollback", amount, recBalBefore, recBalBefore - amount, senderName));

                plugin.getTransactionManager().addTransaction(sender.getUniqueId(), new Transaction(
                        Type.INCOME, "transaction-rollback-received", amount, senBalBefore, senBalBefore + amount, targetName));

                admin.sendMessage("§aRollback successful.");
                // ✅ было: getRegionScheduler().run(plugin, admin.getLocation(), ...)
                plugin.getFoliaLib().getImpl().runAtLocation(admin.getLocation(), t2 -> {
                    plugin.getGuiManager().openGUI(admin, 0, targetUUID, targetName);
                });
            }
        });
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        if (!plugin.getTransactionManager().playerInSearchMode.remove(p.getUniqueId())) return;

        e.setCancelled(true);
        String searchName = e.getMessage().trim();
        if (searchName.isEmpty()) return;

        TransactionManager tm = plugin.getTransactionManager();
        UUID targetUUID = tm.rollbackTargetUUID.getOrDefault(p.getUniqueId(), p.getUniqueId());
        String targetName = tm.searchTargetPlayer.getOrDefault(p.getUniqueId(), p.getName());
        tm.getTransactionsAsync(targetUUID).thenAccept(all -> {
            List<Transaction> found = all.stream()
                    .filter(t -> java.util.Arrays.stream(t.params).anyMatch(param -> param.equalsIgnoreCase(searchName)))
                    .collect(java.util.stream.Collectors.toList());

            // ✅ было: player вместо p (баг!) + getRegionScheduler()
            plugin.getFoliaLib().getImpl().runAtLocation(p.getLocation(), task -> {
                if (found.isEmpty()) {
                    p.sendMessage("§c" + plugin.getConfigManager().getTranslation("no-transactions-found", searchName));
                } else {
                    tm.searchTargetPlayer.put(p.getUniqueId(), searchName);
                    p.sendMessage("§a" + plugin.getConfigManager().getTranslation("search-success", String.valueOf(found.size())));
                }
                plugin.getGuiManager().openGUI(p, 0, targetUUID, targetName);
            });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent e) {
        String[] args = e.getMessage().substring(1).split(" ");
        handleEcoCommand(e.getPlayer(), args);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent e) {
        handleEcoCommand(null, e.getCommand().split(" "));
    }

    private void handleEcoCommand(Player sender, String[] args) {
        if (args.length < 2) return;

        String cmd = args[0].toLowerCase();
        if (!cmd.equals("eco") && !cmd.equals("economy") && !cmd.equals("pay") && !cmd.equals("money")) return;

        TransactionManager tm = plugin.getTransactionManager();
        String actorName = (sender != null) ? sender.getName() : "Console";

        if (cmd.equals("pay") && args.length >= 3 && sender != null) {
            String targetName = args[1];
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) return;

            UUID senderId = sender.getUniqueId();
            double senderBalBefore = plugin.getEconomy().getBalance(sender);
            UUID targetId = target.getUniqueId();
            double targetBalBefore = plugin.getEconomy().getBalance(target);

            tm.ecoInProgress.add(senderId);
            tm.ecoInProgress.add(targetId);

            // ✅ было: getGlobalRegionScheduler().runDelayed(plugin, ..., 5L)
            plugin.getFoliaLib().getImpl().runLater(task -> {
                double senderBalAfter = plugin.getEconomy().getBalance(sender);
                double senderDelta = senderBalBefore - senderBalAfter;
                if (senderDelta > 0.01) {
                    tm.addTransaction(senderId, new Transaction(
                            Type.EXPENSE, "transaction-pay-sent", senderDelta, senderBalBefore, senderBalAfter, target.getName()
                    ));
                }

                double targetBalAfter = plugin.getEconomy().getBalance(target);
                double targetDelta = targetBalAfter - targetBalBefore;
                if (targetDelta > 0.01) {
                    tm.addTransaction(targetId, new Transaction(
                            Type.INCOME, "transaction-pay-received", targetDelta, targetBalBefore, targetBalAfter, sender.getName()
                    ));
                }

                tm.ecoInProgress.remove(senderId);
                tm.ecoInProgress.remove(targetId);
            }, 5L);
            return;
        }

        if ((cmd.equals("eco") || cmd.equals("economy")) && args.length >= 4) {
            String sub = args[1].toLowerCase();
            String targetName = args[2];
            boolean isSet = sub.equals("set") || sub.equals("reset");
            String key = sub.equals("give") ? "transaction-admin-give" : "transaction-admin-take";

            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) return;

            UUID tId = target.getUniqueId();
            double balanceBefore = plugin.getEconomy().getBalance(target);
            tm.ecoInProgress.add(tId);

            // ✅ было: getGlobalRegionScheduler().runDelayed(plugin, ..., 5L)
            plugin.getFoliaLib().getImpl().runLater(task -> {
                double balanceAfter = plugin.getEconomy().getBalance(target);
                double delta = balanceAfter - balanceBefore;

                if (isSet) {
                    tm.addTransaction(tId, new Transaction(
                            Type.YELLOW, "transaction-balance-set", balanceAfter, balanceBefore, balanceAfter, actorName
                    ));
                } else if (Math.abs(delta) > 0.01) {
                    Type type = (delta > 0) ? Type.INCOME : Type.EXPENSE;
                    tm.addTransaction(tId, new Transaction(
                            type, key, Math.abs(delta), balanceBefore, balanceAfter, actorName
                    ));
                }
                tm.ecoInProgress.remove(tId);
            }, 5L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWithdraw(PlayerWithdrawEvent e) {
        if (!plugin.getConfigManager().logExternalTransactions) return;

        UUID uuid = e.getOfflinePlayer().getUniqueId();
        TransactionManager tm = plugin.getTransactionManager();

        if (tm.payInProgress.contains(uuid) ||
                tm.ecoInProgress.contains(uuid) ||
                tm.shopInProgress.contains(uuid)) return;


        double amount = e.getAmount();
        plugin.getFoliaLib().getImpl().runNextTick(task -> {
            String eventKey = "transaction-external-withdraw";
            if (!isEventEnabled(eventKey)) return;
            double balanceBefore = plugin.getEconomy().getBalance(e.getOfflinePlayer());
            double balanceAfter = Math.max(0, balanceBefore - amount);
            if (balanceBefore - balanceAfter > 0 || amount > 0) {
                Transaction t = new Transaction(Type.EXPENSE, eventKey, amount, balanceBefore, balanceAfter);
                plugin.getTransactionManager().addTransaction(uuid, t);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeposit(PlayerDepositEvent e) {
        if (!plugin.getConfigManager().logExternalTransactions) return;

        UUID uuid = e.getOfflinePlayer().getUniqueId();
        TransactionManager tm = plugin.getTransactionManager();

        if (tm.payInProgress.contains(uuid) ||
                tm.ecoInProgress.contains(uuid) ||
                tm.shopInProgress.contains(uuid)) return;



        double amount = e.getAmount();
        plugin.getFoliaLib().getImpl().runNextTick(task -> {
            String eventKey = "transaction-external-deposit";
            if (!isEventEnabled(eventKey)) return;
            double balanceBefore = plugin.getEconomy().getBalance(e.getOfflinePlayer());
            Transaction t = new Transaction(Type.INCOME, eventKey, amount, balanceBefore, balanceBefore + amount);
            plugin.getTransactionManager().addTransaction(uuid, t);
        });
    }



    private boolean isEventEnabled(String key) {
        Map<String, Boolean> events = plugin.getConfigManager().eventEnabled;
        return events.getOrDefault(key, true);
    }
    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        TransactionManager tm = plugin.getTransactionManager();
        if (tm.isDirty(uuid)) {
            plugin.getDatabaseManager().savePlayerSettingsSync(
                    Set.of(uuid),
                    tm.playerGmtOffset,
                    tm.playerFilters,
                    tm.showBalance
            );
        }
        tm.onPlayerQuit(uuid);
    }
}