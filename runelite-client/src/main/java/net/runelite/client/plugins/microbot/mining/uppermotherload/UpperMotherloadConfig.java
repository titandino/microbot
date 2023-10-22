package net.runelite.client.plugins.microbot.mining.uppermotherload;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("UpperMotherload")
public interface UpperMotherloadConfig extends Config {

    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0
    )
    default String GUIDE() {
        return "Start near the bank chest in Motherload mine, with a pickaxe equipt. The top floor must be unlocked. Code shamelessly stolen from Chsami.";
    }
}
