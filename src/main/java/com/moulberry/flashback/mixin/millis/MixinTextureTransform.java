package com.moulberry.flashback.mixin.millis;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.Flashback;
import net.minecraft.client.renderer.RenderStateShard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Maps animated glint time to replay time on the 1.21.1 render-state shard. */
@Mixin(RenderStateShard.class)
public class MixinTextureTransform {

    @WrapOperation(method = "setupGlintTexturing", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;getMillis()J"), require = 0)
    private static long flashback$glintMillis(Operation<Long> original) {
        if (Flashback.isInReplay()) {
            return Flashback.getVisualMillis();
        }
        return original.call();
    }
}
