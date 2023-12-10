package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Inventory;
import net.runelite.client.plugins.microbot.util.models.RS2Item;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.prayer.Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Combat {

    private static final Set<String> lootItems = Set.of("Larran's key", "Blighted super restore(4)");
    public static MonsterEnum task() {
        return WildySlayerPlugin.Instance.wildySlayerScript.task();
    }

    public static void handleFight() {
        Rs2Prayer.fastPray(Prayer.PROTECT_MELEE, task().isPrayMelee());

        debug("Checking loot..");
        getLoot();
        debug("Considering world hop..");
        considerHopping();
        debug("Handling afk or interactive fight..");
        if (task().isAfkable()) {
            handleAfkFight();
        } else {
            handleInteractiveFight();
        }
    }

    private static final int[] worlds = new int[] {320, 323, 324, 332, 338, 339, 340, 355, 356, 357};
    private static void considerHopping() {
        List<Player> otherPlayers = Microbot.getClient().getPlayers().stream()
                .filter(p -> distTo(p.getWorldLocation()) < 30)
                .collect(Collectors.toList());
        if (otherPlayers.size() <= 1) return;
        debug("Hopping because someone else is here..");
        WildyWalk.toResetAggroSpot();
        debug("Hopping...");
        sleep(4000);
        Microbot.hopToWorld(worlds[random(0, worlds.length - 1)]);
        sleep(10_000);
        debug("Hopefully successfully hopped!");
    }

    private static void getLoot() {
        List<RS2Item> itemsToLoot = Arrays.stream(Rs2GroundItem.getAll(4))
                .filter(x -> x.getItem().getHaPrice() > 9000 || lootItems.contains(x.getItem().getName()))
                .collect(Collectors.toList());
        if (random(0, 5) != 0) {
            debug("There's loot, but skipping it (antiban)");
            return;
        }
        debug("Looting items!");
        for (RS2Item item : itemsToLoot) {
            long count = Inventory.count();
            Rs2GroundItem.interact(item);
            sleepUntil(() -> Inventory.count() > count);
        }
        debug("Done looting");
    }

    private static NPC getNPCToAttack() {
        List<NPC> options = Arrays.stream(Rs2Npc.getNpcs())
                .filter(n -> n.getName().equalsIgnoreCase(task().getNpcName()))
                .filter(n -> !n.isDead())
                .filter(n -> n.getInteracting() == null)
                .filter(n -> distTo(n.getWorldLocation()) < 10)
                .sorted(Comparator.comparingInt(x -> distTo(x.getWorldLocation())))
                .collect(Collectors.toList());

        // Retrieve either the first or second npc
        if (options.isEmpty()) {
            return null;
        } else if (random(0, 2) == 0 && options.size() > 1) {
            return options.get(1);
        } else {
            return options.get(0);
        }
    }

    private static int prevTasksRemaining = -1;
    private static long prevExp = 0;
    private static long prevUpdateTime = 0;
    private static void handleInteractiveFight() {
        if (prevExp != Microbot.getClient().getOverallExperience()) prevUpdateTime = System.currentTimeMillis();
        prevExp = Microbot.getClient().getOverallExperience();
        // if the remaining tasks has gone down, or we haven't gotten exp in 15 seconds, attack a new NPC
        if (prevTasksRemaining != WildySlayerPlugin.Instance.wildySlayerScript.slayerPlugin.getAmount()) {
            debug("Tasks remaining decreased, attacking a new NPC");
            prevTasksRemaining = WildySlayerPlugin.Instance.wildySlayerScript.slayerPlugin.getAmount();
            attackNpc();
            return;
        }
        if (prevUpdateTime + 15_000 < System.currentTimeMillis()) {
            debug("No xp in 10s, attacking a new NPC");
            prevUpdateTime = System.currentTimeMillis();
            attackNpc();
        }
    }

    private static void attackNpc() {
        if (getNPCToAttack() == null) {
            debug("Found no NPCs to attack, getting closer to center of task..");
            WildyWalk.toSlayerLocation(task().getTaskName());
            sleep(5000);
        }
        Rs2Npc.interact(getNPCToAttack(), "Attack");
    }

    private static void handleAfkFight() {
        if (prevExp != Microbot.getClient().getOverallExperience()) prevUpdateTime = System.currentTimeMillis();
        if (Microbot.getClient().getLocalPlayer().getHealthScale() != -1) prevUpdateTime = System.currentTimeMillis();
        prevExp = Microbot.getClient().getOverallExperience();
        if (prevUpdateTime + 10_000 < System.currentTimeMillis()) {
            debug("Haven't gotten exp in at least 10 seconds! Going to try and re-trigger aggression...");
            WildyWalk.toResetAggroSpot();
            prevUpdateTime = System.currentTimeMillis() + 20_000; // Give it some time to start up again
        }
    }

}
