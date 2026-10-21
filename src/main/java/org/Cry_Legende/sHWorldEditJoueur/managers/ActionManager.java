package org.Cry_Legende.sHWorldEditJoueur.managers;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.sk89q.worldedit.regions.Region;
import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.utils.Common;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Gère les actions en attente des joueurs (placement ou coupe).
 * Cette classe maintient un état temporaire pour chaque joueur ayant initié une commande
 * et gère l'expiration de ces actions.
 */
public class ActionManager {

    private final SHWorldEditJoueur plugin;
    // Utilise une Map pour associer l'UUID d'un joueur à son action en cours.
    private final Map<UUID, ActionData> pendingActions = new HashMap<>();

    public ActionManager(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
    }

    /**
     * Démarre une nouvelle action pour un joueur (place ou cut).
     *
     * @param player Le joueur qui commence l'action.
     * @param region La sélection WorldEdit au moment où la commande est lancée.
     * @param type   Le type d'action (PLACE ou CUT).
     * @param island L'île sur laquelle l'action a lieu.
     */
    public void startAction(Player player, Region region, ActionData.ActionType type, Island island) {
        // Annule toute action précédente pour ce joueur pour éviter les conflits.
        cancelAction(player, false);

        // Crée un nouvel objet pour stocker les données de l'action.
        ActionData data = new ActionData(player, region, type, island);
        long expirationDelay = plugin.getConfigManager().getCancelTime() * 20L; // Convertir les secondes en ticks

        // Planifie une tâche qui annulera automatiquement l'action après le délai défini dans la config.
        BukkitTask expirationTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            // On vérifie si l'action est toujours en attente avant de l'annuler.
            // Le joueur a peut-être déjà confirmé ou annulé manuellement.
            ActionData currentAction = pendingActions.get(player.getUniqueId());
            if (currentAction != null && currentAction == data) {
                cancelAction(player, true); // true pour notifier le joueur de l'expiration.
            }
        }, expirationDelay);

        // Stocke la tâche d'expiration dans l'objet ActionData pour pouvoir l'annuler plus tard.
        data.setExpirationTask(expirationTask);

        // Ajoute l'action à la liste des actions en attente.
        pendingActions.put(player.getUniqueId(), data);
    }

    /**
     * Annule l'action en cours pour un joueur.
     *
     * @param player Le joueur dont l'action doit être annulée.
     * @param notify true pour envoyer un message de confirmation d'annulation au joueur.
     */
    public void cancelAction(Player player, boolean notify) {
        ActionData data = pendingActions.remove(player.getUniqueId());
        if (data != null) {
            // Il est crucial d'annuler la tâche d'expiration pour éviter qu'elle ne s'exécute inutilement.
            data.cancelExpiration();
            if (notify) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("action-cancelled"));
            }
        }
    }

    /**
     * Récupère l'action en attente pour un joueur.
     *
     * @param player Le joueur.
     * @return L'objet ActionData contenant les informations de l'action, ou null si aucune action n'est en attente.
     */
    public ActionData getPendingAction(Player player) {
        return pendingActions.get(player.getUniqueId());
    }

    /**
     * Confirme et termine une action en attente, et retourne ses données.
     * Appelée lorsque le joueur a validé l'étape suivante (clic sur un item, ou commande de confirmation).
     *
     * @param player Le joueur qui confirme l'action.
     * @return L'objet ActionData de l'action qui vient d'être confirmée, ou null si aucune action n'était en attente.
     */
    public ActionData confirmAndGetAction(Player player) {
        ActionData data = pendingActions.remove(player.getUniqueId());
        if (data != null) {
            // L'action est terminée, on annule donc la tâche d'expiration.
            data.cancelExpiration();
        }
        return data;
    }

    /**
     * Vérifie si un joueur a une action en attente.
     *
     * @param player Le joueur à vérifier.
     * @return true si le joueur a une action en attente.
     */
    public boolean hasPendingAction(Player player) {
        return pendingActions.containsKey(player.getUniqueId());
    }
}