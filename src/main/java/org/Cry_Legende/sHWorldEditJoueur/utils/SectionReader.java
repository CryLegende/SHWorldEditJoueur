package org.Cry_Legende.sHWorldEditJoueur.utils;

import com.fastasyncworldedit.core.queue.IChunkGet;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypesCache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lit le type des blocs d'une sélection hors du thread principal, section par section (16x16x16).
 *
 * <p>FAWE donne chaque section sous forme d'un tableau de 4096 états de bloc : tester une position revient à lire
 * une case de tableau, au lieu de traverser toute la chaîne d'extents d'une EditSession à chaque bloc. Un chunk non
 * chargé est chargé par FAWE sans bloquer le thread principal.
 *
 * <p>Un objet par parcours, utilisé par un seul thread.
 */
public final class SectionReader {

    /** Chunks gardés en mémoire : un parcours de cuboïde n'en touche qu'un à la fois, les autres formes un peu plus. */
    private static final int MAX_CACHED_CHUNKS = 64;

    private final World world;
    private final boolean[] accepted;
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

    /**
     * @param world    monde FAWE à lire
     * @param accepted états acceptés, indexés par numéro d'état FAWE ({@link #acceptedStates})
     */
    public SectionReader(World world, boolean[] accepted) {
        this.world = world;
        this.accepted = accepted;
        this.minY = world.getMinY();
        this.maxY = world.getMaxY();
        this.minSection = minY >> 4;
        this.sectionCount = (maxY >> 4) - minSection + 1;
    }

    /**
     * Table des états dont le type est dans {@code types}, indexée par numéro d'état FAWE.
     */
    public static boolean[] acceptedStates(Set<BlockType> types) {
        BlockState[] states = BlockTypesCache.states;
        boolean[] accepted = new boolean[states.length];
        for (int i = 0; i < states.length; i++) {
            BlockState state = states[i];
            accepted[i] = state != null && types.contains(state.getBlockType());
        }
        return accepted;
    }

    /**
     * Vrai si le bloc à cette position est d'un type accepté. Hors des hauteurs du monde : faux.
     */
    public boolean matches(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return false;
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
        char state = section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
        return state < accepted.length && accepted[state];
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
