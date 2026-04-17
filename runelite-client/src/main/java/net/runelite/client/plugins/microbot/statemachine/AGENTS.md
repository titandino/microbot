# CLAUDE.md — State Machine Framework

## Overview

The `statemachine` package provides `StateMachineScript<S>`, an opt-in base class that extends `Script` to model automation logic as an explicit state machine. Scripts declare states (enum), transitions (guarded edges with reasons), and per-state actions. The framework evaluates transitions each tick, publishes thread-safe debug snapshots, and exposes state via the agent server at `/debug/snapshot`.

**This is additive** — existing scripts extending `Script` are unaffected. No changes were made to `Script.java`.

## Architecture

```
StateMachineScript<S extends Enum<S>> extends Script
│
├─ step()                        ← call this in your scheduleWithFixedDelay lambda
│   ├─ super.run()               ← existing Script guard (heartbeat, pause, blocking events)
│   ├─ evaluate transitions      ← first matching guard wins
│   ├─ publishSnapshot()         ← AtomicReference<StateSnapshot> for debug endpoint
│   └─ onState(currentState)     ← your action code
│
├─ REGISTRY (static)             ← ConcurrentHashMap<name, instance>
│   └─ registered on first step(), removed on shutdown()
│
└─ StateSnapshot<S> (immutable)  ← read by HTTP handler thread without locks
    ├─ currentState, previousState, lastTransitionReason
    ├─ stateEnteredAt, loopCount, transitionCount
    ├─ pendingTransitions[]      ← what could fire next + whether guard is true
    └─ recentTrace[]             ← ring buffer of last 50 transitions
```

## Key Design Decisions

### Why `step()` not `final run()`

`Script.run()` is a boolean guard method — subclasses call `super.run()` inside their scheduled lambda. Its semantics vary across scripts:
- `ExampleScript.run()` starts scheduling (returns `ScheduledFuture`)
- `ShortestPathScript` skips `super.run()` entirely
- `BreakHandlerScript` uses `super.run()` as a guard

Making `run()` final would break these contracts. `step()` is a new method that internally calls `super.run()` as the guard, then evaluates the machine. Scripts call `step()` inside their existing scheduling pattern.

### Why accept blocking `onState()`

Microbot's Rs2* utilities block the script thread:
- `Rs2Walker.walkTo()` — blocks until arrived
- `Rs2Bank.openNearest()` — blocks until bank opens
- `sleepUntil(condition, 5000)` — blocks up to 5 seconds

Requiring non-blocking states would mean rewriting all Rs2* interactions. v1 accepts blocking and mitigates via `stateEnteredAt` / `msInCurrentState` in snapshots.

### Thread-safe snapshots via `AtomicReference`

All mutable state lives on the script's executor thread. After each tick, an immutable `StateSnapshot` is published via `AtomicReference.set()`. The HTTP debug handler reads it via `AtomicReference.get()` — no locks, no synchronization, always consistent.

### Global registry for debug discovery

`StateMachineScript` maintains a static `ConcurrentHashMap<String, StateMachineScript<?>>` so the debug handler can enumerate all active state machines without dependency injection. Scripts register on first `step()` and unregister on `shutdown()`.

## Integration Points

### Agent Server

`StateMachineDebugHandler` registered in `AgentServerPlugin.startUp()` at `/debug/snapshot`.

- `GET /debug/snapshot` — list all registered state machines with current state
- `GET /debug/snapshot?script=Name` — full snapshot for one script (supports partial name match)

### Script Heartbeat

`step()` calls `super.run()` which invokes `ScriptHeartbeatRegistry.recordHeartbeat()`. The existing `/scripts/health` endpoint continues to work. The new `/debug/snapshot` endpoint provides richer state-level data.

### Queryable API

State machine scripts should use the Queryable API (see `api/QUERYABLE_API.md`) for game queries:

```java
// ✅ Preferred — Queryable API
Rs2NpcModel target = Microbot.getRs2NpcCache().query()
    .withName("Guard")
    .nearest();

// ❌ Legacy — static utility
NPC target = Rs2Npc.getNpc("Guard");
```

Available queryable caches:
- `Microbot.getRs2NpcCache().query()` — NPCs
- `Microbot.getRs2TileObjectCache().query()` — Game objects, walls, decorations
- `Microbot.getRs2TileItemCache().query()` — Ground items
- `Microbot.getRs2PlayerCache().query()` — Other players
- `Microbot.getRs2BoatCache().query()` — Boats

Inventory and local player state use static utilities (`Rs2Inventory`, `Rs2Player`) — these don't have queryable caches yet.

## Package Contents

| File | Lines | Purpose |
|------|-------|---------|
| `StateMachineScript.java` | ~230 | Base class: engine, registry, snapshot, hooks |
| `Transition.java` | ~90 | Immutable transition + fluent builder (`from/when/because/goTo`) |
| `StateSnapshot.java` | ~120 | Immutable debug snapshot with `toMap()` for JSON |
| `PendingTransition.java` | ~30 | What could fire from current state |
| `CLAUDE.md` | — | Developer guide (usage, API reference, best practices, troubleshooting) |
| `AGENTS.md` | — | This file (architecture, design decisions, integration) |

## Example Plugin

See `statemachineexample/` package for a complete working example:
- `StateMachineExamplePlugin` — plugin lifecycle (start/stop)
- `StateMachineExampleScript` — 5-state cycle using queryable API
- `StateMachineExampleConfig` — tick delay and cooldown duration

Enable "Microbot State Machine Example" in the plugin panel, then query:
```
GET /debug/snapshot?script=StateMachineExample
```

## What This Doesn't Solve

1. **Within-state complexity** — If `onState()` has 40 lines, break into sub-states. Nested state machines are deferred to v2.
2. **Retroactive migration** — Existing scripts keep working as-is. This is for new scripts.
3. **Blocking stall detection** — A state blocking on `sleepUntil()` looks "stalled" in health checks. Use `msInCurrentState` to distinguish.
4. **Interrupt-awareness** — `Global.sleep()` swallows `InterruptedException`. A future v2 should fix this for cleaner shutdown.

## Non-Negotiable Rules

1. ⚠️ **Never instantiate caches directly** — always use `Microbot.getRs2*Cache().query()`.
2. ⚠️ **Never block the client thread** — `onState()` runs on the script executor thread, which is correct. Don't move it.
3. ⚠️ **Always call `step()`** inside the scheduled lambda — don't call `onState()` or evaluate transitions manually.
4. ⚠️ **Always provide `because()` strings** — they're the debug output. Without them, the trace log is useless.
5. ⚠️ **Always null-check in transition guards** — the framework catches exceptions but the transition won't fire.
6. ⚠️ **Guards must be pure** — no side effects (logging, counters, state mutation). Guards may be re-evaluated for debug snapshots.

## Future Work (v2)

- Interrupt-aware `sleep()` / `sleepUntil()` for clean shutdown during blocking states
- Nested state machines (composable sub-machines for complex states)
- Per-state timeout metadata (`maxTimeInState` with automatic error transition)
- State machine validation (unreachable states, dead ends, cycles) as a static check
- Overlay integration (render current state on the game screen)
