package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.prayer.Prayer;

import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@AllArgsConstructor
@Getter
public enum MonsterEnum {
    SCORPION("Scorpions", "Scorpion", false, new WorldPoint(3224, 3944, 0), null, false, false, null, null),
    HELLHOUND("Hellhounds", "Hellhound", false, new WorldPoint(3445, 10085, 0), Prayer.PROTECT_MELEE, true, true, new WorldPoint(3405, 10092, 0), null),
    ANKOU("Ankou", "Ankou", false, new WorldPoint(3359, 10078, 0), Prayer.PROTECT_MELEE, true, true, new WorldPoint(3400, 10069, 0), null),
    DUST_DEVILS("Dust Devils", "Dust devil", false, new WorldPoint(3439, 10124, 0), Prayer.PROTECT_MELEE, false, true, null, "Facemask"),
    MAMMOTH("Mammoths", "Mammoth", false, new WorldPoint(3165, 3597, 0), Prayer.PROTECT_MELEE, true, false, new WorldPoint(3218, 3594, 0), null),
    CHAOS_DRUIDS("Chaos Druids", "Elder chaos druid", false, new WorldPoint(3239, 3619, 0), Prayer.PROTECT_MAGIC,true, false, new WorldPoint(3270, 3654, 0), null),
    GREATER_DEMONS("Greater Demons", "Greater demon", false, new WorldPoint(3427, 10147, 0), Prayer.PROTECT_MELEE, true, true, new WorldPoint(3407, 10095, 0), null),
    SPIDER("Spiders", "Giant spider", false, new WorldPoint(3167, 3885, 0), null, false, false, new WorldPoint(3105, 3895, 0), null),
    PIRATE("Pirates", "Pirate", true, null, null, false, false, null, null);

    private final String taskName;
    private final String npcName;
    private final boolean skip;
    private final WorldPoint location;
    private final Prayer protectionPrayer;
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
