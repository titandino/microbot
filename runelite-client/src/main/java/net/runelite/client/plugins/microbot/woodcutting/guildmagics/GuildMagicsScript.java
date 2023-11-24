package net.runelite.client.plugins.microbot.woodcutting.guildmagics;

import com.google.common.collect.ImmutableSet;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.bank.Rs2DepositBox;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.models.RS2Item;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;
import net.runelite.client.plugins.microbot.woodcutting.guildmagics.enums.GuildMagicsStatus;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.math.Random.random;

@Slf4j
public class GuildMagicsScript extends Script {

    public static GuildMagicsStatus status = GuildMagicsStatus.BANKING;
    public static final ArrayList<String> debugMessages = new ArrayList<>();

    private static final Set<Integer> BIRD_NEST_IDS = ImmutableSet.of(5);

    public void debug(String msg) {
        log.info(msg);
        if (debugMessages.size() >= 5) debugMessages.remove(0);
        debugMessages.add(msg);
    }

    public boolean run() {
        Microbot.enableAutoRunOn = true;
        status = GuildMagicsStatus.BANKING;

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
                if (getMyGenie() != null) Microbot.getNotifier().notify("[ATTENTION] Dan debug this");

                if (false && getMyGenie() != null) {
                    status = GuildMagicsStatus.INTERACT_GENIE;
                } else if (false && Inventory.hasItem("lamp")) {
                    status = GuildMagicsStatus.USE_LAMP;
                } else if (getNest() != null) {
                    status = GuildMagicsStatus.PICK_UP_NEST;
                } else if (!Inventory.isFull()) {
                    status = GuildMagicsStatus.WOODCUTTING;
                } else if (Inventory.count() >= 26) {
                    status = GuildMagicsStatus.BANKING;
                } else {
                    debug("Don't know what to do, so I'll empty sack");
                    status = GuildMagicsStatus.BANKING;
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
                    case PICK_UP_NEST:
                        debug("Picking up a nest..");
                        Rs2GroundItem.interact(getNest());
                    case WOODCUTTING:
                        cutTree();
                        break;
                    case BANKING:
                        bank();
                        break;
                }


            sleep(random(100, 300));
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private RS2Item getNest() {
        return Arrays.stream(Rs2GroundItem.getAll(5)).filter(x -> BIRD_NEST_IDS.contains(x.getItem().getId())).findFirst().orElse(null);
    }

    private NPC getMyGenie() {
        return Rs2Npc.getNpcs("Genie").stream()
                .filter(n -> n.getInteracting() == Microbot.getClient().getLocalPlayer())
                .filter(n -> Microbot.getWalker().canInteract(n.getWorldLocation()))
                .findFirst().orElse(null);
    }

    private void makeInventorySpace() {
        if (Inventory.isFull()) {
            debug("Dropping log to make room for a hammer..");
            if (!Inventory.hasItem("Magic Logs")) {
                debug("Need to make inventory space, but we don't have Magic Logs, so dropping slot 27. Good luck!");
                Inventory.dropAllStartingFrom(27);
            }
            Inventory.drop("Magic Logs");
        }
    }

    private WorldPoint fuzz(WorldPoint worldLocation) {
        return new WorldPoint(worldLocation.getX() + random(-2, 2), worldLocation.getY() + random(-2, 2), worldLocation.getPlane());
    }
    private void bank() {
        if (Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(new WorldPoint(1591, 3478, 0)) > 10) {
            debug("Walking closer to bank..");
            Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), fuzz(new WorldPoint(1591, 3478, 0))));
            sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(new WorldPoint(1591, 3478, 0)) < 8);
        }
        int bankMethod = random(0, 4);
        bankMethod = 0;
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

    private static final List<WorldPoint> CUTTABLE_TREE_LOCS = List.of(
            // new WorldPoint(1577, 3492, 0), new WorldPoint(1580, 3492, 0), new WorldPoint(1577, 3489, 0), new WorldPoint(1580, 3489, 0),
            // new WorldPoint(1577, 3485, 0), new WorldPoint(1580, 3485, 0), new WorldPoint(1577, 3482, 0), new WorldPoint(1580, 3482, 0)
            new WorldPoint(1596, 3495, 0), new WorldPoint(1596, 3490, 0), new WorldPoint(1596, 3485, 0),
            new WorldPoint(1591, 3493, 0), new WorldPoint(1591, 3487, 0)
            );

    // private static final Set<Integer> CUTTABLE_TREE_IDS = ImmutableSet.of(10834);
    private static final Set<Integer> CUTTABLE_TREE_IDS = ImmutableSet.of(10822);

    private GameObject getTree() {
        // Retrieve all wall objects
        List<GameObject> gameObjects = Rs2GameObject.getGameObjects();

        // Filter based on distance
        List<GameObject> nearbyTrees = gameObjects.stream()
                .filter(x -> x.getWorldLocation().distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) < 40)
                .collect(Collectors.toList());

        // Filter based on ID
        List<GameObject> idFilteredTrees = nearbyTrees.stream()
                .filter(x -> CUTTABLE_TREE_IDS.contains(x.getId()))
                .collect(Collectors.toList());

        // Filter based on location
        List<GameObject> locationFilteredTrees = idFilteredTrees.stream()
                .filter(x -> CUTTABLE_TREE_LOCS.stream().anyMatch(wallLoc -> x.getWorldLocation().equals(wallLoc)))
                .collect(Collectors.toList());

        List<Player> players = Microbot.getClient().getPlayers();
        // Filter out trees that are beside another player
        List<GameObject> playerFilteredTrees = locationFilteredTrees.stream()
                .filter(x -> {
                    for (var p : players) if (p.getWorldLocation().distanceTo(x.getWorldLocation()) > 2) return true;
                    return true;
                }).collect(Collectors.toList());

        if (playerFilteredTrees.size() == 0) {
            debug("Couldn't find a tree that had no players chopping it, so picking a random tree");
            playerFilteredTrees = locationFilteredTrees;
        }

        // Sort the trees
        List<GameObject> orderedTrees = playerFilteredTrees.stream()
                .sorted(Comparator.comparingInt(x -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(x.getWorldLocation())))
                .collect(Collectors.toList());

        // Retrieve either the first or second tree
        if (orderedTrees.isEmpty()) {
            return null;
        } else if (random(0, 2) == 0 && orderedTrees.size() > 1) {
            return orderedTrees.get(1);
        } else {
            return orderedTrees.get(0);
        }
    }
    private void cutTree() {
        debug("Cut tree looking for a tree...");
        GameObject tree = getTree();

        if (tree == null) {
            debug("Found no tree!");
            return;
        }

        if (Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(tree.getWorldLocation()) > 10) {
            debug("Walking closer to tree..");
            Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), fuzz(tree.getWorldLocation())));
            sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(tree.getWorldLocation()) < 8);
        }

        if (!Rs2GameObject.interact(tree)) {
            debug("Failed to click tree!? Sleeping 1-30s then trying again...");
            sleep(random(1000,30000));
            return;
        }
        debug("Clicked tree. Sleep time zzz..");
        sleepUntil(() -> Inventory.isFull() || Rs2GameObject.getGameObjects()
                .stream()
                .filter(x -> x.getWorldLocation().equals(tree.getWorldLocation()))
                .filter(x -> CUTTABLE_TREE_IDS.contains(x.getId()))
                .findFirst().orElse(null) == null, 300_000);

        if (random(0, 2) != 0) {
            debug("Anti-ban: Sleeping another 2-45s (66% chance)");
            sleep(random(2000, 45000));
        }
        else sleep(random(200, 1500));
    }
}