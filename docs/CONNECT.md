# Connecting an MCP client

## Recommended: stdio bridge

The bridge is a zero-dependency Node.js program. Copy the single `bridge/mine-mcp.mjs` file anywhere convenient, or keep the repository checkout.

```sh
node /path/to/mine-mcp.mjs --game-dir /path/to/instance
```

It reads newline-delimited JSON-RPC on stdin and writes only JSON-RPC on stdout. Configure that command in your MCP client; running it in a terminal alone normally waits for input. No npm install is necessary. The game must already be running with the mod installed.

Windows example (JSON escapes backslashes):

```json
{
  "mcpServers": {
    "mine-mcp": {
      "command": "node",
      "args": [
        "C:\\Tools\\Mine-MCP\\bridge\\mine-mcp.mjs",
        "--game-dir",
        "C:\\Users\\YOUR_NAME\\AppData\\Roaming\\.minecraft"
      ]
    }
  }
}
```

For Prism, Modrinth App, CurseForge or other launchers, use the instance's actual game directory (the directory containing `mods`, `config` and `saves`). `--connection-file /path/to/config/mine-mcp-connection.json` can select a connection file directly. `MINE_MCP_GAME_DIR` is an alternative environment variable.

Default directories when no argument is supplied:

| OS | Default |
| --- | --- |
| Windows | `%APPDATA%\.minecraft` |
| macOS | `~/Library/Application Support/minecraft` |
| Linux | `~/.minecraft` |

## Direct HTTP

Read the private `config/mine-mcp-connection.json` from the running instance. It contains `url` and `token`. Configure a stateless Streamable HTTP MCP connection to that URL with `Authorization: Bearer <token>`. Do not copy credentials into a public issue, repository or shared configuration.

The endpoint supports JSON responses to POST and returns HTTP 202 for accepted notifications. GET/DELETE return 405; there is no standalone SSE stream or session ID. HTTP clients must send `Content-Length` (ordinary JSON request APIs do this). Browser origins, remote bind addresses, HTTP redirects and chunked request bodies are unsupported.

This implementation supports MCP revisions `2025-11-25`, `2025-06-18`, `2025-03-26` and `2024-11-05` with the initialize handshake. It negotiates the requested supported revision, otherwise returns `2025-11-25`. It does **not** implement the different `2026-07-28` protocol era; clients must allow legacy negotiation. The stdio bridge preserves JSON-RPC messages and has the same protocol support.

Resources: `minecraft://status`, `minecraft://snapshot`, `minecraft://inventory`, `minecraft://chat`. The `observe_then_act` prompt suggests an observation/action/verification loop.

## Control settings

Close the game before changing `config/mine-mcp.json`:

```json
{
  "port": 0,
  "allowActions": true,
  "token": "KEEP_THE_GENERATED_PRIVATE_VALUE"
}
```

- `port: 0` selects an available port. A fixed port can be set for a direct HTTP client.
- `allowActions: false` makes action tools unavailable at execution time, except releasing inputs and pausing. Observation tools still work.
- F8 toggles control pause from inside Minecraft. A remote MCP action can pause, but cannot resume a paused client.
- A two-second maximum key lease bounds movement if an agent disconnects. Expiration/release is processed on game ticks.
- A timed-out action may already have begun. Inspect the game state before retrying it; the mod cancels requests that expired while still queued.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Connection file missing | Correct instance? Fabric client? `[Mine MCP]` message in the game log? |
| Connection refused | Game stopped/restarted? Correct instance file? The bridge re-reads it every request. |
| HTTP 401 | Use the current token from this instance. |
| HTTP 403 | Host must be loopback with the actual port. Browser `Origin` is rejected. |
| HTTP 400 | Supported protocol header, one JSON object, no duplicate headers/chunked encoding. |
| Action paused | Press F8 in the Minecraft window. |
| No world/player | Join a world; screen/status/registry tools also work at the title screen. |
| Unsupported operation | Read the exact compatibility evidence. This alpha reports missing APIs rather than fabricating data. |
| Client not ticking | Finish loading, close a blocking dialog, then reconnect. Expired queued actions will not replay. |
| Actions stop when switching windows | Minecraft can open its pause menu on focus loss. Keep the game focused or use vanilla F3+P to change pause-on-focus-loss behavior. |
