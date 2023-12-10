package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@AllArgsConstructor
@Getter
public enum MonsterEnum {
    SCORPION("Scorpions", "Scorpion", false, new WorldPoint(3224, 3944, 0), false, false);

    private final String name;
    private final String npcName;
    private final boolean skip;
    private final WorldPoint location;
    private final boolean prayMelee;
    private final boolean afkable;

    public static MonsterEnum getConfig(String name) {
        for (MonsterEnum e : MonsterEnum.values()) {
            if (e.name.equalsIgnoreCase(name)) return e;
        }
        debug("No monster enum for " + name);
        return null;
    }
}
