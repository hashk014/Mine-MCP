package dev.minemcp.mcp;

/** Explicit boundary: a remote connection can never acquire local console authority. */
final class DeveloperPolicy {
    static String decimal(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Expected a finite coordinate or angle");
        // Brigadier's number lexer does not accept Java's scientific notation.
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
    static boolean localOwner(boolean inWorld, boolean hasIntegratedServer, boolean profileMatches) {
        return inWorld && hasIntegratedServer && profileMatches;
    }
    static String command(String command, boolean local) {
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isEmpty() || command.length() > (local ? 8192 : 256) || command.indexOf('\n') >= 0
                || command.indexOf('\r') >= 0 || command.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Use one command, up to " + (local ? 8192 : 256) + " characters");
        return command;
    }
    static String id(String id) {
        if (!id.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+")) throw new IllegalArgumentException("Expected a namespaced registry ID");
        return id;
    }
    static String state(String state) {
        if (!state.matches("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+\\])?"))
            throw new IllegalArgumentException("Use a block ID with optional [property=value] states; use dev_command for NBT");
        return state;
    }
    static long volume(int x1, int y1, int z1, int x2, int y2, int z2) {
        long volume = (Math.abs((long)x2-x1)+1) * (Math.abs((long)y2-y1)+1);
        if (volume > 32768 || Math.abs((long)z2-z1)+1 > 32768 || volume * (Math.abs((long)z2-z1)+1) > 32768)
            throw new IllegalArgumentException("Region is limited to 32768 blocks; split larger edits explicitly");
        return volume * (Math.abs((long)z2-z1)+1);
    }
}
