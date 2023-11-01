package net.runelite.client.plugins.microbot.bruteforcesummer.uppermotherload;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Brute Sorc",
        description = "Brute forces the Sorc Garden",
        tags = {"sorc", "garden", "thieve"},
        enabledByDefault = false
)
public class BruteSorcPlugin extends Plugin {
    @Inject
    private BruteSorcConfig config;
    @Inject
    private OverlayManager overlayManager;

    @Inject
    private BruteSorcOverlay bruteSorcOverlay;
    @Inject
    private BruteSorcScript bruteSorcScript;

    @Provides
    BruteSorcConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BruteSorcConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        overlayManager.add(bruteSorcOverlay);
        bruteSorcScript.run();
    }

    protected void shutDown() {
        bruteSorcScript.shutdown();
        overlayManager.remove(bruteSorcOverlay);
    }
}
