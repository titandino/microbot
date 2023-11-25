package net.runelite.client.plugins.microbot.bruteforcesummer.uppermotherload;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.mining.uppermotherload.UpperMotherloadScript.fuzz;
import static net.runelite.client.plugins.microbot.util.math.Random.random;

@Slf4j
public class BruteSorcScript extends Script {

    public static final ArrayList<String> debugMessages = new ArrayList<>();

    public void debug(String msg) {
        log.info(msg);
        if (debugMessages.size() >= 5) debugMessages.remove(0);
        debugMessages.add(msg);
    }

    public boolean run() {
        Microbot.enableAutoRunOn = true;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            debug("Looping..");
            try {
                if (Inventory.count() == 28) {
                    debug("Inventory full!");
                    bank();
                    return;
                }
                if (Inventory.count() == 0 && Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(new WorldPoint(3305, 3140, 0)) < 100) {
                    entergarden();
                    return;
                }
                if (getTree() != null) {
                    debug("Interacting tree");
                    Rs2GameObject.interact(getTree());
                    long invSize = Inventory.count();
                    sleepUntil(() -> Inventory.count() != invSize || getTree() == null, 50000);
                    sleep(random(2000, 3000));
                } else {
                    debug("Interacting gate");
                    if (!Rs2GameObject.interact(getGate())) {
                        Microbot.getNotifier().notify("[ATTENTION] Couldn't make it back from bank!");
                        sleep(99999999);
                    }
                    sleepUntil(() -> getTree() != null, 10000);
                }
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    private WallObject getSorcHouseDoor() {
        return Rs2GameObject.getWallObjects().stream()
                .filter(x -> x.getWorldLocation().equals(new WorldPoint(3321, 3142, 0)))
                .filter(x -> x.getId() == ObjectID.DOOR_1535)
                .findFirst().orElse(null);
    }

    private void bank() {
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
        debug("Running to Shantay's Pass...'");
        Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), fuzz(new WorldPoint(3305, 3140, 0))));
        sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(new WorldPoint(3305, 3140, 0)) < 8);

        // Deposit at deposit box
        debug("Depositing everything...");
        if (!Rs2DepositBox.isOpen() && !Rs2DepositBox.openDepositBox()) {
            debug("Failed to open deposit box");
            sleep(99999999);
        }
        Rs2DepositBox.depositAll();
    }

    private void entergarden() {
        // Run back outside sorc garden door
        debug("Running back to sorc garden door...");
        Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), fuzz(new WorldPoint(3313, 3141, 0))));
        sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(new WorldPoint(3313, 3141, 0)) < 8);

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

    private GameObject getTree() {
        return Rs2GameObject.getGameObjects().stream()
                //.filter(x -> x.getId() == ObjectID.SQIRK_TREE)
                .filter(x -> x.getId() == ObjectID.HERBS_4980)
                .filter(x -> Microbot.getWalker().canInteract(x.getWorldLocation()))
                .findFirst().orElse(null);
    }

    private WallObject getGate() {
        return Rs2GameObject.getWallObjects().stream()
                .filter(x -> x.getId() == 11987 && x.getWorldLocation().getX() == 2910 && x.getWorldLocation().getY() == 5480)
                .findFirst().orElse(null);
    }
}