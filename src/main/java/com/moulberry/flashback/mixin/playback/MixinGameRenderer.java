package com.moulberry.flashback.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.ext.ItemInHandRendererExt;
import com.moulberry.flashback.ext.MinecraftExt;
import com.moulberry.flashback.gui.GuiDisplay;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import com.moulberry.flashback.visuals.CameraRotation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Shadow @Final Minecraft minecraft;

    @WrapOperation(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;pauseOnLostFocus:Z"), require = 0)
    public boolean getPauseOnLostFocus(Options options, Operation<Boolean> original) {
        if (ReplayUI.isActive() || Flashback.EXPORT_JOB != null) {
            return false;
        }
        return original.call(options);
    }

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    public void renderHead(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (Flashback.RECORDER != null && player != null) {
            Flashback.RECORDER.trackPartialPosition(player, deltaTracker.getGameTimeDeltaPartialTick(true));
        }
        AccurateEntityPositionHandler.apply(Minecraft.getInstance().level, deltaTracker);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", remap = false, ordinal = 0), cancellable = true)
    public void render_noGui(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        if (Flashback.isExporting() && Flashback.EXPORT_JOB.getSettings().noGui()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
    public void renderItemInHand_onlyThroughAPlayer(CallbackInfo ci) {
        if (Flashback.isInReplay() && !GuiDisplay.showingThroughAPlayer()) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    public GameType getPlayerMode(MultiPlayerGameMode gameMode, Operation<GameType> original) {
        // The spectator check: a watched replay player is not a spectator, whatever the viewer's own mode is.
        return Flashback.getWatchedPlayer() != null ? GameType.SURVIVAL : original.call(gameMode);
    }

    /**
     * Lights the hand from the player being watched rather than from the viewer.
     *
     * <p>Vanilla asks for the light at {@code minecraft.player}, who in a replay is the viewer: in the editor that
     * is a body sitting wherever the free camera is, which is often inside terrain. The hand was then lit for that
     * spot instead of for the player whose eyes the shot is through, so a tool held in daylight came out black -
     * and against a dark tunnel it looked like no hand was drawn at all, while the hotbar and hearts beside it,
     * which are drawn from the recorded player, looked right.
     */
    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getPackedLightCoords(Lnet/minecraft/world/entity/Entity;F)I"), require = 0)
    public int renderItemInHand_lightForWatchedPlayer(net.minecraft.client.renderer.entity.EntityRenderDispatcher dispatcher,
            net.minecraft.world.entity.Entity entity, float partialTick, Operation<Integer> original) {
        AbstractClientPlayer watched = Flashback.getWatchedPlayer();
        return original.call(dispatcher, watched != null ? watched : entity, partialTick);
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    public void renderHandsWithSpectatedPlayer(ItemInHandRenderer renderer, float partialTick, PoseStack poseStack,
                                               MultiBufferSource.BufferSource buffers, LocalPlayer localPlayer, int packedLight,
                                               Operation<Void> original) {
        AbstractClientPlayer spectatingPlayer = Flashback.getWatchedPlayer();
        if (spectatingPlayer == null) {
            // No watched player. If the interface is being drawn for one anyway (a spectate camera whose player the
            // replay has not re-created at this tick), draw no hand rather than the viewer's: the viewer owns nothing
            // in a replay, so their hand inside somebody else's interface is the desync this is here to avoid.
            if (!GuiDisplay.showingThroughAPlayer()) {
                original.call(renderer, partialTick, poseStack, buffers, localPlayer, packedLight);
            }
            return;
        }

        Entity cameraEntity = this.minecraft.getCameraEntity() == null ? this.minecraft.player : this.minecraft.getCameraEntity();
        float handPartialTick = this.minecraft.level != null && cameraEntity != null
                && this.minecraft.level.tickRateManager().isEntityFrozen(cameraEntity) ? 1.0f : partialTick;
        ((ItemInHandRendererExt) renderer).flashback$renderHandsWithItems(handPartialTick, poseStack, buffers, spectatingPlayer, packedLight);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V"))
    public void renderLevelSetupCamera(Camera camera, BlockGetter blockGetter, Entity entity, boolean detached,
                                       boolean thirdPersonReverse, float partialTick, Operation<Void> original) {
        if (Flashback.isInReplay()) {
            partialTick = ((MinecraftExt) this.minecraft).flashback$getLocalPlayerPartialTick(partialTick);
        }
        original.call(camera, blockGetter, entity, detached, thirdPersonReverse, partialTick);
    }

    @Inject(method = "getNightVisionScale", at = @At("HEAD"), cancellable = true, require = 0)
    private static void getNightVisionScale(LivingEntity entity, float partialTick, CallbackInfoReturnable<Float> cir) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.replayVisuals.overrideNightVision) {
            cir.setReturnValue(1.0f);
        }
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;rotation()Lorg/joml/Quaternionf;"), require = 0)
    public Quaternionf renderLevelCameraRotation(Camera camera, Operation<Quaternionf> original) {
        return CameraRotation.modifyViewQuaternion(original.call(camera));
    }

    @Inject(method = "tryTakeScreenshotIfNeeded", at = @At("HEAD"), cancellable = true, require = 0)
    public void tryTakeScreenshotIfNeeded(CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            ci.cancel();
        }
    }

    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true, require = 0)
    public void shouldRenderBlockOutline(CallbackInfoReturnable<Boolean> cir) {
        if (Flashback.isInReplay()) {
            Entity cameraEntity = this.minecraft.getCameraEntity();
            if (cameraEntity == this.minecraft.player && cameraEntity != null && cameraEntity.isSpectator()) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "getFov", at = @At("HEAD"), cancellable = true, require = 0)
    public void getFov(Camera camera, float partialTick, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (!Flashback.isInReplay()) {
            return;
        }
        if (!changingFov) {
            cir.setReturnValue(70.0);
            return;
        }
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.replayVisuals.overrideFov) {
            cir.setReturnValue((double) editorState.replayVisuals.overrideFovAmount);
        } else {
            cir.setReturnValue((double) this.minecraft.options.fov().get().intValue());
        }
    }
}
