package dev.minesynapse.mcp;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Bounded HTTP/1.1 transport using java.base only: launcher runtimes may omit jdk.httpserver.
 * No keep-alive, chunked bodies, proxying, WebSockets or filesystem routes are exposed.
 */
public final class LocalServer implements AutoCloseable {
    private final ServerSocket socket;
    private final ThreadPoolExecutor executor;
    private final Config config;
    private final Protocol protocol;
    private volatile boolean running;
    private static final int MAX_BODY = 1024 * 1024;
    private int rate;
    private long rateWindow = System.nanoTime();
    public LocalServer(Config config, Protocol protocol) throws IOException {
        this.config = config; this.protocol = protocol;
        socket = new ServerSocket(); socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), config.port), 32);
        executor = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(32), r -> {
            Thread t = new Thread(r, "MineSynapse-MCP-HTTP"); t.setDaemon(true); return t;
        });
    }
    public void start() {
        running = true;
        Thread acceptor = new Thread(() -> {
            while (running) try {
                Socket client = socket.accept(); client.setSoTimeout(5000);
                try { executor.execute(() -> handle(client)); }
                catch (RejectedExecutionException e) { client.close(); }
            } catch (IOException e) { if (running) System.err.println("[MineSynapse MCP] Accept failed: " + e.getClass().getSimpleName()); }
        }, "MineSynapse-MCP-listener");
        acceptor.setDaemon(true); acceptor.start();
    }
    public int port() { return socket.getLocalPort(); }
    private synchronized boolean allowedRate() {
        long now = System.nanoTime(); if (now - rateWindow >= 1_000_000_000L) { rateWindow = now; rate = 0; }
        return ++rate <= 120;
    }
    private static String line(InputStream input, int max) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') {
                byte[] data = output.toByteArray();
                if (data.length == 0 || data[data.length - 1] != '\r') throw new IOException("CRLF required");
                return new String(data, 0, data.length - 1, StandardCharsets.US_ASCII);
            }
            if (output.size() >= max || c == 0) throw new IOException("Header too large or invalid");
            output.write(c);
        }
        throw new EOFException();
    }
    private void handle(Socket client) {
        try (Socket ignored = client) {
            InputStream input = new BufferedInputStream(client.getInputStream());
            try {
                String[] request = line(input, 4096).split(" ");
                if (request.length != 3 || !request[2].equals("HTTP/1.1")) { reply(client, 400, "Invalid HTTP request"); return; }
                Map<String, String> headers = new HashMap<>(); int size = 0;
                while (true) {
                    String header = line(input, 8192); size += header.length() + 2;
                    if (size > 16384) { reply(client, 431, "Headers too large"); return; }
                    if (header.isEmpty()) break;
                    int colon = header.indexOf(':');
                    if (colon <= 0) { reply(client, 400, "Invalid header"); return; }
                    String key = header.substring(0, colon).toLowerCase(Locale.ROOT);
                    if (!key.matches("[a-z0-9!#$%&'*+.^_`|~-]+") || headers.put(key, header.substring(colon + 1).trim()) != null) { reply(client, 400, "Invalid or duplicate header"); return; }
                }
                if (!request[1].equals("/mcp")) { reply(client, 404, "Unknown endpoint"); return; }
                String host = headers.get("host");
                if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) { reply(client, 403, "Invalid Host"); return; }
                if (headers.containsKey("origin")) { reply(client, 403, "Browser origins are not allowed"); return; }
                String auth = headers.get("authorization");
                if (auth == null || !MessageDigest.isEqual(("Bearer " + config.token).getBytes(StandardCharsets.UTF_8), auth.getBytes(StandardCharsets.UTF_8))) { reply(client, 401, "Bearer token required"); return; }
                if (!request[0].equals("POST")) { reply(client, 405, "Use POST; no standalone SSE stream"); return; }
                if (headers.containsKey("transfer-encoding")) { reply(client, 400, "Transfer-Encoding is not supported; use Content-Length"); return; }
                if (!headers.containsKey("content-length")) { reply(client, 411, "Content-Length required"); return; }
                String contentLength = headers.get("content-length");
                if (!contentLength.matches("[0-9]{1,10}")) { reply(client, 400, "Invalid Content-Length"); return; }
                long length = Long.parseLong(contentLength);
                if (length > MAX_BODY) { reply(client, 413, "Request too large"); return; }
                String version = headers.get("mcp-protocol-version");
                if (version != null && !Protocol.SUPPORTED.contains(version)) { reply(client, 400, "Unsupported MCP protocol version"); return; }
                if (!allowedRate()) { reply(client, 429, "Too many requests"); return; }
                String contentType = headers.get("content-type");
                if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) { reply(client, 415, "Use application/json"); return; }
                byte[] body = new byte[(int) length]; int offset = 0;
                while (offset < body.length) { int n = input.read(body, offset, body.length - offset); if (n < 0) throw new EOFException(); offset += n; }
                JsonElement data;
                try { data = new JsonParser().parse(new String(body, StandardCharsets.UTF_8)); }
                catch (JsonParseException ex) { reply(client, 200, Json.obj("jsonrpc", "2.0", "id", null, "error", Json.obj("code", -32700, "message", "Invalid JSON")).toString()); return; }
                JsonObject response = protocol.handle(data);
                reply(client, response == null ? 202 : 200, response == null ? "" : response.toString());
            } catch (IOException | RuntimeException e) { reply(client, 400, "Invalid request"); }
        } catch (IOException ignored) {}
    }
    private static void reply(Socket client, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        OutputStream output = client.getOutputStream();
        String extra = status == 405 ? "Allow: POST\r\n" : status == 429 ? "Retry-After: 1\r\n" : "";
        output.write(("HTTP/1.1 " + status + " " + (status == 200 ? "OK" : status == 202 ? "Accepted" : "Error") + "\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" + extra + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes); output.flush();
    }
    @Override public void close() { running = false; try { socket.close(); } catch (IOException ignored) {} executor.shutdownNow(); }
}
