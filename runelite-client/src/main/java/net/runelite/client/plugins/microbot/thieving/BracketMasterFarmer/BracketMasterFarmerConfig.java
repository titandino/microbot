package net.runelite.client.plugins.microbot.thieving.BracketMasterFarmer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("BracketMasterFarmer")
public interface BracketMasterFarmerConfig extends Config {

    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0
    )
    default String GUIDE() {
        return "Start near a master farmer and a bank";
    }
}
