package net.runelite.client.plugins.microbot.herblore.fremsnape;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.models.RS2Item;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.mining.uppermotherload.UpperMotherloadScript.fuzz;
import static net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem.interact;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@Slf4j
public class FremSnapeScript extends Script {

    public int snapeLooted = 0;

    public boolean run() {
        Microbot.enableAutoRunOn = true;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (!Microbot.isLoggedIn()) return;
            try {
                if (random(0, 900) == 0) {
                    int sleepTime = random(50_000, 100_000);
                    debug("Anti-ban: Sleeping " + sleepTime + " ms");
                    sleep(sleepTime);
                    debug("Anti-ban: Done sleeping " + sleepTime + "ms");
                }

                if (isInRellekka() && Inventory.isFull())
                    depositSnape();
                else if (isInRellekka())
                    travelWaterbirth();
                else if (Inventory.isFull())
                    travelRellekka();
                else
                    findSnape();
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        return true;
    }

    private void findSnape() {
        debug("Getting snape..");

        RS2Item[] groundItems = Microbot.getClientThread().runOnClientThread(() ->
                Rs2GroundItem.getAll(8)
        );
        for (RS2Item rs2Item : groundItems) {
            if (rs2Item.getItem().getName().equals("Snape grass") &&
                    (rs2Item.getTile().getWorldLocation().getY() == 3765 || rs2Item.getTile().getWorldLocation().getY() == 3763)) {
                debug("Interacting with the snape " + rs2Item.getTile().getWorldLocation().getX() + "...");
                interact(rs2Item);
                long invCount = Inventory.count();
                sleepUntil(() -> Inventory.count() > invCount, 10000);
                if (Inventory.count() > invCount) {
                    snapeLooted += 1;
                    PaintLogsScript.status = "Snape grass looted: " + snapeLooted;
                }
                return;
            }
        }

        debug("Found no snape..");
        sleep(random(1000, 4000));
    }

    private void travelRellekka() {
        debug("Traveling to Rellekka..");
        Rs2Npc.interact("Jarvald", "Rellekka");
        sleepUntil(() -> !isInRellekka());
        sleep(5000);
    }

    private void travelWaterbirth() {
        debug("Traveling Waterbirth");
        Microbot.getWalker().walkTo(fuzz(new WorldPoint(2630, 3676, 0)));
        sleepUntil(() -> new WorldPoint(2630, 3676, 0).distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) < 6);
        Rs2Npc.interact("Jarvald", "Waterbirth Island");
        sleepUntil(() -> !isInRellekka());
        sleep(5000);
    }

    private boolean isInRellekka() {
        return Rs2Npc.getNpc("Yrsa") != null;
    }

    private void depositSnape() {
        debug("Depositing snape grass..");
        Microbot.getWalker().walkTo(fuzz(new WorldPoint(2630, 3676, 0)));
        sleepUntil(() -> new WorldPoint(2630, 3676, 0).distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) < 6);
        Rs2Npc.interact("Peer the Seer", "Deposit-items");
        debug("Sleeping until widget 12582916 isn't null");
        sleepUntil(() -> Rs2Widget.getWidget(12582916) != null, 20000);
        Rs2Widget.clickWidget(12582916);
    }

}