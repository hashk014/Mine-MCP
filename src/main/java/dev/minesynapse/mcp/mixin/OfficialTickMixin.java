package dev.minesynapse.mcp.mixin;
import dev.minesynapse.mcp.MineSynapse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public abstract class OfficialTickMixin {
    @Inject(method = "tick()V", at = @At("HEAD"), remap = false)
    private void minesynapse$tick(CallbackInfo ci) { MineSynapse.tick(); }
}
