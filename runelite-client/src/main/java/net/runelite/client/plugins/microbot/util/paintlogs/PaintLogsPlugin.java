package net.runelite.client.plugins.microbot.util.paintlogs;

import net.runelite.api.events.ChatMessage;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
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
    private OverlayManager overlayManager;
    @Inject
    private LogPaintOverlay logPaintOverlay;
    @Inject
    private PaintLogsScript paintLogsScript;

    @Override
    protected void startUp() throws AWTException {
        overlayManager.add(logPaintOverlay);
        paintLogsScript.run();
    }

    @Override
    protected void shutDown() {
        paintLogsScript.shutdown();
        overlayManager.remove(logPaintOverlay);
        PaintLogsScript.debugMessages = new ArrayList<>();
    }

    @Subscribe
    public void onChatMessage(ChatMessage chatMessage) {
        if (chatMessage.getMessage().toLowerCase().contains("bot")) {
            Microbot.getNotifier().notify("Message contains bot");
        }
        switch (chatMessage.getType()) {
            case MODPRIVATECHAT:
            case PRIVATECHAT:
            case MODCHAT:
                Microbot.getNotifier().notify("Bot got DM: " + chatMessage.getMessage());
                break;
            default:
                System.out.println("Got " + chatMessage.getType() + " message: " + chatMessage.getMessage());
        }
    }
}
