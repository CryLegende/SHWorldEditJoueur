package org.Cry_Legende.sHWorldEditJoueur.config;

import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Gère le chargement, l'analyse et l'accès à toutes les valeurs du fichier config.yml.
 * Centralise la configuration pour faciliter la maintenance et l'accès aux données.
 */
public class ConfigManager {

    private final SHWorldEditJoueur plugin;

    // Settings
    private Sound buildSound;
    private Sound cutSound;
    private String cancelWord;
    private String confirmWord;
    private long cancelTime;
    private Map<Material, ItemStack> replaceGiveMap = new HashMap<>();
    // Ensembles : isBlockBanned() est appelé pour chaque bloc d'une sélection
    private Set<Material> bannedPlace = EnumSet.noneOf(Material.class);
    private Set<Material> bannedCut = EnumSet.noneOf(Material.class);

    // Messages
    private String prefix = "";
    private Map<String, String> messages = new HashMap<>();

    // Groups
    private Map<String, Group> groups = new HashMap<>();

    public ConfigManager(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
    }

    /**
     * Méthode principale pour charger (ou recharger) l'ensemble de la configuration.
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        loadSettings();
        loadMessages();
        loadGroups();
    }

    /**
     * Charge la section 'settings' du config.yml.
     */
    private void loadSettings() {
        ConfigurationSection settings = plugin.getConfig().getConfigurationSection("settings");
        if (settings == null) {
            plugin.getLogger().severe("La section 'settings' est manquante dans config.yml ! Des valeurs par défaut seront utilisées.");
            settings = plugin.getConfig().createSection("settings");
        }

        buildSound = sound(settings.getString("build-sound", "BLOCK_STONE_PLACE"));
        cutSound = sound(settings.getString("cut-sound", "BLOCK_STONE_BREAK"));
        if (buildSound == null || cutSound == null) {
            plugin.getLogger().warning("Un son invalide a été spécifié dans config.yml. Utilisation des valeurs par défaut.");
            buildSound = Sound.BLOCK_STONE_PLACE;
            cutSound = Sound.BLOCK_STONE_BREAK;
        }

        cancelWord = settings.getString("cancel-word", "cancel");
        confirmWord = settings.getString("confirm-word", "confirm");
        cancelTime = settings.getInt("cancel-time", 20);

        bannedPlace = settings.getStringList("banned-blocks.place").stream()
            .map(s -> Material.matchMaterial(s.trim().toUpperCase()))
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));
        bannedCut = settings.getStringList("banned-blocks.cut").stream()
            .map(s -> Material.matchMaterial(s.trim().toUpperCase()))
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));

        replaceGiveMap.clear();
        List<String> replaceGiveList = settings.getStringList("replace-give");
        for (String entry : replaceGiveList) {
            try {
                String[] parts = entry.split(":");
                if (parts.length != 2) {
                    plugin.getLogger().warning("Format invalide dans 'replace-give': " + entry + " (doit être 'BLOC_SOURCE:BLOC_CIBLE')");
                    continue;
                }

                Material fromMaterial = Material.matchMaterial(parts[0].trim().toUpperCase());
                if (fromMaterial == null) {
                    plugin.getLogger().warning("Matériau source invalide dans 'replace-give': " + parts[0]);
                    continue;
                }

                ItemStack toItemStack = parseItemStack(parts[1].trim());
                if (toItemStack != null) {
                    replaceGiveMap.put(fromMaterial, toItemStack);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur de parsing pour l'entrée 'replace-give': " + entry);
            }
        }
    }

    /**
     * Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup).
     */
    private static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    /**
     * Charge la section 'messages' du config.yml.
     */
    private void loadMessages() {
        ConfigurationSection msgSection = plugin.getConfig().getConfigurationSection("messages");
        if (msgSection == null) {
            plugin.getLogger().severe("La section 'messages' est manquante dans config.yml !");
            return;
        }

        prefix = msgSection.getString("prefix", "&7&l« &c&lSH &8&l» &r");
        messages.clear();
        for (String key : msgSection.getKeys(false)) {
            if (key.equals("prefix")) continue;

            if (msgSection.isList(key)) {
                messages.put(key, String.join("\n", msgSection.getStringList(key)));
            } else {
                messages.put(key, msgSection.getString(key));
            }
        }
    }

    /**
     * Charge la section 'groups' du config.yml et les transforme en objets Group.
     */
    private void loadGroups() {
        ConfigurationSection groupSection = plugin.getConfig().getConfigurationSection("groups");
        if (groupSection == null) {
            plugin.getLogger().severe("La section 'groups' est manquante dans config.yml !");
            return;
        }

        groups.clear();
        for (String groupName : groupSection.getKeys(false)) {
            ConfigurationSection current = groupSection.getConfigurationSection(groupName);
            if (current != null) {
                Group group = new Group(
                    groupName,
                    current.getInt("priority", 1),
                    current.getInt("max-blocks", 500),
                    current.getDouble("place-price", 30.0),
                    current.getDouble("cut-price", 30.0)
                );
                groups.put(groupName, group);
            }
        }
    }

    /**
     * Analyse une chaîne de caractères pour la convertir en ItemStack.
     */
    private ItemStack parseItemStack(String data) {
        try {
            String[] parts = data.split(";");
            String materialName = parts[0].toUpperCase();

            if ("AIR".equalsIgnoreCase(materialName)) {
                return new ItemStack(Material.AIR);
            }

            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().warning("Matériau invalide dans 'replace-give' (partie drop): " + materialName);
                return null;
            }

            int amount = 1;
            if (parts.length > 1) {
                amount = Integer.parseInt(parts[1]);
            }
            return new ItemStack(material, amount);
        } catch (Exception e) {
            plugin.getLogger().warning("Erreur de format pour un item dans 'replace-give': " + data);
            return null;
        }
    }

    /**
     * Obtient le groupe le plus prioritaire pour un joueur en fonction de ses permissions.
     */
    public Group getPlayerGroup(Player player) {
        return groups.values().stream()
            .filter(group -> player.hasPermission("shwe.group." + group.getName()))
            .max(Comparator.comparingInt(Group::getPriority))
            .orElse(groups.getOrDefault("default", new Group("default", 1, 500, 30, 30)));
    }

    /**
     * Récupère un message formaté depuis la configuration.
     */
    public String getMessage(String key) {
        String message = messages.getOrDefault(key, "&cMessage manquant dans config.yml: '" + key + "'");
        String coloredMessage = ChatColor.translateAlternateColorCodes('&', message);
        return ChatColor.translateAlternateColorCodes('&', prefix + coloredMessage);
    }

    /**
     * Vérifie si un matériau est banni pour une action spécifique.
     */
    public boolean isBlockBanned(String type, Material material) {
        if ("place".equalsIgnoreCase(type)) {
            return bannedPlace.contains(material);
        } else if ("cut".equalsIgnoreCase(type)) {
            return bannedCut.contains(material);
        }
        return false;
    }

    /**
     * Récupère le drop personnalisé pour un matériau.
     */
    public ItemStack getCustomDrop(Material material) {
        return replaceGiveMap.get(material);
    }

    // --- GETTERS ---

    public Sound getBuildSound() {
        return buildSound;
    }

    public Sound getCutSound() {
        return cutSound;
    }

    public String getCancelWord() {
        return cancelWord;
    }

    public String getConfirmWord() {
        return confirmWord;
    }

    public long getCancelTime() {
        return cancelTime;
    }

    public Set<Material> getBannedCutMaterials() {
        return Collections.unmodifiableSet(bannedCut);
    }
}
