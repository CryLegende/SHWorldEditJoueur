package org.Cry_Legende.sHWorldEditJoueur.commands;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
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

import java.util.*;

/**
 * Gère la commande /place et son argument (cancel).
 */
public class PlaceCommand implements CommandExecutor, TabCompleter {

    private final SHWorldEditJoueur plugin;

    public PlaceCommand(SHWorldEditJoueur plugin) {
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

        // Gestion de la sous-commande /place cancel
        if (args.length > 0 && args[0].equalsIgnoreCase(plugin.getConfigManager().getCancelWord())) {
            ActionData action = plugin.getActionManager().getPendingAction(player);
            if (action != null && action.getType() == ActionData.ActionType.PLACE) {
                plugin.getActionManager().cancelAction(player, true);
            } else {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-action"));
            }
            return true;
        }

        // --- LOGIQUE POUR LANCER UNE NOUVELLE ACTION DE PLACEMENT ---

        // 1. Vérifier la sélection WorldEdit
        Region selection = plugin.getFaweHook().getSelection(player);
        if (selection == null) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("make-selection"));
            return true;
        }

        // 2. Récupérer le groupe du joueur pour les infos de prix
        Group group = plugin.getConfigManager().getPlayerGroup(player);

        // CORRECTION BUG 2 : La vérification stricte de la taille est retirée ici.
        // La logique de limitation est maintenant gérée dans WorldEditManager.

        // 3. Vérifications liées à SuperiorSkyblock
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

        // 4. Compter les blocs plaçables pour le message de confirmation.
        // Le comptage s'arrête à la limite du groupe : au-delà, le nombre exact
        // n'a aucun effet puisque WorldEditManager plafonne de toute façon la pose.
        int cap = group.getMaxBlocks() > 0 ? group.getMaxBlocks() : Integer.MAX_VALUE;
        int placeableCount = plugin.getWorldEditManager()
            .countPlaceable(BukkitAdapter.adapt(player.getWorld()), selection, cap);

        if (placeableCount == 0) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-placeable-blocks-in-selection"));
            return true;
        }

        // 5. Lancer l'action de placement et mettre le joueur en attente
        plugin.getActionManager().startAction(player, selection, ActionData.ActionType.PLACE, island);

        // 6. Envoyer le message de confirmation avec le bon nombre de blocs
        String confirmMessage = plugin.getConfigManager().getMessage("confirm-place")
            .replace("%amount%", String.valueOf(placeableCount))
            .replace("%price%", plugin.getVaultHook().format(group.getPlacePrice()))
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

        // On ne suggère que pour le premier argument
        if (args.length == 1) {
            // On suggère "cancel" uniquement si le joueur a une action de placement en attente
            ActionData action = plugin.getActionManager().getPendingAction(player);
            if (action != null && action.getType() == ActionData.ActionType.PLACE) {
                List<String> suggestions = Collections.singletonList(plugin.getConfigManager().getCancelWord());
                return StringUtil.copyPartialMatches(args[0], suggestions, new ArrayList<>());
            }
        }

        // Ne retourne aucune suggestion dans les autres cas
        return Collections.emptyList();
    }
}
