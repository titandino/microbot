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
        return "You must enable the RuneLite Slayer plugin and Paint Logs. This is an opinionated Wildy Slayer bot by Red Bracket. You will need to customize the code to fit your needs." +
                "\nYour dueling rings must all be set to left-click rub" +
                "\nYour Ardougne cloak must be set to left-click Monastery Teleport" +
                "\nYour fairy ring left-click must be set to DKR" +
                "\nYour Looting Bag left-click must be Open, and in a bank, the left-click must be View";
    }
}
