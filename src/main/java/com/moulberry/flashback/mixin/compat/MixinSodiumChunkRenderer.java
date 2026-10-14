package com.moulberry.flashback.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Sodium 0.5.x (the 1.21.1 generation) keeps these flags in SodiumGameOptions. */
@IfModLoaded(value = "sodium", aliases = "embeddium")
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class MixinSodiumChunkRenderer {

    @WrapOperation(method = "render", require = 0, remap = false,
            at = @At(value = "FIELD", target = "Lme/jellysquid/mods/sodium/client/gui/SodiumGameOptions$PerformanceSettings;useBlockFaceCulling:Z", opcode = Opcodes.GETFIELD, remap = false))
    private boolean flashback$disableFaceCullingForOrtho(Object performanceSettings, Operation<Boolean> original) {
        ExportJob exportJob = Flashback.EXPORT_JOB;
        if (exportJob != null && exportJob.getSettings().projection() == ExportProjection.ORTHOGRAPHIC) {
            return false;
        }
        return original.call(performanceSettings);
    }
}
