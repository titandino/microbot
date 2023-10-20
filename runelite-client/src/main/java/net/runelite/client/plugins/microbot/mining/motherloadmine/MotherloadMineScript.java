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
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static net.runelite.api.AnimationID.*;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.natepainthelper.Info.*;

@Slf4j
public class MotherloadMineScript extends Script {

    private static final Set<Integer> MINING_ANIMATION_IDS = ImmutableSet.of(
            MINING_MOTHERLODE_BRONZE, MINING_MOTHERLODE_IRON, MINING_MOTHERLODE_STEEL,
            MINING_MOTHERLODE_BLACK, MINING_MOTHERLODE_MITHRIL, MINING_MOTHERLODE_ADAMANT,
            MINING_MOTHERLODE_RUNE, MINING_MOTHERLODE_GILDED, MINING_MOTHERLODE_DRAGON,
            MINING_MOTHERLODE_DRAGON_UPGRADED, MINING_MOTHERLODE_DRAGON_OR, MINING_MOTHERLODE_DRAGON_OR_TRAILBLAZER,
            MINING_MOTHERLODE_INFERNAL, MINING_MOTHERLODE_3A, MINING_MOTHERLODE_CRYSTAL,
            MINING_MOTHERLODE_TRAILBLAZER
    );
    public static double version = 1.0;

    final int SACKID = 26688;

    public static MLMStatus status = MLMStatus.MINING;
    public static String debugOld = "Old Placeholder";
    public static String debugNew = "New Placeholder";

    public void debug(String msg) {
        debugOld = debugNew;
        debugNew = msg;
    }

    public static int getAnimation() {
        return Microbot.getClientThread().runOnClientThread(() -> Microbot.getClient().getLocalPlayer().getAnimation());
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
            if (expstarted == 0) {
                expstarted = Microbot.getClient().getSkillExperience(Skill.MINING);
                startinglevel = Microbot.getClient().getRealSkillLevel(Skill.MINING);
                timeBegan = System.currentTimeMillis();
            }
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
                    if (Inventory.hasItem(ItemID.PAYDIRT)) {
                        if (Rs2GameObject.findObjectById(ObjectID.BROKEN_STRUT) != null && Inventory.hasItem("hammer")) {
                            status = MLMStatus.FIXING_WATERWHEEL;
                        } else {
                            status = MLMStatus.DEPOSIT_HOPPER;
                        }
                    } else {
                        status = MLMStatus.BANKING;
                    }
                }
                if (random(1, 100) == 0) {
                    int sleepTime = random(50_000, 100_000);
                    debug("Antiban: Sleeping " + sleepTime + " ms");
                    sleep(sleepTime);
                    debug("Antiban: Done sleeping" + sleepTime + " ms");
                }

                switch (status) {
                    case MINING:
                        if (miningSpot == MLMMiningSpot.IDLE) {
                            findRandomMiningSpot();
                        }
                        if (walkToMiningSpot()) return; // had to walk
                        mineVein();
                        break;
                    case EMPTY_SACK:
                        while (Microbot.getVarbitValue(Varbits.SACK_NUMBER) > 10) {
                            if (Inventory.count() <= 1) {
                                Rs2GameObject.interact(SACKID);
                                debug("Grabbed stuff from sack");
                                sleepUntil(() -> Inventory.count() > 1, 10000);
                            }
                            bank();
                        }
                        emptySack = false;
                        break;
                    case FIXING_WATERWHEEL:
                        // todo - same logic as vein for better detection
                            Rs2GameObject.interact(ObjectID.BROKEN_STRUT);
                            debug("Started fixing waterwheel - sleeping until my smithing exp goes up, or for 10sec");
                            int smithingExp = Microbot.getClient().getSkillExperience(Skill.SMITHING);
                            sleepUntil(() -> Microbot.getClient().getSkillExperience(Skill.SMITHING) > smithingExp, 10000);
                        break;
                    case DEPOSIT_HOPPER:
                        if (Rs2GameObject.interact(ObjectID.HOPPER_26674)) {
                            sleepUntil(() -> !Inventory.isFull());
                            if (Microbot.getVarbitValue(Varbits.SACK_NUMBER) > 50) {
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

    private void bank() {
        if (!Rs2Bank.isOpen() && !Rs2Bank.useBank())
            return;
        sleepUntil(Rs2Bank::isOpen);
        Set<Integer> deposited = new HashSet<Integer>();
        deposited.add(ItemID.HAMMER); // Because we don't want to deposit the hammer
        for (Widget item : Inventory.getInventoryItems()) {
            if (deposited.contains(item.getItemId())) continue;
            Rs2Bank.depositAll(item.getItemId());
            deposited.add(item.getItemId());
            sleep(100, 300);
        }
        if (!Inventory.hasItem("hammer")) Rs2Bank.withdrawOne("hammer", true);
        debug("We done banking");
    }

    private void findRandomMiningSpot() {
        if (random(1, 5) != 2) {
            miningSpot = MLMMiningSpot.SOUTH;
            Collections.shuffle(miningSpot.getWorldPoint());
        } else {
            miningSpot = MLMMiningSpot.WEST_LOWER;
            Collections.shuffle(miningSpot.getWorldPoint());
        }
    }

    private boolean walkToMiningSpot() {
        WorldPoint miningWorldPoint = miningSpot.getWorldPoint().get(0);
        if (Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo2D(miningWorldPoint) > 8) {
            Microbot.getWalker().walkFastLocal(LocalPoint.fromWorld(Microbot.getClient(), miningWorldPoint));
            debug("Walked to mining spot");
            sleepUntil(() -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo2D(miningWorldPoint) < 5, 3000);
            return true;
        }
        return false;
    }

    private static final Set<Integer> MINEABLE_WALL_IDS = ImmutableSet.of(
            26661, 26662, 26663, 26664
            );
    private boolean mineVein() {
        Stream<WallObject> orderedVeins = Rs2GameObject.getWallObjects()
                .stream()
                .filter(x -> MINEABLE_WALL_IDS.contains(x.getId()))
                .sorted(Comparator.comparingInt(x -> Microbot.getClient().getLocalPlayer().getWorldLocation().distanceTo(x.getWorldLocation())))
                .filter(x -> Microbot.getWalker().canInteract(x.getWorldLocation()));

        WallObject vein = random(0, 2) == 0 ? orderedVeins.skip(1).findFirst().orElse(null) : orderedVeins.findFirst().orElse(null);

        if (vein == null) return true;

        if (!Rs2Camera.isTileOnScreen(vein) && random(0, 1) == 0) {
            debug("Looking at vein..");
            Rs2Camera.turnTo(vein);
            sleepUntil(() -> Rs2Camera.isTileOnScreen(vein));
            sleep(200);
            debug("Done looking at vein");
        }
        Rs2GameObject.interact(vein);
        debug("Started mining. Sleeping until the selected vein is gone/inv full, or 60 seconds, whichever happens first.");
        sleepUntil(() -> Inventory.isFull() || Rs2GameObject.getWallObjects()
                .stream()
                .filter(x -> x.getWorldLocation().equals(vein.getWorldLocation())
                        && MINEABLE_WALL_IDS.contains(x.getId())
                        && Microbot.getWalker().canInteract(x.getWorldLocation()))
                .findFirst().orElse(null) == null, 60000);

        debug("Sleeping another 2-15s (33% chance)");
        if (random(0, 2) != 0) sleep(random(2000, 15000));
        return false;
    }
}