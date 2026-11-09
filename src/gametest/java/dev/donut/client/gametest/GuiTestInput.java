package dev.donut.client.gametest;

import dev.donut.client.gui.DonutScreen;
import dev.donut.client.gui.GlassPanel;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** Drives the real tile/inspector UI through its normal mouse and keyboard handlers. */
final class GuiTestInput {
    private static final Pattern OWNER = Pattern.compile("(?:m(\\d+)\\..*|keybind(\\d+)|favorite(\\d+))");
    private GuiTestInput() { }

    static void click(ClientGameTestContext context, String id) { click(context, id, 0, true); }
    static void rightClick(ClientGameTestContext context, String id) { click(context, id, 1, true); }
    static void clickOpeningScreen(ClientGameTestContext context, String id) { click(context, id, 0, false); }

    private static void click(ClientGameTestContext context, String id, int button, boolean settleAfter) {
        settle(context);
        reveal(context, id);
        moveTo(context, id);
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, id);
            require(hit != null, "Control disappeared before clicking: " + id);
            clickLocal(screen, hit.x + hit.w / 2, hit.y + hit.h / 2, button);
        });
        if (settleAfter) settle(context);
    }

    static void moveTo(ClientGameTestContext context, String id) {
        double[] position = context.computeOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, id);
            require(hit != null, "Cannot hover control: " + id);
            return windowPosition(screen, hit.x + hit.w / 2, hit.y + hit.h / 2, client.getWindow().getGuiScale());
        });
        context.getInput().setCursorPos(position[0], position[1]);
        context.waitTick();
    }

    static void type(ClientGameTestContext context, String value) {
        context.runOnClient(client -> value.codePoints().forEach(codepoint -> require(
                screen(client.screen).charTyped(new CharacterEvent(codepoint, 0)), "GUI did not consume text")));
        settle(context);
    }

    static void settle(ClientGameTestContext context) {
        context.waitTick();
        context.waitFor(client -> {
            DonutScreen screen = screen(client.screen);
            return number(screen, "screenProgress") == 1 && number(screen, "contentProgress") == 1
                    && number(screen, "detailProgress") == 1
                    && (field(screen, "colorEditor") == null || number(screen, "modalProgress") == 1);
        });
    }

    static void openAnimated(ClientGameTestContext context, Supplier<DonutScreen> factory) {
        context.setScreen(() -> {
            DonutScreen screen = factory.get();
            require(number(screen, "screenProgress") == 0, "A new GUI must start transparent");
            return screen;
        });
        context.waitForScreen(DonutScreen.class);
        context.waitFor(client -> {
            double progress = number(screen(client.screen), "screenProgress");
            require(progress < 1, "Opening skipped its transition");
            return progress > 0;
        });
        settle(context);
    }

    static void assertSelectionAnimation(ClientGameTestContext context, int module) {
        settle(context);
        String id = "module" + module;
        reveal(context, id);
        moveTo(context, id);
        settle(context);
        boolean changed = context.computeOnClient(client -> number(screen(client.screen), "selectedModule") != module
                || number(screen(client.screen), "previewModule") != module);
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, id);
            clickLocal(screen, hit.x + 10, hit.y + 10, 0);
            require(number(screen, "selectedModule") == module, "Tile did not select the inspector");
            require(number(screen, "detailProgress") == (changed ? 0 : 1), "Inspector selection used the wrong transition");
        });
        if (changed) context.waitFor(client -> {
            double progress = number(screen(client.screen), "detailProgress");
            require(progress < 1, "Inspector skipped intermediate frames");
            return progress > .1;
        });
        settle(context);
    }

    static void closeAnimated(ClientGameTestContext context) {
        settle(context);
        moveTo(context, "close");
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, "close");
            clickLocal(screen, hit.x + hit.w / 2, hit.y + hit.h / 2, 0);
            require(client.screen == screen && (boolean) field(screen, "closing"), "Closing skipped its exit animation");
            require(number(screen, "screenProgress") == 1, "Closing snapped opacity");
        });
        context.waitFor(client -> {
            require(client.screen instanceof DonutScreen, "Closing skipped the fade transition");
            double progress = number(client.screen, "screenProgress");
            return progress > 0 && progress < 1;
        });
        context.waitForScreen(null);
    }

    static void clickWithFade(ClientGameTestContext context, String id, String progressField) {
        settle(context);
        reveal(context, id);
        moveTo(context, id);
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            Bounds hit = find(screen, id);
            clickLocal(screen, hit.x + hit.w / 2, hit.y + hit.h / 2, 0);
            require(number(screen, progressField) == 0, "Content skipped its fade: " + id);
        });
        settle(context);
    }

    static boolean visible(ClientGameTestContext context, String id) {
        return context.computeOnClient(client -> find(screen(client.screen), id) != null);
    }

    static boolean expanded(ClientGameTestContext context, int module) {
        return context.computeOnClient(client -> number(screen(client.screen), "selectedModule") == module);
    }

    static void assertModuleRows(ClientGameTestContext context, int... modules) {
        settle(context);
        scroll(context, 1000, false);
        context.waitTicks(8);
        List<Bounds> placements = new ArrayList<>();
        for (int module : modules) {
            reveal(context, "module" + module);
            Bounds tile = context.computeOnClient(client -> {
                DonutScreen screen = screen(client.screen);
                Bounds card = find(screen, "module" + module), toggle = find(screen, "enable" + module);
                require(card != null && card.h == 78 && toggle != null, "Tile controls are inaccessible: " + module);
                require(card.x >= number(screen, "CX") && toggle.x >= card.x + card.w
                                && toggle.x + toggle.w <= number(screen, "CX") + number(screen, "CW"),
                        "Tile selection overlaps its toggle or leaves the grid");
                require(number(screen, "CX") + number(screen, "CW") < number(screen, "detailX"),
                        "Module grid overlaps the settings inspector");
                return new Bounds(card.x, card.y + (int) number(screen, "scroll"), card.w + 48, card.h);
            });
            for (Bounds other : placements) require(!tile.overlaps(other), "Module tiles overlap");
            placements.add(tile);
        }
        assertContentClipped(context);
        scroll(context, 1000, false);
        context.waitTicks(8);
    }

    static void assertScrolled(ClientGameTestContext context) {
        context.runOnClient(client -> require(number(screen(client.screen), "detailScroll") > 0,
                "Lower settings did not scroll the inspector"));
    }

    static void assertGlassReleased(ClientGameTestContext context) {
        context.waitFor(client -> field(GlassPanel.class, "blurred") == null && field(GlassPanel.class, "blurredView") == null);
    }

    static void assertGlassReady(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var texture = (com.mojang.blaze3d.textures.GpuTexture) field(GlassPanel.class, "blurred");
            require(texture != null, "The ClickGUI did not prepare its frosted scene");
            require(texture.getWidth(0) == (client.getWindow().getWidth() + 1) / 2
                    && texture.getHeight(0) == (client.getWindow().getHeight() + 1) / 2,
                    "The frosted scene did not follow the framebuffer size");
        });
    }

    static void scrollTop(ClientGameTestContext context) {
        scroll(context, 1000, false);
        scroll(context, 1000, true);
        context.waitTicks(8);
    }

    static void assertContentClipped(ClientGameTestContext context) {
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            for (Object raw : hits(screen)) {
                String id = (String) field(raw, "id");
                boolean tile = id.matches("(?:module|enable)\\d+");
                if (!tile && !id.matches("m\\d+\\..*") && !id.matches("(?:keybind|favorite)\\d+")) continue;
                double top = number(screen, tile ? "TOP" : "detailTop");
                double bottom = number(screen, tile ? "BOTTOM" : "detailBottom");
                Bounds hit = bounds(raw);
                require(hit.y >= top && hit.y + hit.h <= bottom, "Offscreen control accepts input: " + id);
            }
        });
    }

    static void clickBelowContent(ClientGameTestContext context) {
        context.runOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            clickLocal(screen, number(screen, "detailX") + number(screen, "detailW") / 2,
                    number(screen, "detailBottom") + 1, 0);
        });
        context.waitTicks(2);
    }

    static void reveal(ClientGameTestContext context, String id) {
        if (id.matches("tab[01345]") && !visible(context, id)) {
            click(context, "workspaceModules");
        }
        var owner = OWNER.matcher(id);
        if (owner.matches()) {
            if (!visible(context, "tab5")) click(context, "workspaceModules");
            String digits = owner.group(1) != null ? owner.group(1) : owner.group(2) != null ? owner.group(2) : owner.group(3);
            int module = Integer.parseInt(digits);
            boolean selected = context.computeOnClient(client -> number(screen(client.screen), "selectedModule") == module
                    && number(screen(client.screen), "previewModule") == module);
            if (!selected) click(context, "module" + module);
        }
        boolean inspector = owner.matches() || id.equals("particles") || id.equals("hud")
                || id.equals("uiScale") || id.equals("animationSpeed") || id.equals("surfaceOpacity") || id.equals("liquidGlass");
        if (id.matches("m4\\.method[012]") && !visible(context, id)) click(context, "m4.method");
        if (id.matches("m10\\.method[012]") && !visible(context, id)) click(context, "m10.method");
        if (fullyVisible(context, id)) return;
        scroll(context, 1000, inspector);
        context.waitTicks(8);
        for (int i = 0; i < 60 && !fullyVisible(context, id); i++) {
            scroll(context, -2, inspector);
            context.waitTicks(4);
        }
        require(fullyVisible(context, id), "Cannot reveal control: " + id);
    }

    private static boolean fullyVisible(ClientGameTestContext context, String id) {
        return context.computeOnClient(client -> {
            Bounds hit = find(screen(client.screen), id);
            return hit != null && (!id.matches("module\\d+") || hit.h == 78);
        });
    }

    private static void scroll(ClientGameTestContext context, double amount, boolean inspector) {
        double[] pos = context.computeOnClient(client -> {
            DonutScreen screen = screen(client.screen);
            double x = number(screen, inspector ? "detailX" : "CX") + number(screen, inspector ? "detailW" : "CW") / 2;
            double y = (number(screen, inspector ? "detailTop" : "TOP") + number(screen, inspector ? "detailBottom" : "BOTTOM")) / 2;
            return windowPosition(screen, x, y, client.getWindow().getGuiScale());
        });
        context.getInput().setCursorPos(pos[0], pos[1]);
        context.waitTick();
        context.runOnClient(client -> screen(client.screen).mouseScrolled(
                pos[0] / client.getWindow().getGuiScale(), pos[1] / client.getWindow().getGuiScale(), 0, amount));
    }

    private static double[] windowPosition(DonutScreen screen, double x, double y, double guiScale) {
        return new double[] {(number(screen, "originX") + x * number(screen, "scale")) * guiScale,
                (number(screen, "originY") + y * number(screen, "scale")) * guiScale};
    }

    private static void clickLocal(DonutScreen screen, double x, double y, int button) {
        var event = new MouseButtonEvent(number(screen, "originX") + x * number(screen, "scale"),
                number(screen, "originY") + y * number(screen, "scale"), new MouseButtonInfo(button, 0));
        require(screen.mouseClicked(event, false), "GUI did not consume mouse input");
        screen.mouseReleased(event);
    }


    private static DonutScreen screen(Object value) {
        require(value instanceof DonutScreen, "Donut screen is not open"); return (DonutScreen) value;
    }
    private static Bounds find(DonutScreen screen, String id) {
        for (Object hit : hits(screen)) if (id.equals(field(hit, "id"))) return bounds(hit);
        return null;
    }
    private static Bounds bounds(Object hit) { return new Bounds(number(hit, "x"), number(hit, "y"), number(hit, "w"), number(hit, "h")); }
    private static List<?> hits(DonutScreen screen) { return (List<?>) field(screen, "hits"); }
    private static double number(Object owner, String name) { return ((Number) field(owner, name)).doubleValue(); }
    private static Object field(Object owner, String name) {
        try {
            Class<?> type = owner instanceof Class<?> ownerClass ? ownerClass : owner.getClass();
            Field field = type.getDeclaredField(name); field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError("Cannot inspect " + name, error); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private record Bounds(double x, double y, double w, double h) {
        boolean overlaps(Bounds other) { return x < other.x + other.w && x + w > other.x && y < other.y + other.h && y + h > other.y; }
    }
}
