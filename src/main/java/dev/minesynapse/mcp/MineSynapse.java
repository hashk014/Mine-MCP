package dev.minesynapse.mcp;

import com.google.gson.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;
import java.util.concurrent.*;

public final class MineSynapse implements ClientModInitializer {
    private static volatile MineSynapse instance;
    private final GameThread game = new GameThread();
    private final Events events = new Events(512);
    private ClientAdapter adapter;
    private Config config;
    private LocalServer server;
    private String version;
    private Path connectionFile;
    private long ticks;
    @Override public void onInitializeClient() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            version = loader.getModContainer("minecraft").get().getMetadata().getVersion().getFriendlyString();
            config = Config.load(loader.getConfigDir());
            Symbols symbols = new Symbols(version, loader.getMappingResolver());
            adapter = new ClientAdapter(symbols, events);
            server = new LocalServer(config, new Protocol(this::execute));
            server.start(); instance = this;
            connectionFile = config.directory.resolve("minesynapse-mcp-connection.json");
            Config.privateWrite(connectionFile, Json.obj("url", "http://127.0.0.1:" + server.port() + "/mcp", "token", config.token,
                    "minecraftVersion", version, "modVersion", Protocol.VERSION, "allowActions", config.allowActions).toString());
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "MineSynapse-MCP-shutdown"));
            System.out.println("[MineSynapse MCP] Listening on 127.0.0.1:" + server.port() + "; connection details in config/minesynapse-mcp-connection.json. F8 pauses actions.");
        } catch (Exception | LinkageError e) {
            // A bridge failure must not crash an otherwise playable client, and must not expose a partial endpoint.
            if (server != null) server.close();
            System.err.println("[MineSynapse MCP] Disabled: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
    public static void tick() {
        MineSynapse current = instance; if (current == null) return;
        try { current.adapter.tick(); current.game.tick(); current.ticks++; }
        catch (RuntimeException e) {
            current.adapter.release();
            if (current.ticks++ % 200 == 0) System.err.println("[MineSynapse MCP] Tick error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
    public static void key(int key, int action) {
        MineSynapse current = instance;
        if (current == null || action != 1) return;
        int f8 = 297;
        try { f8 = ((Number) current.adapter.s.field(current.adapter.s.type("com.mojang.blaze3d.platform.InputConstants"), "KEY_F8")).intValue(); }
        catch (RuntimeException ignored) {}
        if (key == f8) current.adapter.togglePause();
    }
    public static void keyEvent(Object event, int action) {
        MineSynapse current = instance; if (current == null) return;
        Object key = current.adapter.s.optional(event, "key");
        if (key instanceof Number) key(((Number) key).intValue(), action);
    }
    private JsonObject execute(String tool, JsonObject a) throws Exception {
        if (tool.equals("get_events")) return events.read((long) Json.number(a, "after", 0, 0, 9007199254740991d), Json.integer(a, "limit", 100, 1, 256));
        Tools.Tool spec = new Tools().require(tool, a);
        if (spec.action && !tool.equals("release_controls") && !tool.equals("pause_control") && !config.allowActions) throw new IllegalStateException("Actions are disabled in config/minesynapse-mcp.json");
        if (tool.equals("take_screenshot")) {
            CompletableFuture<JsonObject> image = game.call(() -> adapter.screenshot(Json.integer(a, "maxWidth", 640, 160, 1280)), 5000);
            return image.get(10, TimeUnit.SECONDS);
        }
        return game.call(() -> {
            switch (tool) {
                case "get_status": { JsonObject data = adapter.status(); data.addProperty("minecraftVersion", version); data.addProperty("modVersion", Protocol.VERSION); data.addProperty("allowActions", config.allowActions); data.addProperty("tickCount", ticks); return data; }
                case "get_capabilities": return Json.obj("minecraftVersion", version, "loader", "Fabric", "tools", new Tools().list(),
                        "coverage", "Client cache and normal player inputs. Availability depends on the game version. See docs/compatibility.json.",
                        "limitations", Json.arr("No server-only inventory, seed, unloaded chunks or elevated permissions", "No authentication tokens, arbitrary Java/file access or arbitrary packets", "Sampled change events; not lossless network events", "F8 pauses actions; use short input leases"));
                case "get_snapshot": return snapshot();
                case "get_player": return adapter.playerInfo();
                case "get_world": return adapter.worldInfo();
                case "get_inventory": return adapter.inventoryInfo();
                case "get_target": return adapter.target();
                case "get_block": return adapter.block(a);
                case "scan_blocks": return adapter.scan(a);
                case "get_entities": return adapter.entities(a);
                case "get_screen": return adapter.screenInfo();
                case "get_container": return adapter.container();
                case "get_chat": return adapter.chat(a);
                case "get_players": return adapter.players();
                case "get_scoreboard": return adapter.scoreboard();
                case "get_boss_bars": return adapter.bossBars();
                case "get_registry": return adapter.registry(a);
                case "get_recipes": return adapter.recipes(a);
                case "get_commands": return adapter.commands();
                case "get_equipment": return adapter.equipment();
                case "get_held_item_data": return adapter.heldItemData();
                case "get_advancements": return adapter.advancements(a);
                case "get_statistics": return adapter.statistics(a);
                case "get_options": return adapter.options();
                case "inspect_client": return adapter.inspect(a);
                default: return adapter.action(tool, a);
            }
        }, 5000);
    }
    private JsonObject snapshot() {
        JsonObject out = new JsonObject();
        section(out, "status", () -> adapter.status()); section(out, "player", () -> adapter.playerInfo());
        section(out, "world", () -> adapter.worldInfo()); section(out, "inventory", () -> adapter.inventoryInfo());
        section(out, "target", () -> adapter.target()); section(out, "screen", () -> adapter.screenInfo());
        out.addProperty("capturedAt", System.currentTimeMillis()); return out;
    }
    private interface Reader { JsonObject read(); }
    private void section(JsonObject out, String name, Reader reader) {
        try { out.add(name, reader.read()); }
        catch (RuntimeException e) { out.add(name, Json.obj("error", e.getMessage())); }
    }
    private void shutdown() {
        instance = null; game.close(); if (server != null) server.close();
        try {
            // Do not remove a connection file belonging to a newer client instance.
            if (connectionFile != null && Files.exists(connectionFile)) {
                JsonObject existing = Json.parse(new String(Files.readAllBytes(connectionFile), java.nio.charset.StandardCharsets.UTF_8));
                if (Json.string(existing, "url", "").equals("http://127.0.0.1:" + server.port() + "/mcp")) Files.deleteIfExists(connectionFile);
            }
        } catch (Exception ignored) {}
    }
}
