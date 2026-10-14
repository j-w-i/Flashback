package com.moulberry.flashback.screen;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.time.Duration;
import java.util.Objects;

public class UnsupportedLoaderScreen extends Screen {

    private Screen lastScreen;
    private final Component title;
    private final Component messageText;
    private MultiLineLabel message = MultiLineLabel.EMPTY;
    private long lastInitTime;
    private int countdown = 15;

    public UnsupportedLoaderScreen(Screen lastScreen, Component title, Component message) {
        super(title);
        this.lastScreen = lastScreen;
        this.title = title;
        this.messageText = message;
    }

    @Override
    public Component getNarrationMessage() {
        return CommonComponents.joinForNarration(super.getNarrationMessage(), this.messageText);
    }

    @Override
    protected void init() {
        super.init();

        this.lastInitTime = System.currentTimeMillis();
        this.message = MultiLineLabel.create(this.font, this.messageText, this.width - 50);
        int lines = this.message.getLineCount();
        int height = lines * this.font.lineHeight;
        int buttonY = Mth.clamp(90 + height + 12, this.height / 6 + 96, this.height - 24);

        this.clearWidgets();
        if (this.countdown == 0) {
            var button = Button.builder(Component.translatable("flashback.i_understand"), b -> {
                Flashback.getConfig().internal.nextUnsupportedModLoaderWarning = System.currentTimeMillis() + Duration.ofDays(7).toMillis();
                Flashback.getConfig().delayedSaveToDefaultFolder();
                Minecraft.getInstance().setScreen(this.lastScreen);
           }).bounds((this.width - 150) / 2, buttonY, 150, 20).build();
            this.addRenderableWidget(button);
        } else if (this.countdown <= 10) {
            var button = Button.builder(Component.translatable("flashback.i_understand_countdown", Component.literal(String.valueOf(this.countdown))), b -> {})
                               .bounds((this.width - 150) / 2, buttonY, 150, 20).build();
            button.active = false;
            this.addRenderableWidget(button);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 70, 0xFFFFFFFF);
        int y = 90;
        for (var line : this.font.split(this.messageText, this.width - 50)) {
            guiGraphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFFFF);
            y += this.font.lineHeight;
        }

        if (this.countdown > 0 && System.currentTimeMillis() - this.lastInitTime > 1000) {
            this.countdown -= 1;
            this.init();
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

}
