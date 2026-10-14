package com.moulberry.flashback.mixin.playback.lenient_registry;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.serialization.Codec;
import com.moulberry.flashback.Flashback;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Registry.class)
@SuppressWarnings({"rawtypes", "unchecked"})
public interface MixinRegistry {

    /**
     * In 1.21.1, referenceHolderWithLifecycle is the stable registry codec hook. Wrap its decode
     * result so missing references use the same default that lenient replay registries historically
     * supplied; the later synthetic lambda used by newer versions does not exist here.
     */
    @ModifyReturnValue(method = "referenceHolderWithLifecycle", at = @At("RETURN"))
    default Codec flashback$lenientHolderCodec(Codec original) {
        if (!Flashback.isInReplay()) {
            return original;
        }

        Holder.Reference<?> fallback;
        if (this == BuiltInRegistries.PARTICLE_TYPE) {
            fallback = BuiltInRegistries.PARTICLE_TYPE
                .getHolder(ResourceLocation.withDefaultNamespace("ambient_entity_effect"))
                .orElseThrow();
        } else if (this instanceof DefaultedRegistry<?> defaultedRegistry) {
            fallback = defaultedRegistry.getHolder(defaultedRegistry.getDefaultKey()).orElseThrow();
        } else {
            return original;
        }

        return original.orElse(fallback);
    }
}
