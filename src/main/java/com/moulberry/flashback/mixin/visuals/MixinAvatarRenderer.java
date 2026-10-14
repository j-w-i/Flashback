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

import java.util.EnumSet;

@Mixin(PlayerRenderer.class)
public class MixinAvatarRenderer {

    @WrapOperation(method = "setModelProperties", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;isModelPartShown(Lnet/minecraft/world/entity/player/PlayerModelPart;)Z"), require = 0)
    public boolean flashback$isModelPartShown(AbstractClientPlayer player, PlayerModelPart part, Operation<Boolean> original) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            EnumSet<PlayerModelPart> hiddenParts = editorState.hiddenModelParts.get(player.getUUID());
            if (hiddenParts != null && hiddenParts.contains(part)) {
                return false;
            }
        }
        return original.call(player, part);
    }
}
