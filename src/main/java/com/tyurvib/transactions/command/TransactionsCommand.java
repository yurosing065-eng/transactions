package com.tyurvib.transactions.command;

import com.tyurvib.transactions.Transactions;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class TransactionsCommand implements CommandExecutor, TabExecutor {

    private final Transactions plugin;

    public TransactionsCommand(Transactions plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(plugin.getConfigManager().getTranslation("in-game-only"));
            return true;
        }

        if (args.length == 0) {
            if (!p.hasPermission("transactions.use")) return noPerm(p);
            plugin.getGuiManager().openTransactions(p, 0, p.getUniqueId(), p.getName());
            return true;
        }

        String sub = args[0].toLowerCase();

        switch (sub) {
            case "reload", "update" -> {
                if (!p.hasPermission("transactions.reload")) return noPerm(p);
                if (sub.equals("update")) plugin.getConfigManager().updateFiles();

                plugin.getConfigManager().loadConfigValues();
                p.sendMessage("§a" + plugin.getConfigManager().getTranslation(sub.equals("update") ? "update-success" : "config-reloaded"));
                return true;
            }

            case "clean" -> {
                if (!p.hasPermission("transactions.clean")) return noPerm(p);
                plugin.getDatabaseManager().clearAllTransactionsAsync();
                p.sendMessage(plugin.getConfigManager().getTranslation("cleanup-executed"));
                return true;
            }

            case "range" -> {
                if (!p.hasPermission("transactions.range")) return noPerm(p);
                if (args.length != 2) {
                    p.sendMessage("§cUsage: /tr range <days>");
                    return true;
                }
                try {
                    int days = Integer.parseInt(args[1]);
                    plugin.getConfig().set("max-display-transaction-range", days);
                    plugin.saveConfig();
                    plugin.getConfigManager().loadConfigValues();
                    p.sendMessage("§aRange set to " + days);
                } catch (NumberFormatException e) {
                    p.sendMessage("§cInvalid number");
                }
                return true;
            }

            case "gmt" -> {
                if (!p.hasPermission("transactions.gmt")) return noPerm(p);
                if (args.length != 2) {
                    p.sendMessage(plugin.getConfigManager().getTranslation("gmt-usage"));
                    return true;
                }
                try {
                    int gmt = Integer.parseInt(args[1]);
                    plugin.getTransactionManager().playerGmtOffset.put(p.getUniqueId(), gmt);
                    plugin.getTransactionManager().markDirty(p.getUniqueId());
                    p.sendMessage(plugin.getConfigManager().getTranslation("gmt-set", args[1]));
                } catch (NumberFormatException e) {
                    p.sendMessage(plugin.getConfigManager().getTranslation("invalid-gmt"));
                }
                return true;
            }

            case "balance" -> {
                if (!p.hasPermission("transactions.balance")) return noPerm(p);
                if (!plugin.getConfigManager().allowBalanceToggle) return true;

                boolean state = args.length > 1 && args[1].equalsIgnoreCase("on");
                plugin.getTransactionManager().showBalance.put(p.getUniqueId(), state);
                plugin.getTransactionManager().markDirty(p.getUniqueId());
                p.sendMessage(state ? "§aBalance ON" : "§cBalance OFF");
                return true;
            }

            case "player" -> {
                if (!p.hasPermission("transactions.view.others")) return noPerm(p);
                if (args.length < 2) {
                    p.sendMessage("§cUsage: /tr player <name> [txt]");
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                if (!target.hasPlayedBefore() && !target.isOnline()) {
                    p.sendMessage("§cPlayer not found.");
                    return true;
                }
                if (args.length == 3 && args[2].equalsIgnoreCase("txt")) {
                    if (!p.hasPermission("transactions.download")) return noPerm(p);
                    plugin.getTransactionManager().downloadTransactionsToTxt(p.getUniqueId(), target.getUniqueId(), target.getName());
                    return true;
                }

                plugin.getGuiManager().openTransactions(p, 0, target.getUniqueId(), target.getName());
                return true;
            }
        }

        return true;
    }

    private boolean noPerm(Player p) {
        p.sendMessage(plugin.getConfigManager().getTranslation("no-permissions"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) return Collections.emptyList();

        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();

            if (p.hasPermission("transactions.gmt")) completions.add("gmt");
            if (p.hasPermission("transactions.balance")) completions.add("balance");
            if (p.hasPermission("transactions.view.others")) completions.add("player");
            if (p.hasPermission("transactions.reload")) {
                completions.add("reload");
                completions.add("update");
            }
            if (p.hasPermission("transactions.clean")) completions.add("clean");
            if (p.hasPermission("transactions.range")) completions.add("range");

            return completions.stream()
                    .filter(s -> s.startsWith(input))
                    .collect(Collectors.toList());
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("player") && p.hasPermission("transactions.view.others")) {
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (sub.equals("balance") && p.hasPermission("transactions.balance")) {
                return Arrays.asList("on", "off").stream()
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("player")) {
            if (p.hasPermission("transactions.download")) {
                if ("txt".startsWith(args[2].toLowerCase())) return List.of("txt");
            }
        }

        return Collections.emptyList();
    }
}