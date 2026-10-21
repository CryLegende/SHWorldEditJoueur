package org.Cry_Legende.sHWorldEditJoueur.listeners;

import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.managers.ActionData;
import org.Cry_Legende.sHWorldEditJoueur.utils.Common;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Écoute les interactions des joueurs avec leur inventaire.
 * Gère spécifiquement la sélection de bloc pour la commande /place.
 */
public class InventoryListener implements Listener {

    private final SHWorldEditJoueur plugin;

    public InventoryListener(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInventoryClick(InventoryClickEvent event) {
        // 1. S'assurer que le cliqueur est un joueur
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();

        // 2. Vérifier si le joueur a une action en attente
        ActionData action = plugin.getActionManager().getPendingAction(player);
        if (action == null) {
            return;
        }

        // 3. S'assurer que l'action est bien une action de PLACEMENT
        if (action.getType() != ActionData.ActionType.PLACE) {
            return;
        }

        // 4. S'assurer que le clic a lieu dans l'inventaire du joueur (et non un autre GUI)
        // et qu'un item a bien été cliqué.
        if (event.getClickedInventory() != player.getInventory() || event.getCurrentItem() == null) {
            return;
        }

        // Empêche le joueur de bouger l'item dans son inventaire. Le clic est juste une "information".
        event.setCancelled(true);

        ItemStack clickedItem = event.getCurrentItem();

        // 5. Vérifier que le joueur a cliqué sur un item qui est un bloc placable.
        if (!clickedItem.getType().isBlock()) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("invalid-block"));
            return;
        }

        // L'action est confirmée, on retire le joueur de la liste d'attente.
        // On récupère les données de l'action en même temps.
        ActionData confirmedAction = plugin.getActionManager().confirmAndGetAction(player);

        // Il est possible que l'action ait expiré entre le get et la confirmation, donc on vérifie.
        if (confirmedAction == null) {
            return;
        }

        // On ferme l'inventaire pour une meilleure expérience utilisateur.
        player.closeInventory();

        // On délègue l'opération de placement au manager WorldEdit en lui passant toutes les infos nécessaires.
        plugin.getWorldEditManager().executePlace(player, confirmedAction.getRegion(), clickedItem, confirmedAction.getIsland());
    }
}