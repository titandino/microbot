package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin.wildySlayerRunning;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Combat.task;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.MonsterEnum.getConfig;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class WildyWalk {
     private static boolean walkToAndBack(int dx, int dy) {
        WorldPoint origin = Microbot.getClient().getLocalPlayer().getWorldLocation();
        if (!Microbot.getWalker().canReach(origin.dx(dx).dy(dy))) return false;

        debug("Walking " + dx + ", " + dy + " tiles away to reset aggro..");
        while (wildySlayerRunning && distTo(origin.dx(dx).dy(dy)) > 2) {
            Microbot.getWalker().walkTo(origin.dx(dx).dy(dy));
            sleep(1200, 2400);
        }
        debug("Walking back...");
        while (wildySlayerRunning && !Microbot.getClient().getLocalPlayer().getWorldLocation().equals(origin)) {
            Microbot.getWalker().walkTo(origin);
        }
        debug("Aggro reset!");
        return true;
    }
    public static void resetAggro() {
         debug("Walking to aggro reset spot...");
         while (wildySlayerRunning && distTo(task().getAggroResetSpot()) < 3) {
             Microbot.getWalker().walkTo(task().getAggroResetSpot());
             sleep(800, 1600);
         }
    }

    public static int distTo(WorldPoint point) {
         return Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(point);
    }

    public static void walkToSlayerLocation(String taskName) {
         if (inFerox()) {
             debug("Leaving barrier..");
             Rs2GameObject.interact(39652);
             sleepUntil(() -> !inFerox());
             return;
         }
         while (wildySlayerRunning && distTo(new WorldPoint(3122, 3629, 0)) < 40) {
             debug("Getting unstuck from West of Ferox...");
             Microbot.getWalker().walkTo(Microbot.getClient().getLocalPlayer().getWorldLocation().dx(-7).dy(7));
             sleep(800, 1200);
         }
         if (getConfig(taskName).isInSlayerCave() && Microbot.getClient().getLocalPlayer().getWorldLocation().getY() < 10000) {
             goToSlayerCave();
             return;
         }
         if (getConfig(taskName).getLocation().getY() > 3903 && Microbot.getClient().getLocalPlayer().getWorldLocation().getY() <= 3903) {
             if (Rs2GameObject.interact(1728) || Rs2GameObject.interact(1569)) {
                 debug("Opening northern gate...");
                 sleep(5000);
             }
             debug("Walking to the northern gate to get to " + taskName);
             Microbot.getWalker().walkTo(new WorldPoint(3223, 3906, 0));
             sleep(random(1200, 2400));
             return;
         }
         debug("Walking to " + taskName);
         Microbot.getWalker().walkTo(getConfig(taskName).getLocation(), false);
         sleep(600, 1200);
    }

    private static void goToSlayerCave() {
        debug("Going to the slayer cave...");
        if (Rs2GameObject.interact(40388)) {
            debug("Entering slayer cave...");
            sleep(5000);
            return;
        }
        Microbot.getWalker().walkTo(new WorldPoint(3259, 3662, 0));
        sleep(600, 1200);
    }

    private final static WorldPoint slayerCaveEntrance = new WorldPoint(3385, 10053, 0);
    public static void toFerox() {
        if (inFerox()) {
            debug("Already in Ferox.. Sleeping 25 seconds");
            sleep(25_000);
        }
        if (Microbot.getClient().getLocalPlayer().getWorldLocation().getY() > 3903) {
            if (Rs2GameObject.interact(1728) || Rs2GameObject.interact(1569)) {
                debug("Opening northern gate...");
                sleep(5000);
            }
            debug("Walking to the northern gate..");
            Microbot.getWalker().walkTo(new WorldPoint(3224, 3902, 0));
            sleep(random(1200, 2400));
            return;
        }
        if (inSlayerCave() && Microbot.getClient().getLocalPlayer().getWorldLocation().getY() <= 10079 ||
                !inSlayerCave() && Microbot.getClient().getLocalPlayer().getWorldLocation().getY() <= 3679) {
            while (wildySlayerRunning && Microbot.getClient().getLocalPlayer().getHealthScale() != -1) {
                debug("Can't use dueling ring while in combat! Trying to run away");
                Microbot.getWalker().walkTo(Microbot.getClient().getLocalPlayer().getWorldLocation().dy(-10));
                sleep(600, 1200);
            }
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

    private static final WorldPoint fallyBank = new WorldPoint(2946, 3370, 0);
    public static void toFallyBank() {
        while (wildySlayerRunning && distTo(fallyBank) > 10) {
            Microbot.getWalker().walkTo(fallyBank);
            sleep(1200, 2400);
        }
    }

    public static boolean inFerox() {
        return Rs2Npc.getNpc("Banker") != null && Microbot.getWalker().canReach(Rs2Npc.getNpc("Banker").getWorldLocation()) ||
                Rs2Npc.getNpc("Marten") != null && Microbot.getWalker().canReach(Rs2Npc.getNpc("Marten").getWorldLocation());
    }

    private static boolean inSlayerCave() {
        return Microbot.getClient().getLocalPlayer().getWorldLocation().getY() > 10000;
    }

}
