package org.Cry_Legende.sHWorldEditJoueur.commands;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.mask.Masks;
import com.sk89q.worldedit.function.mask.BlockTypeMask;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.config.Group;
import org.Cry_Legende.sHWorldEditJoueur.managers.ActionData;
import org.Cry_Legende.sHWorldEditJoueur.utils.Common;
import org.bukkit.ChatColor;
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

        if (!player.hasPermission("shwe.use")) {
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

        // Contrôles d'île avant le comptage : le parcours de la sélection se fait sur le thread principal,
        // il doit rester borné à la taille d'une île.
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

        // CORRECTION : On compte les blocs réels (non-air) avant de vérifier la limite.
        int nonAirBlocks;
        try (EditSession countSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(player.getWorld()))) {
            // On crée un masque qui ne comptera QUE les blocs qui ne sont PAS de l'air.
            Mask mask = Masks.negate(new BlockTypeMask(countSession, BlockTypes.AIR, BlockTypes.CAVE_AIR, BlockTypes.VOID_AIR));
            nonAirBlocks = countSession.countBlocks(selection, mask);
        } catch (WorldEditException e) {
            player.sendMessage(ChatColor.RED + "Erreur lors du comptage des blocs : " + e.getMessage());
            return true;
        }

        if (nonAirBlocks > group.getMaxBlocks()) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("selection-too-large")
                .replace("%max%", String.valueOf(group.getMaxBlocks()))
                .replace("%size%", String.valueOf(nonAirBlocks))); // On utilise le nombre de blocs réels
            return true;
        }

        plugin.getActionManager().startAction(player, selection, ActionData.ActionType.CUT, island);

        double estimatedCost = nonAirBlocks * group.getCutPrice(); // Le coût est basé sur les blocs réels
        String confirmMessage = plugin.getConfigManager().getMessage("confirm-cut")
            .replace("%amount%", String.valueOf(nonAirBlocks)) // On affiche le nombre de blocs réels
            .replace("%price%", plugin.getVaultHook().format(group.getCutPrice()))
            .replace("%total_price%", plugin.getVaultHook().format(estimatedCost))
            .replace("%time%", String.valueOf(plugin.getConfigManager().getCancelTime()));

        player.sendMessage(confirmMessage.split("\n"));

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
