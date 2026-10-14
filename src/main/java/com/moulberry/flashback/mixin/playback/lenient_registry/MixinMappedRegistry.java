package com.moulberry.flashback.mixin.playback.lenient_registry;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayConfigurationPacketHandler;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

@Mixin(MappedRegistry.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class MixinMappedRegistry {

    @Shadow
    private boolean frozen;

    @Inject(method = "freeze", at = @At("HEAD"))
    public void freeze(CallbackInfoReturnable<Registry> cir) {
        if (!this.frozen && Flashback.isInReplay()) {
            // Replay datapacks are intentionally empty; bind any uninitialized named tags as empty
            // using the public 1.21.1 registry API instead of HolderSet.Named's package-private bind.
            ((Registry<Object>) (Object) this).bindTags(Map.of());
        }
    }

}
