package net.runelite.client.plugins.microbot.trent.brimhavenagility

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.runelite.api.ChatMessageType
import net.runelite.api.Client
import net.runelite.api.CollisionData
import net.runelite.api.CollisionDataFlag
import net.runelite.api.HintArrowType
import net.runelite.api.WorldView
import net.runelite.api.coords.WorldPoint
import net.runelite.api.events.ChatMessage
import net.runelite.api.gameval.ObjectID
import net.runelite.client.eventbus.Subscribe
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginDescriptor
import net.runelite.client.plugins.microbot.Microbot
import net.runelite.client.plugins.microbot.api.tileobject.Rs2TileObjectQueryable
import net.runelite.client.plugins.microbot.api.tileobject.models.Rs2TileObjectModel
import net.runelite.client.plugins.microbot.trent.api.State
import net.runelite.client.plugins.microbot.trent.api.StateMachineScript
import net.runelite.client.plugins.microbot.trent.api.sleepUntil
import net.runelite.client.plugins.microbot.util.Global
import net.runelite.client.plugins.microbot.util.math.Rs2Random
import net.runelite.client.plugins.microbot.util.player.Rs2Player
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker
import java.util.PriorityQueue
import javax.inject.Inject

// ==========================================================================================
// BrimhavenAgility — Brimhaven Agility Arena ticket-grinder with cost-based pathfinding.
//
// The arena floor is a maze of pillars connected by short obstacles (rope/log/ledge
// balances, plank gaps, low walls, monkey bars, the timed blade trap, etc.). The global
// Microbot pathfinder can't price obstacles vs. detours and routinely re-routes around
// the planner's chosen edge, so this script:
//
//   1. Snapshots every in-scene arena obstacle and resolves its two endpoint tiles by
//      probing collision flags around the object (each obstacle bridges two otherwise
//      disconnected walkable tiles).
//   2. Builds a node graph: player tile + active pillar + every obstacle endpoint, with
//      walk edges priced by BFS tile distance / RUN_TPS and obstacle edges priced by a
//      static per-obstacle traversal-time table (sourced from the OSRS wiki).
//   3. Runs Dijkstra to pick the cheapest route to the active pillar — implicitly
//      comparing "walk around" vs. "take the slow obstacle".
//   4. Steps the path with a *local* mover: walk-edges click an intermediate tile via
//      Rs2Walker.walkFastCanvas (no global pathfinder) chosen from the same component
//      BFS the planner used; obstacle-edges click the object and sleepUntil arrival.
//      Replans whenever the hint arrow rotates mid-traversal.
//
// References:
//   - Pillar / obstacle IDs: ObjectID.AGILITY_PILLAR (3607), AGILITY_TICKETPILLAR (3608),
//     and AGILITYARENA_* range 3551-3585 (matched against agility/Obstacles.java in this
//     repo for the canonical brimhaven set).
//   - Hint-arrow API: Client.getHintArrowPoint() — same source the official AgilityPlugin
//     uses to drive the arena timer (AgilityPlugin.onGameTick).
//   - Reachable-tile BFS: Rs2Reachable / Rs2Tile patterns; we re-implement here against
//     CollisionData so we can BFS from arbitrary endpoint tiles, not just the player.
// ==========================================================================================

private val ARENA_CENTER = WorldPoint(2782, 9568, 3)

private val PILLAR_IDS = intArrayOf(
    ObjectID.AGILITY_PILLAR,
    ObjectID.AGILITY_TICKETPILLAR
)

// Pillars are 3x3 GameObjects whose worldLocation is the SW corner; the hint-arrow tile
// can be up to 3 tiles away from that corner, so use a generous match radius.
private const val PILLAR_MATCH_RADIUS = 4
private const val CENTER_REACHED_DISTANCE = 2
private const val RETURN_TIMEOUT_MS = 30_000L
private const val PATH_STEP_TIMEOUT_MS = 12_000L
private const val PATH_PLAN_TIMEOUT_MS = 50_000L
private const val TICKET_CHAT_NEEDLE = "you found a ticket"
private const val DIAG_LOG_INTERVAL_MS = 1000L

// Run-on tile-per-second. OSRS run = 2 tiles/tick = ~3.33 t/s. We assume run-on; the user
// already runs at every other agility-arena bot for a reason.
private const val RUN_TPS = 3.33

// Per-obstacle traversal cost in seconds. Sourced from the OSRS wiki's Agility Arena page
// and confirmed against the official agility plugin's known animation IDs. Where the wiki
// gives a tick range, we use the upper bound to favour walk-around paths in close calls.
//
// Bidirectional unless noted. The matrix-dart blade trap (TRAP_TIMEDBLADE2 = 3567) is
// expensive enough that a 12-tile walk-around at 3.33 t/s (~3.6s) usually beats it.
private val OBSTACLE_COST_SECONDS: Map<Int, Double> = mapOf(
    // Rope balance: 5 ticks = 3.0s
    ObjectID.AGILITYARENA_ROPEBALANCE to 3.0,
    ObjectID.AGILITYARENA_ROPEBALANCE_MIDDLE to 3.0,
    // Log balances: 4 ticks = 2.4s each
    ObjectID.AGILITYARENA_LOGBALANCE1 to 2.4,
    ObjectID.AGILITYARENA_LOGBALANCE1_MIDDLE to 2.4,
    ObjectID.AGILITYARENA_LOGBALANCE2 to 2.4,
    ObjectID.AGILITYARENA_LOGBALANCE2_MIDDLE to 2.4,
    ObjectID.AGILITYARENA_LOGBALANCE3 to 2.4,
    ObjectID.AGILITYARENA_LOGBALANCE3_MIDDLE to 2.4,
    // Ledge balances: 5 ticks = 3.0s
    ObjectID.AGILITYARENA_LEDGEBALANCE to 3.0,
    ObjectID.AGILITYARENA_LEDGEBALANCE_MIDDLE to 3.0,
    ObjectID.AGILITYARENA_LEDGEBALANCE2 to 3.0,
    ObjectID.AGILITYARENA_LEDGEBALANCE2_MIDDLE to 3.0,
    // Monkey bars: 4 ticks = 2.4s
    ObjectID.AGILITYARENA_MONKEYBARS_MIDDLE to 2.4,
    ObjectID.AGILITYARENA_MONKEYBARS_END to 2.4,
    // Low wall (jump-over): 2 ticks = 1.2s
    ObjectID.AGILITYARENA_LOWWALL to 1.2,
    // Rope swing: 3 ticks = 1.8s
    ObjectID.AGILITYARENA_ROPESWING to 1.8,
    // Handholds (cliff traverse): 5 ticks = 3.0s
    ObjectID.AGILITYARENA_HANDHOLDS to 3.0,
    ObjectID.AGILITYARENA_HANDHOLDS_MIDDLE to 3.0,
    // Plank shortcuts (3 plank variants + middle continuations): 3 ticks = 1.8s
    ObjectID.AGILITYARENA_PLANK to 1.8,
    ObjectID.AGILITYARENA_PLANK2 to 1.8,
    ObjectID.AGILITYARENA_PLANK3 to 1.8,
    ObjectID.AGILITYARENA_PLANK_MIDDLE to 1.8,
    ObjectID.AGILITYARENA_PLANK2_MIDDLE to 1.8,
    ObjectID.AGILITYARENA_PLANK3_MIDDLE to 1.8,
    // Pillar-top vault (continues a balance): 2 ticks = 1.2s
    ObjectID.AGILITYARENA_PILLAR_TOP to 1.2,
    ObjectID.AGILITYARENA_PILLAR_TOP_MIDDLE to 1.2,
    // Matrix-dart timed-blade trap: requires waiting for the blade window.
    // Wiki: ~10 ticks worst-case (5s blade cycle + 5 ticks animation) = 6.0s
    ObjectID.AGILITYARENA_TRAP_TIMEDBLADE2 to 6.0,
)

private val OBSTACLE_IDS = OBSTACLE_COST_SECONDS.keys.toIntArray()

// ==========================================================================================
// Plugin / Script wiring
// ==========================================================================================

@PluginDescriptor(
    name = PluginDescriptor.Trent + "Brimhaven Agility",
    description = "Tags the active hint-iconned pillar in the Brimhaven Agility Arena and returns to center",
    tags = ["agility", "minigame", "tickets"],
    enabledByDefault = false
)
class BrimhavenAgility : Plugin() {
    @Inject
    private lateinit var client: Client

    private var running = false
    private val script = BrimhavenAgilityScript()

    @OptIn(DelicateCoroutinesApi::class)
    override fun startUp() {
        if (client.localPlayer != null) {
            running = true
            GlobalScope.launch { run() }
        }
    }

    private fun run() {
        while (running) {
            try {
                script.loop(client)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                Microbot.log("[BrimhavenAgility] loop error: ${t.message}")
            }
        }
    }

    override fun shutDown() {
        running = false
        script.stop()
    }

    @Subscribe
    fun onChatMessage(e: ChatMessage) {
        if (e.type != ChatMessageType.GAMEMESSAGE && e.type != ChatMessageType.SPAM) return
        val text = e.messageNode.value?.lowercase() ?: return
        if (text.contains(TICKET_CHAT_NEEDLE)) {
            script.ticketAwarded = true
        }
    }
}

class BrimhavenAgilityScript : StateMachineScript() {
    override fun getStartState(): State = Root(this)

    @Volatile
    var ticketAwarded: Boolean = false

    /** Timestamp of the most recent 1Hz diagnostic log emission. Read+written from the
     *  background loop only, so plain Long is fine. */
    var lastDiagLogMs: Long = 0L
}

// ==========================================================================================
// Helpers
// ==========================================================================================

/**
 * Active pillar tile per the hint arrow. Filtered on
 * [HintArrowType.COORDINATE] (value 2) — `hasHintArrow()` returns true for
 * NPC/PLAYER/WORLDENTITY arrow types too, which would give us a non-null but
 * irrelevant `hintArrowPoint`. This mirrors the official AgilityPlugin's
 * `client.getHintArrowPoint()` use after `isInAgilityArena()` gating.
 */
private fun activePillarPoint(client: Client): WorldPoint? {
    if (!client.hasHintArrow()) return null
    if (client.hintArrowType != HintArrowType.COORDINATE) return null
    return client.hintArrowPoint
}

private fun pillarAt(arrowPoint: WorldPoint): Rs2TileObjectModel? =
    Rs2TileObjectQueryable()
        .withIds(*PILLAR_IDS)
        .nearest(arrowPoint, PILLAR_MATCH_RADIUS)

private fun atCenter(): Boolean {
    val here = Rs2Player.getWorldLocation() ?: return false
    return here.distanceTo(ARENA_CENTER) <= CENTER_REACHED_DISTANCE
}

/** Emit one diagnostic line per second describing the current decision-loop inputs.
 *  Throttled by [BrimhavenAgilityScript.lastDiagLogMs]. */
private fun diagLog(owner: BrimhavenAgilityScript, stateName: String, client: Client) {
    val now = System.currentTimeMillis()
    if (now - owner.lastDiagLogMs < DIAG_LOG_INTERVAL_MS) return
    owner.lastDiagLogMs = now
    try {
        val here = Rs2Player.getWorldLocation()
        val hasArrow = client.hasHintArrow()
        val arrowType = client.hintArrowType
        val arrow = if (hasArrow) client.hintArrowPoint else null
        val active = activePillarPoint(client)
        val pillar = active?.let { pillarAt(it) }
        val regionId = here?.regionID ?: -1
        Microbot.log(
            "[BrimhavenAgility] $stateName here=$here region=$regionId hasArrow=$hasArrow " +
                "arrowType=$arrowType arrow=$arrow active=$active pillar=${pillar?.let { "${it.id}@${it.worldLocation}" }}"
        )
    } catch (t: Throwable) {
        Microbot.log("[BrimhavenAgility] diagLog error: ${t.message}")
    }
}

// ==========================================================================================
// Arena graph — built from an in-scene obstacle snapshot.
// ==========================================================================================

private data class ObstacleEdge(
    val obj: Rs2TileObjectModel,
    val id: Int,
    val from: WorldPoint,
    val to: WorldPoint,
    val costSeconds: Double,
)

private class ArenaGraph(
    val obstacles: List<ObstacleEdge>,
    private val componentByTile: Map<WorldPoint, Int>,
    private val componentTiles: Map<Int, Set<WorldPoint>>,
) {
    /** BFS distance within the same component, or null if not in any component / unreachable. */
    fun walkDistance(a: WorldPoint, b: WorldPoint): Int? {
        if (a == b) return 0
        val compA = componentByTile[a] ?: return null
        val compB = componentByTile[b] ?: return null
        if (compA != compB) return null
        val tiles = componentTiles[compA] ?: return null
        return bfsTileDistance(a, b, tiles)
    }

    /**
     * Reconstruct the BFS tile-by-tile path from [a] to [b] within the same component.
     * Returns the ordered list of tiles excluding [a] (i.e. first element is the next
     * step). Null if either tile isn't in any component or they're in different ones.
     */
    fun walkPath(a: WorldPoint, b: WorldPoint): List<WorldPoint>? {
        if (a == b) return emptyList()
        val compA = componentByTile[a] ?: return null
        val compB = componentByTile[b] ?: return null
        if (compA != compB) return null
        val tiles = componentTiles[compA] ?: return null
        return bfsTilePath(a, b, tiles)
    }

    /** Snap a possibly-off-walkable point to the nearest walkable tile in any
     *  component. Used for the player's tile (where the player is standing should
     *  always be walkable, but be defensive). */
    fun snapToWalkable(p: WorldPoint): WorldPoint? {
        if (componentByTile.containsKey(p)) return p
        var best: WorldPoint? = null
        var bestD = Int.MAX_VALUE
        for (t in componentByTile.keys) {
            if (t.plane != p.plane) continue
            val d = t.distanceTo(p)
            if (d < bestD) { bestD = d; best = t }
        }
        return best
    }

    /**
     * Snap a target point (e.g. the hint-arrow tile, which sits on top of a 3x3
     * non-walkable pillar) to the closest walkable tile that is *adjacent* to the
     * pillar — Chebyshev distance 1 first, then 2, then 3. The point of snapping is
     * to give us a tile we can stand on to click the pillar; snapping to the global
     * nearest walkable can pick a tile in a different component, leaving Dijkstra
     * with no path. Prefers the candidate in the player's component when ties.
     */
    fun snapToWalkableNearTarget(target: WorldPoint, playerComponent: Int?): WorldPoint? {
        if (componentByTile.containsKey(target)) return target
        for (radius in 1..6) {
            var best: WorldPoint? = null
            var bestD = Int.MAX_VALUE
            var bestSameComponent = false
            for (dx in -radius..radius) for (dy in -radius..radius) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) continue
                val candidate = WorldPoint(target.x + dx, target.y + dy, target.plane)
                val comp = componentByTile[candidate] ?: continue
                val sameComp = playerComponent != null && comp == playerComponent
                val d = candidate.distanceTo(target)
                // Prefer same-component candidates strictly; among same-component,
                // prefer closer; among different-component, also prefer closer.
                val winner = when {
                    best == null -> true
                    sameComp && !bestSameComponent -> true
                    !sameComp && bestSameComponent -> false
                    else -> d < bestD
                }
                if (winner) {
                    best = candidate
                    bestD = d
                    bestSameComponent = sameComp
                }
            }
            if (best != null) return best
        }
        return snapToWalkable(target)
    }

    fun componentOf(p: WorldPoint): Int? = componentByTile[p]

    fun distinctComponentCount(): Int = componentTiles.size
}

/** Standard BFS within a constrained tile set (the component). */
private fun bfsTileDistance(a: WorldPoint, b: WorldPoint, tiles: Set<WorldPoint>): Int? {
    if (!tiles.contains(a) || !tiles.contains(b)) return null
    val dist = HashMap<WorldPoint, Int>()
    val q: ArrayDeque<WorldPoint> = ArrayDeque()
    dist[a] = 0
    q.addLast(a)
    while (q.isNotEmpty()) {
        val cur = q.removeFirst()
        if (cur == b) return dist[cur]
        val d = dist[cur]!!
        for (n in arrayOf(cur.dx(1), cur.dx(-1), cur.dy(1), cur.dy(-1))) {
            if (!tiles.contains(n) || dist.containsKey(n)) continue
            dist[n] = d + 1
            q.addLast(n)
        }
    }
    return null
}

/** BFS within [tiles] reconstructing the path from [a] to [b]. Returned list excludes [a]
 *  and ends with [b]. Empty if a == b. Null if unreachable inside the constrained set. */
private fun bfsTilePath(a: WorldPoint, b: WorldPoint, tiles: Set<WorldPoint>): List<WorldPoint>? {
    if (a == b) return emptyList()
    if (!tiles.contains(a) || !tiles.contains(b)) return null
    val parent = HashMap<WorldPoint, WorldPoint>()
    val q: ArrayDeque<WorldPoint> = ArrayDeque()
    q.addLast(a)
    parent[a] = a
    var found = false
    outer@ while (q.isNotEmpty()) {
        val cur = q.removeFirst()
        for (n in arrayOf(cur.dx(1), cur.dx(-1), cur.dy(1), cur.dy(-1))) {
            if (!tiles.contains(n) || parent.containsKey(n)) continue
            parent[n] = cur
            if (n == b) { found = true; break@outer }
            q.addLast(n)
        }
    }
    if (!found) return null
    val out = ArrayList<WorldPoint>()
    var cur = b
    while (cur != a) {
        out.add(cur)
        cur = parent[cur] ?: return null
    }
    out.reverse()
    return out
}

/**
 * Component-aware reachable-tile BFS over the live collision map for a given plane.
 * Honours per-tile direction flags + BLOCK_MOVEMENT_FULL on neighbour. Mirrors
 * Rs2Reachable but parameterised over the start tile (Rs2Reachable always starts at the
 * player) so we can label every obstacle endpoint's component.
 */
private fun reachableComponent(
    wv: WorldView,
    flags: Array<IntArray>,
    plane: Int,
    start: WorldPoint,
    visited: HashSet<WorldPoint>,
    cap: Int,
): Set<WorldPoint> {
    val baseX = wv.baseX
    val baseY = wv.baseY
    val regionSize = flags.size

    val out = HashSet<WorldPoint>()
    val sx = start.x - baseX
    val sy = start.y - baseY
    if (sx !in 0 until regionSize || sy !in 0 until regionSize) return out
    if ((flags[sx][sy] and CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return out
    if (!visited.add(start)) return out

    out.add(start)
    val q: ArrayDeque<IntArray> = ArrayDeque()
    q.addLast(intArrayOf(sx, sy))

    while (q.isNotEmpty()) {
        val (lx, ly) = q.removeFirst()
        if (out.size >= cap) break
        val tf = flags[lx][ly]

        // South
        run {
            val ny = ly - 1
            if (ny >= 0
                && (tf and CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) == 0
                && (flags[lx][ny] and CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0) {
                val wp = WorldPoint(baseX + lx, baseY + ny, plane)
                if (visited.add(wp)) {
                    out.add(wp)
                    q.addLast(intArrayOf(lx, ny))
                }
            }
        }
        // North
        run {
            val ny = ly + 1
            if (ny < regionSize
                && (tf and CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0
                && (flags[lx][ny] and CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0) {
                val wp = WorldPoint(baseX + lx, baseY + ny, plane)
                if (visited.add(wp)) {
                    out.add(wp)
                    q.addLast(intArrayOf(lx, ny))
                }
            }
        }
        // West
        run {
            val nx = lx - 1
            if (nx >= 0
                && (tf and CollisionDataFlag.BLOCK_MOVEMENT_WEST) == 0
                && (flags[nx][ly] and CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0) {
                val wp = WorldPoint(baseX + nx, baseY + ly, plane)
                if (visited.add(wp)) {
                    out.add(wp)
                    q.addLast(intArrayOf(nx, ly))
                }
            }
        }
        // East
        run {
            val nx = lx + 1
            if (nx < regionSize
                && (tf and CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0
                && (flags[nx][ly] and CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0) {
                val wp = WorldPoint(baseX + nx, baseY + ly, plane)
                if (visited.add(wp)) {
                    out.add(wp)
                    q.addLast(intArrayOf(nx, ly))
                }
            }
        }
    }
    return out
}

/**
 * For an obstacle at [origin], find the two walkable endpoint tiles it bridges.
 *
 * Different obstacle types have very different reach:
 *  - Low wall: adjacent (1 tile)
 *  - Plank / pillar-top vault: 2-3 tiles
 *  - Log / ledge balances: 4-5 tiles
 *  - Rope balances / monkey bars / handholds: 6-8 tiles end-to-end
 *
 * The previous ±4 scan silently dropped any rope-balance / monkey-bar / handhold
 * obstacle from the graph, which left half the arena's edges unmodelled. We now
 * scan up to 8 tiles in each axis and accept the closest cross-component pair
 * whose endpoints lie on opposite sides of the origin (dot product < 0).
 *
 * Returns null when we can't find a clean two-component bridge — either the
 * obstacle's out-of-scene, the cache is mid-update, or the obstacle is a same-
 * component vault.
 */
private const val OBSTACLE_SCAN_RADIUS = 8

private fun resolveEndpoints(
    obj: Rs2TileObjectModel,
    components: Map<WorldPoint, Int>,
): Pair<WorldPoint, WorldPoint>? {
    val origin = obj.worldLocation
    val plane = origin.plane
    val candidates = mutableListOf<WorldPoint>()
    for (dx in -OBSTACLE_SCAN_RADIUS..OBSTACLE_SCAN_RADIUS) {
        for (dy in -OBSTACLE_SCAN_RADIUS..OBSTACLE_SCAN_RADIUS) {
            if (dx == 0 && dy == 0) continue
            val p = WorldPoint(origin.x + dx, origin.y + dy, plane)
            if (components.containsKey(p)) candidates.add(p)
        }
    }
    if (candidates.size < 2) return null

    var best: Pair<WorldPoint, WorldPoint>? = null
    var bestScore = Int.MAX_VALUE
    for (i in candidates.indices) for (j in i + 1 until candidates.size) {
        val a = candidates[i]; val b = candidates[j]
        if (components[a] == components[b]) continue
        // Origin must lie roughly between a and b: dot product of vectors a->origin
        // and b->origin must be negative (opposite sides) — filters out coincidental
        // cross-component pairs that happen to be near the same obstacle.
        val ax = origin.x - a.x; val ay = origin.y - a.y
        val bx = origin.x - b.x; val by = origin.y - b.y
        if (ax * bx + ay * by >= 0) continue
        // Prefer the tightest pair (shortest combined manhattan to origin).
        val score = Math.abs(ax) + Math.abs(ay) + Math.abs(bx) + Math.abs(by)
        if (score < bestScore) {
            bestScore = score
            best = a to b
        }
    }
    return best
}

/**
 * Build the arena graph from the live cache + collision data. Collision-map and scene
 * field reads are atomic field accesses from the client's perspective and are routinely
 * called off-thread elsewhere in this codebase (Rs2Tile.getReachableTilesFromTile etc.),
 * so we don't bounce through ClientThread here — that would also bring in the Kotlin /
 * Lombok static-getter visibility headache documented in the project memory.
 */
private fun buildGraph(client: Client, includePillar: WorldPoint?): ArenaGraph? {
    val wv = client.topLevelWorldView ?: return null
    val maps: Array<CollisionData>? = wv.collisionMaps
    if (maps == null) return null
    val plane = wv.plane
    val flags = maps[plane].flags

    val player = Rs2Player.getWorldLocation() ?: return null
    if (player.plane != plane) return null

    // Snapshot all obstacles in scene.
    val rawObstacles = Rs2TileObjectQueryable()
        .withIds(*OBSTACLE_IDS)
        .toList()
        .filter { it.worldLocation.plane == plane }

    // Component build: BFS from the player's tile, then from every obstacle-adjacent
    // walkable tile we can find. Each new BFS produces a fresh component id only if
    // the seed wasn't visited by an earlier BFS.
    val visited = HashSet<WorldPoint>()
    val componentByTile = HashMap<WorldPoint, Int>()
    val componentTiles = HashMap<Int, Set<WorldPoint>>()
    var nextComp = 0

    fun seed(start: WorldPoint) {
        if (visited.contains(start)) return
        val sx = start.x - wv.baseX
        val sy = start.y - wv.baseY
        if (sx !in 0 until flags.size || sy !in 0 until flags.size) return
        if ((flags[sx][sy] and CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0) return
        val comp = reachableComponent(wv, flags, plane, start, visited, cap = 6000)
        if (comp.isEmpty()) return
        val id = nextComp++
        componentTiles[id] = comp
        for (t in comp) componentByTile[t] = id
    }

    seed(player)
    for (obs in rawObstacles) {
        val w = obs.worldLocation
        for (dx in -OBSTACLE_SCAN_RADIUS..OBSTACLE_SCAN_RADIUS) {
            for (dy in -OBSTACLE_SCAN_RADIUS..OBSTACLE_SCAN_RADIUS) {
                if (dx == 0 && dy == 0) continue
                val candidate = WorldPoint(w.x + dx, w.y + dy, plane)
                seed(candidate)
            }
        }
    }
    if (includePillar != null) {
        seed(includePillar)
        // Pillars are 3x3 GameObjects whose stored worldLocation is the SW corner.
        // Seed a 5x5 footprint around the arrow point so we always pick up adjacent
        // walkable tiles regardless of which corner the arrow lands on.
        for (dx in -3..3) for (dy in -3..3) {
            if (dx == 0 && dy == 0) continue
            seed(WorldPoint(includePillar.x + dx, includePillar.y + dy, plane))
        }
    }

    // Resolve obstacle endpoints into edges.
    val edges = ArrayList<ObstacleEdge>()
    for (obs in rawObstacles) {
        val cost = OBSTACLE_COST_SECONDS[obs.id] ?: continue
        val pair = resolveEndpoints(obs, componentByTile) ?: continue
        edges.add(ObstacleEdge(obs, obs.id, pair.first, pair.second, cost))
    }

    return ArenaGraph(edges, componentByTile, componentTiles)
}

// ==========================================================================================
// Dijkstra over the {nodes = {start, target, obstacle-endpoints}} graph.
// ==========================================================================================

private sealed class Step {
    data class Walk(val to: WorldPoint) : Step()
    data class Obstacle(val edge: ObstacleEdge) : Step()
}

private fun planPath(graph: ArenaGraph, start: WorldPoint, target: WorldPoint): List<Step>? {
    // Gather node set: start, target, and every obstacle endpoint.
    val nodes = LinkedHashSet<WorldPoint>()
    nodes.add(start); nodes.add(target)
    for (e in graph.obstacles) { nodes.add(e.from); nodes.add(e.to) }

    // Adjacency: walk edges within the same component + obstacle edges. Walk cost
    // computed lazily on demand to avoid quadratic BFS up front.
    val nodeList = nodes.toList()
    val nodeIndex = HashMap<WorldPoint, Int>(nodeList.size).apply {
        nodeList.forEachIndexed { i, w -> this[w] = i }
    }
    val startIdx = nodeIndex[start] ?: return null
    val targetIdx = nodeIndex[target] ?: return null

    // Edges per node: list of (toIdx, costSec, step).
    val adj = Array(nodeList.size) { mutableListOf<Triple<Int, Double, Step>>() }
    // Obstacle edges (bidirectional — every arena obstacle has a return variant on the wiki).
    for (e in graph.obstacles) {
        val a = nodeIndex[e.from] ?: continue
        val b = nodeIndex[e.to] ?: continue
        adj[a].add(Triple(b, e.costSeconds, Step.Obstacle(e)))
        adj[b].add(Triple(a, e.costSeconds, Step.Obstacle(e)))
    }
    // Walk edges between every pair of nodes in the same component.
    for (i in nodeList.indices) for (j in nodeList.indices) {
        if (i == j) continue
        val a = nodeList[i]; val b = nodeList[j]
        val d = graph.walkDistance(a, b) ?: continue
        if (d == 0) continue
        adj[i].add(Triple(j, d / RUN_TPS, Step.Walk(b)))
    }

    // Standard Dijkstra.
    val dist = DoubleArray(nodeList.size) { Double.POSITIVE_INFINITY }
    val prev = arrayOfNulls<Pair<Int, Step>>(nodeList.size)
    dist[startIdx] = 0.0
    val pq = PriorityQueue<IntArray>(compareBy { dist[it[0]] })
    pq.add(intArrayOf(startIdx))

    while (pq.isNotEmpty()) {
        val cur = pq.poll()[0]
        if (cur == targetIdx) break
        val curDist = dist[cur]
        for ((next, w, step) in adj[cur]) {
            val nd = curDist + w
            if (nd < dist[next]) {
                dist[next] = nd
                prev[next] = cur to step
                pq.add(intArrayOf(next))
            }
        }
    }

    if (dist[targetIdx] == Double.POSITIVE_INFINITY) return null

    // Walk back through prev.
    val steps = ArrayList<Step>()
    var cur = targetIdx
    while (cur != startIdx) {
        val (p, s) = prev[cur] ?: return null
        steps.add(s)
        cur = p
    }
    steps.reverse()
    return steps
}

// ==========================================================================================
// States
// ==========================================================================================

private class Root(private val owner: BrimhavenAgilityScript) : State() {
    @Volatile
    private var logged = false

    override fun checkNext(client: Client): State? {
        if (activePillarPoint(client) != null) return GoToPillar(owner)
        if (!atCenter()) return ReturnToCenter(owner)
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[BrimhavenAgility] state: Root"); logged = true }
        diagLog(owner, "Root", client)
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }
        Global.sleep(Rs2Random.between(800, 1600))
    }
}

/**
 * GoToPillar: build the arena graph, plan the cheapest route to the active pillar, and
 * execute it step by step. Replans whenever the hint arrow rotates.
 */
private class GoToPillar(private val owner: BrimhavenAgilityScript) : State() {
    @Volatile
    private var logged = false
    private val startMs = System.currentTimeMillis()
    private var targetPoint: WorldPoint? = null

    override fun checkNext(client: Client): State? {
        if (owner.ticketAwarded) {
            owner.ticketAwarded = false
            return ReturnToCenter(owner)
        }
        // Hint arrow flipped off or moved — bounce through Root for a fresh dispatch.
        val current = activePillarPoint(client)
        val target = targetPoint
        if (target != null && (current == null || current != target)) {
            return Root(owner)
        }
        if (System.currentTimeMillis() - startMs > PATH_PLAN_TIMEOUT_MS) return Root(owner)
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[BrimhavenAgility] state: GoToPillar"); logged = true }
        diagLog(owner, "GoToPillar", client)
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }

        val arrow = activePillarPoint(client) ?: return
        if (targetPoint == null) targetPoint = arrow

        // If the pillar is already in scene + adjacent we can short-circuit and click it.
        // Note: Rs2TileObjectModel.click() always returns true (the underlying menu-entry
        // dispatch is fire-and-forget), so we don't gate on its return value. Verify the
        // click landed by watching for ticket-award OR hint-arrow rotation.
        val pillar = pillarAt(arrow)
        val here = Rs2Player.getWorldLocation() ?: return
        if (pillar != null && here.distanceTo(arrow) <= 3) {
            Microbot.log("[BrimhavenAgility] click pillar id=${pillar.id} @${pillar.worldLocation} (here=$here arrow=$arrow)")
            pillar.click()
            sleepUntil(checkEvery = 200, timeout = 4_000) {
                owner.ticketAwarded || activePillarPoint(client) != targetPoint
            }
            return
        }

        val graph = buildGraph(client, arrow) ?: run {
            Microbot.log("[BrimhavenAgility] buildGraph returned null (cache cold?) — retrying")
            Global.sleep(Rs2Random.between(300, 600))
            return
        }

        val playerComp = graph.componentOf(here)
        val startNode = graph.snapToWalkable(here) ?: here
        val targetNode = graph.snapToWalkableNearTarget(arrow, playerComp) ?: arrow
        val path = planPath(graph, startNode, targetNode)
        Microbot.log(
            "[BrimhavenAgility] graph: obstacles=${graph.obstacles.size} " +
                "components=${graph.distinctComponentCount()} " +
                "playerComp=$playerComp startNode=$startNode targetNode=$targetNode " +
                "pathSteps=${path?.size ?: -1}"
        )

        if (path == null || path.isEmpty()) {
            // No graph path — try a naive obstacle-aware fallback so we get *some*
            // forward progress while the planner iterates. Pick the closest in-scene
            // obstacle whose 'from' endpoint is in our component AND whose midpoint
            // is roughly in the direction of the arrow, and click it.
            val fallback = pickNaiveObstacleStep(graph, here, arrow)
            if (fallback != null) {
                Microbot.log(
                    "[BrimhavenAgility] no graph path; naive fallback obstacle=${fallback.id} " +
                        "@${fallback.obj.worldLocation} from=${fallback.from} to=${fallback.to}"
                )
                executeObstacle(client, here, fallback)
                return
            }
            // No fallback either — try walking one tile toward the arrow within our
            // component so we at least nudge ourselves into a position the planner can
            // model on the next loop.
            val nudgeTarget = graph.snapToWalkableNearTarget(arrow, playerComp)
            if (nudgeTarget != null && nudgeTarget != here) {
                val intermediate = pickLocalStep(graph, here, nudgeTarget)
                if (intermediate != null) {
                    Microbot.log("[BrimhavenAgility] nudge walk -> $intermediate")
                    Rs2Walker.walkFastCanvas(intermediate)
                    sleepUntil(checkEvery = 150, timeout = 1_500) {
                        Rs2Player.getWorldLocation() != here || activePillarPoint(client) != targetPoint
                    }
                    return
                }
            }
            Global.sleep(Rs2Random.between(400, 800))
            return
        }

        executeStep(client, graph, here, path[0])
    }

    private fun executeStep(client: Client, graph: ArenaGraph, here: WorldPoint, step: Step) {
        when (step) {
            is Step.Walk -> walkLocally(client, graph, here, step.to)
            is Step.Obstacle -> executeObstacle(client, here, step.edge)
        }
    }

    private fun executeObstacle(client: Client, here: WorldPoint, edge: ObstacleEdge) {
        Microbot.log("[BrimhavenAgility] obstacle id=${edge.id} @${edge.obj.worldLocation} from=${edge.from} to=${edge.to} cost=${edge.costSeconds}s")
        val before = Rs2Player.getWorldLocation() ?: here
        edge.obj.click() // always returns true; verify with sleepUntil below
        sleepUntil(checkEvery = 200, timeout = (edge.costSeconds * 1000 + 4_000).toInt()) {
            val now = Rs2Player.getWorldLocation() ?: return@sleepUntil false
            val landed = now.distanceTo(edge.to) <= 1 && now != before
            val rotated = activePillarPoint(client) != targetPoint
            landed || rotated
        }
    }

    private fun walkLocally(client: Client, graph: ArenaGraph, here: WorldPoint, dest: WorldPoint) {
        val intermediate = pickLocalStep(graph, here, dest) ?: return
        Microbot.log("[BrimhavenAgility] walk -> $intermediate (toward $dest)")
        Rs2Walker.walkFastCanvas(intermediate)
        val before = here
        sleepUntil(checkEvery = 150, timeout = 2_000) {
            val now = Rs2Player.getWorldLocation() ?: return@sleepUntil false
            now != before
                || activePillarPoint(client) != targetPoint
        }
    }
}

private class ReturnToCenter(private val owner: BrimhavenAgilityScript) : State() {
    @Volatile
    private var logged = false
    private val startMs = System.currentTimeMillis()

    override fun checkNext(client: Client): State? {
        if (activePillarPoint(client) != null) return GoToPillar(owner)
        if (atCenter()) return Root(owner)
        if (System.currentTimeMillis() - startMs > RETURN_TIMEOUT_MS) return Root(owner)
        return null
    }

    override fun loop(client: Client, script: StateMachineScript) {
        if (!logged) { Microbot.log("[BrimhavenAgility] state: ReturnToCenter"); logged = true }
        diagLog(owner, "ReturnToCenter", client)
        if (Microbot.pauseAllScripts.get()) {
            Global.sleep(Rs2Random.between(1200, 2600))
            return
        }
        if (atCenter()) return

        val here = Rs2Player.getWorldLocation() ?: return
        val graph = buildGraph(client, ARENA_CENTER) ?: run {
            Global.sleep(Rs2Random.between(300, 600))
            return
        }
        val intermediate = pickLocalStep(graph, here, ARENA_CENTER) ?: run {
            Global.sleep(Rs2Random.between(300, 600))
            return
        }
        if (!Rs2Walker.walkFastCanvas(intermediate)) {
            Global.sleep(Rs2Random.between(300, 600))
            return
        }
        sleepUntil(checkEvery = 150, timeout = 2_000) {
            val now = Rs2Player.getWorldLocation() ?: return@sleepUntil false
            now != here || atCenter() || activePillarPoint(client) != null
        }
    }
}

/**
 * Pick a near-by tile along the BFS path from [from] to [to] within [graph]'s component.
 * Returns a tile roughly LOCAL_STEP_LOOKAHEAD ahead — close enough to be on-canvas, far
 * enough that we don't issue per-tile clicks. Falls back to the closest reachable tile
 * if the planner endpoints are in different components (shouldn't happen — Dijkstra
 * already proved component equality — but keeps the local mover defensive).
 */
private const val LOCAL_STEP_LOOKAHEAD = 6

/**
 * Naive obstacle-aware fallback: when [planPath] returns null but we still want
 * forward progress, pick the closest in-component obstacle whose midpoint is in
 * the rough direction of the arrow. This gives the user *some* clicks while the
 * planner iterates, instead of standing still next to an unmodelled obstacle.
 *
 * "In-component" here means the obstacle's `from` endpoint shares a connected
 * component with the player's tile — so we know we can actually walk to it.
 */
private fun pickNaiveObstacleStep(
    graph: ArenaGraph,
    here: WorldPoint,
    arrow: WorldPoint,
): ObstacleEdge? {
    val playerComp = graph.componentOf(here) ?: return null
    val dx = arrow.x - here.x
    val dy = arrow.y - here.y
    if (dx == 0 && dy == 0) return null

    var best: ObstacleEdge? = null
    var bestScore = Double.MAX_VALUE
    for (edge in graph.obstacles) {
        // Pick the endpoint we could actually walk to from here.
        val fromInComp = graph.componentOf(edge.from) == playerComp
        val toInComp = graph.componentOf(edge.to) == playerComp
        val (near, far) = when {
            fromInComp && !toInComp -> edge.from to edge.to
            toInComp && !fromInComp -> edge.to to edge.from
            fromInComp && toInComp -> {
                // Same-component on both ends — skip (walking would be cheaper).
                continue
            }
            else -> continue // neither endpoint reachable
        }
        // Direction filter: vector from `here` to `far` must align with vector to arrow.
        val ex = far.x - here.x
        val ey = far.y - here.y
        val dot = ex * dx + ey * dy
        if (dot <= 0) continue
        // Score = walk-distance to `near` + euclidean distance from `far` to arrow.
        val walk = graph.walkDistance(here, near) ?: continue
        val approach = Math.hypot((arrow.x - far.x).toDouble(), (arrow.y - far.y).toDouble())
        val score = walk + approach
        if (score < bestScore) {
            bestScore = score
            // Rebuild edge with the right orientation if needed so executeObstacle's
            // `landed` check uses the correct destination.
            best = if (near == edge.from) edge else edge.copy(from = near, to = far)
        }
    }
    return best
}

private fun pickLocalStep(graph: ArenaGraph, from: WorldPoint, to: WorldPoint): WorldPoint? {
    if (from == to) return null
    val direct = graph.walkPath(from, to)
    if (direct != null) {
        if (direct.isEmpty()) return null
        return direct[minOf(LOCAL_STEP_LOOKAHEAD - 1, direct.size - 1)]
    }
    // Cross-component fallback: snap [to] onto the player's component, then path there.
    val snapped = graph.snapToWalkable(to) ?: return null
    val viaSnap = graph.walkPath(from, snapped) ?: return null
    if (viaSnap.isEmpty()) return null
    return viaSnap[minOf(LOCAL_STEP_LOOKAHEAD - 1, viaSnap.size - 1)]
}
