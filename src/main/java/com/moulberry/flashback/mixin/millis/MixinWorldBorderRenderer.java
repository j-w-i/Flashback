package com.moulberry.flashback.mixin.millis;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.Flashback;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * In 1.21.1 the animated world-border mesh is built directly by LevelRenderer; the later
 * WorldBorderRenderer/prepareDynamicTransforms split does not exist.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinWorldBorderRenderer {

    @WrapOperation(method = "renderWorldBorder", at = @At(value = "INVOKE", target = "Lnet/minecraft/Util;getMillis()J"), require = 0)
    private long flashback$worldBorderMillis(Operation<Long> original) {
        if (Flashback.isInReplay()) {
            return Flashback.getVisualMillis();
        }
        return original.call();
    }
}
