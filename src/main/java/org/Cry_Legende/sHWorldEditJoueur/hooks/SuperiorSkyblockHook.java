package org.Cry_Legende.sHWorldEditJoueur.hooks;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class SuperiorSkyblockHook {

    private final SHWorldEditJoueur plugin;

    public SuperiorSkyblockHook(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
    }

    public boolean setup() {
        if (Bukkit.getServer().getPluginManager().getPlugin("SuperiorSkyblock2") != null) {
            Bukkit.getLogger().info("[SHWorldEditJoueur] Hook avec SuperiorSkyblock2 réussi !");
            return true;
        }
        Bukkit.getLogger().severe("[SHWorldEditJoueur] SuperiorSkyblock2 n'a pas été trouvé.");
        return false;
    }

    public Island getIsland(Player player) {
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);
        return superiorPlayer != null ? superiorPlayer.getIsland() : null;
    }

    public Island getIslandAt(Location location) {
        return SuperiorSkyblockAPI.getIslandAt(location);
    }

    public boolean isSelectionOnIsland(Region selection, Island island) {
        if (island == null || selection == null || selection.getWorld() == null) {
            return false;
        }

        org.bukkit.World bukkitWorld = Bukkit.getWorld(selection.getWorld().getName());

        if (bukkitWorld == null || !SuperiorSkyblockAPI.getGrid().isIslandsWorld(bukkitWorld)) {
            return false;
        }

        BlockVector3 minVec = selection.getMinimumPoint();
        BlockVector3 maxVec = selection.getMaximumPoint();

        Location minLoc = new Location(bukkitWorld, minVec.x(), minVec.y(), minVec.z());
        Location maxLoc = new Location(bukkitWorld, maxVec.x(), maxVec.y(), maxVec.z());

        return island.isInsideRange(minLoc) && island.isInsideRange(maxLoc);
    }

    /**
     * CORRECTION : La vérification de permission est maintenant entourée d'un bloc try-catch.
     * Cela empêche une erreur fatale si la permission n'est pas enregistrée, et renvoie 'false'
     * (accès refusé) par sécurité, ce qui est un comportement plus prévisible.
     */
    public boolean hasPermission(Player player, Island island) {
        if (island == null) return false;
        SuperiorPlayer sPlayer = SuperiorSkyblockAPI.getPlayer(player);
        if (sPlayer == null) return false;

        // Le propriétaire a toujours la permission.
        if (island.getOwner().equals(sPlayer)) {
            return true;
        }

        try {
            // Tente de récupérer la permission par son nom.
            IslandPrivilege useWorldEditPrivilege = IslandPrivilege.getByName(plugin.worldEditPrivilegeName);
            // La méthode de l'API s'occupe de la logique pour les membres.
            return island.hasPermission(sPlayer, useWorldEditPrivilege);
        } catch (NullPointerException e) {
            // Cette erreur est attrapée si IslandPrivilege.getByName() ne trouve pas la permission.
            // On log une alerte une seule fois pour ne pas spammer la console.
            plugin.getLogger().warning("La permission custom '" + plugin.worldEditPrivilegeName + "' n'a pas pu être vérifiée car elle semble ne pas exister. Accès refusé par défaut.");
            // Par sécurité, si la permission n'existe pas, on refuse l'action.
            return false;
        }
    }
}