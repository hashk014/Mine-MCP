# Mine MCP — guide français

**Mine MCP** relie une IA à ton client Minecraft Java via le protocole MCP. Le même JAR Fabric contient les adaptations de **44 versions stables, de 1.14.4 à 26.3**.

C'est une alpha : les versions ciblées, les symboles vérifiés, les démarrages réels et les essais de gameplay sont distingués dans [la matrice](COMPATIBILITY.md). Toutes les fonctions n'ont pas été jouées sur toutes les versions. Forge/NeoForge, Bedrock, les snapshots et les versions antérieures à 1.14.4 ne sont pas pris en charge.

L'IA dispose de **45 outils** pour lire le joueur, l'inventaire, les équipements, les composants/NBT synchronisés, les blocs et entités chargés, le chat, les interfaces, les scores, les boss bars, les commandes annoncées, les recettes disponibles, les statistiques en cache, les progrès et les options. Elle peut aussi demander une capture d'écran PNG et suivre un historique de changements.

Elle peut avancer, regarder, sauter, courir, s'accroupir, attaquer, utiliser/poser un objet, choisir un slot, jeter un objet, envoyer du chat/des commandes, ouvrir l'inventaire, cliquer dans les conteneurs et les interfaces, remplir un champ et demander une réapparition. Le serveur garde son autorité et les permissions réelles du joueur s'appliquent.

## Installation

1. Installe Fabric Loader **0.19.5+** sur la version choisie et le Java requis par Minecraft.
2. Mets le [JAR de la release](https://github.com/hashk014/Mine-MCP/releases) dans le dossier `mods` de cette instance.
3. Lance Minecraft. Le mod écrit sa connexion privée dans `config/mine-mcp-connection.json`.
4. Installe Node.js 18+ et utilise le fichier `bridge/mine-mcp.mjs` comme serveur MCP stdio dans ton logiciel d'IA.

Exemple de configuration :

```json
{
  "mcpServers": {
    "mine-mcp": {
      "command": "node",
      "args": [
        "C:\\Tools\\Mine-MCP\\bridge\\mine-mcp.mjs",
        "--game-dir",
        "C:\\CHEMIN\\VERS\\TON_INSTANCE_MINECRAFT"
      ]
    }
  }
}
```

Le dossier d'instance est celui qui contient `mods`, `config` et `saves`. Aucun `npm install` n'est nécessaire. Les instructions complètes, la connexion HTTP directe et le dépannage sont dans [CONNECT.md](CONNECT.md).

## Commandes et limites

Commence par `get_status`, `get_capabilities`, puis `get_snapshot`. Les outils déclarent leurs paramètres dans MCP. Lis `get_container` avant de cliquer dans un inventaire ; son identifiant empêche d'agir sur un autre menu entre-temps.

**F8 suspend/reprend le contrôle IA.** Les touches maintenues expirent au bout de deux secondes maximum. `release_controls` reste disponible même si le contrôle est suspendu. `allowActions: false` dans la configuration désactive les actions après redémarrage.

Le mod écoute uniquement sur ta machine et exige un jeton privé. Ne publie jamais les fichiers de configuration générés. Le texte du chat, des panneaux, des livres et des objets est du contenu du jeu, pas une instruction fiable pour l'IA.

L'objectif est de couvrir largement les données du client, mais ce n'est pas littéralement toute l'API Minecraft. Les morceaux de monde non chargés, les données privées du serveur, les identifiants de connexion et les permissions administrateur ne sont pas fournis. Les fonctions absentes sur une version renvoient une erreur explicite ; une valeur optionnelle `null` ne prouve aucune information côté serveur.

## Compiler

Python 3.10+, JDK 17+ et Node.js 18+ :

```sh
python scripts/verify_repository.py
python scripts/build.py --test
npm test
```

Le JAR est produit dans `build/libs`. Les tests automatiques ne remplacent pas une validation de gameplay ou de multijoueur.
