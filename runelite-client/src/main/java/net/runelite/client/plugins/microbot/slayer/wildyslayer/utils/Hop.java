package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import net.runelite.api.Player;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.http.api.worlds.World;

import java.util.List;
import java.util.stream.Collectors;

import static net.runelite.client.plugins.microbot.slayer.wildyslayer.WildySlayerPlugin.wildySlayerRunning;
import static net.runelite.client.plugins.microbot.slayer.wildyslayer.utils.WildyWalk.*;
import static net.runelite.client.plugins.microbot.util.Global.sleep;
import static net.runelite.client.plugins.microbot.util.math.Random.random;
import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

public class Hop {
    private static final int[] worlds = new int[] {320, 323, 324, 332, 338, 339, 340, 355, 356, 357};
    public static void considerHopping() {
        List<Player> otherPlayers = Microbot.getClient().getPlayers().stream()
                .filter(p -> distTo(p.getWorldLocation()) < 30)
                .collect(Collectors.toList());
        if (otherPlayers.size() <= 1) return;
        debug("Hopping because someone else is here..");
        hopRandomWorld();
    }

    public static void hopRandomWorld() {
        if (inWildy() && !inFerox()) {
            WildyWalk.toResetAggroSpot();
        }
        if (!wildySlayerRunning) return;
        debug("Hopping...");
        sleep(4000);
        int worldNumber = worlds[random(0, worlds.length - 1)];
        Microbot.hopToWorld(worldNumber);
        sleep(10_000);
        debug("Hopefully successfully hopped!");
    }

}
