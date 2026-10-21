package org.Cry_Legende.sHWorldEditJoueur.managers;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.sk89q.worldedit.regions.Region;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Représente les données d'une action en attente (place ou cut) initiée par un joueur.
 * Cet objet est temporaire et est détruit une fois l'action complétée, annulée ou expirée.
 */
public class ActionData {

    /**
     * Énumération pour distinguer les types d'actions possibles.
     */
    public enum ActionType {
        PLACE,
        CUT
    }

    private final Player player;
    private final Region region;
    private final ActionType type;
    private final Island island;
    private BukkitTask expirationTask;

    /**
     * Construit une nouvelle instance de données d'action.
     *
     * @param player Le joueur qui a initié l'action.
     * @param region La sélection WorldEdit du joueur au moment de la commande.
     * @param type   Le type d'action (PLACE ou CUT).
     * @param island L'île sur laquelle l'action est effectuée.
     */
    public ActionData(Player player, Region region, ActionType type, Island island) {
        this.player = player;
        this.region = region;
        this.type = type;
        this.island = island;
    }

    /**
     * @return Le joueur associé à cette action.
     */
    public Player getPlayer() {
        return player;
    }

    /**
     * @return La région de la sélection WorldEdit.
     */
    public Region getRegion() {
        return region;
    }

    /**
     * @return Le type de l'action en cours.
     */
    public ActionType getType() {
        return type;
    }

    /**
     * @return L'île concernée par l'action.
     */
    public Island getIsland() {
        return island;
    }

    /**
     * Stocke la tâche responsable de l'annulation automatique de cette action après un certain temps.
     * Si une tâche d'expiration existait déjà, elle est annulée et remplacée.
     *
     * @param expirationTask La tâche planifiée par le BukkitScheduler.
     */
    public void setExpirationTask(BukkitTask expirationTask) {
        if (this.expirationTask != null && !this.expirationTask.isCancelled()) {
            this.expirationTask.cancel();
        }
        this.expirationTask = expirationTask;
    }

    /**
     * Annule la tâche d'expiration.
     * Doit être appelée lorsque l'action est confirmée ou annulée manuellement
     * pour empêcher l'exécution de l'annulation automatique.
     */
    public void cancelExpiration() {
        if (this.expirationTask != null && !this.expirationTask.isCancelled()) {
            this.expirationTask.cancel();
            this.expirationTask = null;
        }
    }
}