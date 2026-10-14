package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.Flashback;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
public class MixinLivingEntity {

    // 1.21.1 has no updateDirtyEffects method. Its status updates are split into the two legacy
    // hooks below, which remain cancelled during replay to prevent potion effects changing the
    // recorded invisible/glowing appearance.

    @Inject(method = "updateInvisibilityStatus", at = @At("HEAD"), cancellable = true)
    public void updateInvisibilityStatus(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ci.cancel();
        }
    }

    @Inject(method = "updateGlowingStatus", at = @At("HEAD"), cancellable = true)
    public void updateGlowingStatus(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ci.cancel();
        }
    }

}
