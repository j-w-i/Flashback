package com.moulberry.flashback.mixin.playback;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.combo_options.ExportProjection;
import com.moulberry.flashback.exporting.ExportJob;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft 1.21.1 has no separate {@code Projection} object. Its projection is built by
 * {@link GameRenderer#getProjectionMatrix(double)}, so export projections are substituted there.
 */
@Mixin(GameRenderer.class)
public abstract class MixinProjection {

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true, require = 0)
    private void flashback$exportProjection(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        ExportJob exportJob = Flashback.EXPORT_JOB;
        if (exportJob == null) {
            return;
        }

        ExportProjection projection = exportJob.getSettings().projection();
        if (projection != ExportProjection.ORTHOGRAPHIC
                && projection != ExportProjection.CUBE_MAP
                && projection != ExportProjection.EQUIRECTANGULAR) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        float far = minecraft.options.renderDistance().get() * 16.0F;
        float aspect = projection == ExportProjection.ORTHOGRAPHIC
                ? (float) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight())
                : 1.0F;
        Matrix4f exportMatrix;

        if (projection == ExportProjection.ORTHOGRAPHIC) {
            float fovDegrees = (float) fov;
            float orthoHeight = (float) (Math.tan(Math.toRadians(fovDegrees / 2.0F)) * far / 4.0F)
                    / exportJob.getSettings().orthographicZoom();
            float orthoWidth = aspect * orthoHeight;
            exportMatrix = new Matrix4f().setOrtho(
                    -orthoWidth / 2.0F, orthoWidth / 2.0F,
                    -orthoHeight / 2.0F, orthoHeight / 2.0F,
                    -far, far
            );
        } else {
            // The panorama/cube-map renderer renders square faces even when the export canvas is not square.
            exportMatrix = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05F, far);
        }

        cir.setReturnValue(exportMatrix);
    }
}
