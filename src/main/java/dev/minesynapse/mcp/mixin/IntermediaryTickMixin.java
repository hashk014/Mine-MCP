package dev.minesynapse.mcp.mixin;
import dev.minesynapse.mcp.MineSynapse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.class_310", remap = false)
public abstract class IntermediaryTickMixin {
    @Inject(method = "method_1574()V", at = @At("HEAD"), remap = false)
    private void minesynapse$tick(CallbackInfo ci) { MineSynapse.tick(); }
}
