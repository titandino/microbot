package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;

import java.util.Map;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.task;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Gear {
    private static final String[] equip = new String[]{"Rune gloves", "Climbing boots", "Helm of neitiznot", "Dragon scimitar", "Monk's robe top", "Monk's robe", "Cape of legends"};
    private static final Map<String, Integer> inventoryRequirements = Map.of(
            "Monkfish", 5,
            "Prayer potion(4)", 5
    );

    private static final Map<String, Integer> inventoryOptionals = Map.of(
            "Super strength(3)", 2,
            "Super attack(3)", 2
        );

    public static void gearUp() {
        debug("Banking with banker...");
        if (distTo(3138, 3629) > 5) {
            debug("Walking a little closer..");
            Microbot.getWalker().walkTo(new WorldPoint(3138, 3629, 0));
            sleepUntil(() -> distTo(Microbot.getClient().getLocalDestinationLocation()) < 5);
        }
        Rs2Npc.interact("Banker", "Bank");
        sleepUntil(Rs2Bank::isOpen, 15_000);
        if (!Rs2Bank.isOpen()) {
            debug("Failed to open bank, trying again");
            Microbot.getWalker().walkTo(new WorldPoint(3138, 3629, 0));
            return;
        }
        debug("Depositing items...");
        Rs2Bank.depositAll();
        Rs2Bank.depositEquipment();
        sleep(3000);
        debug("Withdrawing gear...");
        Rs2Bank.withdrawOne("Ring of Dueling");
        sleep(600, 1200);
        for (Map.Entry<String, Integer> entry : inventoryRequirements.entrySet()) {
            Rs2Bank.withdrawX(entry.getKey(), entry.getValue(), true);
            System.out.println("Withdrew " + entry.getKey());
            sleep(1300);
        }
        for (String item : equip) {
            Rs2Bank.withdrawOne(item, true);
            System.out.println("Withdrew " + item);
            sleep(600, 1200);
        }
        for (Map.Entry<String, Integer> entry : inventoryOptionals.entrySet()) {
            Rs2Bank.withdrawX(entry.getKey(), entry.getValue(), true);
            System.out.println("Withdrew " + entry.getKey());
            sleep(1300);
        }
        if (task().getHelmOverride() != null) {
            Rs2Bank.depositOne("Helm of neitiznot");
            sleep(600, 1200);
            Rs2Bank.withdrawOne(task().getHelmOverride());
            sleep(600, 1200);
        }
        Rs2Bank.closeBank();
        sleep(3000);
        debug("Equiping gear...");
        for (String item : equip) {
            Rs2Equipment.equipItem(item);
            sleep(600, 1200);
        }
        if (task().getHelmOverride() != null) {
            sleep(1000);
            Rs2Equipment.equipItem(task().getHelmOverride());
            sleep(1000);
            if (Rs2Equipment.hasEquipped(task().getHelmOverride())) {
                debug("Failed to equip helm override - Trying again...");
                Rs2Equipment.equipItemFast(task().getHelmOverride());
                sleep(1000);
            }
        }
    }

    private static int gearFails = 0;
    public static boolean gearedUp() {
        if (gearFails >= 5) {
            Microbot.getNotifier().notify("Failed to gear up 5 times! Sleeping forever...");
            sleep(999999999);
        }
        for (Map.Entry<String, Integer> entry : inventoryRequirements.entrySet()) {
            if (!Inventory.hasItemAmount(entry.getKey(), entry.getValue())) {
                debug("Missing " + entry.getKey() + "x " + entry.getValue());
                gearFails += 1;
                return false;
            }
        }
        for (String item : equip) {
            if (task().getHelmOverride() != null && item.equalsIgnoreCase("Helm of neitiznot")) continue;
            if (!Rs2Equipment.hasEquipped(item)) {
                debug("Missing " + item);
                gearFails += 1;
                return false;
            }
        }
        if (task().getHelmOverride() != null && !Rs2Equipment.hasEquipped(task().getHelmOverride())) {
            debug("Missing " + task().getHelmOverride());
            gearFails += 1;
            return false;
        }
        gearFails = 0;
        return true;
    }

}
