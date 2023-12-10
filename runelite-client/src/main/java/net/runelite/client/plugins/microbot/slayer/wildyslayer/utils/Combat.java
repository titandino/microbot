package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.NPC;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.distTo;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Combat {

    private static MonsterEnum task() {
        return WildySlayerPlugin.Instance.wildySlayerScript.task();
    }
    public static void handleFight() {
        if (task().isPrayMelee()) Rs2Prayer.turnOnMeleePrayer();
        Rs2Prayer.turnOffMeleePrayer();

        if (task().isAfkable()) {
            handleAfkFight();
        } else {
            handleInteractiveFight();
        }
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
        // if the remaining tasks has gone down, or we haven't gotten exp in 10 seconds, attack a new NPC
        if (prevTasksRemaining != WildySlayerPlugin.Instance.wildySlayerScript.slayerPlugin.getAmount()) {
            debug("Tasks remaining decreased, attacking a new NPC");
            prevTasksRemaining = WildySlayerPlugin.Instance.wildySlayerScript.slayerPlugin.getAmount();
            Rs2Npc.interact(getNPCToAttack(), "Attack");
            return;
        }
        if (prevUpdateTime + 10_000 < System.currentTimeMillis()) {
            debug("No xp in 10s, attacking a new NPC");
            prevUpdateTime = System.currentTimeMillis();
            Rs2Npc.interact(getNPCToAttack(), "Attack");
        }
    }

    private static void handleAfkFight() {
        if (prevExp != Microbot.getClient().getOverallExperience()) prevUpdateTime = System.currentTimeMillis();
        prevExp = Microbot.getClient().getOverallExperience();
        if (prevUpdateTime + 10_000 < System.currentTimeMillis()) {
            debug("Haven't gotten exp in at least 10 seconds! Going to try and re-trigger aggression...");
            WildyWalk.resetAggro();
        }
    }

}
