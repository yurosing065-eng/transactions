package com.tyurvib.transactions.listener;

import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.manager.TransactionManager;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;
import net.bestemor.villagermarket.event.interact.BuyShopItemsEvent;
import net.bestemor.villagermarket.event.interact.SellShopItemsEvent;
import net.bestemor.villagermarket.shop.PlayerShop;
import net.bestemor.villagermarket.shop.ShopItem;
import net.bestemor.villagermarket.shop.VillagerShop;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class VillagerMarketListener implements Listener {

    private final Transactions plugin;

    public VillagerMarketListener(Transactions plugin) {
        this.plugin = plugin;
    }

    // Игрок ПОКУПАЕТ из магазина → EXPENSE
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBuyShopItems(BuyShopItemsEvent e) {
        String eventKey = "transaction-villagermarket-buy";
        if (!plugin.getConfigManager().eventEnabled.getOrDefault(eventKey, true)) return;

        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        ShopItem shopItem = e.getShopItem();
        VillagerShop shop = e.getShop();
        int amount = e.getAmount();

        double total = shopItem.getSellPrice(amount, true).doubleValue();
        String itemName = shopItem.getItemName();
        String ownerName = shop instanceof PlayerShop ? ((PlayerShop) shop).getOwnerName() : "Admin";
        if (ownerName == null) ownerName = "Admin";

        TransactionManager tm = plugin.getTransactionManager();
        tm.ecoInProgress.add(uuid);

        double balBefore = plugin.getEconomy().getBalance(player);
        double balAfter = balBefore - total;

        Transaction t = new Transaction(
                Type.EXPENSE,
                eventKey,
                total,
                balBefore,
                balAfter,
                itemName,              // %p1% — предмет
                String.valueOf(amount),// %p2% — количество
                ownerName              // %p3% — владелец магазина
        ).withSource("VillagerMarket");

        tm.addTransaction(uuid, t);

        plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                task -> tm.ecoInProgress.remove(uuid),
                3, TimeUnit.SECONDS);
    }

    // Игрок ПРОДАЁТ в магазин → INCOME
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSellShopItems(SellShopItemsEvent e) {
        String eventKey = "transaction-villagermarket-sell";
        if (!plugin.getConfigManager().eventEnabled.getOrDefault(eventKey, true)) return;

        Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        ShopItem shopItem = e.getShopItem();
        VillagerShop shop = e.getShop();
        int amount = e.getAmount();

        double total = shopItem.getBuyPrice(amount, true).doubleValue();
        String itemName = shopItem.getItemName();
        String ownerName = shop instanceof PlayerShop ? ((PlayerShop) shop).getOwnerName() : "Admin";
        if (ownerName == null) ownerName = "Admin";

        TransactionManager tm = plugin.getTransactionManager();
        tm.ecoInProgress.add(uuid);

        double balBefore = plugin.getEconomy().getBalance(player);
        double balAfter = balBefore + total;

        Transaction t = new Transaction(
                Type.INCOME,
                eventKey,
                total,
                balBefore,
                balAfter,
                itemName,
                String.valueOf(amount),
                ownerName
        ).withSource("VillagerMarket");

        tm.addTransaction(uuid, t);

        plugin.getServer().getAsyncScheduler().runDelayed(plugin,
                task -> tm.ecoInProgress.remove(uuid),
                3, TimeUnit.SECONDS);
    }
}