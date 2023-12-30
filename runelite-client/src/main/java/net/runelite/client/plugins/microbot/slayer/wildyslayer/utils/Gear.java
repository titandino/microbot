package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;

import java.util.Map;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Bank.openBankAndDepositAll;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.task;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Gear {

    private static String[] getEquip() {
        switch (task()) {
            case DUST_DEVILS:
                return new String[]{"Rune gloves", "Climbing boots", "Facemask", "Dragon scimitar", "Monk's robe top", "Monk's robe", "Cape of legends"};
            case GREEN_DRAGONS:
                return new String[]{"Rune gloves", "Climbing boots", "Helm of neitiznot", "Dragon scimitar", "Monk's robe top", "Monk's robe", "Cape of legends", "Anti-dragon shield"};
            case ICE_GIANTS:
                return new String[]{"Rune gloves", "Climbing boots", "Barrelchest anchor", "Rune platebody", "Rune plateskirt", "Cape of legends"};
            default:
                return new String[]{"Rune gloves", "Climbing boots", "Helm of neitiznot", "Dragon scimitar", "Monk's robe top", "Monk's robe", "Cape of legends"};
        }
    }

    private static Map<String, Integer> getInventoryRequirements() {
        switch (task()) {
            case GREEN_DRAGONS:
                return Map.of(
                        "Monkfish", 10,
                        "Prayer potion(4)", 3
                );
            case BEARS:
            case SPIDER:
            case ZOMBIES:
                return Map.of(
                        "Monkfish", 10
                );
            case ICE_GIANTS:
                return Map.of(
                        "Monkfish", 20
                );
            default:
                return Map.of(
                        "Monkfish", 5,
                        "Prayer potion(4)", 5
                );
        }
    }

    private static Map<String, Integer> getInventoryOptionals() {
        switch (task()) {
            case GREEN_DRAGONS:
                return Map.of(
                    "Super strength(3)", 1,
                    "Super attack(3)", 1,
                    "Looting bag", 1
            );
            default:
                return Map.of(
                        "Super strength(3)", 2,
                        "Super attack(3)", 2
                );
        }
    }

    public static void gearUp() {
        openBankAndDepositAll();
        sleep(3000);
        debug("Withdrawing gear...");
        Rs2Bank.withdrawOne("Ring of Dueling");
        sleep(600, 1200);
        for (Map.Entry<String, Integer> entry : getInventoryRequirements().entrySet()) {
            Rs2Bank.withdrawX(entry.getKey(), entry.getValue(), true);
            System.out.println("Withdrew " + entry.getKey());
            sleep(1300);
        }
        for (String item : getEquip()) {
            Rs2Bank.withdrawOne(item, true);
            System.out.println("Withdrew " + item);
            sleep(600, 1200);
        }
        for (Map.Entry<String, Integer> entry : getInventoryOptionals().entrySet()) {
            Rs2Bank.withdrawX(entry.getKey(), entry.getValue(), true);
            System.out.println("Withdrew " + entry.getKey());
            sleep(1300);
        }
        Rs2Bank.closeBank();
        sleep(3000);
        debug("Equiping gear...");
        for (String item : getEquip()) {
            Rs2Equipment.equipItem(item);
            sleep(600, 1200);
        }
    }

    public static int gearFails = 0;
    public static boolean gearedUp() {
        if (gearFails >= 5) {
            Microbot.getNotifier().notify("Failed to gear up 5 times! Sleeping forever...");
            sleep(999999999);
        }
        for (Map.Entry<String, Integer> entry : getInventoryRequirements().entrySet()) {
            if (!Inventory.hasItemAmount(entry.getKey(), entry.getValue())) {
                debug("Missing " + entry.getKey() + "x " + entry.getValue());
                gearFails += 1;
                return false;
            }
        }
        for (String item : getEquip()) {
            if (!Rs2Equipment.hasEquipped(item)) {
                debug("Missing " + item);
                gearFails += 1;
                return false;
            }
        }
        gearFails = 0;
        return true;
    }

}
