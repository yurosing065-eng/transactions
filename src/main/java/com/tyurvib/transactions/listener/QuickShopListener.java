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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true) // LOWEST чтобы сработать первым
    public void onShopPurchase(ShopSuccessPurchaseEvent e) {
        QUser purchaser = e.getPurchaser();
        UUID purchaserUUID = purchaser.getUniqueIdIfRealPlayer().orElse(null);
        if (purchaserUUID == null) return;

        Shop shop = e.getShop();
        String itemName = shop.getItem().getType().name();
        int qty = e.getAmount();
        double total = e.getBalanceWithoutTax(); // полная сумма без налога

        QUser owner = shop.getOwner();
        String shopOwnerName = owner.getUsername();
        if (shopOwnerName == null) shopOwnerName = owner.getDisplay();

        TransactionManager tm = plugin.getTransactionManager();
        boolean playerIsSelling = shop.isBuying();

        String eventKey = playerIsSelling ? "transaction-quickshop-sell" : "transaction-quickshop-buy";
        if (!plugin.getConfigManager().eventEnabled.getOrDefault(eventKey, true)) return;

        Type type = playerIsSelling ? Type.INCOME : Type.EXPENSE;
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(purchaserUUID);
        final String finalOwnerName = shopOwnerName;

        // Блокируем ДО того как QuickShop вызовет Vault
        tm.shopInProgress.add(purchaserUUID);

        // Берём баланс ДО операции прямо сейчас
        double balBefore = plugin.getEconomy().getBalance(offlinePlayer);
        double balAfter = playerIsSelling ? balBefore + total : balBefore - total;

        Transaction t = new Transaction(
                type,
                eventKey,
                total,
                balBefore,
                balAfter,
                itemName,
                String.valueOf(qty),
                finalOwnerName
        ).withSource("QuickShop-Hikari");

        tm.addTransaction(purchaserUUID, t);

        // Снимаем блокировку через delay после того как Vault вызовы завершились
        plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                t2 -> tm.shopInProgress.remove(purchaserUUID),
                3, TimeUnit.SECONDS);
    }

}