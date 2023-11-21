package net.runelite.client.plugins.microbot.thieving;

import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemID;
import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.timers.TimersPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.Microbot.debug;
import static net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment.getEquippedItem;
import static net.runelite.client.plugins.microbot.util.inventory.Inventory.eat;
import static net.runelite.client.plugins.microbot.util.math.Random.random;

public class ThievingScript extends Script {

    public static double version = 1.0;

    public boolean run(ThievingConfig config) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            try {
                process(null, config);
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    public boolean run(net.runelite.api.NPC npc) {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            try {
               process(npc, null);
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    public void process(net.runelite.api.NPC npc, ThievingConfig config) {
        if (Inventory.isFull()) {
            if (Rs2Bank.walkToBank()) {
                Rs2Bank.useBank();
                sleep(1000, 2000);
                sleepUntil(() -> !Microbot.isMoving());
                Rs2Bank.depositAll();
                Rs2Bank.closeBank();
                sleep(1000, 2000);
            }
            return;
        }
        if (Inventory.hasItemAmountStackable("coin pouch", 84)) {
            Inventory.interact("coin pouch");
            return;
        }
        if (config == null ? true : Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) > config.hitpoints()) {
            if (Rs2Npc.interact(config == null ? npc.getName() : config.THIEVING_NPC().getName(), "pickpocket"))
                sleepUntil(() -> Inventory.count() >= 28 || Inventory.hasItemAmountStackable("coin pouch", 84), 60_000);
        }
    }
}
