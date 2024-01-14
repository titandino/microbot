package net.runelite.client.plugins.microbot.museum;

import com.google.inject.Provides;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript;

import javax.inject.Inject;
import java.awt.*;

import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Varrock Museum",
        description = "Gets lamps in the Varrock Museum",
        tags = {"lamp", "varrock", "museum"},
        enabledByDefault = false
)
public class MuseumPlugin extends Plugin {
    @Inject
    private MuseumConfig config;
    @Inject
    private MuseumScript museumScript;

    @Provides
    MuseumConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MuseumConfig.class);
    }


    @Override
    protected void startUp() throws AWTException {
        Microbot.pauseAllScripts = false;
        PaintLogsScript.status = "Starting...";
        museumScript.startTime = System.currentTimeMillis();
        museumScript.lampsRubbed = 0;
        museumScript.timesHopped = 0;
        museumScript.rewardsClaimed = 0;
        museumScript.run();
    }

    protected void shutDown() {
        museumScript.shutdown();
    }

    @Subscribe
    public void onChatMessage(ChatMessage message) {
        if (message.getMessage().equals("I can't reach that!")) {
            debug("Script got stuck, checking if we're in bounds...");
            if (Microbot.getClient().getLocalPlayer().getWorldLocation().isInsideConvexHull(new WorldPoint[]{
                    new WorldPoint(3267, 3446, 0),
                    new WorldPoint(3267, 3442, 0),
                    new WorldPoint(3257, 3446, 0),
                    new WorldPoint(3257, 3442, 0)})) {
                debug("We're in bounds, not doing anything about it");
            } else {
                Microbot.getNotifier().notify("Script got stuck out of bounds, stopping script!");
                Microbot.pauseAllScripts = true;
            }
        }
        if (message.getMessage().equals("You place the find into the crate with all the others and pick up your reward from the crate.")) {
            museumScript.rewardsClaimed += 1;
        }
    }
}
