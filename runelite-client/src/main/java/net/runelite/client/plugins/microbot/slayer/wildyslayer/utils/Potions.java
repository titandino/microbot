package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.Skill;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;

import java.util.Arrays;

import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Potions {
    public static Widget[] getPPots() {
        return Arrays.stream(Microbot.getClientThread().runOnClientThread(Inventory::getPotions))
                .filter(p -> p.getName().toLowerCase().contains("prayer") || p.getName().toLowerCase().contains("super restore"))
                .toArray(Widget[]::new);
    }

    public static void drinkPPot() {
        debug("Drinking a ppot...");
        for (Widget potion: getPPots()) {
            if (potion.getName().toLowerCase().contains("prayer") || potion.getName().toLowerCase().contains("super restore")) {
                Microbot.getMouse().click(potion.getBounds());
                sleep(1200, 2000);
                return;
            }
        }
        debug("Couldn't find a ppot! I'm prolly gonna die");
    }

    public static Widget[] getStrPot() {
        return Arrays.stream(Microbot.getClientThread().runOnClientThread(Inventory::getPotions))
                .filter(p -> p.getName().toLowerCase().contains("strength"))
                .toArray(Widget[]::new);
    }

    public static boolean shouldDrinkStrPot() {
        return getStrPot().length > 0 && Microbot.getClient().getBoostedSkillLevel(Skill.STRENGTH) <  Microbot.getClient().getRealSkillLevel(Skill.STRENGTH) + random(1, 5);
    }

    public static Widget[] getAtkPot() {
        return Arrays.stream(Microbot.getClientThread().runOnClientThread(Inventory::getPotions))
                .filter(p -> p.getName().toLowerCase().contains("attack"))
                .toArray(Widget[]::new);
    }

    public static boolean shouldDrinkAtkPot() {
        return getAtkPot().length > 0 && Microbot.getClient().getBoostedSkillLevel(Skill.ATTACK) <  Microbot.getClient().getRealSkillLevel(Skill.ATTACK) + random(1, 5);
    }

    public static Widget[] getFoods() {
        Widget[] foods = Microbot.getClientThread().runOnClientThread(Inventory::getInventoryFood);
        if (foods == null) return new Widget[0];
        return foods;
    }

    public static void eatFood() {
        debug("Eating some tasties...");
        if (getFoods().length != 0) Microbot.getMouse().click(getFoods()[0].getBounds());
    }

    public static boolean needsToDrinkPPot() {
        return Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) * 100 /  Microbot.getClient().getRealSkillLevel(Skill.PRAYER) < random(25, 30);
    }

    public static boolean needsToEatFood() {
        return Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) * 100 /  Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS) < random(70, 80);
    }

}
