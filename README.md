<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHWorldEditJoueur

*Permet aux joueurs de placer ou couper des blocs en survie avec un coût et des limites.*

<img src="https://img.shields.io/badge/Paper-1.20.2-f2a900?style=for-the-badge" alt="Paper 1.20.2"> <img src="https://img.shields.io/badge/Java-17-e76f00?style=for-the-badge" alt="Java 17"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Permet aux joueurs de placer ou couper des blocs en survie avec un coût et des limites.

Utilisé sur : **Opale**.

## Dépendances

- Optionnelles : `SuperiorSkyblock2`, `FastAsyncWorldEdit`, `Vault`

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/place` | `/p` | Place des blocs dans votre sélection. | — |
| `/cut` | `/c` | Coupe les blocs de votre sélection. | — |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shwe.use` | Permet d'utiliser les commandes de base du plugin. | `true` |
| `shwe.group.default` | Groupe par défaut pour SHWorldEditJoueur. | `true` |
| `shwe.group.vip` | Groupe VIP pour SHWorldEditJoueur. | `op` |
| `shwe.group.vip+` | Groupe VIP+ pour SHWorldEditJoueur. | `op` |
| `shwe.group.vipelite` | Groupe VIP Elite pour SHWorldEditJoueur. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`) :

- `config.yml`

## Compilation

Java 17 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
