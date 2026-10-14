package com.moulberry.flashback.visuals;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/** Registers the legacy OpenGL shaders used by the 1.21.1 renderer. */
public final class ShaderManager {
    public static final ShaderManager INSTANCE = new ShaderManager();

    // Lower-case aliases are retained for the OpenGL backend; upper-case names keep call sites
    // source-compatible while adjacent renderer/mixin code is being ported from RenderPearl.
    public static ShaderInstance blitScreenRoundAlpha;
    public static ShaderInstance blitScreenFlip;
    public static ShaderInstance BLIT_SCREEN_ROUND_ALPHA;
    public static ShaderInstance BLIT_SCREEN_FLIP;

    static {
        INSTANCE.register();
    }

    private ShaderManager() {}

    public void register() {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            context.register(ResourceLocation.parse("flashback:blit_screen_round_alpha"), DefaultVertexFormat.BLIT_SCREEN, shader -> {
                blitScreenRoundAlpha = shader;
                BLIT_SCREEN_ROUND_ALPHA = shader;
            });
            context.register(ResourceLocation.parse("flashback:blit_screen_flip"), DefaultVertexFormat.BLIT_SCREEN, shader -> {
                blitScreenFlip = shader;
                BLIT_SCREEN_FLIP = shader;
            });
        });
    }
}
