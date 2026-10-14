package com.moulberry.flashback.utils;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.opengl.GL40C;

/** RenderTarget helpers implemented with the 1.21.1 OpenGL framebuffer API. */
public final class FramebufferUtils {
    public static final Vector4f TRANSPARENT_CLEAR_COLOUR = new Vector4f(0.0f);
    public static final Vector4f BLACK_CLEAR_COLOUR = new Vector4f(0.0f, 0.0f, 0.0f, 1.0f);

    private static int compositeProgram;
    private static int compositeVertexArray;
    private static int compositeSamplerLocation;

    private FramebufferUtils() {}

    /**
     * Composites a premultiplied-alpha target (such as ImGui's) over the destination.
     * Unlike blitTo, transparent source pixels preserve the destination's contents.
     * Uses raw GL exclusively so RenderSystem's cached state is not disturbed.
     */
    public static void compositeOver(RenderTarget from, RenderTarget to) {
        RenderSystem.assertOnRenderThread();
        if (from.frameBufferId == to.frameBufferId || from.getColorTextureId() == to.getColorTextureId()) {
            throw new IllegalArgumentException("Cannot composite a render target over itself");
        }
        if (from.width <= 0 || from.height <= 0 || to.width <= 0 || to.height <= 0) {
            return;
        }

        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousProgram = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        int previousVertexArray = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousActiveTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int[] previousViewport = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, previousViewport);
        int[] previousColorMask = new int[4];
        GL30C.glGetIntegeri_v(GL11C.GL_COLOR_WRITEMASK, 0, previousColorMask);
        int[] previousPolygonMode = new int[2];
        GL11C.glGetIntegerv(GL11C.GL_POLYGON_MODE, previousPolygonMode);
        boolean previousBlend = GL30C.glIsEnabledi(GL11C.GL_BLEND, 0);
        boolean indexedBlend = GL.getCapabilities().OpenGL40;
        int previousSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        int previousDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        int previousSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        int previousDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        int previousEquationRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB);
        int previousEquationAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);
        boolean previousDepth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        boolean previousScissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        boolean previousCull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        boolean previousStencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        boolean previousLogicOp = GL11C.glIsEnabled(GL11C.GL_COLOR_LOGIC_OP);
        boolean previousRasterizerDiscard = GL11C.glIsEnabled(GL30C.GL_RASTERIZER_DISCARD);
        boolean samplerObjects = GL.getCapabilities().OpenGL33 || GL.getCapabilities().GL_ARB_sampler_objects;
        int previousTexture = -1;
        int previousSampler = -1;
        try {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            if (samplerObjects) {
                previousSampler = GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING);
                GL33C.glBindSampler(0, 0);
            }
            initializeCompositeShader();

            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
            GL11C.glViewport(0, 0, to.width, to.height);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_STENCIL_TEST);
            GL11C.glDisable(GL11C.GL_COLOR_LOGIC_OP);
            GL11C.glDisable(GL30C.GL_RASTERIZER_DISCARD);
            GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, GL11C.GL_FILL);
            GL30C.glColorMaski(0, true, true, true, true);
            GL30C.glEnablei(GL11C.GL_BLEND, 0);
            if (indexedBlend) {
                GL40C.glBlendEquationSeparatei(0, GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
                GL40C.glBlendFuncSeparatei(0, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            } else {
                GL20C.glBlendEquationSeparate(GL14C.GL_FUNC_ADD, GL14C.GL_FUNC_ADD);
                GL14C.glBlendFuncSeparate(GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA,
                    GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            }
            GL20C.glUseProgram(compositeProgram);
            GL20C.glUniform1i(compositeSamplerLocation, 0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, from.getColorTextureId());
            GL30C.glBindVertexArray(compositeVertexArray);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        } finally {
            GL30C.glBindVertexArray(previousVertexArray);
            GL20C.glUseProgram(previousProgram);
            if (previousTexture != -1) {
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            }
            if (previousSampler != -1) {
                GL33C.glBindSampler(0, previousSampler);
            }
            GL13C.glActiveTexture(previousActiveTexture);
            if (indexedBlend) {
                GL40C.glBlendEquationSeparatei(0, previousEquationRgb, previousEquationAlpha);
                GL40C.glBlendFuncSeparatei(0, previousSrcRgb, previousDstRgb, previousSrcAlpha, previousDstAlpha);
            } else {
                GL20C.glBlendEquationSeparate(previousEquationRgb, previousEquationAlpha);
                GL14C.glBlendFuncSeparate(previousSrcRgb, previousDstRgb, previousSrcAlpha, previousDstAlpha);
            }
            if (previousBlend) {
                GL30C.glEnablei(GL11C.GL_BLEND, 0);
            } else {
                GL30C.glDisablei(GL11C.GL_BLEND, 0);
            }
            restoreCapability(GL11C.GL_DEPTH_TEST, previousDepth);
            restoreCapability(GL11C.GL_SCISSOR_TEST, previousScissor);
            restoreCapability(GL11C.GL_CULL_FACE, previousCull);
            restoreCapability(GL11C.GL_STENCIL_TEST, previousStencil);
            restoreCapability(GL11C.GL_COLOR_LOGIC_OP, previousLogicOp);
            restoreCapability(GL30C.GL_RASTERIZER_DISCARD, previousRasterizerDiscard);
            GL30C.glColorMaski(0, previousColorMask[0] != 0, previousColorMask[1] != 0,
                previousColorMask[2] != 0, previousColorMask[3] != 0);
            // Minecraft uses a core profile: polygon mode applies to both faces,
            // and querying it may return only one value.
            GL11C.glPolygonMode(GL11C.GL_FRONT_AND_BACK, previousPolygonMode[0]);
            GL11C.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3]);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
        }
    }

    private static void restoreCapability(int capability, boolean enabled) {
        if (enabled) {
            GL11C.glEnable(capability);
        } else {
            GL11C.glDisable(capability);
        }
    }

    private static void initializeCompositeShader() {
        if (compositeProgram != 0) {
            return;
        }
        int vertexShader = compileCompositeShader(GL20C.GL_VERTEX_SHADER, """
            #version 150
            out vec2 texCoord;
            void main() {
                vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                texCoord = corner;
                gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
            }
            """);
        int fragmentShader = 0;
        int program = 0;
        try {
            fragmentShader = compileCompositeShader(GL20C.GL_FRAGMENT_SHADER, """
                #version 150
                uniform sampler2D source;
                in vec2 texCoord;
                out vec4 fragColor;
                void main() {
                    // Editor overlay and output have matching physical dimensions.
                    // A single exact level-zero fetch preserves crisp UI pixels and
                    // avoids four redundant manual bilinear fetches per screen pixel.
                    ivec2 size = textureSize(source, 0);
                    ivec2 pixel = clamp(ivec2(texCoord * vec2(size)), ivec2(0), size - ivec2(1));
                    fragColor = texelFetch(source, pixel, 0);
                }
                """);
            program = GL20C.glCreateProgram();
            GL20C.glAttachShader(program, vertexShader);
            GL20C.glAttachShader(program, fragmentShader);
            GL30C.glBindFragDataLocation(program, 0, "fragColor");
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
                throw new IllegalStateException("Could not link framebuffer composite shader: "
                    + GL20C.glGetProgramInfoLog(program));
            }
            int samplerLocation = GL20C.glGetUniformLocation(program, "source");
            int vertexArray = GL30C.glGenVertexArrays();
            if (vertexArray == 0) {
                throw new IllegalStateException("Could not create framebuffer composite vertex array");
            }
            compositeSamplerLocation = samplerLocation;
            compositeVertexArray = vertexArray;
            compositeProgram = program;
        } catch (RuntimeException | Error exception) {
            if (program != 0) {
                GL20C.glDeleteProgram(program);
            }
            throw exception;
        } finally {
            GL20C.glDeleteShader(vertexShader);
            if (fragmentShader != 0) {
                GL20C.glDeleteShader(fragmentShader);
            }
        }
    }

    private static int compileCompositeShader(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        try {
            GL20C.glShaderSource(shader, source);
            GL20C.glCompileShader(shader);
            if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
                throw new IllegalStateException("Could not compile framebuffer composite shader: "
                    + GL20C.glGetShaderInfoLog(shader));
            }
            return shader;
        } catch (RuntimeException | Error exception) {
            GL20C.glDeleteShader(shader);
            throw exception;
        }
    }

    public static void clear(RenderTarget target, Vector4f clearColor) {
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] previousScissor = captureScissorState();
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        try {
            target.bindWrite(true);
            RenderSystem.clearColor(clearColor.x, clearColor.y, clearColor.z, clearColor.w);
            RenderSystem.clear(GL11C.GL_COLOR_BUFFER_BIT | (target.useDepth ? GL11C.GL_DEPTH_BUFFER_BIT : 0), Minecraft.ON_OSX);
        } finally {
            target.unbindWrite();
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
            restoreScissorState(previousScissor);
        }
    }

    public static RenderTarget resizeOrCreateFramebuffer(RenderTarget target, int width, int height, boolean useDepth) {
        if (target == null) {
            return new TextureTarget(width, height, useDepth, Minecraft.ON_OSX);
        }
        if (target.width != width || target.height != height) {
            target.resize(width, height, Minecraft.ON_OSX);
        }
        return target;
    }

    /** Blits a full RenderTarget into normalized destination bounds. */
    public static void blitTo(RenderTarget from, RenderTarget to, float x1, float y1, float x2, float y2) {
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] previousScissor = captureScissorState();
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        try {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, from.frameBufferId);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
            int dx1 = Math.round(x1 * to.width);
            int dx2 = Math.round(x2 * to.width);
            int dyTop = Math.round(y1 * to.height);
            int dyBottom = Math.round(y2 * to.height);
            GL30C.glBlitFramebuffer(0, 0, from.width, from.height,
                dx1, to.height - dyBottom, dx2, to.height - dyTop,
                GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_LINEAR);
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw);
            restoreScissorState(previousScissor);
        }
    }

    private static int[] captureScissorState() {
        int[] state = new int[5];
        state[0] = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST) ? 1 : 0;
        int[] box = new int[4];
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, box);
        System.arraycopy(box, 0, state, 1, 4);
        return state;
    }

    private static void restoreScissorState(int[] state) {
        GL11C.glScissor(state[1], state[2], state[3], state[4]);
        if (state[0] != 0) {
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
        } else {
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        }
    }

    public static void blitTo(RenderTarget from, RenderTarget to) {
        blitTo(from, to, 0.0f, 0.0f, 1.0f, 1.0f);
    }
}
