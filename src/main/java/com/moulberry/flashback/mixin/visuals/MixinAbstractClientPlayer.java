package com.moulberry.flashback.mixin.visuals;

import com.mojang.authlib.GameProfile;
import com.moulberry.flashback.FilePlayerSkin;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class MixinAbstractClientPlayer extends Player {

    @Shadow
    public abstract @Nullable PlayerInfo getPlayerInfo();

    @Unique
    private @Nullable PlayerInfo fallbackPlayerInfo;

    protected MixinAbstractClientPlayer(Level level, BlockPos blockPos, float yRot, GameProfile profile) {
        super(level, blockPos, yRot, profile);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void flashback$init(ClientLevel clientLevel, GameProfile profile, CallbackInfo ci) {
        if (Flashback.isInReplay()) {
            if (Minecraft.getInstance().getConnection() != null) {
                try {
                    this.getPlayerInfo();
                } catch (Exception ignored) {
                }
            }
            this.fallbackPlayerInfo = new PlayerInfo(profile, false);
        }
    }

    @Inject(method = "getPlayerInfo", at = @At("RETURN"), cancellable = true)
    private void flashback$getPlayerInfo(CallbackInfoReturnable<PlayerInfo> cir) {
        if (cir.getReturnValue() == null) {
            cir.setReturnValue(this.fallbackPlayerInfo);
        }
    }

    @Unique
    private @Nullable PlayerInfo flashback$skinOverridePlayerInfo;

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void flashback$overrideSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        EditorState state = EditorStateManager.getCurrent();
        if (state == null) {
            return;
        }

        FilePlayerSkin fileSkin = state.skinOverrideFromFile.get(this.getUUID());
        if (fileSkin != null) {
            ResourceLocation texture = fileSkin.getTexture();
            if (texture != null) {
                PlayerSkin.Model model = "slim".equals(fileSkin.getModelName()) ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
                cir.setReturnValue(new PlayerSkin(texture, "", null, null, model, false));
            }
            return;
        }

        GameProfile profile = state.skinOverride.get(this.getUUID());
        if (profile != null) {
            if (this.flashback$skinOverridePlayerInfo == null || this.flashback$skinOverridePlayerInfo.getProfile() != profile) {
                this.flashback$skinOverridePlayerInfo = new PlayerInfo(profile, false);
            }
            cir.setReturnValue(this.flashback$skinOverridePlayerInfo.getSkin());
        }
    }
}
