package com.moulberry.flashback.screen;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Compatibility shim for the legacy options-screen entry point. Current settings are rendered by
 * Flashback's Lattice screen, so redirect here instead of trying to expose removed OptionInstances.
 */
public class ConfigScreen extends OptionsSubScreen {
    private final Screen previous;

    public ConfigScreen(Screen previous) {
        super(previous, Minecraft.getInstance().options, Component.literal("Flashback Options"));
        this.previous = previous;
    }

    @Override
    protected void addOptions() {
        // Settings are supplied by the Lattice config screen below.
    }

    @Override
    protected void init() {
        super.init();
        Minecraft.getInstance().setScreen(Flashback.createConfigScreen(this.previous));
    }

    @Override
    public void removed() {
        Flashback.getConfig().saveToDefaultFolder();
    }
}
