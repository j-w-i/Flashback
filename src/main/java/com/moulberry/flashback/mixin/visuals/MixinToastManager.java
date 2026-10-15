package com.moulberry.flashback.mixin.visuals;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.client.gui.components.toasts.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's "Move with WASD / Look around" tutorial runs against the replay viewer as if it were a new player,
 * so it popped up during playback and was baked into exported frames. A replay viewer is never learning to
 * play, so tutorial toasts are dropped while a replay is open.
 */
@Mixin(ToastComponent.class)
public class MixinToastManager {

    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashback$dropTutorialToastsInReplay(Toast toast, CallbackInfo ci) {
        if (toast instanceof TutorialToast && Flashback.isInReplay()) {
            ci.cancel();
        }
    }

}
