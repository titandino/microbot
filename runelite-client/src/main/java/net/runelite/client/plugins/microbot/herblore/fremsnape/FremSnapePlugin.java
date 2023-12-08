package net.runelite.client.plugins.microbot.herblore.fremsnape;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Frem Snape Grass",
        description = "Picks up snape grass in Relleka",
        tags = {"snape", "grass", "herblore", "prayer"},
        enabledByDefault = false
)
public class FremSnapePlugin extends Plugin {
    @Inject
    private FremSnapeConfig config;
    @Inject
    private FremSnapeScript fremSnapeScript;

    @Provides
    FremSnapeConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(FremSnapeConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        fremSnapeScript.snapeLooted = 0;
        fremSnapeScript.run();
    }

    protected void shutDown() {
        fremSnapeScript.shutdown();
    }
}
