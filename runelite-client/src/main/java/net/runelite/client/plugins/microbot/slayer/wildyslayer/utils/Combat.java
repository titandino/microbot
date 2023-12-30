package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.NPC;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.Hop.considerHopping;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.isNorthOfGate;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.Global.sleepUntil;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Combat {

    public static MonsterEnum task() {
        return WildySlayerPlugin.Instance.wildySlayerScript.task();
    }

    public static void handleFight() {
        debug("Should be fighting!");
        if (task().getProtectionPrayer() != null) Rs2Prayer.fastPray(task().getProtectionPrayer(), true);

        Loot.getLoot(false);
        considerHopping();
        if (task().isAfkable()) {
            handleAfkFight();
        } else {
            handleInteractiveFight();
        }
    }

    private static int distToAttack() {
        if (task() == MonsterEnum.ENTS) return 20;
        return 10;
    }

    private static NPC getNPCToAttack() {
        List<NPC> options = Arrays.stream(Rs2Npc.getNpcs())
                .filter(n -> n.getName().equalsIgnoreCase(task().getNpcName()))
                .filter(n -> !n.isDead())
                .filter(n -> n.getInteracting() == null || n.getInteracting() == Microbot.getClient().getLocalPlayer())
                .filter(n -> distTo(n.getWorldLocation()) < distToAttack())
                .filter(n -> isNorthOfGate(n.getWorldLocation()) && isNorthOfGate(Microbot.getClient().getLocalPlayer().getWorldLocation())
                        || !isNorthOfGate(n.getWorldLocation()) && !isNorthOfGate(Microbot.getClient().getLocalPlayer().getWorldLocation()))
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
            debug("No xp in 15s, attacking a new NPC");
            prevUpdateTime = System.currentTimeMillis();
            attackNpc();
        }
    }

    private static void attackNpc() {
        if (getNPCToAttack() == null) {
            prevTasksRemaining = prevTasksRemaining + 1; // Force the bot to try and attack an NPC immediately next loop
            if (task() == MonsterEnum.ENTS) {
                debug("Found no NPCs to attack, handling Ents case");
                if (distTo(3300, 3590) > 5) {
                    Microbot.getWalker().walkTo(new WorldPoint(3300, 3590, 0));
                    sleepUntil(() -> getNPCToAttack() != null);
                } else {
                    Microbot.getWalker().walkTo(new WorldPoint(3305, 3608, 0));
                    sleepUntil(() -> getNPCToAttack() != null);
                }
            } else {
                debug("Found no NPCs to attack, getting closer to center of task..");
                WildyWalk.toSlayerLocation(task().getTaskName());
                sleep(random(5000, 7000));
            }
        }
        Rs2Npc.interact(getNPCToAttack(), "Attack");
    }

    private static void handleAfkFight() {
        if (prevExp != Microbot.getClient().getOverallExperience()) prevUpdateTime = System.currentTimeMillis();
        if (Microbot.getClient().getLocalPlayer().getHealthScale() != -1) prevUpdateTime = System.currentTimeMillis();
        prevExp = Microbot.getClient().getOverallExperience();
        if (prevUpdateTime + 10_000 < System.currentTimeMillis()) {
            Loot.getLoot(true);
            debug("Haven't gotten exp in at least 10 seconds! Going to try and re-trigger aggression...");
            WildyWalk.toResetAggroSpot();
            prevUpdateTime = System.currentTimeMillis() + 60_000; // Give it some time to start up again
        }
    }

}
