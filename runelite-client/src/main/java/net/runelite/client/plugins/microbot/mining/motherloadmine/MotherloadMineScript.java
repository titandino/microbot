package net.runelite.client.plugins.microbot.mining.motherloadmine;

import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.mining.motherloadmine.enums.MLMMiningSpot;
import net.runelite.client.plugins.microbot.mining.motherloadmine.enums.MLMStatus;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static net.runelite.client.plugins.microbot.util.math.Random.random;

@Slf4j
public class MotherloadMineScript extends Script {

    public static double version = 1.0;

    final static int SACKID = 26688;
    final static int DOWN_LADDER = 19045;
    final static int UP_LADDER = 19044;

    public static MLMStatus status = MLMStatus.MINING;
    public static String debugOld = "Old Placeholder";
    public static String debugNew = "New Placeholder";

    public void debug(String msg) {
        log.info(msg);
        debugOld = debugNew;
        debugNew = msg;
    }

    MLMMiningSpot miningSpot = MLMMiningSpot.IDLE;
    boolean emptySack = false;
    public boolean run() {
        Microbot.enableAutoRunOn = true;
        miningSpot = MLMMiningSpot.IDLE;
        status = MLMStatus.MINING;
        emptySack = false;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            try {
                debug("We looping");

                if (!Inventory.hasItem("hammer"))
                {
                    bank();
                    debug("Opening bank because we have no hammer");
                    return;
                }

                if (Microbot.getVarbitValue(Varbits.SACK_NUMBER) > 80 || (emptySack && !Inventory.contains("pay-dirt"))) {
                    status = MLMStatus.EMPTY_SACK;
                } else if (!Inventory.isFull()) {
                    status = MLMStatus.MINING;
                } else if (Inventory.isFull()) {
                    miningSpot = MLMMiningSpot.IDLE;
                    if (isOnUpperFloor()) {
                        status = MLMStatus.GO_DOWN;
                    }
                    else if (Inventory.hasItem(ItemID.PAYDIRT)) {
                        if (Rs2GameObject.findObjectById(ObjectID.BROKEN_STRUT) != null && Inventory.hasItem("hammer")) {
                            status = MLMStatus.FIXING_WATERWHEEL;
                        } else {
                            status = MLMStatus.DEPOSIT_HOPPER;
                        }
                    } else {
                        status = MLMStatus.BANKING;
                    }
                }

                if (random(0, 100) == 0) {
                    int sleepTime = random(50_000, 100_000);
                    debug("Antiban: Sleeping " + sleepTime + " ms");
                    sleep(sleepTime);
                    debug("Antiban: Done sleeping" + sleepTime + " ms");
                }

                switch (status) {
                    case MINING:
                        if (miningSpot == MLMMiningSpot.IDLE) {
                            miningSpot = MLMMiningSpot.NORTH_UPPER;
                        }
                        if (walkToMiningSpot()) return; // had to walk
                        mineVein();
                        break;
                    case GO_DOWN:
                        Rs2GameObject.interact(DOWN_LADDER);
                        break;
                    case EMPTY_SACK:
                        while (Microbot.getVarbitValue(Varbits.SACK_NUMBER) > 10) {
                            if (Inventory.count() <= 5) {
                                long beforeInvCount = Inventory.count();
                                Rs2GameObject.interact(SACKID);
                                debug("Grabbed stuff from sack");
                                sleepUntil(() -> Inventory.count() > beforeInvCount, 10000);
                            }
                            bank();
                        }
                        emptySack = false;
                        break;
                    case FIXING_WATERWHEEL:
                        fixWaterWheel();
                        break;
                    case DEPOSIT_HOPPER:
                        if (Rs2GameObject.interact(ObjectID.HOPPER_26674)) {
                            debug("Deposited hopper");
                            sleepUntil(() -> !Inventory.isFull());
                            if (Microbot.getVarbitValue(Varbits.SACK_NUMBER) > 50) {
                                emptySack = true;
                            }
                            if (random(0, 2) == 0) {
                                debug("Antiban - emptying sack early..");
                                emptySack = true;
                            }
                        }
                        break;
                    case BANKING:
                        bank();
                        break;
                }

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private void fixWaterWheel() {
        Stream<GameObject> brokenStruts = Rs2GameObject.getGameObjects()
                .stream()
                .filter(x -> x.getId() == ObjectID.BROKEN_STRUT)
                .filter(x -> Microbot.getWalker().canInteract(x.getWorldLocation()));

        GameObject brokenStrut = brokenStruts.findFirst().orElse(null);

        if (brokenStrut != null) {
            if (Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(brokenStrut.getWorldLocation()) > 10) {
                Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), fuzz(brokenStrut.getWorldLocation())));
                debug("Walked closer to strut");
                sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo2D(brokenStrut.getWorldLocation()) < 5, 8000);
                return;
            }
            Rs2GameObject.interact(brokenStrut);
            debug("Started fixing waterwheel - sleeping until the broken strut is fixed, or for 10sec");
            sleepUntil(() -> Rs2GameObject.getGameObjects()
                    .stream()
                    .filter(x -> x.getWorldLocation().equals(brokenStrut.getWorldLocation())
                            && x.getId() == ObjectID.BROKEN_STRUT
                            && Microbot.getWalker().canInteract(x.getWorldLocation()))
                    .findFirst().orElse(null) == null, 10000);
        } else {
            debug("No broken strut found to fix");
        }
    }

    private WorldPoint fuzz(WorldPoint worldLocation) {
        return new WorldPoint(worldLocation.getX() + random(-2, 2), worldLocation.getY() + random(-2, 2), worldLocation.getPlane());
    }

    private void bank() {
        debug("Opening bank if it's not open..");
        if (!Rs2Bank.isOpen() && !Rs2Bank.useBank())
            return;
        sleepUntil(Rs2Bank::isOpen);
        Set<Integer> deposited = new HashSet<>();
        deposited.add(ItemID.HAMMER); // Because we don't want to deposit the hammer

        // Get all inventory items and shuffle the list
        List<Widget> inventoryItems = new ArrayList<>(Arrays.asList(Inventory.getInventoryItems()));
        Collections.shuffle(inventoryItems);

        for (Widget item : inventoryItems) {
            if (deposited.contains(item.getItemId())) continue;
            Rs2Bank.depositAll(item.getItemId());
            deposited.add(item.getItemId());
            sleep(100, 300);
        }
        if (!Inventory.hasItem("hammer")) Rs2Bank.withdrawOne("hammer", true);
        debug("We done banking");
    }

    public static boolean isOnUpperFloor() {
        return Microbot.getWalker().canInteract(Rs2GameObject.findObjectById(DOWN_LADDER).getWorldLocation());
    }

    // returns true if we had to walk
    private boolean walkToMiningSpot() {
        if (isOnUpperFloor()) return false;

        GameObject ladder = Rs2GameObject.getGameObjects()
                .stream()
                .filter(x -> x.getId() == UP_LADDER)
                .filter(x -> Microbot.getWalker().canInteract(x.getWorldLocation()))
                .findFirst().orElse(null);
        if (ladder == null) {
            debug("Couldn't find a reachable ladder to upper motherload mine");
            return false;
        }
        debug("Walking to mining spot");
        Rs2GameObject.interact(ladder);
        sleepUntil(MotherloadMineScript::isOnUpperFloor, 8000);
        return true;
    }

    private static final Set<String> MINEABLE_WALL_LOCS = ImmutableSet.of(
            "3762,5670", "3762,5671", "3762,5672", "3762,5673",
            "3761,5674", "3760,5674", "3759,5674",
            "3758,5675",
            "3755,5677",
            "3754,5676",
            "3754,5678",
            "3753,5680"
            );

    private WallObject getVein() {
        Stream<WallObject> orderedVeins = Rs2GameObject.getWallObjects()
                .stream()
                .filter(x -> MINEABLE_WALL_LOCS.contains(x.getWorldLocation().getX() + "," + x.getWorldLocation().getY()))
                .sorted(Comparator.comparingInt(x -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(x.getWorldLocation())))
                .filter(x -> Microbot.getWalker().canInteract(x.getWorldLocation()));

        return random(0, 2) == 0 ? orderedVeins.skip(1).findFirst().orElse(null) : orderedVeins.findFirst().orElse(null);
    }

    private void mineVein() {
        WallObject vein = getVein();

        if (vein == null) {
            debug("Found no vein!");
            return;
        }

        Rs2GameObject.interact(vein);
        debug("Started mining. Sleeping until the selected vein is gone/inv full, or 60 seconds, whichever happens first.");
        sleepUntil(() -> Inventory.isFull() || Rs2GameObject.getWallObjects()
                .stream()
                .filter(x -> x.getWorldLocation().equals(vein.getWorldLocation())
                        && Microbot.getWalker().canInteract(x.getWorldLocation()))
                .findFirst().orElse(null) == null, 60000);

        debug("Sleeping another 2-15s (33% chance)");
        if (random(0, 2) != 0) sleep(random(2000, 15000));
        else sleep(random(200, 1500));
    }
}