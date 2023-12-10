package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;

import java.util.Map;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Gear {
    private static final String[] equip = new String[]{"Rune gloves", "Climbing boots", "Helm of neitiznot", "Dragon scimitar", "Monk's robe top", "Monk's robe", "Cape of legends"};
    private static final Map<String, Integer> inventoryRequirements = Map.of(
            "Monkfish", 5,
            "Prayer potion(4)", 5
        );
    public static void gearUp() {
        debug("Banking with banker...");
        Rs2Npc.interact("Banker", "Bank");
        sleepUntil(Rs2Bank::isOpen, 15_000);
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
            sleep(600, 1200);
        }
        for (String item : equip) {
            Rs2Bank.withdrawOne(item, true);
            System.out.println("Withdrew " + item);
            sleep(600, 1200);
        }
        Rs2Bank.closeBank();
        sleep(3000);
        debug("Equiping gear...");
        for (String item : equip) {
            Rs2Equipment.equipItem(item);
            sleep(600, 1200);
        }
    }

    public static boolean gearedUp() {
        for (Map.Entry<String, Integer> entry : inventoryRequirements.entrySet()) {
            if (!Inventory.hasItemAmount(entry.getKey(), entry.getValue())) return false;
        }
        for (String item : equip) {
            if (!Rs2Equipment.hasEquipped(item)) return false;
        }
        return true;
    }

}
