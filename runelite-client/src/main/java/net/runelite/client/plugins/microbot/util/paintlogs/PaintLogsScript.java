package net.runelite.client.plugins.microbot.util.paintlogs;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PaintLogsScript extends Script {

    public static String status = "";
    public static ArrayList<String> debugMessages = new ArrayList<>();

    public static void debug(String msg) {
        log.info(msg);
        while (debugMessages.size() >= 5) debugMessages.remove(0);
        debugMessages.add(msg);
    }

    public boolean run() {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

}