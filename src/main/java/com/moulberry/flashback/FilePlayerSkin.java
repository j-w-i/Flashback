package com.moulberry.flashback;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** A replay/editor skin loaded as a 1.21.1 texture and model variant. */
public class FilePlayerSkin {

    private static class CleanState implements Runnable {
        private ResourceLocation texture;

        private CleanState(ResourceLocation texture) {
            this.texture = texture;
        }

        @Override
        public void run() {
            if (this.texture != null) {
                ResourceLocation toRelease = this.texture;
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(toRelease));
                this.texture = null;
            }
        }
    }

    private final String pathToSkin;
    private boolean loaded;
    private @Nullable ResourceLocation texture;
    private String modelName = "default";

    public FilePlayerSkin(String pathToSkin) {
        this.pathToSkin = pathToSkin;
    }

    public @Nullable ResourceLocation getTexture() {
        loadIfNeeded();
        return this.texture;
    }

    public String getModelName() {
        loadIfNeeded();
        return this.modelName;
    }

    private void loadIfNeeded() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        try (InputStream inputStream = Files.newInputStream(Path.of(this.pathToSkin))) {
            NativeImage image = NativeImage.read(inputStream);
            int alphaSample = image.getPixelRGBA(54 * image.getWidth() / 64, 20 * image.getHeight() / 64);
            if (((alphaSample >>> 24) & 0xFF) < 20) {
                this.modelName = "slim";
            }

            ResourceLocation location = ResourceLocation.fromNamespaceAndPath("flashback", "skin_from_file/" + UUID.randomUUID());
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            GlobalCleaner.INSTANCE.register(this, new CleanState(location));
            this.texture = location;
        } catch (Exception e) {
            Flashback.LOGGER.error("Unable to load skin from file", e);
        }
    }
}
