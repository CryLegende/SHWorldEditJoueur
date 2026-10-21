package org.Cry_Legende.sHWorldEditJoueur.managers;

import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Gère la journalisation des actions du plugin dans la console et dans un fichier.
 */
public class LogManager {

    private final SHWorldEditJoueur plugin;
    private final File logFile;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public LogManager(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "logs.txt");

        // S'assure que le fichier existe au démarrage du plugin
        if (!logFile.exists()) {
            try {
                // Crée le dossier du plugin s'il n'existe pas
                plugin.getDataFolder().mkdirs();
                // Crée le fichier de logs
                logFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Impossible de créer le fichier de logs (logs.txt) !");
                e.printStackTrace();
            }
        }
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

    /**
     * Méthode de base pour enregistrer un message dans la console et le fichier de log.
     *
     * @param message Le message à enregistrer.
     */
    private void log(String message) {
        String timestamp = dateFormat.format(new Date());
        String formattedMessage = "[" + timestamp + "] " + message;

        // 1. Log dans la console du serveur
        plugin.getLogger().info(ChatColor.stripColor(formattedMessage));

        // 2. Log dans le fichier logs.txt
        logToFile(formattedMessage);
    }

    /**
     * Écrit une ligne de manière synchronisée dans le fichier logs.txt.
     *
     * @param message Le message complet avec timestamp à écrire.
     */
    private synchronized void logToFile(String message) {
        // L'utilisation de try-with-resources garantit que les ressources (FileWriter, PrintWriter)
        // sont automatiquement fermées, même en cas d'erreur.
        try (FileWriter fw = new FileWriter(logFile, true); // Le 'true' signifie qu'on ajoute à la fin du fichier (append)
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(message);
        } catch (IOException e) {
            plugin.getLogger().severe("Erreur critique lors de l'écriture dans le fichier de logs !");
            e.printStackTrace();
        }
    }
}
