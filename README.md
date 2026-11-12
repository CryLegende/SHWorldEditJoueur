<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHWorldEditJoueur

*Permet aux joueurs de placer ou couper des blocs en survie avec un coût et des limites.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Permet aux joueurs de placer ou couper des blocs en survie avec un coût et des limites.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `SuperiorSkyblock2`, `FastAsyncWorldEdit`, `Vault`, `SHLogs`

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/place [cancel]` | `/p` | Place des blocs dans votre sélection. | `shworldeditjoueur.use` |
| `/cut [confirm\|cancel]` | — | Coupe les blocs de votre sélection. | `shworldeditjoueur.use` |
| `/shworldeditjoueur reload` | `/shwe` | Recharge `config.yml` et `messages.yml` (console comprise). Un fichier YAML invalide laisse l'ancienne configuration en place et affiche le fichier et la ligne de l'erreur. Les actions en attente ne changent pas. | `shworldeditjoueur.admin.reload` |
| `/shworldeditjoueur info [joueur]` | `/shwe` | Palier appliqué à un joueur : groupe, limite de blocs, prix, action en attente. | `shworldeditjoueur.admin.info` |

## Fonctionnement

- La sélection est lue hors du thread principal, au comptage de la commande comme à la confirmation : une grande sélection ne fait pas ramer le serveur.
- La modification se fait ensuite en un seul tick : chaque bloc est relu, l'argent et les objets sont pris, les blocs sont posés ou retirés, puis relus. Le joueur paie et reçoit exactement ce qui a changé. Un bloc cassé ou posé entre la lecture et la confirmation est laissé de côté.
- Un seul `/place` ou `/cut` à la fois par joueur et par île.
- `/place` ne pose que des blocs ordinaires (pas d'objet nommé, enchanté, rempli ou propre à un plugin) et refuse les blocs de deux de haut (porte, lit, grande fleur).
- `/cut` ne coupe jamais l'air, les blocs incassables en survie, les blocs de `banned-blocks.cut` ni ceux qui portent une métadonnée de `protected-metadata`. Il laisse en place les blocs qui gardent quelque chose : conteneur non vide, pupitre avec livre, juke-box avec disque, ruche habitée, bannière à motifs, feu de camp garni.
- Objets rendus par `/cut` : `replace-give`, sinon l'objet du bloc. Une porte ou une grande fleur rend un objet pour ses deux moitiés, une dalle double deux dalles, des bougies, cornichons de mer, œufs de tortue, pétales ou couches de neige leur nombre, un pot garni le pot et la plante.
- Le compte des blocs de l'île (SuperiorSkyblock2) suit son propre calcul : les deux moitiés d'une porte comptent, une dalle double compte pour deux.

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shworldeditjoueur.use` | Permet d'utiliser `/place` et `/cut`. | `true` |
| `shworldeditjoueur.group.default` | Palier par défaut. | `true` |
| `shworldeditjoueur.group.vip` | Palier VIP. | `op` |
| `shworldeditjoueur.group.vip+` | Palier VIP+. | `op` |
| `shworldeditjoueur.group.vipelite` | Palier VIP Elite. | `op` |
| `shworldeditjoueur.group.<groupe>` | Un nœud par groupe de la section `groups` de `config.yml` (exemple : un groupe `youtube` demande `shworldeditjoueur.group.youtube`, à accorder dans LuckPerms). Le groupe de plus haute priorité dont le joueur a le nœud s'applique. | `op` (non déclaré) |
| `shworldeditjoueur.admin` | Toutes les sous-commandes de `/shworldeditjoueur`. | `op` |
| `shworldeditjoueur.admin.reload` | `/shworldeditjoueur reload`. | `op` |
| `shworldeditjoueur.admin.info` | `/shworldeditjoueur info`. | `op` |

Le privilège d'île SuperiorSkyblock2 `SHWORLDEDIT_USE` (réglé par île dans `/is permissions`) n'est pas une permission de serveur : il reste inchangé.

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHWorldEditJoueur/
├── <a href="src/main/resources/config.yml">config.yml</a>
└── <a href="src/main/resources/messages.yml">messages.yml</a>
</pre>

Les `/place` et `/cut` effectués sont écrits dans le fichier `actions` du dossier `SHWorldEditJoueur` de SHLogs (`plugins/SHLogs/`). Sans SHLogs, les lignes passent dans la console.

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
