package dev.minesynapse.mcp.mixin;
import dev.minesynapse.mcp.MineSynapse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "net.minecraft.client.KeyboardHandler", remap = false)
public abstract class OfficialKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), remap = false)
    private void minesynapse$key(long window, int action, @Coerce Object event, CallbackInfo ci) { MineSynapse.keyEvent(event, action); }
}
