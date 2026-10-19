# Changelog

## 0.2.0 Alpha — 2026-10-19

Mine MCP now has 75 tools and distinguishes a verified solo owner from a normal remote player. Solo developer commands work through the integrated server, including cheats-disabled worlds. Multiplayer commands and vanilla NBT debug queries always retain the server's permission checks; remote OP never grants local-console inspection.

Added typed tools for teleport/give, block edits/fill/clone, time/weather/effects/game mode/gamerules, datapack functions, authoritative solo NBT/seed/tick information, native world saving and position checkpoints. Added diagnostics, loaded-chunk inspection, command completion, statistics refresh, coordinate aiming and normal creative inventory requests.

Server work runs on the integrated-server executor with expiration, cancellation, session and pause/config checks. F8 also blocks developer mutations. Missing client chunks are now detected with a non-creating cache lookup instead of the client world's misleading hasChunkAt response. Optional allowSoloDeveloper=false preserves ordinary player authority in solo.

Validation: 56 Java assertions, 3 bridge tests and official MCP SDK checks over stdio/HTTP. The exact JAR passed 106 runtime checks: 51 solo with cheats disabled, 14 remote non-OP, 14 remote OP and 9 after revocation on 1.21.11, plus 18 legacy solo checks on 1.14.4/Java 8. Other releases retain static symbol-catalog evidence, not a complete gameplay pass.

## 0.1.1 Alpha

Initial 45-tool client interface, authenticated loopback MCP transport and standalone stdio bridge, screenshots, bounded player controls and exact-version catalogs for 44 stable Fabric releases.
