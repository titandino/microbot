package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin.wildySlayerRunning;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.task;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class NewTask {
    public static void getNewTaskGear() {
        debug("Banking with banker...");
        if (distTo(3138, 3629) > 5) {
            debug("Walking a little closer..");
            Microbot.getWalker().walkTo(new WorldPoint(3138, 3629, 0));
            sleep(4000);
        }
        Rs2Npc.interact("Banker", "Bank");
        sleepUntil(Rs2Bank::isOpen, 15_000);
        if (!Rs2Bank.isOpen()) {
            debug("Failed to open bank, trying again");
            Microbot.getWalker().walkTo(new WorldPoint(3138, 3629, 0));
            sleep(4000);
            return;
        }
        debug("Depositing items...");
        Rs2Bank.depositAll();
        Rs2Bank.depositEquipment();
        sleep(3000);
        debug("Withdrawing task grab gear...");
        Rs2Bank.withdrawOne("Ardougne cloak");
        sleep(600, 1200);
        Rs2Bank.withdrawOne("Dramen staff");
        sleep(600, 1200);
        Rs2Bank.withdrawOne("Ring of Dueling", false);
        sleep(600, 1200);
        Rs2Bank.closeBank();
        debug("Equiping staff...");
        Rs2Equipment.equipItemFast("Dramen staff");
        sleep(3000);
        if (!Rs2Equipment.hasEquipped("Dramen staff")) {
            debug("Failed to equip staff.. trying again");
            Rs2Equipment.equipItem("Dramen staff");
        }
    }

    public static void getNewTask() {
        debug("Getting a new task...");
        getNewTaskGear();
        if (!wildySlayerRunning) return;
        debug("Using Ardougne cloak");
        Inventory.useItemSafe("Ardougne cloak"); // assumes your Ardougne cloaks are left-click Monastery Teleport
        sleep(5000);
        if (distTo(2606, 3223) > 10) {
            debug("Failed to teleport to Ardougne! Trying again..");
            return;
        }
        debug("Walking to fairy ring..");
        while (wildySlayerRunning && distTo(2656, 3232) > 5) {
            Microbot.getWalker().walkTo(new WorldPoint(2656, 3232, 0));
            sleep(800, 1600);
        }
        if (!wildySlayerRunning) return;
        debug("Interacting fairy ring..");
        Rs2GameObject.interact("Fairy ring", "Last-destination (DKR)");
        sleep(8000);
        debug("Walking to Krystilia");
        Microbot.getWalker().walkTo(Microbot.getClient().getLocalPlayer().getWorldLocation().dy(5));
        sleep(2000);
        while (wildySlayerRunning && distTo(3109, 3514) > 5) {
            Microbot.getWalker().walkTo(new WorldPoint(3109, 3514, 0));
            sleep(2000, 4800);
        }
        if (!wildySlayerRunning) return;
        debug("Getting new task...");
        Rs2Npc.interact("Krystilia", "Assignment");
        sleepUntil(() -> WildySlayerPlugin.Instance.wildySlayerScript.slayerPlugin.getAmount() != 0, 15_000);
        debug((WildySlayerPlugin.Instance.wildySlayerScript.slayerPlugin.getAmount() != 0 ? "Successfully got" : "Failed to get") + " a new task!");
        while (wildySlayerRunning && task().isSkip()) {
            skipAndGetNewTask();
        }
        debug("Teleing to Ferox...");
        Inventory.useItemSafe("Ring of Dueling"); // assumes your dueling rings are left-click rub
        sleepUntil(() -> Rs2Widget.findWidget("Ferox Enclave.") != null);
        Rs2Widget.clickWidget("Ferox Enclave.");
        sleep(3000);
    }

    private static void skipAndGetNewTask() {
        debug("TODO - skip and get a new task");
        Rs2Npc.interact("Krystilia", "Rewards");

        sleepUntil(() -> Rs2Widget.findWidgetExact("Tasks") != null);
        Rs2Widget.clickWidget("Skip Task", true);

        // Click the widget to skip task
        sleepUntil(() -> Rs2Widget.findWidgetExact("Cancel Task") != null);
        Rs2Widget.clickWidget("Skip Task", true);

        // Click the confirm widget
        sleepUntil(() -> Rs2Widget.findWidgetExact("Confirm") != null);
        Rs2Widget.clickWidget("Confirm", true);

        // Close the interface
        debug("Sent esc key to close slayer interface, sleeping 900ms");
        VirtualKeyboard.keyPress(KeyEvent.VK_ESCAPE);
        sleep(900);
    }


}
