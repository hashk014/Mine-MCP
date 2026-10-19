package dev.minemcp.mcp;

import com.google.gson.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;

/** Fixed developer tools. Remote worlds use the ordinary player connection only. */
final class DeveloperAdapter {
    private final ClientAdapter client;
    private final Symbols s;
    private final Object mc;
    private final Config config;
    private final Events events;
    private final Map<String, JsonObject> checkpoints = new LinkedHashMap<>();
    private Object checkpointServer;

    DeveloperAdapter(ClientAdapter client, Config config, Events events) {
        this.client = client; this.s = client.s; this.mc = client.mc; this.config = config; this.events = events;
    }
    private Object player() {
        Object p = s.field(mc, "player"); if (p == null) throw new IllegalStateException("Join a world first"); return p;
    }
    private Object world() { player(); return s.field(mc, "level"); }
    private Object connection() { player(); return s.call(mc, "getConnection"); }
    private Object integrated() { return s.optional(mc, "getSingleplayerServer"); }
    private Object profileId(Object profile) {
        Object id = s.optional(profile, "getId"); return id == null ? s.optional(profile, "id") : id;
    }
    private boolean owner(Object server) {
        Object p = s.optionalField(mc, "player");
        Object profile = s.optional(server, "getSingleplayerProfile");
        // Before the profile API, only the real integrated server reports the local player's name.
        Object id = profileId(profile), playerId = s.optional(p, "getUUID");
        boolean match = id != null && id.equals(playerId);
        if (profile == null && server != null) {
            Object name = s.optional(server, "getSingleplayerName"), localName = s.optional(s.optional(p, "getGameProfile"), "getName");
            match = name != null && name.equals(localName);
        }
        return DeveloperPolicy.localOwner(p != null, server != null, match);
    }
    private String text(Object value) {
        Object string = s.optional(value, "getString");
        return Json.limited(String.valueOf(string == null ? value : string), 8192);
    }
    private Object pos(JsonObject a) {
        return s.create("net.minecraft.core.BlockPos", a.get("x").getAsInt(), a.get("y").getAsInt(), a.get("z").getAsInt());
    }
    private Object serverPlayer(Object server, UUID id) {
        Object p = s.call(s.call(server, "getPlayerList"), "getPlayer", id);
        if (p == null) throw new IllegalStateException("Local server player is no longer connected");
        return p;
    }
    private Object level(Object entity) {
        Object level = s.optional(entity, "serverLevel");
        if (level == null) level = s.optional(entity, "level");
        if (level == null) level = s.field(entity, "level");
        return level;
    }
    private JsonObject xyz(Object entity) {
        JsonObject value = new JsonObject();
        for (String axis : new String[]{"X", "Y", "Z"}) {
            Object n = s.optional(entity, "get" + axis);
            if (n == null) n = s.field(entity, axis.toLowerCase(Locale.ROOT));
            value.add(axis.toLowerCase(Locale.ROOT), Json.value(n));
        }
        return value;
    }
    private String dimension(Object level) {
        Object key = s.optional(level, "dimension"), id = s.optional(key, "location");
        if (id == null) id = s.optional(key, "identifier");
        if (id != null) return id.toString();
        // Legacy Dimension has a DimensionType with a registry name.
        Object type = s.optional(s.optionalField(level, "dimension"), "getType");
        Object name = s.optional(type, "getRegistryName");
        if (name != null) return name.toString();
        Object legacyId = s.optional(type, "getId");
        if (legacyId instanceof Number) {
            int v = ((Number) legacyId).intValue();
            return v == -1 ? "minecraft:the_nether" : v == 1 ? "minecraft:the_end" : "minecraft:overworld";
        }
        throw new UnsupportedOperationException("Dimension identifier unavailable in this version");
    }
    JsonObject session() {
        Object server = integrated(), p = s.optionalField(mc, "player"); boolean local = owner(server);
        Object level = s.optional(p, "getPermissionLevel");
        if(level == null) level = s.optional(s.optional(s.optional(p,"permissions"),"level"),"id");
        return Json.obj("mode", p == null ? "menu" : local ? "singleplayer_owner" : "multiplayer",
                "authority", local && config.allowSoloDeveloper ? "integrated_server_console" : "normal_player",
                "soloDeveloperEnabled", config.allowSoloDeveloper, "localOwner", local,
                "publishedToLan", local ? s.optional(server, "isPublished") : null,
                "reportedPermissionLevel", level,
                "remotePermissionSource", "Server-advertised command tree and server validation; no operator rights are granted",
                "serverReadsAvailable", local && config.allowSoloDeveloper,
                "paused", client.paused(), "sessionEpoch", client.sessionEpoch(),
                "note", "Remote servers never receive elevated console commands. Operator-only debug queries still require server permission.");
    }
    JsonObject debugInfo() {
        Runtime runtime = Runtime.getRuntime(); JsonArray mods = new JsonArray();
        FabricLoader.getInstance().getAllMods().forEach(mod -> {
            if (mods.size() < 512) mods.add(Json.obj("id", mod.getMetadata().getId(), "name", mod.getMetadata().getName(),
                    "version", mod.getMetadata().getVersion().getFriendlyString()));
        });
        return Json.obj("session", session(), "fps", s.optionalField(mc, "fps"), "javaVersion", System.getProperty("java.version"),
                "memory", Json.obj("usedBytes", runtime.totalMemory()-runtime.freeMemory(), "allocatedBytes", runtime.totalMemory(), "maxBytes", runtime.maxMemory()),
                "mods", mods, "options", client.options());
    }
    JsonObject chunkInfo(JsonObject a) {
        Object level = world(); JsonObject p = xyz(player());
        int x = Json.integer(a, "chunkX", ((int)Math.floor(p.get("x").getAsDouble())) >> 4, -1875000, 1875000);
        int z = Json.integer(a, "chunkZ", ((int)Math.floor(p.get("z").getAsDouble())) >> 4, -1875000, 1875000);
        Object chunk = client.loadedClientChunk(x,z);
        if (chunk == null) return Json.obj("chunkX", x, "chunkZ", z, "loaded", false);
        Object blockEntities = s.optional(chunk, "getBlockEntities");
        return Json.obj("chunkX", x, "chunkZ", z, "loaded", true, "empty", s.optional(chunk, "isEmpty"),
                "fullStatus", text(s.optional(chunk, "getFullStatus")), "blockEntities", blockEntities instanceof Map ? ((Map<?,?>)blockEntities).size() : null,
                "scope", "Already loaded client chunk; does not load or generate chunks");
    }
    CompletableFuture<JsonObject> execute(String tool, JsonObject a) {
        switch (tool) {
            case "get_session": return done(session());
            case "get_debug_info": return done(debugInfo());
            case "get_chunk_info": return done(chunkInfo(a));
            case "complete_command": return complete(a);
            case "dev_complete_command": {
                Object server=integrated();
                if(!owner(server) || !config.allowSoloDeveloper) return complete(a);
                long epoch=client.sessionEpoch();
                return onServer(server,false,p->{
                    Object source=s.call(server,"createCommandSourceStack");
                    source=s.call(source,"withEntity",p); source=s.call(source,"withLevel",level(p));
                    return suggestions(s.call(s.call(server,"getCommands"),"getDispatcher"),source,a,epoch,"Local integrated-server console command tree");
                }).thenCompose(pending->pending);
            }
            case "query_block_nbt": return query(a, false);
            case "query_entity_nbt": return query(a, true);
            case "look_at": {
                JsonObject p = client.playerInfo(); double dx=a.get("x").getAsDouble()-p.getAsJsonObject("position").get("x").getAsDouble();
                double dz=a.get("z").getAsDouble()-p.getAsJsonObject("position").get("z").getAsDouble();
                Object eye = s.optional(player(), "getEyeY");
                double eyeY = eye instanceof Number ? ((Number)eye).doubleValue() : p.getAsJsonObject("position").get("y").getAsDouble()+1.62;
                double dy=a.get("y").getAsDouble()-eyeY;
                return done(client.action("look", Json.obj("yaw", Math.toDegrees(Math.atan2(-dx,dz)), "pitch", -Math.toDegrees(Math.atan2(dy,Math.hypot(dx,dz))))));
            }
            case "request_statistics": {
                actionAllowed(); Object packet=s.create("net.minecraft.network.protocol.game.ServerboundClientCommandPacket",
                        s.constant("net.minecraft.network.protocol.game.ServerboundClientCommandPacket$Action", "REQUEST_STATS"));
                s.call(connection(), "send", packet);
                return done(Json.obj("submitted", true, "note", "Normal statistics request sent; poll get_statistics for the server reply"));
            }
            case "creative_set_slot": return done(creative(a));
            case "dev_get_server_info": case "dev_get_block": case "dev_get_entities":
            case "dev_checkpoint": case "dev_list_checkpoints": case "dev_restore_checkpoint":
                return local(tool, a);
            case "dev_save_world": {
                Object server=integrated();
                if(owner(server) && config.allowSoloDeveloper) return onServer(server,true,p->{
                    s.call(s.call(server,"getPlayerList"),"saveAll");
                    Object saved;
                    try { saved=s.call(server,"saveEverything",false,true,true); }
                    catch(UnsupportedOperationException old) { saved=s.call(server,"saveAllChunks",false,true,true); }
                    return Json.obj("completed",true,"saved",saved,"authority","integrated_server_console","note","Player and world save requested with flush; check game logs for storage errors");
                });
                return command(tool,a);
            }
            default:
                if (tool.startsWith("dev_")) return command(tool, a);
                throw new IllegalArgumentException("Unknown developer tool " + tool);
        }
    }
    private static CompletableFuture<JsonObject> done(JsonObject value) { return CompletableFuture.completedFuture(value); }
    private void actionAllowed() {
        if (!config.allowActions) throw new IllegalStateException("Actions are disabled in config");
        if (client.paused()) throw new IllegalStateException("AI control is paused. Press F8 in Minecraft to resume.");
        player();
    }
    private CompletableFuture<JsonObject> command(String tool, JsonObject a) {
        actionAllowed(); String command;
        switch (tool) {
            case "dev_command": command=Json.string(a,"command",""); break;
            case "dev_teleport": command="tp @s " + coordinates(a) + (a.has("yaw") ? " " + number(a.get("yaw"))+" "+number(a.get("pitch")) : ""); break;
            case "dev_give_item": command="give @s "+DeveloperPolicy.id(a.get("item").getAsString())+" "+Json.integer(a,"count",1,1,6400); break;
            case "dev_set_block": command="setblock "+coordinates(a)+" "+DeveloperPolicy.state(a.get("block").getAsString()); break;
            case "dev_fill": case "dev_clone": {
                DeveloperPolicy.volume(a.get("x1").getAsInt(),a.get("y1").getAsInt(),a.get("z1").getAsInt(),a.get("x2").getAsInt(),a.get("y2").getAsInt(),a.get("z2").getAsInt());
                String box=box(a);
                command=tool.equals("dev_fill") ? "fill "+box+" "+DeveloperPolicy.state(a.get("block").getAsString())+" "+Json.string(a,"mode","replace")
                        : "clone "+box+" "+coordinates(a)+" replace normal"; break;
            }
            case "dev_set_time": command="time set "+a.get("time").getAsInt(); break;
            case "dev_set_weather": command="weather "+a.get("weather").getAsString()+" "+Json.integer(a,"seconds",600,1,1000000); break;
            case "dev_set_gamemode": command="gamemode "+a.get("mode").getAsString()+" @s"; break;
            case "dev_summon": command="summon "+DeveloperPolicy.id(a.get("entity").getAsString())+" "+coordinates(a); break;
            case "dev_effect": command="effect give @s "+DeveloperPolicy.id(a.get("effect").getAsString())+" "+Json.integer(a,"seconds",30,1,1000000)+" "+Json.integer(a,"amplifier",0,0,255); break;
            case "dev_set_gamerule": {
                String key=a.get("rule").getAsString(), value=a.get("value").getAsString();
                if (!key.matches("[A-Za-z0-9_:./-]{1,128}") || !value.matches("(?:true|false|-?[0-9]{1,10})")) throw new IllegalArgumentException("Expected a gamerule ID and boolean/integer value");
                command="gamerule "+key+" "+value; break;
            }
            case "dev_run_function": command="function "+DeveloperPolicy.id(a.get("function").getAsString()); break;
            case "dev_save_world": command="save-all flush"; break;
            default: throw new IllegalArgumentException("Unknown developer command " + tool);
        }
        Object server=integrated(); boolean local=owner(server) && config.allowSoloDeveloper;
        command=DeveloperPolicy.command(command,local);
        if (!local) {
            if (command.length()>256) throw new IllegalArgumentException("Remote commands are limited to 256 characters");
            JsonObject sent=client.action("send_command",Json.obj("command",command));
            sent.addProperty("authority","normal_player"); sent.addProperty("command",command);
            return done(sent);
        }
        final String finalCommand=command;
        return onServer(server, true, p -> runCommand(server,p,level(p),finalCommand));
    }
    private static String number(JsonElement value) { return DeveloperPolicy.decimal(value.getAsDouble()); }
    private static String coordinates(JsonObject a) { return number(a.get("x"))+" "+number(a.get("y"))+" "+number(a.get("z")); }
    private static String box(JsonObject a) {
        return a.get("x1").getAsInt()+" "+a.get("y1").getAsInt()+" "+a.get("z1").getAsInt()+" "+a.get("x2").getAsInt()+" "+a.get("y2").getAsInt()+" "+a.get("z2").getAsInt();
    }
    private interface ServerTask<T> { T run(Object player); }
    private <T> CompletableFuture<T> onServer(Object server, boolean action, ServerTask<T> body) {
        if (!owner(server) || !config.allowSoloDeveloper) throw new IllegalStateException("This tool requires the local singleplayer owner and allowSoloDeveloper=true; unavailable on remote servers");
        if (action) actionAllowed();
        UUID id=(UUID)s.call(player(),"getUUID"); long epoch=client.sessionEpoch();
        return DeferredWork.submit(r -> s.call(server,"execute",r), () -> {
            if (epoch!=client.sessionEpoch() || !Boolean.TRUE.equals(s.call(server,"isRunning"))) throw new IllegalStateException("World changed before server execution; nothing was changed");
            if (action && (client.paused() || !config.allowActions)) throw new IllegalStateException("Control paused before server execution; nothing was changed");
            return body.run(serverPlayer(server,id));
        }, 5000);
    }
    private Object proxy(String name, java.lang.reflect.InvocationHandler handler) {
        Class<?> type=s.type(name); return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class) {
                if(method.getName().equals("equals")) return proxy==args[0];
                if(method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if(method.getName().equals("toString")) return "Mine MCP " + name;
            }
            return handler.invoke(proxy,method,args);
        });
    }
    private JsonObject runCommand(Object server,Object p,Object level,String command) {
        JsonArray messages=new JsonArray(), feedback=new JsonArray(); int[] result={Integer.MIN_VALUE}; boolean[] success={false}, failed={false};
        Object output=proxy("net.minecraft.commands.CommandSource",(proxy,method,args)->{
            if (method.getReturnType()==boolean.class) return !s.isNamedMethod(method,"shouldInformAdmins");
            if (args!=null && args.length>0 && messages.size()<100 && method.getReturnType()==void.class) {
                String value=text(args[0]); messages.add(value);
                Object key=s.optional(s.optional(args[0],"getContents"),"getKey"); if(key==null) key=s.optional(args[0],"getKey");
                feedback.add(Json.obj("text",value,"translationKey",key,"component",Json.limited(String.valueOf(args[0]),16384)));
                if("command.failed".equals(key)) failed[0]=true;
            }
            return null;
        });
        Object source=s.call(server,"createCommandSourceStack");
        try { source=s.call(source,"withSource",output); }
        catch(UnsupportedOperationException legacy) {
            source=s.create("net.minecraft.commands.CommandSourceStack",output,s.call(source,"getPosition"),s.call(source,"getRotation"),
                    s.call(source,"getLevel"),4,s.call(source,"getTextName"),s.call(source,"getDisplayName"),server,null);
        }
        source=s.call(source,"withEntity",p); source=s.call(source,"withLevel",level);
        JsonObject point=xyz(p); source=s.call(source,"withPosition",s.create("net.minecraft.world.phys.Vec3",point.get("x").getAsDouble(),point.get("y").getAsDouble(),point.get("z").getAsDouble()));
        try {
            Object callback=proxy("net.minecraft.commands.CommandResultCallback",(proxy,method,args)->{
                if (args!=null && args.length==2 && args[0] instanceof Boolean && args[1] instanceof Number) { success[0]=(Boolean)args[0]; result[0]=((Number)args[1]).intValue(); }
                else if(args!=null && args.length==1 && args[0] instanceof Number) { success[0]=true; result[0]=((Number)args[0]).intValue(); }
                else if(method.getReturnType()==void.class && (args==null || args.length==0)) { success[0]=false; result[0]=0; }
                return null;
            });
            source=s.call(source,"withCallback",callback);
        } catch (UnsupportedOperationException ignored) { /* Legacy performCommand returns the result directly. */ }
        Object commands=s.call(server,"getCommands");
        Object parsed=s.call(s.call(commands,"getDispatcher"),"parse",command,source);
        if(Boolean.TRUE.equals(s.call(s.call(parsed,"getReader"),"canRead")))
            throw new IllegalArgumentException("Command is unknown, not permitted, or has invalid arguments for this Minecraft version: " + Json.limited(command,256));
        try { s.call(commands,"performPrefixedCommand",source,command); }
        catch (UnsupportedOperationException old) {
            Object value=s.call(commands,"performCommand",source,command);
            if (value instanceof Number) { result[0]=((Number)value).intValue(); success[0]=result[0]>0; }
        }
        events.add("developer_command",Json.obj("command",command,"authority","integrated_server_console"));
        Boolean commandSuccess=failed[0] ? Boolean.FALSE : result[0]==Integer.MIN_VALUE ? null : Boolean.valueOf(success[0]);
        return Json.obj("completed",true,"authority","integrated_server_console","command",command,
                "success",commandSuccess,"result",result[0]==Integer.MIN_VALUE ? null : result[0],
                "messages",messages,"feedback",feedback,"untrustedContent",true,"note","Observe world state to confirm effects; command feedback is bounded to 100 messages");
    }
    private CompletableFuture<JsonObject> local(String tool,JsonObject a) {
        Object server=integrated();
        return onServer(server,tool.equals("dev_restore_checkpoint"),p->{
            Object w=level(p);
            switch(tool) {
                case "dev_get_server_info": {
                    JsonArray dimensions=new JsonArray();
                    for(Object l:(Iterable<?>)s.call(server,"getAllLevels")) dimensions.add(Json.obj("id",dimension(l),"seed",s.optional(l,"getSeed")));
                    Object players=s.call(s.call(server,"getPlayerList"),"getPlayers");
                    return Json.obj("authority","integrated_server_console","tick",s.optional(server,"getTickCount"),
                            "averageTickNanos",s.optional(server,"getAverageTickTimeNanos"),"averageTickMillis",s.optional(server,"getAverageTickTime"),
                            "players",players instanceof Collection ? ((Collection<?>)players).size() : null,"dimensions",dimensions,"scope","Local integrated server only");
                }
                case "dev_get_block": {
                    Object at=pos(a);
                    if (!Boolean.TRUE.equals(s.call(w,"hasChunkAt",at))) return Json.obj("loaded",false,"note","Does not force-load chunks");
                    Object state=s.call(w,"getBlockState",at), be=s.optional(w,"getBlockEntity",at);
                    return Json.obj("loaded",true,"state",state.toString(),"blockEntityNbt",be==null ? null : blockNbt(be,w),"scope","Authoritative local server state");
                }
                case "dev_get_entities": {
                    double radius=Json.number(a,"radius",32,0,128); int limit=Json.integer(a,"limit",32,1,128); JsonArray out=new JsonArray();
                    JsonObject origin=xyz(p); int encodedBytes=0, visited=0;
                    Object loaded=s.optional(w,"getAllEntities");
                    if(loaded==null) {
                        Object byId=s.optionalField(w,"entitiesById");
                        if(byId instanceof Map) loaded=((Map<?,?>)byId).values();
                    }
                    if(!(loaded instanceof Iterable)) throw new UnsupportedOperationException("Loaded server entity index unavailable in this version");
                    for(Object e:(Iterable<?>)loaded) {
                        if(++visited>4096) return Json.obj("entities",out,"truncated",true,"scope","Local server loaded entities; 4096 inspected-entity budget");
                        JsonObject point=xyz(e); double d=0;
                        for(String axis:new String[]{"x","y","z"}) { double v=origin.get(axis).getAsDouble()-point.get(axis).getAsDouble(); d+=v*v; }
                        if (d>radius*radius) continue;
                        if (out.size()>=limit) return Json.obj("entities",out,"truncated",true,"scope","Local server loaded entities");
                        String nbt=entityNbt(e,w); encodedBytes+=nbt.length();
                        if(encodedBytes>524288) return Json.obj("entities",out,"truncated",true,"scope","Local server loaded entities; 512 KiB text budget");
                        out.add(Json.obj("id",s.optional(e,"getId"),"uuid",text(s.optional(e,"getUUID")),"position",point,"nbt",nbt));
                    }
                    return Json.obj("entities",out,"truncated",false,"scope","Local server loaded entities");
                }
                default: {
                    if (checkpointServer!=server) { checkpoints.clear(); checkpointServer=server; }
                    if (tool.equals("dev_list_checkpoints")) return Json.obj("checkpoints",checkpoints,"scope","Positions only, in-memory for this local server session");
                    String name=Json.string(a,"name","");
                    if (!name.matches("[A-Za-z0-9_.-]{1,48}")) throw new IllegalArgumentException("Checkpoint name must be 1-48 letters, digits, dots, underscores or dashes");
                    if (tool.equals("dev_checkpoint")) {
                        if (checkpoints.size()>=32 && !checkpoints.containsKey(name)) throw new IllegalStateException("At most 32 checkpoints per local session");
                        JsonObject point=xyz(p); point.addProperty("dimension",dimension(w));
                        point.add("yaw",Json.value(s.optional(p,"getYRot")!=null ? s.optional(p,"getYRot") : s.field(p,"yRot")));
                        point.add("pitch",Json.value(s.optional(p,"getXRot")!=null ? s.optional(p,"getXRot") : s.field(p,"xRot")));
                        checkpoints.put(name,point); return Json.obj("name",name,"checkpoint",point,"scope","Position and view only; not a world/inventory backup");
                    }
                    JsonObject point=checkpoints.get(name);
                    if (point==null) throw new IllegalArgumentException("Unknown checkpoint");
                    Object destination=null;
                    for(Object l:(Iterable<?>)s.call(server,"getAllLevels")) if(dimension(l).equals(point.get("dimension").getAsString())) destination=l;
                    if(destination==null) throw new IllegalStateException("Checkpoint dimension is no longer available");
                    return runCommand(server,p,destination,"tp @s "+coordinates(point)+" "+number(point.get("yaw"))+" "+number(point.get("pitch")));
                }
            }
        });
    }
    private String blockNbt(Object be,Object w) {
        Object tag=s.optional(be,"saveWithFullMetadata",s.optional(w,"registryAccess"));
        if(tag==null) tag=s.optional(be,"saveWithFullMetadata");
        if(tag==null) tag=s.optional(be,"save",s.create("net.minecraft.nbt.CompoundTag"));
        if(tag==null) throw new UnsupportedOperationException("Block entity serialization unavailable");
        return Json.limited(tag.toString(),65536);
    }
    private String entityNbt(Object e,Object w) {
        try {
            Object tag=s.create("net.minecraft.nbt.CompoundTag"); s.call(e,"saveWithoutId",tag); return Json.limited(tag.toString(),65536);
        } catch(UnsupportedOperationException modern) {
            Object reporter=s.field(s.type("net.minecraft.util.ProblemReporter"),"DISCARDING");
            Object output=s.call(s.type("net.minecraft.world.level.storage.TagValueOutput"),"createWithContext",reporter,s.call(w,"registryAccess"));
            s.call(e,"saveWithoutId",output); return Json.limited(s.call(output,"buildResult").toString(),65536);
        }
    }
    private CompletableFuture<JsonObject> complete(JsonObject a) {
        Object con=connection();
        return suggestions(s.call(con,"getCommands"),s.call(con,"getSuggestionsProvider"),a,client.sessionEpoch(),"Player's server-advertised commands; may issue a normal tab-completion request");
    }
    private CompletableFuture<JsonObject> suggestions(Object dispatcher,Object provider,JsonObject a,long epoch,String scope) {
        String command=Json.string(a,"command",""); if(command.startsWith("/")) command=command.substring(1);
        if(command.length()>256 || command.indexOf('\n')>=0 || command.indexOf('\r')>=0) throw new IllegalArgumentException("One command prefix, at most 256 characters");
        Object parsed=s.call(dispatcher,"parse",command,provider);
        int cursor=Json.integer(a,"cursor",command.length(),0,command.length());
        CompletableFuture<?> pending=(CompletableFuture<?>)s.call(dispatcher,"getCompletionSuggestions",parsed,cursor);
        return pending.thenApply(suggestions->{
            if(epoch!=client.sessionEpoch()) throw new IllegalStateException("World changed while waiting for suggestions");
            JsonArray rows=new JsonArray();
            for(Object entry:(List<?>)s.call(suggestions,"getList")) {
                if(rows.size()>=100) break; Object range=s.call(entry,"getRange");
                rows.add(Json.obj("text",s.call(entry,"getText"),"start",s.call(range,"getStart"),"end",s.call(range,"getEnd")));
            }
            return Json.obj("suggestions",rows,"scope",scope);
        });
    }
    private CompletableFuture<JsonObject> query(JsonObject a,boolean entity) {
        // The vanilla debug-query handler has one outstanding transaction, so serialize these requests.
        if(queryPending!=null && !queryPending.isDone()) throw new IllegalStateException("A debug NBT query is already pending");
        Object con=connection(); CompletableFuture<JsonObject> result=new CompletableFuture<>(); long epoch=client.sessionEpoch();
        Consumer<Object> callback=tag->{
            if(epoch!=client.sessionEpoch()) result.completeExceptionally(new IllegalStateException("World changed during NBT query"));
            else result.complete(Json.obj("nbt",tag==null ? null : Json.limited(tag.toString(),65536),"authority","server_debug_query","untrustedContent",true));
        };
        Object handler=s.call(con,"getDebugQueryHandler");
        if(entity) s.call(handler,"queryEntityTag",a.get("entityId").getAsInt(),callback);
        else { Object at=pos(a); if(client.loadedClientChunk(a.get("x").getAsInt() >> 4,a.get("z").getAsInt() >> 4)==null) throw new IllegalStateException("Chunk is not loaded on this client"); s.call(handler,"queryBlockEntityTag",at,callback); }
        queryPending=result;
        return result;
    }
    private CompletableFuture<JsonObject> queryPending;
    private JsonObject creative(JsonObject a) {
        actionAllowed();
        JsonObject abilities=client.playerInfo().getAsJsonObject("abilities");
        if(!Boolean.TRUE.equals(abilities.has("creative") && !abilities.get("creative").isJsonNull() ? abilities.get("creative").getAsBoolean() : false))
            throw new IllegalStateException("Creative mode is required; this tool does not grant it");
        String item=DeveloperPolicy.id(a.get("item").getAsString()); Object id;
        try { id=s.call(s.type("net.minecraft.resources.ResourceLocation"),"parse",item); }
        catch(UnsupportedOperationException old) {
            try { id=s.create("net.minecraft.resources.ResourceLocation",item); }
            catch(UnsupportedOperationException renamed) { id=s.call(s.type("net.minecraft.resources.Identifier"),"parse",item); }
        }
        Object registry;
        try { registry=s.field(s.type("net.minecraft.core.registries.BuiltInRegistries"),"ITEM"); }
        catch(UnsupportedOperationException old) { registry=s.field(s.type("net.minecraft.core.Registry"),"ITEM"); }
        if(!Boolean.TRUE.equals(s.call(registry,"containsKey",id))) throw new IllegalArgumentException("Unknown item ID");
        Object raw=s.optional(registry,"getValue",id); if(raw==null) raw=s.call(registry,"get",id);
        if(raw instanceof Optional) raw=((Optional<?>)raw).orElse(null);
        Object stack=s.create("net.minecraft.world.item.ItemStack",raw,Json.integer(a,"count",1,1,64));
        int max=((Number)s.call(stack,"getMaxStackSize")).intValue();
        if(a.has("count") && a.get("count").getAsInt()>max) throw new IllegalArgumentException("Count exceeds this item's normal stack size");
        int slot=Json.integer(a,"slot",36,9,44);
        // Vanilla's creative screen predicts the local inventory change before sending this request.
        // The server remains authoritative and can reject it if creative permission was revoked.
        Object inventoryMenu=s.field(player(),"inventoryMenu");
        s.call(s.call(inventoryMenu,"getSlot",slot),"set",stack);
        s.call(s.field(mc,"gameMode"),"handleCreativeModeItemAdd",stack,slot);
        return Json.obj("submitted",true,"slot",slot,"item",item,"authority","normal_creative_inventory","note","Server validates creative permission. Read inventory to confirm.");
    }
}
