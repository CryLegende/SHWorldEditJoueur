package org.Cry_Legende.sHWorldEditJoueur.hooks;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * Gère l'intégration avec le plugin FastAsyncWorldEdit (FAWE).
 * Permet de récupérer les sélections des joueurs.
 */
public class FAWEHook {

    private WorldEditPlugin fawe;

    /**
     * Tente de s'accrocher à l'API de FastAsyncWorldEdit.
     * Cette méthode doit être appelée au démarrage du plugin.
     *
     * @return true si le hook a réussi, sinon false.
     */
    public boolean setup() {
        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("FastAsyncWorldEdit");
        if (plugin instanceof WorldEditPlugin) {
            this.fawe = (WorldEditPlugin) plugin;
            Bukkit.getLogger().info("[SHWorldEditJoueur] Hook avec FastAsyncWorldEdit réussi !");
            return true;
        }
        Bukkit.getLogger().severe("[SHWorldEditJoueur] FastAsyncWorldEdit n'a pas été trouvé. Le plugin ne peut pas fonctionner sans.");
        return false;
    }

    /**
     * Récupère la sélection WorldEdit actuelle d'un joueur.
     *
     * @param player Le joueur dont on veut la sélection.
     * @return La région sélectionnée (un objet Region), ou null si la sélection est incomplète ou n'existe pas.
     */
    public Region getSelection(Player player) {
        if (fawe == null) {
            Bukkit.getLogger().warning("[SHWorldEditJoueur] Tentative d'accès à FAWE alors que le hook a échoué.");
            return null;
        }

        try {
            // Obtient la session locale du joueur depuis l'API de FAWE.
            // La session contient toutes les informations relatives à WorldEdit pour ce joueur.
            LocalSession session = fawe.getSession(player);
            if (session == null) {
                return null;
            }

            // Adapte le monde Bukkit du joueur en monde WorldEdit, car l'API WorldEdit
            // travaille avec ses propres objets de monde.
            World selectionWorld = BukkitAdapter.adapt(player.getWorld());

            // Récupère la région de sélection dans le monde approprié.
            return session.getSelection(selectionWorld);

        } catch (IncompleteRegionException e) {
            // Cette exception est normale et est levée si le joueur n'a défini qu'une seule position (pos1).
            // Dans ce cas, nous considérons qu'il n'y a pas de sélection valide et nous retournons null.
            return null;
        } catch (Exception e) {
            // Gère d'autres erreurs potentielles pour éviter que le plugin ne crash.
            Bukkit.getLogger().log(Level.SEVERE, "[SHWorldEditJoueur] Une erreur est survenue en récupérant la sélection FAWE pour " + player.getName(), e);
            return null;
        }
    }

    /**
     * Fournit l'instance de l'API de WorldEdit, utile pour des opérations plus avancées.
     *
     * @return L'instance du plugin WorldEdit, ou null si le hook a échoué.
     */
    public WorldEditPlugin getFaweInstance() {
        return fawe;
    }
}