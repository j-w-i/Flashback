package com.moulberry.flashback.editor.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import imgui.moulberry90.ImDrawData;
import imgui.moulberry90.ImFontAtlas;
import imgui.moulberry90.ImVec2;
import imgui.moulberry90.ImVec4;
import imgui.moulberry90.type.ImInt;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL32C;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Set;

/** OpenGL 3.x implementation of Flashback's ImGui draw-data renderer. */
public class CustomImGuiImplB3D implements CustomImGuiRenderer {
    private int program;
    private int vertexShader;
    private int fragmentShader;
    private int vertexArray;
    private int vertexBuffer;
    private int indexBuffer;
    private int fontTexture;
    private int textureUniform;
    private int projectionUniform;
    private int positionAttribute;
    private int uvAttribute;
    private int colorAttribute;
    private TextureTarget renderTarget;
    private ByteBuffer vertexHost;
    private ByteBuffer indexHost;
    private final Set<Long> nearestTextures = new HashSet<>();
    private final ImVec2 displayPos = new ImVec2();
    private final ImVec2 framebufferScale = new ImVec2();
    private final ImVec4 clipRect = new ImVec4();
    private final float[] projection = new float[16];

    @Override
    public void init() {
        if (this.program != 0) {
            return;
        }
        this.vertexShader = this.compile(GL20C.GL_VERTEX_SHADER, VERTEX_SHADER);
        this.fragmentShader = this.compile(GL20C.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        this.program = GL20C.glCreateProgram();
        GL20C.glAttachShader(this.program, this.vertexShader);
        GL20C.glAttachShader(this.program, this.fragmentShader);
        GL20C.glLinkProgram(this.program);
        if (GL20C.glGetProgrami(this.program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            throw new IllegalStateException("Could not link Flashback ImGui OpenGL program: " + GL20C.glGetProgramInfoLog(this.program));
        }
        this.textureUniform = GL20C.glGetUniformLocation(this.program, "Texture");
        this.projectionUniform = GL20C.glGetUniformLocation(this.program, "ProjMtx");
        this.positionAttribute = GL20C.glGetAttribLocation(this.program, "Position");
        this.uvAttribute = GL20C.glGetAttribLocation(this.program, "UV");
        this.colorAttribute = GL20C.glGetAttribLocation(this.program, "Color");
        this.vertexArray = GL30C.glGenVertexArrays();
        this.vertexBuffer = GL15C.glGenBuffers();
        this.indexBuffer = GL15C.glGenBuffers();
        this.updateFontsTexture();
    }

    private int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shader);
            GL20C.glDeleteShader(shader);
            throw new IllegalStateException("Could not compile Flashback ImGui shader: " + log);
        }
        return shader;
    }

    @Override
    public long getTextureId(int glTextureId) {
        return Integer.toUnsignedLong(glTextureId);
    }

    @Override
    public void setSampleLinear(long id) {
        this.nearestTextures.remove(id);
    }

    @Override
    public void setSampleNearest(long id) {
        this.nearestTextures.add(id);
    }

    @Override
    public void updateFontsTexture() {
        ImFontAtlas atlas = ReplayUI.getIO().getFonts();
        ImInt width = new ImInt();
        ImInt height = new ImInt();
        ByteBuffer pixels = atlas.getTexDataAsRGBA32(width, height);
        int textureWidth = width.intValue();
        int textureHeight = height.intValue();
        long requiredBytes = (long) textureWidth * textureHeight * 4L;
        if (textureWidth <= 0 || textureHeight <= 0 || pixels == null || !pixels.isDirect()
                || requiredBytes > pixels.capacity()) {
            throw new IllegalStateException("Invalid ImGui font atlas: " + textureWidth + "x" + textureHeight
                + ", direct=" + (pixels != null && pixels.isDirect()) + ", capacity="
                + (pixels == null ? -1 : pixels.capacity()));
        }

        int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int previousPixelUnpackBuffer = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int previousUnpackAlignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
        int previousUnpackRowLength = GL11C.glGetInteger(GL12C.GL_UNPACK_ROW_LENGTH);
        int previousSkipRows = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS);
        int previousSkipPixels = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS);
        try {
            // Minecraft may leave a pixel-unpack buffer bound during a resource upload.
            // This upload uses client memory, so explicitly unbind it to avoid treating the
            // ByteBuffer address as a PBO offset (which can crash native GL drivers).
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);

            if (this.fontTexture != 0) {
                GL11C.glDeleteTextures(this.fontTexture);
            }
            this.fontTexture = GL11C.glGenTextures();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, this.fontTexture);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            ByteBuffer uploadPixels = pixels.duplicate();
            uploadPixels.position(0);
            uploadPixels.limit((int) requiredBytes);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_RGBA, textureWidth, textureHeight, 0,
                GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, uploadPixels);
            atlas.setTexID(Integer.toUnsignedLong(this.fontTexture));
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
            GL11C.glPixelStorei(GL12C.GL_UNPACK_ROW_LENGTH, previousUnpackRowLength);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, previousSkipRows);
            GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, previousSkipPixels);
            GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, previousPixelUnpackBuffer);
        }
    }

    @Override
    public RenderTarget renderDrawData(ImDrawData drawData) {
        int framebufferWidth = (int) (drawData.getDisplaySizeX() * drawData.getFramebufferScaleX());
        int framebufferHeight = (int) (drawData.getDisplaySizeY() * drawData.getFramebufferScaleY());
        if (framebufferWidth <= 0 || framebufferHeight <= 0 || drawData.getCmdListsCount() == 0) {
            return null;
        }
        if (this.program == 0) {
            this.init();
        }

        int vertexBytes = drawData.getTotalVtxCount() * ImDrawData.sizeOfImDrawVert();
        int indexBytes = drawData.getTotalIdxCount() * ImDrawData.sizeOfImDrawIdx();
        this.vertexHost = ensureCapacity(this.vertexHost, vertexBytes);
        this.indexHost = ensureCapacity(this.indexHost, indexBytes);
        this.vertexHost.clear();
        this.indexHost.clear();
        for (int i = 0; i < drawData.getCmdListsCount(); i++) {
            this.vertexHost.put(drawData.getCmdListVtxBufferData(i));
            this.indexHost.put(drawData.getCmdListIdxBufferData(i));
        }
        this.vertexHost.flip();
        this.indexHost.flip();

        if (this.renderTarget == null) {
            this.renderTarget = new TextureTarget(framebufferWidth, framebufferHeight, false, false);
        } else if (this.renderTarget.width != framebufferWidth || this.renderTarget.height != framebufferHeight) {
            this.renderTarget.resize(framebufferWidth, framebufferHeight, false);
        }

        GLState state = GLState.capture();
        try {
            this.renderTarget.bindWrite(true);
            GL11C.glViewport(0, 0, framebufferWidth, framebufferHeight);
            GL11C.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT);
            GL11C.glEnable(GL11C.GL_BLEND);
            GL14C.glBlendEquation(GL14C.GL_FUNC_ADD);
            GL14C.glBlendFuncSeparate(GL11C.GL_SRC_ALPHA, GL11C.GL_ONE_MINUS_SRC_ALPHA, GL11C.GL_ONE, GL11C.GL_ONE_MINUS_SRC_ALPHA);
            GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);
            GL11C.glEnable(GL11C.GL_SCISSOR_TEST);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL20C.glUseProgram(this.program);
            GL20C.glUniform1i(this.textureUniform, 0);

            this.setupProjection(drawData);
            GL20C.glUniformMatrix4fv(this.projectionUniform, false, this.projection);
            GL30C.glBindVertexArray(this.vertexArray);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.vertexBuffer);
            GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, this.vertexHost, GL15C.GL_STREAM_DRAW);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, this.indexBuffer);
            GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, this.indexHost, GL15C.GL_STREAM_DRAW);
            int stride = ImDrawData.sizeOfImDrawVert();
            GL20C.glEnableVertexAttribArray(this.positionAttribute);
            GL20C.glEnableVertexAttribArray(this.uvAttribute);
            GL20C.glEnableVertexAttribArray(this.colorAttribute);
            GL20C.glVertexAttribPointer(this.positionAttribute, 2, GL11C.GL_FLOAT, false, stride, 0L);
            GL20C.glVertexAttribPointer(this.uvAttribute, 2, GL11C.GL_FLOAT, false, stride, 8L);
            GL20C.glVertexAttribPointer(this.colorAttribute, 4, GL11C.GL_UNSIGNED_BYTE, true, stride, 16L);

            this.drawCommands(drawData, framebufferWidth, framebufferHeight);
        } finally {
            this.renderTarget.unbindWrite();
            state.restore();
        }
        return this.renderTarget;
    }

    private void drawCommands(ImDrawData data, int framebufferWidth, int framebufferHeight) {
        this.displayPos.set(data.getDisplayPosX(), data.getDisplayPosY());
        this.framebufferScale.set(data.getFramebufferScaleX(), data.getFramebufferScaleY());
        int globalVertexOffset = 0;
        int globalIndexOffset = 0;
        int indexSize = ImDrawData.sizeOfImDrawIdx();
        int glIndexType = indexSize == 2 ? GL11C.GL_UNSIGNED_SHORT : GL11C.GL_UNSIGNED_INT;
        for (int list = 0; list < data.getCmdListsCount(); list++) {
            for (int command = 0; command < data.getCmdListCmdBufferSize(list); command++) {
                long textureId = data.getCmdListCmdBufferTextureId(list, command);
                int texture = (int) textureId;
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
                int filter = this.nearestTextures.contains(textureId) ? GL11C.GL_NEAREST : GL11C.GL_LINEAR;
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, filter);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, filter);

                ImVec4 rect = data.getCmdListCmdBufferClipRect(list, command);
                int x1 = Math.max(0, (int) ((rect.x - this.displayPos.x) * this.framebufferScale.x));
                int y1 = Math.max(0, (int) ((rect.y - this.displayPos.y) * this.framebufferScale.y));
                int x2 = Math.min(framebufferWidth, (int) ((rect.z - this.displayPos.x) * this.framebufferScale.x));
                int y2 = Math.min(framebufferHeight, (int) ((rect.w - this.displayPos.y) * this.framebufferScale.y));
                if (x2 > x1 && y2 > y1) {
                    GL11C.glScissor(x1, framebufferHeight - y2, x2 - x1, y2 - y1);
                    int count = data.getCmdListCmdBufferElemCount(list, command);
                    int firstIndex = data.getCmdListCmdBufferIdxOffset(list, command) + globalIndexOffset;
                    int firstVertex = data.getCmdListCmdBufferVtxOffset(list, command) + globalVertexOffset;
                    GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, count, glIndexType, (long) firstIndex * indexSize, firstVertex);
                }
            }
            globalVertexOffset += data.getCmdListVtxBufferSize(list);
            globalIndexOffset += data.getCmdListIdxBufferSize(list);
        }
    }

    private void setupProjection(ImDrawData data) {
        float left = data.getDisplayPosX();
        float right = left + data.getDisplaySizeX();
        float top = data.getDisplayPosY();
        float bottom = top + data.getDisplaySizeY();
        this.projection[0] = 2.0f / (right - left);
        this.projection[5] = 2.0f / (top - bottom);
        this.projection[10] = -1.0f;
        this.projection[12] = (right + left) / (left - right);
        this.projection[13] = (top + bottom) / (bottom - top);
        this.projection[14] = 0.0f;
        this.projection[15] = 1.0f;
    }

    private static ByteBuffer ensureCapacity(ByteBuffer buffer, int size) {
        if (buffer == null || buffer.capacity() < size) {
            return ByteBuffer.allocateDirect(size + 4096).order(ByteOrder.nativeOrder());
        }
        return buffer;
    }

    private static final String VERTEX_SHADER = """
        #version 150
        uniform mat4 ProjMtx;
        in vec2 Position;
        in vec2 UV;
        in vec4 Color;
        out vec2 Frag_UV;
        out vec4 Frag_Color;
        void main() {
            Frag_UV = UV;
            Frag_Color = Color;
            gl_Position = ProjMtx * vec4(Position, 0.0, 1.0);
        }
        """;

    private static final String FRAGMENT_SHADER = """
        #version 150
        uniform sampler2D Texture;
        in vec2 Frag_UV;
        in vec4 Frag_Color;
        out vec4 Out_Color;
        void main() {
            Out_Color = Frag_Color * texture(Texture, Frag_UV);
        }
        """;

    private static final class GLState {
        private final int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        private final int vao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        private final int arrayBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        private final int elementBuffer = GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        private final int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        private final int texture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        private final int texture0;
        private final int readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        private final int drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        private final int[] viewport = new int[4];
        private final int[] scissor = new int[4];
        private final int blendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        private final int blendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        private final int blendSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        private final int blendDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        private final int blendEquationRgb = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB);
        private final int blendEquationAlpha = GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA);
        private final boolean blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        private final boolean cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        private final boolean depth = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        private final boolean scissorEnabled = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);

        private GLState() {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            this.texture0 = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL13C.glActiveTexture(this.activeTexture);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, this.viewport);
            GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, this.scissor);
        }

        static GLState capture() { return new GLState(); }

        void restore() {
            GL20C.glUseProgram(this.program);
            GL30C.glBindVertexArray(this.vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, this.arrayBuffer);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, this.elementBuffer);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, this.texture0);
            GL13C.glActiveTexture(this.activeTexture);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, this.texture);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, this.readFramebuffer);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, this.drawFramebuffer);
            GL14C.glBlendFuncSeparate(this.blendSrcRgb, this.blendDstRgb, this.blendSrcAlpha, this.blendDstAlpha);
            GL20C.glBlendEquationSeparate(this.blendEquationRgb, this.blendEquationAlpha);
            GL11C.glViewport(this.viewport[0], this.viewport[1], this.viewport[2], this.viewport[3]);
            GL11C.glScissor(this.scissor[0], this.scissor[1], this.scissor[2], this.scissor[3]);
            setEnabled(GL11C.GL_BLEND, this.blend);
            setEnabled(GL11C.GL_CULL_FACE, this.cull);
            setEnabled(GL11C.GL_DEPTH_TEST, this.depth);
            setEnabled(GL11C.GL_SCISSOR_TEST, this.scissorEnabled);
        }

        private static void setEnabled(int capability, boolean enabled) {
            if (enabled) GL11C.glEnable(capability); else GL11C.glDisable(capability);
        }
    }
}
