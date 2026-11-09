package dev.donut.client.namehider;

import dev.donut.client.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Applies the module only to text being displayed; scoreboard data remains authoritative. */
public final class NameHiderController {
    private static NameHiderController instance;
    private final ClientConfig config;

    public NameHiderController(ClientConfig config) {
        this.config = config;
        instance = this;
    }

    public static boolean isEnabled() {
        return instance != null && instance.config.nameHider.enabled;
    }

    public static Component sanitize(Component original) {
        if (!isEnabled() || blurScoreboard()) return original;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return original;
        return SidebarTextSanitizer.replaceUsername(original, client.player.getScoreboardName());
    }

    public static boolean blurScoreboard() {
        return isEnabled() && instance.config.nameHider.blurScoreboard;
    }
}
