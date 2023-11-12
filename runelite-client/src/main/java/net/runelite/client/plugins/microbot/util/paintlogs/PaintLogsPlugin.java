package net.runelite.client.plugins.microbot.util.paintlogs;

import com.google.inject.Provides;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;
import java.util.ArrayList;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Log Paint",
        description = "Provides nice logging paint for Red scripts",
        tags = {"log", "paint", "red"},
        enabledByDefault = false
)
public class PaintLogsPlugin extends Plugin {
    @Inject
    private LogPaintConfig config;
    @Inject
    private OverlayManager overlayManager;

    @Inject
    private LogPaintOverlay logPaintOverlay;
    @Inject
    private PaintLogsScript paintLogsScript;

    @Provides
    LogPaintConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(LogPaintConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        overlayManager.add(logPaintOverlay);
        paintLogsScript.run();
    }

    protected void shutDown() {
        paintLogsScript.shutdown();
        overlayManager.remove(logPaintOverlay);
        PaintLogsScript.debugMessages = new ArrayList<>();
    }
}
