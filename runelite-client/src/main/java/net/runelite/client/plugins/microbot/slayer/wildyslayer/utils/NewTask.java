package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin.wildySlayerRunning;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Bank.openBankAndDepositAll;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.task;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.sleepWalk;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class NewTask {
    public static void getNewTaskGear() {
        openBankAndDepositAll();
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
            sleepWalk();
        }
        if (!wildySlayerRunning) return;
        debug("Getting new task...");
        Rs2Npc.interact("Krystilia", "Assignment");
        sleepUntil(() -> WildySlayerPlugin.Instance.wildySlayerScript.slayerPlugin.getAmount() != 0, 15_000);
        debug((WildySlayerPlugin.Instance.wildySlayerScript.slayerPlugin.getAmount() != 0 ? "Successfully got" : "Failed to get") + " a task!");
        debug("Deciding to skip or not..");
        while (wildySlayerRunning && task() != null && task().isSkip()) {
            skipAndGetNewTask();
        }
        debug("Teleing to Ferox...");
        Inventory.useItemSafe("Ring of Dueling"); // assumes your dueling rings are left-click rub
        sleepUntil(() -> Rs2Widget.findWidget("Ferox Enclave.") != null);
        Rs2Widget.clickWidget("Ferox Enclave.");
        sleep(3000);
    }

    private static void skipAndGetNewTask() {
        Rs2Npc.interact("Krystilia", "Rewards");

        sleepUntil(() -> Rs2Widget.findWidget("Tasks") != null, 15_000);
        if (Rs2Widget.findWidget("Tasks") == null) {
            debug("Tasks widget null! Idk what to do");
            return;
        }
        debug("Tasks widget appeared, clicking it..");
        Rs2Widget.clickWidget("Tasks", true);
        sleep(3000);


        debug("Waiting for Cancel Task to appear");
        // Click the widget to skip task
        sleepUntil(() -> Rs2Widget.findWidgetExact("Cancel Task") != null);
        if (Rs2Widget.findWidgetExact("Cancel Task") == null) {
            debug("Cancel Task widget null! Idk what to do");
            return;
        }
        debug("Cancel Task appeared, clicking it..");
        Rs2Widget.clickWidget("Cancel Task", true);
        sleep(3000);


        // Click the confirm widget
        sleepUntil(() -> Rs2Widget.findWidgetExact("Confirm") != null);
        if (Rs2Widget.findWidgetExact("Confirm") == null) {
            debug("Confirm widget null! Idk what to do");
            return;
        }
        debug("Confirm appeared, clicking it..");
        Rs2Widget.clickWidget("Confirm", true);
        sleep(3000);


        debug("Cancelled the task! Right? Moving in on 3 seconds...");
        sleep(3000);

        Microbot.getWalker().walkTo(Microbot.getClient().getLocalPlayer().getWorldLocation().dx(1));
        sleep(3000);
        debug("Walked away, interface should be closed");
    }


}
