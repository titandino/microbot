package net.runelite.client.plugins.microbot.playerassist.combat;

import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.playerassist.PlayerAssistConfig;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class AttackNpcScript extends Script {

    String[] configAttackableNpcs;

    public void run(PlayerAssistConfig config) {
        String npcToAttack = Arrays.stream(Arrays.stream(config.attackableNpcs().split(",")).map(String::trim).toArray(String[]::new)).findFirst().get();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!super.run()) return;
                if (!config.toggleCombat()) return;
                if (Microbot.getClient().getLocalPlayer().isInteracting() || Microbot.getClient().getLocalPlayer().getAnimation() != -1) {
                    return;
                }
                NPC npc = getNpc(npcToAttack, config.attackRadius());
                if (npc == null) return;
                Rs2Npc.interact(npc, "attack");
                debug("Attacking " + npc.getName());
                sleepUntil(() -> Microbot.getClient().getLocalPlayer().isInteracting() && Microbot.getClient().getLocalPlayer().getInteracting() instanceof NPC);
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 6000, TimeUnit.MILLISECONDS);
    }

    public NPC getNpc(String npcToAttack, int attackRadius) {
        var attackableNpcs =  Microbot.getClient().getNpcs().stream()
                .sorted(Comparator.comparingInt(value -> value.getLocalLocation().distanceTo(Microbot.getClient().getLocalPlayer().getLocalLocation())))
                .filter(x -> !x.isDead()
                        && x.getWorldLocation().distanceTo(Microbot.getClient().getLocalPlayer().getWorldLocation()) < attackRadius
                        && !x.isInteracting()
                        && x.getAnimation() == -1
                        && npcToAttack.equalsIgnoreCase(x.getName())
                ).collect(Collectors.toList());

        if (attackableNpcs.isEmpty()) {
            return null;
        } else if (random(0, 2) == 0 && attackableNpcs.size() > 1) {
            return attackableNpcs.get(1);
        } else {
            return attackableNpcs.get(0);
        }
    }

    public void shutdown() {
        super.shutdown();
        configAttackableNpcs = null;
    }
}