package net.runelite.client.plugins.microbot.fletching.shaftfeathers;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@Slf4j
public class ShaftFeathersScript extends Script {
    public boolean run() {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;

            if (random(0, 4) == 0) {
                debug("Using Feathers on Arrow Shafts...");
                Inventory.useItemOnItem("Feather", "Arrow shaft");
            } else {
                debug("Using Arrow Shafts on Feathers...");
                Inventory.useItemOnItem("Arrow shaft", "Feather");
            }
            sleepUntil(() -> Rs2Widget.getWidget(17694733) != null);
            sleep(random(0, 75));
            VirtualKeyboard.typeString("1");
            int sleeptime = random(8000, 12000);
            debug("Starting sleep for " + sleeptime + "ms...");
            sleep(sleeptime);

            if (random(0, 200) == 0) {
                sleeptime = random(80000, 120000);
                debug("Antiban: Sleeping for " + sleeptime + "ms...");
                sleep(sleeptime);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

}