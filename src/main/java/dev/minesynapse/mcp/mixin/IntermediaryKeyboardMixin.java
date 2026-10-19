package dev.minesynapse.mcp.mixin;
import dev.minesynapse.mcp.MineSynapse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.class_309", remap = false)
public abstract class IntermediaryKeyboardMixin {
    @Inject(method = "method_1466(JIIII)V", at = @At("HEAD"), remap = false)
    private void minesynapse$key(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) { MineSynapse.key(key, action); }
}
