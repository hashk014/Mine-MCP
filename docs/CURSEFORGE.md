# Mine MCP

**Give your connected AI eyes, hands and a developer toolbox inside Minecraft Java.** Mine MCP is a client-side Fabric mod with **75 MCP tools** for solo and multiplayer. No Fabric API or server plugin is required.

[Source code and releases](https://github.com/hashk014/Mine-MCP) · [Connection guide](https://github.com/hashk014/Mine-MCP/blob/main/docs/CONNECT.md) · [Guide en français](https://github.com/hashk014/Mine-MCP/blob/main/docs/FR.md)

## Solo and multiplayer

- **Solo owner, even with cheats disabled:** developer commands run on your integrated server with console authority. Build, teleport, give items, change game modes/time/weather, run datapack functions, inspect full block/entity NBT, read world seeds and save the world.
- **Multiplayer without OP:** normal client controls, inventory, chat, synced data and whatever commands the server permits. The server rejects forbidden edits; the mod never grants OP.
- **Multiplayer with OP:** the same normal player connection, with commands and vanilla F3+I-style NBT requests authorized by that server. Integrated-server inspection and remote console access remain unavailable.

Start with **get_session**. The [developer guide](https://github.com/hashk014/Mine-MCP/blob/main/docs/DEVELOPER.md) covers the full toolbox: setblock/fill/clone, effects, gamerules, command completion, FPS/memory/mod diagnostics, statistics refresh, aiming at coordinates, normal creative inventory requests and named position checkpoints. Checkpoints remember position/view only, not blocks or inventory.

**F8 pauses developer edits too.** Set allowSoloDeveloper to false to keep ordinary player permissions in solo. All action tools obey allowActions. Fill/clone are bounded to 32,768 blocks per call; expensive datapacks can still take time.

## In-game demonstration

Real Minecraft 1.21.11 screenshots from the recorded MCP container demonstration. Developer-tool regression reports for 0.2.0 are linked below.

**Before:** `get_container` reads 12 diamonds and 32 oak logs in the chest.

![Chest before the MCP transfer](https://raw.githubusercontent.com/hashk014/Mine-MCP/main/docs/media/chest-before.png)

**After:** `click_slot` with `QUICK_MOVE` transfers the 12 diamonds into the player inventory; `get_inventory` confirms the result.

![Inventory after the MCP transfer](https://raw.githubusercontent.com/hashk014/Mine-MCP/main/docs/media/chest-after.png)

[Actual MCP calls and responses](https://github.com/hashk014/Mine-MCP/blob/main/docs/media/demo-evidence.json). These are direct client screenshots from an isolated singleplayer test, without added graphical overlays. This scripted demonstration does not claim autonomous model gameplay.

## Observe → act → verify

**Observe your client:** player position, health, food, XP, effects, equipment, inventory, client-synced item components/NBT, loaded blocks and entities, crosshair target, open containers, widgets, chat, tab list, scoreboards, boss bars, recipe data, advancements, cached statistics and registries. PNG screenshots let the agent see the game window too.

**Act as your player:** walk, look, jump, sprint, sneak, attack, use/place items, select a hotbar slot, drop an item, send chat or commands, open/close inventory, click container slots, press buttons and fill text fields. Read the game again to confirm what happened.

**Keep control:** press **F8** to pause/resume AI control. Held inputs expire within two seconds and are released on screen/world changes. The local endpoint requires a generated token and binds only to `127.0.0.1`. `release_controls` stays available while paused. Read-only operation can be enabled in the configuration.

## Install and connect

1. Install **Fabric Loader 0.19.5+** for your Minecraft version and use the Java runtime required by that version.
2. Put **mine-mcp-0.2.0.jar** in that instance's `mods` folder, removing the older Mine MCP JAR. **Fabric API is not required. No server plugin is required.**
3. Launch Minecraft. The private connection file is created at `config/mine-mcp-connection.json` in that instance.
4. Configure a compatible MCP client using the [standalone Node.js bridge](https://github.com/hashk014/Mine-MCP/releases/download/v0.1.1-alpha.1/mine-mcp.mjs) and **Node.js 18+**, or use direct authenticated HTTP. The bridge needs no npm install.
5. Connect your AI, call `get_session` / `get_status` / `get_snapshot`, and join a world for player/world interactions.

Command to configure as an MCP stdio server:

```text
node /absolute/path/mine-mcp.mjs --game-dir /absolute/path/to/your/instance
```

For Prism, Modrinth App, CurseForge App or another launcher, use the actual instance folder containing `mods`, `config` and `saves`. Never publish the generated connection file or token. The AI/model is configured separately; this mod supplies its game tools.

## Alpha compatibility and evidence

One shared JAR contains exact-version symbol catalogs for **44 stable releases from 1.14.4 through 26.3**. These catalogs are statically verified; they are not 44 complete gameplay validations.

- **1.14.4:** 49/49 checks in a real isolated singleplayer client.
- **1.21.11:** 49/49 checks in a real isolated singleplayer client.
- **1.20.1:** 6/6 real-client startup/observation checks.
- **26.3:** graphical test blocked by a native crash also reproduced with the mod removed.
- **Build checks:** 34 Java assertions, 3 bridge tests, official MCP SDK interoperability over stdio and HTTP; Windows/Linux build checks on JDK 17/21/25.

**0.2.0 developer validation:** 106/106 real runtime checks on the exact release JAR: 51 solo checks with cheats disabled on 1.21.11, 14 non-OP + 14 OP + 9 permission-revocation checks against an unmodified vanilla dedicated loopback server, and 18 legacy solo checks on 1.14.4 with Java 8. Also 56 Java assertions, 3 bridge tests and official MCP SDK stdio/HTTP checks. These are recorded test environments, not a guarantee for every production server/plugin stack. The historical 0.1.1 reports above remain evidence for their recorded binary.

[Full per-version matrix and exact artifact hashes](https://github.com/hashk014/Mine-MCP/blob/main/docs/COMPATIBILITY.md). Individual tools/fields can vary by game version; missing mandatory APIs return explicit errors and optional values may be null.

**Fabric / Minecraft Java / client-side only.** Forge, NeoForge, Quilt, Bedrock, snapshots and pre-1.14.4 releases are not supported. MCP initialize-based revisions through 2025-11-25 are supported; the distinct 2026-07-28 protocol era is not implemented.

## Boundaries

On remote servers, Mine MCP uses client-synced data, ordinary player actions and permission-checked vanilla debug queries. It never grants remote console authority, reveals unloaded chunks or bypasses server permissions. Solo owners can inspect and modify their own integrated server. Loaded blocks/entities may include things behind walls. Use automation where your server's rules permit it. In-game text is untrusted content, not instructions for your agent.

This is a broad, evolving interface, not every Minecraft API. Complex modded interfaces, all populated data forms, Linux/macOS game runtime and arbitrary server plugin/anti-cheat stacks have not been exhaustively validated. Tool availability and command syntax vary by Minecraft version.

MIT-licensed independent project by **hashk014**. [License and notices](https://github.com/hashk014/Mine-MCP/blob/main/NOTICE). Not an official Minecraft product and not affiliated with Mojang or Microsoft.
