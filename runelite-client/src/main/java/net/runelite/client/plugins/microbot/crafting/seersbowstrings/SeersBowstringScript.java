package net.runelite.client.plugins.microbot.crafting.seersbowstrings;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectID;
import net.runelite.api.WallObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.mining.uppermotherload.UpperMotherloadScript;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.VirtualKeyboard;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@Slf4j
public class SeersBowstringScript extends Script {

    public boolean run() {
        Microbot.enableAutoRunOn = true;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            try {
                if (random(0, 100) == 0) {
                    int sleepTime = random(50_000, 100_000);
                    debug("Anti-ban: Sleeping " + sleepTime + " ms");
                    sleep(sleepTime);
                    debug("Anti-ban: Done sleeping " + sleepTime + "ms");
                }

                if (getFlaxDoor() != null)
                    openDoor();
                else if (Inventory.hasItem("flax") && getSpinningWheel() != null)
                    spinFlax();
                else if (Inventory.hasItem("flax"))
                    climbUpLadder();
                else if (getSpinningWheel() != null)
                    climbDownLadder();
                else {
                    bankEverything();
                    Rs2Bank.withdrawItemAll("Flax");
                    sleepUntil(() -> Inventory.hasItem("Flax"));
                }
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private void openDoor() {
        debug("Opening flax door..");
        Rs2GameObject.interact(getFlaxDoor(), "Open");
        sleepUntil(() -> getFlaxDoor() == null);
    }

    private WallObject getFlaxDoor() {
        return Rs2GameObject.getWallObjects().stream()
                .filter(x -> x.getWorldLocation().equals(new WorldPoint(2716, 3472, 0)))
                .filter(x -> x.getId() == ObjectID.DOOR_25819)
                .findFirst().orElse(null);
    }

    private GameObject getDownLadder() {
        return Rs2GameObject.getGameObjects().stream()
                .filter(x -> x.getWorldLocation().equals(new WorldPoint(2715, 3470, 1)))
                .filter(x -> x.getId() == ObjectID.LADDER_25939)
                .findFirst().orElse(null);
    }

    private void climbDownLadder() {
        debug("Climbing down ladder.. " + getDownLadder());
        Rs2GameObject.interact(getDownLadder(), "Climb-down");
        sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane() == 0, 30000);
    }

    private GameObject getSpinningWheel() {
        return Rs2GameObject.getGameObjects().stream()
                .filter(x -> x.getId() == ObjectID.SPINNING_WHEEL_25824)
                .findFirst().orElse(null);
    }

    private GameObject getUpLadder() {
        return Rs2GameObject.getGameObjects().stream()
                .filter(x -> x.getWorldLocation().equals(new WorldPoint(2715, 3470, 0)))
                .filter(x -> x.getId() == ObjectID.LADDER_25938)
                .findFirst().orElse(null);
    }

    private final WorldPoint upLadderLoc = new WorldPoint(2719, 3474, 0);
    private void climbUpLadder() {
        walkCloserTo(upLadderLoc);
        debug("Climbing up ladder..");
        Rs2GameObject.interact(getUpLadder(), "Climb-up");
        sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().getPlane() == 1, 30000);
    }

    private void walkCloserTo(WorldPoint dest) {
        if (Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(dest) > 10) {
            Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), UpperMotherloadScript.fuzz(dest)));
            int dist = random(1, 5);
            debug("Walking within " + dist + " to dest...");
            sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo2D(dest) < dist, 8000);
            debug("Walked closer to dest...");
        }
    }

    private void spinFlax() {
        debug("Interacting spin wheel..");
        Rs2GameObject.interact(getSpinningWheel(), "Spin");
        debug("Sleeping until widget 17694736 isn't null");
        sleepUntil(() -> Rs2Widget.getWidget(17694736) != null);
        while (Rs2Widget.getWidget(17694736) != null) {
            sleep(random(100, 300));
            VirtualKeyboard.typeString("3");
            debug("Sent keypress..");
            sleep(1200);
        }
        sleepUntil(() -> Inventory.getAmountForItem("Flax") == 0, 75_000);
        if (random(0, 3) == 0) {
            debug("Afk anti-ban, sleeping up to 12 seconds");
            sleep(random(1000, 12000));
        } else {
            sleep(random(100, 1200));
        }
    }

    private final WorldPoint seersBankLoc = new WorldPoint(2725, 3491, 0);
    private void bankEverything() {
        debug("Starting banking everything..");
        walkCloserTo(seersBankLoc);

        // use bank
        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            debug("Failed to open bank");
            return;
        }
        sleepUntil(Rs2Bank::isOpen);
        if (random(0, 4) != 0) {
            Rs2Bank.depositAll();
        } else {
            Set<Integer> deposited = new HashSet<>();

            // Get all inventory items and shuffle the list
            List<Widget> inventoryItems = new ArrayList<>(Arrays.asList(Objects.requireNonNull(Inventory.getInventoryItems())));
            Collections.shuffle(inventoryItems);

            for (Widget item : inventoryItems) {
                if (deposited.contains(item.getItemId())) continue;
                Rs2Bank.depositAll(item.getItemId());
                deposited.add(item.getItemId());
                sleep(100, 300);
            }
        }
        debug("We done depositing");
    }
}