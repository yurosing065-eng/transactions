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
import java.util.concurrent.TimeUnit;

public class QuickShopListener implements Listener {

    private final Transactions plugin;

    public QuickShopListener(Transactions plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShopPurchase(ShopSuccessPurchaseEvent e) {
        QUser purchaser = e.getPurchaser();

        UUID purchaserUUID = purchaser.getUniqueIdIfRealPlayer().orElse(null);
        if (purchaserUUID == null) return;

        Shop shop = e.getShop();
        String itemName = shop.getItem().getType().name();
        int qty = e.getAmount();
        double total = e.getBalance();

        // Имя владельца магазина
        QUser owner = shop.getOwner();
        String shopOwnerName = owner.getUsername();
        if (shopOwnerName == null) shopOwnerName = owner.getDisplay();

        TransactionManager tm = plugin.getTransactionManager();

        // shop.isBuying() — магазин ПОКУПАЕТ → игрок ПРОДАЁТ → INCOME
        // shop.isSelling() — магазин ПРОДАЁТ → игрок ПОКУПАЕТ → EXPENSE
        boolean playerIsSelling = shop.isBuying();

        String eventKey = playerIsSelling
                ? "transaction-quickshop-sell"
                : "transaction-quickshop-buy";

        if (!plugin.getConfigManager().eventEnabled.getOrDefault(eventKey, true)) return;

        Type type = playerIsSelling ? Type.INCOME : Type.EXPENSE;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(purchaserUUID);
        final String finalOwnerName = shopOwnerName;

        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            double balBefore = plugin.getEconomy().getBalance(offlinePlayer);
            double balAfter = playerIsSelling ? balBefore + total : balBefore - total;

            tm.ecoInProgress.add(purchaserUUID);

            Transaction t = new Transaction(
                    type,
                    eventKey,
                    total,
                    balBefore,
                    balAfter,
                    itemName,             // %p1% — предмет
                    String.valueOf(qty),  // %p2% — количество
                    finalOwnerName        // %p3% — владелец магазина
            ).withSource("QuickShop-Hikari");

            tm.addTransaction(purchaserUUID, t);

            plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                    t2 -> tm.ecoInProgress.remove(purchaserUUID),
                    3, TimeUnit.SECONDS);
        });
    }
}