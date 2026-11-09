package dev.donut.client.gui;

import dev.donut.client.config.ClientConfig;
import dev.donut.client.input.ModuleKeybinds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import static dev.donut.client.gui.UiPaint.*;

/** A live, unscaled screen-space preview with the same bounds as the in-game overlay. */
public final class ProgressBarEditorScreen extends Screen {
    private final Screen parent;
    private final ClientConfig config;
    private final Runnable save;
    private boolean dragging, dirty;
    private double grabX, grabY;

    public ProgressBarEditorScreen(Screen parent, ClientConfig config, Runnable save) {
        super(Component.literal("Move Progress Bar"));
        this.parent = parent;
        this.config = config;
        this.save = save;
    }

    @Override protected void init() {
        dragging = false;
        int buttonWidth = Math.min(132, Math.max(60, (width - 32) / 2));
        addRenderableWidget(Button.builder(Component.literal("Reset position"), button -> {
            config.progressBar.x = .5;
            config.progressBar.y = .12;
            dirty = true;
            flush();
        }).bounds(width / 2 - buttonWidth - 4, height - 32, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(width / 2 + 4, height - 32, buttonWidth, 20).build());
    }

    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x28060A12);
        if (dragging) {
            g.fill(width / 2, 0, width / 2 + 1, height, 0x307A91B8);
            g.fill(0, height / 2, width, height / 2 + 1, 0x307A91B8);
        }
        ProgressBarHud.render(g, config, config.progressBar.completedStages);
        var bounds = ProgressBarHud.layout(width, height, config);
        if (dragging || bounds.contains(mx, my)) {
            outline(g, bounds.x(), bounds.y(), bounds.width(), bounds.height(), 10, 0x70DFE8FF);
        }
        String hint = "Drag to move  /  Arrow keys to adjust  /  Esc to return";
        // Keep the editing instructions out of the captured overlay area.
        smallText(g, font, hint, Math.max(6, (width - smallWidth(font, hint)) / 2f), height - 49, 0xFFD2D9EA);
        if (!config.progressBar.enabled) {
            String preview = "Progress Bar preview - enable the module to show it in game";
            smallText(g, font, preview, Math.max(6, (width - smallWidth(font, preview)) / 2f), height - 64, 0xFFA6B0C7);
        }
        super.render(g, mx, my, delta);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (super.mouseClicked(event, doubled)) return true;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        var bounds = ProgressBarHud.layout(width, height, config);
        if (!bounds.contains(event.x(), event.y())) return false;
        dragging = true;
        grabX = event.x() - bounds.x();
        grabY = event.y() - bounds.y();
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) return super.mouseDragged(event, dx, dy);
        ProgressBarHud.moveTo(config, width, height, event.x() - grabX, event.y() - grabY);
        dirty = true;
        return true;
    }

    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (!dragging) return super.mouseReleased(event);
        dragging = false;
        flush();
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        int dx = event.key() == GLFW.GLFW_KEY_LEFT ? -1 : event.key() == GLFW.GLFW_KEY_RIGHT ? 1 : 0;
        int dy = event.key() == GLFW.GLFW_KEY_UP ? -1 : event.key() == GLFW.GLFW_KEY_DOWN ? 1 : 0;
        if (dx != 0 || dy != 0) {
            int step = (event.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
            var bounds = ProgressBarHud.layout(width, height, config);
            ProgressBarHud.moveTo(config, width, height, bounds.x() + dx * step, bounds.y() + dy * step);
            dirty = true;
            flush();
            return true;
        }
        return super.keyPressed(event);
    }

    private void flush() {
        if (dirty) { save.run(); dirty = false; }
    }

    @Override public void onClose() {
        flush();
        minecraft.setScreen(parent);
    }

    @Override public void removed() {
        dragging = false;
        flush();
        ModuleKeybinds.suppressUntilReleased(minecraft);
    }

    @Override public boolean isPauseScreen() { return false; }
}
