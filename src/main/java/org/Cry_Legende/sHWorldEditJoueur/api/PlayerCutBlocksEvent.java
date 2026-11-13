package org.Cry_Legende.sHWorldEditJoueur.api;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocs que /cut vient de retirer, sur le thread principal, juste avant que le joueur reçoive les objets.
 * <p>
 * Un plugin qui garde des données sur un de ces blocs (ailleurs que dans le bloc lui-même) peut remplacer ce que le
 * joueur reçoit pour ce bloc, par exemple le même bloc avec ses données. Les blocs sont déjà retirés et payés :
 * l'événement ne s'annule pas.
 */
public final class PlayerCutBlocksEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Bloc retiré : position et matériau qu'il avait. */
    public record CutBlock(int x, int y, int z, Material material) {
    }

    private final World world;
    private final List<CutBlock> blocks;
    private final Map<Integer, List<ItemStack>> drops = new HashMap<>();

    public PlayerCutBlocksEvent(Player player, World world, List<CutBlock> blocks) {
        super(player);
        this.world = world;
        this.blocks = Collections.unmodifiableList(blocks);
    }

    public World getWorld() {
        return world;
    }

    public List<CutBlock> getBlocks() {
        return blocks;
    }

    /** Le joueur reçoit {@code items} à la place des objets normaux du bloc n° {@code index} (liste vide : rien). */
    public void setDrops(int index, List<ItemStack> items) {
        if (index < 0 || index >= blocks.size()) {
            throw new IndexOutOfBoundsException(index);
        }
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                copy.add(item.clone());
            }
        }
        drops.put(index, copy);
    }

    /** Objets donnés à la place de ceux du bloc n° {@code index}, ou null s'ils ne sont pas remplacés. */
    public List<ItemStack> getDrops(int index) {
        return drops.get(index);
    }

    /** Vrai si un plugin écoute l'événement : sans écouteur, /cut ne le construit pas. */
    public static boolean hasListeners() {
        return HANDLERS.getRegisteredListeners().length > 0;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
