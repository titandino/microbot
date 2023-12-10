package net.runelite.client.plugins.microbot.slayer.wildyslayer.parallel;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerConfig;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin;
import net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript;
import net.runelite.client.plugins.slayer.SlayerPlugin;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;
import static net.runelite.client.plugins.microbot.util.time.RuntimeCalc.prettyRunTime;

@Slf4j
public class WildySlayerStatusUpdater extends Script {
    private final WildySlayerConfig config;
    private final WildySlayerPlugin plugin;
    private final SlayerPlugin slayerPlugin;

    @Inject
    private WildySlayerStatusUpdater(WildySlayerConfig config, WildySlayerPlugin plugin, SlayerPlugin slayerPlugin) {
        this.config = config;
        this.plugin = plugin;
        this.slayerPlugin = slayerPlugin;
    }

    public boolean run() {
        debug("Got config options " + config.GUIDE());
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            PaintLogsScript.status = "Task: " + slayerPlugin.getTaskName() + " - Remaining: " + slayerPlugin.getAmount() + " - Runtime: " + prettyRunTime(plugin.startTime);
        }, 0, 500, TimeUnit.MILLISECONDS);
        return true;
    }
}