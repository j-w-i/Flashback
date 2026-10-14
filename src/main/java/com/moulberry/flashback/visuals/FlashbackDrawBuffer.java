package com.moulberry.flashback.visuals;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;

/** Persistent GPU buffer for replay editor geometry on Minecraft 1.21.1. */
public class FlashbackDrawBuffer implements AutoCloseable {
    private final VertexBuffer vertexBuffer;
    private boolean uploaded;

    /** Retained for callers that previously passed a RenderPearl usage flag. */
    public FlashbackDrawBuffer(int ignoredUsageFlags) {
        this.vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
    }

    @Override
    public void close() {
        this.vertexBuffer.close();
        this.uploaded = false;
    }

    public void upload(MeshData meshData) {
        // Upload before the caller closes its ByteBufferBuilder. VertexBuffer
        // consumes/closes MeshData; subsequent frames draw the GPU copy.
        this.vertexBuffer.bind();
        try {
            this.vertexBuffer.upload(meshData);
            this.uploaded = true;
        } finally {
            VertexBuffer.unbind();
        }
    }

    public void drawRenderType(RenderType renderType) {
        renderType.setupRenderState();
        try {
            this.drawUploaded();
        } finally {
            renderType.clearRenderState();
        }
    }

    public void draw() {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        this.drawUploaded();
    }

    private void drawUploaded() {
        if (!this.uploaded) {
            throw new IllegalStateException("No mesh has been uploaded");
        }
        this.vertexBuffer.bind();
        try {
            this.vertexBuffer.drawWithShader(RenderSystem.getModelViewMatrix(),
                RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        } finally {
            VertexBuffer.unbind();
        }
    }
}
