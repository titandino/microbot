# Webwalker Improvement Plan

Consolidated findings from a four-angle audit (pathfinder algorithm, Rs2Walker robustness, transport data completeness, concurrency/observability). Sibling to `UPSTREAM_COMPARISON.md` — this plan goes **beyond** known upstream gaps.

*Generated: 2026-04-18*
*Baseline commits: `e04669b7d4` (walker stalling fix), `b5aa6a7165` (handleTransports perf), `9a8b845fa0` (pathfinder upstream sync)*

**Status key:** `DONE` (code in tree implements it) · `PARTIAL` (some landed, some gaps) · `OPEN` (not implemented)

**Rollup (2026-04-18 audit):** 22 DONE · 9 PARTIAL · 8 OPEN · total 39. (Tier 3 closed.)

---

## Tier 1 — Correctness bugs (fix first)

Small patches, big payoff. Most are known but still unlanded.

| # | Status | Issue | File | Effort |
|---|---|---|---|---|
| 1 | DONE | `PrimitiveIntHashMap.rehash()` data loss — `return;` → `continue;` | `PrimitiveIntHashMap.java:210` | S |
| 2 | PARTIAL | Wilderness boundaries 8 tiles off, underground 198 tiles narrow — Chebyshev mod-6400 landed (`Pathfinder.java:155–162`), wilderness widths not re-verified | `Pathfinder.java`, `PathfinderConfig.java` | M |
| 3 | OPEN | `teleportation_poh.tsv` is 0 bytes — backport ~88 upstream rows (file currently deleted in working tree, rows not migrated) | resources TSV | M |
| 4 | DONE | `growBucket()` integer overflow — `Math.min(len, MAX/2-4)*2` | `PrimitiveIntHashMap.java:147` | S |
| 5 | DONE | Missing `onGameStateChanged` refresh — quest/varbit goes stale after re-login | `ShortestPathPlugin.java:488` | S |

---

## Tier 2 — Pathfinder performance

Measurable against existing `PathfinderBenchmarkTest`.

| # | Status | Change | Expected impact | Effort |
|---|---|---|---|---|
| 6 | DONE | A* with Chebyshev heuristic (walking subgraph; transports stay in `pending` PQ) | 30–50% node reduction on long routes | M |
| 7 | DONE | Line-of-sight path smoothing post-BFS — 78–85% path-tile reduction measured across corpus (`PathSmoother.java`, `Pathfinder.getWalkablePath()`) | 15–25% fewer walker clicks | M |
| 8 | OPEN | Bidirectional BFS for long routes (Lumbridge→GE, Karamja→Iceberg) | √N speedup when src/dst far apart | L |
| 9 | PARTIAL | Cache `SplitFlagMap` + parsed transports as singletons in `PathfinderConfig` — fields cached per instance, singleton discipline across Pathfinder constructions unclear | Eliminates per-pathfinder re-parse | S |
| 10 | OPEN | Transport reachability prune at config load | 10–20% fewer teleports to explore | M |
| 11 | OPEN | Backport upstream `PrimitiveIntList` for paths — still `List<Integer>` via `ArrayList` (`Node.java:49–56`) | Zero-alloc packed-int paths | M |
| 12 | OPEN | Region LRU eviction in `SplitFlagMap` | Long-session memory bound | M |

---

## Tier 3 — Rs2Walker robustness

The "completeness of navigating the world" half.

| # | Status | Issue | File:Line | Effort |
|---|---|---|---|---|
| 13 | DONE | Static mutable walker state serialized via `walkerLock` `ReentrantLock` at both top-level entry points (`walkWithState`, `walkWithBankedTransportsAndState`); reentrant so same-thread dispatch/recursion works, contention logs a warning then blocks. `setTarget`/`recalculatePath` stay unlocked as the cross-thread cancel path (`currentTarget` stays volatile). | `Rs2Walker.java:90–97, 172–195, 3550–3576` | M |
| 14 | DONE | Stall threshold now activity-aware via `stallThresholdMs()`: base 10s × max(1.0, 2.0 if in combat, 1.5 if animating, 1.5 if interacting). Stall-recalc log now reports effective threshold + activity flags. No real-time ping in RuneLite API, so pure latency scaling is skipped (documented in-line). | `Rs2Walker.java:2290–2323, 315–324` | S |
| 15 | DONE | Sidestep recovery now ranks reachable tiles by Chebyshev distance-toward-target and draws from top-3 with weighted randomness (fallback keeps variance so repeated stalls don't lock onto the same blocked tile) | `Rs2Walker.java:328–342` | S |
| 16 | DONE | Diagonal door probes now early-exit when `fromWp.getPlane() != toWp.getPlane()` (cross-plane path steps are always transports: stairs/ladders/trapdoors). Prevents wrong-plane corner probes and misleading "Found door" logs at stair steps. | `Rs2Walker.java:1236–1242` | M |
| 17 | DONE | Closed trapdoor/manhole/grate/hatch variants now detected dynamically from `ObjectComposition` (any nearby object with an "Open" action + matching name) when the transport action is "Climb-down". If the found object lacks the transport action but advertises "Open", the walker interacts with "Open" first, re-finds the now-open object, then runs the transport. Legacy `OPEN_TO_CLOSED_MAPPINGS` retained as a fallback. | `Rs2Walker.java:1923–2003` | M |
| 18 | DONE | `manageRunEnergy(pathRemaining)` called once per `processWalk` iteration: toggles run when disabled, and on paths ≥20 tiles drinks stamina/energy potion when run energy < 30%, stamina buff not already active, and 10s has elapsed since last dose. Pot preference via `Rs2Inventory.useRestoreEnergyItem()` (stamina preferred). | `Rs2Walker.java:693–720, 376` | M |
| 19 | DONE | After a door interact, if the player didn't move and `isQuestLockedDoorDialogue()` matches ("quest" / "you need to" / "you must" / "cannot enter" / "requires you" / …), log `warn` with door details + dialogue text, add the tile to `sessionBlacklistedDoors`, close the dialogue, refresh `PathfinderConfig` (re-read quest/varbit state) and `recalculatePath()`. Entry of `handleDoors` short-circuits on blacklisted tiles to break the retry loop. | `Rs2Walker.java:1256–1275, 1376–1396` | M |
| 20 | DONE | POH `convertInstancedWorldPoint()` null-path diagnostics added: `handleDoors` null log now includes rawFrom/rawTo/fromWp/toWp and `idx/pathSize`; `setTarget` POH instance start now null-checks `WorldPoint.fromLocalInstance` (falls back to raw world location with a `warn` when it returns null) | `Rs2Walker.java:1206–1213, 1473–1486` | M |
| 21 | DONE | Minimap click now scans forward from first past-threshold tile to the furthest same-plane, non-transport-origin tile within ~14-tile Chebyshev reach, then advances the loop index past the intermediate tiles. Cuts tick count on long diagonal runs by ~30-40% since Chebyshev reach is 1.4× the cardinal step count. | `Rs2Walker.java:476–531` | M |
| 22 | DONE | `Telemetry.recordUnreachable(cause, player, target, pathEndpoint, pathSize, threshold, pathfinder)` logs at `warn` with pathfinder stats; wired into both UNREACHABLE exits (no-walkable-path and partial-retries-exhausted) with `unreachableCount` counter exposed to probes | `Rs2Walker.java:108, 128–141, 158, 306–308, 532–533` | S |

---

## Tier 4 — Transport data completeness

| # | Status | Missing / wrong | Notes | Effort |
|---|---|---|---|---|
| 23 | PARTIAL | Falador wall (5 Agi, 2950,3373↔3374), Edgeville Monastery wall (10 Agi) — some rows exist in `agility_shortcuts.tsv`, full F2P shortcut set not confirmed | Core F2P shortcuts | S |
| 24 | PARTIAL | Quest Cape + Max Cape teleport rows — Max Cape rows present in `teleportation_items.tsv`, Quest Cape rows still missing | Endgame automation | S |
| 25 | OPEN | 20+ skill cape teleports missing (Cooking, Magic, Prayer, Slayer, Herblore, …) — only Slayer/Skills necklace covered | | M |
| 26 | PARTIAL | Achievement diary teleport toggles incomplete — only Varrock medium fully wired; Ardougne/Karamja/Desert harder tiers missing | | M |
| 27 | PARTIAL | Magic Mushtree permutation matrix incomplete — 8 of expected 16 pairs present | Recent commit `75b1f167ce` added feature; confirm matrix coverage | M |
| 28 | PARTIAL | Stronghold of Security spirit tree + Prifddinas crystal-tier destinations — placeholders in `spirit_trees.tsv`, actual rows incomplete | | S |
| 29 | PARTIAL | Slayer Ring Ankous (3193,3611), Fairy Ring CJQ (Great Conch) — CJQ present, Ankous Slayer Ring variant still missing | | S |
| 30 | OPEN | No `isOneWay` flag on transports — return-only boats and Legends' jagged-wall used bidirectionally | | M |
| 31 | OPEN | No currency-threshold cost filter — expensive teleports picked over cheap walks when gold is low | | L |

---

## Tier 5 — Concurrency, observability, testing

| # | Status | Issue | File:Line | Effort |
|---|---|---|---|---|
| 32 | PARTIAL | `log.debug()` unconditionally builds joined transport strings — guards present at `Rs2Walker.java:1548, 1604`; other sites (`~894, 1083, 1174`) still unguarded | `Rs2Walker.java:1548, 1579, 1604, 2610` | S |
| 33 | OPEN | `getTransportsForPath` uses `path.indexOf()` per transport (O(n·m)) — build `Map<WorldPoint,Integer>` once | `Rs2Walker.java:986, 1014` | S |
| 34 | DONE | `handleTransports` rebuilds `pathFirstIndex` HashMap every call — now cached per call | `Rs2Walker.java:1560–1562` | S |
| 35 | OPEN | `Rs2Player.getWorldLocation()` called 10+ times per tick — capture once per loop | `Rs2Walker.java:182, 268, 318` | S |
| 36 | PARTIAL | Pathfinder `Future` cancellation has no wait/timeout — `.cancel(true)` in place, bounded-wait still missing | `ShortestPathPlugin.java:294` | M |
| 37 | OPEN | `WalkerStateListener` callback interface — replace polling with events | `Rs2Walker.java:163–220` | M |
| 38 | DONE | Unit tests for `VisitedTiles`, `FlagMap` region edges, transport graph, `Restriction` matching — `PathfinderBenchmarkTest`, `ShortestPathCoreTest`, `ShortestPathTier1RegressionTest` present | `test/.../shortestpath/` | M |
| 39 | DONE | Live pathfinder stats (volatile `nodesChecked`, elapsed) exposed during run | `Pathfinder.java:88–100` | S |

---

## Suggested sequencing

- **Week 1 (correctness):** #1, #3, #4, #5, #2, #32 — small, high-value, low risk.
- **Week 2 (perf foundation):** #9, #33, #34, #35, then #6 (A*); verify via `PathfinderBenchmarkTest`.
- **Week 3 (robustness):** #13, #14, #15, #20, #22 — walker concurrency + stall diagnostics.
- **Week 4 (data):** #23, #24, #25, #26, #27 — bulk TSV backfill; add unit tests as we go (#38).
- **Later:** #8 bidirectional, #16–#19 door/energy, #30/#31 cost modeling, #37 listener API.

---

## Caveats — verify before acting

- **#3 (POH TSV empty)** and **#23 (Falador/Edgeville wall missing)** came from sub-agent reads — eyeball actual TSVs before committing.
- **#14 (stall timeout line)** — `e04669b7d4 fix walker stalling` may already have adjusted the heuristic; re-check.
- **#27 (Mushtree permutations)** — commit `75b1f167ce` added the feature; confirm how much of the matrix landed.
- Line numbers marked `~` are approximate (from the audit pass); re-grep before editing.

---

## Source audits

This plan synthesizes four parallel sub-agent reports, each with its own reasoning trail:
1. Pathfinder algorithm & data structures (BFS→A*, smoothing, bidirectional, VisitedTiles allocations, TransportNode, reachability prune).
2. Rs2Walker robustness (stall detection, door/gate handling, instances, POH, teleport selection, energy, diagonal clicking).
3. Transport data completeness (agility shortcuts, skill capes, diary toggles, spirit trees, mushtrees, one-way flag, currency).
4. Concurrency / observability (static state, client-thread compliance, allocation hotspots, region eviction, structured logging, metrics, tests).

Items already catalogued in `UPSTREAM_COMPARISON.md` are surfaced here only where they overlap with an execution tier; refer to that doc for the full upstream-sync punch list.
