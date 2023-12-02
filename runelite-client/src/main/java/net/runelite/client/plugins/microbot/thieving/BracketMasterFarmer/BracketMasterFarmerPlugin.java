package net.runelite.client.plugins.microbot.thieving.BracketMasterFarmer;

import com.google.inject.Provides;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.RedBracket + "Master Farmer",
        description = "Theives master farmers and eats food",
        tags = {"thieve", "theive", "master", "farmer"},
        enabledByDefault = false
)
public class BracketMasterFarmerPlugin extends Plugin {
    @Inject
    private BracketMasterFarmerConfig config;
    @Inject
    private BracketMasterFarmerScript bracketMasterFarmerScript;

    @Provides
    BracketMasterFarmerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(BracketMasterFarmerConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        BracketMasterFarmerScript.failure = 0;
        BracketMasterFarmerScript.success = 0;
        bracketMasterFarmerScript.run();
    }

    protected void shutDown() {
        bracketMasterFarmerScript.shutdown();
    }

    @Subscribe
    public void onChatMessage(ChatMessage message) {
        if (message.getMessage().equals("You fail to pick the Master Farmer's pocket.")) {
            BracketMasterFarmerScript.failure += 1;
        }
        if (message.getMessage().equals("You pick the Master Farmer's pocket.")) {
            BracketMasterFarmerScript.success += 1;
        }

        PaintLogsScript.status = "Success: " + BracketMasterFarmerScript.success + " - Failures: " + BracketMasterFarmerScript.failure;
    }
}
