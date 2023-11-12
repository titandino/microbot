package net.runelite.client.plugins.microbot.crafting.catherbybowstrings;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("GuildMagics")
public interface GuildMagicsConfig extends Config {

    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0
    )
    default String GUIDE() {
        return "Start in the Woodcutting Guild with an axe equipt";
    }
}
