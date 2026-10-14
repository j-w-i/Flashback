package com.moulberry.flashback.mixin.visuals;

import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public class MixinEntityRenderer {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    public void flashback$shouldRender(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (this.flashback$isHidden(entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Entity;)Z", at = @At("HEAD"), cancellable = true, require = 0)
    public void shouldShowName(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && (!editorState.replayVisuals.renderNametags
                || editorState.hideNametags.contains(entity.getUUID()) || this.flashback$isHidden(entity))) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "renderNameTag", at = @At("HEAD"), cancellable = true, require = 0)
    public void renderNameTag(Entity entity, Component component, PoseStack poseStack, MultiBufferSource buffers,
                              int packedLight, float partialTick, CallbackInfo ci) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && (!editorState.replayVisuals.renderNametags
                || editorState.hideNametags.contains(entity.getUUID()) || this.flashback$isHidden(entity))) {
            ci.cancel();
        }
    }

    @Unique
    private boolean flashback$isHidden(Entity entity) {
        EditorState editorState = EditorStateManager.getCurrent();
        return editorState != null && (editorState.hideDuringExport.contains(entity.getUUID())
                || editorState.hideAllSpectators && entity instanceof Player && entity.isSpectator());
    }
}
