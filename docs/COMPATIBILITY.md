# Compatibility evidence

This alpha targets Fabric on 44 stable Minecraft Java releases. One shared JAR is compiled to Java 8 bytecode; the game still needs its own Java runtime.

**Symbols verified** means the exact-version catalog, tick hook, keyboard signature and inherited core member names were verified. It does not mean every tool was played through on that version.

**Gameplay checks** are real MCP calls in an isolated offline singleplayer client. Passing an input-submission check does not prove server-side effects; explicit observed-state checks cover rotation, selected slot, movement, key expiry and chat. Empty scoreboards/boss bars/statistics validate the empty response path, not every populated form. Respawn, item dropping, command effects, complex crafting, multiplayer and every modded GUI were not exhaustively tested.

| Minecraft | Required Java (official manifest) | Symbol catalog | Runtime evidence |
| --- | ---: | --- | --- |
| 26.3 | 25 | symbols-verified | Blocked: native graphics crash, also reproduced with Fabric alone |
| 26.2 | 25 | symbols-verified | Not run |
| 26.1.2 | 25 | symbols-verified | Not run |
| 26.1.1 | 25 | symbols-verified | Not run |
| 26.1 | 25 | symbols-verified | Not run |
| 1.21.11 | 21 | symbols-verified | [49/49 gameplay checks](runtime-results/1.21.11.json) |
| 1.21.10 | 21 | symbols-verified | Not run |
| 1.21.9 | 21 | symbols-verified | Not run |
| 1.21.8 | 21 | symbols-verified | Not run |
| 1.21.7 | 21 | symbols-verified | Not run |
| 1.21.6 | 21 | symbols-verified | Not run |
| 1.21.5 | 21 | symbols-verified | Not run |
| 1.21.4 | 21 | symbols-verified | Not run |
| 1.21.3 | 21 | symbols-verified | Not run |
| 1.21.2 | 21 | symbols-verified | Not run |
| 1.21.1 | 21 | symbols-verified | Not run |
| 1.21 | 21 | symbols-verified | Not run |
| 1.20.6 | 21 | symbols-verified | Not run |
| 1.20.5 | 21 | symbols-verified | Not run |
| 1.20.4 | 17 | symbols-verified | Not run |
| 1.20.3 | 17 | symbols-verified | Not run |
| 1.20.2 | 17 | symbols-verified | Not run |
| 1.20.1 | 17 | symbols-verified | [6/6 client smoke checks](runtime-results/1.20.1.json) |
| 1.20 | 17 | symbols-verified | Not run |
| 1.19.4 | 17 | symbols-verified | Not run |
| 1.19.3 | 17 | symbols-verified | Not run |
| 1.19.2 | 17 | symbols-verified | Not run |
| 1.19.1 | 17 | symbols-verified | Not run |
| 1.19 | 17 | symbols-verified | Not run |
| 1.18.2 | 17 | symbols-verified | Not run |
| 1.18.1 | 17 | symbols-verified | Not run |
| 1.18 | 17 | symbols-verified | Not run |
| 1.17.1 | 16 | symbols-verified | Not run |
| 1.17 | 16 | symbols-verified | Not run |
| 1.16.5 | 8 | symbols-verified | Not run |
| 1.16.4 | 8 | symbols-verified | Not run |
| 1.16.3 | 8 | symbols-verified | Not run |
| 1.16.2 | 8 | symbols-verified | Not run |
| 1.16.1 | 8 | symbols-verified | Not run |
| 1.16 | 8 | symbols-verified | Not run |
| 1.15.2 | 8 | symbols-verified | Not run |
| 1.15.1 | 8 | symbols-verified | Not run |
| 1.15 | 8 | symbols-verified | Not run |
| 1.14.4 | 8 | symbols-verified | [49/49 gameplay checks](runtime-results/1.14.4.json) |

## Runtime notes

- Runtime reports record the exact tested JAR SHA-256. A report for a preceding build is evidence for that build, not automatic proof of a later binary.
- The 26.3 client reached Fabric/mod initialization, then hit native Windows exit `0xC0000005` during graphics/resource startup. A Fabric-only baseline failed the same way. This environment could not complete a 26.3 graphical test.
- Game data is client-cached. The server seed, unloaded chunks, remote inventories and permission escalation are unavailable.
- Optional fields can be null on versions without that API. Direct tools report unsupported mandatory operations as errors; combined snapshots contain per-section errors.
- Windows graphical clients were tested locally. Linux/macOS game runtime and multiplayer acceptance remain unverified. CI tests portable code on Windows/Linux, without a graphical Minecraft session.
- Not supported: Forge, NeoForge, Quilt, Bedrock, snapshots, or pre-1.14.4 versions.

Regenerate this page after collecting reports with `python scripts/update_compatibility.py`.
