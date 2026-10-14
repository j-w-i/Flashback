package com.moulberry.flashback.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Minecraft 1.21.1 has no LevelExtractor/render-state extraction stage. The equivalent legacy
 * render hooks remain in MixinLevelRenderer, and recorded first-person hands are handled by
 * MixinGameRenderer/ItemInHandRenderer. There is intentionally no extraction mixin here.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelExtractor {
}
