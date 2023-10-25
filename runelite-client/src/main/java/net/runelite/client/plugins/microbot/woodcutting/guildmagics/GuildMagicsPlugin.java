package net.runelite.client.plugins.microbot.woodcutting.guildmagics;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Guild Magics",
        description = "Cuts Magic Trees in the Woodcutting Guild",
        tags = {"woodcutting", "magic"},
        enabledByDefault = false
)
public class GuildMagicsPlugin extends Plugin {
    @Inject
    private GuildMagicsConfig config;
    @Inject
    private OverlayManager overlayManager;

    @Inject
    private GuildMagicsOverlay guildMagicsOverlay;
    @Inject
    private GuildMagicsScript guildMagicsScript;

    @Provides
    GuildMagicsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(GuildMagicsConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        overlayManager.add(guildMagicsOverlay);
        guildMagicsScript.run();
    }

    protected void shutDown() {
        guildMagicsScript.shutdown();
        overlayManager.remove(guildMagicsOverlay);
    }
}
