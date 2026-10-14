package com.moulberry.flashback.exporting;

import org.joml.Matrix4fc;

/**
 * Depth linearization parameters for the OpenGL export readback path.
 * The old backend does not expose a RenderPearl UBO, so the equivalent transform is
 * applied to the downloaded float depth plane before it is handed to the encoder.
 */
public final class TransformDepthUniform {
    private float lastNear = Float.NaN;
    private float lastFar = Float.NaN;
    private Parameters cached;

    public Parameters getOrUpdate(Matrix4fc projection) {
        if (projection == null) {
            return this.parameters(0.05f, 1000.0f);
        }
        float near = projection.perspectiveNear();
        float far = projection.perspectiveFar();
        if (!Float.isFinite(near) || !Float.isFinite(far) || near == far) {
            near = 0.05f;
            far = 1000.0f;
        }
        return this.parameters(near, far);
    }

    private Parameters parameters(float near, float far) {
        if (this.cached == null || near != this.lastNear || far != this.lastFar) {
            this.lastNear = near;
            this.lastFar = far;
            this.cached = new Parameters(near, far);
        }
        return this.cached;
    }

    public void close() {
        this.cached = null;
        this.lastNear = Float.NaN;
        this.lastFar = Float.NaN;
    }

    public record Parameters(float near, float far) {
        public void linearize(ImageFrame frame) {
            if (frame.format != ImageFrame.Format.GRAY_F32) {
                throw new IllegalArgumentException("Depth linearization requires a float depth frame");
            }
            float scale = Math.max(this.near, this.far);
            if (!Float.isFinite(scale) || scale <= 0.0f) {
                return;
            }
            long count = (long) frame.width * frame.height;
            for (long i = 0; i < count; i++) {
                long address = frame.pixels + i * Float.BYTES;
                float z = org.lwjgl.system.MemoryUtil.memGetFloat(address) * 2.0f - 1.0f;
                float linear = this.near * this.far / (this.far + z * (this.near - this.far));
                float normalized = linear / scale;
                if (this.near > this.far) {
                    normalized = 1.0f - normalized;
                }
                org.lwjgl.system.MemoryUtil.memPutFloat(address, normalized);
            }
        }
    }
}
