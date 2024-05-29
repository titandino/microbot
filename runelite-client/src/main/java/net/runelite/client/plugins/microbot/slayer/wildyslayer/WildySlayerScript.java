package net.runelite.client.plugins.microbot.slayer.wildyslayer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.MonsterEnum;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.slayer.SlayerPlugin;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.handleFight;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Gear.gearUp;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Gear.gearedUp;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Hop.hopRandomWorld;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.NewTask.getNewTask;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Potions.*;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.*;
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
            System.out.println("Debug - Loop started");
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            Microbot.enableAutoRunOn = true;
            try {
                Rs2Tab.switchToInventoryTab();

                // Decide what to do
                if (!inWildy()) {
                    debug("Lol I died");
                    handleDeath();
                } else if (inFerox() && (needsToDrinkPPot() || needsToEatFood())) {
                    debug("Turning off prayers and drinking from pool...");
                    Rs2Prayer.turnOffMeleePrayer();
                    drinkFromPool();
                } else if (slayerPlugin.getAmount() <= 0 && inFerox()) {
                    getNewTask();
                    return;
                } else if (slayerPlugin.getAmount() <= 0) {
                    debug("Task complete! Going Ferox");
                    toFerox();
                } else if (task().isSkip()) {
                    debug("Task should be skipped!");
                    getNewTask();
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
                    return;
                } else if (needsToEatFood()) {
                    eatFood();
                } else if (!atSlayerLocation()) {
                    debug("Not where my slayer location is!");
                    WildyWalk.toSlayerLocation(slayerPlugin.getTaskName());
                    plugin.lastMeaningfulActonTime = System.currentTimeMillis();
                } else if (shouldDrinkStrPot()) {
                    debug("Drinking strength pot");
                    Microbot.getMouse().click(getStrPot()[0].getBounds());
                } else if (shouldDrinkAtkPot()) {
                    debug("Drinking attack pot");
                    Microbot.getMouse().click(getAtkPot()[0].getBounds());
                } else {
                    handleFight();
                    sleep(5000);
                }
            } catch (Exception ex) {
                System.out.println("Caught exception " + ex + " with message " + ex.getMessage() + " and stacktrace: " + Arrays.toString(ex.getStackTrace()));
            }
            System.out.println("Debug - Loop complete");
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    private void drinkFromPool() {
        Microbot.getWalker().walkTo(new WorldPoint(3134, 3634, 0));
        sleepWalk();
        Rs2GameObject.interact("Pool of Refreshment");
        sleepUntil(() -> !needsToDrinkPPot(), 15_000);
    }

    private void handleDeath() {
        debug("Hopping worlds..");
        hopRandomWorld();
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

    public MonsterEnum task() {
        return MonsterEnum.getConfig(slayerPlugin.getTaskName());
    }

    private boolean atSlayerLocation() {
        switch (task()) {
            case CHAOS_DRUIDS:
                return WildyWalk.distTo(task().getLocation()) < 10;
            case ICE_GIANTS:
                return WildyWalk.distTo(task().getLocation()) < 7;
            default:
                return WildyWalk.distTo(task().getLocation()) < (task().isAfkable() ? 5 : 25);
        }
    }

}