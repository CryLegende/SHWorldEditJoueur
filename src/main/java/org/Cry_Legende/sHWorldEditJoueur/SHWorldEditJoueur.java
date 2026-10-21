package org.Cry_Legende.sHWorldEditJoueur;

import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import org.Cry_Legende.sHWorldEditJoueur.commands.CutCommand;
import org.Cry_Legende.sHWorldEditJoueur.commands.PlaceCommand;
import org.Cry_Legende.sHWorldEditJoueur.config.ConfigManager;
import org.Cry_Legende.sHWorldEditJoueur.hooks.FAWEHook;
import org.Cry_Legende.sHWorldEditJoueur.hooks.SuperiorSkyblockHook;
import org.Cry_Legende.sHWorldEditJoueur.hooks.VaultHook;
import org.Cry_Legende.sHWorldEditJoueur.listeners.InventoryListener;
import org.Cry_Legende.sHWorldEditJoueur.listeners.PlayerListener;
import org.Cry_Legende.sHWorldEditJoueur.managers.ActionManager;
import org.Cry_Legende.sHWorldEditJoueur.managers.LogManager;
import org.Cry_Legende.sHWorldEditJoueur.managers.WorldEditManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SHWorldEditJoueur extends JavaPlugin {

    private static SHWorldEditJoueur instance;

    // Core Components
    private ConfigManager configManager;
    private ActionManager actionManager;
    private WorldEditManager worldEditManager;
    private LogManager logManager;

    // Hooks
    private VaultHook vaultHook;
    private FAWEHook faweHook;
    private SuperiorSkyblockHook ssbHook;
    public final String worldEditPrivilegeName = "SHWORLDEDIT_USE";

    /**
     * CORRECTION MAJEURE : Utilisation de onLoad().
     * Cette méthode s'exécute AVANT le onEnable() de SuperiorSkyblock2.
     * Cela garantit que la permission est enregistrée dans l'API avant que SSB2
     * ne charge les données des joueurs ou des îles.
     */
    @Override
    public void onLoad() {
        // On tente d'enregistrer la permission ici.
        // Comme ton plugin a "depend: [SuperiorSkyblock2]" dans plugin.yml,
        // les classes de SSB2 sont déjà accessibles par le ClassLoader.
        registerIslandPermission();
    }

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("Démarrage de SHWorldEditJoueur...");

        // 1. Initialisation de la configuration
        this.configManager = new ConfigManager(this);
        configManager.loadConfig();

        // 2. Vérification et initialisation des dépendances (Hooks)
        // Note: SSBHook est initialisé ici pour l'accès aux méthodes,
        // mais la permission a déjà été injectée dans le onLoad.
        if (!setupHooks()) {
            getLogger().severe("Une dépendance requise est manquante ! Le plugin va se désactiver.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Initialisation des managers internes
        this.actionManager = new ActionManager(this);
        this.worldEditManager = new WorldEditManager(this);
        this.logManager = new LogManager(this);

        // 4. Commandes
        setupCommands();

        // 5. Listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new InventoryListener(this), this);

        getLogger().info("SHWorldEditJoueur a été activé avec succès !");
    }

    @Override
    public void onDisable() {
        getLogger().info("SHWorldEditJoueur a été désactivé.");
    }

    private void setupCommands() {
        PlaceCommand placeCommand = new PlaceCommand(this);
        getCommand("place").setExecutor(placeCommand);
        getCommand("place").setTabCompleter(placeCommand);

        CutCommand cutCommand = new CutCommand(this);
        getCommand("cut").setExecutor(cutCommand);
        getCommand("cut").setTabCompleter(cutCommand);
    }

    private boolean setupHooks() {
        this.faweHook = new FAWEHook();
        if (!faweHook.setup()) return false;

        this.ssbHook = new SuperiorSkyblockHook(this);
        if (!ssbHook.setup()) return false;

        this.vaultHook = new VaultHook();
        if (!vaultHook.setupEconomy()) return false;

        return true;
    }

    /**
     * Enregistre la permission custom.
     * Appelé depuis onLoad pour précéder l'initialisation de SSB2.
     */
    private void registerIslandPermission() {
        try {
            // Petite sécurité : on vérifie si la classe existe (au cas où SSB2 n'est pas là)
            Class.forName("com.bgsoftware.superiorskyblock.api.island.IslandPrivilege");

            boolean alreadyExists = false;
            try {
                if (IslandPrivilege.getByName(worldEditPrivilegeName) != null) {
                    alreadyExists = true;
                }
            } catch (Exception ignored) {
                // Fallback ancienne méthode
                for (IslandPrivilege privilege : IslandPrivilege.values()) {
                    if (privilege.getName().equalsIgnoreCase(worldEditPrivilegeName)) {
                        alreadyExists = true;
                        break;
                    }
                }
            }

            if (!alreadyExists) {
                IslandPrivilege.register(worldEditPrivilegeName);
                getLogger().info("[onLoad] Permission '" + worldEditPrivilegeName + "' pré-enregistrée dans SuperiorSkyblock2.");
            }
        } catch (ClassNotFoundException e) {
            getLogger().warning("SuperiorSkyblock2 introuvable durant le onLoad. L'enregistrement de la permission est annulé.");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Erreur lors de l'enregistrement de la permission dans onLoad.", e);
        }
    }

    // ... Getters (identiques à avant) ...
    public static SHWorldEditJoueur getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public VaultHook getVaultHook() {
        return vaultHook;
    }

    public FAWEHook getFaweHook() {
        return faweHook;
    }

    public SuperiorSkyblockHook getSsbHook() {
        return ssbHook;
    }

    public ActionManager getActionManager() {
        return actionManager;
    }

    public WorldEditManager getWorldEditManager() {
        return worldEditManager;
    }

    public LogManager getLogManager() {
        return logManager;
    }
}
