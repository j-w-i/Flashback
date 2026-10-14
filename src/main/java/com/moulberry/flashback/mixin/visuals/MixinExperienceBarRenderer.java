package com.moulberry.flashback.mixin.visuals;

import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;

/**
 * In 1.21.1 the experience bar is rendered directly by Gui. The replay-player substitutions for
 * XP progress and level are already implemented by MixinHud on Gui.renderExperienceBar; the later
 * standalone ExperienceBar extraction class does not exist here.
 */
@Mixin(Gui.class)
public abstract class MixinExperienceBarRenderer {
}
