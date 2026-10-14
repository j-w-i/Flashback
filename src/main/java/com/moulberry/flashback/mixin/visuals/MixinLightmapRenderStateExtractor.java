package com.moulberry.flashback.mixin.visuals;

import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 1.21.1 has no LightmapRenderStateExtractor. Its night-vision override is supplied through the
 * legacy LightTexture.updateLightTexture hook in MixinLightTexture instead.
 */
@Mixin(LightTexture.class)
public abstract class MixinLightmapRenderStateExtractor {
}
