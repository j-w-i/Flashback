package com.moulberry.flashback.editor.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import imgui.moulberry90.ImDrawData;

/** OpenGL-backed Dear ImGui renderer interface. Texture ids are native GL texture names. */
public interface CustomImGuiRenderer {
    void init();
    RenderTarget renderDrawData(ImDrawData drawData);
    long getTextureId(int glTextureId);
    void setSampleLinear(long id);
    void setSampleNearest(long id);
    void updateFontsTexture();
}
