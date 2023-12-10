package net.runelite.client.plugins.microbot.slayer.wildyslayer;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.parallel.WildySlayerStatusUpdater;
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
    public WildySlayerConfig config;
    @Inject
    public WildySlayerScript wildySlayerScript;
    @Inject
    public WildySlayerStatusUpdater wildySlayerStatusUpdater;

    @Provides
    WildySlayerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(WildySlayerConfig.class);
    }

    public static boolean wildySlayerRunning = false;

    public long startTime;
    public long lastMeaningfulActonTime;

    @Override
    protected void startUp() throws AWTException {
        startTime = System.currentTimeMillis();
        lastMeaningfulActonTime = System.currentTimeMillis();
        wildySlayerRunning = true;
        Instance = this;
        wildySlayerScript.run();
        wildySlayerStatusUpdater.run();
    }

    protected void shutDown() {
        Instance = null;
        wildySlayerRunning = false;
        wildySlayerScript.shutdown();
        wildySlayerStatusUpdater.shutdown();
    }

    public static WildySlayerPlugin Instance;
}
