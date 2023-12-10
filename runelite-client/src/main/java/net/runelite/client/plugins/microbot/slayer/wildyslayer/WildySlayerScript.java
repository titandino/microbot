package net.runelite.client.plugins.microbot.slayer.wildyslayer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.MonsterEnum;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.slayer.SlayerPlugin;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin.wildySlayerRunning;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.handleFight;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Gear.*;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.*;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@Slf4j
public class WildySlayerScript extends Script {
    public final WildySlayerConfig config;
    public final WildySlayerPlugin plugin;
    public final SlayerPlugin slayerPlugin;

    @Inject
    private WildySlayerScript(WildySlayerConfig config, WildySlayerPlugin plugin, SlayerPlugin slayerPlugin)
    {
        this.config = config;
        this.plugin = plugin;
        this.slayerPlugin = slayerPlugin;
    }

    public boolean run() {
        debug("Got config options " + config.GUIDE());
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            Microbot.enableAutoRunOn = true;
            try {
                // Decide what to do
                if (southOfWildy()) {
                    debug("Lol I died");
                    handleDeath();
                } else if (inFerox() && (needsToDrinkPPot() || needsToEatFood())) {
                    debug("Turning off prayers and drinking from pool...");
                    Rs2Prayer.turnOffMeleePrayer();
                    drinkFromPool();
                } else if (slayerPlugin.getAmount() <= 0 && inFerox()) {
                    getNewTask();
                } else if (slayerPlugin.getAmount() <= 0) {
                    debug("Task complete! Going Ferox");
                    toFerox();
                } else if (task() == null) {
                    debug("Task not supported! Going Ferox..");
                    toFerox();
                } else if (inFerox() && !gearedUp()) {
                    debug("Not geared up for task! Gearing up..");
                    gearUp();
                } else if (needsToDrinkPPot() && getPPots().length == 0) {
                    debug("I have no ppots but I'm thirsty! Going to Ferox..");
                    toFerox();
                } else if (needsToDrinkPPot()) {
                    drinkPPot();
                } else if (needsToEatFood() && getFoods().length == 0) {
                    debug("I eat but no munchies! Going to Ferox..");
                    toFerox();
                } else if (needsToEatFood()) {
                    eatFood();
                } else if (!atSlayerLocation()) {
                    debug("Not where my slayer location is!");
                    WildyWalk.toSlayerLocation(slayerPlugin.getTaskName());
                    plugin.lastMeaningfulActonTime = System.currentTimeMillis();
                } else {
                    debug("Should be fighting!");
                    handleFight();
                    sleep(5000);
                }
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    private void getNewTask() {
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
        sleep(10000);
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
        sleepUntil(() -> slayerPlugin.getAmount() != 0, 15_000);
        debug((slayerPlugin.getAmount() != 0 ? "successfully got" : "failed to get") + " a new task!");
        toFerox();
    }

    private void drinkFromPool() {
        Microbot.getWalker().walkTo(new WorldPoint(3134, 3634, 0));
        sleep(10000);
        Rs2GameObject.interact("Pool of Refreshment");
        sleepUntil(() -> !needsToDrinkPPot(), 30_000);
        sleep(3_000);
    }

    private void handleDeath() {
        debug("Going to Fally bank...");
        toFallyBank();
        if (!Rs2Bank.isOpen()) {
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 30_000);
        }
        debug("Going to Ferox...");
        Rs2Bank.withdrawOne("Ring of Dueling", false);
        Rs2Bank.closeBank();
        sleep(3000);
        toFerox();
    }

    private boolean southOfWildy() {
        return Microbot.getClient().getLocalPlayer().getWorldLocation().getY() <= 3520;
    }

    public MonsterEnum task() {
        return MonsterEnum.getConfig(slayerPlugin.getTaskName());
    }

    private boolean atSlayerLocation() {
        return WildyWalk.distTo(task().getLocation()) < (task().isAfkable() ? 5 : 25);
    }

    private Widget[] getPPots() {
        Widget[] potions = Microbot.getClientThread().runOnClientThread(Inventory::getPotions);
        if (potions == null) return new Widget[0];
        return potions;
    }
    
    private void drinkPPot() {
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

    private Widget[] getFoods() {
        Widget[] foods = Microbot.getClientThread().runOnClientThread(Inventory::getInventoryFood);
        if (foods == null) return new Widget[0];
        return foods;
    }

    private void eatFood() {
        debug("Eating some tasties...");
        if (getFoods().length != 0) Microbot.getMouse().click(getFoods()[0].getBounds());
    }

    private boolean needsToDrinkPPot() {
        return Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) * 100 /  Microbot.getClient().getRealSkillLevel(Skill.PRAYER) < random(25, 30);
    }

    private boolean needsToEatFood() {
        return Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) * 100 /  Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS) < random(70, 80);
    }

}