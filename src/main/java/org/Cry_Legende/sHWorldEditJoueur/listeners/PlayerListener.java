package org.Cry_Legende.sHWorldEditJoueur.listeners;

import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Écoute les événements liés aux joueurs, comme la déconnexion.
 */
public class PlayerListener implements Listener {

    private final SHWorldEditJoueur plugin;

    public PlayerListener(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
    }

    /**
     * Se déclenche lorsqu'un joueur quitte le serveur.
     *
     * @param event L'événement de déconnexion.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Si le joueur qui se déconnecte avait une action en attente (/place ou /cut),
        // on l'annule proprement pour éviter de le garder en mémoire.
        if (plugin.getActionManager().hasPendingAction(player)) {
            // Le 'false' indique de ne pas envoyer de message au joueur, car il est déjà déconnecté.
            plugin.getActionManager().cancelAction(player, false);
        }
    }
}