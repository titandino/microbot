package net.runelite.client.plugins.microbot.statemachineexample;

import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.Default + "State Machine Example",
        description = "Test plugin demonstrating the StateMachineScript framework with observable state transitions",
        tags = {"statemachine", "microbot", "test", "example", "debug"},
        enabledByDefault = false
)
@Slf4j
public class StateMachineExamplePlugin extends Plugin {

    @Inject
    private StateMachineExampleScript script;

    @Inject
    private StateMachineExampleConfig config;

    @Provides
    StateMachineExampleConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(StateMachineExampleConfig.class);
    }

    @Override
    protected void startUp() throws AWTException {
        log.info("State Machine Example plugin started");
        script.run(config);
    }

    @Override
    protected void shutDown() {
        log.info("State Machine Example plugin stopped");
        script.shutdown();
    }
}
