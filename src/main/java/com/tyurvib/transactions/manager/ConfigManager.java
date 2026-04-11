package com.tyurvib.transactions.manager;

import com.tyurvib.transactions.Transactions;
import com.tyurvib.transactions.model.Transaction;
import com.tyurvib.transactions.model.Type;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigManager {

    private final Transactions plugin;
    private Map<String, String> translations;
    private DecimalFormat amountFormatter;

    public String prefix;
    public boolean showStatsButton;
    public boolean allowBalanceToggle;
    public String incomeMessageColor;
    public String incomeAmountColor;
    public String expenseMessageColor;
    public String expenseAmountColor;
    public String yellowMessageColor;
    public String yellowAmountColor;
    public long cleanTransactionsPeriodMs;
    public int defaultGmtOffset;
    public boolean logExternalTransactions;
    public int maxDisplayTransactionRange = 90;
    public Map<String, Boolean> eventEnabled = new HashMap<>();

    public ConfigManager(Transactions plugin) {
        this.plugin = plugin;
        // Сначала создаем папку и копируем файлы
        setupLanguageFiles();
        this.amountFormatter = createDefaultFormatter();
        loadConfigValues();
    }
    public void loadConfigValues() {
        plugin.reloadConfig();
        prefix = plugin.getConfig().getString("prefix", "💰");
        showStatsButton = plugin.getConfig().getBoolean("show-stats-button", true);
        allowBalanceToggle = plugin.getConfig().getBoolean("allow-balance-toggle", true);
        incomeMessageColor = plugin.getConfig().getString("income-message-color", "§f");
        incomeAmountColor = plugin.getConfig().getString("income-amount-color", "§a");
        expenseMessageColor = plugin.getConfig().getString("expense-message-color", "§f");
        expenseAmountColor = plugin.getConfig().getString("expense-amount-color", "§c");
        yellowMessageColor = plugin.getConfig().getString("yellow-message-color", "§f");
        yellowAmountColor = plugin.getConfig().getString("yellow-amount-color", "§e");
        cleanTransactionsPeriodMs = parseTimePeriod(plugin.getConfig().getString("clean-transactions-period", "14d"));
        defaultGmtOffset = plugin.getConfig().getInt("default-gmt-offset", 0);
        logExternalTransactions = plugin.getConfig().getBoolean("log-external-transactions", true);
        maxDisplayTransactionRange = plugin.getConfig().getInt("max-display-transaction-range", 90);

        String language = plugin.getConfig().getString("language", "en");
        File langFile = new File(plugin.getDataFolder() + "/lang", language + ".yml");
        if (!langFile.exists()) {
            langFile = new File(plugin.getDataFolder() + "/lang", "en.yml");
        }
        YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
        translations = new HashMap<>();
        for (String key : langConfig.getKeys(false)) {
            translations.put(key, langConfig.getString(key));
        }
        eventEnabled.clear();
        org.bukkit.configuration.ConfigurationSection eventsSection = plugin.getConfig().getConfigurationSection("events");
        if (eventsSection != null) {
            for (String eventKey : eventsSection.getKeys(false)) {
                eventEnabled.put(eventKey, eventsSection.getBoolean(eventKey + ".enabled", true));
            }
        }
    }
    private void setupLanguageFiles() {
        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }

        String[] defaultLangs = {"en.yml", "ru.yml"};

        for (String fileName : defaultLangs) {
            File file = new File(langFolder, fileName);
            if (!file.exists()) {
                plugin.saveResource("lang/" + fileName, false);
            }
        }
    }
    public String getTranslation(String key, String... placeholders) {
        String translated = translations.getOrDefault(key, key);
        for (int i = 0; i < placeholders.length; i++) {
            translated = translated.replace("%p" + (i + 1) + "%", placeholders[i]);
        }
        return translated;
    }

    public String getTranslatedDescription(Transaction t) {
        String translated = translations.getOrDefault(t.key, t.key);
        for (int i = 0; i < t.params.length; i++) {
            translated = translated.replace("%p" + (i + 1) + "%", t.params[i]);
        }
        String messageColor, amountColor;
        switch (t.type) {
            case INCOME:
                messageColor = incomeMessageColor;
                amountColor = incomeAmountColor;
                break;
            case EXPENSE:
                messageColor = expenseMessageColor;
                amountColor = expenseAmountColor;
                break;
            case YELLOW:
                messageColor = yellowMessageColor;
                amountColor = yellowAmountColor;
                break;
            default:
                messageColor = "§f";
                amountColor = "§a";
        }
        String formattedAmount = "(" + (t.type == Type.INCOME ? "+" : t.type == Type.EXPENSE ? "-" : "") + amountFormatter.format(t.amount) + " " + prefix + ")";
        return messageColor + translated + " " + amountColor + formattedAmount;
    }

    public String getTransactionColor(Type type) {
        switch (type) {
            case INCOME: return incomeMessageColor;
            case EXPENSE: return expenseMessageColor;
            case YELLOW: return yellowMessageColor;
            default: return "§f";
        }
    }

    public DecimalFormat getAmountFormatter() {
        return amountFormatter;
    }

    private DecimalFormat createDefaultFormatter() {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator('.');
        DecimalFormat df = new DecimalFormat("#,###.##", symbols);
        df.setGroupingUsed(true);
        df.setMinimumFractionDigits(0);
        df.setMaximumFractionDigits(2);
        return df;
    }

    private long parseTimePeriod(String period) {
        try {
            Pattern pattern = Pattern.compile("^(\\d+)([smhdw]|mo|y)$");
            Matcher matcher = pattern.matcher(period.toLowerCase());
            if (!matcher.matches()) {
                return TimeUnit.DAYS.toMillis(14);
            }
            long value = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2);
            switch (unit) {
                case "s": return TimeUnit.SECONDS.toMillis(value);
                case "m": return TimeUnit.MINUTES.toMillis(value);
                case "h": return TimeUnit.HOURS.toMillis(value);
                case "d": return TimeUnit.DAYS.toMillis(value);
                case "w": return TimeUnit.DAYS.toMillis(value * 7);
                case "mo": return TimeUnit.DAYS.toMillis(value * 30);
                case "y": return TimeUnit.DAYS.toMillis(value * 365);
                default: return TimeUnit.DAYS.toMillis(14);
            }
        } catch (NumberFormatException e) {
            return TimeUnit.DAYS.toMillis(14);
        }
    }

    public void updateFiles() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        config.options().copyDefaults(true);
        try (InputStream defaultsStream = plugin.getResource("config.yml")) {
            if (defaultsStream != null) {
                YamlConfiguration defaultsConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultsStream));
                config.setDefaults(defaultsConfig);
                config.save(configFile);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        File langFolder = new File(plugin.getDataFolder(), "lang");
        if (langFolder.exists()) {
            File[] langFiles = langFolder.listFiles((dir, name) -> name.endsWith(".yml"));
            if (langFiles != null) {
                for (File langFile : langFiles) {
                    YamlConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
                    try (InputStream defaultsStream = plugin.getResource("lang/" + langFile.getName())) {
                        if (defaultsStream != null) {
                            YamlConfiguration defaultsLangConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultsStream));
                            langConfig.setDefaults(defaultsLangConfig);
                            langConfig.options().copyDefaults(true);
                            langConfig.save(langFile);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }
}