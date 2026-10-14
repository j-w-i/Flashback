package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * Asynchronous OpenGL pixel-pack-buffer readback used by video export.
 * The read is issued while the target is bound; the fence is polled before mapping so
 * encoding can overlap the following render frame.
 */
public class SaveableFramebuffer implements AutoCloseable {
    public @Nullable FloatBuffer audioBuffer;

    private final int width;
    private final int height;
    private int pboId = -1;
    private long fence = 0L;
    private boolean isDownloading;
    private boolean depthDownload;
    private @Nullable TransformDepthUniform.Parameters depthParameters;
    private @Nullable ImageFrame downloaded;

    public SaveableFramebuffer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public void startDownload(RenderTarget target) {
        this.startDownload(target, false, null);
    }

    public void startDepthDownload(RenderTarget target, TransformDepthUniform.Parameters parameters) {
        this.startDownload(target, true, parameters);
    }

    private void startDownload(RenderTarget target, boolean depth, @Nullable TransformDepthUniform.Parameters parameters) {
        if (this.isDownloading) {
            throw new IllegalStateException("Can't start downloading while already downloading");
        }
        if (target.width != this.width || target.height != this.height) {
            throw new IllegalArgumentException("Readback target size does not match PBO size");
        }
        this.isDownloading = true;
        this.depthDownload = depth;
        this.depthParameters = parameters;

        if (this.pboId == -1) {
            this.pboId = GL30C.glGenBuffers();
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
            GL30C.glBufferData(GL30C.GL_PIXEL_PACK_BUFFER, (long) this.width * this.height * 4L, GL30C.GL_STREAM_READ);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
        }

        int previousReadFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousPixelPackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousPackAlignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
        int previousPackRowLength = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH);
        int previousPackSkipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS);
        int previousPackSkipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS);
        try {
            target.bindWrite(true);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
            if (depth) {
                GL11C.glReadPixels(0, 0, this.width, this.height, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, 0L);
            } else {
                GL11C.glReadPixels(0, 0, this.width, this.height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, 0L);
            }
            this.fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            GL11C.glFlush();
        } catch (RuntimeException | Error e) {
            this.isDownloading = false;
            throw e;
        } finally {
            target.unbindWrite();
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, previousPackAlignment);
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, previousPackRowLength);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, previousPackSkipPixels);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, previousPackSkipRows);
        }
    }

    public boolean canFinishDownload() {
        if (!this.isDownloading) {
            throw new IllegalStateException("Can't finish downloading before download has started");
        }
        if (this.downloaded != null) {
            return true;
        }
        if (this.fence == 0L) {
            return false;
        }
        int result = GL32C.glClientWaitSync(this.fence, 0, 0L);
        if (result == GL32C.GL_TIMEOUT_EXPIRED) {
            return false;
        }
        if (result == GL32C.GL_WAIT_FAILED) {
            throw new IllegalStateException("OpenGL failed while waiting for framebuffer readback");
        }
        return true;
    }

    public @Nullable ImageFrame finishDownload() {
        if (!this.isDownloading) {
            throw new IllegalStateException("Can't finish downloading before download has started");
        }
        if (this.downloaded == null) {
            if (!this.canFinishDownload()) {
                return null;
            }
            int previousPixelPackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
            ByteBuffer mapped = GL30C.glMapBuffer(GL30C.GL_PIXEL_PACK_BUFFER, GL30C.GL_READ_ONLY);
            if (mapped == null) {
                GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
                throw new IllegalStateException("OpenGL error occurred while mapping framebuffer readback buffer");
            }

            ImageFrame.Format format = this.depthDownload ? ImageFrame.Format.GRAY_F32 : ImageFrame.Format.RGBA_U8;
            ImageFrame frame = new ImageFrame(this.width, this.height, format, false);
            try {
                MemoryUtil.memCopy(MemoryUtil.memAddress(mapped), frame.pixels, frame.size);
            } finally {
                if (!GL30C.glUnmapBuffer(GL30C.GL_PIXEL_PACK_BUFFER)) {
                    frame.close();
                    GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
                    throw new IllegalStateException("OpenGL reported a corrupted framebuffer readback buffer");
                }
                GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, previousPixelPackBuffer);
            }

            // Color input was vertically flipped by the queue's FBO blit. Direct depth
            // readback still needs row reversal before it matches the encoded frame origin.
            if (this.depthDownload) {
                this.flipRows(frame);
            }
            if (this.depthDownload && this.depthParameters != null) {
                this.depthParameters.linearize(frame);
            }
            this.downloaded = frame;
            this.deleteFence();
        }

        ImageFrame result = this.downloaded;
        this.downloaded = null;
        this.isDownloading = false;
        this.depthParameters = null;
        return result;
    }

    private void flipRows(ImageFrame frame) {
        long rowBytes = (long) this.width * frame.format.bytes();
        long temporary = MemoryUtil.nmemAlloc(rowBytes);
        try {
            for (int y = 0; y < this.height / 2; y++) {
                long top = frame.pixels + y * rowBytes;
                long bottom = frame.pixels + (this.height - 1L - y) * rowBytes;
                MemoryUtil.memCopy(top, temporary, rowBytes);
                MemoryUtil.memCopy(bottom, top, rowBytes);
                MemoryUtil.memCopy(temporary, bottom, rowBytes);
            }
        } finally {
            MemoryUtil.nmemFree(temporary);
        }
    }

    private void deleteFence() {
        if (this.fence != 0L) {
            GL32C.glDeleteSync(this.fence);
            this.fence = 0L;
        }
    }

    @Override
    public void close() {
        this.deleteFence();
        if (this.pboId != -1) {
            GL30C.glDeleteBuffers(this.pboId);
            this.pboId = -1;
        }
        if (this.downloaded != null) {
            this.downloaded.close();
            this.downloaded = null;
        }
        this.isDownloading = false;
        this.depthParameters = null;
    }
}
