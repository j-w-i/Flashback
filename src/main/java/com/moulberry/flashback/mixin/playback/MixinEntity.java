package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(Entity.class)
public abstract class MixinEntity {

    @Shadow
    public abstract boolean isInvisible();

    @Shadow
    private Level level;

    @Shadow
    public abstract boolean isInvisibleTo(Player player);

    @Shadow
    public abstract UUID getUUID();

    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    public void isInvisibleTo(Player player, CallbackInfoReturnable<Boolean> cir) {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null && player == Minecraft.getInstance().player) {
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState != null && editorState.isEntityHidden((Entity) (Object) this)) {
                cir.setReturnValue(true);
                return;
            }

            // In 1.21.1 LivingEntityRenderer asks about visibility to the viewer, even when
            // the camera belongs to a recorded player. Use that player's team/invisibility
            // rules while spectating, and the recording's original viewpoint in free camera.
            Player viewpoint = Flashback.getSpectatingPlayer();
            if (viewpoint == null && this.level.getEntity(replayServer.getLocalPlayerId()) instanceof Player localPlayer) {
                viewpoint = localPlayer;
            }
            if (viewpoint != null && viewpoint != player) {
                cir.setReturnValue(this.isInvisibleTo(viewpoint));
            } else {
                // Do not recurse if the recorded id temporarily resolves to the viewer.
                cir.setReturnValue(this.isInvisible());
            }
        }
    }


}
