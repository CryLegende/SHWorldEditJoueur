package org.Cry_Legende.sHWorldEditJoueur.managers;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.key.Key;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.Cry_Legende.sHWorldEditJoueur.SHWorldEditJoueur;
import org.Cry_Legende.sHWorldEditJoueur.config.ConfigManager;
import org.Cry_Legende.sHWorldEditJoueur.config.Group;
import org.Cry_Legende.sHWorldEditJoueur.utils.BlockTypeReader;
import org.Cry_Legende.sHWorldEditJoueur.utils.Common;
import org.Cry_Legende.sHWorldEditJoueur.utils.SectionReader;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class WorldEditManager {

    /** Délai laissé aux /place en cours quand le plugin s'arrête. */
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final SHWorldEditJoueur plugin;

    /**
     * États de bloc où /place peut poser (air, eau, lave, tous niveaux), indexés par numéro d'état FAWE.
     */
    private final boolean[] replaceableStates;

    /**
     * Cache BlockType -> Material, pour éviter une conversion par bloc dans /cut.
     */
    private final Map<BlockType, Material> materialCache = new HashMap<>();

    /**
     * Lecture de la sélection et pose des blocs de /place, hors du thread principal. Sur le thread principal ne
     * restent que l'argent, l'inventaire et SuperiorSkyblock2.
     */
    private final ExecutorService worker;

    /** Joueurs et îles dont un /place est en cours d'exécution (thread principal uniquement). */
    private final Set<UUID> busyPlayers = new HashSet<>();
    private final Set<UUID> busyIslands = new HashSet<>();

    /** Fins de /place déjà payés : exécutées à l'arrêt du plugin si le planificateur ne l'a pas fait. */
    private final Set<MainTask> pendingFinishes = ConcurrentHashMap.newKeySet();

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
        this.replaceableStates = SectionReader.acceptedStates(types);

        AtomicInteger threadNumber = new AtomicInteger();
        this.worker = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "SHWorldEditJoueur-place-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Attend la fin des /place en cours et applique ceux déjà payés. Appelé par onDisable.
     */
    public void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Des /place étaient encore en cours à l'arrêt du plugin.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (MainTask task : new ArrayList<>(pendingFinishes)) {
            try {
                task.run();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Erreur en terminant un /place à l'arrêt du plugin", e);
            }
        }
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
     * Vrai si le joueur, ou son île, a un /place en cours d'exécution : un second lancé en même temps viserait les
     * mêmes emplacements vides et ferait payer deux fois les mêmes blocs.
     */
    public boolean isBusy(Player player, Island island) {
        return busyPlayers.contains(player.getUniqueId())
            || (island != null && busyIslands.contains(island.getUniqueId()));
    }

    /**
     * Parcourt la région dans l'ordre de WorldEdit et retient les positions où /place peut poser : air, eau ou lave,
     * sans métadonnée protégée ({@code settings.protected-metadata}). S'arrête dès {@code limit} positions trouvées.
     * Tourne hors du thread principal.
     *
     * @param out reçoit les positions trouvées (null : comptage seul)
     * @return le nombre de positions trouvées, borné par {@code limit}
     */
    private int scan(World world, org.bukkit.World bukkitWorld, Region region, int limit, List<BlockVector3> out) {
        if (limit <= 0) {
            return 0;
        }
        SectionReader reader = new SectionReader(world, replaceableStates);
        ConfigManager config = plugin.getConfigManager();
        int found = 0;
        for (BlockVector3 pt : region) {
            int x = pt.x();
            int y = pt.y();
            int z = pt.z();
            if (!reader.matches(x, y, z) || config.isProtected(bukkitWorld, x, y, z)) {
                continue;
            }
            if (out != null) {
                out.add(BlockVector3.at(x, y, z));
            }
            if (++found >= limit) {
                break;
            }
        }
        return found;
    }

    /**
     * Compte, hors du thread principal, les emplacements où /place peut poser, en s'arrêtant à {@code limit} : la
     * limite du groupe plafonne de toute façon ce que le joueur peut poser. {@code onCount} est appelé sur le thread
     * principal si le joueur est encore en ligne.
     */
    public void countPlaceableAsync(Player player, World world, Region region, int limit, IntConsumer onCount) {
        Region copy = region.clone();
        org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        async(() -> scan(world, bukkitWorld, copy, limit, null), (count, error) -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE, "Erreur lors du comptage des blocs remplaçables", error);
                Common.sendMessage(player, plugin.getConfigManager().getMessage("count-error")
                    .replace("%error%", String.valueOf(error.getMessage())));
                return;
            }
            onCount.accept(count);
        }, false);
    }

    /**
     * /place confirmé par le clic sur un bloc de l'inventaire.
     *
     * <ol>
     *   <li>thread principal : vérifications, budget (inventaire et limite du groupe) ;</li>
     *   <li>hors thread principal : recherche des emplacements libres, arrêt au budget ;</li>
     *   <li>thread principal : retrait de l'argent et des blocs pour le nombre exact d'emplacements trouvés ;</li>
     *   <li>hors thread principal : pose par FAWE ;</li>
     *   <li>thread principal : SuperiorSkyblock2, message, son, journal.</li>
     * </ol>
     * Rien n'est payé avant d'avoir trouvé les emplacements, donc aucun remboursement hors erreur de FAWE.
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

        if (plugin.getConfigManager().isBlockBanned("place", template.getType())) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("banned-block-place")
                .replace("%type%", prettyName(template.getType())));
            return;
        }

        // Même critère de comparaison que pour le retrait : sinon des items différents du même matériau
        // (nommés, avec lore...) seraient comptés mais jamais retirés, et les blocs seraient posés gratuitement.
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

        World world = worldOf(player, region);
        org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
        PlaceJob job = new PlaceJob(player, region, island, template, group, world, bukkitWorld,
            BukkitAdapter.adapt(template.getType().createBlockData()), cappedByPermission);

        busyPlayers.add(job.playerId);
        busyIslands.add(job.islandId);
        List<BlockVector3> positions = new ArrayList<>(Math.min(budget, 4096));
        async(() -> scan(world, bukkitWorld, region, budget, positions), (found, error) -> {
            if (error != null) {
                release(job);
                plugin.getLogger().log(Level.SEVERE, "Erreur pendant la lecture de la sélection de /place pour " + player.getName(), error);
                if (player.isOnline()) {
                    Common.sendMessage(player, plugin.getConfigManager().getMessage("count-error")
                        .replace("%error%", String.valueOf(error.getMessage())));
                }
                return;
            }
            chargeAndPlace(job, positions);
        }, false);
    }

    /**
     * Thread principal : fait payer les emplacements trouvés puis lance la pose. Libère le joueur et l'île sur
     * toute sortie anticipée.
     */
    private void chargeAndPlace(PlaceJob job, List<BlockVector3> positions) {
        Player player = job.player;
        boolean launched = false;
        try {
            // Rien n'a encore été payé : un joueur parti ou une île changée entre-temps arrête tout simplement l'action
            if (!player.isOnline() || !isActionStillValid(player, job.region, job.island)) {
                return;
            }
            if (positions.isEmpty()) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-placeable-blocks-in-selection"));
                return;
            }

            // L'inventaire a pu changer pendant la lecture de la sélection
            int available = countItems(player, job.template);
            if (available == 0) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-blocks")
                    .replace("%type%", prettyName(job.template.getType())));
                return;
            }
            int amount = Math.min(positions.size(), available);
            double price = job.group.getPlacePrice();
            double cost = amount * price;

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
            int removed = removeItems(player, job.template, amount);
            if (removed < amount) {
                plugin.getVaultHook().depositPlayer(player, (amount - removed) * price);
                amount = removed;
                cost = amount * price;
                if (amount <= 0) {
                    Common.sendMessage(player, plugin.getConfigManager().getMessage("no-blocks")
                        .replace("%type%", prettyName(job.template.getType())));
                    return;
                }
            }

            List<BlockVector3> toPlace = new ArrayList<>(positions.subList(0, amount));
            int placed = amount;
            double charged = cost;
            launched = true;
            async(() -> {
                setBlocks(job.world, toPlace, job.blockToPlace);
                return null;
            }, (ignored, error) -> finishPlace(job, toPlace, placed, charged, error), true);
        } finally {
            if (!launched) {
                release(job);
            }
        }
    }

    /**
     * Hors thread principal : pose les blocs par FAWE, qui regroupe les écritures par chunk.
     */
    private static void setBlocks(World world, List<BlockVector3> positions, BlockState block) throws WorldEditException {
        try (EditSession session = WorldEdit.getInstance().newEditSession(world)) {
            for (BlockVector3 pt : positions) {
                session.setBlock(pt, block);
            }
            Operations.complete(session.commit());
        }
    }

    /**
     * Thread principal, après la pose : SuperiorSkyblock2, message, son, journal. Sur erreur de FAWE, tout est rendu.
     */
    private void finishPlace(PlaceJob job, List<BlockVector3> positions, int placed, double charged, Throwable error) {
        release(job);
        Player player = job.player;
        if (error != null) {
            refund(player, job.template, placed, charged);
            Common.sendMessage(player, plugin.getConfigManager().getMessage("worldedit-error")
                .replace("%error%", String.valueOf(error.getMessage())));
            plugin.getLogger().log(Level.SEVERE, "Erreur WorldEdit pendant /place pour " + player.getName(), error);
            return;
        }

        // N'avertir que si la limite du groupe a réellement borné la pose : le joueur
        // avait de quoi aller plus loin et la sélection offrait la place.
        int maxAllowedByPerm = job.group.getMaxBlocks();
        if (job.cappedByPermission && placed >= maxAllowedByPerm) {
            Common.sendMessage(player, plugin.getConfigManager().getMessage("limit-exceeded")
                .replace("%max%", String.valueOf(maxAllowedByPerm)));
        }

        Key blockKey = SuperiorSkyblockAPI.getKeys().getKey(job.template);
        if (blockKey != null) {
            job.island.handleBlockPlace(blockKey, placed, true);
        }
        Set<Long> touchedChunks = new HashSet<>();
        for (BlockVector3 pt : positions) {
            touchedChunks.add(((long) (pt.x() >> 4) << 32) | ((pt.z() >> 4) & 0xFFFFFFFFL));
        }
        markChunksDirty(job.island, job.bukkitWorld, touchedChunks);

        Common.sendMessage(player, plugin.getConfigManager().getMessage("placed-success")
            .replace("%amount%", String.valueOf(placed))
            .replace("%type%", prettyName(job.template.getType()))
            .replace("%price%", plugin.getVaultHook().format(charged)));
        if (player.isOnline()) {
            player.playSound(player.getLocation(), plugin.getConfigManager().getBuildSound(), 1.0f, 1.0f);
        }

        plugin.getLogManager().logPlace(player, placed, job.template.getType(), charged);
    }

    private void release(PlaceJob job) {
        busyPlayers.remove(job.playerId);
        busyIslands.remove(job.islandId);
    }

    /**
     * Lance {@code work} hors du thread principal puis {@code onMain} sur le thread principal avec son résultat ou
     * son erreur. {@code critical} : la suite fait suite à un paiement et doit s'exécuter même si le plugin s'arrête
     * entre-temps ({@link #shutdown}).
     */
    private <T> void async(Callable<T> work, BiConsumer<T, Throwable> onMain, boolean critical) {
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
                runOnMain(() -> onMain.accept(finalResult, finalError), critical);
            });
        } catch (RejectedExecutionException e) {
            // Plugin en cours d'arrêt : on est déjà sur le thread principal
            onMain.accept(null, e);
        }
    }

    private void runOnMain(Runnable task, boolean critical) {
        Runnable toRun = task;
        if (critical) {
            MainTask once = new MainTask(task);
            pendingFinishes.add(once);
            toRun = once;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, toRun);
        } catch (IllegalPluginAccessException e) {
            // Plugin désactivé : shutdown() exécute les suites critiques, les autres n'avaient rien fait payer
        }
    }

    /** Suite exécutée une seule fois, par le planificateur ou par shutdown(). */
    private final class MainTask implements Runnable {
        private final AtomicBoolean done = new AtomicBoolean();
        private final Runnable task;

        MainTask(Runnable task) {
            this.task = task;
        }

        @Override
        public void run() {
            if (done.compareAndSet(false, true)) {
                pendingFinishes.remove(this);
                task.run();
            }
        }
    }

    /** Données d'un /place entre ses étapes. */
    private static final class PlaceJob {
        final Player player;
        final UUID playerId;
        final Region region;
        final Island island;
        final UUID islandId;
        final ItemStack template;
        final Group group;
        final World world;
        final org.bukkit.World bukkitWorld;
        final BlockState blockToPlace;
        final boolean cappedByPermission;

        PlaceJob(Player player, Region region, Island island, ItemStack template, Group group, World world,
                 org.bukkit.World bukkitWorld, BlockState blockToPlace, boolean cappedByPermission) {
            this.player = player;
            this.playerId = player.getUniqueId();
            this.region = region;
            this.island = island;
            this.islandId = island.getUniqueId();
            this.template = template;
            this.group = group;
            this.world = world;
            this.bukkitWorld = bukkitWorld;
            this.blockToPlace = blockToPlace;
            this.cappedByPermission = cappedByPermission;
        }
    }

    /**
     * Le calcul du niveau (/is level) ne lit que les chunks que SuperiorSkyblock2 sait occupés : un /place dans un
     * chunk encore vide doit l'y ajouter, sinon le prochain calcul ignore ces blocs.
     */
    private static void markChunksDirty(Island island, org.bukkit.World world, Set<Long> chunks) {
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

    public void executeCut(Player player, Region region, Island island) {
        try {
            // L'état du joueur a pu changer pendant le délai de confirmation (île quittée, droits retirés...)
            if (!isActionStillValid(player, region, island)) {
                return;
            }
            // Un /place en cours sur l'île pose ses blocs en ce moment : on attend qu'il ait fini
            if (isBusy(player, island)) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("action-in-progress"));
                return;
            }

            Group group = plugin.getConfigManager().getPlayerGroup(player);
            World world = worldOf(player, region);

            // Lecture directe de la région. L'ancienne version recopiait d'abord
            // toute la sélection dans un BlockArrayClipboard via ForwardExtentCopy
            // (une passe complète et une allocation dimensionnée sur le volume de la
            // région) avant de la relire bloc par bloc.
            // On ne fait que compter par matériau pendant le parcours : drops et clés SSB2 se calculent ensuite une fois par matériau.
            // Les positions retenues sont gardées : le retrait touche exactement les blocs comptés et payés.
            Map<Material, Integer> cutCounts = new EnumMap<>(Material.class);
            List<BlockVector3> toCut = new ArrayList<>();
            org.bukkit.World bukkitWorld = BukkitAdapter.adapt(world);
            BlockTypeReader reader = new BlockTypeReader(bukkitWorld);
            try (EditSession readSession = WorldEdit.getInstance().newEditSession(world)) {
                for (BlockVector3 pt : region) {
                    try {
                        Material material = reader.type(pt.getX(), pt.getY(), pt.getZ());
                        if (material == null) {
                            // Chunk non chargé : lecture par FAWE comme avant
                            BlockType blockType = readSession.getBlock(pt).getBlockType();
                            if (blockType.getMaterial().isAir()) continue;
                            material = toMaterial(blockType);
                            if (material == null) continue;
                        } else if (material.isAir()) {
                            continue;
                        }
                        if (plugin.getConfigManager().isBlockBanned("cut", material)) continue;
                        if (plugin.getConfigManager().isProtected(bukkitWorld, pt.getX(), pt.getY(), pt.getZ())) continue;

                        cutCounts.merge(material, 1, Integer::sum);
                        if (toCut.size() <= group.getMaxBlocks()) {
                            toCut.add(BlockVector3.at(pt.getX(), pt.getY(), pt.getZ()));
                        }
                    } catch (Exception loopException) {
                        plugin.getLogger().log(Level.SEVERE, "Erreur dans la boucle d'analyse du bloc à " + pt, loopException);
                    }
                }
            }

            int blocksToCutCount = 0;
            Map<ItemStack, Integer> collectedItems = new HashMap<>();
            Map<Key, Integer> blocksToRemoveFromIsland = new HashMap<>();
            for (Map.Entry<Material, Integer> cut : cutCounts.entrySet()) {
                Material material = cut.getKey();
                int amount = cut.getValue();
                blocksToCutCount += amount;

                ItemStack drop = getDropFromBlock(material);
                if (drop != null) collectedItems.merge(drop, amount, Integer::sum);

                Key blockKey = SuperiorSkyblockAPI.getKeys().getKey(material, (short) 0);
                if (blockKey != null) {
                    blocksToRemoveFromIsland.merge(blockKey, amount, Integer::sum);
                }
            }

            if (blocksToCutCount == 0) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("no-cuttable-blocks-in-selection"));
                return;
            }

            // La limite du groupe a été contrôlée à la saisie de la commande ; le contenu a pu grossir depuis
            if (blocksToCutCount > group.getMaxBlocks()) {
                Common.sendMessage(player, plugin.getConfigManager().getMessage("selection-too-large")
                    .replace("%max%", String.valueOf(group.getMaxBlocks()))
                    .replace("%size%", String.valueOf(blocksToCutCount)));
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

            // Seules les positions comptées sont vidées : blocs interdits (tous états, coffre orienté compris)
            // et blocs protégés par métadonnée restent en place.
            try (EditSession writeSession = WorldEdit.getInstance().newEditSession(world)) {
                BlockState air = BlockTypes.AIR.getDefaultState();
                for (BlockVector3 pt : toCut) {
                    writeSession.setBlock(pt, air);
                }
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
            plugin.getLogger().log(Level.SEVERE, "Merci de rapporter l'erreur suivante à votre développeur:", e);
            plugin.getLogger().log(Level.SEVERE, "======================================================");
            Common.sendMessage(player, plugin.getConfigManager().getMessage("internal-error"));
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

        if (!player.isOnline()) {
            // Joueur parti pendant un /place en échec : son inventaire est déjà sauvegardé, les objets seraient perdus
            plugin.getLogger().warning(player.getName() + " s'est déconnecté avant le remboursement de /place : "
                + amount + " " + template.getType() + " à lui rendre (l'argent est rendu).");
            return;
        }

        int maxStackSize = Math.max(1, template.getType().getMaxStackSize());
        int remaining = amount;
        while (remaining > 0) {
            int give = Math.min(remaining, maxStackSize);
            ItemStack stack = template.clone(); // conserve le nom, le lore et les données de l'item d'origine
            stack.setAmount(give);
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

    private ItemStack getDropFromBlock(Material material) {
        ItemStack customDrop = plugin.getConfigManager().getCustomDrop(material);
        if (customDrop != null) {
            return customDrop.getType() == Material.AIR ? null : customDrop.clone();
        }
        if (!material.isItem()) {
            return null; // Eau, lave, torche murale... : pas d'item correspondant, rien à donner
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
