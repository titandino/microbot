package net.runelite.client.plugins.microbot.statemachineexample;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("statemachineexample")
public interface StateMachineExampleConfig extends Config {

    @ConfigItem(
            keyName = "tickDelay",
            name = "Tick Delay (ms)",
            description = "Delay between state machine ticks in milliseconds",
            position = 0
    )
    default int tickDelay() {
        return 600;
    }

    @ConfigItem(
            keyName = "idleDuration",
            name = "Idle Duration (ms)",
            description = "How long to idle between cycles",
            position = 1
    )
    default int idleDuration() {
        return 3000;
    }
}
