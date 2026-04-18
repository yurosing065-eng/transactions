package com.tyurvib.transactions.listener;

import com.ghostchu.quickshop.api.event.economy.ShopSuccessPurchaseEvent;
import com.ghostchu.quickshop.api.obj.QUser;
import com.ghostchu.quickshop.api.shop.Shop;
import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.manager.TransactionManager;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

public class QuickShopListener implements Listener {

    private final Transactions plugin;

    public QuickShopListener(Transactions plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShopPurchase(ShopSuccessPurchaseEvent e) {
        UUID purchaserUUID = e.getPurchaser().getUniqueIdIfRealPlayer().orElse(null);
        if (purchaserUUID == null) return;

        TransactionManager tm = plugin.getTransactionManager();

        // Блокировка в самом начале — до любых Vault-вызовов
        tm.shopInProgress.add(purchaserUUID);

        Shop shop = e.getShop();
        boolean playerIsSelling = shop.isBuying();
        String eventKey = playerIsSelling ? "transaction-quickshop-sell" : "transaction-quickshop-buy";
        if (!plugin.getConfigManager().eventEnabled.getOrDefault(eventKey, true)) {
            tm.shopInProgress.remove(purchaserUUID);
            return;
        }

        String itemName = shop.getItem().getType().name();
        int qty = e.getAmount();
        double total = e.getBalanceWithoutTax();

        QUser owner = shop.getOwner();
        String ownerName = owner.getUsername();
        if (ownerName == null) ownerName = owner.getDisplay();

        Type type = playerIsSelling ? Type.INCOME : Type.EXPENSE;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(purchaserUUID);

        double balBefore = plugin.getEconomy().getBalance(offlinePlayer);
        double balAfter = playerIsSelling ? balBefore + total : balBefore - total;

        Transaction t = new Transaction(
                type, eventKey, total, balBefore, balAfter,
                itemName, String.valueOf(qty), ownerName
        ).withSource("QuickShop-Hikari");

        tm.addTransaction(purchaserUUID, t);

        plugin.getFoliaLib().getImpl().runLaterAsync(
                task -> tm.shopInProgress.remove(purchaserUUID), 3 * 20L);
    }
}