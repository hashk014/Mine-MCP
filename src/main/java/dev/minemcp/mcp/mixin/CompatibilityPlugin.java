package dev.minemcp.mcp.mixin;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CompatibilityPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() {
        String version = FabricLoader.getInstance().getModContainer("minecraft").get().getMetadata().getVersion().getFriendlyString();
        if (!version.startsWith("1.")) return Arrays.asList("OfficialTickMixin", "OfficialKeyboardMixin");
        try (InputStream input = getClass().getResourceAsStream("/mappings/" + version + ".json")) {
            if (input == null) return Collections.emptyList();
            JsonObject data = new JsonParser().parse(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray methods = data.getAsJsonObject("net.minecraft.client.KeyboardHandler").getAsJsonArray("m");
            boolean modern = false;
            for (JsonElement m : methods) if (m.getAsJsonArray().get(0).getAsString().equals("keyPress")) modern = !m.getAsJsonArray().get(2).getAsString().equals("(JIIII)V");
            return Arrays.asList("IntermediaryTickMixin", modern ? "IntermediaryModernKeyboardMixin" : "IntermediaryKeyboardMixin");
        } catch (IOException e) { throw new IllegalStateException("Cannot select Minecraft hooks", e); }
    }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
