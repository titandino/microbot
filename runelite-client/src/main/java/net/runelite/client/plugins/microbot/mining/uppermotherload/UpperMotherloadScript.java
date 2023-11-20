package net.runelite.client.plugins.microbot.mining.uppermotherload;

import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.mining.uppermotherload.enums.UpperMLMSpot;
import net.runelite.client.plugins.microbot.mining.uppermotherload.enums.UpperMLMStatus;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@Slf4j
public class UpperMotherloadScript extends Script {

    final static int SACKID = 26688;
    final static int DOWN_LADDER = 19045;
    final static int UP_LADDER = 19044;

    public static UpperMLMStatus status = UpperMLMStatus.MINING;
    UpperMLMSpot miningSpot = UpperMLMSpot.IDLE;
    boolean emptySack = false;
    public boolean run() {
        Microbot.enableAutoRunOn = true;
        miningSpot = UpperMLMSpot.IDLE;
        status = UpperMLMStatus.MINING;
        emptySack = false;

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

                if (false && getMyGenie() != null) {
                    status = UpperMLMStatus.INTERACT_GENIE;
                } else if (false && Inventory.hasItem("lamp")) {
                    status = UpperMLMStatus.USE_LAMP;
                } else if (Microbot.getVarbitValue(Varbits.SACK_NUMBER) > 80 || (emptySack && !Inventory.contains("pay-dirt"))) {
                    if (isOnUpperFloor()) status = UpperMLMStatus.GO_DOWN;
                    else status = UpperMLMStatus.EMPTY_SACK;
                } else if (!Inventory.isFull()) {
                    status = UpperMLMStatus.MINING;
                } else if (Inventory.isFull()) {
                    miningSpot = UpperMLMSpot.IDLE;
                    if (isOnUpperFloor()) {
                        status = UpperMLMStatus.GO_DOWN;
                    }
                    else if (Inventory.hasItem(ItemID.PAYDIRT)) {
                        if (Rs2GameObject.getGameObjects().stream().filter(x -> x.getId() == ObjectID.STRUT).toArray().length <= 2) {
                            status = UpperMLMStatus.FIXING_WATERWHEEL;
                        } else {
                            status = UpperMLMStatus.DEPOSIT_HOPPER;
                        }
                    } else {
                        status = UpperMLMStatus.EMPTY_SACK;
                    }
                } else {
                    debug("Don't know what to do, so I'll empty sack");
                    status = UpperMLMStatus.EMPTY_SACK;
                }

                debug("We looping - " + status);

                switch (status) {
                    case INTERACT_GENIE:
                        debug("We got a genie!");
                        makeInventorySpace();
                        Rs2Npc.interact(getMyGenie(), "talk-to"); // todo - verify
                        sleepUntil(() -> Inventory.hasItem("lamp")); // todo - verify
                        break;
                    case USE_LAMP:
                        debug("We got a lamp!");
                        Inventory.useItemAction("lamp", "rub"); // todo - verify
                        Rs2Widget.clickChildWidget(786434, 11); // todo - I made this up
                        Rs2Widget.clickChildWidget(786434, 11); // todo - I made this up
                        break;
                    case MINING:
                        if (miningSpot == UpperMLMSpot.IDLE) {
                            miningSpot = UpperMLMSpot.NORTH_UPPER;
                        }
                        if (walkToMiningSpot()) return; // had to walk
                        mineVein();
                        break;
                    case GO_DOWN:
                        debug("Downclimbing");
                        Rs2GameObject.interact(DOWN_LADDER);
                        sleepUntil(() -> !isOnUpperFloor(), 10000);
                        break;
                    case EMPTY_SACK:
                        boolean itemsWereBanked = false;
                        while (Microbot.getVarbitValue(Varbits.SACK_NUMBER) > 10) {
                            if (Inventory.hasItem("hammer") && random(0, 3) == 0) {
                                debug("Anti-ban: Dropping hammer");
                                Inventory.drop("Hammer");
                            }
                            if (itemsWereBanked || Inventory.count() <= 5) {
                                Rs2GameObject.interact(SACKID);
                                sleepUntil(() -> !Rs2Bank.isOpen(), 10000);
                                debug("Grabbed stuff from sack");
                                sleepUntil(() -> Inventory.count() > 0, 10000);
                            }
                            debug("Starting bank from EMPTY_SACK");
                            bank();
                            itemsWereBanked = true; // Because Inventory.count() hasn't updated yet
                        }
                        if (Inventory.count() > 0) bank();
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
                            if (random(0, 2) == 0 && Microbot.getVarbitValue(Varbits.SACK_NUMBER) > 0) {
                                debug("Anti-ban - emptying sack early..");
                                emptySack = true;
                            }
                        }
                        break;
                }


            sleep(random(100, 300));
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private NPC getMyGenie() {
        return Rs2Npc.getNpcs("Genie").stream()
                .filter(n -> n.getInteracting() == Microbot.getClient().getLocalPlayer())
                .filter(n -> Microbot.getWalker().canInteract(n.getWorldLocation()))
                .findFirst().orElse(null);
    }

    private void makeInventorySpace() {
        if (Inventory.isFull()) {
            debug("Dropping pay-dirt to make room for a hammer..");
            if (!Inventory.hasItem("pay-dirt")) {
                debug("Need to make inventory space, but we don't have paydirt, so dropping slot 27. Good luck!");
                Inventory.dropAllStartingFrom(27);
            }
            Inventory.drop("Pay-dirt");
        }
    }

    private void getHammer() {
        debug("Getting a hammer...");
        makeInventorySpace();
        GameObject crate = Rs2GameObject.getGameObjects().stream()
                .filter(x -> x.getId() == ObjectID.CRATE_357)
                .filter(x -> Microbot.getWalker().canInteract(x.getWorldLocation()))
                .sorted(Comparator.comparingInt(x -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(x.getWorldLocation())))
                .collect(Collectors.toList())
                .get(random(0, 1));
        Rs2GameObject.interact(crate);
        sleepUntil(() -> Inventory.hasItem("hammer"), 10000);
        debug("Got my hammer (or timed out)");
    }

    private void fixWaterWheel() {
        if (!Inventory.contains("hammer")) {
            getHammer();
            return;
        }
        TileObject brokenStrut = Rs2GameObject.findObjectById(ObjectID.BROKEN_STRUT);

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
                            && x.getId() == ObjectID.BROKEN_STRUT)
                    .findFirst().orElse(null) == null, 10000);
        } else {
            debug("No broken strut found to fix");
        }
    }

    public static WorldPoint fuzz(WorldPoint worldLocation) {
        return new WorldPoint(worldLocation.getX() + random(-2, 2), worldLocation.getY() + random(-2, 2), worldLocation.getPlane());
    }

    private void bank() {
        int bankMethod = random(0, 4);
        bankMethod = 0; // the main bank is broken lately, so ya
        debug("Opening bank if it's not open - Using bank method " + bankMethod);
        if (bankMethod == 0) {
            if (!Rs2DepositBox.isOpen() && !Rs2DepositBox.openDepositBox()) {
                debug("Failed to open deposit box");
                return;
            }
            Rs2DepositBox.depositAll();
            return;
        }

        // use bank
        if (!Rs2Bank.isOpen() && !Rs2Bank.openBank()) {
            debug("Failed to open bank");
            return;
        }
        sleepUntil(Rs2Bank::isOpen);
        if (bankMethod != 1) {
            Rs2Bank.depositAll();
        } else {
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
        }
        debug("We done banking via method " + bankMethod);
    }

    public static boolean isOnUpperFloor() {
        return !Microbot.getWalker().canInteract(Rs2GameObject.findObjectById(ObjectID.HOPPER_26674).getWorldLocation());
    }

    // returns true if we had to walk
    private boolean walkToMiningSpot() {
        if (isOnUpperFloor()) return false;

        Rs2GameObject.interact(UP_LADDER);
        debug("Walking to mining spot");
        sleepUntil(UpperMotherloadScript::isOnUpperFloor, 8000);
        return true;
    }

    private static final List<WorldPoint> MINEABLE_WALL_LOCS = List.of(
            //"3762,5670", "3762,5671", "3762,5672", "3762,5673",
            new WorldPoint(3762, 5670, 0), new WorldPoint(3762, 5671, 0), new WorldPoint(3762,3672, 0), new WorldPoint(3762, 5673, 0),
            //"3761,5674", "3760,5674", "3759,5674",
            new WorldPoint(3760, 5674, 0), new WorldPoint(3760, 5674, 0), new WorldPoint(3759, 5674, 0),
            //"3758,5675",
            new WorldPoint(3758, 5675, 0),
            //"3755,5677",
            new WorldPoint(3755, 5677, 0),
            //"3754,5676",
            new WorldPoint(3754, 5676, 0),
            //"3754,5678",
            new WorldPoint(3754, 5678, 0),
            //"3753,5680"
            new WorldPoint(3753, 5680, 0)
            );

    private static final Set<Integer> MINEABLE_WALL_IDS = ImmutableSet.of(26661, 26662, 26663, 26664);

    private WallObject getVein() {
        // Retrieve all wall objects
        List<WallObject> wallObjects = Rs2GameObject.getWallObjects();

        // Filter based on distance
        List<WallObject> nearbyVeins = wallObjects.stream()
                .filter(x -> x.getWorldLocation().distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) < 10)
                .collect(Collectors.toList());

        // Filter based on ID
        List<WallObject> idFilteredVeins = nearbyVeins.stream()
                .filter(x -> MINEABLE_WALL_IDS.contains(x.getId()))
                .collect(Collectors.toList());

        // Filter based on location
        List<WallObject> locationFilteredVeins = idFilteredVeins.stream()
                .filter(x -> MINEABLE_WALL_LOCS.stream().anyMatch(wallLoc -> x.getWorldLocation().equals(wallLoc)))
                .collect(Collectors.toList());

        // Sort the veins
        List<WallObject> orderedVeins = locationFilteredVeins.stream()
                .sorted(Comparator.comparingInt(x -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(x.getWorldLocation())))
                .collect(Collectors.toList());

        // Retrieve either the first or second vein
        if (orderedVeins.isEmpty()) {
            return null;
        } else if (random(0, 2) == 0 && orderedVeins.size() > 1) {
            return orderedVeins.get(1);
        } else {
            return orderedVeins.get(0);
        }
    }
    private void mineVein() {
        WallObject vein = getVein();

        if (vein == null) {
            debug("Found no vein!");
            return;
        }

        Rs2GameObject.interact(vein);
        debug("Clicked mine. Sleep time zzz..");
        sleepUntil(() -> Inventory.isFull() || Rs2GameObject.getWallObjects()
                .stream()
                .filter(x -> x.getWorldLocation().equals(vein.getWorldLocation()))
                .filter(x -> MINEABLE_WALL_IDS.contains(x.getId()))
                .findFirst().orElse(null) == null, 60000);

        if (random(0, 2) != 0) {
            debug("Anti-ban: Sleeping another 2-15s (66% chance)");
            sleep(random(2000, 15000));
        }
        else sleep(random(200, 1500));
    }
}