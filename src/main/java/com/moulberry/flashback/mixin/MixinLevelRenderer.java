package com.moulberry.flashback.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.PerfectFrames;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.visuals.ReplayVisuals;
import com.moulberry.flashback.visuals.ShaderManager;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class MixinLevelRenderer {

    @Shadow @Final public ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
    @Shadow private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    @Shadow @Final public SectionOcclusionGraph sectionOcclusionGraph;
    @Shadow private int ticks;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    public void tick(CallbackInfo ci) {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null) {
            this.ticks = replayServer.getReplayTick();
            ci.cancel();
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    public void renderLevel(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                            GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                            Matrix4f projection, CallbackInfo ci) {
        ReplayUI.lastProjectionMatrix = projection;
        ReplayUI.lastViewQuaternion = camera.rotation();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"), require = 0)
    public void renderLevelReturn(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                  GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                                  Matrix4f projection, CallbackInfo ci) {
        ExportJob exportJob = Flashback.EXPORT_JOB;
        if (exportJob != null && exportJob.isRunning()) {
            exportJob.tryDepthDownload();
        }
    }

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true, require = 0)
    public void renderSectionLayer(RenderType renderType, double x, double y, double z,
                                   Matrix4f modelView, Matrix4f projection, CallbackInfo ci) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && !editorState.replayVisuals.renderBlocks) {
            ci.cancel();
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V", ordinal = 2, shift = At.Shift.AFTER), require = 0)
    public void renderLevelRenderCutoutLayer(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                             GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                                             Matrix4f projection, CallbackInfo ci) {
        if (Flashback.isExporting() && Flashback.EXPORT_JOB.getSettings().transparent()) {
            RenderTarget main = Minecraft.getInstance().mainRenderTarget;
            RenderSystem.assertOnRenderThread();
            GlStateManager._disableDepthTest();
            GlStateManager._depthMask(false);
            GlStateManager._viewport(0, 0, main.viewWidth, main.viewHeight);
            GlStateManager._disableBlend();
            ShaderInstance shader = Objects.requireNonNull(ShaderManager.blitScreenRoundAlpha, "Blit shader not loaded");
            shader.setSampler("DiffuseSampler", main.colorTextureId);
            shader.apply();
            BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
            builder.addVertex(0.0f, 0.0f, 0.0f);
            builder.addVertex(1.0f, 0.0f, 0.0f);
            builder.addVertex(1.0f, 1.0f, 0.0f);
            builder.addVertex(0.0f, 1.0f, 0.0f);
            BufferUploader.draw(builder.buildOrThrow());
            shader.clear();
            GlStateManager._enableBlend();
            GlStateManager._depthMask(true);
            GlStateManager._enableDepthTest();
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FogRenderer;levelFogColor()V", shift = At.Shift.AFTER), require = 0)
    public void renderLevelFogColor(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                    GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView,
                                    Matrix4f projection, CallbackInfo ci) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null) {
            return;
        }

        ReplayVisuals visuals = editorState.replayVisuals;
        if (visuals.overrideFogColour) {
            float[] fogColour = visuals.fogColour;
            FogRenderer.fogRed = fogColour[0];
            FogRenderer.fogGreen = fogColour[1];
            FogRenderer.fogBlue = fogColour[2];
            RenderSystem.setShaderFogColor(fogColour[0], fogColour[1], fogColour[2]);
            if (visuals.renderSky) {
                RenderSystem.clearColor(fogColour[0], fogColour[1], fogColour[2], 1.0F);
            }
        }
        if (!visuals.renderSky) {
            if (Flashback.isExporting() && Flashback.EXPORT_JOB.getSettings().transparent()) {
                RenderSystem.clearColor(0.0f, 0.0f, 0.0f, 0.0F);
            } else {
                float[] skyColour = visuals.skyColour;
                RenderSystem.clearColor(skyColour[0], skyColour[1], skyColour[2], 1.0F);
            }
        }
    }

    @WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"), require = 0)
    public boolean renderBlockEntity(BlockEntityRenderDispatcher instance, net.minecraft.world.level.block.entity.BlockEntity blockEntity,
                                     float partialTick, PoseStack poseStack, MultiBufferSource buffers) {
        EditorState editorState = EditorStateManager.getCurrent();
        return editorState == null || editorState.replayVisuals.renderBlocks;
    }

    @WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"), require = 0)
    public boolean renderEntity(LevelRenderer instance, Entity entity, double x, double y, double z, float partialTick,
                                PoseStack poseStack, MultiBufferSource buffers) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (entity instanceof Player) {
            return editorState == null || editorState.replayVisuals.renderPlayers;
        }
        return editorState == null || editorState.replayVisuals.renderEntities;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getCloudsType()Lnet/minecraft/client/CloudStatus;"), require = 0)
    public CloudStatus renderLevelGetCloudsType(Options options, Operation<CloudStatus> original) {
        EditorState editorState = EditorStateManager.getCurrent();
        return editorState != null && !editorState.replayVisuals.renderSky ? CloudStatus.OFF : original.call(options);
    }

    @WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;F)V"), require = 0)
    public boolean renderParticles(ParticleEngine particleEngine, LightTexture lightTexture, Camera camera, float partialTick) {
        EditorState editorState = EditorStateManager.getCurrent();
        return editorState == null || editorState.replayVisuals.renderParticles;
    }

    @WrapWithCondition(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V"), require = 0)
    public boolean renderSky(LevelRenderer instance, Matrix4f modelView, Matrix4f projection, float partialTick,
                             Camera camera, boolean renderBlockOutline, Runnable renderSky) {
        EditorState editorState = EditorStateManager.getCurrent();
        return editorState == null || editorState.replayVisuals.renderSky;
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V"), require = 0)
    public void setupRender(LevelRenderer instance, Camera camera, Frustum frustum, boolean capturedFrustum,
                            boolean spectator, Operation<Void> original) {
        if (!PerfectFrames.isEnabled()) {
            original.call(instance, camera, frustum, capturedFrustum, spectator);
            return;
        }

        boolean compile;
        do {
            compile = false;
            int sectionCount = this.visibleSections.size();
            original.call(instance, camera, frustum, capturedFrustum, spectator);
            if (this.visibleSections.size() != sectionCount) {
                compile = true;
            }

            RenderRegionCache cache = new RenderRegionCache();
            for (SectionRenderDispatcher.RenderSection section : this.visibleSections) {
                if (section.isDirty()) {
                    this.sectionRenderDispatcher.rebuildSectionSync(section, cache);
                    section.setNotDirty();
                    compile = true;
                }
            }
            this.sectionRenderDispatcher.uploadAllPendingUploads();
        } while (compile);
    }
}
