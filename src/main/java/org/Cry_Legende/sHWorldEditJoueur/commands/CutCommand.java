package org.Cry_Legende.sHWorldEditJoueur.commands;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.sk89q.worldedit.regions.Region;
import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.config.Group;
import org.Cry_Legende.sHWorldEditJoueur.managers.ActionData;
import org.Cry_Legende.sHWorldEditJoueur.utils.Common;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Gère la commande /cut et ses arguments (confirm, cancel).
 * Implémente également le TabCompleter pour les suggestions.
 */
public class CutCommand implements CommandExecutor, TabCompleter {

    private final SHWorldEditJoueur plugin;

    public CutCommand(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            Common.sendMessage(sender, plugin.getConfigManager().getMessage("player-only"));
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission(SHWorldEditJoueur.PERMISSION_USE)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-perm"));
            return true;
        }

        // --- GESTION DES SOUS-COMMANDES POUR UNE ACTION EXISTANTE ---
        if (args.length > 0) {
            ActionData action = plugin.getActionManager().getPendingAction(player);

            if (action != null && action.getType() == ActionData.ActionType.CUT) {
                if (args[0].equalsIgnoreCase(plugin.getConfigManager().getCancelWord())) {
                    plugin.getActionManager().cancelAction(player, true);
                    return true;
                } else if (args[0].equalsIgnoreCase(plugin.getConfigManager().getConfirmWord())) {
                    ActionData confirmedAction = plugin.getActionManager().confirmAndGetAction(player);
                    if (confirmedAction != null) {
                        plugin.getWorldEditManager().executeCut(player, confirmedAction.getRegion(), confirmedAction.getIsland());
                    }
                    return true;
                }
            } else if (args[0].equalsIgnoreCase(plugin.getConfigManager().getCancelWord()) || args[0].equalsIgnoreCase(plugin.getConfigManager().getConfirmWord())) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-action"));
                return true;
            }
        }

        // --- LOGIQUE POUR LANCER UNE NOUVELLE ACTION DE COUPE ---

        Region selection = plugin.getFaweHook().getSelection(player);
        if (selection == null) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("make-selection"));
            return true;
        }

        Group group = plugin.getConfigManager().getPlayerGroup(player);

        // Contrôles d'île avant le comptage : le parcours de la sélection reste borné à la taille d'une île.
        Island island = plugin.getSsbHook().getIsland(player);
        if (island == null) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-island"));
            return true;
        }

        if (!plugin.getSsbHook().isSelectionOnIsland(selection, island)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("outside-island"));
            return true;
        }

        if (!plugin.getSsbHook().hasPermission(player, island)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-island-perm"));
            return true;
        }

        // Comptage hors du thread principal (arrêt dès que la limite du groupe est dépassée), puis message de
        // confirmation et action en attente
        plugin.getWorldEditManager().requestCut(player, island, selection, group);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        Player player = (Player) sender;

        if (args.length == 1) {
            ActionData action = plugin.getActionManager().getPendingAction(player);
            if (action != null && action.getType() == ActionData.ActionType.CUT) {
                List<String> suggestions = Arrays.asList(
                    plugin.getConfigManager().getConfirmWord(),
                    plugin.getConfigManager().getCancelWord()
                );
                return StringUtil.copyPartialMatches(args[0], suggestions, new ArrayList<>());
            }
        }

        return Collections.emptyList();
    }
}
