package com.moulberry.flashback.mixin.visuals;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.player.PlayerModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerRenderer.class)
public class MixinPlayerRenderer {

    @WrapOperation(method = "setModelProperties", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;isModelPartShown(Lnet/minecraft/world/entity/player/PlayerModelPart;)Z"), require = 0)
    public boolean flashback$isCapeShown(AbstractClientPlayer player, PlayerModelPart part, Operation<Boolean> original) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (part == PlayerModelPart.CAPE && editorState != null && editorState.hideCape.contains(player.getUUID())) {
            return false;
        }
        return original.call(player, part);
    }
}
