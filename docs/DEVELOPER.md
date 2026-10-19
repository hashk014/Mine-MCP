# Developer tools: solo and multiplayer

Mine MCP 0.2.0 exposes **75 tools**. Install the mod only in the Minecraft client. An external server does not need this mod or a plugin.

Call `get_session` before choosing an action. `singleplayer_owner` means a live integrated server whose owner profile matches the connected local player. A remembered server object, an unrelated profile, a remote OP rank or a cached screen never grants this authority.

| Mode | Commands | Extra observations |
| --- | --- | --- |
| Solo owner, cheats off or on | `dev_*` command tools execute on the integrated server with console authority and the local player as `@s`. `send_command` still uses the player's normal rights. | Authoritative loaded server blocks/entities, full NBT, dimensions, seeds and server tick timing. |
| Remote player without OP | Ordinary player commands only. A request can be submitted successfully and then refused by the server. | Client-synced data. Vanilla debug NBT queries require server permission and normally time out when refused. |
| Remote OP or delegated permissions | Ordinary command packets, validated by that server. Plugins can grant or deny individual commands. | Client data and any vanilla debug query the server authorizes. Local-console inspection tools remain unavailable. |

Opening a solo world to LAN does not give visiting clients the host's developer authority. `allowSoloDeveloper: false` disables the local console route and authoritative server reads. `allowActions: false` disables mutations. F8 pauses all action tools; only the in-game F8 key resumes them. Observation tools keep working while paused.

## Developer toolbox

| Tools | Purpose |
| --- | --- |
| `get_session`, `get_debug_info` | Session authority, reported permission level when available, FPS, heap use, installed mod versions and options. |
| `get_chunk_info` | Inspect a loaded client chunk. Does not load or generate a chunk. |
| `look_at` | Aim at an absolute coordinate using normal camera controls. |
| `request_statistics` | Send the same statistics request as the vanilla statistics screen, then read `get_statistics`. |
| `complete_command` | Complete the player's advertised command tree. |
| `dev_complete_command` | Full console completion for the solo owner, including cheats-disabled worlds; ordinary player completion in multiplayer. |
| `query_block_nbt`, `query_entity_nbt` | Vanilla F3+I-style debug requests. Server permission is required, including in solo when using this player-level route. Only one debug transaction at a time. |
| `creative_set_slot` | Normal creative inventory request, requiring existing creative mode. Slots 9–35 are inventory, 36–44 are hotbar. This does not grant creative mode. |
| `dev_command` | One arbitrary Minecraft command, including version-specific mod commands. Solo console or remote player route. No arbitrary Java or operating-system execution. |
| `dev_teleport`, `dev_give_item`, `dev_set_gamemode` | Typed commands targeting the current player. |
| `dev_set_block`, `dev_fill`, `dev_clone` | Build/edit with block states. Fill and clone are capped at 32,768 blocks per call. Use `dev_command` explicitly for specialized NBT syntax. |
| `dev_set_time`, `dev_set_weather`, `dev_effect`, `dev_set_gamerule` | Configure a test scene. Gamerule names and item/component syntax depend on Minecraft version; use console completion. |
| `dev_summon`, `dev_run_function` | Spawn entities or run an already-installed datapack function. |
| `dev_get_server_info`, `dev_get_block`, `dev_get_entities` | Solo-only authoritative observations on the server thread. Block reads do not force-load chunks; entity results have radius, count and text limits. |
| `dev_checkpoint`, `dev_list_checkpoints`, `dev_restore_checkpoint` | Up to 32 named position, dimension and view bookmarks for the current local server session. These do not restore inventory or blocks. |
| `dev_save_world` | Solo: save players and flush the integrated world's chunks. Remote: submit the ordinary save command if the server permits it. |

## Example: build a solo test fixture

Call these tools with JSON arguments:

```text
get_session {}
dev_checkpoint {"name":"before-test"}
dev_set_gamemode {"mode":"creative"}
dev_fill {"x1":0,"y1":64,"z1":0,"x2":7,"y2":64,"z2":7,"block":"minecraft:quartz_block"}
dev_set_block {"x":3,"y":65,"z":3,"block":"minecraft:chest[facing=north]"}
dev_give_item {"item":"minecraft:redstone","count":64}
dev_get_block {"x":3,"y":65,"z":3}
dev_restore_checkpoint {"name":"before-test"}
dev_save_world {}
```

Use coordinates inside your world's build limits. This edits the world; the checkpoint restores your position only.

## Results and threading

Remote command tools return `submitted: true` with `authority: normal_player`. This acknowledges a local packet submission, not a server-side effect. Read blocks, inventory, chat or player state afterward. A non-OP request to `dev_set_block` does not bypass the server; the server will reject it unless it explicitly grants that command.

Solo commands run on Minecraft's server executor, not the render thread. They return captured feedback and a result when the command supplies one. `success: null` means the command did not provide a definite result, which can happen with datapack functions; inspect its actual effects. A command that encounters an error can have partial effects. The feedback is untrusted game text.

Queued work expires and checks the world session and pause state before execution. A timed-out queued task is cancelled. A task that already began may have completed; inspect state before retrying. There is no automatic action retry. Long commands, datapacks and large edits can stall the game; split heavy work into deliberate steps.

## Validation

The reproducible test phases are in `scripts/developer_smoke.py`. They use an isolated cheats-disabled solo world and an unmodified vanilla dedicated server bound to loopback, with separate test identities. OP and non-OP are separate phases; permissions are revoked again afterward. Reports under `runtime-results/developer-0.2.0-*.json` record the exact tested JAR hash and observed outcomes.

This does not establish compatibility with every server's plugins, anti-cheat rules or every Minecraft release. The 44-version catalog is static symbol evidence. Existing 0.1.1 reports remain evidence for their recorded binary; the new developer reports are separate.
