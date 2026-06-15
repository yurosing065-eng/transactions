package com.tyurvib.transactions;

import com.tcoded.folialib.FoliaLib;
import com.tyurvib.transactions.command.TransactionsCommand;
import com.tyurvib.transactions.listener.QuickShopListener;
import com.tyurvib.transactions.listener.TransactionListener;
import com.tyurvib.transactions.listener.VillagerMarketListener;
import com.tyurvib.transactions.listener.ZAuctionHouseListener;
import com.tyurvib.transactions.manager.ConfigManager;
import com.tyurvib.transactions.manager.DatabaseManager;
import com.tyurvib.transactions.manager.GuiManager;
import com.tyurvib.transactions.manager.TransactionManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class Transactions extends JavaPlugin {

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private TransactionManager transactionManager;
    private FoliaLib foliaLib;
    private GuiManager guiManager;
    private Economy economy;
    private Updater updater;

    @Override
    public void onEnable() {
        this.foliaLib = new FoliaLib(this);
        if (!setupEconomy()) {
            getLogger().severe("Vault or Economy plugin not found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        this.configManager = new ConfigManager(this);
        this.databaseManager = new DatabaseManager(this);
        this.transactionManager = new TransactionManager(this);
        this.guiManager = new GuiManager(this);
        this.updater = new Updater(this, "GbElTVjA");
        getCommand("transactions").setExecutor(new TransactionsCommand(this));
        getServer().getPluginManager().registerEvents(new TransactionListener(this), this);
        // Подключение QuickShop Hikari если присутствует
        if (getServer().getPluginManager().getPlugin("QuickShop-Hikari") != null) {
            getServer().getPluginManager().registerEvents(new QuickShopListener(this), this);
            getLogger().info("QuickShop-Hikari найден, логирование покупок включено.");
        }
        if (getServer().getPluginManager().getPlugin("VillagerMarket") != null) {
            getServer().getPluginManager().registerEvents(new VillagerMarketListener(this), this);
            getLogger().info("VillagerMarket интеграция включена.");
        }
        if (getServer().getPluginManager().getPlugin("zAuctionHouse") != null) {
            getServer().getPluginManager().registerEvents(new ZAuctionHouseListener(this), this);
            getLogger().info("zAuctionHouse интеграция включена.");
        }
        // Residence — раскомментируй после: mvn install:install-file -Dfile=lib/Residence.jar ...
        // if (getServer().getPluginManager().getPlugin("Residence") != null) {
        //     getServer().getPluginManager().registerEvents(new com.tyurvib.transactions.listener.ResidenceListener(this), this);
        //     getLogger().info("Residence интеграция включена.");
        // }
    }
    public FoliaLib getFoliaLib() { return foliaLib; }
    @Override
    public void onDisable() {
        if (transactionManager != null) {
            for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                transactionManager.markDirty(p.getUniqueId());
            }
            transactionManager.saveDirtyPlayersSync();
        }
        if (databaseManager != null) databaseManager.close();
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public ConfigManager getConfigManager() { return configManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public TransactionManager getTransactionManager() { return transactionManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public Economy getEconomy() { return economy; }
    public Updater getUpdater() { return updater; }
}