package org.Cry_Legende.sHWorldEditJoueur.utils;

import com.fastasyncworldedit.core.queue.IChunkGet;
import com.sk89q.worldedit.world.World;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lit l'état des blocs d'une sélection section par section (16x16x16).
 *
 * <p>FAWE donne chaque section sous forme d'un tableau de 4096 numéros d'état : lire une position revient à lire une
 * case de tableau, au lieu de traverser toute la chaîne d'extents d'une EditSession à chaque bloc. Hors du thread
 * principal, un chunk non chargé est chargé par FAWE sans bloquer le serveur. Les numéros se lisent dans
 * {@link BlockStates}.
 *
 * <p>Un objet par parcours, utilisé par un seul thread.
 */
public final class SectionReader {

    /** État renvoyé hors des hauteurs du monde (état réservé de FAWE : aucune table ne l'accepte). */
    public static final char OUTSIDE = 0;

    /** Chunks gardés en mémoire : un parcours de cuboïde n'en touche qu'un à la fois, les autres formes un peu plus. */
    private static final int MAX_CACHED_CHUNKS = 64;

    private final World world;
    private final int minY;
    private final int maxY;
    private final int minSection;
    private final int sectionCount;
    private final Map<Long, ChunkData> chunks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ChunkData> eldest) {
            return size() > MAX_CACHED_CHUNKS;
        }
    };
    private long lastKey = Long.MIN_VALUE;
    private ChunkData last;

    public SectionReader(World world) {
        this.world = world;
        this.minY = world.getMinY();
        this.maxY = world.getMaxY();
        this.minSection = minY >> 4;
        this.sectionCount = (maxY >> 4) - minSection + 1;
    }

    /**
     * Numéro d'état FAWE du bloc à cette position, {@link #OUTSIDE} hors des hauteurs du monde.
     */
    public char state(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return OUTSIDE;
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        ChunkData chunk = last;
        if (key != lastKey) {
            chunk = chunks.get(key);
            if (chunk == null) {
                chunk = new ChunkData(world.get(chunkX, chunkZ), sectionCount);
                chunks.put(key, chunk);
            }
            last = chunk;
            lastKey = key;
        }
        int layer = y >> 4;
        int index = layer - minSection;
        char[] section = chunk.sections[index];
        if (section == null) {
            section = chunk.blocks.load(layer);
            chunk.sections[index] = section;
        }
        return section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    private static final class ChunkData {
        final IChunkGet blocks;
        final char[][] sections;

        ChunkData(IChunkGet blocks, int sectionCount) {
            this.blocks = blocks;
            this.sections = new char[sectionCount][];
        }
    }
}
