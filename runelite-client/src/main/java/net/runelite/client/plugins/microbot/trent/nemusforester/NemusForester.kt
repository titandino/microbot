package net.runelite.client.plugins.microbot.trent.nemusforester

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.Skill
import net.runelite.api.coords.WorldPoint
import net.runelite.api.events.AnimationChanged
import net.runelite.api.events.ChatMessage
import net.runelite.api.gameval.ItemID
import net.runelite.api.gameval.NpcID
import net.runelite.api.gameval.ObjectID
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.bankAt
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.skills.fletching.Rs2Fletching
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import java.util.function.Predicate
import javax.inject.Inject

// ==========================================================================================
// Locations
// ==========================================================================================

private val TREE_TILE = WorldPoint(1359, 3307, 0)

private val TREE_ADJ_TILES = arrayOf(
    WorldPoint(1359, 3307, 0),
    WorldPoint(1355, 3303, 0)
)

private val TOTEM_TILE = WorldPoint(1346, 3319, 0)

private val BANK_TILE = WorldPoint(1386, 3309, 0)

// ==========================================================================================
// Thresholds
// ==========================================================================================

private const val BANK_LOGS_THRESHOLD = 24

private const val FLETCH_MIN_LOGS = 10

private const val FORESTRY_SCAN_RADIUS = 15

// ==========================================================================================
// Items
// ==========================================================================================

private const val YEW_LOG_ID = ItemID.YEW_LOGS

private const val KNIFE_ID = ItemID.KNIFE

private const val YEW_SHORTBOW_U_ID = ItemID.UNSTRUNG_YEW_SHORTBOW

private const val YEW_LONGBOW_U_ID = ItemID.UNSTRUNG_YEW_LONGBOW

private const val COINS_ID = ItemID.COINS

// Vale Offerings (ripe sack loot). Symbol names use ENT_TOTEMS_LOOT / LOOT02 / LOOT03 / LOOT04.
private val VALE_OFFERING_IDS = intArrayOf(
    ItemID.ENT_TOTEMS_LOOT,
    ItemID.ENT_TOTEMS_LOOT02,
    ItemID.ENT_TOTEMS_LOOT03,
    ItemID.ENT_TOTEMS_LOOT04
)

// ==========================================================================================
// Totem Objects (Yew Totem Site + Offering sack variants)
// ==========================================================================================

private const val ENT_TOTEMS_BASE_NONE = ObjectID.ENT_TOTEMS_BASE_NONE

private const val ENT_TOTEMS_BASE_YEW = ObjectID.ENT_TOTEMS_BASE_YEW

private const val ENT_TOTEMS_CARVED_BASE_YEW = ObjectID.ENT_TOTEMS_CARVED_BASE_YEW

private const val ENT_TOTEMS_LOW_YEW = ObjectID.ENT_TOTEMS_LOW_YEW

private const val ENT_TOTEMS_MID_YEW = ObjectID.ENT_TOTEMS_MID_YEW

private const val ENT_TOTEMS_TOP_YEW = ObjectID.ENT_TOTEMS_TOP_YEW

@Suppress("unused")
private const val ENT_TOTEMS_OFFERINGS_NONE = ObjectID.ENT_TOTEMS_OFFERINGS_NONE

// Offering sacks A..D — any one of these appearing above the totem means a ripe sack is claimable.
private val ENT_TOTEMS_OFFERING_FULL_IDS = intArrayOf(
    ObjectID.ENT_TOTEMS_OFFERINGS_A,
    ObjectID.ENT_TOTEMS_OFFERINGS_B,
    ObjectID.ENT_TOTEMS_OFFERINGS_C,
    ObjectID.ENT_TOTEMS_OFFERINGS_D
)

// ==========================================================================================
// Forestry Event IDs
// ==========================================================================================

// NPCs (GATHERING_EVENT_* from NpcID.java) — beekeeper, sapling NPCs, flowering-bush bees,
// ritual dryads & nodes, pheasant forester & pheasant, poachers (indoor/outdoor), fox,
// ent stumps (NPC variant), entlings, woodcutting leprechaun.
private val FORESTRY_NPC_IDS = intArrayOf(
    // Flowering Tree event bees
    NpcID.GATHERING_EVENT_FLOWERING_TREE_BEES,
    // Leprechaun (woodcutting event)
    NpcID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN,
    // Sapling NPC HP-bars
    NpcID.GATHERING_EVENT_SAPLING_NPC_HPBAR_1X1,
    NpcID.GATHERING_EVENT_SAPLING_NPC_HPBAR_2X2,
    // Beehive event
    NpcID.GATHERING_EVENT_BEES_BEEKEEPER,
    NpcID.GATHERING_EVENT_BEES_BEEBOX_1,
    NpcID.GATHERING_EVENT_BEES_BEEBOX_2,
    NpcID.GATHERING_EVENT_BEES_BEEBOX_3,
    NpcID.GATHERING_EVENT_BEES_BEEBOX_4,
    // Enchanted ritual dryad
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_DRYAD,
    // Enchanted ritual nodes (A/B/C/D sets)
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_1,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_2,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_3,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_A_4,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_B_1,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_B_2,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_B_3,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_B_4,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_C_1,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_C_2,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_C_3,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_C_4,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_1,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_2,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_3,
    NpcID.GATHERING_EVENT_ENCHANTED_RITUAL_D_4,
    // Pheasant chase
    NpcID.GATHERING_EVENT_PHEASANT_FORESTER,
    NpcID.GATHERING_EVENT_PHEASANT,
    // Poacher trap & poachers (outdoor/indoor)
    NpcID.GATHERING_EVENT_POACHERS_TRAP,
    NpcID.GATHERING_EVENT_POACHERS_POACHER01_OUTDOORS,
    NpcID.GATHERING_EVENT_POACHERS_POACHER02_OUTDOORS,
    NpcID.GATHERING_EVENT_POACHERS_POACHER03_OUTDOORS,
    NpcID.GATHERING_EVENT_POACHERS_POACHER01_INDOORS,
    NpcID.GATHERING_EVENT_POACHERS_POACHER02_INDOORS,
    NpcID.GATHERING_EVENT_POACHERS_POACHER03_INDOORS,
    NpcID.GATHERING_EVENT_POACHERS_FOX_OUTDOORS,
    NpcID.GATHERING_EVENT_POACHERS_FOX_INDOORS
    // NOTE: Intentionally NOT listed here (deferred pending proper event gating):
    //   - GATHERING_EVENT_ENTLINGS_NPC_01 (12543) / _01_PRUNED (12544)
    //   - GATHERING_EVENT_ENT_STUMP_01 (12545) / _02 (12546)
    // These NPC IDs overlap with permanent ambient NPCs around the Ent Totem site at
    // Nemus Retreat. They sit ~13 tiles from TREE_TILE — inside FORESTRY_SCAN_RADIUS (15) —
    // so including them caused detectForestryEvent() to return true every tick, diverting
    // the Root dispatcher into ForestryEvent and starving Woodcut. Re-add them only when
    // properly gated by co-presence of an actionable event object (e.g. a nearby
    // GATHERING_EVENT_ENT_STUMP_* OBJECT ID confirming the Friendly Ent event is live).
)

// Objects (GATHERING_EVENT_* from ObjectID1.java) — rising roots, sapling sizes & states,
// beehive, leprechaun rainbow/clover, pheasant nests.
private val FORESTRY_OBJ_IDS = intArrayOf(
    // Rising roots
    ObjectID.GATHERING_EVENT_RISING_ROOTS,
    ObjectID.GATHERING_EVENT_RISING_ROOTS_SPECIAL,
    // Struggling sapling (1x1..4x4, withering, saved)
    ObjectID.GATHERING_EVENT_SAPLING_1X1,
    ObjectID.GATHERING_EVENT_SAPLING_WITHERING_1X1,
    ObjectID.GATHERING_EVENT_SAPLING_SAVED_1X1,
    ObjectID.GATHERING_EVENT_SAPLING_2X2,
    ObjectID.GATHERING_EVENT_SAPLING_WITHERING_2X2,
    ObjectID.GATHERING_EVENT_SAPLING_SAVED_2X2,
    ObjectID.GATHERING_EVENT_SAPLING_3X3,
    ObjectID.GATHERING_EVENT_SAPLING_WITHERING_3X3,
    ObjectID.GATHERING_EVENT_SAPLING_SAVED_3X3,
    ObjectID.GATHERING_EVENT_SAPLING_4X4,
    ObjectID.GATHERING_EVENT_SAPLING_WITHERING_4X4,
    ObjectID.GATHERING_EVENT_SAPLING_SAVED_4X4,
    // Sapling ingredients
    ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_1,
    ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_2,
    ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_3,
    ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4A,
    ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4B,
    ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_4C,
    ObjectID.GATHERING_EVENT_SAPLING_INGREDIENT_5,
    // Beehive
    ObjectID.GATHERING_EVENT_BEES_BEEHIVE01,
    // Leprechaun objects
    ObjectID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN_RAINBOW,
    ObjectID.GATHERING_EVENT_WOODCUTTING_LEPRECHAUN_CLOVER,
    // Pheasant nests
    ObjectID.GATHERING_EVENT_PHEASANT_NEST01,
    ObjectID.GATHERING_EVENT_PHEASANT_NEST02
)

// ==========================================================================================
// Keep-in-inventory set
// ==========================================================================================

// Items that must NEVER be deposited during banking.
// Coins + knife + unstrung yew bows (in-progress fletch) + all Vale Offering variants.
// Hatchet name-match is handled in the Bank state (name-based, not ID-based, to tolerate tier swaps).
private val KEEP_ITEM_IDS: IntArray = intArrayOf(
    COINS_ID,
    KNIFE_ID,
    YEW_SHORTBOW_U_ID,
    YEW_LONGBOW_U_ID
) + VALE_OFFERING_IDS

// ==========================================================================================
// Bank chest
// ==========================================================================================

// The Nemus Retreat bank at (1386, 3309, 0) is inside the Civitas illa Fortis region. Two
// Fortis-area bank chest object IDs exist in gameval/ObjectID1.java:
//   - FORTIS_BANK_CHEST = 53014         (large Civitas illa Fortis bank)
//   - FORTIS_BANK_CHEST_SMALL = 53015   (smaller Fortis-variant bank chest)
// Nemus Retreat is a small outpost, so FORTIS_BANK_CHEST_SMALL is the most likely match, but
// this has not been confirmed live yet. We keep the -1 sentinel so the Bank state falls back
// to name-based lookup (Rs2TileObjectQueryable().withName("Bank chest").nearest()), which is
// robust whichever ID the server sends. Once verified in-game, replace -1 with the correct
// constant and the Bank state will automatically route through the fast bankAt() helper.
private const val BANK_CHEST_OBJ: Int = -1

// ==========================================================================================
// Plugin / Script wiring
// ==========================================================================================

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Nemus Forester",
    description = "Woodcuts yews, fletches, handles Forestry events, maintains the Nemus totem",
    tags = ["woodcutting", "fletching", "forestry"],
    enabledByDefault = false
)
class NemusForester : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = NemusForesterScript()

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            script.loop(client)
        }
    }

    override fun shutDown() {
        running = false
    }

    @Subscribe
    fun onChatMessage(e: ChatMessage) {
        script.eventReceived(client, e)
    }

    @Subscribe
    fun onAnimationChanged(e: AnimationChanged) {
        script.eventReceived(client, e)
    }
}

class NemusForesterScript : StateMachineScript() {
    override fun getStartState(): State = Root()
}

// ==========================================================================================
// Guard helpers
// ==========================================================================================

/** True if inventory contains a hatchet (any axe). Name-based to tolerate tier upgrades. */
private fun hasAxe(): Boolean =
    Rs2Inventory.all { it.name != null && it.name.contains("axe", ignoreCase = true) }.isNotEmpty()

/** Returns the exact name of the first axe in the inventory, or null. */
private fun hatchetName(): String? =
    Rs2Inventory.all { it.name != null && it.name.contains("axe", ignoreCase = true) }.firstOrNull()?.name

/** Current yew-log count in inventory. */
private fun invLogCount(): Int = Rs2Inventory.count(YEW_LOG_ID)

/** Real (unboosted) Fletching level. */
private fun fletchingLevel(): Int = Rs2Player.getRealSkillLevel(Skill.FLETCHING)

/** Returns the unstrung yew bow id we should fletch, or null if fletch level too low (< 65). */
private fun fletchTargetId(): Int? = when {
    fletchingLevel() >= 70 -> YEW_LONGBOW_U_ID
    fletchingLevel() >= 65 -> YEW_SHORTBOW_U_ID
    else -> null
}

/**
 * Should we switch to Fletch? Only true if we have the level + enough logs to make it worthwhile,
 * and either (a) the inventory is full (trim it via fletching) or (b) we've hit the threshold.
 */
private fun shouldFletchNow(): Boolean {
    if (fletchTargetId() == null) return false
    val logs = invLogCount()
    if (logs < FLETCH_MIN_LOGS) return false
    return Rs2Inventory.isFull() || logs >= BANK_LOGS_THRESHOLD
}

/**
 * Should we walk to the bank? Only when the inventory is full AND contains meaningful bankable
 * items (yew logs, bird nests, forestry loot). Prevents false triggers when the inventory is
 * full of coins + knife + axe + bows + Vale Offerings.
 */
private fun needsBankTrip(): Boolean {
    if (!Rs2Inventory.isFull()) return false
    val bankable = Rs2Inventory.all { item ->
        item.id == YEW_LOG_ID ||
            (item.name?.contains("nest", ignoreCase = true) == true) ||
            isForestryLoot(item.id)
    }.size
    return bankable >= 8
}

/** Heuristic: is this item ID a forestry loot drop we want to deposit? */
private fun isForestryLoot(id: Int): Boolean {
    // Forestry-item IDs live in the 28179..28665 range (leprechaun charm/insignia, sapling
    // mulch variants, bee canister/fuel, enchanted-ritual charm/circlet/garland, pheasant
    // spoon/cradle/egg, poachers disarmer, fire-yew-logs). If more specific filtering is
    // needed later, enumerate the exact ItemID symbols.
    return id in 28179..28665
}

/**
 * Opportunistic totem-maintenance trigger. Returns true if ANY of:
 *   - a ripe Vale Offering sack is present on the totem (claim is free)
 *   - we hold an unstrung yew bow AND the site's decoration slots < 4
 *   - we hold a knife AND there's an un-carved nearby animal to carve
 *
 * Site number is lazy-resolved here (not just inside TotemMaintain.loop) to avoid a
 * circular dependency: previously `shouldMaintainTotem` short-circuited on
 * `cachedSiteNumber == null` and `cachedSiteNumber` was ONLY populated inside
 * TotemMaintain.loop — which itself never ran unless `shouldMaintainTotem()` was true.
 *
 * Resolve is gated by proximity (<= 25 tiles of TOTEM_TILE) so we don't pay the
 * 24-varbit scan when we're banking, fletching, or walking elsewhere.
 */
private fun shouldMaintainTotem(): Boolean {
    val here = Rs2Player.getWorldLocation() ?: return false
    if (here.distanceTo(TOTEM_TILE) > 25) return false
    val n = cachedSiteNumber ?: resolveSiteNumber()?.also { cachedSiteNumber = it } ?: return false
    val offeringReady = Rs2TileObjectQueryable()
        .withIds(*ENT_TOTEMS_OFFERING_FULL_IDS)
        .where { it.worldLocation.distanceTo(TOTEM_TILE) <= 3 }
        .first() != null
    if (offeringReady) return true
    val holdingBow = Rs2Inventory.contains(YEW_SHORTBOW_U_ID) || Rs2Inventory.contains(YEW_LONGBOW_U_ID)
    val decorations = Microbot.getVarbitValue(siteDecorations(n))
    if (holdingBow && decorations < 4) return true
    if (Rs2Inventory.contains(KNIFE_ID) && needToCarveNearbyAnimal(n)) return true
    return false
}

/**
 * Returns the closest live Forestry NPC or object within FORESTRY_SCAN_RADIUS, or null.
 *
 * Tightened against false positives for objects: they must expose at least one non-"Examine"
 * action via their ObjectComposition. For NPCs we rely on the curated FORESTRY_NPC_IDS list
 * (with known-permanent ENT entries removed) rather than an NPCComposition probe — Rs2NpcModel
 * does not expose a public composition accessor and reaching into Microbot's private client
 * field from Kotlin is awkward. This is sufficient because all remaining NPCs in the list are
 * genuine event-only spawns.
 */
private fun detectForestryEvent(): Any? {
    val here = Rs2Player.getWorldLocation() ?: return null
    val npcIds = FORESTRY_NPC_IDS.toHashSet()
    val npc = Rs2NpcQueryable()
        .where { it.id in npcIds }
        .nearest(FORESTRY_SCAN_RADIUS)
    if (npc != null) return npc
    val objIds = FORESTRY_OBJ_IDS.toHashSet()
    return Rs2TileObjectQueryable()
        .where { o ->
            o.id in objIds &&
                o.worldLocation.distanceTo(here) <= FORESTRY_SCAN_RADIUS &&
                hasActionableAction(o.objectComposition?.actions)
        }
        .nearest()
}

/** True when `actions` contains at least one non-null action that is not "Examine". */
private fun hasActionableAction(actions: Array<String?>?): Boolean {
    if (actions == null) return false
    return actions.any { it != null && !it.equals("Examine", ignoreCase = true) }
}

// ==========================================================================================
// Totem helpers
// ==========================================================================================
//
// The Ent Totems minigame exposes a large block of per-site varbits in VarbitID.java. Sites are
// numbered 1..8; each site occupies an 18-varbit slot starting at ENT_TOTEMS_SITE_1_BASE (17611).
// Site 2 starts at 17629, verified stride = 18 (17629 - 17611). Intra-site offsets (from base):
//   BASE = 0, BASE_CARVED = 1, LOW = 3, MID = 4, TOP = 5, DECORATIONS = 6,
//   ANIMAL_1 = 7, ANIMAL_2 = 8, ANIMAL_3 = 9, DECAY = 10, POINTS = 11.
// (Offset 2 is BASE_MULTILOC, offsets 12..17 are MULTIANIMAL_*/ALL_MULTIANIMALS and are not used
// here.)
//
// Encoding conventions observed in the wild:
//   * LOW/MID/TOP slot varbits read 0 when empty, 1..9 for uncarved log variants, and 10..14
//     when carved (carved value - 9 yields the animal id 1..5).
//   * ANIMAL_1..ANIMAL_3 encode the "required" nearby-animal spawns in 1..5 (0 = no animal).
//   * The resolver scans all eight sites and picks the one whose ANIMAL_1..ANIMAL_3 are
//     populated AND which has a physical yew-totem object within 3 tiles of TOTEM_TILE.

@Volatile private var cachedSiteNumber: Int? = null

private const val SITE_VARBIT_STRIDE = 18

private fun siteBaseVarbit(n: Int): Int     = 17611 + SITE_VARBIT_STRIDE * (n - 1)
private fun siteBaseCarved(n: Int): Int     = siteBaseVarbit(n) + 1
private fun siteLow(n: Int): Int            = siteBaseVarbit(n) + 3
private fun siteMid(n: Int): Int            = siteBaseVarbit(n) + 4
private fun siteTop(n: Int): Int            = siteBaseVarbit(n) + 5
private fun siteDecorations(n: Int): Int    = siteBaseVarbit(n) + 6
private fun siteAnimal(n: Int, k: Int): Int = siteBaseVarbit(n) + 7 + (k - 1)
private fun siteDecay(n: Int): Int          = siteBaseVarbit(n) + 10
private fun sitePoints(n: Int): Int         = siteBaseVarbit(n) + 11

/**
 * Identifies which of the eight totem sites the player is currently maintaining. A site is
 * considered "active" when any of its three ANIMAL_* varbits is non-zero AND at least one
 * yew-totem object (or a bare totem base) is within 3 tiles of TOTEM_TILE.
 *
 * Returns null when the site cannot be resolved (not at Nemus, server hasn't pushed the
 * varbits yet, etc.). Callers should bail until this returns a value.
 */
private fun resolveSiteNumber(): Int? {
    val yewPresent = Rs2TileObjectQueryable()
        .where { o ->
            (o.id == ENT_TOTEMS_BASE_YEW || o.id == ENT_TOTEMS_CARVED_BASE_YEW ||
                o.id == ENT_TOTEMS_LOW_YEW || o.id == ENT_TOTEMS_MID_YEW ||
                o.id == ENT_TOTEMS_TOP_YEW || o.id == ENT_TOTEMS_BASE_NONE) &&
                o.worldLocation.distanceTo(TOTEM_TILE) <= 3
        }
        .first() != null
    if (!yewPresent) return null
    return (1..8).firstOrNull { n ->
        Microbot.getVarbitValue(siteAnimal(n, 1)) != 0 ||
            Microbot.getVarbitValue(siteAnimal(n, 2)) != 0 ||
            Microbot.getVarbitValue(siteAnimal(n, 3)) != 0
    }
}

/**
 * Returns true if at least one of the three nearby-animal slots holds a value (1..5) that has
 * NOT yet been carved onto any of the three stacked totem pieces, AND there is at least one
 * empty stack slot (LOW/MID/TOP == 0) we can actually carve it into.
 *
 * Carved slots encode as 10..14 (animal id + 9). Pending == nearby animals minus already-carved.
 */
private fun needToCarveNearbyAnimal(n: Int): Boolean {
    val slots = listOf(siteLow(n), siteMid(n), siteTop(n))
        .map { Microbot.getVarbitValue(it) }
    val carvedAnimals = slots.filter { it in 10..14 }.map { it - 9 }.toSet()
    val nearbyAnimals = (1..3).map { Microbot.getVarbitValue(siteAnimal(n, it)) }
        .filter { it in 1..5 }.toSet()
    val pending = nearbyAnimals - carvedAnimals
    return pending.isNotEmpty() && slots.any { it == 0 }
}

/**
 * Picks the first un-carved nearby-animal spawn and clicks its matching dialogue (or widget)
 * option. The ANIMAL_* varbits encode 1=Buffalo, 2=Jaguar, 3=Eagle, 4=Snake, 5=Scorpion, so we
 * index into [options] directly.
 *
 * If the "Select an option" dialogue is already up, we use Rs2Dialogue.clickOption(label) for
 * a tick-friendly click. Otherwise we fall back to Rs2Widget.findWidget(label) which walks the
 * widget tree (useful when the carving interface is a bespoke widget rather than the standard
 * dialogue options).
 */
private fun carveFirstMatchingAnimal(n: Int) {
    val slots = listOf(siteLow(n), siteMid(n), siteTop(n))
        .map { Microbot.getVarbitValue(it) }
    val carvedAnimals = slots.filter { it in 10..14 }.map { it - 9 }.toSet()
    val pending = (1..3).map { Microbot.getVarbitValue(siteAnimal(n, it)) }
        .filter { it in 1..5 && it !in carvedAnimals }
    val first = pending.firstOrNull() ?: return
    val options = arrayOf("Buffalo", "Jaguar", "Eagle", "Snake", "Scorpion")
    val label = options[first - 1]
    if (Rs2Dialogue.hasSelectAnOption() && Rs2Dialogue.clickOption(label)) return
    Rs2Widget.findWidget(label, null)?.let { Rs2Widget.clickWidget(it) }
}

// ==========================================================================================
// States
// ==========================================================================================

// Root dispatcher. Priority: ForestryEvent > TotemMaintain > Bank > Fletch > Woodcut. Always
// returns a non-null state — Root is purely a router, so `loop()` is never reached.
private class Root : State() {
    override fun checkNext(client: Client): State? {
        if (detectForestryEvent() != null) return ForestryEvent()
        if (shouldMaintainTotem()) return TotemMaintain()
        if (needsBankTrip()) return Bank()
        if (shouldFletchNow()) return Fletch()
        return Woodcut()
    }

    override fun loop(client: Client, script: StateMachineScript) {}
}

// Chops yew trees anchored at TREE_TILE. Walks to a randomized adjacent tile on approach,
// then interacts with the nearest ObjectID.YEWTREE (10822) within 5 tiles of the PLAYER —
// not the hardcoded TREE_TILE, to tolerate Nemus yew-tree footprints whose worldLocation
// may be 1-2 tiles offset from the anchor. Waits on animation stop OR tree-gone OR a small
// log gain. Returns to Root() whenever priorities may need re-evaluation: inventory is full
// (need bank/fletch decision), a Forestry event spawned, or totem maintenance is wanted.
private class Woodcut : State() {
    override fun checkNext(client: Client): State? {
        if (Rs2Inventory.isFull()) return Root()
        if (detectForestryEvent() != null) return Root()
        if (shouldMaintainTotem()) return Root()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        // 1. Pause guard (BreakHandler / user pause)
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // 2. If far from the tree tile, walk to a RANDOMIZED adjacent tile
        val here = Rs2Player.getWorldLocation()
        if (here == null || here.distanceTo(TREE_TILE) > 3) {
            val dest = TREE_ADJ_TILES.random()
            Rs2Walker.walkTo(dest, 1)
            Rs2Player.waitForWalking(Rs2Random.between(3000, 6000))
            return
        }

        // 3. If already chopping (interacting with a tree), don't double-click.
        //    isInteracting() is narrower than isAnimating(): it only reports true when the
        //    player is actively bound to another entity, avoiding false gates from residual
        //    walking animation or nearby NPC forced animations bleeding through.
        if (Rs2Player.isInteracting()) return

        // 4. Find a nearby yew tree and chop it.
        //
        //    ID-based, NOT name-based: ObjectComposition.getName() can be null during
        //    scene-load / cache churn, is case-and-whitespace-sensitive (any server-side
        //    rename breaks it), and requires a composition cache round-trip per candidate.
        //    ObjectID.YEWTREE (10822) is the canonical wild, woodcuttable yew tree — the one
        //    with the "Chop down" action at Nemus Retreat, Falador, etc. Farming-patch
        //    variants (YEW_TREE_1..9, YEW_TREE_FULLYGROWN_*, YEW_TREE_DISEASED_*, YEW_TREE_DEAD_*)
        //    live in the 8502-8534 range and are explicitly NOT matched — those are player
        //    farming patches, not wild trees. DEADMAN_YEWTREE (10823), XBOWS_YEWTREE_* (17039-41)
        //    are also intentionally excluded (not Nemus-relevant). A grep of gameval/ObjectID.java
        //    for NEMUS/FORESTRY_YEW/GATHERING_EVENT*YEW returned zero hits, so there is no
        //    location-specific Nemus variant to add.
        //
        //    Distance check is PLAYER-based (not TREE_TILE-based): the yew trees at Nemus
        //    occupy multi-tile footprints whose worldLocation reports the south-west corner,
        //    which can sit 1-2 tiles off TREE_TILE. Combined with the randomized adjacent-tile
        //    walk above (player can stand up to 2 tiles from TREE_TILE), the old `<= 2`
        //    filter could fail even when the player was literally next to a tree. 5-tile
        //    radius keeps us well inside interact range while tolerating layout drift.
        //    `here` is smart-cast to non-null here: the step-2 `if (here == null ...) return`
        //    above guarantees it by the time we reach this point.
        val playerLoc = here
        val tree = Rs2TileObjectQueryable()
            .withIds(ObjectID.YEWTREE)
            .where { it.worldLocation.distanceTo(playerLoc) <= 5 }
            .nearest()

        if (tree == null) {
            // Respawn pending - small jitter so we don't busy-loop
            Global.sleep(Rs2Random.between(420, 980))
            return
        }

        val logsBefore = Rs2Inventory.count(YEW_LOG_ID)
        val treeWorldLoc = tree.worldLocation
        val treeId = tree.id

        if (!tree.click("Chop down")) {
            Global.sleep(Rs2Random.between(300, 700))
            return
        }

        // 5. Verify the click actually dispatched — wait a short random deadline for EITHER
        //    interacting OR animating to turn true. If neither flips, the menu action didn't
        //    fire (clickbox occluded, menu swap, etc.); bail with a tiny jitter so we pick a
        //    different tree next iteration without hammer-clicking.
        val started = Rs2Global.sleepUntilTrueShort({
            Rs2Player.isInteracting() || Rs2Player.isAnimating()
        }, Rs2Random.between(1500, 2200))
        if (!started) {
            Microbot.log("NemusForester: chop click silent on tree $treeId @ $treeWorldLoc — retrying")
            Global.sleep(Rs2Random.between(420, 980))
            return
        }

        // 6. Wait for: animation stopped OR the tree vanished OR we gained logs
        sleepUntil(timeout = Rs2Random.between(30_000, 90_000)) {
            !Rs2Player.isAnimating() ||
                Rs2Inventory.count(YEW_LOG_ID) > logsBefore + 2 ||
                Rs2TileObjectQueryable()
                    .withId(treeId)
                    .where { o -> o.worldLocation == treeWorldLoc }
                    .first() == null
        }
    }
}

/**
 * Tiny wrapper around Global.sleepUntilTrue that returns whether the condition became true
 * before the timeout (Global.sleepUntilTrue returns void). Keeps the click-verification
 * logic readable.
 */
private object Rs2Global {
    fun sleepUntilTrueShort(cond: () -> Boolean, timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Global.sleep(120)
        }
        return cond()
    }
}

// Deposits every bankable item (yew logs, nests, forestry-event loot) at the Nemus Retreat
// bank chest while preserving the "keep" invariant set (coins / knife / unstrung yew bows /
// Vale Offerings / any-name-"axe"). Re-withdraws a knife if the player dropped or lost it.
//
// Object-ID resolution is intentionally two-path: if BANK_CHEST_OBJ is set to a verified
// in-game ID we use the bankAt() fast path (walks + clicks in one tick-friendly helper);
// otherwise we fall back to a name-based query ("Bank chest" preferred, then "Bank booth").
// Name-based lookup tolerates either the large or small Civitas illa Fortis chest variant.
//
// Returns to Root() once the deposit cycle is done (inventory no longer full AND no logs left
// AND the bank interface is closed), or when a Forestry event appears while the bank is closed
// (we defer opening the bank in that case — event handling is higher priority).
private class Bank : State() {
    override fun checkNext(client: Client): State? {
        if (!Rs2Inventory.isFull() && invLogCount() == 0 && !Rs2Bank.isOpen()) return Root()
        if (detectForestryEvent() != null && !Rs2Bank.isOpen()) return Root()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        // 1. Pause guard (BreakHandler / user pause)
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // 2. Get the bank open. ensureBankOpen() walks + clicks as needed and returns false
        //    when still making progress on a prior tick; we bail so the next loop iteration
        //    re-checks state instead of spinning.
        if (!ensureBankOpen()) return

        // 3. Deposit every non-keep item. We use the predicate overload of depositAllExcept
        //    so we can blend an ID whitelist (coins/knife/unstrung bows/Vale Offerings) with
        //    a name-based axe check (tolerates tier upgrades).
        val keepIds = KEEP_ITEM_IDS.toHashSet()
        val axeName = hatchetName()
        Rs2Bank.depositAllExcept(Predicate<Rs2ItemModel> { item ->
            item.id in keepIds ||
                (axeName != null && item.name.equals(axeName, ignoreCase = true))
        })
        sleepUntil(timeout = Rs2Random.between(4000, 6500)) {
            invLogCount() == 0 &&
                Rs2Inventory.all { it.name.contains("nest", ignoreCase = true) }.isEmpty() &&
                Rs2Inventory.all { isForestryLoot(it.id) }.isEmpty()
        }

        // 4. If we somehow lost the knife (death, accidental drop, etc.), re-withdraw one so
        //    fletching and totem carving continue to work on the next cycle.
        if (!Rs2Inventory.contains(KNIFE_ID) && Rs2Bank.hasItem(KNIFE_ID)) {
            Rs2Bank.withdrawOne(KNIFE_ID)
            sleepUntil(timeout = Rs2Random.between(1500, 2500)) { Rs2Inventory.contains(KNIFE_ID) }
        }

        // 5. Close the bank so walker + interactions work on the next loop.
        Rs2Bank.closeBank()
        sleepUntil(timeout = Rs2Random.between(2000, 3500)) { !Rs2Bank.isOpen() }
    }
}

// Opens the Nemus Retreat bank chest. Two code paths:
//
//   Path A (preferred): BANK_CHEST_OBJ is a verified GameObject ID. We hand off to the
//     shared bankAt() helper in ExtensionGlobals.kt, which walks to BANK_TILE and clicks
//     the chest in one place.
//
//   Path B (fallback): BANK_CHEST_OBJ == -1. We walk manually if far from BANK_TILE, then
//     query the nearest "Bank chest" (or "Bank booth" if that returns null) within 8 tiles
//     and click it. Returns true once Rs2Bank.isOpen().
private fun ensureBankOpen(): Boolean {
    if (BANK_CHEST_OBJ != -1) return bankAt(BANK_CHEST_OBJ, BANK_TILE)

    if (Rs2Bank.isOpen()) return true

    val here = Rs2Player.getWorldLocation()
    if (here == null || here.distanceTo(BANK_TILE) > 8) {
        Rs2Walker.walkTo(BANK_TILE, 5)
        Rs2Player.waitForWalking(Rs2Random.between(3000, 6000))
        return false
    }

    val chest = Rs2TileObjectQueryable()
        .withName("Bank chest")
        .nearest(8)
        ?: Rs2TileObjectQueryable().withName("Bank booth").nearest(8)
    if (chest == null) {
        Global.sleep(Rs2Random.between(420, 820))
        return false
    }

    if (chest.click("Bank")) {
        sleepUntil(timeout = Rs2Random.between(5000, 8000)) { Rs2Bank.isOpen() }
    }
    return Rs2Bank.isOpen()
}

// Fletches yew logs in place at the tree, piggybacking on tree-respawn idle time. Produces
// unstrung yew shortbows (fletch 65..69) or unstrung yew longbows (fletch >=70). Uses the
// shared Rs2Fletching.fletchItems(int, String, String) entry point, which internally combines
// the knife with a yew log, opens the multiskill interface, and drives the quantity buttons.
// Target-ID resolution is done via fletchTargetId(); option names ("Shortbow"/"Longbow") mirror
// Rs2Fletching.getFletchingItemDisplayName(...) as used by autoFletchAvailable, so they match
// the case-insensitive substring lookup in Rs2Widget.handleProcessingInterface. Returns to
// Root() when logs are exhausted, a Forestry event pops up, or the target id becomes null
// (e.g. level dropped — shouldn't happen in practice, but guards the `when` in loop()).
private class Fletch : State() {
    override fun checkNext(client: Client): State? {
        if (!Rs2Inventory.contains(YEW_LOG_ID)) return Root()
        if (detectForestryEvent() != null) return Root()
        if (fletchTargetId() == null) return Root()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        val target = fletchTargetId()
        if (target == null) {
            Global.sleep(Rs2Random.between(420, 980))
            return
        }

        if (!Rs2Inventory.contains(YEW_LOG_ID) || !Rs2Inventory.contains(KNIFE_ID)) {
            Global.sleep(Rs2Random.between(420, 980))
            return
        }

        val optionName = when (target) {
            YEW_LONGBOW_U_ID -> "Longbow"
            YEW_SHORTBOW_U_ID -> "Shortbow"
            else -> return
        }

        val logsBefore = invLogCount()
        val started = Rs2Fletching.fletchItems(YEW_LOG_ID, optionName, "All")
        if (!started) {
            Global.sleep(Rs2Random.between(500, 1200))
            return
        }

        sleepUntil(timeout = Rs2Random.between(9500, 14500)) {
            invLogCount() < logsBefore - 2 ||
                Rs2Inventory.isFull() ||
                !Rs2Inventory.contains(YEW_LOG_ID) ||
                detectForestryEvent() != null
        }
    }
}

// Opportunistic totem servicing: claims ripe offering sacks, decorates with unstrung yew bows
// when the decorations slot is < 4, and carves un-carved nearby animals when we hold a knife.
// At most one action per loop tick, then returns — letting the top-level StateMachineScript re-
// evaluate priorities cleanly. Routes back to Root() when a Forestry event appears or when
// shouldMaintainTotem() no longer reports anything to do. `cachedSiteNumber` is lazy-resolved
// inside the loop, matching the other totem helpers.
//
// Priority order (short-circuits on the first actionable item):
//   A. Claim a ripe offering sack within 3 tiles of TOTEM_TILE.
//   B. Decorate if we hold an unstrung yew bow AND decorations < 4.
//   C. Carve if we hold a knife AND there's an un-carved nearby animal.
//
// The offering-sack menu action is "Rummage" on OSRS (confirmed via LootTrackerPlugin's
// "You rummage through the offerings" chat-message hook at ItemID.ENT_TOTEMS_LOOT). We try
// "Rummage" first, then fall back to "Claim"/"Take"/"Collect" defensively in case the cache
// returns impostor actions.
private class TotemMaintain : State() {
    override fun checkNext(client: Client): State? {
        if (detectForestryEvent() != null) return Root()
        if (!shouldMaintainTotem()) return Root()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // 1. Walk to TOTEM_TILE if we're more than 3 tiles away.
        val here = Rs2Player.getWorldLocation()
        if (here == null || here.distanceTo(TOTEM_TILE) > 3) {
            Rs2Walker.walkTo(TOTEM_TILE, 2)
            Rs2Player.waitForWalking(Rs2Random.between(4000, 8000))
            return
        }

        // 2. Lazy-resolve the site number (null -> nothing to do here this tick).
        if (cachedSiteNumber == null) {
            cachedSiteNumber = resolveSiteNumber()
            if (cachedSiteNumber == null) {
                Global.sleep(Rs2Random.between(500, 1100))
                return
            }
        }
        val n = cachedSiteNumber!!

        // 3. Priority A: claim a ripe offering sack if one is present.
        val offeringSack = Rs2TileObjectQueryable()
            .withIds(*ENT_TOTEMS_OFFERING_FULL_IDS)
            .where { it.worldLocation.distanceTo(TOTEM_TILE) <= 3 }
            .nearest()
        if (offeringSack != null) {
            // The OSRS menu action is "Rummage" — confirmed via
            // LootTrackerPlugin.onChatMessage("You rummage through the offerings"). We resolve
            // it against the ObjectComposition to stay robust if a future revision renames it;
            // "Claim"/"Take"/"Collect" are defensive fallbacks.
            val compActions = offeringSack.objectComposition?.actions
            val action = compActions
                ?.firstOrNull { it != null && it.equals("Rummage", ignoreCase = true) }
                ?: compActions?.firstOrNull { it != null && it.equals("Claim", ignoreCase = true) }
                ?: compActions?.firstOrNull { it != null && it.equals("Take", ignoreCase = true) }
                ?: compActions?.firstOrNull { it != null && it.equals("Collect", ignoreCase = true) }
                ?: compActions?.firstOrNull { it != null && !it.equals("Examine", ignoreCase = true) }
                ?: "Rummage"
            offeringSack.click(action)
            sleepUntil(timeout = Rs2Random.between(3500, 5500)) {
                Rs2TileObjectQueryable()
                    .withIds(*ENT_TOTEMS_OFFERING_FULL_IDS)
                    .where { it.worldLocation.distanceTo(TOTEM_TILE) <= 3 }
                    .first() == null
            }
            return
        }

        // 4. Priority B: decorate if we hold an unstrung yew bow AND decoration slots < 4.
        val decorations = Microbot.getVarbitValue(siteDecorations(n))
        val holdingBow = Rs2Inventory.contains(YEW_SHORTBOW_U_ID) || Rs2Inventory.contains(YEW_LONGBOW_U_ID)
        if (holdingBow && decorations < 4) {
            val totem = Rs2TileObjectQueryable()
                .withIds(
                    ENT_TOTEMS_BASE_YEW,
                    ENT_TOTEMS_CARVED_BASE_YEW,
                    ENT_TOTEMS_LOW_YEW,
                    ENT_TOTEMS_MID_YEW,
                    ENT_TOTEMS_TOP_YEW
                )
                .where { it.worldLocation.distanceTo(TOTEM_TILE) <= 3 }
                .nearest()
            if (totem != null) {
                totem.click("Decorate")
                sleepUntil(timeout = Rs2Random.between(4000, 6000)) {
                    Microbot.getVarbitValue(siteDecorations(n)) > decorations
                }
                return
            }
        }

        // 5. Priority C: carve un-carved nearby animal (requires knife).
        if (Rs2Inventory.contains(KNIFE_ID) && needToCarveNearbyAnimal(n)) {
            val totem = Rs2TileObjectQueryable()
                .withIds(ENT_TOTEMS_BASE_YEW, ENT_TOTEMS_CARVED_BASE_YEW)
                .where { it.worldLocation.distanceTo(TOTEM_TILE) <= 3 }
                .nearest()
            if (totem != null) {
                totem.click("Carve")
                // Wait for the species-picker UI, then click the right species.
                sleepUntil(timeout = Rs2Random.between(3500, 5000)) { Rs2Dialogue.hasSelectAnOption() }
                carveFirstMatchingAnimal(n)
                Rs2Player.waitForAnimation(Rs2Random.between(2500, 4000))
                Global.sleep(Rs2Random.between(400, 800))
                return
            }
        }

        // 6. Nothing actionable this tick — tiny jitter to avoid busy-loop.
        Global.sleep(Rs2Random.between(500, 1100))
    }
}

// Generic Forestry event dispatcher. v1 intentionally skips per-event hand-coding: the state
// clicks the first non-"Examine" (and non-"Talk-to") action on the nearest Forestry NPC or
// GameObject within FORESTRY_SCAN_RADIUS, resolves any in-progress dialogue, then returns.
// A hard 180-second bail-out prevents stuck events from hanging the bot — `checkNext()` routes
// back to Root() once the event is gone or the budget expires.
//
// Action-accessor verification:
//   * Rs2NpcModel (api.npc.models variant, returned by Rs2NpcQueryable) wraps the underlying
//     runelite-api NPC via a Lombok-@Getter `npc` field; NPC.getComposition() returns the
//     NPCComposition, whose getActions() returns String[] (may contain nulls). So the
//     Kotlin accessor chain is `rs2NpcModel.npc?.composition?.actions`.
//   * Rs2TileObjectModel has a direct `objectComposition?.actions` accessor — mirrors the
//     pattern used in TotemMaintain.
//   * Rs2NpcModel.click(String) returns boolean and handles line-of-sight / walk-to retries
//     internally — matching the Rs2TileObjectModel.click contract. The legacy static
//     Rs2Npc.interact helper is @Deprecated class-wide; we use the instance method instead.
//   * Rs2TileObjectModel.click(String) returns boolean.
private class ForestryEvent : State() {
    // `startMs` resets each time the dispatcher instantiates a new ForestryEvent — this is the
    // desired behavior: each event entry gets a fresh 180s budget.
    private val startMs = System.currentTimeMillis()

    override fun checkNext(client: Client): State? {
        if (detectForestryEvent() == null) return Root()
        if (System.currentTimeMillis() - startMs > 180_000) return Root()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // 180-second bail-out — if an event refuses to resolve, back off.
        if (System.currentTimeMillis() - startMs > 180_000) {
            Global.sleep(Rs2Random.between(400, 900))
            return
        }

        // Resolve any in-progress dialogue first (e.g., a pheasant hand-in).
        if (Rs2Dialogue.isInDialogue()) {
            Rs2Dialogue.clickContinue()
            Global.sleep(Rs2Random.between(350, 700))
            return
        }

        val target = detectForestryEvent()
        if (target == null) {
            // Event ended before we could act — exit quietly.
            Global.sleep(Rs2Random.between(400, 900))
            return
        }

        when (target) {
            is Rs2NpcModel -> handleForestryNpc(target)
            is Rs2TileObjectModel -> handleForestryObject(target)
        }

        Global.sleep(Rs2Random.between(440, 920))
    }
}

// ==========================================================================================
// Forestry handlers (top-level, private)
// ==========================================================================================

private fun handleForestryNpc(npc: Rs2NpcModel) {
    // Rs2NpcModel (api.npc.models variant, returned by the queryable) wraps the underlying
    // runelite-api NPC in a Lombok-@Getter `npc` field; NPC.getComposition() returns the
    // NPCComposition whose getActions() returns String[] (may contain nulls). Prefer any
    // action that is not "Examine" or "Talk-to" (Talk-to would chain into a dialogue loop
    // for the pheasant forester etc.); fall back to anything that is not "Examine" if
    // nothing else is available.
    val actions = npc.npc?.composition?.actions ?: return
    val action = actions
        .filterNotNull()
        .firstOrNull { it != "Examine" && it != "Talk-to" }
        ?: actions.filterNotNull().firstOrNull { it != "Examine" }
        ?: return
    // Rs2NpcModel.click(String) handles line-of-sight retries and returns true on
    // successful queue — mirrors the Rs2TileObjectModel.click contract used elsewhere.
    npc.click(action)
    Rs2Player.waitForWalking(Rs2Random.between(2500, 4500))
    if (Rs2Dialogue.isInDialogue()) {
        Rs2Dialogue.clickContinue()
    }
}

private fun handleForestryObject(obj: Rs2TileObjectModel) {
    val actions = obj.objectComposition?.actions ?: return
    val action = actions
        .filterNotNull()
        .firstOrNull { it != "Examine" }
        ?: return
    obj.click(action)
    Rs2Player.waitForAnimation(Rs2Random.between(2500, 4500))
}
