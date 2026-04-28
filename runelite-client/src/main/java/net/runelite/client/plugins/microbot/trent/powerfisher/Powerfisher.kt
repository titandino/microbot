package net.runelite.client.plugins.microbot.trent.powerfisher

import com.google.inject.Provides
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.gameval.ItemID
import net.runelite.client.config.Config
import net.runelite.client.config.ConfigGroup
import net.runelite.client.config.ConfigItem
import net.runelite.client.config.ConfigManager
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import javax.inject.Inject

// ==========================================================================================
// Powerfisher — generic power-fishing loop, fish species/method selectable via config.
//
// In Leagues, a relic auto-cooks the fish on catch — so the dropper covers raw, cooked,
// and burnt outcomes in a single sweep. This script's job is the tightest possible
// "drop & re-fish" loop: drop every accumulating fish, keep tools/bait, and click the
// nearest fishing spot whenever we aren't already fishing. The user starts the script
// standing at a fishing location with the right gear.
//
// Deliberately out of scope (per user brief):
//   - No banking, walking to a fishing location, world-hopping, food, combat.
//   - No equipping/withdrawing — assumes rod/harpoon/cage/net + bait already present.
//   - No hardcoded coordinates: the script runs anywhere a fishing spot with the
//     selected action is in scene (Lumbridge / Barbarian Village / Catherby / etc.).
//
// Item IDs (verified against runelite-api gameval/ItemID.java):
//   - Trout/Salmon line: RAW_TROUT 335, TROUT 333, RAW_SALMON 331, SALMON 329
//   - Sardine/Herring:   RAW_SARDINE 327, SARDINE 325, RAW_HERRING 345, HERRING 347
//   - Pike:              RAW_PIKE 349, PIKE 351
//   - Shrimp/Anchovy:    RAW_SHRIMP 317, SHRIMP 315, RAW_ANCHOVIES 321, ANCHOVIES 319
//   - Tuna/Swordfish:    RAW_TUNA 359, TUNA 361, RAW_SWORDFISH 371, SWORDFISH 373
//   - Lobster:           RAW_LOBSTER 377, LOBSTER 379
//   - Bass/Mackerel:     RAW_BASS 363, BASS 365, RAW_MACKEREL 353, MACKEREL 355
//   - Monkfish:          RAW_MONKFISH 7944, MONKFISH 7946, BURNT_MONKFISH 7948
//   - Anglerfish:        RAW_ANGLERFISH 13439, ANGLERFISH 13441, BURNT_ANGLERFISH 13443
//   - Karambwan:         TBWT_RAW_KARAMBWAN 3142, TBWT_COOKED_KARAMBWAN 3144,
//                        TBWT_POORLY_COOKED_KARAMBWAN 3146, TBWT_BURNT_KARAMBWAN 3148
//   - Burnt fish (low-tier shared bins): BURNTFISH1 323, BURNTFISH2 343, BURNTFISH3 357,
//     BURNTFISH4 367, BURNTFISH5 369. The gameval set doesn't formally map each fish to
//     a specific BURNTFISH id, but the IDs are interleaved with their fish tier in the
//     ItemID.java listing. We include the adjacent variant(s) for each tier and add
//     extras when uncertain — dropping a non-matching id is a no-op.
//   - Shark burnt: BURNT_SHARK 387 (dedicated, not in BURNTFISH1-5).
//
// NPC matching:
//   Fishing spots are NPCs (not objects). The gameval NpcID.java intentionally has no
//   canonical "FISHING_SPOT_*" constants — fishing-spot NPCs share generic IDs that
//   shuffle on tick and vary by location. The reliable selector is the spot name + the
//   menu action (e.g. "Rod fishing spot" + "Lure", "Fishing spot" + "Net"). This matches
//   trent/shiloriverfish/ShiloRiverFish.kt which uses the same approach for trout/salmon.
//
// Loop cadence: ~600ms tick with jitter (300-900ms idle / 100-300ms post-action). The
// game handles dropping in parallel with the fishing animation, so we don't need to
// pause fishing to drop — we just spam dropAll(...) every iteration when full.
//
// Modes intentionally omitted (gameval constants not available):
//   - BARBARIAN_HARPOON: no RAW_LEAPING_TROUT/SALMON/STURGEON constants in gameval.
//   - INFERNAL_EEL:      INFERNAL_EEL exists (21293) but no RAW_/BURNT_ variant exists.
// ==========================================================================================

// ------------------------------------------------------------------------------------------
// Fishing modes
// ------------------------------------------------------------------------------------------

/**
 * One fishing strategy = one menu-action + one spot-name + the full set of fish item IDs
 * to drop on inventory full.
 *
 * - [spotName] is matched case-insensitively against the NPC's name. Most spots are just
 *   "Fishing spot"; rod-fishing spots (lure/bait) are "Rod fishing spot".
 * - [action]   is the right-click menu option we click on the spot ("Lure", "Bait",
 *   "Small Net", "Harpoon", "Cage", "Big Net", "Net", "Fish").
 * - [dropIds]  is the species-specific drop list — raw + cooked + burnt variants. We
 *   include every BURNTFISH-N variant adjacent to the species' tier in ItemID.java since
 *   gameval doesn't formally map species→burnt; dropping a non-matching id is a no-op.
 */
enum class FishingMode(
    val displayName: String,
    val spotName: String,
    val action: String,
    val dropIds: List<Int>,
) {
    LURE_TROUT_SALMON(
        displayName = "Lure: Trout & Salmon",
        spotName = "rod fishing spot",
        action = "Lure",
        dropIds = listOf(
            ItemID.RAW_TROUT, ItemID.TROUT,
            ItemID.RAW_SALMON, ItemID.SALMON,
            ItemID.BURNTFISH1,
        ),
    ),
    BAIT_SARDINE_HERRING(
        displayName = "Bait: Sardine & Herring",
        spotName = "rod fishing spot",
        action = "Bait",
        dropIds = listOf(
            ItemID.RAW_SARDINE, ItemID.SARDINE,
            ItemID.RAW_HERRING, ItemID.HERRING,
            ItemID.BURNTFISH1,
        ),
    ),
    BAIT_PIKE(
        displayName = "Bait: Pike",
        spotName = "rod fishing spot",
        action = "Bait",
        dropIds = listOf(
            ItemID.RAW_PIKE, ItemID.PIKE,
            ItemID.BURNTFISH1, ItemID.BURNTFISH2,
        ),
    ),
    SMALL_NET(
        displayName = "Small Net: Shrimp & Anchovies",
        spotName = "fishing spot",
        action = "Small Net",
        dropIds = listOf(
            ItemID.RAW_SHRIMP, ItemID.SHRIMP,
            ItemID.RAW_ANCHOVIES, ItemID.ANCHOVIES,
            ItemID.BURNTFISH1,
        ),
    ),
    HARPOON_TUNA_SWORDFISH(
        displayName = "Harpoon: Tuna & Swordfish",
        spotName = "fishing spot",
        action = "Harpoon",
        dropIds = listOf(
            ItemID.RAW_TUNA, ItemID.TUNA,
            ItemID.RAW_SWORDFISH, ItemID.SWORDFISH,
            // Tuna 359-361 / swordfish 371-373 sit adjacent to BURNTFISH4 367 and
            // BURNTFISH5 369 in ItemID.java. Include both — extras are no-ops.
            ItemID.BURNTFISH4, ItemID.BURNTFISH5,
        ),
    ),
    HARPOON_SHARK(
        displayName = "Harpoon: Shark",
        spotName = "fishing spot",
        action = "Harpoon",
        dropIds = listOf(
            ItemID.RAW_SHARK, ItemID.SHARK,
            ItemID.BURNT_SHARK,
        ),
    ),
    CAGE_LOBSTER(
        displayName = "Cage: Lobster",
        spotName = "fishing spot",
        action = "Cage",
        dropIds = listOf(
            ItemID.RAW_LOBSTER, ItemID.LOBSTER,
            // Lobster 377-379 sits adjacent to BURNTFISH4 367 / BURNTFISH5 369.
            ItemID.BURNTFISH4, ItemID.BURNTFISH5,
        ),
    ),
    BIG_NET_BASS(
        displayName = "Big Net: Bass & Mackerel",
        spotName = "fishing spot",
        action = "Big Net",
        dropIds = listOf(
            ItemID.RAW_BASS, ItemID.BASS,
            ItemID.RAW_MACKEREL, ItemID.MACKEREL,
            // Mackerel 353-355 / bass 363-365 sit adjacent to BURNTFISH3 357.
            ItemID.BURNTFISH3, ItemID.BURNTFISH4,
        ),
    ),
    KARAMBWAN(
        displayName = "Karambwan",
        spotName = "karambwan fishing spot",
        action = "Fish",
        dropIds = listOf(
            // Karambwan IDs are namespaced TBWT_* in gameval (Tai Bwo Wannai). The
            // POORLY_COOKED variant happens when cooking without proper level/tools and
            // is included so the loop drops it too. There's no separate "poison karambwan"
            // raw fish in gameval — POISON_KARAMBWAN_PASTE is a different (paste) item.
            ItemID.TBWT_RAW_KARAMBWAN,
            ItemID.TBWT_COOKED_KARAMBWAN,
            ItemID.TBWT_POORLY_COOKED_KARAMBWAN,
            ItemID.TBWT_BURNT_KARAMBWAN,
        ),
    ),
    MONKFISH(
        displayName = "Net: Monkfish",
        spotName = "fishing spot",
        action = "Net",
        dropIds = listOf(
            ItemID.RAW_MONKFISH, ItemID.MONKFISH,
            ItemID.BURNT_MONKFISH,
        ),
    ),
    ANGLERFISH(
        displayName = "Bait: Anglerfish",
        spotName = "fishing spot",
        action = "Bait",
        dropIds = listOf(
            ItemID.RAW_ANGLERFISH, ItemID.ANGLERFISH,
            ItemID.BURNT_ANGLERFISH,
        ),
    ),
    ;

    /** Used as the dropdown label in the config UI. */
    override fun toString(): String = displayName
}

// ------------------------------------------------------------------------------------------
// Config
// ------------------------------------------------------------------------------------------

@ConfigGroup(PowerfisherConfig.GROUP)
interface PowerfisherConfig : Config {
    companion object {
        const val GROUP = "trentPowerfisher"
    }

    @ConfigItem(
        keyName = "fishingMode",
        name = "Fishing Mode",
        description = "Pick the fishing action and fish species. Drops the right fish automatically when inventory fills.",
        position = 0,
    )
    fun fishingMode(): FishingMode = FishingMode.LURE_TROUT_SALMON
}

// ==========================================================================================
// Plugin
// ==========================================================================================

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Powerfisher",
    description = "Power-fishes the selected mode (lure/bait/net/harpoon/cage/...), drops raw/cooked/burnt fish",
    tags = ["fishing"],
    enabledByDefault = false,
)
class Powerfisher : Plugin() {
    @Inject
    private lateinit var client: Client

    @Inject
    private lateinit var config: PowerfisherConfig

    @Provides
    fun provideConfig(configManager: ConfigManager): PowerfisherConfig =
        configManager.getConfig(PowerfisherConfig::class.java)

    @Volatile private var running = false

    /**
     * Resolved at startup so config-change-mid-run doesn't whiplash the loop. To re-pick
     * a mode the user toggles the plugin off+on; matches Bankstander's lazy resolution.
     */
    @Volatile private var activeMode: FishingMode = FishingMode.LURE_TROUT_SALMON

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer == null) return
        activeMode = config.fishingMode()
        Microbot.log(
            "[Powerfisher] starting — mode=${activeMode.displayName} " +
                "(spot=\"${activeMode.spotName}\", action=\"${activeMode.action}\")",
        )
        running = true
        GlobalScope.launch { run() }
    }

    override fun shutDown() {
        running = false
    }

    private fun run() {
        while (running) {
            try {
                loop()
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                running = false
                return
            } catch (t: Throwable) {
                Microbot.log("[Powerfisher] loop error: ${t.message}")
                Global.sleep(Rs2Random.between(800, 1600))
            }
        }
    }

    private fun loop() {
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        val mode = activeMode

        // 1. Only drop when the inventory is full — let fish accumulate to 28, then
        //    sweep every raw/cooked/burnt slot in one call. Standard powerfishing pattern:
        //    dropping every tick wastes interactions and trips anti-pattern heuristics.
        if (Rs2Inventory.isFull()) {
            Rs2Inventory.dropAll(*mode.dropIds.toIntArray())
        }

        // 2. Already fishing? Let the animation continue and re-tick.
        if (Rs2Player.isAnimating() || Rs2Player.isInteracting()) {
            Global.sleep(Rs2Random.between(300, 600))
            return
        }

        // 3. Click the nearest in-scene fishing spot matching the mode. The cache holds
        //    NPC names case-sensitively per server capitalization, but Rs2NpcQueryable's
        //    withName matches name segments; we pass it lower-case and rely on its own
        //    case handling (matches the existing trent fishing scripts).
        val spot = Rs2NpcQueryable()
            .withName(mode.spotName)
            .nearest()
        if (spot != null && spot.click(mode.action)) {
            // Wait briefly for the fishing animation to start; if it doesn't, fall
            // through next tick and try again.
            sleepUntil(checkEvery = 100, timeout = 4000) {
                Rs2Player.isAnimating() || Rs2Player.isInteracting()
            }
        } else {
            // No spot in scene (just shuffled / out of range). Short idle.
            Global.sleep(Rs2Random.between(400, 900))
        }
    }
}
