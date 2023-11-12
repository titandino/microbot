package net.runelite.client.plugins.microbot.playerassist.combat;

import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.playerassist.PlayerAssistConfig;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;

import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class FoodScript extends Script {

    public boolean run(PlayerAssistConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!config.toggleFood()) return;
                Widget[] foods = Microbot.getClientThread().runOnClientThread(() -> Inventory.getInventoryFood());
                if (foods == null || foods.length == 0) {
                    Microbot.getNotifier().notify("No food left");
                    return;
                }
                for (Widget food : foods) {
                    debug("Eating " + food.getName());
                    Microbot.getMouse().click(food.getBounds());
                    sleep(1200, 2000);
                    break;
                }
            } catch(Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 6000, TimeUnit.MILLISECONDS);
        return true;
    }

}
