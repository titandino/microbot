package net.runelite.client.plugins.microbot.slayer.wildyslayer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("WildySlayerConfig")
public interface WildySlayerConfig extends Config {

    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0
    )
    default String GUIDE() {
        StringBuilder sb = new StringBuilder("You must enable the RuneLite Slayer plugin and Paint Logs. This is an opinionated Wildy Slayer bot by Red Bracket. You will need to customize the code to fit your needs.");
        sb.append("\nYour dueling rings must all be set to left-click rub");
        return sb.toString();
    }
}
