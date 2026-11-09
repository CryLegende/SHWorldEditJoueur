package org.Cry_Legende.sHWorldEditJoueur.utils;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Type des blocs d'une sélection, lu dans une copie de chaque chunk (ChunkSnapshot).
 * EditSession.getBlock de FAWE traverse toute sa chaîne d'extents à chaque position : sur une grande
 * sélection, /place et /cut passaient 100 à 400 ms rien qu'à lire les blocs sur le thread principal.
 * Un chunk non chargé n'est jamais chargé ici : {@link #type} renvoie null et l'appelant lit le bloc autrement.
 */
public final class BlockTypeReader {

    private final World world;
    private final int minHeight;
    private final int maxHeight;
    private final Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
    private final Set<Long> unloaded = new HashSet<>();

    public BlockTypeReader(World world) {
        this.world = world;
        this.minHeight = world.getMinHeight();
        this.maxHeight = world.getMaxHeight();
    }

    public Material type(int x, int y, int z) {
        if (y < minHeight || y >= maxHeight) {
            return null;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        ChunkSnapshot snapshot = snapshots.get(key);
        if (snapshot == null) {
            if (unloaded.contains(key)) {
                return null;
            }
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                unloaded.add(key);
                return null;
            }
            snapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false);
            snapshots.put(key, snapshot);
        }
        return snapshot.getBlockType(x & 15, y, z & 15);
    }
}
