package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.sleepWalk;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.inventory.Inventory.findItem;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Bank {
    public static void openBankAndDepositAll() {
        debug("Banking with banker...");
        if (distTo(3138, 3629) > 5) {
            debug("Walking a little closer..");
            Microbot.getWalker().walkTo(new WorldPoint(3138, 3629, 0));
            sleepWalk();
        }
        Rs2Npc.interact("Banker", "Bank");
        sleepUntil(Rs2Bank::isOpen, 15_000);
        if (!Rs2Bank.isOpen()) {
            debug("Failed to open bank, trying again");
            Microbot.getWalker().walkTo(new WorldPoint(3138, 3629, 0));
            sleepWalk();
            Rs2Npc.interact("Banker", "Bank");
            sleepUntil(Rs2Bank::isOpen, 15_000);
        }
        if (!Rs2Bank.isOpen()) {
            debug("Failed to open bank");
            return;
        }
        debug("Depositing items...");
        if (Inventory.hasLootingBagWithView()) {
            debug("Depositing Looting bag contents");
            Widget item = findItem("Looting bag", true);
            if (item == null) {
                debug("Looting bag wasn't found!?");
            }
            Microbot.getMouse().click(item.getBounds().getCenterX(), item.getBounds().getCenterY());
            debug("Clicked looting bag...");
            sleep(3000);
            Rs2Widget.clickWidget(983_048);
            debug("Clicked widget");
            debug("Deposited Looting bag contents");
        } else {
            debug("Looting bag does not have view!");
        }
        Rs2Bank.depositAll();
        Rs2Bank.depositEquipment();
        debug("Deposited everything");
    }

}
