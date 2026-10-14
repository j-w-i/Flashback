package com.moulberry.flashback.mixin.visuals;

import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// LivingEntity.getItemBySlot is abstract in 1.21.1 and therefore has no HEAD instruction.
// Inject into each concrete implementation so the visibility override stays render-wide.
@Mixin({Mob.class, Player.class, ArmorStand.class})
public abstract class MixinLivingEntity {

    // This isn't ideal. Ideally we'd only do this during rendering, but unfortunately
    // there isn't a specific method to override when rendering. I also don't want to have
    // to manually wrap every getItemBySlot call inside the model rendering code, so here we are

    @Inject(method = "getItemBySlot(Lnet/minecraft/world/entity/EquipmentSlot;)Lnet/minecraft/world/item/ItemStack;", at = @At("HEAD"), cancellable = true)
    private void flashback$hideEquipment(EquipmentSlot equipmentSlot, CallbackInfoReturnable<ItemStack> cir) {
        Entity entity = (Entity) (Object) this;
        if (entity.level().isClientSide()) {
            EditorState editorState = EditorStateManager.getCurrent();
            if (editorState != null) {
                var hidden = editorState.hiddenEquipment.get(entity.getUUID());
                if (hidden != null && hidden.contains(equipmentSlot)) {
                    cir.setReturnValue(ItemStack.EMPTY);
                }
            }
        }
    }

}
