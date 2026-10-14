package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.moulberry.flashback.editor.ui.ReplayUI;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.Objects;

/** PBO-backed, ordered framebuffer readback queue for export. */
public class SaveableFramebufferQueue implements AutoCloseable {
    private final int width;
    private final int height;
    private final ArrayDeque<SaveableFramebuffer> available = new ArrayDeque<>();
    private final ArrayDeque<SaveableFramebuffer> waiting = new ArrayDeque<>();
    private final TextureTarget flipBuffer;
    private final TransformDepthUniform transformDepth = new TransformDepthUniform();

    public SaveableFramebufferQueue(int width, int height) {
        this.width = width;
        this.height = height;
        this.flipBuffer = new TextureTarget(width, height, false, false);
    }

    public SaveableFramebuffer take() {
        SaveableFramebuffer buffer = this.available.pollFirst();
        return buffer != null ? buffer : new SaveableFramebuffer(this.width, this.height);
    }

    private void blitFlip(RenderTarget source, boolean supersampling) {
        int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int filter = supersampling ? GL11C.GL_LINEAR : GL11C.GL_NEAREST;
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, source.frameBufferId);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.flipBuffer.frameBufferId);
            GL30C.glBlitFramebuffer(0, 0, this.width, this.height,
                0, this.height, this.width, 0, GL11C.GL_COLOR_BUFFER_BIT, filter);
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        }
    }

    public void startDownload(RenderTarget target, SaveableFramebuffer buffer, boolean supersampling) {
        this.blitFlip(target, supersampling);
        buffer.startDownload(this.flipBuffer);
        this.waiting.addLast(buffer);
    }

    public void startDepthDownload(RenderTarget target, SaveableFramebuffer buffer) {
        TransformDepthUniform.Parameters parameters = this.transformDepth.getOrUpdate(ReplayUI.lastProjectionMatrix);
        buffer.startDepthDownload(target, parameters);
        this.waiting.addLast(buffer);
    }

    public @Nullable ImageFrame finishDownload() {
        SaveableFramebuffer first = this.waiting.peekFirst();
        if (first == null) {
            return null;
        }
        ImageFrame image = first.finishDownload();
        if (image == null) {
            return null;
        }
        image.audioBuffer = first.audioBuffer;
        SaveableFramebuffer recycled = this.waiting.removeFirst();
        recycled.audioBuffer = null;
        this.available.addLast(recycled);
        return image;
    }

    public @Nullable ImageFrame[] finishDownloadMultiple(int count) {
        if (count < 0 || this.waiting.size() < count) {
            return null;
        }
        var iterator = this.waiting.iterator();
        for (int i = 0; i < count; i++) {
            if (!iterator.next().canFinishDownload()) {
                return null;
            }
        }

        ImageFrame[] result = new ImageFrame[count];
        for (int i = 0; i < count; i++) {
            SaveableFramebuffer next = Objects.requireNonNull(this.waiting.removeFirst());
            ImageFrame image = Objects.requireNonNull(next.finishDownload());
            image.audioBuffer = next.audioBuffer;
            next.audioBuffer = null;
            result[i] = image;
            this.available.addLast(next);
        }
        return result;
    }

    public boolean isEmpty() {
        return this.waiting.isEmpty();
    }

    public int pendingCount() {
        return this.waiting.size();
    }

    @Override
    public void close() {
        for (SaveableFramebuffer buffer : this.waiting) {
            buffer.close();
        }
        for (SaveableFramebuffer buffer : this.available) {
            buffer.close();
        }
        this.waiting.clear();
        this.available.clear();
        this.flipBuffer.destroyBuffers();
        this.transformDepth.close();
    }
}
