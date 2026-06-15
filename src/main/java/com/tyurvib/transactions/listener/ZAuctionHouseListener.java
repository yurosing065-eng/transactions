package com.tyurvib.transactions.listener;

import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.manager.TransactionManager;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;
import fr.maxlego08.zauctionhouse.api.event.events.purchase.AuctionPrePurchaseItemEvent;
import fr.maxlego08.zauctionhouse.api.item.Item;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

public class ZAuctionHouseListener implements Listener {

    private final Transactions plugin;

    public ZAuctionHouseListener(Transactions plugin) {
        this.plugin = plugin;
    }

    /**
     * Покупатель покупает лот — списываем у покупателя, зачисляем продавцу.
     * MONITOR + ignoreCancelled: деньги уже переведены к этому моменту.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuctionPurchase(AuctionPrePurchaseItemEvent e) {
        Player buyer = e.getPlayer();
        Item item = e.getItem();

        UUID buyerUUID = buyer.getUniqueId();
        UUID sellerUUID = item.getSellerUniqueId();
        String sellerName = item.getSellerName() != null ? item.getSellerName() : "Unknown";
        double price = item.getPrice().doubleValue();

        TransactionManager tm = plugin.getTransactionManager();
        tm.shopInProgress.add(buyerUUID);
        if (sellerUUID != null) tm.shopInProgress.add(sellerUUID);

        // Покупатель — EXPENSE
        String buyKey = "transaction-zauctionhouse-buy";
        if (plugin.getConfigManager().eventEnabled.getOrDefault(buyKey, true)) {
            double buyerBal = plugin.getEconomy().getBalance(buyer);
            tm.addTransaction(buyerUUID, new Transaction(
                    Type.EXPENSE, buyKey, price, buyerBal, buyerBal - price,
                    sellerName
            ).withSource("zAuctionHouse"));
        }

        // Продавец — INCOME (async, т.к. getOfflinePlayer + getBalance могут читать диск)
        String sellKey = "transaction-zauctionhouse-sell";
        final String buyerName = buyer.getName();
        if (sellerUUID != null && plugin.getConfigManager().eventEnabled.getOrDefault(sellKey, true)) {
            final UUID fSellerUUID = sellerUUID;
            plugin.getFoliaLib().getImpl().runAsync(task -> {
                OfflinePlayer seller = Bukkit.getOfflinePlayer(fSellerUUID);
                double sellerBal = plugin.getEconomy().getBalance(seller);
                tm.addTransaction(fSellerUUID, new Transaction(
                        Type.INCOME, sellKey, price, sellerBal, sellerBal + price,
                        buyerName
                ).withSource("zAuctionHouse"));
                tm.shopInProgress.remove(fSellerUUID);
            });
        } else if (sellerUUID != null) {
            plugin.getFoliaLib().getImpl().runLaterAsync(task -> tm.shopInProgress.remove(sellerUUID), 3 * 20L);
        }

        plugin.getFoliaLib().getImpl().runLaterAsync(task -> tm.shopInProgress.remove(buyerUUID), 3 * 20L);
    }
}
