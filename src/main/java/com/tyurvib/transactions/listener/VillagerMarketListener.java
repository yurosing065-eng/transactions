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

public class VillagerMarketListener implements Listener {

    private final Transactions plugin;

    public VillagerMarketListener(Transactions plugin) {
        this.plugin = plugin;
    }

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

        double balBefore = plugin.getEconomy().getBalance(player);
        double balAfter = balBefore - total;

        Transaction t = new Transaction(
                Type.EXPENSE, eventKey, total, balBefore, balAfter,
                itemName, String.valueOf(amount), ownerName
        ).withSource("VillagerMarket");

        tm.addTransaction(uuid, t);

        plugin.getFoliaLib().getImpl().runLaterAsync(
                task -> tm.ecoInProgress.remove(uuid), 3 * 20L);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBuyEarly(BuyShopItemsEvent e) {
        plugin.getTransactionManager().ecoInProgress.add(e.getPlayer().getUniqueId());
    }
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

        double balBefore = plugin.getEconomy().getBalance(player);
        double balAfter = balBefore + total;

        Transaction t = new Transaction(
                Type.INCOME, eventKey, total, balBefore, balAfter,
                itemName, String.valueOf(amount), ownerName
        ).withSource("VillagerMarket");

        tm.addTransaction(uuid, t);

        plugin.getFoliaLib().getImpl().runLaterAsync(
                task -> tm.ecoInProgress.remove(uuid), 3 * 20L);
    }
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSellEarly(SellShopItemsEvent e) {
        plugin.getTransactionManager().ecoInProgress.add(e.getPlayer().getUniqueId());
    }

}