package dev.minemcp.mcp;

import com.google.gson.*;
import java.util.*;

public final class Events {
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();
    private final int capacity;
    private long sequence;
    public Events(int capacity) { this.capacity = capacity; }
    public synchronized void add(String type, Object data) {
        events.addLast(Json.obj("id", ++sequence, "time", System.currentTimeMillis(), "type", type, "data", data));
        while (events.size() > capacity) events.removeFirst();
    }
    public synchronized JsonObject read(long after, int limit) {
        JsonArray result = new JsonArray();
        for (JsonObject e : events) if (e.get("id").getAsLong() > after && result.size() < limit) result.add(Json.parse(e.toString()));
        long oldest = events.isEmpty() ? sequence + 1 : events.peekFirst().get("id").getAsLong();
        long next = result.size() == 0 ? Math.max(after, sequence) : result.get(result.size() - 1).getAsJsonObject().get("id").getAsLong();
        return Json.obj("events", result, "nextCursor", next, "latestCursor", sequence, "oldestCursor", oldest,
                "gap", after < oldest - 1, "hasMore", next < sequence);
    }
}
