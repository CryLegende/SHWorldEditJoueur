package org.Cry_Legende.sHWorldEditJoueur.managers;

import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.utils.ActionLog;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Écrit les actions du plugin dans le fichier « actions » de SHLogs.
 */
public class LogManager {

    private static final String LOG_FILE = "actions";

    private final SHWorldEditJoueur plugin;

    public LogManager(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
    }

    /**
     * Enregistre une action de placement (/place) dans les journaux.
     *
     * @param player Le joueur qui a effectué l'action.
     * @param amount La quantité de blocs placés.
     * @param type   Le type de bloc.
     * @param cost   Le coût total de l'opération.
     */
    public void logPlace(Player player, int amount, Material type, double cost) {
        String formattedCost = plugin.getVaultHook().format(cost);
        String message = String.format("%s a placé %d bloc(s) de %s pour %s.",
            player.getName(),
            amount,
            type.toString().toLowerCase(),
            formattedCost
        );
        log(message);
    }

    /**
     * Enregistre une action de coupe (/cut) dans les journaux avec la liste des items.
     *
     * @param player Le joueur qui a effectué l'action.
     * @param amount La quantité de blocs coupés.
     * @param cost   Le coût total de l'opération.
     * @param items  La liste formatée des items récupérés.
     */
    public void logCut(Player player, int amount, double cost, String items) {
        String formattedCost = plugin.getVaultHook().format(cost);
        String message = String.format("%s a coupé %d bloc(s) pour %s. Items récupérés: [%s]",
            player.getName(),
            amount,
            formattedCost,
            items
        );
        log(message);
    }

    private void log(String message) {
        ActionLog.write(plugin, LOG_FILE, message);
    }
}
