package dev.minemcp.mcp;

import com.google.gson.*;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/** Client-only observations and normal player actions. All entry points run on the game thread. */
public final class ClientAdapter {
    final Symbols s;
    final Object mc;
    private final Events events;
    private final Map<String, Long> held = new HashMap<>();
    private final Map<String, String> previous = new HashMap<>();
    private int ticks;
    private volatile boolean paused;
    private volatile long sessionEpoch;
    private Object lastLevel;
    private static final Map<String, String> KEYS = new LinkedHashMap<>();
    static {
        KEYS.put("forward", "keyUp"); KEYS.put("back", "keyDown"); KEYS.put("left", "keyLeft"); KEYS.put("right", "keyRight");
        KEYS.put("jump", "keyJump"); KEYS.put("sneak", "keyShift"); KEYS.put("sprint", "keySprint");
        KEYS.put("attack", "keyAttack"); KEYS.put("use", "keyUse");
    }
    public ClientAdapter(Symbols symbols, Events events) {
        this.s = symbols; this.events = events;
        mc = s.call(s.type("net.minecraft.client.Minecraft"), "getInstance");
    }
    private Object player() {
        Object p = s.field(mc, "player");
        if (p == null) throw new IllegalStateException("Join a world first");
        return p;
    }
    private Object world() {
        Object level = s.field(mc, "level");
        if (level == null) throw new IllegalStateException("Join a world first");
        return level;
    }
    private Object connection() { return s.call(mc, "getConnection"); }
    private Object menu() { return s.field(player(), "containerMenu"); }
    private Object inventory() {
        Object p = player(); Object result = s.optional(p, "getInventory");
        return result != null ? result : s.field(p, "inventory");
    }
    private Object screen() {
        Object result = s.optionalField(mc, "screen");
        return result != null ? result : s.optional(s.optionalField(mc, "gui"), "screen");
    }
    private void setScreen(Object screen) {
        try { s.call(mc, "setScreen", screen); }
        catch (UnsupportedOperationException e) { s.call(s.field(mc, "gui"), "setScreen", screen); }
    }
    private Object hud() {
        Object gui = s.field(mc, "gui"); Object h = s.optionalField(gui, "hud"); return h == null ? gui : h;
    }
    private Object xyz(Object pos, String method, String field) {
        Object value = s.optional(pos, method); return value == null ? s.optionalField(pos, field) : value;
    }
    private JsonObject position(Object e) {
        return Json.obj("x", xyz(e, "getX", "x"), "y", xyz(e, "getY", "y"), "z", xyz(e, "getZ", "z"));
    }
    private String text(Object component) {
        if (component == null) return "";
        Object result = s.optional(component, "getString");
        return Json.limited(String.valueOf(result == null ? component : result), 8192);
    }
    private Object get(Object o, String method, String field) {
        Object value = s.optional(o, method); return value == null ? s.optionalField(o, field) : value;
    }
    private JsonElement scalar(Object o) {
        if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character) return Json.value(o);
        if (o instanceof Enum) return Json.value(((Enum<?>) o).name());
        if (o instanceof UUID) return Json.value(o.toString());
        return Json.obj("class", s.namedClass(o));
    }
    private List<Object> list(Object source) {
        List<Object> result = new ArrayList<>();
        if (source instanceof Iterable) for (Object value : (Iterable<?>) source) { if (result.size() >= 4096) break; result.add(value); }
        else if (source instanceof Map) for (Object value : ((Map<?, ?>) source).values()) { if (result.size() >= 4096) break; result.add(value); }
        return result;
    }
    private String registryId(String registry, Object object) {
        for (String cls : new String[]{"net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.Registry"}) {
            try {
                Object reg = s.field(s.type(cls), registry);
                Object key = s.call(reg, "getKey", object);
                if (key != null) return key.toString();
            } catch (UnsupportedOperationException ignored) {}
        }
        return Json.limited(String.valueOf(object), 512);
    }
    private JsonObject entity(Object e) {
        JsonObject out = Json.obj("id", s.optional(e, "getId"), "uuid", text(s.optional(e, "getUUID")),
                "type", registryId("ENTITY_TYPE", s.optional(e, "getType")), "name", text(s.optional(e, "getName")), "position", position(e),
                "yaw", get(e, "getYRot", "yRot"), "pitch", get(e, "getXRot", "xRot"),
                "alive", s.optional(e, "isAlive"), "invisible", s.optional(e, "isInvisible"),
                "onGround", get(e, "onGround", "onGround"), "health", s.optional(e, "getHealth"),
                "maxHealth", s.optional(e, "getMaxHealth"), "customName", text(s.optional(e, "getCustomName")));
        Object velocity = s.optional(e, "getDeltaMovement");
        out.add("velocity", velocity == null ? JsonNull.INSTANCE : position(velocity));
        JsonArray effects = new JsonArray();
        for (Object effect : list(s.optional(e, "getActiveEffects"))) effects.add(Json.obj(
                "effect", registryId("MOB_EFFECT", s.optional(effect, "getEffect")),
                "duration", s.optional(effect, "getDuration"), "amplifier", s.optional(effect, "getAmplifier"),
                "ambient", s.optional(effect, "isAmbient"), "visible", s.optional(effect, "isVisible")));
        out.add("effects", effects);
        return out;
    }
    public JsonObject playerInfo() {
        Object p = player(); JsonObject out = entity(p);
        Object food = s.optional(p, "getFoodData");
        Object abilities = get(p, "getAbilities", "abilities");
        out.add("food", Json.obj("level", s.optional(food, "getFoodLevel"), "saturation", s.optional(food, "getSaturationLevel")));
        out.add("experience", Json.obj("level", s.optionalField(p, "experienceLevel"), "progress", s.optionalField(p, "experienceProgress"), "total", s.optionalField(p, "totalExperience")));
        out.add("abilities", Json.obj("flying", s.optionalField(abilities, "flying"), "mayFly", s.optionalField(abilities, "mayfly"),
                "creative", s.optionalField(abilities, "instabuild"), "invulnerable", s.optionalField(abilities, "invulnerable"),
                "flyingSpeed", s.optional(abilities, "getFlyingSpeed"), "walkingSpeed", s.optional(abilities, "getWalkingSpeed")));
        out.add("selectedSlot", scalar(get(inventory(), "getSelectedSlot", "selected")));
        out.add("gameMode", scalar(s.optional(s.optionalField(mc, "gameMode"), "getPlayerMode")));
        out.add("dimension", Json.value(text(s.optional(world(), "dimension"))));
        out.add("air", scalar(s.optional(p, "getAirSupply")));
        out.add("armor", scalar(s.optional(p, "getArmorValue")));
        out.add("sprinting", scalar(s.optional(p, "isSprinting")));
        out.add("sneaking", scalar(s.optional(p, "isShiftKeyDown")));
        out.add("usingItem", scalar(s.optional(p, "isUsingItem")));
        return out;
    }
    private JsonObject item(Object stack) {
        if (stack == null) return Json.obj("empty", true);
        Object raw = s.optional(stack, "getItem");
        JsonObject out = Json.obj("id", registryId("ITEM", raw), "count", s.optional(stack, "getCount"),
                "empty", s.optional(stack, "isEmpty"), "name", text(s.optional(stack, "getHoverName")),
                "damage", s.optional(stack, "getDamageValue"), "maxDamage", s.optional(stack, "getMaxDamage"),
                "maxStackSize", s.optional(stack, "getMaxStackSize"), "enchanted", s.optional(stack, "isEnchanted"));
        Object tag = s.optional(stack, "getTag");
        Object components = s.optional(stack, "getComponents");
        out.add("nbt", tag == null ? JsonNull.INSTANCE : Json.value(Json.limited(tag.toString(), 16384)));
        out.add("components", components == null ? JsonNull.INSTANCE : Json.value(Json.limited(components.toString(), 16384)));
        return out;
    }
    public JsonObject inventoryInfo() {
        Object inv = inventory(); int size = ((Number) s.call(inv, "getContainerSize")).intValue();
        JsonArray result = new JsonArray();
        for (int i = 0; i < Math.min(size, 256); i++) { JsonObject row = item(s.call(inv, "getItem", i)); row.addProperty("slot", i); result.add(row); }
        return Json.obj("selectedSlot", get(inv, "getSelectedSlot", "selected"), "slots", result,
                "mainHand", item(s.optional(player(), "getMainHandItem")), "offHand", item(s.optional(player(), "getOffhandItem")));
    }
    public JsonObject worldInfo() {
        Object w = world(); Object p = player(); Object pos = s.optional(p, "blockPosition");
        if (pos == null) { JsonObject v = position(p); pos = s.create("net.minecraft.core.BlockPos", (int) Math.floor(v.get("x").getAsDouble()), (int) Math.floor(v.get("y").getAsDouble()), (int) Math.floor(v.get("z").getAsDouble())); }
        Object biome = s.optional(w, "getBiome", pos);
        Object b = s.optional(biome, "value"); if (b == null) b = biome;
        return Json.obj("dimension", text(s.optional(w, "dimension")), "gameTime", s.optional(w, "getGameTime"),
                "dayTime", s.optional(w, "getDayTime"), "raining", s.optional(w, "isRaining"), "thundering", s.optional(w, "isThundering"),
                "difficulty", text(s.optional(w, "getDifficulty")), "minY", first(s.optional(w, "getMinY"), s.optional(w, "getMinBuildHeight")),
                "maxY", first(s.optional(w, "getMaxY"), s.optional(w, "getMaxBuildHeight")), "seaLevel", s.optional(w, "getSeaLevel"),
                "biome", registryId("BIOME", b), "biomeHolder", text(biome), "hardcore", s.optional(s.optional(w, "getLevelData"), "isHardcore"),
                "border", inspectScalars(s.optional(w, "getWorldBorder"), 0, 60), "source", "client-loaded state; no server-only data");
    }
    private static Object first(Object a, Object b) { return a == null ? b : a; }
    private Object blockPos(JsonObject args) {
        return s.create("net.minecraft.core.BlockPos", Json.integer(args, "x", 0, -30000000, 30000000),
                Json.integer(args, "y", 0, -2048, 2048), Json.integer(args, "z", 0, -30000000, 30000000));
    }
    public JsonObject block(JsonObject args) { return block(blockPos(args), Json.bool(args, "includeData", true)); }
    Object loadedClientChunk(int x, int z) {
        Object full;
        try { full = s.field(s.type("net.minecraft.world.level.chunk.status.ChunkStatus"), "FULL"); }
        catch (UnsupportedOperationException old) { full = s.field(s.type("net.minecraft.world.level.chunk.ChunkStatus"), "FULL"); }
        return s.call(s.call(world(), "getChunkSource"), "getChunk", x, z, full, false);
    }
    private JsonObject block(Object pos, boolean data) {
        Object w = world();
        JsonObject point = position(pos);
        if (loadedClientChunk(point.get("x").getAsInt() >> 4, point.get("z").getAsInt() >> 4) == null)
            return Json.obj("position", point, "loaded", false);
        Object state = s.call(w, "getBlockState", pos);
        Object raw = s.call(state, "getBlock");
        JsonObject out = Json.obj("position", position(pos), "loaded", true, "id", registryId("BLOCK", raw),
                "state", Json.limited(state.toString(), 4096), "air", s.optional(state, "isAir"),
                "destroySpeed", s.optional(state, "getDestroySpeed", w, pos), "light", s.optional(w, "getMaxLocalRawBrightness", pos));
        if (data) {
            Object be = s.optional(w, "getBlockEntity", pos);
            if (be != null) {
                Object tag = s.optional(be, "saveWithFullMetadata");
                if (tag == null) tag = s.optional(be, "saveWithFullMetadata", s.optional(w, "registryAccess"));
                if (tag == null) tag = s.optional(be, "saveWithoutMetadata", s.optional(w, "registryAccess"));
                out.add("blockEntity", Json.obj("class", s.namedClass(be), "nbt", tag == null ? null : Json.limited(tag.toString(), 32768),
                        "fields", inspectScalars(be, 0, 80), "scope", "Only data already synced to this client"));
            }
        }
        return out;
    }
    public JsonObject scan(JsonObject args) {
        int radius = Json.integer(args, "radius", 3, 0, 8), limit = Json.integer(args, "limit", 256, 1, 4096);
        JsonObject center = args.has("x") ? args : position(player());
        int x = (int) Math.floor(center.get("x").getAsDouble()), y = (int) Math.floor(center.get("y").getAsDouble()), z = (int) Math.floor(center.get("z").getAsDouble());
        String filter = Json.string(args, "idContains", "");
        JsonArray results = new JsonArray(); int examined = 0; boolean truncated = false;
        outer: for (int dy = -radius; dy <= radius; dy++) for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
            JsonObject row = block(s.create("net.minecraft.core.BlockPos", x + dx, y + dy, z + dz), false); examined++;
            if (!row.get("loaded").getAsBoolean()) continue;
            if (!filter.isEmpty() && !row.get("id").getAsString().contains(filter)) continue;
            if (Json.bool(args, "excludeAir", true) && row.has("air") && !row.get("air").isJsonNull() && row.get("air").getAsBoolean()) continue;
            if (results.size() >= limit) { truncated = true; break outer; }
            results.add(row);
        }
        return Json.obj("blocks", results, "examined", examined, "truncated", truncated, "radius", radius);
    }
    public JsonObject entities(JsonObject args) {
        int limit = Json.integer(args, "limit", 64, 1, 256);
        double radius = Json.number(args, "radius", 32, 0, 256); String filter = Json.string(args, "typeContains", "");
        Object loaded = s.call(world(), "entitiesForRendering");
        Object p = player(); JsonArray result = new JsonArray(); int matched = 0;
        for (Object e : list(loaded)) {
            Number distance = (Number) s.optional(p, "distanceToSqr", e);
            if (distance != null && distance.doubleValue() > radius * radius) continue;
            if (!filter.isEmpty() && !registryId("ENTITY_TYPE", s.optional(e, "getType")).contains(filter)) continue;
            matched++; if (result.size() < limit) result.add(entity(e));
        }
        return Json.obj("entities", result, "matched", matched, "truncated", matched > limit, "scope", "loaded client entities (including entities behind blocks)");
    }
    public JsonObject target() {
        Object hit = s.optionalField(mc, "hitResult");
        if (hit == null) return Json.obj("type", "MISS");
        JsonObject out = Json.obj("type", text(s.optional(hit, "getType")), "location", position(s.optional(hit, "getLocation")));
        Object entity = s.optional(hit, "getEntity"); if (entity != null) out.add("entity", entity(entity));
        Object pos = s.optional(hit, "getBlockPos"); if (pos != null) out.add("block", block(pos, true));
        out.add("face", Json.value(text(s.optional(hit, "getDirection"))));
        return out;
    }
    public JsonObject screenInfo() {
        Object screen = screen(); JsonArray widgets = new JsonArray();
        for (Object child : list(s.optional(screen, "children"))) {
            JsonObject widget = Json.obj("index", widgets.size(), "class", s.namedClass(child), "message", text(s.optional(child, "getMessage")),
                    "x", get(child, "getX", "x"), "y", get(child, "getY", "y"), "width", s.optional(child, "getWidth"),
                    "height", get(child, "getHeight", "height"), "active", s.optionalField(child, "active"), "visible", s.optionalField(child, "visible"),
                    "value", s.optional(child, "getValue"));
            JsonArray entries = new JsonArray();
            for (Object entry : list(s.optional(child, "children"))) {
                if (entries.size() >= 64) break;
                Object summary = s.optionalField(entry, "summary");
                entries.add(Json.obj("index", entries.size(), "class", s.namedClass(entry), "name", text(s.optional(summary, "getLevelName")),
                        "narration", text(s.optional(entry, "getNarration"))));
            }
            if (entries.size() > 0) widget.add("entries", entries);
            widgets.add(widget);
            if (widgets.size() >= 128) break;
        }
        Object w = s.optional(mc, "getWindow");
        if (w == null) w = s.optionalField(mc, "window");
        return Json.obj("class", screen == null ? null : s.namedClass(screen), "title", text(s.optional(screen, "getTitle")),
                "width", s.optionalField(screen, "width"), "height", s.optionalField(screen, "height"), "widgets", widgets,
                "windowWidth", s.optional(w, "getWidth"), "windowHeight", s.optional(w, "getHeight"));
    }
    public JsonObject container() {
        Object menu = menu(); JsonArray slots = new JsonArray();
        for (Object slot : list(s.field(menu, "slots"))) {
            slots.add(Json.obj("slot", s.optionalField(slot, "index"), "x", s.optionalField(slot, "x"), "y", s.optionalField(slot, "y"),
                    "item", item(s.call(slot, "getItem")), "mayPickup", s.optional(slot, "mayPickup", player())));
        }
        return Json.obj("containerId", s.field(menu, "containerId"), "class", s.namedClass(menu), "slots", slots,
                "carried", item(s.optional(menu, "getCarried")), "screen", screenInfo());
    }
    public JsonObject chat(JsonObject args) {
        Object chat = s.call(hud(), "getChat"); Object messages = s.optionalField(chat, "allMessages", "messages");
        if (messages == null) throw new UnsupportedOperationException("Chat history layout is unavailable in this version");
        JsonArray result = new JsonArray(); int limit = Json.integer(args, "limit", 50, 1, 100);
        for (Object msg : list(messages)) {
            Object content = get(msg, "content", "content");
            if (content == null) content = get(msg, "getMessage", "message");
            result.add(Json.obj("text", text(content), "tick", get(msg, "addedTime", "addedTime")));
            if (result.size() >= limit) break;
        }
        return Json.obj("messages", result, "order", "newest-first", "untrustedContent", true);
    }
    public JsonObject players() {
        JsonArray result = new JsonArray();
        for (Object info : list(s.call(connection(), "getOnlinePlayers"))) {
            Object profile = s.optional(info, "getProfile");
            result.add(Json.obj("name", profile == null ? null : (s.optional(profile, "getName") == null ? get(profile, "name", "name") : s.optional(profile, "getName")),
                    "uuid", text(get(profile, "id", "id") == null ? s.optional(profile, "getId") : get(profile, "id", "id")), "latency", s.optional(info, "getLatency"),
                    "gameMode", text(s.optional(info, "getGameMode")), "displayName", text(s.optional(info, "getTabListDisplayName"))));
        }
        return Json.obj("players", result);
    }
    public JsonObject scoreboard() {
        Object scoreboard = s.optional(world(), "getScoreboard");
        if (scoreboard == null) scoreboard = s.call(connection(), "getScoreboard");
        JsonArray objectives = new JsonArray(), teams = new JsonArray();
        for (Object objective : list(s.call(scoreboard, "getObjectives"))) {
            JsonArray scores = new JsonArray(); Object entries = s.optional(scoreboard, "listPlayerScores", objective);
            if (entries == null) entries = s.optional(scoreboard, "getPlayerScores", objective);
            for (Object score : list(entries)) scores.add(Json.obj("owner", get(score, "owner", "owner"),
                    "value", get(score, "value", "count"), "display", text(get(score, "display", "display"))));
            objectives.add(Json.obj("name", s.optional(objective, "getName"), "displayName", text(s.optional(objective, "getDisplayName")), "scores", scores));
        }
        for (Object team : list(s.optional(scoreboard, "getPlayerTeams"))) teams.add(Json.obj("name", s.optional(team, "getName"),
                "displayName", text(s.optional(team, "getDisplayName")), "members", Json.value(list(s.optional(team, "getPlayers"))),
                "prefix", text(s.optional(team, "getPlayerPrefix")), "suffix", text(s.optional(team, "getPlayerSuffix"))));
        return Json.obj("objectives", objectives, "teams", teams);
    }
    public JsonObject bossBars() {
        Object overlay = s.call(hud(), "getBossOverlay"); Object source = s.field(overlay, "events");
        JsonArray result = new JsonArray();
        for (Object event : list(source)) result.add(Json.obj("name", text(s.optional(event, "getName")), "progress", s.optional(event, "getProgress"),
                "color", text(s.optional(event, "getColor")), "overlay", text(s.optional(event, "getOverlay")), "id", text(s.optional(event, "getId"))));
        return Json.obj("bossBars", result);
    }
    private JsonObject inspectScalars(Object root, int offset, int limit) {
        JsonObject out = new JsonObject(); if (root == null) return out;
        List<String> names = s.fieldNames(root); int emitted = 0;
        for (int i = offset; i < names.size() && emitted < limit; i++) {
            String name = names.get(i);
            if (name.toLowerCase(Locale.ROOT).matches(".*(token|session|password|secret|connection|profilekey|userapi|service|minecraft|server|network|proxy).*")) continue;
            Object value = s.optionalField(root, name);
            if (value instanceof String) value = Json.limited((String) value, 4096);
            out.add(name, scalar(value)); emitted++;
        }
        return out;
    }
    public JsonObject inspect(JsonObject args) {
        String root = Json.string(args, "root", "player"); Object target;
        switch (root) {
            case "player": target = player(); break;
            case "inventory": target = inventory(); break;
            case "world": target = world(); break;
            case "screen": target = screen(); break;
            case "container": target = menu(); break;
            case "target_entity": target = s.optional(s.optionalField(mc, "hitResult"), "getEntity"); break;
            default: throw new IllegalArgumentException("Unknown inspection root");
        }
        return Json.obj("class", s.namedClass(target), "fields", inspectScalars(target,
                Json.integer(args, "offset", 0, 0, 10000), Json.integer(args, "limit", 100, 1, 256)),
                "note", "Scalars only. Object references are described by class and cannot be traversed.");
    }
    public JsonObject registry(JsonObject args) {
        String registry = Json.string(args, "registry", "ITEM"); Object reg = null;
        for (String cls : new String[]{"net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.Registry"}) {
            try { reg = s.field(s.type(cls), registry); break; } catch (UnsupportedOperationException ignored) {}
        }
        if (reg == null) throw new UnsupportedOperationException("Registry unavailable: " + registry);
        Collection<?> keys = (Collection<?>) s.call(reg, "keySet"); List<String> values = new ArrayList<>();
        String filter = Json.string(args, "contains", "");
        for (Object key : keys) if (key.toString().contains(filter)) values.add(key.toString());
        Collections.sort(values);
        int offset = Json.integer(args, "offset", 0, 0, 100000), limit = Json.integer(args, "limit", 100, 1, 1000);
        return Json.obj("values", values.subList(Math.min(offset, values.size()), Math.min(offset + limit, values.size())),
                "total", values.size(), "nextOffset", Math.min(offset + limit, values.size()));
    }
    public JsonObject recipes(JsonObject args) {
        Object manager = s.optional(world(), "getRecipeManager");
        if (manager == null) manager = s.optional(connection(), "getRecipeManager");
        Object source = s.optional(manager, "getRecipes");
        if (source == null) source = s.optionalField(manager, "recipes");
        boolean displayOnly = false;
        if (source == null) {
            Object book = s.optional(player(), "getRecipeBook");
            if (book == null) book = s.optionalField(player(), "recipeBook");
            source = s.optionalField(book, "known"); displayOnly = true;
        }
        if (source == null) throw new UnsupportedOperationException("Recipe payloads are not exposed by this version's client; use the recipe book UI");
        JsonArray result = new JsonArray(); List<Object> flat = new ArrayList<>();
        for (Object row : list(source)) { if (row instanceof Map) flat.addAll(list(row)); else flat.add(row); }
        int offset = Json.integer(args, "offset", 0, 0, 100000), limit = Json.integer(args, "limit", 100, 1, 500);
        for (int i = offset; i < flat.size() && result.size() < limit; i++) {
            Object holder = flat.get(i); Object recipe = s.optional(holder, "value"); if (recipe == null) recipe = holder;
            Object id = s.optional(holder, "id"); if (id == null) id = s.optional(recipe, "getId");
            Object output = s.optional(recipe, "getResultItem"); if (output == null) output = s.optional(recipe, "getResultItem", s.optional(world(), "registryAccess"));
            Object display = s.optional(recipe, "display");
            result.add(Json.obj("id", text(id), "type", text(s.optional(recipe, "getType")), "result", output == null ? null : item(output),
                    "display", display == null ? null : Json.limited(display.toString(), 8192)));
        }
        return Json.obj("recipes", result, "nextOffset", offset + result.size(), "total", flat.size(), "displayOnly", displayOnly, "scope", "recipes available in the client cache");
    }
    public JsonObject equipment() {
        JsonObject out = new JsonObject();
        for (Object slot : s.type("net.minecraft.world.entity.EquipmentSlot").getEnumConstants())
            out.add(((Enum<?>) slot).name(), item(s.optional(player(), "getItemBySlot", slot)));
        return Json.obj("equipment", out);
    }
    public JsonObject advancements(JsonObject args) {
        Object manager = s.call(connection(), "getAdvancements");
        Object source = s.field(manager, "progress");
        if (!(source instanceof Map)) throw new UnsupportedOperationException("Advancement progress map unavailable");
        int offset = Json.integer(args, "offset", 0, 0, 100000), limit = Json.integer(args, "limit", 100, 1, 500);
        JsonArray out = new JsonArray(); int index = 0;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) source).entrySet()) {
            if (index++ < offset) continue; if (out.size() >= limit) break;
            Object progress = e.getValue(), holder = e.getKey(), adv = s.optional(holder, "value"); if (adv == null) adv = holder;
            Object id = s.optional(holder, "id"); if (id == null) id = s.optional(adv, "getId");
            Object display = s.optional(adv, "display"); if (display == null) display = s.optional(adv, "getDisplay");
            if (display instanceof Optional) display = ((Optional<?>) display).orElse(null);
            out.add(Json.obj("id", text(id), "done", s.optional(progress, "isDone"), "percent", s.optional(progress, "getPercent"),
                    "title", text(s.optional(display, "getTitle")), "completedCriteria", list(s.optional(progress, "getCompletedCriteria")),
                    "remainingCriteria", list(s.optional(progress, "getRemainingCriteria"))));
        }
        return Json.obj("advancements", out, "nextOffset", offset + out.size(), "total", ((Map<?, ?>) source).size(), "scope", "Only advancement data synced to this player");
    }
    public JsonObject statistics(JsonObject args) {
        Object counter = s.call(player(), "getStats"); Object values = s.field(counter, "stats");
        if (!(values instanceof Map)) throw new UnsupportedOperationException("Statistics layout unavailable");
        int offset = Json.integer(args, "offset", 0, 0, 100000), limit = Json.integer(args, "limit", 100, 1, 500);
        JsonArray out = new JsonArray(); int index = 0;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) values).entrySet()) {
            if (index++ < offset) continue; if (out.size() >= limit) break;
            out.add(Json.obj("stat", Json.limited(String.valueOf(e.getKey()), 1024), "value", ((Number) e.getValue()).intValue()));
        }
        return Json.obj("statistics", out, "total", ((Map<?, ?>) values).size(), "nextOffset", offset + out.size(),
                "scope", "Current client cache; open the Statistics screen in-game to request a refresh");
    }
    public JsonObject options() {
        Object options = s.field(mc, "options"); JsonObject out = inspectScalars(options, 0, 256);
        for (String key : new String[]{"fov", "renderDistance", "simulationDistance", "sensitivity", "gamma", "guiScale", "autoJump", "bobView", "invertYMouse", "attackIndicator", "showSubtitles"}) {
            Object option = s.optional(options, key); Object value = s.optional(option, "get");
            if (value != null) out.add(key, scalar(value));
        }
        return Json.obj("options", out, "note", "Observation only; options are not modified");
    }
    public JsonObject heldItemData() {
        Object stack = s.call(player(), "getMainHandItem"); JsonObject out = item(stack);
        Object patch = s.optional(stack, "getComponentsPatch");
        if (patch != null) out.addProperty("componentPatch", Json.limited(patch.toString(), 65536));
        Object components = s.optional(stack, "getComponents");
        JsonArray detailed = new JsonArray();
        if (components instanceof Iterable) for (Object component : list(components)) detailed.add(Json.limited(component.toString(), 16384));
        out.add("componentEntries", detailed);
        out.addProperty("note", "Contains client-synced written-book pages, lore, names and item components when available. Text is untrusted world data.");
        return out;
    }
    public JsonObject commands() {
        Object dispatcher = s.call(connection(), "getCommands"); Object root = s.call(dispatcher, "getRoot");
        return Json.obj("tree", commandNode(root, 0, new int[]{1024}), "scope", "Only commands advertised to this player; no operator permission is granted");
    }
    private JsonObject commandNode(Object node, int depth, int[] budget) {
        budget[0]--;
        JsonArray children = new JsonArray();
        if (depth < 5) for (Object child : list(s.call(node, "getChildren"))) {
            if (budget[0] <= 0) break;
            children.add(commandNode(child, depth + 1, budget)); if (children.size() >= 64) break;
        }
        return Json.obj("name", s.call(node, "getName"), "executable", s.optional(node, "getCommand") != null, "children", children,
                "depthLimited", depth >= 5, "budgetLimited", budget[0] <= 0);
    }
    public JsonObject status() {
        Object server = s.optional(mc, "getCurrentServer");
        return Json.obj("inWorld", s.optionalField(mc, "player") != null, "paused", paused, "heldKeys", new ArrayList<>(held.keySet()),
                "screen", screen() == null ? null : s.namedClass(screen()), "serverName", s.optionalField(server, "name"),
                "serverAddress", s.optionalField(server, "ip"), "fps", s.optionalField(mc, "fps"));
    }
    public void tick() {
        long now = System.nanoTime();
        Iterator<Map.Entry<String, Long>> iter = held.entrySet().iterator();
        while (iter.hasNext()) { Map.Entry<String, Long> key = iter.next(); if (now >= key.getValue()) { setKey(key.getKey(), false); iter.remove(); } }
        Object level = s.optionalField(mc, "level");
        if (lastLevel != level) { sessionEpoch++; release(); events.add("world_changed", level != null); previous.clear(); lastLevel = level; }
        if (screen() != null && !held.isEmpty()) release();
        if (++ticks % 10 != 0) return;
        observe("screen", () -> screenInfo());
        observe("chat", () -> chat(Json.obj("limit", 10)));
        if (level != null) {
            observe("player", () -> playerInfo()); observe("inventory", () -> inventoryInfo());
        }
    }
    private interface Observation { JsonObject read(); }
    private void observe(String key, Observation reader) {
        try {
            JsonObject value = reader.read(); String encoded = value.toString();
            if (!encoded.equals(previous.put(key, encoded))) events.add(key, value);
        } catch (RuntimeException ignored) { /* Optional observations do not break the game tick; direct tools report errors. */ }
    }
    private void setKey(String name, boolean down) {
        String field = KEYS.get(name); if (field == null) throw new IllegalArgumentException("Unknown control: " + name);
        Object mapping = s.field(s.field(mc, "options"), field);
        try { s.call(mapping, "setDown", down); }
        catch (UnsupportedOperationException legacy) { s.call(s.type("net.minecraft.client.KeyMapping"), "set", s.field(mapping, "key"), down); }
    }
    public void release() {
        for (String name : new ArrayList<>(held.keySet())) try { setKey(name, false); } catch (RuntimeException ignored) {}
        held.clear();
    }
    public void togglePause() { paused = !paused; release(); events.add("control_paused", paused); }
    public boolean paused() { return paused; }
    public long sessionEpoch() { return sessionEpoch; }
    public JsonObject action(String name, JsonObject a) {
        if (name.equals("release_controls")) { release(); return Json.obj("released", true); }
        if (name.equals("pause_control")) { if (!paused) togglePause(); return Json.obj("paused", true, "resume", "Press F8 in Minecraft"); }
        if (paused) throw new IllegalStateException("AI control is paused. Press F8 in Minecraft to resume.");
        Object p = s.optionalField(mc, "player");
        if (p == null && !name.equals("click_widget") && !name.equals("set_text") && !name.equals("select_list_entry")) throw new IllegalStateException("Join a world first");
        switch (name) {
            case "hold_controls": {
                if (screen() != null) throw new IllegalStateException("Close the current screen before moving");
                int duration = Json.integer(a, "durationMs", 250, 50, 2000);
                JsonArray keys = a.getAsJsonArray("keys");
                for (JsonElement k : keys) if (!KEYS.containsKey(k.getAsString())) throw new IllegalArgumentException("Unknown control " + k);
                release();
                for (JsonElement k : keys) { String key = k.getAsString(); setKey(key, true); held.put(key, System.nanoTime() + duration * 1_000_000L); }
                return Json.obj("held", keys, "leaseMs", duration);
            }
            case "look": {
                double yaw = Json.number(a, "yaw", 0, -360000, 360000), pitch = Json.number(a, "pitch", 0, -90, 90);
                try { s.call(p, "setYRot", (float) yaw); } catch (UnsupportedOperationException e) { s.set(p, "yRot", (float) yaw); }
                try { s.call(p, "setXRot", (float) pitch); } catch (UnsupportedOperationException e) { s.set(p, "xRot", (float) pitch); }
                return Json.obj("yaw", yaw, "pitch", pitch);
            }
            case "select_hotbar": {
                int slot = Json.integer(a, "slot", 0, 0, 8); Object inv = inventory();
                try { s.call(inv, "setSelectedSlot", slot); } catch (UnsupportedOperationException e) { s.set(inv, "selected", slot); }
                return Json.obj("selectedSlot", slot);
            }
            case "attack": requireGameplay(); s.call(mc, "startAttack"); break;
            case "use_item": requireGameplay(); s.call(mc, "startUseItem"); break;
            case "stop_using_item": s.call(s.field(mc, "gameMode"), "releaseUsingItem", p); break;
            case "drop_item": requireGameplay(); s.call(p, "drop", Json.bool(a, "wholeStack", false)); break;
            case "send_chat": {
                String message = Json.string(a, "message", "");
                if (message.isEmpty() || message.length() > 256 || message.startsWith("/") || message.contains("\n") || message.contains("\r"))
                    throw new IllegalArgumentException("Chat must be 1-256 characters, one line, without leading slash");
                try { s.call(connection(), "sendChat", message); } catch (UnsupportedOperationException e) { s.call(p, "chat", message); }
                break;
            }
            case "send_command": {
                String command = Json.string(a, "command", ""); if (command.startsWith("/")) command = command.substring(1);
                if (command.isEmpty() || command.length() > 256 || command.contains("\n") || command.contains("\r")) throw new IllegalArgumentException("Command must be a single line, 1-256 characters");
                try { s.call(connection(), "sendCommand", command); } catch (UnsupportedOperationException e) { s.call(p, "chat", "/" + command); }
                break;
            }
            case "open_inventory": release(); setScreen(s.create("net.minecraft.client.gui.screens.inventory.InventoryScreen", p)); break;
            case "close_screen": release(); s.call(p, "closeContainer"); setScreen(null); break;
            case "click_slot": {
                Object menu = menu(); int currentId = ((Number) s.field(menu, "containerId")).intValue();
                int expected = Json.integer(a, "containerId", -1, 0, Integer.MAX_VALUE);
                if (expected != currentId) throw new IllegalStateException("Container changed; read get_container and retry with its current containerId");
                int slot = Json.integer(a, "slot", 0, -999, 255); int button = Json.integer(a, "button", 0, 0, 8);
                List<Object> slots = list(s.field(menu, "slots"));
                if (slot != -999 && (slot < 0 || slot >= slots.size())) throw new IllegalArgumentException("Invalid slot");
                Object click = s.constant("net.minecraft.world.inventory.ClickType", Json.string(a, "clickType", "PICKUP"));
                s.call(s.field(mc, "gameMode"), "handleInventoryMouseClick", currentId, slot, button, click, p); break;
            }
            case "click_widget": {
                Object screen = screen();
                if (screen == null) throw new IllegalStateException("No open screen");
                String expected = Json.string(a, "screenClass", "");
                if (!s.namedClass(screen).equals(expected)) throw new IllegalStateException("Screen changed; read get_screen first");
                List<Object> children = list(s.call(screen, "children"));
                int index = Json.integer(a, "index", 0, 0, Math.max(0, children.size() - 1));
                if (children.isEmpty()) throw new IllegalArgumentException("No widgets");
                Object widget = children.get(index);
                if (Boolean.FALSE.equals(s.optionalField(widget, "active")) || Boolean.FALSE.equals(s.optionalField(widget, "visible"))) throw new IllegalStateException("Widget is disabled or hidden");
                try { s.call(widget, "onPress"); }
                catch (UnsupportedOperationException old) { s.call(widget, "onPress", s.create("net.minecraft.client.input.KeyEvent", 0, 0, 0)); }
                break;
            }
            case "set_text": {
                Object screen = screen();
                if (screen == null || !s.namedClass(screen).equals(Json.string(a, "screenClass", ""))) throw new IllegalStateException("Screen changed; read get_screen first");
                List<Object> children = list(s.call(screen, "children"));
                int index = Json.integer(a, "index", 0, 0, Math.max(0, children.size() - 1));
                String value = Json.string(a, "text", "");
                if (value.length() > 2048 || children.isEmpty()) throw new IllegalArgumentException("Invalid text field/value");
                s.call(children.get(index), "setValue", value); break;
            }
            case "select_list_entry": {
                Object screen = screen();
                if (screen == null || !s.namedClass(screen).equals(Json.string(a, "screenClass", ""))) throw new IllegalStateException("Screen changed; read get_screen first");
                List<Object> widgets = list(s.call(screen, "children"));
                int widgetIndex = Json.integer(a, "index", 0, 0, Math.max(0, widgets.size() - 1));
                if (widgets.isEmpty()) throw new IllegalArgumentException("No widgets");
                Object widget = widgets.get(widgetIndex); List<Object> entries = list(s.call(widget, "children"));
                int entryIndex = Json.integer(a, "entry", 0, 0, Math.max(0, entries.size() - 1));
                if (entries.isEmpty()) throw new IllegalArgumentException("Empty list");
                s.call(widget, "setSelected", entries.get(entryIndex));
                // Older world lists update Play/Edit buttons on keyboard selection, not in setSelected.
                if (s.namedClass(widget).equals("net.minecraft.client.gui.screens.worldselection.WorldSelectionList")) s.optional(widget, "moveSelection", 0);
                break;
            }
            case "respawn": s.call(p, "respawn"); break;
            default: throw new UnsupportedOperationException("Action not implemented: " + name);
        }
        events.add("action", Json.obj("name", name));
        return Json.obj("submitted", true, "action", name, "note", "Local input submitted; server acceptance is not implied. Observe state to confirm.");
    }
    private void requireGameplay() { if (screen() != null) throw new IllegalStateException("Close the current screen first"); }
    public CompletableFuture<JsonObject> screenshot(int maxWidth) {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        Consumer<Object> callback = image -> {
            try {
                int width = ((Number) s.call(image, "getWidth")).intValue(), height = ((Number) s.call(image, "getHeight")).intValue();
                if (width <= 0 || height <= 0 || width > 16384 || height > 16384) throw new IllegalStateException("Invalid framebuffer dimensions");
                int outWidth = Math.min(width, maxWidth), outHeight = Math.max(1, (int) ((long) height * outWidth / width));
                BufferedImage output = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
                boolean modernPixel = true;
                try { s.call(image, "getPixel", 0, 0); } catch (UnsupportedOperationException e) { modernPixel = false; }
                for (int y = 0; y < outHeight; y++) for (int x = 0; x < outWidth; x++) {
                    int px = x * width / outWidth, py = y * height / outHeight;
                    int pixel = ((Number) s.call(image, modernPixel ? "getPixel" : "getPixelRGBA", px, py)).intValue();
                    if (!modernPixel) pixel = (pixel & 0xff00ff00) | ((pixel & 255) << 16) | ((pixel >>> 16) & 255);
                    output.setRGB(x, y, pixel);
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(output, "png", bytes);
                result.complete(Json.obj("type", "image", "mimeType", "image/png", "data", Base64.getEncoder().encodeToString(bytes.toByteArray())));
            } catch (Throwable e) { result.completeExceptionally(e); }
            finally { try { s.call(image, "close"); } catch (RuntimeException ignored) {} }
        };
        try {
            Object target = s.call(mc, "getMainRenderTarget"); Class<?> screenshot = s.type("net.minecraft.client.Screenshot");
            try { Object image = s.call(screenshot, "takeScreenshot", target); callback.accept(image); }
            catch (UnsupportedOperationException old) {
                try { s.call(screenshot, "takeScreenshot", target, callback); }
                catch (UnsupportedOperationException legacy) {
                    Object image = s.call(screenshot, "takeScreenshot", s.field(target, "width"), s.field(target, "height"), target);
                    callback.accept(image);
                }
            }
        } catch (Throwable e) { result.completeExceptionally(e); }
        return result;
    }
}
