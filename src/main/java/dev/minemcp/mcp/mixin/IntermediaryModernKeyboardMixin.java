package dev.minemcp.mcp.mixin;
import dev.minemcp.mcp.MineMCP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.class_309", remap = false)
public abstract class IntermediaryModernKeyboardMixin {
    @Inject(method = "method_1466", at = @At("HEAD"), remap = false)
    private void minemcp$key(long window, int action, @Coerce Object event, CallbackInfo ci) { MineMCP.keyEvent(event, action); }
}
