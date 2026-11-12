package org.Cry_Legende.sHWorldEditJoueur.managers;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.config.ConfigManager;
import org.Cry_Legende.sHWorldEditJoueur.config.Group;
import org.Cry_Legende.sHWorldEditJoueur.utils.BlockStates;
import org.Cry_Legende.sHWorldEditJoueur.utils.Common;
import org.Cry_Legende.sHWorldEditJoueur.utils.SectionReader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.Campfire;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.EntityBlockStorage;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * /place et /cut.
 *
 * <p>Parcourir la sélection est la seule étape longue : elle tourne hors du thread principal ({@link SectionReader}),
 * pour le comptage affiché à la commande comme pour la recherche des blocs à la confirmation. Elle ne modifie rien.
 *
 * <p>Tout le reste se fait ensuite en une seule étape sur le thread principal, donc dans un même tick : chaque bloc
 * retenu est relu, l'argent et les objets sont pris, les blocs sont posés ou retirés par FAWE (appliqué immédiatement
 * sur le thread principal), puis relus. Le joueur paie et reçoit exactement ce qui a changé dans le monde : un bloc
 * cassé, posé ou modifié entre le parcours et la confirmation est simplement laissé de côté, jamais compté deux fois.
 */
public class WorldEditManager {

    private final SHWorldEditJoueur plugin;
    private final BlockStates states;
    private final char airState;

    /** Parcours des sélections. Deux threads : un joueur ne bloque pas les autres. */
    private final ExecutorService worker;

    /** Joueurs dont un parcours ou une action est en cours, îles dont une action est en cours (thread principal). */
    private final Set<UUID> busyPlayers = new HashSet<>();
    private final Set<UUID> busyIslands = new HashSet<>();

    public WorldEditManager(SHWorldEditJoueur plugin) {
        this.plugin = plugin;
        this.states = new BlockStates(plugin.getLogger());
        this.airState = BlockTypes.AIR.getDefaultState().getOrdinalChar();

        AtomicInteger threadNumber = new AtomicInteger();
        this.worker = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "SHWorldEditJoueur-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Arrêt du plugin. Rien n'est payé hors du thread principal : un parcours interrompu n'a rien coûté.
     */
    public void shutdown() {
        worker.shutdownNow();
    }

    /**
     * Vrai si le joueur a déjà un parcours ou une action en cours, ou son île une action : un second lancé en même
     * temps lirait la sélection avant que le premier ait fini de la modifier.
     */
    public boolean isBusy(Player player, Island island) {
        return busyPlayers.contains(player.getUniqueId())
            || (island != null && busyIslands.contains(island.getUniqueId()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // /place
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Pourquoi cet objet ne peut pas être posé avec /place (clé de message), ou null s'il peut l'être.
     * Vérifié au clic, avant de consommer l'action en attente : le joueur peut cliquer un autre objet.
     */
    public String placeRefusal(ItemStack item) {
        Material type = item.getType();
        if (!type.isBlock() || type.isAir()) {
            return "invalid-block";
        }
        if (plugin.getConfigManager().isBlockBanned("place", type)) {
            return "banned-block-place";
        }
        // Porte, lit, grande fleur : FAWE ne poserait qu'une moitié
        BlockData data = type.createBlockData();
        if (data instanceof Bed || (data instanceof Bisected && !(data instanceof Stairs) && !(data instanceof TrapDoor))) {
            return "invalid-block";
        }
        // Seuls les blocs ordinaires : un objet nommé, enchanté, rempli (boîte de shulker) ou marqué par un plugin
        // (générateur...) serait retiré de l'inventaire et remplacé par un bloc ordinaire.
        if (!item.isSimilar(new ItemStack(type))) {
            return "plain-blocks-only";
        }
        return null;
    }

    /**
     * /place : compte hors du thread principal les emplacements libres de la sélection (arrêt à la limite du groupe),
     * puis met l'action en attente du clic sur un bloc.
     */
    public void requestPlace(Player player, Island island, Region selection, Group group) {
        if (isBusy(player, island)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("action-in-progress"));
            return;
        }
        int cap = group.getMaxBlocks() > 0 ? group.getMaxBlocks() : Integer.MAX_VALUE;
        Region region = selection.clone();
        World world = BukkitAdapter.adapt(player.getWorld());
        org.bukkit.World bukkitWorld = player.getWorld();
        UUID playerId = player.getUniqueId();
        busyPlayers.add(playerId);
        async(() -> scanPlace(world, bukkitWorld, region, cap, null), (count, error) -> {
            busyPlayers.remove(playerId);
            if (!isLive(player)) {
                return;
            }
            if (error != null) {
                countError(player, error);
                return;
            }
            if (count == 0) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-placeable-blocks-in-selection"));
                return;
            }
            plugin.getActionManager().startAction(player, region, ActionData.ActionType.PLACE, island);
            String confirmMessage = plugin.getConfigManager().getMessage("confirm-place")
                .replace("%amount%", String.valueOf(count))
                .replace("%price%", plugin.getVaultHook().format(group.getPlacePrice()))
                .replace("%time%", String.valueOf(plugin.getConfigManager().getCancelTime()));
            player.sendMessage(confirmMessage.split("\n"));
        });
    }

    /**
     * /place confirmé par le clic sur un bloc de l'inventaire ({@link #placeRefusal} déjà vérifié).
     */
    public void executePlace(Player player, Region region, ItemStack chosenItem, Island island) {
        // L'item cliqué est l'objet vivant de l'inventaire : on travaille sur une copie autonome
        ItemStack template = chosenItem.clone();
        template.setAmount(1);

        // L'état du joueur a pu changer pendant le délai de confirmation (île quittée, droits retirés...)
        if (!isActionStillValid(player, region, island)) {
            return;
        }
        if (isBusy(player, island)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("action-in-progress"));
            return;
        }
        int playerBlockCount = countItems(player, template);
        if (playerBlockCount == 0) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-blocks")
                .replace("%type%", prettyName(template.getType())));
            return;
        }

        Group group = plugin.getConfigManager().getPlayerGroup(player);
        int maxAllowedByPerm = group.getMaxBlocks();
        boolean cappedByPermission = maxAllowedByPerm > 0 && playerBlockCount > maxAllowedByPerm;
        int budget = cappedByPermission ? maxAllowedByPerm : playerBlockCount;

        Job job = new Job(player, region, island, group, worldOf(player, region));
        BlockState block = BukkitAdapter.adapt(template.getType().createBlockData());
        Positions found = new Positions(Math.min(budget, 4096));
        lock(job);
        async(() -> scanPlace(job.world, job.bukkitWorld, region, budget, found),
            (count, error) -> applyPlace(job, found, error, template, block, cappedByPermission));
    }

    /** Hors thread principal : emplacements libres (air, eau, lave) non protégés, dans l'ordre de WorldEdit. */
    private int scanPlace(World world, org.bukkit.World bukkitWorld, Region region, int limit, Positions out) {
        SectionReader reader = new SectionReader(world);
        ConfigManager config = plugin.getConfigManager();
        int found = 0;
        for (BlockVector3 pt : region) {
            int x = pt.x();
            int y = pt.y();
            int z = pt.z();
            char state = reader.state(x, y, z);
            if (!states.replaceable[state] || config.isProtected(bukkitWorld, x, y, z)) {
                continue;
            }
            if (out != null) {
                out.add(x, y, z, state);
            }
            if (++found >= limit) {
                break;
            }
        }
        return found;
    }

    /** Thread principal, en un tick : relecture, paiement, pose, contrôle. */
    private void applyPlace(Job job, Positions found, Throwable scanError, ItemStack template, BlockState block,
                            boolean cappedByPermission) {
        Player player = job.player;
        try {
            if (scanError != null) {
                if (isLive(player)) countError(player, scanError);
                return;
            }
            // Rien n'a encore été payé : un joueur parti (ou reconnecté : nouvel inventaire) ou une île changée
            // entre-temps arrête simplement l'action
            if (!isLive(player) || !isActionStillValid(player, job.region, job.island)) {
                return;
            }
            int available = countItems(player, template);
            if (available == 0) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-blocks")
                    .replace("%type%", prettyName(template.getType())));
                return;
            }

            // Un bloc posé depuis le parcours n'est jamais écrasé
            SectionReader reader = new SectionReader(job.world);
            ConfigManager config = plugin.getConfigManager();
            Positions targets = new Positions(Math.min(found.size, available));
            for (int i = 0; i < found.size && targets.size < available; i++) {
                int x = found.x[i], y = found.y[i], z = found.z[i];
                char state = reader.state(x, y, z);
                if (states.replaceable[state] && !config.isProtected(job.bukkitWorld, x, y, z)) {
                    targets.add(x, y, z, state);
                }
            }
            if (targets.size == 0) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-placeable-blocks-in-selection"));
                return;
            }

            double price = job.group.getPlacePrice();
            int amount = targets.size;
            if (!plugin.getVaultHook().has(player, amount * price)) {
                double missing = amount * price - plugin.getVaultHook().getBalance(player);
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-money")
                    .replace("%missing%", plugin.getVaultHook().format(missing)));
                return;
            }
            if (!plugin.getVaultHook().withdraw(player, amount * price)) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-money-transaction-failed"));
                return;
            }
            int removed = removeItems(player, template, amount);
            if (removed < amount) {
                plugin.getVaultHook().depositPlayer(player, (amount - removed) * price);
                amount = removed;
                if (amount == 0) {
                    Common.sendMessage(player, plugin.getConfigManager().getMessage("no-blocks")
                        .replace("%type%", prettyName(template.getType())));
                    return;
                }
            }

            try {
            Throwable setError = setBlocks(job.world, targets, amount, block);

                // Seuls les blocs réellement posés sont payés. Comparaison par type : FAWE peut raccorder une barrière ou un
                // mur à ses voisins, son état final n'est alors plus celui demandé.
                Material placedType = template.getType();
                Positions placed = changed(job.world, targets, amount, state -> states.material[state] == placedType);
                int notPlaced = amount - placed.size;
                if (notPlaced > 0) {
                    refund(player, template, notPlaced, notPlaced * price);
                }
                if (setError != null) {
                    plugin.getLogger().log(Level.SEVERE, "Erreur WorldEdit pendant /place pour " + player.getName()
                        + " (" + placed.size + "/" + amount + " blocs posés)", setError);
                }
                if (placed.size == 0) {
                    Common.sendMessage(player, plugin.getConfigManager().getMessage("worldedit-error")
                        .replace("%error%", String.valueOf(setError != null ? setError.getMessage() : "aucun bloc posé")));
                    return;
                }

                // N'avertir que si la limite du groupe a réellement borné la pose
                int maxAllowedByPerm = job.group.getMaxBlocks();
                if (cappedByPermission && placed.size >= maxAllowedByPerm) {
                    Common.sendMessage(player, plugin.getConfigManager().getMessage("limit-exceeded")
                        .replace("%max%", String.valueOf(maxAllowedByPerm)));
                }

                Key blockKey = SuperiorSkyblockAPI.getKeys().getKey(template);
                if (blockKey != null) {
                    job.island.handleBlockPlace(blockKey, placed.size, true);
                }
                markChunksDirty(job.island, job.bukkitWorld, placed);

                double charged = placed.size * price;
                Common.sendMessage(player, plugin.getConfigManager().getMessage("placed-success")
                    .replace("%amount%", String.valueOf(placed.size))
                    .replace("%type%", prettyName(template.getType()))
                    .replace("%price%", plugin.getVaultHook().format(charged)));
                player.playSound(player.getLocation(), plugin.getConfigManager().getBuildSound(), 1.0f, 1.0f);
                plugin.getLogManager().logPlace(player, placed.size, template.getType(), charged);
            } catch (RuntimeException e) {
                paidFailure(player, "/place", amount * price, template.getType(), amount, e);
            }
        } finally {
            unlock(job);
        }
    }

    /**
     * Le calcul du niveau (/is level) ne lit que les chunks que SuperiorSkyblock2 sait occupés : un /place dans un
     * chunk encore vide doit l'y ajouter, sinon le prochain calcul ignore ces blocs.
     */
    private static void markChunksDirty(Island island, org.bukkit.World world, Positions placed) {
        Set<Long> chunks = new LinkedHashSet<>();
        for (int i = 0; i < placed.size; i++) {
            chunks.add(((long) (placed.x[i] >> 4) << 32) | ((placed.z[i] >> 4) & 0xFFFFFFFFL));
        }
        List<long[]> toMark = new ArrayList<>();
        for (long chunk : chunks) {
            int chunkX = (int) (chunk >> 32);
            int chunkZ = (int) chunk;
            if (!island.isChunkDirty(world, chunkX, chunkZ)) {
                toMark.add(new long[]{chunkX, chunkZ});
            }
        }
        for (int i = 0; i < toMark.size(); i++) {
            long[] chunk = toMark.get(i);
            island.markChunkDirty(world, (int) chunk[0], (int) chunk[1], i == toMark.size() - 1);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // /cut
    // ------------------------------------------------------------------------------------------------------------

    /**
     * /cut : compte hors du thread principal les blocs à couper (arrêt dès que la limite du groupe est dépassée),
     * puis met l'action en attente de /cut confirm.
     */
    public void requestCut(Player player, Island island, Region selection, Group group) {
        if (isBusy(player, island)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("action-in-progress"));
            return;
        }
        int max = group.getMaxBlocks();
        int limit = cutLimit(max);
        Region region = selection.clone();
        World world = BukkitAdapter.adapt(player.getWorld());
        org.bukkit.World bukkitWorld = player.getWorld();
        UUID playerId = player.getUniqueId();
        busyPlayers.add(playerId);
        async(() -> scanCut(world, bukkitWorld, region, limit, null), (count, error) -> {
            busyPlayers.remove(playerId);
            if (!isLive(player)) {
                return;
            }
            if (error != null) {
                countError(player, error);
                return;
            }
            if (count > max) {
                tooLarge(player, max, count);
                return;
            }
            if (count == 0) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-cuttable-blocks-in-selection"));
                return;
            }
            plugin.getActionManager().startAction(player, region, ActionData.ActionType.CUT, island);
            String confirmMessage = plugin.getConfigManager().getMessage("confirm-cut")
                .replace("%amount%", String.valueOf(count))
                .replace("%price%", plugin.getVaultHook().format(group.getCutPrice()))
                .replace("%total_price%", plugin.getVaultHook().format(count * group.getCutPrice()))
                .replace("%time%", String.valueOf(plugin.getConfigManager().getCancelTime()));
            player.sendMessage(confirmMessage.split("\n"));
        });
    }

    /**
     * /cut confirm.
     */
    public void executeCut(Player player, Region region, Island island) {
        // L'état du joueur a pu changer pendant le délai de confirmation (île quittée, droits retirés...)
        if (!isActionStillValid(player, region, island)) {
            return;
        }
        if (isBusy(player, island)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("action-in-progress"));
            return;
        }
        Group group = plugin.getConfigManager().getPlayerGroup(player);
        int limit = cutLimit(group.getMaxBlocks());
        Job job = new Job(player, region, island, group, worldOf(player, region));
        Positions found = new Positions(Math.min(limit, 4096));
        lock(job);
        async(() -> scanCut(job.world, job.bukkitWorld, region, limit, found),
            (count, error) -> applyCut(job, found, error));
    }

    /** La limite du groupe plus un : trouver un bloc de trop suffit à refuser la sélection. */
    private static int cutLimit(int max) {
        return max < Integer.MAX_VALUE ? Math.max(1, max + 1) : Integer.MAX_VALUE;
    }

    /**
     * Hors thread principal : blocs que /cut peut couper (ni air, ni incassable en survie, ni interdit, ni protégé),
     * dans l'ordre de WorldEdit.
     */
    private int scanCut(World world, org.bukkit.World bukkitWorld, Region region, int limit, Positions out) {
        SectionReader reader = new SectionReader(world);
        ConfigManager config = plugin.getConfigManager();
        Set<Material> banned = config.getBannedCut();
        int found = 0;
        for (BlockVector3 pt : region) {
            int x = pt.x();
            int y = pt.y();
            int z = pt.z();
            char state = reader.state(x, y, z);
            // Interdiction par bloc ou par l'objet qui le pose : KELP couvre KELP_PLANT, PLAYER_HEAD PLAYER_WALL_HEAD
            if (states.neverCut[state] || banned.contains(states.material[state]) || banned.contains(states.item[state])
                || config.isProtected(bukkitWorld, x, y, z)) {
                continue;
            }
            if (out != null) {
                out.add(x, y, z, state);
            }
            if (++found >= limit) {
                break;
            }
        }
        return found;
    }

    /** Thread principal, en un tick : relecture, paiement, retrait, contrôle, objets rendus. */
    private void applyCut(Job job, Positions found, Throwable scanError) {
        Player player = job.player;
        try {
            if (scanError != null) {
                if (isLive(player)) countError(player, scanError);
                return;
            }
            if (!isLive(player) || !isActionStillValid(player, job.region, job.island)) {
                return;
            }
            int max = job.group.getMaxBlocks();
            if (found.size > max) {
                // La sélection a grossi depuis la commande
                tooLarge(player, max, found.size);
                return;
            }

            // Seuls les blocs encore identiques à ceux du parcours sont coupés : un bloc cassé ou changé entre-temps
            // n'est ni payé ni rendu.
            SectionReader reader = new SectionReader(job.world);
            ConfigManager config = plugin.getConfigManager();
            Positions targets = new Positions(found.size);
            int keptFull = 0;
            for (int i = 0; i < found.size; i++) {
                int x = found.x[i], y = found.y[i], z = found.z[i];
                char state = reader.state(x, y, z);
                if (state != found.state[i] || config.isProtected(job.bukkitWorld, x, y, z)) {
                    continue;
                }
                if (states.tile[state] && holdsSomething(job.bukkitWorld, x, y, z)) {
                    keptFull++;
                    continue;
                }
                targets.add(x, y, z, state);
            }
            addPartners(job, reader, targets);
            if (targets.size == 0) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage(keptFull > 0
                    ? "cut-kept-containers-only" : "no-cuttable-blocks-in-selection"));
                return;
            }

            double price = job.group.getCutPrice();
            double cost = targets.size * price;
            if (!plugin.getVaultHook().has(player, cost)) {
                double missing = cost - plugin.getVaultHook().getBalance(player);
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-money")
                    .replace("%missing%", plugin.getVaultHook().format(missing)));
                return;
            }
            if (!plugin.getVaultHook().withdraw(player, cost)) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-money-transaction-failed"));
                return;
            }

            try {
            Throwable setError = setBlocks(job.world, targets, targets.size, BlockTypes.AIR.getDefaultState());

                // Seuls les blocs réellement retirés sont payés et rendus
                Positions cut = changed(job.world, targets, targets.size, state -> state == airState);
                int notCut = targets.size - cut.size;
                if (notCut > 0) {
                    plugin.getVaultHook().depositPlayer(player, notCut * price);
                }
                if (setError != null) {
                    plugin.getLogger().log(Level.SEVERE, "Erreur WorldEdit pendant /cut pour " + player.getName()
                        + " (" + cut.size + "/" + targets.size + " blocs retirés)", setError);
                }
                if (cut.size == 0) {
                    Common.sendMessage(player, plugin.getConfigManager().getMessage("worldedit-error")
                        .replace("%error%", String.valueOf(setError != null ? setError.getMessage() : "aucun bloc retiré")));
                    return;
                }

                // Objets rendus et blocs retirés du compte de l'île, par matériau. cut.state garde l'état d'avant la coupe.
                Map<Material, Integer> items = new EnumMap<>(Material.class);
                Map<Material, Integer> islandBlocks = new EnumMap<>(Material.class);
                for (int i = 0; i < cut.size; i++) {
                    char state = cut.state[i];
                    Material material = states.material[state];
                    items.merge(material, (int) states.cutItems[state], Integer::sum);
                    islandBlocks.merge(material, (int) states.islandAmount[state], Integer::sum);
                }
                for (Map.Entry<Material, Integer> entry : islandBlocks.entrySet()) {
                    Key blockKey = SuperiorSkyblockAPI.getKeys().getKey(entry.getKey(), (short) 0);
                    if (blockKey == null) continue;
                    try {
                        job.island.handleBlockBreak(blockKey, entry.getValue(), true);
                    } catch (Exception e) {
                        plugin.getLogger().log(Level.SEVERE, "handleBlockBreak a échoué pour la clé " + blockKey, e);
                    }
                }
                Map<ItemStack, Integer> drops = new LinkedHashMap<>();
                for (Map.Entry<Material, Integer> entry : items.entrySet()) {
                    if (entry.getValue() <= 0) continue;
                    for (ItemStack drop : dropsOf(entry.getKey())) {
                        int amount = drop.getAmount() * entry.getValue();
                        drop.setAmount(1);
                        drops.merge(drop, amount, Integer::sum);
                    }
                }
                giveItemsToPlayer(player, drops);

                double charged = cut.size * price;
                Common.sendMessage(player, plugin.getConfigManager().getMessage("cut-success")
                    .replace("%amount%", String.valueOf(cut.size))
                    .replace("%price%", plugin.getVaultHook().format(charged)));
                if (keptFull > 0) {
                    Common.sendMessage(player, plugin.getConfigManager().getMessage("cut-kept-containers")
                        .replace("%amount%", String.valueOf(keptFull)));
                }
                player.playSound(player.getLocation(), plugin.getConfigManager().getCutSound(), 1.0f, 1.0f);

                String itemsLog = drops.entrySet().stream()
                    .map(entry -> entry.getValue() + " " + entry.getKey().getType())
                    .collect(Collectors.joining(", "));
                plugin.getLogManager().logCut(player, cut.size, charged, itemsLog);
            } catch (RuntimeException e) {
                paidFailure(player, "/cut", cost, null, 0, e);
            }
        } finally {
            unlock(job);
        }
    }

    /**
     * Thread principal : le bloc garde quelque chose que /cut ferait disparaître (contenu d'un conteneur, livre d'un
     * pupitre, disque, abeilles, motifs d'une bannière, tessons d'un pot décoré, profil d'une tête, nourriture d'un feu
     * de camp). Il reste en place.
     */
    private static boolean holdsSomething(org.bukkit.World world, int x, int y, int z) {
        org.bukkit.block.BlockState state = world.getBlockAt(x, y, z).getState(false);
        if (state instanceof InventoryHolder holder && !holder.getInventory().isEmpty()) {
            return true;
        }
        if (state instanceof EntityBlockStorage<?> storage && storage.getEntityCount() > 0) {
            return true;
        }
        if (state instanceof Banner banner && banner.numberOfPatterns() > 0) {
            return true;
        }
        if (state instanceof DecoratedPot pot && pot.getSherds().values().stream().anyMatch(m -> m != Material.BRICK)) {
            return true;
        }
        if (state instanceof Skull skull && skull.getPlayerProfile() != null) {
            return true;
        }
        if (state instanceof Campfire campfire) {
            for (int i = 0; i < campfire.getSize(); i++) {
                ItemStack item = campfire.getItem(i);
                if (item != null && !item.getType().isAir()) return true;
            }
        }
        return false;
    }

    /**
     * Objets rendus pour un bloc coupé : replace-give de la config, sinon l'objet qui pose le bloc. Un pot de fleur
     * garni rend le pot et la plante.
     */
    private List<ItemStack> dropsOf(Material material) {
        ItemStack custom = plugin.getConfigManager().getCustomDrop(material);
        if (custom != null) {
            return custom.getType().isAir() ? List.of() : List.of(custom.clone());
        }
        String name = material.name();
        if (name.startsWith("POTTED_")) {
            List<ItemStack> drops = new ArrayList<>(2);
            drops.add(new ItemStack(Material.FLOWER_POT));
            String plant = name.substring("POTTED_".length())
                .replace("FLOWERING_AZALEA_BUSH", "FLOWERING_AZALEA")
                .replace("AZALEA_BUSH", "AZALEA");
            Material plantMaterial = Material.matchMaterial(plant);
            if (plantMaterial != null && plantMaterial.isItem()) {
                drops.add(new ItemStack(plantMaterial));
            }
            return drops;
        }
        // Le bloc lui-même, sinon l'objet qui le pose (torche murale : torche, carottes : carotte...). Eau, lave : rien.
        Material item = states.itemOf(material);
        return item == null ? List.of() : List.of(new ItemStack(item));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Commun
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Thread principal : pose {@code block} aux {@code count} premières positions. FAWE applique immédiatement sur le
     * thread principal. Renvoie l'erreur éventuelle (le contrôle qui suit compte ce qui a réellement changé).
     */
    private static Throwable setBlocks(World world, Positions positions, int count, BlockState block) {
        try (EditSession session = WorldEdit.getInstance().newEditSession(world)) {
            for (int i = 0; i < count; i++) {
                session.setBlock(positions.x[i], positions.y[i], positions.z[i], block);
            }
            Operations.complete(session.commit());
            return null;
        } catch (RuntimeException e) { // WorldEditException comprise
            return e;
        }
    }

    /**
     * Thread principal : parmi les {@code count} premières positions, celles dont l'état est maintenant accepté par
     * {@code expected}. L'état gardé pour chacune est celui d'avant la modification.
     */
    private static Positions changed(World world, Positions positions, int count, StatePredicate expected) {
        SectionReader reader = new SectionReader(world);
        Positions changed = new Positions(count);
        for (int i = 0; i < count; i++) {
            if (expected.test(reader.state(positions.x[i], positions.y[i], positions.z[i]))) {
                changed.add(positions.x[i], positions.y[i], positions.z[i], positions.state[i]);
            }
        }
        return changed;
    }

    /**
     * Lance {@code work} hors du thread principal puis {@code onMain} sur le thread principal avec son résultat ou
     * son erreur. Le plugin arrêté entre-temps : la suite est abandonnée (rien n'a été payé).
     */
    private <T> void async(Callable<T> work, BiConsumer<T, Throwable> onMain) {
        try {
            worker.execute(() -> {
                T result = null;
                Throwable error = null;
                try {
                    result = work.call();
                } catch (Throwable t) {
                    error = t;
                }
                T finalResult = result;
                Throwable finalError = error;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> onMain.accept(finalResult, finalError));
                } catch (IllegalPluginAccessException ignored) {
                    // Plugin désactivé
                }
            });
        } catch (RejectedExecutionException e) {
            onMain.accept(null, e); // Plugin en cours d'arrêt : on est déjà sur le thread principal
        }
    }

    /**
     * Le joueur est toujours connecté avec cette même session. Après une déconnexion, isOnline() reste vrai sur l'ancien
     * objet dès que le joueur est revenu, mais son inventaire n'est plus celui du jeu.
     */
    private static boolean isLive(Player player) {
        return player.isConnected() && Bukkit.getPlayer(player.getUniqueId()) == player;
    }

    /**
     * /cut : la moitié jumelle d'une porte, d'une grande plante ou d'un lit coupé est coupée avec lui, même hors de la
     * sélection, comme quand on casse le bloc à la main. Sinon elle disparaîtrait au prochain voisin mis à jour sans
     * rien rendre, et SuperiorSkyblock2 la compterait encore.
     */
    private void addPartners(Job job, SectionReader reader, Positions targets) {
        ConfigManager config = plugin.getConfigManager();
        Set<Long> taken = null;
        int initial = targets.size;
        for (int i = 0; i < initial; i++) {
            char state = targets.state[i];
            if (!states.twoPart[state]) continue;
            if (taken == null) {
                taken = new HashSet<>();
                for (int j = 0; j < targets.size; j++) taken.add(pack(targets.x[j], targets.y[j], targets.z[j]));
            }
            int px = targets.x[i] + states.partnerDx[state];
            int py = targets.y[i] + states.partnerDy[state];
            int pz = targets.z[i] + states.partnerDz[state];
            long key = pack(px, py, pz);
            if (taken.contains(key)) continue;
            char other = reader.state(px, py, pz);
            // La vraie jumelle : même bloc, et son décalage pointe en retour vers celui-ci
            if (!states.twoPart[other] || states.material[other] != states.material[state]
                || states.partnerDx[other] != -states.partnerDx[state]
                || states.partnerDy[other] != -states.partnerDy[state]
                || states.partnerDz[other] != -states.partnerDz[state]) continue;
            if (config.isProtected(job.bukkitWorld, px, py, pz)
                || !job.island.isInsideRange(new org.bukkit.Location(job.bukkitWorld, px, py, pz))) continue;
            targets.add(px, py, pz, other);
            taken.add(key);
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * Erreur après le paiement : rien n'est rendu automatiquement (l'état exact est inconnu), tout est journalisé pour
     * un remboursement à la main.
     */
    private void paidFailure(Player player, String action, double paid, Material items, int itemCount, Exception e) {
        plugin.getLogger().log(Level.SEVERE, action + " de " + player.getName() + " interrompu après le paiement : "
            + plugin.getVaultHook().format(paid) + " prélevés" + (items != null ? ", " + itemCount + " " + items + " retirés" : "")
            + ". À vérifier et rembourser à la main si besoin.", e);
        Common.sendMessage(player, plugin.getConfigManager().getMessage("internal-error"));
    }

    private void lock(Job job) {
        busyPlayers.add(job.playerId);
        busyIslands.add(job.islandId);
    }

    private void unlock(Job job) {
        busyPlayers.remove(job.playerId);
        busyIslands.remove(job.islandId);
    }

    private void countError(Player player, Throwable error) {
        plugin.getLogger().log(Level.SEVERE, "Erreur pendant la lecture d'une sélection pour " + player.getName(), error);
        Common.sendMessage(player, plugin.getConfigManager().getMessage("count-error")
            .replace("%error%", String.valueOf(error.getMessage())));
    }

    private void tooLarge(Player player, int max, int size) {
        Common.sendMessage(player, plugin.getConfigManager().getMessage("selection-too-large")
            .replace("%max%", String.valueOf(max))
            .replace("%size%", String.valueOf(size)));
    }

    /**
     * Rend au joueur des blocs et l'argent correspondant (blocs payés mais pas posés).
     */
    private void refund(Player player, ItemStack template, int amount, double money) {
        if (money > 0) {
            plugin.getVaultHook().depositPlayer(player, money);
        }
        if (amount > 0) {
            giveItemsToPlayer(player, Map.of(template, amount));
        }
    }

    private String prettyName(Material material) {
        return material.toString().toLowerCase(Locale.ROOT).replace("_", " ");
    }

    private int countItems(Player player, ItemStack template) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.isSimilar(template)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /**
     * Retire jusqu'à {@code amount} items identiques à {@code itemToRemove}.
     *
     * @return le nombre d'items réellement retirés
     */
    private int removeItems(Player player, ItemStack itemToRemove, int amount) {
        if (amount <= 0) return 0;
        int remaining = amount;

        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && remaining > 0; i++) {
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
        }
        player.updateInventory();
        return amount - remaining;
    }

    /**
     * Monde de la sélection (et non celui du joueur à l'instant de la confirmation).
     */
    private World worldOf(Player player, Region region) {
        World regionWorld = region.getWorld();
        return regionWorld != null ? regionWorld : BukkitAdapter.adapt(player.getWorld());
    }

    /**
     * Revérifie, au moment de la confirmation, ce qui n'était contrôlé qu'à la saisie de la commande.
     */
    private boolean isActionStillValid(Player player, Region region, Island island) {
        Island current = plugin.getSsbHook().getIsland(player);
        if (current == null || !current.getUniqueId().equals(island.getUniqueId())) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-island"));
            return false;
        }
        if (!plugin.getSsbHook().isSelectionOnIsland(region, island)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("outside-island"));
            return false;
        }
        if (!plugin.getSsbHook().hasPermission(player, island)) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("no-island-perm"));
            return false;
        }
        return true;
    }

    /**
     * Donne les objets au joueur, par piles ; ce qui ne rentre pas tombe à ses pieds.
     *
     * @param items objet (quantité ignorée) et nombre à donner
     */
    private void giveItemsToPlayer(Player player, Map<ItemStack, Integer> items) {
        PlayerInventory inventory = player.getInventory();
        boolean inventoryWasFull = false;

        for (Map.Entry<ItemStack, Integer> entry : items.entrySet()) {
            ItemStack baseItem = entry.getKey();
            if (baseItem == null || baseItem.getType().isAir()) continue;

            int totalAmount = entry.getValue();
            int maxStackSize = Math.max(1, baseItem.getMaxStackSize());
            while (totalAmount > 0) {
                int amountToGive = Math.min(totalAmount, maxStackSize);
                ItemStack stackToGive = baseItem.clone();
                stackToGive.setAmount(amountToGive);
                for (ItemStack leftover : inventory.addItem(stackToGive).values()) {
                    inventoryWasFull = true;
                    player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                }
                totalAmount -= amountToGive;
            }
        }

        if (inventoryWasFull) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("inventory-full"));
        }
    }

    @FunctionalInterface
    private interface StatePredicate {
        boolean test(char state);
    }

    /** Positions retenues par un parcours et l'état lu à chacune. */
    private static final class Positions {
        int[] x;
        int[] y;
        int[] z;
        char[] state;
        int size;

        Positions(int capacity) {
            int c = Math.max(16, capacity);
            x = new int[c];
            y = new int[c];
            z = new int[c];
            state = new char[c];
        }

        void add(int px, int py, int pz, char s) {
            if (size == x.length) {
                int c = size * 2;
                x = Arrays.copyOf(x, c);
                y = Arrays.copyOf(y, c);
                z = Arrays.copyOf(z, c);
                state = Arrays.copyOf(state, c);
            }
            x[size] = px;
            y[size] = py;
            z[size] = pz;
            state[size] = s;
            size++;
        }
    }

    /** Joueur, sélection et île d'une action confirmée. */
    private static final class Job {
        final Player player;
        final UUID playerId;
        final Region region;
        final Island island;
        final UUID islandId;
        final Group group;
        final World world;
        final org.bukkit.World bukkitWorld;

        Job(Player player, Region region, Island island, Group group, World world) {
            this.player = player;
            this.playerId = player.getUniqueId();
            this.region = region;
            this.island = island;
            this.islandId = island.getUniqueId();
            this.group = group;
            this.world = world;
            this.bukkitWorld = BukkitAdapter.adapt(world);
        }
    }
}
