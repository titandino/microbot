package net.runelite.client.plugins.microbot.crafting.seersbowstrings;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Seers Bowstrings",
        description = "Spins bowstrings in Seers village",
        tags = {"crafting", "flax", "bow", "bowstring"},
        enabledByDefault = false
)
public class SeersBowstringsPlugin extends Plugin {
    @Inject
    private SeersBowstringsConfig config;
    @Inject
    private SeersBowstringScript seersBowstringScript;

    @Provides
    SeersBowstringsConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(SeersBowstringsConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        seersBowstringScript.run();
    }

    protected void shutDown() {
        seersBowstringScript.shutdown();
    }
}
