package com.moulberry.flashback.mixin.visuals;

import com.mojang.blaze3d.vertex.PoseStack;
import com.moulberry.flashback.visuals.FlashbackDebugRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugRenderer.class)
public class MixinDebugRenderer {

    /**
     * DebugRenderer in 1.21.1 renders its built-in renderers directly instead of maintaining the
     * newer mutable renderer list. Draw Flashback's outlines at the tail of that same render pass.
     */
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V", at = @At("TAIL"))
    private void flashback$renderHiddenEntities(PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                                double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        new FlashbackDebugRenderer(Minecraft.getInstance()).render(poseStack, buffers, cameraX, cameraY, cameraZ);
    }
}
