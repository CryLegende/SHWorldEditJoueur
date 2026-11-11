package org.Cry_Legende.sHWorldEditJoueur.commands;

import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.config.ConfigManager;
import org.Cry_Legende.sHWorldEditJoueur.config.Group;
import org.Cry_Legende.sHWorldEditJoueur.config.ReloadException;
import org.Cry_Legende.sHWorldEditJoueur.managers.ActionData;
import org.Cry_Legende.sHWorldEditJoueur.utils.Common;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /shworldeditjoueur : administration du plugin (reload, info), console comprise.
 */
public class AdminCommand implements TabExecutor {

    private static final String PERMISSION_PREFIX = "shworldeditjoueur.admin.";
    private static final List<String> SUBCOMMANDS = List.of("reload", "info");

    private final SHWorldEditJoueur plugin;

    public AdminCommand(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ConfigManager config = plugin.getConfigManager();
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            Common.sendMessage(sender, config.getMessage("no-perm"));
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            Common.sendMessage(sender, config.getMessage("usage-line").replace("%commands%", String.join("|", allowed)));
        } else if (!allowed.contains(sub)) {
            Common.sendMessage(sender, config.getMessage("no-perm"));
        } else if (sub.equals("reload")) {
            reload(sender);
        } else {
            info(sender, args.length > 1 ? args[1] : null);
        }
        return true;
    }

    /**
     * Relit config.yml et messages.yml. Un fichier illisible laisse tout en l'état : l'erreur (fichier, ligne,
     * problème) est donnée à l'expéditeur et à la console. Les actions en attente ne sont pas touchées.
     */
    private void reload(CommandSender sender) {
        try {
            ReloadException.read(new File(plugin.getDataFolder(), "config.yml"));
            ReloadException.read(new File(plugin.getDataFolder(), "messages.yml"));
        } catch (ReloadException e) {
            plugin.getLogger().warning("Rechargement refusé, ancienne configuration conservée : " + e.getMessage());
            Common.sendMessage(sender, plugin.getConfigManager().getMessage("reload-failed")
                .replace("%file%", e.file()).replace("%line%", e.line()).replace("%error%", e.problem()));
            return;
        }
        plugin.getConfigManager().loadConfig();
        Common.sendMessage(sender, plugin.getConfigManager().getMessage("reload"));
    }

    /** Palier que le plugin applique à un joueur : groupe, limite de blocs, prix, action en attente. */
    private void info(CommandSender sender, String name) {
        ConfigManager config = plugin.getConfigManager();
        Player target;
        if (name != null) {
            target = Bukkit.getPlayerExact(name);
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            Common.sendMessage(sender, config.getMessage("usage-info"));
            return;
        }
        if (target == null) {
            Common.sendMessage(sender, config.getMessage("player-not-found").replace("%player%", name));
            return;
        }
        Group group = config.getPlayerGroup(target);
        ActionData action = plugin.getActionManager().getPendingAction(target);
        String pending = config.getMessage(action == null ? "info-pending-none"
            : action.getType() == ActionData.ActionType.PLACE ? "info-pending-place" : "info-pending-cut");
        Common.sendMessage(sender, config.getMessage("info")
            .replace("%player%", target.getName())
            .replace("%group%", group.getName())
            .replace("%max%", String.valueOf(group.getMaxBlocks()))
            .replace("%place_price%", plugin.getVaultHook().format(group.getPlacePrice()))
            .replace("%cut_price%", plugin.getVaultHook().format(group.getCutPrice()))
            .replace("%pending%", pending));
    }

    /** Sous-commandes que l'expéditeur a le droit d'utiliser. */
    private static List<String> allowed(CommandSender sender) {
        List<String> allowed = new ArrayList<>();
        for (String sub : SUBCOMMANDS) {
            if (sender.hasPermission(PERMISSION_PREFIX + sub)) {
                allowed.add(sub);
            }
        }
        return allowed;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        List<String> allowed = allowed(sender);
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            allowed.stream().filter(sub -> sub.startsWith(typed)).forEach(out::add);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("info") && allowed.contains("info")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    out.add(online.getName());
                }
            }
        }
        return out;
    }
}
