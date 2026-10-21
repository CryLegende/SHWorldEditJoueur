package org.Cry_Legende.sHWorldEditJoueur.managers;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.mask.BlockMask;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.config.Group;
import org.Cry_Legende.sHWorldEditJoueur.utils.Common;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class WorldEditManager {

    private final SHWorldEditJoueur plugin;

    /**
     * Types de blocs considérés comme remplaçables par /place.
     * Construit une seule fois : tester l'appartenance à ce Set ne lit que l'état
     * du bloc, alors qu'un BlockMask force la lecture du bloc complet (NBT inclus)
     * à chaque position, ce qui coûtait l'essentiel du temps de la commande.
     */
    private final Set<BlockType> replaceableTypes;

    /**
     * Cache BlockType -> Material, pour éviter une conversion par bloc dans /cut.
     */
    private final Map<BlockType, Material> materialCache = new HashMap<>();

    public WorldEditManager(SHWorldEditJoueur plugin) {
        this.plugin = plugin;

        Set<BlockType> types = new HashSet<>();
        for (BlockType type : new BlockType[]{
            BlockTypes.AIR, BlockTypes.CAVE_AIR, BlockTypes.VOID_AIR,
            BlockTypes.WATER, BlockTypes.LAVA}) {
            if (type != null) {
                types.add(type);
            }
        }
        this.replaceableTypes = Collections.unmodifiableSet(types);
    }

    private Material toMaterial(BlockType type) {
        Material cached = materialCache.get(type);
        if (cached == null) {
            cached = BukkitAdapter.adapt(type);
            materialCache.put(type, cached);
        }
        return cached;
    }

    /**
     * Compte les blocs remplaçables d'une région, en s'arrêtant dès que {@code limit}
     * est atteint.
     *
     * <p>Remplace {@code EditSession.countBlocks(region, BlockMask)} : cette méthode
     * parcourait la région entière sans sortie anticipée et lisait le bloc complet
     * (NBT) à chaque position. Ici on ne lit que l'état du bloc et on s'arrête dès
     * que le résultat ne peut plus influencer la décision, la limite du groupe
     * plafonnant de toute façon ce que le joueur peut poser.
     *
     * @param world  monde WorldEdit concerné
     * @param region sélection à analyser
     * @param limit  plafond au-delà duquel le comptage s'arrête (<= 0 : renvoie 0)
     * @return le nombre de blocs remplaçables trouvés, borné par {@code limit}
     */
    public int countPlaceable(World world, Region region, int limit) {
        if (limit <= 0) {
            return 0;
        }

        int found = 0;
        try (EditSession session = WorldEdit.getInstance().newEditSession(world)) {
            for (BlockVector3 pt : region) {
                if (replaceableTypes.contains(session.getBlock(pt).getBlockType())) {
                    found++;
                    if (found >= limit) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Erreur lors du comptage des blocs remplaçables", e);
            return 0;
        }
        return found;
    }

    public void executePlace(Player player, Region region, ItemStack chosenItem, Island island) {
        Group group = plugin.getConfigManager().getPlayerGroup(player);
        World world = BukkitAdapter.adapt(player.getWorld());
        BlockState blockToPlace = BukkitAdapter.adapt(chosenItem.getType().createBlockData());

        if (plugin.getConfigManager().isBlockBanned("place", chosenItem.getType())) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("banned-block-place")
                .replace("%type%", prettyName(chosenItem.getType())));
            return;
        }

        int playerBlockCount = countItems(player, chosenItem.getType(), chosenItem.getDurability());
        if (playerBlockCount == 0) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-blocks")
                .replace("%type%", prettyName(chosenItem.getType())));
            return;
        }

        // Le budget se déduit de l'inventaire et de la limite du groupe : aucun
        // parcours de la région n'est nécessaire pour l'établir. Le nombre réel de
        // blocs posés est compté pendant la pose, et le surplus est remboursé.
        int budget = playerBlockCount;
        int maxAllowedByPerm = group.getMaxBlocks();
        boolean cappedByPermission = false;
        if (maxAllowedByPerm > 0 && budget > maxAllowedByPerm) {
            budget = maxAllowedByPerm;
            cappedByPermission = true;
        }

        double reservedCost = budget * group.getPlacePrice();

        if (!plugin.getVaultHook().has(player, reservedCost)) {
            double missing = reservedCost - plugin.getVaultHook().getBalance(player);
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-money")
                .replace("%missing%", plugin.getVaultHook().format(missing)));
            return;
        }

        if (!plugin.getVaultHook().withdraw(player, reservedCost)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-money-transaction-failed"));
            return;
        }
        removeItems(player, chosenItem, budget);

        // Une seule passe sur la région : lecture de l'état, écriture directe dans
        // l'EditSession (FAWE regroupe les écritures par chunk), arrêt dès que le
        // budget est épuisé. L'ancienne version faisait trois passes complètes
        // (countBlocks, remplissage d'un BlockArrayClipboard dimensionné sur toute
        // la région, puis collage) pour le même résultat.
        int affected = 0;
        try (EditSession session = WorldEdit.getInstance().newEditSession(world)) {
            for (BlockVector3 pt : region) {
                if (affected >= budget) {
                    break;
                }
                if (!replaceableTypes.contains(session.getBlock(pt).getBlockType())) {
                    continue;
                }
                session.setBlock(pt, blockToPlace);
                affected++;
            }
            Operations.complete(session.commit());
        } catch (WorldEditException e) {
            refund(player, chosenItem, budget, reservedCost);
            player.sendMessage(ChatColor.RED + "Une erreur WorldEdit est survenue, vos items et votre argent vous ont été rendus : " + e.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Erreur WorldEdit pendant /place pour " + player.getName(), e);
            return;
        }

        // Rembourse la part réservée mais non consommée.
        int unused = budget - affected;
        if (unused > 0) {
            refund(player, chosenItem, unused, unused * group.getPlacePrice());
        }

        if (affected == 0) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-placeable-blocks-in-selection"));
            return;
        }

        // N'avertir que si la limite du groupe a réellement borné la pose : le joueur
        // avait de quoi aller plus loin et la sélection offrait la place.
        if (cappedByPermission && affected >= maxAllowedByPerm) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("limit-exceeded")
                .replace("%max%", String.valueOf(maxAllowedByPerm)));
        }

        Key blockKey = SuperiorSkyblockAPI.getKeys().getKey(chosenItem);
        if (blockKey != null) {
            island.handleBlockPlace(blockKey, affected, true);
        }

        double chargedCost = affected * group.getPlacePrice();
        Common.sendMessage(player, plugin.getConfigManager().getMessage("placed-success")
            .replace("%amount%", String.valueOf(affected))
            .replace("%type%", prettyName(chosenItem.getType()))
            .replace("%price%", plugin.getVaultHook().format(chargedCost)));
        player.playSound(player.getLocation(), plugin.getConfigManager().getBuildSound(), 1.0f, 1.0f);

        plugin.getLogManager().logPlace(player, affected, chosenItem.getType(), chargedCost);
    }

    public void executeCut(Player player, Region region, Island island) {
        try {
            Group group = plugin.getConfigManager().getPlayerGroup(player);
            World world = BukkitAdapter.adapt(player.getWorld());

            int blocksToCutCount = 0;
            Map<ItemStack, Integer> collectedItems = new HashMap<>();
            Map<Key, Integer> blocksToRemoveFromIsland = new HashMap<>();

            // Lecture directe de la région. L'ancienne version recopiait d'abord
            // toute la sélection dans un BlockArrayClipboard via ForwardExtentCopy
            // (une passe complète et une allocation dimensionnée sur le volume de la
            // région) avant de la relire bloc par bloc.
            try (EditSession readSession = WorldEdit.getInstance().newEditSession(world)) {
                for (BlockVector3 pt : region) {
                    try {
                        BlockState block = readSession.getBlock(pt);
                        BlockType blockType = block.getBlockType();
                        if (blockType.getMaterial().isAir()) continue;

                        Material material = toMaterial(blockType);
                        if (material == null) continue;
                        if (plugin.getConfigManager().isBlockBanned("cut", material)) continue;

                        blocksToCutCount++;
                        ItemStack drop = getDropFromBlock(material);
                        if (drop != null) collectedItems.merge(drop, 1, Integer::sum);

                        Key blockKey = SuperiorSkyblockAPI.getKeys().getKey(material, (short) 0);
                        if (blockKey != null) {
                            blocksToRemoveFromIsland.merge(blockKey, 1, Integer::sum);
                        }
                    } catch (Exception loopException) {
                        plugin.getLogger().log(Level.SEVERE, "Erreur dans la boucle d'analyse du bloc à " + pt, loopException);
                    }
                }
            }

            if (blocksToCutCount == 0) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-cuttable-blocks-in-selection"));
                return;
            }

            double totalCost = blocksToCutCount * group.getCutPrice();
            if (!plugin.getVaultHook().has(player, totalCost)) {
                double missing = totalCost - plugin.getVaultHook().getBalance(player);
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-money").replace("%missing%", plugin.getVaultHook().format(missing)));
                return;
            }

            if (!plugin.getVaultHook().withdraw(player, totalCost)) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-money-transaction-failed"));
                return;
            }

            try (EditSession writeSession = WorldEdit.getInstance().newEditSession(world)) {
                Set<BaseBlock> bannedBaseBlocks = plugin.getConfigManager().getBannedCutMaterials().stream()
                    .filter(Material::isBlock)
                    .map(material -> BukkitAdapter.adapt(material.createBlockData()).toBaseBlock())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

                if (!bannedBaseBlocks.isEmpty()) {
                    Mask bannedMask = new BlockMask(writeSession, bannedBaseBlocks);
                    writeSession.setMask(bannedMask.inverse());
                }

                writeSession.setBlocks(region, BlockTypes.AIR.getDefaultState());
                Operations.complete(writeSession.commit());
            }

            for (Map.Entry<Key, Integer> entry : blocksToRemoveFromIsland.entrySet()) {
                try {
                    island.handleBlockBreak(entry.getKey(), entry.getValue(), true);
                } catch (Exception ssbException) {
                    plugin.getLogger().severe("ERREUR LORS DE L'APPEL A handleBlockBreak pour la clé '" + entry.getKey().toString() + "': " + ssbException.getMessage());
                }
            }

            giveItemsToPlayer(player, collectedItems);

            Common.sendMessage(player, plugin.getConfigManager().getMessage("cut-success")
                .replace("%amount%", String.valueOf(blocksToCutCount))
                .replace("%price%", plugin.getVaultHook().format(totalCost)));

            player.playSound(player.getLocation(), plugin.getConfigManager().getCutSound(), 1.0f, 1.0f);

            String itemsLog = collectedItems.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey().getType().toString())
                .collect(Collectors.joining(", "));
            plugin.getLogManager().logCut(player, blocksToCutCount, totalCost, itemsLog);

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "======================================================");
            plugin.getLogger().log(Level.SEVERE, "UNE ERREUR INTERNE CRITIQUE EST SURVENUE DANS SHWORLDEDITJOUEUR");
            plugin.getLogger().log(Level.SEVERE, "Joueur: " + player.getName());
            if (region != null) {
                plugin.getLogger().log(Level.SEVERE, "Région: " + region.getVolume() + " blocs de " + region.getMinimumPoint() + " à " + region.getMaximumPoint());
            }
            plugin.getLogger().log(Level.SEVERE, "Merci de rapporter l'erreur suivante à votre développeur:");
            e.printStackTrace();
            plugin.getLogger().log(Level.SEVERE, "======================================================");
            player.sendMessage(ChatColor.RED + "Une erreur interne est survenue. Veuillez contacter un administrateur.");
        }
    }

    /**
     * Rend au joueur des blocs et l'argent correspondant.
     *
     * <p>L'ancienne version passait le nombre de blocs à {@code depositPlayer} au lieu
     * du montant, ce qui remboursait 10 à 30 fois trop peu selon le prix du groupe.
     * Les items rendus étaient également perdus si l'inventaire était plein.
     */
    private void refund(Player player, ItemStack template, int amount, double money) {
        if (amount <= 0 && money <= 0) return;

        if (money > 0) {
            plugin.getVaultHook().depositPlayer(player, money);
        }
        if (amount <= 0) return;

        int maxStackSize = Math.max(1, template.getType().getMaxStackSize());
        int remaining = amount;
        while (remaining > 0) {
            int give = Math.min(remaining, maxStackSize);
            ItemStack stack = new ItemStack(template.getType(), give, template.getDurability());
            HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            for (ItemStack leftover : leftovers.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            remaining -= give;
        }
    }

    private String prettyName(Material material) {
        return material.toString().toLowerCase(Locale.ROOT).replace("_", " ");
    }

    private int countItems(Player player, Material material, short data) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material && item.getDurability() == data) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeItems(Player player, ItemStack itemToRemove, int amount) {
        if (amount <= 0) return;
        int remaining = amount;

        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack current = contents[i];

            if (current != null && current.isSimilar(itemToRemove)) {
                int currentAmount = current.getAmount();
                if (remaining >= currentAmount) {
                    player.getInventory().setItem(i, null);
                    remaining -= currentAmount;
                } else {
                    current.setAmount(currentAmount - remaining);
                    remaining = 0;
                }
            }
            if (remaining == 0) {
                break;
            }
        }
        player.updateInventory();
    }

    private ItemStack getDropFromBlock(Material material) {
        ItemStack customDrop = plugin.getConfigManager().getCustomDrop(material);
        if (customDrop != null) {
            return customDrop.getType() == Material.AIR ? null : customDrop.clone();
        }
        return new ItemStack(material, 1);
    }

    private void giveItemsToPlayer(Player player, Map<ItemStack, Integer> items) {
        PlayerInventory inventory = player.getInventory();
        boolean inventoryWasFull = false;

        for (Map.Entry<ItemStack, Integer> entry : items.entrySet()) {
            ItemStack baseItem = entry.getKey();
            if (baseItem == null || baseItem.getType() == Material.AIR) continue;

            int totalAmount = entry.getValue();
            int maxStackSize = baseItem.getType().getMaxStackSize();

            while (totalAmount > 0) {
                int amountToGive = Math.min(totalAmount, maxStackSize);
                ItemStack stackToGive = baseItem.clone();
                stackToGive.setAmount(amountToGive);

                HashMap<Integer, ItemStack> leftovers = inventory.addItem(stackToGive);

                if (!leftovers.isEmpty()) {
                    inventoryWasFull = true;
                    for (ItemStack leftover : leftovers.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                }
                totalAmount -= amountToGive;
            }
        }

        if (inventoryWasFull) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("inventory-full"));
        }
    }
}
