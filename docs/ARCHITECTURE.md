# Architecture and contribution notes

```mermaid
flowchart LR
    AI[MCP client / AI agent] -->|stdio JSON-RPC| Bridge[Node.js bridge]
    Bridge -->|authenticated loopback HTTP| Server[Bounded Java HTTP transport]
    AI -->|optional direct HTTP| Server
    Server --> Protocol[MCP router and JSON Schema validation]
    Protocol --> Queue[Expiring game-thread work queue]
    Queue --> Adapter[Client observations and normal inputs]
    Adapter --> Symbols[Exact-version symbol catalog]
    Symbols --> Game[Minecraft client]
    Game --> Events[Bounded sampled change history]
    Events --> Protocol
```

The shared JAR contains Java 8 bytecode and references no Minecraft classes directly. Fabric's loader entrypoint starts a loopback endpoint; Mixin hooks the client tick and keyboard handler. Before 26.1, hooks use stable intermediary names. From 26.1, hooks use the unobfuscated official names. The keyboard callback changed in 1.21.9, so the plugin selects the matching signature from the exact-version catalog.

`scripts/generate_mappings.py` joins Mojang's public names, official descriptors, Fabric intermediary names and the exact binary class hierarchy. Overrides can inherit a mapping from an interface; looking up a method only on its declaring mapping row is insufficient. The generated catalog holds selected signatures, not method bodies or complete game mappings. Sources and hashes are recorded beside the compatibility matrix.

`Symbols` resolves known fields/methods through actual superclasses and interfaces. Reflection is an internal compatibility mechanism, not an MCP Java execution API. The MCP surface uses fixed tool handlers and fixed inspection roots. `inspect_client` returns scalar values or class descriptions; it cannot traverse objects into authentication/networking internals.

HTTP workers do not touch Minecraft state. They submit bounded jobs to `GameThread`. Every job has a deadline and an atomic queued/running/cancelled state. Jobs cancelled before execution cannot later mutate the game. A timeout after execution begins has an explicitly uncertain outcome. Each tick drains at most four jobs, with an eight-millisecond scheduling budget; an individual job can exceed that budget, so expensive observations have input/result limits.

`take_screenshot` handles synchronous legacy framebuffer reads and newer asynchronous callbacks. It copies a downscaled image, closes the native image and returns an MCP `image/png` block. The HTTP worker waits outside the game thread. Screenshots do not write arbitrary local files.

The transport uses `ServerSocket` instead of the optional `jdk.httpserver` module: some launcher runtimes omit that module. It accepts one bounded HTTP/1.1 request per socket, caps header/body sizes, rejects ambiguous framing and duplicate headers, requires a token, restricts Host, rejects browser origins and uses a bounded worker pool. It implements only the local POST/JSON subset needed by this MCP server.

`get_events` polls a ring buffer; it is not an MCP subscription, a packet log or lossless event delivery. Observations are sampled every ten ticks and action/control/world changes are also recorded. Pagination gives the last delivered cursor and distinguishes buffer overflow from an empty page.

Tests must keep these distinctions visible:

1. Catalog verification checks shipped versions, tick/keyboard signatures and inherited core names.
2. Java behavioral tests exercise schema errors, JSON-RPC semantics, transport authentication, origin rejection, cancellation, pagination and token persistence.
3. Node tests exercise bridge URL restrictions, forwarding, notifications and concurrent stdio IDs.
4. Client smoke tests verify a real graphical client and actual MCP calls.
5. Gameplay tests require an isolated world, state changes, screenshots and post-action observation. They do not establish multiplayer/server acceptance on other servers.
