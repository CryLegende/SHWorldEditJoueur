package org.Cry_Legende.sHWorldEditJoueur.utils;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.block.BlockTypesCache;
import org.bukkit.Material;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Tables indexées par numéro d'état de bloc FAWE (celui des tableaux de {@link SectionReader}), construites une fois
 * au démarrage. Les parcours hors du thread principal n'y font que des lectures de tableau.
 */
public final class BlockStates {

    /** Taille des tables : tout numéro d'état tient dans un char, aucune lecture ne peut sortir du tableau. */
    private static final int SIZE = 1 << 16;

    /** Air, eau ou lave (tous niveaux) : /place peut y poser. */
    public final boolean[] replaceable = new boolean[SIZE];
    /** Matériau Bukkit de l'état (null : état inconnu). */
    public final Material[] material = new Material[SIZE];
    /**
     * Objet qui pose ce bloc (torche murale : torche, carottes : carotte, varech : varech...), null s'il n'y en a pas.
     * Sert aux objets rendus par /cut et aux interdictions de banned-blocks.cut, écrites avec des noms d'objets.
     */
    public final Material[] item = new Material[SIZE];
    /** Air, ou bloc qu'un joueur ne peut pas casser en survie (bedrock, barrière, cadre de portail de l'End...). */
    public final boolean[] neverCut = new boolean[SIZE];
    /** Le bloc porte une entité de bloc : contenu éventuel à vérifier avant /cut. */
    public final boolean[] tile = new boolean[SIZE];
    /**
     * Objets rendus par /cut pour ce bloc : 0 pour la moitié haute d'une porte ou d'une grande plante et la tête
     * d'un lit (l'autre moitié rend l'objet), 2 pour une dalle double, le nombre de bougies, cornichons de mer, œufs,
     * pétales ou couches de neige, 1 sinon.
     */
    public final byte[] cutItems = new byte[SIZE];
    /** Blocs retirés du compte de l'île par SuperiorSkyblock2 : 2 pour une dalle double, 1 sinon (comme son calcul). */
    public final byte[] islandAmount = new byte[SIZE];
    /**
     * Bloc en deux parties (porte, grande plante, lit) : décalage vers l'autre moitié. {@link #twoPart} faux sinon.
     */
    public final boolean[] twoPart = new boolean[SIZE];
    public final byte[] partnerDx = new byte[SIZE];
    public final byte[] partnerDy = new byte[SIZE];
    public final byte[] partnerDz = new byte[SIZE];

    /** Objet qui pose chaque bloc, par matériau de bloc. */
    private final Map<Material, Material> itemOfBlock = new EnumMap<>(Material.class);

    public BlockStates(Logger logger) {
        BlockState[] states = BlockTypesCache.states;
        Set<BlockType> replaceableTypes = Set.of(BlockTypes.AIR, BlockTypes.CAVE_AIR, BlockTypes.VOID_AIR,
            BlockTypes.WATER, BlockTypes.LAVA);
        Map<BlockType, Material> materials = new HashMap<>();
        Map<BlockType, Material> items = new HashMap<>();
        int unknown = 0;
        for (int i = 0; i < Math.min(states.length, SIZE); i++) {
            BlockState state = states[i];
            // État réservé de FAWE (numéro 0, hors du monde) : aucun matériau, jamais coupé ni rempli
            if (state == null || state.getBlockType() == BlockTypes.__RESERVED__) {
                neverCut[i] = true;
                continue;
            }
            BlockType type = state.getBlockType();
            Material m = materials.computeIfAbsent(type, BlockStates::toMaterial);
            material[i] = m;
            replaceable[i] = replaceableTypes.contains(type);
            if (m == null) {
                neverCut[i] = true;
                unknown++;
                continue;
            }
            Material placing = items.computeIfAbsent(type, t -> itemOf(t, m));
            item[i] = placing;
            if (placing != null) {
                itemOfBlock.put(m, placing);
            }
            neverCut[i] = m.isAir() || hardness(m) < 0;
            tile[i] = type.getMaterial().isTile();
            cutItems[i] = (byte) cutItems(type, state);
            islandAmount[i] = (byte) (isDoubleSlab(type, state) ? 2 : 1);
            partner(i, type, state);
        }
        if (unknown > 0) {
            logger.warning(unknown + " états de bloc FAWE sans matériau Bukkit : /cut ne les coupera pas.");
        }
    }

    /** Objet qui pose ce bloc (le bloc lui-même s'il est un objet), null s'il n'y en a pas. */
    public Material itemOf(Material block) {
        return itemOfBlock.get(block);
    }

    private static Material toMaterial(BlockType type) {
        try {
            return BukkitAdapter.adapt(type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Material itemOf(BlockType type, Material block) {
        if (block.isItem()) {
            return block;
        }
        try {
            Material placing = BukkitAdapter.adapt(type.getDefaultState()).getPlacementMaterial();
            return placing != null && placing.isItem() && !placing.isAir() ? placing : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static float hardness(Material m) {
        return m.isBlock() ? m.getHardness() : -1;
    }

    private static int cutItems(BlockType type, BlockState state) {
        // Portes, grandes fleurs, hautes herbes, plante carnivore, petite feuille tombante : la moitié haute ne rend rien.
        // Les escaliers et trappes ont aussi « half », mais avec top/bottom : jamais « upper ».
        if ("upper".equals(value(type, state, "half"))) return 0;
        if ("head".equals(value(type, state, "part"))) return 0;
        if (isDoubleSlab(type, state)) return 2;
        for (String counted : new String[]{"candles", "pickles", "eggs", "flower_amount", "segment_amount"}) {
            Object v = value(type, state, counted);
            if (v instanceof Integer amount) return amount;
        }
        if (type == BlockTypes.SNOW && value(type, state, "layers") instanceof Integer layers) return layers;
        return 1;
    }

    /** Moitié haute ou basse (« half » upper/lower), pied ou tête de lit (« part » foot/head, selon « facing »). */
    private void partner(int i, BlockType type, BlockState state) {
        Object half = value(type, state, "half");
        if ("upper".equals(half) || "lower".equals(half)) {
            twoPart[i] = true;
            partnerDy[i] = (byte) ("upper".equals(half) ? -1 : 1);
            return;
        }
        Object part = value(type, state, "part");
        Object facing = value(type, state, "facing");
        if (("foot".equals(part) || "head".equals(part)) && facing instanceof String f) {
            int dx = switch (f) { case "east" -> 1; case "west" -> -1; default -> 0; };
            int dz = switch (f) { case "south" -> 1; case "north" -> -1; default -> 0; };
            if (dx == 0 && dz == 0) return;
            int sign = "foot".equals(part) ? 1 : -1;   // la tête est devant le pied, dans le sens de facing
            twoPart[i] = true;
            partnerDx[i] = (byte) (dx * sign);
            partnerDz[i] = (byte) (dz * sign);
        }
    }

    private static boolean isDoubleSlab(BlockType type, BlockState state) {
        return type.id().endsWith("_slab") && "double".equals(value(type, state, "type"));
    }

    private static Object value(BlockType type, BlockState state, String property) {
        Property<?> p = type.getPropertyMap().get(property);
        if (p == null) return null;
        Object v = state.getState(p);
        return v instanceof String s ? s.toLowerCase() : v;
    }
}
