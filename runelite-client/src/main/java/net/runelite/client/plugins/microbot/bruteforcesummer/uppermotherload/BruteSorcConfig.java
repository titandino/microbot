package net.runelite.client.plugins.microbot.bruteforcesummer.uppermotherload;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("BruteSorc")
public interface BruteSorcConfig extends Config {

    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0
    )
    default String GUIDE() {
        return "Start outside summer garden";
    }
}
