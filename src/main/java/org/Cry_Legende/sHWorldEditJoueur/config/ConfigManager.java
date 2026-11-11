package org.Cry_Legende.sHWorldEditJoueur.config;

import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Gère le chargement, l'analyse et l'accès aux valeurs de config.yml et aux textes de messages.yml.
 * Centralise la configuration pour faciliter la maintenance et l'accès aux données.
 */
public class ConfigManager {

    /** Préfixe du nœud de palier : shworldeditjoueur.group.<clé du groupe dans config.yml> (ex. shworldeditjoueur.group.vip). */
    public static final String GROUP_PERMISSION_PREFIX = "shworldeditjoueur.group.";

    private final SHWorldEditJoueur plugin;

    // Le reload construit de nouvelles valeurs puis les remplace d'un bloc : les champs sont volatiles et les
    // collections jamais modifiées sur place (elles sont lues pendant les actions, qui peuvent durer plusieurs ticks).

    // Settings
    private volatile Sound buildSound;
    private volatile Sound cutSound;
    private volatile String cancelWord = "cancel";
    private volatile String confirmWord = "confirm";
    private volatile long cancelTime = 20;
    private volatile Map<Material, ItemStack> replaceGiveMap = new HashMap<>();
    // Ensembles : isBlockBanned() est appelé pour chaque bloc d'une sélection
    private volatile Set<Material> bannedPlace = EnumSet.noneOf(Material.class);
    private volatile Set<Material> bannedCut = EnumSet.noneOf(Material.class);
    // Métadonnées Bukkit qui protègent un bloc posé par un autre plugin (générateurs...)
    private volatile List<String> protectedMetadata = List.of();

    // Messages
    private volatile String prefix = "";
    private volatile Map<String, String> messages = new HashMap<>();

    // Groups
    private volatile Map<String, Group> groups = new HashMap<>();

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

        Sound newBuildSound = sound(settings.getString("build-sound", "BLOCK_STONE_PLACE"));
        Sound newCutSound = sound(settings.getString("cut-sound", "BLOCK_STONE_BREAK"));
        if (newBuildSound == null || newCutSound == null) {
            plugin.getLogger().warning("Un son invalide a été spécifié dans config.yml. Utilisation des valeurs par défaut.");
            newBuildSound = Sound.BLOCK_STONE_PLACE;
            newCutSound = Sound.BLOCK_STONE_BREAK;
        }
        buildSound = newBuildSound;
        cutSound = newCutSound;

        cancelWord = settings.getString("cancel-word", "cancel");
        confirmWord = settings.getString("confirm-word", "confirm");
        cancelTime = settings.getInt("cancel-time", 20);

        Set<Material> newBannedPlace = settings.getStringList("banned-blocks.place").stream()
            .map(s -> Material.matchMaterial(s.trim().toUpperCase()))
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));
        Set<Material> newBannedCut = settings.getStringList("banned-blocks.cut").stream()
            .map(s -> Material.matchMaterial(s.trim().toUpperCase()))
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));
        bannedPlace = newBannedPlace;
        bannedCut = newBannedCut;
        protectedMetadata = settings.getStringList("protected-metadata").stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();

        Map<Material, ItemStack> newReplaceGive = new HashMap<>();
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
                    newReplaceGive.put(fromMaterial, toItemStack);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur de parsing pour l'entrée 'replace-give': " + entry);
            }
        }
        replaceGiveMap = newReplaceGive;
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
     * Charge messages.yml. Une clé absente reprend le texte livré avec le plugin.
     */
    private void loadMessages() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        YamlConfiguration yaml;
        try {
            yaml = ReloadException.read(file);
        } catch (ReloadException e) {
            plugin.getLogger().warning("messages.yml illisible, textes du jar utilisés : " + e.getMessage());
            yaml = new YamlConfiguration();
        }
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) {
                yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Lecture des messages livrés avec le plugin impossible : " + e.getMessage());
        }

        Map<String, String> newMessages = new HashMap<>();
        Set<String> keys = new LinkedHashSet<>(yaml.getKeys(false));
        if (yaml.getDefaults() != null) {
            keys.addAll(yaml.getDefaults().getKeys(false));
        }
        for (String key : keys) {
            if (key.equals("prefix")) continue;

            if (yaml.isList(key)) {
                newMessages.put(key, String.join("\n", yaml.getStringList(key)));
            } else {
                newMessages.put(key, yaml.getString(key));
            }
        }
        prefix = yaml.getString("prefix", "");
        messages = newMessages;
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

        Map<String, Group> newGroups = new HashMap<>();
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
                newGroups.put(groupName, group);
            }
        }
        groups = newGroups;
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
            .filter(group -> player.hasPermission(GROUP_PERMISSION_PREFIX + group.getName()))
            .max(Comparator.comparingInt(Group::getPriority))
            .orElse(groups.getOrDefault("default", new Group("default", 1, 500, 30, 30)));
    }

    /**
     * Récupère un message formaté depuis la configuration.
     */
    public String getMessage(String key) {
        String message = messages.getOrDefault(key, "&cMessage manquant dans messages.yml: '" + key + "'");
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
     * Vrai si le bloc porte une des métadonnées de {@code settings.protected-metadata} :
     * ni /cut ni /place n'y touchent.
     */
    public boolean isProtected(World world, int x, int y, int z) {
        if (protectedMetadata.isEmpty()) return false;
        Block block = world.getBlockAt(x, y, z);
        for (String key : protectedMetadata) {
            if (block.hasMetadata(key)) return true;
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

}
