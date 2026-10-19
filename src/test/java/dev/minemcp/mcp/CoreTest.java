package dev.minemcp.mcp;

import com.google.gson.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Behavioral tests: transport security, protocol semantics, action cancellation and event gaps. */
public final class CoreTest {
    private static int assertions;
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static JsonObject request(String method, JsonObject params) { return Json.obj("jsonrpc", "2.0", "id", 1, "method", method, "params", params); }
    private static void rejects(Runnable action, String message) { boolean rejected = false; try { action.run(); } catch (IllegalArgumentException e) { rejected = true; } check(rejected, message); }
    public static void main(String[] args) throws Exception {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
        AtomicInteger calls = new AtomicInteger(); Protocol protocol = new Protocol((name, a) -> { calls.incrementAndGet(); if (name.equals("get_world")) throw new UnsupportedOperationException("No world"); return Json.obj("ok", true); });
        JsonObject init = protocol.handle(request("initialize", Json.obj("protocolVersion", "2099-01-01")));
        check(init.getAsJsonObject("result").get("protocolVersion").getAsString().equals("2025-11-25"), "negotiation fallback");
        check(protocol.handle(request("ping", Json.obj())).getAsJsonObject("result").size() == 0, "ping");
        check(protocol.handle(request("tools/list", Json.obj())).getAsJsonObject("result").getAsJsonArray("tools").size() >= 39, "tool catalog");
        check(protocol.handle(Json.obj("jsonrpc", "2.0", "method", "tools/call", "params", Json.obj("name", "attack"))) == null && calls.get() == 0, "notifications never execute actions");
        check(protocol.handle(request("missing", Json.obj())).getAsJsonObject("error").get("code").getAsInt() == -32601, "unknown method");
        check(protocol.handle(Json.arr()).getAsJsonObject("error").get("code").getAsInt() == -32600, "batch unsupported");
        check(protocol.handle(request("tools/call", Json.obj("name", "look", "arguments", Json.obj("yaw", 10)))).getAsJsonObject("error").get("code").getAsInt() == -32602, "required arguments");
        check(protocol.handle(request("tools/call", Json.obj("name", "get_world"))).getAsJsonObject("result").get("isError").getAsBoolean(), "tool execution error");
        check(protocol.handle(request("tools/call", Json.obj("name", "get_status"))).getAsJsonObject("result").has("structuredContent"), "structured result");
        check(protocol.handle(request("resources/read", Json.obj("uri", "minecraft://status"))).getAsJsonObject("result").getAsJsonArray("contents").size() == 1, "resource read");
        check(protocol.handle(request("resources/read", Json.obj("uri", "file:///secret"))).getAsJsonObject("error").get("code").getAsInt() == -32002, "no file resources");
        Tools tools = new Tools();
        rejects(() -> tools.require("look", Json.obj("yaw", 0, "pitch", 91)), "pitch bounds");
        rejects(() -> tools.require("hold_controls", Json.obj("keys", Json.arr("forward"), "durationMs", 5000)), "bounded key lease");
        rejects(() -> tools.require("get_player", Json.obj("secret", true)), "extra arguments rejected");
        rejects(() -> tools.require("select_hotbar", Json.obj("slot", 1.5)), "integer validation");
        rejects(() -> tools.require("hold_controls", Json.obj("keys", Json.arr("teleport"))), "key enum");
        rejects(() -> tools.require("scan_blocks", Json.obj("x", 1)), "scan center all or none");
        rejects(() -> tools.require("get_chat", Json.obj("limit", "12")), "numeric string rejected");
        Events events = new Events(2); events.add("one", 1); events.add("two", 2); events.add("three", 3);
        JsonObject page = events.read(0, 1);
        check(page.get("gap").getAsBoolean(), "event overflow reports a gap");
        check(page.get("nextCursor").getAsInt() == 2 && page.get("hasMore").getAsBoolean(), "event cursor pagination");
        check(!events.read(2, 4).get("gap").getAsBoolean(), "current cursor no gap");
        FakeOverload fake = new FakeOverload();
        Symbols symbols = new Symbols(Json.obj(FakeOverload.class.getName(), Json.obj("c", FakeOverload.class.getName(), "f", Json.arr(),
                "m", Json.arr(Json.arr("read", "a", "(Ljava/lang/String;)Ljava/lang/String;")))));
        check("safe".equals(symbols.call(fake, "read", "safe")), "mapped method descriptor matches");
        boolean blocked = false;
        try { symbols.call(fake, "read", new Object()); } catch (UnsupportedOperationException e) { blocked = true; }
        check(blocked && fake.mutations == 0, "ambiguous obfuscated overload cannot invoke a different action");
        GameThread game = new GameThread(); AtomicInteger mutations = new AtomicInteger();
        try { game.call(() -> mutations.incrementAndGet(), 15); throw new AssertionError("Expected timeout"); } catch (TimeoutException expected) {}
        game.tick(); check(mutations.get() == 0, "expired queued mutation never executes later");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<Integer> success = worker.submit(() -> game.call(() -> 42, 1000));
        long deadline = System.nanoTime() + 900_000_000L;
        while (!success.isDone() && System.nanoTime() < deadline) { game.tick(); Thread.sleep(2); }
        check(success.get() == 42, "game queue executes on tick"); worker.shutdownNow(); game.close();
        Path temp = Files.createTempDirectory("minemcp-test-"); Config config = Config.load(temp);
        check(config.token.length() >= 32 && Config.load(temp).token.equals(config.token), "secure persistent token");
        try (LocalServer server = new LocalServer(config, protocol)) {
            server.start(); String url = "http://127.0.0.1:" + server.port() + "/mcp";
            check(http(url, "POST", null, null, "{}").status == 401, "missing token denied");
            check(http(url, "POST", "wrong", null, "{}").status == 401, "wrong token denied");
            check(http(url, "POST", config.token, "https://evil.example", "{}").status == 403, "browser origin denied");
            check(http(url, "GET", config.token, null, null).status == 405, "standalone stream not supported");
            Response valid = http(url, "POST", config.token, null, request("ping", Json.obj()).toString());
            check(valid.status == 200 && Json.parse(valid.body).has("result"), "authenticated JSON-RPC");
            check(http(url, "POST", config.token, null, "{").body.contains("-32700"), "parse error");
            check(http(url, "POST", config.token, null, Json.obj("jsonrpc", "2.0", "method", "notifications/initialized").toString()).status == 202, "notification no response");
            check(http(url + "/evil", "POST", config.token, null, "{}").status == 404, "exact endpoint");
        }
        Files.deleteIfExists(temp.resolve("mine-mcp.json")); Files.deleteIfExists(temp);
        System.out.println("CoreTest: " + assertions + " assertions passed");
    }
    public static final class FakeOverload {
        int mutations;
        public String a(String value) { return value; }
        public void a(Object value) { mutations++; }
    }
    private static final class Response { int status; String body; Response(int s, String b) { status = s; body = b; } }
    private static Response http(String url, String method, String token, String origin, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setRequestMethod(method); c.setReadTimeout(5000);
        c.setRequestProperty("Content-Type", "application/json");
        if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
        if (origin != null) c.setRequestProperty("Origin", origin);
        if (body != null) { c.setDoOutput(true); try (OutputStream out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); } }
        int status = c.getResponseCode(); InputStream input = status >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (input != null) try (InputStream stream = input) { byte[] buf = new byte[1024]; int n; while ((n = stream.read(buf)) != -1) out.write(buf, 0, n); }
        c.disconnect(); return new Response(status, new String(out.toByteArray(), StandardCharsets.UTF_8));
    }
}
