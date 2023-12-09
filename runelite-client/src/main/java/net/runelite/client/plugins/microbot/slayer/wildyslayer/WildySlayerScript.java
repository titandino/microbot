package net.runelite.client.plugins.microbot.slayer.wildyslayer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.slayer.SlayerPlugin;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@Slf4j
public class WildySlayerScript extends Script {
    private final WildySlayerConfig config;
    private final WildySlayerPlugin plugin;
    private final SlayerPlugin slayerPlugin;

    @Inject
    private WildySlayerScript(WildySlayerConfig config, WildySlayerPlugin plugin, SlayerPlugin slayerPlugin)
    {
        this.config = config;
        this.plugin = plugin;
        this.slayerPlugin = slayerPlugin;
    }

    private long _lastRunXp = 0;

    public boolean run() {
        debug("Got config options " + config.GUIDE());
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            try {
                // Check for stuckness
                if (Microbot.getClient().getOverallExperience() > _lastRunXp) plugin.lastMeaningfulActonTime = System.currentTimeMillis();
                _lastRunXp = Microbot.getClient().getOverallExperience();

                // Decide what to do
                if (inFerox()) {
                    debug("We're in ferox! Happy days.");
                    sleep(5_000);
                } else if (slayerPlugin.getAmount() <= 0) {
                    debug("Task is complete!");
                    toFerox();
                } else if (needsToDrinkPPot() && getPPots().length == 0) {
                    debug("I have no ppots but I'm thirsty! Going to Ferox..");
                    toFerox();
                } else if (needsToDrinkPPot()) {
                    drinkPPot();
                } else if (plugin.lastMeaningfulActonTime + 10_000 < System.currentTimeMillis()) {
                    debug("Haven't gotten exp in at least 10 seconds! Going to try and re-trigger aggression...");
                    resetAggro();
                } else {
                    debug("Should be fighting! Sleeping 25 secs..");
                    sleep(25_000);
                }
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private boolean walkToAndBack(int dx, int dy) {
        WorldPoint origin = Microbot.getClient().getLocalPlayer().getWorldLocation();
        if (!Microbot.getWalker().canReach(origin.dx(dx).dy(dy))) return false;

        debug("Walking " + dx + ", " + dy + " tiles away to reset aggro..");
        Microbot.getWalker().walkTo(origin.dx(dx).dy(dy));
        sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().equals(origin.dx(dx).dy(dy)), 30_000);
        debug("Walking back...");
        Microbot.getWalker().walkTo(origin);
        sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().equals(origin), 30_000);
        debug("Aggro reset!");
        return true;
    }
    private void resetAggro() {
        // Need to run 30+ tiles away and back
        // Try S, E and W
        for (int i = 0; i < 5; i++) {
            if (walkToAndBack(30 + i, 0)) return;
            if (walkToAndBack(-30 - i, 0)) return;
            if (walkToAndBack(0, -30 - i)) return;
        }
        debug("Failed to reset aggro! halp");
        Microbot.getNotifier().notify("Failed to reset monster aggro!");
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
                debug("Drinking " + potion.getName());
                Microbot.getMouse().click(potion.getBounds());
                sleep(1200, 2000);
                return;
            }
        }
        debug("Couldn't find a ppot! I'm prolly gonna die");
    }

    private final WorldPoint slayerCaveEntrance = new WorldPoint(3385, 10053, 0);
    private void toFerox() {
        if (inFerox() && Microbot.getClient().getLocalPlayer().getWorldLocation().getY() <= 10079 || !inFerox() && Microbot.getClient().getLocalPlayer().getWorldLocation().getY() <= 3679) {
            debug("Using dueling ring");
            Inventory.useItemSafe("Ring of Dueling"); // assumes your dueling rings are left-click rub
            sleepUntil(() -> Rs2Widget.findWidget("Ferox Enclave.") != null);
            Rs2Widget.clickWidget("Ferox Enclave.");
            sleep(3000);
            return;
        }
        if (inSlayerCave()) {
            debug("Walking to slayer cave entrance..");
            Microbot.getWalker().walkTo(slayerCaveEntrance);
            sleep(random(1200, 2400));
        } else {
            debug("Walking south..");
            Microbot.getWalker().walkTo(Microbot.getClient().getLocalPlayer().getWorldLocation().dy(-10));
            sleep(random(1200, 2400));
        }
    }

    private boolean inFerox() {
        return Rs2Npc.getNpc("Banker") != null;
    }

    private boolean inSlayerCave() {
        return Microbot.getClient().getLocalPlayer().getWorldLocation().getY() > 10000;
    }

    private boolean needsToDrinkPPot() {
        return Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) * 100 /  Microbot.getClient().getRealSkillLevel(Skill.PRAYER) < random(25, 30);
    }

}