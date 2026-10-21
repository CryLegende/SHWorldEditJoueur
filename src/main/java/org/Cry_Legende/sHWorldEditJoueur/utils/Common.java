package org.Cry_Legende.sHWorldEditJoueur.utils;

import org.bukkit.command.CommandSender;

/**
 * Classe utilitaire pour les fonctions communes et répétitives.
 */
public class Common {

    /**
     * Envoie un message formaté à un joueur ou à la console.
     * Cette méthode gère automatiquement les codes couleurs et les messages sur plusieurs lignes.
     *
     * @param sender  Le destinataire du message (peut être un Player ou la Console).
     * @param message Le message à envoyer.
     */
    public static void sendMessage(CommandSender sender, String message) {
        // Ne fait rien si le message est nul ou vide pour éviter les erreurs.
        if (message == null || message.isEmpty()) {
            return;
        }

        // Gère les messages multilignes définis avec un "\n" dans le ConfigManager.
        // Chaque ligne est envoyée séparément.
        for (String line : message.split("\n")) {
            sender.sendMessage(line);
        }
    }
}
