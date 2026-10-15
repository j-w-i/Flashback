package com.moulberry.flashback.mixin.gui;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.gui.GuiDisplay;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Draws the recorded player in the inventory screen's character preview.
 *
 * <p>The screen the replay shows is built over the viewer, because the game offers no other player to build
 * it over, and the preview draws whoever the screen was built for. In a replay that is the viewer, who is not
 * in the world to be drawn, so the preview was an empty black box. The recorded player is the one the
 * inventory belongs to, so they are what the preview should show.
 */
@Mixin(InventoryScreen.class)
public class MixinInventoryScreenPreview {

    @WrapOperation(method = "renderBg", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/screens/inventory/InventoryScreen;renderEntityInInventoryFollowsMouse(Lnet/minecraft/client/gui/GuiGraphics;IIIIIFFFLnet/minecraft/world/entity/LivingEntity;)V"),
        require = 0)
    private void flashback$previewRecordedPlayer(GuiGraphics graphics, int x1, int y1, int x2, int y2, int scale,
            float yOffset, float mouseX, float mouseY, LivingEntity entity, Operation<Void> original) {
        if (Flashback.isInReplay()) {
            Player recorded = GuiDisplay.recordedPlayer();
            if (recorded != null) {
                entity = recorded;
            }
        }
        original.call(graphics, x1, y1, x2, y2, scale, yOffset, mouseX, mouseY, entity);
    }
}
