package net.runelite.client.plugins.microbot.crafting.scripts;

import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.crafting.CraftingConfig;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard;

import java.awt.event.KeyEvent;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;


public class GemsScript extends Script {

    public static double version = 1.0;

    public boolean run(CraftingConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            try {
                if (!Microbot.hasLevel(config.gemType().getLevelRequired(), Skill.CRAFTING)) {
                    Microbot.showMessage("Crafting level to low to craft " + config.gemType().getName());
                    shutdown();
                    return;
                }
                if (random(0, 250) == 0) {
                    debug("AFK antiban");
                    sleep(random(1000,100000));
                }
                final String uncutGemName = "uncut " + config.gemType().getName();
                if (!Inventory.hasItem("uncut " + config.gemType().getName()) || !Inventory.hasItem("chisel")) {
                    debug("Banking..");
                    Rs2Bank.openBank();
                    sleepUntil(() -> Rs2Bank.isOpen());
                    if (Rs2Bank.isOpen()) {
                        Rs2Bank.depositAll("crushed gem");
                        Rs2Bank.depositAll(config.gemType().getName());
                        if(Rs2Bank.hasItem(uncutGemName)) {
                            debug("Withdrawing gems..");
                            Rs2Bank.withdrawItemAll(uncutGemName);
                        } else{
                            Microbot.showMessage("Run out of Materials");
                            shutdown();
                        }
                        sleepUntil(() -> Inventory.contains("uncut " + config.gemType().getName()));
                        Rs2Bank.closeBank();
                        sleepUntil(() -> !Rs2Bank.isOpen());
                    }
                } else {
                    if (random(0, 4) == 0) {
                        debug("Using chisel on gems..");
                        Inventory.useItem("chisel");
                        Inventory.useItem(uncutGemName);
                    } else {
                        debug("Using gems on chisel..");
                        Inventory.useItem(uncutGemName);
                        Inventory.useItem("chisel");
                    }
                    sleep(random(600, 1200));
                    VirtualKeyboard.keyPress(KeyEvent.VK_SPACE);
                    sleep(4000);
                    debug("Sleeping until we're done crafting..");
                    sleepUntil(() -> !Microbot.isGainingExp || !Inventory.hasItem(uncutGemName), 30000);
                    if (random(0, 2) != 0) {
                        debug("Sleeping a bit after full inven antiban..");
                        sleep(random(1000,5000));
                    }
                }

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}
