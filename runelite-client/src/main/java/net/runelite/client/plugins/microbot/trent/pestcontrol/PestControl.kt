package net.runelite.client.plugins.microbot.trent.pestcontrol

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.Client
import net.runelite.api.coords.WorldArea
import net.runelite.api.coords.WorldPoint
import net.runelite.api.gameval.InterfaceID
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
import net.runelite.client.plugins.microbot.util.combat.Rs2Combat
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget
import net.runelite.api.EquipmentInventorySlot
import javax.inject.Inject

// ==========================================================================================
// PestControl — combat-first Veteran boat bot.
//
// User brief (paraphrased): the plugin-hub Pest Control bot AFKs too much and fails the
// activity-meter threshold, wasting games with zero points. This replacement prioritizes
// combat: join the Veteran lander, wait in the lobby, fight as hard as possible while
// in-game, collect rewards, re-queue. No AIO walker gymnastics, no banking, no gear swaps,
// no prayer flicking.
//
// Scope (intentionally narrow):
//   1. GoToBoat   — single Rs2Walker.walkTo() hop to the Veteran lander pier at Port Sarim,
//                   then click the Gangplank. Teleports us to the lobby island.
//   2. Lobby      — stand still. The game auto-teleports us to the island when the match
//                   starts (>= 5 players + 50-tick cooldown). We poll the region + the
//                   Pest Control HUD widget for the transition.
//   3. InGame     — HOT LOOP. Re-target every ~2-3s. Priority: attackable portal > spinner
//                   > brawler > any other pest. Never walks toward distant mobs if a close
//                   one exists. Karambwan-only eat guard at <50% HP so we don't die instantly.
//
// On match end we INTENTIONALLY skip any squire / reward-claiming flow. Commendation
// points are awarded by the server automatically; the squire dialogue is solely for
// spending accumulated points (XP lamps, void armor, etc.) — not something an
// automation run cares about mid-grind. Every second in dialogue is a second not
// earning more points in the next match. So match end → re-dispatch via Root, which
// correctly routes to Lobby (we teleport onto the lander) or GoToBoat (fallback).
//
// Explicitly out of scope: banking, food withdraw, prayer, specs, gear, auto-login, death
// recovery, world-hopping. User said: "if the player dies or runs out of food, that's their
// problem". We do eat a Karambwan from the inventory when HP drops below 50% in the combat
// loop so the script doesn't stat-die immediately — that's the one concession, per the
// brief. Users pre-stock the inventory with as many karambwans as they want.
//
// Why veteran (100+ combat): user said so. All boat-tier-specific IDs below are the "3"
// variants — PEST_LANDER_GANGPLANK_3 (id 25632), PEST_VOIDKNIGHT_3 (id 1757).
//
// ==========================================================================================
// Research notes & sources (so future maintenance can verify without re-reading everything):
//
// Portal NPCs  (runelite-api/src/main/java/net/runelite/api/gameval/NpcID.java:8270-8345):
//   - Purple/Blue/Yellow/Red attackable (small boat):   1739-1742  (PEST_PORTAL_N_ACTIVE)
//   - Purple/Blue/Yellow/Red shielded   (small boat):   1743-1746  (PEST_PORTAL_SHIELD_N_ACTIVE)
//   - Purple/Blue/Yellow/Red attackable (BT1 / bigger): 1747-1750  (PEST_PORTAL_N_ACTIVE_BT1)
//   - Purple/Blue/Yellow/Red shielded   (BT1 / bigger): 1751-1754  (PEST_PORTAL_SHIELD_N_ACTIVE_BT1)
//
//   The BT1 (bigger-tier) IDs are the Veteran lander's portals. We include BOTH sets in
//   the attackable query as a defensive measure — OSRS has occasionally swapped which
//   tier spawns which variant, and the cost of targeting a wrong-set portal is zero
//   (it simply won't be found in-scene). Shielded IDs are explicitly NOT targeted.
//
// Pest monsters (NpcID.java:8020-8265). These come in 5 tiers (1-5) and some have A/B
// variants per tier. We hardcode the full union for BOTH tier 4 and tier 5 (Veteran-tier
// pests) plus lower tiers as a backstop. Tier-5 is the tough bracket but Veteran boat
// mixes 4s and 5s, so covering both is right:
//   SPINNER_1..5          1709..1713    (heals portals — HIGHEST prio after attackable portal)
//   BRAWLER_1..5          1734..1738    (blocks tiles; killing one opens up movement)
//   RAVAGER_1..5          1704..1708    (attacks barricades/gates; incidental XP)
//   SPLATTER_1..5         1689..1693    (low HP; easy points)
//   SHIFTER_1..5 A/B      1694..1703    (teleports, attacks knight directly)
//   TORCHER_1..5 A/B      1714..1723    (ranged attacks portals; disruptive)
//   DEFILER_1..5 A/B      1724..1733    (ranged; disruptive)
//
// Gangplank  (runelite-api/gameval/ObjectID.java):
//   Veteran-specific symbolic IDs DO exist — three per-boat-tier gangplank objects:
//     - PEST_LANDER_GANGPLANK   = 14315  (Novice)                          (line 46670)
//     - PEST_LANDER_GANGPLANK_2 = 25631  (Intermediate)                    (line 79718)
//     - PEST_LANDER_GANGPLANK_3 = 25632  (Veteran)                         (line 79723)
//   We use 25632 (PEST_LANDER_GANGPLANK_3). The in-game menu action is "Cross Gangplank"
//   (NOT just "Cross") — confirmed from an in-game screenshot at position (2637, 2651, 0).
//
//   Historical / AVOID-THIS note: the previous implementation used PEST_GANGPLANK_ON
//   (id 14284, ObjectID.java:46603). That is a DIFFERENT object — likely an auxiliary
//   "gangplank on" state for the novice boat — and NOT the Veteran lander gangplank.
//   Do not revert to 14284; it won't resolve at the Veteran pier.
//
// Region IDs  (cross-referenced from runelite-client/src/main/java/net/runelite/client/
// plugins/discord/DiscordGameEventType.java):
//   - 10537  Void Knights' Outpost        (the lobby island, where the boat drops us)
//   - 10536  Pest Control                 (the actual island where the game happens)
//   - anywhere-else                       (probably Port Sarim — mainland, need to travel)
//   The classic Port Sarim pier region isn't a named DiscordAreaType constant; we handle
//   the "not in outpost and not in island" case by just walking back to the Veteran pier.
//
// Game-start/end detection — two complementary signals, either triggers transition:
//   (A) Region change:
//         region 10536  →  InGame       (always indicates we're on the island)
//         region 10537  →  Lobby        (always indicates we're on the outpost)
//         region anywhere-else  →  GoToBoat  (we need to re-queue)
//   (B) HUD widget:  InterfaceID.PestStatusOverlay.PEST_STATUS_PORT2 is non-null iff
//         we're in an active match. This is EXACTLY the signal the canonical RuneLite
//         pestcontrol plugin uses (PestControlOverlay.java:67). We use it as a secondary
//         confirmation during InGame to avoid edge cases where the region change lags
//         the teleport by a tick or two.
//
// Shield drop detection — we DON'T need the chat-message parser from the RuneLite plugin
// (PestControlPlugin.java:99-108). We can just scan the NPC cache directly:
//   - An attackable portal ID present in the scene  →  it's attackable (no shield).
//   - Only shielded portal IDs in scene             →  attack pests instead; waiting for
//                                                      shields to drop is the game's job.
// This is simpler and more robust than maintaining a rotation table.
//
// AFK-kick rule — OSRS pest control kicks any player who doesn't attack anything within
// ~8-10 seconds of the match starting, AND per-match if their activity meter hits zero.
// Our strategy: click Attack every time we're idle (not in combat and not interacting).
// Re-target cadence is ~1.5s sleepUntil on "target dead OR not attacking OR spinner spawned"
// which is well inside the AFK threshold. Details in InGame.loop().
// ==========================================================================================

// ------------------------------------------------------------------------------------------
// Hardcoded top-level constants (match Pickpocketer TARGET / FOOD_NAME style).
// ------------------------------------------------------------------------------------------

// Veteran Pest Control pier tile (Port Sarim). VERIFIED from an in-game screenshot AND
// (historically) a live user log of an unfiltered name-based "Gangplank" scan. The
// gangplank object itself sits at (2637, 2653, 0) with id=25632 and actions=[Cross].
// (The diagnostic scan itself has been retired — it was a ~22s startup blocker because a
// name-filtered query forces lazy-load of every ObjectComposition in the scene. The
// ID-indexed query in GoToBoat.loop is int-compared and cheap.)
// DO NOT "correct" this back to (2632, 2648) — that value came from MinigameLocation.java:66
// (PEST_CONTROL_VETERAN), which is a worldmap waypoint/centroid, NOT the actual gangplank
// tile.
//
// Veteran pier tile. Deliberately 2 tiles south of the gangplank object at (2637, 2653) —
// the gangplank tile itself may be blocked/non-walkable for pathing. Walking to (2637, 2651)
// with a 3-tile tolerance lands us close enough to click the gangplank object. Last round's
// attempt to use (2637, 2653) broke the flow entirely — do NOT change this back without
// testing.
private val VETERAN_PIER = WorldPoint(2637, 2651, 0)

// The Veteran lander gangplank — one symbolic id per boat tier exists, and _3 is Veteran.
// See ObjectID.java line 79723. The in-game menu action is "Cross Gangplank".
private const val GANGPLANK_ID = ObjectID.PEST_LANDER_GANGPLANK_3  // 25632

// Menu action string for the gangplank. Screenshot confirms the full action is
// "Cross Gangplank" — NOT just "Cross". Using the wrong action string silently no-ops.
private const val GANGPLANK_ACTION = "Cross Gangplank"

// Void Knight NPC id on the Veteran combat island. There are four PEST_VOIDKNIGHT_N ids in
// NpcID.java:8350-8365 — one per boat tier (Novice=1, Intermediate=2, Veteran=3, Elite=4).
// We walk toward him on InGame entry so the player starts combat from the center of the
// island, not the southern spawn edge. The _CONTROL* variants (NpcID.java:13933-13948) are
// the commendation-reward NPCs at the outpost and are NOT what we want here.
private const val VETERAN_VOID_KNIGHT_ID = NpcID.PEST_VOIDKNIGHT_3  // 1757

// Region IDs.
//   10536 = Pest Control island (unambiguous — only exists inside an active match).
//   10537 = Void Knights' Outpost — BUT this region covers BOTH the mainland pier AND
//           the moored landers. The Discord plugin's CITY_VOID_OUTPOST label for 10537
//           is just the umbrella name, not "lobby".
//   Because region 10537 is ambiguous we have TWO complementary ways to distinguish
//   "standing on the pier waiting to cross" from "standing on the lander waiting for
//   the match to start":
//     (1) VETERAN_LANDER_AREA — a bounding-box check around the lander deck.
//     (2) recentlyCrossedGangplank — a latch set when the "Cross Gangplank" click
//         succeeds, cleared when the match starts (InGame entry) or the player walks
//         back off the lander. This is the belt-and-suspenders fallback for when the
//         bounding box is slightly off — a successful gangplank click PUTS us on the
//         lander by definition, so we trust that signal regardless of coords.
private const val REGION_OUTPOST = 10537
private const val REGION_ISLAND = 10536

// Veteran lander deck bounding box — "on the lander, waiting for match to start".
//
// CRITICAL: this box MUST NOT include ANY pier tile. The pier runs south from the
// gangplank at (2637, 2651, 0) to the mainland (y decreasing) and also extends east of
// the lander. If the box overlaps the pier, Root dispatches to Lobby while the user is
// still standing on the mainland/pier side — and Lobby then waits indefinitely for the
// match to start, which never happens because we never actually crossed onto the deck.
// Dead lock. That was the regression: the previous (2634, 2652, 6, 4, 0) box covered
// x ∈ [2634, 2639], y ∈ [2652, 2655], which swept up pier tiles EAST of the lander —
// e.g. the player at (2638, 2653) wrongly reported onLander=true, triggering the
// Lobby→dead-wait loop.
//
// Coordinate sourcing: verified IN-GAME by the user (not derived from any RuneLite
// source, plugin, or screenshot heuristic). The user stood on the actual corners of
// the Veteran lander deck and read off the WorldPoint values directly:
//   - Bottom-left (SW) corner: (2632, 2649, 0)
//   - Top-right   (NE) corner: (2635, 2654, 0)
// ⇒ x ∈ [2632, 2635] (width 4), y ∈ [2649, 2654] (height 6), plane 0.
//
// Sanity check vs. known pier geometry: the gangplank at (2637, 2651) sits at x=2637,
// which is >= 2636 and therefore OUTSIDE this bbox (x max is 2635). The pier tiles east
// of the gangplank — e.g. (2638, 2653), the tile that previously false-positived —
// also sit at x=2638, far east of the bbox's eastern edge at 2635. Both are correctly
// excluded, which is the correctness requirement.
//
// The `recentlyCrossedGangplank` latch below remains the belt-and-suspenders fallback:
// a successful "Cross Gangplank" click puts us on the lander by definition, and the
// latch stays true until InGame clears it. The bbox is now authoritative (user-verified
// corners) but the latch stays as a second signal in case of edge cases.
//
// DO NOT widen this eastward past x=2635 — that immediately reintroduces the
// east-pier-false-positive regression we just fixed.
private val VETERAN_LANDER_AREA = WorldArea(2632, 2649, 4, 6, 0)

// Tiles from player — only engage pests inside this radius, NEVER walk across the island
// to hit a distant mob. The Void Knight island is small; 12 tiles comfortably covers
// "everything around the center knight" without drifting to far-side portals. This is the
// SCENE-SCAN radius; the weapon-specific attack range (see AttackProfile) is the gating
// check applied inside `canAttackWithProfile` — so a melee player scanning 12 tiles still
// only engages pests within 1-2 tiles, while a blowpipe user engages out to ~7.
private const val NEAR_COMBAT_RADIUS = 12

// Maximum tiles to scan for portals. Portals are at fixed compass locations on the
// island; 40 tiles covers all four from the spawn point. Same caveat as NEAR_COMBAT_RADIUS:
// this is just how far we LOOK for candidates; `canAttackWithProfile` applies the actual
// weapon range as the gating filter before we try to attack.
private const val PORTAL_SCAN_RADIUS = 40

// User-escape-hatch: hardcode an attack range if auto-detection misbehaves. Keep at -1
// (disabled) for the normal case — Rs2Combat.getAttackRange() does the right thing for
// every weapon present in WeaponsGenerator's WEAPONS_MAP, and the name-based fallback
// below handles anything missing. Set to e.g. 7 to force "blowpipe-like" range.
private const val ATTACK_RANGE_OVERRIDE: Int = -1

// Default attack ranges per style bucket when auto-detection can't resolve a weapon.
// Melee defaults to 1 (spear/sword) rather than 2 (halberd) — halberd-specific range is
// not worth a keyword lookup for Pest Control where nearly all melee setups are sub-2.
private const val DEFAULT_MELEE_RANGE = 1
private const val DEFAULT_RANGED_RANGE = 7
private const val DEFAULT_MAGIC_RANGE = 10

// Re-target poll in the hot loop. Short enough we never breach the AFK-kick window
// (~8-10s per wiki). 1500ms is the sweet spot: long enough one attack lands, short
// enough we notice a portal-shield drop immediately.
private const val RETARGET_POLL_MS = 1500

// How long Lobby waits between polls. Kept TIGHT (<=900ms) so the moment the game-start
// teleport fires, we detect it within a single poll and transition to InGame before the
// player has wasted multiple ticks standing on the now-empty lander deck. The earlier
// 2000-3200ms window was idle-friendly but let the transition drift by 1-3s in the worst
// case; the wakeup-latency cost of a shorter poll is negligible compared to an AFK tick
// on the combat island.
private const val LOBBY_POLL_MIN_MS = 600
private const val LOBBY_POLL_MAX_MS = 900

// Lobby-diagnostic throttle: how long between dispatch-debug log lines while idling in
// the lobby. One line every ~3s is informative for "stuck in lobby" bug reports without
// spamming the chatbox.
private const val LOBBY_DIAG_LOG_INTERVAL_MS = 3_000L

// InGame empty-target tick: how long to sleep when pickTarget() returns null mid-match.
// Kept SHORT (<=800ms) so if a mob just died and the next one is one tick away from
// spawning, we re-scan and engage immediately. The earlier 400-900ms window is preserved
// but codified as a named constant so future "why is the bot AFKing between kills?"
// questions have an obvious knob.
private const val EMPTY_TARGET_SLEEP_MIN_MS = 400
private const val EMPTY_TARGET_SLEEP_MAX_MS = 800

// Combat-zone approach — on InGame entry, walk toward the Void Knight until we're within
// this radius. From there, all four portals (fixed compass positions on the island) and
// the bulk of pest spawns are inside pickTarget()'s scan radii so the combat loop "just
// works". 8 tiles is close enough we're mid-action but far enough we don't bodyblock the
// Void Knight's own defenders.
private const val COMBAT_ZONE_RADIUS = 8

// Mid-walk combat interrupt radius — if a valid target appears this close while we're
// still walking to the Void Knight, short-circuit the walk and attack instead. This is
// the user's explicit fix for "if a pest spawns on top of us we should attack, not
// finish the walk". 8 tiles puts us well inside melee/ranged/magic easy-cast range.
private const val WALK_INTERRUPT_ATTACK_RADIUS = 8

// Approach-phase bailout timer. On match start we walk toward the Void Knight before
// engaging (see InGame.loop). If the knight is gated off behind a closed fence (very
// possible at match start — the compound fences are shut until defenders unlock them),
// Rs2Walker.walkTo would loop indefinitely trying to path through a wall and we'd AFK
// through the opening seconds of the match. After this timeout we commit to the combat
// loop wherever we are and let `pickTarget` find whatever is reachable. 20s is comfortably
// inside the AFK-kick window and gives the walker plenty of room for a clean approach
// when nothing's gated.
private const val APPROACH_TIMEOUT_MS = 20_000L

// Gate-watch log throttle. When every nearby pest / portal is on the far side of a closed
// gate, pickTarget returns null tick-after-tick and we'd spam the log if we emitted per
// call. Once every ~5s is frequent enough to see in real-time but not noisy enough to
// drown the rest of the InGame diagnostics. Matches the `LOBBY_DIAG_LOG_INTERVAL_MS` shape.
private const val GATED_TARGET_LOG_INTERVAL_MS = 5_000L

// Void Knight fallback WorldPoint — used if the NPC query fails (scene still loading,
// NPC cache not yet populated on the first tick after teleport). Region 10536 decodes to
// regionX=41, regionY=40, so the region's base world coords are (41*64, 40*64) = (2624,
// 2560) and the center is (2656, 2592, 0). The portal coords in the RuneLite reference
// plugin (pestcontrol/Portal.java:41-44) cluster their region-local coords at roughly
// (8,30), (55,29), (48,13), (22,12) → center (33, 21) → world (2657, 2581). Midpoint
// between map-center and portal-centroid is close enough to the Void Knight's actual
// spawn to serve as a pathing target; the Void-Knight-NPC query is the primary source.
private val VOID_KNIGHT_FALLBACK = WorldPoint(2656, 2591, 0)

// Portal NPC id groups. Defensive union of BT1 (Veteran) + non-BT1 (lower tier) —
// matching a wrong-set id is a no-op, so overmatching costs nothing and protects us
// against Jagex swapping tier definitions.
private val ATTACKABLE_PORTAL_IDS = intArrayOf(
    // Standard portals
    NpcID.PEST_PORTAL_1_ACTIVE,      // 1739
    NpcID.PEST_PORTAL_2_ACTIVE,      // 1740
    NpcID.PEST_PORTAL_3_ACTIVE,      // 1741
    NpcID.PEST_PORTAL_4_ACTIVE,      // 1742
    // BT1 (Veteran / bigger-tier) portals
    NpcID.PEST_PORTAL_1_ACTIVE_BT1,  // 1747
    NpcID.PEST_PORTAL_2_ACTIVE_BT1,  // 1748
    NpcID.PEST_PORTAL_3_ACTIVE_BT1,  // 1749
    NpcID.PEST_PORTAL_4_ACTIVE_BT1,  // 1750
)

// Spinner NPC ids. Spinners heal portals — kill them FIRST if a portal is attackable,
// because a spinner regen tick undoes our portal progress.
private val SPINNER_IDS = intArrayOf(
    NpcID.PEST_SPINNER_1,  // 1709
    NpcID.PEST_SPINNER_2,  // 1710
    NpcID.PEST_SPINNER_3,  // 1711
    NpcID.PEST_SPINNER_4,  // 1712
    NpcID.PEST_SPINNER_5,  // 1713
)

// Brawler NPC ids. Brawlers block tiles; killing them opens up movement for the rest
// of the team. Second-priority target after spinner.
private val BRAWLER_IDS = intArrayOf(
    NpcID.PEST_BRAWLER_1,  // 1734
    NpcID.PEST_BRAWLER_2,  // 1735
    NpcID.PEST_BRAWLER_3,  // 1736
    NpcID.PEST_BRAWLER_4,  // 1737
    NpcID.PEST_BRAWLER_5,  // 1738
)

// All other pest monster ids — everything we might hit if nothing high-value is close.
// Includes Ravager, Splatter, Shifter A/B, Torcher A/B, Defiler A/B across all tiers.
private val OTHER_PEST_IDS = intArrayOf(
    // Ravagers
    NpcID.PEST_RAVAGER_1, NpcID.PEST_RAVAGER_2, NpcID.PEST_RAVAGER_3, NpcID.PEST_RAVAGER_4, NpcID.PEST_RAVAGER_5,
    // Splatters
    NpcID.PEST_SPLATTER_1, NpcID.PEST_SPLATTER_2, NpcID.PEST_SPLATTER_3, NpcID.PEST_SPLATTER_4, NpcID.PEST_SPLATTER_5,
    // Shifters
    NpcID.PEST_SHIFTER_1A, NpcID.PEST_SHIFTER_1B,
    NpcID.PEST_SHIFTER_2A, NpcID.PEST_SHIFTER_2B,
    NpcID.PEST_SHIFTER_3A, NpcID.PEST_SHIFTER_3B,
    NpcID.PEST_SHIFTER_4A, NpcID.PEST_SHIFTER_4B,
    NpcID.PEST_SHIFTER_5A, NpcID.PEST_SHIFTER_5B,
    // Torchers
    NpcID.PEST_TORCHER_1A, NpcID.PEST_TORCHER_1B,
    NpcID.PEST_TORCHER_2A, NpcID.PEST_TORCHER_2B,
    NpcID.PEST_TORCHER_3A, NpcID.PEST_TORCHER_3B,
    NpcID.PEST_TORCHER_4A, NpcID.PEST_TORCHER_4B,
    NpcID.PEST_TORCHER_5A, NpcID.PEST_TORCHER_5B,
    // Defilers
    NpcID.PEST_DEFILER_1A, NpcID.PEST_DEFILER_1B,
    NpcID.PEST_DEFILER_2A, NpcID.PEST_DEFILER_2B,
    NpcID.PEST_DEFILER_3A, NpcID.PEST_DEFILER_3B,
    NpcID.PEST_DEFILER_4A, NpcID.PEST_DEFILER_4B,
    NpcID.PEST_DEFILER_5A, NpcID.PEST_DEFILER_5B,
)

// ==========================================================================================
// Plugin / Script wiring (matches Pickpocketer shape — simple while-loop on a coroutine).
// ==========================================================================================

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Pest Control",
    description = "Combat-first Pest Control (Veteran boat): attack portals and pests aggressively",
    tags = ["combat", "minigame"],
    enabledByDefault = false,
)
class PestControl : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private lateinit var script: PestControlScript

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            // Recreate the script on every startUp so `state` = fresh Root() and `stopped`
            // = false. RuneLite doesn't recreate the Plugin instance between enable/disable
            // cycles, so a class-level `val script = PestControlScript()` would persist
            // stale state (e.g. mid-match InGame) across restarts — the new coroutine would
            // then try to execute InGame.loop while not actually in a match and hang.
            // Rebuilding the script here nukes `state`, `stopped`, and any future-added
            // fields in one line, with no risk of forgetting a field.
            script = PestControlScript()

            // Latches / timestamps / anything file-scope that survives plugin disable → re-enable.
            // Reset explicitly so a fresh run starts in a clean state. File-scope `@Volatile`
            // vars live in the package-level companion; they outlive PestControlScript and
            // would otherwise carry truthy/non-zero values from the previous run.
            recentlyCrossedGangplank = false
            lastGatedTargetLogMs = 0L
            lastOutOfRangeLogMs = 0L
            lastGoToBoatLogMs = 0L

            Microbot.log("[PestControl] plugin started (script rebuilt, latches cleared)")
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running && !script.stopped) {
            script.loop(client)
        }
    }

    override fun shutDown() {
        running = false
        // Don't wait for the coroutine to exit here — it'll exit on its next loop iteration
        // when it checks `while (running)`. If someone rapid-fires disable/enable, the new
        // startUp() rebuilds script/state from scratch so the tail-end of the old coroutine
        // can't corrupt the new run (it's looping against the OLD script instance, whose
        // state transitions are now irrelevant — the new coroutine runs against the new
        // script instance only). The state recreation in startUp is what makes rapid-restart
        // safe; nothing to clean up here.
    }
}

class PestControlScript : StateMachineScript() {
    override fun getStartState(): State = Root()
}

/**
 * Latched flag: true from the moment we successfully click "Cross Gangplank" until either
 * (a) the match starts (InGame entry clears it) or (b) we can confirm we're not on the
 * lander anymore (region change away from the outpost). This is a belt-and-suspenders
 * fallback for `isOnVeteranLander()`: if our bounding-box coords are slightly off, a
 * successful gangplank click puts us on the lander by definition, and we can treat that
 * as authoritative until the next state transition.
 *
 * Why a latch rather than a bigger/looser bounding box: a looser box would risk matching
 * the mainland pier tile, which would break the Lobby→GoToBoat recovery path.
 */
@Volatile private var recentlyCrossedGangplank: Boolean = false

// ==========================================================================================
// Helpers
// ==========================================================================================

/**
 * True while the in-game HUD is showing (we're actually playing a match). This is the
 * exact signal the canonical RuneLite pestcontrol plugin uses (PestControlOverlay.java:67).
 * Robust against tick-lag between teleport and region update.
 */
private fun isInPestControlGame(): Boolean =
    Rs2Widget.getWidget(InterfaceID.PestStatusOverlay.PEST_STATUS_PORT2) != null

/**
 * True iff the player is currently standing on the Veteran lander deck (waiting-for-match).
 *
 * Signal composition (either is sufficient):
 *   (a) Bounding box: VETERAN_LANDER_AREA.contains(here). Authoritative when coords align,
 *       best-effort otherwise — the box is a conservative guess post-screenshot-fix.
 *   (b) `recentlyCrossedGangplank` latch + still in the outpost region. If we successfully
 *       clicked "Cross Gangplank" and haven't left the outpost region yet, we're on the
 *       lander by definition. This protects us against a slightly-off bounding box.
 *
 * Note: region 10537 alone is NOT sufficient — the mainland pier is also in region 10537.
 */
private fun isOnVeteranLander(): Boolean {
    val here = Rs2Player.getWorldLocation() ?: return false
    if (VETERAN_LANDER_AREA.contains(here)) return true
    // Belt-and-suspenders: if we just crossed and we're still in the outpost region, trust
    // the latch. Guarded by region check so a teleport mid-lobby doesn't lie to us.
    if (recentlyCrossedGangplank && here.regionID == REGION_OUTPOST) return true
    return false
}

/** True iff the player's current region is the active Pest Control island. */
private fun onIsland(): Boolean =
    Rs2Player.getWorldLocation()?.regionID == REGION_ISLAND

/**
 * Combined "match is live" signal: either the HUD widget is showing OR we're on the
 * island. The `||` (OR) is load-bearing — EITHER signal alone trips the transition so
 * tick-lag between the teleport firing and the HUD widget inflating cannot strand us in
 * Lobby for extra ticks. The canonical RuneLite pestcontrol plugin uses the HUD signal
 * alone (PestControlOverlay.java:67) and is occasionally late by ~1 tick; the region
 * check races it and whichever fires first wins.
 */
private fun isInActiveMatch(): Boolean =
    isInPestControlGame() || onIsland()

/**
 * Returns the nearest Void Knight NPC on the Veteran combat island, or null.
 *
 * Deliberately NOT reachability-filtered — we use this as a walk-target during the
 * approach phase, which might itself be blocked by a closed gate at match start. The
 * approach code already has a bailout (APPROACH_TIMEOUT_MS) so a truly unreachable
 * Void Knight just commits us to the combat loop in place, which is the right behavior.
 */
private fun findVoidKnight(): Rs2NpcModel? =
    Rs2NpcQueryable()
        .withId(VETERAN_VOID_KNIGHT_ID)
        .nearest(PORTAL_SCAN_RADIUS)

// ------------------------------------------------------------------------------------------
// Attack-style + range detection.
//
// The previous implementation used `.nearestReachable(radius)` everywhere, which delegates
// to `Rs2Reachable.isReachable(...)` — pathfinding BFS over the collision maps. That's the
// RIGHT check for melee (you walk to the target) but the WRONG check for ranged/magic:
// arrows and spells fly over gates and waist-high fences that block walking, so the BFS
// was excluding perfectly valid ranged/magic targets and we appeared to AFK while portals
// sat wide-open across a closed fence.
//
// Fix: resolve the current combat style + weapon range, then pick the correct filter:
//   - MELEE  → pathfinding reachability (current behavior, correct: we walk to the target).
//   - RANGED → projectile line-of-sight + weapon range (distance gate + LOS gate).
//   - MAGIC  → same as RANGED.
//
// Range detection: `Rs2Combat.getAttackRange()` (util/combat/Rs2Combat.java:199-220) resolves
// range via the WEAPONS_MAP lookup (WeaponsGenerator.java) using varbit COM_MODE / varbit
// COMBAT_WEAPON_CATEGORY. Returns 1 if the weapon isn't mapped OR the style resolves to
// melee. Style detection: `Rs2Combat.getWeaponAttackStyle()` returns the human-readable
// style name ("Accurate"/"Aggressive"/"Defensive"/"Controlled" for melee; "Accurate"/
// "Rapid"/"Longrange" for ranged; "Accurate"/"Longrange"/"Defensive" for magic staves).
// Style name alone can't tell melee from ranged/magic definitively — "Accurate" is
// shared — so we use a two-layer classifier:
//   (1) Fast path: `Rs2Combat.getAttackRange()` returns the authoritative range AND the
//       known-weapon branch in that function already knows whether the weapon is Melee /
//       Ranged / Staff / Wand / PoweredStaff via the Weapon subclass. We can't read the
//       subclass directly (WEAPONS_MAP is package-private), but a range > 1 is a strong
//       signal the weapon is ranged/magic.
//   (2) Slow-path / fallback: weapon-name keyword bucket (bow, xbow, blowpipe, staff,
//       wand, dart, knife, thrownaxe, chinchompa). Handles the "weapon not in WEAPONS_MAP"
//       case where Rs2Combat.getAttackRange() returns a conservative 1.
//
// LoS helper: `WorldArea.hasLineOfSightTo(WorldView, WorldPoint)` (runelite-api/.../
// coords/WorldArea.java:647) — takes the NPC's WorldArea and checks line of sight to the
// player's WorldPoint through the scene's collision data. This is exactly what
// `Rs2NpcModel.hasLineOfSight()` (api/npc/models/Rs2NpcModel.java:164-177) uses — we
// mirror its shape here but inline it so the range check short-circuits before the LOS
// walk (cheaper: one distanceTo vs one Bresenham ray-cast).
// ------------------------------------------------------------------------------------------

private enum class AttackStyle { MELEE, RANGED, MAGIC }

private data class AttackProfile(val style: AttackStyle, val range: Int)

/**
 * Resolves the current attack style + range from the equipped weapon.
 *
 * RANGE IS THE PRIMARY SIGNAL — name-based classification has historically misfired
 * whenever a user's weapon name didn't match our keyword list (e.g. Armadyl c'bow was
 * missed because "Armadyl" has no bow/xbow token... until you lowercase, but then
 * "Zaryte c'bow" or a future weapon can also miss). The bug symptom: name-classifier
 * reports MELEE, everything gets gated through pathfinding reachability (`isReachable`),
 * and the bot appears frozen even when perfectly fine ranged targets are in front of it.
 *
 * Rules (fail-safe toward LoS branch when in doubt about range):
 *   (1) If ATTACK_RANGE_OVERRIDE >= 1, honor that. User's escape hatch.
 *   (2) Otherwise ask `Rs2Combat.getAttackRange()`. That call consults WEAPONS_MAP,
 *       which encodes the range for every known weapon in the game. Melee weapons
 *       return 1 (or 2 for halberd). Bows/xbows/blowpipes return their projectile range.
 *       Staves/wands return their cast range.
 *   (3) If the resolved range > 1, branch RANGED (LoS gate). We don't care whether
 *       it's technically magic or ranged — the LoS branch handles both identically.
 *   (4) If the resolved range <= 1 OR the lookup threw, default to MELEE with range 1.
 *
 * The name classifier is KEPT but downgraded to a *log hint only* — it lets us tag the
 * log with "detected weapon: ..." for reader sanity, but the actual branch decision is
 * driven entirely by the numeric range. This is more robust than string matching.
 */
private fun currentAttackProfile(): AttackProfile {
    // User override wins unconditionally.
    if (ATTACK_RANGE_OVERRIDE >= 1) {
        return AttackProfile(
            style = if (ATTACK_RANGE_OVERRIDE > 1) AttackStyle.RANGED else AttackStyle.MELEE,
            range = ATTACK_RANGE_OVERRIDE,
        )
    }

    val combatRange = try {
        Rs2Combat.getAttackRange()
    } catch (_: Throwable) {
        0
    }

    return if (combatRange > 1) {
        // Ranged or magic — we don't actually need to distinguish because the LoS branch
        // handles both. `AttackStyle.RANGED` is purely a notional label for logging.
        AttackProfile(style = AttackStyle.RANGED, range = combatRange)
    } else {
        // Range <= 1 or lookup failed. Default to MELEE/1 — `canAttackWithProfile` will
        // gate via pathfinding reachability, matching historical behavior for unarmed /
        // melee setups.
        AttackProfile(style = AttackStyle.MELEE, range = DEFAULT_MELEE_RANGE)
    }
}

/**
 * Name-based style classifier. PURELY A LOG HINT now — the actual branch decision in
 * `currentAttackProfile()` is gated on the numeric range from `Rs2Combat.getAttackRange()`.
 * String matching is fragile (every new weapon with a non-obvious name slips through the
 * keyword list), whereas WEAPONS_MAP encodes the exact range per weapon ID. This function
 * exists solely so the diagnostic log can tag "detected weapon X" for reader sanity when
 * chasing down "why is the bot idling" questions.
 *
 * Lowercased substring matching on the weapon name — deliberately dumb and fast. Order of
 * checks matters: "bow" must come after "blowpipe" (and similar), because "blowpipe"
 * contains "bow". Keyword list is deliberately wide so users see a sensible tag for common
 * PC setups: Armadyl cbow, Twisted bow, Toxic blowpipe, Bow of faerdhinen, Craw's bow,
 * Dragon cbow, Magic shortbow, Trident of the seas/swamp, Sanguinesti staff, Kodai wand,
 * Tumeken's shadow, etc.
 */
private fun classifyStyleFromName(name: String): AttackStyle {
    if (name.isEmpty()) return AttackStyle.MELEE
    // Magic first — staves and wands are the most distinctive keywords.
    if (name.contains("staff") || name.contains("wand") || name.contains("trident") ||
        name.contains("sceptre") || name.contains("thammaron") ||
        name.contains("sanguinesti") || name.contains("kodai") || name.contains("shadow") ||
        name.contains("harmonised") || name.contains("nightmare") || name.contains("ancient sceptre")
    ) {
        return AttackStyle.MAGIC
    }
    // Ranged — ordering: blowpipe before bow (blowpipe contains "bow" if we ever drop the
    // "pipe" check), dart/knife/thrownaxe/chinchompa are unambiguous thrown ranged.
    if (name.contains("blowpipe") || name.contains("crossbow") || name.contains("c'bow") ||
        name.contains("cbow") || name.contains("bow") || name.contains("dart") ||
        name.contains("knife") || name.contains("thrownaxe") || name.contains("chinchompa") ||
        name.contains("javelin") || name.contains("craw") || name.contains("faerdhinen") ||
        name.contains("webweaver") || name.contains("tonalztics") || name.contains("bowfa")
    ) {
        return AttackStyle.RANGED
    }
    // Everything else — melee.
    return AttackStyle.MELEE
}

/**
 * The single gating predicate used by every pickTarget feeder.
 *
 * For melee: pathfinding reachability (old `.nearestReachable` behavior) — we have to
 * walk to the target, so if the BFS can't find a path there we can't engage.
 *
 * For ranged/magic: distance gate followed by projectile line-of-sight. Gates and
 * internal fences in the Pest Control compound block walking but NOT projectiles, so
 * a pest across a closed fence is a perfectly valid target as long as:
 *   (a) it's inside weapon range (portal at 30 tiles is unreachable by blowpipe),
 *   (b) the projectile can physically travel there (no full-LOS-blocking wall between).
 *
 * Distance check is evaluated first because it's a cheap integer subtraction vs the LOS
 * check's Bresenham ray-cast over the collision grid.
 */
private fun canAttackWithProfile(npc: Rs2NpcModel, profile: AttackProfile): Boolean {
    val here = Rs2Player.getWorldLocation() ?: return false
    val there = npc.worldLocation ?: return false
    if (here.distanceTo(there) > profile.range) return false
    return when (profile.style) {
        AttackStyle.MELEE -> npc.isReachable
        // Reuse Rs2NpcModel.hasLineOfSight() (api/npc/models/Rs2NpcModel.java:164-177) —
        // it computes WorldArea.hasLineOfSightTo(wv, playerLoc) internally using the same
        // WorldView lookup we'd do here, so calling it keeps the Kotlin-side surface simple
        // and avoids the Lombok-generated `Microbot.getClient()` visibility issue with the
        // Kotlin compiler (`client` field is @Getter + private; Kotlin's synthetic property
        // rewrite can't use the generated getter).
        AttackStyle.RANGED, AttackStyle.MAGIC -> npc.hasLineOfSight()
    }
}

// ------------------------------------------------------------------------------------------
// LoS+range-filtered feeders for pickTarget().
//
// Each feeder picks the nearest NPC of its category that passes `canAttackWithProfile`.
// The scan radius is widened to max(categoryRadius, profile.range) so a long-range weapon
// (trident of the seas at 8, magic shortbow at 10, blowpipe at 7) doesn't have targets
// pre-filtered away before the final LOS/range predicate runs. Portals get an extra clamp
// via `portalScanRadius` so we don't scan the entire scene when the weapon range is low.
// ------------------------------------------------------------------------------------------

/** Returns the nearest attackable (shield-down) portal NPC within weapon reach, or null. */
private fun nearestAttackablePortal(profile: AttackProfile): Rs2NpcModel? {
    // Portals live at fixed compass locations on the island; a melee player at the spawn
    // edge may be 20+ tiles from the nearest one. The existing PORTAL_SCAN_RADIUS of 40
    // covers every portal from every reasonable player position. We keep it as the upper
    // bound rather than widening further — `canAttackWithProfile` will still gate any
    // beyond-range portal out regardless.
    val scanRadius = PORTAL_SCAN_RADIUS.coerceAtLeast(profile.range)
    return Rs2NpcQueryable()
        .withIds(*ATTACKABLE_PORTAL_IDS)
        .where { canAttackWithProfile(it, profile) }
        .nearest(scanRadius)
}

/** Returns the nearest spinner within weapon reach, or null. */
private fun nearestSpinner(profile: AttackProfile): Rs2NpcModel? {
    val scanRadius = NEAR_COMBAT_RADIUS.coerceAtLeast(profile.range)
    return Rs2NpcQueryable()
        .withIds(*SPINNER_IDS)
        .where { canAttackWithProfile(it, profile) }
        .nearest(scanRadius)
}

/** Returns the nearest brawler within weapon reach, or null. */
private fun nearestBrawler(profile: AttackProfile): Rs2NpcModel? {
    val scanRadius = NEAR_COMBAT_RADIUS.coerceAtLeast(profile.range)
    return Rs2NpcQueryable()
        .withIds(*BRAWLER_IDS)
        .where { canAttackWithProfile(it, profile) }
        .nearest(scanRadius)
}

/** Returns any nearest other pest within weapon reach, or null. */
private fun nearestOtherPest(profile: AttackProfile): Rs2NpcModel? {
    val scanRadius = NEAR_COMBAT_RADIUS.coerceAtLeast(profile.range)
    return Rs2NpcQueryable()
        .withIds(*OTHER_PEST_IDS)
        .where { canAttackWithProfile(it, profile) }
        .nearest(scanRadius)
}

/**
 * Returns the nearest attackable candidate IN THE SCENE at ANY distance, with no range
 * gate and no LoS gate. Priority ordering matches `pickTarget()`:
 *   portal > spinner > brawler > other.
 *
 * Used as a "walk toward something attackable" fallback when `pickTarget()` returns null
 * mid-match — the user wants the bot to close distance so a target eventually gets into
 * weapon range, rather than standing still and polling forever. PORTAL_SCAN_RADIUS is
 * a generous bound that covers the whole island from any reasonable player position.
 */
private fun nearestAnyAttackableIgnoringRangeAndLoS(): Rs2NpcModel? {
    Rs2NpcQueryable().withIds(*ATTACKABLE_PORTAL_IDS).nearest(PORTAL_SCAN_RADIUS)?.let { return it }
    Rs2NpcQueryable().withIds(*SPINNER_IDS).nearest(PORTAL_SCAN_RADIUS)?.let { return it }
    Rs2NpcQueryable().withIds(*BRAWLER_IDS).nearest(PORTAL_SCAN_RADIUS)?.let { return it }
    return Rs2NpcQueryable().withIds(*OTHER_PEST_IDS).nearest(PORTAL_SCAN_RADIUS)
}

// ------------------------------------------------------------------------------------------
// Unfiltered counterparts — used ONLY to detect "there are candidate pests nearby, but
// every one of them is gated off". That's the signal for the throttled "all targets gated"
// log. Without this paired check we couldn't tell "no pests in scene" apart from "plenty
// of pests, all behind walls", and the latter is the case we actually want to log loudly.
// ------------------------------------------------------------------------------------------

/** Returns true iff any attackable portal exists within scan range (reachable or not). */
private fun anyAttackablePortalInScene(): Boolean =
    Rs2NpcQueryable()
        .withIds(*ATTACKABLE_PORTAL_IDS)
        .nearest(PORTAL_SCAN_RADIUS) != null

/** Returns true iff any nearby pest of any category exists within combat range. */
private fun anyNearbyPest(): Boolean {
    if (Rs2NpcQueryable().withIds(*SPINNER_IDS).nearest(NEAR_COMBAT_RADIUS) != null) return true
    if (Rs2NpcQueryable().withIds(*BRAWLER_IDS).nearest(NEAR_COMBAT_RADIUS) != null) return true
    if (Rs2NpcQueryable().withIds(*OTHER_PEST_IDS).nearest(NEAR_COMBAT_RADIUS) != null) return true
    return false
}

/**
 * Throttle timestamp for the "all reachable targets gated" log emitted from pickTarget.
 * Declared at file scope (not inside pickTarget) so the throttle survives repeated pickTarget
 * invocations — same pattern as Lobby's `lastDiagLogMs`.
 *
 * Distinct from `lastOutOfRangeLogMs` below: this one fires when NPCs exist in scene but
 * every one fails the LOS+range predicate and the style is MELEE (pathfinding gated).
 * The out-of-range log fires when the style is RANGED/MAGIC and the candidates are
 * outside weapon range / blocked by a full-LOS wall. Kept as separate signals so a bug
 * report tells us immediately which condition the bot is hitting.
 */
@Volatile private var lastGatedTargetLogMs: Long = 0L

/**
 * Throttle timestamp for the "no target in-range" log emitted from pickTarget when the
 * style is RANGED/MAGIC. Complements `lastGatedTargetLogMs` (melee, pathfinding-gated).
 * Both throttles are file-scope @Volatile so they survive across pickTarget invocations.
 */
@Volatile private var lastOutOfRangeLogMs: Long = 0L

/** Shared throttle interval for the out-of-range log (same cadence as gated-target log). */
private const val OUT_OF_RANGE_LOG_INTERVAL_MS = 5_000L

/**
 * Throttle interval for the per-candidate "pickTarget-debug" breakdown. Fires whenever
 * pickTarget returns null AND there are in-scene candidates (any id group). Shows each
 * NPC's position, distance, in-range, reachability, LoS, and final verdict — so when a
 * user pastes the log we see EXACTLY why every candidate got rejected. The previous
 * "no target in-range" one-liner only told us "something" failed, not WHICH stage. 4s
 * cadence gives a fresh line every few loop ticks without drowning the chatbox.
 */
private const val PICK_TARGET_DEBUG_LOG_INTERVAL_MS = 4_000L

/** Max number of candidates to print per pickTarget-debug emission — caps the log width. */
private const val PICK_TARGET_DEBUG_MAX_CANDIDATES = 5

/** Throttle timestamp for the per-candidate pickTarget-debug breakdown. */
@Volatile private var lastPickTargetDebugLogMs: Long = 0L

// ------------------------------------------------------------------------------------------
// GoToBoat diagnostic throttle. File-scope so it survives repeated loop() invocations
// across the GoToBoat state instance.
//
// Currently unused — kept declared for a potential re-enable of throttled decision-path
// logging once the post-match click is verified snappy. All GoToBoat log sites are
// unconditional right now (very useful for reproducing the named-scan startup stall bug
// should it return).
//
// `lastGoToBoatLogMs` — primary decision-path log, ~once per 500ms (currently inert).
// ------------------------------------------------------------------------------------------
private const val GOTO_BOAT_LOG_INTERVAL_MS = 500L
@Volatile private var lastGoToBoatLogMs: Long = 0L

/**
 * Target picker — the heart of the combat-first mandate.
 *
 * Priority (top-down):
 *   1. Nearest IN-RANGE attackable portal. Portals die fast and dropping all 4 wins the
 *      game / maxes points. Portals are at fixed distances so PORTAL_SCAN_RADIUS is
 *      generous; we let the weapon-range check decide whether we can actually hit.
 *   2. Nearest IN-RANGE spinner within NEAR_COMBAT_RADIUS. Spinners regen portal HP;
 *      killing one during a portal fight is free damage undo-prevention.
 *   3. Nearest IN-RANGE brawler within NEAR_COMBAT_RADIUS. Second-priority because
 *      unblocking a tile helps the team.
 *   4. Any other IN-RANGE pest within NEAR_COMBAT_RADIUS. Just points and activity-meter
 *      fuel.
 *
 * IN-RANGE FILTER: every layer uses `.where { canAttackWithProfile(it, profile) }` —
 * a two-step gate that checks (a) weapon range distance and (b) style-dependent reach:
 *   - MELEE:  pathfinding reachability (same as the old `.nearestReachable` behavior).
 *             Walking through closed gates is impossible; BFS correctly excludes those.
 *   - RANGED/MAGIC:  projectile line-of-sight via WorldArea.hasLineOfSightTo. Arrows
 *                    and spells DO fly over closed gates and fences — so the old
 *                    pathfinding filter was wrongly excluding valid ranged/magic
 *                    targets and the bot appeared to AFK.
 *
 * We DO NOT walk across the island to a distant pest — NEAR_COMBAT_RADIUS enforces
 * that explicitly for categories 2-4. Portals get the longer leash because they're the
 * whole reason we're here.
 *
 * DIAGNOSTIC LOGGING: if every layer returned null AND there are actually candidates in
 * the scene, emit a throttled log line so the user can see WHY we're idle. Two distinct
 * signals:
 *   - MELEE: "all reachable targets gated" (pathfinding failure; waiting for a gate).
 *   - RANGED/MAGIC: "no target in-range" with counts (style/range too short or LOS
 *                   blocked by a full-LOS wall like the stone fort interior).
 * Each is throttled to ~one line per 5s via a file-scope @Volatile timestamp.
 */
private fun pickTarget(): Rs2NpcModel? {
    val profile = currentAttackProfile()

    // 1. Attackable portal (combat-first: these are the highest-value targets).
    nearestAttackablePortal(profile)?.let { return it }

    // 2. Spinner (portal-healer) within close range.
    nearestSpinner(profile)?.let { return it }

    // 3. Brawler (tile-blocker) within close range.
    nearestBrawler(profile)?.let { return it }

    // 4. Anything else within close range.
    nearestOtherPest(profile)?.let { return it }

    // Nothing in range. Emit a throttled diagnostic that tells us WHY: distinguish
    // "melee path-gated" from "ranged/magic out of range or LOS-blocked". Check the
    // unfiltered-scene predicates only after the in-range layer came up empty so the
    // common path (target found) pays nothing.
    val hasPortalInScene = anyAttackablePortalInScene()
    val hasNearbyPest = anyNearbyPest()
    if (hasPortalInScene || hasNearbyPest) {
        val now = System.currentTimeMillis()
        // Per-candidate verbose breakdown. Fires on its own cadence so a copy-pasted log
        // shows exactly why each NPC got rejected — eliminates the guesswork the older
        // one-liner left us with ("style=MELEE, range=1; all reachable targets gated").
        if (now - lastPickTargetDebugLogMs >= PICK_TARGET_DEBUG_LOG_INTERVAL_MS) {
            logPickTargetDebug(profile)
            lastPickTargetDebugLogMs = now
        }
        when (profile.style) {
            AttackStyle.MELEE -> {
                if (now - lastGatedTargetLogMs >= GATED_TARGET_LOG_INTERVAL_MS) {
                    Microbot.log(
                        "[PestControl] pickTarget: no target in-range " +
                            "(style=MELEE, range=${profile.range}); " +
                            "all reachable targets gated — waiting for gate open"
                    )
                    lastGatedTargetLogMs = now
                }
            }
            AttackStyle.RANGED, AttackStyle.MAGIC -> {
                if (now - lastOutOfRangeLogMs >= OUT_OF_RANGE_LOG_INTERVAL_MS) {
                    // Count unfiltered candidates so the user can see "3 NPCs in scene are
                    // out-of-range/blocked" rather than a generic "nothing to attack".
                    val sceneCount = countUnfilteredCandidates()
                    Microbot.log(
                        "[PestControl] pickTarget: no target in-range " +
                            "(style=${profile.style}, range=${profile.range}); " +
                            "$sceneCount NPCs in scene are out-of-range/blocked"
                    )
                    lastOutOfRangeLogMs = now
                }
            }
        }
    }
    return null
}

/**
 * Returns the total count of attackable portals + nearby pests in scene (unfiltered by
 * range/LOS/reachability). Used only by the diagnostic "X NPCs in scene are
 * out-of-range/blocked" log — sums across all four NPC-id categories.
 */
private fun countUnfilteredCandidates(): Int {
    var total = 0
    total += Rs2NpcQueryable().withIds(*ATTACKABLE_PORTAL_IDS).count()
    total += Rs2NpcQueryable().withIds(*SPINNER_IDS).count()
    total += Rs2NpcQueryable().withIds(*BRAWLER_IDS).count()
    total += Rs2NpcQueryable().withIds(*OTHER_PEST_IDS).count()
    return total
}

/**
 * Emits a detailed per-candidate breakdown of WHY each in-scene candidate got rejected
 * by `canAttackWithProfile`. This is the definitive diagnostic for "bot is AFKing but I
 * see pests/portals around me" — exactly the class of bug that the earlier one-line
 * "no target in-range" log couldn't distinguish.
 *
 * Shows, per candidate (up to PICK_TARGET_DEBUG_MAX_CANDIDATES, in priority order
 * portal > spinner > brawler > other):
 *   - NPC name + ID
 *   - WorldPoint
 *   - distance from player
 *   - profile range
 *   - in-range? (distance <= range)
 *   - reachable? (npc.isReachable — pathfinding BFS used in the MELEE branch)
 *   - LoS? (npc.hasLineOfSight() — Bresenham projectile ray used in the RANGED branch)
 *   - final verdict per-candidate (EXCLUDED reason / OK)
 *
 * Throttled to PICK_TARGET_DEBUG_LOG_INTERVAL_MS cadence via a file-scope @Volatile
 * timestamp. Caller is responsible for gating — this function always emits when called.
 */
private fun logPickTargetDebug(profile: AttackProfile) {
    val here = Rs2Player.getWorldLocation()
    val weaponName: String = try {
        Rs2Equipment.get(EquipmentInventorySlot.WEAPON)?.name ?: "<none>"
    } catch (_: Throwable) {
        "<lookup-failed>"
    }
    val nameHint = classifyStyleFromName(weaponName.lowercase())

    Microbot.log(
        "[PestControl] pickTarget-debug: style=${profile.style}, range=${profile.range}, " +
            "weapon='$weaponName' (nameHint=$nameHint), playerPos=$here"
    )

    // Collect in priority order, up to the cap. We pull from each id-group unfiltered and
    // limit across the total budget so the log isn't dominated by 20 Defilers when a
    // portal is right there — priority groups get seats first.
    val byPriority: List<Pair<String, IntArray>> = listOf(
        "portal" to ATTACKABLE_PORTAL_IDS,
        "spinner" to SPINNER_IDS,
        "brawler" to BRAWLER_IDS,
        "other" to OTHER_PEST_IDS,
    )

    var budget = PICK_TARGET_DEBUG_MAX_CANDIDATES
    var emitted = 0
    for ((group, ids) in byPriority) {
        if (budget <= 0) break
        // Unfiltered pull, sorted by distance from player so the user sees closest-first.
        // Trimmed to the remaining budget so a crowded "other" group can't drown out the
        // portal/spinner/brawler lines higher in priority.
        val pulled = Rs2NpcQueryable()
            .withIds(*ids)
            .toList()
            .sortedBy { npc ->
                val p = npc.worldLocation ?: return@sortedBy Int.MAX_VALUE
                here?.distanceTo(p) ?: Int.MAX_VALUE
            }
            .take(budget)
        for (npc in pulled) {
            val npcLoc = npc.worldLocation
            val dist = if (here != null && npcLoc != null) here.distanceTo(npcLoc) else -1
            val inRange = dist in 0..profile.range
            val reachable = try { npc.isReachable } catch (_: Throwable) { false }
            val losOk = try { npc.hasLineOfSight() } catch (_: Throwable) { false }
            val verdict = when {
                dist < 0 -> "EXCLUDED (no-loc)"
                !inRange -> "EXCLUDED (out-of-range: dist=$dist > range=${profile.range})"
                profile.style == AttackStyle.MELEE && !reachable ->
                    "EXCLUDED (MELEE branch: isReachable=false — pathfinding gated)"
                profile.style != AttackStyle.MELEE && !losOk ->
                    "EXCLUDED (RANGED/MAGIC branch: hasLineOfSight=false — LoS blocked)"
                else -> "OK (would return)"
            }
            Microbot.log(
                "[PestControl] pickTarget-debug:   [$group] ${npc.name} #${npc.id} @ $npcLoc " +
                    "dist=$dist inRange=$inRange reach=$reachable LoS=$losOk -> $verdict"
            )
            emitted++
            budget--
            if (budget <= 0) break
        }
    }

    if (emitted == 0) {
        Microbot.log("[PestControl] pickTarget-debug:   (no in-scene candidates)")
    }
}

/**
 * True iff the player is currently engaged with (attacking) the given target. Combined
 * check: if we're explicitly interacting with them, or if we're in combat and they're
 * on our interacting line. The Rs2Player.isInCombat() signal stays true for a few ticks
 * after the last hit so it survives brief inter-swing gaps.
 */
private fun isAttackingTarget(target: Rs2NpcModel?): Boolean {
    if (target == null) return false
    val interacting = Rs2Player.getInteracting() ?: return Rs2Player.isInCombat() && false
    // Rs2NpcModel wraps an NPC but `getInteracting()` returns the raw Actor; compare by
    // name+worldLocation because direct == on different wrapper classes won't match.
    val targetName = target.name ?: return false
    if (interacting.name != targetName) return false
    val targetLoc = target.worldLocation ?: return false
    val interactingLoc = interacting.worldLocation ?: return false
    return interactingLoc == targetLoc
}

// ==========================================================================================
// States
// ==========================================================================================

/**
 * Root — dispatcher. Routes based on HUD widget + island region + lander bounding box.
 *
 * Decision order (first match wins):
 *   1. isInActiveMatch (HUD visible OR region == island)            →  InGame
 *   2. isOnVeteranLander() OR recentlyCrossedGangplank              →  Lobby
 *   3. default — we're on the pier or mainland, need to board       →  GoToBoat
 *
 * There is NO PostGame state. On match end the server teleports us back to the lander;
 * re-entering Root routes to Lobby and the next match queues up automatically. We never
 * talk to the squire — commendation points are awarded automatically by the server and
 * the squire exists only to SPEND them, which we don't care about mid-grind.
 *
 * Logging: EVERY dispatch emits exactly one log line. Root is a transient state — the
 * state machine constructs a new Root instance only on full re-dispatch (match end,
 * disconnect recovery, etc.), and each call to checkNext immediately returns a different
 * state. This is NOT spammy: one line per meaningful transition point. The previous
 * one-shot `startupLogged` guard was the reason the user saw "printing nothing" — Root
 * logged once on plugin start, dispatched to Lobby, and Lobby never re-entered Root, so
 * no further dispatch logs ever fired even though the bot was stuck for the entire run.
 */
private class Root : State() {
    override fun checkNext(client: Client): State? {
        val inActiveMatch = isInActiveMatch()
        val onLander = isOnVeteranLander()
        val hudVisible = isInPestControlGame()
        val crossed = recentlyCrossedGangplank
        val here = Rs2Player.getWorldLocation()
        val regionId = here?.regionID ?: -1

        // Pick the next state per the truth table documented above.
        val next: State = when {
            inActiveMatch        -> InGame()
            onLander || crossed  -> Lobby()
            else                 -> GoToBoat()
        }

        // Per-dispatch log. One line per Root.checkNext call — since Root is transient
        // (immediately hands off to the chosen next state), this is at most one line per
        // full state-machine re-dispatch. Contains every signal that fed the decision so
        // a user copy-pasting a single line into a bug report gives us enough context to
        // diagnose without needing to reproduce.
        Microbot.log(
            "[PestControl] dispatch: region=$regionId, pos=$here, " +
                "onLander=$onLander, hudVisible=$hudVisible, crossed=$crossed " +
                "-> ${next.javaClass.simpleName}"
        )
        return next
    }

    override fun loop(client: Client, script: StateMachineScript) {
        // Unreachable in practice — checkNext above always returns non-null, so the state
        // machine transitions immediately. Kept for defensive safety and to satisfy the
        // abstract State contract. A brief idle sleeps prevents a tight spin in the
        // pathological case where some guard changes the return to null.
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }
        Global.sleep(Rs2Random.between(800, 1600))
    }
}

/**
 * GoToBoat — single-hop walkTo the Veteran pier tile, then click the Gangplank.
 *
 * Intentionally dumb on purpose: the user called out the previous bot's "AIO walker for
 * pathing around places" as a problem. We issue ONE walkTo(VETERAN_PIER, 3) call and let
 * the client's standard walker handle the short walk. No multi-step sequencing, no zone
 * checks, no teleport variants. If the user didn't start us at Port Sarim this will hang
 * — acceptable per brief (they start it at the pier).
 */
private class GoToBoat : State() {
    @Volatile private var logged = false

    override fun checkNext(client: Client): State? {
        if (isInActiveMatch()) return InGame()
        if (isOnVeteranLander()) return Lobby()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        // Loop-entry log: emitted BEFORE any state-entry gate, before we even try to fetch
        // player location. We want to see every single invocation of GoToBoat.loop so we can
        // distinguish "state machine broken, never called" from "state machine fine, cache
        // empty" on the next user report. No throttle — tidy logs are a later concern.
        Microbot.log("[PestControl][GoToBoat] tick: entry (pre-fetch)")

        if (!logged) {
            Microbot.log("[PestControl] state: GoToBoat")
            logged = true
        }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        val playerLoc = Rs2Player.getWorldLocation() ?: run {
            Microbot.log("[PestControl][GoToBoat] tick: playerLoc=null, retry immediately")
            Global.sleep(Rs2Random.between(80, 150))
            return
        }

        // Post-fetch entry log — pairs with the pre-fetch one so we can see the player's
        // position on every tick. Together with the pre-fetch log, any 20-second gap in the
        // output means the state machine isn't invoking GoToBoat.loop, NOT that this function
        // is silently sleeping.
        Microbot.log("[PestControl][GoToBoat] tick: entry, pos=$playerLoc")

        val distToPier = playerLoc.distanceTo(VETERAN_PIER)

        // If we're far from the pier, walk there. Single walkTo — no AIO-walker multi-step.
        if (distToPier > 4) {
            // Branch 1: far-from-pier log, BEFORE the walk is issued.
            Microbot.log(
                "[PestControl][GoToBoat] player=(${playerLoc.x}, ${playerLoc.y}, ${playerLoc.plane}), " +
                    "dist-to-pier=$distToPier -> walkTo(${VETERAN_PIER.x}, ${VETERAN_PIER.y}, 3)"
            )
            Rs2Walker.walkTo(VETERAN_PIER, 3)
            Rs2Player.waitForWalking(6_000)
            return
        }

        // Primary ID-indexed query — cheap, cached, and tells us whether our hardcoded
        // GANGPLANK_ID constant matches what's in-scene. The previous unfiltered name-based
        // secondary scan (`Rs2TileObjectQueryable().where { obj.name == "Gangplank" }.nearest(20)`)
        // was the ~22-second startup blocker: on script startup many ObjectComposition entries
        // aren't loaded yet and a name-filtered scan forces lazy-load of thousands of objects.
        // The ID-indexed query below is int-compared and cheap. GANGPLANK_ID has been
        // confirmed across runs — no diagnostic needed.
        val gangplank: Rs2TileObjectModel? = Rs2TileObjectQueryable()
            .withId(GANGPLANK_ID)
            .nearest(10)

        if (gangplank == null) {
            // Branch 2: gangplank-null log. No 6-second sleepUntil here — the outer
            // `while (running) script.loop(client)` loop re-enters within microseconds, so a
            // tiny 80-150ms jitter (to avoid a CPU-pegging spin-loop) gives us ~10 re-queries
            // per second. If the scene cache genuinely needs time, it needs time — but we're
            // not ADDING artificial delay on top of that.
            Microbot.log(
                "[PestControl][GoToBoat] tick: gangplank=null, retrying immediately (no sleep)"
            )
            Global.sleep(Rs2Random.between(80, 150))
            return
        }

        // Branch 3: gangplank-found log — emit actions so we can see if the menu action
        // string is something other than our hardcoded "Cross Gangplank".
        val gangplankLoc = gangplank.worldLocation
        val gangplankActions: List<String?> =
            gangplank.objectComposition?.actions?.toList() ?: emptyList()
        Microbot.log(
            "[PestControl][GoToBoat] player=(${playerLoc.x}, ${playerLoc.y}, ${playerLoc.plane}), " +
                "dist-to-pier=$distToPier, " +
                "gangplank=(${gangplankLoc.x}, ${gangplankLoc.y}, ${gangplankLoc.plane}), " +
                "actions=$gangplankActions"
        )

        // Gangplank click retry loop. A single click is occasionally ignored by the server
        // (clickbox occluded by another player/pet, menu-entry swap at click time, network
        // hiccup, or the ObjectComposition's action list fuzzy-matching drifting between
        // "Cross" and "Cross Gangplank"). Rather than one-shot the click and then wait 3s
        // for a transition that may never fire, we try up to GANGPLANK_CLICK_ATTEMPTS
        // times, one OSRS tick (~600ms) apart, bailing the moment the transition predicate
        // fires. Each attempt first tries the fuzzy "Cross Gangplank" action, falling back
        // to the literal "Cross" action exposed by the ObjectComposition. sleepUntil returns
        // early the instant the predicate is true, so a successful click latency is
        // essentially unchanged vs. the old single-click path — we only pay the 600ms tick
        // delay if a click didn't land. recentlyCrossedGangplank is latched on the FIRST
        // successful click; subsequent retries are pure insurance.
        // Retry loop local predicate: do NOT use isOnVeteranLander() here because it returns
        // true via the recentlyCrossedGangplank latch we just set — would cause the loop to
        // exit after one attempt regardless of whether we physically moved. Use physical
        // bbox + active-match as the ground truth. The latch continues to serve the outer
        // Root-dispatch / post-cross routing (isOnVeteranLander) where it's correctly
        // designed to bridge the brief window between click and coord update.
        fun physicallyCrossedOrInMatch(): Boolean {
            val here = Rs2Player.getWorldLocation() ?: return false
            return VETERAN_LANDER_AREA.contains(here) || isInActiveMatch()
        }

        val maxAttempts = 3
        var attempts = 0
        var crossed = physicallyCrossedOrInMatch()
        var anyClickSucceeded = false
        while (attempts < maxAttempts && !crossed) {
            val primaryResult = gangplank.click(GANGPLANK_ACTION)
            val clickOk = if (primaryResult) true else gangplank.click("Cross")
            Microbot.log(
                "[PestControl][GoToBoat] click attempt ${attempts + 1}/$maxAttempts: " +
                    "primary=$primaryResult, ok=$clickOk"
            )
            if (clickOk) {
                anyClickSucceeded = true
                // Latch on first success — keeps the original "click succeeded latches the
                // fallback isOnVeteranLander() guard" semantics intact for the OUTER Root
                // dispatcher. The retry loop itself uses physicallyCrossedOrInMatch() above
                // to avoid short-circuiting on this latch.
                recentlyCrossedGangplank = true
            }
            // One-tick-ish wait before the next click attempt. sleepUntil returns early the
            // instant the predicate fires, so a successful cross bails fast and we don't
            // burn the remaining retries. If the click didn't dispatch (LOS, menu swap), we
            // still wait the full 600ms — equivalent to one game tick — before retrying.
            // Using physicallyCrossedOrInMatch here (NOT isOnVeteranLander) so the latch
            // we just set above doesn't instantly satisfy the predicate.
            sleepUntil(timeout = 600) { physicallyCrossedOrInMatch() }
            crossed = physicallyCrossedOrInMatch()
            attempts++
        }
        // Summary log so we can see in the run log whether the retries were needed and
        // whether we actually boarded. `attempts` counts how many loop iterations ran
        // (1..maxAttempts), `crossed` is the transition-fired signal.
        Microbot.log(
            "[PestControl][GoToBoat] gangplank crossing: attempts=$attempts, " +
                "crossed=$crossed, anyClickOk=$anyClickSucceeded"
        )

        // If none of the clicks dispatched AND we didn't cross (e.g. the menu swap kept
        // winning every attempt), back off briefly so the next outer tick doesn't
        // immediately re-enter a tight retry loop at the same spot.
        if (!anyClickSucceeded && !crossed) {
            Global.sleep(Rs2Random.between(400, 900))
        }
    }
}

/**
 * Lobby — wait on the Veteran lander deck for the match to start.
 *
 * Per wiki: a match starts when 5+ players are queued AND 50 ticks (~30s) have elapsed.
 * When it fires, the game auto-teleports all lobby players to the Pest Control island.
 * We do nothing except poll — no walking, no tabbing, no antiban nudge (standing still on
 * the lander is normal player behavior and the brief was explicit: minimize AFK noise but
 * lobby is fine).
 *
 * Poll cadence is tight: sleepUntil wakes us the moment EITHER isInActiveMatch fires OR
 * we step off the lander (disconnect, manual move), with a fallback max sleep of
 * LOBBY_POLL_MAX_MS so the outer state-machine loop still gets to re-evaluate if neither
 * fires. This is the core fix for "script AFKs in lobby": before, a 2-3s sleep could let
 * the teleport-to-island fire mid-sleep and the InGame state wouldn't start for multiple
 * ticks after landing.
 *
 * The transition itself is detected by the island-region change (→ 10536) OR the HUD
 * widget becoming visible — see isInActiveMatch() which uses `||`, not `&&`. Either alone
 * authoritatively trips the state change. If the player somehow steps off the lander
 * bounding box mid-wait (manual movement, disconnect), we fall back to GoToBoat.
 *
 * A throttled diagnostic log emits once per LOBBY_DIAG_LOG_INTERVAL_MS (~3s) with the
 * current signals — region, player pos, onLander, hudVisible — so "stuck in lobby" bug
 * reports carry enough data to diagnose without adding per-poll spam. Throttle lives in
 * a @Volatile timestamp so it survives repeated loop() invocations.
 */
private class Lobby : State() {
    @Volatile private var logged = false
    @Volatile private var lastDiagLogMs = 0L

    override fun checkNext(client: Client): State? {
        if (isInActiveMatch()) return InGame()
        // If we somehow stepped off the lander (player moved manually, disconnect +
        // re-login elsewhere, etc.), re-queue via GoToBoat.
        if (!isOnVeteranLander()) return GoToBoat()
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[PestControl] state: Lobby"); logged = true }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // Throttled lobby-poll diagnostic. Emits at most once every ~3s; a stuck-in-lobby
        // bug report shows exactly what signals the script is seeing.
        val now = System.currentTimeMillis()
        if (now - lastDiagLogMs >= LOBBY_DIAG_LOG_INTERVAL_MS) {
            val here = Rs2Player.getWorldLocation()
            val regionId = here?.regionID ?: -1
            val onLander = isOnVeteranLander()
            val hudVisible = isInPestControlGame()
            Microbot.log(
                "[PestControl] lobby-poll: region=$regionId, pos=$here, " +
                    "onLander=$onLander, hudVisible=$hudVisible"
            )
            lastDiagLogMs = now
        }

        // Sleep-until returns as soon as EITHER: the match starts (HUD or region flips)
        // OR we're no longer on the lander (manual move, disconnect). The random check
        // cadence stays tight; the overall max timeout is bounded by LOBBY_POLL_MAX_MS so
        // we still hand control back to the outer state-machine loop on a predictable
        // beat for checkNext() to run.
        sleepUntil(
            checkEvery = Rs2Random.between(LOBBY_POLL_MIN_MS, LOBBY_POLL_MAX_MS),
            timeout = LOBBY_POLL_MAX_MS,
        ) {
            isInActiveMatch() || !isOnVeteranLander()
        }
    }
}

/**
 * InGame — the hot combat loop. This is where the "fail less at earning points" mandate
 * pays off.
 *
 * On entry to InGame, the player has just teleported to the island's southern spawn edge.
 * The very first thing we do is walk toward the Void Knight at the island center. Without
 * this, pickTarget() often returned a portal >12 tiles away with no close pest, and the
 * subsequent `.click("Attack")` would either fail silently (unclear path) or auto-walk
 * the player to the portal extremely slowly, AFK-ing through the first several seconds of
 * the match — exactly the user-reported symptom. The walk-to-Void-Knight step is a ONE-
 * SHOT per InGame instance (States are freshly constructed on transition per
 * StateMachineScript's contract, so the `reachedCombatZone` field resets every match).
 *
 * CRITICAL: the approach walk is interruptible. On every tick during the walk we re-run
 * pickTarget() and, if a target is within WALK_INTERRUPT_ATTACK_RADIUS, we short-circuit
 * and attack. Addresses "if a pest spawns on top of us while walking, don't finish the
 * walk — attack immediately."
 *
 * Loop body per tick (after the approach is complete):
 *   0. Conservative eat-guard (no food layer): eat a Karambwan from the inventory if HP is
 *      below 50% and we still have one. Per brief: we're not a food-management script but
 *      we shouldn't face-tank to death.
 *   1. Pick target via pickTarget() (portal > spinner > brawler > other).
 *   2. If no target found — wait briefly and re-scan. Can happen mid-teleport or when
 *      the nearest pest has just died; NEVER stand here more than a tick or two.
 *   3. If we're NOT already attacking the picked target, click Attack. This re-engages
 *      every time a higher-priority target shows up (e.g. a portal's shield just dropped
 *      while we were fighting a pest).
 *   4. sleepUntil on (target dies, better target appears, player stops being in combat)
 *      with a short timeout. This is the anti-AFK cadence: max ~1.5s between re-checks.
 *
 * Why the re-engage is keyed on "not attacking THIS target" and not just "not attacking
 * anything": during a portal fight, if a spinner spawns, we want to STOP attacking the
 * portal and kill the spinner first. pickTarget() will return the spinner (higher prio
 * since it's in NEAR_COMBAT_RADIUS), and we'll detect we're not attacking it and click.
 */
private class InGame : State() {
    @Volatile private var logged = false
    @Volatile private var endLogged = false

    // One-shot latch: once we've walked into the combat zone for this InGame instance, we
    // never walk again this match. States are reconstructed per transition so a new match
    // starts with reachedCombatZone=false.
    @Volatile private var reachedCombatZone = false

    // Timestamp of the first InGame loop tick where the approach walk was issued. Used to
    // enforce APPROACH_TIMEOUT_MS — if the Void Knight is gated off (walled-compound fences
    // still closed at match start), Rs2Walker.walkTo would flail indefinitely trying to
    // path through a shut gate. After the timeout we commit to `reachedCombatZone = true`
    // wherever we are and let the combat loop take over; pickTarget will find whatever's
    // reachable around us. 0 = not yet started.
    @Volatile private var approachStartMs: Long = 0L

    override fun checkNext(client: Client): State? {
        // Match over — HUD gone and we're no longer on the island. Re-dispatch through
        // Root, which will correctly route to Lobby (we auto-teleport onto the lander on
        // match end) or GoToBoat (fallback). NO squire / PostGame flow — points are
        // awarded automatically by the server and we want the next match ASAP.
        if (!isInActiveMatch()) {
            if (!endLogged) {
                Microbot.log("[PestControl] match ended — re-queuing")
                endLogged = true
            }
            return Root()
        }
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) {
            Microbot.log("[PestControl] state: InGame")
            // Match has started — we're no longer "between gangplank-click and lobby";
            // clear the lander latch so the bounding box is the sole signal next lobby.
            recentlyCrossedGangplank = false
            logged = true
        }
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        // -1. Approach phase — ONCE per match, walk toward the Void Knight so the rest of
        // the combat loop runs from the center of the island where portals + pests are
        // inside pickTarget()'s scan radii. Interruptible: if a target appears within
        // easy reach mid-walk, attack it instead of finishing the walk.
        //
        // Bailout: if the knight is gated off behind a closed fence at match start, the
        // walker would flail indefinitely trying to path through a wall. After
        // APPROACH_TIMEOUT_MS elapses we commit to the combat loop wherever we are and
        // let pickTarget find whatever's reachable nearby. This is tracked by
        // `approachStartMs`, stamped on the first approach-phase tick of this match.
        if (!reachedCombatZone) {
            val here = Rs2Player.getWorldLocation() ?: run {
                // Player location not yet available — very brief wait, then retry. Can
                // happen on the first tick after teleport while the client is still
                // initializing the scene.
                Global.sleep(Rs2Random.between(200, 400))
                return
            }

            // Stamp the approach start on the first tick we actually reach this branch.
            // `0` sentinel means "not yet started" — guards against an entry on a tick
            // where we early-returned above without ever attempting to walk.
            if (approachStartMs == 0L) {
                approachStartMs = System.currentTimeMillis()
            }

            // Approach-phase bailout: if we've been trying to reach the knight for longer
            // than APPROACH_TIMEOUT_MS, give up and commit to the combat loop in place.
            // The Void Knight may be walled off by the compound fence at match start and
            // the walker can't path through closed gates; better to engage whatever is
            // around us than to keep bumping the wall.
            val approachElapsed = System.currentTimeMillis() - approachStartMs
            if (approachElapsed > APPROACH_TIMEOUT_MS) {
                Microbot.log(
                    "[PestControl] approach timeout after ${approachElapsed}ms — " +
                        "committing to combat in place (Void Knight likely gated)"
                )
                reachedCombatZone = true
                return
            }

            // Prefer the real NPC (robust to Jagex-side repositioning). Fall back to a
            // hardcoded island-center tile if the NPC cache is still catching up.
            val voidKnight = findVoidKnight()
            val target: WorldPoint = voidKnight?.worldLocation ?: VOID_KNIGHT_FALLBACK

            if (here.distanceTo(target) > COMBAT_ZONE_RADIUS) {
                // Short-circuit the walk if something pounced on us. Any valid target
                // within easy reach takes priority — we shouldn't AFK-walk through a
                // pest that's standing next to us. pickTarget() now reachability-filters
                // so this won't engage a through-the-wall pest.
                val opportunisticTarget = pickTarget()
                if (opportunisticTarget != null) {
                    val oppLoc = opportunisticTarget.worldLocation
                    if (oppLoc != null && here.distanceTo(oppLoc) <= WALK_INTERRUPT_ATTACK_RADIUS) {
                        Microbot.log(
                            "[PestControl] interrupt-walk: engaging " +
                                "${opportunisticTarget.name} @ $oppLoc"
                        )
                        // Don't flip reachedCombatZone on success — we may still drift
                        // back to finish the approach after this kill if we're not yet
                        // close enough. Deliberately conservative: one kill in place,
                        // then the next loop re-evaluates distance.
                        if (!opportunisticTarget.click("Attack")) {
                            Global.sleep(Rs2Random.between(200, 500))
                        }
                        return
                    }
                }

                // No close target — issue the walk. walkTo accepts a tolerance radius so
                // we don't walk onto the NPC tile and stack with him.
                Microbot.log("[PestControl] walk-to-void-knight: target=$target, dist=${here.distanceTo(target)}")
                Rs2Walker.walkTo(target, COMBAT_ZONE_RADIUS)
                Rs2Player.waitForWalking(6_000)
                return
            }

            reachedCombatZone = true
            Microbot.log("[PestControl] reached combat zone (Void Knight area) at $target")
        }

        // 0. Karambwan eat guard. User's explicit requirement: eat a "Karambwan" from the
        // inventory when HP is below 50%. This replaces the previous generic Rs2Player.eatAt
        // call — we want a deterministic, opinionated food pick (Karambwan is the PvM combo-
        // food standard and users can pre-stock as many as they want). The script does not
        // bank, so running out = player's problem, same as the rest of the script's "no food
        // management layer" contract. The action string is "Eat" (canonical OSRS menu action
        // on cooked food — confirmed via AGENTS.md examples and Rs2Inventory.interact's
        // default case-insensitive substring matching makes this robust to case drift).
        if (Rs2Player.getHealthPercentage() < 50 && Rs2Inventory.contains("Karambwan")) {
            Rs2Inventory.interact("Karambwan", "Eat")
            // Post-eat jitter so we don't fire a second identical click before the server
            // has processed the consume (and would turn into a wasted click on whatever
            // item slotted into that inventory spot after the karambwan is gone).
            Global.sleep(Rs2Random.between(300, 600))
            return
        }

        // 1. Pick target.
        val target = pickTarget()
        if (target == null) {
            // Nothing in range / valid. But pickTarget only considers candidates that
            // pass the range + LoS/reachability gate — the user could be sitting in a
            // dead zone of the island where every pest/portal is far away or blocked by
            // a wall we have no LoS to. Walk toward the nearest attackable candidate
            // (any distance, no LoS gate) so eventually SOMETHING gets into range.
            //
            // Priority order matches pickTarget (portal > spinner > brawler > other),
            // so we don't drift toward a distant brawler when a closer spinner exists.
            // The new walk-to-nearest logic runs AFTER `reachedCombatZone` flipped
            // above, so it doesn't fight the one-shot approach-to-VoidKnight walk.
            val anyAttackable = nearestAnyAttackableIgnoringRangeAndLoS()
            if (anyAttackable != null) {
                val anyLoc = anyAttackable.worldLocation
                val here = Rs2Player.getWorldLocation()
                val dist = if (here != null && anyLoc != null) here.distanceTo(anyLoc) else -1
                Microbot.log(
                    "[PestControl] walking toward ${anyAttackable.name} @ $anyLoc " +
                        "dist=$dist (no target in range yet)"
                )
                if (anyLoc != null) {
                    // Tolerance 1 so walkTo doesn't try to land on the NPC's tile (which
                    // is often un-walkable — e.g. a portal occupies a fixed tile). The
                    // short waitForWalking means we re-pickTarget quickly once we close
                    // distance; if the walk gets interrupted by something in-range
                    // appearing, the next loop tick engages it.
                    Rs2Walker.walkTo(anyLoc, 1)
                    Rs2Player.waitForWalking(4_000)
                }
                return
            }
            // Really nothing — not even unfiltered candidates in scene. Brief sleep;
            // next tick re-scans. Never sit here for multiple seconds — if a mob just
            // died, another is usually one tick away. Do NOT transition back to Root /
            // don't re-enter the approach walk; standing in place while the scene
            // repopulates is correct behavior.
            Global.sleep(Rs2Random.between(EMPTY_TARGET_SLEEP_MIN_MS, EMPTY_TARGET_SLEEP_MAX_MS))
            return
        }

        // 2. If we're already attacking this specific target, let the swing land — short
        // sleep, then re-evaluate on the next tick (in case a higher-prio target spawns).
        if (isAttackingTarget(target)) {
            Global.sleep(Rs2Random.between(400, 800))
            return
        }

        // 3. Click Attack. Logs the re-target for the user's "is it actually attacking?"
        // debug question. We log the target name + world location on every retarget so
        // the log is useful without being per-swing spammy.
        val targetName = target.name ?: "<unnamed>"
        val targetLoc = target.worldLocation
        Microbot.log("[PestControl] target: $targetName @ $targetLoc")

        if (target.click("Attack")) {
            // 4. Wait for: target dies, we stop being in combat, OR a better target appears.
            // Timeout is short — the brief was explicit about never going >3s between
            // combat events.
            sleepUntil(checkEvery = 300, timeout = RETARGET_POLL_MS) {
                // Exit early if:
                //   - A higher-priority target than our current one appears (shield just
                //     dropped on a portal, or a spinner spawned next to us).
                //   - Our target is dead or gone from the scene.
                //   - We've stopped interacting (target died / we got rotated off by
                //     terrain or a tile push).
                val currentBest = pickTarget()
                val currentBestLoc = currentBest?.worldLocation
                val targetStillBest = currentBestLoc != null &&
                    currentBestLoc == target.worldLocation
                !targetStillBest || !isAttackingTarget(target)
            }
        } else {
            // Click failed (menu race, LOS). Very brief jitter and retry next tick.
            Global.sleep(Rs2Random.between(200, 500))
        }
    }
}

