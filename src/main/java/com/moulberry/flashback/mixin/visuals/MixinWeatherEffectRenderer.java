package com.moulberry.flashback.mixin.visuals;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class MixinWeatherEffectRenderer {

    @Unique
    private Biome.Precipitation flashback$forcedPrecipitation;

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), require = 0)
    private void flashback$selectPrecipitation(LightTexture lightTexture, float partialTick,
                                                double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
        this.flashback$forcedPrecipitation = null;
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null) {
            this.flashback$forcedPrecipitation = switch (editorState.replayVisuals.overrideWeatherMode) {
                case NONE -> null;
                case CLEAR, OVERCAST -> Biome.Precipitation.NONE;
                case RAINING, THUNDERING -> Biome.Precipitation.RAIN;
                case SNOWING -> Biome.Precipitation.SNOW;
            };
        }
    }

    @WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;hasPrecipitation()Z"), require = 0)
    private boolean flashback$hasPrecipitation(Biome biome, Operation<Boolean> original) {
        if (this.flashback$forcedPrecipitation != null) {
            return this.flashback$forcedPrecipitation != Biome.Precipitation.NONE;
        }
        return original.call(biome);
    }

    @WrapOperation(method = "renderSnowAndRain", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"), require = 0)
    private Biome.Precipitation flashback$getPrecipitationAt(Biome biome, net.minecraft.core.BlockPos pos,
                                                              Operation<Biome.Precipitation> original) {
        if (this.flashback$forcedPrecipitation != null) {
            return this.flashback$forcedPrecipitation;
        }
        return original.call(biome, pos);
    }
}
