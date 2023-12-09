package net.runelite.client.plugins.microbot.slayer.wildyslayer;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.slayer.SlayerPlugin;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Wildy Slayer",
        description = "Trains slayer in the wilderness",
        tags = {"slayer", "wilderness"},
        enabledByDefault = false
)

@PluginDependency(SlayerPlugin.class)
public class WildySlayerPlugin extends Plugin {
    @Inject
    private net.runelite.client.plugins.slayer.SlayerPluginService slayerPluginService;
    @Inject
    private WildySlayerConfig config;
    @Inject
    private WildySlayerScript wildySlayerScript;
    @Inject
    private WildySlayerStatusUpdater wildySlayerStatusUpdater;

    @Provides
    WildySlayerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WildySlayerConfig.class);
    }

    public long startTime;
    public long lastMeaningfulActonTime;

    @Override
    protected void startUp() throws AWTException {
        startTime = System.currentTimeMillis();
        lastMeaningfulActonTime = System.currentTimeMillis();
        wildySlayerScript.run();
        wildySlayerStatusUpdater.run();
    }

    protected void shutDown() {
        wildySlayerScript.shutdown();
        wildySlayerStatusUpdater.shutdown();
    }
}
