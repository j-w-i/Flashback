package com.moulberry.flashback.mixin.playback;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftServer.class)
public class MixinMinecraftServer {

    /**
     * Replay update-tags packets have already installed the recorded tags on the registry. A replay
     * server reloads from an empty data-pack set, so the normal resource-reload callback would erase
     * those tags. 1.21.1 applies them through ReloadableServerResources.updateRegistryTags().
     */
    @WrapOperation(
        method = "method_29440",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/ReloadableServerResources;updateRegistryTags()V")
    )
    private void flashback$preserveReplayTags(ReloadableServerResources resources, Operation<Void> original) {
        if ((Object) this instanceof ReplayServer) {
            return;
        }
        original.call(resources);
    }
}
