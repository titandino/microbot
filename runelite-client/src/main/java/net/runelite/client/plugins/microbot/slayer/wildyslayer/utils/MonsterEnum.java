package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;

import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@AllArgsConstructor
@Getter
public enum MonsterEnum {
    SCORPION("Scorpions", "Scorpion", false, new WorldPoint(3224, 3944, 0), false, false, false, null, null),
    HELLHOUND("Hellhounds", "Hellhound", false, new WorldPoint(3445, 10085, 0), true, true, true, new WorldPoint(3405, 10092, 0), null),
    ANKOU("Ankou", "Ankou", false, new WorldPoint(3359, 10078, 0), true, true, true, new WorldPoint(3400, 10069, 0), null),
    DUST_DEVILS("Dust Devils", "Dust Devil", false, new WorldPoint(3439, 10124, 0), true, false, true, null, "Facemask");

    private final String taskName;
    private final String npcName;
    private final boolean skip;
    private final WorldPoint location;
    private final boolean prayMelee;
    private final boolean afkable;
    private final boolean inSlayerCave;
    private final WorldPoint aggroResetSpot;
    private final String helmOverride;

    public static MonsterEnum getConfig(String name) {
        for (MonsterEnum e : MonsterEnum.values()) {
            if (e.taskName.equalsIgnoreCase(name)) return e;
        }
        debug("No monster enum for " + name);
        return null;
    }
}
