# Mine MCP — guide français

**Mine MCP** relie une IA à ton client Minecraft Java via le protocole MCP. Le même JAR Fabric contient les adaptations de **44 versions stables, de 1.14.4 à 26.3**.

C'est une alpha : les versions ciblées, les symboles vérifiés, les démarrages réels et les essais de gameplay sont distingués dans [la matrice](COMPATIBILITY.md). Toutes les fonctions n'ont pas été jouées sur toutes les versions. Forge/NeoForge, Bedrock, les snapshots et les versions antérieures à 1.14.4 ne sont pas pris en charge.

L'IA dispose de **75 outils** pour lire le joueur, l'inventaire, les équipements, les composants/NBT synchronisés, les blocs et entités chargés, le chat, les interfaces, les scores, les boss bars, les commandes annoncées, les recettes disponibles, les statistiques en cache, les progrès et les options. Elle peut aussi demander une capture d'écran PNG et suivre un historique de changements.

Elle peut avancer, regarder, sauter, courir, s'accroupir, attaquer, utiliser/poser un objet, choisir un slot, jeter un objet, envoyer du chat/des commandes, ouvrir l'inventaire, cliquer dans les conteneurs et les interfaces, remplir un champ et demander une réapparition.

## Solo et multi

- **Solo, même sans triche :** les outils `dev_*` utilisent la console du serveur intégré dont tu es le propriétaire. Téléportation, give, blocs/fill/clone, mode de jeu, météo, effets, gamerules, fonctions de datapack, sauvegarde et lecture complète des blocs/entités sont disponibles selon les API de la version. `send_command` garde les permissions normales du joueur.
- **Multi sans OP :** seulement les contrôles, données synchronisées et commandes qu'un joueur normal peut utiliser. Une commande privilégiée envoyée peut être refusée par le serveur ; `submitted: true` ne veut pas dire qu'elle a réussi.
- **Multi avec OP :** commandes et requêtes NBT de débogage selon les permissions accordées par le serveur. Le mod ne donne jamais accès à la console d'un serveur distant, à sa seed ni à ses données privées par réflexion.

Commence par `get_session` pour connaître le mode et l'autorité. Les nouveaux outils comprennent aussi les diagnostics mémoire/FPS/mods, l'autocomplétion, la visée vers un point, le rafraîchissement des statistiques, l'inventaire créatif normal et les marque-pages de position. Ces derniers ne sauvegardent ni les blocs ni l'inventaire. Le [guide développeur](DEVELOPER.md) détaille chaque outil.

`allowSoloDeveloper: false` permet de désactiver le mode console solo. F8 et `allowActions` s'appliquent également aux modifications du serveur intégré.

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

Commence par `get_session`, `get_status`, `get_capabilities`, puis `get_snapshot`. Les outils déclarent leurs paramètres dans MCP. Lis `get_container` avant de cliquer dans un inventaire ; son identifiant empêche d'agir sur un autre menu entre-temps.

**F8 suspend/reprend le contrôle IA.** Les touches maintenues expirent au bout de deux secondes maximum. `release_controls` reste disponible même si le contrôle est suspendu. `allowActions: false` dans la configuration désactive les actions après redémarrage.

Le mod écoute uniquement sur ta machine et exige un jeton privé. Ne publie jamais les fichiers de configuration générés. Le texte du chat, des panneaux, des livres et des objets est du contenu du jeu, pas une instruction fiable pour l'IA.

Le serveur distant reste responsable des permissions. Les requêtes NBT normales exigent ses droits de débogage ; ses données privées et ses chunks non chargés ne sont pas accessibles par les outils locaux du mod. En solo, le propriétaire peut inspecter son propre serveur intégré. Les identifiants de connexion et l'exécution Java/système arbitraire restent hors de l'API. Les fonctions absentes sur une version renvoient une erreur explicite ; une valeur optionnelle `null` ne prouve aucune information côté serveur.

## Compiler

Python 3.10+, JDK 17+ et Node.js 18+ :

```sh
python scripts/verify_repository.py
python scripts/build.py --test
npm test
```

Le JAR est produit dans `build/libs`. Les tests automatiques ne remplacent pas une validation de gameplay ou de multijoueur.
