package org.Cry_Legende.sHWorldEditJoueur.config;

/**
 * Représente un groupe de configuration pour SHWorldEditJoueur.
 * Contient les limites et les coûts spécifiques à un groupe de joueurs.
 * Cet objet est immuable une fois créé.
 */
public class Group {

    private final String name;
    private final int priority;
    private final int maxBlocks;
    private final double placePrice;
    private final double cutPrice;

    /**
     * Construit un nouvel objet Group.
     *
     * @param name       Le nom du groupe (ex: "default", "vip").
     * @param priority   La priorité pour la sélection du groupe (le plus élevé est prioritaire).
     * @param maxBlocks  Le nombre maximum de blocs pour une opération.
     * @param placePrice Le coût par bloc pour l'opération /place.
     * @param cutPrice   Le coût par bloc pour l'opération /cut.
     */
    public Group(String name, int priority, int maxBlocks, double placePrice, double cutPrice) {
        this.name = name;
        this.priority = priority;
        this.maxBlocks = maxBlocks;
        this.placePrice = placePrice;
        this.cutPrice = cutPrice;
    }

    /**
     * @return Le nom du groupe, correspondant à la clé dans le fichier de configuration.
     */
    public String getName() {
        return name;
    }

    /**
     * La priorité est utilisée pour déterminer quel groupe appliquer si un joueur
     * a la permission pour plusieurs groupes. Le groupe avec la priorité la plus élevée sera choisi.
     *
     * @return La priorité numérique du groupe.
     */
    public int getPriority() {
        return priority;
    }

    /**
     * @return La limite maximale de blocs que les joueurs de ce groupe peuvent
     * modifier en une seule opération.
     */
    public int getMaxBlocks() {
        return maxBlocks;
    }

    /**
     * @return Le prix en monnaie (via Vault) pour chaque bloc placé avec /place.
     */
    public double getPlacePrice() {
        return placePrice;
    }

    /**
     * @return Le prix en monnaie (via Vault) pour chaque bloc coupé avec /cut.
     */
    public double getCutPrice() {
        return cutPrice;
    }
}
