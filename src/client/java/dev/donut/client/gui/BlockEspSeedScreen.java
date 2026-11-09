package dev.donut.client.gui;

import dev.donut.client.config.BlockEspSettings;
import dev.donut.client.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit numeric seed editor; invalid or cancelled input never replaces the saved seed. */
public final class BlockEspSeedScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private EditBox field;
    private String draft, error = "";

    public BlockEspSeedScreen(Screen parent, ClientConfig config, Runnable save) {
        super(Component.literal("BlockESP world seed"));
        this.parent = parent;
        this.config = config;
        this.save = save;
        draft = config.blockEsp.seed;
    }
    @Override protected void init() {
        int w = Math.min(330, width - 32), x = (width - w) / 2, y = height / 2 - 25;
        field = addRenderableWidget(new EditBox(font, x, y, w, 22, Component.literal("Numeric world seed")));
        field.setMaxLength(20);
        field.setValue(draft);
        field.setResponder(value -> draft = value);
        setInitialFocus(field);
        addRenderableWidget(Button.builder(Component.literal("Apply seed"), button -> apply())
                .bounds(x, y + 55, (w - 8) / 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(x + (w + 8) / 2, y + 55, (w - 8) / 2, 20).build());
    }
    private void apply() {
        try {
            long value = BlockEspSettings.parseSeed(draft);
            config.blockEsp.seed = Long.toString(value);
            save.run();
            onClose();
        } catch (IllegalArgumentException failure) { error = failure.getMessage(); }
    }
    @Override public void render(GuiGraphics graphics, int mx, int my, float delta) {
        graphics.fill(0, 0, width, height, 0xEE101722);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 76, 0xFFF0F5FA);
        graphics.drawCenteredString(font, "Vanilla Java 1.21.11 - original generated ore positions", width / 2, height / 2 - 54, 0xFFB5C7D8);
        graphics.drawCenteredString(font, font.plainSubstrByWidth(error, width - 24), width / 2, height / 2 + 8, 0xFFFF8590);
        super.render(graphics, mx, my, delta);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
