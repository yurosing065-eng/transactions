package com.tyurvib.transactions.command;
//не должно вызывать лагов
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
            plugin.getGuiManager().openGUI(p, 0, p.getUniqueId(), p.getName());
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("reload") || sub.equals("update")) {
            if (!p.isOp()) return noPerm(p);
            plugin.getConfigManager().loadConfigValues();
            p.sendMessage("§aConfig reloaded.");
            return true;
        }

        if (sub.equals("clean")) {
            if (!p.isOp()) return noPerm(p);
            plugin.getDatabaseManager().clearAllTransactionsAsync();
            p.sendMessage(plugin.getConfigManager().getTranslation("cleanup-executed"));
            return true;
        }

        if (sub.equals("range") && p.isOp()) {
            if (args.length != 2) { p.sendMessage("Usage: /tr range <days>"); return true; }
            try {
                int days = Integer.parseInt(args[1]);
                plugin.getConfig().set("max-display-transaction-range", days);
                plugin.saveConfig();
                plugin.getConfigManager().loadConfigValues();
                p.sendMessage("Range set to " + days);
            } catch (NumberFormatException e) { p.sendMessage("Invalid number"); }
            return true;
        }

        if (sub.equals("gmt")) {
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

        if (sub.equals("balance") && plugin.getConfigManager().allowBalanceToggle) {
            boolean state = args.length > 1 && args[1].equalsIgnoreCase("on");
            plugin.getTransactionManager().showBalance.put(p.getUniqueId(), state);
            plugin.getTransactionManager().markDirty(p.getUniqueId());
            p.sendMessage(state ? "Balance ON" : "Balance OFF");
            return true;
        }

        if (sub.equals("player") && p.hasPermission("transactions.view.others")) {
            if (args.length < 2) { p.sendMessage("Usage: /tr player <name>"); return true; }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (!target.hasPlayedBefore()) { p.sendMessage("Player not found."); return true; }

            if (args.length == 3 && args[2].equalsIgnoreCase("txt") && p.isOp()) {
                plugin.getTransactionManager().downloadTransactionsToTxt(p.getUniqueId(), target.getUniqueId(), target.getName());
                return true;
            }
            plugin.getGuiManager().openGUI(p, 0, target.getUniqueId(), target.getName());
            return true;
        }
        if (sub.equals("reload") || sub.equals("update")) {
            if (!p.isOp()) {
                p.sendMessage(plugin.getConfigManager().getTranslation("no-permissions"));
                return true;
            }

            if (sub.equals("update")) {
                plugin.getConfigManager().updateFiles();
            }

            plugin.getConfigManager().loadConfigValues();
            p.sendMessage("§a" + plugin.getConfigManager().getTranslation(sub.equals("update") ? "update-success" : "config-reloaded"));
            return true;
        }

        return true;
    }

    private boolean noPerm(Player p) {
        p.sendMessage(plugin.getConfigManager().getTranslation("no-permissions"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            String input = args[0].toLowerCase();
            List<String> subs = new ArrayList<>(Arrays.asList("gmt", "balance", "player"));
            if (sender.isOp()) subs.addAll(Arrays.asList("reload", "update", "clean", "range"));

            for (String s : subs) {
                if (s.startsWith(input)) completions.add(s);
            }
            return completions;
        }


        if (args.length == 2 && args[0].equalsIgnoreCase("player") && sender.hasPermission("transactions.view.others")) {
            String input = args[1].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }


        if (args.length == 2 && args[0].equalsIgnoreCase("balance")) {
            return Arrays.asList("on", "off").stream()
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
}