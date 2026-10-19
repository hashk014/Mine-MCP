package dev.minesynapse.mcp;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

public final class Config {
    public int port = 0;
    public boolean allowActions = true;
    public String token;
    public final Path directory;
    public Config(Path directory) { this.directory = directory; }
    public static Config load(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path path = directory.resolve("minesynapse-mcp.json"); Config config = new Config(directory);
        if (Files.exists(path)) {
            JsonObject data = Json.parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
            config.port = Json.integer(data, "port", 0, 0, 65535);
            config.allowActions = Json.bool(data, "allowActions", true);
            config.token = Json.string(data, "token", null);
        }
        if (config.token == null) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            config.token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        if (!config.token.matches("[A-Za-z0-9_-]{32,128}")) throw new IOException("Token must be 32-128 base64url characters");
        privateWrite(path, Json.obj("port", config.port, "allowActions", config.allowActions, "token", config.token).toString());
        return config;
    }
    static void privateWrite(Path path, String data) throws IOException {
        Path temp = Files.createTempFile(path.getParent(), ".minesynapse-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Windows uses the config directory's inherited ACL. */ }
            Files.write(temp, (data + "\n").getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
