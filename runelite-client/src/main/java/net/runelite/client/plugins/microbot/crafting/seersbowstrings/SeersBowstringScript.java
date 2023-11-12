package net.runelite.client.plugins.microbot.crafting.seersbowstrings;

import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.models.RS2Item;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@Slf4j
public class SeersBowstringScript extends Script {

    public boolean run() {
        Microbot.enableAutoRunOn = true;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            try {
                if (random(0, 100) == 0) {
                    int sleepTime = random(50_000, 100_000);
                    debug("Anti-ban: Sleeping " + sleepTime + " ms");
                    sleep(sleepTime);
                    debug("Anti-ban: Done sleeping " + sleepTime + "ms");
                }

                if (Inventory.hasItem("flax") && getSpinningWheel() != null)
                    spinFlax();
                else if (Inventory.hasItem("flax"))
                    climbUpLadder();
                else if (getSpinningWheel() != null)
                    climbDownLadder();
                else
                    bankEverything();


            sleep(random(6000, 9000));
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private void climbDownLadder() {
    }

    private Object getSpinningWheel() {
        return null;
    }

    private void climbUpLadder() {
    }

    private void spinFlax() {
    }

    private void bankEverything() {
        int bankMethod = random(0, 4);
        debug("Opening bank if it's not open - Using bank method " + bankMethod);
        if (bankMethod == 0) {
            if (!Rs2DepositBox.isOpen() && !Rs2DepositBox.openDepositBox()) {
                debug("Failed to open deposit box");
                return;
            }
            Rs2DepositBox.depositAll();
            return;
        }

        // use bank
        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            debug("Failed to open bank");
            return;
        }
        sleepUntil(Rs2Bank::isOpen);
        if (bankMethod != 1) {
            Rs2Bank.depositAll();
        } else {
            Set<Integer> deposited = new HashSet<>();

            // Get all inventory items and shuffle the list
            List<Widget> inventoryItems = new ArrayList<>(Arrays.asList(Inventory.getInventoryItems()));
            Collections.shuffle(inventoryItems);

            for (Widget item : inventoryItems) {
                if (deposited.contains(item.getItemId())) continue;
                Rs2Bank.depositAll(item.getItemId());
                deposited.add(item.getItemId());
                sleep(100, 300);
            }
        }
        debug("We done banking via method " + bankMethod);
    }
}