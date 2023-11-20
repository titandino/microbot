package net.runelite.client.plugins.microbot.fletching.shaftfeathers;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("ShaftFeathers")
public interface ShaftFeathersConfig extends Config {

    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0
    )
    default String GUIDE() {
        return "Start with arrow shafts and feathers in inventory";
    }
}
