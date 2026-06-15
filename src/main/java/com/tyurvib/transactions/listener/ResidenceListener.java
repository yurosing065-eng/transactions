package com.tyurvib.transactions.listener;

import com.bekvon.bukkit.residence.event.ResidenceBuyEvent;
import com.bekvon.bukkit.residence.event.ResidenceSellEvent;
import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.manager.TransactionManager;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

/**
 * Поддержка Residence (by bekvon) — покупка и продажа участков.
 * API: com.bekvon.bukkit.residence.event
 */
public class ResidenceListener implements Listener {

    private final Transactions plugin;

    public ResidenceListener(Transactions plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceBuy(ResidenceBuyEvent e) {
        String eventKey = "transaction-residence-buy";
        if (!plugin.getConfigManager().eventEnabled.getOrDefault(eventKey, true)) return;

        Player buyer = e.getPlayer();
        UUID uuid = buyer.getUniqueId();
        TransactionManager tm = plugin.getTransactionManager();
        tm.shopInProgress.add(uuid);

        String residenceName = e.getResidence().getName();
        double price = e.getPrice();

        double balBefore = plugin.getEconomy().getBalance(buyer);
        double balAfter = balBefore - price;

        tm.addTransaction(uuid, new Transaction(
                Type.EXPENSE, eventKey, price, balBefore, balAfter,
                residenceName
        ).withSource("Residence"));

        plugin.getFoliaLib().getImpl().runLaterAsync(
                task -> tm.shopInProgress.remove(uuid), 3 * 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onResidenceSell(ResidenceSellEvent e) {
        String eventKey = "transaction-residence-sell";
        if (!plugin.getConfigManager().eventEnabled.getOrDefault(eventKey, true)) return;

        Player seller = e.getPlayer();
        UUID uuid = seller.getUniqueId();
        TransactionManager tm = plugin.getTransactionManager();
        tm.shopInProgress.add(uuid);

        String residenceName = e.getResidence().getName();
        double price = e.getPrice();

        double balBefore = plugin.getEconomy().getBalance(seller);
        double balAfter = balBefore + price;

        tm.addTransaction(uuid, new Transaction(
                Type.INCOME, eventKey, price, balBefore, balAfter,
                residenceName
        ).withSource("Residence"));

        plugin.getFoliaLib().getImpl().runLaterAsync(
                task -> tm.shopInProgress.remove(uuid), 3 * 20L);
    }
}
