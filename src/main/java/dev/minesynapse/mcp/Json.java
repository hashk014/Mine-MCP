package dev.minesynapse.mcp;

import com.google.gson.*;
import java.util.*;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private Json() {}
    public static JsonObject obj(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add(String.valueOf(pairs[i]), value(pairs[i + 1]));
        return result;
    }
    public static JsonElement value(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonElement) return (JsonElement) value;
        return GSON.toJsonTree(value);
    }
    public static JsonArray arr(Object... values) {
        JsonArray result = new JsonArray();
        for (Object value : values) result.add(value(value));
        return result;
    }
    public static String string(JsonObject o, String k, String fallback) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : fallback;
    }
    public static int integer(JsonObject o, String k, int fallback, int min, int max) {
        double d = number(o, k, fallback, min, max);
        if (d != Math.rint(d)) throw new IllegalArgumentException(k + " must be an integer");
        return (int) d;
    }
    public static double number(JsonObject o, String k, double fallback, double min, double max) {
        double n = o.has(k) ? o.get(k).getAsDouble() : fallback;
        if (Double.isNaN(n) || Double.isInfinite(n) || n < min || n > max)
            throw new IllegalArgumentException(k + " must be between " + min + " and " + max);
        return n;
    }
    public static boolean bool(JsonObject o, String k, boolean fallback) {
        return o.has(k) ? o.get(k).getAsBoolean() : fallback;
    }
    public static String limited(String s, int max) { return s.length() <= max ? s : s.substring(0, max) + "…"; }
    public static JsonObject parse(String text) { return new JsonParser().parse(text).getAsJsonObject(); }
}
