package net.runelite.client.plugins.microbot.trent.ironpowermine

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldPoint
import net.runelite.api.gameval.ItemID
import net.runelite.api.gameval.ObjectID
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import javax.inject.Inject

// ==========================================================================================
// IronPowerMine — Leagues iron power-mining with auto-drop of auto-smelted steel bars.
//
// In Leagues, a relic auto-smelts ore on pickup: mining iron rocks produces STEEL_BAR
// directly (iron ore + coal → steel, the game handles it). This script tightens the
// standard power-mining loop for that: drop steel bars as they appear and immediately
// click the next iron rock, cycling between rocks so a depleted tile never costs us a
// full respawn. Hard ceiling: never carry more than 2 steel bars — if we ever see 2+,
// dropAll before the next click.
//
// Deliberately out of scope (user brief):
//   - No banking, walking to mine, world-hopping, food, combat, prayer, gear swap.
//   - No hardcoded coordinates. Iron-rock detection is scene-local — run this anywhere
//     you're standing adjacent to an iron cluster (Varrock East, Al Kharid, Mining
//     Guild, Legends' Guild, dwarves, Ardougne, Piscatoris, any Leagues-accessible spot).
//   - No anti-logout nudge (constant mining + dropping is steady input — the 5-minute
//     idle-logout timer is not at risk).
//   - No auto-login integration — enable "Mocrosoft AutoLogin" separately if you need
//     reconnection on drop.
//   - No config group — plugin has only @PluginDescriptor wiring.
//
// Iron-rock IDs (gameval/ObjectID.java, verified):
//   - IRONROCK1 = 11364  (ObjectID.java:37275)
//   - IRONROCK2 = 11365  (ObjectID.java:37280)
// Both are the same "Rocks" object with "Mine" action; alternating IDs are just the
// Jagex texture/variant toggle. Cross-confirmed by trent/ironsuperheat/IronSuperheat.kt
// which uses the same pair for its mining path.
//
// Steel bar ID: ItemID.STEEL_BAR = 2353 (gameval/ItemID.java:7066).
//
// Drop helper: Rs2Inventory.dropAll(int... ids) (Rs2Inventory.java:488) drops every
// inventory slot matching the IDs in one call. drop(int) only drops ONE slot, which
// would require us to loop — dropAll handles the 2-bar-ceiling case (and any race that
// stacks 3+) in a single helper invocation.
//
// Inner hot-loop sequence:
//   1. If inventory.count(STEEL_BAR) > 0  →  dropAll(STEEL_BAR)
//                                          →  short sleepUntil(bars == 0, ~600ms)
//   2. Find nearest (within MINE_RADIUS) live iron rock object (IRONROCK1|IRONROCK2).
//   3. If already interacting AND the specific tile we're mining still has IRONROCK1/2,
//      DON'T double-click — just brief sleep and loop (lets the current swing resolve).
//   4. Else click "Mine" on the nearest iron rock. Snapshot its WorldPoint+ID.
//   5. Tight sleepUntil: exits on (a) steel bar appears, (b) our rock's tile no longer
//      has IRONROCK1/2 (depleted → became empty husk or COALROCK variant), or
//      (c) timeout ~2.4s — short because we want to pre-scan for the next rock as
//      early as possible and because iron's base mine time is 1-3 ticks with high
//      strength / good pickaxe.
//
// Why the "specific tile still has IRONROCK1/2" check and not Rs2Player.isAnimating():
//   isAnimating() keeps returning true for ~600ms after the last swing (Rs2Player.java
//   caches lastAnimationTime with a 600ms window), and first-tick-after-click it can
//   still be false even though the click is in flight. The tile-content check is
//   event-driven via the object cache: the instant the server sends a depleted-rock
//   update the queryable no longer returns IRONROCK1/2 at that tile, and we can commit
//   to the next rock. Animation-based gating would add ~600ms of dead time per rock.
//
// Pickaxe-missing failure mode: on every loop tick we check inventory+equipment for an
// item whose name contains "pickaxe" (same idiom as NemusForester's hasAxe()). If none
// is present the state machine stays in Root with a SAFE log and a longer sleep — no
// click attempts, no crash — so the user can drop a pickaxe in and we'll pick up where
// we left off. This mirrors the "fail cleanly" directive in the brief.
//
// Approximate XP/hr: with ~2.4s per rock cycle + ~1 dropAll per bar, expect roughly
// 60-75k Mining XP/hr at 75+ mining with a rune/dragon pickaxe on a 2-3 rock cluster
// (Varrock East or Mining Guild scale). Mining Guild + crystal pickaxe puts ceiling
// closer to 80-90k/hr. Throughput is bottlenecked by tick speed and respawn, not by
// the script's inner loop — that's the intent of the tile-content gating above.
// ==========================================================================================

// Iron rock object IDs (gameval). Both variants are the same rock; the game alternates
// them for visual variation — we match both.
private val IRON_ROCK_IDS = intArrayOf(
    ObjectID.IRONROCK1, // 11364
    ObjectID.IRONROCK2, // 11365
)

// Steel bar item (auto-smelt output of iron ore in Leagues).
private const val STEEL_BAR_ID = ItemID.STEEL_BAR // 2353

// Hard ceiling per the user brief: never carry more than 2 steel bars at once.
private const val MAX_STEEL_BARS = 2

// How far we search for iron rocks each tick. 6 tiles comfortably covers any standard
// 2-3 rock cluster (rocks in a cluster are typically <= 2 tiles from the player and
// from each other). Keeps the scan cheap and avoids picking up rocks in a different
// cluster across the scene.
private const val MINE_RADIUS = 6

// Tight per-swing wait. Iron mining is typically 1-3 ticks (600-1800ms) per rock with
// a competent pickaxe; with relic crit procs it can be instant. We poll early exits
// (bar appeared, rock depleted) and bail at 2400ms so a slow/missed swing doesn't
// stall the loop — the next iteration will re-select the nearest rock.
private const val SWING_TIMEOUT_MS = 2_400

// Deadline for the short wait that confirms a dropAll actually cleared the inventory
// slots before we commit to the next rock click. Dropping a single bar is one client-
// side menu invocation per slot (150-300ms each per Rs2Inventory.dropAll internal
// pacing); 2 bars max → ~600ms worst case.
private const val DROP_CLEAR_TIMEOUT_MS = 900

// Log a progress line every N bars dropped. 28 is a round inventory (historical
// banking unit) and keeps log noise low at ~60-75k/hr (~1 progress line per ~90s).
private const val PROGRESS_LOG_EVERY = 28

// ==========================================================================================
// Plugin / Script wiring
// ==========================================================================================

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Iron Power Mine",
    description = "Leagues iron power-mining: mines iron, drops auto-smelted steel bars, " +
        "caps at 2 steel bars in inventory. Start standing at any iron rock cluster " +
        "with a pickaxe equipped or in inventory.",
    tags = ["mining", "leagues", "powermine"],
    enabledByDefault = false,
)
class IronPowerMine : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = IronPowerMineScript()

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
}

class IronPowerMineScript : StateMachineScript() {
    override fun getStartState(): State = Root()
}

// ==========================================================================================
// Progress counter (total steel bars dropped this session)
// ==========================================================================================

@Volatile private var barsDropped: Long = 0

private fun recordDrop(count: Int) {
    if (count <= 0) return
    val before = barsDropped
    barsDropped += count.toLong()
    val bucketBefore = before / PROGRESS_LOG_EVERY
    val bucketAfter = barsDropped / PROGRESS_LOG_EVERY
    if (bucketAfter > bucketBefore) {
        Microbot.log("[IronPowerMine] progress: $barsDropped steel bars dropped")
    }
}

// ==========================================================================================
// Guards
// ==========================================================================================

/**
 * True if the player has a pickaxe somewhere useful — inventory OR equipment (worn).
 * Mirrors NemusForester.hasAxe(): name-contains match on "pickaxe" so every tier
 * (bronze → crystal → dragon-or etc.) is covered without hardcoding IDs.
 */
private fun hasPickaxe(): Boolean {
    val invHas = Rs2Inventory.all { it.name != null && it.name.contains("pickaxe", ignoreCase = true) }
        .isNotEmpty()
    if (invHas) return true
    // Equipment check — Rs2Equipment.isWearing(name, exact=false) does a case-insensitive
    // substring match, so "Dragon pickaxe (or)" / "Crystal pickaxe" etc. all hit "pickaxe".
    return Rs2Equipment.isWearing("pickaxe", false)
}

/** Current steel-bar count in inventory. */
private fun steelBarCount(): Int = Rs2Inventory.count(STEEL_BAR_ID)

/**
 * Snapshot of the nearest iron-rock object within MINE_RADIUS, or null if none are
 * visible (scene just loaded, all rocks depleted simultaneously, or player moved out
 * of a cluster). We return the model so the caller can read both its world location
 * and its ID for the post-click depletion check.
 */
private fun nearestIronRock(): Rs2TileObjectModel? =
    Rs2TileObjectQueryable()
        .withIds(*IRON_ROCK_IDS)
        .nearest(MINE_RADIUS)

/**
 * True if the given tile still has an iron rock on it (either IRONROCK1 or IRONROCK2).
 * Used post-click to decide "is our target still here to mine" vs "commit to the next
 * rock immediately". An iron rock becoming a coal rock variant or disappearing entirely
 * both count as "rock is gone" for our purposes.
 */
private fun ironRockStillAt(tile: WorldPoint): Boolean =
    Rs2TileObjectQueryable()
        .withIds(*IRON_ROCK_IDS)
        .where { it.worldLocation == tile }
        .first() != null

// ==========================================================================================
// States
// ==========================================================================================

/**
 * Root: single-shot dispatcher. The script has only one real state (Mine) — Root
 * exists to satisfy the StateMachineScript contract and to gate on the pickaxe
 * precondition. If there's no pickaxe, we park here and log (no crash, no clicks).
 */
private class Root : State() {
    @Volatile private var loggedStart = false

    override fun checkNext(client: Client): State? {
        if (!hasPickaxe()) return null // Stay in Root; loop() will log & sleep.
        return Mine()
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!loggedStart) {
            Microbot.log("[IronPowerMine] state: Root")
            loggedStart = true
        }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }
        // Only reason we'd land in loop() is "no pickaxe present". Log once per minute
        // so the user sees the reason without log-spam, and sleep long enough that the
        // script is essentially idle while they fix it.
        Microbot.log("[IronPowerMine] no pickaxe in inventory or equipment — idling")
        Global.sleep(Rs2Random.between(20_000, 45_000))
    }
}

/**
 * Mine: the hot loop. Each tick:
 *
 *   1. Drop steel bars if present (always cleared down to 0 before clicking the next
 *      rock — the 2-bar ceiling is enforced by never letting ANY bar accumulate).
 *   2. Pick the nearest live iron rock inside MINE_RADIUS.
 *   3. Short-circuit if we're already committed to that exact rock and still swinging
 *      (avoids cancelling our own attack).
 *   4. Otherwise click "Mine" and poll a tight exit predicate.
 *
 * State exits:
 *   - Pickaxe lost/removed → back to Root (Root will re-log and idle).
 *   - Paused → yield to pause handler.
 *
 * Returns to Root on pickaxe loss so the "fail cleanly" contract holds if the user
 * banks their pickaxe mid-run, gets disarmed by an event, etc.
 */
private class Mine : State() {
    @Volatile private var loggedStart = false

    // The tile we last clicked "Mine" on (if any). Used to distinguish "still swinging
    // the rock we committed to" vs "we haven't committed to a rock yet / it's depleted"
    // for the double-click-avoidance check.
    private var committedTile: WorldPoint? = null

    override fun checkNext(client: Client): State? {
        if (!hasPickaxe()) return Root()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!loggedStart) {
            Microbot.log("[IronPowerMine] state: Mine")
            loggedStart = true
        }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // -------- 1. Drop steel bars (always drop every one — never cap at 1 or 2). --------
        val barsBefore = steelBarCount()
        if (barsBefore > 0) {
            // dropAll(int...) drops every matching slot in one call. Internally paces
            // each drop by 150-300ms (unless naturalMouse is on, in which case the
            // natural-mouse timing takes over). For 1-2 bars that's ~150-600ms total.
            Rs2Inventory.dropAll(STEEL_BAR_ID)
            sleepUntil(checkEvery = 80, timeout = DROP_CLEAR_TIMEOUT_MS) {
                steelBarCount() == 0
            }
            val actuallyDropped = barsBefore - steelBarCount()
            recordDrop(actuallyDropped)
            // If the ceiling was breached (which should only happen under dropped-click
            // race conditions), log it — surfaces the race condition without derailing
            // the loop.
            if (barsBefore > MAX_STEEL_BARS) {
                Microbot.log(
                    "[IronPowerMine] steel-bar ceiling breach recovered: " +
                        "had $barsBefore, dropped $actuallyDropped",
                )
            }
            // After dropping, invalidate the committed tile — the drop interaction
            // cancels our swing and we need to re-commit to a rock.
            committedTile = null
            // Don't wait longer — fall through and click the next rock THIS tick.
        }

        // -------- 2. Find the nearest live iron rock. --------
        val rock = nearestIronRock()
        if (rock == null) {
            // No iron rocks visible — either cluster is all respawning, or the scene
            // hasn't loaded one yet. Very short jitter to avoid a tight spin; the
            // cache is event-driven so we'll react the moment a rock reappears.
            committedTile = null
            Global.sleep(Rs2Random.between(180, 360))
            return
        }
        val rockTile = rock.worldLocation

        // -------- 3. Double-click avoidance: if we already committed to THIS exact
        //            tile and we're still actively interacting (swinging), let the
        //            current action resolve. Re-clicking the same rock mid-swing
        //            wastes a tick and occasionally cancels the current animation. --------
        if (committedTile == rockTile && Rs2Player.isInteracting()) {
            // Short poll for early exits: bar appeared (swing resolved with loot), or
            // the rock depleted. Both signal "time to move to next rock".
            sleepUntil(checkEvery = 80, timeout = SWING_TIMEOUT_MS) {
                steelBarCount() > 0 || !ironRockStillAt(rockTile)
            }
            return
        }

        // -------- 4. Click "Mine" on the nearest rock. --------
        if (!rock.click("Mine")) {
            // Click-menu dispatch silently failed — LOS block, off-screen, menu swap,
            // or a click race. Short jitter and the next loop iteration will pick the
            // freshest nearest rock (may be a different one now).
            committedTile = null
            Global.sleep(Rs2Random.between(120, 260))
            return
        }
        committedTile = rockTile

        // -------- 5. Tight poll until one of our exit conditions. Short timeout so
        //            the next iteration can re-select a rock the moment ours depletes,
        //            instead of waiting for isAnimating() to drop (which has a
        //            600ms lag by design in Rs2Player). --------
        sleepUntil(checkEvery = 80, timeout = SWING_TIMEOUT_MS) {
            steelBarCount() > 0 || !ironRockStillAt(rockTile)
        }
    }
}
