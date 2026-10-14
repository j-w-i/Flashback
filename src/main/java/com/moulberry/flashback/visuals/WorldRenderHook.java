package com.moulberry.flashback.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.ReplayMarker;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** World-space replay marker and camera-path rendering on Minecraft's 1.21 OpenGL path. */
public final class WorldRenderHook {
    private WorldRenderHook() {}

    public static void renderHook(PoseStack poseStack, float partialTick, boolean renderBlockOutline,
                                  Camera camera, GameRenderer gameRenderer, LightTexture lightTexture,
                                  Matrix4f projection) {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer == null || Flashback.isExporting() || !ReplayUI.isActive()) {
            return;
        }

        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && editorState.replayVisuals.cameraPath) {
            CameraPath.renderCameraPath(poseStack, camera, replayServer);
        }

        FlashbackMeta meta = replayServer.getMetadata();
        if (meta.replayMarkers.isEmpty() || Minecraft.getInstance().level == null) {
            return;
        }

        BufferBuilder markerBuilder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        String dimension = Minecraft.getInstance().level.dimension().toString();

        for (ReplayMarker marker : meta.replayMarkers.values()) {
            if (marker.position() == null) {
                continue;
            }
            ReplayMarker.MarkerPosition position = marker.position();
            if (!position.dimension().equals(dimension)) {
                continue;
            }

            poseStack.pushPose();
            poseStack.translate(
                position.position().x - camera.getPosition().x,
                position.position().y - camera.getPosition().y,
                position.position().z - camera.getPosition().z
            );
            poseStack.mulPose(camera.rotation());

            float halfSize = 0.2f;
            int color = marker.colour() | 0xFF000000;
            markerBuilder.addVertex(poseStack.last(), -halfSize, -halfSize, 0.0f).setUv(0f, 0f).setColor(color);
            markerBuilder.addVertex(poseStack.last(), halfSize, -halfSize, 0.0f).setUv(1f, 0f).setColor(color);
            markerBuilder.addVertex(poseStack.last(), halfSize, halfSize, 0.0f).setUv(1f, 1f).setColor(color);
            markerBuilder.addVertex(poseStack.last(), -halfSize, halfSize, 0.0f).setUv(0f, 1f).setColor(color);

            if (marker.description() != null) {
                Font font = Minecraft.getInstance().font;
                Matrix4f model = new Matrix4f(poseStack.last().pose());
                model.rotate((float) Math.PI, 0.0f, 1.0f, 0.0f);
                model.scale(-0.025f, -0.025f, -0.025f);
                int textWidth = font.width(marker.description());
                font.drawInBatch(marker.description(), -textWidth / 2.0f, -20.0f,
                    0xFFFFFFFF, true, model, buffers, Font.DisplayMode.NORMAL, 0, 0xF000F0);
            }
            poseStack.popPose();
        }

        MeshData meshData = markerBuilder.build();
        if (meshData != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
            RenderSystem.setShaderTexture(0, ResourceLocation.parse("flashback:world_marker_circle.png"));
            BufferUploader.drawWithShader(meshData);
        }
        buffers.endBatch();
    }
}
