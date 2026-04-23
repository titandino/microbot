package net.runelite.client.plugins.microbot.trent.gemstonecrab

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.EquipmentInventorySlot
import net.runelite.api.coords.WorldPoint
import net.runelite.api.gameval.NpcID
import net.runelite.api.gameval.ObjectID
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.api.npc.Rs2NpcQueryable
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.misc.SpecialAttackWeaponEnum
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import javax.inject.Inject

// ==========================================================================================
// GemstoneCrab — AFK combat re-engage + anti-logout nudge + inter-arena traversal.
//
// Scope (intentionally minimal — the crab is designed to be highly AFK):
//   1. Keep the player logged in via a jittered camera-yaw nudge well under OSRS's ~5 minute
//      input-idle auto-logout window.
//   2. If the player isn't attacking a Gemstone Crab, click Attack on the nearest one.
//   3. When the crab burrows (NPC despawns / shell spawns), immediately traverse via the
//      cave network. The Gemstone Crab rotates between three fixed arenas (Tlati Rainforest
//      North / East / South) and NEVER re-spawns in the arena it just burrowed in, so any
//      local resurface-wait is wasted time. The only delay we keep is a short debounce
//      (RESURFACE_WAIT_MS) to absorb false-positive burrow detection from transient
//      interacting-null windows between player swings. After the debounce, click the
//      nearest Cave object ("Enter" action) which the game auto-routes to the crab's
//      current location (wiki: "At each of the three mines, a cave can be crawled through
//      to quickly take players to the crab's current location" … "it is recommended to
//      travel to the nearest spawn and use the cave network, rather than world-hopping").
//      Repeat (underground → surface → underground via whichever Cave object is nearest)
//      until a live crab appears on-scene.
//
// Explicitly OUT OF SCOPE: banking, food, prayer, potions, loot, gear swaps, death recovery,
// world-hopping. The user doesn't want scope creep here.
//
// Requirements (wiki — not implemented, script assumes they're met):
//   - "Children of the Sun" quest for Varlamore access.
//   - Player must already be at one of the three Gemstone Crab arena locations (Tlati
//     Rainforest North / East / South). The "Passage to Gemstone Crab" surface entries
//     live at (1278, 3168, 0), (1351, 3124, 0), (1246, 3036, 0) per RuneLite's worldmap
//     plugin DungeonLocation.java — this script does NOT walk you to the first arena
//     from elsewhere, but once you're there it WILL follow the crab between arenas via
//     the cave network.
//
// Wiki facts that drove design:
//   - Attack speed 7 ticks, max hit 1 — long idle windows between player actions, so the
//     client's 5-min input-idle auto-logout will fire unless we nudge it.
//   - The crab stays stationary "for approximately 10 minutes" per stance, then burrows.
//     After burrowing, a "Gemstone Crab Shell" NPC (NpcID.GEMSTONE_CRAB_REMAINS = 14780)
//     lingers for 90 seconds before disappearing. The crab rotates through three fixed
//     spawn locations — crucially, NOT the arena it just burrowed in. Per the wiki, each
//     of the three surface cave entrances (and the underground arena exits) auto-routes
//     the player to the crab's current arena. We leverage that: as soon as a burrow is
//     detected (after a short debounce to absorb false positives), click the nearest
//     "Cave" object to transition to wherever the crab is now. In practice, one hop via
//     the nearest Cave object usually suffices.
//   - Players standing on the burrow tile get shoved out — we don't need to move out of
//     the way, the game does it.
//
// NPC IDs (from runelite-api/gameval/NpcID.java):
//   - GEMSTONE_CRAB          = 14779  (alive, attackable)
//   - GEMSTONE_CRAB_REMAINS  = 14780  (post-burrow shell, 90s lifetime)
//   - GEMSTONE_CRAB_DIRECTOR = 14781  (arena director NPC — NOT attackable, ignore)
//
// Anti-logout strategy:
//   Every JITTER ms (randomised ~200s–260s, well under the 300s auto-logout) we toggle the
//   camera yaw by a small delta. This calls Client.setCameraYawTarget(), which the server
//   registers as player input (same mechanism arrow-key panning uses). Low-impact visual
//   jitter, consistent with other trent scripts' style, and does NOT interrupt combat.
//
// Re-login: NOT handled by this script. The camera-yaw nudge above only defeats the client's
//   ~5-minute input-idle auto-logout; it does NOT recover from connection drops, the 6-hour
//   session timer, server restarts, or kicks. For those, enable the bundled AutoLogin
//   watchdog plugin ("Mocrosoft AutoLogin" in the plugin panel; source:
//   accountselector/AutoLoginPlugin.java). It runs its own scheduled executor that polls
//   login state independently of any bot script — survives this script being disabled,
//   crashing, or restarted, which in-script login handling would not.
// ==========================================================================================

// NPC IDs
private const val GEMSTONE_CRAB_ID = NpcID.GEMSTONE_CRAB            // 14779
private const val GEMSTONE_CRAB_SHELL_ID = NpcID.GEMSTONE_CRAB_REMAINS  // 14780

// Cave-transition object ID — the "Cave" object (menu action "Enter") that appears at all
// three surface Passage-to-Gemstone-Crab entries and at the matching exit inside each
// underground arena. Sourced from runelite-api/src/main/java/net/runelite/api/gameval/
// ObjectID1.java:68466 (CAVE_ROCK02_ENTRANCE01_GEMSTONE = 57631). The shortest-path
// transports.tsv at line 5024 confirms surface→cave routing through this object class
// (1289 3136 0 → 1245 9527 0 Enter;Cave;57591 is the Dragon Nest entry, adjacent to the
// crab arenas; all three crab passages use the same object class 57631).
private const val CAVE_ENTRANCE_ID = ObjectID.CAVE_ROCK02_ENTRANCE01_GEMSTONE  // 57631

// Radius for the Traverse state's Cave object scan. The cave entry can be a few tiles
// away inside the arena, and on the surface we may spawn a short walk from the next entry.
// 30 is generous but still excludes unrelated objects elsewhere in the scene.
private const val CAVE_SCAN_RADIUS = 30

// Maximum radius around the player to consider a crab "our crab" when engaging / waiting
// for resurface. The arena is small enough that 20 tiles comfortably covers any spawn.
private const val CRAB_SCAN_RADIUS = 20

// Short debounce before falling through Burrowed → Traverse. The crab NEVER re-spawns in
// the arena it just burrowed in — it rotates between Tlati Rainforest North / East / South
// (wiki: "at each of the three mines, a cave can be crawled through to quickly take players
// to the crab's current location" … "it is recommended to travel to the nearest spawn and
// use the cave network, rather than world-hopping"). So sitting and waiting locally is
// wasted time. This short window exists ONLY to absorb false-positive burrow detection —
// specifically the transient tick-or-two between player swings where `getInteracting()`
// can legitimately be null mid-animation-cycle without the crab actually being gone — and
// to let the cache settle after an NpcDespawned event. After that, we traverse.
private const val RESURFACE_WAIT_MS = 3_000

// Sanity cap for the Traverse state. Two cave clicks + walking between them should complete
// well inside this window; if not, something's wrong (pathing stuck, scene not updating,
// etc.) and we bail back to Root rather than spinning forever.
private const val TRAVERSE_TIMEOUT_MS = 120_000

// Anti-logout jitter window. OSRS auto-logouts after ~300 seconds of zero input. We fire
// well under that, randomised so it's not robotic. Never exceeds 270s — even in the worst
// case we stay ~30s inside the safety margin.
private const val ANTI_LOGOUT_MIN_MS = 200_000
private const val ANTI_LOGOUT_MAX_MS = 260_000

// ==========================================================================================
// Plugin / Script wiring
// ==========================================================================================

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Gemstone Crab",
    description = "AFK Gemstone Crab: re-engages, follows burrows, prevents auto-logout",
    tags = ["combat", "afk"],
    enabledByDefault = false
)
class GemstoneCrab : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = GemstoneCrabScript()

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

class GemstoneCrabScript : StateMachineScript() {
    override fun getStartState(): State = Root()
}

// ==========================================================================================
// Helpers
// ==========================================================================================

/** Returns the nearest live Gemstone Crab within CRAB_SCAN_RADIUS, or null. */
private fun nearestCrab(): Rs2NpcModel? =
    Rs2NpcQueryable()
        .withId(GEMSTONE_CRAB_ID)
        .nearest(CRAB_SCAN_RADIUS)

/** Returns the nearest post-burrow Crab Shell (lingering remains) within scan radius, or null. */
private fun nearestCrabShell(): Rs2NpcModel? =
    Rs2NpcQueryable()
        .withId(GEMSTONE_CRAB_SHELL_ID)
        .nearest(CRAB_SCAN_RADIUS)

/**
 * Returns the nearest Gemstone Crab Cave-transition object on the current scene within
 * CAVE_SCAN_RADIUS, or null. The same object class appears on both sides of the transition
 * (surface Passage-to-Gemstone-Crab and underground arena exit), which is why we can use a
 * single ID-based query regardless of which side we're on during traversal.
 */
private fun nearestCaveEntrance(): Rs2TileObjectModel? =
    Rs2TileObjectQueryable()
        .withId(CAVE_ENTRANCE_ID)
        .nearest(CAVE_SCAN_RADIUS)

/**
 * True when the local player is currently interacting with (targeting) a Gemstone Crab.
 * We combine isInCombat() — which keys off the "combat timeout" backed by the last hit
 * received/dealt event — with a direct interacting check so we're robust to the brief
 * pause right after a burrow where isInCombat() is still true but the target is gone.
 */
private fun attackingCrab(): Boolean {
    val target = Rs2Player.getInteracting() ?: return false
    return target.name?.equals("Gemstone Crab", ignoreCase = true) == true
}

// ==========================================================================================
// Anti-logout nudge
// ==========================================================================================
//
// Tracks the last-activity timestamp. We bump it every time the script itself clicks
// something (Rs2NpcModel.click) — no need to nudge if the player is actively attacking.
// The nudge itself is a ±8..16 degree camera yaw toggle-back, which the client reports
// to the server as player input, resetting the idle timer. Zero gameplay impact.

@Volatile private var lastActivityMs: Long = System.currentTimeMillis()
@Volatile private var nextNudgeMs: Long = System.currentTimeMillis() + Rs2Random.between(
    ANTI_LOGOUT_MIN_MS,
    ANTI_LOGOUT_MAX_MS
)

private fun markActivity() {
    lastActivityMs = System.currentTimeMillis()
    nextNudgeMs = System.currentTimeMillis() + Rs2Random.between(
        ANTI_LOGOUT_MIN_MS,
        ANTI_LOGOUT_MAX_MS
    )
}

// ==========================================================================================
// Special attack
// ==========================================================================================
//
// While the crab is perfectly AFK for pure DPS, the user's spec bar refills on a long timer
// regardless of whether we use it — so banking those specs into the crab is free damage.
//
// Mechanism:
//   - VarPlayerID.SA_ENERGY (varp 300, 0..1000) is the canonical spec energy signal.
//     Rs2Combat.getSpecEnergy() wraps it.
//   - The "is the spec bar currently toggled on?" state is read from the minimap spec orb
//     sprite via Rs2Combat.getSpecState() (sprite 1608 = enabled).
//   - Rs2Combat.setSpecState(true, energyRequired) handles both checks (threshold + current
//     state) and performs the widget click if needed. We just need to give it the right
//     per-weapon energy cost.
//   - Per-weapon cost comes from SpecialAttackWeaponEnum (util/misc/SpecialAttackWeaponEnum.java),
//     keyed by a case-insensitive "name contains" match — same matching style used by
//     microbot's own SpecialAttackConfigs.useSpecWeapon(). Weapons without spec (e.g. plain
//     rune scimitar) simply don't appear in the enum, so the lookup returns null and we
//     no-op — no NPE, no hardcoded fallback required.
//
// Throttle: once we've attempted to toggle, back off for SPEC_ATTEMPT_COOLDOWN_MS before
// re-checking. Prevents hammering the widget click every tick during the ~1-2 ticks it
// takes for the toggle to register in getSpecState().

private const val SPEC_ATTEMPT_COOLDOWN_MS = 1500L

@Volatile private var lastSpecAttemptMs: Long = 0L

/**
 * Looks up the currently equipped weapon's spec energy cost by name-matching against
 * [SpecialAttackWeaponEnum]. Returns null if no weapon is equipped, the weapon has no
 * entry in the enum (i.e. no special attack), or the weapon's name is unavailable.
 *
 * CRITICAL — `name` vs `getName()`:
 *   [SpecialAttackWeaponEnum] is a Java enum whose constructor's first parameter shadows
 *   `java.lang.Enum.name()` with a Lombok-generated `getName()` returning the human-readable
 *   label (e.g. "dragon scimitar"). From Kotlin, `it.name` resolves to the inherited
 *   built-in `Enum.name` property — which returns the DECLARATION identifier
 *   ("DRAGON_SCIMITAR"), NOT the field value. That made the `.contains(...)` match fail
 *   for every equipped weapon, silently returning null and disabling spec entirely.
 *
 *   We explicitly call `.getName()` (the Lombok getter) so matching compares the equipped
 *   item name against the lowercase human-readable label as intended and consistent with
 *   SpecialAttackConfigs.java which uses `specialAttackWeapon.getName()`.
 */
// Explicit .getName()/.getEnergyRequired() calls — see KDoc above: property syntax on
// `name` resolves to Enum.name and returns "DRAGON_SCIMITAR", not the Lombok field.
@Suppress("UsePropertyAccessSyntax")
private fun equippedWeaponSpecCost(): Int? {
    val weapon = Rs2Equipment.get(EquipmentInventorySlot.WEAPON) ?: return null
    val name = weapon.name?.lowercase() ?: return null
    // Match style mirrors SpecialAttackConfigs.useSpecWeapon — the enum's `name` field is
    // the canonical substring (e.g. "dragon scimitar") and the equipped item name may
    // include suffixes/prefixes like "(kp)" or "Blessed dragon scimitar". Enum order is
    // stable, so first match wins; collisions between short and long names are not
    // currently an issue in the enum.
    return SpecialAttackWeaponEnum.entries
        .firstOrNull { name.contains(it.getName().lowercase()) }
        ?.getEnergyRequired()
}

/**
 * Broader combat-gate for spec use than [attackingCrab]. Between our player's 7-tick swings
 * `Rs2Player.getInteracting()` can transiently return null mid-animation-cycle, which
 * happens to overlap exactly with the window where we'd ideally pre-arm the spec. Allow
 * spec arming whenever a live crab is on-scene AND either we're currently interacting with
 * it OR the client still considers us in combat (isInCombat is backed by the last-hit
 * timeout so it stays true across the idle gap).
 *
 * Still guarded: we require a live crab nearby — we won't spec at nothing.
 */
private fun canSpecInCombatWithCrab(): Boolean {
    if (nearestCrab() == null) return false
    return attackingCrab() || Rs2Player.isInCombat()
}

/**
 * If we're in combat with a crab and the equipped weapon's spec is affordable + not already
 * toggled, click the spec orb. Safe to call every loop tick — cheap varp reads plus a time
 * throttle guard. Does not issue an attack click; auto-retaliate / Engage's existing
 * click("Attack") flow will trigger the special on the next swing.
 */
private fun tryUseSpecialAttack() {
    if (!canSpecInCombatWithCrab()) return

    val now = System.currentTimeMillis()
    if (now - lastSpecAttemptMs < SPEC_ATTEMPT_COOLDOWN_MS) return

    val cost = equippedWeaponSpecCost() ?: return

    if (Rs2Combat.getSpecEnergy() < cost) return

    if (Rs2Combat.getSpecState()) return

    lastSpecAttemptMs = now
    // setSpecState re-checks energy + current state internally and handles the widget click.
    // We don't need the return value; if it fails transiently the next tick will retry after
    // the cooldown.
    Rs2Combat.setSpecState(true, cost)
    // Count as player activity — a spec orb click is server-visible input.
    markActivity()
}

/**
 * Fires a tiny camera yaw wiggle if we're past nextNudgeMs. Splits the nudge into a small
 * offset and a return, so the player view isn't permanently rotated. Only runs when nothing
 * else in this tick has already counted as activity.
 */
private fun maybeNudgeAntiLogout() {
    val now = System.currentTimeMillis()
    if (now < nextNudgeMs) return

    val currentYaw = Rs2Camera.getYaw()
    // Yaw is 0..2047 on OSRS; 8..16 degrees ≈ 45..91 yaw units. Sign picked randomly.
    val delta = Rs2Random.between(45, 91) * (if (Rs2Random.between(0, 1) == 0) 1 else -1)
    val bumped = ((currentYaw + delta) % 2048 + 2048) % 2048
    Rs2Camera.setYaw(bumped)
    Global.sleep(Rs2Random.between(320, 680))
    Rs2Camera.setYaw(currentYaw)
    markActivity()
}

// ==========================================================================================
// States
// ==========================================================================================

/**
 * Dispatcher. Routes to Engage, Fighting, Burrowed, or Traverse based on current combat +
 * NPC state. Assumes the user started the plugin at a Gemstone Crab arena — consistent
 * with other trent/ AFK plugins, this script will not try to walk you there from scratch.
 * Once in an arena, follow-on traversal between arenas is handled by Burrowed → Traverse
 * once the short debounce elapses; the crab never re-spawns locally after burrowing.
 */
private class Root : State() {
    @Volatile private var logged = false

    override fun checkNext(client: Client): State? {
        // Live crab + already attacking it → Fighting.
        if (attackingCrab()) return Fighting()

        // Live crab nearby but not attacking it → Engage.
        if (nearestCrab() != null) return Engage()

        // No live crab but a shell remains → Burrowed (short debounce, then Traverse).
        if (nearestCrabShell() != null) return Burrowed()

        // Neither alive nor shell visible — could be the brief post-shell window, a
        // burrowed rotation (crab is now in a different arena), or a just-logged-in scene
        // still populating. Route straight to Traverse; the user started the script, they
        // want action. Traverse has its own TRAVERSE_TIMEOUT_MS sanity cap.
        return Traverse()
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[GemstoneCrab] state: Root"); logged = true }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }
        // checkNext always hands off to a working state — this loop body runs only in
        // the rare pathological case where checkNext was called out of band. Keep the
        // anti-logout nudge alive regardless.
        maybeNudgeAntiLogout()
        Global.sleep(Rs2Random.between(1500, 3200))
    }
}

/**
 * Engage: click Attack on the nearest Gemstone Crab. Transitions to Fighting once
 * attackingCrab() reports true.
 */
private class Engage : State() {
    @Volatile private var logged = false

    override fun checkNext(client: Client): State? {
        if (attackingCrab()) return Fighting()
        if (nearestCrab() == null && nearestCrabShell() != null) return Burrowed()
        if (nearestCrab() == null && nearestCrabShell() == null) return Root()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[GemstoneCrab] state: Engage"); logged = true }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        val crab = nearestCrab()
        if (crab == null) {
            Global.sleep(Rs2Random.between(400, 900))
            return
        }

        // click("Attack") returns true when the menu action was dispatched. We treat that
        // as player activity for anti-logout purposes regardless of whether the attack
        // actually connects this tick (the server sees the click either way).
        if (crab.click("Attack")) {
            markActivity()
            sleepUntil(timeout = Rs2Random.between(4000, 6500)) {
                attackingCrab() || nearestCrab() == null
            }
        } else {
            // Click silent (LOS occlusion, menu swap, etc.) — small jitter then retry.
            Global.sleep(Rs2Random.between(420, 980))
        }
    }
}

/**
 * Fighting: idle state while the crab chews us for 1s of damage every 4.2s. We do almost
 * nothing here — the crab auto-attacks us back per wiki, and our auto-retaliate handles
 * resuming attacks. Transitions:
 *   - Our target vanishes AND a shell appears → Burrowed (debounce, then Traverse).
 *   - Our target vanishes AND no shell, no crab → Burrowed (debounce, then Traverse).
 *   - We stop attacking BUT a live crab is still nearby → Engage (re-click; auto-retaliate
 *     may have dropped or a new rotation crab came up).
 * Runs the anti-logout nudge opportunistically.
 */
private class Fighting : State() {
    @Volatile private var logged = false

    override fun checkNext(client: Client): State? {
        // Still attacking our crab → stay here.
        if (attackingCrab()) return null

        // Not attacking. If a shell is visible or no crab is around, we likely just
        // witnessed a burrow → go to Burrowed for a short debounce before traversing.
        if (nearestCrabShell() != null || nearestCrab() == null) return Burrowed()

        // A live crab is on-scene but we aren't attacking it → re-engage.
        return Engage()
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[GemstoneCrab] state: Fighting"); logged = true }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }
        // Bank free damage: if the equipped weapon has a spec and we have energy, toggle
        // the orb. Auto-retaliate / the next swing consumes it — we don't click Attack.
        tryUseSpecialAttack()
        maybeNudgeAntiLogout()
        // Short poll so checkNext() can react to burrow events quickly (the shell spawn
        // is the earliest signal we can observe).
        Global.sleep(Rs2Random.between(900, 1800))
    }
}

/**
 * Burrowed: short debounce after a detected burrow. The Gemstone Crab NEVER re-spawns in
 * the arena it just burrowed in (it rotates between three fixed arenas), so there is no
 * "local resurface" to wait for. This state exists only to absorb false-positive burrow
 * detection — specifically the transient tick-or-two between player 7-tick swings where
 * `Rs2Player.getInteracting()` can return null mid-animation-cycle without the crab
 * actually being gone — and to let the NPC cache settle after an NpcDespawned event. If
 * a live crab reappears within the debounce window (meaning we hit a false positive),
 * hand back to Root; otherwise fall through to Traverse.
 */
private class Burrowed : State() {
    @Volatile private var logged = false
    private val startMs = System.currentTimeMillis()

    override fun checkNext(client: Client): State? {
        // A live crab (re)appeared within the debounce window — false-positive burrow.
        // Let Root dispatch us back to Engage/Fighting.
        if (nearestCrab() != null) return Root()

        // Debounce elapsed — the crab has moved to a different arena. Traverse the cave
        // network to follow it.
        if (System.currentTimeMillis() - startMs > RESURFACE_WAIT_MS) return Traverse()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[GemstoneCrab] state: Burrowed"); logged = true }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // Anti-logout nudge is cheap and safe during the debounce.
        maybeNudgeAntiLogout()

        // Tight poll for the debounce — we want to exit fast in both directions (false
        // positive → Root, real burrow → Traverse). Cache is event-driven so a short
        // check interval is fine.
        sleepUntil(checkEvery = 300, timeout = 1000) {
            nearestCrab() != null
        }
    }
}

/**
 * Traverse: the crab has rotated to a different arena. Per wiki, every cave entrance
 * auto-routes the player to the crab's current location — so all we need to do is reach
 * and click the nearest Cave object, repeating as necessary until a live crab is on-scene.
 *
 * State mechanics (on every loop tick):
 *   1. If a live crab appears — success, hand off to Root (which routes to Engage).
 *   2. If we've exceeded TRAVERSE_TIMEOUT_MS — bail to Root; Root will re-dispatch to
 *      Traverse with a fresh timer if nothing's visible, avoiding an infinite traversal
 *      that never resets.
 *   3. If a Cave object is within CAVE_SCAN_RADIUS, click "Enter" and wait for walking
 *      to settle + the player's world location to shift (the surface ↔ underground hop is
 *      a large coord jump, so any meaningful delta indicates the transition fired).
 *   4. Otherwise walk toward the cave: on surface after exiting, the nearest cave entry
 *      will be within one of the three documented passage-to-gemstone-crab tiles. We
 *      let Rs2Walker route to the nearest known entry tile.
 *
 * The anti-logout nudge still runs here — a long traversal can legitimately span several
 * minutes in pathological cases (stuck on an obstacle, long auto-route path).
 */
private class Traverse : State() {
    @Volatile private var logged = false
    private val startMs = System.currentTimeMillis()

    override fun checkNext(client: Client): State? {
        // A live crab is on-scene — we've arrived. Root → Engage.
        if (nearestCrab() != null) return Root()

        // Sanity bail. If we can't complete a traversal in two minutes, something's wrong;
        // drop back to Root which will decide what to do next (typically re-enter Traverse
        // with a fresh timer, not an infinite-wait Burrowed).
        if (System.currentTimeMillis() - startMs > TRAVERSE_TIMEOUT_MS) return Root()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[GemstoneCrab] state: Traverse"); logged = true }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // Keep the idle timer healthy — traversal can take a while and we may spend
        // significant time just walking to the cave mouth.
        maybeNudgeAntiLogout()

        val playerBefore = Rs2Player.getWorldLocation() ?: return
        val cave = nearestCaveEntrance()

        if (cave != null) {
            // We have a Cave object in sight — attempt to enter it. The action on this
            // object class is "Enter" (see transports.tsv line 5024 for the canonical
            // menu option). On success the game auto-routes us to the crab's current
            // arena (wiki). On failure (out of reach, click dropped) the next loop tick
            // will either reposition us or let Rs2Walker retry.
            if (cave.click("Enter")) {
                markActivity()
                // Wait for walking to kick off and for a region change. The underground
                // arena coords live at y≈9527 while surface entries are at y≈3036..3168,
                // so a successful transition produces a >>100 tile jump — any non-trivial
                // coord delta is a good signal. Cap the total wait at ~12s to absorb
                // pathing time; the condition predicate short-circuits on success.
                Rs2Player.waitForWalking(12_000)
                sleepUntil(checkEvery = 400, timeout = 12_000) {
                    val now = Rs2Player.getWorldLocation() ?: return@sleepUntil false
                    now.distanceTo(playerBefore) > 50 || nearestCrab() != null
                }
            } else {
                // Object was visible but not clickable — usually an LOS/reachability
                // issue. Walk toward it so the next tick has a closer, cleaner target.
                Rs2Walker.walkTo(cave.worldLocation, 2)
                Rs2Player.waitForWalking(4_000)
            }
        } else {
            // No Cave object in range. This happens on the surface when we're arriving
            // from an exit and the next entry is across the map (the three passages are
            // ~40-90 tiles apart). Route to the nearest known surface entry; once within
            // CAVE_SCAN_RADIUS the next tick's nearestCaveEntrance() will pick it up.
            val target = nearestSurfacePassage(playerBefore)
            if (target != null) {
                Rs2Walker.walkTo(target, 4)
                Rs2Player.waitForWalking(6_000)
            } else {
                // Neither a scene cave nor a known surface passage is useful from here
                // (e.g. we're mid-transition or out of scope). Small idle to avoid a
                // tight spin — checkNext's timeout will eventually bail us out.
                Global.sleep(Rs2Random.between(800, 1600))
            }
        }
    }
}

/**
 * Returns the surface "Passage to Gemstone Crab" tile nearest to [anchor], or null if the
 * anchor is clearly underground (we don't want to walk to a surface-only waypoint while
 * still inside an arena — the next loop tick's Cave-object lookup handles the underground
 * exit click).
 *
 * Coordinates sourced from DungeonLocation.java:218-220
 * (runelite-client/src/main/java/net/runelite/client/plugins/worldmap/DungeonLocation.java).
 */
private fun nearestSurfacePassage(anchor: WorldPoint): WorldPoint? {
    // Y > ~5000 heuristic: the crab arenas sit in the underground region around y=9527.
    // If we're there, clicking the local Cave handles exiting — don't pathfind to a
    // surface waypoint from under the map.
    if (anchor.y > 5000) return null
    return SURFACE_PASSAGES.minByOrNull { it.distanceTo(anchor) }
}

// Three surface Passage-to-Gemstone-Crab tiles, from the RuneLite worldmap plugin's
// DungeonLocation.java (TLATI_RAINFOREST_NORTH / EAST / SOUTH). Any of these will
// auto-route us to the crab's current arena when entered.
private val SURFACE_PASSAGES = listOf(
    WorldPoint(1278, 3168, 0),
    WorldPoint(1351, 3124, 0),
    WorldPoint(1246, 3036, 0),
)
