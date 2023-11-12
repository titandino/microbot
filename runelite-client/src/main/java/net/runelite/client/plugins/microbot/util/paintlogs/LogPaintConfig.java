package net.runelite.client.plugins.microbot.util.paintlogs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("LogPaint")
public interface LogPaintConfig extends Config {

    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0
    )
    default String GUIDE() {
        return "Activate this with other Red scripts for nice logging";
    }
}
