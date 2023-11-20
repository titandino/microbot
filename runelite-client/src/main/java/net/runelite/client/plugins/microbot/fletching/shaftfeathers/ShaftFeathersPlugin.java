package net.runelite.client.plugins.microbot.fletching.shaftfeathers;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Shaft Feathers",
        description = "Attaches feathers to shafts",
        tags = {"fletch", "arrow", "shaft", "feather"},
        enabledByDefault = false
)
public class ShaftFeathersPlugin extends Plugin {
    @Inject
    private ShaftFeathersConfig config;
    @Inject
    private ShaftFeathersScript shaftFeathersScript;

    @Provides
    ShaftFeathersConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(ShaftFeathersConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        shaftFeathersScript.run();
    }

    protected void shutDown() {
        shaftFeathersScript.shutdown();
    }
}
