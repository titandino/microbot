package net.runelite.client.plugins.microbot.bruteforcesummer.uppermotherload;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;

import java.util.*;
import java.util.concurrent.TimeUnit;

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
                    Microbot.getNotifier().notify("[ATTENTION] Inventory full!");
                    sleep(20000);
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
                    Rs2GameObject.interact(getGate());
                    sleepUntil(() -> getTree() != null, 10000);
                }
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }

    private GameObject getTree() {
        return Rs2GameObject.getGameObjects().stream()
                .filter(x -> x.getId() == ObjectID.SQIRK_TREE)
                .filter(x -> Microbot.getWalker().canInteract(x.getWorldLocation()))
                .findFirst().orElse(null);
    }

    private WallObject getGate() {
        return Rs2GameObject.getWallObjects().stream()
                .filter(x -> x.getId() == 11987 && x.getWorldLocation().getX() == 2910 && x.getWorldLocation().getY() == 5480)
                .findFirst().orElse(null);
    }
}