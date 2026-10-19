package dev.minemcp.mcp.mixin;
import dev.minemcp.mcp.MineMCP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.class_310", remap = false)
public abstract class IntermediaryTickMixin {
    @Inject(method = "method_1574()V", at = @At("HEAD"), remap = false)
    private void minemcp$tick(CallbackInfo ci) { MineMCP.tick(); }
}
