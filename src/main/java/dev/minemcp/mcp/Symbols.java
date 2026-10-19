package dev.minemcp.mcp;

import com.google.gson.*;
import net.fabricmc.loader.api.MappingResolver;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.ConcurrentHashMap;

/** Small version-exact symbol tables; no Minecraft classes appear in mod bytecode. */
public final class Symbols {
    private final JsonObject catalog;
    private final MappingResolver resolver;
    private final Map<String, JsonObject> byRuntime = new HashMap<>();
    private final Map<String, String> namedByRuntime = new HashMap<>();
    private final Map<String, Class<?>> classes = new ConcurrentHashMap<>();
    private final Map<String, List<Method>> methodCache = new ConcurrentHashMap<>();
    private final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

    public Symbols(String version, MappingResolver resolver) throws IOException {
        this.resolver = resolver;
        InputStream input = Symbols.class.getResourceAsStream("/mappings/" + version + ".json");
        if (input == null) throw new IOException("Unsupported Minecraft version " + version + "; see docs/compatibility.json");
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            catalog = new JsonParser().parse(reader).getAsJsonObject();
        }
        index();
    }
    Symbols(JsonObject catalog) { this.catalog = catalog; this.resolver = null; index(); }
    private void index() {
        for (Map.Entry<String, JsonElement> entry : catalog.entrySet()) {
            JsonObject row = entry.getValue().getAsJsonObject();
            byRuntime.put(runtimeClass(row.get("c").getAsString()), row);
            namedByRuntime.put(runtimeClass(row.get("c").getAsString()), entry.getKey());
        }
    }

    private String runtimeClass(String name) {
        return resolver == null || !name.startsWith("net.minecraft.class_") ? name : resolver.mapClassName("intermediary", name);
    }
    public Class<?> type(String name) {
        Class<?> cached = classes.get(name);
        if (cached != null) return cached;
        JsonElement row = catalog.get(name);
        if (row == null && name.indexOf('.') < 0) {
            for (Map.Entry<String, JsonElement> e : catalog.entrySet()) {
                if (e.getKey().endsWith("." + name)) { row = e.getValue(); break; }
            }
        }
        String runtime = row == null ? name : runtimeClass(row.getAsJsonObject().get("c").getAsString());
        try {
            Class<?> result = Class.forName(runtime);
            classes.put(name, result);
            return result;
        } catch (ClassNotFoundException e) { throw new UnsupportedOperationException("Class unavailable: " + name); }
    }
    private List<String> names(Class<?> owner, String name, String kind) {
        List<String> result = new ArrayList<>();
        JsonObject row = byRuntime.get(owner.getName());
        if (row != null) {
            for (JsonElement element : row.getAsJsonArray(kind)) {
                JsonArray member = element.getAsJsonArray();
                if (!name.equals(member.get(0).getAsString())) continue;
                String mapped = member.get(1).getAsString();
                String inter = row.get("c").getAsString();
                if (resolver != null && inter.startsWith("net.minecraft.class_")) {
                    mapped = kind.equals("m")
                        ? resolver.mapMethodName("intermediary", inter, mapped, member.get(2).getAsString())
                        : resolver.mapFieldName("intermediary", inter, mapped, member.get(2).getAsString());
                }
                result.add(mapped);
            }
        }
        result.add(name);
        return result;
    }
    public Object field(Object target, String name) {
        if (target == null) return null;
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        String cacheKey = type.getName() + '#' + name;
        Field field = fieldCache.get(cacheKey);
        if (field == null) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (String candidate : names(current, name, "f")) {
                    try { field = current.getDeclaredField(candidate); field.setAccessible(true); break; }
                    catch (NoSuchFieldException ignored) {}
                }
                if (field != null) break;
            }
            if (field == null) throw new UnsupportedOperationException(type.getSimpleName() + "." + name + " is unavailable");
            fieldCache.put(cacheKey, field);
        }
        try { return field.get(target instanceof Class ? null : target); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static boolean fits(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (!type.isPrimitive()) return type.isInstance(value);
        return type == boolean.class && value instanceof Boolean || type == char.class && value instanceof Character
            || type != boolean.class && type != char.class && value instanceof Number;
    }
    private static Object[] coerce(Class<?>[] types, Object[] args) {
        Object[] result = args.clone();
        for (int i = 0; i < types.length; i++) {
            if (!(args[i] instanceof Number)) continue;
            Number n = (Number) args[i];
            if (types[i] == int.class) result[i] = n.intValue();
            if (types[i] == float.class) result[i] = n.floatValue();
            if (types[i] == double.class) result[i] = n.doubleValue();
            if (types[i] == long.class) result[i] = n.longValue();
            if (types[i] == short.class) result[i] = n.shortValue();
            if (types[i] == byte.class) result[i] = n.byteValue();
        }
        return result;
    }
    public Object call(Object target, String name, Object... args) {
        if (target == null) throw new UnsupportedOperationException("No target for " + name);
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        String key = type.getName() + '#' + name;
        List<Method> methods = methodCache.get(key);
        if (methods == null) {
            methods = new ArrayList<>();
            Set<Class<?>> visited = new HashSet<>();
            findMethods(type, name, methods, visited);
            methodCache.put(key, methods);
        }
        for (Method method : methods) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != args.length || target instanceof Class && !Modifier.isStatic(method.getModifiers())) continue;
            boolean match = true;
            for (int i = 0; i < args.length; i++) if (!fits(params[i], args[i])) { match = false; break; }
            if (!match) continue;
            try { method.setAccessible(true); return method.invoke(target instanceof Class ? null : target, coerce(params, args)); }
            catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new IllegalStateException("Invocation failed: " + name, cause);
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        throw new UnsupportedOperationException(type.getSimpleName() + "." + name + "(" + args.length + ") unavailable");
    }
    private void findMethods(Class<?> type, String name, List<Method> out, Set<Class<?>> visited) {
        if (type == null || !visited.add(type)) return;
        JsonObject row = byRuntime.get(type.getName());
        for (Method method : type.getDeclaredMethods()) {
            boolean matches = method.getName().equals(name);
            if (row != null) for (JsonElement e : row.getAsJsonArray("m")) {
                JsonArray member = e.getAsJsonArray();
                if (!member.get(0).getAsString().equals(name)) continue;
                String mapped = member.get(1).getAsString(), owner = row.get("c").getAsString(), desc = member.get(2).getAsString();
                if (resolver != null && owner.startsWith("net.minecraft.class_")) mapped = resolver.mapMethodName("intermediary", owner, mapped, desc);
                if (mapped.equals(method.getName()) && runtimeDescriptor(desc).equals(descriptor(method))) matches = true;
            }
            if (matches) out.add(method);
        }
        findMethods(type.getSuperclass(), name, out, visited);
        for (Class<?> iface : type.getInterfaces()) findMethods(iface, name, out, visited);
    }
    private String runtimeDescriptor(String desc) {
        Matcher matcher = Pattern.compile("L([^;]+);").matcher(desc); StringBuffer out = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement("L" + runtimeClass(matcher.group(1).replace('/', '.')).replace('.', '/') + ";"));
        matcher.appendTail(out); return out.toString();
    }
    private static String descriptor(Method method) {
        StringBuilder out = new StringBuilder("("); for (Class<?> type : method.getParameterTypes()) out.append(descriptor(type));
        return out.append(')').append(descriptor(method.getReturnType())).toString();
    }
    private static String descriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V"; if (type == boolean.class) return "Z"; if (type == byte.class) return "B";
        if (type == char.class) return "C"; if (type == short.class) return "S"; if (type == int.class) return "I";
        if (type == long.class) return "J"; if (type == float.class) return "F"; return "D";
    }
    public Object create(String name, Object... args) {
        for (Constructor<?> ctor : type(name).getDeclaredConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            if (types.length != args.length) continue;
            boolean match = true;
            for (int i = 0; i < types.length; i++) if (!fits(types[i], args[i])) match = false;
            if (match) try { ctor.setAccessible(true); return ctor.newInstance(coerce(types, args)); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        throw new UnsupportedOperationException("Constructor unavailable: " + name);
    }
    public void set(Object target, String name, Object value) {
        // Reuse resolved field cache; only fixed adapter code calls this, never a user-supplied field name.
        field(target, name);
        try { fieldCache.get(target.getClass().getName() + '#' + name).set(target, value); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object constant(String name, String value) { return Enum.valueOf((Class) type(name), value); }
    public Object optionalField(Object target, String... names) {
        if (target == null) return null;
        for (String name : names) try { return field(target, name); } catch (UnsupportedOperationException ignored) {}
        return null;
    }
    public Object optional(Object target, String name, Object... args) {
        if (target == null) return null;
        try { return call(target, name, args); } catch (UnsupportedOperationException ignored) { return null; }
    }
    public boolean isNamedMethod(Method method, String name) {
        return names(method.getDeclaringClass(), name, "m").contains(method.getName());
    }
    public String namedClass(Object target) {
        if (target == null) return "null";
        String runtime = target instanceof Class ? ((Class<?>) target).getName() : target.getClass().getName();
        return namedByRuntime.containsKey(runtime) ? namedByRuntime.get(runtime) : runtime;
    }
    public List<String> fieldNames(Object target) {
        List<String> names = new ArrayList<>();
        if (target == null) return names;
        for (Class<?> type = target.getClass(); type != null && type.getName().startsWith("net.minecraft."); type = type.getSuperclass()) {
            JsonObject row = byRuntime.get(type.getName());
            if (row != null) for (JsonElement field : row.getAsJsonArray("f")) {
                String name = field.getAsJsonArray().get(0).getAsString();
                if (!names.contains(name)) names.add(name);
            }
        }
        Collections.sort(names);
        return names;
    }
}
