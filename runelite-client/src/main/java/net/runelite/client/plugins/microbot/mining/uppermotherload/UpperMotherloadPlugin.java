package net.runelite.client.plugins.microbot.mining.uppermotherload;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Upper Motherload",
        description = "Mines on the top floor of the Motherload mine",
        tags = {"paydirt", "mine", "motherload"},
        enabledByDefault = false
)
public class UpperMotherloadPlugin extends Plugin {
    @Inject
    private UpperMotherloadConfig config;
    @Inject
    private OverlayManager overlayManager;

    @Inject
    private UpperMotherloadScript upperMotherloadScript;

    @Provides
    UpperMotherloadConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(UpperMotherloadConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        upperMotherloadScript.run();
    }

    protected void shutDown() {
        upperMotherloadScript.shutdown();
    }
}
