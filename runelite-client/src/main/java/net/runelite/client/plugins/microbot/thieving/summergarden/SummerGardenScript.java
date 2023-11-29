package net.runelite.client.plugins.microbot.thieving.summergarden;

import net.runelite.api.GameObject;
import net.runelite.api.ObjectID;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.mining.uppermotherload.UpperMotherloadScript.fuzz;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class SummerGardenScript extends Script {

    public static double version = 1.0;

    public static WorldPoint startingPosition = new WorldPoint(2910, 5481, 0);
    public static TileObject herbs = null;
    static int success = 0;
    static int failure = 0;

    @Override
    public boolean run() {
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            long startTime = System.currentTimeMillis();
            Microbot.enableAutoRunOn = false;
            if (!super.run()) return;
            try {
                if (random(0, 25000) == 0) {
                    int sleepTime = random(0, 90000);
                    debug("Afk antiban for " + sleepTime + "ms..");
                    sleep(sleepTime);
                }
                if (random(0, 100) == 0) {
                    debug("Randomly reassigning herb gameobject to grab..");
                    getHerb();
                }
                if (Inventory.count() == 28) {
                    debug("Inventory full!");
                    bank();
                    return;
                }
                if (Inventory.count() == 0 && Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(new WorldPoint(3305, 3140, 0)) < 100) {
                    entergarden();
                    return;
                }
                if (herbs == null) {
                    getHerb();
                }

                if (Microbot.getClient().getLocalPlayer().getWorldLocation().equals(startingPosition)) {
                    Rs2Player.toggleRunEnergy(true);
                    if (ElementalCollisionDetector.getTicksUntilStart() == 0 && Microbot.getClient().getEnergy() > 2_000) {
                        clickHerb(startTime);
                    }
                    return;
                } else {
                    debug("Not at starting position..");
                }

                if (Microbot.getClient().getLocalPlayer().getWorldLocation().getY() >= 5481) {
                    Microbot.getNotifier().notify("Somehow not at start position, but also north of gate. Gonna click herb, YOLO");
                    clickHerb(startTime);
                    sleep(random(15_000, 20_000));
                    return;
                }

                goToStartTile();

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 10, TimeUnit.MILLISECONDS);
        return true;
    }

    private void clickHerb(long startTime) {
        debug("Time to go!");
        Rs2GameObject.interact(herbs);
        long timeToClick = (System.currentTimeMillis() - startTime) - 300;
        System.out.println("Time from start to click: " + timeToClick + " ms");
        if (timeToClick > 300) Microbot.getNotifier().notify("Click took more than 300ms - Check if we fail to get herbs");
        sleepUntil(Microbot::isMoving);
        sleepUntil(() -> !Microbot.isMoving(), 30000);
        sleepUntilOnClientThread(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().getY() < 5481);
        if (Microbot.getClient().getEnergy() > 5_000) {
            debug("Powernap - We can get another run in if we go quick");
            sleep(random(1500, 2800)); //caught or success timeout
        } else {
            debug("Out of the garden, sleeping...");
            sleep(random(1500, 10000));
        }
    }

    private void goToStartTile() {
        debug("Going to starting position");
        TileObject gate = Rs2GameObject.findObjectById(ObjectID.GATE_11987);

        if (gate == null) {
            return;
        }
        if (Microbot.getClient().getEnergy() < 4_000) Rs2Player.toggleRunEnergy(false);
        sleep(random(600, 900));
        Rs2GameObject.interact(gate);
        sleepUntil(Microbot::isMoving);
        sleepUntil(() -> !Microbot.isMoving());
        sleepUntilOnClientThread(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().equals(startingPosition), 10000);
        sleep(random(600, 1800));
        Rs2Player.toggleRunEnergy(true);
        sleep(random(600, 1200));
    }

    private void getHerb() {
        WorldPoint location = random(0, 2) == 0 ? new WorldPoint(2923, 5483, 0) : new WorldPoint(2924, 5482, 0);
        List<GameObject> gameObjects = Rs2GameObject.getGameObjects();
        for (net.runelite.api.GameObject gameObject : gameObjects) {
            if (gameObject.getId() == 4980 && gameObject.getWorldLocation().equals(location))
                herbs = gameObject;
        }
    }

    private WallObject getSorcHouseDoor() {
        return Rs2GameObject.getWallObjects().stream()
                .filter(x -> x.getWorldLocation().equals(new WorldPoint(3321, 3142, 0)))
                .filter(x -> x.getId() == ObjectID.DOOR_1535)
                .findFirst().orElse(null);
    }

    private void bank() {
        Rs2Player.toggleRunEnergy(false);
        sleep(random(600, 900));
        // Leave the sorc garden
        debug("Leaving sorc garden...");
        Rs2GameObject.interact("Fountain");
        sleep(15000);

        // Open door if closed
        debug("Checking if door is closed..");
        if (getSorcHouseDoor() != null) {
            Rs2GameObject.interact(getSorcHouseDoor(), "Open");
            sleepUntil(() -> getSorcHouseDoor() == null);
        }

        // Run to Shantay's Pass
        debug("Running to Shantay's Pass...");
        Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), fuzz(new WorldPoint(3305, 3140, 0))));
        sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(new WorldPoint(3305, 3140, 0)) < 6, 10000);

        // Deposit at deposit box
        debug("Depositing everything...");
        if (!Rs2DepositBox.isOpen() && !Rs2DepositBox.openDepositBox()) {
            debug("Failed to open deposit box");
            Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), new WorldPoint(3304, 3133, 0)));
            sleep(random(6000, 12000));
            if (!Rs2DepositBox.isOpen() && !Rs2DepositBox.openDepositBox()) {
                Microbot.getNotifier().notify("We stuck");
                sleep(99999999);
            } else {
                debug("Got to deposit box the second time");
            }
        }
        Rs2DepositBox.depositAll();
        sleepUntil(() -> Inventory.count() == 0);
    }

    private void entergarden() {
        Rs2Player.toggleRunEnergy(false);
        sleep(random(600, 900));
        // Run back outside sorc garden door
        debug("Running back to sorc garden door...");
        Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), fuzz(new WorldPoint(3313, 3142, 0))));
        sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(new WorldPoint(3313, 3142, 0)) < 3, 10000);

        // Check for door again
        debug("Checking if door is closed..");
        if (getSorcHouseDoor() != null) {
            Rs2GameObject.interact(getSorcHouseDoor(), "Open");
            sleepUntil(() -> getSorcHouseDoor() == null);
        }
        // Tele with NPC to enter sorc garden
        debug("Teleing to sorc garden...");
        Rs2Npc.interact("Apprentice", "Teleport");
        sleep(10000);
    }

    @Override
    public void shutdown() {
        success = 0;
        failure = 0;
        herbs = null;
        super.shutdown();
    }

}
