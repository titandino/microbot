package net.runelite.client.plugins.microbot.thieving.BracketMasterFarmer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;

import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@Slf4j
public class BracketMasterFarmerScript extends Script {

    public static int failure;
    public static int success;

    public boolean run() {
        Microbot.enableAutoRunOn = true;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            try {
                if (random(0, 100) == 0) {
                    int sleepTime = random(20_000, 60_000);
                    debug("Anti-ban: Sleeping " + sleepTime + " ms");
                    sleep(sleepTime);
                    debug("Anti-ban: Done sleeping " + sleepTime + "ms");
                }

                if (Inventory.isFull() || Inventory.getInventoryFood().length == 0) {
                    bank();
                    return;
                }
                if (Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) < 50) {
                    debug("Eating...");
                    Inventory.eatItem("Monkfish");
                    sleep(random(600, 900));
                    return;
                }
                thieveFarmer();

            sleep(random(100, 300));
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private void thieveFarmer() {
        debug("Thieving farmer..");
        Rs2Npc.interact("Master farmer", "Pickpocket");
        sleep(random(600, 800));
    }

    private void bank() {
        debug("Banking..");
        sleep(2000);
        // use bank
        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            debug("Failed to open bank");
            return;
        }
        sleepUntil(Rs2Bank::isOpen);
        sleep(random(600, 900));
        Rs2Bank.depositAll();
        sleep(random(600, 900));
        Rs2Bank.withdrawXExact("Monkfish", 5);
        sleep(random(600, 900));
        Rs2Bank.closeBank();
        sleep(random(600, 900));
    }
}