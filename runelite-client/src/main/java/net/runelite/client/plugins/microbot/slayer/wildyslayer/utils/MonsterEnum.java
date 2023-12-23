package net.runelite.client.plugins.microbot.slayer.wildyslayer.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import net.runelite.api.coords.Direction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.util.prayer.Prayer;

import static net.runelite.client.plugins.microbot.util.paintlogs.PaintLogsScript.debug;

@AllArgsConstructor
@Getter
public enum MonsterEnum {
    SCORPION("Scorpions", "Scorpion", false, new WorldPoint(3224, 3944, 0), null, false, false, null, null, Direction.EAST),
    HELLHOUND("Hellhounds", "Hellhound", false, new WorldPoint(3445, 10085, 0), Prayer.PROTECT_MELEE, true, true, new WorldPoint(3405, 10092, 0), null, Direction.EAST),
    ANKOU("Ankou", "Ankou", false, new WorldPoint(3359, 10078, 0), Prayer.PROTECT_MELEE, true, true, new WorldPoint(3400, 10069, 0), null, Direction.EAST),
    DUST_DEVILS("Dust Devils", "Dust devil", false, new WorldPoint(3439, 10124, 0), Prayer.PROTECT_MELEE, false, true, null, "Facemask", Direction.EAST),
    MAMMOTH("Mammoths", "Mammoth", false, new WorldPoint(3165, 3597, 0), Prayer.PROTECT_MELEE, true, false, new WorldPoint(3218, 3594, 0), null, Direction.EAST),
    CHAOS_DRUIDS("Chaos Druids", "Elder chaos druid", false, new WorldPoint(3239, 3619, 0), Prayer.PROTECT_MAGIC,true, false, new WorldPoint(3270, 3654, 0), null, Direction.EAST),
    GREATER_DEMONS("Greater Demons", "Greater demon", false, new WorldPoint(3427, 10147, 0), Prayer.PROTECT_MELEE, true, true, new WorldPoint(3407, 10095, 0), null, Direction.EAST),
    SPIDER("Spiders", "Giant spider", false, new WorldPoint(3167, 3885, 0), null, false, false, new WorldPoint(3105, 3895, 0), null, Direction.EAST),
    ENTS("Ents", "Ent", false, new WorldPoint(3304, 3605, 0), Prayer.PROTECT_MELEE, false, false, new WorldPoint(3239, 3586, 0), null, Direction.EAST),
    ICE_WARRIOR("Ice Warriors", "Ice warrior", false, new WorldPoint(2950, 3869, 0), Prayer.PROTECT_MELEE, false, false, new WorldPoint(2962, 3828, 0), null, Direction.WEST),
    BLACK_DEMONS("Black Demons", "Black demon", false, new WorldPoint(3363, 10120, 0), Prayer.PROTECT_MELEE, true, true, new WorldPoint(3382, 10077, 0), null, Direction.EAST),
    SKELETONS("Skeletons", "Skeleton", false, new WorldPoint(3258, 3735, 0), null, false, false, new WorldPoint(3265, 3652, 0), null, Direction.EAST),
    JELLIES("Jellies", "Jelly", false, new WorldPoint(3431, 10102, 0), Prayer.PROTECT_MELEE, false, true, new WorldPoint(3400, 10065, 0), null, Direction.EAST),
    PIRATE("Pirates", "Pirate", true, null, null, false, false, null, null, Direction.EAST),
    MAGIC_AXES("Magic Axes", "Magic Axe", true, null, null, false, false, null, null, Direction.EAST),
    ROGUES("Rogues", "Rogue", true, null, null, false, false, null, null, Direction.EAST);

    private final String taskName;
    private final String npcName;
    private final boolean skip;
    private final WorldPoint location;
    private final Prayer protectionPrayer;
    private final boolean afkable;
    private final boolean inSlayerCave;
    private final WorldPoint aggroResetSpot;
    private final String helmOverride;
    private final Direction feroxExitDir;

    public static MonsterEnum getConfig(String name) {
        for (MonsterEnum e : MonsterEnum.values()) {
            if (e.taskName.equalsIgnoreCase(name)) return e;
        }
        debug("No monster enum for " + name);
        return null;
    }
}
