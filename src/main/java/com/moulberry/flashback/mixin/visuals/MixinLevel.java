package com.moulberry.flashback.mixin.visuals;

import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class MixinLevel {

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    public void getRainLevel(float f, CallbackInfoReturnable<Float> cir) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && (Object)this instanceof ClientLevel) {
            switch (editorState.replayVisuals.overrideWeatherMode) {
                case CLEAR -> cir.setReturnValue(0.0f);
                case OVERCAST, RAINING, SNOWING, THUNDERING -> cir.setReturnValue(1.0f);
            }
        }
    }


    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    public void getThunderLevel(float f, CallbackInfoReturnable<Float> cir) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && (Object)this instanceof ClientLevel) {
            switch (editorState.replayVisuals.overrideWeatherMode) {
                case CLEAR, OVERCAST, RAINING, SNOWING -> cir.setReturnValue(0.0f);
                case THUNDERING -> cir.setReturnValue(1.0f);
            }
        }
    }

    /**
     * 1.21.1 has no default/overworld clock-time getters. Its normalized celestial time is derived
     * from Level#getDayTime, so override that client-side clock only while a replay visual override
     * is active.
     */
    @Inject(method = "getDayTime()J", at = @At("HEAD"), cancellable = true)
    public void getDayTime(CallbackInfoReturnable<Long> cir) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && (Object)this instanceof ClientLevel && editorState.replayVisuals.overrideTimeOfDay >= 0) {
            cir.setReturnValue((long) Math.floorMod(editorState.replayVisuals.overrideTimeOfDay, 24000));
        }
    }

}
