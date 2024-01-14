package net.runelite.client.plugins.microbot.museum;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Player;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;
import static net.runelite.client.plugins.microbot.util.time.RuntimeCalc.prettyRunTime;

@Slf4j
public class MuseumScript extends Script {

    public int lampsRubbed = 0;
    public int timesHopped = 0;
    public int rewardsClaimed = 0;
    public long startTime = 0;

    public boolean run() {
        Microbot.enableAutoRunOn = true;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            try {
                PaintLogsScript.status = "Rewards claimed: " + rewardsClaimed + " - Lamps acquired: " + lampsRubbed + " - Times hopped: " + timesHopped + " - Runtime: " + prettyRunTime(startTime);
                if (random(0, 100) == 0) {
                    int sleepTime = random(50_000, 100_000);
                    debug("Anti-ban: Sleeping " + sleepTime + " ms");
                    sleep(sleepTime);
                    debug("Anti-ban: Done sleeping " + sleepTime + "ms");
                }

                considerHopping();
                if (Inventory.isFull() && Inventory.contains("Uncleaned find")) cleanFinds();
                else if (inventoryHasLamp()) useLamp();
                else if (inventoryHasFinds()) putFindsInCrate();
                else if (inventoryHasCrateRewards()) dropCrateRewards();
                else if (!Inventory.isFull()) {
                    getUncleanedFinds();
                } else {
                    afk();
                }
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private void useLamp() {
        long invLamps = Inventory.getAmountForItem("Antique lamp");
        Inventory.interact("Antique lamp");
        sleepUntil(() -> Rs2Widget.hasWidget("Choose the stat you wish to to be advanced!"));
        if (!Rs2Widget.hasWidget("Choose the stat you wish to be advanced!")) {
            debug("Failed to rub the lamp!");
            return;
        }
        Rs2Widget.clickWidget(15728654);
        sleepUntil(() -> Rs2Widget.hasWidget("Confirm: Slayer"));
        if (!Rs2Widget.hasWidget("Confirm: Slayer")) {
            debug("Failed to click Slayer icon!");
            return;
        }
        Rs2Widget.clickWidget("Confirm: Slayer");
        sleepUntil(() -> Inventory.getAmountForItem("Antique lamp") < invLamps);
        lampsRubbed += 1;
    }

    private void afk() {
        debug("I dunno what to do! Sleeping...");
        sleep(50_000);
    }

    private void getUncleanedFinds() {
        debug("Getting finds...");
        int clicks = 0;
        while (!Inventory.isFull() && clicks < 50) {
            clicks++;
            Rs2GameObject.interact("Dig Site specimen rocks");
            sleep(100, 250);
        }
    }

    private void putFindsInCrate() {
        debug("Putting finds in crate...");
        Rs2GameObject.interact("Storage crate");
        sleepUntil(() -> Rs2Widget.hasWidget("Yes, place all my finds in the crate."), 15_000);
        debug("Clicking widget that says I want to put my finds in the crate...");
        Rs2Widget.clickWidget("Yes, place all my finds in the crate.");
        sleepUntil(() -> !inventoryHasFinds(), 30_000);
    }

    private void cleanFinds() {
        debug("Cleaning finds...");
        Rs2GameObject.interact("Specimen table");
        sleepUntil(() -> !Inventory.contains("Uncleaned find"), 120_000);
    }

    private void dropCrateRewards() {
        while (Inventory.contains("Bones")) {
            debug("Burying bones..");
            Inventory.interact("Bones");
            sleep(600, 1200);
        }
        while (Inventory.contains("Big bones")) {
            debug("Burying big bones..");
            Inventory.interact("Big bones");
            sleep(600, 1200);
        }
        debug("Dropping crate rewards...");
        Inventory.dropAll(new String[]{"Bones", "Uncut jade", "Coins", "Bowl", "Pot", "Iron bolts", "Iron dagger", "Bronze limbs", "Wooden stock", "Tin ore", "Copper ore", "Iron ore", "Coal", "Mithril ore", "Iron knife", "Iron dart", "Iron arrowtips", "Uncut opal", "Broken glass", "Broken arrow"});
        debug("Done dropping crate rewards..");
    }

    private boolean inventoryHasLamp() {
        return Inventory.contains("Antique lamp");
    }

    private boolean inventoryHasCrateRewards() {
        return Inventory.contains("Coins") || Inventory.contains("Bowl") || Inventory.contains("Pot") || Inventory.contains("Iron bolts") || Inventory.contains("Bronze limbs") || Inventory.contains("Wooden stock") || Inventory.contains("Tin ore") || Inventory.contains("Copper ore") || Inventory.contains("Iron ore") || Inventory.contains("Coal") || Inventory.contains("Mithril ore") || Inventory.contains("Iron knife") || Inventory.contains("Iron dart") || Inventory.contains("Iron arrowtips") || Inventory.contains("Uncut opal") || Inventory.contains("Big bones");
    }

    private boolean inventoryHasFinds() {
        return Inventory.contains("Pottery") || Inventory.contains("Jewellery") || Inventory.contains("Old chipped vase") || Inventory.contains("Arrowheads");
    }

    private static final int[] worlds = new int[] {320, 323, 324, 332, 338, 339, 340, 355, 356, 357};
    public void considerHopping() {
        List<Player> otherPlayers = Microbot.getClient().getPlayers().stream()
                .filter(p -> distTo(p.getWorldLocation()) < 30)
                .collect(Collectors.toList());
        if (otherPlayers.size() <= 1) return;
        debug("Hopping because someone else is here..");
        hopRandomWorld();
    }

    public void hopRandomWorld() {
        debug("Hopping...");
        Microbot.getWalker().walkTo(Microbot.getClient().getLocalPlayer().getWorldLocation());
        sleep(1500);
        debug("Walked to close interface...");
        int worldNumber = worlds[random(0, worlds.length - 1)];
        Microbot.hopToWorld(worldNumber);
        debug("Called hop to world");
        sleep(10_000);
        debug("Hopefully successfully hopped!");
        timesHopped += 1;
    }

}